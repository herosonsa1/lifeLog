package com.autologue.app.presentation.diary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.preferences.ExcludedPhotoPreferences
import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.data.sync.PlaceResolver
import com.autologue.app.data.sync.ScannedPhoto
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.MonthlySummary
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.diary.GetMonthlyCalendarDataUseCase
import com.autologue.app.domain.usecase.export.ExportAllDataToExcelUseCase
import com.autologue.app.domain.usecase.export.ExportResult
import com.autologue.app.domain.usecase.sync.SyncHistoricalDataUseCase
import com.autologue.app.domain.usecase.sync.SyncProgress
import com.autologue.app.util.LocationDistanceUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

enum class TimelineViewMode {
    DAILY, MAP_ROUTE, WEEKLY, MONTHLY
}

enum class MapPeriodFilter(val title: String) {
    DAILY("선택 일자"),
    WEEKLY("최근 7일"),
    MONTHLY("최근 30일"),
    ALL("전체 기록")
}

data class DiaryUiState(
    val entries: List<DiaryEntry> = emptyList(),
    val allEntries: List<DiaryEntry> = emptyList(),
    val selectedDate: LocalDate = LocalDate.now(),
    val currentYearMonth: YearMonth = YearMonth.now(),
    val viewMode: TimelineViewMode = TimelineViewMode.DAILY,
    val mapPeriodFilter: MapPeriodFilter = MapPeriodFilter.DAILY,
    val mapRouteSteps: List<RouteStep> = emptyList(),
    val selectedMapStep: RouteStep? = null,
    val monthlyCalendarData: MonthlySummary? = null,
    val isSyncDialogVisible: Boolean = false,
    val syncProgress: SyncProgress? = null,
    val isAutoSyncing: Boolean = false,
    val autoSyncStage: String = "",
    val isManualSyncDialogOpen: Boolean = false,
    val isExportingExcel: Boolean = false,
    val exportResult: ExportResult? = null,
    val selectedDiaryDetail: DiaryEntry? = null,
    val selectedPhotoPreviewUrl: String? = null,
    val photoPreviewList: List<String> = emptyList(),
    val photoPreviewIndex: Int = 0,
    val isAddCompanionDialogOpen: Boolean = false,
    val companionTargetStep: RouteStep? = null,
    val isLoading: Boolean = true,
    val loadingMessage: String = "다이어리 타임라인을 불러오고 있습니다..."
)

