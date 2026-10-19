package com.autologue.app.presentation.golf

import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.getDisplayClubName
import com.autologue.app.domain.model.getScorecardStats
import com.autologue.app.domain.model.isSelfTransfer
import com.autologue.app.presentation.expense.AnalyticsComparisonBasis
import com.autologue.app.presentation.expense.AnalyticsPeriodUnit
import com.autologue.app.presentation.expense.ExpenseAnalyticsEngine
import com.autologue.app.presentation.expense.PeriodSpec
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToInt

enum class GolfPageTab(val label: String) {
    ROUNDS("라운드 기록 · 예약"),
    ANALYTICS("기간별 골프 통계 · 비교")
}

enum class GolfAnalyticsSubTab(val label: String) {
    OVERVIEW_INSIGHT("종합 비교 · 인사이트"),
    ACTIVITY_COUNT_TABLE("횟수 · 활동 비교 도표"),
    SCORE_SHOT_TABLE("스코어 · 샷 상세 도표")
}

data class GolfActivityPeriodSnapshot(
    val period: PeriodSpec,
    val fieldRoundCount: Int,
    val screenGolfCount: Int,
    val totalActivityCount: Int,
    val scoredRoundCount: Int,
    val distinctClubCount: Int,
    val averageScore: Double?,
    val bestScore: Int?,
    val bestScoreClubName: String?,
    val worstScore: Int?,
    val averagePutts: Double?,
    val averageBirdiesPerRound: Double?,
    val averageParsPerRound: Double?,
    val averageBogeysPerRound: Double?,
    val averageDoublePlusPerRound: Double?,
    val parOrBetterRatePercent: Double?,
    val averagePenalty: Double?,
    val averageGirPercent: Double?,
    val averageDriveDistanceM: Double?,
    val averageAdjustedDriveDistanceM: Double?,
    val averageTempo: Double?,
    val averageSteps: Int?,
    val totalGolfExpense: Long,
    val fieldGolfExpense: Long,
    val screenGolfExpense: Long,
    val equipmentGolfExpense: Long,
    val topFieldClubsSummary: String,
    val topScreenVenuesSummary: String
)

data class GolfMetricDeltaRow(
    val metricKey: String,
    val title: String,
    val subtitle: String,
    val unitSuffix: String,
    val currentValue: Double?,
    val comparisonValue: Double?,
    val yoyValue: Double?,
    val deltaFromComparison: Double?,
    val deltaPercentFromComparison: Float?,
    val deltaFromYoy: Double?,
    val deltaPercentFromYoy: Float?,
    /**
     * true: 타수·퍼트·벌타·더블보기처럼 수치가 낮아질수록 긍정적(개선)인 지표
     * false: 라운딩 횟수·버디·파·비거리·GIR처럼 수치가 높아질수록 활동량/실력이 증가한 지표
     */
    val lowerIsBetter: Boolean,
    val isIntegerMetric: Boolean,
    val detailNote: String,
    val isHighlightedChange: Boolean = false
)

data class GolfPeriodTrendBarItem(
    val period: PeriodSpec,
    val fieldRoundCount: Int,
    val screenGolfCount: Int,
    val totalActivityCount: Int,
    val averageScore: Double?,
    val bestScore: Int?,
    val isSelectedCurrent: Boolean,
    val isComparisonTarget: Boolean
)

data class GolfPeriodComparisonReport(
    val unit: AnalyticsPeriodUnit,
    val effectiveBasis: AnalyticsComparisonBasis,
    val currentPeriod: PeriodSpec,
    val previousPeriod: PeriodSpec,
    val yoyPeriod: PeriodSpec,
    val activeComparisonPeriod: PeriodSpec,
    val currentSnapshot: GolfActivityPeriodSnapshot,
    val previousSnapshot: GolfActivityPeriodSnapshot,
    val yoySnapshot: GolfActivityPeriodSnapshot,
    val activeComparisonSnapshot: GolfActivityPeriodSnapshot,
    // 핵심 요약 증감 (직전 기간 대비 & 전년 동기 대비 동시 제공)
    val seqTotalCountDelta: Int,
    val seqTotalCountDeltaPct: Float?,
    val yoyTotalCountDelta: Int,
    val yoyTotalCountDeltaPct: Float?,
    val seqFieldCountDelta: Int,
    val yoyFieldCountDelta: Int,
    val seqScreenCountDelta: Int,
    val yoyScreenCountDelta: Int,
    val seqAvgScoreDelta: Double?,
    val yoyAvgScoreDelta: Double?,
    // 강조 항목 및 인사이트 문장
    val topChangedActivityMetric: GolfMetricDeltaRow?,
    val topChangedScoreMetric: GolfMetricDeltaRow?,
    val headlineInsight: String,
    val scoreAnalysisInsight: String,
    val yoyComparisonInsight: String,
    // 서브탭 도표 데이터
    val activityDeltaRows: List<GolfMetricDeltaRow>,
    val scoreShotDeltaRows: List<GolfMetricDeltaRow>,
    val trendItems: List<GolfPeriodTrendBarItem>
)

object GolfAnalyticsEngine {

    private val SCREEN_GOLF_KEYWORDS = listOf(
        "스크린", "골프존", "GOLFZON", "프렌즈", "카카오VX", "KAKAOVX",
        "SG골프", "김캐디", "투비전", "비전플러스", "더스윙", "지투어", "연습장", "GDR", "QED"
    )

