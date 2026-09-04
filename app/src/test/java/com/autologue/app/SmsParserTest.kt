package com.autologue.app

import com.autologue.app.data.parser.SmsParser
import com.autologue.app.domain.model.ExpenseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class SmsParserTest {

    @Test
    fun parseShinhanCardSms_correctlyExtractsFields() {
        val raw = "[Web발신] 신한카드 승인 홍*동 45,000원(일시불) 08/31 12:30 스타벅스강남점 누적1,200,000원"
        val tx = SmsParser.parse("신한카드", raw)

        assertNotNull(tx)
        assertEquals(45000L, tx?.amount)
        assertEquals("스타벅스강남점", tx?.merchantName)
        assertEquals(ExpenseCategory.CAFE, tx?.category)
    }

    @Test
    fun parseHyundaiCardGasStation_correctlyExtractsFuel() {
        val raw = "현대카드 M3 승인 홍길동 55,000원 일시불 08/31 14:20 GS칼텍스역삼주유소"
        val tx = SmsParser.parse("현대카드", raw)

        assertNotNull(tx)
        assertEquals(55000L, tx?.amount)
        assertEquals(ExpenseCategory.FUEL, tx?.category)
    }

    @Test
    fun parseGolfClub_correctlyExtractsGolfField() {
        val raw = "삼성카드 280,000원 승인 08/31 18:10 레이크사이드CC"
        val tx = SmsParser.parse("삼성카드", raw)

        assertNotNull(tx)
        assertEquals(280000L, tx?.amount)
        assertEquals(ExpenseCategory.GOLF_FIELD, tx?.category)
    }
}
