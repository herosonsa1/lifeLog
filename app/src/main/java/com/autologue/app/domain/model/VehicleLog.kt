package com.autologue.app.domain.model

import java.time.LocalDateTime

enum class VehicleLogType {
    REFUELING, TRIP_DRIVING, MAINTENANCE, PARKING
}

data class VehicleLog(
    val id: Long = 0,
    val timestamp: LocalDateTime,
    val logType: VehicleLogType,
    val fuelCost: Long = 0L,
    val fuelAmountLiters: Double = 0.0,
    val daysSinceLastFuel: Int? = null,
    val tripDistanceKm: Double = 0.0,
    val currentOdometerKm: Double? = null,
    val estimatedEfficiencyKmPerL: Double? = null,
    val gasStationName: String? = null,
    val note: String? = null
)
