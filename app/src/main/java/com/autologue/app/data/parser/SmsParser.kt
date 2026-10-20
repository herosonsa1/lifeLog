package com.autologue.app.data.parser

import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import java.time.LocalDateTime
import java.util.regex.Pattern

object SmsParser {

    fun parseMultiple(rawText: String, fallbackDateTime: LocalDateTime = LocalDateTime.now()): List<Transaction> {
        val clean = rawText.trim().replace("\r\n", "\n")
        if (clean.isBlank()) return emptyList()

        // 1. 공백 라인(\n\s*\n)으로 1차 분할 시도
        var chunks = clean.split(Regex("""\n\s*\n""")).map { it.trim() }.filter { it.isNotBlank() }

        // 2. 만약 공백 라인이 없거나 1개 덩어리인데 내용 내에 여러 결제 문자가 연속되어 있다면 라인 단위 스마트 분할
        if (chunks.size <= 1) {
            val lines = clean.split("\n")
            val smartChunks = mutableListOf<String>()
            val currentChunkLines = mutableListOf<String>()

            val cardStartRegex = Regex("""^(?:\[(?:일시불|할부|체크|해외|신용|\d+개월)?\.?(?:승인|취소)\]|(?:NH|농협|KB|국민|신한|삼성|현대|롯데|우리|하나|BC|비씨)(?:카드|체크|BC|법인|\([0-9*]{2,4}\)|[0-9*]{4}))""")

            for (line in lines) {
                val trimmedLine = line.trim()
                var isStart = false

                if (trimmedLine == "알림" || trimmedLine.startsWith("[Web발신]")) {
                    isStart = true
                } else if (cardStartRegex.containsMatchIn(trimmedLine)) {
                    val joinedSoFar = currentChunkLines.joinToString(" ")
                    if (joinedSoFar.contains("카드") || joinedSoFar.contains("승인") || joinedSoFar.contains("원")) {
                        isStart = true
                    }
                }

                if (isStart && currentChunkLines.isNotEmpty()) {
                    val joinedChunk = currentChunkLines.joinToString("\n").trim()
                    if (joinedChunk.contains("원") || joinedChunk.contains("승인") || joinedChunk.contains("취소")) {
                        smartChunks.add(joinedChunk)
                        currentChunkLines.clear()
                    }
                }
                currentChunkLines.add(line)
            }
            if (currentChunkLines.isNotEmpty()) {
                smartChunks.add(currentChunkLines.joinToString("\n").trim())
            }

            if (smartChunks.size > 1) {
                chunks = smartChunks
            }
        }

        // 3. 만약 여전히 1개 덩어리면 단일 파싱
        if (chunks.size <= 1) {
            val single = parse(null, clean, fallbackDateTime)
            return if (single != null) listOf(single) else emptyList()
        }

        // 4. 각 청크별 파싱 수행
        val result = mutableListOf<Transaction>()
        for (chunk in chunks) {
            val tx = parse(null, chunk, fallbackDateTime)
            if (tx != null) {
                result.add(tx)
            }
        }

        // 중복 제거 및 시간 역순 정렬
        return result.distinctBy {
            "${it.amount}_${it.timestamp}_${it.merchantName}"
        }.sortedByDescending { it.timestamp }
    }

    private val INVALID_MERCHANT_NAMES = setOf(
        "제목", "제목없음", "제목 없음", "no subject", "nosubject",
        "가맹점", "카드 가맹점", "기타 가맹점", "간편결제 가맹점", "알림", "[web발신]", "web발신",
        "금액", "결제금액", "승인금액", "이용금액", "사용금액", "합계", "총액",
        "누적이용금액", "누적금액", "누적사용금액", "누적결제금액", "총누적", "총누적금액", "총누적이용금액", "누적",
        "결제예정금액", "결제예정액", "결제예정", "청구예정금액", "청구예정액", "청구금액",
        "총사용금액", "총이용금액", "총결제금액", "총사용액", "총이용액",
        "출금예정금액", "출금예정액", "출금예정", "지급예정금액", "지급예정액", "지급예정",
        "[일시불.승인]", "[체크.승인]", "[할부.승인]", "[해외.승인]", "[승인]", "[취소]"
    )

    /**
     * 승인/결제 문자 내 부가 정보로 붙거나 단독 안내로 오는 누적/결제예정/총사용/출금예정/지급예정/한도 구문 및 금액 정규식
     */
    private val NON_USAGE_AMOUNT_CLAUSE_REGEX = Regex(
        """(?:/\s*)*(?:총누적이용금액|총누적금액|누적이용금액|누적사용금액|누적결제금액|누적금액|총누적|누적|결제예정금액|결제예정액|결제예정|청구예정금액|청구예정액|청구금액|총사용금액|총이용금액|총결제금액|총사용액|총이용액|당월이용금액|금월이용금액|이번달이용금액|출금예정금액|출금예정액|익월출금예정|출금예정|지급예정금액|지급예정액|지급예정|인출예정|납부예정|할인예정금액|할인예정|적립예정|캐시백예정|가능금액|가용한도|남은한도|잔여한도|이용한도|한도)(?:\s*안내)?[\s:：/\n\r]*[\d,]+원?"""
    )

    private val NON_USAGE_TRAIL_KEYWORD_REGEX = Regex(
        """(?:/\s*)*(?:총누적이용금액|총누적금액|누적이용금액|누적사용금액|누적결제금액|누적금액|총누적|결제예정금액|결제예정액|청구예정금액|청구예정액|총사용금액|총이용금액|총결제금액|총사용액|총이용액|출금예정금액|출금예정액|출금예정|지급예정금액|지급예정액|지급예정)(?:\s*안내)?"""
    )

    private val EXCLUDED_NAVER_PAY_TOKENS = listOf("네이버페이", "naverpay", "naver_pay", "네이버파이낸셜", "npay")

    private val INVALID_MERCHANT_SUBSTRINGS = listOf(
        "누적이용금액", "누적사용금액", "누적결제금액", "누적금액",
        "결제예정금액", "결제예정액", "총사용금액", "총이용금액",
        "청구예정금액", "이용대금명세서", "신용(,",
        "상품권을", "상품권 증정", "상품권 지급", "상품권 받", "기프티콘", "교환권",
        "무이자", "무이자할부", "부분무이자",
        "할인 혜택", "할인혜택", "할인과", "등 할인", "청구할인", "즉시할인",
        "캐시백", "페이백", "혜택", "프로모션", "이벤트",
        "누려보세요", "만나보세요", "받아보세요", "확인해보세요", "이용해보세요", "신청해보세요",
        "보세요", "있어요", "드려요", "받으세요", "받을 수", "받으실 수",
        "주유소 등", "음식점,", "링크 한 번", "링크 한번", "링크로",
        "수신거부", "무료거부", "준법감시", "심의필", "유의사항",
        "리볼빙", "일부결제금액이월약정", "카드론", "현금서비스", "http://", "https://"
    )

    private val SPAM_KEYWORDS = listOf(
        "[광고]", "(광고)", "무료수신거부", "수신거부", "무료거부", "인증번호", "본인확인",
        "비밀번호", "로그인", "택배", "배송완료", "배송출발", "입고안내",
        "쿠폰 지급", "쿠폰 안내", "포인트 충전 완료", "포인트 적립 안내", "이벤트 안내",
        "상품권을 받을 수", "상품권 증정", "상품권 지급", "기프티콘 증정",
        "무이자할부", "무이자 할부", "부분무이자",
        "누려보세요", "만나보세요", "받아보세요", "확인해보세요", "이용해보세요", "신청해보세요", "참여해보세요", "챙겨보세요",
        "링크 한 번으로", "링크 한번으로", "주유소 등 할인", "음식점, 주유소",
        "혜택 안내", "혜택안내", "할인 혜택", "할인혜택", "적립 혜택", "적립혜택", "캐시백 혜택",
        "준법감시", "심의필", "일부결제금액이월약정", "금리인하요구권"
    )

    private val PROMO_INSTALLMENT_RANGE_REGEX = Regex("""\d+\s*[~～-]\s*\d+\s*개월""")
    private val PROMO_AMOUNT_CONTEXT_REGEX = Regex(
        """[\d,]+원\s*(?:상품권|쿠폰|기프티콘|교환권|캐시백|페이백|할인|청구할인|즉시할인|적립|혜택|증정|경품|리워드|드려요|받으세요|받을\s*수|이상\s*결제|이상\s*이용|한도|무이자)|(?:최대|연간|월간|매월)\s*[\d,]+원\s*(?:할인|혜택|캐시백|적립|상품권|쿠폰)"""
    )
    private val EXPLICIT_APPROVAL_DATE_REGEX = Regex(
        """(?<![0-9~～-])(?:20\d{2}[./-])?(?:1[0-2]|0?[1-9])[./-](?:3[01]|[12]\d|0?[1-9])(?:\s+\d{1,2}:\d{2}|(?![0-9~～-]|개월))|(?:1[0-2]|0?[1-9])월\s*(?:3[01]|[12]\d|0?[1-9])일"""
    )

