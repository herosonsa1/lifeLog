package com.autologue.app.presentation.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.preferences.CommuteConfig
import com.autologue.app.data.preferences.MultiVehiclePreferences
import com.autologue.app.data.preferences.UserLocationPreferences
import com.autologue.app.data.preferences.VehicleMaintenanceConfig
import com.autologue.app.data.preferences.VehicleMaintenancePreferences
import com.autologue.app.data.preferences.VehicleProfile
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.getAssignedVehicleId
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.util.FuelEconomyCalculator
import com.autologue.app.util.LocationDistanceUtils
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import com.autologue.app.domain.model.buildUpdatedNote
import com.autologue.app.domain.model.getExplicitUnitPrice
import com.autologue.app.domain.model.isCustomFuel
import javax.inject.Inject

enum class LogFilterMode {
    ALL,
    REFUEL_ONLY,
    DRIVING_ONLY
}

data class CarLedgerUiState(
    val logs: List<VehicleLog> = emptyList(),
    val totalFuelExpense: Long = 0L,
    val latestIntervalDays: Int? = null,
    val totalDrivingDistanceKm: Double = 0.0,
    val averageEfficiencyKmPerL: Double = 12.5,
    val isLoading: Boolean = true,
    val loadingTitle: String = "차계부 내역 조회 중",
    val loadingMessage: String = "차량 주유 및 주행 기록을 불러오고 있습니다...",
    val isSyncing: Boolean = false,
    val commuteConfig: CommuteConfig = CommuteConfig(),
    val showLocationDialog: Boolean = false,
    val maintenanceConfig: VehicleMaintenanceConfig = VehicleMaintenanceConfig(),
    val showMaintenanceDialog: Boolean = false,
    val vehicles: List<VehicleProfile> = emptyList(),
    val selectedVehicleId: String = "car_1",
    val selectedVehicleDefaultGasPrice: Double = 1650.0,
    val showVehicleManageDialog: Boolean = false,
    val filterCurrentVehicleOnly: Boolean = true,
    val editingRefuelLog: VehicleLog? = null,
    val logFilterMode: LogFilterMode = LogFilterMode.ALL,
    val showAddRefuelDialog: Boolean = false,
    val showPasteSmsDialog: Boolean = false,
    val syncResultMessage: String? = null,
    val totalRefuelCount: Int = 0,
    val totalDrivingCount: Int = 0,
    val isSelectionMode: Boolean = false,
    val selectedLogIds: Set<Long> = emptySet()
) {
    val filteredLogs: List<VehicleLog>
        get() = when (logFilterMode) {
            LogFilterMode.ALL -> logs
            LogFilterMode.REFUEL_ONLY -> logs.filter { it.logType == VehicleLogType.REFUELING || it.logType == VehicleLogType.MAINTENANCE }
            LogFilterMode.DRIVING_ONLY -> logs.filter { it.logType == VehicleLogType.TRIP_DRIVING }
        }
}

