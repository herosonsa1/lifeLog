package com.autologue.app.data.parser

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
        assertEquals("하나0*2*", tx.cardOrBankName)
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
}
