package com.autologue.app.data.local.db.dao

import androidx.room.*
import com.autologue.app.data.local.entity.TransactionEntity
import com.autologue.app.domain.model.ExpenseCategory
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionDao {
    @Query("SELECT * FROM transactions ORDER BY timestamp DESC")
    fun getAllTransactions(): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE timestamp BETWEEN :start AND :end ORDER BY timestamp DESC")
    fun getTransactionsByDateRange(start: Long, end: Long): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE category = :category ORDER BY timestamp DESC")
    fun getTransactionsByCategory(category: ExpenseCategory): Flow<List<TransactionEntity>>

    @Query("SELECT * FROM transactions WHERE amount = :amount AND merchantName = :merchantName AND timestamp = :timestamp LIMIT 1")
    suspend fun findExactDuplicate(amount: Long, merchantName: String, timestamp: java.time.LocalDateTime): TransactionEntity?

    @Query("SELECT * FROM transactions WHERE originalText = :originalText AND originalText != '' LIMIT 1")
    suspend fun findByOriginalText(originalText: String): TransactionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTransaction(transaction: TransactionEntity): Long

    @Update
    suspend fun updateTransaction(transaction: TransactionEntity)

    @Query("DELETE FROM transactions WHERE id = :id")
    suspend fun deleteTransactionById(id: Long)

    @Query("SELECT COALESCE(SUM(amount), 0) FROM transactions WHERE timestamp BETWEEN :start AND :end")
    suspend fun getTotalExpenseBetween(start: Long, end: Long): Long

    @Query("SELECT * FROM transactions ORDER BY id ASC")
    suspend fun getAllTransactionsSync(): List<TransactionEntity>
}
