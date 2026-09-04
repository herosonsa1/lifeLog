package com.autologue.app.domain.model

data class TransactionRule(
    val id: Long = 0,
    val keywordPattern: String,
    val targetCategory: ExpenseCategory,
    val customTag: String? = null,
    val isRegex: Boolean = false,
    val priority: Int = 0,
    val usageCount: Int = 0
)
