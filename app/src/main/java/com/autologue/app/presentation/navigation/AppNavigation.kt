package com.autologue.app.presentation.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.*
import com.autologue.app.presentation.car.CarLedgerScreen
import com.autologue.app.presentation.common.AutoLogueLoadingOverlay
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.UnifiedSpeedDialFab
import com.autologue.app.presentation.diary.DiaryScreen
import com.autologue.app.presentation.expense.ExpenseScreen
import com.autologue.app.presentation.golf.GolfScreen
import com.autologue.app.presentation.lifestyle.LifestyleScreen
import com.autologue.app.presentation.theme.*

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var pendingScreen by remember { mutableStateOf<Screen?>(null) }
    var isMenuTransitioning by remember { mutableStateOf(false) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var exitToast by remember { mutableStateOf<Toast?>(null) }

    val effectiveRoute = pendingScreen?.route ?: currentRoute

    // 메뉴 탭 클릭 시 먼저 하단 탭 선택 상태와 로딩 바를 즉시 렌더링한 뒤 네비게이션을 수행하여 멈춘 느낌 원천 해소
    LaunchedEffect(pendingScreen) {
        val target = pendingScreen ?: return@LaunchedEffect
        if (currentRoute == target.route) {
            pendingScreen = null
            isMenuTransitioning = false
            return@LaunchedEffect
        }
        isMenuTransitioning = true
        // 1프레임 양보하여 하단 탭 하이라이트 및 상단 진행 바가 0ms 즉시 그려지도록 보장
        withFrameNanos { }
        runCatching {
            navController.navigate(target.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
        withFrameNanos { }
        pendingScreen = null
        isMenuTransitioning = false
    }

    // 스마트 다이어리(홈) 메뉴 상태에서 뒤로가기 1회 시 토스트 알림, 2초 내 한 번 더 누르면 앱 완전 종료
    BackHandler(enabled = effectiveRoute == Screen.Diary.route || effectiveRoute == null) {
        val now = System.currentTimeMillis()
        if (now - lastBackPressTime < 2000L) {
            exitToast?.cancel()
            (context as? Activity)?.finishAffinity()
        } else {
            lastBackPressTime = now
            exitToast?.cancel()
            exitToast = Toast.makeText(
                context,
                "뒤로가기 버튼을 한 번 더 누르면 앱이 종료됩니다.",
                Toast.LENGTH_SHORT
            ).also { it.show() }
        }
    }

    val items = listOf(
        Screen.Diary,
        Screen.Expense,
        Screen.CarLedger,
        Screen.Golf,
        Screen.Lifestyle
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

                    items.forEach { screen ->
                        val isSelected = effectiveRoute == screen.route
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clickable {
                                    if (effectiveRoute != screen.route) {
                                        pendingScreen = screen
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
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            NavHost(
                navController = navController,
                startDestination = Screen.Diary.route,
                enterTransition = { fadeIn(animationSpec = tween(140)) },
                exitTransition = { fadeOut(animationSpec = tween(100)) },
                popEnterTransition = { fadeIn(animationSpec = tween(140)) },
                popExitTransition = { fadeOut(animationSpec = tween(100)) },
                modifier = Modifier.fillMaxSize()
            ) {
                composable(Screen.Diary.route) { DiaryScreen() }
                composable(Screen.Expense.route) { ExpenseScreen() }
                composable(Screen.CarLedger.route) { CarLedgerScreen() }
                composable(Screen.Golf.route) { GolfScreen() }
                composable(Screen.Lifestyle.route) { LifestyleScreen() }
            }

            val activeScreenObj = items.find { it.route == effectiveRoute } ?: Screen.Diary
            AutoLogueLoadingOverlay(
                isLoading = isMenuTransitioning,
                title = "${activeScreenObj.title} 화면으로 이동 중",
                message = "${activeScreenObj.title} 데이터를 불러오고 있습니다...",
                accentColor = activeScreenObj.themeColor,
                delayMillis = 500L,
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

