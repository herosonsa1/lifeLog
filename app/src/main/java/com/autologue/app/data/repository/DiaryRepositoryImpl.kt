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
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.model.RouteStepType
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject

class DiaryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
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
            val mergedSteps = combinedSteps.mapNotNull { step ->
                val photos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
                if (step.stepType == RouteStepType.PHOTO && photos.isEmpty()) {
                    null
                } else {
                    step.copy(photoUris = photos)
                }
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
            routeSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).mapNotNull { step ->
                val photos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
                if (step.stepType == RouteStepType.PHOTO && photos.isEmpty()) {
                    null
                } else {
                    step.copy(photoUris = photos)
                }
            }
        )
        diaryDao.insertEntry(cleanEntry.toEntity())
    }

    override suspend fun updateDiaryEntry(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        val cleanEntry = entry.copy(
            photoUris = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) },
            tags = entry.tags.distinct(),
            routeSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).mapNotNull { step ->
                val photos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
                if (step.stepType == RouteStepType.PHOTO && photos.isEmpty()) {
                    null
                } else {
                    step.copy(photoUris = photos)
                }
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

                // 1. 스크린샷 이미지 탐색 및 영구 제외 등록
                val allUris = (entry.photoUris + entry.routeSteps.flatMap { it.photoUris }).distinct()
                val screenshotUris = allUris.filter { HistoricalDataImporter.isUriScreenshot(context, it) }
                for (uri in screenshotUris) {
                    excludedPhotoPreferences.excludePhoto(uri)
                }

                // 2. 내부 중복 데이터(routeSteps, photoUris, tags) 및 스크린샷 정리
                val cleanPhotos = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) }
                val cleanTags = entry.tags.distinct()
                val cleanSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).mapNotNull { step ->
                    val stepPhotos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
                    // 사진 전용 스텝인데 사진이 0장이 된 경우(스크린샷만 있던 스텝 등) 스텝 자체 제거
                    if (step.stepType == RouteStepType.PHOTO && stepPhotos.isEmpty()) {
                        null
                    } else {
                        val newTitle = if (step.title == "서울 방이동" && step.stepType == RouteStepType.PHOTO) {
                            step.locationName ?: "사진 기록"
                        } else step.title
                        step.copy(
                            title = newTitle,
                            photoUris = stepPhotos,
                            description = if (step.stepType == RouteStepType.PHOTO) {
                                "사진 ${stepPhotos.size}장 촬영"
                            } else step.description
                        )
                    }
                }

                // 스크린샷 제거 후 다이어리 제목에 잔존하던 '서울 방이동', 비정상 UI 단어 타이틀 자가치유 보정
                var newTitle = entry.title
                val isCorruptedTitle = newTitle.contains("기록 삭제") || newTitle.contains("저장하기") ||
                        newTitle.contains("상세 기록") || newTitle.contains("FIELD") ||
                        newTitle == "서울 방이동 일정" || newTitle == "서울 방이동" || newTitle.contains("사진 촬영") || newTitle == "사진 기록 일정"
                if (isCorruptedTitle) {
                    val validPlace = cleanSteps.firstOrNull { it.latitude != null && !it.locationName.isNullOrBlank() }?.locationName
                    val golfStep = cleanSteps.firstOrNull { it.stepType == RouteStepType.GOLF && com.autologue.app.data.sync.DailyRouteAggregator.isRealGolfClub(it.locationName ?: it.title) }
                    val cleanGolf = golfStep?.let { (it.locationName ?: it.title).replace(" 라운드", "").replace(" 라운딩", "") }
                    newTitle = when {
                        cleanGolf != null && validPlace != null -> "$cleanGolf 라운딩 & $validPlace"
                        cleanGolf != null -> "$cleanGolf 라운딩"
                        validPlace != null -> "${validPlace} 일정"
                        cleanPhotos.isNotEmpty() -> "오늘의 사진 기록"
                        else -> "${d.monthValue}월 ${d.dayOfMonth}일의 다이어리"
                    }
                }

                if (cleanSteps.size != entry.routeSteps.size ||
                    cleanPhotos.size != entry.photoUris.size ||
                    cleanTags.size != entry.tags.size ||
                    newTitle != entry.title
                ) {
                    val updated = entry.copy(
                        title = newTitle,
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
