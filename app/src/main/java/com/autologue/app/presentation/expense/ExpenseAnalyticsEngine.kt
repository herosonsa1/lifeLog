package com.autologue.app.presentation.expense

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.isDepositTransaction
import com.autologue.app.domain.model.isSelfTransfer
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class AnalyticsPeriodUnit(val label: String, val shortLabel: String) {
    MONTHLY("월간 비교", "월간"),
    QUARTERLY("분기별 비교", "분기별"),
    HALF_YEARLY("반기별 비교", "반기별"),
    YEARLY("연간 비교", "연간")
}

enum class AnalyticsComparisonBasis(val label: String) {
    PREVIOUS_PERIOD("직전 기간 대비"),
    YEAR_OVER_YEAR("전년 동기 대비")
}

enum class ExpenseDeltaSortMode(val label: String) {
    BY_CHANGE_MAGNITUDE("변동폭 큰 순"),
    BY_INCREASE_AMOUNT("증가액 순"),
    BY_DECREASE_AMOUNT("절감액 순"),
    BY_CURRENT_AMOUNT("현재 지출 순")
}

enum class ExpenseAnalyticsSubTab(val label: String) {
    OVERVIEW_INSIGHT("종합 비교 · 인사이트"),
    CATEGORY_DELTA_TABLE("항목별 증감 도표")
}

