package com.autologue.app.presentation.common

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import com.autologue.app.domain.usecase.sync.SyncHistoricalDataUseCase
import com.autologue.app.domain.usecase.sync.SyncProgress
import com.autologue.app.presentation.theme.AppColors
import com.autologue.app.presentation.theme.AppTypography
import com.autologue.app.presentation.theme.PureWhite
import com.autologue.app.presentation.theme.Slate100
import com.autologue.app.presentation.theme.Slate200
import com.autologue.app.presentation.theme.Slate600
import com.autologue.app.presentation.theme.Slate700
import com.autologue.app.presentation.theme.Slate900
import com.autologue.app.presentation.theme.Spacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow
import javax.inject.Inject

@HiltViewModel
class GlobalSyncBannerViewModel @Inject constructor(
    private val syncHistoricalDataUseCase: SyncHistoricalDataUseCase
) : ViewModel() {
    val syncState: StateFlow<SyncProgress?> = syncHistoricalDataUseCase.syncState

    fun dismissBanner() {
        syncHistoricalDataUseCase.dismissSyncBanner()
    }
}

/**
 * 앱 내 어느 메뉴(다이어리, 가계부, 차계부, 스마트 골프, 라이프 궤도)에 있더라도
 * 최상단에 고정되어 백그라운드 데이터 수집·분석의 전체 건수 대비 진행 건수와 완료율(%)을 실시간 표시하는 전역 배너.
 */
@Composable
fun GlobalSyncProgressBannerHost(
    modifier: Modifier = Modifier,
    viewModel: GlobalSyncBannerViewModel = hiltViewModel()
) {
    val syncProgress by viewModel.syncState.collectAsState()

    // 100% 완료 상태가 되면 사용자가 완료 결과를 충분히 인지할 수 있도록 12초간 유지 후 자동 닫힘
    LaunchedEffect(syncProgress?.isDone, syncProgress?.stage) {
        val current = syncProgress
        if (current != null && current.isDone && !current.isRunning) {
            delay(12_000L)
            viewModel.dismissBanner()
        }
    }

    AnimatedVisibility(
        visible = syncProgress != null,
        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(200)),
        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(180)),
        modifier = modifier.fillMaxWidth()
    ) {
        val progress = syncProgress
        if (progress != null) {
            GlobalSyncProgressBannerContent(
                progress = progress,
                onDismiss = { viewModel.dismissBanner() }
            )
        }
    }
}

@Composable
fun GlobalSyncProgressBannerContent(
    progress: SyncProgress,
    onDismiss: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    val isDone = progress.isDone && !progress.isRunning
    val accentColor = if (isDone) Color(0xFF059669) else AppColors.primary
    val bgTint = if (isDone) Color(0xFFF0FDF4) else Color(0xFFF8FAFC)
    val borderTint = if (isDone) Color(0xFFA7F3D0) else Color(0xFFCBD5E1)

    val targetProgress = if (isDone) {
        1f
    } else {
        (progress.progressPercent.coerceIn(1, 99) / 100f)
    }
    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 260),
        label = "global_sync_progress"
    )

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(bgTint)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            // 1행: 상태 타이틀 + [전체 M건 중 N건 (XX% 완료)] 정량 배지 + 닫기 버튼
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    if (isDone) {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            contentDescription = "수집 완료",
                            tint = accentColor,
                            modifier = Modifier.size(15.dp)
                        )
                    } else {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            strokeWidth = 2.dp,
                            color = accentColor
                        )
                    }

                    if (!isDone && progress.totalSteps > 1) {
                        Surface(
                            color = accentColor.copy(alpha = 0.10f),
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "${progress.stepNumber}/${progress.totalSteps}단계",
                                style = AppTypography.caption.copy(
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = accentColor
                                ),
                                modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.5.dp)
                            )
                        }
                    }

                    Text(
                        text = if (isDone) {
                            "백그라운드 수집·분석 완료"
                        } else {
                            "백그라운드 수집·분석 진행 중"
                        },
                        style = AppTypography.caption.copy(
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = Slate900
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Surface(
                        color = PureWhite,
                        border = BorderStroke(1.dp, borderTint),
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = progress.formatQuantitativeSummary(),
                            style = AppTypography.caption.copy(
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = accentColor
                            ),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.5.dp)
                        )
                    }

                    if (isDone && onDismiss != null) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .clickable { onDismiss() }
                                .padding(2.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "배너 닫기",
                                tint = Slate600,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                }
            }

            // 2행: 정량 진행률 게이지 바 (0% ~ 100%)
            LinearProgressIndicator(
                progress = { animatedProgress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(3.dp)),
                color = accentColor,
                trackColor = Slate200
            )

            // 3행: 세부 진행 단계 설명 + 실시간 누적 건수 및 백그라운드 유지 안내
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = progress.stage.ifBlank { "결제 문자 및 갤러리 사진을 병렬 분석하고 있습니다..." },
                    style = AppTypography.caption.copy(
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate700
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )

                val hasCounts = progress.syncedTxCount > 0 || progress.syncedPhotoCount > 0 || progress.syncedGolfCount > 0
                if (hasCounts) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = buildString {
                            append("결제 ${progress.syncedTxCount}건 · 사진 ${progress.syncedPhotoCount}장")
                            if (progress.syncedGolfCount > 0) append(" · 골프 ${progress.syncedGolfCount}건")
                        },
                        style = AppTypography.caption.copy(
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate600
                        )
                    )
                } else if (!isDone) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "앱을 닫아도 계속 진행됨",
                        style = AppTypography.caption.copy(
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate600
                        )
                    )
                }
            }
        }
        HairlineDivider(color = borderTint)
    }
}
