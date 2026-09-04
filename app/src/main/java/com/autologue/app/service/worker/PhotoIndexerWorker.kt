package com.autologue.app.service.worker

import android.content.ContentUris
import android.content.Context
import android.location.Geocoder
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
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
    private val diaryRepository: DiaryRepository
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