data class PeriodSpec(
    val unit: AnalyticsPeriodUnit,
    val year: Int,
    val subIndex: Int // MONTHLY: 1..12, QUARTERLY: 1..4, HALF_YEARLY: 1..2, YEARLY: 1
) {
    val startDate: LocalDate
        get() = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> YearMonth.of(year, subIndex.coerceIn(1, 12)).atDay(1)
            AnalyticsPeriodUnit.QUARTERLY -> {
                val startMonth = ((subIndex.coerceIn(1, 4) - 1) * 3) + 1
                YearMonth.of(year, startMonth).atDay(1)
            }
            AnalyticsPeriodUnit.HALF_YEARLY -> {
                val startMonth = if (subIndex <= 1) 1 else 7
                YearMonth.of(year, startMonth).atDay(1)
            }
            AnalyticsPeriodUnit.YEARLY -> LocalDate.of(year, 1, 1)
        }

    val endDate: LocalDate
        get() = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> YearMonth.of(year, subIndex.coerceIn(1, 12)).atEndOfMonth()
            AnalyticsPeriodUnit.QUARTERLY -> {
                val endMonth = (subIndex.coerceIn(1, 4) * 3)
                YearMonth.of(year, endMonth).atEndOfMonth()
            }
            AnalyticsPeriodUnit.HALF_YEARLY -> {
                val endMonth = if (subIndex <= 1) 6 else 12
                YearMonth.of(year, endMonth).atEndOfMonth()
            }
            AnalyticsPeriodUnit.YEARLY -> LocalDate.of(year, 12, 31)
        }

    val totalDays: Int
        get() = ChronoUnit.DAYS.between(startDate, endDate).toInt() + 1

    val displayTitle: String
        get() = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> "${year}년 ${subIndex}월"
            AnalyticsPeriodUnit.QUARTERLY -> {
                val mStart = ((subIndex - 1) * 3) + 1
                val mEnd = subIndex * 3
                "${year}년 ${subIndex}분기 (${mStart}~${mEnd}월)"
            }
            AnalyticsPeriodUnit.HALF_YEARLY -> {
                if (subIndex <= 1) "${year}년 상반기 (1~6월)" else "${year}년 하반기 (7~12월)"
            }
            AnalyticsPeriodUnit.YEARLY -> "${year}년 연간 (1~12월)"
        }

    val compactTitle: String
        get() = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> "${year % 100}년 ${subIndex}월"
            AnalyticsPeriodUnit.QUARTERLY -> "${year % 100}년 ${subIndex}분기"
            AnalyticsPeriodUnit.HALF_YEARLY -> if (subIndex <= 1) "${year % 100}년 상반기" else "${year % 100}년 하반기"
            AnalyticsPeriodUnit.YEARLY -> "${year}년"
        }

    val shortChartLabel: String
        get() = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> "${subIndex}월"
            AnalyticsPeriodUnit.QUARTERLY -> "${year % 100}.${subIndex}Q"
            AnalyticsPeriodUnit.HALF_YEARLY -> if (subIndex <= 1) "${year % 100}.상" else "${year % 100}.하"
            AnalyticsPeriodUnit.YEARLY -> "${year}년"
        }

    fun previousSequential(): PeriodSpec = when (unit) {
        AnalyticsPeriodUnit.MONTHLY -> {
            val prevYm = YearMonth.of(year, subIndex.coerceIn(1, 12)).minusMonths(1)
            PeriodSpec(unit, prevYm.year, prevYm.monthValue)
        }
        AnalyticsPeriodUnit.QUARTERLY -> {
            if (subIndex > 1) PeriodSpec(unit, year, subIndex - 1)
            else PeriodSpec(unit, year - 1, 4)
        }
        AnalyticsPeriodUnit.HALF_YEARLY -> {
            if (subIndex > 1) PeriodSpec(unit, year, 1)
            else PeriodSpec(unit, year - 1, 2)
        }
        AnalyticsPeriodUnit.YEARLY -> PeriodSpec(unit, year - 1, 1)
    }

    fun nextSequential(): PeriodSpec = when (unit) {
        AnalyticsPeriodUnit.MONTHLY -> {
            val nextYm = YearMonth.of(year, subIndex.coerceIn(1, 12)).plusMonths(1)
            PeriodSpec(unit, nextYm.year, nextYm.monthValue)
        }
        AnalyticsPeriodUnit.QUARTERLY -> {
            if (subIndex < 4) PeriodSpec(unit, year, subIndex + 1)
            else PeriodSpec(unit, year + 1, 1)
        }
        AnalyticsPeriodUnit.HALF_YEARLY -> {
            if (subIndex < 2) PeriodSpec(unit, year, 2)
            else PeriodSpec(unit, year + 1, 1)
        }
        AnalyticsPeriodUnit.YEARLY -> PeriodSpec(unit, year + 1, 1)
    }

    fun samePeriodLastYear(): PeriodSpec = PeriodSpec(unit, year - 1, subIndex)

    fun sequentialBasisLabel(): String = when (unit) {
        AnalyticsPeriodUnit.MONTHLY -> "전월 대비"
        AnalyticsPeriodUnit.QUARTERLY -> "전분기 대비"
        AnalyticsPeriodUnit.HALF_YEARLY -> "전반기 대비"
        AnalyticsPeriodUnit.YEARLY -> "전년 대비"
    }

    fun yoyBasisLabel(): String = when (unit) {
        AnalyticsPeriodUnit.MONTHLY -> "전년 동월 대비"
        AnalyticsPeriodUnit.QUARTERLY -> "전년 동분기 대비"
        AnalyticsPeriodUnit.HALF_YEARLY -> "전년 동반기 대비"
        AnalyticsPeriodUnit.YEARLY -> "전년 대비"
    }

    fun contains(date: LocalDate): Boolean = !date.isBefore(startDate) && !date.isAfter(endDate)

    companion object {
        fun fromDate(unit: AnalyticsPeriodUnit, date: LocalDate): PeriodSpec = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> PeriodSpec(unit, date.year, date.monthValue)
            AnalyticsPeriodUnit.QUARTERLY -> PeriodSpec(unit, date.year, ((date.monthValue - 1) / 3) + 1)
            AnalyticsPeriodUnit.HALF_YEARLY -> PeriodSpec(unit, date.year, if (date.monthValue <= 6) 1 else 2)
            AnalyticsPeriodUnit.YEARLY -> PeriodSpec(unit, date.year, 1)
        }
    }
}

