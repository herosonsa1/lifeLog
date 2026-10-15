package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.TransactionDao
import com.autologue.app.data.local.entity.TransactionEntity
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.repository.TransactionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.ZoneId
import javax.inject.Inject

class TransactionRepositoryImpl @Inject constructor(
    private val transactionDao: TransactionDao
) : TransactionRepository {

    override fun getAllTransactionsFlow(): Flow<List<Transaction>> {
        return transactionDao.getAllTransactions().map { entities -> entities.map { it.toDomain() } }
    }

    override fun getTransactionsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Transaction>> {
        val startMilli = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        return transactionDao.getTransactionsByDateRange(startMilli, endMilli).map { it.map { e -> e.toDomain() } }
    }

    override fun getTransactionsByCategory(category: ExpenseCategory): Flow<List<Transaction>> {
        return transactionDao.getTransactionsByCategory(category).map { it.map { e -> e.toDomain() } }
    }

    override suspend fun insertTransaction(transaction: Transaction): Long = withContext(Dispatchers.IO) {
        val timestampMillis = transaction.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

        if (transaction.originalText.isNotBlank()) {
            val existingByText = transactionDao.findByOriginalText(transaction.originalText)
            if (existingByText != null) {
                return@withContext existingByText.id
            }
        }

        // 5분 윈도우 기반 지능형 중복 방지 (동일 금액 + ±5분 이내 동일/유사 가맹점)
        val windowMilli = 5 * 60 * 1000L
        val candidates = transactionDao.findTransactionsByAmountAndDateRange(
            amount = transaction.amount,
            start = timestampMillis - windowMilli,
            end = timestampMillis + windowMilli
        )

        val duplicate = candidates.firstOrNull { existing ->
            val isExactMerchant = existing.merchantName == transaction.merchantName
            val isSimilarMerchant = existing.merchantName.isNotBlank() && transaction.merchantName.isNotBlank() &&
                    (existing.merchantName.contains(transaction.merchantName) || transaction.merchantName.contains(existing.merchantName))
            val existingMillis = existing.timestamp.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
            val timeDiff = Math.abs(existingMillis - timestampMillis)

            isExactMerchant || (isSimilarMerchant && timeDiff <= 3 * 60 * 1000L)
        }

        if (duplicate != null) {
            return@withContext duplicate.id
        }

        val existing = transactionDao.findExactDuplicate(transaction.amount, transaction.merchantName, transaction.timestamp)
        if (existing != null) {
            return@withContext existing.id
        }

        transactionDao.insertTransaction(transaction.toEntity())
    }

    override suspend fun updateTransaction(transaction: Transaction) = withContext(Dispatchers.IO) {
        transactionDao.updateTransaction(transaction.toEntity())
    }

    override suspend fun deleteTransaction(id: Long) = withContext(Dispatchers.IO) {
        transactionDao.deleteTransactionById(id)
    }

    override suspend fun getTotalExpenseBetween(start: LocalDateTime, end: LocalDateTime): Long = withContext(Dispatchers.IO) {
        val startMilli = start.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = end.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        transactionDao.getTotalExpenseBetween(startMilli, endMilli)
    }

    override suspend fun cleanDuplicates(): Int = withContext(Dispatchers.IO) {
        val all = transactionDao.getAllTransactionsSync()
        val toDeleteIds = mutableSetOf<Long>()

        // 1. 기존 DB 내 오분류된 카드 승인 및 주유 내역 자가 치유 (Self-Healing)
        for (tx in all) {
            val raw = tx.originalText
            val isManual = raw.startsWith("[직접 입력]") || raw.startsWith("[차계부 직접 입력]")
            val isCardMsg = !isManual && com.autologue.app.data.parser.SmsParser.isCardApprovalMessage(raw)

            if (isCardMsg && (
                    tx.paymentMethod == com.autologue.app.domain.model.PaymentMethod.BANK_TRANSFER ||
                    tx.category == ExpenseCategory.TRANSFER ||
                    tx.category == ExpenseCategory.ETC ||
                    tx.merchantName in listOf("출금 내역", "계좌 이체", "카드 가맹점", "가맹점", "정선우", "정*우")
                )
            ) {
                val reparsed = com.autologue.app.data.parser.SmsParser.parse(tx.cardOrBankName, raw, tx.timestamp)
                    ?: com.autologue.app.data.parser.SmsParser.parse(null, raw, tx.timestamp)
                if (reparsed != null) {
                    val healed = tx.copy(
                        amount = reparsed.amount,
                        merchantName = reparsed.merchantName,
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

            // 주유 키워드가 상호명 또는 원문에 포함된 거래가 주유 외 카테고리로 잘못 분류된 경우 복원
            val isFuel = VehicleRepositoryImpl.isFuelMerchant(tx.merchantName) ||
                (!isManual && VehicleRepositoryImpl.isFuelMerchant(raw))
            if (isFuel && tx.category != ExpenseCategory.FUEL) {
                val healedCategory = tx.copy(category = ExpenseCategory.FUEL, isAutoCategorized = true)
                transactionDao.updateTransaction(healedCategory)
                continue
            }

            // 순수 은행 이체/출금 내역만 TRANSFER로 분류 (카드 승인 문자는 제외)
            if (!isCardMsg && tx.category == ExpenseCategory.ETC) {
                val fullText = "${tx.merchantName} ${tx.originalText} ${tx.transferMemo ?: ""}"
                val hasTransferKeyword = fullText.contains("출금") || fullText.contains("송금") || fullText.contains("이체")
                if (hasTransferKeyword) {
                    val updated = tx.copy(category = ExpenseCategory.TRANSFER, isAutoCategorized = true)
                    transactionDao.updateTransaction(updated)
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
