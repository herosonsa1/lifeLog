package com.autologue.app

import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.LifeOrbitPeriod
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.lifestyle.CalculateLifestyleMetricsUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class CalculateLifestyleMetricsUseCaseTest {

    private class FakeTransactionRepo(private val txs: List<Transaction>) : TransactionRepository {
        override fun getAllTransactionsFlow(): Flow<List<Transaction>> = flowOf(txs)
        override fun getTransactionsByDateRange(start: LocalDateTime, end: LocalDateTime) = flowOf(txs)
        override fun getTransactionsByCategory(category: ExpenseCategory) = flowOf(txs)
        override suspend fun insertTransaction(transaction: Transaction) = 1L
        override suspend fun updateTransaction(transaction: Transaction) {}
        override suspend fun deleteTransaction(id: Long) {}
        override suspend fun getTotalExpenseBetween(start: LocalDateTime, end: LocalDateTime) = 0L
        override suspend fun cleanDuplicates() = 0
    }

    private class FakeVehicleRepo(private val logs: List<VehicleLog>) : VehicleRepository {
        override fun getAllVehicleLogsFlow(): Flow<List<VehicleLog>> = flowOf(logs)
        override fun getRefuelingLogsFlow(): Flow<List<VehicleLog>> = flowOf(logs)
        override suspend fun insertVehicleLog(log: VehicleLog) = 1L
        override suspend fun getVehicleLogById(id: Long) = null
        override suspend fun updateVehicleLog(log: VehicleLog) {}
        override suspend fun getLatestRefuelingLog() = null
        override suspend fun getDrivingDistanceBetween(start: LocalDate, end: LocalDate) = 0.0
        override suspend fun cleanDuplicates() = 0
        override suspend fun cleanDuplicatesAndCorruptedLogs(homeName: String, companyName: String, commuteDistanceKm: Double) = 0
        override suspend fun syncRefuelingFromTransactions(transactions: List<Transaction>) = 0
        override suspend fun deleteRefuelingLogByTransaction(amount: Long, date: LocalDate, merchantName: String) = 0
        override suspend fun syncDrivingLogsFromDiary(diaryEntries: List<DiaryEntry>) = 0
        override suspend fun clearTripDrivingLogs() = 0
        override suspend fun recordCommuteTrip(isToWork: Boolean, homeName: String, companyName: String, distanceKm: Double) = 1L
    }

    private class FakeDiaryRepo(private val entries: List<DiaryEntry>) : DiaryRepository {
        override fun getDiaryEntriesFlow(): Flow<List<DiaryEntry>> = flowOf(entries)
        override fun getDiaryEntriesByDateRange(start: LocalDate, end: LocalDate) = flowOf(entries)
        override suspend fun getDiaryEntryById(id: Long) = null
        override suspend fun insertDiaryEntry(entry: DiaryEntry) = 1L
        override suspend fun updateDiaryEntry(entry: DiaryEntry) {}
        override suspend fun deleteDiaryEntry(id: Long) {}
        override suspend fun cleanDuplicates() = 0
        override suspend fun generateClustersForDate(date: LocalDate) = emptyList<com.autologue.app.domain.model.PlaceCluster>()
    }

    @Test
    fun testCalculateLifestyleMetrics_emptyDataReturnsZeroScoresAndStandbyStatus() = runBlocking {
        // 데이터가 아예 없는 초기 설치 상태: 더미 점수(50점)가 아닌 0점과 대기 상태 반환 검증
        val useCase = CalculateLifestyleMetricsUseCase(
            transactionRepository = FakeTransactionRepo(emptyList()),
            vehicleRepository = FakeVehicleRepo(emptyList()),
            diaryRepository = FakeDiaryRepo(emptyList())
        )

        val result = useCase(LifeOrbitPeriod.MONTH).first()

        assertEquals(0, result.balanceScores.overallScore)
        assertEquals(0, result.balanceScores.activityScore)
        assertEquals(0, result.balanceScores.financeScore)
        assertEquals(0, result.balanceScores.mobilityScore)
        assertEquals(0, result.balanceScores.achievementScore)
        assertEquals("일상 데이터 수집 대기 중", result.archetype)
        assertEquals(0L, result.financialVelocity.totalExpense)
        assertEquals(0.0, result.mobilityOrbit.totalDrivingKm, 0.01)
        assertEquals(0, result.spatialFootprint.recordedDays)
    }

    @Test
    fun testCalculateLifestyleMetrics_computesAccurateVelocityAndScores() = runBlocking {
        val now = LocalDateTime.now()

        // 1. 거래 내역 (순수 지출 150,000 + 50,000 = 200,000 / 본인 이체 1,000,000 제외 / 수입 2,000,000 제외)
        val transactions = listOf(
            Transaction(
                id = 1,
                amount = 150000L,
                merchantName = "남서울CC",
                originalText = "남서울CC 150,000원",
                timestamp = now.minusDays(3),
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.GOLF_FIELD,
                cardOrBankName = "현대카드"
            ),
            Transaction(
                id = 2,
                amount = 50000L,
                merchantName = "스타벅스",
                originalText = "스타벅스 50,000원",
                timestamp = now.minusDays(5),
                paymentMethod = PaymentMethod.CHECK_CARD,
                category = ExpenseCategory.CAFE,
                cardOrBankName = "신한카드"
            ),
            Transaction(
                id = 3,
                amount = 1000000L,
                merchantName = "정선우(국민)",
                originalText = "정선우 계좌이체",
                timestamp = now.minusDays(2),
                paymentMethod = PaymentMethod.BANK_TRANSFER,
                category = ExpenseCategory.TRANSFER,
                cardOrBankName = "국민은행"
            ),
            Transaction(
                id = 4,
                amount = 2000000L,
                merchantName = "급여",
                originalText = "급여 입금",
                timestamp = now.minusDays(10),
                paymentMethod = PaymentMethod.BANK_TRANSFER,
                category = ExpenseCategory.INCOME,
                cardOrBankName = "우리은행"
            )
        )

        // 2. 차량 내역
        val vehicleLogs = listOf(
            VehicleLog(
                id = 1,
                timestamp = now.minusDays(3),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 120.0
            ),
            VehicleLog(
                id = 2,
                timestamp = now.minusDays(1),
                logType = VehicleLogType.REFUELING,
                fuelCost = 80000L,
                fuelAmountLiters = 50.0,
                estimatedEfficiencyKmPerL = 13.5
            )
        )

        // 3. 다이어리 내역
        val diaryEntries = listOf(
            DiaryEntry(
                id = 1,
                date = now.minusDays(3),
                title = "주말 외출 일상",
                summary = "하남 스타필드 방문",
                placeName = "스타필드 하남",
                address = "경기도 하남시 미사대로 750",
                photoUris = listOf("uri1", "uri2"),
                drivingDistanceKm = 60.0
            )
        )

        val useCase = CalculateLifestyleMetricsUseCase(
            transactionRepository = FakeTransactionRepo(transactions),
            vehicleRepository = FakeVehicleRepo(vehicleLogs),
            diaryRepository = FakeDiaryRepo(diaryEntries)
        )

        val result = useCase(LifeOrbitPeriod.MONTH).first()

        // 검증: 순수 지출 총합 = 200,000원 (본인이체 및 수입 제외 확인)
        assertEquals(200000L, result.financialVelocity.totalExpense)
        assertEquals("필드 라운드", result.financialVelocity.topCategoryName)
        assertEquals(150000L, result.financialVelocity.topCategoryAmount)
        assertEquals(75.0, result.financialVelocity.topCategoryRatio, 0.1)

        // 모빌리티 검증: 주행거리 120 + 60 = 180km, 주유비 80,000원, 연비 13.5
        assertEquals(180.0, result.mobilityOrbit.totalDrivingKm, 0.1)
        assertEquals(80000L, result.mobilityOrbit.totalFuelCost)
        assertEquals(13.5, result.mobilityOrbit.averageFuelEconomy, 0.1)

        // 공간 발자취 검증
        assertEquals(1, result.spatialFootprint.recordedDays)
        assertEquals(2, result.spatialFootprint.totalPhotosArchived)

        // 밸런스 점수 범위 검증 (10~100)
        assertTrue(result.balanceScores.overallScore in 10..100)
        assertTrue(result.balanceScores.activityScore in 10..100)
        assertTrue(result.balanceScores.financeScore in 10..100)
        assertTrue(result.balanceScores.mobilityScore in 10..100)
        assertTrue(result.balanceScores.achievementScore in 10..100)

        // 인사이트 검증
        assertTrue("데이터가 있을 때 맞춤형 라이프스타일 인사이트가 생성되어야 함", result.insights.isNotEmpty())
        assertNotNull(result.archetype)
        assertTrue(result.archetype.isNotBlank())
    }
}
