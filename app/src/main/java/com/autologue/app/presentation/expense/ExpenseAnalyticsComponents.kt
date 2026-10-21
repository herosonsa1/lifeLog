package com.autologue.app.presentation.expense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.MetricBadge
import com.autologue.app.presentation.theme.*
import kotlin.math.abs

// 엔터프라이즈 라이트 테마용 저채도 시맨틱 컬러 팔레트
private val IncreaseRoseText = Color(0xFFB91C1C)
private val IncreaseRoseBg = Color(0xFFFEF2F2)
private val IncreaseRoseBorder = Color(0xFFFECACA)
private val IncreaseBarFill = Color(0xFFDC2626)

private val DecreaseTealText = Color(0xFF0F766E)
private val DecreaseTealBg = Color(0xFFF0FDFA)
private val DecreaseTealBorder = Color(0xFF99F6E4)
private val DecreaseBarFill = Color(0xFF0D9488)

private val PrimarySlateFill = Color(0xFF1E293B)
private val ComparisonNeutralFill = Color(0xFFCBD5E1)
private val ComparisonNeutralStroke = Color(0xFF64748B)
private val AccentOutlineBlue = Color(0xFF2563EB)
private val AccentSoftBlueBg = Color(0xFFF8FAFC)

@Composable
fun ExpensePeriodComparisonSection(
    report: ExpensePeriodComparisonReport,
    selectedSubTab: ExpenseAnalyticsSubTab,
    onSelectSubTab: (ExpenseAnalyticsSubTab) -> Unit,
    selectedSortMode: ExpenseDeltaSortMode,
    onSelectSortMode: (ExpenseDeltaSortMode) -> Unit,
    onSelectPeriodUnit: (AnalyticsPeriodUnit) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onSelectComparisonBasis: (AnalyticsComparisonBasis) -> Unit,
    onNavigateToCategoryTransactions: (String, PeriodSpec) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // 1. 주기 선택 탭 바: [월간] | [분기별] | [반기별] | [연간]
        PeriodUnitTabSelector(
            selectedUnit = report.unit,
            onSelectUnit = onSelectPeriodUnit
        )

        // 2. 기준 기간 이동 네비게이터 + 비교 기준 전환 ([직전 기간 대비] vs [전년 동기 대비])
        PeriodNavigationAndBasisBar(
            currentPeriod = report.currentPeriod,
            effectiveBasis = report.effectiveBasis,
            onPrevious = onPreviousPeriod,
            onNext = onNextPeriod,
            onSelectBasis = onSelectComparisonBasis
        )

        // 3. 분석 화면 서브탭: [종합 비교 · 인사이트] | [항목별 증감 도표 (N개)]
        AnalyticsSubViewTabBar(
            selectedSubTab = selectedSubTab,
            categoryCount = report.categoryDeltas.size,
            onSelectSubTab = onSelectSubTab
        )

        when (selectedSubTab) {
            ExpenseAnalyticsSubTab.OVERVIEW_INSIGHT -> {
                // (A) 총 지출 증감 & 전월·전년 동시 비교 요약 카드
                ExpenseTotalComparisonOverviewCard(report = report)

                // (B) 가장 변경폭이 큰 지출 항목 강조 & 핵심 인사이트 진단 카드
                ExpenseTopChangeAndInsightCard(
                    report = report,
                    onJumpToDeltaTable = {
                        onSelectSubTab(ExpenseAnalyticsSubTab.CATEGORY_DELTA_TABLE)
                    },
                    onNavigateToCategory = { catName ->
                        onNavigateToCategoryTransactions(catName, report.currentPeriod)
                    }
                )

                // (C) 최근 주기별 총 지출 추이 막대 도표
                ExpensePeriodTrendBarChartCard(report = report)
            }

            ExpenseAnalyticsSubTab.CATEGORY_DELTA_TABLE -> {
                val sortedItems = remember(report.categoryDeltas, selectedSortMode) {
                    ExpenseAnalyticsEngine.sortCategoryDeltas(report.categoryDeltas, selectedSortMode)
                }

                // (D) 항목별 증감 비교 도표 (이중 수평 막대 + 최대 변동 항목 강조)
                ExpenseCategoryDeltaTableCard(
                    report = report,
                    sortedItems = sortedItems,
                    selectedSortMode = selectedSortMode,
                    onSelectSortMode = onSelectSortMode,
                    onNavigateToCategory = { catName ->
                        onNavigateToCategoryTransactions(catName, report.currentPeriod)
                    }
                )
            }
        }
    }
}

