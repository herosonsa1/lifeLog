package com.autologue.app.presentation.common

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoStories
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.theme.*
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * 앱 구동 초기 백그라운드 기록 동기화와 연동되는 프리미엄 웰컴 애니메이션 화면
 * 
 * 4개 메뉴의 시그니처 컬러(다이어리 블루, 가계부 로즈, 차계부 앰버, 골프 그린)가
 * 궤도를 그리며 조화롭게 맥동(Pulse)하고, 동기화 진행 상태를 감성적으로 피드백합니다.
 */
@Composable
fun WelcomeSplashScreen(
    isSyncCompleted: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSyncCompleted by rememberUpdatedState(isSyncCompleted)

    // [버그 방어] isSyncCompleted 변경 시마다 LaunchedEffect가 재실행되어
    // startTime이 리셋되어 1.8초를 추가 대기하는 현상을 원천 방어 (Unit 기반 1회 시작)
    LaunchedEffect(Unit) {
        val startTime = System.currentTimeMillis()
        val minDuration = 1800L
        val maxTimeout = 3000L

        while (true) {
            val elapsed = System.currentTimeMillis() - startTime
            if (elapsed >= minDuration && currentSyncCompleted) {
                break
            }
            if (elapsed >= maxTimeout) {
                break
            }
            delay(100)
        }
        onDismiss()
    }

    // 1. 궤도 회전 애니메이션 (360도 무한 회전)
    val infiniteTransition = rememberInfiniteTransition(label = "welcome_orbit")
    val orbitAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 4000, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "orbit_angle"
    )

    // 2. 심볼 펄스 & 스케일 애니메이션
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    // 3. 프로그레스 바 무한 진행 효과
    val progressOffset by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "progress_offset"
    )

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(AppColors.background)
            .clickable { onDismiss() }, // 화면 탭 시 즉시 스킵 가능
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = Spacing.xxl)
        ) {
            // 🌟 4원색 시그니처 궤도 로고 심볼
            Box(
                modifier = Modifier
                    .size(130.dp)
                    .scale(pulseScale),
                contentAlignment = Alignment.Center
            ) {
                // 중앙 소프트 배경 서클
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(
                                    Color(0xFFEFF6FF),
                                    PureWhite
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.AutoStories,
                        contentDescription = "AutoLogue",
                        tint = MenuColors.diary,
                        modifier = Modifier.size(38.dp)
                    )
                }

                // 4원색 궤도 도트들 (회전 변환)
                val dotColors = listOf(
                    MenuColors.diary,     // 0도: 다이어리 블루
                    MenuColors.expense,   // 90도: 가계부 로즈
                    MenuColors.carLedger, // 180도: 차계부 앰버
                    MenuColors.golf       // 270도: 골프 포레스트 그린
                )
                val orbitRadius = 48.dp

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .rotate(orbitAngle)
                ) {
                    dotColors.forEachIndexed { index, color ->
                        val baseAngleRad = Math.toRadians((index * 90.0))
                        val offsetX = (orbitRadius.value * cos(baseAngleRad)).dp
                        val offsetY = (orbitRadius.value * sin(baseAngleRad)).dp

                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .offset(x = offsetX, y = offsetY)
                                .size(11.dp)
                                .clip(CircleShape)
                                .background(color)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            // 🏷️ 브랜드 타이틀 및 슬로건
            Text(
                text = "AutoLogue",
                style = AppTypography.display.copy(
                    fontSize = 28.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.6).sp,
                    color = Slate900
                )
            )

            Spacer(modifier = Modifier.height(Spacing.xxs))

            Text(
                text = "스마트 라이프로그 & 일상 다이어리",
                style = AppTypography.caption.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Slate500
                )
            )

            Spacer(modifier = Modifier.height(44.dp))

            // ⏳ 백그라운드 기록 동기화 상태 텍스트
            Text(
                text = if (isSyncCompleted) "동기화 완료 · 화면을 준비합니다" else "결제 내역 · 주행 기록 · 일상 타임라인 동기화 중...",
                style = AppTypography.caption.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSyncCompleted) MenuColors.golf else Slate600
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

            // 슬림 라운드 인디케이터 바
            Box(
                modifier = Modifier
                    .width(160.dp)
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp))
                    .background(Slate100)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .fillMaxWidth(if (isSyncCompleted) 1f else 0.45f + (progressOffset * 0.45f))
                        .clip(RoundedCornerShape(1.5.dp))
                        .background(
                            Brush.horizontalGradient(
                                colors = listOf(
                                    MenuColors.diary,
                                    MenuColors.expense,
                                    MenuColors.carLedger,
                                    MenuColors.golf
                                )
                            )
                        )
                )
            }
        }
    }
}
