package com.autologue.app.domain.usecase.expense

import com.autologue.app.data.preferences.MerchantAliasPreferences
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.TransactionRuleRepository
import com.autologue.app.domain.repository.VehicleRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ProcessTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val ruleRepository: TransactionRuleRepository,
    private val vehicleRepository: VehicleRepository,
    private val merchantAliasPreferences: MerchantAliasPreferences
) {
    constructor(
        transactionRepository: TransactionRepository,
        ruleRepository: TransactionRuleRepository,
        vehicleRepository: VehicleRepository
    ) : this(transactionRepository, ruleRepository, vehicleRepository, MerchantAliasPreferences())

    suspend operator fun invoke(transaction: Transaction): Long {
        val rawMerchant = transaction.merchantName.trim()
        val aliasedMerchant = merchantAliasPreferences.resolveAlias(rawMerchant).ifBlank { rawMerchant }

        // "네이버페이" 항목은 모든 이력 등록 대상에서 원천 제외
        if (transaction.paymentMethod == PaymentMethod.NAVER_PAY ||
            com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                merchantName = rawMerchant,
                cardOrBankName = transaction.cardOrBankName,
                originalText = transaction.originalText
            ) ||
            com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                merchantName = aliasedMerchant,
                cardOrBankName = transaction.cardOrBankName,
                originalText = transaction.originalText
            )
        ) {
            return -1L
        }

        val rules = ruleRepository.getAllRules()
        var finalCategory = transaction.category
        var matchedRuleId: Long? = null

        val targetSearchText = "$aliasedMerchant $rawMerchant ${transaction.transferMemo ?: ""}".trim()

        for (rule in rules) {
            val matched = if (rule.isRegex) {
                runCatching { Regex(rule.keywordPattern).containsMatchIn(targetSearchText) }.getOrDefault(false)
            } else {
                targetSearchText.contains(rule.keywordPattern, ignoreCase = true)
            }

            if (matched) {
                finalCategory = rule.targetCategory
                matchedRuleId = rule.id
                ruleRepository.incrementRuleUsage(rule.id)
                break
            }
        }

        val enrichedTx = transaction.copy(
            merchantName = aliasedMerchant,
            category = finalCategory,
            ruleIdApplied = matchedRuleId,
            isAutoCategorized = matchedRuleId != null || finalCategory != ExpenseCategory.ETC
        )

        val isCancellation = enrichedTx.amount < 0 || enrichedTx.transferMemo?.contains("승인취소") == true
        val txId = transactionRepository.insertTransaction(enrichedTx)
        if (txId <= 0L) {
            return -1L
        }

        val isFuel = finalCategory == ExpenseCategory.FUEL ||
            com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(rawMerchant) ||
            com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(enrichedTx.merchantName) ||
            com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(enrichedTx.originalText) ||
            merchantAliasPreferences.getOriginalNamesForTarget(enrichedTx.merchantName).any {
                com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(it)
            }

        if (isFuel) {
            if (enrichedTx.category != ExpenseCategory.FUEL) {
                transactionRepository.updateTransaction(enrichedTx.copy(id = txId, category = ExpenseCategory.FUEL))
            }

            if (isCancellation) {
                val cancelAmt = Math.abs(enrichedTx.amount)
                vehicleRepository.deleteRefuelingLogByTransaction(cancelAmt, enrichedTx.timestamp.toLocalDate(), enrichedTx.merchantName)
            }

            runCatching {
                val allTxs = transactionRepository.getAllTransactionsFlow().first()
                vehicleRepository.syncRefuelingFromTransactions(allTxs)
            }
        }

        return txId
    }
}
