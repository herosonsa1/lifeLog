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
}