    private val STOCK_OR_NEWS_KEYWORDS = listOf(
        "목표주가", "투자의견", "매수", "매도", "체결", "공모주", "청약",
        "배당", "시황", "코스피", "코스닥", "증시", "상한가", "하한가",
        "시가총액", "현재가", "장전", "브리핑", "리포트", "애널리스트", "컨센서스",
        "주주", "보유수량", "예수금", "수익률", "손익"
    )

    private val NON_USAGE_NOTICE_KEYWORDS = listOf(
        "결제예정금액", "결제예정액", "결제예정일", "이번달 결제예정", "이번 달 결제예정",
        "청구예정금액", "청구예정액", "청구금액", "청구액", "이용대금명세서", "이용대금 안내", "이용대금안내",
        "명세서 안내", "명세서안내", "결제일 안내", "결제일안내", "결제금액 안내", "결제금액안내",
        "총사용금액", "총이용금액", "총결제금액", "총사용액", "총이용액",
        "누적이용금액 안내", "누적금액 안내", "누적사용금액 안내", "이번달 누적", "이번 달 누적",
        "출금예정 안내", "출금예정안내", "출금예정금액 안내", "인출예정 안내", "납부예정 안내", "결제대금 출금", "자동납부 안내", "자동납부안내",
        "연체 안내", "리볼빙 안내", "한도 안내", "한도안내", "한도 상향", "한도상향",
        "유효기간 만료", "재발급 안내", "연회비 안내"
    )

    private val SUMMARY_TOKENS = listOf(
        "누적이용금액", "누적사용금액", "누적결제금액", "누적금액", "총누적", "결제예정", "총사용", "총이용",
        "출금예정", "인출예정", "납부예정", "지급예정"
    )

    private val WON_AMOUNT_REGEX = Regex("""[\d,]+원""")
    private val INSTALLMENT_MONTH_REGEX = Regex("""(?<![0-9~～-])\d{1,2}개월""")
    private val CARD_BRAND_1_REGEX = Regex("""(?:NH|KB|국민|신한|삼성|현대|롯데|우리|하나|BC|비씨|기업|씨티|우체국|새마을|신협|수협|카카오뱅크|토스뱅크|케이뱅크)\s*(?:BC\s*|법인\s*|WON\s*)?(?:카드|체크|신용|법인)""")
    private val CARD_BRAND_2_REGEX = Regex("""(?:NH|농협|삼성|현대|신한|KB|국민|롯데|우리|하나|BC|비씨)(?:카드|체크|BC|법인)?\s*(?:\([0-9*]{2,4}\)|[0-9*]{2,})""")
    private val CARD_BRAND_3_REGEX = Regex("""(?:신용|체크)\s*\([,0-9*]+\)""")

    /**
     * 문자열 내에 명시적인 승인/거래 일자(MM/DD, YYYY.MM.DD, M월 D일 등)가 포함되어 있는지 확인합니다.
     */
    fun hasExplicitApprovalDate(text: String?): Boolean {
        if (text.isNullOrBlank()) return false
        val stripped = text.replace(PROMO_INSTALLMENT_RANGE_REGEX, " ")
            .replace(Regex("""\b(?:15\d{2}|16\d{2}|18\d{2}|080|02|0\d{1,2})-\d{3,4}(?:-\d{4})?\b"""), " ")
            .replace(Regex("""\b\d{3,}[-\d*]{5,}\b"""), " ")
        return EXPLICIT_APPROVAL_DATE_REGEX.containsMatchIn(stripped)
    }

    /**
     * 문자열에서 누적이용금액, 결제예정금액, 총사용금액, 잔액, 한도 등 당시 실사용 금액이 아닌 구문을 모두 제거합니다.
     */
    fun stripNonUsageAmounts(body: String): String {
        return body
            .replace(NON_USAGE_AMOUNT_CLAUSE_REGEX, "")
            .replace(NON_USAGE_TRAIL_KEYWORD_REGEX, "")
    }

    private fun containsNaverPayToken(raw: String?): Boolean {
        if (raw.isNullOrBlank()) return false
        val compact = if (raw.indexOf(' ') >= 0) raw.replace(" ", "").lowercase() else raw.lowercase()
        for (token in EXCLUDED_NAVER_PAY_TOKENS) {
            if (compact.contains(token)) return true
        }
        return false
    }

    /**
     * "네이버페이"(NAVERPAY, 네이버파이낸셜, Npay 등) 관련 항목인지 판별합니다.
     * 사용자 요청에 따라 네이버페이 항목은 가계부 이력 등록 대상에서 원천 제외합니다.
     */
    fun isNaverPayExcluded(
        merchantName: String? = null,
        cardOrBankName: String? = null,
        originalText: String? = null
    ): Boolean {
        return containsNaverPayToken(merchantName) ||
            containsNaverPayToken(cardOrBankName) ||
            containsNaverPayToken(originalText)
    }

    fun isInvalidMerchantName(name: String?): Boolean {
        if (name.isNullOrBlank()) return true
        val trimmed = name.trim()
        if (trimmed.lowercase() in INVALID_MERCHANT_NAMES) return true
        if (containsNaverPayToken(trimmed)) return true
        if (INVALID_MERCHANT_SUBSTRINGS.any { trimmed.contains(it, ignoreCase = true) }) return true
        // 광고/안내 문장 부호(!, ?, ~, ～) 또는 할부 개월 수가 가맹점명에 혼입된 경우 차단
        if (trimmed.contains("!") || trimmed.contains("?") || trimmed.contains("~") || trimmed.contains("～")) return true
        if (PROMO_INSTALLMENT_RANGE_REGEX.containsMatchIn(trimmed) || Regex("""\d+\s*개월""").containsMatchIn(trimmed)) return true
        // 상호명이 아닌 긴 안내 문장(28자 초과 또는 마침표 뒤 복문/5어절 이상 문장) 차단
        if (trimmed.length > 28) return true
        if (trimmed.contains(". ") || trimmed.endsWith("다.") || trimmed.endsWith("요.")) return true
        val wordCount = trimmed.split(Regex("""\s+""")).size
        if (wordCount >= 5) return true
        return false
    }

    /**
     * 이미 저장된 트랜잭션이나 파싱 후보가 누적이용금액/결제예정금액/총사용금액/광고/안내 메시지 등 비실사용 오인입 내역이거나
     * 사용처·승인일자·금액이 명확하지 않거나 이력 등록 제외 대상("네이버페이")인지 판별합니다.
     */
    fun isNonUsageOrCorruptedTransaction(
        merchantName: String?,
        cardOrBankName: String?,
        originalText: String
    ): Boolean {
        if (isNaverPayExcluded(merchantName, cardOrBankName, originalText)) return true
        if (isInvalidMerchantName(merchantName)) return true
        val card = cardOrBankName?.trim().orEmpty()
        if (card.startsWith("(결제)") || card.startsWith("[결제]") || card.startsWith("(승인)") || WON_AMOUNT_REGEX.containsMatchIn(card)) {
            return true
        }
        if (isNonPaymentMessage(originalText)) return true
        if (!merchantName.isNullOrBlank() && isNonPaymentMessage(merchantName)) return true
        return false
    }

    /**
     * 주식/증권 시황 브리핑, 뉴스레터, 광고/혜택 안내, 본인인증, 이력 제외 대상("네이버페이") 및
     * 누적이용금액·결제예정금액·총사용금액·명세서 안내 등 당시 사용된 결제 내역이 아닌 문자인지 판별합니다.
     */
    fun isNonPaymentMessage(body: String): Boolean {
        val clean = body.trim()
        if (clean.isBlank()) return true

        // 0. 이력 등록 제외 대상("네이버페이") 즉시 차단
        if (containsNaverPayToken(clean)) {
            return true
        }

        // 1. 광고 / 혜택 / 무이자할부 안내 / 인증 / 쿠폰·포인트 안내 문자 즉시 차단
        if (SPAM_KEYWORDS.any { clean.contains(it, ignoreCase = true) }) {
            return true
        }
        if (PROMO_INSTALLMENT_RANGE_REGEX.containsMatchIn(clean)) {
            return true
        }
        if (PROMO_AMOUNT_CONTEXT_REGEX.containsMatchIn(clean)) {
            return true
        }

        // 2. 주식/증권 시황/뉴스/청약/매매 체결 브리핑 문자 차단
        if (STOCK_OR_NEWS_KEYWORDS.any { clean.contains(it, ignoreCase = true) }) {
            return true
        }

        // 3. 결제예정금액, 총사용금액, 청구금액, 명세서 등 당시 실사용 결제가 아닌 안내 문자 즉시 차단
        if (NON_USAGE_NOTICE_KEYWORDS.any { clean.contains(it, ignoreCase = true) }) {
            return true
        }

        // 4. 누적이용금액/누적금액/결제예정/총사용 등이 포함된 메시지에서 해당 비실사용 금액 구문을 제거했을 때
        //    실제 결제된 승인 금액([\d,]+원)이 전혀 남아있지 않거나, 제목/헤더 형태로만 존재하면 비결제 안내 문자로 판별
        if (SUMMARY_TOKENS.any { clean.contains(it, ignoreCase = true) }) {
            val stripped = stripNonUsageAmounts(clean)
            if (!WON_AMOUNT_REGEX.containsMatchIn(stripped)) {
                return true
            }
            // 누적/총사용 키워드 뒤에 금액이 바로 붙은 부가정보 형태가 아니라 헤더로 쓰이고 별도 줄에 '금액 211,254원'만 있는 안내문 차단
            val hasExplicitApprovalContext = stripped.contains("승인") ||
                stripped.contains("일시불") ||
                stripped.contains("사용처") ||
                stripped.contains("가맹점") ||
                stripped.contains("(결제)") ||
                stripped.contains("[결제]") ||
                stripped.contains("체크출금")
            if (!hasExplicitApprovalContext) {
                return true
            }
        }

        return false
    }

