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
import com.autologue.app.data.sync.PlaceResolver
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import javax.inject.Inject

class DiaryRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val diaryDao: DiaryDao,
    private val excludedPhotoPreferences: ExcludedPhotoPreferences,
    private val placeResolver: PlaceResolver
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
        // 미래 날짜(오늘 이후)의 잘못된 일정/다이어리 생성 원천 차단 (예: 10/24 나인CC 등)
        if (date.isAfter(LocalDate.now())) {
            return@withContext -1L
        }
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
            val stepsExpense = com.autologue.app.data.sync.DailyRouteAggregator.calculateRouteStepsExpense(mergedSteps)
            val resolvedExpense = maxOf(entry.totalExpense, existing.totalExpense, stepsExpense)
            val hasRealGolf = (entry.hasGolfRound || existing.hasGolfRound) &&
                mergedSteps.any { it.stepType == RouteStepType.GOLF && com.autologue.app.data.sync.DailyRouteAggregator.isRealGolfClub(it.locationName ?: it.title) }
            val isBogusScorecardSummary = existing.summary.contains("스코어카드 자동 분석") && !hasRealGolf
            val resolvedSummary = when {
                isBogusScorecardSummary && entry.summary.isNotBlank() && !entry.summary.contains("스코어카드 자동 분석") -> entry.summary
                isBogusScorecardSummary -> com.autologue.app.data.sync.DailyRouteAggregator.buildSummaryNarrative(
                    date = date,
                    steps = mergedSteps,
                    places = emptyList(),
                    photoCount = mergedPhotos.size,
                    totalExpense = resolvedExpense
                )
                existing.summary.isNotBlank() -> existing.summary
                else -> entry.summary
            }

            val merged = existing.copy(
                title = if (isUserCustomTitle) existing.title else entry.title,
                summary = resolvedSummary,
                placeName = entry.placeName ?: existing.placeName,
                address = entry.address ?: existing.address,
                latitude = entry.latitude ?: existing.latitude,
                longitude = entry.longitude ?: existing.longitude,
                photoUris = mergedPhotos,
                totalExpense = resolvedExpense,
                drivingDistanceKm = if (entry.drivingDistanceKm > 0) entry.drivingDistanceKm else existing.drivingDistanceKm,
                hasGolfRound = hasRealGolf,
                tags = (existing.tags + entry.tags).distinct(),
                routeSteps = mergedSteps,
                movementSummary = entry.movementSummary ?: existing.movementSummary
            )
            diaryDao.updateEntry(merged)
            return@withContext existing.id
        }
        val cleanSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).mapNotNull { step ->
            val photos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
            if (step.stepType == RouteStepType.PHOTO && photos.isEmpty()) {
                null
            } else {
                step.copy(photoUris = photos)
            }
        }
        val initialExpense = maxOf(
            entry.totalExpense,
            com.autologue.app.data.sync.DailyRouteAggregator.calculateRouteStepsExpense(cleanSteps)
        )
        val cleanEntry = entry.copy(
            photoUris = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) },
            totalExpense = initialExpense,
            tags = entry.tags.distinct(),
            routeSteps = cleanSteps
        )
        diaryDao.insertEntry(cleanEntry.toEntity())
    }

    override suspend fun updateDiaryEntry(entry: DiaryEntry) = withContext(Dispatchers.IO) {
        val cleanSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).mapNotNull { step ->
            val photos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
            if (step.stepType == RouteStepType.PHOTO && photos.isEmpty()) {
                null
            } else {
                step.copy(photoUris = photos)
            }
        }
        val resolvedExpense = maxOf(
            entry.totalExpense,
            com.autologue.app.data.sync.DailyRouteAggregator.calculateRouteStepsExpense(cleanSteps)
        )
        val cleanEntry = entry.copy(
            photoUris = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) },
            totalExpense = resolvedExpense,
            tags = entry.tags.distinct(),
            routeSteps = cleanSteps
        )
        diaryDao.updateEntry(cleanEntry.toEntity())
    }

    override suspend fun deleteDiaryEntry(id: Long) = withContext(Dispatchers.IO) {
        diaryDao.deleteEntryById(id)
    }

    override suspend fun cleanDuplicates(): Int = withContext(Dispatchers.IO) {
        val all = diaryDao.getAllEntriesSync()
        val seen = mutableSetOf<LocalDate>()
        val today = LocalDate.now()
        val medicalKeywords = listOf(
            "병원", "의원", "약국", "치과", "한의원", "내과", "외과", "정형외과",
            "피부과", "안과", "이비인후과", "산부인과", "소아과", "비뇨기과", "신경외과",
            "재활의학과", "마취통증", "검진센터", "메디컬", "클리닉"
        )
        var modifiedCount = 0
        for (entry in all) {
            val d = entry.date.toLocalDate()
            // 미래 날짜(10/24 등)에 잘못 생성된 다이어리 엔트리 또는 '나인CC' 단독 오탐 다이어리 즉시 삭제
            val isFakeNineCcOnly = (entry.title.contains("나인CC") || entry.title.contains("나인 CC")) &&
                entry.routeSteps.all { it.title.contains("나인CC") || it.title.contains("나인 CC") }
            if (d.isAfter(today) || isFakeNineCcOnly || d in seen) {
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
                val cleanTags = entry.tags.distinct().toMutableList()
                var stepsContentChanged = false
                val cleanSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).mapNotNull { step ->
                    val stepPhotos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
                    // 사진 전용 스텝인데 사진이 0장이 된 경우(스크린샷만 있던 스텝 등) 스텝 자체 제거
                    if (step.stepType == RouteStepType.PHOTO && stepPhotos.isEmpty()) {
                        stepsContentChanged = true
                        null
                    } else if (step.stepType == RouteStepType.GOLF && com.autologue.app.data.sync.DailyRouteAggregator.isInvalidOrDummyGolfStep(step)) {
                        stepsContentChanged = true
                        null
                    } else {
                        val lat = step.latitude
                        val lng = step.longitude
                        val needsGeoRefresh = (step.locationName?.contains("문정동") == true || step.title.contains("문정동")) &&
                                lat != null && lng != null && lat != 0.0 && lng != 0.0
                        val resolved = if (needsGeoRefresh) placeResolver.resolveGeoLocation(context, lat!!, lng!!) else null
                        val targetLocationName = resolved?.placeName ?: step.locationName
                        val targetAddress = resolved?.address ?: step.address

                        val newTitle = when {
                            needsGeoRefresh && resolved != null -> resolved.placeName
                            step.title == "서울 방이동" && step.stepType == RouteStepType.PHOTO -> targetLocationName ?: "사진 기록"
                            else -> step.title
                        }
                        val isMedicalTx = step.stepType == RouteStepType.TRANSACTION &&
                            medicalKeywords.any { step.title.contains(it) }
                        val newCategory = if (isMedicalTx && (step.category == null || step.category == "기타")) {
                            stepsContentChanged = true
                            "병원/약제"
                        } else step.category
                        val newDesc = when {
                            step.stepType == RouteStepType.PHOTO -> "사진 ${stepPhotos.size}장 촬영"
                            isMedicalTx && step.description?.contains("· 기타") == true -> {
                                stepsContentChanged = true
                                step.description.replace("· 기타", "· 병원/약제")
                            }
                            else -> step.description
                        }
                        if (newTitle != step.title || targetLocationName != step.locationName || stepPhotos.size != step.photoUris.size) {
                            stepsContentChanged = true
                        }
                        step.copy(
                            title = newTitle,
                            locationName = targetLocationName,
                            address = targetAddress,
                            photoUris = stepPhotos,
                            category = newCategory,
                            description = newDesc
                        )
                    }
                }

                // 3. routeSteps 내 실제 결제 금액 합산으로 totalExpense 0원 누락 복원
                val stepsExpense = com.autologue.app.data.sync.DailyRouteAggregator.calculateRouteStepsExpense(cleanSteps)
                val resolvedExpense = maxOf(entry.totalExpense, stepsExpense)
                if (resolvedExpense > 0L && "지출기록" !in cleanTags) {
                    cleanTags.add("지출기록")
                }

                // 4. 실제 골프 라운드 스텝 유무 확인 및 병원 영수증/중간 주행 경유지로 오염된 메모·동선 자가치유
                val hadIntermediateDrivingSteps = entry.routeSteps.any {
                    com.autologue.app.data.sync.DailyRouteAggregator.isIntermediateDrivingStep(it) ||
                        it.description?.contains("GPS 백그라운드 수신") == true
                }
                val hasRealGolf = cleanSteps.any {
                    it.stepType == RouteStepType.GOLF && com.autologue.app.data.sync.DailyRouteAggregator.isRealGolfClub(it.locationName ?: it.title)
                }
                val newHasGolfRound = entry.hasGolfRound && hasRealGolf
                if (!newHasGolfRound) {
                    cleanTags.removeAll { it == "골프" || it.startsWith("⛳") }
                }
                val distinctPlaces = cleanSteps
                    .filter { step ->
                        step.stepType != RouteStepType.TRANSACTION &&
                        !com.autologue.app.data.sync.DailyRouteAggregator.isIntermediateDrivingStep(step) &&
                        (step.stepType != RouteStepType.DRIVING || com.autologue.app.data.sync.DailyRouteAggregator.isBoardingOrAlightingStep(step))
                    }
                    .mapNotNull { it.locationName ?: it.title.takeIf { t -> !t.contains("주행") && !t.contains("승차") && !t.contains("하차") && !t.contains("촬영") && !t.contains("사진") } }
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != "출발 인근" && it != "도착 인근" && it != "사진 기록" }
                    .distinct()

                val isBogusScorecardMemo = entry.summary.contains("스코어카드 자동 분석") && !hasRealGolf
                val isLegacyPeriodicGpsMemo = entry.summary.contains("10분 주기 GPS")
                val newSummary = if (isBogusScorecardMemo || isLegacyPeriodicGpsMemo || hadIntermediateDrivingSteps) {
                    com.autologue.app.data.sync.DailyRouteAggregator.buildSummaryNarrative(
                        date = d,
                        steps = cleanSteps,
                        places = distinctPlaces,
                        photoCount = cleanPhotos.size,
                        totalExpense = resolvedExpense
                    )
                } else entry.summary

                val newMovementSummary = if (hadIntermediateDrivingSteps) {
                    if (distinctPlaces.isNotEmpty()) distinctPlaces.joinToString(" ➔ ") else "기록된 활동 없음"
                } else entry.movementSummary

                // 5. 스크린샷/중간 주행 경유지 제거 후 다이어리 제목 자가치유 보정
                var newTitle = entry.title
                val isCorruptedTitle = hadIntermediateDrivingSteps ||
                        newTitle.contains("일상 및 주행 기록") ||
                        newTitle.contains("기록 삭제") || newTitle.contains("저장하기") ||
                        newTitle.contains("상세 기록") || newTitle.contains("FIELD") ||
                        newTitle == "서울 방이동 일정" || newTitle == "서울 방이동" || newTitle.contains("사진 촬영") || newTitle == "사진 기록 일정" ||
                        (newTitle.contains("문정동") && cleanSteps.any { it.locationName?.contains("문정동") == false && !it.locationName.isNullOrBlank() })
                if (isCorruptedTitle) {
                    val golfStep = cleanSteps.firstOrNull { it.stepType == RouteStepType.GOLF && com.autologue.app.data.sync.DailyRouteAggregator.isRealGolfClub(it.locationName ?: it.title) }
                    val cleanGolf = golfStep?.let { (it.locationName ?: it.title).replace(" 라운드", "").replace(" 라운딩", "") }
                    newTitle = when {
                        cleanGolf != null && distinctPlaces.isNotEmpty() -> "$cleanGolf 라운딩 & ${distinctPlaces.first()}"
                        cleanGolf != null -> "$cleanGolf 라운딩"
                        distinctPlaces.size >= 2 -> "${distinctPlaces.first()} & ${distinctPlaces.last()}"
                        distinctPlaces.size == 1 -> "${distinctPlaces.first()} 일정"
                        cleanPhotos.isNotEmpty() -> "오늘의 사진 기록"
                        else -> "${d.monthValue}월 ${d.dayOfMonth}일의 다이어리"
                    }
                }

                if (cleanSteps.size != entry.routeSteps.size ||
                    stepsContentChanged ||
                    hadIntermediateDrivingSteps ||
                    cleanPhotos.size != entry.photoUris.size ||
                    cleanTags != entry.tags ||
                    newTitle != entry.title ||
                    resolvedExpense != entry.totalExpense ||
                    newSummary != entry.summary ||
                    newMovementSummary != entry.movementSummary ||
                    newHasGolfRound != entry.hasGolfRound
                ) {
                    val updated = entry.copy(
                        title = newTitle,
                        summary = newSummary,
                        photoUris = cleanPhotos,
                        totalExpense = resolvedExpense,
                        hasGolfRound = newHasGolfRound,
                        tags = cleanTags,
                        routeSteps = cleanSteps,
                        movementSummary = newMovementSummary
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
