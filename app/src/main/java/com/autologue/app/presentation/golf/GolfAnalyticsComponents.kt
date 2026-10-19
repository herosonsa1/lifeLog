package com.autologue.app.presentation.golf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.MetricBadge
import com.autologue.app.presentation.expense.AnalyticsComparisonBasis
import com.autologue.app.presentation.expense.AnalyticsPeriodUnit
import com.autologue.app.presentation.expense.ExpenseAnalyticsEngine
import com.autologue.app.presentation.expense.PeriodNavigationAndBasisBar
import com.autologue.app.presentation.expense.PeriodUnitTabSelector
import com.autologue.app.presentation.theme.*
import kotlin.math.abs

// 긍정/개선(타수 단축, 라운딩/버디 증가 등): 차분한 포레스트/틸 계열
private val GolfPositiveText = Color(0xFF0F766E)
private val GolfPositiveBg = Color(0xFFF0FDFA)
private val GolfPositiveBorder = Color(0xFF99F6E4)
private val GolfPositiveFill = Color(0xFF0D9488)

// 주의/상승(타수 상승, 벌타 증가, 활동 감소 등): 차분한 로즈/앰버 계열
private val GolfCautionText = Color(0xFFB91C1C)
private val GolfCautionBg = Color(0xFFFEF2F2)
private val GolfCautionBorder = Color(0xFFFECACA)
private val GolfCautionFill = Color(0xFFDC2626)

// 필드 라운딩 / 스크린골프 구분 솔리드 컬러 (저채도 엔터프라이즈 톤)
private val FieldGolfSolidFill = Color(0xFF15803D)
private val ScreenGolfSolidFill = Color(0xFF2563EB)
private val ComparisonMutedFill = Color(0xFFCBD5E1)
private val YoyMutedFill = Color(0xFF94A3B8)

@Composable
fun GolfAnalyticsSection(
    report: GolfPeriodComparisonReport,
    selectedSubTab: GolfAnalyticsSubTab,
    onSelectSubTab: (GolfAnalyticsSubTab) -> Unit,
    onSelectPeriodUnit: (AnalyticsPeriodUnit) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onSelectComparisonBasis: (AnalyticsComparisonBasis) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 주기 선택 탭: [월간] | [분기별] | [반기별] | [연간]
        PeriodUnitTabSelector(
            selectedUnit = report.unit,
            onSelectUnit = onSelectPeriodUnit
        )

        // 2. 기준 기간 이동 네비게이터 + 비교 기준 토글 ([직전 대비] vs [전년 대비])
        PeriodNavigationAndBasisBar(
            currentPeriod = report.currentPeriod,
            effectiveBasis = report.effectiveBasis,
            onPrevious = onPreviousPeriod,
            onNext = onNextPeriod,
            onSelectBasis = onSelectComparisonBasis
        )

        // 3. 골프 통계 서브탭 (한 화면에 모든 표를 넣지 않고 목적별 3개 탭으로 분리)
        GolfAnalyticsSubViewTabBar(
            selectedSubTab = selectedSubTab,
            onSelectSubTab = onSelectSubTab
        )

        when (selectedSubTab) {
            GolfAnalyticsSubTab.OVERVIEW_INSIGHT -> {
                // (A) 필드 라운딩 횟수 · 스크린골프 횟수 · 평균 스코어 3대 핵심 비교 요약 카드
                GolfOverviewTripleSummaryCard(report = report)

                // (B) 최대 변동 항목 강조 및 스코어·활동 인사이트 카드
                GolfTopChangeAndInsightCard(
                    report = report,
                    onJumpToActivityTable = {
                        onSelectSubTab(GolfAnalyticsSubTab.ACTIVITY_COUNT_TABLE)
                    },
                    onJumpToScoreTable = {
                        onSelectSubTab(GolfAnalyticsSubTab.SCORE_SHOT_TABLE)
                    }
                )

                // (C) 최근 주기별(월간/분기/반기/연간) 필드·스크린 횟수 및 평균 타수 추이 도표
                GolfPeriodTrendChartCard(report = report)
            }

            GolfAnalyticsSubTab.ACTIVITY_COUNT_TABLE -> {
                // (D) 라운딩 횟수 · 스크린골프 횟수 · 방문 구장 · 골프 지출 상세 비교 도표
                GolfDeltaTableCard(
                    title = "라운딩 · 스크린골프 활동 비교 도표",
                    subtitle = "${report.currentPeriod.displayTitle} 기준 직전 기간(${report.previousPeriod.compactTitle}) 및 전년 동기(${report.yoyPeriod.compactTitle}) 대조",
                    currentPeriodLabel = report.currentPeriod.compactTitle,
                    comparisonPeriodLabel = report.activeComparisonPeriod.compactTitle,
                    yoyPeriodLabel = report.yoyPeriod.compactTitle,
                    showSeparateYoyBar = report.effectiveBasis != AnalyticsComparisonBasis.YEAR_OVER_YEAR,
                    rows = report.activityDeltaRows
                )
            }

            GolfAnalyticsSubTab.SCORE_SHOT_TABLE -> {
                // (E) 평균 타수 · 최저타 · 퍼트 · 파세이브율 · 버디 · 벌타 · 비거리 상세 비교 도표
                GolfDeltaTableCard(
                    title = "스코어 · 샷 경기력 비교 도표",
                    subtitle = "타수·퍼트·벌타는 낮아질수록 실력 향상(단축), 버디·파세이브·비거리는 높아질수록 향상으로 표시됩니다",
                    currentPeriodLabel = report.currentPeriod.compactTitle,
                    comparisonPeriodLabel = report.activeComparisonPeriod.compactTitle,
                    yoyPeriodLabel = report.yoyPeriod.compactTitle,
                    showSeparateYoyBar = report.effectiveBasis != AnalyticsComparisonBasis.YEAR_OVER_YEAR,
                    rows = report.scoreShotDeltaRows
                )
            }
        }
    }
}

