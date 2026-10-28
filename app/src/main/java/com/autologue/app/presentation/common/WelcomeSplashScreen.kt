package com.autologue.app.presentation.common

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.theme.AppColors
import com.autologue.app.presentation.theme.AppTypography
import com.autologue.app.presentation.theme.MenuColors
import com.autologue.app.presentation.theme.Slate100
import com.autologue.app.presentation.theme.Slate500
import com.autologue.app.presentation.theme.Slate600
import com.autologue.app.presentation.theme.Slate900
import com.autologue.app.presentation.theme.Spacing
import kotlinx.coroutines.delay
import kotlin.math.cos
import kotlin.math.sin

/**
 * 앱 구동 초기 백그라운드 기록 동기화와 연동되는 웰컴 애니메이션 화면
 */
@Composable
fun WelcomeSplashScreen(
    isSyncCompleted: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentSyncCompleted by rememberUpdatedState(isSyncCompleted)

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

    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.04f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

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
            .clickable { onDismiss() },
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(horizontal = Spacing.xxl)
        ) {
            Box(
                modifier = Modifier
                    .size(140.dp)
                    .scale(pulseScale),
                contentAlignment = Alignment.Center
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(id = com.autologue.app.R.drawable.app_logo),
                    contentDescription = "나의일상다이어리",
                    modifier = Modifier
                        .size(84.dp)
                        .clip(RoundedCornerShape(20.dp))
                )

                val dotColors = listOf(
                    MenuColors.diary,
                    MenuColors.expense,
                    MenuColors.carLedger,
                    MenuColors.lifestyle
                )
                val orbitRadius = 56.dp

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

            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "나의일상",
                    style = AppTypography.display.copy(
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                        color = Slate900
                    )
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "다이어리",
                    style = AppTypography.h1.copy(
                        fontSize = 22.sp,
                        fontWeight = FontWeight.ExtraBold,
                        letterSpacing = 1.5.sp,
                        color = MenuColors.diary
                    )
                )
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            Text(
                text = "스마트 라이프로그 & 일상 다이어리",
                style = AppTypography.caption.copy(
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = Slate500
                )
            )

            Spacer(modifier = Modifier.height(40.dp))

            Text(
                text = if (isSyncCompleted) "동기화 완료 · 화면을 준비합니다" else "결제 내역 · 주행 기록 · 일상 타임라인 동기화 중...",
                style = AppTypography.caption.copy(
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (isSyncCompleted) AppColors.successText else Slate600
                )
            )

            Spacer(modifier = Modifier.height(10.dp))

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
                        .background(MenuColors.diary)
                )
            }
        }
    }
}
