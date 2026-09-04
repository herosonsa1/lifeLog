package com.autologue.app.data.parser

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import java.time.LocalDateTime
import java.util.regex.Pattern

object NotificationParser {

    private val KAKAO_PAY_SEND_PATTERNS = listOf(
        Pattern.compile("""(?:카카오페이|카카오톡)?.*?(?<receiver>\S+?)님에게\s*(?<amount>[\d,]+)원을\s*(?:보냈어요|송금했습니다|송금완료)(?:\s*\[(?<memo>.*?)\])?"""),
        Pattern.compile("""(?<receiver>\S+?)님께\s*(?<amount>[\d,]+)원\s*송금""")
    )

    private val KAKAO_PAY_PAY_PATTERNS = listOf(
        Pattern.compile("""(?<merchant>.+?)\s*(?:에서)?\s*(?<amount>[\d,]+)원\s*(?:결제|승인|결제완료)"""),
        Pattern.compile("""(?:결제|승인)\s*(?:완료)?\s*(?<amount>[\d,]+)원\s*(?<merchant>.+)"""),
        Pattern.compile("""\[카카오페이\]\s*(?<amount>[\d,]+)원\s*(?<merchant>.+)""")
    )

    private val TOSS_PATTERNS = listOf(
        Pattern.compile("""(?<merchant>.+?)\s*(?<amount>[\d,]+)원\s*(?:결제완료|결제|출금)"""),
        Pattern.compile("""(?<receiver>\S+?)님에게\s*(?<amount>[\d,]+)원을\s*보냈어요""")
    )

    private val BANK_APP_PATTERNS = listOf(
        Pattern.compile("""\[(?<bank>\S+?)\]\s*(?:출금|체크출금|체크카드)?\s*(?<amount>[\d,]+)원\s*(?<merchant>.+)"""),
        Pattern.compile("""(?<bank>\S+은행|\S+카드)?\s*(?:출금|결제)\s*(?<amount>[\d,]+)원\s*(?<merchant>.+)""")
    )

    private val GOLFZON_PATTERN = Pattern.compile("""\[골프존\]\s*(?<store>.+?)\s*예약\s*완료|\[골프존\]\s*(?<store2>.+?)\s*라운드\s*(?<score>\d+)타""")
    private val TMAP_DRIVING_PATTERN = Pattern.compile("""주행거리\s*(?<distance>[\d.]+)\s*km\s*운행\s*완료""")

    sealed class ParsedNotificationResult {
        data class TxResult(val transaction: Transaction) : ParsedNotificationResult()
        data class GolfzonResult(val storeName: String, val score: Int?) : ParsedNotificationResult()
        data class TmapResult(val distanceKm: Double) : ParsedNotificationResult()
    }