@Composable
fun PeriodUnitTabSelector(
    selectedUnit: AnalyticsPeriodUnit,
    onSelectUnit: (AnalyticsPeriodUnit) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(38.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Slate100)
            .border(1.dp, Slate200, RoundedCornerShape(10.dp))
            .padding(3.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AnalyticsPeriodUnit.entries.forEach { unit ->
            val isSelected = selectedUnit == unit
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(7.dp))
                    .background(if (isSelected) PureWhite else Color.Transparent)
                    .then(
                        if (isSelected) Modifier.border(1.dp, Slate300, RoundedCornerShape(7.dp))
                        else Modifier
                    )
                    .clickable { onSelectUnit(unit) },
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = unit.shortLabel,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) Slate900 else Slate600
                )
            }
        }
    }
}

@Composable
fun PeriodNavigationAndBasisBar(
    currentPeriod: PeriodSpec,
    effectiveBasis: AnalyticsComparisonBasis,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onSelectBasis: (AnalyticsComparisonBasis) -> Unit
) {
    Surface(
        shape = RoundedCornerShape(12.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 좌측: 기간 네비게이터 (< 2026년 4분기 (10~12월) >)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Slate50,
                    border = BorderStroke(1.dp, Slate200)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        IconButton(
                            onClick = onPrevious,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronLeft,
                                contentDescription = "이전 기간",
                                tint = Slate700,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Text(
                            text = currentPeriod.displayTitle,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        )
                        IconButton(
                            onClick = onNext,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = "다음 기간",
                                tint = Slate700,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                }

                // 우측: 비교 기준 전환 (연간이 아닐 때 [직전 대비] | [전년 대비] 토글)
                if (currentPeriod.unit != AnalyticsPeriodUnit.YEARLY) {
                    Row(
                        modifier = Modifier
                            .height(30.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Slate100)
                            .border(1.dp, Slate200, RoundedCornerShape(8.dp))
                            .padding(2.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val seqSelected = effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD
                        val yoySelected = effectiveBasis == AnalyticsComparisonBasis.YEAR_OVER_YEAR

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (seqSelected) PureWhite else Color.Transparent)
                                .then(
                                    if (seqSelected) Modifier.border(1.dp, Slate300, RoundedCornerShape(6.dp))
                                    else Modifier
                                )
                                .clickable { onSelectBasis(AnalyticsComparisonBasis.PREVIOUS_PERIOD) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = currentPeriod.sequentialBasisLabel(),
                                fontSize = 11.sp,
                                fontWeight = if (seqSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (seqSelected) Slate900 else Slate600
                            )
                        }

                        Box(
                            modifier = Modifier
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (yoySelected) PureWhite else Color.Transparent)
                                .then(
                                    if (yoySelected) Modifier.border(1.dp, Slate300, RoundedCornerShape(6.dp))
                                    else Modifier
                                )
                                .clickable { onSelectBasis(AnalyticsComparisonBasis.YEAR_OVER_YEAR) }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "전년 대비",
                                fontSize = 11.sp,
                                fontWeight = if (yoySelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (yoySelected) Slate900 else Slate600
                            )
                        }
                    }
                } else {
                    MetricBadge(
                        text = "전년도 비교 기준",
                        textColor = Slate700,
                        backgroundColor = Slate100
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalyticsSubViewTabBar(
    selectedSubTab: ExpenseAnalyticsSubTab,
    categoryCount: Int,
    onSelectSubTab: (ExpenseAnalyticsSubTab) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ExpenseAnalyticsSubTab.entries.forEach { tab ->
            val isSelected = selectedSubTab == tab
            val labelText = when (tab) {
                ExpenseAnalyticsSubTab.OVERVIEW_INSIGHT -> tab.label
                ExpenseAnalyticsSubTab.CATEGORY_DELTA_TABLE -> "${tab.label} (${categoryCount}개)"
            }
            Surface(
                onClick = { onSelectSubTab(tab) },
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) Slate900 else PureWhite,
                border = BorderStroke(1.dp, if (isSelected) Slate900 else Slate200),
                modifier = Modifier
                    .weight(1f)
                    .height(36.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Text(
                        text = labelText,
                        fontSize = 12.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isSelected) PureWhite else Slate700
                    )
                }
            }
        }
    }
}

@Composable
private fun ExpenseTotalComparisonOverviewCard(
    report: ExpensePeriodComparisonReport
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 상단 헤더: 선택 기간 타이틀 및 결제 건수
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${report.currentPeriod.displayTitle} 총 지출",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Slate600
                )
                MetricBadge(
                    text = "총 ${report.currentTxCount}건 · 일평균 %,d원".format(report.currentDailyAverage),
                    textColor = Slate700,
                    backgroundColor = Slate100
                )
            }

            // 메인 총 지출 금액 + 활성 비교 기준 증감 배지
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Text(
                    text = "%,d원".format(report.currentTotalExpense),
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )

                val activeDelta = report.activeDeltaAmount
                val activePctStr = ExpenseAnalyticsEngine.formatSignedPercent(
                    report.activeDeltaPercent,
                    report.currentTotalExpense,
                    report.activeComparisonTotalExpense
                )
                val badgeBg = when {
                    activeDelta > 0L -> IncreaseRoseBg
                    activeDelta < 0L -> DecreaseTealBg
                    else -> Slate100
                }
                val badgeBorder = when {
                    activeDelta > 0L -> IncreaseRoseBorder
                    activeDelta < 0L -> DecreaseTealBorder
                    else -> Slate200
                }
                val badgeTextCol = when {
                    activeDelta > 0L -> IncreaseRoseText
                    activeDelta < 0L -> DecreaseTealText
                    else -> Slate700
                }
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = badgeBg,
                    border = BorderStroke(1.dp, badgeBorder)
                ) {
                    Text(
                        text = "${ExpenseAnalyticsEngine.formatSignedAmount(activeDelta)} ($activePctStr)",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextCol,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }
            }

            // 보조 수치 서브카드 그룹: [직전 기간 대비] vs [전년 동기 대비] 나란히 비교
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 1) 직전 기간 대비 서브카드 (월간: 전월, 분기: 전분기, 반기: 전반기, 연간: 전년)
                val isSeqFocused = report.effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD
                DeltaSummarySubCard(
                    title = "${report.currentPeriod.sequentialBasisLabel()} (${report.previousPeriod.compactTitle})",
                    baselineAmount = report.previousTotalExpense,
                    deltaAmount = report.seqDeltaAmount,
                    deltaPercent = report.seqDeltaPercent,
                    currentAmount = report.currentTotalExpense,
                    isFocused = isSeqFocused,
                    modifier = Modifier.weight(1f)
                )

                // 2) 전년 동기 대비 서브카드 (월간: 전년 동월, 분기: 전년 동분기, 반기: 전년 동반기, 연간: 1일평균 비교)
                if (report.unit != AnalyticsPeriodUnit.YEARLY) {
                    val isYoyFocused = report.effectiveBasis == AnalyticsComparisonBasis.YEAR_OVER_YEAR
                    DeltaSummarySubCard(
                        title = "${report.currentPeriod.yoyBasisLabel()} (${report.yoyPeriod.compactTitle})",
                        baselineAmount = report.yoyTotalExpense,
                        deltaAmount = report.yoyDeltaAmount,
                        deltaPercent = report.yoyDeltaPercent,
                        currentAmount = report.currentTotalExpense,
                        isFocused = isYoyFocused,
                        modifier = Modifier.weight(1f)
                    )
                } else {
                    val dailyDelta = report.currentDailyAverage - report.activeComparisonDailyAverage
                    val dailyPct = ExpenseAnalyticsEngine.calculatePercentChange(
                        report.currentDailyAverage,
                        report.activeComparisonDailyAverage
                    )
                    DeltaSummarySubCard(
                        title = "일평균 지출 비교 (${report.previousPeriod.compactTitle})",
                        baselineAmount = report.activeComparisonDailyAverage,
                        deltaAmount = dailyDelta,
                        deltaPercent = dailyPct,
                        currentAmount = report.currentDailyAverage,
                        isFocused = false,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}

@Composable
private fun DeltaSummarySubCard(
    title: String,
    baselineAmount: Long,
    deltaAmount: Long,
    deltaPercent: Float?,
    currentAmount: Long,
    isFocused: Boolean,
    modifier: Modifier = Modifier
) {
    val pctText = ExpenseAnalyticsEngine.formatSignedPercent(deltaPercent, currentAmount, baselineAmount)
    val deltaColor = when {
        deltaAmount > 0L -> IncreaseRoseText
        deltaAmount < 0L -> DecreaseTealText
        else -> Slate700
    }
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isFocused) AccentSoftBlueBg else Slate50,
        border = BorderStroke(
            width = if (isFocused) 1.5.dp else 1.dp,
            color = if (isFocused) AccentOutlineBlue else Slate200
        ),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(10.dp),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                text = title,
                fontSize = 11.sp,
                fontWeight = if (isFocused) FontWeight.Bold else FontWeight.Medium,
                color = if (isFocused) AccentOutlineBlue else Slate600,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = if (baselineAmount > 0L || currentAmount > 0L) {
                    "${ExpenseAnalyticsEngine.formatSignedAmount(deltaAmount)} ($pctText)"
                } else {
                    "기록 없음"
                },
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = deltaColor,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = "비교 기준액: %,d원".format(baselineAmount),
                fontSize = 10.5.sp,
                color = Slate500,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun ExpenseTopChangeAndInsightCard(
    report: ExpensePeriodComparisonReport,
    onJumpToDeltaTable: () -> Unit,
    onNavigateToCategory: (String) -> Unit
) {
    val largest = report.largestChangeItem
    val topInc = report.topIncreaseItem
    val topDec = report.topDecreaseItem

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "최대 변동 지출 강조 및 소비 인사이트",
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = Slate900
                )
                MetricBadge(
                    text = if (report.effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) {
                        report.currentPeriod.sequentialBasisLabel()
                    } else {
                        report.currentPeriod.yoyBasisLabel()
                    },
                    textColor = AccentOutlineBlue,
                    backgroundColor = Color(0xFFEFF6FF)
                )
            }

            // 1. 가장 변경폭이 큰 지출 항목 하이라이트 박스 (Clean Outline 강조)
            if (largest != null) {
                val isInc = largest.deltaAmount >= 0L
                val outlineCol = if (isInc) IncreaseBarFill else DecreaseBarFill
                val tintBg = if (isInc) IncreaseRoseBg else DecreaseTealBg
                val textCol = if (isInc) IncreaseRoseText else DecreaseTealText
                val pctStr = ExpenseAnalyticsEngine.formatSignedPercent(
                    largest.deltaPercent,
                    largest.currentAmount,
                    largest.comparisonAmount
                )

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = tintBg,
                    border = BorderStroke(1.5.dp, outlineCol),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToCategory(largest.categoryName) }
                ) {
                    Column(
                        modifier = Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    shape = RoundedCornerShape(5.dp),
                                    color = outlineCol
                                ) {
                                    Text(
                                        text = if (isInc) "최대 지출 증가 항목" else "최대 지출 절감 항목",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PureWhite,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                    )
                                }
                                Text(
                                    text = largest.categoryName,
                                    fontSize = 15.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate900
                                )
                            }
                            Text(
                                text = "${ExpenseAnalyticsEngine.formatSignedAmount(largest.deltaAmount)} ($pctStr)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = textCol
                            )
                        }

                        Text(
                            text = "비교 기간 %,d원(${largest.comparisonTxCount}건) → 이번 기간 %,d원(${largest.currentTxCount}건) · 전체 지출의 %.1f%%".format(
                                largest.comparisonAmount,
                                largest.currentAmount,
                                largest.currentSharePercent
                            ),
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = Slate700
                        )

                        Text(
                            text = "원인 진단: ${largest.changeCauseSummary}",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate800
                        )

                        if (largest.topMerchantsSummary.isNotBlank()) {
                            Text(
                                text = "주요 결제처: ${largest.topMerchantsSummary}",
                                fontSize = 11.sp,
                                color = Slate600
                            )
                        }
                    }
                }
            }

            // 2. [최대 증가] vs [최대 절감] 보조 비교 서브카드 (양쪽 모두 존재할 때 한눈에 대조)
            if (topInc != null && topDec != null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToCategory(topInc.categoryName) }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("가장 늘어난 항목", fontSize = 10.5.sp, color = Slate500, fontWeight = FontWeight.Medium)
                            Text(
                                text = topInc.categoryName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${ExpenseAnalyticsEngine.formatSignedAmount(topInc.deltaAmount)} (${
                                    ExpenseAnalyticsEngine.formatSignedPercent(topInc.deltaPercent, topInc.currentAmount, topInc.comparisonAmount)
                                })",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = IncreaseRoseText
                            )
                        }
                    }

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Slate50,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier
                            .weight(1f)
                            .clickable { onNavigateToCategory(topDec.categoryName) }
                    ) {
                        Column(
                            modifier = Modifier.padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(3.dp)
                        ) {
                            Text("가장 줄어든 항목", fontSize = 10.5.sp, color = Slate500, fontWeight = FontWeight.Medium)
                            Text(
                                text = topDec.categoryName,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate900,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                text = "${ExpenseAnalyticsEngine.formatSignedAmount(topDec.deltaAmount)} (${
                                    ExpenseAnalyticsEngine.formatSignedPercent(topDec.deltaPercent, topDec.currentAmount, topDec.comparisonAmount)
                                })",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = DecreaseTealText
                            )
                        }
                    }
                }
            }

            // 3. 구조화된 3줄 핵심 인사이트 리포트
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Slate50,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "소비 변동 핵심 인사이트",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate800
                    )
                    InsightBulletRow(label = "총평", text = report.headlineInsight)
                    InsightBulletRow(label = "원인", text = report.causeAnalysisInsight)
                    InsightBulletRow(label = "전년비교", text = report.yoyComparisonInsight)
                }
            }

            // 4. 항목별 증감 도표 탭 바로가기 버튼
            Surface(
                onClick = onJumpToDeltaTable,
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFEFF6FF),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "전체 카테고리(${report.categoryDeltas.size}개) 전월·전년 증감 도표 상세 보기",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOutlineBlue
                    )
                    Text(
                        text = "도표 이동 >",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = AccentOutlineBlue
                    )
                }
            }
        }
    }
}

