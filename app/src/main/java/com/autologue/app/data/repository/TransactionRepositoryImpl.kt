package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.TransactionDao
import com.autologue.app.data.local.entity.TransactionEntity
import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.data.preferences.MerchantAliasPreferences
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.isDepositTransaction
import com.autologue.app.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

class TransactionRepositoryImpl @Inject constructor(
    private val transactionDao: TransactionDao,
    private val merchantAliasPreferences: MerchantAliasPreferences,
    private val expenseCustomMetaPreferences: ExpenseCustomMetaPreferences
) : TransactionRepository {

    constructor(
        transactionDao: TransactionDao,
        merchantAliasPreferences: MerchantAliasPreferences = MerchantAliasPreferences()
    ) : this(transactionDao, merchantAliasPreferences, ExpenseCustomMetaPreferences())

    private fun resolveMerchantWithAlias(
        merchantName: String,
        originalText: String,
        cardOrBankName: String?,
        timestamp: LocalDateTime,
        isDeposit: Boolean
    ): String {
        val directResolved = merchantAliasPreferences.resolveAlias(merchantName, isDeposit)
        if (directResolved.isNotBlank() && directResolved != merchantName) {
            return directResolved
        }
        val isManual = originalText.startsWith("[직접 입력]") || originalText.startsWith("[차계부 직접 입력]")
        if (!isManual && originalText.isNotBlank()) {
            val rawParsed = com.autologue.app.data.parser.SmsParser.parse(cardOrBankName, originalText, timestamp)?.merchantName
                ?: com.autologue.app.data.parser.SmsParser.parse(null, originalText, timestamp)?.merchantName
            if (!rawParsed.isNullOrBlank()) {
                val rawResolved = merchantAliasPreferences.resolveAlias(rawParsed, isDeposit)
                if (rawResolved.isNotBlank() && rawResolved != rawParsed) {
                    return rawResolved
                }
            }
        }
        return directResolved.ifBlank { merchantName }
    }

    private fun shouldExcludeTransaction(tx: Transaction): Boolean {
        if (tx.paymentMethod == com.autologue.app.domain.model.PaymentMethod.NAVER_PAY ||
            com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                tx.merchantName,
                tx.cardOrBankName,
                tx.originalText
            )
        ) {
            return true
        }
        if (expenseCustomMetaPreferences.isTransactionDeleted(tx)) {
            return true
        }
        val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
        if (!isManual && com.autologue.app.data.parser.SmsParser.isNonUsageOrCorruptedTransaction(
                tx.merchantName,
                tx.cardOrBankName,
                tx.originalText
            )
        ) {
            return true
        }
        return false
    }

    override fun getAllTransactionsFlow(): Flow<List<Transaction>> {
        return transactionDao.getAllTransactions().map { entities ->
            entities.map { it.toDomain() }.filterNot { shouldExcludeTransaction(it) }
        }
    }

    override fun getTransactionsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Transaction>> {
        val startMilli = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return transactionDao.getTransactionsByDateRange(startMilli, endMilli).map { list ->
            list.map { e -> e.toDomain() }.filterNot { shouldExcludeTransaction(it) }
        }
    }

    override fun getTransactionsByCategory(category: ExpenseCategory): Flow<List<Transaction>> {
        return transactionDao.getTransactionsByCategory(category).map { list ->
            list.map { e -> e.toDomain() }.filterNot { shouldExcludeTransaction(it) }
        }
    }

    override suspend fun insertTransaction(transaction: Transaction): Long = withContext(Dispatchers.IO) {
        // 이력 등록 제외 대상("네이버페이")은 수동/자동 구분 없이 원천 차단
        if (transaction.paymentMethod == com.autologue.app.domain.model.PaymentMethod.NAVER_PAY ||
            com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                transaction.merchantName,
                transaction.cardOrBankName,
                transaction.originalText
            )
        ) {
            return@withContext -1L
        }

        val isManual = transaction.originalText.startsWith("[직접 입력]") || transaction.originalText.startsWith("[차계부 직접 입력]")
        if (isManual) {
            expenseCustomMetaPreferences.unmarkManualTransactionDeleted(transaction)
        } else if (
            expenseCustomMetaPreferences.isTransactionDeleted(transaction) ||
            com.autologue.app.data.parser.SmsParser.isNonUsageOrCorruptedTransaction(
                transaction.merchantName,
                transaction.cardOrBankName,
                transaction.originalText
            )
        ) {
            return@withContext -1L
        }

        val isDeposit = transaction.isDepositTransaction()
        val isOneTime = expenseCustomMetaPreferences.isOneTimeEdited(transaction)

        val resolvedMerchantName = if (isOneTime) {
            transaction.merchantName
        } else {
            resolveMerchantWithAlias(
                merchantName = transaction.merchantName,
                originalText = transaction.originalText,
                cardOrBankName = transaction.cardOrBankName,
                timestamp = transaction.timestamp,
                isDeposit = isDeposit
            )
        }
        val savedCardDesignation = if (!isOneTime) {
            expenseCustomMetaPreferences.resolveMerchantCardDesignation(resolvedMerchantName, isDeposit)
                ?: expenseCustomMetaPreferences.resolveMerchantCardDesignation(transaction.merchantName, isDeposit)
        } else null

        val savedBaseCategory = if (!isOneTime) {
            expenseCustomMetaPreferences.resolveMerchantBaseCategory(resolvedMerchantName, isDeposit)
                ?: expenseCustomMetaPreferences.resolveMerchantBaseCategory(transaction.merchantName, isDeposit)
        } else null

        val effectiveCategory = savedBaseCategory ?: transaction.category
        val canonicalCardOrBank = savedCardDesignation
            ?: expenseCustomMetaPreferences.resolveDisplayCardOrBankName(
                transaction.copy(merchantName = resolvedMerchantName, category = effectiveCategory)
            )

        val aliasedTransaction = transaction.copy(
            merchantName = resolvedMerchantName,
            category = effectiveCategory,
            cardOrBankName = canonicalCardOrBank ?: transaction.cardOrBankName
        )

        val timestampMillis = aliasedTransaction.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (aliasedTransaction.originalText.isNotBlank()) {
            val existingByText = transactionDao.findByOriginalText(aliasedTransaction.originalText)
            if (existingByText != null) {
                if (!expenseCustomMetaPreferences.isOneTimeEditedId(existingByText.id) &&
                    existingByText.merchantName != aliasedTransaction.merchantName &&
                    merchantAliasPreferences.isKnownAliasTarget(aliasedTransaction.merchantName, isDeposit)
                ) {
                    transactionDao.updateTransaction(existingByText.copy(merchantName = aliasedTransaction.merchantName))
                }
                return@withContext existingByText.id
            }
        }

        // 5분 윈도우 기반 지능형 중복 방지 (동일 금액 + 동일 입금/출금 방향 + ±5분 이내 동일/유사 가맹점)
        val windowMilli = 5 * 60 * 1000L
        val candidates = transactionDao.findTransactionsByAmountAndDateRange(
            amount = aliasedTransaction.amount,
            start = timestampMillis - windowMilli,
            end = timestampMillis + windowMilli
        )

        val duplicate = candidates.firstOrNull { existing ->
            if (existing.toDomain().isDepositTransaction() != isDeposit) return@firstOrNull false
            val isExactMerchant = existing.merchantName == aliasedTransaction.merchantName ||
                existing.merchantName == transaction.merchantName
            val isSimilarMerchant = existing.merchantName.isNotBlank() && aliasedTransaction.merchantName.isNotBlank() &&
                    (existing.merchantName.contains(aliasedTransaction.merchantName) || aliasedTransaction.merchantName.contains(existing.merchantName))
            val existingMillis = existing.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val timeDiff = Math.abs(existingMillis - timestampMillis)

            isExactMerchant || (isSimilarMerchant && timeDiff <= 3 * 60 * 1000L)
        }

        if (duplicate != null) {
            if (!expenseCustomMetaPreferences.isOneTimeEditedId(duplicate.id) &&
                duplicate.merchantName != aliasedTransaction.merchantName &&
                merchantAliasPreferences.isKnownAliasTarget(aliasedTransaction.merchantName, isDeposit)
            ) {
                transactionDao.updateTransaction(duplicate.copy(merchantName = aliasedTransaction.merchantName))
            }
            return@withContext duplicate.id
        }

        val existing = transactionDao.findExactDuplicate(aliasedTransaction.amount, aliasedTransaction.merchantName, aliasedTransaction.timestamp)
        if (existing != null && existing.toDomain().isDepositTransaction() == isDeposit) {
            return@withContext existing.id
        }

        transactionDao.insertTransaction(aliasedTransaction.toEntity())
    }

    override suspend fun updateTransaction(transaction: Transaction) = withContext(Dispatchers.IO) {
        transactionDao.updateTransaction(transaction.toEntity())
    }

    override suspend fun renameMerchantGlobally(
        oldMerchantName: String,
        newMerchantName: String,
        rawOriginalMerchantName: String?,
        isDeposit: Boolean,
        targetCategory: ExpenseCategory?,
        targetCardOrBankName: String?
    ): Int = withContext(Dispatchers.IO) {
        val targetName = newMerchantName.trim()
        if (targetName.isBlank()) return@withContext 0

        if (!oldMerchantName.equals(targetName, ignoreCase = true) ||
            (!rawOriginalMerchantName.isNullOrBlank() && !rawOriginalMerchantName.equals(targetName, ignoreCase = true))
        ) {
            merchantAliasPreferences.registerAlias(
                oldName = oldMerchantName,
                newName = targetName,
                rawOriginalName = rawOriginalMerchantName,
                isDeposit = isDeposit
            )
        }

        val all = transactionDao.getAllTransactionsSync()
        var updatedCount = 0
        val genericPlaceholders = setOf("출금 내역", "입금 내역", "계좌 이체", "카드 가맹점", "가맹점", "정선우", "정*우")
        for (tx in all) {
            val domainTx = tx.toDomain()
            // 1회성 저장으로 보호된 내역은 일괄 치환에서 제외
            if (expenseCustomMetaPreferences.isOneTimeEdited(domainTx)) continue

            // [핵심] 입금(isDeposit == true)과 출금(isDeposit == false)이 다른 내역은 절대 함께 치환하지 않음
            val txIsDeposit = domainTx.isDepositTransaction()
            if (txIsDeposit != isDeposit) continue

            val directMatched = merchantAliasPreferences.isSameMerchant(tx.merchantName, oldMerchantName) ||
                merchantAliasPreferences.isSameMerchant(tx.merchantName, targetName) ||
                (!rawOriginalMerchantName.isNullOrBlank() && merchantAliasPreferences.isSameMerchant(tx.merchantName, rawOriginalMerchantName))

            val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
            val needsRawParse = !directMatched && !isManual && tx.originalText.isNotBlank() && (
                tx.merchantName in genericPlaceholders ||
                (oldMerchantName.isNotBlank() && tx.originalText.contains(oldMerchantName, ignoreCase = true)) ||
                (!rawOriginalMerchantName.isNullOrBlank() && tx.originalText.contains(rawOriginalMerchantName, ignoreCase = true))
            )
            val rawParsed = if (needsRawParse) {
                com.autologue.app.data.parser.SmsParser.parse(tx.cardOrBankName, tx.originalText, tx.timestamp)?.merchantName
                    ?: com.autologue.app.data.parser.SmsParser.parse(null, tx.originalText, tx.timestamp)?.merchantName
            } else null

            val isMatchedOld = directMatched ||
                (!rawParsed.isNullOrBlank() && (
                    merchantAliasPreferences.isSameMerchant(rawParsed, oldMerchantName) ||
                    merchantAliasPreferences.isSameMerchant(rawParsed, targetName) ||
                    (!rawOriginalMerchantName.isNullOrBlank() && merchantAliasPreferences.isSameMerchant(rawParsed, rawOriginalMerchantName))
                ))

            val resolvedName = if (isMatchedOld) {
                targetName
            } else {
                merchantAliasPreferences.resolveAlias(tx.merchantName, txIsDeposit).ifBlank { tx.merchantName }
            }

            val resolvedCategory = if (isMatchedOld && targetCategory != null) {
                targetCategory
            } else {
                tx.category
            }

            val resolvedCard = if (isMatchedOld && !targetCardOrBankName.isNullOrBlank()) {
                targetCardOrBankName
            } else {
                tx.cardOrBankName
            }

            if ((resolvedName.isNotBlank() && resolvedName != tx.merchantName) ||
                resolvedCategory != tx.category ||
                resolvedCard != tx.cardOrBankName
            ) {
                transactionDao.updateTransaction(
                    tx.copy(
                        merchantName = resolvedName.ifBlank { tx.merchantName },
                        category = resolvedCategory,
                        cardOrBankName = resolvedCard
                    )
                )
                updatedCount++
            }
        }
        updatedCount
    }

    override suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        val all = transactionDao.getAllTransactionsSync()
        val target = all.firstOrNull { it.id == id }
        if (target != null) {
            val targetDomain = target.toDomain()
            expenseCustomMetaPreferences.markTransactionDeleted(targetDomain)
            val targetDate = target.timestamp.toLocalDate()
            val targetNormMer = MerchantAliasPreferences.normalizeMerchantKey(target.merchantName)
            val targetIsDep = targetDomain.isDepositTransaction()

            val twinTargets = all.filter { other ->
                if (other.id == id) return@filter true
                val otherDomain = other.toDomain()
                if (expenseCustomMetaPreferences.isTransactionDeleted(otherDomain)) return@filter true
                if (other.amount != target.amount) return@filter false
                if (otherDomain.isDepositTransaction() != targetIsDep) return@filter false
                if (other.timestamp.toLocalDate() != targetDate) return@filter false
                val otherNormMer = MerchantAliasPreferences.normalizeMerchantKey(other.merchantName)
                val sameMer = (targetNormMer.isNotBlank() && targetNormMer == otherNormMer) ||
                    merchantAliasPreferences.isSameMerchant(other.merchantName, target.merchantName) ||
                    (target.originalText.isNotBlank() && target.originalText == other.originalText)
                sameMer
            }
            expenseCustomMetaPreferences.markTransactionsDeleted(twinTargets.map { it.toDomain() })
            for (twin in twinTargets) {
                transactionDao.deleteTransactionById(twin.id)
            }
        } else {
            transactionDao.deleteTransactionById(id)
        }
    }

    override suspend fun getTotalExpenseBetween(start: LocalDateTime, end: LocalDateTime): Long = withContext(Dispatchers.IO) {
        val startMilli = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        transactionDao.getTotalExpenseBetween(startMilli, endMilli)
    }

    override suspend fun cleanDuplicates(): Int = withContext(Dispatchers.IO) {
        val all = transactionDao.getAllTransactionsSync()
        val toDeleteIds = mutableSetOf<Long>()

        // 1. 기존 DB 내 삭제된 항목, 이력 제외 대상("네이버페이"), 비결제 스팸/시황/누적이용금액/결제예정금액/총사용금액 문자('금액' 등), 주유 내역 자가 치유 및 결제명 자동 치환
        for (rawTx in all) {
            val raw = rawTx.originalText
            val isManual = raw.startsWith("[직접 입력]") || raw.startsWith("[차계부 직접 입력]")
            val domainRawTx = rawTx.toDomain()

            if (rawTx.paymentMethod == com.autologue.app.domain.model.PaymentMethod.NAVER_PAY ||
                com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                    rawTx.merchantName,
                    rawTx.cardOrBankName,
                    raw
                ) ||
                expenseCustomMetaPreferences.isTransactionDeleted(domainRawTx)
            ) {
                toDeleteIds.add(rawTx.id)
                continue
            }

            // 누적이용금액, 결제예정금액, 총사용금액 또는 '금액' 오탐 레코드는 즉시 삭제 대상에 추가
            val rawMerchant = rawTx.merchantName.trim()
            val rawCard = rawTx.cardOrBankName.trim()
            val isAccumOrScheduledGhost = !isManual && (
                rawMerchant == "금액" ||
                rawMerchant.contains("누적이용금액") ||
                rawMerchant.contains("누적금액") ||
                rawMerchant.contains("결제예정금액") ||
                rawMerchant.contains("결제예정") ||
                rawMerchant.contains("총사용금액") ||
                rawMerchant.contains("총이용금액") ||
                rawMerchant.contains("신용(,") ||
                rawCard.startsWith("(결제)") ||
                rawCard.startsWith("[결제]") ||
                Regex("""[\d,]+원""").containsMatchIn(rawCard)
            )
            if (isAccumOrScheduledGhost) {
                toDeleteIds.add(rawTx.id)
                continue
            }

            // 1회성 저장으로 수정된 항목은 가맹점명/카테고리/카드명 자동 치환 및 재분류에서 완전히 보호
            if (expenseCustomMetaPreferences.isOneTimeEdited(domainRawTx)) {
                continue
            }

            val isDeposit = domainRawTx.isDepositTransaction()

            // 1-0. 사용자가 등록한 결제처 별칭(입금/출금 방향별 자동 치환 규칙) 및 영구 카테고리/카드 설정 + 카드/은행 표준명 최우선 반영
            val aliasedMerchant = resolveMerchantWithAlias(rawTx.merchantName, raw, rawTx.cardOrBankName, rawTx.timestamp, isDeposit)
            val savedBaseCategory = expenseCustomMetaPreferences.resolveMerchantBaseCategory(aliasedMerchant, isDeposit)
                ?: expenseCustomMetaPreferences.resolveMerchantBaseCategory(rawTx.merchantName, isDeposit)
            val effectiveCat = savedBaseCategory ?: rawTx.category
            val savedCard = expenseCustomMetaPreferences.resolveMerchantCardDesignation(aliasedMerchant, isDeposit)
                ?: expenseCustomMetaPreferences.resolveMerchantCardDesignation(rawTx.merchantName, isDeposit)
                ?: expenseCustomMetaPreferences.resolveDisplayCardOrBankName(
                    domainRawTx.copy(merchantName = aliasedMerchant.ifBlank { rawTx.merchantName }, category = effectiveCat)
                )

            val tx = if ((aliasedMerchant.isNotBlank() && aliasedMerchant != rawTx.merchantName) ||
                (savedBaseCategory != null && savedBaseCategory != rawTx.category) ||
                (!savedCard.isNullOrBlank() && savedCard != rawTx.cardOrBankName)
            ) {
                val updated = rawTx.copy(
                    merchantName = aliasedMerchant.ifBlank { rawTx.merchantName },
                    category = effectiveCat,
                    cardOrBankName = savedCard?.ifBlank { rawTx.cardOrBankName } ?: rawTx.cardOrBankName
                )
                transactionDao.updateTransaction(updated)
                updated
            } else {
                rawTx
            }
            val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(tx.merchantName, isDeposit) || savedBaseCategory != null

            // 1-1. 비결제 문자(주식 시황, 광고, 뉴스, 누적/결제예정/총사용 안내 등) 또는 무효 가맹점명 레코드 정리
            if (!isManual && (
                    com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(raw) ||
                    (!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(tx.merchantName))
                )
            ) {
                val reparsed = com.autologue.app.data.parser.SmsParser.parse(null, raw, tx.timestamp)
                if (reparsed != null && !com.autologue.app.data.parser.SmsParser.isNonUsageOrCorruptedTransaction(reparsed.merchantName, reparsed.cardOrBankName, raw)) {
                    val reparsedDeposit = reparsed.isDepositTransaction()
                    val healedMerchant = resolveMerchantWithAlias(reparsed.merchantName, raw, reparsed.cardOrBankName ?: tx.cardOrBankName, tx.timestamp, reparsedDeposit)
                    val healed = tx.copy(
                        amount = reparsed.amount,
                        merchantName = healedMerchant,
                        paymentMethod = reparsed.paymentMethod,
                        category = reparsed.category,
                        cardOrBankName = reparsed.cardOrBankName ?: tx.cardOrBankName,
                        transferMemo = reparsed.transferMemo,
                        isAutoCategorized = true
                    )
                    transactionDao.updateTransaction(healed)
                } else {
                    toDeleteIds.add(tx.id)
                }
                continue
            }

            val isCardMsg = !isManual && com.autologue.app.data.parser.SmsParser.isCardApprovalMessage(raw)

            if (isCardMsg && !isUserAliased && (
                    tx.paymentMethod == com.autologue.app.domain.model.PaymentMethod.BANK_TRANSFER ||
                    tx.category == ExpenseCategory.TRANSFER ||
                    tx.category == ExpenseCategory.ETC ||
                    tx.merchantName in listOf("출금 내역", "입금 내역", "계좌 이체", "카드 가맹점", "가맹점", "정선우", "정*우")
                )
            ) {
                val reparsed = com.autologue.app.data.parser.SmsParser.parse(tx.cardOrBankName, raw, tx.timestamp)
                    ?: com.autologue.app.data.parser.SmsParser.parse(null, raw, tx.timestamp)
                if (reparsed != null) {
                    val reparsedDeposit = reparsed.isDepositTransaction()
                    val healedMerchant = resolveMerchantWithAlias(reparsed.merchantName, raw, reparsed.cardOrBankName ?: tx.cardOrBankName, tx.timestamp, reparsedDeposit)
                    val healed = tx.copy(
                        amount = reparsed.amount,
                        merchantName = healedMerchant,
                        paymentMethod = reparsed.paymentMethod,
                        category = reparsed.category,
                        cardOrBankName = reparsed.cardOrBankName ?: tx.cardOrBankName,
                        transferMemo = reparsed.transferMemo,
                        isAutoCategorized = true
                    )
                    transactionDao.updateTransaction(healed)
                    continue
                }
            }

            // 주유 키워드가 상호명(또는 별칭 원본 가맹점명)에 포함된 거래가 주유 외 카테고리로 잘못 분류된 경우 복원
            val isAliasFromFuel = merchantAliasPreferences.getOriginalNamesForTarget(tx.merchantName, isDeposit)
                .any { VehicleRepositoryImpl.isFuelMerchant(it) }
            val isFuel = !isDeposit && (
                VehicleRepositoryImpl.isFuelMerchant(tx.merchantName) ||
                isAliasFromFuel ||
                (!isManual && isCardMsg && VehicleRepositoryImpl.isFuelMerchant(raw))
            )
            if (isFuel && tx.category != ExpenseCategory.FUEL) {
                val healedCategory = tx.copy(category = ExpenseCategory.FUEL, isAutoCategorized = true)
                transactionDao.updateTransaction(healedCategory)
                continue
            } else if (!isManual && !isFuel && tx.category == ExpenseCategory.FUEL && !isUserAliased) {
                val recalcCategory = com.autologue.app.data.parser.RuleMatcherEngine.classifyMerchant(tx.merchantName, originalText = raw)
                val healedCategory = tx.copy(category = recalcCategory, isAutoCategorized = true)
                transactionDao.updateTransaction(healedCategory)
                continue
            }

            // 기존 '기타(ETC)'로 저장된 내역 중 병원/약제 등 신규 기본 분류 규칙에 매칭되는 내역 자동 업그레이드
            if (tx.category == ExpenseCategory.ETC && !expenseCustomMetaPreferences.isOneTimeEditedId(tx.id) && savedBaseCategory == null) {
                val recalcCategory = com.autologue.app.data.parser.RuleMatcherEngine.classifyMerchant(tx.merchantName, originalText = raw)
                if (recalcCategory != ExpenseCategory.ETC) {
                    val healedCategory = tx.copy(category = recalcCategory, isAutoCategorized = true)
                    transactionDao.updateTransaction(healedCategory)
                    continue
                }
            }

            // 순수 은행 이체/입출금 내역 분류 (카드 승인 문자는 제외)
            if (!isCardMsg && tx.category == ExpenseCategory.ETC && savedBaseCategory == null) {
                val fullText = "${tx.merchantName} ${tx.originalText} ${tx.transferMemo ?: ""}"
                if (isDeposit) {
                    val updated = tx.copy(category = ExpenseCategory.INCOME, isAutoCategorized = true)
                    transactionDao.updateTransaction(updated)
                } else {
                    val hasTransferKeyword = fullText.contains("출금") || fullText.contains("송금") || fullText.contains("이체")
                    if (hasTransferKeyword) {
                        val updated = tx.copy(category = ExpenseCategory.TRANSFER, isAutoCategorized = true)
                        transactionDao.updateTransaction(updated)
                    }
                }
            }
        }

        // 2. 퍼지 시간 윈도우 중복 탐지 (동일 금액 + 10분 이내 + 동일/유사 가맹점)
        val refreshedAll = transactionDao.getAllTransactionsSync()
        val sortedList = refreshedAll.sortedBy { it.id }
        for (i in sortedList.indices) {
            val a = sortedList[i]
            if (a.id in toDeleteIds) continue
            val aMillis = a.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

            for (j in (i + 1) until sortedList.size) {
                val b = sortedList[j]
                if (b.id in toDeleteIds) continue
                if (a.amount != b.amount) continue
                if (a.toDomain().isDepositTransaction() != b.toDomain().isDepositTransaction()) continue

                val bMillis = b.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                val timeDiff = Math.abs(aMillis - bMillis)
                val isTimeClose = timeDiff <= 10 * 60 * 1000L

                val isSameOriginalText = a.originalText.isNotBlank() && a.originalText == b.originalText
                val isSameMerchant = a.merchantName == b.merchantName ||
                        (a.merchantName.isNotBlank() && b.merchantName.isNotBlank() &&
                                (a.merchantName.contains(b.merchantName) || b.merchantName.contains(a.merchantName)))

                if (isSameOriginalText || (isTimeClose && isSameMerchant)) {
                    toDeleteIds.add(b.id)
                }
            }
        }

        for (id in toDeleteIds) {
            transactionDao.deleteTransactionById(id)
        }

        toDeleteIds.size
    }

    private fun TransactionEntity.toDomain() = Transaction(
        id = id, amount = amount, merchantName = merchantName, originalText = originalText,
        timestamp = timestamp, paymentMethod = paymentMethod, category = category,
        cardOrBankName = cardOrBankName, transferMemo = transferMemo,
        isAutoCategorized = isAutoCategorized, ruleIdApplied = ruleIdApplied
    )

    private fun Transaction.toEntity() = TransactionEntity(
        id = id, amount = amount, merchantName = merchantName, originalText = originalText,
        timestamp = timestamp, paymentMethod = paymentMethod, category = category,
        cardOrBankName = cardOrBankName, transferMemo = transferMemo,
        isAutoCategorized = isAutoCategorized, ruleIdApplied = ruleIdApplied
    )
}
