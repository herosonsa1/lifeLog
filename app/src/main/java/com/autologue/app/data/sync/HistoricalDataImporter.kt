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

@Singleton
class HistoricalDataImporter @Inject constructor(
    private val placeResolver: PlaceResolver,
    private val dailyRouteAggregator: DailyRouteAggregator,
    private val excludedPhotoPreferences: ExcludedPhotoPreferences
) {

    suspend fun scanHistoricalSms(context: Context, daysBack: Int? = 7, limit: Int = 300): List<Transaction> = withContext(Dispatchers.IO) {
        // [L-01] READ_SMS 권한 사전 체크 — SecurityException 원천 방지
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("HistoricalDataImporter", "READ_SMS 권한 없음 — SMS 스캔 건너뜀")
            return@withContext emptyList()
        }
        val result = mutableListOf<Transaction>()
        try {
            val projection = arrayOf(
                Telephony.Sms._ID,
                Telephony.Sms.ADDRESS,
                Telephony.Sms.BODY,
                Telephony.Sms.DATE
            )

            val uri = Uri.parse("content://sms")
            val sortOrder = "${Telephony.Sms.DATE} DESC"

            val actualDays = daysBack ?: 7
            val minDateMillis = System.currentTimeMillis() - (actualDays.toLong() * 24 * 60 * 60 * 1000L)

            val selection = "${Telephony.Sms.DATE} >= ?"
            val selectionArgs = arrayOf(minDateMillis.toString())

            context.contentResolver.query(uri, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
                val addressCol = cursor.getColumnIndex(Telephony.Sms.ADDRESS)
                val bodyCol = cursor.getColumnIndex(Telephony.Sms.BODY)
                val dateCol = cursor.getColumnIndex(Telephony.Sms.DATE)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    val address = if (addressCol >= 0) cursor.getString(addressCol) else null
                    val body = if (bodyCol >= 0) cursor.getString(bodyCol) else ""
                    val dateMillis = if (dateCol >= 0) cursor.getLong(dateCol) else System.currentTimeMillis()

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
            t.printStackTrace()
        }
        result
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
    suspend fun scanHistoricalGolfCandidates(context: Context, daysBack: Int? = 60, limit: Int = 50): List<ScannedPhoto> = withContext(Dispatchers.IO) {
        val candidates = mutableListOf<ScannedPhoto>()
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

                val highPriority = mutableListOf<ScannedPhoto>()
                val mediumPriority = mutableListOf<ScannedPhoto>()
                val lowPriority = mutableListOf<ScannedPhoto>()

                while (cursor.moveToNext()) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val dateTaken = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L
                    val displayName = if (displayNameCol >= 0) cursor.getString(displayNameCol) else null
                    val dataPath = if (dataCol >= 0) cursor.getString(dataCol) else null
                    val relativePath = if (relativePathCol >= 0) cursor.getString(relativePathCol) else null
                    val isScreenshotFlag = false

                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    val uriString = contentUri.toString()
                    if (excludedPhotoPreferences.isExcluded(uriString)) {
                        continue
                    }

                    val timestampMillis = when {
                        dateTaken > 0 -> dateTaken
                        dateAdded > 0 -> dateAdded * 1000L
                        else -> System.currentTimeMillis()
                    }
                    if (minDateMillis > 0L && timestampMillis < minDateMillis) continue

                    val photoTime = Instant.ofEpochMilli(timestampMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDateTime()

                    val isSc = isScreenshot(displayName, relativePath, dataPath, isScreenshotFlag)
                    val searchStr = "${displayName ?: ""} ${relativePath ?: ""} ${dataPath ?: ""}".lowercase(java.util.Locale.ROOT)
                    val hasGolfKeyword = searchStr.contains("golf") || searchStr.contains("골프") ||
                            searchStr.contains("score") || searchStr.contains("스코어") ||
                            searchStr.contains("locker") || searchStr.contains("락커") || searchStr.contains("라커") ||
                            searchStr.contains("round") || searchStr.contains("라운드") ||
                            searchStr.contains("smartscore") || searchStr.contains("스마트스코어") ||
                            searchStr.contains("cc") || searchStr.contains("gc") ||
                            searchStr.contains("c.c") || searchStr.contains("g.c") ||
                            searchStr.contains("골프장") || searchStr.contains("전표") ||
                            searchStr.contains("야디지") || searchStr.contains("골프존") ||
                            searchStr.contains("카카오골프")

                    val item = ScannedPhoto(
                        uri = uriString,
                        time = photoTime,
                        tags = if (isSc) listOf("스크린샷") else emptyList()
                    )

                    if (hasGolfKeyword) {
                        highPriority.add(item)
                    } else if (isSc) {
                        // 스크린샷 이미지는 골프 스코어카드일 확률이 높으므로 2순위 후보로 수집
                        mediumPriority.add(item)
                    } else {
                        // 3순위: 카메라 등으로 촬영한 지류 영수증/스코어카드 후보 (일반 사진 풀)
                        lowPriority.add(item)
                    }
                }

                candidates.addAll(highPriority)
                val remainingForMedium = limit - candidates.size
                if (remainingForMedium > 0) {
                    candidates.addAll(mediumPriority.take(remainingForMedium))
                }
                val remainingForLow = limit - candidates.size
                if (remainingForLow > 0) {
                    candidates.addAll(lowPriority.take(remainingForLow))
                }
            }
            android.util.Log.d("HistoricalDataImporter", "골프 후보 사진 스캔 완료: 총 ${candidates.size}장 수집됨")
        } catch (t: Throwable) {
            android.util.Log.e("HistoricalDataImporter", "골프 후보 사진 스캔 중 오류", t)
            t.printStackTrace()
        }
        candidates.take(limit)
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