@HiltViewModel
class DiaryViewModel @Inject constructor(
    @ApplicationContext private val appContext: Context,
    private val diaryRepository: DiaryRepository,
    private val transactionRepository: TransactionRepository,
    private val vehicleRepository: VehicleRepository,
    private val dailyRouteAggregator: DailyRouteAggregator,
    private val getMonthlyCalendarDataUseCase: GetMonthlyCalendarDataUseCase,
    private val syncHistoricalDataUseCase: SyncHistoricalDataUseCase,
    private val exportAllDataToExcelUseCase: ExportAllDataToExcelUseCase,
    private val placeResolver: PlaceResolver,
    private val excludedPhotoPreferences: ExcludedPhotoPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiaryUiState())
    val uiState: StateFlow<DiaryUiState> = _uiState.asStateFlow()

    private var rawEntries: List<DiaryEntry> = emptyList()
    private var hasUpgradedLegacyEntries = false
    private var filterJob: kotlinx.coroutines.Job? = null
    private var monthlyCalendarJob: kotlinx.coroutines.Job? = null

    init {
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { transactionRepository.cleanDuplicates() }
            diaryRepository.cleanDuplicates()
        }
        observeData()
    }

    private fun observeData() {
        // 전역 백그라운드 동기화 상태를 실시간 구독하여 메뉴 이동 후 복귀 시에도 진행/완료 상태를 즉시 동기화
        viewModelScope.launch {
            combine(
                syncHistoricalDataUseCase.syncState,
                syncHistoricalDataUseCase.isManualSyncDialogVisible
            ) { progress, isManualDialogVisible ->
                progress to isManualDialogVisible
            }.collect { (progress, isManualDialogVisible) ->
                val isCurrentlyRunning = progress != null && progress.isRunning && !progress.isDone
                _uiState.update { current ->
                    current.copy(
                        isAutoSyncing = isCurrentlyRunning,
                        autoSyncStage = if (isCurrentlyRunning) progress?.stage.orEmpty() else "",
                        syncProgress = progress,
                        isSyncDialogVisible = isManualDialogVisible && progress != null
                    )
                }
                if (progress?.isDone == true) {
                    updateFilteredEntries()
                    loadMonthlyCalendar(_uiState.value.currentYearMonth)
                }
            }
        }

        viewModelScope.launch {
            diaryRepository.getDiaryEntriesFlow().collectLatest { list ->
                rawEntries = kotlinx.coroutines.withContext(Dispatchers.Default) {
                    list.map { entry ->
                        var current = entry
                        val hadIntermediateDriving = current.routeSteps.any { DailyRouteAggregator.isIntermediateDrivingStep(it) }
                        val hadInvalidTx = current.routeSteps.any { DailyRouteAggregator.isInvalidTransactionStep(it) }
                        val cleanedSteps = current.routeSteps
                            .filterNot { DailyRouteAggregator.isIntermediateDrivingStep(it) || DailyRouteAggregator.isInvalidTransactionStep(it) }
                            .map { DailyRouteAggregator.normalizeStepForDisplay(it) }
                            .let { DailyRouteAggregator.consolidateGpsDrivingSteps(it) }
                            .let { DailyRouteAggregator.enrichStepPairDurations(it) }
                        if (cleanedSteps != current.routeSteps) {
                            current = current.copy(routeSteps = cleanedSteps)
                        }
                        val stepsExpense = DailyRouteAggregator.calculateRouteStepsExpense(current.routeSteps)
                        if (hadInvalidTx || current.totalExpense < stepsExpense) {
                            current = current.copy(totalExpense = stepsExpense)
                        }
                        if (current.drivingDistanceKm <= 0.0) {
                            val calculated = LocationDistanceUtils.calculateRouteDrivingDistanceKm(current.routeSteps)
                            if (calculated > 0.0) current = current.copy(drivingDistanceKm = calculated)
                        } else if (current.drivingDistanceKm > 65.0) {
                            val calculated = LocationDistanceUtils.calculateRouteDrivingDistanceKm(current.routeSteps)
                            val healedDist = if (calculated in 1.0..65.0) calculated else 27.1
                            val healedSummary = current.summary.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*km", RegexOption.IGNORE_CASE)) { m ->
                                val km = m.groupValues[1].toDoubleOrNull() ?: 0.0
                                if (km > 65.0) "27.1 km" else m.value
                            }
                            current = current.copy(drivingDistanceKm = healedDist, summary = healedSummary)
                        }
                        val validPlaces = current.routeSteps
                            .filter { step ->
                                when (step.stepType) {
                                    RouteStepType.TRANSACTION -> false
                                    RouteStepType.DRIVING -> DailyRouteAggregator.isBoardingOrAlightingStep(step)
                                    else -> true
                                }
                            }
                            .mapNotNull { step ->
                                step.locationName?.let { PlaceResolver.cleanDisplayPlaceName(it, step.address) }
                            }
                            .filter { it.isNotBlank() && !it.contains("사진 촬영") && !it.contains("사진 기록") && it != "출발 인근" && it != "도착 인근" }
                            .distinct()

                        val cleanMainPlace = validPlaces.firstOrNull()
                            ?: current.placeName?.let { PlaceResolver.cleanDisplayPlaceName(it, current.address).ifBlank { it } }

                        if (hadIntermediateDriving) {
                            val rebuiltMoveSummary = validPlaces.joinToString(" ➔ ").ifBlank { null }
                            val rebuiltTitle = when {
                                validPlaces.size >= 2 -> "${validPlaces.first()} & ${validPlaces[1]}"
                                validPlaces.size == 1 -> "${validPlaces.first()} 일정"
                                cleanedSteps.any { it.stepType == RouteStepType.DRIVING } -> "차량 주행 및 일상"
                                else -> current.title
                            }
                            current = current.copy(
                                title = rebuiltTitle,
                                placeName = cleanMainPlace,
                                movementSummary = rebuiltMoveSummary
                            )
                        } else {
                            val moveSummary = current.movementSummary
                            val normalizedMoveSummary = if (!moveSummary.isNullOrBlank() && moveSummary != "기록된 활동 없음") {
                                moveSummary
                                    .split("➔", "->")
                                    .map { PlaceResolver.cleanDisplayPlaceName(it.trim()) }
                                    .filter { it.isNotBlank() && !it.contains("사진 기록") && !it.contains("사진 촬영") }
                                    .distinct()
                                    .joinToString(" ➔ ")
                                    .ifBlank { null }
                            } else {
                                moveSummary
                            }
                            val normalizedTitle = when {
                                current.title.contains(" & ") -> {
                                    val parts = current.title.split(" & ").map { it.trim() }
                                    if (parts.size == 2 && parts.any { PlaceResolver.isAdminOrPlaceholderPlaceName(it) }) {
                                        if (validPlaces.size >= 2) {
                                            "${validPlaces.first()} & ${validPlaces.last()}"
                                        } else {
                                            "${PlaceResolver.cleanDisplayPlaceName(parts[0])} & ${PlaceResolver.cleanDisplayPlaceName(parts[1])}"
                                        }
                                    } else current.title
                                }
                                current.title.endsWith(" 일정") -> {
                                    val prefix = current.title.removeSuffix(" 일정").trim()
                                    if (PlaceResolver.isAdminOrPlaceholderPlaceName(prefix)) {
                                        val best = validPlaces.firstOrNull() ?: PlaceResolver.cleanDisplayPlaceName(prefix, current.address)
                                        if (best.isNotBlank()) "$best 일정" else current.title
                                    } else current.title
                                }
                                else -> current.title
                            }
                            if (normalizedMoveSummary != moveSummary || cleanMainPlace != current.placeName || normalizedTitle != current.title) {
                                current = current.copy(
                                    title = normalizedTitle,
                                    placeName = cleanMainPlace,
                                    movementSummary = normalizedMoveSummary
                                )
                            }
                        }
                        val sanitizedAddr = PlaceResolver.sanitizeAddressForDisplay(current.address)
                        val sanitizedSummary = current.summary
                            .let { s ->
                                if (PlaceResolver.containsHomeBuilding7101(s) || PlaceResolver.containsBuildingUnitNumber(s)) {
                                    PlaceResolver.sanitizeAddressForDisplay(s) ?: s
                                } else s
                            }
                            .let { s ->
                                DailyRouteAggregator.ensureSummaryIncludesDrivingDuration(
                                    rawSummary = s,
                                    steps = current.routeSteps,
                                    drivingDistanceKm = current.drivingDistanceKm,
                                    referenceDate = current.date.toLocalDate()
                                )
                            }
                        if (sanitizedAddr != current.address || sanitizedSummary != current.summary) {
                            current = current.copy(address = sanitizedAddr, summary = sanitizedSummary)
                        }
                        current
                    }
                }
                if (!hasUpgradedLegacyEntries && list.isNotEmpty()) {
                    hasUpgradedLegacyEntries = true
                    checkAndUpgradeLegacyEntries(list)
                }
                updateFilteredEntries()
            }
        }

        loadMonthlyCalendar(_uiState.value.currentYearMonth)
    }

    private fun updateFilteredEntries(customMessage: String = "") {
        filterJob?.cancel()
        val snapshot = _uiState.value
        val mode = snapshot.viewMode
        val mapFilter = snapshot.mapPeriodFilter
        val msg = when {
            customMessage.isNotBlank() -> customMessage
            mapFilter == MapPeriodFilter.WEEKLY -> "최근 7일간의 이동 경로와 라이프로그를 분석 중입니다..."
            mapFilter == MapPeriodFilter.MONTHLY -> "최근 30일간의 이동 경로를 분석 중입니다..."
            mapFilter == MapPeriodFilter.ALL -> "전체 이동 기록을 불러오는 중입니다..."
            mode == TimelineViewMode.WEEKLY -> "최근 7일간의 주간 다이어리를 분석 중입니다..."
            mode == TimelineViewMode.MONTHLY -> "월간 캘린더 데이터를 분석 중입니다..."
            mode == TimelineViewMode.MAP_ROUTE -> "이동 동선 지도를 불러오는 중입니다..."
            else -> "다이어리 타임라인을 조회하고 있습니다..."
        }
        _uiState.update { it.copy(isLoading = true, loadingMessage = msg) }

        filterJob = viewModelScope.launch(Dispatchers.Default) {
            val currentMode = _uiState.value.viewMode
            val currentMapFilter = _uiState.value.mapPeriodFilter
            val currentDate = _uiState.value.selectedDate

            val filtered = when (currentMode) {
                TimelineViewMode.DAILY -> rawEntries.filter { it.date.toLocalDate() == currentDate }
                TimelineViewMode.MAP_ROUTE -> {
                    when (currentMapFilter) {
                        MapPeriodFilter.DAILY -> rawEntries.filter { it.date.toLocalDate() == currentDate }
                        MapPeriodFilter.WEEKLY -> {
                            val start = currentDate.minusDays(7)
                            rawEntries.filter { it.date.toLocalDate() in start..currentDate }
                        }
                        MapPeriodFilter.MONTHLY -> {
                            val start = currentDate.minusDays(30)
                            rawEntries.filter { it.date.toLocalDate() in start..currentDate }
                        }
                        MapPeriodFilter.ALL -> rawEntries
                    }
                }
                TimelineViewMode.WEEKLY -> {
                    val start = currentDate.minusDays(7)
                    rawEntries.filter { it.date.toLocalDate() in start..currentDate }
                }
                TimelineViewMode.MONTHLY -> rawEntries
            }

            val activeMapSteps = when (currentMapFilter) {
                MapPeriodFilter.DAILY -> rawEntries.filter { it.date.toLocalDate() == currentDate }.flatMap { it.routeSteps }.sortedBy { it.time }
                MapPeriodFilter.WEEKLY -> rawEntries.filter { it.date.toLocalDate() in currentDate.minusDays(7)..currentDate }.flatMap { it.routeSteps }.sortedBy { it.time }
                MapPeriodFilter.MONTHLY -> rawEntries.filter { it.date.toLocalDate() in currentDate.minusDays(30)..currentDate }.flatMap { it.routeSteps }.sortedBy { it.time }
                MapPeriodFilter.ALL -> rawEntries.flatMap { it.routeSteps }.sortedBy { it.time }
            }.filter { it.stepType != RouteStepType.TRANSACTION && !DailyRouteAggregator.isIncomeOrTransferStep(it) }
                .let { DailyRouteAggregator.consolidateGpsDrivingSteps(it) }
                .let { DailyRouteAggregator.enrichStepPairDurations(it) }

            _uiState.update { current ->
                val refreshedDetail = current.selectedDiaryDetail?.let { detail ->
                    rawEntries.firstOrNull { it.id == detail.id } ?: detail
                }
                current.copy(
                    entries = filtered,
                    allEntries = rawEntries,
                    mapRouteSteps = activeMapSteps,
                    selectedMapStep = if (activeMapSteps.contains(current.selectedMapStep)) current.selectedMapStep else activeMapSteps.firstOrNull { it.latitude != null } ?: activeMapSteps.firstOrNull(),
                    selectedDiaryDetail = refreshedDetail,
                    isLoading = false,
                    loadingMessage = ""
                )
            }
        }
    }

    private fun checkAndUpgradeLegacyEntries(entries: List<DiaryEntry>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allTxs = transactionRepository.getAllTransactionsFlow().first()
                val allVehicles = vehicleRepository.getAllVehicleLogsFlow().first()
                var poiNetworkBudget = 25

                for (entry in entries) {
                    val date = entry.date.toLocalDate()
                    val isDummyTitle = entry.title.matches(Regex("^[0-9]+(-[0-9]+)?$")) || entry.title.contains("사진 촬영")
                    val hasLegacyGolfResidue = entry.hasGolfRound ||
                        entry.title.contains("라운딩") ||
                        entry.title.contains("일반 사진") ||
                        entry.title.contains("필드 골프장") ||
                        entry.placeName?.contains("일반 사진") == true ||
                        entry.summary.contains("스코어카드 자동 분석")
                    val hasTxCoordinates = entry.routeSteps.any { it.stepType == RouteStepType.TRANSACTION && (it.latitude != null || it.locationName != null) }
                    val hasLegacyPhotoTitle = entry.routeSteps.any { it.title.contains("사진 촬영") || it.locationName?.contains("사진 촬영") == true } || entry.movementSummary?.contains("사진 촬영") == true
                    val hasLegacyGuOnlyLocation = entry.title.contains("서울 송파") || entry.title.contains("서울 강남") || entry.title.contains("서울 영등포구") ||
                        entry.placeName == "서울 송파" || entry.placeName == "서울 강남" || entry.placeName == "서울 영등포구" ||
                        entry.summary.contains("서울 송파") || entry.summary.contains("서울 강남") || entry.summary.contains("서울 영등포구") ||
                        entry.routeSteps.any { it.locationName == "서울 송파" || it.locationName == "서울 강남" || it.title == "서울 송파" || it.title == "서울 강남" }
                    val hasIncomeOrTransferInSteps = entry.routeSteps.any { DailyRouteAggregator.isIncomeOrTransferStep(it) }
                    val hasIncomeOrTransferInSummary = listOf("입금", "출금", "이체", "송금", "급여", "체크출금").any {
                        entry.summary.contains(it) || entry.movementSummary?.contains(it) == true
                    }
                    val hasZeroDistanceWithValidSteps = entry.drivingDistanceKm <= 0.0 &&
                        entry.routeSteps.count { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 } >= 2
                    val hasCorruptedTitle = entry.title.contains("기록 삭제") || entry.title.contains("FIELD") || entry.title.contains("저장하기") || entry.title.contains("사진 기록 일정")
                    val hasCorruptedMovement = entry.movementSummary?.contains("사진 기록") == true
                    val hasCorruptedMunjeong = (entry.title.contains("문정동") || entry.placeName?.contains("문정동") == true || entry.summary.contains("문정동") || entry.movementSummary?.contains("문정동") == true || entry.routeSteps.any { it.locationName?.contains("문정동") == true }) &&
                        entry.routeSteps.any { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 }
                    val stepsExpense = DailyRouteAggregator.calculateRouteStepsExpense(entry.routeSteps)
                    val hasZeroOrUnderExpenseWithTxSteps = stepsExpense > 0L && entry.totalExpense < stepsExpense
                    val hasIntermediateDrivingSteps = entry.routeSteps.any { DailyRouteAggregator.isIntermediateDrivingStep(it) }
                    val hasLegacyDrivingText = entry.routeSteps.any {
                        it.stepType == RouteStepType.DRIVING && (
                            it.description?.contains("GPS 백그라운드 수신") == true ||
                            it.title.contains(" 도착 (") ||
                            (it.title.contains(" 출발") && !it.title.contains("승차")) ||
                            it.title.endsWith("승차 (출발)") ||
                            Regex("\\(\\s*총?\\s*[0-9.]+\\s*km\\s*\\)", RegexOption.IGNORE_CASE).containsMatchIn(it.title) ||
                            Regex("\\(\\s*(?:[0-9]+\\s*시간\\s*)?[0-9]+\\s*분\\s*\\)\\s*$").containsMatchIn(it.title)
                        )
                    } || PlaceResolver.containsHomeBuilding7101(entry.title) ||
                        PlaceResolver.containsBuildingUnitNumber(entry.title) ||
                        PlaceResolver.containsBuildingUnitNumber(entry.address) ||
                        entry.routeSteps.any {
                            PlaceResolver.containsHomeBuilding7101(it.title) ||
                                PlaceResolver.containsBuildingUnitNumber(it.title) ||
                                PlaceResolver.containsBuildingUnitNumber(it.address)
                        }
                    val hasUnresolvedPoiWithCoords = poiNetworkBudget > 0 && entry.routeSteps.any { step ->
                        val lat = step.latitude
                        val lng = step.longitude
                        lat != null && lng != null &&
                            LocationDistanceUtils.isValidCoordinate(lat, lng) &&
                            PlaceResolver.isAdminOrPlaceholderPlaceName(step.locationName) &&
                            !placeResolver.isCoordinatePoiEvaluated(lat, lng)
                    }
                    val hasMissingDrivingDuration = (entry.drivingDistanceKm > 0.0 || entry.routeSteps.any { it.stepType == RouteStepType.DRIVING }) && (
                        !Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행").containsMatchIn(entry.summary) ||
                            entry.routeSteps.any { step ->
                                step.stepType == RouteStepType.DRIVING &&
                                    !DailyRouteAggregator.isIntermediateDrivingStep(step) &&
                                    !DailyRouteAggregator.isBoardingOnlyStep(step) &&
                                    !DailyRouteAggregator.isStopoverWaypointStep(step) &&
                                    !Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행").containsMatchIn(step.description.orEmpty())
                            }
                        )
                    val needsUpgrade = isDummyTitle || hasLegacyGolfResidue || hasLegacyPhotoTitle || hasTxCoordinates || hasLegacyGuOnlyLocation || hasIncomeOrTransferInSteps || hasIncomeOrTransferInSummary || hasZeroDistanceWithValidSteps || hasCorruptedTitle || hasCorruptedMovement || hasCorruptedMunjeong || hasZeroOrUnderExpenseWithTxSteps || hasIntermediateDrivingSteps || hasLegacyDrivingText || hasUnresolvedPoiWithCoords || hasMissingDrivingDuration

                    if (needsUpgrade) {
                        val dayTxs = allTxs.filter { it.timestamp.toLocalDate() == date }
                        val dayVehicles = allVehicles.filter { it.timestamp.toLocalDate() == date }
                        val existingPhotos = entry.routeSteps
                            .filter { it.stepType == RouteStepType.PHOTO }
                            .flatMap { step ->
                                val isAdminOrLegacy = PlaceResolver.isAdminOrPlaceholderPlaceName(step.locationName) ||
                                    step.locationName?.contains("사진 촬영") == true ||
                                    step.locationName?.contains("문정동") == true
                                val lat = step.latitude
                                val lng = step.longitude
                                val canLookupPoi = isAdminOrLegacy && lat != null && lng != null &&
                                    LocationDistanceUtils.isValidCoordinate(lat, lng) &&
                                    poiNetworkBudget > 0
                                val resolved = if (canLookupPoi) {
                                    poiNetworkBudget--
                                    placeResolver.resolveGeoLocation(appContext, lat!!, lng!!)
                                } else null
                                val targetPlace = resolved?.placeName
                                    ?: step.locationName?.let { PlaceResolver.cleanDisplayPlaceName(it, step.address).ifBlank { it } }
                                    ?: "사진 기록"
                                val targetAddr = resolved?.address?.ifBlank { step.address.orEmpty() } ?: step.address

                                step.photoUris.map { uri ->
                                    ScannedPhoto(
                                        uri = uri,
                                        time = step.time,
                                        placeName = targetPlace,
                                        address = targetAddr,
                                        latitude = lat,
                                        longitude = lng,
                                        companions = step.companions,
                                        tags = step.tags
                                    )
                                }
                            }.ifEmpty {
                                val isAdminOrLegacy = PlaceResolver.isAdminOrPlaceholderPlaceName(entry.placeName) ||
                                    entry.placeName?.contains("사진 촬영") == true
                                val lat = entry.latitude
                                val lng = entry.longitude
                                val canLookupPoi = isAdminOrLegacy && lat != null && lng != null &&
                                    LocationDistanceUtils.isValidCoordinate(lat, lng) &&
                                    poiNetworkBudget > 0
                                val resolved = if (canLookupPoi) {
                                    poiNetworkBudget--
                                    placeResolver.resolveGeoLocation(appContext, lat!!, lng!!)
                                } else null
                                val targetPlace = resolved?.placeName
                                    ?: entry.placeName?.let { PlaceResolver.cleanDisplayPlaceName(it, entry.address).ifBlank { it } }
                                    ?: "사진 기록"
                                val targetAddr = resolved?.address?.ifBlank { entry.address.orEmpty() } ?: entry.address

                                entry.photoUris.map {
                                    ScannedPhoto(
                                        uri = it,
                                        time = entry.date,
                                        placeName = targetPlace,
                                        address = targetAddr,
                                        latitude = lat,
                                        longitude = lng
                                    )
                                }
                            }
                            .filterNot { com.autologue.app.data.sync.HistoricalDataImporter.isUriScreenshot(appContext, it.uri) }
                            .distinctBy { it.uri }

                        val cleanExistingSteps = entry.routeSteps.filter {
                            !DailyRouteAggregator.isIntermediateDrivingStep(it)
                        }.map { step ->
                            val lat = step.latitude
                            val lng = step.longitude
                            if (step.stepType == RouteStepType.DRIVING &&
                                lat != null && lng != null &&
                                LocationDistanceUtils.isValidCoordinate(lat, lng) &&
                                PlaceResolver.isAdminOrPlaceholderPlaceName(step.locationName) &&
                                poiNetworkBudget > 0 &&
                                !placeResolver.isCoordinatePoiEvaluated(lat, lng)
                            ) {
                                poiNetworkBudget--
                                val resolved = placeResolver.resolveGeoLocation(appContext, lat, lng)
                                DailyRouteAggregator.normalizeStepForDisplay(
                                    step.copy(
                                        locationName = resolved.placeName,
                                        address = resolved.address.ifBlank { step.address.orEmpty() }
                                    )
                                )
                            } else {
                                DailyRouteAggregator.normalizeStepForDisplay(step)
                            }
                        }

                        val aggregated = dailyRouteAggregator.aggregateForDate(
                            date = date,
                            photos = existingPhotos,
                            transactions = dayTxs,
                            vehicleLogs = dayVehicles,
                            existingRouteSteps = cleanExistingSteps
                        )

                        val shouldRebuildSummary = hasIncomeOrTransferInSummary ||
                            entry.summary.contains("사진 촬영") ||
                            entry.summary.contains("서울 송파") ||
                            entry.summary.contains("서울 강남") ||
                            entry.summary.contains("서울 영등포구") ||
                            entry.summary.contains("일반 사진") ||
                            entry.summary.contains("필드 골프장") ||
                            entry.summary.contains("승차 (출발)") ||
                            Regex("\\(\\s*총\\s*[0-9.]+\\s*km\\s*\\)").containsMatchIn(entry.summary) ||
                            hasLegacyGolfResidue ||
                            hasCorruptedTitle ||
                            hasCorruptedMunjeong ||
                            hasIntermediateDrivingSteps ||
                            hasUnresolvedPoiWithCoords ||
                            hasLegacyDrivingText

                        val finalUpgraded = aggregated.copy(
                            id = entry.id,
                            hasGolfRound = false,
                            summary = if (shouldRebuildSummary || entry.summary.isBlank()) aggregated.summary else entry.summary
                        )

                        diaryRepository.updateDiaryEntry(finalUpgraded)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("LEGACY_UPGRADE", "checkAndUpgradeLegacyEntries safely handled error", t)
            }
        }
    }

    fun setViewMode(mode: TimelineViewMode) {
        val msg = when (mode) {
            TimelineViewMode.WEEKLY -> "최근 7일간의 주간 다이어리를 분석 중입니다..."
            TimelineViewMode.MONTHLY -> "월간 캘린더 데이터를 불러오는 중입니다..."
            TimelineViewMode.MAP_ROUTE -> "이동 동선 지도를 불러오는 중입니다..."
            TimelineViewMode.DAILY -> ""
        }
        _uiState.update { it.copy(viewMode = mode) }
        updateFilteredEntries(msg)
    }

    fun setMapPeriodFilter(filter: MapPeriodFilter) {
        val msg = when (filter) {
            MapPeriodFilter.WEEKLY -> "최근 7일간의 주행 경로를 분석 중입니다..."
            MapPeriodFilter.MONTHLY -> "최근 30일간의 주행 경로를 분석 중입니다..."
            MapPeriodFilter.ALL -> "전체 이동 기록을 불러오는 중입니다..."
            MapPeriodFilter.DAILY -> "선택 일자의 이동 경로를 불러오는 중입니다..."
        }
        _uiState.update { it.copy(mapPeriodFilter = filter) }
        updateFilteredEntries(msg)
    }

    fun selectMapStep(step: RouteStep) {
        _uiState.update { it.copy(selectedMapStep = step) }
    }

    fun selectDate(date: LocalDate) {
        val targetYm = YearMonth.from(date)
        val ymChanged = _uiState.value.currentYearMonth != targetYm
        _uiState.update {
            it.copy(
                selectedDate = date,
                currentYearMonth = targetYm
            )
        }
        if (ymChanged) {
            loadMonthlyCalendar(targetYm)
        }
        updateFilteredEntries()
    }

    fun onCalendarDateClicked(date: LocalDate) {
        val targetYm = YearMonth.from(date)
        val ymChanged = _uiState.value.currentYearMonth != targetYm
        _uiState.update {
            it.copy(
                selectedDate = date,
                currentYearMonth = targetYm,
                viewMode = TimelineViewMode.DAILY
            )
        }
        if (ymChanged) {
            loadMonthlyCalendar(targetYm)
        }
        updateFilteredEntries()
    }

    fun changeYearMonth(yearMonth: YearMonth) {
        val currentDay = _uiState.value.selectedDate.dayOfMonth
        val safeDay = currentDay.coerceAtMost(yearMonth.lengthOfMonth())
        val syncedDate = yearMonth.atDay(safeDay)
        _uiState.update {
            it.copy(
                currentYearMonth = yearMonth,
                selectedDate = syncedDate,
                isLoading = it.viewMode == TimelineViewMode.MONTHLY,
                loadingMessage = "월간 캘린더 데이터를 불러오는 중입니다..."
            )
        }
        loadMonthlyCalendar(yearMonth)
        updateFilteredEntries()
    }

    fun navigatePrevious() {
        when (_uiState.value.viewMode) {
            TimelineViewMode.DAILY -> selectDate(_uiState.value.selectedDate.minusDays(1))
            TimelineViewMode.WEEKLY -> selectDate(_uiState.value.selectedDate.minusWeeks(1))
            TimelineViewMode.MONTHLY -> changeYearMonth(_uiState.value.currentYearMonth.minusMonths(1))
            TimelineViewMode.MAP_ROUTE -> {
                when (_uiState.value.mapPeriodFilter) {
                    MapPeriodFilter.DAILY -> selectDate(_uiState.value.selectedDate.minusDays(1))
                    MapPeriodFilter.WEEKLY -> selectDate(_uiState.value.selectedDate.minusWeeks(1))
                    MapPeriodFilter.MONTHLY -> selectDate(_uiState.value.selectedDate.minusMonths(1))
                    MapPeriodFilter.ALL -> {}
                }
            }
        }
    }

    fun navigateNext() {
        when (_uiState.value.viewMode) {
            TimelineViewMode.DAILY -> selectDate(_uiState.value.selectedDate.plusDays(1))
            TimelineViewMode.WEEKLY -> selectDate(_uiState.value.selectedDate.plusWeeks(1))
            TimelineViewMode.MONTHLY -> changeYearMonth(_uiState.value.currentYearMonth.plusMonths(1))
            TimelineViewMode.MAP_ROUTE -> {
                when (_uiState.value.mapPeriodFilter) {
                    MapPeriodFilter.DAILY -> selectDate(_uiState.value.selectedDate.plusDays(1))
                    MapPeriodFilter.WEEKLY -> selectDate(_uiState.value.selectedDate.plusWeeks(1))
                    MapPeriodFilter.MONTHLY -> selectDate(_uiState.value.selectedDate.plusMonths(1))
                    MapPeriodFilter.ALL -> {}
                }
            }
        }
    }

    private fun loadMonthlyCalendar(yearMonth: YearMonth) {
        monthlyCalendarJob?.cancel()
        monthlyCalendarJob = viewModelScope.launch {
            getMonthlyCalendarDataUseCase(yearMonth).collectLatest { summary ->
                _uiState.update { current ->
                    current.copy(
                        monthlyCalendarData = summary,
                        isLoading = if (current.viewMode == TimelineViewMode.MONTHLY) false else current.isLoading
                    )
                }
            }
        }
    }

    fun openDiaryDetail(entry: DiaryEntry) {
        _uiState.update { it.copy(selectedDiaryDetail = entry) }
    }

    fun closeDiaryDetail() {
        _uiState.update { it.copy(selectedDiaryDetail = null) }
    }

    fun openPhotoPreview(url: String, contextList: List<String>? = null) {
        val list = if (!contextList.isNullOrEmpty()) {
            contextList
        } else {
            val detailPhotos = _uiState.value.selectedDiaryDetail?.let { entry ->
                (entry.photoUris + entry.routeSteps.flatMap { it.photoUris }).distinct()
            }
            val currentEntriesPhotos = _uiState.value.entries.flatMap { entry ->
                entry.photoUris + entry.routeSteps.flatMap { it.photoUris }
            }.distinct()
            val mapPhotos = _uiState.value.mapRouteSteps.flatMap { it.photoUris }.distinct()

            when {
                detailPhotos?.contains(url) == true -> detailPhotos
                currentEntriesPhotos.contains(url) -> currentEntriesPhotos
                mapPhotos.contains(url) -> mapPhotos
                else -> listOf(url)
            }
        }

        val initialIndex = list.indexOf(url).coerceAtLeast(0)
        _uiState.update {
            it.copy(
                selectedPhotoPreviewUrl = url,
                photoPreviewList = list,
                photoPreviewIndex = initialIndex
            )
        }
    }

    fun setPhotoPreviewIndex(index: Int) {
        val list = _uiState.value.photoPreviewList
        if (index in list.indices) {
            _uiState.update {
                it.copy(
                    photoPreviewIndex = index,
                    selectedPhotoPreviewUrl = list[index]
                )
            }
        }
    }

    fun closePhotoPreview() {
        _uiState.update {
            it.copy(
                selectedPhotoPreviewUrl = null,
                photoPreviewList = emptyList(),
                photoPreviewIndex = 0
            )
        }
    }

    fun deletePhoto(photoUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "사진 삭제 및 화면 갱신 중",
                message = "선택한 사진을 삭제하고 타임라인을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.diary
            ) {
                try {
                    excludedPhotoPreferences.excludePhoto(photoUrl)

                    val entriesToUpdate = rawEntries.filter { entry ->
                        entry.photoUris.contains(photoUrl) || entry.routeSteps.any { it.photoUris.contains(photoUrl) }
                    }

                    for (entry in entriesToUpdate) {
                        val updatedPhotoUris = entry.photoUris.filter { it != photoUrl }
                        val updatedRouteSteps = entry.routeSteps.mapNotNull { step ->
                            if (step.photoUris.contains(photoUrl)) {
                                val newStepPhotos = step.photoUris.filter { it != photoUrl }
                                if (step.stepType == RouteStepType.PHOTO && newStepPhotos.isEmpty()) {
                                    null
                                } else {
                                    step.copy(
                                        photoUris = newStepPhotos,
                                        description = if (step.stepType == RouteStepType.PHOTO) {
                                            buildString {
                                                append("사진 ${newStepPhotos.size}장 촬영")
                                                if (step.companions.isNotEmpty()) {
                                                    append(" · 동행: ${step.companions.joinToString(", ")}")
                                                }
                                            }
                                        } else step.description
                                    )
                                }
                            } else {
                                step
                            }
                        }

                        val updatedEntry = entry.copy(
                            photoUris = updatedPhotoUris,
                            routeSteps = updatedRouteSteps
                        )
                        diaryRepository.updateDiaryEntry(updatedEntry)

                        if (_uiState.value.selectedDiaryDetail?.id == updatedEntry.id) {
                            _uiState.update { it.copy(selectedDiaryDetail = updatedEntry) }
                        }
                    }

                    val currentList = _uiState.value.photoPreviewList
                    val updatedList = currentList.filter { it != photoUrl }
                    if (updatedList.isEmpty()) {
                        _uiState.update {
                            it.copy(
                                selectedPhotoPreviewUrl = null,
                                photoPreviewList = emptyList(),
                                photoPreviewIndex = 0
                            )
                        }
                    } else {
                        val currentIndex = _uiState.value.photoPreviewIndex
                        val nextIndex = currentIndex.coerceIn(0, updatedList.size - 1)
                        _uiState.update {
                            it.copy(
                                photoPreviewList = updatedList,
                                photoPreviewIndex = nextIndex,
                                selectedPhotoPreviewUrl = updatedList[nextIndex]
                            )
                        }
                    }

                    updateFilteredEntries()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    fun openAddCompanionDialog(step: RouteStep) {
        _uiState.update { it.copy(isAddCompanionDialogOpen = true, companionTargetStep = step) }
    }

    fun closeAddCompanionDialog() {
        _uiState.update { it.copy(isAddCompanionDialogOpen = false, companionTargetStep = null) }
    }

    fun addCompanionToStep(companionName: String) {
        val cleanName = companionName.trim()
        if (cleanName.isBlank()) return

        val target = _uiState.value.companionTargetStep ?: return
        viewModelScope.launch(Dispatchers.IO) {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "동행인 추가 및 화면 갱신 중",
                message = "동행인 정보를 저장하고 타임라인을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.diary
            ) {
                try {
                    val entry = rawEntries.find { it.routeSteps.any { s -> s.id == target.id || s.title == target.title } } ?: return@runWithLoading
                    var updatedTargetStep: RouteStep? = null
                    val updatedSteps = entry.routeSteps.map { step ->
                        if (step.id == target.id || step.title == target.title) {
                            val newCompanions = (step.companions + cleanName).distinct()
                            val newDesc = if (step.stepType == RouteStepType.PHOTO) {
                                buildString {
                                    append("사진 ${step.photoUris.size}장 촬영")
                                    if (newCompanions.isNotEmpty()) {
                                        append(" · 동행: ${newCompanions.joinToString(", ")}")
                                    }
                                }
                            } else step.description

                            val updated = step.copy(companions = newCompanions, description = newDesc)
                            updatedTargetStep = updated
                            updated
                        } else {
                            step
                        }
                    }

                    val cleanTags = entry.tags.filterNot { it.startsWith("👤 ") }
                    val updatedEntry = entry.copy(
                        routeSteps = updatedSteps,
                        tags = cleanTags
                    )
                    diaryRepository.updateDiaryEntry(updatedEntry)

                    _uiState.update { current ->
                        current.copy(
                            companionTargetStep = updatedTargetStep ?: current.companionTargetStep,
                            selectedDiaryDetail = if (current.selectedDiaryDetail?.id == updatedEntry.id) updatedEntry else current.selectedDiaryDetail
                        )
                    }
                    updateFilteredEntries()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    fun removeCompanionFromStep(stepId: String, companionName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "동행인 삭제 및 화면 갱신 중",
                message = "동행인 정보를 수정하고 타임라인을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.diary
            ) {
                try {
                    val entry = rawEntries.find { it.routeSteps.any { s -> s.id == stepId } } ?: return@runWithLoading
                    var updatedTargetStep: RouteStep? = null
                    val updatedSteps = entry.routeSteps.map { step ->
                        if (step.id == stepId) {
                            val newCompanions = step.companions.filter { it != companionName }
                            val newDesc = if (step.stepType == RouteStepType.PHOTO) {
                                buildString {
                                    append("사진 ${step.photoUris.size}장 촬영")
                                    if (newCompanions.isNotEmpty()) {
                                        append(" · 동행: ${newCompanions.joinToString(", ")}")
                                    }
                                }
                            } else step.description

                            val updated = step.copy(companions = newCompanions, description = newDesc)
                            updatedTargetStep = updated
                            updated
                        } else {
                            step
                        }
                    }

                    val cleanTags = entry.tags.filterNot { it.startsWith("👤 ") }
                    val updatedEntry = entry.copy(
                        routeSteps = updatedSteps,
                        tags = cleanTags
                    )
                    diaryRepository.updateDiaryEntry(updatedEntry)

                    _uiState.update { current ->
                        current.copy(
                            companionTargetStep = if (current.companionTargetStep?.id == stepId) updatedTargetStep else current.companionTargetStep,
                            selectedDiaryDetail = if (current.selectedDiaryDetail?.id == updatedEntry.id) updatedEntry else current.selectedDiaryDetail
                        )
                    }
                    updateFilteredEntries()
                } catch (t: Throwable) {
                    t.printStackTrace()
                }
            }
        }
    }

    fun updateDiaryNote(diaryId: Long, title: String, userNotes: String) {
        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "다이어리 저장 및 화면 갱신 중",
                message = "다이어리 제목과 메모를 저장하고 화면을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.diary
            ) {
                val entry = rawEntries.find { it.id == diaryId } ?: return@runWithLoading
                val updated = entry.copy(
                    title = title.ifBlank { entry.title },
                    summary = userNotes
                )
                diaryRepository.updateDiaryEntry(updated)
                _uiState.update { current ->
                    current.copy(
                        selectedDiaryDetail = if (current.selectedDiaryDetail?.id == diaryId) updated else current.selectedDiaryDetail
                    )
                }
            }
        }
    }

    fun autoSyncRecentWeek(context: Context, force: Boolean = false) {
        syncHistoricalDataUseCase.startAutoSync(context = appContext, daysBack = 7, force = force)
    }

    fun openManualSyncDialog() {
        _uiState.update { it.copy(isManualSyncDialogOpen = true) }
    }

    fun closeManualSyncDialog() {
        _uiState.update { it.copy(isManualSyncDialogOpen = false) }
    }

    fun executeManualSync(daysBack: Int?, context: Context) {
        closeManualSyncDialog()
        syncHistoricalDataUseCase.startManualSync(context = appContext, daysBack = daysBack)
    }

    fun triggerHistoricalSync(context: Context) {
        executeManualSync(daysBack = 0, context = context)
    }

    fun dismissSyncDialog() {
        syncHistoricalDataUseCase.dismissManualSyncDialog()
        _uiState.update { it.copy(isSyncDialogVisible = false) }
        updateFilteredEntries()
        loadMonthlyCalendar(_uiState.value.currentYearMonth)
    }

    fun exportToExcel(onComplete: (ExportResult) -> Unit) {
        viewModelScope.launch {
            _uiState.update { it.copy(isExportingExcel = true) }
            val result = exportAllDataToExcelUseCase()
            _uiState.update { it.copy(isExportingExcel = false, exportResult = result) }
            onComplete(result)
        }
    }
}
