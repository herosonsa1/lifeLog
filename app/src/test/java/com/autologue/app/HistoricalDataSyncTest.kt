package com.autologue.app

import com.autologue.app.data.parser.SmsParser
import com.autologue.app.domain.model.ExpenseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.time.YearMonth

class HistoricalDataSyncTest {

    @Test
    fun batchSmsParsing_parsesMultipleCardsCorrectly() {
        val sampleSmsList = listOf(
            "[Web발신] 신한카드 승인 15,000원 08/10 12:30 스타벅스",
            "현대카드 M3 승인 60,000원 일시불 08/15 14:00 GS칼텍스",
            "삼성카드 250,000원 승인 08/20 07:30 남촌CC",
            "[KB국민카드] 12,000원 승인 08/25 19:00 맥도날드"
        )

        val parsed = sampleSmsList.mapNotNull { SmsParser.parse(null, it) }

        assertEquals(4, parsed.size)
        assertEquals(ExpenseCategory.CAFE, parsed[0].category)
        assertEquals(ExpenseCategory.FUEL, parsed[1].category)
        assertEquals(ExpenseCategory.GOLF_FIELD, parsed[2].category)
        assertEquals(ExpenseCategory.FOOD, parsed[3].category)
    }

    @Test
    fun yearMonthDayCalculations_matchCalendarGrid() {
        val ym = YearMonth.of(2026, 8)
        assertEquals(31, ym.lengthOfMonth())
        val firstDay = ym.atDay(1)
        // 2026-08-01 is Saturday (6)
        assertEquals(java.time.DayOfWeek.SATURDAY, firstDay.dayOfWeek)
    }

    @Test
    fun deduplicateRouteSteps_mergesIdenticalPhotoStepsCorrectly() {
        val photoUris = (1..32).map { "content://media/external/images/media/$it" }
        val time = java.time.LocalDateTime.of(2026, 9, 7, 6, 30)

        // 앱 동기화/업데이트 반복으로 동일한 장소·시간의 사진 32장 스텝이 8회 중복 누적된 시나리오
        val duplicateSteps = (1..8).map { _ ->
            com.autologue.app.domain.model.RouteStep(
                id = java.util.UUID.randomUUID().toString(),
                time = time,
                stepType = com.autologue.app.domain.model.RouteStepType.PHOTO,
                title = "충청북 주덕읍",
                description = "사진 32장 촬영",
                locationName = "충청북 주덕읍",
                address = "대한민국 충청북도 충주시 주덕읍 화곡리 1091",
                latitude = 37.0,
                longitude = 127.8,
                photoUris = photoUris
            )
        }

        val cleaned = com.autologue.app.data.sync.DailyRouteAggregator.deduplicateRouteSteps(duplicateSteps)

        // 8개 중복 스텝이 완벽히 1개로 병합되고 순수 읍 명칭('주덕읍')으로 정규화되었는지 검증
        assertEquals(1, cleaned.size)
        assertEquals("주덕읍", cleaned[0].title)
        assertEquals(32, cleaned[0].photoUris.size)
        assertEquals("사진 32장 촬영", cleaned[0].description)
    }

    @Test
    fun segmentRouteIntoTrips_and_resolveEffectiveRouteEndpoints_handlesCommuteRoundTripCorrectly() {
        val steps = listOf(
            com.autologue.app.domain.model.RouteStep(
                id = "1",
                time = java.time.LocalDateTime.of(2026, 10, 19, 6, 15),
                stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 (출발)",
                locationName = "하남시 학암동",
                address = "대한민국 경기도 하남시 학암동 652",
                latitude = 37.4785,
                longitude = 127.1465,
                tags = listOf("차량주행", "⭐ 벤츠", "승차", "출발지점")
            ),
            com.autologue.app.domain.model.RouteStep(
                id = "2",
                time = java.time.LocalDateTime.of(2026, 10, 19, 7, 15),
                stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 (총 28.5 km)",
                locationName = "서울 영등포구",
                address = "대한민국 서울특별시 영등포구 여의도동",
                latitude = 37.5187,
                longitude = 126.9098,
                tags = listOf("차량주행", "⭐ 벤츠", "하차", "도착지점")
            ),
            com.autologue.app.domain.model.RouteStep(
                id = "3",
                time = java.time.LocalDateTime.of(2026, 10, 19, 17, 30),
                stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 (출발)",
                locationName = "서울 영등포구",
                address = "대한민국 서울특별시 영등포구 여의도동",
                latitude = 37.5187,
                longitude = 126.9098,
                tags = listOf("차량주행", "⭐ 벤츠", "승차", "출발지점")
            ),
            com.autologue.app.domain.model.RouteStep(
                id = "4",
                time = java.time.LocalDateTime.of(2026, 10, 19, 18, 32),
                stepType = com.autologue.app.domain.model.RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 (총 28.5 km)",
                locationName = "하남시 학암동",
                address = "대한민국 경기도 하남시 학암동 652",
                latitude = 37.4785,
                longitude = 127.1465,
                tags = listOf("차량주행", "⭐ 벤츠", "하차", "도착지점")
            )
        )

        // 1. 60분이 소요된 출근/퇴근 주행이 각각 1지점씩 쪼개지지 않고 2지점씩 2개의 트립으로 묶여야 함
        val tripGroups = com.autologue.app.presentation.diary.map.segmentRouteIntoTrips(steps)
        assertEquals(2, tripGroups.size)
        assertEquals(2, tripGroups[0].steps.size)
        assertEquals(2, tripGroups[1].steps.size)
        assertEquals(60, tripGroups[0].durationMinutes)
        assertEquals(62, tripGroups[1].durationMinutes)
        assertEquals("학암동 ➔ 여의도동", tripGroups[0].subtitle)

        // 2. 전체 왕복 동선(하남 출발 -> 서울 -> 하남 도착)에서 경로 앱 실행 시 출발지(하남)와 주요 목적지(서울)가 선택되어야 함
        val endpoints = com.autologue.app.presentation.diary.map.resolveEffectiveRouteEndpoints(steps)
        assertNotNull(endpoints)
        assertEquals("하남시 학암동", endpoints!!.first.locationName)
        assertEquals("서울 영등포구", endpoints.second.locationName)

        // 3. 상세 한국 지도 HTML에 구글 한국 지도 타일(hl=ko&gl=kr)과 시·군·구 지역경계선 데이터가 포함되어야 함
        val html = com.autologue.app.presentation.diary.map.buildInteractiveHtmlMap(steps, null)
        org.junit.Assert.assertTrue(html.contains("hl=ko&gl=kr"))
        org.junit.Assert.assertTrue(html.contains("하남시"))
        org.junit.Assert.assertTrue(html.contains("송파구"))
    }
}
