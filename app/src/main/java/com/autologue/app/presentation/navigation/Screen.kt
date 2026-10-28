package com.autologue.app.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountBalanceWallet
import androidx.compose.material.icons.filled.Collections
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Insights
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
    object Lifestyle : Screen("lifestyle", "라이프 궤도", Icons.Default.Insights, MenuColors.lifestyle)
}
