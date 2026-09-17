package com.autologue.app.domain.usecase.expense

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.isSelfTransfer
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * 지난달과 비교하여 동일 금액 및 동일 날짜(휴일 대체 평일 보정)에 결제/출금된 내역을
 * 정기지출(구독료, 학원비, 통신비, 보험료, 적금, 월세 등)로 자동 판별하는 엔진
 */
object RecurringExpenseDetector {

    /**
     * 주어진 트랜잭션 목록 전체를 분석하여, 각 트랜잭션이 정기지출인지 여부를 Set<Long>(트랜잭션 ID)으로 반환합니다.
     */
    fun detectRecurringTransactionIds(allTransactions: List<Transaction>): Set<Long> {
        val recurringIds = mutableSetOf<Long>()
        val expenseTransactions = allTransactions.filter { 
            it.category != ExpenseCategory.INCOME && !it.isSelfTransfer() && it.amount > 0L 
        }

        for (tx in expenseTransactions) {
            val isRecurring = isRecurringTransaction(tx, expenseTransactions)
            if (isRecurring) {
                recurringIds.add(tx.id)
            }
        }
        return recurringIds
    }

    /**
     * 특정 트랜잭션이 직전 달(또는 직후 달)의 동일 금액/날짜 패턴과 일치하는 정기지출인지 검사
     */
    fun isRecurringTransaction(target: Transaction, allExpenses: List<Transaction>): Boolean {
        val targetDate = target.timestamp.toLocalDate()
        val targetMonth = targetDate.monthValue
        val targetYear = targetDate.year
        val targetDay = targetDate.dayOfMonth
        val targetAmount = target.amount
        val cleanTargetMerchant = cleanMerchantForComparison(target.merchantName)

        // 비교 대상: 직전 1개월 전(또는 다음 1개월 후) 범위의 거래들
        val candidates = allExpenses.filter { candidate ->
            if (candidate.id == target.id) return@filter false
            if (candidate.amount != targetAmount) return@filter false

            val cDate = candidate.timestamp.toLocalDate()
            val monthsDiff = ChronoUnit.MONTHS.between(cDate.withDayOfMonth(1), targetDate.withDayOfMonth(1))
            // 정확히 1달 차이 (지난달 또는 다음달)
            abs(monthsDiff) == 1L
        }

        for (candidate in candidates) {
            val cDate = candidate.timestamp.toLocalDate()
            val cDay = cDate.dayOfMonth

            // 1. 날짜 조건 점검 (동일 날짜 또는 휴일 이연 보정)
            val isDayMatched = isDayMatchedWithHolidayAdjustment(targetDate, cDate)
            if (!isDayMatched) continue

            // 2. 가맹점 / 적요 / 계좌 유사도 점검
            val cleanCandidateMerchant = cleanMerchantForComparison(candidate.merchantName)
            val isMerchantMatched = isMerchantSimilar(cleanTargetMerchant, cleanCandidateMerchant) ||
                    (target.cardOrBankName == candidate.cardOrBankName && target.category == candidate.category && abs(targetDay - cDay) <= 1)

            if (isMerchantMatched) {
                return true
            }
        }

        return false
    }

    /**
     * 날짜 일치 판별:
     * - 동일한 일자 (예: 10일 == 10일)
     * - 또는 휴일(주말: 토/일)이 끼어 있어 직후 평일(월요일 등)로 결제일이 1~3일 이연된 경우 허용
     */
    fun isDayMatchedWithHolidayAdjustment(date1: LocalDate, date2: LocalDate): Boolean {
        val day1 = date1.dayOfMonth
        val day2 = date2.dayOfMonth
        val dayDiff = abs(day1 - day2)

        // 1. 정확히 같은 날짜
        if (dayDiff == 0) return true

        // 2. 날짜 오차가 1일 이내인 경우 (말일 30/31일 차이, 은행 익일 처리 등 기본 허용)
        if (dayDiff <= 1) return true

        // 3. 날짜 차이가 2~3일 이내인 경우 (주말 또는 공휴일 연휴 결제 이연)
        if (dayDiff <= 3) {
            val isWeekendAdjacent = date1.dayOfWeek in listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY) ||
                    date2.dayOfWeek in listOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY, DayOfWeek.SATURDAY, DayOfWeek.SUNDAY)
            if (isWeekendAdjacent) {
                return true
            }
        }

        return false
    }

    private fun cleanMerchantForComparison(merchant: String): String {
        return merchant.replace("주식회사", "")
            .replace("(주)", "")
            .replace("(출금)", "")
            .replace("(입금)", "")
            .replace("결제", "")
            .replace("승인", "")
            .replace(" ", "")
            .trim()
    }

    private fun isMerchantSimilar(m1: String, m2: String): Boolean {
        if (m1.isBlank() || m2.isBlank()) return false
        if (m1 == m2) return true
        if (m1.contains(m2) || m2.contains(m1)) return true
        return false
    }
}
