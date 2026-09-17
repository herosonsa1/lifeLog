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

            isExactMerchant || (isSimilarMerchant && timeDiff <= 3 * 60 * 1000L) ||
                    (existing.category == transaction.category && timeDiff <= 2 * 60 * 1000L)
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

        // 1. 기존 DB 내 오분류된 이체/출금 카테고리 자동 교정
        for (tx in all) {
            if (tx.category == ExpenseCategory.ETC) {
                val fullText = "${tx.merchantName} ${tx.originalText} ${tx.transferMemo ?: ""}"
                val hasTransferKeyword = fullText.contains("출금") || fullText.contains("송금") ||
                        fullText.contains("이체") || Regex("""\d{3,}[-\d*]{5,}""").containsMatchIn(fullText)
                if (hasTransferKeyword) {
                    val updated = tx.copy(category = ExpenseCategory.TRANSFER, isAutoCategorized = true)
                    transactionDao.updateTransaction(updated)
                }
            }
        }

        // 2. 퍼지 시간 윈도우 중복 탐지 (동일 금액 + 10분 이내 + 동일/유사 가맹점)
        val sortedList = all.sortedBy { it.id }
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
