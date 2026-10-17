package com.autologue.app.data.parser

import com.autologue.app.domain.model.isBankAccountTransaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class SmsParserTest {

    @Test
    fun `결제예정금액 및 총사용금액 안내 문자는 거래로 등록되지 않아야 한다`() {
        val scheduledSms = """
            [Web발신]
            삼성카드
            10/15 결제예정금액 안내
            결제금액 211,254원
        """.trimIndent()
        assertTrue(SmsParser.isNonPaymentMessage(scheduledSms))
        assertNull(SmsParser.parse("삼성카드", scheduledSms))

        val totalUsageSms = """
            [Web발신]
            삼성카드 정*우님
            총사용금액 211,254원 (10/15 기준)
        """.trimIndent()
        assertTrue(SmsParser.isNonPaymentMessage(totalUsageSms))
        assertNull(SmsParser.parse("삼성카드", totalUsageSms))

        val cumulativeOnlySms = """
            [Web발신]
            삼성카드
            10/15 13:55
            누적이용금액 211,254원
        """.trimIndent()
        assertTrue(SmsParser.isNonPaymentMessage(cumulativeOnlySms))
        assertNull(SmsParser.parse("삼성카드", cumulativeOnlySms))
    }

    @Test
    fun `슬래시 구분 카드 알림에서 누적이용금액이 결제금액이나 가맹점명으로 오인입되지 않아야 한다`() {
        val title = "(결제) 4,500원"
        val bodyOnly = "지에스25송파물류단지점 / 신용(,0*2*) / 10/16 18:36 / 누적이용금액 497,507원"

        // 본문 단독 파싱 시 실제 승인금액 없이 누적이용금액만 존재하므로 반드시 null 반환
        assertNull(SmsParser.parse(title, bodyOnly))

        // NotificationParser 통합 파싱 시 실제 결제금액 4,500원과 정상 가맹점명만 추출되어야 함
        val notifResult = NotificationParser.parse(
            packageName = "com.hanaskcard.paycla",
            title = title,
            text = bodyOnly
        ) as? NotificationParser.ParsedNotificationResult.TxResult

        assertNotNull(notifResult)
        val tx = notifResult!!.transaction
        assertEquals(4500L, tx.amount)
        assertEquals("지에스25송파물류단지점", tx.merchantName)
        assertEquals("하나카드", tx.cardOrBankName)
    }

    @Test
    fun `카드사 및 은행 힌트 문자열에서 표준 카드사명과 은행명이 자동 추출되어야 한다`() {
        assertEquals(
            "하나카드",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalCardCompany("하나0*2*")
        )
        assertEquals(
            "NH농협카드",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalCardCompany("NH농협1*3*")
        )
        assertEquals(
            "우리카드",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalCardCompany("우리(4*5*)")
        )
        assertEquals(
            "KB국민카드",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalCardCompany("KB국민체크")
        )

        assertEquals(
            "NH농협은행",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName("NH농협")
        )
        assertEquals(
            "우리은행",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName("우리")
        )
        assertEquals(
            "하나은행",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName("하나")
        )
        assertEquals(
            "KB국민은행",
            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName("KB국민")
        )
    }

    @Test
    fun `기존 DB에 잘못 저장된 누적이용금액 및 금액 오탐 레코드를 정확히 식별해야 한다`() {
        assertTrue(
            SmsParser.isNonUsageOrCorruptedTransaction(
                merchantName = "지에스25송파물류단지점 / 신용(,0*2*) / / 누적이용금액",
                cardOrBankName = "(결제) 4,500원",
                originalText = "지에스25송파물류단지점 / 신용(,0*2*) / 10/16 18:36 / 누적이용금액 497,507원"
            )
        )
        assertTrue(
            SmsParser.isNonUsageOrCorruptedTransaction(
                merchantName = "금액",
                cardOrBankName = "삼성카드",
                originalText = "삼성카드 금액 211,254원"
            )
        )
        assertFalse(
            SmsParser.isNonUsageOrCorruptedTransaction(
                merchantName = "지에스25송파물류단지",
                cardOrBankName = "하나0*2*",
                originalText = "승인 금액 4,500원 카드 하나0*2* 사용처 지에스25송파물류단지"
            )
        )
    }

    @Test
    fun `동일 상대방 이름이라도 입금과 출금 내역이 각각 다른 방향 구분값으로 판별 및 키 분리되어야 한다`() {
        val depositSms = """
            [Web발신]
            NH농협 입금 50,000원
            10/16 14:20 312-****-9414-21
            이민희 잔액 433,252원
        """.trimIndent()

        val withdrawSms = """
            [Web발신]
            NH농협 출금 22,000원
            10/16 15:10 312-****-9414-21
            이민희 잔액 411,252원
        """.trimIndent()

        val depositTx = SmsParser.parse("NH농협", depositSms)
        val withdrawTx = SmsParser.parse("NH농협", withdrawSms)

        assertNotNull(depositTx)
        assertNotNull(withdrawTx)
        assertEquals("NH농협은행", depositTx!!.cardOrBankName)
        assertEquals("NH농협은행", withdrawTx!!.cardOrBankName)
        assertTrue(depositTx.isBankAccountTransaction())
        assertTrue(withdrawTx.isBankAccountTransaction())

        assertTrue(com.autologue.app.domain.model.isDepositDirection(
            category = depositTx.category,
            merchantName = depositTx.merchantName,
            originalText = depositSms,
            transferMemo = depositTx.transferMemo
        ))
        assertFalse(com.autologue.app.domain.model.isDepositDirection(
            category = withdrawTx.category,
            merchantName = withdrawTx.merchantName,
            originalText = withdrawSms,
            transferMemo = withdrawTx.transferMemo
        ))

        // 동일 인물("이민희")에 대해 입금 키(IN::)와 출금 키(OUT::)가 서로 달라야 함
        val inKey = com.autologue.app.data.preferences.MerchantAliasPreferences.buildDirectionalBaseKey(depositTx.merchantName, isDeposit = true)
        val outKey = com.autologue.app.data.preferences.MerchantAliasPreferences.buildDirectionalBaseKey(withdrawTx.merchantName, isDeposit = false)
        assertEquals("IN::이민희", inKey)
        assertEquals("OUT::이민희", outKey)
        assertFalse(inKey == outKey)
    }

    @Test
    fun `업무 및 법인 분류 선택 시 법인카드 기본값 판별 및 추출 가능한 카드사 정보 기준 법인카드 영구저장 키 추출 검증`() {
        // 1. 업무/법인 분류 및 카테고리 판별 검증
        assertTrue(com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("업무/법인"))
        assertTrue(com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("법인"))
        assertTrue(com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("업무"))
        assertTrue(com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("법인경비"))
        assertFalse(com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("본인"))
        assertFalse(com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("가족"))
        assertFalse(com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification("식비"))

        // 2. 추출 가능한 카드사 정보(원본 카드 토큰 및 표준 카드사명) 기준 키 추출 검증
        val hanaHint = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.extractRawCardHintToken(
            rawCardOrBankName = "하나0*2*",
            originalText = "승인 금액 4,500원 카드 하나0*2* 사용처 지에스25"
        )
        assertEquals("하나0*2*", hanaHint)

        val hanaKeys = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.extractCardCorporateKeys(
            cardCompany = "하나카드",
            rawCardOrBankName = "하나0*2*",
            originalText = "승인 금액 4,500원 카드 하나0*2* 사용처 지에스25"
        )
        assertTrue(hanaKeys.contains("하나0*2*"))
        assertTrue(hanaKeys.contains("하나카드#0*2*"))
        assertTrue(hanaKeys.contains("하나카드"))

        val nhKeys = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.extractCardCorporateKeys(
            cardCompany = null,
            rawCardOrBankName = "NH2*7*",
            originalText = "[Web발신] NH카드2*7* 승인 15,000원 일시불"
        )
        assertTrue(nhKeys.contains("NH농협카드#2*7*"))
        assertTrue(nhKeys.contains("NH농협카드"))
    }

    @Test
    fun `네이버페이 관련 문자 및 알림은 모든 경로에서 이력 등록 대상에서 제외되어야 한다`() {
        val naverPaySms1 = """
            [Web발신]
            네이버페이 결제완료
            25,000원
            10/17 14:20
            네이버파이낸셜
        """.trimIndent()
        assertTrue(SmsParser.isNaverPayExcluded(originalText = naverPaySms1))
        assertNull(SmsParser.parse("네이버페이", naverPaySms1))

        val naverPayCardSms = """
            [Web발신]
            신한카드(1234) 승인 정*우 32,000원(일시불) 10/17 15:10 네이버페이 누적 450,000원
        """.trimIndent()
        assertTrue(SmsParser.isNaverPayExcluded(merchantName = "네이버페이", cardOrBankName = "신한카드", originalText = naverPayCardSms))
        assertNull(SmsParser.parse("신한카드", naverPayCardSms))

        val npayNotif = NotificationParser.parse(
            packageName = "com.nhn.android.search",
            title = "네이버페이 결제",
            text = "네이버페이 19,800원 결제 완료"
        )
        assertNull(npayNotif)

        // 일반 가맹점 결제는 정상 파싱되어야 함
        val normalCardSms = """
            [Web발신]
            신한카드(1234) 승인 정*우 12,000원(일시불) 10/17 15:30 스타벅스판교점 누적 462,000원
        """.trimIndent()
        val normalTx = SmsParser.parse("신한카드", normalCardSms)
        assertNotNull(normalTx)
        assertEquals(12000L, normalTx!!.amount)
    }

    @Test
    fun `특정 항목 삭제 시 동일 날짜 및 금액과 가맹점의 쌍둥이 레코드까지 함께 삭제 판별되어야 한다`() {
        val prefs = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences()
        val txPrimary = com.autologue.app.domain.model.Transaction(
            id = 101L,
            amount = 15000L,
            merchantName = "스타벅스 판교점",
            originalText = "[Web발신] 신한카드 승인 15,000원 스타벅스 판교점",
            timestamp = LocalDateTime.of(2026, 10, 17, 12, 30, 10),
            paymentMethod = com.autologue.app.domain.model.PaymentMethod.CREDIT_CARD,
            category = com.autologue.app.domain.model.ExpenseCategory.CAFE,
            cardOrBankName = "신한카드"
        )
        // 동일 거래가 1분 뒤 다른 원문(MMS/알림 등) 및 다른 ID로 중복 저장되어 있던 쌍둥이 레코드
        val txTwin = com.autologue.app.domain.model.Transaction(
            id = 102L,
            amount = 15000L,
            merchantName = "스타벅스판교점",
            originalText = "(결제) 15,000원 스타벅스판교점 / 신한카드",
            timestamp = LocalDateTime.of(2026, 10, 17, 12, 31, 5),
            paymentMethod = com.autologue.app.domain.model.PaymentMethod.CREDIT_CARD,
            category = com.autologue.app.domain.model.ExpenseCategory.CAFE,
            cardOrBankName = "신한카드"
        )

        prefs.markTransactionDeleted(txPrimary)

        assertTrue(prefs.isTransactionDeleted(txPrimary))
        assertTrue(prefs.isTransactionDeleted(txTwin))
    }
}


