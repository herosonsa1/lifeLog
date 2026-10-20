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

    @Test
    fun detectRecurring_monthlyVaryingTitleSuffix_markedAsRecurring() {
        // 예별손080154 -> 예별손090154 (94,250원), 동양08-155 -> 동양09-155 (106,120원)
        val yebyeolAug = createTx(201L, 94250L, "예별손080154", 2026, 8, 25, ExpenseCategory.TRANSFER, "KB국민은행")
        val yebyeolSep = createTx(202L, 94250L, "예별손090154", 2026, 9, 25, ExpenseCategory.TRANSFER, "KB국민은행")
        val dongyangAug = createTx(203L, 106120L, "동양08-155", 2026, 8, 25, ExpenseCategory.TRANSFER, "KB국민은행")
        val dongyangSep = createTx(204L, 106120L, "동양09-155", 2026, 9, 25, ExpenseCategory.TRANSFER, "KB국민은행")

        assertEquals("예별손", RecurringExpenseDetector.extractMerchantPrefixStem("예별손090154"))
        assertEquals("동양", RecurringExpenseDetector.extractMerchantPrefixStem("동양09-155"))

        val recurringIds = RecurringExpenseDetector.detectRecurringTransactionIds(
            listOf(yebyeolAug, yebyeolSep, dongyangAug, dongyangSep)
        )

        assertTrue(recurringIds.contains(201L))
        assertTrue(recurringIds.contains(202L))
        assertTrue(recurringIds.contains(203L))
        assertTrue(recurringIds.contains(204L))
    }

    @Test
    fun customMetaPreferences_fixedExpenseMatchPrefix_matchesMonthlyVaryingTitles() {
        val prefs = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences()

        assertEquals("예별손", com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.suggestFixedExpensePrefix("예별손090154"))
        assertEquals("동양", com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.suggestFixedExpensePrefix("동양09-155"))

        val txSep = createTx(301L, 94250L, "예별손090154", 2026, 9, 25, ExpenseCategory.TRANSFER, "KB국민은행")
        val txOct = createTx(302L, 94250L, "예별손100154", 2026, 10, 25, ExpenseCategory.TRANSFER, "KB국민은행")
        val txDongyang = createTx(303L, 106120L, "동양10-155", 2026, 10, 25, ExpenseCategory.TRANSFER, "KB국민은행")

        prefs.setFixedExpense(
            txId = txSep.id,
            merchantName = txSep.merchantName,
            isFixed = true,
            applyToMerchantRule = true,
            isDeposit = false,
            matchPrefix = "예별손"
        )
        prefs.setFixedExpense(
            txId = 0L,
            merchantName = "동양09-155",
            isFixed = true,
            applyToMerchantRule = true,
            isDeposit = false,
            matchPrefix = "동양"
        )

        assertTrue(prefs.resolveIsFixedExpense(txSep))
        assertTrue(prefs.resolveIsFixedExpense(txOct))
        assertTrue(prefs.resolveIsFixedExpense(txDongyang))
        assertEquals("예별손", prefs.resolveFixedExpenseMatchPrefix("예별손110154", isDeposit = false))
        assertEquals("동양", prefs.resolveFixedExpenseMatchPrefix("동양11-155", isDeposit = false))
    }
}
