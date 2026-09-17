package com.autologue.app.presentation.lifestyle

import android.content.Context
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Insights
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Payments
import androidx.compose.material.icons.filled.SportsGolf
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TrendingUp
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autologue.app.domain.model.FinancialVelocity
import com.autologue.app.domain.model.GolfAthleticTrajectory
import com.autologue.app.domain.model.LifeBalanceScores
import com.autologue.app.domain.model.LifeOrbitPeriod
import com.autologue.app.domain.model.LifestyleInsight
import com.autologue.app.domain.model.LifestyleMetrics
import com.autologue.app.domain.model.MobilityOrbit
import com.autologue.app.domain.model.SpatioTemporalFootprint
import com.autologue.app.domain.usecase.sync.SyncProgress
import com.autologue.app.presentation.theme.AppColors
import com.autologue.app.presentation.theme.AppShapes
import com.autologue.app.presentation.theme.AppTypography
import com.autologue.app.presentation.theme.MenuColors
import com.autologue.app.presentation.theme.Spacing
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun LifestyleScreen(
    viewModel: LifestyleViewModel = hiltViewModel()
) {
    val context = LocalContext.current
    val selectedPeriod by viewModel.selectedPeriod.collectAsState()
    val metrics by viewModel.metrics.collectAsState()
    val syncProgress by viewModel.syncProgress.collectAsState()

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
            .padding(horizontal = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.md)
    ) {
        item {
            Spacer(modifier = Modifier.height(Spacing.xs))
            LifestyleHeader(
                onSyncClick = { viewModel.syncFromDevice(context, 30) },
                isSyncing = syncProgress?.isRunning == true
            )
        }

        syncProgress?.let { progress ->
            item {
                SyncProgressBanner(progress = progress)
            }
        }

        item {
            PeriodSelectorTabs(
                selectedPeriod = selectedPeriod,
                onPeriodSelected = { viewModel.selectPeriod(it) }
            )
        }

        item {
            LifeOrbitHeroCard(
                metrics = metrics,
                onSyncClick = { viewModel.syncFromDevice(context, 30) }
            )
        }

        item {
            LifeBalanceRadarSection(scores = metrics.balanceScores)
        }

        item {
            OrbitCompassSection(insights = metrics.insights)
        }

        item {
            FourPillarsBentoGrid(metrics = metrics)
        }

        item {
            Spacer(modifier = Modifier.height(Spacing.xl))
        }
    }
}

@Composable
private fun LifestyleHeader(
    onSyncClick: () -> Unit,
    isSyncing: Boolean
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xs),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(MenuColors.lifestyleBg),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Insights,
                        contentDescription = null,
                        tint = MenuColors.lifestyle,
                        modifier = Modifier.size(18.dp)
                    )
                }
                Text(
                    text = "라이프 궤도",
                    style = AppTypography.h1,
                    color = AppColors.textPrimary
                )
            }
            Text(
                text = "내 모바일폰의 실제 결제·이동·스포츠·기록 기반 통합 인텔리전스",
                style = AppTypography.caption,
                color = AppColors.textSecondary,
                modifier = Modifier.padding(top = 2.dp)
            )
        }

        Surface(
            shape = AppShapes.pill,
            color = if (isSyncing) AppColors.surfaceVariant else MenuColors.lifestyleBg,
            border = androidx.compose.foundation.BorderStroke(1.dp, MenuColors.lifestyleBorder),
            modifier = Modifier.clickable(enabled = !isSyncing) { onSyncClick() }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
            ) {
                if (isSyncing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(12.dp),
                        strokeWidth = 1.5.dp,
                        color = MenuColors.lifestyle
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Sync,
                        contentDescription = "동기화",
                        tint = MenuColors.lifestyle,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = if (isSyncing) "동기화 중" else "내 폰 동기화",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold),
                    color = MenuColors.lifestyle
                )
            }
        }
    }
}

