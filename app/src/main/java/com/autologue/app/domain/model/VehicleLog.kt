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

fun VehicleLog.getAssignedVehicleId(defaultVehicleId: String = "car_1"): String {
    val n = note ?: return defaultVehicleId
    return when {
        n.contains("[car_2]") || n.contains("car_2") || n.contains("차량 2") -> "car_2"
        n.contains("[car_1]") || n.contains("car_1") || n.contains("차량 1") -> "car_1"
        else -> defaultVehicleId
    }
}

