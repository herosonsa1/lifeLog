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
    val totalMessagesScanned: Int
)

@Singleton
class HistoricalDataImporter @Inject constructor(
    private val placeResolver: PlaceResolver,
    private val dailyRouteAggregator: DailyRouteAggregator,
    private val excludedPhotoPreferences: ExcludedPhotoPreferences
) {

    suspend fun scanHistoricalSms(context: Context, daysBack: Int? = 90, limit: Int = 2000): List<Transaction> {
        return scanHistoricalSmsDetailed(context, daysBack, limit).transactions
    }

    suspend fun scanHistoricalSmsDetailed(
        context: Context,
        daysBack: Int? = 90,
        limit: Int = 2000
    ): SmsScanResult = withContext(Dispatchers.IO) {
        // [L-01] READ_SMS 권한 사전 체크 — SecurityException 원천 방지
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("HistoricalDataImporter", "READ_SMS 권한 없음 — SMS/MMS 스캔 건너뜀")
            return@withContext SmsScanResult(emptyList(), isPermissionGranted = false, totalMessagesScanned = 0)
        }

        val minDateMillis = if (daysBack != null && daysBack > 0) {
            System.currentTimeMillis() - (daysBack.toLong() * 24 * 60 * 60 * 1000L)
        } else {
            null
        }

        val allTxs = mutableListOf<Transaction>()
        var totalScanned = 0

        // 1. 단문 SMS 수신함 스캔
        val (smsTxs, smsCount) = scanSmsMessages(context, minDateMillis, limit)
        allTxs.addAll(smsTxs)
        totalScanned += smsCount

        // 2. 장문 MMS / LMS 수신함 스캔 (80~90바이트 초과 카드 승인 문자 완벽 지원)
        val (mmsTxs, mmsCount) = scanMmsMessages(context, minDateMillis, limit)
        allTxs.addAll(mmsTxs)
        totalScanned += mmsCount

        // 3. 중복 제거 및 시간 역순 정렬
        val distinctTxs = allTxs.distinctBy {
            "${it.amount}_${it.timestamp}_${it.merchantName}"
        }.sortedByDescending { it.timestamp }

        android.util.Log.d(
            "HistoricalDataImporter",
            "문자 스캔 완료: 총 ${totalScanned}건 스캔(SMS: $smsCount, MMS: $mmsCount), 거래 ${distinctTxs.size}건 파싱"
        )

        SmsScanResult(
            transactions = distinctTxs,
            isPermissionGranted = true,
            totalMessagesScanned = totalScanned
        )
    }

    private fun scanSmsMessages(
        context: Context,
        minDateMillis: Long?,
        limit: Int
    ): Pair<List<Transaction>, Int> {
        val result = mutableListOf<Transaction>()
        var count = 0
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            val (selection, selectionArgs) = if (minDateMillis != null && minDateMillis > 0) {
                Pair("${Telephony.Sms.DATE} >= ?", arrayOf(minDateMillis.toString()))
            } else {
                Pair(null, null)
            }

            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val urisToTry = listOf(
                Telephony.Sms.Inbox.CONTENT_URI,
                Uri.parse("content://sms/inbox"),
                Uri.parse("content://sms")
            )

            var cursor: android.database.Cursor? = null
            for (uri in urisToTry) {
                val candidate = runCatching {
                    context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                }.getOrNull()
                if (candidate != null) {
                    if (candidate.count > 0) {
                        cursor?.close()
                        cursor = candidate
                        break
                    } else if (cursor == null) {
                        cursor = candidate
                    } else {
                        candidate.close()
                    }
                }
            }

            cursor?.use { c ->
                val addressCol = c.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = c.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = c.getColumnIndex(Telephony.Sms.DATE)

                while (c.moveToNext() && count < limit) {
                    val address = if (addressCol >= 0) c.getString(addressCol) else null
                    val body = if (bodyCol >= 0) c.getString(bodyCol) else ""
                    val dateMillis = if (dateCol >= 0) c.getLong(dateCol) else System.currentTimeMillis()

                    if (body.isNotBlank()) {
                        val fallbackTime = Instant.ofEpochMilli(dateMillis)
                            .atZone(ZoneId.systemDefault())
                            .toLocalDateTime()

                        val parsed = SmsParser.parse(address, body, fallbackTime)
                        if (parsed != null) {
                            result.add(parsed)
                        }
                    }
                    count++
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "SMS 스캔 중 예외 안전 포획", t)
        }
        return Pair(result, count)
    }

    private fun scanMmsMessages(
        context: Context,
        minDateMillis: Long?,
        limit: Int
    ): Pair<List<Transaction>, Int> {
        val result = mutableListOf<Transaction>()
        var count = 0
        try {
            val projection = arrayOf(
                Telephony.Mms._ID,
                Telephony.Mms.DATE
            )

            val (selection, selectionArgs) = if (minDateMillis != null && minDateMillis > 0) {
                val minDateSec = minDateMillis / 1000L
                Pair("${Telephony.Mms.DATE} >= ?", arrayOf(minDateSec.toString()))
            } else {
                Pair(null, null)
            }

            val sortOrder = "${Telephony.Mms.DATE} DESC"

            val urisToTry = listOf(
                Telephony.Mms.Inbox.CONTENT_URI,
                Uri.parse("content://mms/inbox"),
                Uri.parse("content://mms")
            )

            var cursor: android.database.Cursor? = null
            for (uri in urisToTry) {
                val candidate = runCatching {
                    context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)
                }.getOrNull()
                if (candidate != null) {
                    if (candidate.count > 0) {
                        cursor?.close()
                        cursor = candidate
                        break
                    } else if (cursor == null) {
                        cursor = candidate
                    } else {
                        candidate.close()
                    }
                }
            }

            cursor?.use { c ->
                val idCol = c.getColumnIndex(Telephony.Mms._ID)
                val dateCol = c.getColumnIndex(Telephony.Mms.DATE)

                while (c.moveToNext() && count < limit) {
                    val mmsId = if (idCol >= 0) c.getString(idCol) else null
                    val dateSec = if (dateCol >= 0) c.getLong(dateCol) else (System.currentTimeMillis() / 1000L)
                    val dateMillis = dateSec * 1000L

                    if (mmsId != null) {
                        val body = getMmsBody(context, mmsId)
                        val address = getMmsSender(context, mmsId)

                        if (body.isNotBlank()) {
                            val fallbackTime = Instant.ofEpochMilli(dateMillis)
                                .atZone(ZoneId.systemDefault())
                                .toLocalDateTime()

                            val parsed = SmsParser.parse(address, body, fallbackTime)
                            if (parsed != null) {
                                result.add(parsed)
                            }
                        }
                    }
                    count++
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "MMS/LMS 스캔 중 예외 안전 포획", t)
        }
        return Pair(result, count)
    }

    private fun getMmsBody(context: Context, mmsId: String): String {
        val sb = StringBuilder()
        try {
            val partUris = listOf(
                Pair(Uri.parse("content://mms/$mmsId/part"), Pair(null, null)),
                Pair(Uri.parse("content://mms/part"), Pair("mid = ?", arrayOf(mmsId)))
            )

            var partCursor: android.database.Cursor? = null
            for ((uri, querySpec) in partUris) {
                partCursor = runCatching {
                    context.contentResolver.query(
                        uri,
                        arrayOf("_id", "ct", "text", "_data"),
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
                    val ct = if (ctCol >= 0) c.getString(ctCol) else ""
                    if ("text/plain".equals(ct, ignoreCase = true)) {
                        val text = if (textCol >= 0) c.getString(textCol) else null
                        if (!text.isNullOrBlank()) {
                            sb.append(text)
                        } else if (idCol >= 0) {
                            val partId = c.getString(idCol)
                            val dataText = readMmsPartData(context, partId)
                            if (dataText.isNotBlank()) {
                                sb.append(dataText)
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
            context.contentResolver.query(addrUri, arrayOf("address", "type"), "type = 137", null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val addrCol = c.getColumnIndex("address")
                    if (addrCol >= 0) {
                        return c.getString(addrCol)
                    }
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "MMS 발신자 조회 실패: mmsId=$mmsId", t)
        }
        return null
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