@Composable
private fun SyncProgressBanner(progress: SyncProgress) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MenuColors.lifestyleBg),
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, MenuColors.lifestyleBorder)
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Text(
                text = "내 모바일폰 실제 데이터 동기화 중",
                style = AppTypography.caption.copy(fontWeight = FontWeight.Bold),
                color = MenuColors.lifestyle
            )
            Text(
                text = progress.stage,
                style = AppTypography.caption,
                color = AppColors.textSecondary,
                modifier = Modifier.padding(top = 2.dp, bottom = 4.dp)
            )
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp)
                    .clip(RoundedCornerShape(1.5.dp)),
                color = MenuColors.lifestyle,
                trackColor = MenuColors.lifestyleBorder
            )
        }
    }
}

@Composable
private fun PeriodSelectorTabs(
    selectedPeriod: LifeOrbitPeriod,
    onPeriodSelected: (LifeOrbitPeriod) -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = AppShapes.pill,
        color = AppColors.surfaceVariant,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LifeOrbitPeriod.values().forEach { period ->
                val isSelected = period == selectedPeriod
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(AppShapes.button)
                        .background(if (isSelected) MenuColors.lifestyle else Color.Transparent)
                        .clickable { onPeriodSelected(period) }
                        .padding(vertical = 8.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = period.label,
                        style = AppTypography.caption.copy(
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        ),
                        color = if (isSelected) Color.White else AppColors.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun LifeOrbitHeroCard(
    metrics: LifestyleMetrics,
    onSyncClick: () -> Unit
) {
    val isZeroState = metrics.balanceScores.overallScore == 0

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "라이프 궤도 종합 지수",
                        style = AppTypography.caption,
                        color = AppColors.textSecondary
                    )
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier.padding(top = 2.dp)
                    ) {
                        Text(
                            text = "${metrics.balanceScores.overallScore}",
                            style = AppTypography.display.copy(
                                fontSize = 32.sp,
                                fontWeight = FontWeight.Bold
                            ),
                            color = if (isZeroState) AppColors.textMuted else MenuColors.lifestyle
                        )
                        Text(
                            text = "/ 100",
                            style = AppTypography.captionMuted,
                            modifier = Modifier.padding(bottom = 6.dp)
                        )
                    }
                }

                Surface(
                    shape = AppShapes.pill,
                    color = if (isZeroState) AppColors.surfaceVariant else MenuColors.lifestyleBg,
                    border = androidx.compose.foundation.BorderStroke(
                        1.dp,
                        if (isZeroState) AppColors.border else MenuColors.lifestyleBorder
                    )
                ) {
                    Text(
                        text = metrics.archetype,
                        style = AppTypography.caption.copy(fontWeight = FontWeight.Bold),
                        color = if (isZeroState) AppColors.textSecondary else MenuColors.lifestyle,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.sm))
            Text(
                text = metrics.archetypeSubtitle,
                style = AppTypography.bodySecondary,
                lineHeight = 18.sp
            )

            if (isZeroState) {
                Spacer(modifier = Modifier.height(Spacing.sm))
                Button(
                    onClick = onSyncClick,
                    modifier = Modifier.fillMaxWidth(),
                    shape = AppShapes.button,
                    colors = ButtonDefaults.buttonColors(containerColor = MenuColors.lifestyle)
                ) {
                    Icon(
                        imageVector = Icons.Default.Download,
                        contentDescription = null,
                        modifier = Modifier.size(16.dp),
                        tint = Color.White
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "내 폰의 실제 일상 기록 가져오기",
                        style = AppTypography.caption.copy(fontWeight = FontWeight.Bold),
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
private fun LifeBalanceRadarSection(scores: LifeBalanceScores) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border)
    ) {
        Column(modifier = Modifier.padding(Spacing.md)) {
            Text(
                text = "4대 라이프스타일 밸런스 맵",
                style = AppTypography.h2,
                color = AppColors.textPrimary
            )
            Text(
                text = "일상 기록, 재정 건전성, 이동 반경, 스포츠 성취도의 균형도",
                style = AppTypography.caption,
                color = AppColors.textSecondary,
                modifier = Modifier.padding(top = 1.dp, bottom = Spacing.sm)
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp),
                contentAlignment = Alignment.Center
            ) {
                LifeBalanceRadarCanvas(scores = scores)
            }

            // 4개 축 수치 표시 바
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = Spacing.xs),
                horizontalArrangement = Arrangement.SpaceAround
            ) {
                AxisScoreBadge(label = "활동성", score = scores.activityScore)
                AxisScoreBadge(label = "재정", score = scores.financeScore)
                AxisScoreBadge(label = "모빌리티", score = scores.mobilityScore)
                AxisScoreBadge(label = "성취도", score = scores.achievementScore)
            }
        }
    }
}