    fun isScreenGolfRound(round: GolfRound): Boolean {
        if (round.golfType == GolfType.SCREEN) return true
        val text = "${round.clubName} ${round.memo.orEmpty()}"
        return SCREEN_GOLF_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    fun isScreenGolfTransaction(tx: Transaction): Boolean {
        if (tx.category == ExpenseCategory.GOLF_SCREEN) return true
        val text = "${tx.merchantName} ${tx.originalText}"
        return SCREEN_GOLF_KEYWORDS.any { text.contains(it, ignoreCase = true) }
    }

    fun isFieldGolfTransaction(tx: Transaction): Boolean {
        if (tx.category == ExpenseCategory.GOLF_FIELD) return true
        val m = tx.merchantName
        return (m.contains("CC", ignoreCase = true) ||
            m.contains("GC", ignoreCase = true) ||
            m.contains("컨트리") ||
            m.contains("골프장")) && !isScreenGolfTransaction(tx)
    }

    fun isGolfEquipmentTransaction(tx: Transaction): Boolean {
        if (tx.category == ExpenseCategory.GOLF_EQUIPMENT) return true
        val m = tx.merchantName
        return m.contains("골프용품") || m.contains("골프샵") || m.contains("골핑") || m.contains("골프존마켓")
    }

    fun buildReport(
        allRounds: List<GolfRound>,
        allTransactions: List<Transaction>,
        unit: AnalyticsPeriodUnit,
        targetPeriod: PeriodSpec,
        requestedBasis: AnalyticsComparisonBasis
    ): GolfPeriodComparisonReport {
        val effectiveBasis = if (unit == AnalyticsPeriodUnit.YEARLY) {
            AnalyticsComparisonBasis.YEAR_OVER_YEAR
        } else {
            requestedBasis
        }

        val today = LocalDate.now()
        // 미래 예약 전용 빈 라운드는 완료된 라운드 통계에서 제외
        val validCompletedRounds = allRounds.filter { round ->
            val roundDay = (round.startTime ?: round.roundDate).toLocalDate()
            val isRealOrScreen = DailyRouteAggregator.isRealGolfClub(round.clubName) || isScreenGolfRound(round)
            isRealOrScreen &&
                !roundDay.isAfter(today) &&
                ((round.totalScore != null && round.totalScore > 0) ||
                    round.holeScores.isNotEmpty() ||
                    roundDay.isBefore(today))
        }

        val validExpenseTxs = allTransactions.filter { tx ->
            tx.category != ExpenseCategory.INCOME && !tx.isSelfTransfer() && tx.amount > 0L
        }

        val previousPeriod = targetPeriod.previousSequential()
        val yoyPeriod = targetPeriod.samePeriodLastYear()
        val activeComparisonPeriod = when (effectiveBasis) {
            AnalyticsComparisonBasis.PREVIOUS_PERIOD -> previousPeriod
            AnalyticsComparisonBasis.YEAR_OVER_YEAR -> yoyPeriod
        }

        val curSnap = buildPeriodSnapshot(targetPeriod, validCompletedRounds, validExpenseTxs)
        val prevSnap = buildPeriodSnapshot(previousPeriod, validCompletedRounds, validExpenseTxs)
        val yoySnap = buildPeriodSnapshot(yoyPeriod, validCompletedRounds, validExpenseTxs)
        val activeCompSnap = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) prevSnap else yoySnap

        val seqTotalDelta = curSnap.totalActivityCount - prevSnap.totalActivityCount
        val seqTotalPct = calcIntPct(curSnap.totalActivityCount, prevSnap.totalActivityCount)
        val yoyTotalDelta = curSnap.totalActivityCount - yoySnap.totalActivityCount
        val yoyTotalPct = calcIntPct(curSnap.totalActivityCount, yoySnap.totalActivityCount)

        val seqFieldDelta = curSnap.fieldRoundCount - prevSnap.fieldRoundCount
        val yoyFieldDelta = curSnap.fieldRoundCount - yoySnap.fieldRoundCount
        val seqScreenDelta = curSnap.screenGolfCount - prevSnap.screenGolfCount
        val yoyScreenDelta = curSnap.screenGolfCount - yoySnap.screenGolfCount

        val seqScoreDelta = if (curSnap.averageScore != null && prevSnap.averageScore != null) {
            round1(curSnap.averageScore - prevSnap.averageScore)
        } else null
        val yoyScoreDelta = if (curSnap.averageScore != null && yoySnap.averageScore != null) {
            round1(curSnap.averageScore - yoySnap.averageScore)
        } else null

        val activityRows = buildActivityDeltaRows(curSnap, activeCompSnap, yoySnap)
        val scoreShotRows = buildScoreShotDeltaRows(curSnap, activeCompSnap, yoySnap)

        val topActivityRow = activityRows.firstOrNull { it.isHighlightedChange }
        val topScoreRow = scoreShotRows.firstOrNull { it.isHighlightedChange }

        // 최근 추이 막대 생성 (월간 6개, 분기 6개, 반기 4개, 연간 4개)
        val barCount = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> 6
            AnalyticsPeriodUnit.QUARTERLY -> 6
            AnalyticsPeriodUnit.HALF_YEARLY -> 4
            AnalyticsPeriodUnit.YEARLY -> 4
        }
        val trendPeriods = ArrayList<PeriodSpec>(barCount)
        var cursor = targetPeriod
        repeat(barCount) {
            trendPeriods.add(0, cursor)
            cursor = cursor.previousSequential()
        }
        val trendItems = trendPeriods.map { pSpec ->
            val snap = buildPeriodSnapshot(pSpec, validCompletedRounds, validExpenseTxs)
            GolfPeriodTrendBarItem(
                period = pSpec,
                fieldRoundCount = snap.fieldRoundCount,
                screenGolfCount = snap.screenGolfCount,
                totalActivityCount = snap.totalActivityCount,
                averageScore = snap.averageScore,
                bestScore = snap.bestScore,
                isSelectedCurrent = pSpec == targetPeriod,
                isComparisonTarget = pSpec == activeComparisonPeriod
            )
        }