    fun isCardApprovalMessage(body: String): Boolean {
        val clean = body.trim()
        if (isNonPaymentMessage(clean)) return false

        val cleanForAction = clean
            .replace("무이자할부", " ")
            .replace("무이자 할부", " ")
            .replace("부분무이자", " ")
            .replace(PROMO_INSTALLMENT_RANGE_REGEX, " ")

        val hasApprovalAction = cleanForAction.contains("승인") ||
            cleanForAction.contains("일시불") ||
            cleanForAction.contains("사용처") ||
            cleanForAction.contains("가맹점") ||
            cleanForAction.contains("(결제)") ||
            cleanForAction.contains("[결제]") ||
            cleanForAction.contains("[일시불.") ||
            cleanForAction.contains("[체크.") ||
            cleanForAction.contains("[할부.") ||
            cleanForAction.contains("[해외.") ||
            cleanForAction.contains("체크출금") ||
            cleanForAction.contains("카드출금") ||
            cleanForAction.contains("체크결제") ||
            cleanForAction.contains("카드결제")

        val hasExplicitCardHeaderWithNo = Regex(
            """(?:NH|농협|KB|국민|신한|삼성|현대|롯데|우리|하나|BC|비씨|기업|IBK)\s*(?:농협|국민|BC|법인|WON)?\s*(?:(?:체크\s*|신용\s*)?(?:카드|체크)\s*[\d*()-]{2,}|\([0-9*]{2,4}\))"""
        ).containsMatchIn(cleanForAction)

        val hasCardBrand = CARD_BRAND_1_REGEX.containsMatchIn(cleanForAction) ||
            CARD_BRAND_2_REGEX.containsMatchIn(cleanForAction) ||
            CARD_BRAND_3_REGEX.containsMatchIn(cleanForAction) ||
            hasExplicitCardHeaderWithNo ||
            (cleanForAction.contains("1588-1600") && (cleanForAction.contains("카드") || cleanForAction.contains("체크"))) ||
            ((cleanForAction.contains("1588-9955") || cleanForAction.contains("1599-9955")) && (cleanForAction.contains("우리") || cleanForAction.contains("카드") || cleanForAction.contains("체크") || cleanForAction.contains("승인")))

        val isPureAccountTransfer = (cleanForAction.contains("입금") || cleanForAction.contains("송금") || cleanForAction.contains("계좌이체")) &&
            !cleanForAction.contains("승인") && !cleanForAction.contains("일시불") && !cleanForAction.contains("사용처") &&
            !hasExplicitCardHeaderWithNo

        return (hasApprovalAction || (hasCardBrand && (cleanForAction.contains("결제완료") || cleanForAction.contains("결제") || cleanForAction.contains("출금")))) && !isPureAccountTransfer
    }

    private fun resolveTimestamp(
        fallbackDateTime: LocalDateTime,
        month: Int,
        day: Int,
        hour: Int,
        minute: Int
    ): LocalDateTime {
        return runCatching {
            var candidate = LocalDateTime.of(fallbackDateTime.year, month, day, hour, minute)
            val now = LocalDateTime.now()
            if (candidate.isAfter(now.plusDays(2))) {
                candidate = candidate.minusYears(1)
            }
            candidate
        }.getOrDefault(fallbackDateTime)
    }

    fun parse(sender: String?, body: String, fallbackDateTime: LocalDateTime = LocalDateTime.now()): Transaction? {
        val rawTrimmed = body.trim().replace("\r\n", "\n")
        if (rawTrimmed.isBlank()) return null

        // 비결제 문자(광고, 증권/주식 시황, 누적이용금액/결제예정금액/총사용금액 단독 안내) 및 이력 등록 제외 대상("네이버페이") 최상단 원천 차단
        if (isNaverPayExcluded(cardOrBankName = sender, originalText = rawTrimmed) || isNonPaymentMessage(rawTrimmed)) return null

        // 승인 문자 내부에 부가 정보로 포함된 누적이용금액/결제예정금액/총사용금액 구문을 금액 추출 전에 선제 제거
        val cleanBody = stripNonUsageAmounts(rawTrimmed)
            .lines()
            .map { it.trim() }
            .filter { it.isNotBlank() && it.lowercase() !in setOf("제목", "제목없음", "제목 없음", "no subject", "nosubject") }
            .joinToString("\n")
            .replace(Regex("""^알림\s*\n+"""), "")
            .trim()
        if (cleanBody.isBlank() || !cleanBody.contains("원")) return null

        val year = fallbackDateTime.year

        // 1. 네이버플러스 멤버십 / 디지털 구독 결제 (예: [네이버 멤버십] 넷플릭스 스탠다드 6,500원 결제)
        val naverResult = parseNaverMembership(cleanBody, fallbackDateTime)
        if (naverResult != null) return naverResult

        // 2. 우리카드 승인/취소 정밀 파서 ([일시불.승인], 우리카드(1234), 우리(1234), 우리체크, 우리BC 등 모든 포맷 우선 포획)
        val wooriResult = parseWooriCard(cleanBody, sender, year, fallbackDateTime)
        if (wooriResult != null) return wooriResult

        // 3. 하나카드 및 주요 카드사 RCS / 알림톡 Key-Value 템플릿 (예: 승인 \n 금액 1,990원 \n 카드 하나0*2* \n 사용처 배민클럽_우아한형제 ...)
        val hanaResult = parseHanaCard(cleanBody, year, fallbackDateTime)
        if (hanaResult != null) return hanaResult

        // 4. NH농협카드 승인 (예: NH카드2*7*승인 \n 신*선 \n 2,800원 일시불 \n 08/31 16:39 \n 아이스크림살래(힐스점))
        val nhCardResult = parseNhCard(cleanBody, year, fallbackDateTime)
        if (nhCardResult != null) return nhCardResult

        // 5. 삼성카드 승인 (예: 삼성1844승인 정*우 \n 119,000원 일시불 \n 08/10 09:11 (주)천재교과서)
        val samsungResult = parseSamsungCard(cleanBody, year, fallbackDateTime)
        if (samsungResult != null) return samsungResult

        // 6. KB국민카드 승인 (예: KB국민카드4024 승인 \n 정*우 \n 58,390원 08/10 \n LGUPLUS 통신요금자)
        val kbCardResult = parseKbCard(cleanBody, year, fallbackDateTime)
        if (kbCardResult != null) return kbCardResult

        // 7. 신한, 현대, 롯데, 우리, BC 등 표준 카드 승인 문자 (은행 이체 파서보다 반드시 우선 실행하여 카드 승인 문자 가로채기 방지)
        val standardCardResult = parseStandardCard(cleanBody, sender, year, fallbackDateTime)
        if (standardCardResult != null) return standardCardResult

        // 7-2. 범용 카드 승인/취소 정밀 파서 (줄바꿈/어순 변형 카드 문자 100% 포획)
        if (isCardApprovalMessage(cleanBody)) {
            val universalCardResult = parseUniversalCardSms(cleanBody, sender, fallbackDateTime)
            if (universalCardResult != null) return universalCardResult
        }

        // 8. KB국민은행 계좌 입출금 알림 (예: [KB]08/24 11:09 \n 475801**231 \n 정선우 \n 입금 \n 700,000 \n 잔액1,047,518)
        val kbBankResult = parseKbBank(cleanBody, year, fallbackDateTime)
        if (kbBankResult != null) return kbBankResult

        // 9. NH농협 계좌 입출금 알림 (예: 농협 출금20,000원 \n 08/29 02:33 312-****-9414-21 \n 카카오페이 잔액638,728원)
        val nhBankResult = parseNhBank(cleanBody, year, fallbackDateTime)
        if (nhBankResult != null) return nhBankResult

        // 9-2. 계좌번호 또는 입금/출금/송금/이체/잔액/보험금 지급 패턴 기반 은행 입출금 SMS 정밀 파싱 (카드 승인 문자가 아닌 경우에만 수행)
        val isBankTransferSms = !isCardApprovalMessage(cleanBody) && (
            cleanBody.contains("출금") || cleanBody.contains("입금") || cleanBody.contains("송금") ||
                cleanBody.contains("이체") || cleanBody.contains("잔액") ||
                cleanBody.contains("보험금") || cleanBody.contains("실비") || cleanBody.contains("실손") ||
                cleanBody.contains("보상금") || cleanBody.contains("지급완료") || cleanBody.contains("지급되었") ||
                Regex("""\d{3,}[-\d*]{5,}""").containsMatchIn(cleanBody)
            )
        if (isBankTransferSms) {
            val transferResult = parseBankTransferSms(cleanBody, sender, year, fallbackDateTime)
            if (transferResult != null) return transferResult
        }

        // 10. 카카오페이 / 토스 간편결제 문자
        val payResult = parseSimplePay(cleanBody, fallbackDateTime)
        if (payResult != null) return payResult

        // 11. 범용 금액 및 가맹점 추출 Fallback (실제 결제/승인 키워드가 있는 경우에만 제한 허용)
        return parseFallback(cleanBody, sender, fallbackDateTime)
    }