@Composable
private fun AxisScoreBadge(label: String, score: Int) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            text = label,
            style = AppTypography.caption,
            color = AppColors.textSecondary
        )
        Text(
            text = "${score}점",
            style = AppTypography.body.copy(fontWeight = FontWeight.Bold),
            color = if (score > 0) AppColors.textPrimary else AppColors.textMuted,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}

@Composable
private fun LifeBalanceRadarCanvas(scores: LifeBalanceScores) {
    Canvas(
        modifier = Modifier
            .size(190.dp)
            .clipToBounds() // AP-COMPOSE-CANVAS-OVERFLOW-NOCLIP 방어
    ) {
        val center = Offset(size.width / 2f, size.height / 2f)
        val maxRadius = (minOf(size.width, size.height) / 2f) - 16.dp.toPx()

        val angles = listOf(
            -90.0, // 상: 활동성 (Activity)
            0.0,   // 우: 재정 (Finance)
            90.0,  // 하: 이동성 (Mobility)
            180.0  // 좌: 성취도 (Achievement)
        )

        // 1. 거미줄 가이드라인 (25%, 50%, 75%, 100%)
        val stepRatios = listOf(0.25f, 0.5f, 0.75f, 1.0f)
        stepRatios.forEach { ratio ->
            val r = maxRadius * ratio
            val guidePath = Path()
            angles.forEachIndexed { i, angleDeg ->
                val rad = Math.toRadians(angleDeg)
                val x = center.x + (r * cos(rad)).toFloat()
                val y = center.y + (r * sin(rad)).toFloat()
                if (i == 0) guidePath.moveTo(x, y) else guidePath.lineTo(x, y)
            }
            guidePath.close()
            drawPath(
                path = guidePath,
                color = Color.LightGray.copy(alpha = 0.35f),
                style = Stroke(width = 1.dp.toPx())
            )
        }

        // 2. 중심에서 각 축 꼭짓점으로의 축 선
        angles.forEach { angleDeg ->
            val rad = Math.toRadians(angleDeg)
            val endX = center.x + (maxRadius * cos(rad)).toFloat()
            val endY = center.y + (maxRadius * sin(rad)).toFloat()
            drawLine(
                color = Color.LightGray.copy(alpha = 0.5f),
                start = center,
                end = Offset(endX, endY),
                strokeWidth = 1.dp.toPx()
            )
        }

        // 3. 실제 점수 다각형 (Data Polygon)
        val scoreValues = listOf(
            scores.activityScore.coerceIn(0, 100),
            scores.financeScore.coerceIn(0, 100),
            scores.mobilityScore.coerceIn(0, 100),
            scores.achievementScore.coerceIn(0, 100)
        )

        val hasAnyScore = scoreValues.any { it > 0 }

        if (hasAnyScore) {
            val dataPath = Path()
            val points = mutableListOf<Offset>()
            angles.forEachIndexed { i, angleDeg ->
                val rad = Math.toRadians(angleDeg)
                val r = maxRadius * (maxOf(5, scoreValues[i]) / 100f)
                val x = center.x + (r * cos(rad)).toFloat()
                val y = center.y + (r * sin(rad)).toFloat()
                val point = Offset(x, y)
                points.add(point)
                if (i == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
            }
            dataPath.close()

            drawPath(
                path = dataPath,
                color = MenuColors.lifestyle.copy(alpha = 0.22f),
                style = Fill
            )

            drawPath(
                path = dataPath,
                color = MenuColors.lifestyle,
                style = Stroke(width = 2.dp.toPx())
            )

            points.forEach { pt ->
                drawCircle(
                    color = MenuColors.lifestyle,
                    radius = 3.5.dp.toPx(),
                    center = pt
                )
                drawCircle(
                    color = Color.White,
                    radius = 1.5.dp.toPx(),
                    center = pt
                )
            }
        } else {
            // 점수가 전혀 없을 때 중심에 원형 인디케이터 표시
            drawCircle(
                color = Color.LightGray.copy(alpha = 0.6f),
                radius = 4.dp.toPx(),
                center = center
            )
        }
    }
}

@Composable
private fun OrbitCompassSection(insights: List<LifestyleInsight>) {
    if (insights.isEmpty()) return

    Column(modifier = Modifier.fillMaxWidth()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
            modifier = Modifier.padding(bottom = Spacing.xs)
        ) {
            Icon(
                imageVector = Icons.Default.TrendingUp,
                contentDescription = null,
                tint = MenuColors.lifestyle,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = "스마트 궤도 나침반",
                style = AppTypography.h3,
                color = AppColors.textPrimary
            )
        }

        Column(verticalArrangement = Arrangement.spacedBy(Spacing.xs)) {
            insights.forEach { insight ->
                InsightCard(insight = insight)
            }
        }
    }
}