data class CategoryDeltaItem(
    val categoryName: String,
    val currentAmount: Long,
    val comparisonAmount: Long,
    val yoyAmount: Long,
    val deltaAmount: Long,
    val deltaPercent: Float?,
    val currentSharePercent: Float,
    val comparisonSharePercent: Float,
    val sharePointDiff: Float,
    val currentTxCount: Int,
    val comparisonTxCount: Int,
    val currentAvgPerTx: Long,
    val comparisonAvgPerTx: Long,
    val topMerchantsSummary: String,
    val changeCauseSummary: String,
    val isLargestIncrease: Boolean = false,
    val isLargestDecrease: Boolean = false,
    val isLargestMagnitudeChange: Boolean = false
)

data class PeriodTrendBarItem(
    val period: PeriodSpec,
    val totalExpense: Long,
    val totalIncome: Long,
    val txCount: Int,
    val isSelectedCurrent: Boolean,
    val isComparisonTarget: Boolean
)

data class ExpensePeriodComparisonReport(
    val unit: AnalyticsPeriodUnit,
    val effectiveBasis: AnalyticsComparisonBasis,
    val currentPeriod: PeriodSpec,
    val previousPeriod: PeriodSpec,
    val yoyPeriod: PeriodSpec,
    val activeComparisonPeriod: PeriodSpec,
    val currentTotalExpense: Long,
    val previousTotalExpense: Long,
    val yoyTotalExpense: Long,
    val activeComparisonTotalExpense: Long,
    val currentTotalIncome: Long,
    val activeComparisonTotalIncome: Long,
    val seqDeltaAmount: Long,
    val seqDeltaPercent: Float?,
    val yoyDeltaAmount: Long,
    val yoyDeltaPercent: Float?,
    val activeDeltaAmount: Long,
    val activeDeltaPercent: Float?,
    val currentTxCount: Int,
    val activeComparisonTxCount: Int,
    val currentDailyAverage: Long,
    val activeComparisonDailyAverage: Long,
    val largestChangeItem: CategoryDeltaItem?,
    val topIncreaseItem: CategoryDeltaItem?,
    val topDecreaseItem: CategoryDeltaItem?,
    val headlineInsight: String,
    val causeAnalysisInsight: String,
    val yoyComparisonInsight: String,
    val trendBars: List<PeriodTrendBarItem>,
    val categoryDeltas: List<CategoryDeltaItem>
)

object ExpenseAnalyticsEngine {

    fun calculatePercentChange(current: Long, baseline: Long): Float? {
        if (baseline <= 0L) return null
        return ((current - baseline).toDouble() / baseline.toDouble() * 100.0).toFloat()
    }

    fun formatSignedAmount(delta: Long): String {
        return when {
            delta > 0L -> "+%,d원".format(delta)
            delta < 0L -> "-%,d원".format(abs(delta))
            else -> "0원 (변동 없음)"
        }
    }

    fun formatSignedPercent(pct: Float?, current: Long = 0L, baseline: Long = 0L): String {
        if (pct == null) {
            return if (current > 0L && baseline == 0L) "신규 발생" else "0.0%"
        }
        return when {
            pct > 0.04f -> "+%.1f%%".format(pct)
            pct < -0.04f -> "%.1f%%".format(pct)
            else -> "0.0%"
        }
    }

