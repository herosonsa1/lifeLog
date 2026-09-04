package com.autologue.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.autologue.app.domain.model.VehicleLogType
import java.time.LocalDateTime

@Entity(tableName = "vehicle_logs")
data class VehicleLogEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val timestamp: LocalDateTime,
    val logType: VehicleLogType,
    val fuelCost: Long,
    val fuelAmountLiters: Double,
    val daysSinceLastFuel: Int?,
    val tripDistanceKm: Double,
    val currentOdometerKm: Double?,
    val estimatedEfficiencyKmPerL: Double?,
    val gasStationName: String?,
    val note: String?
)