@Composable
private fun InsightCard(insight: LifestyleInsight) {
    val accentColor = if (insight.isPositive) Color(0xFF10B981) else Color(0xFFF59E0B)

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Box(
                modifier = Modifier
                    .width(4.dp)
                    .height(84.dp)
                    .background(accentColor)
            )

            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = insight.category,
                        style = AppTypography.caption,
                        color = accentColor
                    )
                    Text(
                        text = if (insight.isPositive) "안내" else "개선 권장",
                        style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold),
                        color = AppColors.textSecondary
                    )
                }

                Text(
                    text = insight.title,
                    style = AppTypography.body.copy(fontWeight = FontWeight.Bold),
                    color = AppColors.textPrimary,
                    modifier = Modifier.padding(top = 1.dp)
                )

                Text(
                    text = insight.actionGuidance,
                    style = AppTypography.caption,
                    color = AppColors.textSecondary,
                    modifier = Modifier.padding(top = 2.dp)
                )
            }
        }
    }
}

@Composable
private fun FourPillarsBentoGrid(metrics: LifestyleMetrics) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        Text(
            text = "4대 라이프 인텔리전스 궤적",
            style = AppTypography.h3,
            color = AppColors.textPrimary,
            modifier = Modifier.padding(top = Spacing.xs)
        )

        // 1. 재정 템포 (Financial Velocity)
        FinancialPillarCard(data = metrics.financialVelocity)

        // 2. 모빌리티 궤도 (Mobility Orbit)
        MobilityPillarCard(data = metrics.mobilityOrbit)

        // 3. 골프 & 체력 (Golf Trajectory)
        GolfPillarCard(data = metrics.golfTrajectory)

        // 4. 시공간 풋프린트 (Spatial Footprint)
        SpatialPillarCard(data = metrics.spatialFootprint)
    }
}

