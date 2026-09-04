package com.autologue.app.domain.usecase.vehicle

import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

data class VehicleStats(
    val totalFuelExpense: Long,
    val averageFuelIntervalDays: Double,
    val estimatedAverageEfficiency: Double,
    val totalDrivingDistanceKm: Double
)

class CalculateVehicleStatsUseCase @Inject constructor(
    private val vehicleRepository: VehicleRepository
) {
    fun getLogs(): Flow<List<VehicleLog>> = vehicleRepository.getAllVehicleLogsFlow()
}
