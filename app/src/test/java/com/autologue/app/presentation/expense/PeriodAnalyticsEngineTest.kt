package com.autologue.app.presentation.expense

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.presentation.car.CarCostCategoryType
import com.autologue.app.presentation.car.CarLedgerAnalyticsEngine
import com.autologue.app.presentation.golf.GolfAnalyticsEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class PeriodAnalyticsEngineTest {

    @Test
    fun `PeriodSpec calculates date ranges and previous periods accurately across all units`() {
        // 1) 월간 (2026년 10월)
        val monthly = PeriodSpec.fromDate(AnalyticsPeriodUnit.MONTHLY, LocalDate.of(2026, 10, 15))
        assertEquals(LocalDate.of(2026, 10, 1), monthly.startDate)
        assertEquals(LocalDate.of(2026, 10, 31), monthly.endDate)
        assertEquals(PeriodSpec(AnalyticsPeriodUnit.MONTHLY, 2026, 9), monthly.previousSequential())
        assertEquals(PeriodSpec(AnalyticsPeriodUnit.MONTHLY, 2025, 10), monthly.samePeriodLastYear())

        // 2) 분기별 (2026년 1분기 -> 이전 분기: 2025년 4분기)
        val q1 = PeriodSpec(AnalyticsPeriodUnit.QUARTERLY, 2026, 1)
        assertEquals(LocalDate.of(2026, 1, 1), q1.startDate)
        assertEquals(LocalDate.of(2026, 3, 31), q1.endDate)
        assertEquals(PeriodSpec(AnalyticsPeriodUnit.QUARTERLY, 2025, 4), q1.previousSequential())
        assertEquals(PeriodSpec(AnalyticsPeriodUnit.QUARTERLY, 2025, 1), q1.samePeriodLastYear())

        // 3) 반기별 (2026년 하반기 -> 직전 반기: 2026년 상반기, 전년 동기: 2025년 하반기)
        val h2 = PeriodSpec(AnalyticsPeriodUnit.HALF_YEARLY, 2026, 2)
        assertEquals(LocalDate.of(2026, 7, 1), h2.startDate)
        assertEquals(LocalDate.of(2026, 12, 31), h2.endDate)
        assertEquals(PeriodSpec(AnalyticsPeriodUnit.HALF_YEARLY, 2026, 1), h2.previousSequential())
        assertEquals(PeriodSpec(AnalyticsPeriodUnit.HALF_YEARLY, 2025, 2), h2.samePeriodLastYear())

        // 4) 연간 (2026년 -> 직전 연도: 2025년)
        val y2026 = PeriodSpec(AnalyticsPeriodUnit.YEARLY, 2026, 1)
        assertEquals(LocalDate.of(2026, 1, 1), y2026.startDate)
        assertEquals(LocalDate.of(2026, 12, 31), y2026.endDate)
        assertEquals(PeriodSpec(AnalyticsPeriodUnit.YEARLY, 2025, 1), y2026.previousSequential())
    }

    @Test
    fun `ExpenseAnalyticsEngine identifies top changed category and generates clear insight`() {
        val transactions = listOf(
            // 2026년 9월: 식비 200,000원, 쇼핑 100,000원 (총 300,000원)
            Transaction(
                id = 1L,
                timestamp = LocalDateTime.of(2026, 9, 10, 12, 30),
                amount = 100_000L,
                merchantName = "광화문식당",
                originalText = "승인 100,000원 광화문식당",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.FOOD,
                cardOrBankName = "현대카드"
            ),
            Transaction(
                id = 2L,
                timestamp = LocalDateTime.of(2026, 9, 20, 19, 0),
                amount = 100_000L,
                merchantName = "강남회관",
                originalText = "승인 100,000원 강남회관",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.FOOD,
                cardOrBankName = "현대카드"
            ),
            Transaction(
                id = 3L,
                timestamp = LocalDateTime.of(2026, 9, 25, 15, 0),
                amount = 100_000L,
                merchantName = "쿠팡",
                originalText = "승인 100,000원 쿠팡",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.SHOPPING,
                cardOrBankName = "현대카드"
            ),
            // 2026년 10월: 식비 180,000원(-20,000원), 필드 라운드 450,000원(+450,000원), 쇼핑 120,000원(+20,000원) -> 총 750,000원
            Transaction(
                id = 4L,
                timestamp = LocalDateTime.of(2026, 10, 5, 12, 30),
                amount = 180_000L,
                merchantName = "광화문식당",
                originalText = "승인 180,000원 광화문식당",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.FOOD,
                cardOrBankName = "현대카드"
            ),
            Transaction(
                id = 5L,
                timestamp = LocalDateTime.of(2026, 10, 12, 14, 0),
                amount = 250_000L,
                merchantName = "남서울CC",
                originalText = "승인 250,000원 남서울CC",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.GOLF_FIELD,
                cardOrBankName = "현대카드"
            ),
            Transaction(
                id = 6L,
                timestamp = LocalDateTime.of(2026, 10, 18, 16, 0),
                amount = 200_000L,
                merchantName = "레이크사이드CC",
                originalText = "승인 200,000원 레이크사이드CC",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.GOLF_FIELD,
                cardOrBankName = "현대카드"
            ),
            Transaction(
                id = 7L,
                timestamp = LocalDateTime.of(2026, 10, 22, 11, 0),
                amount = 120_000L,
                merchantName = "쿠팡",
                originalText = "승인 120,000원 쿠팡",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.SHOPPING,
                cardOrBankName = "현대카드"
            )
        )

        val octPeriod = PeriodSpec(AnalyticsPeriodUnit.MONTHLY, 2026, 10)
        val report = ExpenseAnalyticsEngine.buildReport(
            allTransactions = transactions,
            unit = AnalyticsPeriodUnit.MONTHLY,
            targetPeriod = octPeriod,
            requestedBasis = AnalyticsComparisonBasis.PREVIOUS_PERIOD,
            customCategoryByTxId = emptyMap(),
            userClassByTxId = emptyMap(),
            corporateByTxId = emptyMap(),
            recurringTransactionIds = emptySet(),
            getBuiltInCategoryName = { cat -> cat.displayName }
        )

        assertEquals(750_000L, report.currentTotalExpense)
        assertEquals(300_000L, report.activeComparisonTotalExpense)
        assertEquals(450_000L, report.activeDeltaAmount)
        assertEquals(150.0f, report.activeDeltaPercent ?: 0.0f, 0.1f)

        // 가장 변동폭이 큰 카테고리는 필드 라운드(+450,000원)여야 함
        assertNotNull(report.largestChangeItem)
        assertEquals("필드 라운드", report.largestChangeItem?.categoryName)
        assertEquals(450_000L, report.largestChangeItem?.deltaAmount)
        assertTrue(report.headlineInsight.contains("750,000원"))
        assertTrue(report.causeAnalysisInsight.contains("필드 라운드"))
        assertTrue(report.causeAnalysisInsight.contains("남서울CC"))
    }

    @Test
    fun `CarLedgerAnalyticsEngine compares driving distance and maintenance costs with correlation insight`() {
        val logs = listOf(
            // 2026년 3분기(7~9월): 주행 1,000km, 주유 150,000원, 정비 0원
            VehicleLog(
                id = 1L,
                timestamp = LocalDateTime.of(2026, 8, 10, 8, 30),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 600.0,
                note = "출퇴근 왕복 주행"
            ),
            VehicleLog(
                id = 2L,
                timestamp = LocalDateTime.of(2026, 9, 12, 7, 0),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 400.0,
                note = "골프 라운딩 이동"
            ),
            VehicleLog(
                id = 3L,
                timestamp = LocalDateTime.of(2026, 8, 20, 18, 0),
                logType = VehicleLogType.REFUELING,
                fuelCost = 150_000,
                fuelAmountLiters = 90.0,
                gasStationName = "SK에너지 직영주유소"
            ),
            // 2026년 4분기(10~12월): 주행 950km(-5%), 주유 140,000원, 정비 320,000원(타이어/엔진오일)
            VehicleLog(
                id = 4L,
                timestamp = LocalDateTime.of(2026, 10, 15, 8, 30),
                logType = VehicleLogType.TRIP_DRIVING,
                tripDistanceKm = 950.0,
                note = "출퇴근 왕복 주행"
            ),
            VehicleLog(
                id = 5L,
                timestamp = LocalDateTime.of(2026, 10, 18, 19, 0),
                logType = VehicleLogType.REFUELING,
                fuelCost = 140_000,
                fuelAmountLiters = 84.0,
                gasStationName = "GS칼텍스 주유소"
            ),
            VehicleLog(
                id = 6L,
                timestamp = LocalDateTime.of(2026, 10, 20, 14, 0),
                logType = VehicleLogType.MAINTENANCE,
                fuelCost = 320_000,
                gasStationName = "블루핸즈 엔진오일 및 브레이크패드 교체"
            )
        )

        val q4Period = PeriodSpec(AnalyticsPeriodUnit.QUARTERLY, 2026, 4)
        val report = CarLedgerAnalyticsEngine.buildReport(
            logs = logs,
            unit = AnalyticsPeriodUnit.QUARTERLY,
            targetPeriod = q4Period,
            requestedBasis = AnalyticsComparisonBasis.PREVIOUS_PERIOD
        )

        assertEquals(950.0, report.currentTotalDistanceKm, 0.01)
        assertEquals(1000.0, report.activeComparisonDistanceKm, 0.01)
        assertEquals(-50.0, report.activeDistanceDeltaKm, 0.01)

        assertEquals(460_000L, report.currentTotalCost)
        assertEquals(150_000L, report.activeComparisonTotalCost)
        assertEquals(310_000L, report.activeCostDeltaAmount)

        // 가장 변동폭이 큰 유지비 항목은 정비 · 세차 · 소모품(+320,000원)
        assertNotNull(report.largestCostChangeItem)
        assertEquals(CarCostCategoryType.MAINTENANCE, report.largestCostChangeItem?.type)
        assertEquals(320_000L, report.largestCostChangeItem?.deltaAmount)

        // 주행거리는 줄었으나 정비비로 인해 유지비가 증가했다는 인사이트 검증
        assertTrue(report.costHighlightInsight.contains("정비 · 세차 · 소모품"))
        assertTrue(report.costHighlightInsight.contains("블루핸즈"))
    }

    @Test
    fun `GolfAnalyticsEngine compares field rounds, screen golf, scores and YoY period accurately`() {
        val rounds = listOf(
            // 2025년 하반기(전년 동기): 필드 1회(92타, 퍼트 35, 벌타 3), 스크린 1회(88타)
            GolfRound(
                id = 1L,
                clubName = "남서울 CC",
                roundDate = LocalDateTime.of(2025, 9, 14, 8, 0),
                golfType = GolfType.FIELD,
                totalScore = 92,
                totalPutts = 35,
                penaltyCount = 3,
                greenFeeExpense = 240_000L,
                averageDriveDistance = 200.0,
                girPercentage = 27.8,
                memo = "[통계: 버디 0, 파 5, 보기 9, 더블+ 4]"
            ),
            GolfRound(
                id = 2L,
                clubName = "골프존파크 판교점",
                roundDate = LocalDateTime.of(2025, 10, 5, 19, 30),
                golfType = GolfType.SCREEN,
                totalScore = 88,
                totalPutts = 32,
                penaltyCount = 1,
                greenFeeExpense = 25_000L,
                averageDriveDistance = 205.0,
                girPercentage = 38.9,
                memo = "[통계: 버디 1, 파 7, 보기 7, 더블+ 3]"
            ),
            // 2026년 상반기(직전 반기): 필드 1회(89타, 퍼트 34, 벌타 2)
            GolfRound(
                id = 3L,
                clubName = "레이크사이드 CC",
                roundDate = LocalDateTime.of(2026, 5, 10, 7, 30),
                golfType = GolfType.FIELD,
                totalScore = 89,
                totalPutts = 34,
                penaltyCount = 2,
                greenFeeExpense = 250_000L,
                averageDriveDistance = 204.0,
                girPercentage = 33.3,
                memo = "[통계: 버디 1, 파 6, 보기 8, 더블+ 3]"
            ),
            // 2026년 하반기(현재 대상): 필드 2회(84타, 82타 -> 평균 83타), 스크린 라운드 1회(80타)
            GolfRound(
                id = 4L,
                clubName = "남서울 CC",
                roundDate = LocalDateTime.of(2026, 9, 20, 8, 0),
                golfType = GolfType.FIELD,
                totalScore = 84,
                totalPutts = 31,
                penaltyCount = 1,
                greenFeeExpense = 240_000L,
                averageDriveDistance = 212.0,
                girPercentage = 44.4,
                memo = "[통계: 버디 2, 파 8, 보기 7, 더블+ 1]"
            ),
            GolfRound(
                id = 5L,
                clubName = "스카이72 CC",
                roundDate = LocalDateTime.of(2026, 10, 11, 9, 0),
                golfType = GolfType.FIELD,
                totalScore = 82,
                totalPutts = 30,
                penaltyCount = 0,
                greenFeeExpense = 230_000L,
                averageDriveDistance = 216.0,
                girPercentage = 50.0,
                memo = "[통계: 버디 2, 파 9, 보기 6, 더블+ 1]"
            ),
            GolfRound(
                id = 6L,
                clubName = "프렌즈스크린 분당점",
                roundDate = LocalDateTime.of(2026, 10, 3, 20, 0),
                golfType = GolfType.SCREEN,
                totalScore = 80,
                totalPutts = 29,
                penaltyCount = 0,
                greenFeeExpense = 22_000L,
                averageDriveDistance = 218.0,
                girPercentage = 55.6,
                memo = "[통계: 버디 3, 파 9, 보기 5, 더블+ 1]"
            )
        )

        // 가계부 내 스크린골프 추가 결제 1건 (10월 15일 — 라운드 기록과 다른 날짜이므로 추가 1회로 합산되어야 함)
        val transactions = listOf(
            Transaction(
                id = 101L,
                timestamp = LocalDateTime.of(2026, 10, 15, 21, 0),
                amount = 25_000L,
                merchantName = "골프존파크 정자점",
                originalText = "승인 25,000원 골프존파크 정자점",
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = ExpenseCategory.GOLF_SCREEN,
                cardOrBankName = "신한카드"
            )
        )

        val h2Period = PeriodSpec(AnalyticsPeriodUnit.HALF_YEARLY, 2026, 2)

        // 1) 직전 반기(2026년 상반기) 대비 비교 검증
        val prevReport = GolfAnalyticsEngine.buildReport(
            allRounds = rounds,
            allTransactions = transactions,
            unit = AnalyticsPeriodUnit.HALF_YEARLY,
            targetPeriod = h2Period,
            requestedBasis = AnalyticsComparisonBasis.PREVIOUS_PERIOD
        )

        assertEquals(2, prevReport.currentSnapshot.fieldRoundCount)
        assertEquals(2, prevReport.currentSnapshot.screenGolfCount) // SCREEN 라운드 1회 + 다른 날짜 결제 1회
        assertEquals(4, prevReport.currentSnapshot.totalActivityCount)
        assertEquals(1, prevReport.activeComparisonSnapshot.fieldRoundCount)
        assertEquals(0, prevReport.activeComparisonSnapshot.screenGolfCount)

        // 평균 스코어: 현재 (84+82+80)/3 = 82.0타 vs 직전 반기 89.0타 -> -7.0타 단축
        assertEquals(82.0, prevReport.currentSnapshot.averageScore ?: 0.0, 0.1)
        assertEquals(89.0, prevReport.activeComparisonSnapshot.averageScore ?: 0.0, 0.1)
        assertEquals(-7.0, prevReport.seqAvgScoreDelta ?: 0.0, 0.1)
        assertTrue(prevReport.scoreAnalysisInsight.contains("단축"))

        // 2) 전년 동반기(2025년 하반기) 대비 비교 검증
        val yoyReport = GolfAnalyticsEngine.buildReport(
            allRounds = rounds,
            allTransactions = transactions,
            unit = AnalyticsPeriodUnit.HALF_YEARLY,
            targetPeriod = h2Period,
            requestedBasis = AnalyticsComparisonBasis.YEAR_OVER_YEAR
        )

        assertEquals(PeriodSpec(AnalyticsPeriodUnit.HALF_YEARLY, 2025, 2), yoyReport.activeComparisonPeriod)
        assertEquals(1, yoyReport.activeComparisonSnapshot.fieldRoundCount)
        assertEquals(1, yoyReport.activeComparisonSnapshot.screenGolfCount)
        assertEquals(2, yoyReport.activeComparisonSnapshot.totalActivityCount)
        assertEquals(90.0, yoyReport.activeComparisonSnapshot.averageScore ?: 0.0, 0.1) // (92+88)/2 = 90.0타
        assertEquals(-8.0, yoyReport.yoyAvgScoreDelta ?: 0.0, 0.1) // 82.0 - 90.0 = -8.0타 단축
        assertTrue(yoyReport.yoyComparisonInsight.contains("전년 동반기"))
    }

    @Test
    fun `SyncProgress formats quantitative count and completion percentage accurately`() {
        val runningProgress = com.autologue.app.domain.usecase.sync.SyncProgress(
            isRunning = true,
            stage = "[2/4단계] 과거 전체 갤러리 사진·위치 분석 중 (310/600장)",
            syncedTxCount = 480,
            syncedPhotoCount = 310,
            syncedGolfCount = 0,
            isDone = false,
            stepNumber = 2,
            totalSteps = 4,
            stepProcessedCount = 310,
            stepTotalCount = 600,
            overallProcessedCount = 790,
            overallTotalCount = 1240,
            progressPercent = 63
        )
        assertEquals("전체 1,240건 중 790건 (63% 완료)", runningProgress.formatQuantitativeSummary())

        val doneProgress = runningProgress.copy(
            isRunning = false,
            isDone = true,
            overallProcessedCount = 1240,
            overallTotalCount = 1240,
            progressPercent = 100
        )
        assertEquals("전체 1,240건 중 1,240건 (100% 완료)", doneProgress.formatQuantitativeSummary())
    }
}
