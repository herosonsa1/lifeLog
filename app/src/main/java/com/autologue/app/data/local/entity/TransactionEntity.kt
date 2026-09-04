package com.autologue.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import java.time.LocalDateTime

@Entity(tableName = "transactions")
data class TransactionEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val amount: Long,
    val merchantName: String,
    val originalText: String,
    val timestamp: LocalDateTime,
    val paymentMethod: PaymentMethod,
    val category: ExpenseCategory,
    val cardOrBankName: String,
    val transferMemo: String?,
    val isAutoCategorized: Boolean,
    val ruleIdApplied: Long?
)
