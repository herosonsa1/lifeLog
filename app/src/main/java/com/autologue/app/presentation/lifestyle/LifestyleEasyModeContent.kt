package com.autologue.app.presentation.lifestyle

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.domain.model.LifeOrbitPeriod
import com.autologue.app.domain.model.LifestyleInsight
import com.autologue.app.domain.model.LifestyleMetrics
import com.autologue.app.domain.usecase.sync.SyncProgress
import com.autologue.app.presentation.common.*

@Composable
fun LifestyleEasyModeContent(
    selectedPeriod: LifeOrbitPeriod,
    metrics: LifestyleMetrics,
    syncProgress: SyncProgress?,
    onSelectPeriod: (LifeOrbitPeriod) -> Unit,
    onSyncClick: () -> Unit
) {
    val context = LocalContext.current
    val periods = LifeOrbitPeriod.values().toList()
    val selectedIdx = periods.indexOf(selectedPeriod).coerceAtLeast(0)

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = EasyTheme.CanvasBg,
        topBar = {
            EasyModeTopBar(
                title = "리포트",
                subtitle = "일상·소비·이동·골프를 한눈에 정리했어요",
                accentColor = EasyTheme.tossBlue,
                onOpenSettings = { EasyModeManager.openSettingsSheet() },
                onQuickSwitchToStandard = { EasyModeManager.setEasyMode(context, false) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 기간 선택 세그먼트 바 (1주 / 1개월 / 3개월 / 1년)
            item {
                EasySegmentedBar(
                    options = periods.map { it.label },
                    selectedIndex = selectedIdx,
                    onSelect = { idx ->
                        periods.getOrNull(idx)?.let(onSelectPeriod)
                    }
                )
            }

            // 동기화 진행 배너 (진행 중일 때만)
            if (syncProgress != null) {
                item {
                    EasySectionCard {
                        Text(
                            text = "내 폰의 실제 기록을 동기화하고 있어요",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            color = EasyTheme.tossBlue
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = syncProgress.stage,
                            fontSize = 12.5.sp,
                            color = EasyTheme.textSecondary
                        )
                        Spacer(modifier = Modifier.height(10.dp))
                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(6.dp)
                                .clip(RoundedCornerShape(999.dp)),
                            color = EasyTheme.tossBlue,
                            trackColor = EasyTheme.DividerColor
                        )
                    }
                }
            }

            // 2. 종합 라이프 밸런스 히어로 카드
            item {
                EasySectionCard {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Top
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${selectedPeriod.label} 라이프 밸런스 지수",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EasyTheme.textSecondary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(
                                verticalAlignment = Alignment.Bottom,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "${metrics.balanceScores.overallScore}점",
                                    fontSize = 32.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = EasyTheme.textPrimary,
                                    letterSpacing = (-0.8).sp
                                )
                                Text(
                                    text = "/ 100",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EasyTheme.textMuted,
                                    modifier = Modifier.padding(bottom = 5.dp)
                                )
                            }
                        }

                        Surface(
                            shape = RoundedCornerShape(999.dp),
                            color = EasyTheme.tossBlueSoft
                        ) {
                            Text(
                                text = metrics.archetype,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = EasyTheme.tossBlue,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = metrics.archetypeSubtitle,
                        fontSize = 13.5.sp,
                        color = EasyTheme.textSecondary,
                        lineHeight = 19.sp
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // 4대 균형 지표 바 (활동성 / 재정 / 모빌리티 / 성취도)
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = EasyTheme.SubtleBoxBg,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            EasyBalanceBarRow(
                                label = "일상 활동성",
                                score = metrics.balanceScores.activityScore,
                                color = EasyTheme.tossBlue
                            )
                            EasyBalanceBarRow(
                                label = "재정 건전성",
                                score = metrics.balanceScores.financeScore,
                                color = EasyTheme.PositiveGreen
                            )
                            EasyBalanceBarRow(
                                label = "이동 · 모빌리티",
                                score = metrics.balanceScores.mobilityScore,
                                color = EasyTheme.WarningAmber
                            )
                            EasyBalanceBarRow(
                                label = "골프 · 성취도",
                                score = metrics.balanceScores.achievementScore,
                                color = EasyTheme.AppleIndigo
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    EasyActionButton(
                        text = if (syncProgress?.isRunning == true) "동기화 진행 중..." else "내 폰 기록 한 번에 동기화",
                        icon = Icons.Default.Sync,
                        onClick = onSyncClick,
                        isPrimary = true,
                        accentColor = EasyTheme.tossBlue,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            // 3. 4대 영역 핵심 요약 카드 (소비 / 내 차 / 골프 / 일상 발자취)
            item {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EasyDomainSummaryCard(
                        icon = Icons.Default.Payments,
                        iconTint = EasyTheme.tossBlue,
                        iconBg = EasyTheme.tossBlueSoft,
                        title = "내 소비 템포",
                        primaryLabel = "기간 총 지출",
                        primaryValue = "%,d원".format(metrics.financialVelocity.totalExpense),
                        subLabel1 = "일평균 지출",
                        subValue1 = "%,d원".format(metrics.financialVelocity.dailyAverageExpense),
                        subLabel2 = "최다 지출 분야",
                        subValue2 = "${metrics.financialVelocity.topCategoryName} (${metrics.financialVelocity.topCategoryRatio}%)"
                    )

                    EasyDomainSummaryCard(
                        icon = Icons.Default.DirectionsCar,
                        iconTint = EasyTheme.WarningAmber,
                        iconBg = EasyTheme.WarningAmberBg,
                        title = "내 차 모빌리티",
                        primaryLabel = "누적 주행거리",
                        primaryValue = "${metrics.mobilityOrbit.totalDrivingKm} km",
                        subLabel1 = "평균 연비",
                        subValue1 = if (metrics.mobilityOrbit.averageFuelEconomy > 0) "${metrics.mobilityOrbit.averageFuelEconomy} km/L" else "산출 대기",
                        subLabel2 = "주행 일수 · 주유비",
                        subValue2 = "${metrics.mobilityOrbit.drivingDaysCount}일 · %,d원".format(metrics.mobilityOrbit.totalFuelCost)
                    )

                    EasyDomainSummaryCard(
                        icon = Icons.Default.SportsGolf,
                        iconTint = EasyTheme.PositiveGreen,
                        iconBg = EasyTheme.PositiveGreenBg,
                        title = "골프 · 액티브",
                        primaryLabel = "라운드 횟수",
                        primaryValue = "${metrics.golfTrajectory.roundCount}회",
                        subLabel1 = "평균 · 베스트 타수",
                        subValue1 = if (metrics.golfTrajectory.averageScore > 0) {
                            "${metrics.golfTrajectory.averageScore}타 · 최고 ${metrics.golfTrajectory.bestScore ?: "-"}타"
                        } else {
                            "기록 대기"
                        },
                        subLabel2 = "평균 퍼트 · 걸음수",
                        subValue2 = "${metrics.golfTrajectory.averagePutts?.let { "${it}퍼트" } ?: "-"} · ${if (metrics.golfTrajectory.totalFieldSteps > 0) "%,d보".format(metrics.golfTrajectory.totalFieldSteps) else "걸음 기록 없음"}"
                    )

                    EasyDomainSummaryCard(
                        icon = Icons.Default.Map,
                        iconTint = EasyTheme.AppleIndigo,
                        iconBg = Color(0xFFEEF2FF),
                        title = "일상 발자취",
                        primaryLabel = "기록된 일수",
                        primaryValue = "${metrics.spatialFootprint.recordedDays}일",
                        subLabel1 = "보관된 사진",
                        subValue1 = "${metrics.spatialFootprint.totalPhotosArchived}장",
                        subLabel2 = "주요 활동 지역",
                        subValue2 = "${metrics.spatialFootprint.primaryArea} (${metrics.spatialFootprint.placeClusterCount}곳)"
                    )
                }
            }

            // 4. 스마트 코칭 인사이트 리스트
            if (metrics.insights.isNotEmpty()) {
                item {
                    EasySectionCard {
                        Text(
                            text = "맞춤 라이프 인사이트",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EasyTheme.textPrimary
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            metrics.insights.forEach { insight ->
                                EasyInsightItem(insight = insight)
                            }
                        }
                    }
                }
            }

            item {
                Spacer(modifier = Modifier.height(72.dp))
            }
        }
    }
}

@Composable
private fun EasyBalanceBarRow(
    label: String,
    score: Int,
    color: Color
) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = EasyTheme.textSecondary
            )
            Text(
                text = "${score}점",
                fontSize = 13.sp,
                fontWeight = FontWeight.ExtraBold,
                color = EasyTheme.textPrimary
            )
        }
        Spacer(modifier = Modifier.height(5.dp))
        LinearProgressIndicator(
            progress = { (score.coerceIn(0, 100)) / 100f },
            modifier = Modifier
                .fillMaxWidth()
                .height(6.dp)
                .clip(RoundedCornerShape(999.dp)),
            color = color,
            trackColor = EasyTheme.DividerColor
        )
    }
}

