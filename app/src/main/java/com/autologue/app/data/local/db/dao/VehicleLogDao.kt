package com.autologue.app.data.local.db.dao

import androidx.room.*
import com.autologue.app.data.local.entity.VehicleLogEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface VehicleLogDao {
    @Query("SELECT * FROM vehicle_logs ORDER BY timestamp DESC")
    fun getAllVehicleLogs(): Flow<List<VehicleLogEntity>>

    @Query("SELECT * FROM vehicle_logs WHERE logType = 'REFUELING' ORDER BY timestamp DESC")
    fun getRefuelingLogs(): Flow<List<VehicleLogEntity>>

    @Query("SELECT * FROM vehicle_logs WHERE logType = 'REFUELING' ORDER BY timestamp DESC LIMIT 1")
    suspend fun getLatestRefuelingLog(): VehicleLogEntity?

    @Query("SELECT * FROM vehicle_logs WHERE timestamp = :timestamp AND fuelCost = :fuelCost AND gasStationName = :gasStationName LIMIT 1")
    suspend fun findExactFuelLog(timestamp: java.time.LocalDateTime, fuelCost: Long, gasStationName: String?): VehicleLogEntity?

    @Query("SELECT * FROM vehicle_logs WHERE id = :id LIMIT 1")
    suspend fun getVehicleLogById(id: Long): VehicleLogEntity?

    @Query("SELECT COALESCE(SUM(tripDistanceKm), 0.0) FROM vehicle_logs WHERE timestamp BETWEEN :start AND :end")
    suspend fun getDrivingDistanceBetween(start: Long, end: Long): Double

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertVehicleLog(log: VehicleLogEntity): Long

    @Update
    suspend fun updateVehicleLog(log: VehicleLogEntity)

    @Query("DELETE FROM vehicle_logs WHERE id = :id")
    suspend fun deleteVehicleLogById(id: Long)

    @Query("SELECT * FROM vehicle_logs ORDER BY id ASC")
    suspend fun getAllVehicleLogsSync(): List<VehicleLogEntity>

    @Query("DELETE FROM vehicle_logs WHERE logType = 'TRIP_DRIVING'")
    suspend fun deleteAllTripDrivingLogs(): Int
}