@HiltViewModel
class CarLedgerViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: android.content.Context,
    private val vehicleRepository: VehicleRepository,
    private val transactionRepository: TransactionRepository,
    private val diaryRepository: DiaryRepository,
    private val golfRepository: com.autologue.app.domain.repository.GolfRepository,
    private val locationPreferences: UserLocationPreferences,
    private val maintenancePreferences: VehicleMaintenancePreferences,
    private val multiVehiclePreferences: MultiVehiclePreferences,
    private val importer: com.autologue.app.data.sync.HistoricalDataImporter,
    private val processTransactionUseCase: com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
) : ViewModel() {

    private val placeResolver = com.autologue.app.data.sync.PlaceResolver()

    private val _uiState = MutableStateFlow(CarLedgerUiState())
    val uiState: StateFlow<CarLedgerUiState> = _uiState.asStateFlow()

    private var loadDataJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
        observeCommuteConfig()
        observeMaintenanceConfig()
        observeVehicles()
        observeExpenseTransactionsForVehicleSync()
    }

    private fun observeExpenseTransactionsForVehicleSync() {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            transactionRepository.getAllTransactionsFlow().collectLatest { txs ->
                if (txs.isNotEmpty()) {
                    runCatching { vehicleRepository.syncRefuelingFromTransactions(txs) }
                }
            }
        }
    }

    private suspend fun resolveCommuteNeighborhoods(cfg: CommuteConfig): Pair<String, String> {
        val homeHint = cfg.homeAddress.ifBlank { cfg.homeName }
        val compHint = cfg.companyAddress.ifBlank { cfg.companyName }
        val homeDong = runCatching {
            placeResolver.resolveNeighborhoodName(
                context = context,
                lat = cfg.homeLat,
                lng = cfg.homeLng,
                fallbackAddressHint = homeHint
            )
        }.getOrDefault("").ifBlank {
            placeResolver.inferNeighborhoodFromText(homeHint).ifBlank { cfg.homeName.ifBlank { "송파구 위례동" } }
        }
        val compDong = runCatching {
            placeResolver.resolveNeighborhoodName(
                context = context,
                lat = cfg.companyLat,
                lng = cfg.companyLng,
                fallbackAddressHint = compHint
            )
        }.getOrDefault("").ifBlank {
            placeResolver.inferNeighborhoodFromText(compHint).ifBlank { cfg.companyName.ifBlank { "영등포구 영등포동" } }
        }
        return Pair(homeDong, compDong)
    }

    private fun observeVehicles() {
        viewModelScope.launch {
            multiVehiclePreferences.vehicles.collectLatest { vList ->
                val currentSel = _uiState.value.selectedVehicleId
                val targetProfile = vList.find { it.id == currentSel }
                val targetEff = targetProfile?.targetEfficiencyKmPerL ?: 12.5
                val targetGasPrice = targetProfile?.defaultGasPrice ?: 1650.0
                _uiState.value = _uiState.value.copy(
                    vehicles = vList,
                    averageEfficiencyKmPerL = targetEff,
                    selectedVehicleDefaultGasPrice = targetGasPrice
                )
            }
        }
        viewModelScope.launch {
            multiVehiclePreferences.selectedVehicleId.collectLatest { selId ->
                val targetProfile = _uiState.value.vehicles.find { it.id == selId }
                val targetEff = targetProfile?.targetEfficiencyKmPerL ?: 12.5
                val targetGasPrice = targetProfile?.defaultGasPrice ?: 1650.0
                _uiState.value = _uiState.value.copy(
                    selectedVehicleId = selId,
                    averageEfficiencyKmPerL = targetEff,
                    selectedVehicleDefaultGasPrice = targetGasPrice
                )
                maintenancePreferences.setCurrentVehicleId(selId)
            }
        }
    }

    private fun observeMaintenanceConfig() {
        viewModelScope.launch {
            maintenancePreferences.config.collectLatest { mCfg ->
                _uiState.value = _uiState.value.copy(maintenanceConfig = mCfg)
            }
        }
    }

    private fun observeCommuteConfig() {
        viewModelScope.launch {
            locationPreferences.config.collectLatest { cfg ->
                _uiState.value = _uiState.value.copy(
                    commuteConfig = cfg,
                    // 최초 실행 시 설정되지 않았으면 자동으로 지도 다이얼로그 오픈
                    showLocationDialog = if (!cfg.isConfigured && _uiState.value.logs.isEmpty()) false else _uiState.value.showLocationDialog
                )
            }
        }
    }

    private fun loadData() {
        loadDataJob?.cancel()
        _uiState.value = _uiState.value.copy(
            isLoading = true,
            loadingTitle = "차계부 내역 조회 중",
            loadingMessage = "차량 주유 및 주행 기록을 불러오고 있습니다..."
        )
        loadDataJob = viewModelScope.launch {
            combine(
                vehicleRepository.getAllVehicleLogsFlow(),
                diaryRepository.getDiaryEntriesFlow(),
                multiVehiclePreferences.selectedVehicleId,
                multiVehiclePreferences.vehicles
            ) { logs, _, selectedId, vehicleList ->
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                    val vehicleProfile = vehicleList.find { it.id == selectedId }
                    val targetEff = vehicleProfile?.targetEfficiencyKmPerL ?: 12.5
                    val targetGasPrice = vehicleProfile?.defaultGasPrice ?: 1650.0

                    // [원칙] 차량 주행거리는 오직 실제 차량 블루투스 세션(TRIP_DRIVING)만 반영하며,
                    // 다이어리의 도보/대중교통 일반 이동은 차량 연비/소모품에 합산하지 않음
                    val diaryLookup: (LocalDateTime?, LocalDateTime) -> Double = { _, _ -> 0.0 }

                    // 1. 선택된 차량에 대해 Full-to-Full 주유 주기 및 구간 연비 계산 (차량별 설정 기본 유가 전달)
                    val calculationResult = FuelEconomyCalculator.calculateForVehicle(
                        targetVehicleId = selectedId,
                        allLogs = logs,
                        gasPrice = targetGasPrice,
                        additionalDrivingDistanceLookup = diaryLookup
                    )

                    // 계산된 주유 로그 맵 생성 (ID 기준)
                    val enrichedFuelMap = calculationResult.enrichedFuelLogs.associateBy { it.id }

                    // 전체 로그 리스트에 계산된 주유 로그 치환
                    val enrichedLogs = logs.map { log ->
                        enrichedFuelMap[log.id] ?: log
                    }

                    // 선택된 차량의 주유 로그 필터링 (미배정 자동 수집 주유 내역은 선택된 차량에 표시)
                    val vehicleFuelLogs = enrichedLogs.filter {
                        it.logType == VehicleLogType.REFUELING && it.getAssignedVehicleId(defaultVehicleId = selectedId) == selectedId
                    }
                    val totalFuel = vehicleFuelLogs.sumOf { it.fuelCost }

                    // 선택된 차량의 총 주행거리 (주행 로그 및 다이어리 종합)
                    val vehicleDrivingLogs = enrichedLogs.filter {
                        it.logType == VehicleLogType.TRIP_DRIVING && it.getAssignedVehicleId(defaultVehicleId = selectedId) == selectedId
                    }
                    val vehicleLogDist = vehicleDrivingLogs.sumOf { it.tripDistanceKm }
                    val diaryDist = diaryLookup(null, LocalDateTime.now())
                    val totalDist = if (vehicleLogDist > 0.0) {
                        if (selectedId == "car_1") maxOf(vehicleLogDist, diaryDist) else vehicleLogDist
                    } else diaryDist

                    // 총 주유 리터 합계 (1,650원 기준 환산량 반영)
                    val totalFuelLiters = vehicleFuelLogs.sumOf { it.fuelAmountLiters }
                    val rawEffFromTotal = if (totalDist > 0.0 && totalFuelLiters > 0.0) {
                        Math.round((totalDist / totalFuelLiters) * 10.0) / 10.0
                    } else null

                    // 최종 평균 연비 (구간 가중평균 우선 -> 전체 누적 연비 -> 차량 프로필 목표 연비)
                    val avgEff = when {
                        calculationResult.weightedAverageEfficiencyKmPerL != null &&
                        calculationResult.weightedAverageEfficiencyKmPerL in 4.0..30.0 ->
                            calculationResult.weightedAverageEfficiencyKmPerL
                        rawEffFromTotal != null && rawEffFromTotal in 4.0..30.0 ->
                            rawEffFromTotal
                        else -> targetEff
                    }

                    // 표시할 로그 목록: 현재 차량 필터 여부에 따라 분기
                    val displayedLogs = if (_uiState.value.filterCurrentVehicleOnly) {
                        enrichedLogs.filter { it.getAssignedVehicleId(defaultVehicleId = selectedId) == selectedId }
                    } else {
                        enrichedLogs
                    }

                    val refuelCount = displayedLogs.count { it.logType == VehicleLogType.REFUELING || it.logType == VehicleLogType.MAINTENANCE }
                    val drivingCount = displayedLogs.count { it.logType == VehicleLogType.TRIP_DRIVING }

                    _uiState.value.copy(
                        isLoading = false,
                        selectedVehicleId = selectedId,
                        selectedVehicleDefaultGasPrice = targetGasPrice,
                        logs = displayedLogs,
                        totalFuelExpense = totalFuel,
                        latestIntervalDays = calculationResult.latestIntervalDays ?: vehicleFuelLogs.firstOrNull()?.daysSinceLastFuel,
                        totalDrivingDistanceKm = Math.round(totalDist * 10.0) / 10.0,
                        averageEfficiencyKmPerL = avgEff,
                        totalRefuelCount = refuelCount,
                        totalDrivingCount = drivingCount
                    )
                }
            }.collectLatest { nextState ->
                _uiState.value = nextState
            }
        }
    }

    fun toggleVehicleFilter() {
        val newFilter = !_uiState.value.filterCurrentVehicleOnly
        _uiState.value = _uiState.value.copy(filterCurrentVehicleOnly = newFilter)
        loadData()
    }

    fun toggleSelectionMode() {
        val next = !_uiState.value.isSelectionMode
        _uiState.value = _uiState.value.copy(
            isSelectionMode = next,
            selectedLogIds = if (next) _uiState.value.selectedLogIds else emptySet()
        )
    }

    fun toggleSelectLog(logId: Long) {
        val current = _uiState.value.selectedLogIds.toMutableSet()
        if (current.contains(logId)) {
            current.remove(logId)
        } else {
            current.add(logId)
        }
        _uiState.value = _uiState.value.copy(selectedLogIds = current)
    }

    fun selectAllVisibleLogs() {
        val visibleIds = _uiState.value.filteredLogs.map { it.id }.toSet()
        val current = _uiState.value.selectedLogIds
        val next = if (visibleIds.isNotEmpty() && current.containsAll(visibleIds)) {
            current - visibleIds
        } else {
            current + visibleIds
        }
        _uiState.value = _uiState.value.copy(selectedLogIds = next)
    }

    fun deleteSelectedLogs() {
        val ids = _uiState.value.selectedLogIds.toList()
        if (ids.isEmpty()) return
        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "차계부 기록 삭제 및 화면 갱신 중",
                message = "선택한 주유/주행 기록을 삭제하고 화면을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
            ) {
                val count = vehicleRepository.deleteVehicleLogs(ids)
                _uiState.value = _uiState.value.copy(
                    isSelectionMode = false,
                    selectedLogIds = emptySet(),
                    syncResultMessage = "선택한 차계부 기록(주유/주행) ${count}건을 영구 삭제했습니다. (자동 동기화 시 재등록 차단)"
                )
            }
        }
    }

    fun deleteSingleLog(logId: Long) {
        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "차계부 기록 삭제 및 화면 갱신 중",
                message = "해당 기록을 삭제하고 화면을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
            ) {
                vehicleRepository.deleteVehicleLog(logId)
                _uiState.value = _uiState.value.copy(
                    editingRefuelLog = null,
                    syncResultMessage = "해당 기록을 영구 삭제했습니다. (자동 동기화 시 재등록 차단)"
                )
            }
        }
    }

    fun assignVehicleToFuelLog(logId: Long, targetVehicleId: String) {
        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "차량 지정 및 화면 갱신 중",
                message = "선택한 차량으로 기록을 변경하고 통계를 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
            ) {
                val log = vehicleRepository.getVehicleLogById(logId) ?: return@runWithLoading
                val currentNote = log.note ?: ""
                // 기존 [car_1], [car_2], [차량 1], [차량 2] 태그 제거 후 새 차량 태그 지정
                val cleanNote = currentNote.replace(Regex("\\[(car_1|car_2|차량 1|차량 2)[^\\]]*\\]\\s*"), "").trim()
                val targetVehicle = _uiState.value.vehicles.find { it.id == targetVehicleId }
                val vName = targetVehicle?.name?.split(" ")?.firstOrNull() ?: if (targetVehicleId == "car_2") "차량 2" else "차량 1"
                val newNote = "[$targetVehicleId: $vName] $cleanNote".trim()
                vehicleRepository.updateVehicleLog(log.copy(note = newNote))
            }
        }
    }

    fun openRefuelEditDialog(log: VehicleLog) {
        _uiState.value = _uiState.value.copy(editingRefuelLog = log)
    }

    fun closeRefuelEditDialog() {
        _uiState.value = _uiState.value.copy(editingRefuelLog = null)
    }

    fun updateRefuelDetail(
        logId: Long,
        gasStationName: String = "",
        targetVehicleId: String,
        fuelCost: Long,
        explicitLiters: Double?,
        unitPrice: Double?,
        isOneTimeSave: Boolean = false
    ) {
        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "주유 내역 저장 및 화면 갱신 중",
                message = "주유 내역 변경 사항을 반영하고 화면을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
            ) {
                val log = vehicleRepository.getVehicleLogById(logId) ?: return@runWithLoading
                val oldStationName = log.gasStationName?.trim().orEmpty()
                val finalStationName = gasStationName.trim().ifBlank { oldStationName.ifBlank { "주유소" } }
                val isStationRenamed = finalStationName.isNotBlank() && finalStationName != oldStationName

                val targetVehicle = _uiState.value.vehicles.find { it.id == targetVehicleId }
                val vName = targetVehicle?.name?.split(" ")?.firstOrNull() ?: if (targetVehicleId == "car_2") "차량 2" else "차량 1"

                val isCustom = (explicitLiters != null && explicitLiters > 0.0) || (unitPrice != null && unitPrice > 0.0)
                val baseUpdatedNote = log.buildUpdatedNote(
                    targetVehicleId = targetVehicleId,
                    vehicleShortName = vName,
                    unitPrice = unitPrice,
                    isCustom = isCustom
                ).replace("[one_time_edit:true]", "").trim()

                var updatedNote = if (isStationRenamed && !baseUpdatedNote.contains("[custom_name:true]")) {
                    "$baseUpdatedNote [custom_name:true]".trim()
                } else {
                    baseUpdatedNote
                }
                if (isOneTimeSave) {
                    updatedNote = "$updatedNote [one_time_edit:true]".trim()
                }

                val fallbackGasPrice = targetVehicle?.defaultGasPrice ?: FuelEconomyCalculator.DEFAULT_GAS_PRICE
                val finalLiters = when {
                    explicitLiters != null && explicitLiters > 0.0 -> Math.round(explicitLiters * 10.0) / 10.0
                    unitPrice != null && unitPrice > 0.0 && fuelCost > 0L -> Math.round((fuelCost.toDouble() / unitPrice) * 10.0) / 10.0
                    else -> Math.round((fuelCost.toDouble() / fallbackGasPrice) * 10.0) / 10.0
                }

                val updatedLog = log.copy(
                    gasStationName = finalStationName,
                    fuelCost = fuelCost,
                    fuelAmountLiters = finalLiters,
                    note = updatedNote
                )
                vehicleRepository.updateVehicleLog(updatedLog)

                if (isOneTimeSave) {
                    _uiState.value = _uiState.value.copy(
                        syncResultMessage = "주유 내역을 1회성 저장했습니다. (동일 주유소의 다른 내역에는 영향을 주지 않으며 고정 보호됩니다)"
                    )
                } else if (isStationRenamed && oldStationName.isNotBlank() && oldStationName != "주유소") {
                    val fuelRenamed = vehicleRepository.renameGasStationGlobally(
                        oldStationName = oldStationName,
                        newStationName = finalStationName
                    )
                    val txRenamed = transactionRepository.renameMerchantGlobally(
                        oldMerchantName = oldStationName,
                        newMerchantName = finalStationName
                    )
                    val totalRenamed = fuelRenamed + txRenamed
                    val extraMsg = if (totalRenamed > 0) " (동일 결제처 ${totalRenamed}건 일괄 치환 완료)" else ""
                    _uiState.value = _uiState.value.copy(
                        syncResultMessage = "결제명(주유소명)을 '$finalStationName'(으)로 수정했습니다. 같은 결제처 항목은 계속 자동 치환됩니다.$extraMsg"
                    )
                }
                closeRefuelEditDialog()
            }
        }
    }

    fun setLogFilterMode(mode: LogFilterMode) {
        com.autologue.app.presentation.common.GlobalLoadingRegistry.trackUiUpdate(
            scope = viewModelScope,
            title = "차계부 필터 전환 및 화면 갱신 중",
            message = "선택한 조회 구분으로 기록을 갱신하고 있습니다...",
            accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
        ) {
            _uiState.value = _uiState.value.copy(logFilterMode = mode)
        }
    }

    fun openAddRefuelDialog() {
        _uiState.value = _uiState.value.copy(showAddRefuelDialog = true)
    }

    fun closeAddRefuelDialog() {
        _uiState.value = _uiState.value.copy(showAddRefuelDialog = false)
    }

    fun dismissSyncResultMessage() {
        _uiState.value = _uiState.value.copy(syncResultMessage = null)
    }

    fun addManualRefuelLog(
        vehicleId: String,
        gasStationName: String,
        fuelCost: Long,
        explicitLiters: Double?,
        unitPrice: Double?,
        timestamp: LocalDateTime
    ) {
        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "주유 기록 추가 및 화면 갱신 중",
                message = "입력하신 주유 기록을 차계부와 가계부에 저장하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
            ) {
                val targetVehicle = _uiState.value.vehicles.find { it.id == vehicleId }
                val vName = targetVehicle?.name?.split(" ")?.firstOrNull() ?: if (vehicleId == "car_2") "차량 2" else "차량 1"
                val fallbackGasPrice = targetVehicle?.defaultGasPrice ?: FuelEconomyCalculator.DEFAULT_GAS_PRICE

                val finalLiters = when {
                    explicitLiters != null && explicitLiters > 0.0 -> Math.round(explicitLiters * 10.0) / 10.0
                    unitPrice != null && unitPrice > 0.0 && fuelCost > 0L -> Math.round((fuelCost.toDouble() / unitPrice) * 10.0) / 10.0
                    else -> Math.round((fuelCost.toDouble() / fallbackGasPrice) * 10.0) / 10.0
                }

                val tags = mutableListOf<String>()
                tags.add("[$vehicleId: $vName]")
                if (unitPrice != null && unitPrice > 0.0) {
                    tags.add("[unit_price: %.1f]".format(java.util.Locale.US, unitPrice))
                }
                tags.add("[custom_fuel:true]")
                val noteStr = (tags.joinToString(" ") + " 차계부 직접 등록").trim()

                val lastFuel = vehicleRepository.getLatestRefuelingLog()
                val daysSince = if (lastFuel != null) {
                    java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), timestamp.toLocalDate()).toInt()
                } else null

                // 1. 차계부 VehicleLog 등록
                val vLog = VehicleLog(
                    timestamp = timestamp,
                    logType = VehicleLogType.REFUELING,
                    fuelCost = fuelCost,
                    fuelAmountLiters = finalLiters,
                    daysSinceLastFuel = daysSince,
                    gasStationName = gasStationName.ifBlank { "주유소" },
                    note = noteStr
                )
                vehicleRepository.insertVehicleLog(vLog)

                // 2. 가계부 Transaction 동시 등록 (데이터 일관성 유지)
                val tx = com.autologue.app.domain.model.Transaction(
                    amount = fuelCost,
                    merchantName = gasStationName.ifBlank { "주유소" },
                    originalText = "[차계부 직접 입력] $gasStationName %,d원".format(fuelCost),
                    timestamp = timestamp,
                    paymentMethod = com.autologue.app.domain.model.PaymentMethod.CREDIT_CARD,
                    category = com.autologue.app.domain.model.ExpenseCategory.FUEL,
                    cardOrBankName = "차계부 직접 입력",
                    transferMemo = "차계부 주유 등록 연동",
                    isAutoCategorized = true
                )
                transactionRepository.insertTransaction(tx)

                closeAddRefuelDialog()
            }
        }
    }

    fun showPermissionDeniedMessage() {
        _uiState.value = _uiState.value.copy(
            syncResultMessage = "문자(SMS) 읽기 권한이 허용되지 않았습니다. 앱 설정에서 권한을 허용해주세요."
        )
    }

    fun manualSyncRefueling() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                loadingTitle = "주유 내역 동기화 중",
                loadingMessage = "최근 결제 문자 및 주유 기록을 정밀 분석하고 있습니다..."
            )
            val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                // 0. 기존 DB 내 오분류된 카드/주유 내역 자가 치유 선행
                runCatching { transactionRepository.cleanDuplicates() }

                val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                    context,
                    android.Manifest.permission.READ_SMS
                ) == android.content.pm.PackageManager.PERMISSION_GRANTED

                val cfg = locationPreferences.config.value
                val (home, comp) = resolveCommuteNeighborhoods(cfg)
                val dist = if (cfg.commuteRoundTripKm > 0.0) cfg.commuteRoundTripKm else 54.2
                runCatching { vehicleRepository.cleanDuplicatesAndCorruptedLogs(home, comp, dist) }

                // 1. 기기 결제 문자(SMS/LMS/MMS/RCS) 최근 365일 전수 스캔 및 가계부/차계부 자동 적재
                var scanResult = com.autologue.app.data.sync.SmsScanResult(emptyList(), hasPerm, 0)
                if (hasPerm) {
                    runCatching {
                        scanResult = importer.scanHistoricalSmsDetailed(context, daysBack = 365, limit = 5000)
                        for (tx in scanResult.transactions) {
                            processTransactionUseCase(tx)
                        }
                    }.onFailure {
                        android.util.Log.e("CarLedgerViewModel", "SMS/MMS 스캔 또는 처리 중 예외", it)
                    }
                }

                // 2. 실제 골프 라운드 DB(18홀 유효)와 대조 전, 미래 오탐(10/24 등) 및 '나인CC' 가짜 라운드 우선 제거
                val today = LocalDate.now()
                runCatching {
                    val rawRounds = golfRepository.getAllGolfRoundsList()
                    for (r in rawRounds) {
                        val rDay = (r.startTime ?: r.roundDate).toLocalDate()
                        val isFakeNine = r.clubName.contains("나인CC", ignoreCase = true) ||
                            r.clubName.contains("나인 CC", ignoreCase = true) ||
                            r.clubName == "나인"
                        val isFutureFalseOcr = rDay.isAfter(today) && (
                            r.totalScore != null ||
                            r.holeScores.isNotEmpty() ||
                            !r.scorecardPhotoUri.isNullOrBlank() ||
                            r.matchingPhotoUris.isNotEmpty() ||
                            isFakeNine ||
                            rDay == LocalDate.of(2026, 10, 24)
                        )
                        if (isFakeNine || isFutureFalseOcr) {
                            golfRepository.deleteGolfRound(r.id)
                        }
                    }
                    diaryRepository.cleanDuplicates()
                }
                val allRounds = runCatching { golfRepository.getAllGolfRoundsList() }.getOrDefault(emptyList())
                vehicleRepository.syncAndCleanWithGolfRounds(allRounds)

                // 3. 가짜 다이어리 이동 및 비정상 더미 주행 기록 상시 정제
                vehicleRepository.cleanDuplicatesAndCorruptedLogs(home, comp, dist)

                // 4. 주유 결제 내역 동기화 (셀프주유소 가승인 15만원 자동 선별 및 실주유액 등록)
                runCatching { transactionRepository.cleanDuplicates() }
                val txs = transactionRepository.getAllTransactionsFlow().first()
                val addedCount = vehicleRepository.syncRefuelingFromTransactions(txs)

                // 5. 재정제 및 골프 정합성 확정
                vehicleRepository.cleanDuplicatesAndCorruptedLogs(home, comp, dist)
                vehicleRepository.syncAndCleanWithGolfRounds(allRounds)

                val totalFuelLogs = vehicleRepository.getRefuelingLogsFlow().first().size

                val totalScanned = scanResult.totalMessagesScanned
                when {
                    !hasPerm && totalFuelLogs == 0 ->
                        "문자(SMS) 읽기 권한이 필요합니다. [주유 동기화]를 눌러 권한을 허용해주세요."
                    addedCount > 0 ->
                        "결제 문자에서 주유 기록 ${addedCount}건을 새로 자동 동기화했습니다 (총 ${totalFuelLogs}건)."
                    totalFuelLogs > 0 ->
                        "모든 주유 결제 내역(총 ${totalFuelLogs}건)이 최신 상태로 자동 동기화되었습니다."
                    totalScanned > 0 ->
                        "기기 문자 총 ${totalScanned}건을 분석했으나 신규 주유 내역을 찾지 못했습니다."
                    else ->
                        "주유 결제 내역 스캔을 완료했습니다."
                }
            }
            _uiState.value = _uiState.value.copy(isSyncing = false, syncResultMessage = msg)
        }
    }

    fun openPasteSmsDialog() {
        _uiState.value = _uiState.value.copy(showPasteSmsDialog = true)
    }

    fun closePasteSmsDialog() {
        _uiState.value = _uiState.value.copy(showPasteSmsDialog = false)
    }

    fun importPastedTransactions(transactions: List<Transaction>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                loadingTitle = "문자 내역 반영 중",
                loadingMessage = "붙여넣은 결제 문자를 가계부·차계부에 반영하고 있습니다...",
                showPasteSmsDialog = false
            )
            val msg = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                var addedTxCount = 0
                for (tx in transactions) {
                    runCatching {
                        processTransactionUseCase(tx)
                        addedTxCount++
                    }
                }
                val cfg = locationPreferences.config.value
                val (home, comp) = resolveCommuteNeighborhoods(cfg)
                val dist = if (cfg.commuteRoundTripKm > 0.0) cfg.commuteRoundTripKm else 54.2

                val txs = transactionRepository.getAllTransactionsFlow().first()
                val addedFuelCount = vehicleRepository.syncRefuelingFromTransactions(txs)
                vehicleRepository.cleanDuplicatesAndCorruptedLogs(home, comp, dist)

                val totalFuel = vehicleRepository.getRefuelingLogsFlow().first().size
                if (addedFuelCount > 0) {
                    "결제 내역 ${transactions.size}건을 등록했습니다. (가계부 ${addedTxCount}건, 차계부 주유 ${addedFuelCount}건 반영, 총 주유 ${totalFuel}건)"
                } else {
                    "결제 내역 ${transactions.size}건을 가계부에 성공적으로 등록했습니다."
                }
            }
            _uiState.value = _uiState.value.copy(
                isSyncing = false,
                syncResultMessage = msg
            )
        }
    }

    fun openLocationDialog() {
        _uiState.value = _uiState.value.copy(showLocationDialog = true)
    }

    fun closeLocationDialog() {
        _uiState.value = _uiState.value.copy(showLocationDialog = false)
    }

    fun saveCommuteConfig(
        homeName: String,
        homeAddress: String,
        homeLat: Double,
        homeLng: Double,
        companyName: String,
        companyAddress: String,
        companyLat: Double,
        companyLng: Double,
        roundTripKm: Double,
        carBt: String
    ) {
        locationPreferences.updateConfig(
            homeName = homeName,
            homeAddress = homeAddress,
            homeLat = homeLat,
            homeLng = homeLng,
            companyName = companyName,
            companyAddress = companyAddress,
            companyLat = companyLat,
            companyLng = companyLng,
            roundTripKm = roundTripKm,
            carBluetoothDevice = carBt
        )
        closeLocationDialog()

        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "출퇴근 설정 저장 및 화면 갱신 중",
                message = "출퇴근 거점 설정을 반영하고 주행 기록을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
            ) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val (homeDong, compDong) = resolveCommuteNeighborhoods(locationPreferences.config.value)
                    vehicleRepository.cleanDuplicatesAndCorruptedLogs(
                        homeName = homeDong,
                        companyName = compDong,
                        commuteDistanceKm = roundTripKm
                    )
                }
            }
        }
    }

    fun recordManualCommute(isToWork: Boolean) {
        viewModelScope.launch {
            com.autologue.app.presentation.common.GlobalLoadingRegistry.runWithLoading(
                title = "출퇴근 주행 기록 및 화면 갱신 중",
                message = "출퇴근 주행 기록을 추가하고 화면을 갱신하고 있습니다...",
                accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
            ) {
                kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    val cfg = locationPreferences.config.value
                    val (homeDong, compDong) = resolveCommuteNeighborhoods(cfg)
                    val dist = if (cfg.commuteOneWayKm > 0) cfg.commuteOneWayKm else (cfg.commuteRoundTripKm / 2.0).coerceAtLeast(1.0)
                    vehicleRepository.recordCommuteTrip(
                        isToWork = isToWork,
                        homeName = homeDong,
                        companyName = compDong,
                        distanceKm = dist
                    )
                }
            }
        }
    }

    fun clearAllDummyLogs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isSyncing = true,
                loadingTitle = "주행 기록 정리 중",
                loadingMessage = "비정상 주행 기록을 정리하고 있습니다..."
            )
            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                vehicleRepository.clearTripDrivingLogs()
                locationPreferences.resetConfig()
                // 유효한 18홀 라운드에 대한 정상 주행기록만 복원
                val allRounds = runCatching { golfRepository.getAllGolfRoundsList() }.getOrDefault(emptyList())
                vehicleRepository.syncAndCleanWithGolfRounds(allRounds)
            }
            _uiState.value = _uiState.value.copy(isSyncing = false)
        }
    }

    fun resetCommuteSettings() {
        locationPreferences.resetConfig()
    }

    fun openMaintenanceDialog() {
        _uiState.value = _uiState.value.copy(showMaintenanceDialog = true)
    }

    fun closeMaintenanceDialog() {
        _uiState.value = _uiState.value.copy(showMaintenanceDialog = false)
    }

    fun updateMaintenanceConfig(
        engineOilLastKm: Int,
        engineOilDate: String,
        engineOilInterval: Int,
        airconLastKm: Int,
        airconDate: String,
        airconInterval: Int,
        tireLastKm: Int,
        tireDate: String,
        tireInterval: Int
    ) {
        com.autologue.app.presentation.common.GlobalLoadingRegistry.trackUiUpdate(
            scope = viewModelScope,
            title = "소모품 설정 저장 및 화면 갱신 중",
            message = "차량 소모품 관리 주기를 반영하고 화면을 갱신하고 있습니다...",
            accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
        ) {
            maintenancePreferences.updateConfig(
                engineOilLastKm = engineOilLastKm,
                engineOilDate = engineOilDate,
                engineOilInterval = engineOilInterval,
                airconLastKm = airconLastKm,
                airconDate = airconDate,
                airconInterval = airconInterval,
                tireLastKm = tireLastKm,
                tireDate = tireDate,
                tireInterval = tireInterval
            )
            closeMaintenanceDialog()
        }
    }

    fun markConsumableReplaced(itemId: String) {
        val currentDist = _uiState.value.totalDrivingDistanceKm.toInt()
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        maintenancePreferences.markReplaced(itemId, currentDist, today)
    }

    fun resetMaintenanceDefaults() {
        maintenancePreferences.resetToDefaults()
    }

    fun selectVehicle(vehicleId: String) {
        if (_uiState.value.selectedVehicleId != vehicleId) {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                loadingTitle = "차량 전환 중",
                loadingMessage = "선택한 차량의 주유 및 주행 기록을 계산하고 있습니다..."
            )
        }
        multiVehiclePreferences.selectVehicle(vehicleId)
        maintenancePreferences.setCurrentVehicleId(vehicleId)
    }

    fun openVehicleManageDialog() {
        _uiState.value = _uiState.value.copy(showVehicleManageDialog = true)
    }

    fun closeVehicleManageDialog() {
        _uiState.value = _uiState.value.copy(showVehicleManageDialog = false)
    }

    fun saveVehicleProfiles(car1: VehicleProfile, car2: VehicleProfile) {
        com.autologue.app.presentation.common.GlobalLoadingRegistry.trackUiUpdate(
            scope = viewModelScope,
            title = "차량 프로필 저장 및 화면 갱신 중",
            message = "차량 프로필 정보를 저장하고 통계를 갱신하고 있습니다...",
            accentColor = com.autologue.app.presentation.theme.MenuColors.carLedger
        ) {
            multiVehiclePreferences.updateVehicle(
                id = car1.id,
                name = car1.name,
                licensePlate = car1.licensePlate,
                fuelType = car1.fuelType,
                bluetoothDevice = car1.bluetoothDevice,
                initialOdometerKm = car1.initialOdometerKm,
                targetEfficiencyKmPerL = car1.targetEfficiencyKmPerL,
                defaultGasPrice = car1.defaultGasPrice
            )
            multiVehiclePreferences.updateVehicle(
                id = car2.id,
                name = car2.name,
                licensePlate = car2.licensePlate,
                fuelType = car2.fuelType,
                bluetoothDevice = car2.bluetoothDevice,
                initialOdometerKm = car2.initialOdometerKm,
                targetEfficiencyKmPerL = car2.targetEfficiencyKmPerL,
                defaultGasPrice = car2.defaultGasPrice
            )
            closeVehicleManageDialog()
        }
    }
}