@Composable
private fun EasyDomainSummaryCard(
    icon: ImageVector,
    iconTint: Color,
    iconBg: Color,
    title: String,
    primaryLabel: String,
    primaryValue: String,
    subLabel1: String,
    subValue1: String,
    subLabel2: String,
    subValue2: String
) {
    EasySectionCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(iconBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = iconTint,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = EasyTheme.textPrimary
                    )
                    Text(
                        text = primaryLabel,
                        fontSize = 11.5.sp,
                        color = EasyTheme.textSecondary
                    )
                }
            }

            Text(
                text = primaryValue,
                fontSize = 20.sp,
                fontWeight = FontWeight.ExtraBold,
                color = EasyTheme.textPrimary
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Surface(
            shape = RoundedCornerShape(14.dp),
            color = EasyTheme.SubtleBoxBg,
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = subLabel1,
                        fontSize = 11.5.sp,
                        color = EasyTheme.textSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subValue1,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Box(
                    modifier = Modifier
                        .width(1.dp)
                        .height(24.dp)
                        .background(EasyTheme.DividerColor)
                )
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .padding(start = 12.dp)
                ) {
                    Text(
                        text = subLabel2,
                        fontSize = 11.5.sp,
                        color = EasyTheme.textSecondary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subValue2,
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun EasyInsightItem(insight: LifestyleInsight) {
    val badgeBg = if (insight.isPositive) EasyTheme.PositiveGreenBg else EasyTheme.WarningAmberBg
    val badgeText = if (insight.isPositive) EasyTheme.PositiveGreen else EasyTheme.WarningAmber

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = EasyTheme.SubtleBoxBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = badgeBg
                ) {
                    Text(
                        text = insight.category,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeText,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Text(
                    text = if (insight.isPositive) "긍정 신호" else "체크 포인트",
                    fontSize = 11.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EasyTheme.textSecondary
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = insight.title,
                fontSize = 14.5.sp,
                fontWeight = FontWeight.Bold,
                color = EasyTheme.textPrimary
            )
            Spacer(modifier = Modifier.height(3.dp))
            Text(
                text = insight.actionGuidance,
                fontSize = 12.5.sp,
                color = EasyTheme.textSecondary,
                lineHeight = 18.sp
            )
        }
    }
}
