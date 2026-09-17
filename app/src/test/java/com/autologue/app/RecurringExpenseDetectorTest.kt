package com.autologue.app

import com.autologue.app.data.preferences.UserAccount
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.usecase.expense.RecurringExpenseDetector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class RecurringExpenseDetectorTest {

    private fun createTx(
        id: Long,
        amount: Long,
        merchantName: String,
        year: Int,
        month: Int,
        day: Int,
        category: ExpenseCategory = ExpenseCategory.LIVING,
        cardOrBankName: String = "신한카드"
    ): Transaction {
        return Transaction(
            id = id,
            amount = amount,
            merchantName = merchantName,
            originalText = "$merchantName %,d원".format(amount),
            timestamp = LocalDateTime.of(year, month, day, 10, 0),
            paymentMethod = PaymentMethod.CREDIT_CARD,
            category = category,
            cardOrBankName = cardOrBankName,
            isAutoCategorized = true
        )
    }

    @Test
    fun detectRecurring_exactSameDayAndAmount_markedAsRecurring() {
        // 지난달 8월 15일, 이번달 9월 15일 동일 금액 119,000원 (천재교과서)
        val tx1 = createTx(1L, 119000L, "(주)천재교과서", 2026, 8, 15)
        val tx2 = createTx(2L, 119000L, "천재교과서", 2026, 9, 15)
        val tx3 = createTx(3L, 45000L, "스타벅스", 2026, 9, 16, ExpenseCategory.CAFE)

        val recurringIds = RecurringExpenseDetector.detectRecurringTransactionIds(listOf(tx1, tx2, tx3))

        assertTrue(recurringIds.contains(1L))
        assertTrue(recurringIds.contains(2L))
        assertFalse(recurringIds.contains(3L))
    }

    @Test
    fun detectRecurring_weekendHolidayShift_markedAsRecurring() {
        // 8월 10일(일요일) -> 결제일이 8월 11일(월요일)로 이연
        // 9월 10일(수요일) 정상 결제일
        val txAug = createTx(10L, 55000L, "KT통신요금", 2026, 8, 11, ExpenseCategory.LIVING)
        val txSep = createTx(20L, 55000L, "KT통신요금", 2026, 9, 10, ExpenseCategory.LIVING)

        val recurringIds = RecurringExpenseDetector.detectRecurringTransactionIds(listOf(txAug, txSep))

        assertTrue(recurringIds.contains(10L))
        assertTrue(recurringIds.contains(20L))
    }

    @Test
    fun detectRecurring_differentAmount_notRecurring() {
        // 날짜는 같으나 금액이 다른 일반 소비
        val tx1 = createTx(100L, 50000L, "일반식당", 2026, 8, 15, ExpenseCategory.FOOD)
        val tx2 = createTx(101L, 65000L, "일반식당", 2026, 9, 15, ExpenseCategory.FOOD)

        val recurringIds = RecurringExpenseDetector.detectRecurringTransactionIds(listOf(tx1, tx2))

        assertFalse(recurringIds.contains(100L))
        assertFalse(recurringIds.contains(101L))
    }

    @Test
    fun userAccount_badgeLabel_formatsCorrectly() {
        val accWithAlias = UserAccount(
            bankName = "NH농협",
            accountNumberPattern = "312-****-9414-21",
            alias = "생활비 통장"
        )
        assertEquals("NH농협 · 생활비 통장", accWithAlias.badgeLabel)

        val accWithoutAlias = UserAccount(
            bankName = "KB국민",
            accountNumberPattern = "07491612193855",
            alias = ""
        )
        assertEquals("KB국민", accWithoutAlias.badgeLabel)
    }
}
