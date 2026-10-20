package com.autologue.app.presentation.common

import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
import com.autologue.app.presentation.navigation.Screen

data class QuickActionItem(
    val title: String,
    val subtitle: String,
    val icon: ImageVector,
    val iconBgColor: Color,
    val targetRoute: String,
    val onClick: () -> Unit
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun UnifiedSpeedDialFab(
    navController: NavController,
    modifier: Modifier = Modifier
) {
    var showSheet by remember { mutableStateOf(false) }
    val view = LocalView.current
    val context = LocalContext.current
    val isEasyMode by EasyModeManager.isEasyMode.collectAsState()

    val navigateToTab: (String) -> Unit = { route ->
        if (navController.currentDestination?.route != route) {
            navController.navigate(route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = true
                }
                launchSingleTop = true
                restoreState = true
            }
        }
    }

    val quickActions = listOf(
        QuickActionItem(
            title = "지출 직접 추가",
            subtitle = "카드/현금 지출 및 영수증 직접 입력",
            icon = Icons.Default.AddCard,
            iconBgColor = Color(0xFF2563EB),
            targetRoute = Screen.Expense.route,
            onClick = {
                navigateToTab(Screen.Expense.route)
            }
        ),
        QuickActionItem(
            title = "주유 내역 및 주행",
            subtitle = "차계부 주유비 및 주행거리 동기화",
            icon = Icons.Default.LocalGasStation,
            iconBgColor = Color(0xFFD97706),
            targetRoute = Screen.CarLedger.route,
            onClick = {
                navigateToTab(Screen.CarLedger.route)
            }
        ),
        QuickActionItem(
            title = "골프 라커룸/스코어카드 스캔",
            subtitle = "안내지 및 스코어카드 OCR 자동 분석",
            icon = Icons.Default.SportsGolf,
            iconBgColor = Color(0xFF059669),
            targetRoute = Screen.Golf.route,
            onClick = {
                navigateToTab(Screen.Golf.route)
            }
        ),
        QuickActionItem(
            title = "다이어리 일정 및 사진",
            subtitle = "오늘의 타임라인 및 사진 확인",
            icon = Icons.Default.AutoStories,
            iconBgColor = Color(0xFF7C3AED),
            targetRoute = Screen.Diary.route,
            onClick = {
                navigateToTab(Screen.Diary.route)
            }
        )
    )

    FloatingActionButton(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            showSheet = true
        },
        containerColor = if (isEasyMode) EasyTheme.tossBlue else Color(0xFF1E3A8A),
        contentColor = Color.White,
        shape = CircleShape,
        modifier = modifier
            .padding(bottom = 12.dp, end = 8.dp)
            .shadow(
                elevation = if (isEasyMode) 4.dp else 8.dp,
                shape = CircleShape,
                ambientColor = Color(0x331E3A8A),
                spotColor = Color(0x331E3A8A)
            )
    ) {
        Icon(
            imageVector = if (isEasyMode) Icons.Default.Add else Icons.Default.FlashOn,
            contentDescription = "원터치 빠른 액션 및 설정",
            tint = if (isEasyMode) Color.White else Color(0xFF67E8F9),
            modifier = Modifier.size(24.dp)
        )
    }

    if (showSheet) {
        ModalBottomSheet(
            onDismissRequest = { showSheet = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
            dragHandle = { BottomSheetDefaults.DragHandle() }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 24.dp)
                    .padding(bottom = 36.dp)
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "원터치 빠른 작업 및 모드 설정",
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                    Spacer(modifier = Modifier.weight(1f))
                    IconButton(onClick = { showSheet = false }) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "닫기",
                            tint = Color(0xFF64748B)
                        )
                    }
                }

                // 고급모드(기존 상세 UI/UX) ↔ 쉬운 모드(기본) 원터치 전환 배너 카드
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = EasyTheme.tossBlueSoft,
                    border = BorderStroke(1.dp, EasyTheme.tossBlue.copy(alpha = 0.35f)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 6.dp)
                ) {
                    val isAdvancedMode = !isEasyMode
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "고급모드 전환 (기존 상세 UI/UX)",
                                fontSize = 14.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = EasyTheme.textPrimary
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = if (isAdvancedMode) "현재 고급모드(기존 상세 UI/UX) 이용 중" else "현재 기본 쉬운 모드(Toss · Apple 스타일) 적용 중",
                                fontSize = 12.sp,
                                color = EasyTheme.textSecondary
                            )
                        }
                        Switch(
                            checked = isAdvancedMode,
                            onCheckedChange = { advancedEnabled ->
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                EasyModeManager.setEasyMode(context, !advancedEnabled)
                                Toast.makeText(
                                    context,
                                    if (advancedEnabled) "고급모드(기존 상세 UI/UX)로 전환되었습니다." else "기본 쉬운 모드로 전환되었습니다.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.White,
                                checkedTrackColor = EasyTheme.tossBlue
                            )
                        )
                    }
                }

                Spacer(modifier = Modifier.height(6.dp))

                Text(
                    text = "원하는 작업을 선택하시면 즉시 해당 화면으로 이동합니다.",
                    fontSize = 13.sp,
                    color = Color(0xFF64748B),
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                quickActions.forEach { action ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 5.dp)
                            .clickable {
                                view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                                showSheet = false
                                action.onClick()
                            }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(14.dp)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(42.dp)
                                    .clip(CircleShape)
                                    .background(action.iconBgColor.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = action.icon,
                                    contentDescription = action.title,
                                    tint = action.iconBgColor,
                                    modifier = Modifier.size(22.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(14.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = action.title,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = action.subtitle,
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFF94A3B8),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }
            }
        }
    }
}
