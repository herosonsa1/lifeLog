package com.autologue.app

import com.autologue.app.data.parser.NotificationParser
import com.autologue.app.domain.model.ExpenseCategory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationParserTest {

    @Test
    fun parseKakaoPaySend_extractsCorrectTx() {
        val title = "카카오페이"
        val text = "이몽룡님에게 30,000원을 보냈어요 [골프비 정산]"
        val result = NotificationParser.parse("com.kakao.talk", title, text)

        assertNotNull(result)
        assertTrue(result is NotificationParser.ParsedNotificationResult.TxResult)
        val tx = (result as NotificationParser.ParsedNotificationResult.TxResult).transaction
        assertEquals(30000L, tx.amount)
        assertEquals("이몽룡", tx.merchantName)
        assertEquals("골프비 정산", tx.transferMemo)
    }

    @Test
    fun parseTmapDistance_extractsCorrectDrivingKm() {
        val title = "TMAP"
        val text = "주행거리 42.5 km 운행 완료"
        val result = NotificationParser.parse("com.skt.tmap.ku", title, text)

        assertNotNull(result)
        assertTrue(result is NotificationParser.ParsedNotificationResult.TmapResult)
        val tmap = result as NotificationParser.ParsedNotificationResult.TmapResult
        assertEquals(42.5, tmap.distanceKm, 0.01)
    }

    @Test
    fun parseBankTransferNotification_extractsCorrectDetails() {
        val packageName = "nh.smart.banking"
        val title = "[NH스마트알림]"
        val text = "09/14 00:23 312-****-9414-21 이민희 22,000원 출금 잔액383,252원"
        val result = NotificationParser.parse(packageName, title, text)

        assertNotNull(result)
        assertTrue(result is NotificationParser.ParsedNotificationResult.TxResult)
        val tx = (result as NotificationParser.ParsedNotificationResult.TxResult).transaction
        assertEquals(22000L, tx.amount)
        assertEquals("이민희", tx.merchantName)
        assertEquals(ExpenseCategory.TRANSFER, tx.category)
        assertEquals(9, tx.timestamp.monthValue)
        assertEquals(14, tx.timestamp.dayOfMonth)
        assertEquals(0, tx.timestamp.hour)
        assertEquals(23, tx.timestamp.minute)
        assertNotNull(tx.transferMemo)
        assertTrue(tx.transferMemo!!.contains("383,252"))
        assertTrue(tx.transferMemo!!.contains("312-****-9414-21"))
    }
}