@Composable
private fun GolfAnalyticsSubViewTabBar(
    selectedSubTab: GolfAnalyticsSubTab,
    onSelectSubTab: (GolfAnalyticsSubTab) -> Unit
) {
    Surface(
        color = Slate100,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            GolfAnalyticsSubTab.values().forEach { tab ->
                val isSelected = selectedSubTab == tab
                Surface(
                    onClick = { onSelectSubTab(tab) },
                    shape = RoundedCornerShape(8.dp),
                    color = if (isSelected) PureWhite else Color.Transparent,
                    border = if (isSelected) BorderStroke(1.dp, Slate300) else null,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = tab.label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) Slate900 else Slate600,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun GolfOverviewTripleSummaryCard(
    report: GolfPeriodComparisonReport
) {
    val cur = report.currentSnapshot
    val prev = report.previousSnapshot
    val yoy = report.yoySnapshot
    val seqLabel = report.currentPeriod.sequentialBasisLabel()
    val yoyLabel = report.currentPeriod.yoyBasisLabel()

    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "${report.currentPeriod.displayTitle} 골프 활동 요약",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = "$seqLabel(${report.previousPeriod.compactTitle}) 및 $yoyLabel(${report.yoyPeriod.compactTitle}) 동시 비교",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }
                MetricBadge(
                    text = "총 활동 ${cur.totalActivityCount}회",
                    textColor = MenuColors.golf,
                    backgroundColor = MenuColors.golfBg
                )
            }

            // 3대 핵심 지표: 1) 필드 라운딩 횟수 | 2) 스크린골프 횟수 | 3) 평균 타수(최저타)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1) 필드 라운딩 횟수
                GolfPrimaryMetricBox(
                    title = "필드 라운딩",
                    mainValue = "${cur.fieldRoundCount}회",
                    subLabel = if (cur.distinctClubCount > 0) "방문 구장 ${cur.distinctClubCount}곳" else "정규 구장 라운드",
                    seqText = "$seqLabel ${GolfAnalyticsEngine.formatSignedInt(report.seqFieldCountDelta)}회 (${prev.fieldRoundCount}회)",
                    yoyText = "$yoyLabel ${GolfAnalyticsEngine.formatSignedInt(report.yoyFieldCountDelta)}회 (${yoy.fieldRoundCount}회)",
                    isPositiveDelta = report.seqFieldCountDelta >= 0,
                    accentColor = FieldGolfSolidFill,
                    modifier = Modifier.weight(1f)
                )

                // 2) 스크린골프 횟수
                GolfPrimaryMetricBox(
                    title = "스크린골프",
                    mainValue = "${cur.screenGolfCount}회",
                    subLabel = "스크린·연습장 통합",
                    seqText = "$seqLabel ${GolfAnalyticsEngine.formatSignedInt(report.seqScreenCountDelta)}회 (${prev.screenGolfCount}회)",
                    yoyText = "$yoyLabel ${GolfAnalyticsEngine.formatSignedInt(report.yoyScreenCountDelta)}회 (${yoy.screenGolfCount}회)",
                    isPositiveDelta = report.seqScreenCountDelta >= 0,
                    accentColor = ScreenGolfSolidFill,
                    modifier = Modifier.weight(1f)
                )
            }

            // 3) 평균 스코어 & 최저타(라베) 비교 서브카드
            Surface(
                color = Slate50,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "평균 스코어 및 최저타 비교",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            Text(
                                text = if (cur.bestScore != null) {
                                    "기간 최저타: ${cur.bestScore}타 (${cur.bestScoreClubName ?: "구장"})"
                                } else {
                                    "스코어 기록 라운드 ${cur.scoredRoundCount}회"
                                },
                                fontSize = 11.sp,
                                color = Slate500
                            )
                        }
                        Text(
                            text = cur.averageScore?.let { "${it}타" } ?: "기록 없음",
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (cur.averageScore != null) Slate900 else Slate400
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val seqScoreStr = report.seqAvgScoreDelta?.let { diff ->
                            val sign = if (diff > 0) "+${diff}타" else "${diff}타"
                            "$sign (이전 ${prev.averageScore}타)"
                        } ?: "비교 기록 없음"
                        val yoyScoreStr = report.yoyAvgScoreDelta?.let { diff ->
                            val sign = if (diff > 0) "+${diff}타" else "${diff}타"
                            "$sign (전년 ${yoy.averageScore}타)"
                        } ?: "전년 기록 없음"

                        ScoreComparisonMiniPill(
                            label = seqLabel,
                            valueText = seqScoreStr,
                            isImproved = (report.seqAvgScoreDelta ?: 0.0) < 0.0,
                            isWorse = (report.seqAvgScoreDelta ?: 0.0) > 0.0,
                            modifier = Modifier.weight(1f)
                        )
                        ScoreComparisonMiniPill(
                            label = yoyLabel,
                            valueText = yoyScoreStr,
                            isImproved = (report.yoyAvgScoreDelta ?: 0.0) < 0.0,
                            isWorse = (report.yoyAvgScoreDelta ?: 0.0) > 0.0,
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }

            // 4) 보조 지표 그리드 (평균 퍼트 · 파 세이브율 · 골프 총 지출)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                SecondarySubMetricChip(
                    label = "평균 퍼트",
                    value = cur.averagePutts?.let { "${it}P" } ?: "-",
                    modifier = Modifier.weight(1f)
                )
                SecondarySubMetricChip(
                    label = "파 세이브율",
                    value = cur.parOrBetterRatePercent?.let { "${it}%" } ?: "-",
                    modifier = Modifier.weight(1f)
                )
                SecondarySubMetricChip(
                    label = "골프 관련 지출",
                    value = if (cur.totalGolfExpense > 0L) {
                        "%,d만".format((cur.totalGolfExpense / 10_000L).coerceAtLeast(1L))
                    } else "0원",
                    modifier = Modifier.weight(1f)
                )
            }
        }
    }
}

