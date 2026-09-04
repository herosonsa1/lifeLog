package com.autologue.app.data.local.db.dao

import androidx.room.*
import com.autologue.app.data.local.entity.TransactionRuleEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface TransactionRuleDao {
    @Query("SELECT * FROM transaction_rules ORDER BY priority DESC, usageCount DESC")
    fun getAllRulesFlow(): Flow<List<TransactionRuleEntity>>

    @Query("SELECT * FROM transaction_rules ORDER BY priority DESC, usageCount DESC")
    suspend fun getAllRules(): List<TransactionRuleEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRule(rule: TransactionRuleEntity): Long

    @Update
    suspend fun updateRule(rule: TransactionRuleEntity)

    @Query("DELETE FROM transaction_rules WHERE id = :id")
    suspend fun deleteRuleById(id: Long)

    @Query("UPDATE transaction_rules SET usageCount = usageCount + 1 WHERE id = :ruleId")
    suspend fun incrementRuleUsage(ruleId: Long)
}