    fun buildReport(
        allTransactions: List<Transaction>,
        unit: AnalyticsPeriodUnit,
        targetPeriod: PeriodSpec,
        requestedBasis: AnalyticsComparisonBasis,
        customCategoryByTxId: Map<Long, String>,
        userClassByTxId: Map<Long, String>,
        corporateByTxId: Map<Long, Boolean>,
        recurringTransactionIds: Set<Long>,
        selectedUserFilter: String? = null,
        selectedCorporateFilter: Boolean? = null,
        isRecurringFilterOnly: Boolean = false,
        getBuiltInCategoryName: (ExpenseCategory) -> String
    ): ExpensePeriodComparisonReport {
        val effectiveBasis = if (unit == AnalyticsPeriodUnit.YEARLY) {
            AnalyticsComparisonBasis.YEAR_OVER_YEAR
        } else {
            requestedBasis
        }

        // 필터 조건(사용자 분류, 법인카드, 고정항목) 적용
        var baseFiltered = allTransactions
        if (isRecurringFilterOnly) {
            baseFiltered = baseFiltered.filter { it.id in recurringTransactionIds }
        }
        if (!selectedUserFilter.isNullOrBlank()) {
            baseFiltered = if (selectedUserFilter == UNASSIGNED_USER_FILTER) {
                baseFiltered.filter { userClassByTxId[it.id] == null }
            } else {
                baseFiltered.filter { userClassByTxId[it.id] == selectedUserFilter }
            }
        }
        if (selectedCorporateFilter != null) {
            baseFiltered = baseFiltered.filter { (corporateByTxId[it.id] == true) == selectedCorporateFilter }
        }

        val expenseTxs = baseFiltered.filter { tx ->
            tx.category != ExpenseCategory.INCOME && !tx.isSelfTransfer() && tx.amount > 0L
        }
        val incomeTxs = baseFiltered.filter { tx ->
            (tx.category == ExpenseCategory.INCOME || tx.isDepositTransaction()) && tx.amount > 0L
        }

        val previousPeriod = targetPeriod.previousSequential()
        val yoyPeriod = targetPeriod.samePeriodLastYear()
        val activeComparisonPeriod = when (effectiveBasis) {
            AnalyticsComparisonBasis.PREVIOUS_PERIOD -> previousPeriod
            AnalyticsComparisonBasis.YEAR_OVER_YEAR -> yoyPeriod
        }

        val currentExpList = expenseTxs.filter { targetPeriod.contains(it.timestamp.toLocalDate()) }
        val prevExpList = expenseTxs.filter { previousPeriod.contains(it.timestamp.toLocalDate()) }
        val yoyExpList = expenseTxs.filter { yoyPeriod.contains(it.timestamp.toLocalDate()) }
        val activeCompExpList = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) prevExpList else yoyExpList

        val currentIncome = incomeTxs
            .filter { targetPeriod.contains(it.timestamp.toLocalDate()) }
            .sumOf { it.amount }
        val activeCompIncome = incomeTxs
            .filter { activeComparisonPeriod.contains(it.timestamp.toLocalDate()) }
            .sumOf { it.amount }

        val currentTotal = currentExpList.sumOf { it.amount }
        val prevTotal = prevExpList.sumOf { it.amount }
        val yoyTotal = yoyExpList.sumOf { it.amount }
        val activeCompTotal = activeCompExpList.sumOf { it.amount }

        val seqDeltaAmt = currentTotal - prevTotal
        val seqDeltaPct = calculatePercentChange(currentTotal, prevTotal)
        val yoyDeltaAmt = currentTotal - yoyTotal
        val yoyDeltaPct = calculatePercentChange(currentTotal, yoyTotal)
        val activeDeltaAmt = currentTotal - activeCompTotal
        val activeDeltaPct = calculatePercentChange(currentTotal, activeCompTotal)

        val currentDailyAvg = if (targetPeriod.totalDays > 0) currentTotal / targetPeriod.totalDays else 0L
        val activeCompDailyAvg = if (activeComparisonPeriod.totalDays > 0) activeCompTotal / activeComparisonPeriod.totalDays else 0L

        // 카테고리별 집계
        fun resolveCatName(tx: Transaction): String {
            return customCategoryByTxId[tx.id]?.takeIf { it.isNotBlank() }
                ?: getBuiltInCategoryName(tx.category)
        }

        val currentByCat = currentExpList.groupBy(::resolveCatName)
        val activeCompByCat = activeCompExpList.groupBy(::resolveCatName)
        val yoyByCat = yoyExpList.groupBy(::resolveCatName)

