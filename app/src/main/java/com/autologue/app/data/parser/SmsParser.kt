package com.autologue.app.data.parser

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import java.time.LocalDateTime
import java.util.regex.Pattern

object SmsParser {

    fun parse(sender: String?, body: String, fallbackDateTime: LocalDateTime = LocalDateTime.now()): Transaction? {
        val cleanBody = body.trim().replace("\r\n", "\n")
        val year = fallbackDateTime.year

        // 1. 네이버플러스 멤버십 / 디지털 구독 결제 (예: [네이버 멤버십] 넷플릭스 스탠다드 6,500원 결제)
        val naverResult = parseNaverMembership(cleanBody, fallbackDateTime)
        if (naverResult != null) return naverResult

        // 2. 하나카드 RCS / 알림톡 템플릿 (예: 승인 \n 금액 1,990원 \n 카드 하나0*2* \n 사용처 배민클럽_우아한형제 ...)
        val hanaResult = parseHanaCard(cleanBody, year, fallbackDateTime)
        if (hanaResult != null) return hanaResult

        // 3. NH농협카드 승인 (예: NH카드2*7*승인 \n 신*선 \n 2,800원 일시불 \n 08/31 16:39 \n 아이스크림살래(힐스점))
        val nhCardResult = parseNhCard(cleanBody, year, fallbackDateTime)
        if (nhCardResult != null) return nhCardResult

        // 4. 삼성카드 승인 (예: 삼성1844승인 정*우 \n 119,000원 일시불 \n 08/10 09:11 (주)천재교과서)
        val samsungResult = parseSamsungCard(cleanBody, year, fallbackDateTime)
        if (samsungResult != null) return samsungResult

        // 5. KB국민카드 승인 (예: KB국민카드4024 승인 \n 정*우 \n 58,390원 08/10 \n LGUPLUS 통신요금자)
        val kbCardResult = parseKbCard(cleanBody, year, fallbackDateTime)
        if (kbCardResult != null) return kbCardResult

        // 6. KB국민은행 계좌 입출금 알림 (예: [KB]08/24 11:09 \n 475801**231 \n 정선우 \n 입금 \n 700,000 \n 잔액1,047,518)
        val kbBankResult = parseKbBank(cleanBody, year, fallbackDateTime)
        if (kbBankResult != null) return kbBankResult

        // 7. NH농협 계좌 입출금 알림 (예: 농협 출금20,000원 \n 08/29 02:33 312-****-9414-21 \n 카카오페이 잔액638,728원)
        val nhBankResult = parseNhBank(cleanBody, year, fallbackDateTime)
        if (nhBankResult != null) return nhBankResult

        // 8. 신한, 현대, 롯데, 우리, BC 등 표준 카드 승인 문자
        val standardCardResult = parseStandardCard(cleanBody, sender, year, fallbackDateTime)
        if (standardCardResult != null) return standardCardResult

        // 9. 카카오페이 / 토스 간편결제 문자
        val payResult = parseSimplePay(cleanBody, fallbackDateTime)
        if (payResult != null) return payResult

        // 10. 범용 금액 및 가맹점 추출 Fallback
        return parseFallback(cleanBody, sender, fallbackDateTime)
    }

    private fun parseNaverMembership(body: String, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile("""\[네이버\s*멤버십\]\s*(?<merchant>.+?)\s+(?<amount>[\d,]+)원\s*결제""")
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val merchant = m.group("merchant")?.trim() ?: "네이버 멤버십"
            val category = RuleMatcherEngine.classifyMerchant(merchant)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = fallbackDateTime,
                paymentMethod = PaymentMethod.NAVER_PAY,
                category = category,
                cardOrBankName = "네이버 멤버십",
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseHanaCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        if (body.contains("하나") || body.contains("1800-1111") || (body.contains("금액") && body.contains("사용처") && body.contains("거래시간"))) {
            val amtPattern = Pattern.compile("""금액\s*(?<amount>[\d,]+)원?""")
            val merPattern = Pattern.compile("""사용처\s*(?<merchant>[^\n\r]+)""")
            val cardPattern = Pattern.compile("""카드\s*(?<card>[^\n\r]+)""")
            val timePattern = Pattern.compile("""거래시간\s*(?<month>\d{1,2})/(?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2})""")

            val amtMatcher = amtPattern.matcher(body)
            val merMatcher = merPattern.matcher(body)

            if (amtMatcher.find() && merMatcher.find()) {
                val amount = amtMatcher.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
                val merchant = merMatcher.group("merchant")?.trim() ?: "하나카드 가맹점"
                
                val cardMatcher = cardPattern.matcher(body)
                val cardName = if (cardMatcher.find()) cardMatcher.group("card")?.trim() ?: "하나카드" else "하나카드"

                var timestamp = fallbackDateTime
                val timeMatcher = timePattern.matcher(body)
                if (timeMatcher.find()) {
                    val mo = timeMatcher.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
                    val da = timeMatcher.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
                    val ho = timeMatcher.group("hour")?.toIntOrNull() ?: 0
                    val mi = timeMatcher.group("minute")?.toIntOrNull() ?: 0
                    timestamp = runCatching { LocalDateTime.of(year, mo, da, ho, mi) }.getOrDefault(fallbackDateTime)
                }

                val category = RuleMatcherEngine.classifyMerchant(merchant)
                return Transaction(
                    amount = amount,
                    merchantName = merchant,
                    originalText = body,
                    timestamp = timestamp,
                    paymentMethod = PaymentMethod.CREDIT_CARD,
                    category = category,
                    cardOrBankName = cardName,
                    isAutoCategorized = true
                )
            }
        }
        return null
    }

