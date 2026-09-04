package com.autologue.app.domain.repository

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import java.time.LocalDateTime

interface TransactionRepository {
    fun getAllTransactionsFlow(): Flow<List<Transaction>>
    fun getTransactionsByDateRange(start: LocalDateTime, end: LocalDateTime): Flow<List<Transaction>>
    fun getTransactionsByCategory(category: ExpenseCategory): Flow<List<Transaction>>
    suspend fun insertTransaction(transaction: Transaction): Long
    suspend fun updateTransaction(transaction: Transaction)
    suspend fun deleteTransaction(id: Long)
    suspend fun getTotalExpenseBetween(start: LocalDateTime, end: LocalDateTime): Long
    suspend fun cleanDuplicates(): Int
}
