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
 * 적요/가맹점에 순수하게 사용자 이름("정선우", "정*우", "본인")만 단독 기재된 본인 계좌 간 이동 내역 여부 판별.
 * "한화정선우", "DB정선우", "디비정선우" 등 보험사/기관명과 결합된 입금자명은 본인 계좌 이동이 아닌 정상 외부 입금으로 판별합니다.
 */
fun Transaction.isSelfTransfer(): Boolean {
    // 신용카드/체크카드 결제 승인 문자에 포함된 회원명("정선우")은 계좌간 이동이 아닌 정상 카드 결제임
    if (paymentMethod == PaymentMethod.CREDIT_CARD || paymentMethod == PaymentMethod.CHECK_CARD) {
        return false
    }
    val pureMerchant = merchantName
        .replace(Regex("""\s*\((?:입금|출금)\)\s*$"""), "")
        .replace("님", "")
        .trim()

    if (pureMerchant == "정선우" || pureMerchant == "정*우" || pureMerchant == "본인") {
        return true
    }

    if (pureMerchant in TransactionDeduplicator.GENERIC_TRANSFER_PLACEHOLDERS) {
        val memo = transferMemo.orEmpty()
        if (Regex("""적요\s*[:：]\s*(?:정선우|정\*우|본인)(?:\s|\)|$)""").containsMatchIn(memo)) {
            return true
        }
    }
    return false
}

/**
 * 알림톡 제목이나 안내 문구에 포함되는 중립 복합어("입출금", "입/출금", "출금계좌", "출금가능" 등)를 제거하여
 * 실제 거래 방향("입금" vs "출금") 오판을 원천 차단합니다.
 */
fun stripNeutralTransferTerms(text: String): String {
    if (text.isBlank()) return text
    return text
        .replace("입출금", " ")
        .replace("입/출금", " ")
        .replace("입·출금", " ")
        .replace("입-출금", " ")
        .replace("출금계좌", " ")
        .replace("출금가능", " ")
        .replace("출금한도", " ")
        .replace("자동출금계좌", " ")
        .replace("입금계좌", " ")
}

/**
 * 해당 내역이 '입금(수입)'인지 '출금(지출/이체/결제)'인지 판별합니다.
 * - true: 입금 (입금 문자/알림, 보험금/실비 지급 알림 또는 수입/입금 카테고리)
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
    if (category == ExpenseCategory.INCOME) return true

    val orig = originalText.orEmpty()
    val memo = transferMemo.orEmpty()
    val mer = merchantName.orEmpty().trim()

    val cleanOrig = stripNeutralTransferTerms(orig)
    val cleanMemo = stripNeutralTransferTerms(memo)

    val isInsuranceOrPayoutDeposit = cleanOrig.contains("보험금") ||
        cleanOrig.contains("실비") ||
        cleanOrig.contains("실손") ||
        cleanOrig.contains("보상금") ||
        cleanOrig.contains("지급완료") ||
        cleanOrig.contains("지급금액") ||
        cleanOrig.contains("지급되었") ||
        cleanOrig.contains("환급금")

    val origHasDeposit = cleanOrig.contains("입금") || isInsuranceOrPayoutDeposit
    val origHasWithdraw = cleanOrig.contains("출금") ||
        cleanOrig.contains("승인") ||
        cleanOrig.contains("결제") ||
        cleanOrig.contains("일시불") ||
        cleanOrig.contains("할부")

    // 원문에서 '입출금' 등 중립어를 제거한 뒤 명시적 '입금'(또는 보험금 지급)만 있고 '출금/승인/결제'가 없다면,
    // 과거 파서에서 merchantName이 '출금 내역'으로 잘못 저장되었더라도 입금(true)으로 복원 판별합니다.
    if (origHasDeposit && !origHasWithdraw) return true
    if (origHasWithdraw && !origHasDeposit) return false

    val hasExplicitWithdraw = origHasWithdraw ||
        cleanMemo.startsWith("출금") || mer.endsWith("(출금)") || mer == "출금 내역"
    val hasExplicitDeposit = origHasDeposit || cleanMemo.startsWith("입금") ||
        mer.endsWith("(입금)") || mer == "입금 내역" ||
        mer.contains("실비보상") || mer.contains("보험금")

    if (hasExplicitDeposit && !hasExplicitWithdraw) return true
    if (hasExplicitWithdraw && !hasExplicitDeposit) return false

    return false
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

/**
 * 다중 채널(문자 + 보험사 알림톡 + 은행 알림톡 등) 동시 수신 내역의
 * 시간·금액·입출금 방향·잔액·기관명 비교 기반 단일 건 통합 집계 및 대표 항목 선정 엔진
 */
