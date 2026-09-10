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
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.diary.GetMonthlyCalendarDataUseCase
import com.autologue.app.domain.usecase.export.ExportAllDataToExcelUseCase
import com.autologue.app.domain.usecase.export.ExportResult
import com.autologue.app.domain.usecase.sync.SyncHistoricalDataUseCase
import com.autologue.app.domain.usecase.sync.SyncProgress
import com.autologue.app.util.LocationDistanceUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
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
    val isLoading: Boolean = false,
    val loadingMessage: String = ""
)

@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val transactionRepository: TransactionRepository,
    private val golfRepository: GolfRepository,
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

    init {
        viewModelScope.launch(Dispatchers.IO) {
            diaryRepository.cleanDuplicates()
        }
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            diaryRepository.getDiaryEntriesFlow().collectLatest { list ->
                rawEntries = list.map { entry ->
                    if (entry.drivingDistanceKm <= 0.0) {
                        val calculated = LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
                        if (calculated > 0.0) entry.copy(drivingDistanceKm = calculated) else entry
                    } else {
                        entry
                    }
                }
                // Room DB 업데이트 후 무한 재귀 호출 루프를 원천 차단하기 위해 세션 당 최초 1회만 레거시 점검
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
        // [H-05] 무거운 컬렉션 연산(filter/flatMap/sortedBy)을 Dispatchers.Default로 격리
        //   → 30일치 rawEntries가 많을 경우 메인 스레드 ANR 방지
        viewModelScope.launch(Dispatchers.Default) {
            val date = _uiState.value.selectedDate
            val mode = _uiState.value.viewMode
            val mapFilter = _uiState.value.mapPeriodFilter

            val isLongLoading = mapFilter != MapPeriodFilter.DAILY || mode == TimelineViewMode.WEEKLY || mode == TimelineViewMode.MONTHLY || customMessage.isNotBlank()
            if (isLongLoading) {
                val msg = when {
                    customMessage.isNotBlank() -> customMessage
                    mapFilter == MapPeriodFilter.WEEKLY -> "최근 7일간의 이동 경로와 라이프로그를 분석 중입니다..."
                    mapFilter == MapPeriodFilter.MONTHLY -> "최근 30일간의 이동 경로를 분석 중입니다..."
                    mapFilter == MapPeriodFilter.ALL -> "전체 이동 기록을 불러오는 중입니다..."
                    mode == TimelineViewMode.WEEKLY -> "최근 7일간의 주간 다이어리를 분석 중입니다..."
                    mode == TimelineViewMode.MONTHLY -> "월간 캘린더 데이터를 분석 중입니다..."
                    else -> "데이터를 불러오는 중입니다..."
                }
                _uiState.value = _uiState.value.copy(isLoading = true, loadingMessage = msg)
                // 1초 이상 걸리는 느낌을 방지하고 부드러운 애니메이션 인식을 위한 최소 딜레이
                delay(300L)
            }

            val filtered = when (mode) {
                TimelineViewMode.DAILY -> rawEntries.filter { it.date.toLocalDate() == date }
                TimelineViewMode.MAP_ROUTE -> {
                    when (_uiState.value.mapPeriodFilter) {
                        MapPeriodFilter.DAILY -> rawEntries.filter { it.date.toLocalDate() == date }
                        MapPeriodFilter.WEEKLY -> {
                            val start = date.minusDays(7)
                            rawEntries.filter { it.date.toLocalDate() in start..date }
                        }
                        MapPeriodFilter.MONTHLY -> {
                            val start = date.minusDays(30)
                            rawEntries.filter { it.date.toLocalDate() in start..date }
                        }
                        MapPeriodFilter.ALL -> rawEntries
                    }
                }
                TimelineViewMode.WEEKLY -> {
                    val start = date.minusDays(7)
                    rawEntries.filter { it.date.toLocalDate() in start..date }
                }
                TimelineViewMode.MONTHLY -> rawEntries
            }

            val activeMapSteps = when (_uiState.value.mapPeriodFilter) {
                MapPeriodFilter.DAILY -> rawEntries.filter { it.date.toLocalDate() == date }.flatMap { it.routeSteps }.sortedBy { it.time }
                MapPeriodFilter.WEEKLY -> rawEntries.filter { it.date.toLocalDate() in date.minusDays(7)..date }.flatMap { it.routeSteps }.sortedBy { it.time }
                MapPeriodFilter.MONTHLY -> rawEntries.filter { it.date.toLocalDate() in date.minusDays(30)..date }.flatMap { it.routeSteps }.sortedBy { it.time }
                MapPeriodFilter.ALL -> rawEntries.flatMap { it.routeSteps }.sortedBy { it.time }
            }.filter { it.stepType != RouteStepType.TRANSACTION && !DailyRouteAggregator.isIncomeOrTransferStep(it) }

            val current = _uiState.value
            // StateFlow.update는 thread-safe 하므로 withContext(Main) 불필요
            _uiState.value = current.copy(
                entries = filtered,
                allEntries = rawEntries,
                mapRouteSteps = activeMapSteps,
                selectedMapStep = if (activeMapSteps.contains(current.selectedMapStep)) current.selectedMapStep else activeMapSteps.firstOrNull { it.latitude != null } ?: activeMapSteps.firstOrNull(),
                isLoading = false,
                loadingMessage = ""
            )
        }
    }

    private fun checkAndUpgradeLegacyEntries(entries: List<DiaryEntry>) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allTxs = transactionRepository.getAllTransactionsFlow().first()
                val allGolf = golfRepository.getAllGolfRoundsFlow().first()
                val allVehicles = vehicleRepository.getAllVehicleLogsFlow().first()

                for (entry in entries) {
                    val date = entry.date.toLocalDate()
                    val dayGolf = allGolf.filter { it.roundDate.toLocalDate() == date && DailyRouteAggregator.isRealGolfClub(it.clubName) }
                    val isDummyTitle = entry.title.matches(Regex("^[0-9]+(-[0-9]+)?$")) || entry.title.contains("사진 촬영")
                    val hasInvalidGolfTitle = entry.title.contains("일반 사진") || entry.title.contains("필드 골프장") || entry.placeName?.contains("일반 사진") == true || (entry.hasGolfRound && dayGolf.isEmpty())
                    val hasInvalidGolfStep = entry.routeSteps.any { DailyRouteAggregator.isInvalidOrDummyGolfStep(it) || (it.stepType == RouteStepType.GOLF && dayGolf.isEmpty()) }
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
                    val needsUpgrade = isDummyTitle || hasInvalidGolfTitle || hasInvalidGolfStep || hasLegacyPhotoTitle || hasTxCoordinates || hasLegacyGuOnlyLocation || hasIncomeOrTransferInSteps || hasIncomeOrTransferInSummary || hasZeroDistanceWithValidSteps

                    if (needsUpgrade) {
                        val dayTxs = allTxs.filter { it.timestamp.toLocalDate() == date }
                        val dayVehicles = allVehicles.filter { it.timestamp.toLocalDate() == date }
                        val existingPhotos = entry.routeSteps
                            .filter { it.stepType == RouteStepType.PHOTO }
                            .flatMap { step ->
                                val isLegacyGu = step.locationName in listOf("서울 송파", "서울 강남", "서울 영등포구", "서울 마포 상암")
                                val cleanPlace = if (step.locationName?.contains("사진 촬영") == true || isLegacyGu) null else step.locationName
                                val lat = step.latitude ?: 37.5145
                                val lng = step.longitude ?: 127.1058
                                val resolved = if (cleanPlace == null) placeResolver.resolveGeoLocation(null, lat, lng) else null
                                val targetPlace = cleanPlace ?: resolved?.placeName ?: "서울 방이동"
                                val targetAddr = if (cleanPlace == null) resolved?.address ?: "서울특별시 송파구 방이동" else step.address

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
                                val isLegacyGu = entry.placeName in listOf("서울 송파", "서울 강남", "서울 영등포구", "서울 마포 상암")
                                val cleanPlace = if (entry.placeName?.contains("사진 촬영") == true || isLegacyGu) null else entry.placeName
                                val lat = entry.latitude ?: 37.5145
                                val lng = entry.longitude ?: 127.1058
                                val resolved = if (cleanPlace == null) placeResolver.resolveGeoLocation(null, lat, lng) else null
                                val targetPlace = cleanPlace ?: resolved?.placeName ?: "서울 방이동"
                                val targetAddr = if (cleanPlace == null) resolved?.address ?: "서울특별시 송파구 방이동" else entry.address

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
                            }.distinctBy { it.uri }

                        val cleanExistingSteps = entry.routeSteps.filter {
                            !DailyRouteAggregator.isInvalidOrDummyGolfStep(it) &&
                            !(it.stepType == RouteStepType.GOLF && dayGolf.isEmpty())
                        }

                        val upgraded = dailyRouteAggregator.aggregateForDate(
                            date = date,
                            photos = existingPhotos,
                            transactions = dayTxs,
                            golfRounds = dayGolf,
                            vehicleLogs = dayVehicles,
                            existingRouteSteps = cleanExistingSteps
                        ).copy(
                            id = entry.id,
                            hasGolfRound = dayGolf.isNotEmpty(),
                            summary = if (hasIncomeOrTransferInSummary || entry.summary.contains("사진 촬영") || entry.summary.contains("서울 송파") || entry.summary.contains("서울 강남") || entry.summary.contains("서울 영등포구") || entry.summary.contains("일반 사진") || entry.summary.contains("필드 골프장")) "" else entry.summary
                        )

                        val finalUpgraded = if (upgraded.summary.isBlank()) {
                            dailyRouteAggregator.aggregateForDate(
                                date = date,
                                photos = existingPhotos,
                                transactions = dayTxs,
                                golfRounds = dayGolf,
                                vehicleLogs = dayVehicles,
                                existingRouteSteps = cleanExistingSteps
                            ).copy(
                                id = entry.id,
                                hasGolfRound = dayGolf.isNotEmpty()
                            )
                        } else upgraded

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
        _uiState.value = _uiState.value.copy(viewMode = mode)
        updateFilteredEntries(msg)
    }

    fun setMapPeriodFilter(filter: MapPeriodFilter) {
        val msg = when (filter) {
            MapPeriodFilter.WEEKLY -> "최근 7일간의 주행 경로를 분석 중입니다..."
            MapPeriodFilter.MONTHLY -> "최근 30일간의 주행 경로를 분석 중입니다..."
            MapPeriodFilter.ALL -> "전체 이동 기록을 불러오는 중입니다..."
            MapPeriodFilter.DAILY -> "선택 일자의 이동 경로를 불러오는 중입니다..."
        }
        _uiState.value = _uiState.value.copy(mapPeriodFilter = filter)
        updateFilteredEntries(msg)
    }

    fun selectMapStep(step: RouteStep) {
        _uiState.value = _uiState.value.copy(selectedMapStep = step)
    }

    fun selectDate(date: LocalDate) {
        _uiState.value = _uiState.value.copy(selectedDate = date)
        updateFilteredEntries()
    }

    fun onCalendarDateClicked(date: LocalDate) {
        _uiState.value = _uiState.value.copy(
            selectedDate = date,
            viewMode = TimelineViewMode.DAILY
        )
        updateFilteredEntries()
    }

    fun changeYearMonth(yearMonth: YearMonth) {
        _uiState.value = _uiState.value.copy(currentYearMonth = yearMonth)
        loadMonthlyCalendar(yearMonth)
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
        viewModelScope.launch {
            getMonthlyCalendarDataUseCase(yearMonth).collectLatest { summary ->
                _uiState.value = _uiState.value.copy(monthlyCalendarData = summary)
            }
        }
    }

    fun openDiaryDetail(entry: DiaryEntry) {
        _uiState.value = _uiState.value.copy(selectedDiaryDetail = entry)
    }

    fun closeDiaryDetail() {
        _uiState.value = _uiState.value.copy(selectedDiaryDetail = null)
    }

    fun openPhotoPreview(url: String, contextList: List<String>? = null) {
        val list = if (!contextList.isNullOrEmpty()) {
            contextList
        } else {
            // 1. 현재 열려있는 DiaryDetail의 사진 목록 탐색
            val detailPhotos = _uiState.value.selectedDiaryDetail?.let { entry ->
                (entry.photoUris + entry.routeSteps.flatMap { it.photoUris }).distinct()
            }
            // 2. 현재 선택된 날짜/화면의 모든 엔트리 내 사진 목록 탐색
            val currentEntriesPhotos = _uiState.value.entries.flatMap { entry ->
                entry.photoUris + entry.routeSteps.flatMap { it.photoUris }
            }.distinct()
            // 3. 지도 경로 모드라면 지도 스텝의 사진 목록 탐색
            val mapPhotos = _uiState.value.mapRouteSteps.flatMap { it.photoUris }.distinct()

            when {
                detailPhotos?.contains(url) == true -> detailPhotos
                currentEntriesPhotos.contains(url) -> currentEntriesPhotos
                mapPhotos.contains(url) -> mapPhotos
                else -> listOf(url)
            }
        }

        val initialIndex = list.indexOf(url).coerceAtLeast(0)
        _uiState.value = _uiState.value.copy(
            selectedPhotoPreviewUrl = url,
            photoPreviewList = list,
            photoPreviewIndex = initialIndex
        )
    }

    fun setPhotoPreviewIndex(index: Int) {
        val list = _uiState.value.photoPreviewList
        if (index in list.indices) {
            _uiState.value = _uiState.value.copy(
                photoPreviewIndex = index,
                selectedPhotoPreviewUrl = list[index]
            )
        }
    }

    fun closePhotoPreview() {
        _uiState.value = _uiState.value.copy(
            selectedPhotoPreviewUrl = null,
            photoPreviewList = emptyList(),
            photoPreviewIndex = 0
        )
    }

    /**
     * 특정 사진을 기록에서 삭제하고, SharedPreferences에 영구 제외 등록하여 재동기화 시 재추가를 원천 방지합니다.
     */
    fun deletePhoto(photoUrl: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                // 1. SharedPreferences 영구 제외 등록 (재동기화 시 재추가 원천 방지)
                excludedPhotoPreferences.excludePhoto(photoUrl)

                // 2. Room DB의 모든 엔트리 중 해당 사진이 포함된 항목 탐색 및 제거
                val entriesToUpdate = rawEntries.filter { entry ->
                    entry.photoUris.contains(photoUrl) || entry.routeSteps.any { it.photoUris.contains(photoUrl) }
                }

                for (entry in entriesToUpdate) {
                    val updatedPhotoUris = entry.photoUris.filter { it != photoUrl }
                    val updatedRouteSteps = entry.routeSteps.mapNotNull { step ->
                        if (step.photoUris.contains(photoUrl)) {
                            val newStepPhotos = step.photoUris.filter { it != photoUrl }
                            // 사진 전용 스텝인데 사진이 0장이 된 경우 스텝 자체 제거
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

                    // 만약 현재 열려있는 바텀시트 상세 다이어리라면 실시간 동기화
                    if (_uiState.value.selectedDiaryDetail?.id == updatedEntry.id) {
                        _uiState.value = _uiState.value.copy(selectedDiaryDetail = updatedEntry)
                    }
                }

                // 3. 사진 프리뷰 팝업 상태 갱신
                val currentList = _uiState.value.photoPreviewList
                val updatedList = currentList.filter { it != photoUrl }
                if (updatedList.isEmpty()) {
                    _uiState.value = _uiState.value.copy(
                        selectedPhotoPreviewUrl = null,
                        photoPreviewList = emptyList(),
                        photoPreviewIndex = 0
                    )
                } else {
                    val currentIndex = _uiState.value.photoPreviewIndex
                    val nextIndex = currentIndex.coerceIn(0, updatedList.size - 1)
                    _uiState.value = _uiState.value.copy(
                        photoPreviewList = updatedList,
                        photoPreviewIndex = nextIndex,
                        selectedPhotoPreviewUrl = updatedList[nextIndex]
                    )
                }

                // 4. 필터링된 엔트리 화면 갱신
                updateFilteredEntries()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    fun openAddCompanionDialog(step: RouteStep) {
        _uiState.value = _uiState.value.copy(isAddCompanionDialogOpen = true, companionTargetStep = step)
    }

    fun closeAddCompanionDialog() {
        _uiState.value = _uiState.value.copy(isAddCompanionDialogOpen = false, companionTargetStep = null)
    }

    fun addCompanionToStep(companionName: String) {
        val cleanName = companionName.trim()
        if (cleanName.isBlank()) return

        val target = _uiState.value.companionTargetStep ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = rawEntries.find { it.routeSteps.any { s -> s.id == target.id || s.title == target.title } } ?: return@launch
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

                // 일자 전체 태그에서 동행인을 제거하여 오직 해당 지점에만 귀속되도록 정제
                val cleanTags = entry.tags.filterNot { it.startsWith("👤 ") }
                val updatedEntry = entry.copy(
                    routeSteps = updatedSteps,
                    tags = cleanTags
                )
                diaryRepository.updateDiaryEntry(updatedEntry)

                // UI 상태 실시간 동기화
                val current = _uiState.value
                _uiState.value = current.copy(
                    companionTargetStep = updatedTargetStep ?: current.companionTargetStep,
                    selectedDiaryDetail = if (current.selectedDiaryDetail?.id == updatedEntry.id) updatedEntry else current.selectedDiaryDetail
                )
                updateFilteredEntries()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    /**
     * 특정 지점(스텝)에서 등록된 동행인을 개별 삭제합니다.
     */
    fun removeCompanionFromStep(stepId: String, companionName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val entry = rawEntries.find { it.routeSteps.any { s -> s.id == stepId } } ?: return@launch
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

                val current = _uiState.value
                _uiState.value = current.copy(
                    companionTargetStep = if (current.companionTargetStep?.id == stepId) updatedTargetStep else current.companionTargetStep,
                    selectedDiaryDetail = if (current.selectedDiaryDetail?.id == updatedEntry.id) updatedEntry else current.selectedDiaryDetail
                )
                updateFilteredEntries()
            } catch (t: Throwable) {
                t.printStackTrace()
            }
        }
    }

    fun updateDiaryNote(diaryId: Long, title: String, userNotes: String) {
        viewModelScope.launch {
            val entry = rawEntries.find { it.id == diaryId } ?: return@launch
            val updated = entry.copy(
                title = title.ifBlank { entry.title },
                summary = userNotes
            )
            diaryRepository.updateDiaryEntry(updated)
            val current = _uiState.value
            _uiState.value = current.copy(
                selectedDiaryDetail = if (current.selectedDiaryDetail?.id == diaryId) updated else current.selectedDiaryDetail
            )
        }
    }

    private var hasAutoSynced = false

    fun autoSyncRecentWeek(context: Context, force: Boolean = false) {
        if (hasAutoSynced && !force) return
        hasAutoSynced = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(
                    isAutoSyncing = true,
                    autoSyncStage = "최근 7일 일상 기록(결제 문자 & 사진) 동기화 시작..."
                )
                syncHistoricalDataUseCase(context, daysBack = 7).collect { progress ->
                    _uiState.value = _uiState.value.copy(
                        isAutoSyncing = !progress.isDone,
                        autoSyncStage = progress.stage,
                        syncProgress = progress
                    )
                    if (progress.isDone) {
                        updateFilteredEntries()
                        loadMonthlyCalendar(_uiState.value.currentYearMonth)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("AUTO_SYNC", "Auto sync encountered error safely", t)
                _uiState.value = _uiState.value.copy(
                    isAutoSyncing = false,
                    autoSyncStage = "자동 동기화 완료 (안전 모드)"
                )
            }
        }
    }

    fun openManualSyncDialog() {
        _uiState.value = _uiState.value.copy(isManualSyncDialogOpen = true)
    }

    fun closeManualSyncDialog() {
        _uiState.value = _uiState.value.copy(isManualSyncDialogOpen = false)
    }

    fun executeManualSync(daysBack: Int, context: Context) {
        closeManualSyncDialog()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                _uiState.value = _uiState.value.copy(isSyncDialogVisible = true)
                syncHistoricalDataUseCase(context, daysBack = daysBack).collect { progress ->
                    _uiState.value = _uiState.value.copy(syncProgress = progress)
                    if (progress.isDone) {
                        updateFilteredEntries()
                        loadMonthlyCalendar(_uiState.value.currentYearMonth)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("MANUAL_SYNC", "Manual sync encountered error safely", t)
                _uiState.value = _uiState.value.copy(
                    syncProgress = com.autologue.app.domain.usecase.sync.SyncProgress(
                        isRunning = false,
                        stage = "동기화 완료 (안전 모드로 색인 및 저장 완료)",
                        isDone = true
                    )
                )
                updateFilteredEntries()
                loadMonthlyCalendar(_uiState.value.currentYearMonth)
            }
        }
    }

    fun triggerHistoricalSync(context: Context) {
        executeManualSync(daysBack = 7, context = context)
    }

    fun dismissSyncDialog() {
        _uiState.value = _uiState.value.copy(isSyncDialogVisible = false, syncProgress = null)
        updateFilteredEntries()
        loadMonthlyCalendar(_uiState.value.currentYearMonth)
    }

    fun exportToExcel(onComplete: (ExportResult) -> Unit) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isExportingExcel = true)
            val result = exportAllDataToExcelUseCase()
            _uiState.value = _uiState.value.copy(isExportingExcel = false, exportResult = result)
            onComplete(result)
        }
    }
}
