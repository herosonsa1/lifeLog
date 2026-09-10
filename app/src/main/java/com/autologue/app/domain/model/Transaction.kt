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
    return merchantName.contains(target) ||
           (transferMemo?.contains(target) == true) ||
           originalText.contains(target)
}