        val allCatNames = (currentByCat.keys + activeCompByCat.keys).distinct()
        val currentTotalSafe = currentTotal.coerceAtLeast(1L)
        val activeCompTotalSafe = activeCompTotal.coerceAtLeast(1L)

        val rawDeltas = allCatNames.mapNotNull { catName ->
            val curTxs = currentByCat[catName].orEmpty()
            val compTxs = activeCompByCat[catName].orEmpty()
            val yoyCatTxs = yoyByCat[catName].orEmpty()

            val curAmt = curTxs.sumOf { it.amount }
            val compAmt = compTxs.sumOf { it.amount }
            val yoyAmt = yoyCatTxs.sumOf { it.amount }
            if (curAmt == 0L && compAmt == 0L) return@mapNotNull null

            val deltaAmt = curAmt - compAmt
            val deltaPct = calculatePercentChange(curAmt, compAmt)
            val curShare = if (currentTotal > 0L) (curAmt.toFloat() / currentTotalSafe * 100f) else 0f
            val compShare = if (activeCompTotal > 0L) (compAmt.toFloat() / activeCompTotalSafe * 100f) else 0f
            val curCnt = curTxs.size
            val compCnt = compTxs.size
            val curAvg = if (curCnt > 0) curAmt / curCnt else 0L
            val compAvg = if (compCnt > 0) compAmt / compCnt else 0L

            val topMerchants = curTxs
                .groupBy { it.merchantName.ifBlank { "기타 가맹점" } }
                .mapValues { (_, mTxs) -> mTxs.sumOf { it.amount } }
                .entries
                .sortedByDescending { it.value }
                .take(2)
                .joinToString(" · ") { (mName, mAmt) -> "$mName ${"%,d원".format(mAmt)}" }

            val causeSummary = buildCategoryCauseSummary(
                curAmt = curAmt,
                compAmt = compAmt,
                curCnt = curCnt,
                compCnt = compCnt,
                curAvg = curAvg,
                compAvg = compAvg
            )

            CategoryDeltaItem(
                categoryName = catName,
                currentAmount = curAmt,
                comparisonAmount = compAmt,
                yoyAmount = yoyAmt,
                deltaAmount = deltaAmt,
                deltaPercent = deltaPct,
                currentSharePercent = curShare,
                comparisonSharePercent = compShare,
                sharePointDiff = curShare - compShare,
                currentTxCount = curCnt,
                comparisonTxCount = compCnt,
                currentAvgPerTx = curAvg,
                comparisonAvgPerTx = compAvg,
                topMerchantsSummary = topMerchants,
                changeCauseSummary = causeSummary
            )
        }

        val maxIncreaseCat = rawDeltas.filter { it.deltaAmount > 0L }.maxByOrNull { it.deltaAmount }?.categoryName
        val maxDecreaseCat = rawDeltas.filter { it.deltaAmount < 0L }.minByOrNull { it.deltaAmount }?.categoryName
        val maxMagnitudeCat = rawDeltas.filter { it.deltaAmount != 0L }.maxByOrNull { abs(it.deltaAmount) }?.categoryName

        val enrichedDeltas = rawDeltas
            .map { item ->
                item.copy(
                    isLargestIncrease = item.categoryName == maxIncreaseCat,
                    isLargestDecrease = item.categoryName == maxDecreaseCat,
                    isLargestMagnitudeChange = item.categoryName == maxMagnitudeCat
                )
            }
            .sortedWith(
                compareByDescending<CategoryDeltaItem> { abs(it.deltaAmount) }
                    .thenByDescending { it.currentAmount }
            )

        val largestChangeItem = enrichedDeltas.firstOrNull { it.isLargestMagnitudeChange }
        val topIncreaseItem = enrichedDeltas.firstOrNull { it.isLargestIncrease }
        val topDecreaseItem = enrichedDeltas.firstOrNull { it.isLargestDecrease }

        // 추이 막대 생성 (월간 6개, 분기 6개, 반기 4개, 연간 4개)
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

