package com.autologue.app.data.sync

import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.presentation.diary.map.isArrivalStep
import com.autologue.app.presentation.diary.map.isDepartureStep
import com.autologue.app.presentation.diary.map.isStopoverStep
import com.autologue.app.presentation.diary.map.segmentRouteIntoTrips
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GpsStopoverConsolidationTest {

    @Test
    fun `consolidateGpsDrivingSteps keeps only initial origin and final destination when intermediate GPS noise exists`() {
        val steps = listOf(
            RouteStep(
                id = "dep_1",
                time = LocalDateTime.of(2026, 10, 28, 8, 10),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 위례더샵포레스트 정문",
                locationName = "위례더샵포레스트 정문",
                latitude = 37.4785,
                longitude = 127.1465,
                tags = listOf("차량주행", "⭐ 벤츠", "승차", "출발지점")
            ),
            RouteStep(
                id = "mid_gps_1",
                time = LocalDateTime.of(2026, 10, 28, 8, 22),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 주행 경유 · 송파구 장지동",
                description = "GPS 백그라운드 수신",
                locationName = "장지동",
                latitude = 37.4860,
                longitude = 127.1260,
                tags = listOf("차량주행", "주행경유")
            ),
            RouteStep(
                id = "mid_dup_boarding",
                time = LocalDateTime.of(2026, 10, 28, 8, 30),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 강남구 수서역",
                locationName = "수서역",
                latitude = 37.4873,
                longitude = 127.1019,
                tags = listOf("차량주행", "승차")
            ),
            RouteStep(
                id = "arr_1",
                time = LocalDateTime.of(2026, 10, 28, 8, 52),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 · 타임스퀘어",
                locationName = "타임스퀘어",
                latitude = 37.5172,
                longitude = 126.9068,
                tags = listOf("차량주행", "⭐ 벤츠", "하차", "도착지점")
            )
        )

        val consolidated = DailyRouteAggregator.deduplicateRouteSteps(steps)

        assertEquals(2, consolidated.size)
        assertEquals("dep_1", consolidated[0].id)
        assertEquals("arr_1", consolidated[1].id)
        assertTrue(isDepartureStep(consolidated[0]))
        assertTrue(isArrivalStep(consolidated[1]))
        assertFalse(consolidated.any { isStopoverStep(it) })
        assertFalse(consolidated[1].title.contains("(42분)"))
        assertTrue(consolidated[1].description?.contains("42분 운행") == true)
    }

    @Test
    fun `consolidateGpsDrivingSteps merges alighting and re-departure within 1 hour at same location into single stopover`() {
        val steps = listOf(
            RouteStep(
                id = "dep_home",
                time = LocalDateTime.of(2026, 10, 28, 10, 0),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 위례더샵포레스트 정문",
                locationName = "위례더샵포레스트 정문",
                latitude = 37.4785,
                longitude = 127.1465,
                tags = listOf("차량주행", "⭐ 벤츠", "승차", "출발지점")
            ),
            RouteStep(
                id = "alight_stop",
                time = LocalDateTime.of(2026, 10, 28, 10, 20),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 · 판교역 상가주차장",
                locationName = "판교역 상가주차장",
                latitude = 37.3948,
                longitude = 127.1112,
                tags = listOf("차량주행", "⭐ 벤츠", "하차", "도착지점")
            ),
            // 동일 하차 위치(약 80m 이내)에서 25분(1,500초 <= 3,600초) 만에 다시 운행 시작
            RouteStep(
                id = "reboard_stop",
                time = LocalDateTime.of(2026, 10, 28, 10, 45),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 판교역 상가주차장",
                locationName = "판교역 상가주차장",
                latitude = 37.3951,
                longitude = 127.1115,
                tags = listOf("차량주행", "⭐ 벤츠", "승차", "출발지점")
            ),
            RouteStep(
                id = "arr_final",
                time = LocalDateTime.of(2026, 10, 28, 11, 15),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 · 현대백화점 무역센터점",
                locationName = "현대백화점 무역센터점",
                latitude = 37.5086,
                longitude = 127.0598,
                tags = listOf("차량주행", "⭐ 벤츠", "하차", "도착지점")
            )
        )

        val consolidated = DailyRouteAggregator.deduplicateRouteSteps(steps)

        // 최초 출발지 + 단일 경유지 + 최종 도착지 = 총 3개
        assertEquals(3, consolidated.size)
        assertTrue(isDepartureStep(consolidated[0]))
        assertTrue(isStopoverStep(consolidated[1]))
        assertFalse(isDepartureStep(consolidated[1]))
        assertFalse(isArrivalStep(consolidated[1]))
        assertTrue(isArrivalStep(consolidated[2]))

        assertEquals("⭐ [벤츠] 경유지 · 판교역 상가주차장", consolidated[1].title)
        assertTrue(consolidated[1].description?.contains("경유 체류 25분") == true)
        assertTrue("경유지" in consolidated[1].tags)
        // 순수 운행시간(1구간 20분 + 2구간 30분 = 50분)이 제목이 아닌 최종 도착지 설명란에 반영되는지 검증
        assertFalse(consolidated[2].title.contains("(50분)"))
        assertTrue(consolidated[2].description?.contains("50분 운행") == true)

        // 멱등성(2회 연속 호출해도 동일 결과 유지) 검증
        val secondPass = DailyRouteAggregator.consolidateGpsDrivingSteps(consolidated)
        assertEquals(consolidated, secondPass)

        // 트립 그룹 분할 시에도 쪼개지지 않고 [출발 ➔ 경유지(경유) ➔ 도착] 단일 트립으로 묶이는지 검증
        val trips = segmentRouteIntoTrips(consolidated)
        assertEquals(1, trips.size)
        assertEquals(3, trips[0].steps.size)
        assertEquals(
            "위례더샵포레스트 정문 ➔ 판교역 상가주차장(경유) ➔ 현대백화점 무역센터점",
            trips[0].subtitle
        )
    }

    @Test
    fun `consolidateGpsDrivingSteps merges exact 60-minute boundary at same stopover location`() {
        val steps = listOf(
            RouteStep(
                id = "s1",
                time = LocalDateTime.of(2026, 10, 28, 13, 0, 0),
                stepType = RouteStepType.DRIVING,
                title = "🚗 [테슬라] 승차 · 수서역",
                locationName = "수서역",
                latitude = 37.4873,
                longitude = 127.1019,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "s2",
                time = LocalDateTime.of(2026, 10, 28, 13, 20, 0),
                stepType = RouteStepType.DRIVING,
                title = "🚗 [테슬라] 하차 · 스타필드 하남",
                locationName = "스타필드 하남",
                latitude = 37.5455,
                longitude = 127.2237,
                tags = listOf("하차", "도착지점")
            ),
            // 정확히 60분(3,600초) 후 동일 위치에서 재출발 -> 1시간 이내 조건 충족으로 경유지 1개 통합
            RouteStep(
                id = "s3",
                time = LocalDateTime.of(2026, 10, 28, 14, 20, 0),
                stepType = RouteStepType.DRIVING,
                title = "🚗 [테슬라] 승차 · 스타필드 하남",
                locationName = "스타필드 하남",
                latitude = 37.5456,
                longitude = 127.2238,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "s4",
                time = LocalDateTime.of(2026, 10, 28, 14, 45, 0),
                stepType = RouteStepType.DRIVING,
                title = "🚗 [테슬라] 하차 · 수서역",
                locationName = "수서역",
                latitude = 37.4873,
                longitude = 127.1019,
                tags = listOf("하차", "도착지점")
            )
        )

        val consolidated = DailyRouteAggregator.consolidateGpsDrivingSteps(steps)
        assertEquals(3, consolidated.size)
        assertTrue(isStopoverStep(consolidated[1]))
    }

    @Test
    fun `consolidateGpsDrivingSteps keeps separate trips when stay exceeds 1 hour at same location`() {
        val steps = listOf(
            RouteStep(
                id = "t1_dep",
                time = LocalDateTime.of(2026, 10, 28, 9, 0, 0),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 위례더샵포레스트 정문",
                locationName = "위례더샵포레스트 정문",
                latitude = 37.4785,
                longitude = 127.1465,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "t1_arr",
                time = LocalDateTime.of(2026, 10, 28, 9, 30, 0),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 · 스타필드 코엑스몰",
                locationName = "스타필드 코엑스몰",
                latitude = 37.5116,
                longitude = 127.0595,
                tags = listOf("하차", "도착지점")
            ),
            // 61분(3,660초 > 3,600초) 체류 후 동일 위치 재출발 -> 경유지가 아닌 별개 운행의 출발지로 분리
            RouteStep(
                id = "t2_dep",
                time = LocalDateTime.of(2026, 10, 28, 10, 31, 0),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 스타필드 코엑스몰",
                locationName = "스타필드 코엑스몰",
                latitude = 37.5116,
                longitude = 127.0595,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "t2_arr",
                time = LocalDateTime.of(2026, 10, 28, 11, 5, 0),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 · 위례더샵포레스트 정문",
                locationName = "위례더샵포레스트 정문",
                latitude = 37.4785,
                longitude = 127.1465,
                tags = listOf("하차", "도착지점")
            )
        )

        val consolidated = DailyRouteAggregator.deduplicateRouteSteps(steps)

        assertEquals(4, consolidated.size)
        assertFalse(consolidated.any { isStopoverStep(it) })

        val trips = segmentRouteIntoTrips(consolidated)
        assertEquals(2, trips.size)
        assertEquals(2, trips[0].steps.size)
        assertEquals(2, trips[1].steps.size)
    }

    @Test
    fun `consolidateGpsDrivingSteps keeps separate trips when re-departure within 1 hour is from a different location`() {
        val steps = listOf(
            RouteStep(
                id = "d1",
                time = LocalDateTime.of(2026, 10, 28, 15, 0),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 위례더샵포레스트 정문",
                locationName = "위례더샵포레스트 정문",
                latitude = 37.4785,
                longitude = 127.1465,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "a1",
                time = LocalDateTime.of(2026, 10, 28, 15, 20),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 · 수서역",
                locationName = "수서역",
                latitude = 37.4873,
                longitude = 127.1019,
                tags = listOf("하차", "도착지점")
            ),
            // 25분 이내 재출발이지만 하차지(수서역)가 아닌 약 4.5km 떨어진 잠실역에서 다른 차량/운행 출발 -> 병합 금지
            RouteStep(
                id = "d2",
                time = LocalDateTime.of(2026, 10, 28, 15, 45),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 · 잠실역",
                locationName = "잠실역",
                latitude = 37.5133,
                longitude = 127.1001,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "a2",
                time = LocalDateTime.of(2026, 10, 28, 16, 15),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 · 현대백화점 무역센터점",
                locationName = "현대백화점 무역센터점",
                latitude = 37.5086,
                longitude = 127.0598,
                tags = listOf("하차", "도착지점")
            )
        )

        val consolidated = DailyRouteAggregator.deduplicateRouteSteps(steps)

        assertEquals(4, consolidated.size)
        assertFalse(consolidated.any { isStopoverStep(it) })

        val trips = segmentRouteIntoTrips(consolidated)
        assertEquals(2, trips.size)
    }
}
