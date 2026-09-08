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
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import javax.inject.Inject

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
    val showVehicleManageDialog: Boolean = false
)

@HiltViewModel
class CarLedgerViewModel @Inject constructor(
    private val vehicleRepository: VehicleRepository,
    private val transactionRepository: TransactionRepository,
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
                val targetEff = vList.find { it.id == currentSel }?.targetEfficiencyKmPerL ?: 12.5
                _uiState.value = _uiState.value.copy(
                    vehicles = vList,
                    averageEfficiencyKmPerL = targetEff
                )
            }
        }
        viewModelScope.launch {
            multiVehiclePreferences.selectedVehicleId.collectLatest { selId ->
                val targetEff = _uiState.value.vehicles.find { it.id == selId }?.targetEfficiencyKmPerL ?: 12.5
                _uiState.value = _uiState.value.copy(
                    selectedVehicleId = selId,
                    averageEfficiencyKmPerL = targetEff
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
            vehicleRepository.getAllVehicleLogsFlow().collectLatest { logs ->
                val fuelLogs = logs.filter { it.logType == VehicleLogType.REFUELING }
                val totalFuel = fuelLogs.sumOf { it.fuelCost }
                val latest = fuelLogs.firstOrNull()?.daysSinceLastFuel
                val totalDist = logs.sumOf { it.tripDistanceKm }

                _uiState.value = _uiState.value.copy(
                    logs = logs,
                    totalFuelExpense = totalFuel,
                    latestIntervalDays = latest,
                    totalDrivingDistanceKm = totalDist
                )
            }
        }
    }

    fun manualSyncRefueling() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isSyncing = true)
            val cfg = locationPreferences.config.value
            if (cfg.isConfigured) {
                vehicleRepository.cleanDuplicatesAndCorruptedLogs(cfg.homeName, cfg.companyName, cfg.commuteRoundTripKm)
            }
            val txs = transactionRepository.getAllTransactionsFlow().first()
            vehicleRepository.syncRefuelingFromTransactions(txs)
            if (cfg.isConfigured) {
                vehicleRepository.cleanDuplicatesAndCorruptedLogs(cfg.homeName, cfg.companyName, cfg.commuteRoundTripKm)
            }
            _uiState.value = _uiState.value.copy(isSyncing = false)
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
            targetEfficiencyKmPerL = car1.targetEfficiencyKmPerL
        )
        multiVehiclePreferences.updateVehicle(
            id = car2.id,
            name = car2.name,
            licensePlate = car2.licensePlate,
            fuelType = car2.fuelType,
            bluetoothDevice = car2.bluetoothDevice,
            initialOdometerKm = car2.initialOdometerKm,
            targetEfficiencyKmPerL = car2.targetEfficiencyKmPerL
        )
        closeVehicleManageDialog()
    }
}