    private fun parseNhCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile(
            """NH카드(?<cardNo>[^\s\n\r]+)?\s*승인.*?\n(?:[^\n\r]+\n)?(?<amount>[\d,]+)원\s*(?:일시불|\d+개월)?.*?\n(?<month>\d{1,2})/(?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2})\s*\n(?<merchant>[^\n\r]+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val cardNo = m.group("cardNo")
            val cardName = if (!cardNo.isNullOrBlank()) "NH농협카드($cardNo)" else "NH농협카드"

            val month = m.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: 0
            val minute = m.group("minute")?.toIntOrNull() ?: 0

            val timestamp = runCatching {
                LocalDateTime.of(year, month, day, hour, minute)
            }.getOrDefault(fallbackDateTime)

            var merchant = m.group("merchant")?.trim() ?: "농협카드 가맹점"
            merchant = merchant.replace(Regex("""\s*(?:총누적|누적)[\d,]+.*$"""), "").trim()

            val category = RuleMatcherEngine.classifyMerchant(merchant)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = cardName,
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseSamsungCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile(
            """삼성(?<cardNo>\d+)?\s*승인.*?\n(?<amount>[\d,]+)원\s*(?:일시불|\d+개월)?.*?\n(?<month>\d{1,2})/(?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2})\s*(?<merchant>.+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val cardNo = m.group("cardNo")
            val cardName = if (!cardNo.isNullOrBlank()) "삼성카드($cardNo)" else "삼성카드"

            val month = m.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: 0
            val minute = m.group("minute")?.toIntOrNull() ?: 0

            val timestamp = runCatching {
                LocalDateTime.of(year, month, day, hour, minute)
            }.getOrDefault(fallbackDateTime)

            var merchant = m.group("merchant")?.trim() ?: "삼성카드 가맹점"
            merchant = merchant.replace("일시불", "").replace("승인", "").trim()

            val category = RuleMatcherEngine.classifyMerchant(merchant)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = cardName,
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseKbCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile(
            """KB국민카드(?<cardNo>\d+)?\s*승인.*?\n(?:[^\n\r]+\n)?(?<amount>[\d,]+)원(?:\s*(?<month>\d{1,2})/(?<day>\d{1,2}))?.*?\n(?<merchant>.+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val cardNo = m.group("cardNo")
            val cardName = if (!cardNo.isNullOrBlank()) "KB국민카드($cardNo)" else "KB국민카드"

            var timestamp = fallbackDateTime
            val monthStr = m.group("month")
            val dayStr = m.group("day")
            if (!monthStr.isNullOrBlank() && !dayStr.isNullOrBlank()) {
                val mo = monthStr.toIntOrNull() ?: fallbackDateTime.monthValue
                val da = dayStr.toIntOrNull() ?: fallbackDateTime.dayOfMonth
                timestamp = runCatching {
                    LocalDateTime.of(year, mo, da, fallbackDateTime.hour, fallbackDateTime.minute)
                }.getOrDefault(fallbackDateTime)
            }

            var merchant = m.group("merchant")?.trim() ?: "KB국민카드 가맹점"
            merchant = merchant.replace("일시불", "").replace("승인", "").trim()

            val category = RuleMatcherEngine.classifyMerchant(merchant)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = cardName,
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseKbBank(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile(
            """\[KB\](?<month>\d{1,2})/(?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2}).*?\n(?<acc>[^\n\r]+)\n(?<memo>[^\n\r]+)\n(?<type>입금|출금)\n(?<amount>[\d,]+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val txType = m.group("type") ?: "출금"
            val memo = m.group("memo")?.trim() ?: "KB국민은행"

            val month = m.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: 0
            val minute = m.group("minute")?.toIntOrNull() ?: 0

            val timestamp = runCatching {
                LocalDateTime.of(year, month, day, hour, minute)
            }.getOrDefault(fallbackDateTime)

            val isIncome = (txType == "입금")
            val category = if (isIncome) ExpenseCategory.INCOME else ExpenseCategory.TRANSFER

            return Transaction(
                amount = amount,
                merchantName = "$memo ($txType)",
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.BANK_TRANSFER,
                category = category,
                cardOrBankName = "KB국민은행",
                transferMemo = "$txType 내역 (적요: $memo)",
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseNhBank(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile(
            """농협\s*(?<type>출금|입금)(?<amount>[\d,]+)원\s*\n(?<month>\d{1,2})/(?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2}).*?\n(?<memo>[^\n\r]+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val txType = m.group("type") ?: "출금"

            val month = m.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: 0
            val minute = m.group("minute")?.toIntOrNull() ?: 0

            val timestamp = runCatching {
                LocalDateTime.of(year, month, day, hour, minute)
            }.getOrDefault(fallbackDateTime)

            val rawMemo = m.group("memo")?.trim() ?: "농협계좌"
            val cleanMemo = rawMemo.replace(Regex("""\s*잔액[\d,]+.*$"""), "").trim()

            val isIncome = (txType == "입금")
            val category = if (isIncome) ExpenseCategory.INCOME else ExpenseCategory.TRANSFER

            return Transaction(
                amount = amount,
                merchantName = "$cleanMemo ($txType)",
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.BANK_TRANSFER,
                category = category,
                cardOrBankName = "NH농협",
                transferMemo = "$txType 내역 (적요: $cleanMemo)",
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseStandardCard(body: String, sender: String?, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile(
            """(?:\[Web발신\])?\s*(?:\[(?<cardHeader>[^\]]+)\])?\s*(?<cardName>[가-힣A-Za-z0-9]+(?:카드|체크|신용|페이|은행)?)(?:\s*(?<cardNo>\d{4}|\d+\*+))?\s*(?:승인)?\s*(?:[가-힣\*]+\s+)?(?<amount>[\d,]+)원(?:\s*(?:일시불|\d+개월))?(?:\s*(?<month>\d{1,2})/(?<day>\d{1,2}))?(?:\s*(?<hour>\d{1,2}):(?<minute>\d{1,2}))?\s*(?<merchant>.+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val rawCardName = m.group("cardHeader") ?: m.group("cardName") ?: sender ?: "신용카드"
            val cardNo = m.group("cardNo")
            val fullCardName = if (!cardNo.isNullOrBlank()) "$rawCardName($cardNo)" else rawCardName

            val monthStr = m.group("month")
            val dayStr = m.group("day")
            val hourStr = m.group("hour")
            val minuteStr = m.group("minute")

            val timestamp = if (!monthStr.isNullOrBlank() && !dayStr.isNullOrBlank()) {
                val mo = monthStr.toIntOrNull() ?: fallbackDateTime.monthValue
                val da = dayStr.toIntOrNull() ?: fallbackDateTime.dayOfMonth
                val ho = hourStr?.toIntOrNull() ?: fallbackDateTime.hour
                val mi = minuteStr?.toIntOrNull() ?: fallbackDateTime.minute
                runCatching { LocalDateTime.of(year, mo, da, ho, mi) }.getOrDefault(fallbackDateTime)
            } else {
                fallbackDateTime
            }

            var merchant = m.group("merchant")?.trim() ?: "카드 가맹점"
            merchant = merchant.replace("일시불", "").replace("승인", "").trim()

            val category = RuleMatcherEngine.classifyMerchant(merchant)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = if (rawCardName.contains("체크")) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = fullCardName,
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseSimplePay(body: String, fallbackDateTime: LocalDateTime): Transaction? {
        if (body.contains("카카오페이") || body.contains("토스") || body.contains("네이버페이")) {
            val amtPattern = Pattern.compile("""(?<amount>[\d,]+)원""")
            val m = amtPattern.matcher(body)
            if (m.find()) {
                val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
                val method = when {
                    body.contains("카카오페이") -> PaymentMethod.KAKAO_PAY
                    body.contains("토스") -> PaymentMethod.TOSS_PAY
                    else -> PaymentMethod.NAVER_PAY
                }
                val merchant = extractMerchantFromPay(body)
                val category = RuleMatcherEngine.classifyMerchant(merchant)
                return Transaction(
                    amount = amount,
                    merchantName = merchant,
                    originalText = body,
                    timestamp = fallbackDateTime,
                    paymentMethod = method,
                    category = category,
                    cardOrBankName = method.name,
                    isAutoCategorized = true
                )
            }
        }
        return null
    }

    private fun extractMerchantFromPay(body: String): String {
        val lines = body.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
        for (line in lines) {
            if (!line.contains("원") && !line.contains("결제") && !line.contains("승인") && !line.contains("Web발신") && !line.contains("알림")) {
                return line
            }
        }
        return "간편결제 가맹점"
    }

    private fun parseFallback(body: String, sender: String?, fallbackDateTime: LocalDateTime): Transaction? {
        val amtPattern = Pattern.compile("""(?<amount>[\d,]+)원""")
        val m = amtPattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val lines = body.split("\n").map { it.trim() }.filter { it.isNotEmpty() }
            val merchant = lines.lastOrNull() ?: "기타 가맹점"
            val category = RuleMatcherEngine.classifyMerchant(merchant)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = fallbackDateTime,
                paymentMethod = PaymentMethod.UNKNOWN,
                category = category,
                cardOrBankName = sender ?: "기타",
                isAutoCategorized = true
            )
        }
        return null
    }
}
