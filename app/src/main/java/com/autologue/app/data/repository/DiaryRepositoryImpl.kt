package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.DiaryDao
import com.autologue.app.data.local.entity.DiaryEntryEntity
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.PlaceCluster
import com.autologue.app.domain.repository.DiaryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import com.autologue.app.data.preferences.ExcludedPhotoPreferences
import javax.inject.Inject

class DiaryRepositoryImpl @Inject constructor(
    private val diaryDao: DiaryDao,
    private val excludedPhotoPreferences: ExcludedPhotoPreferences
) : DiaryRepository {

    override fun getDiaryEntriesFlow(): Flow<List<DiaryEntry>> {
        return diaryDao.getAllEntries().map { entities -> entities.map { it.toDomain() } }
    }

    override fun getDiaryEntriesByDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DiaryEntry>> {
        val startEpoch = startDate.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endEpoch = endDate.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return diaryDao.getEntriesByDateRange(startEpoch, endEpoch).map { entities -> entities.map { it.toDomain() } }
    }

    override suspend fun getDiaryEntryById(id: Long): DiaryEntry? = withContext(Dispatchers.IO) {
        diaryDao.getEntryById(id)?.toDomain()
    }

    override suspend fun insertDiaryEntry(entry: DiaryEntry): Long = withContext(Dispatchers.IO) {
        val date = entry.date.toLocalDate()
        val startDateTime = date.atStartOfDay()
        val endDateTime = date.atTime(23, 59, 59, 999999999)

        val existing = diaryDao.getEntryByDateRangeSingle(startDateTime, endDateTime)
        if (existing != null) {
            val isUserCustomTitle = !existing.title.matches(Regex("^[0-9]+(-[0-9]+)?$")) && existing.title.isNotBlank()
            val mergedPhotos = (existing.photoUris + entry.photoUris).distinct().filterNot { excludedPhotoPreferences.isExcluded(it) }
            val rawCombined = if (entry.routeSteps.isEmpty()) {
                existing.routeSteps
            } else if (existing.routeSteps.isEmpty()) {
                entry.routeSteps
            } else {
                existing.routeSteps + entry.routeSteps
            }
            val combinedSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(rawCombined)
            val mergedSteps = combinedSteps.map { step ->
                step.copy(photoUris = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) })
            }
            val merged = existing.copy(
                title = if (isUserCustomTitle) existing.title else entry.title,
                summary = if (existing.summary.isNotBlank()) existing.summary else entry.summary,
                placeName = entry.placeName ?: existing.placeName,
                address = entry.address ?: existing.address,
                latitude = entry.latitude ?: existing.latitude,
                longitude = entry.longitude ?: existing.longitude,
                photoUris = mergedPhotos,
                totalExpense = entry.totalExpense,
                drivingDistanceKm = if (entry.drivingDistanceKm > 0) entry.drivingDistanceKm else existing.drivingDistanceKm,
                hasGolfRound = entry.hasGolfRound || existing.hasGolfRound,
                tags = (existing.tags + entry.tags).distinct(),
                routeSteps = mergedSteps,
                movementSummary = entry.movementSummary ?: existing.movementSummary
            )
            diaryDao.updateEntry(merged)
            return@withContext existing.id
        }
        val cleanEntry = entry.copy(
            photoUris = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) },
            tags = entry.tags.distinct(),
            routeSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).map { step ->
                step.copy(photoUris = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) })
            }
        )
        diaryDao.insertEntry(cleanEntry.toEntity())
    }

    override suspend fun updateDiaryEntry(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        val cleanEntry = entry.copy(
            photoUris = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) },
            tags = entry.tags.distinct(),
            routeSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).map { step ->
                step.copy(photoUris = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) })
            }
        )
        diaryDao.updateEntry(cleanEntry.toEntity())
    }

    override suspend fun deleteDiaryEntry(id: Long) = withContext(Dispatchers.IO) {
        diaryDao.deleteEntryById(id)
    }

    override suspend fun cleanDuplicates(): Int = withContext(Dispatchers.IO) {
        val all = diaryDao.getAllEntriesSync()
        val seen = mutableSetOf<LocalDate>()
        var modifiedCount = 0
        for (entry in all) {
            val d = entry.date.toLocalDate()
            if (d in seen) {
                diaryDao.deleteEntryById(entry.id)
                modifiedCount++
            } else {
                seen.add(d)
                // 내부 중복 데이터(routeSteps, photoUris, tags) 정리
                val cleanPhotos = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) }
                val cleanTags = entry.tags.distinct()
                val cleanSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).map { step ->
                    step.copy(photoUris = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) })
                }
                if (cleanSteps.size != entry.routeSteps.size || cleanPhotos.size != entry.photoUris.size || cleanTags.size != entry.tags.size) {
                    val updated = entry.copy(
                        photoUris = cleanPhotos,
                        tags = cleanTags,
                        routeSteps = cleanSteps
                    )
                    diaryDao.updateEntry(updated)
                    modifiedCount++
                }
            }
        }
        modifiedCount
    }

    override suspend fun generateClustersForDate(date: LocalDate): List<PlaceCluster> = withContext(Dispatchers.IO) {
        listOf(
            PlaceCluster(
                placeName = "오피스 허브",
                address = "서울특별시 강남구 테헤란로",
                latitude = 37.5000,
                longitude = 127.0365,
                startTime = date.atTime(9, 0),
                endTime = date.atTime(18, 0),
                photoUris = emptyList()
            )
        )
    }

    private fun DiaryEntryEntity.toDomain() = DiaryEntry(
        id = id, date = date, title = title, summary = summary,
        placeName = placeName, address = address, latitude = latitude, longitude = longitude,
        photoUris = photoUris, totalExpense = totalExpense, drivingDistanceKm = drivingDistanceKm,
        hasGolfRound = hasGolfRound, tags = tags,
        routeSteps = routeSteps, movementSummary = movementSummary
    )

    private fun DiaryEntry.toEntity() = DiaryEntryEntity(
        id = id, date = date, title = title, summary = summary,
        placeName = placeName, address = address, latitude = latitude, longitude = longitude,
        photoUris = photoUris, totalExpense = totalExpense, drivingDistanceKm = drivingDistanceKm,
        hasGolfRound = hasGolfRound, tags = tags,
        routeSteps = routeSteps, movementSummary = movementSummary
    )
}
