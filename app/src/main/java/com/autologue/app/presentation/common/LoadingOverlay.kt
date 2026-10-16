package com.autologue.app.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.theme.AppColors
import com.autologue.app.presentation.theme.PureWhite
import com.autologue.app.presentation.theme.Spacing
import kotlinx.coroutines.delay

/**
 * 메뉴 간 화면 이동이나 데이터 조회/필터링이 0.5초(500ms) 이상 소요될 때
 * 멈춘 것처럼 보이지 않도록 로딩 중임을 명확히 표시하는 공통 애니메이션 오버레이
 */
@Composable
fun AutoLogueLoadingOverlay(
    isLoading: Boolean,
    title: String = "데이터를 불러오는 중",
    message: String = "요청하신 화면과 내역을 준비하고 있습니다...",
    accentColor: Color = AppColors.primary,
    delayMillis: Long = 500L,
    showImmediateTopBar: Boolean = true,
    modifier: Modifier = Modifier
) {
    var showFullOverlay by remember { mutableStateOf(false) }

    LaunchedEffect(isLoading, delayMillis) {
        if (isLoading) {
            if (delayMillis <= 0L) {
                showFullOverlay = true
            } else {
                delay(delayMillis)
                if (isLoading) {
                    showFullOverlay = true
                }
            }
        } else {
            if (showFullOverlay) {
                // 오버레이가 한 번 표시된 경우 최소 220ms 유지 후 자연스럽게 페이드아웃
                delay(220L)
            }
            showFullOverlay = false
        }
    }

    Box(modifier = modifier.fillMaxSize()) {
        // 1. 작업 시작 즉시 상단에 슬림한 진행 바를 표시하여 터치/조회 반응성 확보
        AnimatedVisibility(
            visible = isLoading && showImmediateTopBar,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.5.dp),
                color = accentColor,
                trackColor = accentColor.copy(alpha = 0.14f)
            )
        }

        // 2. 0.5초(500ms) 이상 소요 시 중앙 로딩 애니메이션 카드 표시
        AnimatedVisibility(
            visible = showFullOverlay,
            enter = fadeIn(animationSpec = tween(180)),
            exit = fadeOut(animationSpec = tween(220)),
            modifier = Modifier.fillMaxSize()
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A).copy(alpha = 0.32f))
                    .clickable(enabled = false) {},
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = PureWhite,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .padding(horizontal = Spacing.xxl)
                        .fillMaxWidth(0.82f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "AutoLogueLoadingPulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 0.94f,
                            targetValue = 1.06f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(700, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseScale"
                        )

                        Box(
                            modifier = Modifier
                                .size(52.dp)
                                .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                                .clip(CircleShape)
                                .background(accentColor.copy(alpha = 0.10f)),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(
                                strokeWidth = 3.dp,
                                color = accentColor,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = title,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = message.ifBlank { "요청하신 내역을 조회하고 있습니다..." },
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )
                    }
                }
            }
        }
    }
}