object TransactionDeduplicator {

    val GENERIC_TRANSFER_PLACEHOLDERS = setOf(
        "입금 내역", "출금 내역", "계좌 이체", "이체/출금", "카드 가맹점", "가맹점", "정선우", "정*우", "본인"
    )

    private val BALANCE_EXTRACT_REGEX = Regex("""잔액\s*[:：]?\s*(?<bal>[\d,]+)원?""")
    private val DIRECTION_SUFFIX_CLEAN_REGEX = Regex("""\s*\((?:입금|출금)\)\s*$""")

    fun extractBalance(tx: Transaction): Long? {
        val memoMatch = tx.transferMemo?.let { BALANCE_EXTRACT_REGEX.find(it) }
        if (memoMatch != null) {
            memoMatch.groups["bal"]?.value?.replace(",", "")?.toLongOrNull()?.let { return it }
        }
        val origMatch = BALANCE_EXTRACT_REGEX.find(tx.originalText)
        return origMatch?.groups?.get("bal")?.value?.replace(",", "")?.toLongOrNull()
    }

    /**
     * 가맹점명/입금자명 및 원문에서 보험사·금융사·기관 공통 식별 토큰을 추출합니다.
     * 예: "한화손해(실비보상)", "한화손해보험", "한화손보", "한화정선우" -> "INS_HANWHA"
     * 예: "DB손해보험", "DB손보", "디비손보", "DB정선우", "디비정선우" -> "INS_DB"
     */
    fun extractInstitutionToken(merchantName: String, originalText: String = ""): String? {
        val combined = "${merchantName.trim()} ${originalText.trim()}".uppercase()
        val merUpper = merchantName.trim().uppercase()

        return when {
            merUpper.contains("한화") || combined.contains("한화손") || combined.contains("한화정선우") -> "INS_HANWHA"
            merUpper.contains("DB") || merUpper.contains("디비") || merUpper.contains("동부화재") ||
                combined.contains("DB손") || combined.contains("디비손") ||
                combined.contains("DB정선우") || combined.contains("디비정선우") -> "INS_DB"
            merUpper.contains("삼성화재") || merUpper.contains("삼성생명") || merUpper.contains("삼성정선우") ||
                combined.contains("삼성화재") -> "INS_SAMSUNG"
            merUpper.contains("현대해상") || merUpper.contains("현대정선우") || combined.contains("현대해상") -> "INS_HYUNDAI"
            merUpper.contains("메리츠") || combined.contains("메리츠") -> "INS_MERITZ"
            merUpper.contains("KB손") || merUpper.contains("KB정선우") || combined.contains("KB손해보험") -> "INS_KB"
            merUpper.contains("롯데손") || merUpper.contains("롯데정선우") || combined.contains("롯데손해보험") -> "INS_LOTTE"
            merUpper.contains("흥국") || combined.contains("흥국화재") -> "INS_HEUNGKUK"
            merUpper.contains("교보") || combined.contains("교보생명") -> "INS_KYOBO"
            merUpper.contains("농협생명") || merUpper.contains("농협손해") || merUpper.contains("NH손해") -> "INS_NH"
            else -> null
        }
    }

    /**
     * 가맹점명에서 '정선우', '(입금)', '(출금)', '(실비보상)', '손해보험', '손보', '(주)' 등을 제거한 핵심 상호 어근 추출
     */
    fun extractMerchantCoreStem(name: String): String {
        val cleaned = name.trim()
            .replace(DIRECTION_SUFFIX_CLEAN_REGEX, "")
            .replace(Regex("""\([^)]*\)"""), "")
            .replace("주식회사", "")
            .replace("손해보험", "")
            .replace("화재보험", "")
            .replace("생명보험", "")
            .replace("손보", "")
            .replace("손해", "")
            .replace("화재", "")
            .replace("생명", "")
            .replace("보험", "")
            .replace("실비보상", "")
            .replace("실비", "")
            .replace(Regex("""정\*?선우$"""), "")
            .replace(Regex("""\s+"""), "")
            .uppercase()
            .replace("디비", "DB")
        return if (cleaned.length >= 2 && cleaned !in setOf("입금내역", "출금내역", "계좌이체", "이체/출금", "가맹점", "카드가맹점")) {
            cleaned
        } else {
            ""
        }
    }

