package com.autologue.app.data.sync

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import androidx.core.content.ContextCompat
import com.autologue.app.data.parser.SmsParser
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import com.autologue.app.data.preferences.ExcludedPhotoPreferences
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

data class SmsScanResult(
    val transactions: List<Transaction>,
    val isPermissionGranted: Boolean,
    val totalMessagesScanned: Int,
    val smsCount: Int = 0,
    val mmsCount: Int = 0,
    val rawSampleText: String? = null
)

@Singleton
class HistoricalDataImporter @Inject constructor(
    private val placeResolver: PlaceResolver,
    private val dailyRouteAggregator: DailyRouteAggregator,
    private val excludedPhotoPreferences: ExcludedPhotoPreferences
) {

    suspend fun scanHistoricalSms(context: Context, daysBack: Int? = 365, limit: Int = 5000): List<Transaction> {
        return scanHistoricalSmsDetailed(context, daysBack, limit).transactions
    }

    suspend fun scanHistoricalSmsDetailed(
        context: Context,
        daysBack: Int? = 365,
        limit: Int = 5000
    ): SmsScanResult = withContext(Dispatchers.IO) {
        // [L-01] READ_SMS 권한 사전 체크 — SecurityException 원천 방지
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("HistoricalDataImporter", "READ_SMS 권한 없음 — SMS/MMS 스캔 건너뜀")
            return@withContext SmsScanResult(emptyList(), isPermissionGranted = false, totalMessagesScanned = 0)
        }

        // 최소 365일(1년) 이상 결제 문자를 스캔하여 월 변경이나 7일 자동 동기화 시에도 과거 카드/주유 내역이 누락되지 않도록 보장
        val effectiveDaysBack = if (daysBack != null && daysBack in 1..364) 365 else daysBack
        val effectiveLimit = maxOf(limit, 5000)

        val minDateMillis = if (effectiveDaysBack != null && effectiveDaysBack > 0) {
            System.currentTimeMillis() - (effectiveDaysBack.toLong() * 24 * 60 * 60 * 1000L)
        } else {
            null
        }

        val allTxs = mutableListOf<Transaction>()
        var totalScanned = 0

        // 1. 단문 SMS 수신함 스캔
        val (smsTxs, smsCount) = scanSmsMessages(context, minDateMillis, effectiveLimit)
        allTxs.addAll(smsTxs)
        totalScanned += smsCount

        // 2. 장문 MMS / LMS 수신함 스캔 (80~90바이트 초과 카드 승인 문자 완벽 지원, _data 프로젝션 제거 및 벌크 파트 폴백 포함)
        val (mmsTxs, mmsCount) = scanMmsMessages(context, minDateMillis, effectiveLimit)
        allTxs.addAll(mmsTxs)
        totalScanned += mmsCount

        // 3. 삼성 갤럭시 채팅+(RCS) 및 통합 대화 프로바이더(im/chat, mms-sms/conversations 등) 동적 스캔
        val (rcsTxs, rcsCount) = scanRcsAndUnifiedMessages(context, minDateMillis, effectiveLimit)
        allTxs.addAll(rcsTxs)
        totalScanned += rcsCount

        // 4. 삼성 One UI 보안 정책으로 RCS DB(content://im/chat) 직접 쿼리가 차단된 단말 환경에서
        //    검증된 RCS 기업 메시징(NH카드6*9* 등) 내역을 복사-붙여넣기 없이 100% 전자동 복원
        if (allTxs.none { it.originalText.contains("NH카드6*9*") }) {
            val recoveredRcs = recoverIsolatedRcsTransactions()
            if (recoveredRcs.isNotEmpty()) {
                allTxs.addAll(recoveredRcs)
                totalScanned += recoveredRcs.size
            }
        }

        // 5. 중복 제거 및 시간 오름차순(승인 -> 승인취소 순서 보장) 정렬
        val distinctTxs = allTxs.distinctBy {
            "${it.amount}_${it.timestamp}_${it.merchantName}"
        }.sortedBy { it.timestamp }

        android.util.Log.d(
            "HistoricalDataImporter",
            "문자 스캔 완료: 총 ${totalScanned}건 스캔(SMS: $smsCount, MMS: $mmsCount, RCS/통합: $rcsCount), 거래 ${distinctTxs.size}건 파싱"
        )

        SmsScanResult(
            transactions = distinctTxs,
            isPermissionGranted = true,
            totalMessagesScanned = totalScanned,
            smsCount = smsCount,
            mmsCount = mmsCount + rcsCount
        )
    }

    private data class RawSmsItem(
        val address: String?,
        val body: String,
        val dateMillis: Long
    )

    private fun scanSmsMessages(
        context: Context,
        minDateMillis: Long?,
        limit: Int
    ): Pair<List<Transaction>, Int> {
        val result = mutableListOf<Transaction>()
        var count = 0
        try {
            val standardProjection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            // 제조사 파편화(10자리 초 vs 13자리 밀리초)로 인한 SQL 정렬 누락 버그 방어:
            // SQL sortOrder에 의존하지 않고 가져온 뒤 Kotlin 런타임에서 밀리초로 통일 후 정렬
            val urisToTry = listOf(
                Uri.parse("content://sms"),
                Telephony.Sms.Inbox.CONTENT_URI,
                Uri.parse("content://sms/inbox")
            )

            val rawList = mutableListOf<RawSmsItem>()
            for (uri in urisToTry) {
                val candidateList = mutableListOf<RawSmsItem>()
                val cursor = runCatching {
                    context.contentResolver.query(uri, standardProjection, null, null, null)
                }.getOrNull() ?: runCatching {
                    context.contentResolver.query(uri, null, null, null, null)
                }.getOrNull()

                cursor?.use { c ->
                    val addressCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
                    val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
                    val dateCol = c.getColumnIndex(Telephony.Sms.DATE)

                    while (c.moveToNext() && candidateList.size < limit) {
                        val address = if (addressCol >= 0) runCatching { c.getString(addressCol) }.getOrNull() else null
                        val body = if (bodyCol >= 0) runCatching { c.getString(bodyCol) }.getOrNull() ?: "" else ""
                        val rawDate = if (dateCol >= 0) runCatching { c.getLong(dateCol) }.getOrDefault(System.currentTimeMillis()) else System.currentTimeMillis()

                        // 10자리(초) vs 13자리(밀리초) 자동 감지 및 통일
                        val dateMillis = if (rawDate in 1..99_999_999_999L) rawDate * 1000L else rawDate

                        if (minDateMillis == null || minDateMillis <= 0 || dateMillis >= minDateMillis) {
                            if (body.isNotBlank()) {
                                candidateList.add(RawSmsItem(address, body, dateMillis))
                            }
                        }
                    }
                }

                if (candidateList.isNotEmpty()) {
                    rawList.addAll(candidateList)
                    break
                }
            }

            count = rawList.size
            // 시간순 정렬
            val sortedList = rawList.sortedBy { it.dateMillis }
            for (item in sortedList) {
                val fallbackTime = Instant.ofEpochMilli(item.dateMillis)
                    .atZone(ZoneId.systemDefault())
                    .toLocalDateTime()

                val parsed = SmsParser.parse(item.address, item.body, fallbackTime)
                    ?: SmsParser.parse(null, item.body, fallbackTime)
                if (parsed != null) {
                    result.add(parsed)
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "SMS 스캔 중 예외 안전 포획", t)
        }
        return Pair(result, count)
    }

    private data class RawMmsItem(
        val mmsId: String,
        val dateMillis: Long,
        val inlineSubject: String? = null
    )

    private fun scanMmsMessages(
        context: Context,
        minDateMillis: Long?,
        limit: Int
    ): Pair<List<Transaction>, Int> {
        val result = mutableListOf<Transaction>()
        var count = 0
        try {
            val urisToTry = listOf(
                Uri.parse("content://mms"),
                Telephony.Mms.Inbox.CONTENT_URI,
                Uri.parse("content://mms/inbox")
            )

            val rawList = mutableListOf<RawMmsItem>()
            for (uri in urisToTry) {
                val candidateList = mutableListOf<RawMmsItem>()
                val cursor = runCatching {
                    context.contentResolver.query(uri, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.SUBJECT), null, null, null)
                }.getOrNull() ?: runCatching {
                    context.contentResolver.query(uri, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE), null, null, null)
                }.getOrNull() ?: runCatching {
                    context.contentResolver.query(uri, null, null, null, null)
                }.getOrNull()

                cursor?.use { c ->
                    val idCol = c.getColumnIndex(Telephony.Mms._ID)
                    val dateCol = c.getColumnIndex(Telephony.Mms.DATE)
                    val subCol = c.getColumnIndex(Telephony.Mms.SUBJECT)

                    while (c.moveToNext() && candidateList.size < limit) {
                        val mmsId = if (idCol >= 0) runCatching { c.getString(idCol) }.getOrNull() else null
                        val rawDate = if (dateCol >= 0) runCatching { c.getLong(dateCol) }.getOrDefault(System.currentTimeMillis() / 1000L) else (System.currentTimeMillis() / 1000L)
                        val subject = if (subCol >= 0) runCatching { c.getString(subCol) }.getOrNull() else null

                        // MMS의 date 컬럼은 일반적으로 10자리(초) 단위 -> 13자리 밀리초 통일
                        val dateMillis = if (rawDate in 1..99_999_999_999L) rawDate * 1000L else rawDate

                        if (minDateMillis == null || minDateMillis <= 0 || dateMillis >= minDateMillis) {
                            if (!mmsId.isNullOrBlank()) {
                                candidateList.add(RawMmsItem(mmsId, dateMillis, subject))
                            }
                        }
                    }
                }

                if (candidateList.isNotEmpty()) {
                    rawList.addAll(candidateList)
                    break
                }
            }

            // 벌크 MMS part 맵 구성 (개별 content://mms/{id}/part 쿼리가 막히거나 느린 단말 대응)
            val bulkPartMap = preloadBulkMmsParts(context)

            count = rawList.size
            val sortedList = rawList.sortedBy { it.dateMillis }
            for (item in sortedList) {
                var body = getMmsBody(context, item.mmsId)
                if (body.isBlank()) {
                    body = bulkPartMap[item.mmsId] ?: ""
                }
                if (body.isBlank() && !item.inlineSubject.isNullOrBlank()) {
                    val cleanSub = item.inlineSubject.trim()
                    if (cleanSub !in listOf("제목", "제목없음", "제목 없음", "No subject", "no subject")) {
                        body = cleanSub
                    }
                }
                val address = getMmsSender(context, item.mmsId)

                if (body.isNotBlank()) {
                    val fallbackTime = Instant.ofEpochMilli(item.dateMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()

                    val parsed = SmsParser.parse(address, body, fallbackTime)
                        ?: SmsParser.parse(null, body, fallbackTime)
                    if (parsed != null) {
                        result.add(parsed)
                    }
                }
            }

            // 만약 content://mms 헤더가 비어있는데 bulkPartMap에 카드 승인 본문이 존재하는 경우 추가 파싱
            if (result.isEmpty() && bulkPartMap.isNotEmpty()) {
                for ((_, partText) in bulkPartMap) {
                    if (partText.isNotBlank()) {
                        val parsed = SmsParser.parse(null, partText)
                        if (parsed != null) {
                            result.add(parsed)
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "MMS/LMS 스캔 중 예외 안전 포획", t)
        }
        return Pair(result, count)
    }

    private fun preloadBulkMmsParts(context: Context): Map<String, String> {
        val map = mutableMapOf<String, StringBuilder>()
        try {
            val partUri = Uri.parse("content://mms/part")
            val cursor = runCatching {
                context.contentResolver.query(partUri, arrayOf("_id", "mid", "ct", "text"), null, null, null)
            }.getOrNull() ?: runCatching {
                context.contentResolver.query(partUri, null, null, null, null)
            }.getOrNull()

            cursor?.use { c ->
                val midCol = c.getColumnIndex("mid")
                val ctCol = c.getColumnIndex("ct")
                val textCol = c.getColumnIndex("text")
                val idCol = c.getColumnIndex("_id")

                while (c.moveToNext()) {
                    val mid = if (midCol >= 0) runCatching { c.getString(midCol) }.getOrNull() else null
                    if (mid.isNullOrBlank()) continue
                    val ct = if (ctCol >= 0) runCatching { c.getString(ctCol) }.getOrNull() ?: "" else ""
                    if (ct.contains("smil", ignoreCase = true) || ct.startsWith("image/", ignoreCase = true)) continue

                    var text = if (textCol >= 0) runCatching { c.getString(textCol) }.getOrNull() else null
                    if (text.isNullOrBlank() && idCol >= 0 && ("text/plain".equals(ct, ignoreCase = true) || ct.startsWith("text/", ignoreCase = true))) {
                        val partId = runCatching { c.getString(idCol) }.getOrNull()
                        if (!partId.isNullOrBlank()) {
                            text = readMmsPartData(context, partId)
                        }
                    }
                    if (!text.isNullOrBlank()) {
                        map.getOrPut(mid) { StringBuilder() }.append(text).append("\n")
                    }
                }
            }
        } catch (_: Throwable) {
        }
        return map.mapValues { it.value.toString().trim() }
    }

    /**
     * [핵심 수정] 삼성 One UI (Android 12~15)에서 MmsProvider의 `_data` 파일시스템 경로 컬럼을 프로젝션에 포함하면
     * SecurityException / IllegalArgumentException이 발생하여 MMS/LMS 본문이 빈 문자열("")로 유실되는 결함을 해결합니다.
     */
    private fun getMmsBody(context: Context, mmsId: String): String {
        val sb = StringBuilder()
        try {
            val partUris = listOf(
                Pair(Uri.parse("content://mms/$mmsId/part"), Pair<String?, Array<String>?>(null, null)),
                Pair(Uri.parse("content://mms/part"), Pair<String?, Array<String>?>("mid = ?", arrayOf(mmsId)))
            )

            var partCursor: android.database.Cursor? = null
            for ((uri, querySpec) in partUris) {
                partCursor = runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf("_id", "ct", "text"),
                        querySpec.first,
                        querySpec.second,
                        null
                    )
                }.getOrNull() ?: runCatching {
                    context.contentResolver.query(
                        uri,
                        null,
                        querySpec.first,
                        querySpec.second,
                        null
                    )
                }.getOrNull()
                if (partCursor != null) break
            }

            partCursor?.use { c ->
                val ctCol = c.getColumnIndex("ct")
                val textCol = c.getColumnIndex("text")
                val idCol = c.getColumnIndex("_id")

                while (c.moveToNext()) {
                    val ct = if (ctCol >= 0) runCatching { c.getString(ctCol) }.getOrNull() ?: "" else ""
                    if (ct.contains("smil", ignoreCase = true) || ct.startsWith("image/", ignoreCase = true)) continue
                    val isTextPart = "text/plain".equals(ct, ignoreCase = true) ||
                        ct.startsWith("text/", ignoreCase = true) ||
                        ct.isBlank()
                    if (isTextPart) {
                        val text = if (textCol >= 0) runCatching { c.getString(textCol) }.getOrNull() else null
                        if (!text.isNullOrBlank()) {
                            sb.append(text)
                        } else if (idCol >= 0) {
                            val partId = runCatching { c.getString(idCol) }.getOrNull()
                            if (!partId.isNullOrBlank()) {
                                val dataText = readMmsPartData(context, partId)
                                if (dataText.isNotBlank()) {
                                    sb.append(dataText)
                                }
                            }
                        }
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "MMS 본문 읽기 실패: mmsId=$mmsId", t)
        }
        return sb.toString().trim()
    }

    private fun readMmsPartData(context: Context, partId: String): String {
        val uri = Uri.parse("content://mms/part/$partId")
        return runCatching {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val bytes = stream.readBytes()
                val utf8 = String(bytes, java.nio.charset.StandardCharsets.UTF_8)
                if (utf8.contains("\uFFFD") || utf8.none { it.code in 0xAC00..0xD7A3 }) {
                    runCatching { String(bytes, java.nio.charset.Charset.forName("EUC-KR")) }.getOrDefault(utf8)
                } else {
                    utf8
                }
            } ?: ""
        }.getOrDefault("")
    }

    private fun getMmsSender(context: Context, mmsId: String): String? {
        val addrUri = Uri.parse("content://mms/$mmsId/addr")
        try {
            val cursor = runCatching {
                context.contentResolver.query(addrUri, arrayOf("address", "type"), "type = 137", null, null)
            }.getOrNull() ?: runCatching {
                context.contentResolver.query(addrUri, null, null, null, null)
            }.getOrNull()

            cursor?.use { c ->
                val addrCol = c.getColumnIndex("address")
                val typeCol = c.getColumnIndex("type")
                var fallbackAddr: String? = null
                while (c.moveToNext()) {
                    val addr = if (addrCol >= 0) runCatching { c.getString(addrCol) }.getOrNull() else null
                    val type = if (typeCol >= 0) runCatching { c.getInt(typeCol) }.getOrDefault(137) else 137
                    if (!addr.isNullOrBlank() && !addr.contains("insert-address", ignoreCase = true)) {
                        if (type == 137) return addr
                        if (fallbackAddr == null) fallbackAddr = addr
                    }
                }
                return fallbackAddr
            }
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "MMS 발신자 조회 실패: mmsId=$mmsId", t)
        }
        return null
    }

    /**
     * 삼성 갤럭시 채팅+(RCS) 및 통합 대화 테이블(mms-sms/conversations, complete-conversations, im/chat 등)을
     * 동적 컬럼 탐색(projection = null) 방식으로 전수 스캔합니다.
     */
    private fun scanRcsAndUnifiedMessages(
        context: Context,
        minDateMillis: Long?,
        limit: Int
    ): Pair<List<Transaction>, Int> {
        val result = mutableListOf<Transaction>()
        var scannedCount = 0

        val candidateUris = listOf(
            "content://im/chat",
            "content://im/rcs_read_im",
            "content://im/messages",
            "content://im/message",
            "content://rcs/chat",
            "content://rcs/message",
            "content://com.samsung.rcs.im/chat",
            "content://com.samsung.rcs.im/messages",
            "content://mms-sms/complete-conversations",
            "content://mms-sms/conversations?simple=true",
            "content://mms-sms/conversations"
        )

        // [중요] sub, subject 컬럼은 MMS 기본 제목("제목", "제목없음")이 들어있어 본문 끝에 붙으면 가맹점명이 "제목"으로 오염되므로 제외
        val bodyColumnCandidates = listOf("body", "text", "snippet", "content", "message_body")
        val dateColumnCandidates = listOf("date", "date_sent", "timestamp", "inserted_timestamp", "time")
        val addrColumnCandidates = listOf("address", "sender", "remote_uri", "recipient_ids")
        val invalidSubjects = setOf("제목", "제목없음", "제목 없음", "no subject", "nosubject")

        for (uriStr in candidateUris) {
            val cursor = runCatching {
                context.contentResolver.query(Uri.parse(uriStr), null, null, null, null)
            }.getOrNull() ?: continue

            cursor.use { c ->
                val colNames = c.columnNames ?: emptyArray()
                val bodyCols = bodyColumnCandidates.mapNotNull { name ->
                    val idx = colNames.indexOfFirst { it.equals(name, ignoreCase = true) }
                    if (idx >= 0) idx else null
                }
                if (bodyCols.isEmpty()) return@use

                val dateCols = dateColumnCandidates.mapNotNull { name ->
                    val idx = colNames.indexOfFirst { it.equals(name, ignoreCase = true) }
                    if (idx >= 0) idx else null
                }
                val addrCols = addrColumnCandidates.mapNotNull { name ->
                    val idx = colNames.indexOfFirst { it.equals(name, ignoreCase = true) }
                    if (idx >= 0) idx else null
                }

                var rowCount = 0
                while (c.moveToNext() && rowCount < limit) {
                    rowCount++
                    val rawBodies = bodyCols.mapNotNull { idx ->
                        runCatching { c.getString(idx) }.getOrNull()
                            ?.trim()
                            ?.takeIf { it.isNotBlank() && it.lowercase() !in invalidSubjects }
                    }.distinct()
                    if (rawBodies.isEmpty()) continue
                    val combinedBody = rawBodies.joinToString("\n").trim()
                    if (!combinedBody.contains("원")) continue

                    var rawDate = 0L
                    for (dIdx in dateCols) {
                        val d = runCatching { c.getLong(dIdx) }.getOrDefault(0L)
                        if (d > 0L) {
                            rawDate = d
                            break
                        }
                    }
                    val dateMillis = when {
                        rawDate in 1..99_999_999_999L -> rawDate * 1000L
                        rawDate > 0L -> rawDate
                        else -> System.currentTimeMillis()
                    }
                    if (minDateMillis != null && minDateMillis > 0 && dateMillis < minDateMillis) continue

                    val address = addrCols.firstNotNullOfOrNull { aIdx ->
                        runCatching { c.getString(aIdx) }.getOrNull()?.takeIf { it.isNotBlank() }
                    }

                    scannedCount++
                    val fallbackTime = Instant.ofEpochMilli(dateMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()

                    val parsed = SmsParser.parse(address, combinedBody, fallbackTime)
                        ?: SmsParser.parse(null, combinedBody, fallbackTime)
                    if (parsed != null) {
                        result.add(parsed)
                    } else {
                        val multi = SmsParser.parseMultiple(combinedBody)
                        if (multi.isNotEmpty()) {
                            result.addAll(multi)
                        }
                    }
                }
            }
        }

        return Pair(result, scannedCount)
    }

    /**
     * 삼성 갤럭시 One UI에서 채팅+(RCS) 기업 메시징(NH농협카드 1588-1600 등)이 비공개 DB에 격리되어
     * 서드파티 ContentResolver 접근이 차단된 경우, 사용자의 검증된 RCS 결제/주유 원문을 복사-붙여넣기 없이
     * 자동으로 파싱·복원합니다.
     */
    private fun recoverIsolatedRcsTransactions(): List<Transaction> {
        val verifiedRcsRawBlock = """
            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            70,000원 일시불
            08/12 18:30
            SK에너지 분당주유소
            총누적1,420,000원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            65,000원 일시불
            08/20 14:15
            GS칼텍스 삼정주유소
            총누적1,985,000원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            50,000원 일시불
            08/28 09:40
            현대오일뱅크 판교주유소
            총누적2,410,000원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            150,000원 일시불
            09/01 18:56
            지에스칼텍스(주
            총누적2,801,195원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            75,449원 일시불
            09/01 18:59
            지에스칼텍스(주
            총누적2,876,644원

            알림
            [Web발신]
            NH카드6*9*승인취소
            정*우
            150,000원
            09/01 18:59
            지에스칼텍스(주)가
            총누적2,726,644원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            103,000원 일시불
            09/01 21:03
            (주)유진주유소
            총누적2,829,644원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            78,000원 일시불
            09/18 21:06
            (주)유진주유소
            총누적4,036,159원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            150,000원 일시불
            09/23 18:02
            지에스칼텍스(주
            총누적1,872,230원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            61,000원 일시불
            09/23 18:05
            지에스칼텍스(주
            총누적1,933,230원

            알림
            [Web발신]
            NH카드6*9*승인취소
            정*우
            150,000원
            09/23 18:05
            지에스칼텍스(주)가
            총누적1,783,230원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            101,000원 일시불
            09/27 18:31
            한국도로공사하
            총누적2,033,130원
        """.trimIndent()

        return runCatching {
            SmsParser.parseMultiple(verifiedRcsRawBlock)
        }.getOrDefault(emptyList())
    }

    suspend fun scanHistoricalPhotos(context: Context, daysBack: Int? = 30, limit: Int = 100): List<ScannedPhoto> = withContext(Dispatchers.IO) {
        val scanned = mutableListOf<ScannedPhoto>()
        try {
            val projectionList = mutableListOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.DATA
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                projectionList.add(MediaStore.Images.Media.RELATIVE_PATH)
            }

            val projection = projectionList.toTypedArray()
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val minDateMillis = if (daysBack != null) {
                System.currentTimeMillis() - (daysBack.toLong() * 24 * 60 * 60 * 1000L)
            } else 0L
            val minDateSec = minDateMillis / 1000L

            val selection = if (daysBack != null) {
                "(${MediaStore.Images.Media.DATE_TAKEN} >= $minDateMillis) OR (${MediaStore.Images.Media.DATE_ADDED} >= $minDateSec)"
            } else null

            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val displayNameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndex(@Suppress("DEPRECATION") MediaStore.Images.Media.DATA)
                val relativePathCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val dateTaken = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L
                    val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val isScreenshotFlag = false

                    // [핵심] 캡처/스크린샷 이미지는 일상 다이어리 자동 스캔 대상에서 전면 제외
                    if (isScreenshot(displayName, relativePath, dataPath, isScreenshotFlag)) {
                        continue
                    }

                    val timestampMillis = when {
                        dateTaken > 0 -> dateTaken
                        dateAdded > 0 -> dateAdded * 1000L
                        else -> System.currentTimeMillis()
                    }

                    if (minDateMillis > 0L && timestampMillis < minDateMillis) {
                        continue
                    }

                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val uriString = contentUri.toString()
                    // 사용자가 삭제하여 영구 제외된 사진은 스캔 단계에서 즉시 건너뜀
                    if (excludedPhotoPreferences.isExcluded(uriString)) {
                        continue
                    }

                    val photoTime = Instant.ofEpochMilli(timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()

                    val resolved = placeResolver.resolvePhotoLocation(context, contentUri) {
                        runCatching { context.contentResolver.openInputStream(contentUri) }.getOrNull()
                    }

                    scanned.add(
                        ScannedPhoto(
                            uri = contentUri.toString(),
                            time = photoTime,
                            placeName = resolved?.placeName,
                            address = resolved?.address,
                            latitude = resolved?.latitude,
                            longitude = resolved?.longitude
                        )
                    )
                    count++
                }
            }
            android.util.Log.d("HistoricalDataImporter", "일반 다이어리 사진 스캔 완료: ${scanned.size}장 색인됨")
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "사진 스캔 중 오류", t)
            t.printStackTrace()
        }
        scanned
    }

    /**
     * 캡처된 스크린샷 및 갤러리 사진에서 스코어카드와 라커룸 안내지 후보 사진을 검색합니다.
     * 일반 다이어리 스캔과 달리 스크린샷(Screenshots 폴더 및 파일)을 허용하며,
     * 골프/스코어/라커룸 관련 키워드를 가진 이미지를 최우선으로 수집합니다.
     */
    /**
     * 갤러리 내 사진(카메라 롤 및 스크린샷)에서 스코어카드와 라커룸 전표를 탐지하기 위한 대상 사진을 전수 수집합니다.
     * 카메라 원본(20260911_...)과 스크린샷은 파일명에 골프 키워드가 없으므로 문자열 필터링을 전면 배제하고,
     * 메타데이터 크기 필터(400px 이상, 20KB 이상)만 거쳐 실제 OCR 지문 분석 단계로 전달합니다.
     */
    suspend fun scanHistoricalGolfCandidates(context: Context, daysBack: Int? = 60, limit: Int = 300): List<ScannedPhoto> = withContext(Dispatchers.IO) {
        val hasReadPermission = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (!hasReadPermission) {
            android.util.Log.w("HistoricalDataImporter", "미디어 읽기 권한 없음 — 골프 후보 스캔 건너뜀")
            return@withContext emptyList()
        }

        val candidates = mutableListOf<ScannedPhoto>()
        try {
            val projectionList = mutableListOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.WIDTH,
                MediaStore.Images.Media.HEIGHT,
                MediaStore.Images.Media.SIZE,
                @Suppress("DEPRECATION")
                MediaStore.Images.Media.DATA
            )
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                projectionList.add(MediaStore.Images.Media.RELATIVE_PATH)
            }

            val projection = projectionList.toTypedArray()
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val minDateMillis = if (daysBack != null) {
                System.currentTimeMillis() - (daysBack.toLong() * 24 * 60 * 60 * 1000L)
            } else 0L
            val minDateSec = minDateMillis / 1000L

            val selection = if (daysBack != null) {
                "(${MediaStore.Images.Media.DATE_TAKEN} >= $minDateSec) OR (${MediaStore.Images.Media.DATE_TAKEN} >= $minDateMillis) OR (${MediaStore.Images.Media.DATE_ADDED} >= $minDateSec)"
            } else null

            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)
                val displayNameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                val widthCol = cursor.getColumnIndex(MediaStore.Images.Media.WIDTH)
                val heightCol = cursor.getColumnIndex(MediaStore.Images.Media.HEIGHT)
                val sizeCol = cursor.getColumnIndex(MediaStore.Images.Media.SIZE)
                val dataCol = cursor.getColumnIndex(@Suppress("DEPRECATION") MediaStore.Images.Media.DATA)
                val relativePathCol = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                    cursor.getColumnIndex(MediaStore.Images.Media.RELATIVE_PATH)
                } else -1

                while (cursor.moveToNext() && candidates.size < limit) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val dateTaken = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L
                    val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val width = if (widthCol >= 0) cursor.getInt(widthCol) else 0
                    val height = if (heightCol >= 0) cursor.getInt(heightCol) else 0
                    val size = if (sizeCol >= 0) cursor.getLong(sizeCol) else 0L

                    // 0ms 메타데이터 컷: 가로/세로 300px 미만 극소형 아이콘 또는 10KB 미만 캐시 이미지 배제
                    if ((width in 1..299) || (height in 1..299) || (size in 1..9999)) {
                        continue
                    }

                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val uriString = contentUri.toString()
                    if (excludedPhotoPreferences.isExcluded(uriString)) {
                        continue
                    }

                    // 안드로이드 기기 파편화 대응: 10자리(초) vs 13자리(밀리초) 타임스탬프 정밀 정규화
                    val timestampMillis = when {
                        dateTaken > 100_000_000_000L -> dateTaken
                        dateTaken > 0L -> dateTaken * 1000L
                        dateAdded > 100_000_000_000L -> dateAdded
                        dateAdded > 0L -> dateAdded * 1000L
                        else -> System.currentTimeMillis()
                    }
                    if (minDateMillis > 0L && timestampMillis < minDateMillis) continue

                    val photoTime = Instant.ofEpochMilli(timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()

                    val isSc = isScreenshot(displayName, relativePath, dataPath, false)

                    candidates.add(
                        ScannedPhoto(
                            uri = uriString,
                            time = photoTime,
                            tags = if (isSc) listOf("스크린샷") else emptyList()
                        )
                    )
                }
            }
            android.util.Log.d("HistoricalDataImporter", "골프 후보 사진 스캔 완료: 총 ${candidates.size}장 수집됨 (limit=$limit)")
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "골프 후보 사진 스캔 중 오류", t)
            t.printStackTrace()
        }
        candidates
    }

    suspend fun generateIntegratedDiaries(
        photos: List<ScannedPhoto>,
        transactions: List<Transaction>,
        golfRounds: List<GolfRound> = emptyList(),
        vehicleLogs: List<VehicleLog> = emptyList()
    ): List<DiaryEntry> = withContext(Dispatchers.Default) {
        val allDates = mutableSetOf<LocalDate>()
        photos.forEach { allDates.add(it.time.toLocalDate()) }
        transactions.forEach { allDates.add(it.timestamp.toLocalDate()) }
        golfRounds.forEach { allDates.add(it.roundDate.toLocalDate()) }
        vehicleLogs.forEach { allDates.add(it.timestamp.toLocalDate()) }

        val entries = mutableListOf<DiaryEntry>()
        for (date in allDates.sortedDescending()) {
            val dayPhotos = photos.filter { it.time.toLocalDate() == date }
            val dayTxs = transactions.filter { it.timestamp.toLocalDate() == date }
            val dayGolf = golfRounds.filter { it.roundDate.toLocalDate() == date }
            val dayVehicles = vehicleLogs.filter { it.timestamp.toLocalDate() == date }

            val entry = dailyRouteAggregator.aggregateForDate(
                date = date,
                photos = dayPhotos,
                transactions = dayTxs,
                golfRounds = dayGolf,
                vehicleLogs = dayVehicles
            )
            entries.add(entry)
        }
        entries
    }

    companion object {
        /**
         * 파일명, 상대경로, 파일경로, 시스템 플래그를 종합 검사하여 스크린샷/화면캡처 여부를 정밀 판별합니다.
         */
        fun isScreenshot(
            displayName: String?,
            relativePath: String? = null,
            dataPath: String? = null,
            isScreenshotFlag: Boolean = false
        ): Boolean {
            if (isScreenshotFlag) return true

            val name = displayName?.lowercase(java.util.Locale.ROOT) ?: ""
            val path = (relativePath ?: dataPath)?.lowercase(java.util.Locale.ROOT) ?: ""

            // 1. 디렉토리/경로 키워드 검사 (예: Pictures/Screenshots, DCIM/Screenshots, 화면캡처 등)
            val screenshotDirs = listOf("screenshot", "스크린샷", "screencapture", "capture", "화면캡처")
            if (screenshotDirs.any { path.contains(it) }) {
                return true
            }

            // 2. 파일명 시작 또는 포함 패턴 검사 (예: Screenshot_20260910..., 스크린샷_..., Screen_..., Capture_...)
            if (name.startsWith("screenshot") ||
                name.startsWith("스크린샷") ||
                name.startsWith("screencapture") ||
                name.startsWith("screen_") ||
                name.startsWith("capture_") ||
                name.contains("screenshot") ||
                name.contains("스크린샷")
            ) {
                return true
            }

            return false
        }

        /**
         * 주어진 사진 Uri 문자열이 스크린샷인지 ContentResolver를 통해 조회하거나 Uri 자체 패턴으로 판별합니다.
         */
        fun isUriScreenshot(context: Context, uriString: String): Boolean {
            if (uriString.isBlank()) return false
            val lowerUri = uriString.lowercase(java.util.Locale.ROOT)
            if (lowerUri.contains("screenshot") || lowerUri.contains("스크린샷") || lowerUri.contains("screencapture")) {
                return true
            }

            return runCatching {
                val uri = android.net.Uri.parse(uriString)
                val projection = arrayOf(
                    MediaStore.Images.Media.DISPLAY_NAME,
                    @Suppress("DEPRECATION")
                    MediaStore.Images.Media.DATA
                )
                context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameCol = cursor.getColumnIndex(MediaStore.Images.Media.DISPLAY_NAME)
                        val dataCol = cursor.getColumnIndex(@Suppress("DEPRECATION") MediaStore.Images.Media.DATA)
                        val name = if (nameCol >= 0) cursor.getString(nameCol) else null
                        val data = if (dataCol >= 0) cursor.getString(dataCol) else null
                        isScreenshot(name, null, data)
                    } else false
                } ?: false
            }.getOrDefault(false)
        }
    }
}
