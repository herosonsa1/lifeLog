package com.autologue.app

import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.data.sync.PlaceResolver
import com.autologue.app.data.sync.ScannedPhoto
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DailyRouteAggregatorTest {

    private lateinit var placeResolver: PlaceResolver
    private lateinit var aggregator: DailyRouteAggregator

    @Before
    fun setUp() {
        placeResolver = PlaceResolver()
        aggregator = DailyRouteAggregator(placeResolver)
    }

    @Test
    fun aggregateForDate_excludesPhotoRecordFromMovementSummary() {
        val targetDate = LocalDate.of(2026, 9, 21)

        // 10:46 위치 없는 사진 ("사진 기록")
        val photo1 = ScannedPhoto(
            uri = "content://media/1",
            time = targetDate.atTime(10, 46),
            latitude = null,
            longitude = null,
            placeName = "사진 기록"
        )
        // 19:36 하남시 사진 (위치 메타데이터 기반 -> "경기 하남시")
        val photo2 = ScannedPhoto(
            uri = "content://media/2",
            time = targetDate.atTime(19, 36),
            latitude = 37.4912,
            longitude = 127.1512,
            placeName = "경기 하남시"
        )

        val diary = aggregator.aggregateForDate(
            date = targetDate,
            photos = listOf(photo1, photo2),
            transactions = emptyList(),
            vehicleLogs = emptyList()
        )

        // "사진 기록"이 동선 체인에서 제외되고 불필요한 도 접두어를 제거한 장소명만 표출되는지 검증
        assertFalse(diary.movementSummary?.contains("사진 기록") == true)
        assertEquals("하남시", diary.movementSummary)
        assertEquals("하남시 일정", diary.title)
        assertFalse(diary.hasGolfRound)
    }

    @Test
    fun aggregateForDate_appliesSpecificPoiNamesAndDrivingDurationWithoutDuplicateDistance() {
        val targetDate = LocalDate.of(2026, 9, 22)

        val aptGatePhoto = ScannedPhoto(
            uri = "content://media/apt_gate",
            time = targetDate.atTime(8, 10),
            latitude = 37.4785,
            longitude = 127.1465,
            placeName = "경기도 하남시 학암동 위례더샵포레스트 정문"
        )

        val drivingLog = com.autologue.app.domain.model.VehicleLog(
            id = 50L,
            timestamp = targetDate.atTime(9, 0),
            logType = com.autologue.app.domain.model.VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[car_1: 벤츠] [위례더샵포레스트 정문 ➔ 타임스퀘어 (27.1 km)] (42분 운행)"
        )

        val diary = aggregator.aggregateForDate(
            date = targetDate,
            photos = listOf(aptGatePhoto),
            transactions = emptyList(),
            vehicleLogs = listOf(drivingLog)
        )

        assertEquals("위례더샵포레스트 정문 ➔ 타임스퀘어", diary.movementSummary)
        assertEquals("위례더샵포레스트 정문 & 타임스퀘어", diary.title)
        assertTrue(diary.routeSteps.any { it.title == "위례더샵포레스트 정문" })
        assertTrue(diary.routeSteps.any { it.title.contains("승차 · 위례더샵포레스트 정문") })
        assertTrue(diary.routeSteps.any {
            it.title.contains("하차 · 타임스퀘어") &&
                !it.title.contains("42분") &&
                !it.title.contains("km") &&
                it.description?.contains("42분 운행") == true
        })
        assertTrue(diary.summary.contains("42분 운행"))
    }

    @Test
    fun standaloneOrphanAndMidnightDrivingSteps_and_legacySummary_alwaysIncludeDuration() {
        // 1. 단독 DRIVING 스텝('승차/하차' 키워드 없이 제목에 '(13분)'만 있던 기록) -> 설명란으로 '13분 운행' 이관 보존
        val standaloneStep = com.autologue.app.domain.model.RouteStep(
            id = "standalone_1",
            time = java.time.LocalDateTime.of(2026, 10, 28, 12, 15),
            stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
            title = "⭐ [벤츠] 영등포역 정류장 인근 주행 (13분)",
            locationName = "영등포역,타임스퀘어 정류장"
        )
        val normalizedStandalone = DailyRouteAggregator.deduplicateRouteSteps(listOf(standaloneStep))
        assertEquals(1, normalizedStandalone.size)
        assertFalse(normalizedStandalone[0].title.contains("13분"))
        assertTrue(normalizedStandalone[0].description?.contains("13분 운행") == true)

        // 2. 선행 승차 스텝이 누락된 단독 하차 스텝(1개짜리 리스트)도 운행시간이 비지 않고 채워지는지 검증
        val orphanArrival = com.autologue.app.domain.model.RouteStep(
            id = "orphan_arr",
            time = java.time.LocalDateTime.of(2026, 10, 28, 19, 30),
            stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
            title = "⭐ [벤츠] 하차 · 스타필드 하남",
            description = "집 ➔ 스타필드 하남 (11.5 km)",
            locationName = "스타필드 하남",
            tags = listOf("하차", "도착지점")
        )
        val enrichedOrphan = DailyRouteAggregator.enrichStepPairDurations(listOf(orphanArrival))
        assertEquals(1, enrichedOrphan.size)
        assertTrue(enrichedOrphan[0].description?.contains("운행") == true)

        // 3. 자정 경유 주행(23:50 승차 -> 익일 00:15 하차 = 25분 운행) 소요시간 산출 검증
        val midnightDep = com.autologue.app.domain.model.RouteStep(
            id = "mid_dep",
            time = java.time.LocalDateTime.of(2026, 10, 27, 23, 50),
            stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
            title = "⭐ [벤츠] 승차 · 회사",
            locationName = "회사",
            tags = listOf("승차", "출발지점")
        )
        val midnightArr = com.autologue.app.domain.model.RouteStep(
            id = "mid_arr",
            time = java.time.LocalDateTime.of(2026, 10, 28, 0, 15),
            stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
            title = "⭐ [벤츠] 하차 · 집",
            locationName = "집",
            tags = listOf("하차", "도착지점")
        )
        val enrichedMidnight = DailyRouteAggregator.deduplicateRouteSteps(listOf(midnightDep, midnightArr))
        assertEquals(2, enrichedMidnight.size)
        assertTrue(enrichedMidnight[1].description?.contains("25분 운행") == true)

        // 4. 기존 DB 다이어리 요약문(summary)에 운행시간이 빠져 있었을 때 즉시 보강되는지 검증
        val healedSummary = DailyRouteAggregator.ensureSummaryIncludesDrivingDuration(
            rawSummary = "23:50 ⭐ [벤츠] 승차 · 회사 ➔ 00:15 ⭐ [벤츠] 하차 · 집 (방문 장소 2곳)",
            steps = enrichedMidnight,
            drivingDistanceKm = 27.1,
            referenceDate = LocalDate.of(2026, 10, 28)
        )
        assertTrue(healedSummary.contains("25분 운행"))
    }

    @Test
    fun aggregateForDate_combinesPhotosAndTransactionsIntoDailySummary() {
        val targetDate = LocalDate.of(2026, 9, 21)

        val familyPhoto = ScannedPhoto(
            uri = "content://media/family",
            time = targetDate.atTime(14, 20),
            latitude = 37.5450,
            longitude = 127.2230,
            placeName = "스타필드 하남"
        )

        val tx = Transaction(
            id = 10L,
            amount = 42_000L,
            merchantName = "폴바셋 하남스타필드점",
            originalText = "승인 42,000원 폴바셋 하남스타필드점",
            timestamp = targetDate.atTime(15, 10),
            paymentMethod = PaymentMethod.CREDIT_CARD,
            category = ExpenseCategory.CAFE,
            cardOrBankName = "현대카드"
        )

        val diary = aggregator.aggregateForDate(
            date = targetDate,
            photos = listOf(familyPhoto),
            transactions = listOf(tx),
            vehicleLogs = emptyList()
        )

        assertEquals(42_000L, diary.totalExpense)
        assertFalse(diary.movementSummary?.contains("사진 기록") == true)
        assertTrue(diary.routeSteps.size >= 2)
    }
}
