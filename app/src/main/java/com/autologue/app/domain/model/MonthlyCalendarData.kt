package com.autologue.app.domain.model

import java.time.LocalDate
import java.time.YearMonth

data class DaySummary(
    val date: LocalDate,
    val totalExpense: Long = 0L,
    val hasGolfRound: Boolean = false,
    val hasRefueling: Boolean = false,
    val photoCount: Int = 0,
    val entryCount: Int = 0
)

data class MonthlySummary(
    val yearMonth: YearMonth,
    val totalExpense: Long = 0L,
    val totalGolfRounds: Int = 0,
    val totalDistanceKm: Double = 0.0,
    val days: Map<LocalDate, DaySummary> = emptyMap()
)
