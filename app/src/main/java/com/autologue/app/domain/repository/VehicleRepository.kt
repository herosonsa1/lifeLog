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
    suspend fun cleanDuplicatesAndCorruptedLogs(homeName: String = "서울 방이동", companyName: String = "판교 테크노밸리", commuteDistanceKm: Double = 37.0): Int
    suspend fun syncRefuelingFromTransactions(transactions: List<com.autologue.app.domain.model.Transaction>): Int
}
