package com.autologue.app.domain.model

import java.time.LocalDateTime

enum class PaymentMethod {
    CREDIT_CARD, CHECK_CARD, KAKAO_PAY, TOSS_PAY, NAVER_PAY, BANK_TRANSFER, UNKNOWN
}

enum class ExpenseCategory(val displayName: String, val iconRes: String) {
    FOOD("식비", "restaurant"),
    CAFE("카페/간식", "coffee"),
    FUEL("주유/충전", "local_gas_station"),
    CAR_MAINTENANCE("차량정비/세차", "build"),
    GOLF_FIELD("필드 라운드", "sports_golf"),
    GOLF_SCREEN("스크린 골프", "videogame_asset"),
    GOLF_EQUIPMENT("골프 용품", "shopping_bag"),
    SHOPPING("쇼핑", "shopping_cart"),
    TRANSPORT("교통", "directions_transit"),
    LIVING("생활/마트", "store"),
    MEDICAL("병원/약제", "local_hospital"),
    INCOME("수입/입금", "arrow_downward"),
    TRANSFER("이체/출금", "sync_alt"),
    ETC("기타", "more_horiz")
}

data class Transaction(
    val id: Long = 0,
    val amount: Long,
    val merchantName: String,
    val originalText: String,
    val timestamp: LocalDateTime,
    val paymentMethod: PaymentMethod,
    val category: ExpenseCategory,
    val cardOrBankName: String,
    val transferMemo: String? = null,
    val isAutoCategorized: Boolean = false,
    val ruleIdApplied: Long? = null
)

/**
 * 적요/가맹점/원문에 사용자 이름("정선우")이 포함된 본인 계좌 간 이동 내역 여부 판별
 */
fun Transaction.isSelfTransfer(): Boolean {
    val target = "정선우"
    // 신용카드/체크카드 결제 승인 문자에 포함된 회원명("정선우")은 계좌간 이동이 아닌 정상 카드 결제임
    if (paymentMethod == PaymentMethod.CREDIT_CARD || paymentMethod == PaymentMethod.CHECK_CARD) {
        return false
    }
    return merchantName.contains(target) || (transferMemo?.contains(target) == true)
}

/**
 * 해당 내역이 '입금(수입)'인지 '출금(지출/이체/결제)'인지 판별합니다.
 * - true: 입금 (입금 문자/알림 또는 수입/입금 카테고리)
 * - false: 출금 (출금/이체/송금 문자, 카드 승인/결제, 일반 지출)
 */
fun Transaction.isDepositTransaction(): Boolean {
    return isDepositDirection(
        category = category,
        originalText = originalText,
        transferMemo = transferMemo,
        merchantName = merchantName
    )
}

fun isDepositDirection(
    category: ExpenseCategory? = null,
    originalText: String? = null,
    transferMemo: String? = null,
    merchantName: String? = null
): Boolean {
    val orig = originalText.orEmpty()
    val memo = transferMemo.orEmpty()
    val mer = merchantName.orEmpty()

    val hasExplicitWithdraw = orig.contains("출금") || orig.contains("승인") || orig.contains("결제") ||
        memo.startsWith("출금") || mer.endsWith("(출금)") || mer == "출금 내역"
    val hasExplicitDeposit = orig.contains("입금") || memo.startsWith("입금") ||
        mer.endsWith("(입금)") || mer == "입금 내역"

    if (hasExplicitDeposit && !hasExplicitWithdraw) return true
    if (hasExplicitWithdraw && !hasExplicitDeposit) return false

    return category == ExpenseCategory.INCOME
}

/**
 * 해당 내역이 카드사 결제가 아닌 '은행 계좌 출금/이체/입금' 내역인지 판별합니다.
 * 출금/이체/입금 내역인 경우 카드 선택 UI를 숨기고 은행 선택 UI를 표시합니다.
 */
fun Transaction.isBankAccountTransaction(overrideCategory: ExpenseCategory? = null): Boolean {
    val effectiveCat = overrideCategory ?: category
    if (effectiveCat == ExpenseCategory.TRANSFER || effectiveCat == ExpenseCategory.INCOME) return true
    if (isDepositTransaction()) return true
    if (paymentMethod == PaymentMethod.BANK_TRANSFER) return true

    val orig = originalText
    val memo = transferMemo.orEmpty()
    val hasCardIndicator = orig.contains("카드") || orig.contains("신용(") || orig.contains("체크(") ||
        orig.contains("일시불") || orig.contains("할부") || orig.contains("사용처") ||
        orig.contains("(결제)") || orig.contains("누적이용") || cardOrBankName.contains("카드") ||
        cardOrBankName.contains("페이")
    if (hasCardIndicator) return false

    return orig.contains("출금") || orig.contains("입금") || orig.contains("잔액") ||
        memo.contains("계좌:") || memo.contains("출금") || memo.contains("입금")
}