@Composable
private fun InsightBulletRow(
    label: String,
    text: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = PureWhite,
            border = BorderStroke(1.dp, Slate300),
            modifier = Modifier.padding(top = 1.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold,
                color = Slate700,
                modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
            )
        }
        Text(
            text = text,
            fontSize = 12.sp,
            color = Slate800,
            lineHeight = 17.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ExpensePeriodTrendBarChartCard(
    report: ExpensePeriodComparisonReport
) {
    val bars = report.trendBars
    val maxExpense = bars.maxOfOrNull { it.totalExpense }?.coerceAtLeast(1L) ?: 1L
    val nonZeroBars = bars.filter { it.totalExpense > 0L }
    val avgExpense = if (nonZeroBars.isNotEmpty()) {
        nonZeroBars.sumOf { it.totalExpense } / nonZeroBars.size
    } else 0L

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "최근 ${bars.size}개 ${report.unit.shortLabel} 총 지출 추이 도표",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = "진한 막대: 선택 기간 · 점선 테두리: 비교 기준 기간",
                        fontSize = 11.sp,
                        color = Slate500
                    )
                }
                if (avgExpense > 0L) {
                    MetricBadge(
                        text = "평균 %,d원".format(avgExpense),
                        textColor = Slate700,
                        backgroundColor = Slate100
                    )
                }
            }

            // 수직 막대 차트 + 상단 수치 직접 정렬
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(176.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(Slate50)
                    .border(1.dp, Slate200, RoundedCornerShape(10.dp))
                    .padding(horizontal = 10.dp, vertical = 12.dp)
            ) {
                // 평균 지출 점선 가이드라인
                if (avgExpense in 1L..maxExpense) {
                    val avgRatio = (avgExpense.toFloat() / maxExpense.toFloat()).coerceIn(0f, 1f)
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 22.dp, bottom = 24.dp)
                    ) {
                        val y = size.height * (1f - avgRatio)
                        drawLine(
                            color = Color(0xFF94A3B8),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx(),
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f)
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    bars.forEach { bar ->
                        val ratio = if (maxExpense > 0L) {
                            (bar.totalExpense.toFloat() / maxExpense.toFloat()).coerceIn(0f, 1f)
                        } else 0f

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // 막대 바로 위에 주요 수치 직접 배치
                            Text(
                                text = formatCompactKoreanWon(bar.totalExpense),
                                fontSize = 10.sp,
                                fontWeight = if (bar.isSelectedCurrent || bar.isComparisonTarget) FontWeight.Bold else FontWeight.Medium,
                                color = when {
                                    bar.isSelectedCurrent -> Slate900
                                    bar.isComparisonTarget -> AccentOutlineBlue
                                    else -> Slate600
                                },
                                maxLines = 1,
                                textAlign = TextAlign.Center
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                contentAlignment = Alignment.BottomCenter
                            ) {
                                val barHeightFraction = if (bar.totalExpense > 0L) ratio.coerceAtLeast(0.06f) else 0.02f
                                val fillCol = when {
                                    bar.isSelectedCurrent -> PrimarySlateFill
                                    bar.isComparisonTarget -> Color(0xFFDBEAFE)
                                    else -> ComparisonNeutralFill
                                }

                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.68f)
                                        .fillMaxHeight(barHeightFraction)
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val cr = CornerRadius(6.dp.toPx(), 6.dp.toPx())
                                        drawRoundRect(
                                            color = fillCol,
                                            cornerRadius = cr,
                                            size = Size(size.width, size.height)
                                        )
                                        if (bar.isComparisonTarget) {
                                            drawRoundRect(
                                                color = AccentOutlineBlue,
                                                cornerRadius = cr,
                                                size = Size(size.width, size.height),
                                                style = Stroke(
                                                    width = 1.5.dp.toPx(),
                                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 4f), 0f)
                                                )
                                            )
                                        } else if (bar.isSelectedCurrent) {
                                            drawRoundRect(
                                                color = Slate900,
                                                cornerRadius = cr,
                                                size = Size(size.width, size.height),
                                                style = Stroke(width = 1.5.dp.toPx())
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = bar.period.shortChartLabel,
                                fontSize = 11.sp,
                                fontWeight = if (bar.isSelectedCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (bar.isSelectedCurrent) Slate900 else Slate600,
                                maxLines = 1
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ExpenseCategoryDeltaTableCard(
    report: ExpensePeriodComparisonReport,
    sortedItems: List<CategoryDeltaItem>,
    selectedSortMode: ExpenseDeltaSortMode,
    onSelectSortMode: (ExpenseDeltaSortMode) -> Unit,
    onNavigateToCategory: (String) -> Unit
) {
    val maxCategoryAmount = remember(sortedItems) {
        sortedItems.maxOfOrNull { maxOf(it.currentAmount, it.comparisonAmount) }?.coerceAtLeast(1L) ?: 1L
    }

    Surface(
        shape = RoundedCornerShape(14.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // 상단 타이틀 및 범례
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "항목별 지출 증감 비교 도표",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900
                    )
                    Text(
                        text = "${report.currentPeriod.compactTitle} vs ${report.activeComparisonPeriod.compactTitle}",
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = AccentOutlineBlue
                    )
                }
                Text(
                    text = "상단 막대: ${report.currentPeriod.compactTitle} (현재) · 하단 막대: ${report.activeComparisonPeriod.compactTitle} (비교 기준)",
                    fontSize = 11.sp,
                    color = Slate500
                )
            }

            // 정렬 기준 필터 칩
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                ExpenseDeltaSortMode.entries.forEach { mode ->
                    val isSelected = selectedSortMode == mode
                    Surface(
                        onClick = { onSelectSortMode(mode) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isSelected) Slate900 else Slate50,
                        border = BorderStroke(1.dp, if (isSelected) Slate900 else Slate200)
                    ) {
                        Text(
                            text = mode.label,
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PureWhite else Slate700,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }
            }

            HairlineDivider()

            if (sortedItems.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "선택한 기간 및 비교 기간에 집계된 카테고리 지출 내역이 없습니다.",
                        fontSize = 12.5.sp,
                        color = Slate500
                    )
                }
            } else {
                sortedItems.forEachIndexed { index, item ->
                    CategoryDeltaComparisonRow(
                        rank = index + 1,
                        item = item,
                        currentLabel = report.currentPeriod.compactTitle,
                        comparisonLabel = report.activeComparisonPeriod.compactTitle,
                        yoyLabel = report.yoyPeriod.compactTitle,
                        showYoySubMetric = report.effectiveBasis != AnalyticsComparisonBasis.YEAR_OVER_YEAR,
                        maxScaleAmount = maxCategoryAmount,
                        onClickCategory = { onNavigateToCategory(item.categoryName) }
                    )
                    if (index < sortedItems.lastIndex) {
                        HairlineDivider(modifier = Modifier.padding(vertical = 4.dp))
                    }
                }
            }
        }
    }
}

