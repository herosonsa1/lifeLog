package com.autologue.app.domain.repository

import com.autologue.app.domain.model.VehicleLog
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface VehicleRepository {
    fun getAllVehicleLogsFlow(): Flow<List<VehicleLog>>
    fun getRefuelingLogsFlow(): Flow<List<VehicleLog>>
    suspend fun insertVehicleLog(log: VehicleLog): Long
    suspend fun getLatestRefuelingLog(): VehicleLog?
    suspend fun getDrivingDistanceBetween(start: LocalDate, end: LocalDate): Double
    suspend fun cleanDuplicates(): Int
    suspend fun cleanDuplicatesAndCorruptedLogs(homeName: String = "", companyName: String = "", commuteDistanceKm: Double = 0.0): Int
    suspend fun syncRefuelingFromTransactions(transactions: List<com.autologue.app.domain.model.Transaction>): Int
    suspend fun syncDrivingLogsFromDiary(diaryEntries: List<com.autologue.app.domain.model.DiaryEntry>): Int
    suspend fun clearTripDrivingLogs(): Int
    suspend fun recordCommuteTrip(isToWork: Boolean, homeName: String, companyName: String, distanceKm: Double): Long
}