        val trendBars = trendPeriods.map { pSpec ->
            val pExp = expenseTxs.filter { pSpec.contains(it.timestamp.toLocalDate()) }
            val pInc = incomeTxs.filter { pSpec.contains(it.timestamp.toLocalDate()) }
            PeriodTrendBarItem(
                period = pSpec,
                totalExpense = pExp.sumOf { it.amount },
                totalIncome = pInc.sumOf { it.amount },
                txCount = pExp.size,
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
            currentTotal = currentTotal,
            compTotal = activeCompTotal,
            deltaAmount = activeDeltaAmt,
            deltaPercent = activeDeltaPct
        )

        val causeAnalysisInsight = buildCauseAnalysisInsight(
            basisName = basisName,
            totalDelta = activeDeltaAmt,
            largestChangeItem = largestChangeItem,
            topIncreaseItem = topIncreaseItem,
            topDecreaseItem = topDecreaseItem
        )

        val yoyComparisonInsight = buildYoyInsight(
            unit = unit,
            yoyPeriod = yoyPeriod,
            currentTotal = currentTotal,
            yoyTotal = yoyTotal,
            yoyDeltaAmt = yoyDeltaAmt,
            yoyDeltaPct = yoyDeltaPct,
            enrichedDeltas = enrichedDeltas
        )

        return ExpensePeriodComparisonReport(
            unit = unit,
            effectiveBasis = effectiveBasis,
            currentPeriod = targetPeriod,
            previousPeriod = previousPeriod,
            yoyPeriod = yoyPeriod,
            activeComparisonPeriod = activeComparisonPeriod,
            currentTotalExpense = currentTotal,
            previousTotalExpense = prevTotal,
            yoyTotalExpense = yoyTotal,
            activeComparisonTotalExpense = activeCompTotal,
            currentTotalIncome = currentIncome,
            activeComparisonTotalIncome = activeCompIncome,
            seqDeltaAmount = seqDeltaAmt,
            seqDeltaPercent = seqDeltaPct,
            yoyDeltaAmount = yoyDeltaAmt,
            yoyDeltaPercent = yoyDeltaPct,
            activeDeltaAmount = activeDeltaAmt,
            activeDeltaPercent = activeDeltaPct,
            currentTxCount = currentExpList.size,
            activeComparisonTxCount = activeCompExpList.size,
            currentDailyAverage = currentDailyAvg,
            activeComparisonDailyAverage = activeCompDailyAvg,
            largestChangeItem = largestChangeItem,
            topIncreaseItem = topIncreaseItem,
            topDecreaseItem = topDecreaseItem,
            headlineInsight = headlineInsight,
            causeAnalysisInsight = causeAnalysisInsight,
            yoyComparisonInsight = yoyComparisonInsight,
            trendBars = trendBars,
            categoryDeltas = enrichedDeltas
        )
    }

    fun sortCategoryDeltas(
        items: List<CategoryDeltaItem>,
        sortMode: ExpenseDeltaSortMode
    ): List<CategoryDeltaItem> = when (sortMode) {
        ExpenseDeltaSortMode.BY_CHANGE_MAGNITUDE ->
            items.sortedWith(compareByDescending<CategoryDeltaItem> { abs(it.deltaAmount) }.thenByDescending { it.currentAmount })
        ExpenseDeltaSortMode.BY_INCREASE_AMOUNT ->
            items.sortedWith(compareByDescending<CategoryDeltaItem> { it.deltaAmount }.thenByDescending { it.currentAmount })
        ExpenseDeltaSortMode.BY_DECREASE_AMOUNT ->
            items.sortedWith(compareBy<CategoryDeltaItem> { it.deltaAmount }.thenByDescending { it.comparisonAmount })
        ExpenseDeltaSortMode.BY_CURRENT_AMOUNT ->
            items.sortedWith(compareByDescending<CategoryDeltaItem> { it.currentAmount }.thenByDescending { abs(it.deltaAmount) })
    }