@Composable
private fun GolfPrimaryMetricBox(
    title: String,
    mainValue: String,
    subLabel: String,
    seqText: String,
    yoyText: String,
    isPositiveDelta: Boolean,
    accentColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Slate50,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, Slate200),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate700
                )
                Box(
                    modifier = Modifier
                        .size(8.dp)
                        .clip(CircleShape)
                        .background(accentColor)
                )
            }
            Text(
                text = mainValue,
                fontSize = 21.sp,
                fontWeight = FontWeight.Bold,
                color = Slate900
            )
            Text(
                text = subLabel,
                fontSize = 10.5.sp,
                color = Slate500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            HairlineDivider()
            Text(
                text = seqText,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = if (isPositiveDelta) GolfPositiveText else Slate600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = yoyText,
                fontSize = 10.5.sp,
                color = Slate600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ScoreComparisonMiniPill(
    label: String,
    valueText: String,
    isImproved: Boolean,
    isWorse: Boolean,
    modifier: Modifier = Modifier
) {
    val bg = when {
        isImproved -> GolfPositiveBg
        isWorse -> GolfCautionBg
        else -> PureWhite
    }
    val borderCol = when {
        isImproved -> GolfPositiveBorder
        isWorse -> GolfCautionBorder
        else -> Slate200
    }
    val txtCol = when {
        isImproved -> GolfPositiveText
        isWorse -> GolfCautionText
        else -> Slate600
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, borderCol),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                color = Slate500
            )
            Text(
                text = valueText,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = txtCol,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun SecondarySubMetricChip(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = Slate50,
        shape = RoundedCornerShape(8.dp),
        border = BorderStroke(1.dp, Slate200),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = label,
                fontSize = 10.5.sp,
                color = Slate500
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = value,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = Slate800
            )
        }
    }
}