    fun parse(packageName: String, title: String, text: String): ParsedNotificationResult? {
        val combined = "$title $text".trim()

        // 1. 카카오톡 / 카카오페이 푸시 알림
        if (packageName.contains("kakaopay") || packageName.contains("kakao.talk") || combined.contains("카카오페이")) {
            for (pattern in KAKAO_PAY_SEND_PATTERNS) {
                val sendMatcher = pattern.matcher(combined)
                if (sendMatcher.find()) {
                    val amount = sendMatcher.group("amount")?.replace(",", "")?.toLongOrNull() ?: 0L
                    if (amount <= 0) continue
                    val receiver = sendMatcher.group("receiver")?.trim() ?: "카카오페이 송금"
                    val memo = runCatching { sendMatcher.group("memo")?.trim() }.getOrNull()
                    val category = RuleMatcherEngine.classifyMerchant("$receiver ${memo ?: ""}")

                    return ParsedNotificationResult.TxResult(
                        Transaction(
                            amount = amount,
                            merchantName = receiver,
                            originalText = combined,
                            timestamp = LocalDateTime.now(),
                            paymentMethod = PaymentMethod.KAKAO_PAY,
                            category = category,
                            cardOrBankName = "카카오페이 송금",
                            transferMemo = memo,
                            isAutoCategorized = category != ExpenseCategory.ETC
                        )
                    )
                }
            }

            for (pattern in KAKAO_PAY_PAY_PATTERNS) {
                val payMatcher = pattern.matcher(combined)
                if (payMatcher.find()) {
                    val amount = payMatcher.group("amount")?.replace(",", "")?.toLongOrNull() ?: 0L
                    if (amount <= 0) continue
                    var merchant = payMatcher.group("merchant")?.trim() ?: "카카오페이 가맹점"
                    merchant = merchant.replace("결제", "").replace("완료", "").replace("승인", "").trim()
                    if (merchant.isBlank()) merchant = "카카오페이 가맹점"
                    val category = RuleMatcherEngine.classifyMerchant(merchant)

                    return ParsedNotificationResult.TxResult(
                        Transaction(
                            amount = amount,
                            merchantName = merchant,
                            originalText = combined,
                            timestamp = LocalDateTime.now(),
                            paymentMethod = PaymentMethod.KAKAO_PAY,
                            category = category,
                            cardOrBankName = "카카오페이 결제",
                            isAutoCategorized = category != ExpenseCategory.ETC
                        )
                    )
                }
            }
        }

        // 2. 토스 푸시 알림
        if (packageName.contains("viva.republica.toss") || combined.contains("토스")) {
            for (pattern in TOSS_PATTERNS) {
                val tossMatcher = pattern.matcher(combined)
                if (tossMatcher.find()) {
                    val amount = tossMatcher.group("amount")?.replace(",", "")?.toLongOrNull() ?: 0L
                    if (amount <= 0) continue
                    val merchant = runCatching { tossMatcher.group("merchant")?.trim() }.getOrNull()
                        ?: runCatching { tossMatcher.group("receiver")?.trim() }.getOrNull()
                        ?: "토스 결제/송금"
                    val category = RuleMatcherEngine.classifyMerchant(merchant)

                    return ParsedNotificationResult.TxResult(
                        Transaction(
                            amount = amount,
                            merchantName = merchant,
                            originalText = combined,
                            timestamp = LocalDateTime.now(),
                            paymentMethod = PaymentMethod.TOSS_PAY,
                            category = category,
                            cardOrBankName = "토스페이",
                            isAutoCategorized = category != ExpenseCategory.ETC
                        )
                    )
                }
            }
        }

        // 3. 은행/카드사 공식 앱 푸시 알림
        for (pattern in BANK_APP_PATTERNS) {
            val bankMatcher = pattern.matcher(combined)
            if (bankMatcher.find()) {
                val amount = bankMatcher.group("amount")?.replace(",", "")?.toLongOrNull() ?: 0L
                if (amount <= 0) continue
                val bank = runCatching { bankMatcher.group("bank")?.trim() }.getOrNull() ?: "은행 앱 알림"
                var merchant = runCatching { bankMatcher.group("merchant")?.trim() }.getOrNull() ?: "가맹점"
                merchant = merchant.replace("승인", "").replace("완료", "").replace("출금", "").trim()
                val category = RuleMatcherEngine.classifyMerchant(merchant)

                return ParsedNotificationResult.TxResult(
                    Transaction(
                        amount = amount,
                        merchantName = merchant,
                        originalText = combined,
                        timestamp = LocalDateTime.now(),
                        paymentMethod = PaymentMethod.BANK_TRANSFER,
                        category = category,
                        cardOrBankName = bank,
                        isAutoCategorized = category != ExpenseCategory.ETC
                    )
                )
            }
        }

        // 4. 골프존 예약/스코어 푸시
        if (packageName.contains("golfzon") || combined.contains("골프존")) {
            val golfMatcher = GOLFZON_PATTERN.matcher(combined)
            if (golfMatcher.find()) {
                val store = golfMatcher.group("store") ?: golfMatcher.group("store2") ?: "골프존파크"
                val score = golfMatcher.group("score")?.toIntOrNull()
                return ParsedNotificationResult.GolfzonResult(store.trim(), score)
            }
        }

        // 5. TMAP 주행 완료 푸시
        if (packageName.contains("skt.tmap") || combined.contains("TMAP") || combined.contains("티맵")) {
            val tmapMatcher = TMAP_DRIVING_PATTERN.matcher(combined)
            if (tmapMatcher.find()) {
                val distance = tmapMatcher.group("distance")?.toDoubleOrNull() ?: 0.0
                return ParsedNotificationResult.TmapResult(distance)
            }
        }

        return null
    }
}