    /**
     * 농협 '[입출금]' 알림톡 등에서 '입출금' 단어 내 '출금' 문자열로 인해
     * 실제 입금 거래가 '출금 내역(TRANSFER)'으로 오분류되어 중복 저장된 레코드인지 판별합니다.
     */
    fun isPhantomWithdrawOfDeposit(candidateWithdraw: Transaction, confirmedDeposit: Transaction): Boolean {
        if (candidateWithdraw.amount != confirmedDeposit.amount) return false
        if (!confirmedDeposit.isDepositTransaction()) return false

        val timeDiffMillis = kotlin.math.abs(
            java.time.Duration.between(candidateWithdraw.timestamp, confirmedDeposit.timestamp).toMillis()
        )
        if (timeDiffMillis > 10 * 60 * 1000L) return false

        val mer = candidateWithdraw.merchantName.trim()
        val isGenericWithdrawPlaceholder = mer == "출금 내역" || mer == "계좌 이체" || mer == "이체/출금"
        if (!isGenericWithdrawPlaceholder) return false

        val balA = extractBalance(candidateWithdraw)
        val balB = extractBalance(confirmedDeposit)
        if (balA != null && balB != null && balA == balB) {
            return true
        }

        val cleanOrig = stripNeutralTransferTerms(candidateWithdraw.originalText)
        if (cleanOrig.contains("입금")) {
            return true
        }

        // 동일 시각(±5분 이내) + 동일 금액의 입금 내역과 함께 생성된 플레이스홀더 '출금 내역'은 입출금 알림톡 중복 건
        return timeDiffMillis <= 5 * 60 * 1000L && candidateWithdraw.isBankAccountTransaction()
    }