    private fun buildCategoryCauseSummary(
        curAmt: Long,
        compAmt: Long,
        curCnt: Int,
        compCnt: Int,
        curAvg: Long,
        compAvg: Long
    ): String {
        if (compAmt == 0L && curAmt > 0L) {
            return "이번 기간 신규 발생 (${curCnt}건, 건당 평균 %,d원)".format(curAvg)
        }
        if (curAmt == 0L && compAmt > 0L) {
            return "이번 기간 지출 없음 (이전 ${compCnt}건 전액 절감)"
        }
        val cntDiff = curCnt - compCnt
        val avgDiff = curAvg - compAvg
        val cntSign = if (cntDiff >= 0) "+${cntDiff}건" else "${cntDiff}건"
        val avgSign = if (avgDiff >= 0) "+%,d원".format(avgDiff) else "-%,d원".format(abs(avgDiff))

        val driverText = when {
            curAmt > compAmt && cntDiff > 0 && avgDiff <= 0 -> "결제 빈도 증가 주도"
            curAmt > compAmt && cntDiff <= 0 && avgDiff > 0 -> "건당 결제 단가 상승 주도"
            curAmt > compAmt && cntDiff > 0 && avgDiff > 0 -> "결제 빈도 및 건당 단가 동반 상승"
            curAmt < compAmt && cntDiff < 0 -> "결제 횟수 감소로 절감"
            curAmt < compAmt && avgDiff < 0 -> "건당 지출 규모 축소로 절감"
            else -> "전반적 지출 규모 유지"
        }
        return "$driverText · 건수 ${compCnt}건→${curCnt}건($cntSign) · 건당 평균 $avgSign"
    }

    private fun buildHeadlineInsight(
        targetTitle: String,
        basisName: String,
        currentTotal: Long,
        compTotal: Long,
        deltaAmount: Long,
        deltaPercent: Float?
    ): String {
        if (currentTotal == 0L && compTotal == 0L) {
            return "$targetTitle 및 비교 기간에 집계된 지출 내역이 없습니다."
        }
        if (compTotal == 0L && currentTotal > 0L) {
            return "$targetTitle 총 지출은 %,d원입니다. (비교 기간 지출 내역 없음)".format(currentTotal)
        }
        val pctText = formatSignedPercent(deltaPercent, currentTotal, compTotal)
        val curFormatted = "%,d원".format(currentTotal)
        val deltaFormatted = "%,d원".format(abs(deltaAmount))
        return when {
            deltaAmount > 0L ->
                "$targetTitle 총 지출은 ${curFormatted}으로, $basisName $deltaFormatted($pctText) 더 지출했습니다."
            deltaAmount < 0L ->
                "$targetTitle 총 지출은 ${curFormatted}으로, $basisName $deltaFormatted($pctText) 덜 지출하여 절감했습니다."
            else ->
                "$targetTitle 총 지출은 ${curFormatted}으로 $basisName 지출 규모가 동일합니다."
        }
    }

