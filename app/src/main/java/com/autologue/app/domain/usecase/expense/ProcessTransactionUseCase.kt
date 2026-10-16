package com.autologue.app.domain.usecase.expense

import com.autologue.app.data.preferences.MerchantAliasPreferences
import com.autologue.app.domain.model.*
import com.autologue.app.domain.repository.*
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import javax.inject.Inject

class ProcessTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val ruleRepository: TransactionRuleRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfRepository: GolfRepository,
    private val merchantAliasPreferences: MerchantAliasPreferences
) {
    constructor(
        transactionRepository: TransactionRepository,
        ruleRepository: TransactionRuleRepository,
        vehicleRepository: VehicleRepository,
        golfRepository: GolfRepository
    ) : this(transactionRepository, ruleRepository, vehicleRepository, golfRepository, MerchantAliasPreferences())

    suspend operator fun invoke(transaction: Transaction): Long {
        val rawMerchant = transaction.merchantName.trim()
        val aliasedMerchant = merchantAliasPreferences.resolveAlias(rawMerchant).ifBlank { rawMerchant }

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
                // 승인취소 시 해당 금액의 차계부 가승인 주유 로그 즉시 삭제
                val cancelAmt = Math.abs(enrichedTx.amount)
                vehicleRepository.deleteRefuelingLogByTransaction(cancelAmt, enrichedTx.timestamp.toLocalDate(), enrichedTx.merchantName)
            }

            // 순서 무관(과거 스캔 시 역순 처리 포함) 가승인 15만원 상쇄 및 실주유 내역 정합성 보장을 위해 전체 트랜잭션 기준 동기화 수행
            runCatching {
                val allTxs = transactionRepository.getAllTransactionsFlow().first()
                vehicleRepository.syncRefuelingFromTransactions(allTxs)
            }
        }

        if (finalCategory == ExpenseCategory.GOLF_FIELD || finalCategory == ExpenseCategory.GOLF_SCREEN) {
            val golfType = if (finalCategory == ExpenseCategory.GOLF_FIELD) GolfType.FIELD else GolfType.SCREEN
            val existingRound = golfRepository.getGolfRoundByDate(enrichedTx.timestamp.toLocalDate())

            if (existingRound == null) {
                val round = GolfRound(
                    clubName = enrichedTx.merchantName,
                    roundDate = enrichedTx.timestamp,
                    golfType = golfType,
                    greenFeeExpense = enrichedTx.amount,
                    memo = "결제 자동 감지: ${enrichedTx.merchantName}"
                )
                golfRepository.insertGolfRound(round)
            }
        }

        return txId
    }
}
