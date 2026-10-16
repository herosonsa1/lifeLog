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

    @Test
    fun cleanDuplicatesAndCorruptedLogs_preservesRealGpsDrivingLogsAndPurgesJemokFuel() = runBlocking {
        // 1. 일반 GPS 추적 주행 기록 1 (오전 이동) -> 절대 삭제되면 안 됨
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 10L,
                timestamp = LocalDateTime.of(2026, 10, 15, 11, 20),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 14.3,
                note = "[car_1: 벤츠] [서울 송파구 방이동 (위례성대로) ➔ 서울 강남구 역삼동 (테헤란로) (14.3 km)] (32분 운행 · GPS 11개 지점)"
            )
        )

        // 2. 같은 날 일반 GPS 추적 주행 기록 2 (오후 이동) -> 하루 다회 일반 주행도 모두 보존되어야 함
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 11L,
                timestamp = LocalDateTime.of(2026, 10, 15, 15, 45),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 9.8,
                note = "[car_1: 벤츠] [서울 강남구 역삼동 (테헤란로) ➔ 경기 성남시 분당구 판교역로 (9.8 km)] (24분 운행 · GPS 8개 지점)"
            )
        )

        // 3. 가짜 "제목" 오염 주유 기록 -> 즉시 자동 삭제되어야 함
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 12L,
                timestamp = LocalDateTime.of(2026, 9, 25, 12, 0),
                logType = VehicleLogType.REFUELING,
                fuelCost = 30000L,
                fuelAmountLiters = 18.2,
                gasStationName = "제목",
                note = "가계부 결제 내역 자동 분석 (신용카드)"
            )
        )

        repository.cleanDuplicatesAndCorruptedLogs(
            homeName = "우리집",
            companyName = "회사",
            commuteDistanceKm = 25.0
        )

        val remainingIds = fakeDao.logs.map { it.id }
        assertTrue("오전 일반 GPS 주행 기록은 보존되어야 함", remainingIds.contains(10L))
        assertTrue("오후 일반 GPS 주행 기록도 보존되어야 함", remainingIds.contains(11L))
        assertFalse("가짜 '제목' 주유 기록은 삭제되어야 함", remainingIds.contains(12L))
    }

    @Test
    fun fuelEconomyCalculator_usesVehicleConfiguredGasPriceDynamically() {
        // 초기 동기화 시 1,650원/L로 18.2L가 저장된 자동 수집 주유 내역이라도,
        // 벤츠 차량 설정 유가가 1,850원/L이면 30,000 / 1,850 = 16.2L로 동적 환산되어야 함
        val autoSyncedFuelLog = com.autologue.app.domain.model.VehicleLog(
            id = 100L,
            timestamp = LocalDateTime.of(2026, 9, 25, 18, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 30000L,
            fuelAmountLiters = 18.2, // 과거 1,650원 기준 저장값
            gasStationName = "지에스칼텍스",
            note = "[car_1: 벤츠] 가계부 결제 내역 자동 분석 (신용카드)"
        )

        val result = com.autologue.app.util.FuelEconomyCalculator.calculateForVehicle(
            targetVehicleId = "car_1",
            allLogs = listOf(autoSyncedFuelLog),
            gasPrice = 1850.0
        )

        assertEquals(1, result.enrichedFuelLogs.size)
        assertEquals(16.2, result.enrichedFuelLogs.first().fuelAmountLiters, 0.01)
    }

    @Test
    fun merchantAliasPreferences_registersAndResolvesChainedAliases() {
        val aliasPrefs = com.autologue.app.data.preferences.MerchantAliasPreferences()
        aliasPrefs.registerAlias(
            oldName = "(주)스타벅스코리아",
            newName = "스타벅스",
            rawOriginalName = "스타벅스코리아"
        )
        assertEquals("스타벅스", aliasPrefs.resolveAlias("(주)스타벅스코리아"))
        assertEquals("스타벅스", aliasPrefs.resolveAlias("(주) 스타벅스 코리아"))
        assertEquals("스타벅스", aliasPrefs.resolveAlias("스타벅스코리아"))

        // 연쇄 수정 시(스타벅스 -> 단골 스벅) 최초 원본 가맹점명도 새 결제명으로 자동 치환되어야 함
        aliasPrefs.registerAlias(
            oldName = "스타벅스",
            newName = "단골 스벅"
        )
        assertEquals("단골 스벅", aliasPrefs.resolveAlias("(주)스타벅스코리아"))
        assertEquals("단골 스벅", aliasPrefs.resolveAlias("스타벅스"))
        assertTrue(aliasPrefs.isKnownAliasTarget("단골 스벅"))
    }

    @Test
    fun renameGasStationGlobally_updatesExistingLogsAndAutoReplacesFutureSyncs() = runBlocking {
        // 기존 동일 주유소 기록 2건 삽입
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 20L,
                timestamp = LocalDateTime.of(2026, 9, 10, 18, 0),
                logType = VehicleLogType.REFUELING,
                fuelCost = 65000L,
                fuelAmountLiters = 39.4,
                gasStationName = "GS칼텍스(주)위례점",
                note = "가계부 결제 내역 자동 분석"
            )
        )
        fakeDao.insertVehicleLog(
            createLogEntity(
                id = 21L,
                timestamp = LocalDateTime.of(2026, 9, 25, 19, 0),
                logType = VehicleLogType.REFUELING,
                fuelCost = 70000L,
                fuelAmountLiters = 42.4,
                gasStationName = "GS칼텍스(주)위례점",
                note = "가계부 결제 내역 자동 분석"
            )
        )

        // 결제명 1회 수정 -> 기존 동일 결제처 항목 일괄 치환 검증
        val renamedCount = repository.renameGasStationGlobally(
            oldStationName = "GS칼텍스(주)위례점",
            newStationName = "우리동네 단골주유"
        )
        assertEquals(2, renamedCount)
        assertEquals("우리동네 단골주유", fakeDao.getVehicleLogById(20L)?.gasStationName)
        assertEquals("우리동네 단골주유", fakeDao.getVehicleLogById(21L)?.gasStationName)

        // 향후 동일 결제처("GS칼텍스(주)위례점")로부터 신규 주유 거래가 동기화될 때 자동으로 "우리동네 단골주유"로 치환되어 기록되는지 검증
        val newFuelTx = com.autologue.app.domain.model.Transaction(
            id = 500L,
            amount = 60000L,
            merchantName = "GS칼텍스(주)위례점",
            originalText = "[Web발신] 신한카드 승인 60,000원 10/15 18:20 GS칼텍스(주)위례점",
            timestamp = LocalDateTime.of(2026, 10, 15, 18, 20),
            paymentMethod = com.autologue.app.domain.model.PaymentMethod.CREDIT_CARD,
            category = com.autologue.app.domain.model.ExpenseCategory.FUEL,
            cardOrBankName = "신한카드"
        )
        val added = repository.syncRefuelingFromTransactions(listOf(newFuelTx))
        assertEquals(1, added)
        val newlySynced = fakeDao.logs.find { it.fuelCost == 60000L }
        assertEquals("우리동네 단골주유", newlySynced?.gasStationName)

        // 정제 로직(cleanDuplicatesAndCorruptedLogs) 수행 후에도 사용자 지정 결제명이 삭제되지 않고 보존되는지 검증
        repository.cleanDuplicatesAndCorruptedLogs("우리집", "회사", 50.0)
        assertEquals(3, fakeDao.logs.count { it.gasStationName == "우리동네 단골주유" })
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
