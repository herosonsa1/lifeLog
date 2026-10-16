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

    @Test
    fun parseMultiple_userPastedBlock_parsesAllMessagesSuccessfully() {
        val giantPastedBlock = """
            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            150,000원 일시불
            09/01 18:56
            지에스칼텍스(주
            총누적2,801,195원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            75,449원 일시불
            09/01 18:59
            지에스칼텍스(주
            총누적2,876,644원

            알림
            [Web발신]
            NH카드6*9*승인취소
            정*우
            150,000원
            09/01 18:59
            지에스칼텍스(주)가
            총누적2,726,644원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            103,000원 일시불
            09/01 21:03
            (주)유진주유소
            총누적2,829,644원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            78,000원 일시불
            09/18 21:06
            (주)유진주유소
            총누적4,036,159원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            150,000원 일시불
            09/23 18:02
            지에스칼텍스(주
            총누적1,872,230원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            61,000원 일시불
            09/23 18:05
            지에스칼텍스(주
            총누적1,933,230원

            알림
            [Web발신]
            NH카드6*9*승인취소
            정*우
            150,000원
            09/23 18:05
            지에스칼텍스(주)가
            총누적1,783,230원

            알림
            [Web발신]
            NH카드6*9*승인
            정*우
            101,000원 일시불
            09/27 18:31
            한국도로공사하
            총누적2,033,130원
        """.trimIndent()

        val parsedList = SmsParser.parseMultiple(giantPastedBlock)
        assertEquals(9, parsedList.size)

        val fuelList = parsedList.filter { it.category == ExpenseCategory.FUEL }
        assertEquals(9, fuelList.size)

        val merchants = parsedList.map { it.merchantName }.toSet()
        assertEquals(setOf("한국도로공사하", "지에스칼텍스", "유진주유소"), merchants)
    }

    @Test
    fun parseCardSmsWithPhoneOrBalance_neverMisclassifiedAsBankTransfer() {
        // 고객센터 전화번호(1588-1600)나 카드 마스킹 번호, 체크출금/잔액 문구가 함께 포함된 카드 승인 문자
        val raw = """
            [Web발신]
            KB국민체크(1234)
            정선우님
            09/25 14:20
            68,000원 체크출금 승인
            SK에너지 강남주유소
            잔액 520,000원
            문의 1588-1688
        """.trimIndent()

        val tx = SmsParser.parse("1588-1688", raw)
        assertNotNull(tx)
        assertEquals(68000L, tx?.amount)
        assertEquals(ExpenseCategory.FUEL, tx?.category)
        assertEquals(com.autologue.app.domain.model.PaymentMethod.CHECK_CARD, tx?.paymentMethod)
        // 본인 계좌 이체(isSelfTransfer)로 오인되어 지출 합계에서 누락되지 않아야 함
        assertEquals(false, tx?.category == ExpenseCategory.TRANSFER)
    }

    @Test
    fun parseMultilineSamsungAndKbCardSms_extractsFuelAndCardCorrectly() {
        val samsungMultiline = """
            [Web발신]
            삼성카드 3412 승인
            정*우
            82,000원 일시불
            09/28 19:15
            에쓰오일 판교주유소
            누적 1,450,000원
        """.trimIndent()
        val tx1 = SmsParser.parse("1588-8700", samsungMultiline)
        assertNotNull(tx1)
        assertEquals(82000L, tx1?.amount)
        assertEquals(ExpenseCategory.FUEL, tx1?.category)
        assertEquals(com.autologue.app.domain.model.PaymentMethod.CREDIT_CARD, tx1?.paymentMethod)

        val universalCard = """
            [Web발신]
            우리카드(9*1*) 승인
            정*우님
            45,000원 일시불
            09/29 12:40
            스타벅스 코리아
            누적 890,000원
        """.trimIndent()
        val tx2 = SmsParser.parse("1588-9955", universalCard)
        assertNotNull(tx2)
        assertEquals(45000L, tx2?.amount)
        assertEquals(ExpenseCategory.CAFE, tx2?.category)
        assertEquals(com.autologue.app.domain.model.PaymentMethod.CREDIT_CARD, tx2?.paymentMethod)
    }

    @Test
    fun notificationParser_samsungMessagingRcsAndCardApp_parsesFuelAndCardAccurately() {
        val rcsText = """
            [Web발신]
            NH카드6*9*승인
            정*우
            101,000원 일시불
            09/27 18:31
            한국도로공사하
            총누적2,033,130원
        """.trimIndent()

        val res = com.autologue.app.data.parser.NotificationParser.parse(
            packageName = "com.samsung.android.messaging",
            title = "NH농협카드 (1588-1600)",
            text = rcsText
        )
        assertNotNull(res)
        val txResult = res as com.autologue.app.data.parser.NotificationParser.ParsedNotificationResult.TxResult
        assertEquals(101000L, txResult.transaction.amount)
        assertEquals("한국도로공사하", txResult.transaction.merchantName)
        assertEquals(ExpenseCategory.FUEL, txResult.transaction.category)
        assertEquals(com.autologue.app.domain.model.PaymentMethod.CREDIT_CARD, txResult.transaction.paymentMethod)
    }

    @Test
    fun parseMmsSubjectPrefixAndNonPaymentStockNews_rejectsFakeJemokAndExtractsRealMerchant() {
        // 1. 삼성 메시지 통합 테이블에서 sub="제목"과 결합된 비결제 주식/시황 문자 -> null 반환 (가짜 '제목' 주유 기록 원천 차단)
        val stockSmsWithJemok = """
            제목
            [Web발신]
            (광고) 오늘의 증시 시황 안내
            SK이노베이션 목표주가 120,000원 상향
            에쓰오일 매수 추천 30,000원
            무료거부 080-1234-5678
        """.trimIndent()
        val fakeTx1 = SmsParser.parse("02-1234-5678", stockSmsWithJemok)
        assertEquals(null, fakeTx1)

        // 2. 충전 포인트/이벤트 안내 문자 -> null 반환
        val pointEventWithJemok = """
            제목
            [안내] 포인트 충전 완료 30,000원 쿠폰 지급 안내
        """.trimIndent()
        val fakeTx2 = SmsParser.parse("1588-0000", pointEventWithJemok)
        assertEquals(null, fakeTx2)

        // 3. 실제 카드 승인 문자 앞에 MMS "제목" 줄이 붙어 있는 경우 -> "제목"을 제거하고 실제 주유소("GS칼텍스 잠실주유소")만 정확히 추출
        val realCardWithJemok = """
            제목
            [Web발신]
            NH카드6*9*승인
            정*우
            65,000원 일시불
            10/15 18:10
            GS칼텍스 잠실주유소
            총누적1,250,000원
        """.trimIndent()
        val realTx = SmsParser.parse("1588-1600", realCardWithJemok)
        assertNotNull(realTx)
        assertEquals(65000L, realTx?.amount)
        assertEquals("GS칼텍스 잠실주유소", realTx?.merchantName)
        assertEquals(ExpenseCategory.FUEL, realTx?.category)
    }

    @Test
    fun permanentSaveOptions_defaultsAndSelectiveConfig_preservesAmountsAndAppliesSelectedFields() {
        val defaultOptions = com.autologue.app.data.preferences.PermanentSaveOptions()
        assertEquals(true, defaultOptions.applyMerchantName)
        assertEquals(true, defaultOptions.applyCategory)
        assertEquals(true, defaultOptions.applyUserClassification)
        assertEquals(true, defaultOptions.applyCardAndCorporate)
        assertEquals(true, defaultOptions.applyFixedExpense)
        assertEquals(false, defaultOptions.applyMemo)

        val customOptions = com.autologue.app.data.preferences.PermanentSaveOptions(
            applyMerchantName = false,
            applyCategory = true,
            applyUserClassification = true,
            applyCardAndCorporate = true,
            applyFixedExpense = false,
            applyMemo = false,
            selectedExistingTxIds = setOf(101L, 202L, 303L)
        )
        assertEquals(false, customOptions.applyMerchantName)
        assertEquals(true, customOptions.applyCategory)
        assertEquals(true, customOptions.applyUserClassification)
        assertEquals(3, customOptions.selectedExistingTxIds?.size)
        assertEquals(
            true,
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("업무/법인")
        )
    }
}