@Composable
private fun GolfTopChangeAndInsightCard(
    report: GolfPeriodComparisonReport,
    onJumpToActivityTable: () -> Unit,
    onJumpToScoreTable: () -> Unit
) {
    val topAct = report.topChangedActivityMetric
    val topScore = report.topChangedScoreMetric

    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.5.dp, Slate800),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "핵심 변동 강조 및 골프 라이프 인사이트",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                MetricBadge(
                    text = "자동 원인 진단",
                    textColor = PureWhite,
                    backgroundColor = Slate900
                )
            }

            // 최대 변동 항목 요약 박스
            if (topAct != null || topScore != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (topAct != null) {
                        val deltaStr = GolfAnalyticsEngine.formatSignedDelta(
                            topAct.deltaFromComparison,
                            topAct.unitSuffix,
                            topAct.isIntegerMetric
                        )
                        Surface(
                            color = Slate50,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Slate300),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onJumpToActivityTable() }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "최대 활동 변동",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate500
                                )
                                Text(
                                    text = topAct.title,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate900,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = deltaStr,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = FieldGolfSolidFill
                                )
                            }
                        }
                    }

                    if (topScore != null) {
                        val deltaVal = topScore.deltaFromComparison ?: 0.0
                        val isBetter = if (topScore.lowerIsBetter) deltaVal < 0.0 else deltaVal > 0.0
                        val deltaStr = GolfAnalyticsEngine.formatSignedDelta(
                            topScore.deltaFromComparison,
                            topScore.unitSuffix,
                            topScore.isIntegerMetric
                        )
                        Surface(
                            color = if (isBetter) GolfPositiveBg else GolfCautionBg,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (isBetter) GolfPositiveBorder else GolfCautionBorder),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { onJumpToScoreTable() }
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = if (isBetter) "주요 실력 향상 지표" else "주요 스코어 변동",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isBetter) GolfPositiveText else GolfCautionText
                                )
                                Text(
                                    text = topScore.title,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate900,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = deltaStr,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isBetter) GolfPositiveText else GolfCautionText
                                )
                            }
                        }
                    }
                }
            }

            // 3단 인사이트 문장 박스
            Surface(
                color = Slate50,
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    InsightBulletItem(
                        tag = "활동량 비교",
                        body = report.headlineInsight
                    )
                    HairlineDivider()
                    InsightBulletItem(
                        tag = "스코어 진단",
                        body = report.scoreAnalysisInsight
                    )
                    HairlineDivider()
                    InsightBulletItem(
                        tag = "전년 동기 대비",
                        body = report.yoyComparisonInsight
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightBulletItem(
    tag: String,
    body: String
) {
    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(
            text = tag,
            fontSize = 10.5.sp,
            fontWeight = FontWeight.Bold,
            color = MenuColors.golf
        )
        Text(
            text = body,
            fontSize = 12.sp,
            color = Slate800,
            lineHeight = 17.sp
        )
    }
}

@Composable
private fun GolfPeriodTrendChartCard(
    report: GolfPeriodComparisonReport
) {
    val items = report.trendItems
    val maxCount = (items.maxOfOrNull { it.totalActivityCount } ?: 1).coerceAtLeast(1)

    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "최근 ${items.size}주기 라운딩·스크린 횟수 및 평균 타수 추이",
                        fontSize = 13.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = "막대 상단: 총 횟수(필드+스크린) 및 평균 타수 직접 표기",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    LegendDotLabel(color = FieldGolfSolidFill, label = "필드")
                    LegendDotLabel(color = ScreenGolfSolidFill, label = "스크린")
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(158.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.Bottom
            ) {
                items.forEach { bar ->
                    val ratio = (bar.totalActivityCount.toFloat() / maxCount.toFloat()).coerceIn(0f, 1f)
                    val fieldShare = if (bar.totalActivityCount > 0) {
                        bar.fieldRoundCount.toFloat() / bar.totalActivityCount.toFloat()
                    } else 0f

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight(),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Bottom
                    ) {
                        // 상단 평균 타수 배지
                        Text(
                            text = bar.averageScore?.let { "${it}타" } ?: "-",
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (bar.isSelectedCurrent) MenuColors.golf else Slate500,
                            maxLines = 1
                        )
                        // 총 횟수 직접 정렬
                        Text(
                            text = "${bar.totalActivityCount}회",
                            fontSize = 11.sp,
                            fontWeight = if (bar.isSelectedCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = if (bar.isSelectedCurrent) Slate900 else Slate700
                        )
                        Spacer(modifier = Modifier.height(4.dp))

                        // 스택형 수직 막대 (필드 + 스크린)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth(0.68f)
                                .height((84.dp * ratio).coerceAtLeast(6.dp))
                                .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                .background(
                                    if (bar.totalActivityCount == 0) Slate200 else ScreenGolfSolidFill
                                )
                                .then(
                                    if (bar.isSelectedCurrent) {
                                        Modifier.border(
                                            width = 1.5.dp,
                                            color = Slate900,
                                            shape = RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp)
                                        )
                                    } else Modifier
                                ),
                            contentAlignment = Alignment.BottomCenter
                        ) {
                            if (bar.fieldRoundCount > 0) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .fillMaxHeight(fieldShare.coerceIn(0.15f, 1f))
                                        .background(FieldGolfSolidFill)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = bar.period.shortChartLabel,
                            fontSize = 10.5.sp,
                            fontWeight = if (bar.isSelectedCurrent) FontWeight.Bold else FontWeight.Medium,
                            color = if (bar.isSelectedCurrent) Slate900 else Slate600,
                            maxLines = 1
                        )
                        Text(
                            text = "F${bar.fieldRoundCount}·S${bar.screenGolfCount}",
                            fontSize = 9.sp,
                            color = Slate500,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LegendDotLabel(
    color: Color,
    label: String
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(CircleShape)
                .background(color)
        )
        Text(
            text = label,
            fontSize = 10.5.sp,
            color = Slate600
        )
    }
}

@Composable
private fun GolfDeltaTableCard(
    title: String,
    subtitle: String,
    currentPeriodLabel: String,
    comparisonPeriodLabel: String,
    yoyPeriodLabel: String,
    showSeparateYoyBar: Boolean,
    rows: List<GolfMetricDeltaRow>
) {
    Surface(
        color = PureWhite,
        shape = RoundedCornerShape(14.dp),
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(
                    text = title,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                Text(
                    text = subtitle,
                    fontSize = 11.sp,
                    color = Slate500
                )
            }

            rows.forEachIndexed { idx, row ->
                GolfMetricComparisonItemCard(
                    rank = idx + 1,
                    row = row,
                    currentPeriodLabel = currentPeriodLabel,
                    comparisonPeriodLabel = comparisonPeriodLabel,
                    yoyPeriodLabel = yoyPeriodLabel,
                    showSeparateYoyBar = showSeparateYoyBar
                )
            }
        }
    }
}

@Composable
private fun GolfMetricComparisonItemCard(
    rank: Int,
    row: GolfMetricDeltaRow,
    currentPeriodLabel: String,
    comparisonPeriodLabel: String,
    yoyPeriodLabel: String,
    showSeparateYoyBar: Boolean
) {
    val deltaVal = row.deltaFromComparison ?: 0.0
    val hasChange = row.deltaFromComparison != null && abs(deltaVal) >= 0.05
    val isPositiveOutcome = if (row.lowerIsBetter) deltaVal < -0.04 else deltaVal > 0.04
    val isNegativeOutcome = if (row.lowerIsBetter) deltaVal > 0.04 else deltaVal < -0.04

    val borderStroke = when {
        row.isHighlightedChange && isPositiveOutcome -> BorderStroke(1.5.dp, GolfPositiveFill)
        row.isHighlightedChange && isNegativeOutcome -> BorderStroke(1.5.dp, GolfCautionFill)
        row.isHighlightedChange -> BorderStroke(1.5.dp, Slate800)
        else -> BorderStroke(1.dp, Slate200)
    }

    val maxScale = listOfNotNull(row.currentValue, row.comparisonValue, row.yoyValue)
        .maxOrNull()
        ?.coerceAtLeast(1.0)
        ?: 1.0

    val deltaStr = GolfAnalyticsEngine.formatSignedDelta(
        row.deltaFromComparison,
        row.unitSuffix,
        row.isIntegerMetric
    )
    val pctStr = row.deltaPercentFromComparison?.let { pct ->
        if (pct > 0.04f) "+%.1f%%".format(pct)
        else if (pct < -0.04f) "%.1f%%".format(pct)
        else "0.0%"
    }

    Surface(
        color = if (row.isHighlightedChange) Slate50 else PureWhite,
        shape = RoundedCornerShape(12.dp),
        border = borderStroke,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1행: 번호 + 지표명 + 강조 배지 + 우측 증감 수치
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(if (row.isHighlightedChange) Slate900 else Slate200),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$rank",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (row.isHighlightedChange) PureWhite else Slate700
                        )
                    }
                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = row.title,
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900
                            )
                            if (row.isHighlightedChange) {
                                MetricBadge(
                                    text = "핵심 변동",
                                    textColor = PureWhite,
                                    backgroundColor = if (isPositiveOutcome) GolfPositiveFill else if (isNegativeOutcome) GolfCautionFill else Slate800
                                )
                            }
                        }
                        Text(
                            text = row.subtitle,
                            fontSize = 10.5.sp,
                            color = Slate500
                        )
                    }
                }

                val deltaColor = when {
                    !hasChange -> Slate600
                    isPositiveOutcome -> GolfPositiveText
                    isNegativeOutcome -> GolfCautionText
                    else -> Slate700
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = if (pctStr != null && hasChange) "$deltaStr ($pctStr)" else deltaStr,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = deltaColor
                    )
                    if (showSeparateYoyBar) {
                        val yoyDeltaText = GolfAnalyticsEngine.formatSignedDelta(
                            row.deltaFromYoy,
                            row.unitSuffix,
                            row.isIntegerMetric
                        )
                        Text(
                            text = "전년 대비: $yoyDeltaText",
                            fontSize = 10.sp,
                            color = Slate500
                        )
                    }
                }
            }

            // 2행: 수평 비교 막대 (현재 기간 vs 비교 기간 [+ 전년 동기])
            val curRatio = ((row.currentValue ?: 0.0) / maxScale).toFloat().coerceIn(0f, 1f)
            val compRatio = ((row.comparisonValue ?: 0.0) / maxScale).toFloat().coerceIn(0f, 1f)
            val yoyRatio = ((row.yoyValue ?: 0.0) / maxScale).toFloat().coerceIn(0f, 1f)

            val curBarColor = when {
                isPositiveOutcome -> GolfPositiveFill
                isNegativeOutcome -> GolfCautionFill
                else -> FieldGolfSolidFill
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                GolfHorizontalBarRow(
                    periodLabel = currentPeriodLabel,
                    ratio = curRatio,
                    valueText = GolfAnalyticsEngine.formatMetricValue(row.currentValue, row.unitSuffix, row.isIntegerMetric),
                    barColor = curBarColor,
                    isCurrent = true
                )
                GolfHorizontalBarRow(
                    periodLabel = comparisonPeriodLabel,
                    ratio = compRatio,
                    valueText = GolfAnalyticsEngine.formatMetricValue(row.comparisonValue, row.unitSuffix, row.isIntegerMetric),
                    barColor = ComparisonMutedFill,
                    isCurrent = false
                )
                if (showSeparateYoyBar) {
                    GolfHorizontalBarRow(
                        periodLabel = "전년($yoyPeriodLabel)",
                        ratio = yoyRatio,
                        valueText = GolfAnalyticsEngine.formatMetricValue(row.yoyValue, row.unitSuffix, row.isIntegerMetric),
                        barColor = YoyMutedFill,
                        isCurrent = false
                    )
                }
            }

            // 3행: 보조 설명 서브카드
            if (row.detailNote.isNotBlank()) {
                Surface(
                    color = Slate100,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = row.detailNote,
                        fontSize = 11.sp,
                        color = Slate700,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun GolfHorizontalBarRow(
    periodLabel: String,
    ratio: Float,
    valueText: String,
    barColor: Color,
    isCurrent: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = periodLabel,
            fontSize = 10.5.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) Slate900 else Slate500,
            modifier = Modifier.width(68.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(14.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Slate100),
            contentAlignment = Alignment.CenterStart
        ) {
            if (ratio > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(ratio.coerceAtLeast(0.04f))
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(barColor)
                )
            }
        }

        Text(
            text = valueText,
            fontSize = 11.5.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) Slate900 else Slate600,
            textAlign = TextAlign.End,
            modifier = Modifier.width(72.dp)
        )
    }
}
