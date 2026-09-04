package com.autologue.app.presentation.car

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.preferences.CommuteConfig
import com.autologue.app.data.preferences.UserLocationPreferences
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
import javax.inject.Inject

data class CarLedgerUiState(
    val logs: List<VehicleLog> = emptyList(),
    val totalFuelExpense: Long = 0L,
    val latestIntervalDays: Int? = null,
    val totalDrivingDistanceKm: Double = 0.0,
    val averageEfficiencyKmPerL: Double = 12.5,
    val isSyncing: Boolean = false,
    val commuteConfig: CommuteConfig = CommuteConfig(),
    val showLocationDialog: Boolean = false
)

@HiltViewModel
class CarLedgerViewModel @Inject constructor(
    private val vehicleRepository: VehicleRepository,
    private val transactionRepository: TransactionRepository,
    private val locationPreferences: UserLocationPreferences
) : ViewModel() {

    private val _uiState = MutableStateFlow(CarLedgerUiState())
    val uiState: StateFlow<CarLedgerUiState> = _uiState.asStateFlow()

    init {
        loadData()
        observeCommuteConfig()
        manualSyncRefueling()
    }

    private fun observeCommuteConfig() {
        viewModelScope.launch {
            locationPreferences.config.collectLatest { cfg ->
                _uiState.value = _uiState.value.copy(commuteConfig = cfg)
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
            vehicleRepository.cleanDuplicatesAndCorruptedLogs(cfg.homeName, cfg.companyName, cfg.commuteRoundTripKm)
            val txs = transactionRepository.getAllTransactionsFlow().first()
            vehicleRepository.syncRefuelingFromTransactions(txs)
            vehicleRepository.cleanDuplicatesAndCorruptedLogs(cfg.homeName, cfg.companyName, cfg.commuteRoundTripKm)
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
        companyName: String,
        companyAddress: String,
        roundTripKm: Double
    ) {
        locationPreferences.updateConfig(
            homeName = homeName,
            homeAddress = homeAddress,
            companyName = companyName,
            companyAddress = companyAddress,
            roundTripKm = roundTripKm
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
}