    private fun parseNaverMembership(body: String, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile("""\[네이버\s*멤버십\]\s*(?<merchant>.+?)\s+(?<amount>[\d,]+)원\s*결제""")
        val m = pattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val merchant = m.group("merchant")?.trim() ?: "네이버 멤버십"
            val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
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

    private fun parseWooriCard(body: String, sender: String?, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val combined = "${sender.orEmpty()} $body"
        val hasWooriHeader = combined.contains("우리카드") ||
            combined.contains("우리체크") ||
            combined.contains("우리BC", ignoreCase = true) ||
            combined.contains("우리법인") ||
            combined.contains("우리WON", ignoreCase = true) ||
            combined.contains("1588-9955") ||
            combined.contains("1599-9955") ||
            Regex("""우리\s*\([0-9*]{2,4}\)""").containsMatchIn(body) ||
            Regex("""\[(?:일시불|할부|체크|해외|신용|\d+개월)\.(?:승인|취소)\]""").containsMatchIn(body) ||
            ((sender?.contains("우리") == true || body.contains("[우리]")) && isCardApprovalMessage(body))

        if (!hasWooriHeader) return null

        // 순수 우리은행 계좌 입출금(승인/일시불/카드 키워드 없이 입금/송금/계좌이체/잔액만 있는 문자) 제외
        val hasCardContext = body.contains("승인") ||
            body.contains("일시불") ||
            body.contains("할부") ||
            body.contains("체크") ||
            body.contains("카드") ||
            body.contains("가맹점") ||
            body.contains("사용처") ||
            Regex("""우리\s*\([0-9*]{2,4}\)""").containsMatchIn(body)
        if (!hasCardContext) return null

        val isCancel = body.contains("승인취소") ||
            body.contains("결제취소") ||
            body.contains("취소승인") ||
            body.contains(".취소]") ||
            body.contains("[취소]") ||
            Regex("""\b취소\b""").containsMatchIn(body)

        val bodyWithoutAccum = stripNonUsageAmounts(body)
            .replace(Regex("""잔액[\s:：]*[\d,]+원?"""), "")
            .replace(Regex("""(?:출금예정|지급예정|결제예정|청구예정|할인예정|적립예정)(?:금액|액|일)?[\s:：]*[\d,./-]*원?"""), "")
            .replace(Regex("""\b(?:15|16|18)\d{2}-\d{4}\b"""), "")
            .trim()

        val labeledAmtMatch = Regex("""(?:승인|결제|취소|이용)\s*금액\s*[:：]?\s*(?<amt>[\d,]+)원?""").find(bodyWithoutAccum)
        val amtMatch = labeledAmtMatch ?: Regex("""(?<amt>[\d,]+)원""").find(bodyWithoutAccum) ?: return null
        val rawAmount = amtMatch.groups["amt"]?.value?.replace(",", "")?.toLongOrNull() ?: return null
        if (rawAmount <= 0L) return null
        val amount = if (isCancel) -rawAmount else rawAmount

        val explicitMerMatch = Regex("""(?:사용처|가맹점명?|이용가맹점|이용처)\s*[:：]\s*(?<mer>[^\n\r]+)""").find(bodyWithoutAccum)
        val bodyForDate = bodyWithoutAccum
            .replace(Regex("""(?:우리|비씨|BC)\s*(?:BC|법인|WON|체크|신용|카드)*\s*\([0-9*]{2,4}\)"""), " ")
        val dateMatch = Regex("""(?:20\d{2}[./-])?(?<month>1[0-2]|0?[1-9])[./-](?<day>3[01]|[12]\d|0?[1-9])(?:\s+(?<hour>\d{1,2}):(?<minute>\d{1,2}))?""").find(bodyForDate)
        if (dateMatch == null && explicitMerMatch == null && !hasExplicitApprovalDate(bodyForDate)) {
            return null
        }

        val timestamp = if (dateMatch != null) {
            val mo = dateMatch.groups["month"]?.value?.toIntOrNull() ?: fallbackDateTime.monthValue
            val da = dateMatch.groups["day"]?.value?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val ho = dateMatch.groups["hour"]?.value?.toIntOrNull() ?: fallbackDateTime.hour
            val mi = dateMatch.groups["minute"]?.value?.toIntOrNull() ?: fallbackDateTime.minute
            resolveTimestamp(fallbackDateTime, mo, da, ho, mi)
        } else {
            fallbackDateTime
        }

        val merchant = if (explicitMerMatch != null) {
            cleanMerchantName(explicitMerMatch.groups["mer"]?.value ?: "우리카드 가맹점")
        } else {
            val candidateLines = bodyWithoutAccum.lines()
                .map { it.trim() }
                .filter { line ->
                    line.isNotBlank() &&
                        !line.equals("[Web발신]", ignoreCase = true) &&
                        line != "알림"
                }
                .map { line ->
                    line.replace(Regex("""\[Web발신\]""", RegexOption.IGNORE_CASE), " ")
                        .replace(Regex("""\[(?:일시불|할부|체크|해외|신용|\d+개월)?\.?(?:승인취소|결제취소|취소|승인|결제)\]"""), " ")
                        .replace(Regex("""\[[^\]]*(?:우리|카드|체크|BC|승인|취소|결제)[^\]]*\]"""), " ")
                        .replace(Regex("""(?:우리|비씨|BC)\s*(?:BC|법인|WON|체크|신용|카드)*\s*(?:\([0-9*]{2,6}\)|[0-9*()-]{2,8})?"""), " ")
                        .replace(Regex("""(?:신용|체크|법인)\s*\([,0-9*]+\)"""), " ")
                        .replace(Regex("""[가-힣]+\*[가-힣*]*님?"""), " ")
                        .replace(Regex("""[가-힣]{2,4}님(?=\s|$)"""), " ")
                        .replace(amtMatch.value, " ")
                        .replace(Regex("""[\d,]+원"""), " ")
                        .replace(Regex("""(?:20\d{2}[./-])?(?:1[0-2]|0?[1-9])[./-](?:3[01]|[12]\d|0?[1-9])(?:\s+\d{1,2}:\d{1,2}(?::\d{1,2})?)?"""), " ")
                        .replace(Regex("""(?:\(\s*(?:일시불|할부|\d+개월|체크|신용|결제|승인|취소|승인취소)\s*\)|일시불\.승인|체크\.승인|할부\.승인|해외\.승인|승인취소|결제취소|취소승인|체크출금|카드출금|체크결제|카드결제|일시불|할부|\d+개월|승인|취소|결제완료|결제|출금예정|지급예정|출금)"""), " ")
                        .replace(Regex("""\s+"""), " ")
                        .trim()
                }
                .filter { cand ->
                    cand.isNotBlank() &&
                        cand.length >= 2 &&
                        !isInvalidMerchantName(cand) &&
                        !com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(cand)
                }

            val rawMer = candidateLines.lastOrNull() ?: "카드 가맹점"
            cleanMerchantName(rawMer)
        }

        if (isInvalidMerchantName(merchant) ||
            merchant == "가맹점" ||
            merchant == "카드 가맹점" ||
            com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(merchant)
        ) {
            return null
        }

        val isCheck = body.contains("체크") || sender?.contains("체크") == true
        val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
        return Transaction(
            amount = amount,
            merchantName = merchant,
            originalText = body,
            timestamp = timestamp,
            paymentMethod = if (isCheck) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
            category = category,
            cardOrBankName = "우리카드",
            transferMemo = if (isCancel) "[승인취소]" else null,
            isAutoCategorized = true
        )
    }

    private fun parseHanaCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        if (body.contains("하나") || body.contains("1800-1111") || (body.contains("금액") && (body.contains("사용처") || body.contains("가맹점")))) {
            val isCancel = body.contains("승인취소") || body.contains("결제취소") || body.startsWith("취소")
            val amtPattern = Pattern.compile("""(?:승인|결제|취소)?\s*금액\s*[:：]?\s*(?<amount>[\d,]+)원?""")
            val merPattern = Pattern.compile("""(?:사용처|가맹점명?|이용가맹점)\s*[:：]?\s*(?<merchant>[^\n\r]+)""")
            val cardPattern = Pattern.compile("""(?:^|\n)\s*(?:카드명?|이용카드|결제카드)\s*[:：]?\s*(?<card>[^\n\r]+)""")
            val timePattern = Pattern.compile("""(?:거래시간|일시|승인일시|이용일시)?\s*[:：]?\s*(?:20\d{2}[./-])?(?<month>\d{1,2})[./-](?<day>\d{1,2})\s+(?<hour>\d{1,2}):(?<minute>\d{1,2})""")

            val amtMatcher = amtPattern.matcher(body)
            val merMatcher = merPattern.matcher(body)

            if (amtMatcher.find() && merMatcher.find()) {
                val rawAmount = amtMatcher.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
                val amount = if (isCancel) -rawAmount else rawAmount
                val rawMerchant = merMatcher.group("merchant")?.trim() ?: "하나카드 가맹점"
                val merchant = cleanMerchantName(rawMerchant)
                if (isInvalidMerchantName(merchant) || merchant == "가맹점") return null

                val cardMatcher = cardPattern.matcher(body)
                val rawCardHint = if (cardMatcher.find()) cardMatcher.group("card")?.trim() else null
                val cardName = ExpenseCustomMetaPreferences.inferCanonicalCardCompany(rawCardHint, body) ?: "하나카드"

                var timestamp = fallbackDateTime
                val timeMatcher = timePattern.matcher(body)
                if (timeMatcher.find()) {
                    val mo = timeMatcher.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
                    val da = timeMatcher.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
                    val ho = timeMatcher.group("hour")?.toIntOrNull() ?: 0
                    val mi = timeMatcher.group("minute")?.toIntOrNull() ?: 0
                    timestamp = resolveTimestamp(fallbackDateTime, mo, da, ho, mi)
                }

                val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
                return Transaction(
                    amount = amount,
                    merchantName = merchant,
                    originalText = body,
                    timestamp = timestamp,
                    paymentMethod = PaymentMethod.CREDIT_CARD,
                    category = category,
                    cardOrBankName = cardName,
                    transferMemo = if (isCancel) "[승인취소]" else null,
                    isAutoCategorized = true
                )
            }
        }
        return null
    }