    /**
     * 두 거래 내역이 다중 채널(문자·보험사 알림톡·은행 알림톡 등)로 수신된 동일한 단일 거래인지 판별합니다.
     */
    fun areSameTransactionEvent(
        a: Transaction,
        b: Transaction,
        isSameMerchantAlias: ((String, String) -> Boolean)? = null
    ): Boolean {
        if (a.id != 0L && a.id == b.id) return true
        if (a.amount != b.amount) return false

        val aManual = a.originalText.startsWith("[직접 입력]") || a.originalText.startsWith("[차계부 직접 입력]")
        val bManual = b.originalText.startsWith("[직접 입력]") || b.originalText.startsWith("[차계부 직접 입력]")

        if (!aManual && !bManual && a.originalText.isNotBlank() && a.originalText == b.originalText) {
            return true
        }

        val timeDiffMillis = kotlin.math.abs(
            java.time.Duration.between(a.timestamp, b.timestamp).toMillis()
        )
        if (timeDiffMillis > 30 * 60 * 1000L) return false

        val aRawDeposit = a.isDepositTransaction()
        val bRawDeposit = b.isDepositTransaction()
        val isPhantomPair = (aRawDeposit && !bRawDeposit && isPhantomWithdrawOfDeposit(b, a)) ||
            (!aRawDeposit && bRawDeposit && isPhantomWithdrawOfDeposit(a, b))

        if (aRawDeposit != bRawDeposit && !isPhantomPair) {
            return false
        }

        val isEffectiveDeposit = aRawDeposit || bRawDeposit

        // 둘 다 수동 직접 입력인 경우에는 가맹점명이 같고 1분 이내일 때만 중복 처리
        if (aManual && bManual) {
            return timeDiffMillis <= 60_000L && a.merchantName.trim().equals(b.merchantName.trim(), ignoreCase = true)
        }

        val merA = a.merchantName.trim()
        val merB = b.merchantName.trim()
        val normA = merA.replace(Regex("""\s+"""), "").uppercase()
        val normB = merB.replace(Regex("""\s+"""), "").uppercase()

        val isExactOrSubstringMerchant = normA.isNotBlank() && normB.isNotBlank() &&
            (normA == normB || normA.contains(normB) || normB.contains(normA) ||
                isSameMerchantAlias?.invoke(merA, merB) == true)

        val tokenA = extractInstitutionToken(merA, a.originalText)
        val tokenB = extractInstitutionToken(merB, b.originalText)
        val isSameInstitution = tokenA != null && tokenA == tokenB

        val stemA = extractMerchantCoreStem(merA)
        val stemB = extractMerchantCoreStem(merB)
        val isSameCoreStem = stemA.isNotBlank() && stemB.isNotBlank() &&
            (stemA == stemB || stemA.contains(stemB) || stemB.contains(stemA))

        val balA = extractBalance(a)
        val balB = extractBalance(b)
        val isSameBalance = balA != null && balB != null && balA == balB

        val isEitherPlaceholderOrNamed = merA in GENERIC_TRANSFER_PLACEHOLDERS ||
            merB in GENERIC_TRANSFER_PLACEHOLDERS ||
            merA.endsWith("정선우") || merB.endsWith("정선우") ||
            merA.endsWith("정*우") || merB.endsWith("정*우")

        if (isEffectiveDeposit) {
            // 1) 입금 내역: 동일 금액이 10분 이내에 여러 건(문자 + 보험사 알림톡 + 은행 알림톡) 들어온 경우 동일 입금 건으로 통합
            if (timeDiffMillis <= 10 * 60 * 1000L) {
                return true
            }
            // 2) 입금 내역: 보험사 지급 알림톡과 은행 입금 문자 간 시차(최대 30분)가 있더라도 기관/어근/잔액/플레이스홀더가 매칭되면 통합
            if (isExactOrSubstringMerchant || isSameInstitution || isSameCoreStem || isSameBalance || isEitherPlaceholderOrNamed) {
                return true
            }
            return false
        } else {
            // 출금/이체/결제 내역
            if (timeDiffMillis <= 10 * 60 * 1000L && (isExactOrSubstringMerchant || isSameInstitution || isSameCoreStem)) {
                return true
            }
            // 은행 계좌 출금/이체가 문자와 알림톡으로 동시에 수신된 경우 (5분 이내 + 동일 금액 + 동일 잔액 또는 플레이스홀더/동일 시각)
            if (timeDiffMillis <= 5 * 60 * 1000L && a.isBankAccountTransaction() && b.isBankAccountTransaction()) {
                if (isSameBalance || isEitherPlaceholderOrNamed || timeDiffMillis <= 3 * 60 * 1000L) {
                    return true
                }
            }
            return false
        }
    }

    /**
     * 중복 그룹 내에서 화면에 표시하고 남길 대표 항목(Winner)의 품질 점수를 산출합니다.
     * 1순위: 사용자가 1회성 수정한 항목 (+10,000)
     * 2순위: 사용자가 영구 별칭/카테고리/분류 규칙을 지정한 항목, 예: "한화손해(실비보상)" (+5,000)
     * 3순위: 구체적 상호/기관명이 살아있는 항목, 예: "DB손해보험", "한화손해보험" (+1,000 ~ +1,800)
     * 4순위: 통장 축약 인자명, 예: "한화정선우", "DB정선우" (-500)
     * 5순위: 일반 플레이스홀더, 예: "입금 내역", "출금 내역" (-2,000 ~ -5,000)
     */
    fun scoreTransactionQuality(
        tx: Transaction,
        isOneTimeEdited: Boolean = false,
        isKnownAliasOrCustomRule: Boolean = false,
        forceDepositCluster: Boolean = false
    ): Int {
        var score = 0
        if (isOneTimeEdited) score += 10_000
        if (isKnownAliasOrCustomRule) score += 5_000

        val mer = tx.merchantName.trim()
        when {
            mer in GENERIC_TRANSFER_PLACEHOLDERS -> score -= 2_000
            mer.endsWith("정선우") || mer.endsWith("정*우") -> score -= 500
            else -> {
                score += 1_000
                if (mer.contains("(") && mer.contains(")") && !mer.endsWith("(입금)") && !mer.endsWith("(출금)")) {
                    score += 800
                }
            }
        }

        val isDep = forceDepositCluster || tx.isDepositTransaction()
        if (isDep) {
            if (tx.category == ExpenseCategory.INCOME) score += 300
            if (mer == "출금 내역" || tx.category == ExpenseCategory.TRANSFER) score -= 3_000
        }

        return score
    }

