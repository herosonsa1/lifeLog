package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.TransactionRuleDao
import com.autologue.app.data.local.entity.TransactionRuleEntity
import com.autologue.app.domain.model.TransactionRule
import com.autologue.app.domain.repository.TransactionRuleRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import javax.inject.Inject

class TransactionRuleRepositoryImpl @Inject constructor(
    private val ruleDao: TransactionRuleDao
) : TransactionRuleRepository {

    override fun getAllRulesFlow(): Flow<List<TransactionRule>> {
        return ruleDao.getAllRulesFlow().map { it.map { e -> e.toDomain() } }
    }

    override suspend fun getAllRules(): List<TransactionRule> = withContext(Dispatchers.IO) {
        ruleDao.getAllRules().map { it.toDomain() }
    }

    override suspend fun insertRule(rule: TransactionRule): Long = withContext(Dispatchers.IO) {
        ruleDao.insertRule(rule.toEntity())
    }

    override suspend fun updateRule(rule: TransactionRule) = withContext(Dispatchers.IO) {
        ruleDao.updateRule(rule.toEntity())
    }

    override suspend fun deleteRule(id: Long) = withContext(Dispatchers.IO) {
        ruleDao.deleteRuleById(id)
    }

    override suspend fun incrementRuleUsage(ruleId: Long) = withContext(Dispatchers.IO) {
        ruleDao.incrementRuleUsage(ruleId)
    }

    private fun TransactionRuleEntity.toDomain() = TransactionRule(
        id = id, keywordPattern = keywordPattern, targetCategory = targetCategory,
        customTag = customTag, isRegex = isRegex, priority = priority, usageCount = usageCount
    )

    private fun TransactionRule.toEntity() = TransactionRuleEntity(
        id = id, keywordPattern = keywordPattern, targetCategory = targetCategory,
        customTag = customTag, isRegex = isRegex, priority = priority, usageCount = usageCount
    )
}
