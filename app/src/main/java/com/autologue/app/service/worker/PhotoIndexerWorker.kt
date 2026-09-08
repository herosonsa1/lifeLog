package com.autologue.app.service.worker

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.os.Build
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.autologue.app.data.preferences.ExcludedPhotoPreferences
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.repository.DiaryRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.Locale

@HiltWorker
class PhotoIndexerWorker @AssistedInject constructor(
    @Assisted private val context: Context,
    @Assisted workerParams: WorkerParameters,
    private val diaryRepository: DiaryRepository,
    private val excludedPhotoPreferences: ExcludedPhotoPreferences
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        // [C-02] 미디어 접근 권한 사전 체크 — 권한 없으면 무한 재시도 대신 즉시 failure() 반환
        val requiredPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }
        if (ContextCompat.checkSelfPermission(context, requiredPermission) != PackageManager.PERMISSION_GRANTED) {
            android.util.Log.w("PhotoIndexerWorker", "미디어 접근 권한 없음 — 작업 중단 (재시도 금지)")
            return@withContext Result.failure() // [C-02] 권한 부재 시 retry() 무한루프 방지
        }

        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DISPLAY_NAME
            )

            val sortOrder = "${MediaStore.Images.Media.DATE_TAKEN} DESC"
            val queryUri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI

            context.contentResolver.query(queryUri, projection, null, null, sortOrder)?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val dateTakenColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_TAKEN)

                var count = 0
                while (cursor.moveToNext() && count < 20) {
                    val id = cursor.getLong(idColumn)
                    val dateTaken = cursor.getLong(dateTakenColumn)
                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    if (excludedPhotoPreferences.isExcluded(contentUri.toString())) {
                        count++
                        continue
                    }

                    runCatching {
                        context.contentResolver.openInputStream(contentUri)?.use { input ->
                            val exif = ExifInterface(input)
                            val latLong = FloatArray(2)
                            if (exif.getLatLong(latLong)) {
                                val lat = latLong[0].toDouble()
                                val lng = latLong[1].toDouble()

                                val geocoder = Geocoder(context, Locale.KOREA)
                                val addresses = geocoder.getFromLocation(lat, lng, 1)
                                val addressLine = addresses?.firstOrNull()?.getAddressLine(0)
                                val placeName = addresses?.firstOrNull()?.featureName ?: addressLine

                                val photoDate = if (dateTaken > 0) {
                                    Instant.ofEpochMilli(dateTaken).atZone(ZoneId.systemDefault()).toLocalDateTime()
                                } else LocalDateTime.now()

                                val entry = DiaryEntry(
                                    date = photoDate,
                                    title = placeName ?: "오늘의 발자취",
                                    summary = "자동 색인된 위치 및 사진 기록입니다.",
                                    placeName = placeName,
                                    address = addressLine,
                                    latitude = lat,
                                    longitude = lng,
                                    photoUris = listOf(contentUri.toString())
                                )
                                diaryRepository.insertDiaryEntry(entry)
                            }
                        }
                    }
                    count++
                }
            }
            Result.success()
        } catch (e: SecurityException) {
            // [C-02] SecurityException: 재시도해도 해결 불가 → failure() 반환으로 무한루프 방지
            android.util.Log.e("PhotoIndexerWorker", "보안 예외 — 재시도 중단", e)
            Result.failure()
        } catch (e: Exception) {
            // 일반 IO 에러는 retry() 허용 (일시적 실패 가능)
            android.util.Log.w("PhotoIndexerWorker", "일반 오류 — 재시도 예약", e)
            Result.retry()
        }
    }
}