    fun cleanMerchantName(raw: String): String {
        val statusOnlyRegex = Regex("""^(?:\[(?:일시불|할부|체크|해외|신용|\d+개월)?\.?(?:승인취소|결제취소|취소|승인|결제)\]|체크출금|카드출금|체크결제|카드결제|체크|출금예정|지급예정|출금|일시불|\d+개월|승인취소|취소|승인|결제완료|결제|\((?:일시불|\d+개월|결제|승인)\))(?:\s+(?:\[(?:일시불|할부|체크|해외|신용|\d+개월)?\.?(?:승인취소|결제취소|취소|승인|결제)\]|체크출금|카드출금|체크결제|카드결제|출금예정|지급예정|일시불|\d+개월|승인취소|취소|승인|결제완료|결제|\((?:일시불|\d+개월|결제|승인)\)))*$""")
        val csPhoneOnlyRegex = Regex("""^(?:고객센터|대표번호|문의)?\s*[:：]?\s*(?:15|16|18)\d{2}-\d{4}$""")
        val dateOnlyRegex = Regex("""^(?:20\d{2}[./-])?\d{1,2}[./-]\d{1,2}(?:\s+\d{1,2}:\d{1,2}(?::\d{1,2})?)?$""")
        val customerNameOnlyRegex = Regex("""^(?:[가-힣]+\*[가-힣*]*님?|[가-힣]{2,4}님)$""")
        var name = stripNonUsageAmounts(raw).lines().map { it.trim() }.firstOrNull {
            it.isNotBlank() &&
                it.lowercase() !in INVALID_MERCHANT_NAMES &&
                !it.startsWith("총누적") &&
                !it.startsWith("누적") &&
                !it.startsWith("결제예정") &&
                !it.startsWith("출금예정") &&
                !it.startsWith("지급예정") &&
                !it.startsWith("총사용") &&
                !it.startsWith("총이용") &&
                !it.startsWith("잔액") &&
                !csPhoneOnlyRegex.matches(it) &&
                !dateOnlyRegex.matches(it) &&
                !customerNameOnlyRegex.matches(it) &&
                !com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(it) &&
                !statusOnlyRegex.matches(it)
        } ?: raw.trim()
        name = stripNonUsageAmounts(name)
            .replace(Regex("""\b(?:15|16|18)\d{2}-\d{4}\b"""), "")
            .replace(Regex("""잔액\s*[:：]?\s*[\d,]+원?"""), "")
            .replace(Regex("""(?:출금예정|지급예정)\s*[:：]?\s*[\d,]*원?"""), "")
            .trim()
        // 슬래시(/) 구분자로 이어진 카드정보/할부/누적이용금액 파트(예: "지에스25송파물류단지점 / 신용(,0*2*) / / 누적이용금액") 제거
        name = name.replace(Regex("""\s*/\s*(?:신용|체크|일시불|할부|\d+개월|누적|결제예정|총사용|총이용).*$"""), "").trim()
        name = name.replace(Regex("""(?:\s*/\s*)+$"""), "").trim()
        name = name.replace(Regex("""^(?:\[(?:일시불|할부|체크|해외|신용|\d+개월)?\.?(?:승인취소|결제취소|취소|승인|결제)\]|\((?:결제|승인|취소|승인취소)\)|체크출금|카드출금|체크결제|카드결제|일시불|\d+개월|승인취소|승인|결제완료|결제)\s+"""), "").trim()
        name = name.replace(Regex("""\s*(?:\((?:일시불|\d+개월|결제|승인)\)|일시불|\d+개월|체크출금|카드출금|체크결제|카드결제|출금예정|지급예정|승인취소|승인|결제완료|결제)\s*$"""), "").trim()
        // (주), (유), 주식회사 등 접두/접미사 정제
        name = name.replace(Regex("""^\((?:주|유)\)\s*"""), "")
        name = name.replace(Regex("""^주식회사\s*"""), "")
        name = name.replace(Regex("""\s*\((?:주|유)\)가?$"""), "")
        name = name.replace(Regex("""\s*\((?:주|유)$"""), "")
        name = name.replace(Regex("""\s*주식회사$"""), "")
        val finalName = name.trim()
        return if (
            finalName.isBlank() ||
            finalName.lowercase() in INVALID_MERCHANT_NAMES ||
            com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(finalName)
        ) {
            "가맹점"
        } else {
            finalName
        }
    }

    private fun parseNhCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val hasNhCardHeader = Regex("""(?:NH|농협)\s*(?:농협)?\s*(?:체크\s*|신용\s*)?(?:카드|체크)""").containsMatchIn(body) ||
            (body.contains("1588-1600") && (body.contains("농협") || body.contains("NH") || body.contains("카드") || body.contains("체크")))
        if (!hasNhCardHeader) return null

        val isCancel = body.contains("승인취소") || body.contains("결제취소") || body.contains("취소")
        val cleanNhBody = body
            .replace(Regex("""\b(?:15|16|18)\d{2}-\d{4}\b"""), "")
            .replace(Regex("""잔액\s*[:：]?\s*[\d,]+원?"""), "")
            .trim()

