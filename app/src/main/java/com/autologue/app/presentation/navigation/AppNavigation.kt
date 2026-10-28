package com.autologue.app.presentation.navigation

import android.app.Activity
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FabPosition
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.autologue.app.presentation.car.CarLedgerScreen
import com.autologue.app.presentation.common.AppSettingsBottomSheet
import com.autologue.app.presentation.common.EasyModeManager
import com.autologue.app.presentation.common.EasyTheme
import com.autologue.app.presentation.common.GlobalLoadingOverlayHost
import com.autologue.app.presentation.common.GlobalLoadingRegistry
import com.autologue.app.presentation.common.GlobalSyncProgressBannerHost
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.LocalEasyMode
import com.autologue.app.presentation.common.UnifiedSpeedDialFab
import com.autologue.app.presentation.diary.DiaryScreen
import com.autologue.app.presentation.expense.ExpenseScreen
import com.autologue.app.presentation.lifestyle.LifestyleScreen
import com.autologue.app.presentation.theme.AppColors
import com.autologue.app.presentation.theme.Spacing

@Composable
fun AppNavigation(navController: NavHostController = rememberNavController()) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        EasyModeManager.ensureInitialized(context)
    }
    val isEasyMode by EasyModeManager.isEasyMode.collectAsState()
    val isSettingsSheetOpen by EasyModeManager.isSettingsSheetOpen.collectAsState()

    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route
    var pendingScreen by remember { mutableStateOf<Screen?>(null) }
    val visitedRoutes = remember { mutableStateListOf(Screen.Diary.route) }
    var lastBackPressTime by remember { mutableLongStateOf(0L) }
    var exitToast by remember { mutableStateOf<Toast?>(null) }

    val effectiveRoute = pendingScreen?.route ?: currentRoute

    LaunchedEffect(pendingScreen) {
        val target = pendingScreen ?: return@LaunchedEffect
        if (currentRoute == target.route) {
            pendingScreen = null
            GlobalLoadingRegistry.remove("app_nav_transition")
            return@LaunchedEffect
        }
        val isFirstVisit = !visitedRoutes.contains(target.route)
        GlobalLoadingRegistry.upsert(
            id = "app_nav_transition",
            title = "데이터 처리 및 화면 갱신 중",
            message = "${target.title} 화면과 데이터를 준비하고 있습니다...",
            accentColor = target.themeColor,
            immediateOverlay = isFirstVisit
        )
        withFrameNanos { }
        if (isFirstVisit) {
            withFrameNanos { }
        }
        runCatching {
            navController.navigate(target.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
        if (!visitedRoutes.contains(target.route)) {
            visitedRoutes.add(target.route)
        }
        withFrameNanos { }
        withFrameNanos { }
        pendingScreen = null
        GlobalLoadingRegistry.remove("app_nav_transition")
    }

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
        Screen.Lifestyle
    )

    CompositionLocalProvider(LocalEasyMode provides isEasyMode) {
        Box(modifier = Modifier.fillMaxSize()) {
            Scaffold(
                contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                floatingActionButton = {
                    UnifiedSpeedDialFab(navController = navController)
                },
                floatingActionButtonPosition = FabPosition.End,
                bottomBar = {
                    val rawNavBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
                    val bottomSafeMargin = if (rawNavBottom >= 32.dp) rawNavBottom else 12.dp

                    if (isEasyMode) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(EasyTheme.surface)
                        ) {
                            HorizontalDivider(color = EasyTheme.borderSubtle, thickness = 0.8.dp)
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(EasyTheme.surface)
                                    .padding(start = 8.dp, end = 8.dp, top = 6.dp, bottom = bottomSafeMargin),
                                horizontalArrangement = Arrangement.SpaceAround,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                items.forEach { screen ->
                                    val isSelected = effectiveRoute == screen.route
                                    val easyLabel = when (screen) {
                                        Screen.Diary -> "일상"
                                        Screen.Expense -> "내 소비"
                                        Screen.CarLedger -> "내 차"
                                        Screen.Lifestyle -> "리포트"
                                    }
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .clip(RoundedCornerShape(14.dp))
                                            .clickable {
                                                if (effectiveRoute != screen.route) {
                                                    pendingScreen = screen
                                                }
                                            }
                                            .padding(vertical = 5.dp),
                                        horizontalAlignment = Alignment.CenterHorizontally
                                    ) {
                                        Icon(
                                            imageVector = screen.icon,
                                            contentDescription = easyLabel,
                                            modifier = Modifier.size(22.dp),
                                            tint = if (isSelected) EasyTheme.textPrimary else Color(0xFFB0B8C1)
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = easyLabel,
                                            fontSize = 11.5.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) EasyTheme.textPrimary else EasyTheme.textMuted
                                        )
                                    }
                                }
                            }
                        }
                    } else {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(AppColors.surface)
                        ) {
                            HairlineDivider()
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(AppColors.surface)
                                    .padding(top = 2.dp, bottom = bottomSafeMargin),
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
                                            .padding(top = 4.dp, bottom = 3.dp),
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
                                        Spacer(modifier = Modifier.height(3.dp))
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
                }
            ) { paddingValues ->
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .statusBarsPadding()
                        .padding(paddingValues)
                ) {
                    GlobalSyncProgressBannerHost()

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
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
                            composable(Screen.Lifestyle.route) { LifestyleScreen() }
                        }
                    }
                }
            }

            if (isSettingsSheetOpen) {
                AppSettingsBottomSheet(
                    isEasyMode = isEasyMode,
                    onSetEasyMode = { enabled ->
                        EasyModeManager.setEasyMode(context, enabled)
                    },
                    onDismiss = {
                        EasyModeManager.closeSettingsSheet()
                    }
                )
            }

            GlobalLoadingOverlayHost()
        }
    }
}
