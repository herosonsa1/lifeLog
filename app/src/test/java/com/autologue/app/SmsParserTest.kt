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

    @Test
    fun parseUserReportedNhCardFuelMessages_correctlyIdentifiesAllFuelAndCancellations() {
        // 1. 09/01 18:56 지에스칼텍스(주 150,000원 가승인
        val msg1 = """
            [Web발신]
            NH카드6*9*승인
            정*우
            150,000원 일시불
            09/01 18:56
            지에스칼텍스(주
            총누적2,801,195원
        """.trimIndent()
        val tx1 = SmsParser.parse("1588-1600", msg1)
        assertNotNull(tx1)
        assertEquals(150000L, tx1?.amount)
        assertEquals("지에스칼텍스", tx1?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx1?.category)

        // 2. 09/01 18:59 지에스칼텍스(주 75,449원 실제 주유
        val msg2 = """
            [Web발신]
            NH카드6*9*승인
            정*우
            75,449원 일시불
            09/01 18:59
            지에스칼텍스(주
            총누적2,876,644원
        """.trimIndent()
        val tx2 = SmsParser.parse("1588-1600", msg2)
        assertNotNull(tx2)
        assertEquals(75449L, tx2?.amount)
        assertEquals("지에스칼텍스", tx2?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx2?.category)

        // 3. 09/01 18:59 지에스칼텍스(주)가 150,000원 승인취소
        val msg3 = """
            [Web발신]
            NH카드6*9*승인취소
            정*우
            150,000원
            09/01 18:59
            지에스칼텍스(주)가
            총누적2,726,644원
        """.trimIndent()
        val tx3 = SmsParser.parse("1588-1600", msg3)
        assertNotNull(tx3)
        assertEquals(-150000L, tx3?.amount)
        assertEquals("지에스칼텍스", tx3?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx3?.category)
        assertEquals("[승인취소]", tx3?.transferMemo)

        // 4. 09/01 21:03 (주)유진주유소 103,000원 주유
        val msg4 = """
            [Web발신]
            NH카드6*9*승인
            정*우
            103,000원 일시불
            09/01 21:03
            (주)유진주유소
            총누적2,829,644원
        """.trimIndent()
        val tx4 = SmsParser.parse("1588-1600", msg4)
        assertNotNull(tx4)
        assertEquals(103000L, tx4?.amount)
        assertEquals("유진주유소", tx4?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx4?.category)

        // 5. 09/18 21:06 (주)유진주유소 78,000원 주유
        val msg5 = """
            [Web발신]
            NH카드6*9*승인
            정*우
            78,000원 일시불
            09/18 21:06
            (주)유진주유소
            총누적4,036,159원
        """.trimIndent()
        val tx5 = SmsParser.parse("1588-1600", msg5)
        assertNotNull(tx5)
        assertEquals(78000L, tx5?.amount)
        assertEquals("유진주유소", tx5?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx5?.category)

        // 6. 09/23 18:02 지에스칼텍스(주 150,000원 가승인
        val msg6 = """
            [Web발신]
            NH카드6*9*승인
            정*우
            150,000원 일시불
            09/23 18:02
            지에스칼텍스(주
            총누적1,872,230원
        """.trimIndent()
        val tx6 = SmsParser.parse("1588-1600", msg6)
        assertNotNull(tx6)
        assertEquals(150000L, tx6?.amount)
        assertEquals("지에스칼텍스", tx6?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx6?.category)

        // 7. 09/23 18:05 지에스칼텍스(주 61,000원 실제 주유
        val msg7 = """
            [Web발신]
            NH카드6*9*승인
            정*우
            61,000원 일시불
            09/23 18:05
            지에스칼텍스(주
            총누적1,933,230원
        """.trimIndent()
        val tx7 = SmsParser.parse("1588-1600", msg7)
        assertNotNull(tx7)
        assertEquals(61000L, tx7?.amount)
        assertEquals("지에스칼텍스", tx7?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx7?.category)

        // 8. 09/23 18:05 지에스칼텍스(주)가 150,000원 승인취소
        val msg8 = """
            [Web발신]
            NH카드6*9*승인취소
            정*우
            150,000원
            09/23 18:05
            지에스칼텍스(주)가
            총누적1,783,230원
        """.trimIndent()
        val tx8 = SmsParser.parse("1588-1600", msg8)
        assertNotNull(tx8)
        assertEquals(-150000L, tx8?.amount)
        assertEquals("지에스칼텍스", tx8?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx8?.category)
        assertEquals("[승인취소]", tx8?.transferMemo)

        // 9. 09/27 18:31 한국도로공사하 101,000원 고속도로 알뜰주유소 주유
        val msg9 = """
            [Web발신]
            NH카드6*9*승인
            정*우
            101,000원 일시불
            09/27 18:31
            한국도로공사하
            총누적2,033,130원
        """.trimIndent()
        val tx9 = SmsParser.parse("1588-1600", msg9)
        assertNotNull(tx9)
        assertEquals(101000L, tx9?.amount)
        assertEquals("한국도로공사하", tx9?.merchantName)
        assertEquals(ExpenseCategory.FUEL, tx9?.category)
    }
}
