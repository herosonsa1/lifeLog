package com.autologue.app.presentation.diary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import dagger.hilt.android.lifecycle.HiltViewModel
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
    val isExportingExcel: Boolean = false,
    val exportResult: ExportResult? = null,
    val selectedDiaryDetail: DiaryEntry? = null,
    val selectedPhotoPreviewUrl: String? = null,
    val isAddCompanionDialogOpen: Boolean = false,
    val companionTargetStep: RouteStep? = null
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
    private val placeResolver: PlaceResolver
) : ViewModel() {

    private val _uiState = MutableStateFlow(DiaryUiState())
    val uiState: StateFlow<DiaryUiState> = _uiState.asStateFlow()

    private var rawEntries: List<DiaryEntry> = emptyList()

    init {
        viewModelScope.launch(Dispatchers.IO) {
            diaryRepository.cleanDuplicates()
        }
        observeData()
    }

    private fun observeData() {
        viewModelScope.launch {
            diaryRepository.getDiaryEntriesFlow().collectLatest { list ->
                rawEntries = list
                checkAndUpgradeLegacyEntries(list)
                updateFilteredEntries()
            }
        }

        loadMonthlyCalendar(_uiState.value.currentYearMonth)
    }

    private fun updateFilteredEntries() {
        val date = _uiState.value.selectedDate
        val mode = _uiState.value.viewMode
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

        val allRouteSteps = (if (mode == TimelineViewMode.MAP_ROUTE) filtered else rawEntries)
            .flatMap { it.routeSteps }
            .sortedBy { it.time }

        val activeMapSteps = when (_uiState.value.mapPeriodFilter) {
            MapPeriodFilter.DAILY -> rawEntries.filter { it.date.toLocalDate() == date }.flatMap { it.routeSteps }.sortedBy { it.time }
            MapPeriodFilter.WEEKLY -> rawEntries.filter { it.date.toLocalDate() in date.minusDays(7)..date }.flatMap { it.routeSteps }.sortedBy { it.time }
            MapPeriodFilter.MONTHLY -> rawEntries.filter { it.date.toLocalDate() in date.minusDays(30)..date }.flatMap { it.routeSteps }.sortedBy { it.time }
            MapPeriodFilter.ALL -> rawEntries.flatMap { it.routeSteps }.sortedBy { it.time }
        }.filter { it.stepType != RouteStepType.TRANSACTION && !DailyRouteAggregator.isIncomeOrTransferStep(it) }

        val current = _uiState.value
        _uiState.value = current.copy(
            entries = filtered,
            allEntries = rawEntries,
            mapRouteSteps = activeMapSteps,
            selectedMapStep = if (activeMapSteps.contains(current.selectedMapStep)) current.selectedMapStep else activeMapSteps.firstOrNull { it.latitude != null } ?: activeMapSteps.firstOrNull()
        )
    }

    private fun checkAndUpgradeLegacyEntries(entries: List<DiaryEntry>) {
        viewModelScope.launch(Dispatchers.IO) {
            val allTxs = transactionRepository.getAllTransactionsFlow().first()
            val allGolf = golfRepository.getAllGolfRoundsFlow().first()
            val allVehicles = vehicleRepository.getAllVehicleLogsFlow().first()

            for (entry in entries) {
                val date = entry.date.toLocalDate()
                val isDummyTitle = entry.title.matches(Regex("^[0-9]+(-[0-9]+)?$")) || entry.title.contains("사진 촬영")
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
                val needsUpgrade = isDummyTitle || hasLegacyPhotoTitle || entry.routeSteps.isEmpty() || entry.movementSummary.isNullOrBlank() || hasTxCoordinates || hasLegacyGuOnlyLocation || hasIncomeOrTransferInSteps || hasIncomeOrTransferInSummary

                if (needsUpgrade) {
                    val dayTxs = allTxs.filter { it.timestamp.toLocalDate() == date }
                    val dayGolf = allGolf.filter { it.roundDate.toLocalDate() == date }
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
                        }

                    val upgraded = dailyRouteAggregator.aggregateForDate(
                        date = date,
                        photos = existingPhotos,
                        transactions = dayTxs,
                        golfRounds = dayGolf,
                        vehicleLogs = dayVehicles
                    ).copy(
                        id = entry.id,
                        summary = if (hasIncomeOrTransferInSummary || entry.summary.contains("사진 촬영") || entry.summary.contains("서울 송파") || entry.summary.contains("서울 강남") || entry.summary.contains("서울 영등포구")) "" else entry.summary
                    )

                    val finalUpgraded = if (upgraded.summary.isBlank()) {
                        dailyRouteAggregator.aggregateForDate(
                            date = date,
                            photos = existingPhotos,
                            transactions = dayTxs,
                            golfRounds = dayGolf,
                            vehicleLogs = dayVehicles
                        ).copy(id = entry.id)
                    } else upgraded

                    diaryRepository.updateDiaryEntry(finalUpgraded)
                }
            }
        }
    }

    fun setViewMode(mode: TimelineViewMode) {
        _uiState.value = _uiState.value.copy(viewMode = mode)
        updateFilteredEntries()
    }

    fun setMapPeriodFilter(filter: MapPeriodFilter) {
        _uiState.value = _uiState.value.copy(mapPeriodFilter = filter)
        updateFilteredEntries()
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

    fun openPhotoPreview(url: String) {
        _uiState.value = _uiState.value.copy(selectedPhotoPreviewUrl = url)
    }

    fun closePhotoPreview() {
        _uiState.value = _uiState.value.copy(selectedPhotoPreviewUrl = null)
    }

    fun openAddCompanionDialog(step: RouteStep) {
        _uiState.value = _uiState.value.copy(isAddCompanionDialogOpen = true, companionTargetStep = step)
    }

    fun closeAddCompanionDialog() {
        _uiState.value = _uiState.value.copy(isAddCompanionDialogOpen = false, companionTargetStep = null)
    }

    fun addCompanionToStep(companionName: String) {
        val target = _uiState.value.companionTargetStep ?: return
        viewModelScope.launch(Dispatchers.IO) {
            val entry = rawEntries.find { it.routeSteps.any { s -> s.id == target.id || s.title == target.title } } ?: return@launch
            val updatedSteps = entry.routeSteps.map { step ->
                if (step.id == target.id || step.title == target.title) {
                    val newCompanions = (step.companions + companionName.trim()).distinct()
                    step.copy(companions = newCompanions)
                } else {
                    step
                }
            }
            val allCompanions = updatedSteps.flatMap { it.companions }.distinct()
            val updatedTags = (entry.tags + allCompanions.map { "👤 $it" }).distinct()
            val updatedEntry = entry.copy(
                routeSteps = updatedSteps,
                tags = updatedTags
            )
            diaryRepository.updateDiaryEntry(updatedEntry)
            closeAddCompanionDialog()
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
            syncHistoricalDataUseCase(context, daysBack = 7).collect { progress ->
                if (progress.isDone) {
                    updateFilteredEntries()
                    loadMonthlyCalendar(_uiState.value.currentYearMonth)
                }
            }
        }
    }

    fun triggerHistoricalSync(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncDialogVisible = true)
            syncHistoricalDataUseCase(context).collect { progress ->
                _uiState.value = _uiState.value.copy(syncProgress = progress)
            }
        }
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