@Composable
private fun CategoryDeltaComparisonRow(
    rank: Int,
    item: CategoryDeltaItem,
    currentLabel: String,
    comparisonLabel: String,
    yoyLabel: String,
    showYoySubMetric: Boolean,
    maxScaleAmount: Long,
    onClickCategory: () -> Unit
) {
    val isHighlighted = item.isLargestMagnitudeChange || item.isLargestIncrease || item.isLargestDecrease
    val isIncrease = item.deltaAmount > 0L
    val isDecrease = item.deltaAmount < 0L

    val borderStroke = when {
        item.isLargestMagnitudeChange && isIncrease -> BorderStroke(1.5.dp, IncreaseBarFill)
        item.isLargestMagnitudeChange && isDecrease -> BorderStroke(1.5.dp, DecreaseBarFill)
        item.isLargestIncrease -> BorderStroke(1.dp, IncreaseRoseBorder)
        item.isLargestDecrease -> BorderStroke(1.dp, DecreaseTealBorder)
        else -> BorderStroke(1.dp, Slate200)
    }

    val containerBg = when {
        item.isLargestMagnitudeChange && isIncrease -> IncreaseRoseBg.copy(alpha = 0.55f)
        item.isLargestMagnitudeChange && isDecrease -> DecreaseTealBg.copy(alpha = 0.55f)
        else -> PureWhite
    }

    val pctText = ExpenseAnalyticsEngine.formatSignedPercent(
        item.deltaPercent,
        item.currentAmount,
        item.comparisonAmount
    )

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = containerBg,
        border = borderStroke,
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClickCategory() }
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            // 1행: 순위 + 카테고리명 + 강조 배지 + 우측 증감액 및 증감률(%)
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
                            .background(if (isHighlighted) Slate900 else Slate200),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "$rank",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isHighlighted) PureWhite else Slate700
                        )
                    }
                    Text(
                        text = item.categoryName,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate900,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (item.isLargestMagnitudeChange) {
                        MetricBadge(
                            text = "최대 변동",
                            textColor = PureWhite,
                            backgroundColor = if (isIncrease) IncreaseBarFill else DecreaseBarFill
                        )
                    } else if (item.isLargestIncrease) {
                        MetricBadge(
                            text = "최대 증가",
                            textColor = IncreaseRoseText,
                            backgroundColor = IncreaseRoseBg
                        )
                    } else if (item.isLargestDecrease) {
                        MetricBadge(
                            text = "최대 절감",
                            textColor = DecreaseTealText,
                            backgroundColor = DecreaseTealBg
                        )
                    }
                }

                val deltaCol = when {
                    isIncrease -> IncreaseRoseText
                    isDecrease -> DecreaseTealText
                    else -> Slate600
                }
                val pctStr = ExpenseAnalyticsEngine.formatSignedPercent(
                    item.deltaPercent,
                    item.currentAmount,
                    item.comparisonAmount
                )
                Text(
                    text = "${ExpenseAnalyticsEngine.formatSignedAmount(item.deltaAmount)} ($pctStr)",
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Bold,
                    color = deltaCol
                )
            }

            // 2행: 이중 수평 막대 도표 (현재 기간 vs 비교 기간) + 우측 수치 직접 정렬
            val curRatio = (item.currentAmount.toFloat() / maxScaleAmount.toFloat()).coerceIn(0f, 1f)
            val compRatio = (item.comparisonAmount.toFloat() / maxScaleAmount.toFloat()).coerceIn(0f, 1f)
            val curBarColor = when {
                isIncrease -> IncreaseBarFill
                isDecrease -> DecreaseBarFill
                else -> PrimarySlateFill
            }

            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                // 현재 기간 바
                DualComparisonBarRow(
                    periodLabel = currentLabel,
                    ratio = curRatio,
                    amount = item.currentAmount,
                    txCount = item.currentTxCount,
                    sharePercent = item.currentSharePercent,
                    barColor = curBarColor,
                    isCurrent = true
                )
                // 비교 기간 바
                DualComparisonBarRow(
                    periodLabel = comparisonLabel,
                    ratio = compRatio,
                    amount = item.comparisonAmount,
                    txCount = item.comparisonTxCount,
                    sharePercent = item.comparisonSharePercent,
                    barColor = ComparisonNeutralFill,
                    isCurrent = false
                )
            }

            // 3행: 소프트 틴트 서브카드 (변동 원인 + 전년 동기 참고 수치 + 내역 이동)
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Slate50,
                border = BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = item.changeCauseSummary,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate700,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = "내역 보기 >",
                            fontSize = 10.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = AccentOutlineBlue
                        )
                    }
                    if (showYoySubMetric && item.yoyAmount > 0L) {
                        val yoyDiff = item.currentAmount - item.yoyAmount
                        val yoyPct = ExpenseAnalyticsEngine.calculatePercentChange(item.currentAmount, item.yoyAmount)
                        val yoyAmtFormatted = "%,d원".format(item.yoyAmount)
                        val yoyDiffFormatted = ExpenseAnalyticsEngine.formatSignedAmount(yoyDiff)
                        val yoyPctFormatted = ExpenseAnalyticsEngine.formatSignedPercent(yoyPct, item.currentAmount, item.yoyAmount)
                        Text(
                            text = "전년 동기($yoyLabel) $yoyAmtFormatted 대비: $yoyDiffFormatted ($yoyPctFormatted)",
                            fontSize = 10.5.sp,
                            color = Slate600
                        )
                    }
                    if (item.topMerchantsSummary.isNotBlank()) {
                        Text(
                            text = "주요 가맹점: ${item.topMerchantsSummary}",
                            fontSize = 10.5.sp,
                            color = Slate500,
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
private fun DualComparisonBarRow(
    periodLabel: String,
    ratio: Float,
    amount: Long,
    txCount: Int,
    sharePercent: Float,
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
            fontSize = 11.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) Slate800 else Slate500,
            modifier = Modifier.width(62.dp),
            maxLines = 1
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(Slate100)
        ) {
            val widthFraction = if (amount > 0L) ratio.coerceAtLeast(0.04f) else 0f
            if (widthFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(widthFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(barColor)
                        .then(
                            if (!isCurrent) Modifier.border(1.dp, ComparisonNeutralStroke, RoundedCornerShape(4.dp))
                            else Modifier
                        )
                )
            }
        }

        // 막대 우측에 금액·건수·비중 직접 정렬
        Text(
            text = "%,d원 (%d건 · %.0f%%)".format(amount, txCount, sharePercent),
            fontSize = 11.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) Slate900 else Slate600,
            textAlign = TextAlign.End,
            modifier = Modifier.width(136.dp),
            maxLines = 1
        )
    }
}

fun formatCompactKoreanWon(amount: Long): String {
    if (amount <= 0L) return "0원"
    return when {
        amount >= 100_000_000L -> "%.1f억".format(amount / 100_000_000.0)
        amount >= 10_000L -> "%,d만".format(amount / 10_000L)
        else -> "%,d원".format(amount)
    }
}
