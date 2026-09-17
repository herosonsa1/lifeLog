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

        // 3. 은행 계좌 이체/출금 전용 정밀 파싱 (NH농협, KB국민, 신한, 우리, 하나 등)
        // 예: "09/14 00:23 312-****-9414-21 이민희 22,000원 출금 잔액383,252원"
        //     "[NH스마트알림] 22,000원 출금 09/14 00:23 312-****-9414-21 이민희 잔액383,252원"
        //     "09/14 00:23 312-****-9414-21 이민희 잔액383,252원 22,000원"
        val isBankTransferNotice = combined.contains("출금") || combined.contains("송금") ||
                combined.contains("이체") || combined.contains("잔액") ||
                Regex("""\d{3,}[-\d*]{5,}""").containsMatchIn(combined)
        if (isBankTransferNotice) {
            val transferTx = parseBankTransferNotification(packageName, combined)
            if (transferTx != null) {
                return ParsedNotificationResult.TxResult(transferTx)
            }
        }

        // 3-2. 일반 은행/카드사 공식 앱 결제 알림
        for (pattern in BANK_APP_PATTERNS) {
            val bankMatcher = pattern.matcher(combined)
            if (bankMatcher.find()) {
                val amount = bankMatcher.group("amount")?.replace(",", "")?.toLongOrNull() ?: 0L
                if (amount <= 0) continue
                val bank = runCatching { bankMatcher.group("bank")?.trim() }.getOrNull() ?: "은행 앱 알림"
                var merchant = runCatching { bankMatcher.group("merchant")?.trim() }.getOrNull() ?: "가맹점"
                merchant = merchant.replace("승인", "").replace("완료", "").replace("출금", "").trim()
                val parsedDateTime = extractDateTime(combined)
                val category = RuleMatcherEngine.classifyMerchant(merchant)

                return ParsedNotificationResult.TxResult(
                    Transaction(
                        amount = amount,
                        merchantName = merchant,
                        originalText = combined,
                        timestamp = parsedDateTime,
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

    private fun extractDateTime(text: String, fallback: LocalDateTime = LocalDateTime.now()): LocalDateTime {
        val datePattern = Pattern.compile("""(?<month>\d{1,2})/(?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2})""")
        val m = datePattern.matcher(text)
        if (m.find()) {
            val month = m.group("month")?.toIntOrNull() ?: fallback.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallback.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: fallback.hour
            val minute = m.group("minute")?.toIntOrNull() ?: fallback.minute
            return runCatching {
                LocalDateTime.of(fallback.year, month, day, hour, minute)
            }.getOrDefault(fallback)
        }
        return fallback
    }

    private fun parseBankTransferNotification(packageName: String, combined: String): Transaction? {
        // 잔액 정보 사전 분리 (거래 금액과 잔액 혼동 원천 차단)
        val balanceMatch = Regex("""잔액\s*(?<balance>[\d,]+)원?""").find(combined)
        val balanceStr = balanceMatch?.groups?.get("balance")?.value
        val textWithoutBalance = if (balanceMatch != null) combined.replace(balanceMatch.value, "") else combined

        // 1. 거래 금액 추출
        val amtMatch = Regex("""(?:출금|송금|이체)?\s*(?<amt>[\d,]+)원\s*(?:출금|송금|이체)?""").find(textWithoutBalance)
            ?: Regex("""\b(?<amt>[\d,]+)원\b""").find(textWithoutBalance)
        val amount = amtMatch?.groups?.get("amt")?.value?.replace(",", "")?.toLongOrNull() ?: return null
        if (amount <= 0) return null

        // 2. 날짜/시간 추출
        val timestamp = extractDateTime(combined)

        // 3. 은행명 추출
        val bankBracketMatch = Regex("""\[(?<bank>[^\]]+)\]""").find(combined)
        val bankCandidate = bankBracketMatch?.groups?.get("bank")?.value?.trim()
        val bankName = when {
            bankCandidate != null && (bankCandidate.contains("은행") || bankCandidate.contains("알림") || bankCandidate.contains("NH") || bankCandidate.contains("KB")) -> {
                if (bankCandidate.contains("NH") || bankCandidate.contains("농협")) "NH농협"
                else if (bankCandidate.contains("KB") || bankCandidate.contains("국민")) "KB국민"
                else bankCandidate
            }
            combined.contains("농협") || combined.contains("NH") || packageName.contains("nh.") || combined.contains("312-") -> "NH농협"
            combined.contains("국민") || combined.contains("KB") || packageName.contains("kbstar") || combined.contains("0749") -> "KB국민"
            combined.contains("신한") || packageName.contains("shinhan") -> "신한은행"
            combined.contains("우리") || packageName.contains("wooribank") -> "우리은행"
            combined.contains("하나") || packageName.contains("kebhana") -> "하나은행"
            combined.contains("카카오뱅크") || packageName.contains("kakaobank") -> "카카오뱅크"
            combined.contains("토스뱅크") -> "토스뱅크"
            else -> bankCandidate ?: "은행 이체"
        }

        // 4. 계좌번호 추출
        val accMatch = Regex("""(?<acc>\d{3,}[-\d*]{5,})""").find(combined)
        val accNo = accMatch?.groups?.get("acc")?.value

        // 5. 수취인/메모 추출: 계좌번호, 날짜, 금액, 잔액 등을 제외하고 남은 유효 텍스트에서 추출
        var remain = combined
        if (bankBracketMatch != null) remain = remain.replace(bankBracketMatch.value, "")
        if (balanceMatch != null) remain = remain.replace(balanceMatch.value, "")
        remain = remain.replace(amtMatch.value, "")
        remain = remain.replace(Regex("""\d{1,2}/\d{1,2}\s*\d{1,2}:\d{1,2}"""), "")
        if (accNo != null) remain = remain.replace(accNo, "")
        remain = remain.replace(Regex("""(?:출금|송금|이체|결제|승인|완료|잔액|원)\b"""), "")
        remain = remain.replace("출금", "").replace("송금", "").replace("이체", "")
        remain = remain.trim()

        val nameCandidateMatch = Regex("""([가-힣a-zA-Z\s]{2,10})""").find(remain)
        val receiverName = nameCandidateMatch?.value?.trim()?.takeIf { it.isNotBlank() && !it.contains("은행") && !it.contains("알림") }

        // [핵심] 계좌번호는 절대 merchantName(제목)에 노출하지 않음
        val merchant = if (!receiverName.isNullOrBlank()) {
            receiverName
        } else {
            "출금 내역"
        }

        val memoParts = mutableListOf<String>()
        if (!accNo.isNullOrBlank()) memoParts.add("계좌: $accNo")
        if (!balanceStr.isNullOrBlank()) memoParts.add("잔액 ${balanceStr}원")
        val transferMemo = if (memoParts.isNotEmpty()) memoParts.joinToString(" · ") else "이체/출금"

        return Transaction(
            amount = amount,
            merchantName = merchant,
            originalText = combined,
            timestamp = timestamp,
            paymentMethod = PaymentMethod.BANK_TRANSFER,
            category = ExpenseCategory.TRANSFER,
            cardOrBankName = bankName,
            transferMemo = transferMemo,
            isAutoCategorized = true
        )
    }
}