        val pattern = Pattern.compile(
            """(?:NH|농협)\s*(?:농협)?\s*(?:체크\s*|신용\s*)?(?:카드|체크)?\s*(?<cardNo>[\d*()-]+)?\s*(?:[가-힣*]+(?:님)?\s*)?(?:승인취소|결제취소|취소|승인|체크출금|카드출금|체크결제|카드결제|결제|출금)?.*?(?<amount>[\d,]+)원(?:\s*(?:\((?:일시불|\d+개월)\)|일시불|\d+개월|체크출금|카드출금|출금|승인|결제))*.*?(?:20\d{2}[./-])?(?<month>\d{1,2})[./-](?<day>\d{1,2})\s+(?<hour>\d{1,2}):(?<minute>\d{1,2})\s*\n?(?<merchant>.+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(cleanNhBody)
        if (m.find()) {
            val rawAmount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            if (rawAmount <= 0L) return null
            val amount = if (isCancel) -rawAmount else rawAmount
            val cardName = "NH농협카드"

            val month = m.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: 0
            val minute = m.group("minute")?.toIntOrNull() ?: 0

            val timestamp = resolveTimestamp(fallbackDateTime, month, day, hour, minute)

            val rawMerchant = m.group("merchant")?.trim() ?: "농협카드 가맹점"
            val merchant = cleanMerchantName(rawMerchant)
            if (!isInvalidMerchantName(merchant) &&
                merchant != "가맹점" &&
                !com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(merchant)
            ) {
                val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
                return Transaction(
                    amount = amount,
                    merchantName = merchant,
                    originalText = body,
                    timestamp = timestamp,
                    paymentMethod = if (body.contains("체크")) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
                    category = category,
                    cardOrBankName = cardName,
                    transferMemo = if (isCancel) "[승인취소]" else null,
                    isAutoCategorized = true
                )
            }
        }
        return parseUniversalCardSms(cleanNhBody, "NH농협카드", fallbackDateTime)?.copy(
            originalText = body,
            cardOrBankName = "NH농협카드"
        )
    }

    private fun parseSamsungCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        if (!body.contains("삼성") || !isCardApprovalMessage(body)) return null
        val isCancel = body.contains("승인취소") || body.contains("취소")
        val pattern = Pattern.compile(
            """삼성(?:카드)?(?<cardNo>[0-9*()]+(?!,))?\s*(?:승인취소|취소|승인)?.*?(?<amount>[\d,]+)원\s*(?:\((?:일시불|\d+개월)\)|일시불|\d+개월|승인취소|승인)?.*?(?:20\d{2}[./-])?(?<month>\d{1,2})[./-](?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2})\s*(?<merchant>.+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val rawAmount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            val amount = if (isCancel) -rawAmount else rawAmount
            val cardName = "삼성카드"

            val month = m.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: 0
            val minute = m.group("minute")?.toIntOrNull() ?: 0

            val timestamp = resolveTimestamp(fallbackDateTime, month, day, hour, minute)

            val rawMerchant = m.group("merchant")?.trim() ?: "삼성카드 가맹점"
            val merchant = cleanMerchantName(rawMerchant)
            if (isInvalidMerchantName(merchant) || merchant == "가맹점") return null

            val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = cardName,
                transferMemo = if (isCancel) "[승인취소]" else null,
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseKbCard(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        if ((!body.contains("KB") && !body.contains("국민카드") && !body.contains("국민체크")) || !isCardApprovalMessage(body)) return null
        if (body.startsWith("[KB]") && (body.contains("\n입금\n") || body.contains("\n출금\n"))) return null
        val isCancel = body.contains("승인취소") || body.contains("취소")
        val pattern = Pattern.compile(
            """(?:\[?KB)?국민(?:카드|체크)?\]?(?<cardNo>[0-9*()]+(?!,))?\s*(?:승인취소|취소|승인|체크출금)?.*?(?<amount>[\d,]+)원(?:\s*(?:\((?:일시불|\d+개월)\)|일시불|\d+개월|체크출금|승인취소|취소|승인))*(?:\s*(?:20\d{2}[./-])?(?<month>\d{1,2})[./-](?<day>\d{1,2})(?:\s*(?<hour>\d{1,2}):(?<minute>\d{1,2}))?)?\s*(?<merchant>.+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val rawAmount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            if (rawAmount <= 0L) return null
            val amount = if (isCancel) -rawAmount else rawAmount
            val cardName = "KB국민카드"

            val monthStr = m.group("month")
            val dayStr = m.group("day")
            val hourStr = m.group("hour")
            val minuteStr = m.group("minute")
            if (monthStr.isNullOrBlank() || dayStr.isNullOrBlank()) {
                // 승인일자가 명시되지 않은 KB 안내 문자는 집계 제외
                if (!hasExplicitApprovalDate(body)) return null
            }
            val timestamp = if (!monthStr.isNullOrBlank() && !dayStr.isNullOrBlank()) {
                val mo = monthStr.toIntOrNull() ?: fallbackDateTime.monthValue
                val da = dayStr.toIntOrNull() ?: fallbackDateTime.dayOfMonth
                val ho = hourStr?.toIntOrNull() ?: fallbackDateTime.hour
                val mi = minuteStr?.toIntOrNull() ?: fallbackDateTime.minute
                resolveTimestamp(fallbackDateTime, mo, da, ho, mi)
            } else {
                fallbackDateTime
            }

            val rawMerchant = m.group("merchant")?.trim() ?: return null
            val merchant = cleanMerchantName(rawMerchant)
            if (isInvalidMerchantName(merchant) || merchant == "가맹점") return null

            val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = if (body.contains("체크")) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = cardName,
                transferMemo = if (isCancel) "[승인취소]" else null,
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

            val month = m.group("month")?.toIntOrNull() ?: fallbackDateTime.monthValue
            val day = m.group("day")?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val hour = m.group("hour")?.toIntOrNull() ?: 0
            val minute = m.group("minute")?.toIntOrNull() ?: 0

            val timestamp = resolveTimestamp(fallbackDateTime, month, day, hour, minute)

            val isIncome = (txType == "입금")
            val category = if (isIncome) ExpenseCategory.INCOME else ExpenseCategory.TRANSFER

            val rawMemo = m.group("memo")?.trim() ?: txType
            val acc = m.group("acc")?.trim()
            val displayMerchant = if (rawMemo.matches(Regex("""^[\d* -]{6,}$"""))) {
                if (isIncome) "입금 내역" else "출금 내역"
            } else {
                rawMemo
            }

            return Transaction(
                amount = amount,
                merchantName = displayMerchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.BANK_TRANSFER,
                category = category,
                cardOrBankName = "KB국민은행",
                transferMemo = "$txType 내역" + (if (!acc.isNullOrBlank()) " (계좌: $acc)" else "") + (if (rawMemo != displayMerchant) " (적요: $rawMemo)" else ""),
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseNhBank(body: String, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        val pattern = Pattern.compile(
            """농협\s*(?<type>출금|입금)\s*(?<amount>[\d,]+)원\s*\n(?<month>\d{1,2})/(?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2}).*?\n(?<memo>[^\n\r]+)""",
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

            val timestamp = resolveTimestamp(fallbackDateTime, month, day, hour, minute)

            val rawMemo = m.group("memo")?.trim() ?: "농협계좌"
            val cleanMemo = rawMemo.replace(Regex("""\s*잔액\s*[\d,]+.*$"""), "").trim()

            val isIncome = (txType == "입금")
            val category = if (isIncome) ExpenseCategory.INCOME else ExpenseCategory.TRANSFER

            return Transaction(
                amount = amount,
                merchantName = "$cleanMemo ($txType)",
                originalText = body,
                timestamp = timestamp,
                paymentMethod = PaymentMethod.BANK_TRANSFER,
                category = category,
                cardOrBankName = "NH농협은행",
                transferMemo = "$txType 내역 (적요: $cleanMemo)",
                isAutoCategorized = true
            )
        }
        return null
    }

    private fun parseBankTransferSms(body: String, sender: String?, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        if (isCardApprovalMessage(body)) return null

        val cleanDirBody = com.autologue.app.domain.model.stripNeutralTransferTerms(body)
        val isInsurancePayout = cleanDirBody.contains("보험금") || cleanDirBody.contains("실비") ||
            cleanDirBody.contains("실손") || cleanDirBody.contains("보상금") ||
            cleanDirBody.contains("지급완료") || cleanDirBody.contains("지급금액") ||
            cleanDirBody.contains("지급되었") || cleanDirBody.contains("환급금")
        val isDeposit = (cleanDirBody.contains("입금") || isInsurancePayout) && !cleanDirBody.contains("출금")

        // 잔액 정보 사전 분리 (거래 금액과 잔액 혼동 원천 차단)
        val balanceMatch = Regex("""잔액\s*(?<balance>[\d,]+)원?""").find(body)
        val balanceStr = balanceMatch?.groups?.get("balance")?.value
        val textWithoutBalance = if (balanceMatch != null) body.replace(balanceMatch.value, "") else body

        // 1. 거래 금액 추출
        val amtMatch = Regex("""(?:출금|입금|송금|이체|지급|금액)?\s*[:：]?\s*(?<amt>[\d,]+)원\s*(?:출금|입금|송금|이체|지급)?""").find(textWithoutBalance)
            ?: Regex("""\b(?<amt>[\d,]+)원\b""").find(textWithoutBalance)
        val amount = amtMatch?.groups?.get("amt")?.value?.replace(",", "")?.toLongOrNull() ?: return null
        if (amount <= 0) return null

        // 2. 날짜/시간 추출 (고객센터 대표번호 1588-1600 등은 계좌번호에서 제외)
        val dateMatch = Regex("""(?<month>\d{1,2})[./-](?<day>\d{1,2})\s*(?<hour>\d{1,2}):(?<minute>\d{1,2})""").find(body)
        val bodyWithoutCsPhone = body.replace(Regex("""\b(?:15|16|18)\d{2}-\d{4}\b"""), " ")
        val accMatch = Regex("""(?<acc>\d{3,}[-\d*]{5,})""").find(bodyWithoutCsPhone)
        if (dateMatch == null && !hasExplicitApprovalDate(body) && accMatch == null && balanceMatch == null) {
            return null
        }
        val timestamp = if (dateMatch != null) {
            val mo = dateMatch.groups["month"]?.value?.toIntOrNull() ?: fallbackDateTime.monthValue
            val da = dateMatch.groups["day"]?.value?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val ho = dateMatch.groups["hour"]?.value?.toIntOrNull() ?: fallbackDateTime.hour
            val mi = dateMatch.groups["minute"]?.value?.toIntOrNull() ?: fallbackDateTime.minute
            resolveTimestamp(fallbackDateTime, mo, da, ho, mi)
        } else {
            fallbackDateTime
        }

        // 3. 은행명 추출 (표준 은행명으로 정규화)
        val allBracketMatches = Regex("""\[(?<tag>[^\]]+)\]""").findAll(body).toList()
        val bracketTags = allBracketMatches.mapNotNull { it.groups["tag"]?.value?.trim() }
            .filter { it.isNotBlank() && !it.equals("Web발신", ignoreCase = true) }
        val bankCandidate = bracketTags.firstOrNull {
            ExpenseCustomMetaPreferences.inferCanonicalBankName(it, null) in ExpenseCustomMetaPreferences.DEFAULT_BANK_NAMES
        } ?: bracketTags.firstOrNull()
        val bankName = ExpenseCustomMetaPreferences.inferCanonicalBankName(bankCandidate ?: sender, body)

        // 대괄호 안에 은행명이 아닌 보험사/기관명(예: [한화손해보험], [DB손해보험])이 들어있는 경우 보존
        val bracketInstitution = bracketTags.firstOrNull { tag ->
            ExpenseCustomMetaPreferences.inferCanonicalBankName(tag, null) !in ExpenseCustomMetaPreferences.DEFAULT_BANK_NAMES &&
                !tag.contains("입출금") && !tag.contains("알림") &&
                tag !in setOf("입금", "출금", "송금", "이체")
        }

        // 4. 계좌번호 추출
        val accNo = accMatch?.groups?.get("acc")?.value

        // 5. 수취인/입금자/가맹점 추출: 잔액, 금액, 일시, 고객센터 번호, 카드번호 파편, 계좌번호, 은행명 제거 후 남은 이름 추출
        var remain = com.autologue.app.domain.model.stripNeutralTransferTerms(bodyWithoutCsPhone)
        for (bm in allBracketMatches) {
            val tag = bm.groups["tag"]?.value?.trim().orEmpty()
            if (tag != bracketInstitution) {
                remain = remain.replace(bm.value, " ")
            } else {
                remain = remain.replace(bm.value, " $tag ")
            }
        }
        if (balanceMatch != null) remain = remain.replace(balanceMatch.value, " ")
        remain = remain.replace(amtMatch.value, " ")
        remain = remain.replace(Regex("""\d{1,2}[./-]\d{1,2}\s*\d{1,2}:\d{1,2}"""), " ")
        if (accNo != null) remain = remain.replace(accNo, " ")
        // 은행명 제거 전에 "NH농협카드 6*9*", "농협카드 2*7*", "카드 6*9*" 등 카드 헤더+번호를 먼저 통째로 제거하여 "카드 6", "카드 2" 잔여물 원천 차단
        remain = remain.replace(
            Regex("""(?:NH|농협|KB|국민|신한|삼성|현대|롯데|우리|하나|BC|비씨|기업|IBK)?\s*(?:농협|국민)?\s*(?:체크\s*|신용\s*)?(?:카드|체크)\s*[\d*()-]+"""),
            " "
        )
        remain = remain.replace(Regex("""(?:NH농협은행|NH농협|농협은행|농협|KB국민은행|KB국민|국민은행|신한은행|우리은행|하나은행|카카오뱅크|토스뱅크|기업은행|IBK기업|Web발신)"""), " ")
        remain = remain.replace(Regex("""(?:입출금알림|입출금|입금안내|출금안내|보험금지급|지급완료|지급안내|지급금액|체크출금|카드출금|출금|입금|송금|이체|결제|승인|완료|잔액|계좌|안내|알림톡|알림|원)"""), " ")
        remain = remain.trim()

        val nameCandidates = Regex("""([가-힣a-zA-Z0-9\s()]{2,18})""").findAll(remain)
            .map { it.value.trim() }
            .filter { cand ->
                cand.length >= 2 &&
                    !cand.contains("은행") &&
                    !cand.contains("알림") &&
                    !cand.matches(Regex("""^[\d\s-]+$""")) &&
                    !com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(cand) &&
                    !isInvalidMerchantName(cand)
            }
            .toList()

        val receiverName = bracketInstitution
            ?: nameCandidates.firstOrNull {
                it != "정선우" && it != "정*우" && it != "고객님" && it != "정선우님" && !it.endsWith("님")
            }
            ?: nameCandidates.firstOrNull()

        // [핵심] 계좌번호는 merchantName(제목)에 절대 노출하지 않고 상대방 이름 또는 입금/출금 내역으로 설정
        val merchant = if (!receiverName.isNullOrBlank()) {
            receiverName
        } else {
            if (isDeposit) "입금 내역" else "출금 내역"
        }
        if (isInvalidMerchantName(merchant)) return null

        val memoParts = mutableListOf<String>()
        if (!accNo.isNullOrBlank()) memoParts.add("계좌: $accNo")
        if (!balanceStr.isNullOrBlank()) memoParts.add("잔액 ${balanceStr}원")
        val transferMemo = if (memoParts.isNotEmpty()) {
            memoParts.joinToString(" · ")
        } else {
            if (isDeposit) "입금 내역" else "이체/출금"
        }

        return Transaction(
            amount = amount,
            merchantName = merchant,
            originalText = body,
            timestamp = timestamp,
            paymentMethod = PaymentMethod.BANK_TRANSFER,
            category = if (isDeposit) ExpenseCategory.INCOME else ExpenseCategory.TRANSFER,
            cardOrBankName = bankName,
            transferMemo = transferMemo,
            isAutoCategorized = true
        )
    }

    private fun parseStandardCard(body: String, sender: String?, year: Int, fallbackDateTime: LocalDateTime): Transaction? {
        if (!isCardApprovalMessage(body)) return null
        val isCancel = body.contains("승인취소") || body.contains("취소")
        val pattern = Pattern.compile(
            """(?:\[Web발신\])?\s*(?:\[(?<cardHeader>[^\]]+)\])?\s*(?<cardName>[가-힣A-Za-z]+(?:카드|체크|신용|페이|은행))(?:\s*(?<cardNo>[0-9*()]{2,}(?!,)))?\s*(?:[A-Za-z0-9]+\s+)?(?:승인취소|취소|승인|결제)?\s*(?:[가-힣\*]+(?:님)?\s+)?(?<amount>[\d,]+)원(?:\s*(?:\((?:일시불|\d+개월)\)|일시불|\d+개월|승인취소|취소|승인))?(?:\s*(?:20\d{2}[./-])?(?<month>\d{1,2})[./-](?<day>\d{1,2}))?(?:\s*(?<hour>\d{1,2}):(?<minute>\d{1,2}))?\s*(?<merchant>.+)""",
            Pattern.DOTALL
        )
        val m = pattern.matcher(body)
        if (m.find()) {
            val rawAmount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            if (rawAmount <= 0L) return null
            val amount = if (isCancel) -rawAmount else rawAmount
            val rawCardName = m.group("cardHeader") ?: m.group("cardName") ?: sender ?: "신용카드"
            val fullCardName = ExpenseCustomMetaPreferences.inferCanonicalCardCompany(rawCardName, body) ?: rawCardName

            val monthStr = m.group("month")
            val dayStr = m.group("day")
            val hourStr = m.group("hour")
            val minuteStr = m.group("minute")

            // 승인일자(MM/DD 등)가 명확하지 않은 광고/안내 문자는 절대 거래로 집계하지 않음
            if (monthStr.isNullOrBlank() || dayStr.isNullOrBlank()) {
                if (!hasExplicitApprovalDate(body)) return null
            }

            val timestamp = if (!monthStr.isNullOrBlank() && !dayStr.isNullOrBlank()) {
                val mo = monthStr.toIntOrNull() ?: fallbackDateTime.monthValue
                val da = dayStr.toIntOrNull() ?: fallbackDateTime.dayOfMonth
                val ho = hourStr?.toIntOrNull() ?: fallbackDateTime.hour
                val mi = minuteStr?.toIntOrNull() ?: fallbackDateTime.minute
                resolveTimestamp(fallbackDateTime, mo, da, ho, mi)
            } else {
                fallbackDateTime
            }

            val rawMerchant = m.group("merchant")?.trim() ?: return null
            val merchant = cleanMerchantName(rawMerchant)
            if (isInvalidMerchantName(merchant) ||
                merchant == "가맹점" ||
                com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(merchant)
            ) return null

            val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
            return Transaction(
                amount = amount,
                merchantName = merchant,
                originalText = body,
                timestamp = timestamp,
                paymentMethod = if (rawCardName.contains("체크")) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = fullCardName,
                transferMemo = if (isCancel) "[승인취소]" else null,
                isAutoCategorized = true
            )
        }
        return null
    }

    /**
     * 카드사별 줄바꿈·어순 변형(일자 먼저 표기, 금액 나중 표기, 단문/장문/RCS 변형 등)을 모두 수용하는 범용 카드 승인 파서
     */
    private fun parseUniversalCardSms(body: String, sender: String?, fallbackDateTime: LocalDateTime): Transaction? {
        val isCancel = body.contains("승인취소") || body.contains("결제취소") || body.contains("취소승인") ||
            Regex("""\b취소\b""").containsMatchIn(body)

        // 1. 누적이용금액, 결제예정금액, 총사용금액, 잔액, 고객센터 번호(1588-1600 등) 제거 후 실제 승인 금액 추출
        val bodyWithoutAccum = stripNonUsageAmounts(body)
            .replace(Regex("""잔액[\s:：]*[\d,]+원?"""), "")
            .replace(Regex("""\b(?:15|16|18)\d{2}-\d{4}\b"""), "")
        val amtMatch = Regex("""(?<amt>[\d,]+)원""").find(bodyWithoutAccum) ?: return null
        val rawAmount = amtMatch.groups["amt"]?.value?.replace(",", "")?.toLongOrNull() ?: return null
        if (rawAmount <= 0L) return null
        val amount = if (isCancel) -rawAmount else rawAmount

        // 2. 날짜/시간 추출 및 명시적 가맹점 라벨 확인 (승인일자와 명시적 사용처 라벨이 모두 없는 불명확 문자는 차단)
        val explicitMerMatch = Regex("""(?:사용처|가맹점명?|이용처)\s*[:：]\s*(?<mer>[^\n\r]+)""").find(bodyWithoutAccum)
        val dateMatch = Regex("""(?:20\d{2}[./-])?(?<month>1[0-2]|0?[1-9])[./-](?<day>3[01]|[12]\d|0?[1-9])(?:\s+(?<hour>\d{1,2}):(?<minute>\d{1,2}))?""").find(bodyWithoutAccum)
        if (dateMatch == null && explicitMerMatch == null && !hasExplicitApprovalDate(bodyWithoutAccum)) {
            return null
        }
        val timestamp = if (dateMatch != null) {
            val mo = dateMatch.groups["month"]?.value?.toIntOrNull() ?: fallbackDateTime.monthValue
            val da = dateMatch.groups["day"]?.value?.toIntOrNull() ?: fallbackDateTime.dayOfMonth
            val ho = dateMatch.groups["hour"]?.value?.toIntOrNull() ?: fallbackDateTime.hour
            val mi = dateMatch.groups["minute"]?.value?.toIntOrNull() ?: fallbackDateTime.minute
            resolveTimestamp(fallbackDateTime, mo, da, ho, mi)
        } else {
            fallbackDateTime
        }

        // 3. 카드사 식별 (알림 제목에 '(결제) 4,500원' 등이 sender로 넘어온 경우 절대 카드명으로 사용하지 않음)
        val safeSender = sender?.trim()?.takeIf { s ->
            s.isNotBlank() &&
                !s.contains("원") &&
                !s.startsWith("(결제)") &&
                !s.startsWith("[결제]") &&
                !s.startsWith("(승인)") &&
                !s.startsWith("[승인]")
        }
        val slashCardNoMatch = Regex("""(?:신용|체크)\s*\(\s*,?\s*(?<no>[0-9*]{2,})\s*\)""").find(bodyWithoutAccum)
        val slashCardNo = slashCardNoMatch?.groups?.get("no")?.value
        val cardName = ExpenseCustomMetaPreferences.inferCanonicalCardCompany(safeSender, body)
            ?: ExpenseCustomMetaPreferences.inferCanonicalCardCompany(safeSender, bodyWithoutAccum)
            ?: when {
                body.contains("1588-1600") -> "NH농협카드"
                body.contains("1588-9955") || body.contains("1599-9955") -> "우리카드"
                !slashCardNo.isNullOrBlank() -> "하나카드"
                else -> safeSender ?: "신용카드"
            }

        // 4. 가맹점명 추출 (명시적 라벨 우선 -> 슬래시 구분자 -> 라인별 정제 추출)
        val slashMerMatch = Regex("""(?<mer>[^\n\r/]+?)\s*/\s*(?:신용|체크)\s*\(""").find(bodyWithoutAccum)
        val merchant = when {
            explicitMerMatch != null -> {
                cleanMerchantName(explicitMerMatch.groups["mer"]?.value ?: "카드 가맹점")
            }
            slashMerMatch != null -> {
                val rawSlashMer = slashMerMatch.groups["mer"]?.value
                    ?.replace(amtMatch.value, "")
                    ?.replace(Regex("""^\s*(?:\((?:결제|승인|취소|승인취소)\)|\[(?:결제|승인|취소|승인취소)\])\s*"""), "")
                    ?.trim() ?: "카드 가맹점"
                cleanMerchantName(rawSlashMer)
            }
            else -> {
                val candidateLines = bodyWithoutAccum.lines()
                    .map { it.trim() }
                    .filter { line ->
                        line.isNotBlank() &&
                            line != "[Web발신]" &&
                            line != "알림" &&
                            !line.matches(Regex("""^[가-힣]+\*[가-힣*]*님?$""")) &&
                            !line.matches(Regex("""^[가-힣]{2,4}님$"""))
                    }
                    .map { line ->
                        line.replace("[Web발신]", "")
                            .replace(Regex("""\[(?:일시불|할부|체크|해외|신용|\d+개월)?\.?(?:승인취소|결제취소|취소|승인|결제)\]"""), "")
                            .replace(Regex("""\[[^\]]*카드[^\]]*\]"""), "")
                            .replace(Regex("""(?:NH|농협|KB|국민|신한|삼성|현대|롯데|우리|하나|BC|비씨|기업|IBK)\s*(?:농협|국민|BC|법인|WON)?\s*(?:체크\s*|신용\s*)?(?:카드|체크)?\s*[\d*()-]*"""), "")
                            .replace(Regex("""(?:체크\s*|신용\s*)?(?:카드|체크)\s*[\d*()-]+"""), "")
                            .replace(Regex("""[가-힣]+\*[가-힣*]*님?"""), "")
                            .replace(Regex("""[가-힣]{2,4}님(?=\s|$)"""), "")
                            .replace(amtMatch.value, "")
                            .replace(Regex("""(?:20\d{2}[./-])?\d{1,2}[./-]\d{1,2}(?:\s+\d{1,2}:\d{1,2})?"""), "")
                            .replace(Regex("""(?:\((?:일시불|\d+개월|결제|승인|취소|승인취소)\)|일시불|\d+개월|승인취소|취소|승인|체크출금|카드출금|체크결제|카드결제|결제완료|결제|출금예정|지급예정|출금)"""), "")
                            .trim()
                    }
                    .filter {
                        it.isNotBlank() &&
                            it.length >= 2 &&
                            !isInvalidMerchantName(it) &&
                            !com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(it)
                    }

                val rawMer = candidateLines.lastOrNull() ?: "카드 가맹점"
                cleanMerchantName(rawMer)
            }
        }

        if (isInvalidMerchantName(merchant) ||
            merchant == "가맹점" ||
            merchant == "카드 가맹점" ||
            com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(merchant)
        ) return null

        val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = bodyWithoutAccum)
        return Transaction(
            amount = amount,
            merchantName = merchant,
            originalText = body,
            timestamp = timestamp,
            paymentMethod = if (bodyWithoutAccum.contains("체크")) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
            category = category,
            cardOrBankName = cardName,
            transferMemo = if (isCancel) "[승인취소]" else null,
            isAutoCategorized = true
        )
    }

    private fun parseSimplePay(body: String, fallbackDateTime: LocalDateTime): Transaction? {
        if (isNaverPayExcluded(originalText = body) || isNonPaymentMessage(body)) return null
        val hasPayAction = body.contains("결제완료") || body.contains("결제") || body.contains("승인") || body.contains("송금") || body.contains("보냈어요")
        if (!hasPayAction || !hasExplicitApprovalDate(body)) return null
        if (body.contains("카카오페이") || body.contains("토스")) {
            val amtPattern = Pattern.compile("""(?<amount>[\d,]+)원""")
            val m = amtPattern.matcher(body)
            if (m.find()) {
                val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
                if (amount <= 0L) return null
                val method = when {
                    body.contains("카카오페이") -> PaymentMethod.KAKAO_PAY
                    else -> PaymentMethod.TOSS_PAY
                }
                val merchant = extractMerchantFromPay(body)
                if (isInvalidMerchantName(merchant)) return null
                val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
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
        // 승인일자와 명시적인 결제/승인/일시불/체크출금 키워드가 모두 명확한 경우에만 거래로 파싱
        val hasPaymentKeyword = body.contains("승인") || body.contains("일시불") || body.contains("체크출금")
        if (!hasPaymentKeyword || !hasExplicitApprovalDate(body) || isNonPaymentMessage(body)) return null

        val amtPattern = Pattern.compile("""(?<amount>[\d,]+)원""")
        val m = amtPattern.matcher(body)
        if (m.find()) {
            val amount = m.group("amount")?.replace(",", "")?.toLongOrNull() ?: return null
            if (amount <= 0L) return null
            val lines = body.split("\n").map { it.trim() }.filter {
                it.isNotEmpty() &&
                    it.lowercase() !in INVALID_MERCHANT_NAMES &&
                    !it.startsWith("총누적") &&
                    !it.startsWith("누적")
            }
            val rawLast = lines.lastOrNull() ?: return null
            val merchant = cleanMerchantName(rawLast)
            if (isInvalidMerchantName(merchant) || merchant == "가맹점" || merchant.length > 28) return null

            val category = RuleMatcherEngine.classifyMerchant(merchant, originalText = body)
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
