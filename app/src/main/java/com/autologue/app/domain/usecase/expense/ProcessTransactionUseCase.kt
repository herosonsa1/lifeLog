package com.autologue.app.domain.usecase.expense

import com.autologue.app.domain.model.*
import com.autologue.app.domain.repository.*
import java.time.LocalDate
import javax.inject.Inject

class ProcessTransactionUseCase @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val ruleRepository: TransactionRuleRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfRepository: GolfRepository
) {
    suspend operator fun invoke(transaction: Transaction): Long {
        val rules = ruleRepository.getAllRules()
        var finalCategory = transaction.category
        var matchedRuleId: Long? = null

        val targetSearchText = "${transaction.merchantName} ${transaction.transferMemo ?: ""}".trim()

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
            category = finalCategory,
            ruleIdApplied = matchedRuleId,
            isAutoCategorized = matchedRuleId != null || finalCategory != ExpenseCategory.ETC
        )

        val txId = transactionRepository.insertTransaction(enrichedTx)

        if (finalCategory == ExpenseCategory.FUEL) {
            val lastFuel = vehicleRepository.getLatestRefuelingLog()
            val daysSince = if (lastFuel != null) {
                java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), enrichedTx.timestamp.toLocalDate()).toInt()
            } else null

            val vehicleLog = VehicleLog(
                timestamp = enrichedTx.timestamp,
                logType = VehicleLogType.REFUELING,
                fuelCost = enrichedTx.amount,
                fuelAmountLiters = if (enrichedTx.amount > 0) enrichedTx.amount / 1650.0 else 0.0,
                daysSinceLastFuel = daysSince,
                gasStationName = enrichedTx.merchantName,
                note = "가계부 결제 연동 자동 기록"
            )
            vehicleRepository.insertVehicleLog(vehicleLog)
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
