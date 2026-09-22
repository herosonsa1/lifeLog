package com.autologue.app.presentation.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.preferences.CommuteConfig
import com.autologue.app.data.preferences.MultiVehiclePreferences
import com.autologue.app.data.preferences.UserLocationPreferences
import com.autologue.app.data.preferences.VehicleMaintenanceConfig
import com.autologue.app.data.preferences.VehicleMaintenancePreferences
import com.autologue.app.data.preferences.VehicleProfile
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
    val syncResultMessage: String? = null,
    val totalRefuelCount: Int = 0,
    val totalDrivingCount: Int = 0
) {
    val filteredLogs: List<VehicleLog>
        get() = when (logFilterMode) {
            LogFilterMode.ALL -> logs
            LogFilterMode.REFUEL_ONLY -> logs.filter { it.logType == VehicleLogType.REFUELING }
            LogFilterMode.DRIVING_ONLY -> logs.filter { it.logType == VehicleLogType.TRIP_DRIVING }
        }
}

@HiltViewModel
class CarLedgerViewModel @Inject constructor(
    private val vehicleRepository: VehicleRepository,
    private val transactionRepository: TransactionRepository,
    private val diaryRepository: DiaryRepository,
    private val golfRepository: com.autologue.app.domain.repository.GolfRepository,
    private val locationPreferences: UserLocationPreferences,
    private val maintenancePreferences: VehicleMaintenancePreferences,
    private val multiVehiclePreferences: MultiVehiclePreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(CarLedgerUiState())
    val uiState: StateFlow<CarLedgerUiState> = _uiState.asStateFlow()

    init {
        loadData()
        observeCommuteConfig()
        observeMaintenanceConfig()
        observeVehicles()
        manualSyncRefueling()
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
        viewModelScope.launch {
            combine(
                vehicleRepository.getAllVehicleLogsFlow(),
                diaryRepository.getDiaryEntriesFlow(),
                multiVehiclePreferences.selectedVehicleId
            ) { logs, diaryEntries, selectedId ->
                val vehicleProfile = _uiState.value.vehicles.find { it.id == selectedId }
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

                // 선택된 차량의 주유 로그 필터링
                val vehicleFuelLogs = enrichedLogs.filter {
                    it.logType == VehicleLogType.REFUELING && it.getAssignedVehicleId() == selectedId
                }
                val totalFuel = vehicleFuelLogs.sumOf { it.fuelCost }

                // 선택된 차량의 총 주행거리 (주행 로그 및 다이어리 종합)
                val vehicleDrivingLogs = enrichedLogs.filter {
                    it.logType == VehicleLogType.TRIP_DRIVING && it.getAssignedVehicleId() == selectedId
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
                    enrichedLogs.filter { it.getAssignedVehicleId() == selectedId }
                } else {
                    enrichedLogs
                }

                val refuelCount = displayedLogs.count { it.logType == VehicleLogType.REFUELING }
                val drivingCount = displayedLogs.count { it.logType == VehicleLogType.TRIP_DRIVING }

                _uiState.value = _uiState.value.copy(
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
            }.collectLatest { }
        }
    }

    fun toggleVehicleFilter() {
        val newFilter = !_uiState.value.filterCurrentVehicleOnly
        _uiState.value = _uiState.value.copy(filterCurrentVehicleOnly = newFilter)
        loadData()
    }

    fun assignVehicleToFuelLog(logId: Long, targetVehicleId: String) {
        viewModelScope.launch {
            val log = vehicleRepository.getVehicleLogById(logId) ?: return@launch
            val currentNote = log.note ?: ""
            // 기존 [car_1], [car_2], [차량 1], [차량 2] 태그 제거 후 새 차량 태그 지정
            val cleanNote = currentNote.replace(Regex("\\[(car_1|car_2|차량 1|차량 2)[^\\]]*\\]\\s*"), "").trim()
            val targetVehicle = _uiState.value.vehicles.find { it.id == targetVehicleId }
            val vName = targetVehicle?.name?.split(" ")?.firstOrNull() ?: if (targetVehicleId == "car_2") "차량 2" else "차량 1"
            val newNote = "[$targetVehicleId: $vName] $cleanNote".trim()
            vehicleRepository.updateVehicleLog(log.copy(note = newNote))
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
        targetVehicleId: String,
        fuelCost: Long,
        explicitLiters: Double?,
        unitPrice: Double?
    ) {
        viewModelScope.launch {
            val log = vehicleRepository.getVehicleLogById(logId) ?: return@launch
            val targetVehicle = _uiState.value.vehicles.find { it.id == targetVehicleId }
            val vName = targetVehicle?.name?.split(" ")?.firstOrNull() ?: if (targetVehicleId == "car_2") "차량 2" else "차량 1"

            val isCustom = (explicitLiters != null && explicitLiters > 0.0) || (unitPrice != null && unitPrice > 0.0)
            val updatedNote = log.buildUpdatedNote(
                targetVehicleId = targetVehicleId,
                vehicleShortName = vName,
                unitPrice = unitPrice,
                isCustom = isCustom
            )

            val fallbackGasPrice = targetVehicle?.defaultGasPrice ?: FuelEconomyCalculator.DEFAULT_GAS_PRICE
            val finalLiters = when {
                explicitLiters != null && explicitLiters > 0.0 -> Math.round(explicitLiters * 10.0) / 10.0
                unitPrice != null && unitPrice > 0.0 && fuelCost > 0L -> Math.round((fuelCost.toDouble() / unitPrice) * 10.0) / 10.0
                else -> Math.round((fuelCost.toDouble() / fallbackGasPrice) * 10.0) / 10.0
            }

            val updatedLog = log.copy(
                fuelCost = fuelCost,
                fuelAmountLiters = finalLiters,
                note = updatedNote
            )
            vehicleRepository.updateVehicleLog(updatedLog)
            closeRefuelEditDialog()
        }
    }

    fun setLogFilterMode(mode: LogFilterMode) {
        _uiState.value = _uiState.value.copy(logFilterMode = mode)
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

    fun manualSyncRefueling() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val cfg = locationPreferences.config.value
            val home = if (cfg.homeName.isNotBlank()) cfg.homeName else "우리집"
            val comp = if (cfg.companyName.isNotBlank()) cfg.companyName else "회사"
            val dist = if (cfg.commuteRoundTripKm > 0.0) cfg.commuteRoundTripKm else 25.0

            // 1. 실제 골프 라운드 DB(18홀 유효)와 대조하여 누락된 주행기록 자동 복원 및 유령 레코드(8.8, 9.1 등) 영구 삭제
            val allRounds = runCatching { golfRepository.getAllGolfRoundsList() }.getOrDefault(emptyList())
            vehicleRepository.syncAndCleanWithGolfRounds(allRounds)

            // 2. 가짜 다이어리 이동 및 비정상 더미 주행 기록 상시 정제
            vehicleRepository.cleanDuplicatesAndCorruptedLogs(home, comp, dist)

            // 3. 주유 결제 내역 동기화
            val txs = transactionRepository.getAllTransactionsFlow().first()
            val addedCount = vehicleRepository.syncRefuelingFromTransactions(txs)

            // 4. 재정제 및 골프 정합성 확정
            vehicleRepository.cleanDuplicatesAndCorruptedLogs(home, comp, dist)
            vehicleRepository.syncAndCleanWithGolfRounds(allRounds)

            val msg = if (addedCount > 0) {
                "가계부 결제 내역에서 주유 기록 ${addedCount}건을 새로 동기화했습니다."
            } else {
                "모든 주유 결제 내역이 이미 최신 상태로 동기화되어 있습니다."
            }
            _uiState.value = _uiState.value.copy(isSyncing = false, syncResultMessage = msg)
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
            vehicleRepository.cleanDuplicatesAndCorruptedLogs(
                homeName = homeName,
                companyName = companyName,
                commuteDistanceKm = roundTripKm
            )
        }
    }

    fun recordManualCommute(isToWork: Boolean) {
        viewModelScope.launch {
            val cfg = locationPreferences.config.value
            val dist = if (cfg.commuteOneWayKm > 0) cfg.commuteOneWayKm else (cfg.commuteRoundTripKm / 2.0).coerceAtLeast(1.0)
            vehicleRepository.recordCommuteTrip(
                isToWork = isToWork,
                homeName = cfg.homeName.ifBlank { "우리집" },
                companyName = cfg.companyName.ifBlank { "회사" },
                distanceKm = dist
            )
        }
    }

    fun clearAllDummyLogs() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            vehicleRepository.clearTripDrivingLogs()
            locationPreferences.resetConfig()
            // 유효한 18홀 라운드에 대한 정상 주행기록만 복원
            val allRounds = runCatching { golfRepository.getAllGolfRoundsList() }.getOrDefault(emptyList())
            vehicleRepository.syncAndCleanWithGolfRounds(allRounds)
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

    fun markConsumableReplaced(itemId: String) {
        val currentDist = _uiState.value.totalDrivingDistanceKm.toInt()
        val today = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd"))
        maintenancePreferences.markReplaced(itemId, currentDist, today)
    }

    fun resetMaintenanceDefaults() {
        maintenancePreferences.resetToDefaults()
    }

    fun selectVehicle(vehicleId: String) {
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

