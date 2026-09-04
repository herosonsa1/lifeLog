package com.autologue.app.domain.repository

import com.autologue.app.domain.model.TransactionRule
import kotlinx.coroutines.flow.Flow

interface TransactionRuleRepository {
    fun getAllRulesFlow(): Flow<List<TransactionRule>>
    suspend fun getAllRules(): List<TransactionRule>
    suspend fun insertRule(rule: TransactionRule): Long
    suspend fun updateRule(rule: TransactionRule)
    suspend fun deleteRule(id: Long)
    suspend fun incrementRuleUsage(ruleId: Long)
}
