package com.autologue.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.vector.ImageVector

sealed class Screen(val route: String, val title: String, val icon: ImageVector) {
    object Diary : Screen("diary", "다이어리", Icons.Default.Collections)
    object Expense : Screen("expense", "가계부", Icons.Default.AccountBalanceWallet)
    object CarLedger : Screen("car_ledger", "차계부", Icons.Default.DirectionsCar)
    object Golf : Screen("golf", "골프", Icons.Default.SportsGolf)
}
