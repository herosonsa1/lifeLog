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
import kotlinx.coroutines.flow.flowOn
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

    private fun isKnownAliasOrCustomRule(tx: Transaction): Boolean {
        val isDep = tx.isDepositTransaction()
        val resolvedMer = merchantAliasPreferences.resolveAlias(tx.merchantName, isDep).ifBlank { tx.merchantName }
        return resolvedMer != tx.merchantName ||
            merchantAliasPreferences.isKnownAliasTarget(resolvedMer, isDep) ||
            merchantAliasPreferences.isKnownAliasTarget(tx.merchantName, isDep) ||
            expenseCustomMetaPreferences.resolveMerchantBaseCategory(resolvedMer, isDep) != null ||
            expenseCustomMetaPreferences.resolveMerchantBaseCategory(tx.merchantName, isDep) != null ||
            expenseCustomMetaPreferences.resolveCustomCategoryLabel(tx) != null ||
            expenseCustomMetaPreferences.resolveUserClassification(tx) != null
    }

    private fun deduplicateDomainList(list: List<Transaction>): List<Transaction> {
        if (list.size <= 1) return list
        return com.autologue.app.domain.model.TransactionDeduplicator.deduplicate(
            transactions = list,
            isSameMerchantAlias = merchantAliasPreferences::isSameMerchant,
            isOneTimeEditedProvider = expenseCustomMetaPreferences::isOneTimeEdited,
            isKnownAliasOrRuleProvider = ::isKnownAliasOrCustomRule
        )
    }

    override fun getAllTransactionsFlow(): Flow<List<Transaction>> {
        return transactionDao.getAllTransactions().map { entities ->
            val validList = entities.map { it.toDomain() }.filterNot { shouldExcludeTransaction(it) }
            deduplicateDomainList(validList)
        }.flowOn(Dispatchers.Default)
    }

    override fun getTransactionsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Transaction>> {
        val startMilli = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return transactionDao.getTransactionsByDateRange(startMilli, endMilli).map { list ->
            val validList = list.map { e -> e.toDomain() }.filterNot { shouldExcludeTransaction(it) }
            deduplicateDomainList(validList)
        }.flowOn(Dispatchers.Default)
    }

    override fun getTransactionsByCategory(category: ExpenseCategory): Flow<List<Transaction>> {
        return transactionDao.getTransactionsByCategory(category).map { list ->
            val validList = list.map { e -> e.toDomain() }.filterNot { shouldExcludeTransaction(it) }
            deduplicateDomainList(validList)
        }.flowOn(Dispatchers.Default)
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
        val savedBaseCategory = if (!isOneTime) {
            expenseCustomMetaPreferences.resolveMerchantBaseCategory(resolvedMerchantName, isDeposit)
                ?: expenseCustomMetaPreferences.resolveMerchantBaseCategory(transaction.merchantName, isDeposit)
        } else null

        val effectiveCategory = savedBaseCategory
            ?: if (isDeposit && transaction.category == ExpenseCategory.TRANSFER) ExpenseCategory.INCOME else transaction.category
        val canonicalCardOrBank = expenseCustomMetaPreferences.resolveDisplayCardOrBankName(
            transaction.copy(merchantName = resolvedMerchantName, category = effectiveCategory),
            fallbackMerchantName = transaction.merchantName
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
                val existingDomain = existingByText.toDomain()
                if (!expenseCustomMetaPreferences.isOneTimeEdited(existingDomain)) {
                    val forceDep = isDeposit || existingDomain.isDepositTransaction()
                    val existingScore = com.autologue.app.domain.model.TransactionDeduplicator.scoreTransactionQuality(
                        tx = existingDomain,
                        isOneTimeEdited = false,
                        isKnownAliasOrCustomRule = isKnownAliasOrCustomRule(existingDomain),
                        forceDepositCluster = forceDep
                    )
                    val incomingScore = com.autologue.app.domain.model.TransactionDeduplicator.scoreTransactionQuality(
                        tx = aliasedTransaction,
                        isOneTimeEdited = isOneTime,
                        isKnownAliasOrCustomRule = isKnownAliasOrCustomRule(aliasedTransaction),
                        forceDepositCluster = forceDep
                    )
                    val isBetterDetail = incomingScore > existingScore ||
                        com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                            aliasedTransaction.merchantName, aliasedTransaction.transferMemo
                        ) > com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                            existingDomain.merchantName, existingDomain.transferMemo
                        ) ||
                        merchantAliasPreferences.isKnownAliasTarget(aliasedTransaction.merchantName, isDeposit)

                    if (isBetterDetail && (
                            existingByText.merchantName != aliasedTransaction.merchantName ||
                            existingByText.category != aliasedTransaction.category ||
                            existingByText.cardOrBankName != aliasedTransaction.cardOrBankName ||
                            existingByText.transferMemo != aliasedTransaction.transferMemo
                        )
                    ) {
                        transactionDao.updateTransaction(
                            existingByText.copy(
                                merchantName = aliasedTransaction.merchantName,
                                category = if (forceDep) ExpenseCategory.INCOME else aliasedTransaction.category,
                                paymentMethod = aliasedTransaction.paymentMethod,
                                cardOrBankName = aliasedTransaction.cardOrBankName.ifBlank { existingByText.cardOrBankName },
                                transferMemo = aliasedTransaction.transferMemo
                            )
                        )
                    }
                }
                return@withContext existingByText.id
            }
        }

        // 다중 채널(문자 + 보험사 알림톡 + 은행 알림톡 + 체크출금 알림) 시간·금액·입출금 내역 통합 중복 방지
        val windowMilli = 30 * 60 * 1000L
        val candidates = transactionDao.findTransactionsByAmountAndDateRange(
            amount = aliasedTransaction.amount,
            start = timestampMillis - windowMilli,
            end = timestampMillis + windowMilli
        )

        val duplicate = candidates.firstOrNull { existing ->
            com.autologue.app.domain.model.TransactionDeduplicator.areSameTransactionEvent(
                a = existing.toDomain(),
                b = aliasedTransaction,
                isSameMerchantAlias = merchantAliasPreferences::isSameMerchant
            )
        }

        if (duplicate != null) {
            val existingDomain = duplicate.toDomain()
            if (!expenseCustomMetaPreferences.isOneTimeEdited(existingDomain)) {
                val forceDep = isDeposit || existingDomain.isDepositTransaction()
                val existingScore = com.autologue.app.domain.model.TransactionDeduplicator.scoreTransactionQuality(
                    tx = existingDomain,
                    isOneTimeEdited = false,
                    isKnownAliasOrCustomRule = isKnownAliasOrCustomRule(existingDomain),
                    forceDepositCluster = forceDep
                )
                val incomingScore = com.autologue.app.domain.model.TransactionDeduplicator.scoreTransactionQuality(
                    tx = aliasedTransaction,
                    isOneTimeEdited = isOneTime,
                    isKnownAliasOrCustomRule = isKnownAliasOrCustomRule(aliasedTransaction),
                    forceDepositCluster = forceDep
                )
                val isBetterDetail = incomingScore > existingScore ||
                    com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                        aliasedTransaction.merchantName, aliasedTransaction.transferMemo
                    ) > com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                        existingDomain.merchantName, existingDomain.transferMemo
                    )
                if (isBetterDetail) {
                    transactionDao.updateTransaction(
                        duplicate.copy(
                            merchantName = aliasedTransaction.merchantName,
                            category = if (forceDep) ExpenseCategory.INCOME else aliasedTransaction.category,
                            paymentMethod = aliasedTransaction.paymentMethod,
                            cardOrBankName = aliasedTransaction.cardOrBankName.ifBlank { duplicate.cardOrBankName },
                            transferMemo = if (com.autologue.app.domain.model.TransactionDeduplicator.hasCustomerServicePhoneAsAccount(duplicate.transferMemo)) {
                                aliasedTransaction.transferMemo
                            } else {
                                aliasedTransaction.transferMemo ?: duplicate.transferMemo
                            }
                        )
                    )
                }
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

    override suspend fun updateTransactions(transactions: List<Transaction>) = withContext(Dispatchers.IO) {
        if (transactions.isEmpty()) return@withContext
        transactionDao.updateTransactions(transactions.map { it.toEntity() })
    }

    override suspend fun renameMerchantGlobally(
        oldMerchantName: String,
        newMerchantName: String,
        rawOriginalMerchantName: String?,
        isDeposit: Boolean,
        targetCategory: ExpenseCategory?,
        targetCardOrBankName: String?
    ): Int = withContext(Dispatchers.IO) {
        val targetName = MerchantAliasPreferences.sanitizeSingleLine(newMerchantName)
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
        val toUpdate = ArrayList<TransactionEntity>()
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
                toUpdate.add(
                    tx.copy(
                        merchantName = resolvedName.ifBlank { tx.merchantName },
                        category = resolvedCategory,
                        cardOrBankName = resolvedCard
                    )
                )
            }
        }
        if (toUpdate.isNotEmpty()) {
            transactionDao.updateTransactions(toUpdate)
        }
        toUpdate.size
    }

    override suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        deleteTransactionsByIds(listOf(id))
    }

    override suspend fun deleteTransactionsByIds(ids: Collection<Long>) = withContext(Dispatchers.IO) {
        val distinctIds = ids.distinct()
        if (distinctIds.isEmpty()) return@withContext
        val all = transactionDao.getAllTransactionsSync()
        val targetSet = distinctIds.toHashSet()
        val matchedEntities = all.filter { it.id in targetSet }
        if (matchedEntities.isNotEmpty()) {
            val allTwinEntities = ArrayList<TransactionEntity>(matchedEntities.size * 2)
            val seenIds = HashSet<Long>(targetSet)
            allTwinEntities.addAll(matchedEntities)

            for (target in matchedEntities) {
                val targetDomain = target.toDomain()
                val targetDate = target.timestamp.toLocalDate()
                val targetNormMer = MerchantAliasPreferences.normalizeMerchantKey(target.merchantName)
                val targetIsDep = targetDomain.isDepositTransaction()
                for (other in all) {
                    if (other.id in seenIds) continue
                    val otherDomain = other.toDomain()
                    if (other.amount != target.amount) continue
                    if (otherDomain.isDepositTransaction() != targetIsDep) continue
                    if (other.timestamp.toLocalDate() != targetDate) continue
                    val otherNormMer = MerchantAliasPreferences.normalizeMerchantKey(other.merchantName)
                    val sameMer = (targetNormMer.isNotBlank() && targetNormMer == otherNormMer) ||
                        merchantAliasPreferences.isSameMerchant(other.merchantName, target.merchantName) ||
                        (target.originalText.isNotBlank() && target.originalText == other.originalText)
                    if (sameMer) {
                        seenIds.add(other.id)
                        allTwinEntities.add(other)
                    }
                }
            }
            expenseCustomMetaPreferences.markTransactionsDeleted(allTwinEntities.map { it.toDomain() })
            seenIds.toList().chunked(500).forEach { chunk ->
                transactionDao.deleteTransactionsByIds(chunk)
            }
        } else {
            distinctIds.chunked(500).forEach { chunk ->
                transactionDao.deleteTransactionsByIds(chunk)
            }
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

            // DB에 저장된 결제명·카드/은행명·메모에 줄바꿈(\r, \n, \t)이나 연속 공백이 포함된 경우 1회성 저장 여부와 무관하게 즉시 단일 공백으로 정제
            val cleanDbMerchant = MerchantAliasPreferences.sanitizeSingleLine(rawTx.merchantName)
            val cleanDbCard = MerchantAliasPreferences.sanitizeSingleLine(rawTx.cardOrBankName)
            val cleanDbMemo = rawTx.transferMemo?.let { MerchantAliasPreferences.sanitizeSingleLine(it).ifBlank { null } }
            val sanitizedRawTx = if (cleanDbMerchant != rawTx.merchantName ||
                cleanDbCard != rawTx.cardOrBankName ||
                cleanDbMemo != rawTx.transferMemo
            ) {
                val healedLineBreakTx = rawTx.copy(
                    merchantName = cleanDbMerchant.ifBlank { rawTx.merchantName.trim() },
                    cardOrBankName = cleanDbCard,
                    transferMemo = cleanDbMemo
                )
                transactionDao.updateTransaction(healedLineBreakTx)
                healedLineBreakTx
            } else {
                rawTx
            }

            // 1회성 저장으로 수정된 항목은 가맹점명/카테고리/카드명 자동 치환 및 재분류에서 완전히 보호
            if (expenseCustomMetaPreferences.isOneTimeEdited(domainRawTx)) {
                continue
            }

            val isDeposit = domainRawTx.isDepositTransaction()

            // 1-0. 사용자가 등록한 결제처 별칭(입금/출금 방향별 자동 치환 규칙) 및 영구 카테고리/카드 설정 + 카드/은행 표준명 최우선 반영
            val baseMerForAlias = if (isDeposit && sanitizedRawTx.merchantName == "출금 내역") "입금 내역" else sanitizedRawTx.merchantName
            val aliasedMerchant = resolveMerchantWithAlias(baseMerForAlias, raw, sanitizedRawTx.cardOrBankName, sanitizedRawTx.timestamp, isDeposit)
            val savedBaseCategory = expenseCustomMetaPreferences.resolveMerchantBaseCategory(aliasedMerchant, isDeposit)
                ?: expenseCustomMetaPreferences.resolveMerchantBaseCategory(baseMerForAlias, isDeposit)
            val effectiveCat = savedBaseCategory
                ?: if (isDeposit && sanitizedRawTx.category == ExpenseCategory.TRANSFER) ExpenseCategory.INCOME else sanitizedRawTx.category
            val savedCard = expenseCustomMetaPreferences.resolveDisplayCardOrBankName(
                domainRawTx.copy(merchantName = aliasedMerchant.ifBlank { baseMerForAlias }, category = effectiveCat),
                fallbackMerchantName = baseMerForAlias
            )

            val tx = if ((aliasedMerchant.isNotBlank() && aliasedMerchant != sanitizedRawTx.merchantName) ||
                (effectiveCat != sanitizedRawTx.category) ||
                (!savedCard.isNullOrBlank() && savedCard != sanitizedRawTx.cardOrBankName)
            ) {
                val updated = sanitizedRawTx.copy(
                    merchantName = aliasedMerchant.ifBlank { baseMerForAlias },
                    category = effectiveCat,
                    cardOrBankName = savedCard?.ifBlank { sanitizedRawTx.cardOrBankName } ?: sanitizedRawTx.cardOrBankName
                )
                transactionDao.updateTransaction(updated)
                updated
            } else {
                sanitizedRawTx
            }
            val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(tx.merchantName, isDeposit) || savedBaseCategory != null

            val lacksExplicitApprovalDate = !isManual &&
                raw.isNotBlank() &&
                !com.autologue.app.data.parser.SmsParser.hasExplicitApprovalDate(raw) &&
                !(raw.contains("금액") && (raw.contains("사용처") || raw.contains("가맹점"))) &&
                !raw.contains("카카오페이") &&
                !raw.contains("토스") &&
                !raw.startsWith("[네이버")

            // 1-1. 비결제 문자(주식 시황, 광고, 뉴스, 누적/결제예정/총사용 안내, 승인일자 불명확 등) 또는 무효 가맹점명 레코드 정리
            if (!isManual && (
                    lacksExplicitApprovalDate ||
                    com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(raw) ||
                    com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(tx.merchantName) ||
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
            val isCardPlaceholderOrCsPhoneMemo = !isManual && (
                com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(tx.merchantName, tx.transferMemo) ||
                    com.autologue.app.domain.model.TransactionDeduplicator.hasCustomerServicePhoneAsAccount(tx.transferMemo)
                )

            if ((isCardMsg || isCardPlaceholderOrCsPhoneMemo) && (!isUserAliased || isCardPlaceholderOrCsPhoneMemo) && (
                    tx.paymentMethod == com.autologue.app.domain.model.PaymentMethod.BANK_TRANSFER ||
                    tx.category == ExpenseCategory.TRANSFER ||
                    tx.category == ExpenseCategory.ETC ||
                    isCardPlaceholderOrCsPhoneMemo ||
                    tx.merchantName in listOf("출금 내역", "입금 내역", "계좌 이체", "카드 가맹점", "가맹점", "정선우", "정*우")
                )
            ) {
                val reparsed = com.autologue.app.data.parser.SmsParser.parse(null, raw, tx.timestamp)
                    ?: com.autologue.app.data.parser.SmsParser.parse(tx.cardOrBankName, raw, tx.timestamp)
                if (reparsed != null &&
                    (!isCardPlaceholderOrCsPhoneMemo ||
                        com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                            reparsed.merchantName, reparsed.transferMemo
                        ) > com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                            tx.merchantName, tx.transferMemo
                        ))
                ) {
                    val reparsedDeposit = reparsed.isDepositTransaction()
                    val healedMerchant = resolveMerchantWithAlias(reparsed.merchantName, raw, reparsed.cardOrBankName.ifBlank { tx.cardOrBankName }, tx.timestamp, reparsedDeposit)
                    val healed = tx.copy(
                        amount = reparsed.amount,
                        merchantName = healedMerchant,
                        paymentMethod = reparsed.paymentMethod,
                        category = reparsed.category,
                        cardOrBankName = reparsed.cardOrBankName.ifBlank { tx.cardOrBankName },
                        transferMemo = reparsed.transferMemo,
                        isAutoCategorized = true
                    )
                    transactionDao.updateTransaction(healed)
                    continue
                } else if (com.autologue.app.domain.model.TransactionDeduplicator.hasCustomerServicePhoneAsAccount(tx.transferMemo)) {
                    val cleanedMemo = tx.transferMemo
                        ?.replace(Regex("""계좌\s*[:：]\s*(?:15|16|18)\d{2}-\d{4}(?:\s*·\s*)?"""), "")
                        ?.trim()
                        ?.ifBlank { null }
                    if (cleanedMemo != tx.transferMemo) {
                        transactionDao.updateTransaction(tx.copy(transferMemo = cleanedMemo))
                    }
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

        // 2. 다중 채널(문자 + 보험사 알림톡 + 은행 알림톡 + 체크출금 알림) 시간·금액·입출금 방향 기반 통합 중복 제거 및 상세 결제명 대표 항목 보존
        val refreshedAll = transactionDao.getAllTransactionsSync()
            .filterNot { it.id in toDeleteIds }
        val domainMap = refreshedAll.associateBy { it.id }
        val domainList = refreshedAll.map { it.toDomain() }

        val clusters = com.autologue.app.domain.model.TransactionDeduplicator.clusterDuplicates(
            transactions = domainList,
            isSameMerchantAlias = merchantAliasPreferences::isSameMerchant
        )

        for (cluster in clusters) {
            if (cluster.size <= 1) continue
            val winner = com.autologue.app.domain.model.TransactionDeduplicator.selectWinner(
                cluster = cluster,
                isOneTimeEditedProvider = expenseCustomMetaPreferences::isOneTimeEdited,
                isKnownAliasOrRuleProvider = ::isKnownAliasOrCustomRule
            )
            val winnerEntity = domainMap[winner.id]
            if (winnerEntity != null && (
                    winnerEntity.merchantName != winner.merchantName ||
                    winnerEntity.category != winner.category ||
                    winnerEntity.cardOrBankName != winner.cardOrBankName ||
                    winnerEntity.paymentMethod != winner.paymentMethod ||
                    winnerEntity.transferMemo != winner.transferMemo
                )
            ) {
                transactionDao.updateTransaction(
                    winnerEntity.copy(
                        merchantName = winner.merchantName,
                        category = winner.category,
                        cardOrBankName = winner.cardOrBankName,
                        paymentMethod = winner.paymentMethod,
                        transferMemo = winner.transferMemo
                    )
                )
            }
            for (member in cluster) {
                if (member.id != winner.id) {
                    toDeleteIds.add(member.id)
                }
            }
        }

        for (id in toDeleteIds) {
            transactionDao.deleteTransactionById(id)
        }

        toDeleteIds.size
    }

    private fun TransactionEntity.toDomain(): Transaction {
        val cleanMer = MerchantAliasPreferences.sanitizeSingleLine(merchantName)
        val cleanCard = MerchantAliasPreferences.sanitizeSingleLine(cardOrBankName)
        val cleanMemo = transferMemo?.let { MerchantAliasPreferences.sanitizeSingleLine(it).ifBlank { null } }
        val isManual = originalText.startsWith("[직접 입력]") || originalText.startsWith("[차계부 직접 입력]")

        if (!isManual && originalText.isNotBlank() && !expenseCustomMetaPreferences.isOneTimeEditedId(id) && (
                com.autologue.app.domain.model.TransactionDeduplicator.isGenericOrCardPlaceholderMerchant(cleanMer, cleanMemo) ||
                    com.autologue.app.domain.model.TransactionDeduplicator.hasCustomerServicePhoneAsAccount(cleanMemo)
            )
        ) {
            val reparsed = com.autologue.app.data.parser.SmsParser.parse(null, originalText, timestamp)
                ?: com.autologue.app.data.parser.SmsParser.parse(cleanCard, originalText, timestamp)
            if (reparsed != null &&
                com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                    reparsed.merchantName, reparsed.transferMemo
                ) > com.autologue.app.domain.model.TransactionDeduplicator.scoreMerchantDetailLevel(
                    cleanMer, cleanMemo
                )
            ) {
                return Transaction(
                    id = id,
                    amount = reparsed.amount,
                    merchantName = MerchantAliasPreferences.sanitizeSingleLine(reparsed.merchantName),
                    originalText = originalText,
                    timestamp = timestamp,
                    paymentMethod = reparsed.paymentMethod,
                    category = reparsed.category,
                    cardOrBankName = MerchantAliasPreferences.sanitizeSingleLine(reparsed.cardOrBankName.ifBlank { cleanCard }),
                    transferMemo = reparsed.transferMemo?.let { MerchantAliasPreferences.sanitizeSingleLine(it).ifBlank { null } },
                    isAutoCategorized = isAutoCategorized,
                    ruleIdApplied = ruleIdApplied
                )
            }
        }

        val sanitizedMemo = if (com.autologue.app.domain.model.TransactionDeduplicator.hasCustomerServicePhoneAsAccount(cleanMemo)) {
            cleanMemo?.replace(Regex("""계좌\s*[:：]\s*(?:15|16|18)\d{2}-\d{4}(?:\s*·\s*)?"""), "")?.trim()?.ifBlank { null }
        } else {
            cleanMemo
        }

        return Transaction(
            id = id,
            amount = amount,
            merchantName = cleanMer,
            originalText = originalText,
            timestamp = timestamp,
            paymentMethod = paymentMethod,
            category = category,
            cardOrBankName = cleanCard,
            transferMemo = sanitizedMemo,
            isAutoCategorized = isAutoCategorized,
            ruleIdApplied = ruleIdApplied
        )
    }

    private fun Transaction.toEntity() = TransactionEntity(
        id = id,
        amount = amount,
        merchantName = MerchantAliasPreferences.sanitizeSingleLine(merchantName),
        originalText = originalText,
        timestamp = timestamp,
        paymentMethod = paymentMethod,
        category = category,
        cardOrBankName = MerchantAliasPreferences.sanitizeSingleLine(cardOrBankName),
        transferMemo = transferMemo?.let { MerchantAliasPreferences.sanitizeSingleLine(it).ifBlank { null } },
        isAutoCategorized = isAutoCategorized,
        ruleIdApplied = ruleIdApplied
    )
}
