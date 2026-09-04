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
        val seen = mutableSetOf<String>()
        var deletedCount = 0
        for (tx in all) {
            val key = if (tx.originalText.isNotBlank()) {
                tx.originalText.trim()
            } else {
                "${tx.timestamp}_${tx.amount}_${tx.merchantName}"
            }
            if (key in seen) {
                transactionDao.deleteTransactionById(tx.id)
                deletedCount++
            } else {
                seen.add(key)
            }
        }
        deletedCount
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
