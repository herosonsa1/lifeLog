package com.autologue.app.presentation.navigation

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import com.autologue.app.presentation.car.CarLedgerScreen
import com.autologue.app.presentation.diary.DiaryScreen
import com.autologue.app.presentation.expense.ExpenseScreen
import com.autologue.app.presentation.golf.GolfScreen

@Composable
fun AutoLogueNavGraph(
    navController: NavHostController,
    paddingValues: PaddingValues
) {
    NavHost(
        navController = navController,
        startDestination = Screen.Diary.route,
        modifier = Modifier.padding(paddingValues)
    ) {
        composable(Screen.Diary.route) { DiaryScreen() }
        composable(Screen.Expense.route) { ExpenseScreen() }
        composable(Screen.CarLedger.route) { CarLedgerScreen() }
        composable(Screen.Golf.route) { GolfScreen() }
    }
}
