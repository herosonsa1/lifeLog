package com.autologue.app

import com.autologue.app.data.local.db.dao.VehicleLogDao
import com.autologue.app.data.local.entity.VehicleLogEntity
import com.autologue.app.data.repository.VehicleRepositoryImpl
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.VehicleLogType
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.time.LocalDateTime

class VehicleRepositoryCommuteTest {

    private lateinit var fakeDao: FakeVehicleLogDao
    private lateinit var repository: VehicleRepositoryImpl

    @Before
    fun setUp() {
        fakeDao = FakeVehicleLogDao()
        repository = VehicleRepositoryImpl(fakeDao)
    }

    private fun createLogEntity(
        id: Long = 0L,
        timestamp: LocalDateTime,
        logType: VehicleLogType,
        fuelCost: Long = 0L,
        fuelAmountLiters: Double = 0.0,
        tripDistanceKm: Double = 0.0,
        gasStationName: String? = null,
        note: String? = null
    ) = VehicleLogEntity(
        id = id,
        timestamp = timestamp,
        logType = logType,
        fuelCost = fuelCost,
        fuelAmountLiters = fuelAmountLiters,
        daysSinceLastFuel = null,
        tripDistanceKm = tripDistanceKm,
        currentOdometerKm = null,
        estimatedEfficiencyKmPerL = null,
        gasStationName = gasStationName,
        note = note
    )

    @Test
    fun syncDrivingLogsFromDiary_doesNotAddDrivingLogs() = runBlocking {
        // 다이어리에 도보/대중교통 이동거리가 있어도 차계부 주행 기록으로 삽입되지 않아야 함
        val diary = DiaryEntry(
            id = 1L,
            date = LocalDateTime.of(2026, 9, 15, 12, 0),
            title = "점심 식사 이동",
            summary = "영등포에서 동작으로 이동",
            drivingDistanceKm = 7.7,
            movementSummary = "서울 영등포동 ➔ 서울 동작동"
        )

        val added = repository.syncDrivingLogsFromDiary(listOf(diary))
        assertEquals("다이어리 이동은 차계부 주행으로 등록되지 않아야 함", 0, added)
        assertEquals("차계부 DB에 저장된 주행 기록이 0건이어야 함", 0, fakeDao.logs.size)
    }

    @Test
    fun cleanDuplicatesAndCorruptedLogs_removesDiaryMovementLogsAndUnusualCommute() = runBlocking {
        // 1. 다이어리 이동 동선 (영등포동 -> 동작동) 가짜 주행 기록
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 1L,
                timestamp = LocalDateTime.of(2026, 9, 15, 12, 0),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 7.7,
                note = "다이어리 이동 동선 (서울 영등포동 ➔ 서울 동작동)"
            )
        )

        // 2. 다이어리 이동 동선 (영등포동 -> 방이동) 가짜 주행 기록
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 2L,
                timestamp = LocalDateTime.of(2026, 9, 10, 10, 6),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 21.7,
                note = "다이어리 이동 동선 (서울 영등포동 ➔ 서울 방이동)"
            )
        )

        // 3. 새벽 3시 53분에 비정상 생성된 출퇴근 왕복 주행 기록
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 3L,
                timestamp = LocalDateTime.of(2026, 8, 8, 3, 53),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 25.1,
                note = "출퇴근 왕복 주행 (우리집 ↔ 회사)"
            )
        )

        // 4. 대낮 14시 22분에 비정상 생성된 출퇴근 왕복 주행 기록
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 4L,
                timestamp = LocalDateTime.of(2026, 9, 11, 14, 22),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 25.1,
                note = "출퇴근 왕복 주행 (우리집 ↔ 회사)"
            )
        )

        // 5. 정상 주유 기록 (보존되어야 함)
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 5L,
                timestamp = LocalDateTime.of(2026, 9, 2, 19, 10),
                logType = VehicleLogType.REFUELING,
                fuelCost = 68000L,
                fuelAmountLiters = 41.2,
                gasStationName = "SK에너지 강남주유소"
            )
        )

        // 6. 정상 아침 출근 편도 기록 (보존되어야 함)
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 6L,
                timestamp = LocalDateTime.of(2026, 9, 14, 8, 30),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 12.5,
                note = "출근 주행 (우리집 ➔ 회사)"
            )
        )

        val modified = repository.cleanDuplicatesAndCorruptedLogs(
            homeName = "우리집",
            companyName = "회사",
            commuteDistanceKm = 25.0
        )

        assertTrue(modified > 0)

        val remainingIds = fakeDao.logs.map { it.id }
        assertFalse("다이어리 이동동선(동작동)은 삭제되어야 함", remainingIds.contains(1L))
        assertFalse("다이어리 이동동선(방이동)은 삭제되어야 함", remainingIds.contains(2L))
        assertFalse("새벽 3시 53분 출퇴근 왕복은 삭제되어야 함", remainingIds.contains(3L))
        assertFalse("대낮 14시 22분 출퇴근 왕복은 삭제되어야 함", remainingIds.contains(4L))

        assertTrue("정품 주유 기록은 보존되어야 함", remainingIds.contains(5L))
        assertTrue("정상 출근 편도 기록은 보존되어야 함", remainingIds.contains(6L))
    }

    private class FakeVehicleLogDao : VehicleLogDao {
        val logs = mutableListOf<VehicleLogEntity>()

        override fun getAllVehicleLogs(): Flow<List<VehicleLogEntity>> = flowOf(logs)
        override fun getRefuelingLogs(): Flow<List<VehicleLogEntity>> = flowOf(logs.filter { it.logType == VehicleLogType.REFUELING })
        override suspend fun getLatestRefuelingLog(): VehicleLogEntity? = logs.lastOrNull { it.logType == VehicleLogType.REFUELING }
        override suspend fun findExactFuelLog(timestamp: LocalDateTime, fuelCost: Long, gasStationName: String?): VehicleLogEntity? {
            return logs.find { it.timestamp == timestamp && it.fuelCost == fuelCost && it.gasStationName == gasStationName }
        }
        override suspend fun getVehicleLogById(id: Long): VehicleLogEntity? = logs.find { it.id == id }
        override suspend fun getDrivingDistanceBetween(start: Long, end: Long): Double = 0.0

        override suspend fun insertVehicleLog(log: VehicleLogEntity): Long {
            val id = if (log.id == 0L) (logs.maxOfOrNull { it.id } ?: 0L) + 1L else log.id
            logs.add(log.copy(id = id))
            return id
        }

        override suspend fun updateVehicleLog(log: VehicleLogEntity) {
            val idx = logs.indexOfFirst { it.id == log.id }
            if (idx >= 0) logs[idx] = log
        }

        override suspend fun deleteVehicleLogById(id: Long) {
            logs.removeAll { it.id == id }
        }

        override suspend fun getAllVehicleLogsSync(): List<VehicleLogEntity> = logs.toList()
        override suspend fun deleteAllTripDrivingLogs(): Int {
            val cnt = logs.count { it.logType == VehicleLogType.TRIP_DRIVING }
            logs.removeAll { it.logType == VehicleLogType.TRIP_DRIVING }
            return cnt
        }
    }
}
