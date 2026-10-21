package com.autologue.app.presentation.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.common.EasyTheme
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.LocalEasyMode
import com.autologue.app.presentation.common.MetricBadge
import com.autologue.app.presentation.expense.AnalyticsComparisonBasis
import com.autologue.app.presentation.expense.AnalyticsPeriodUnit
import com.autologue.app.presentation.expense.ExpenseAnalyticsEngine
import com.autologue.app.presentation.expense.PeriodNavigationAndBasisBar
import com.autologue.app.presentation.expense.PeriodUnitTabSelector
import com.autologue.app.presentation.expense.formatCompactKoreanWon
import com.autologue.app.presentation.theme.*
import kotlin.math.abs

private val CarCostIncreaseText = Color(0xFFB91C1C)
private val CarCostIncreaseBg = Color(0xFFFEF2F2)
private val CarCostIncreaseBorder = Color(0xFFFECACA)
private val CarCostIncreaseFill = Color(0xFFDC2626)

private val CarCostDecreaseText = Color(0xFF0F766E)
private val CarCostDecreaseBg = Color(0xFFF0FDFA)
private val CarCostDecreaseBorder = Color(0xFF99F6E4)
private val CarCostDecreaseFill = Color(0xFF0D9488)

private val CarDrivingPrimaryFill = Color(0xFF2563EB)
private val CarDrivingSoftBg = Color(0xFFEFF6FF)
private val CarDrivingBorder = Color(0xFF93C5FD)

private val CarNeutralFill = Color(0xFFCBD5E1)
private val CarNeutralStroke = Color(0xFF64748B)
private val CarDarkSlateFill = Color(0xFF1E293B)

@Composable
fun CarLedgerAnalyticsSection(
    report: CarLedgerComparisonReport,
    selectedSubTab: CarLedgerAnalyticsSubTab,
    onSelectSubTab: (CarLedgerAnalyticsSubTab) -> Unit,
    onSelectPeriodUnit: (AnalyticsPeriodUnit) -> Unit,
    onPreviousPeriod: () -> Unit,
    onNextPeriod: () -> Unit,
    onSelectComparisonBasis: (AnalyticsComparisonBasis) -> Unit,
    onJumpToLogTabWithFilter: (LogFilterMode) -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(
                horizontal = if (isEasyMode) 0.dp else Spacing.xl,
                vertical = if (isEasyMode) 2.dp else Spacing.md
            ),
        verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 14.dp else 12.dp)
    ) {
        // 1. 주기 조건 탭: [월간] | [분기별] | [반기별] | [연간]
        PeriodUnitTabSelector(
            selectedUnit = report.unit,
            onSelectUnit = onSelectPeriodUnit
        )

        // 2. 기준 기간 이동 네비게이터 + 비교 기준 전환 ([직전 대비] vs [전년 대비])
        PeriodNavigationAndBasisBar(
            currentPeriod = report.currentPeriod,
            effectiveBasis = report.effectiveBasis,
            onPrevious = onPreviousPeriod,
            onNext = onNextPeriod,
            onSelectBasis = onSelectComparisonBasis
        )

        // 3. 차계부 통계 뷰 서브탭 (한 화면에 모든 표를 넣지 않고 3개의 탭으로 분리)
        CarAnalyticsSubViewTabBar(
            selectedSubTab = selectedSubTab,
            onSelectSubTab = onSelectSubTab
        )

        when (selectedSubTab) {
            CarLedgerAnalyticsSubTab.OVERVIEW_INSIGHT -> {
                // (A) 총 주행거리(km) & 총 차량유지비(원) 동시 비교 카드
                CarOverviewDualSummaryCard(report = report)

                // (B) 가장 변경폭이 큰 유지비 강조 & 주행-유지비 상관관계 인사이트 카드
                CarTopChangeAndCorrelationInsightCard(
                    report = report,
                    onJumpToCostTable = {
                        onSelectSubTab(CarLedgerAnalyticsSubTab.COST_BREAKDOWN_TABLE)
                    },
                    onJumpToDrivingTable = {
                        onSelectSubTab(CarLedgerAnalyticsSubTab.DRIVING_BREAKDOWN_TABLE)
                    }
                )

                // (C) 최근 주기별 주행거리(km) & 차량유지비(원) 추이 도표
                CarPeriodDualTrendChartCard(report = report)
            }

            CarLedgerAnalyticsSubTab.COST_BREAKDOWN_TABLE -> {
                // (D) 차량유지비 항목별(주유/충전 · 주차/통행료 · 정비/세차) 증감 비교 도표
                CarCostBreakdownDeltaCard(
                    report = report,
                    onNavigateToRefuelLogs = {
                        onJumpToLogTabWithFilter(LogFilterMode.REFUEL_ONLY)
                    }
                )
            }

            CarLedgerAnalyticsSubTab.DRIVING_BREAKDOWN_TABLE -> {
                // (E) 주행 목적별(출퇴근 · 골프 · 일반) 주행거리 증감 비교 도표
                CarDrivingPurposeDeltaCard(
                    report = report,
                    onNavigateToDrivingLogs = {
                        onJumpToLogTabWithFilter(LogFilterMode.DRIVING_ONLY)
                    }
                )
            }
        }
    }
}