    private fun buildCauseAnalysisInsight(
        basisName: String,
        totalDelta: Long,
        largestChangeItem: CategoryDeltaItem?,
        topIncreaseItem: CategoryDeltaItem?,
        topDecreaseItem: CategoryDeltaItem?
    ): String {
        if (largestChangeItem == null) {
            return "비교 가능한 카테고리별 지출 변동 내역이 없습니다."
        }
        val parts = mutableListOf<String>()
        val largestDir = if (largestChangeItem.deltaAmount >= 0L) "증가" else "감소"
        val largestPct = formatSignedPercent(
            largestChangeItem.deltaPercent,
            largestChangeItem.currentAmount,
            largestChangeItem.comparisonAmount
        )
        val merchantNote = if (largestChangeItem.topMerchantsSummary.isNotBlank()) {
            " [주요 지출처: ${largestChangeItem.topMerchantsSummary}]"
        } else ""
        parts.add(
            "가장 변경폭이 큰 항목은 '${largestChangeItem.categoryName}'(${formatSignedAmount(largestChangeItem.deltaAmount)}, $largestPct $largestDir)입니다.$merchantNote"
        )

        if (totalDelta > 0L && topIncreaseItem != null) {
            val contribution = if (totalDelta > 0L) {
                (topIncreaseItem.deltaAmount.toDouble() / totalDelta.toDouble() * 100.0).coerceAtMost(100.0)
            } else 0.0
            val contribStr = "%.0f%%".format(contribution)
            parts.add(
                "'${topIncreaseItem.categoryName}' 지출이 ${topIncreaseItem.comparisonTxCount}건에서 ${topIncreaseItem.currentTxCount}건으로 변하며 전체 지출 증가분의 ${contribStr}를 차지했습니다."
            )
            if (topDecreaseItem != null && topDecreaseItem.deltaAmount < 0L) {
                val decAmtStr = "%,d원".format(abs(topDecreaseItem.deltaAmount))
                parts.add(
                    "반면 '${topDecreaseItem.categoryName}' 항목에서는 ${decAmtStr}을 절감해 일부 상승 폭을 상쇄했습니다."
                )
            }
        } else if (totalDelta < 0L && topDecreaseItem != null) {
            val decAmtStr = "%,d원".format(abs(topDecreaseItem.deltaAmount))
            parts.add(
                "'${topDecreaseItem.categoryName}' 항목이 $basisName $decAmtStr 줄어들며 전체 소비 절감을 주도했습니다."
            )
            if (topIncreaseItem != null && topIncreaseItem.deltaAmount > 0L) {
                val incAmtStr = "+%,d원".format(topIncreaseItem.deltaAmount)
                parts.add(
                    "다만 '${topIncreaseItem.categoryName}' 항목은 $incAmtStr 늘어났으므로 예산 관리에 참고하세요."
                )
            }
        }
        return parts.joinToString(" ")
    }

    private fun buildYoyInsight(
        unit: AnalyticsPeriodUnit,
        yoyPeriod: PeriodSpec,
        currentTotal: Long,
        yoyTotal: Long,
        yoyDeltaAmt: Long,
        yoyDeltaPct: Float?,
        enrichedDeltas: List<CategoryDeltaItem>
    ): String {
        val label = when (unit) {
            AnalyticsPeriodUnit.MONTHLY -> "전년 동월(${yoyPeriod.compactTitle})"
            AnalyticsPeriodUnit.QUARTERLY -> "전년 동분기(${yoyPeriod.compactTitle})"
            AnalyticsPeriodUnit.HALF_YEARLY -> "전년 동반기(${yoyPeriod.compactTitle})"
            AnalyticsPeriodUnit.YEARLY -> "전년도(${yoyPeriod.compactTitle})"
        }
        if (yoyTotal <= 0L) {
            return "$label 지출 기록이 없어 전년 대비 증감률은 산출되지 않았습니다."
        }
        val pctStr = formatSignedPercent(yoyDeltaPct, currentTotal, yoyTotal)
        val topYoyDiffItem = enrichedDeltas
            .filter { it.yoyAmount > 0L || it.currentAmount > 0L }
            .maxByOrNull { abs(it.currentAmount - it.yoyAmount) }
        val topYoySuffix = topYoyDiffItem?.let {
            val diff = it.currentAmount - it.yoyAmount
            " (전년 대비 최대 변동: '${it.categoryName}' ${formatSignedAmount(diff)})"
        }.orEmpty()
        val yoyDeltaStr = "%,d원".format(abs(yoyDeltaAmt))

        return when {
            yoyDeltaAmt > 0L -> "$label 대비 총 지출이 $yoyDeltaStr($pctStr) 증가했습니다.$topYoySuffix"
            yoyDeltaAmt < 0L -> "$label 대비 총 지출이 $yoyDeltaStr($pctStr) 감소했습니다.$topYoySuffix"
            else -> "$label 대비 총 지출 규모가 동일합니다."
        }
    }
}
