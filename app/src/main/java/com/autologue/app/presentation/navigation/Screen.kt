package com.autologue.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import com.autologue.app.presentation.theme.MenuColors

sealed class Screen(
    val route: String,
    val title: String,
    val icon: ImageVector,
    val themeColor: Color
) {
    object Diary : Screen("diary", "다이어리", Icons.Default.Collections, MenuColors.diary)
    object Expense : Screen("expense", "가계부", Icons.Default.AccountBalanceWallet, MenuColors.expense)
    object CarLedger : Screen("car_ledger", "차계부", Icons.Default.DirectionsCar, MenuColors.carLedger)
    object Golf : Screen("golf", "골프", Icons.Default.SportsGolf, MenuColors.golf)
}
