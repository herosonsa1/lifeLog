package com.autologue.app.presentation.navigation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.autologue.app.presentation.car.CarLedgerScreen
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.UnifiedSpeedDialFab
import com.autologue.app.presentation.diary.DiaryScreen
import com.autologue.app.presentation.expense.ExpenseScreen
import com.autologue.app.presentation.golf.GolfScreen
import com.autologue.app.presentation.theme.*

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val items = listOf(
        Screen.Diary,
        Screen.Expense,
        Screen.CarLedger,
        Screen.Golf
    )

    Scaffold(
        floatingActionButton = {
            UnifiedSpeedDialFab(navController = navController)
        },
        floatingActionButtonPosition = FabPosition.End,
        bottomBar = {
            Column {
                HairlineDivider()
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.surface)
                        .padding(top = Spacing.xs, bottom = Spacing.xxs),
                    horizontalArrangement = Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val navBackStackEntry by navController.currentBackStackEntryAsState()
                    val currentRoute = navBackStackEntry?.destination?.route

                    items.forEach { screen ->
                        val isSelected = currentRoute == screen.route
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (currentRoute != screen.route) {
                                        navController.navigate(screen.route) {
                                            popUpTo(navController.graph.findStartDestination().id) {
                                                saveState = true
                                            }
                                            launchSingleTop = true
                                            restoreState = true
                                        }
                                    }
                                }
                                .padding(vertical = Spacing.xs),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Icon(
                                imageVector = screen.icon,
                                contentDescription = screen.title,
                                modifier = Modifier.size(20.dp),
                                tint = if (isSelected) screen.themeColor else AppColors.textMuted
                            )
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            Text(
                                text = screen.title,
                                fontSize = 10.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                color = if (isSelected) screen.themeColor else AppColors.textMuted
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // 메뉴별 고유 색상 언더라인 인디케이터
                            Box(
                                modifier = Modifier
                                    .height(3.dp)
                                    .width(if (isSelected) 24.dp else 12.dp)
                                    .clip(RoundedCornerShape(1.5.dp))
                                    .background(
                                        if (isSelected) screen.themeColor
                                        else screen.themeColor.copy(alpha = 0.18f)
                                    )
                            )
                        }
                    }
                }
            }
        }
    )
 { paddingValues ->
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
}
