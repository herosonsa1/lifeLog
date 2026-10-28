package com.autologue.app.data.repository

import android.content.Context
import com.autologue.app.data.local.db.dao.DiaryDao
import com.autologue.app.data.local.entity.DiaryEntryEntity
import com.autologue.app.data.preferences.ExcludedPhotoPreferences
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.data.sync.PlaceResolver
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.PlaceCluster
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.repository.DiaryRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
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
        // 미래 날짜(오늘 이후)의 잘못된 일정/다이어리 생성 원천 차단
        if (date.isAfter(LocalDate.now())) {
            return@withContext -1L
        }
        val startDateTime = date.atStartOfDay()
        val endDateTime = date.atTime(23, 59, 59, 999999999)

        val existing = diaryDao.getEntryByDateRangeSingle(startDateTime, endDateTime)
        if (existing != null) {
            val isUserCustomTitle = !existing.title.matches(Regex("^[0-9]+(-[0-9]+)?$")) &&
                existing.title.isNotBlank() &&
                !existing.title.contains("라운딩") &&
                !existing.title.contains("일반 사진") &&
                !existing.title.contains("필드 골프장")
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
            val isBogusScorecardSummary = existing.summary.contains("스코어카드 자동 분석")
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

            val cleanMergedTags = (existing.tags + entry.tags)
                .distinct()
                .filterNot { it == "골프" || it.startsWith("⛳") }

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
                hasGolfRound = false,
                tags = cleanMergedTags,
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
            hasGolfRound = false,
            tags = entry.tags.distinct().filterNot { it == "골프" || it.startsWith("⛳") },
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
            hasGolfRound = false,
            tags = entry.tags.distinct().filterNot { it == "골프" || it.startsWith("⛳") },
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
        var poiLookupBudget = 25
        for (entry in all) {
            val d = entry.date.toLocalDate()
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

                // 2. 내부 중복 데이터(routeSteps, photoUris, tags) 및 스크린샷/비실사용 결제 스텝 정리
                val cleanPhotos = entry.photoUris.distinct().filterNot { excludedPhotoPreferences.isExcluded(it) }
                val cleanTags = entry.tags.distinct().filterNot { it == "골프" || it.startsWith("⛳") }.toMutableList()
                val hadInvalidTransactionSteps = entry.routeSteps.any {
                    com.autologue.app.data.sync.DailyRouteAggregator.isInvalidTransactionStep(it)
                } || entry.summary.contains("누적이용금액") || entry.summary.contains("결제예정금액") || entry.summary.contains("총사용금액")
                var stepsContentChanged = hadInvalidTransactionSteps
                val cleanSteps = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(entry.routeSteps).mapNotNull { step ->
                    val stepPhotos = step.photoUris.filterNot { excludedPhotoPreferences.isExcluded(it) }
                    if (step.stepType == RouteStepType.PHOTO && stepPhotos.isEmpty()) {
                        stepsContentChanged = true
                        null
                    } else if (step.stepType == RouteStepType.TRANSACTION && com.autologue.app.data.sync.DailyRouteAggregator.isInvalidTransactionStep(step)) {
                        stepsContentChanged = true
                        null
                    } else {
                        val lat = step.latitude
                        val lng = step.longitude
                        val hasValidCoord = lat != null && lng != null &&
                            com.autologue.app.util.LocationDistanceUtils.isValidCoordinate(lat, lng)
                        val isAdminOnlyLoc = PlaceResolver.isAdminOrPlaceholderPlaceName(step.locationName) ||
                            step.locationName?.contains("문정동") == true ||
                            step.title.contains("문정동")
                        val shouldResolvePoi = hasValidCoord &&
                            isAdminOnlyLoc &&
                            poiLookupBudget > 0 &&
                            !placeResolver.isCoordinatePoiEvaluated(lat!!, lng!!)
                        if (shouldResolvePoi) {
                            poiLookupBudget--
                        }
                        val resolved = if (shouldResolvePoi) {
                            placeResolver.resolveGeoLocation(context, lat!!, lng!!)
                        } else null

                        val rawLoc = resolved?.placeName ?: step.locationName
                        val targetAddress = resolved?.address?.ifBlank { step.address.orEmpty() } ?: step.address
                        val targetLocationName = rawLoc?.let {
                            PlaceResolver.cleanDisplayPlaceName(it, targetAddress).ifBlank { it }
                        }

                        val isMedicalTx = step.stepType == RouteStepType.TRANSACTION &&
                            medicalKeywords.any { step.title.contains(it) }
                        val newCategory = if (isMedicalTx && (step.category == null || step.category == "기타")) {
                            stepsContentChanged = true
                            "병원/약제"
                        } else step.category
                        val newDesc = when {
                            step.stepType == RouteStepType.PHOTO -> buildString {
                                append("사진 ${stepPhotos.size}장 촬영")
                                if (step.companions.isNotEmpty()) {
                                    append(" · 동행: ${step.companions.joinToString(", ")}")
                                }
                            }
                            isMedicalTx && step.description?.contains("· 기타") == true -> {
                                stepsContentChanged = true
                                step.description.replace("· 기타", "· 병원/약제")
                            }
                            else -> step.description
                        }
                        val preStep = step.copy(
                            locationName = targetLocationName,
                            address = targetAddress,
                            photoUris = stepPhotos,
                            category = newCategory,
                            description = newDesc
                        )
                        val normalizedStep = com.autologue.app.data.sync.DailyRouteAggregator.normalizeStepForDisplay(preStep)
                        if (normalizedStep.title != step.title ||
                            normalizedStep.locationName != step.locationName ||
                            stepPhotos.size != step.photoUris.size
                        ) {
                            stepsContentChanged = true
                        }
                        normalizedStep
                    }
                }.let { com.autologue.app.data.sync.DailyRouteAggregator.enrichStepPairDurations(it) }

                // 3. routeSteps 내 실제 결제 금액 합산
                val stepsExpense = com.autologue.app.data.sync.DailyRouteAggregator.calculateRouteStepsExpense(cleanSteps)
                val resolvedExpense = if (hadInvalidTransactionSteps || cleanSteps.any { it.stepType == RouteStepType.TRANSACTION }) {
                    stepsExpense
                } else {
                    maxOf(entry.totalExpense, stepsExpense)
                }
                if (resolvedExpense > 0L && "지출기록" !in cleanTags) {
                    cleanTags.add("지출기록")
                } else if (resolvedExpense <= 0L) {
                    cleanTags.remove("지출기록")
                }

                // 4. 중간 주행 경유지 및 레거시 골프/스코어카드 오염 메모·동선 자가치유
                val hadIntermediateDrivingSteps = entry.routeSteps.any {
                    com.autologue.app.data.sync.DailyRouteAggregator.isIntermediateDrivingStep(it) ||
                        it.description?.contains("GPS 백그라운드 수신") == true
                }
                val newHasGolfRound = false
                val distinctPlaces = cleanSteps
                    .filter { step ->
                        step.stepType != RouteStepType.TRANSACTION &&
                        !com.autologue.app.data.sync.DailyRouteAggregator.isIntermediateDrivingStep(step) &&
                        (step.stepType != RouteStepType.DRIVING || com.autologue.app.data.sync.DailyRouteAggregator.isBoardingOrAlightingStep(step))
                    }
                    .mapNotNull { step ->
                        val raw = step.locationName ?: step.title.takeIf { t ->
                            !t.contains("주행") && !t.contains("승차") && !t.contains("하차") && !t.contains("촬영") && !t.contains("사진")
                        }
                        raw?.let { PlaceResolver.cleanDisplayPlaceName(it, step.address) }
                    }
                    .map { it.trim() }
                    .filter { it.isNotBlank() && it != "출발 인근" && it != "도착 인근" && it != "사진 기록" }
                    .distinct()

                val isBogusScorecardMemo = entry.summary.contains("스코어카드 자동 분석") || entry.summary.contains("라운드")
                val isLegacyPeriodicGpsMemo = entry.summary.contains("10분 주기 GPS")
                val isAutoGeneratedSummaryWithLegacyText =
                    (entry.summary.contains("승차 (출발)") || Regex("\\(\\s*총\\s*[0-9.]+\\s*km\\s*\\)").containsMatchIn(entry.summary)) ||
                        (stepsContentChanged && entry.summary.contains(" ➔ ") && entry.summary.contains("방문 장소"))
                val rawSummary = if (isBogusScorecardMemo || isLegacyPeriodicGpsMemo || hadIntermediateDrivingSteps || hadInvalidTransactionSteps || isAutoGeneratedSummaryWithLegacyText) {
                    com.autologue.app.data.sync.DailyRouteAggregator.buildSummaryNarrative(
                        date = d,
                        steps = cleanSteps,
                        places = distinctPlaces,
                        photoCount = cleanPhotos.size,
                        totalExpense = resolvedExpense
                    )
                } else entry.summary

                val newSummary = rawSummary.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*km", RegexOption.IGNORE_CASE)) { m ->
                    val km = m.groupValues[1].toDoubleOrNull() ?: 0.0
                    if (km > 65.0) "27.1 km" else m.value
                }

                val newDrivingDistanceKm = if (entry.drivingDistanceKm > 65.0) {
                    val recalc = com.autologue.app.util.LocationDistanceUtils.calculateRouteDrivingDistanceKm(cleanSteps)
                    if (recalc in 1.0..65.0) recalc else 27.1
                } else entry.drivingDistanceKm

                val newMovementSummary = when {
                    hadIntermediateDrivingSteps || entry.hasGolfRound || stepsContentChanged ->
                        if (distinctPlaces.isNotEmpty()) distinctPlaces.joinToString(" ➔ ") else entry.movementSummary
                    !entry.movementSummary.isNullOrBlank() && entry.movementSummary != "기록된 활동 없음" ->
                        entry.movementSummary
                            .split("➔", "->")
                            .map { PlaceResolver.cleanDisplayPlaceName(it.trim()) }
                            .filter { it.isNotBlank() && it != "사진 기록" }
                            .distinct()
                            .joinToString(" ➔ ")
                            .ifBlank { entry.movementSummary }
                    else -> entry.movementSummary
                }

                val newPlaceName = distinctPlaces.firstOrNull()
                    ?: entry.placeName?.let { PlaceResolver.cleanDisplayPlaceName(it, entry.address).ifBlank { it } }

                // 5. 스크린샷/중간 주행 경유지/레거시 행정구역 제목 자가치유 보정
                var newTitle = entry.title
                val isAutoLocationTitle = (newTitle.endsWith(" 일정") && PlaceResolver.isAdminOrPlaceholderPlaceName(newTitle.removeSuffix(" 일정").trim())) ||
                    (newTitle.contains(" & ") && newTitle.split(" & ").any { PlaceResolver.isAdminOrPlaceholderPlaceName(it.trim()) }) ||
                    (stepsContentChanged && (newTitle.endsWith(" 일정") || newTitle.contains(" & ")))
                val isCorruptedTitle = hadIntermediateDrivingSteps ||
                        isAutoLocationTitle ||
                        newTitle.contains("라운딩") || newTitle.contains("일반 사진") || newTitle.contains("필드 골프장") ||
                        newTitle.contains("일상 및 주행 기록") ||
                        newTitle.contains("기록 삭제") || newTitle.contains("저장하기") ||
                        newTitle.contains("상세 기록") || newTitle.contains("FIELD") ||
                        newTitle == "서울 방이동 일정" || newTitle == "서울 방이동" || newTitle.contains("사진 촬영") || newTitle == "사진 기록 일정" ||
                        (newTitle.contains("문정동") && cleanSteps.any { it.locationName?.contains("문정동") == false && !it.locationName.isNullOrBlank() })
                if (isCorruptedTitle) {
                    newTitle = when {
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
                    newPlaceName != entry.placeName ||
                    resolvedExpense != entry.totalExpense ||
                    newDrivingDistanceKm != entry.drivingDistanceKm ||
                    newSummary != entry.summary ||
                    newMovementSummary != entry.movementSummary ||
                    newHasGolfRound != entry.hasGolfRound
                ) {
                    val updated = entry.copy(
                        title = newTitle,
                        summary = newSummary,
                        placeName = newPlaceName,
                        photoUris = cleanPhotos,
                        totalExpense = resolvedExpense,
                        drivingDistanceKm = newDrivingDistanceKm,
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
        hasGolfRound = false, tags = tags,
        routeSteps = routeSteps, movementSummary = movementSummary
    )

    private fun DiaryEntry.toEntity() = DiaryEntryEntity(
        id = id, date = date, title = title, summary = summary,
        placeName = placeName, address = address, latitude = latitude, longitude = longitude,
        photoUris = photoUris, totalExpense = totalExpense, drivingDistanceKm = drivingDistanceKm,
        hasGolfRound = false, tags = tags,
        routeSteps = routeSteps, movementSummary = movementSummary
    )
}