        val basisName = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) {
            "${targetPeriod.sequentialBasisLabel()}(${previousPeriod.compactTitle})"
        } else {
            "${targetPeriod.yoyBasisLabel()}(${yoyPeriod.compactTitle})"
        }

        val headlineInsight = buildHeadlineInsight(
            targetTitle = targetPeriod.displayTitle,
            basisName = basisName,
            cur = curSnap,
            comp = activeCompSnap
        )

        val scoreInsight = buildScoreAnalysisInsight(
            basisName = basisName,
            cur = curSnap,
            comp = activeCompSnap
        )

        val yoyInsight = buildYoyComparisonInsight(
            unit = unit,
            yoyPeriod = yoyPeriod,
            cur = curSnap,
            yoy = yoySnap
        )

        return GolfPeriodComparisonReport(
            unit = unit,
            effectiveBasis = effectiveBasis,
            currentPeriod = targetPeriod,
            previousPeriod = previousPeriod,
            yoyPeriod = yoyPeriod,
            activeComparisonPeriod = activeComparisonPeriod,
            currentSnapshot = curSnap,
            previousSnapshot = prevSnap,
            yoySnapshot = yoySnap,
            activeComparisonSnapshot = activeCompSnap,
            seqTotalCountDelta = seqTotalDelta,
            seqTotalCountDeltaPct = seqTotalPct,
            yoyTotalCountDelta = yoyTotalDelta,
            yoyTotalCountDeltaPct = yoyTotalPct,
            seqFieldCountDelta = seqFieldDelta,
            yoyFieldCountDelta = yoyFieldDelta,
            seqScreenCountDelta = seqScreenDelta,
            yoyScreenCountDelta = yoyScreenDelta,
            seqAvgScoreDelta = seqScoreDelta,
            yoyAvgScoreDelta = yoyScoreDelta,
            topChangedActivityMetric = topActivityRow,
            topChangedScoreMetric = topScoreRow,
            headlineInsight = headlineInsight,
            scoreAnalysisInsight = scoreInsight,
            yoyComparisonInsight = yoyInsight,
            activityDeltaRows = activityRows,
            scoreShotDeltaRows = scoreShotRows,
            trendItems = trendItems
        )
    }

    private fun buildPeriodSnapshot(
        period: PeriodSpec,
        allRounds: List<GolfRound>,
        allExpenseTxs: List<Transaction>
    ): GolfActivityPeriodSnapshot {
        val periodRounds = allRounds.filter { r ->
            period.contains((r.startTime ?: r.roundDate).toLocalDate())
        }
        val periodTxs = allExpenseTxs.filter { tx ->
            period.contains(tx.timestamp.toLocalDate())
        }

        val fieldRounds = periodRounds.filter { !isScreenGolfRound(it) }
        val screenRoundsFromGolfDb = periodRounds.filter { isScreenGolfRound(it) }

        // 가계부 결제 내역 중 스크린골프 결제 건 (동일 날짜에 이미 GolfRound 스크린 기록이 있으면 중복 카운트 방지)
        val screenRoundDates = screenRoundsFromGolfDb.map { (it.startTime ?: it.roundDate).toLocalDate() }.toSet()
        val screenTxs = periodTxs.filter { isScreenGolfTransaction(it) }
        val additionalScreenTxDates = screenTxs
            .map { it.timestamp.toLocalDate() }
            .distinct()
            .filter { it !in screenRoundDates }

        val fieldCount = fieldRounds.size
        val screenCount = screenRoundsFromGolfDb.size + additionalScreenTxDates.size
        val totalActivity = fieldCount + screenCount

        // 스코어 분석 (50타 이상 유효 스코어 기록 기준)
        val scoredRounds = periodRounds.filter { (it.totalScore ?: 0) >= 50 }
        val scores = scoredRounds.mapNotNull { it.totalScore }
        val avgScore = if (scores.isNotEmpty()) round1(scores.average()) else null
        val bestRound = scoredRounds.minByOrNull { it.totalScore ?: Int.MAX_VALUE }
        val bestScore = bestRound?.totalScore
        val bestClub = bestRound?.getDisplayClubName()
        val worstScore = scores.maxOrNull()

        val puttsList = periodRounds.mapNotNull { it.totalPutts }.filter { it > 0 }
        val avgPutts = if (puttsList.isNotEmpty()) round1(puttsList.average()) else null

        val scorecardStatsList = periodRounds.mapNotNull { it.getScorecardStats() }
        val avgBirdies = if (scorecardStatsList.isNotEmpty()) {
            round1(scorecardStatsList.map { it.birdieCount }.average())
        } else null
        val avgPars = if (scorecardStatsList.isNotEmpty()) {
            round1(scorecardStatsList.map { it.parCount }.average())
        } else null
        val avgBogeys = if (scorecardStatsList.isNotEmpty()) {
            round1(scorecardStatsList.map { it.bogeyCount }.average())
        } else null
        val avgDoublePlus = if (scorecardStatsList.isNotEmpty()) {
            round1(scorecardStatsList.map { it.doublePlusCount }.average())
        } else null

        val parOrBetterRate = if (scorecardStatsList.isNotEmpty()) {
            val totalParOrBetter = scorecardStatsList.sumOf { it.birdieCount + it.parCount }
            val totalHoles = (scorecardStatsList.size * 18).coerceAtLeast(1)
            round1(totalParOrBetter.toDouble() / totalHoles.toDouble() * 100.0)
        } else null

        val penalties = periodRounds.mapNotNull { it.penaltyCount }
        val avgPenalty = if (penalties.isNotEmpty()) round1(penalties.average()) else null

        val girs = periodRounds.mapNotNull { it.girPercentage }.filter { it > 0.0 }
        val avgGir = if (girs.isNotEmpty()) round1(girs.average()) else null

        val allDrives = periodRounds.flatMap { it.driveDistances }.ifEmpty {
            periodRounds.mapNotNull { it.averageDriveDistance }
        }.filter { it > 0.0 }
        val avgDrive = if (allDrives.isNotEmpty()) round1(allDrives.average()) else null

        val adjDrives = periodRounds.mapNotNull { it.getEffectiveAdjustedDriveDistance() }.filter { it > 0.0 }
        val avgAdjDrive = if (adjDrives.isNotEmpty()) round1(adjDrives.average()) else avgDrive

        val tempos = periodRounds.flatMap { it.tempos }.ifEmpty {
            periodRounds.mapNotNull { it.averageTempo }
        }.filter { it > 0.0 }
        val avgTempo = if (tempos.isNotEmpty()) {
            kotlin.math.round(tempos.average() * 100.0) / 100.0
        } else null

        val stepsList = periodRounds.mapNotNull { it.steps }.filter { it > 0 }
        val avgSteps = if (stepsList.isNotEmpty()) stepsList.average().roundToInt() else null

        // 골프 관련 지출 합산
        val fieldTxs = periodTxs.filter { isFieldGolfTransaction(it) }
        val equipTxs = periodTxs.filter { isGolfEquipmentTransaction(it) }
        val fieldTxAmt = fieldTxs.sumOf { it.amount }
        val roundGreenFeeAmt = fieldRounds.sumOf { it.greenFeeExpense }
        val fieldExpense = maxOf(fieldTxAmt, roundGreenFeeAmt)
        val screenExpense = screenTxs.sumOf { it.amount }
        val equipExpense = equipTxs.sumOf { it.amount }
        val totalGolfExpense = fieldExpense + screenExpense + equipExpense

        val distinctClubs = fieldRounds
            .map { com.autologue.app.domain.model.splitClubAndCourse(it.clubName).first.ifBlank { it.clubName } }
            .distinct()
            .size

        val topFieldClubs = fieldRounds
            .groupBy { com.autologue.app.domain.model.splitClubAndCourse(it.clubName).first.ifBlank { it.clubName } }
            .entries
            .sortedByDescending { it.value.size }
            .take(3)
            .joinToString(" · ") { (club, list) -> "${club}(${list.size}회)" }

        val screenVenueNames = screenRoundsFromGolfDb.map { it.clubName } +
            screenTxs.map { it.merchantName.ifBlank { "스크린골프" } }
        val topScreenVenues = screenVenueNames
            .groupBy { it }
            .entries
            .sortedByDescending { it.value.size }
            .take(2)
            .joinToString(" · ") { (name, list) -> "${name}(${list.size}회)" }

        return GolfActivityPeriodSnapshot(
            period = period,
            fieldRoundCount = fieldCount,
            screenGolfCount = screenCount,
            totalActivityCount = totalActivity,
            scoredRoundCount = scoredRounds.size,
            distinctClubCount = distinctClubs,
            averageScore = avgScore,
            bestScore = bestScore,
            bestScoreClubName = bestClub,
            worstScore = worstScore,
            averagePutts = avgPutts,
            averageBirdiesPerRound = avgBirdies,
            averageParsPerRound = avgPars,
            averageBogeysPerRound = avgBogeys,
            averageDoublePlusPerRound = avgDoublePlus,
            parOrBetterRatePercent = parOrBetterRate,
            averagePenalty = avgPenalty,
            averageGirPercent = avgGir,
            averageDriveDistanceM = avgDrive,
            averageAdjustedDriveDistanceM = avgAdjDrive,
            averageTempo = avgTempo,
            averageSteps = avgSteps,
            totalGolfExpense = totalGolfExpense,
            fieldGolfExpense = fieldExpense,
            screenGolfExpense = screenExpense,
            equipmentGolfExpense = equipExpense,
            topFieldClubsSummary = topFieldClubs,
            topScreenVenuesSummary = topScreenVenues
        )
    }

    private fun buildActivityDeltaRows(
        cur: GolfActivityPeriodSnapshot,
        comp: GolfActivityPeriodSnapshot,
        yoy: GolfActivityPeriodSnapshot
    ): List<GolfMetricDeltaRow> {
        val raw = listOf(
            createIntRow(
                key = "FIELD_COUNT",
                title = "필드 라운딩 횟수",
                subtitle = "정규 골프장(CC/GC) 라운딩 횟수",
                unitSuffix = "회",
                curVal = cur.fieldRoundCount,
                compVal = comp.fieldRoundCount,
                yoyVal = yoy.fieldRoundCount,
                lowerIsBetter = false,
                detailNote = if (cur.topFieldClubsSummary.isNotBlank()) {
                    "주요 방문 구장: ${cur.topFieldClubsSummary}"
                } else {
                    "방문 골프장 ${cur.distinctClubCount}곳 (비교 기간 ${comp.distinctClubCount}곳)"
                }
            ),
            createIntRow(
                key = "SCREEN_COUNT",
                title = "스크린골프 횟수",
                subtitle = "골프존·프렌즈·카카오VX 등 스크린/연습 이용",
                unitSuffix = "회",
                curVal = cur.screenGolfCount,
                compVal = comp.screenGolfCount,
                yoyVal = yoy.screenGolfCount,
                lowerIsBetter = false,
                detailNote = if (cur.topScreenVenuesSummary.isNotBlank()) {
                    "주요 이용 매장: ${cur.topScreenVenuesSummary}"
                } else {
                    "스코어카드 및 스크린 결제 내역 통합 집계"
                }
            ),
            createIntRow(
                key = "TOTAL_ACTIVITY",
                title = "총 골프 활동 (필드+스크린)",
                subtitle = "기간 내 전체 필드 라운딩 및 스크린골프 합산",
                unitSuffix = "회",
                curVal = cur.totalActivityCount,
                compVal = comp.totalActivityCount,
                yoyVal = yoy.totalActivityCount,
                lowerIsBetter = false,
                detailNote = "필드 ${cur.fieldRoundCount}회 + 스크린 ${cur.screenGolfCount}회"
            ),
            createIntRow(
                key = "CLUB_DIVERSITY",
                title = "방문 골프장 다양성",
                subtitle = "기간 내 라운딩한 고유 골프장(CC/GC) 수",
                unitSuffix = "곳",
                curVal = cur.distinctClubCount,
                compVal = comp.distinctClubCount,
                yoyVal = yoy.distinctClubCount,
                lowerIsBetter = false,
                detailNote = if (cur.distinctClubCount > 0) {
                    "구장당 평균 %.1f회 방문".format(cur.fieldRoundCount.toDouble() / cur.distinctClubCount.coerceAtLeast(1))
                } else "해당 기간 방문 구장 없음"
            ),
            createLongExpenseRow(
                key = "GOLF_EXPENSE",
                title = "총 골프 관련 지출",
                subtitle = "필드 그린피·스크린 이용료·골프용품 결제 합계",
                curAmt = cur.totalGolfExpense,
                compAmt = comp.totalGolfExpense,
                yoyAmt = yoy.totalGolfExpense,
                detailNote = "필드 %,d원 · 스크린 %,d원 · 용품 %,d원".format(
                    cur.fieldGolfExpense,
                    cur.screenGolfExpense,
                    cur.equipmentGolfExpense
                )
            )
        )

        val highlightKey = raw
            .filter { it.metricKey in listOf("FIELD_COUNT", "SCREEN_COUNT", "TOTAL_ACTIVITY") && (it.deltaFromComparison ?: 0.0) != 0.0 }
            .maxByOrNull { abs(it.deltaFromComparison ?: 0.0) }
            ?.metricKey

        return raw.map { it.copy(isHighlightedChange = it.metricKey == highlightKey) }
    }

    private fun buildScoreShotDeltaRows(
        cur: GolfActivityPeriodSnapshot,
        comp: GolfActivityPeriodSnapshot,
        yoy: GolfActivityPeriodSnapshot
    ): List<GolfMetricDeltaRow> {
        val raw = mutableListOf<GolfMetricDeltaRow>()

        raw.add(
            createDoubleRow(
                key = "AVG_SCORE",
                title = "평균 타수 (18홀)",
                subtitle = "기간 내 스코어 기록 라운드(${cur.scoredRoundCount}회) 평균",
                unitSuffix = "타",
                curVal = cur.averageScore,
                compVal = comp.averageScore,
                yoyVal = yoy.averageScore,
                lowerIsBetter = true,
                detailNote = buildScoreRangeNote(cur, comp)
            )
        )

        raw.add(
            createDoubleRow(
                key = "BEST_SCORE",
                title = "기간 최저타 (베스트 스코어)",
                subtitle = "해당 기간 가장 잘 친 라운드 스코어",
                unitSuffix = "타",
                curVal = cur.bestScore?.toDouble(),
                compVal = comp.bestScore?.toDouble(),
                yoyVal = yoy.bestScore?.toDouble(),
                lowerIsBetter = true,
                isInteger = true,
                detailNote = if (cur.bestScore != null && !cur.bestScoreClubName.isNullOrBlank()) {
                    "달성 구장: ${cur.bestScoreClubName} (${cur.bestScore}타)"
                } else "해당 기간 스코어 기록 없음"
            )
        )

        raw.add(
            createDoubleRow(
                key = "AVG_PUTTS",
                title = "평균 퍼트수",
                subtitle = "18홀 기준 라운드당 평균 퍼트",
                unitSuffix = "P",
                curVal = cur.averagePutts,
                compVal = comp.averagePutts,
                yoyVal = yoy.averagePutts,
                lowerIsBetter = true,
                detailNote = if (cur.averagePutts != null) {
                    "홀당 평균 %.2f퍼트".format(cur.averagePutts / 18.0)
                } else "퍼트 기록 없음"
            )
        )

        raw.add(
            createDoubleRow(
                key = "PAR_OR_BETTER_RATE",
                title = "파 세이브율 (버디+파 비율)",
                subtitle = "18홀 중 파(Par) 이하로 막아낸 홀 비중",
                unitSuffix = "%",
                curVal = cur.parOrBetterRatePercent,
                compVal = comp.parOrBetterRatePercent,
                yoyVal = yoy.parOrBetterRatePercent,
                lowerIsBetter = false,
                detailNote = if (cur.averageBirdiesPerRound != null && cur.averageParsPerRound != null) {
                    "라운드당 평균 버디 ${cur.averageBirdiesPerRound}개 · 파 ${cur.averageParsPerRound}개"
                } else "홀별 상세 스코어 기록 기준"
            )
        )

        raw.add(
            createDoubleRow(
                key = "AVG_BIRDIES",
                title = "라운드당 평균 버디",
                subtitle = "한 라운드(18홀)당 기록한 평균 버디 수",
                unitSuffix = "개",
                curVal = cur.averageBirdiesPerRound,
                compVal = comp.averageBirdiesPerRound,
                yoyVal = yoy.averageBirdiesPerRound,
                lowerIsBetter = false,
                detailNote = "버디 결정력 및 공격적 핀 공략 지표"
            )
        )

        raw.add(
            createDoubleRow(
                key = "AVG_DOUBLE_PLUS",
                title = "라운드당 더블보기 이상",
                subtitle = "18홀 중 더블보기(+2) 이상 대량 실점 홀수",
                unitSuffix = "홀",
                curVal = cur.averageDoublePlusPerRound,
                compVal = comp.averageDoublePlusPerRound,
                yoyVal = yoy.averageDoublePlusPerRound,
                lowerIsBetter = true,
                detailNote = if (cur.averageBogeysPerRound != null) {
                    "라운드당 평균 보기 ${cur.averageBogeysPerRound}홀 · 더블+ ${cur.averageDoublePlusPerRound ?: 0.0}홀"
                } else "스코어 방어 안정성 지표"
            )
        )

        raw.add(
            createDoubleRow(
                key = "AVG_PENALTY",
                title = "라운드당 평균 벌타",
                subtitle = "OB · 해저드 등 평균 페널티 타수",
                unitSuffix = "타",
                curVal = cur.averagePenalty,
                compVal = comp.averagePenalty,
                yoyVal = yoy.averagePenalty,
                lowerIsBetter = true,
                detailNote = "티샷 안정성 및 코스 매니지먼트 지표"
            )
        )

        if (cur.averageDriveDistanceM != null || comp.averageDriveDistanceM != null || yoy.averageDriveDistanceM != null) {
            raw.add(
                createDoubleRow(
                    key = "AVG_DRIVE",
                    title = "평균 티샷 비거리",
                    subtitle = "드라이버 티샷 평균 비거리 (파3 제외)",
                    unitSuffix = "m",
                    curVal = cur.averageDriveDistanceM,
                    compVal = comp.averageDriveDistanceM,
                    yoyVal = yoy.averageDriveDistanceM,
                    lowerIsBetter = false,
                    detailNote = if (cur.averageAdjustedDriveDistanceM != null) {
                        "최고·최저 제외 보정 비거리: ${cur.averageAdjustedDriveDistanceM}m"
                    } else "티샷 비거리 추이"
                )
            )
        }

        if (cur.averageGirPercent != null || comp.averageGirPercent != null || yoy.averageGirPercent != null) {
            raw.add(
                createDoubleRow(
                    key = "AVG_GIR",
                    title = "그린 적중률 (GIR)",
                    subtitle = "정규 타수 내 온그린 성공 비율",
                    unitSuffix = "%",
                    curVal = cur.averageGirPercent,
                    compVal = comp.averageGirPercent,
                    yoyVal = yoy.averageGirPercent,
                    lowerIsBetter = false,
                    detailNote = if (cur.averageGirPercent != null) {
                        "18홀 중 평균 %.1f홀 정규 온그린".format(cur.averageGirPercent * 0.18)
                    } else "아이언 샷 정확도 지표"
                )
            )
        }

        val highlightKey = raw
            .firstOrNull { it.metricKey == "AVG_SCORE" && it.deltaFromComparison != null && abs(it.deltaFromComparison) >= 0.1 }
            ?.metricKey
            ?: raw.filter { it.deltaFromComparison != null && abs(it.deltaFromComparison) > 0.05 }
                .maxByOrNull { abs(it.deltaPercentFromComparison ?: 0f) }
                ?.metricKey

        return raw.map { it.copy(isHighlightedChange = it.metricKey == highlightKey) }
    }

    private fun buildScoreRangeNote(
        cur: GolfActivityPeriodSnapshot,
        comp: GolfActivityPeriodSnapshot
    ): String {
        if (cur.bestScore == null) return "이번 기간 집계된 18홀 스코어 기록 없음"
        val curRange = if (cur.worstScore != null && cur.worstScore != cur.bestScore) {
            "최저 ${cur.bestScore}타 ~ 최고 ${cur.worstScore}타"
        } else {
            "단일 스코어 ${cur.bestScore}타"
        }
        val compAvgText = comp.averageScore?.let { " (비교 기간 평균 ${it}타)" }.orEmpty()
        return "$curRange$compAvgText"
    }

    private fun createIntRow(
        key: String,
        title: String,
        subtitle: String,
        unitSuffix: String,
        curVal: Int,
        compVal: Int,
        yoyVal: Int,
        lowerIsBetter: Boolean,
        detailNote: String
    ): GolfMetricDeltaRow {
        val deltaComp = (curVal - compVal).toDouble()
        val pctComp = calcIntPct(curVal, compVal)
        val deltaYoy = (curVal - yoyVal).toDouble()
        val pctYoy = calcIntPct(curVal, yoyVal)
        return GolfMetricDeltaRow(
            metricKey = key,
            title = title,
            subtitle = subtitle,
            unitSuffix = unitSuffix,
            currentValue = curVal.toDouble(),
            comparisonValue = compVal.toDouble(),
            yoyValue = yoyVal.toDouble(),
            deltaFromComparison = deltaComp,
            deltaPercentFromComparison = pctComp,
            deltaFromYoy = deltaYoy,
            deltaPercentFromYoy = pctYoy,
            lowerIsBetter = lowerIsBetter,
            isIntegerMetric = true,
            detailNote = detailNote
        )
    }

    private fun createLongExpenseRow(
        key: String,
        title: String,
        subtitle: String,
        curAmt: Long,
        compAmt: Long,
        yoyAmt: Long,
        detailNote: String
    ): GolfMetricDeltaRow {
        val deltaComp = (curAmt - compAmt).toDouble()
        val pctComp = ExpenseAnalyticsEngine.calculatePercentChange(curAmt, compAmt)
        val deltaYoy = (curAmt - yoyAmt).toDouble()
        val pctYoy = ExpenseAnalyticsEngine.calculatePercentChange(curAmt, yoyAmt)
        return GolfMetricDeltaRow(
            metricKey = key,
            title = title,
            subtitle = subtitle,
            unitSuffix = "원",
            currentValue = curAmt.toDouble(),
            comparisonValue = compAmt.toDouble(),
            yoyValue = yoyAmt.toDouble(),
            deltaFromComparison = deltaComp,
            deltaPercentFromComparison = pctComp,
            deltaFromYoy = deltaYoy,
            deltaPercentFromYoy = pctYoy,
            lowerIsBetter = true,
            isIntegerMetric = true,
            detailNote = detailNote
        )
    }

    private fun createDoubleRow(
        key: String,
        title: String,
        subtitle: String,
        unitSuffix: String,
        curVal: Double?,
        compVal: Double?,
        yoyVal: Double?,
        lowerIsBetter: Boolean,
        isInteger: Boolean = false,
        detailNote: String
    ): GolfMetricDeltaRow {
        val deltaComp = if (curVal != null && compVal != null) round1(curVal - compVal) else null
        val pctComp = if (curVal != null && compVal != null && abs(compVal) > 0.05) {
            ((curVal - compVal) / compVal * 100.0).toFloat()
        } else null
        val deltaYoy = if (curVal != null && yoyVal != null) round1(curVal - yoyVal) else null
        val pctYoy = if (curVal != null && yoyVal != null && abs(yoyVal) > 0.05) {
            ((curVal - yoyVal) / yoyVal * 100.0).toFloat()
        } else null

        return GolfMetricDeltaRow(
            metricKey = key,
            title = title,
            subtitle = subtitle,
            unitSuffix = unitSuffix,
            currentValue = curVal,
            comparisonValue = compVal,
            yoyValue = yoyVal,
            deltaFromComparison = deltaComp,
            deltaPercentFromComparison = pctComp,
            deltaFromYoy = deltaYoy,
            deltaPercentFromYoy = pctYoy,
            lowerIsBetter = lowerIsBetter,
            isIntegerMetric = isInteger,
            detailNote = detailNote
        )
    }

    private fun buildHeadlineInsight(
        targetTitle: String,
        basisName: String,
        cur: GolfActivityPeriodSnapshot,
        comp: GolfActivityPeriodSnapshot
    ): String {
        if (cur.totalActivityCount == 0 && comp.totalActivityCount == 0) {
            return "$targetTitle 및 비교 기간에 기록된 필드 라운딩·스크린골프 내역이 없습니다."
        }
        val totalDiff = cur.totalActivityCount - comp.totalActivityCount
        val fieldDiff = cur.fieldRoundCount - comp.fieldRoundCount
        val screenDiff = cur.screenGolfCount - comp.screenGolfCount
        val pctStr = formatSignedCountPercent(
            calcIntPct(cur.totalActivityCount, comp.totalActivityCount),
            cur.totalActivityCount,
            comp.totalActivityCount
        )

        val countPart = when {
            totalDiff > 0 ->
                "$targetTitle 총 골프 활동은 ${cur.totalActivityCount}회(필드 ${cur.fieldRoundCount}회 · 스크린 ${cur.screenGolfCount}회)로, $basisName +${totalDiff}회($pctStr) 늘었습니다."
            totalDiff < 0 ->
                "$targetTitle 총 골프 활동은 ${cur.totalActivityCount}회(필드 ${cur.fieldRoundCount}회 · 스크린 ${cur.screenGolfCount}회)로, $basisName ${totalDiff}회($pctStr) 줄었습니다."
            else ->
                "$targetTitle 총 골프 활동은 ${cur.totalActivityCount}회(필드 ${cur.fieldRoundCount}회 · 스크린 ${cur.screenGolfCount}회)로 $basisName 활동 횟수가 동일합니다."
        }

        val detailMix = when {
            fieldDiff != 0 || screenDiff != 0 ->
                " (필드 ${formatSignedInt(fieldDiff)}회, 스크린 ${formatSignedInt(screenDiff)}회)"
            else -> ""
        }
        return "$countPart$detailMix"
    }

    private fun buildScoreAnalysisInsight(
        basisName: String,
        cur: GolfActivityPeriodSnapshot,
        comp: GolfActivityPeriodSnapshot
    ): String {
        if (cur.averageScore == null && comp.averageScore == null) {
            return "스코어카드가 등록되면 평균 타수, 퍼트, 버디·파 세이브율 변화 원인을 자동으로 진단합니다."
        }
        if (cur.averageScore != null && comp.averageScore == null) {
            val bestNote = cur.bestScore?.let { " 최저타는 ${it}타(${cur.bestScoreClubName ?: "구장"})입니다." }.orEmpty()
            return "이번 기간 평균 타수는 ${cur.averageScore}타입니다.$bestNote (비교 기간 스코어 기록 없음)"
        }
        if (cur.averageScore == null && comp.averageScore != null) {
            return "이번 기간에는 스코어가 입력된 라운드가 없습니다. (비교 기간 평균 ${comp.averageScore}타)"
        }

        val curScore = cur.averageScore!!
        val compScore = comp.averageScore!!
        val scoreDiff = round1(curScore - compScore)
        val parts = mutableListOf<String>()

        when {
            scoreDiff < -0.1 ->
                parts.add("$basisName 평균 타수가 ${compScore}타에서 ${curScore}타로 ${abs(scoreDiff)}타 단축(실력 향상)되었습니다.")
            scoreDiff > 0.1 ->
                parts.add("$basisName 평균 타수가 ${compScore}타에서 ${curScore}타로 +${scoreDiff}타 상승했습니다.")
            else ->
                parts.add("$basisName 평균 타수가 ${curScore}타로 일정하게 유지되었습니다.")
        }

        // 원인 분석 (퍼트, 벌타, 더블보기, 파 세이브율)
        val reasons = mutableListOf<String>()
        if (cur.averagePutts != null && comp.averagePutts != null) {
            val puttDiff = round1(cur.averagePutts - comp.averagePutts)
            if (abs(puttDiff) >= 0.3) {
                val sign = if (puttDiff > 0) "+${puttDiff}P 증가" else "${puttDiff}P 감소"
                reasons.add("평균 퍼트수 변화(${comp.averagePutts}P→${cur.averagePutts}P, $sign)")
            }
        }
        if (cur.averagePenalty != null && comp.averagePenalty != null) {
            val penDiff = round1(cur.averagePenalty - comp.averagePenalty)
            if (abs(penDiff) >= 0.3) {
                val sign = if (penDiff > 0) "+${penDiff}타 증가" else "${penDiff}타 감소"
                reasons.add("평균 벌타 변화($sign)")
            }
        }
        if (cur.averageDoublePlusPerRound != null && comp.averageDoublePlusPerRound != null) {
            val dblDiff = round1(cur.averageDoublePlusPerRound - comp.averageDoublePlusPerRound)
            if (abs(dblDiff) >= 0.3) {
                val sign = if (dblDiff > 0) "+${dblDiff}홀 증가" else "${dblDiff}홀 감소"
                reasons.add("더블보기 이상 홀수 변화($sign)")
            }
        }

        if (reasons.isNotEmpty()) {
            parts.add("주요 요인은 ${reasons.joinToString(" 및 ")}입니다.")
        }
        if (cur.bestScore != null) {
            parts.add("기간 내 베스트 스코어는 ${cur.bestScore}타(${cur.bestScoreClubName ?: "라운드"})입니다.")
        }
        return parts.joinToString(" ")
    }

    private fun buildYoyComparisonInsight(
        unit: AnalyticsPeriodUnit,
        yoyPeriod: PeriodSpec,
        cur: GolfActivityPeriodSnapshot,
        yoy: GolfActivityPeriodSnapshot
    ): String {
        val label = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> "전년 동월(${yoyPeriod.compactTitle})"
            AnalyticsPeriodUnit.QUARTERLY -> "전년 동분기(${yoyPeriod.compactTitle})"
            AnalyticsPeriodUnit.HALF_YEARLY -> "전년 동반기(${yoyPeriod.compactTitle})"
            AnalyticsPeriodUnit.YEARLY -> "전년도(${yoyPeriod.compactTitle})"
        }
        if (yoy.totalActivityCount == 0 && yoy.averageScore == null) {
            return "$label 기록된 골프 라운드 및 스크린골프 내역이 없습니다."
        }
        val fieldDiff = cur.fieldRoundCount - yoy.fieldRoundCount
        val screenDiff = cur.screenGolfCount - yoy.screenGolfCount
        val scoreNote = if (cur.averageScore != null && yoy.averageScore != null) {
            val sDiff = round1(cur.averageScore - yoy.averageScore)
            val sText = when {
                sDiff < 0 -> "${abs(sDiff)}타 단축(${yoy.averageScore}타→${cur.averageScore}타)"
                sDiff > 0 -> "+${sDiff}타 상승(${yoy.averageScore}타→${cur.averageScore}타)"
                else -> "${cur.averageScore}타 동일"
            }
            ", 평균 스코어는 $sText"
        } else ""

        return "$label 대비 필드 라운딩 ${formatSignedInt(fieldDiff)}회(${yoy.fieldRoundCount}회→${cur.fieldRoundCount}회), 스크린골프 ${formatSignedInt(screenDiff)}회(${yoy.screenGolfCount}회→${cur.screenGolfCount}회)$scoreNote 변화했습니다."
    }

    fun formatMetricValue(value: Double?, unitSuffix: String, isInteger: Boolean): String {
        if (value == null) return "-"
        return if (unitSuffix == "원") {
            "%,d원".format(value.toLong())
        } else if (isInteger) {
            "%,d%s".format(value.roundToInt(), unitSuffix)
        } else {
            "%.1f%s".format(value, unitSuffix)
        }
    }

    fun formatSignedDelta(delta: Double?, unitSuffix: String, isInteger: Boolean): String {
        if (delta == null) return "비교 기록 없음"
        if (abs(delta) < 0.04) return "변동 없음"
        return if (unitSuffix == "원") {
            val l = delta.toLong()
            if (l > 0L) "+%,d원".format(l) else "-%,d원".format(abs(l))
        } else if (isInteger) {
            val i = delta.roundToInt()
            if (i > 0) "+%,d%s".format(i, unitSuffix) else "%,d%s".format(i, unitSuffix)
        } else {
            if (delta > 0.0) "+%.1f%s".format(delta, unitSuffix) else "%.1f%s".format(delta, unitSuffix)
        }
    }

    fun formatSignedInt(delta: Int): String = when {
        delta > 0 -> "+$delta"
        delta < 0 -> "$delta"
        else -> "0"
    }

    fun formatSignedCountPercent(pct: Float?, cur: Int, base: Int): String {
        if (pct == null) {
            return if (cur > 0 && base == 0) "신규 활동" else "0.0%"
        }
        return when {
            pct > 0.04f -> "+%.1f%%".format(pct)
            pct < -0.04f -> "%.1f%%".format(pct)
            else -> "0.0%"
        }
    }

    private fun calcIntPct(cur: Int, base: Int): Float? {
        if (base <= 0) return null
        return ((cur - base).toDouble() / base.toDouble() * 100.0).toFloat()
    }

    private fun round1(v: Double): Double = kotlin.math.round(v * 10.0) / 10.0
}
