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
        val duplicateSteps = (1..8).map { index ->
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

        // 8개 중복 스텝이 완벽히 1개로 병합되었는지 검증
        assertEquals(1, cleaned.size)
        assertEquals("충청북 주덕읍", cleaned[0].title)
        assertEquals(32, cleaned[0].photoUris.size)
        assertEquals("사진 32장 촬영", cleaned[0].description)
    }
}
