package com.autologue.app.data.sync

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.provider.Telephony
import com.autologue.app.data.parser.SmsParser
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HistoricalDataImporter @Inject constructor(
    private val placeResolver: PlaceResolver,
    private val dailyRouteAggregator: DailyRouteAggregator
) {

    suspend fun scanHistoricalSms(context: Context, daysBack: Int? = 7, limit: Int = 300): List<Transaction> = withContext(Dispatchers.IO) {
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        result
    }

    suspend fun scanHistoricalPhotos(context: Context, daysBack: Int? = 7, limit: Int = 40): List<ScannedPhoto> = withContext(Dispatchers.IO) {
        val scanned = mutableListOf<ScannedPhoto>()
        try {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DATE_TAKEN,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.DISPLAY_NAME
            )
            val uri = MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            val sortOrder = "${MediaStore.Images.Media.DATE_ADDED} DESC"

            val actualDays = daysBack ?: 7
            val minDateMillis = System.currentTimeMillis() - (actualDays.toLong() * 24 * 60 * 60 * 1000L)
            val minDateSec = minDateMillis / 1000L
            val selection = "(${MediaStore.Images.Media.DATE_TAKEN} >= $minDateMillis) OR (${MediaStore.Images.Media.DATE_ADDED} >= $minDateSec)"

            context.contentResolver.query(uri, projection, selection, null, sortOrder)?.use { cursor ->
                val idCol = cursor.getColumnIndex(MediaStore.Images.Media._ID)
                val dateTakenCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_TAKEN)
                val dateAddedCol = cursor.getColumnIndex(MediaStore.Images.Media.DATE_ADDED)

                var count = 0
                while (cursor.moveToNext() && count < limit) {
                    if (idCol < 0) continue
                    val id = cursor.getLong(idCol)
                    val dateTaken = if (dateTakenCol >= 0) cursor.getLong(dateTakenCol) else 0L
                    val dateAdded = if (dateAddedCol >= 0) cursor.getLong(dateAddedCol) else 0L

                    val timestampMillis = when {
                        dateTaken > 0 -> dateTaken
                        dateAdded > 0 -> dateAdded * 1000L
                        else -> System.currentTimeMillis()
                    }

                    if (minDateMillis != null && timestampMillis < minDateMillis) {
                        continue
                    }

                    val contentUri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
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
        } catch (e: Exception) {
            e.printStackTrace()
        }
        scanned
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
}
