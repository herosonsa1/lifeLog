package com.autologue.app.domain.usecase.expense

import com.autologue.app.domain.model.TransactionRule
import com.autologue.app.domain.repository.TransactionRuleRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

class ManageTransactionRulesUseCase @Inject constructor(
    private val ruleRepository: TransactionRuleRepository
) {
    fun getAllRules(): Flow<List<TransactionRule>> = ruleRepository.getAllRulesFlow()
    suspend fun addRule(rule: TransactionRule): Long = ruleRepository.insertRule(rule)
    suspend fun deleteRule(ruleId: Long) = ruleRepository.deleteRule(ruleId)
}