    /**
     * 트랜잭션 리스트를 금액 버킷($O(N)$) 단위로 클러스터링하여 중복 그룹 목록을 반환합니다.
     */
    fun clusterDuplicates(
        transactions: List<Transaction>,
        isSameMerchantAlias: ((String, String) -> Boolean)? = null
    ): List<List<Transaction>> {
        if (transactions.size <= 1) return transactions.map { listOf(it) }

        val byAmount = transactions.groupBy { it.amount }
        val clusters = ArrayList<List<Transaction>>(transactions.size)

        for ((_, bucket) in byAmount) {
            if (bucket.size == 1) {
                clusters.add(bucket)
                continue
            }
            val visited = BooleanArray(bucket.size)
            for (i in bucket.indices) {
                if (visited[i]) continue
                visited[i] = true
                val group = ArrayList<Transaction>(4)
                group.add(bucket[i])

                var added = true
                while (added) {
                    added = false
                    for (j in bucket.indices) {
                        if (visited[j]) continue
                        val candidate = bucket[j]
                        if (group.any { member -> areSameTransactionEvent(member, candidate, isSameMerchantAlias) }) {
                            visited[j] = true
                            group.add(candidate)
                            added = true
                        }
                    }
                }
                clusters.add(group)
            }
        }
        return clusters
    }

    /**
     * 중복 클러스터에서 최우선 대표 항목(Winner) 1건만 선택하여 반환합니다.
     */
    fun selectWinner(
        cluster: List<Transaction>,
        isOneTimeEditedProvider: (Transaction) -> Boolean = { false },
        isKnownAliasOrRuleProvider: (Transaction) -> Boolean = { false }
    ): Transaction {
        if (cluster.size == 1) return cluster.first()

        val hasConfirmedDeposit = cluster.any { it.isDepositTransaction() }
        val winner = cluster.maxWithOrNull(
            compareBy<Transaction> { tx ->
                scoreTransactionQuality(
                    tx = tx,
                    isOneTimeEdited = isOneTimeEditedProvider(tx),
                    isKnownAliasOrCustomRule = isKnownAliasOrRuleProvider(tx),
                    forceDepositCluster = hasConfirmedDeposit
                )
            }.thenBy { tx ->
                // 점수가 같으면 먼저 생성된(유효 ID가 작은) 항목 우선
                if (tx.id > 0L) -tx.id else Long.MIN_VALUE
            }
        ) ?: cluster.first()

        return if (hasConfirmedDeposit && winner.category != ExpenseCategory.INCOME && !isOneTimeEditedProvider(winner)) {
            val fixedMer = if (winner.merchantName == "출금 내역") "입금 내역" else winner.merchantName
            winner.copy(category = ExpenseCategory.INCOME, merchantName = fixedMer)
        } else {
            winner
        }
    }

    /**
     * 전체 트랜잭션 리스트에서 중복 내역을 통합 제거하고 대표 항목 1건씩만 남긴 리스트를 반환합니다.
     */
    fun deduplicate(
        transactions: List<Transaction>,
        isSameMerchantAlias: ((String, String) -> Boolean)? = null,
        isOneTimeEditedProvider: (Transaction) -> Boolean = { false },
        isKnownAliasOrRuleProvider: (Transaction) -> Boolean = { false }
    ): List<Transaction> {
        if (transactions.size <= 1) return transactions
        val indexMap = HashMap<Long, Int>(transactions.size * 2)
        transactions.forEachIndexed { idx, tx -> indexMap[tx.id] = idx }

        val clusters = clusterDuplicates(transactions, isSameMerchantAlias)
        val winners = ArrayList<Transaction>(clusters.size)
        for (cluster in clusters) {
            winners.add(selectWinner(cluster, isOneTimeEditedProvider, isKnownAliasOrRuleProvider))
        }

        // 원래 정렬 순서(시간 역순 등) 유지
        winners.sortBy { indexMap[it.id] ?: Int.MAX_VALUE }
        return winners
    }
}