@Composable
private fun FinancialPillarCard(data: FinancialVelocity) {
    PillarCardLayout(
        title = "재정 템포 (Financial Velocity)",
        icon = Icons.Default.Payments,
        accentColor = MenuColors.expense
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(label = "총 지출", value = "${String.format("%,d", data.totalExpense)}원")
            PillarMetricItem(label = "일평균 지출", value = "${String.format("%,d", data.dailyAverageExpense)}원")
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(
                label = "최상위 지출 항목",
                value = "${data.topCategoryName} (${data.topCategoryRatio}%)"
            )
            PillarMetricItem(
                label = "라이프스타일 지출 비율",
                value = "${data.lifestyleExpenseRatio}%"
            )
        }
    }
}

@Composable
private fun MobilityPillarCard(data: MobilityOrbit) {
    PillarCardLayout(
        title = "모빌리티 궤도 (Mobility Orbit)",
        icon = Icons.Default.DirectionsCar,
        accentColor = MenuColors.carLedger
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(label = "누적 주행거리", value = "${data.totalDrivingKm} km")
            PillarMetricItem(
                label = "평균 연비",
                value = if (data.averageFuelEconomy > 0) "${data.averageFuelEconomy} km/L" else "산출 중"
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(label = "주행 일수 / 주유비", value = "${data.drivingDaysCount}일 / ${String.format("%,d", data.totalFuelCost)}원")
            PillarMetricItem(label = "이동 활동 레벨", value = data.mobilityActivityLevel)
        }
    }
}

@Composable
private fun GolfPillarCard(data: GolfAthleticTrajectory) {
    PillarCardLayout(
        title = "골프 & 체력 (Athletic Trajectory)",
        icon = Icons.Default.SportsGolf,
        accentColor = MenuColors.golf
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(label = "라운드 횟수", value = "${data.roundCount}회")
            PillarMetricItem(
                label = "평균 타수 / 베스트",
                value = if (data.averageScore > 0) "${data.averageScore}타 / ${data.bestScore ?: "-"}타" else "기록 대기"
            )
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(
                label = "평균 퍼트 수",
                value = data.averagePutts?.let { "${it}개" } ?: "-"
            )
            PillarMetricItem(
                label = "필드 누적 걸음수",
                value = if (data.totalFieldSteps > 0) "${String.format("%,d", data.totalFieldSteps)}보" else "기록 없음"
            )
        }
    }
}

@Composable
private fun SpatialPillarCard(data: SpatioTemporalFootprint) {
    PillarCardLayout(
        title = "시공간 풋프린트 (Spatial Footprint)",
        icon = Icons.Default.Map,
        accentColor = MenuColors.diary
    ) {
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(label = "기록된 일수", value = "${data.recordedDays}일")
            PillarMetricItem(label = "방문 클러스터", value = "${data.placeClusterCount}개소")
        }
        Spacer(modifier = Modifier.height(Spacing.xs))
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            PillarMetricItem(label = "아카이브 사진", value = "${data.totalPhotosArchived}장")
            PillarMetricItem(label = "주 활동 거점", value = data.primaryArea)
        }
    }
}

@Composable
private fun PillarCardLayout(
    title: String,
    icon: ImageVector,
    accentColor: Color,
    content: @Composable () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = AppColors.surface),
        shape = AppShapes.card,
        border = androidx.compose.foundation.BorderStroke(1.dp, AppColors.border)
    ) {
        Column(modifier = Modifier.padding(Spacing.sm)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.padding(bottom = Spacing.xs)
            ) {
                Box(
                    modifier = Modifier
                        .size(22.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(accentColor.copy(alpha = 0.12f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(14.dp)
                    )
                }
                Text(
                    text = title,
                    style = AppTypography.h3,
                    color = AppColors.textPrimary
                )
            }
            content()
        }
    }
}

@Composable
private fun PillarMetricItem(label: String, value: String) {
    Column {
        Text(
            text = label,
            style = AppTypography.caption,
            color = AppColors.textSecondary
        )
        Text(
            text = value,
            style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold),
            color = AppColors.textPrimary,
            modifier = Modifier.padding(top = 1.dp)
        )
    }
}
