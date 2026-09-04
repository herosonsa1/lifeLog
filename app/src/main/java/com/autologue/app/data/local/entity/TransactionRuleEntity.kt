package com.autologue.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.autologue.app.domain.model.ExpenseCategory

@Entity(tableName = "transaction_rules")
data class TransactionRuleEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val keywordPattern: String,
    val targetCategory: ExpenseCategory,
    val customTag: String?,
    val isRegex: Boolean,
    val priority: Int,
    val usageCount: Int
)