@Composable
private fun CarAnalyticsSubViewTabBar(
    selectedSubTab: CarLedgerAnalyticsSubTab,
    onSelectSubTab: (CarLedgerAnalyticsSubTab) -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 8.dp else 6.dp)
    ) {
        CarLedgerAnalyticsSubTab.entries.forEach { tab ->
            val isSelected = selectedSubTab == tab
            Surface(
                onClick = { onSelectSubTab(tab) },
                shape = RoundedCornerShape(if (isEasyMode) 14.dp else 10.dp),
                color = if (isEasyMode) {
                    if (isSelected) EasyTheme.textPrimary else EasyTheme.surface
                } else {
                    if (isSelected) Slate900 else PureWhite
                },
                border = if (isEasyMode) null else BorderStroke(1.dp, if (isSelected) Slate900 else Slate200),
                modifier = Modifier
                    .weight(1f)
                    .height(if (isEasyMode) 40.dp else 36.dp)
            ) {
                Box(contentAlignment = Alignment.Center, modifier = Modifier.padding(horizontal = 6.dp)) {
                    Text(
                        text = tab.label,
                        fontSize = if (isEasyMode) 12.5.sp else 11.5.sp,
                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                        color = if (isEasyMode) {
                            if (isSelected) PureWhite else EasyTheme.textSecondary
                        } else {
                            if (isSelected) PureWhite else Slate700
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
        }
    }
}

@Composable
private fun CarOverviewDualSummaryCard(
    report: CarLedgerComparisonReport
) {
    val isEasyMode = LocalEasyMode.current
    val basisLabel = if (report.effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) {
        "${report.currentPeriod.sequentialBasisLabel()} (${report.previousPeriod.compactTitle})"
    } else {
        "${report.currentPeriod.yoyBasisLabel()} (${report.yoyPeriod.compactTitle})"
    }

    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 22.dp else 14.dp),
        color = if (isEasyMode) EasyTheme.surface else PureWhite,
        border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isEasyMode) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 14.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "${report.currentPeriod.displayTitle} 주행 · 유지비 요약",
                    fontSize = if (isEasyMode) 15.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                MetricBadge(
                    text = basisLabel,
                    textColor = if (isEasyMode) EasyTheme.tossBlue else CarDrivingPrimaryFill,
                    backgroundColor = if (isEasyMode) EasyTheme.tossBlueSoft else CarDrivingSoftBg
                )
            }

            // 좌/우 2열 메인 요약 카드: [총 주행거리 증감] vs [총 차량유지비 증감]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 10.dp else 8.dp)
            ) {
                // 1) 총 주행거리 카드
                val distPctStr = CarLedgerAnalyticsEngine.formatSignedPercentForDouble(
                    report.activeDistanceDeltaPercent,
                    report.currentTotalDistanceKm,
                    report.activeComparisonDistanceKm
                )
                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                    color = if (isEasyMode) EasyTheme.surfaceSubtle else Slate50,
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 14.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "총 주행거리 (${report.currentTripCount}회)",
                            fontSize = if (isEasyMode) 12.sp else 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate600,
                            maxLines = 1
                        )
                        Text(
                            text = "%,.1f km".format(report.currentTotalDistanceKm),
                            fontSize = if (isEasyMode) 21.sp else 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                            maxLines = 1
                        )
                        Text(
                            text = "${CarLedgerAnalyticsEngine.formatSignedKm(report.activeDistanceDeltaKm)} ($distPctStr)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                report.activeDistanceDeltaKm > 0.05 -> if (isEasyMode) EasyTheme.tossBlue else CarDrivingPrimaryFill
                                report.activeDistanceDeltaKm < -0.05 -> CarCostDecreaseText
                                else -> if (isEasyMode) EasyTheme.textSecondary else Slate600
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "비교 기간: %,.1f km".format(report.activeComparisonDistanceKm),
                            fontSize = 11.sp,
                            color = if (isEasyMode) EasyTheme.textMuted else Slate500,
                            maxLines = 1
                        )
                    }
                }

                // 2) 총 차량유지비(주유+주차+정비) 카드
                val costPctStr = ExpenseAnalyticsEngine.formatSignedPercent(
                    report.activeCostDeltaPercent,
                    report.currentTotalCost,
                    report.activeComparisonTotalCost
                )
                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                    color = if (isEasyMode) EasyTheme.surfaceSubtle else Slate50,
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 14.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "총 차량유지비 (${report.currentCostCount}건)",
                            fontSize = if (isEasyMode) 12.sp else 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate600,
                            maxLines = 1
                        )
                        Text(
                            text = "%,d원".format(report.currentTotalCost),
                            fontSize = if (isEasyMode) 21.sp else 20.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                            maxLines = 1
                        )
                        Text(
                            text = "${ExpenseAnalyticsEngine.formatSignedAmount(report.activeCostDeltaAmount)} ($costPctStr)",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = when {
                                report.activeCostDeltaAmount > 0L -> CarCostIncreaseText
                                report.activeCostDeltaAmount < 0L -> CarCostDecreaseText
                                else -> if (isEasyMode) EasyTheme.textSecondary else Slate600
                            },
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "비교 기간: %,d원".format(report.activeComparisonTotalCost),
                            fontSize = 11.sp,
                            color = if (isEasyMode) EasyTheme.textMuted else Slate500,
                            maxLines = 1
                        )
                    }
                }
            }

            // 하단 보조 지표 서브카드: [전년 동기 대비] & [1km당 차량유지비]
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 10.dp else 8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 14.dp else 10.dp),
                    color = if (isEasyMode) EasyTheme.surfaceSubtle else PureWhite,
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                    modifier = Modifier.weight(1f)
                ) {
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 12.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "${report.currentPeriod.yoyBasisLabel()} (${report.yoyPeriod.compactTitle})",
                            fontSize = 11.sp,
                            color = if (isEasyMode) EasyTheme.textMuted else Slate500,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = "주행 ${CarLedgerAnalyticsEngine.formatSignedKm(report.yoyDistanceDeltaKm)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEasyMode) EasyTheme.textPrimary else Slate800,
                            maxLines = 1
                        )
                        Text(
                            text = "유지비 ${ExpenseAnalyticsEngine.formatSignedAmount(report.yoyCostDeltaAmount)}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (report.yoyCostDeltaAmount > 0L) CarCostIncreaseText else CarCostDecreaseText,
                            maxLines = 1
                        )
                    }
                }

                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 14.dp else 10.dp),
                    color = if (isEasyMode) EasyTheme.surfaceSubtle else PureWhite,
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                    modifier = Modifier.weight(1f)
                ) {
                    val costPerKmDiff = report.currentCostPerKm - report.activeComparisonCostPerKm
                    val costPerKmSign = when {
                        costPerKmDiff > 0L -> "+%,d원/km".format(costPerKmDiff)
                        costPerKmDiff < 0L -> "-%,d원/km".format(abs(costPerKmDiff))
                        else -> "변동 없음"
                    }
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 12.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(3.dp)
                    ) {
                        Text(
                            text = "1km당 유지비 · 총 주유량",
                            fontSize = 11.sp,
                            color = if (isEasyMode) EasyTheme.textMuted else Slate500,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1
                        )
                        Text(
                            text = if (report.currentCostPerKm > 0L) "%,d원/km ($costPerKmSign)".format(report.currentCostPerKm) else "산출 전",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isEasyMode) EasyTheme.textPrimary else Slate800,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = "주유량 %.1fL (비교 %.1fL)".format(report.currentTotalFuelLiters, report.activeComparisonTotalFuelLiters),
                            fontSize = 11.sp,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate600,
                            maxLines = 1
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CarTopChangeAndCorrelationInsightCard(
    report: CarLedgerComparisonReport,
    onJumpToCostTable: () -> Unit,
    onJumpToDrivingTable: () -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    val largestCost = report.largestCostChangeItem

    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 22.dp else 14.dp),
        color = if (isEasyMode) EasyTheme.surface else PureWhite,
        border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isEasyMode) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 14.dp else 12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = if (isEasyMode) "최대 변동 유지비 · 상관관계 인사이트" else "최대 변동 유지비 강조 및 주행 상관관계 인사이트",
                    fontSize = if (isEasyMode) 15.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.width(8.dp))
                MetricBadge(
                    text = "자동 진단",
                    textColor = if (isEasyMode) EasyTheme.tossBlue else CarDrivingPrimaryFill,
                    backgroundColor = if (isEasyMode) EasyTheme.tossBlueSoft else CarDrivingSoftBg
                )
            }

            // 1. 가장 변경폭이 큰 차량유지비 항목 하이라이트 박스 (줄바꿈/겹침 없는 2단 레이아웃)
            if (largestCost != null && (largestCost.currentAmount > 0L || largestCost.comparisonAmount > 0L)) {
                val isInc = largestCost.deltaAmount >= 0L
                val outlineCol = if (isInc) CarCostIncreaseFill else CarCostDecreaseFill
                val tintBg = if (isInc) CarCostIncreaseBg else CarCostDecreaseBg
                val textCol = if (isInc) CarCostIncreaseText else CarCostDecreaseText
                val pctStr = ExpenseAnalyticsEngine.formatSignedPercent(
                    largestCost.deltaPercent,
                    largestCost.currentAmount,
                    largestCost.comparisonAmount
                )

                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                    color = tintBg,
                    border = if (isEasyMode) BorderStroke(1.dp, outlineCol.copy(alpha = 0.35f)) else BorderStroke(1.5.dp, outlineCol),
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onJumpToCostTable() }
                ) {
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 16.dp else 14.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = outlineCol
                        ) {
                            Text(
                                text = if (isInc) "최대 증가 유지비" else "최대 절감 유지비",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = largestCost.type.displayName,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.weight(1f, fill = false)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${ExpenseAnalyticsEngine.formatSignedAmount(largestCost.deltaAmount)} ($pctStr)",
                                fontSize = 15.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = textCol,
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        Text(
                            text = "비교 기간 %,d원(${largestCost.comparisonCount}건) → 이번 기간 %,d원(${largestCost.currentCount}건) · 전체 유지비의 %.1f%%".format(
                                largestCost.comparisonAmount,
                                largestCost.currentAmount,
                                largestCost.currentSharePercent
                            ),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = if (isEasyMode) EasyTheme.textSecondary else Slate700
                        )

                        Text(
                            text = "세부 내역: ${largestCost.causeSummary}",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = if (isEasyMode) EasyTheme.textPrimary else Slate800
                        )

                        if (largestCost.topPlacesSummary.isNotBlank()) {
                            Text(
                                text = "주요 지출 장소: ${largestCost.topPlacesSummary}",
                                fontSize = 11.5.sp,
                                color = if (isEasyMode) EasyTheme.textSecondary else Slate600
                            )
                        }
                    }
                }
            }

            // 2. 주행거리-유지비 상관관계 3줄 인사이트 박스
            Surface(
                shape = RoundedCornerShape(if (isEasyMode) 16.dp else 10.dp),
                color = if (isEasyMode) EasyTheme.surfaceSubtle else Slate50,
                border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier.padding(if (isEasyMode) 14.dp else 12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "주행거리 · 유지비 상관관계 분석 결과",
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEasyMode) EasyTheme.textPrimary else Slate800
                    )
                    CarInsightRow(label = "주행·비용", text = report.correlationInsight)
                    CarInsightRow(label = "최대변동", text = report.costHighlightInsight)
                    CarInsightRow(label = "목적·효율", text = report.drivingAndEfficiencyInsight)
                }
            }

            // 3. 상세 도표 바로가기 버튼 2종
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    onClick = onJumpToCostTable,
                    shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
                    color = if (isEasyMode) EasyTheme.tossBlueSoft else CarDrivingSoftBg,
                    border = if (isEasyMode) null else BorderStroke(1.dp, CarDrivingBorder),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "유지비 항목별 도표 >",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEasyMode) EasyTheme.tossBlue else CarDrivingPrimaryFill,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
                Surface(
                    onClick = onJumpToDrivingTable,
                    shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
                    color = if (isEasyMode) EasyTheme.chipBg else Slate50,
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate300),
                    modifier = Modifier.weight(1f)
                ) {
                    Text(
                        text = "주행거리 상세 도표 >",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEasyMode) EasyTheme.textSecondary else Slate800,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        modifier = Modifier.padding(vertical = 10.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun CarInsightRow(
    label: String,
    text: String
) {
    val isEasyMode = LocalEasyMode.current
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Surface(
            shape = RoundedCornerShape(if (isEasyMode) 6.dp else 4.dp),
            color = if (isEasyMode) EasyTheme.surface else PureWhite,
            border = if (isEasyMode) null else BorderStroke(1.dp, Slate300),
            modifier = Modifier.padding(top = 1.dp)
        ) {
            Text(
                text = label,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEasyMode) EasyTheme.tossBlue else Slate700,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }
        Text(
            text = text,
            fontSize = 12.5.sp,
            color = if (isEasyMode) EasyTheme.textPrimary else Slate800,
            lineHeight = 18.sp,
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun CarPeriodDualTrendChartCard(
    report: CarLedgerComparisonReport
) {
    val isEasyMode = LocalEasyMode.current
    val items = report.trendItems
    val maxDist = items.maxOfOrNull { it.totalDistanceKm }?.coerceAtLeast(1.0) ?: 1.0
    val maxCost = items.maxOfOrNull { it.totalMaintenanceCost }?.coerceAtLeast(1L) ?: 1L

    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 22.dp else 14.dp),
        color = if (isEasyMode) EasyTheme.surface else PureWhite,
        border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isEasyMode) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "최근 ${items.size}개 ${report.unit.shortLabel} 주행거리 · 차량유지비 추이",
                        fontSize = if (isEasyMode) 15.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEasyMode) EasyTheme.textPrimary else Slate900
                    )
                    Text(
                        text = "좌측 파란 막대: 주행거리(km) · 우측 슬레이트 막대: 유지비(원)",
                        fontSize = 11.5.sp,
                        color = if (isEasyMode) EasyTheme.textMuted else Slate500
                    )
                }
            }

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(184.dp)
                    .clip(RoundedCornerShape(if (isEasyMode) 16.dp else 10.dp))
                    .background(if (isEasyMode) EasyTheme.surfaceSubtle else Slate50)
                    .then(
                        if (isEasyMode) Modifier
                        else Modifier.border(1.dp, Slate200, RoundedCornerShape(10.dp))
                    )
                    .padding(horizontal = 8.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxSize(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.Bottom
                ) {
                    items.forEach { item ->
                        val distRatio = (item.totalDistanceKm / maxDist).toFloat().coerceIn(0f, 1f)
                        val costRatio = (item.totalMaintenanceCost.toFloat() / maxCost.toFloat()).coerceIn(0f, 1f)

                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight(),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Bottom
                        ) {
                            // 상단 수치 2줄 직접 정렬 (주행거리 km / 유지비 만원)
                            Text(
                                text = "%.0fkm".format(item.totalDistanceKm),
                                fontSize = 9.5.sp,
                                fontWeight = if (item.isSelectedCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = CarDrivingPrimaryFill,
                                maxLines = 1
                            )
                            Text(
                                text = formatCompactKoreanWon(item.totalMaintenanceCost),
                                fontSize = 9.5.sp,
                                fontWeight = if (item.isSelectedCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (item.isSelectedCurrent) Slate900 else Slate600,
                                maxLines = 1
                            )
                            Spacer(modifier = Modifier.height(4.dp))

                            // 이중 수직 막대 (좌: 주행거리, 우: 유지비)
                            Row(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(0.78f),
                                horizontalArrangement = Arrangement.spacedBy(3.dp),
                                verticalAlignment = Alignment.Bottom
                            ) {
                                val distH = if (item.totalDistanceKm > 0.0) distRatio.coerceAtLeast(0.06f) else 0.02f
                                val costH = if (item.totalMaintenanceCost > 0L) costRatio.coerceAtLeast(0.06f) else 0.02f

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(distH)
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val cr = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                        drawRoundRect(
                                            color = if (item.isSelectedCurrent) CarDrivingPrimaryFill else Color(0xFF93C5FD),
                                            cornerRadius = cr,
                                            size = Size(size.width, size.height)
                                        )
                                        if (item.isComparisonTarget) {
                                            drawRoundRect(
                                                color = CarDrivingPrimaryFill,
                                                cornerRadius = cr,
                                                size = Size(size.width, size.height),
                                                style = Stroke(
                                                    width = 1.2.dp.toPx(),
                                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f), 0f)
                                                )
                                            )
                                        }
                                    }
                                }

                                Box(
                                    modifier = Modifier
                                        .weight(1f)
                                        .fillMaxHeight(costH)
                                ) {
                                    Canvas(modifier = Modifier.fillMaxSize()) {
                                        val cr = CornerRadius(4.dp.toPx(), 4.dp.toPx())
                                        drawRoundRect(
                                            color = if (item.isSelectedCurrent) CarDarkSlateFill else CarNeutralFill,
                                            cornerRadius = cr,
                                            size = Size(size.width, size.height)
                                        )
                                        if (item.isComparisonTarget) {
                                            drawRoundRect(
                                                color = CarDarkSlateFill,
                                                cornerRadius = cr,
                                                size = Size(size.width, size.height),
                                                style = Stroke(
                                                    width = 1.2.dp.toPx(),
                                                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(5f, 3f), 0f)
                                                )
                                            )
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = item.period.shortChartLabel,
                                fontSize = 11.sp,
                                fontWeight = if (item.isSelectedCurrent) FontWeight.Bold else FontWeight.Medium,
                                color = if (item.isSelectedCurrent) Slate900 else Slate600,
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
private fun CarCostBreakdownDeltaCard(
    report: CarLedgerComparisonReport,
    onNavigateToRefuelLogs: () -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    val maxCostScale = remember(report.costDeltas) {
        report.costDeltas.maxOfOrNull { maxOf(it.currentAmount, it.comparisonAmount) }?.coerceAtLeast(1L) ?: 1L
    }

    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 22.dp else 14.dp),
        color = if (isEasyMode) EasyTheme.surface else PureWhite,
        border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isEasyMode) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "차량유지비 항목별(주유 · 주차 · 정비) 증감 도표",
                        fontSize = if (isEasyMode) 15.sp else 14.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEasyMode) EasyTheme.textPrimary else Slate900
                    )
                    Text(
                        text = "${report.currentPeriod.compactTitle} vs ${report.activeComparisonPeriod.compactTitle} 비교 (가장 변동폭이 큰 지출 최상단 강조)",
                        fontSize = 11.5.sp,
                        color = if (isEasyMode) EasyTheme.textMuted else Slate500
                    )
                }
            }

            HairlineDivider()

            report.costDeltas.forEachIndexed { index, item ->
                val isInc = item.deltaAmount > 0L
                val isDec = item.deltaAmount < 0L
                val pctStr = ExpenseAnalyticsEngine.formatSignedPercent(
                    item.deltaPercent,
                    item.currentAmount,
                    item.comparisonAmount
                )

                val borderStroke = when {
                    item.isLargestMagnitudeChange && isInc -> BorderStroke(1.5.dp, CarCostIncreaseFill)
                    item.isLargestMagnitudeChange && isDec -> BorderStroke(1.5.dp, CarCostDecreaseFill)
                    isEasyMode -> null
                    else -> BorderStroke(1.dp, Slate200)
                }
                val bgTint = when {
                    item.isLargestMagnitudeChange && isInc -> CarCostIncreaseBg.copy(alpha = 0.55f)
                    item.isLargestMagnitudeChange && isDec -> CarCostDecreaseBg.copy(alpha = 0.55f)
                    isEasyMode -> EasyTheme.surfaceSubtle
                    else -> PureWhite
                }

                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                    color = bgTint,
                    border = borderStroke,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToRefuelLogs() }
                ) {
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 14.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                                        .background(if (item.isLargestMagnitudeChange) Slate900 else Slate200),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.isLargestMagnitudeChange) PureWhite else Slate700
                                    )
                                }
                                Text(
                                    text = item.type.displayName,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate900,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                if (item.isLargestMagnitudeChange && item.deltaAmount != 0L) {
                                    MetricBadge(
                                        text = "최대 변동",
                                        textColor = PureWhite,
                                        backgroundColor = if (isInc) CarCostIncreaseFill else CarCostDecreaseFill
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${ExpenseAnalyticsEngine.formatSignedAmount(item.deltaAmount)} ($pctStr)",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isInc -> CarCostIncreaseText
                                    isDec -> CarCostDecreaseText
                                    else -> Slate600
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        val curRatio = (item.currentAmount.toFloat() / maxCostScale.toFloat()).coerceIn(0f, 1f)
                        val compRatio = (item.comparisonAmount.toFloat() / maxCostScale.toFloat()).coerceIn(0f, 1f)
                        val curColor = when {
                            isInc -> CarCostIncreaseFill
                            isDec -> CarCostDecreaseFill
                            else -> CarDarkSlateFill
                        }

                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            CarHorizontalMoneyBarRow(
                                label = report.currentPeriod.compactTitle,
                                ratio = curRatio,
                                amount = item.currentAmount,
                                count = item.currentCount,
                                sharePct = item.currentSharePercent,
                                barColor = curColor,
                                isCurrent = true
                            )
                            CarHorizontalMoneyBarRow(
                                label = report.activeComparisonPeriod.compactTitle,
                                ratio = compRatio,
                                amount = item.comparisonAmount,
                                count = item.comparisonCount,
                                sharePct = item.comparisonSharePercent,
                                barColor = CarNeutralFill,
                                isCurrent = false
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
                            color = if (isEasyMode) EasyTheme.surface else Slate50,
                            border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
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
                                        text = item.causeSummary,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Slate700,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Text(
                                        text = "기록 보기 >",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = CarDrivingPrimaryFill
                                    )
                                }
                                if (report.effectiveBasis != AnalyticsComparisonBasis.YEAR_OVER_YEAR && item.yoyAmount > 0L) {
                                    val yoyDiff = item.currentAmount - item.yoyAmount
                                    val yoyPct = ExpenseAnalyticsEngine.calculatePercentChange(item.currentAmount, item.yoyAmount)
                                    val yoyAmtFormatted = "%,d원".format(item.yoyAmount)
                                    val yoyDiffFormatted = ExpenseAnalyticsEngine.formatSignedAmount(yoyDiff)
                                    val yoyPctFormatted = ExpenseAnalyticsEngine.formatSignedPercent(yoyPct, item.currentAmount, item.yoyAmount)
                                    Text(
                                        text = "전년 동기(${report.yoyPeriod.compactTitle}) $yoyAmtFormatted 대비: $yoyDiffFormatted ($yoyPctFormatted)",
                                        fontSize = 10.5.sp,
                                        color = Slate600
                                    )
                                }
                                if (item.topPlacesSummary.isNotBlank()) {
                                    Text(
                                        text = "주요 이용처: ${item.topPlacesSummary}",
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
        }
    }
}

@Composable
private fun CarDrivingPurposeDeltaCard(
    report: CarLedgerComparisonReport,
    onNavigateToDrivingLogs: () -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    val maxKmScale = remember(report.drivingDeltas) {
        report.drivingDeltas.maxOfOrNull { maxOf(it.currentDistanceKm, it.comparisonDistanceKm) }?.coerceAtLeast(1.0) ?: 1.0
    }

    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 22.dp else 14.dp),
        color = if (isEasyMode) EasyTheme.surface else PureWhite,
        border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (isEasyMode) 20.dp else 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Column {
                Text(
                    text = "주행 목적별(출퇴근 · 골프 · 일반) 주행거리 비교 도표",
                    fontSize = if (isEasyMode) 15.sp else 14.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEasyMode) EasyTheme.textPrimary else Slate900
                )
                Text(
                    text = "${report.currentPeriod.compactTitle} vs ${report.activeComparisonPeriod.compactTitle} 주행량 증감 비교",
                    fontSize = 11.5.sp,
                    color = if (isEasyMode) EasyTheme.textMuted else Slate500
                )
            }

            HairlineDivider()

            report.drivingDeltas.forEachIndexed { index, item ->
                val isInc = item.deltaDistanceKm > 0.05
                val isDec = item.deltaDistanceKm < -0.05
                val pctStr = CarLedgerAnalyticsEngine.formatSignedPercentForDouble(
                    item.deltaPercent,
                    item.currentDistanceKm,
                    item.comparisonDistanceKm
                )

                val borderStroke = when {
                    item.isLargestMagnitudeChange -> BorderStroke(1.5.dp, CarDrivingPrimaryFill)
                    isEasyMode -> null
                    else -> BorderStroke(1.dp, Slate200)
                }

                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 12.dp),
                    color = if (item.isLargestMagnitudeChange) CarDrivingSoftBg.copy(alpha = 0.45f)
                            else if (isEasyMode) EasyTheme.surfaceSubtle else PureWhite,
                    border = borderStroke,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onNavigateToDrivingLogs() }
                ) {
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 14.dp else 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
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
                                        .background(if (item.isLargestMagnitudeChange) CarDrivingPrimaryFill else Slate200),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 10.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (item.isLargestMagnitudeChange) PureWhite else Slate700
                                    )
                                }
                                Text(
                                    text = item.purpose.displayName,
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
                                        backgroundColor = CarDrivingPrimaryFill
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "${CarLedgerAnalyticsEngine.formatSignedKm(item.deltaDistanceKm)} ($pctStr)",
                                fontSize = 13.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = when {
                                    isInc -> CarDrivingPrimaryFill
                                    isDec -> CarCostDecreaseText
                                    else -> Slate600
                                },
                                maxLines = 1,
                                softWrap = false
                            )
                        }

                        val curRatio = (item.currentDistanceKm / maxKmScale).toFloat().coerceIn(0f, 1f)
                        val compRatio = (item.comparisonDistanceKm / maxKmScale).toFloat().coerceIn(0f, 1f)

                        Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                            CarHorizontalKmBarRow(
                                label = report.currentPeriod.compactTitle,
                                ratio = curRatio,
                                distanceKm = item.currentDistanceKm,
                                tripCount = item.currentTripCount,
                                sharePct = item.currentSharePercent,
                                barColor = CarDrivingPrimaryFill,
                                isCurrent = true
                            )
                            CarHorizontalKmBarRow(
                                label = report.activeComparisonPeriod.compactTitle,
                                ratio = compRatio,
                                distanceKm = item.comparisonDistanceKm,
                                tripCount = item.comparisonTripCount,
                                sharePct = item.comparisonSharePercent,
                                barColor = CarNeutralFill,
                                isCurrent = false
                            )
                        }

                        Surface(
                            shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
                            color = if (isEasyMode) EasyTheme.surface else Slate50,
                            border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 7.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = item.causeSummary,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate700,
                                    modifier = Modifier.weight(1f)
                                )
                                Text(
                                    text = "주행 기록 >",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = CarDrivingPrimaryFill
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun CarHorizontalMoneyBarRow(
    label: String,
    ratio: Float,
    amount: Long,
    count: Int,
    sharePct: Float,
    barColor: Color,
    isCurrent: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
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
                            if (!isCurrent) Modifier.border(1.dp, CarNeutralStroke, RoundedCornerShape(4.dp))
                            else Modifier
                        )
                )
            }
        }
        Text(
            text = "%,d원 (%d건 · %.0f%%)".format(amount, count, sharePct),
            fontSize = 11.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) Slate900 else Slate600,
            textAlign = TextAlign.End,
            modifier = Modifier.width(136.dp),
            maxLines = 1
        )
    }
}

@Composable
private fun CarHorizontalKmBarRow(
    label: String,
    ratio: Float,
    distanceKm: Double,
    tripCount: Int,
    sharePct: Float,
    barColor: Color,
    isCurrent: Boolean
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = label,
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
            val widthFraction = if (distanceKm > 0.05) ratio.coerceAtLeast(0.04f) else 0f
            if (widthFraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(widthFraction)
                        .fillMaxHeight()
                        .clip(RoundedCornerShape(4.dp))
                        .background(barColor)
                        .then(
                            if (!isCurrent) Modifier.border(1.dp, CarNeutralStroke, RoundedCornerShape(4.dp))
                            else Modifier
                        )
                )
            }
        }
        Text(
            text = "%.1fkm (%d회 · %.0f%%)".format(distanceKm, tripCount, sharePct),
            fontSize = 11.sp,
            fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.Medium,
            color = if (isCurrent) Slate900 else Slate600,
            textAlign = TextAlign.End,
            modifier = Modifier.width(136.dp),
            maxLines = 1
        )
    }
}
