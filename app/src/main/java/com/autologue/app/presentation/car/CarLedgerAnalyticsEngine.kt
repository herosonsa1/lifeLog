package com.autologue.app.presentation.car

import com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.isCommuteDrivingLog
import com.autologue.app.domain.model.isParkingLog
import com.autologue.app.domain.model.isToHomeCommuteLog
import com.autologue.app.domain.model.isToWorkCommuteLog
import com.autologue.app.presentation.expense.AnalyticsComparisonBasis
import com.autologue.app.presentation.expense.AnalyticsPeriodUnit
import com.autologue.app.presentation.expense.ExpenseAnalyticsEngine
import com.autologue.app.presentation.expense.PeriodSpec
import kotlin.math.abs
import kotlin.math.roundToLong

enum class CarLedgerPageTab(val label: String) {
    RECORDS("운행 · 주유 기록"),
    STATISTICS("주행 · 유지비 통계")
}

enum class CarLedgerAnalyticsSubTab(val label: String) {
    OVERVIEW_INSIGHT("주행 · 유지비 종합"),
    COST_BREAKDOWN_TABLE("유지비 항목별 도표"),
    DRIVING_BREAKDOWN_TABLE("주행거리 상세 도표")
}

enum class CarCostCategoryType(val displayName: String, val description: String) {
    FUEL("주유 · 충전비", "주유소 및 충전소 결제 지출"),
    PARKING("주차 · 통행료", "주차장 및 하이패스·통행 결제"),
    MAINTENANCE("정비 · 세차 · 소모품", "엔진오일, 정비소, 타이어, 세차 지출")
}

enum class CarDrivingPurposeType(val displayName: String, val description: String) {
    COMMUTE("출퇴근 주행", "집 ↔ 회사 정기 출퇴근 운행"),
    GENERAL("일반 · 일상 주행", "주말 나들이, 쇼핑, 일상 이동 운행")
}

data class CarCostDeltaItem(
    val type: CarCostCategoryType,
    val currentAmount: Long,
    val comparisonAmount: Long,
    val yoyAmount: Long,
    val deltaAmount: Long,
    val deltaPercent: Float?,
    val currentSharePercent: Float,
    val comparisonSharePercent: Float,
    val currentCount: Int,
    val comparisonCount: Int,
    val currentFuelLiters: Double = 0.0,
    val comparisonFuelLiters: Double = 0.0,
    val topPlacesSummary: String,
    val causeSummary: String,
    val isLargestMagnitudeChange: Boolean = false,
    val isLargestIncrease: Boolean = false,
    val isLargestDecrease: Boolean = false
)

data class CarDrivingDeltaItem(
    val purpose: CarDrivingPurposeType,
    val currentDistanceKm: Double,
    val comparisonDistanceKm: Double,
    val yoyDistanceKm: Double,
    val deltaDistanceKm: Double,
    val deltaPercent: Float?,
    val currentSharePercent: Float,
    val comparisonSharePercent: Float,
    val currentTripCount: Int,
    val comparisonTripCount: Int,
    val causeSummary: String,
    val isLargestMagnitudeChange: Boolean = false
)

data class CarPeriodTrendItem(
    val period: PeriodSpec,
    val totalDistanceKm: Double,
    val totalMaintenanceCost: Long,
    val fuelCost: Long,
    val parkingCost: Long,
    val repairCost: Long,
    val tripCount: Int,
    val isSelectedCurrent: Boolean,
    val isComparisonTarget: Boolean
)

data class CarLedgerComparisonReport(
    val unit: AnalyticsPeriodUnit,
    val effectiveBasis: AnalyticsComparisonBasis,
    val currentPeriod: PeriodSpec,
    val previousPeriod: PeriodSpec,
    val yoyPeriod: PeriodSpec,
    val activeComparisonPeriod: PeriodSpec,
    // 주행거리 지표
    val currentTotalDistanceKm: Double,
    val previousTotalDistanceKm: Double,
    val yoyTotalDistanceKm: Double,
    val activeComparisonDistanceKm: Double,
    val seqDistanceDeltaKm: Double,
    val seqDistanceDeltaPercent: Float?,
    val yoyDistanceDeltaKm: Double,
    val yoyDistanceDeltaPercent: Float?,
    val activeDistanceDeltaKm: Double,
    val activeDistanceDeltaPercent: Float?,
    val currentTripCount: Int,
    val activeComparisonTripCount: Int,
    // 차량유지비(주유+주차+정비) 총괄 지표
    val currentTotalCost: Long,
    val previousTotalCost: Long,
    val yoyTotalCost: Long,
    val activeComparisonTotalCost: Long,
    val seqCostDeltaAmount: Long,
    val seqCostDeltaPercent: Float?,
    val yoyCostDeltaAmount: Long,
    val yoyCostDeltaPercent: Float?,
    val activeCostDeltaAmount: Long,
    val activeCostDeltaPercent: Float?,
    val currentCostCount: Int,
    val activeComparisonCostCount: Int,
    // km당 유지비 및 주유량 지표
    val currentCostPerKm: Long,
    val activeComparisonCostPerKm: Long,
    val currentTotalFuelLiters: Double,
    val activeComparisonTotalFuelLiters: Double,
    // 최대 변동 항목 및 인사이트
    val largestCostChangeItem: CarCostDeltaItem?,
    val largestDrivingChangeItem: CarDrivingDeltaItem?,
    val correlationInsight: String,
    val costHighlightInsight: String,
    val drivingAndEfficiencyInsight: String,
    // 도표용 리스트
    val costDeltas: List<CarCostDeltaItem>,
    val drivingDeltas: List<CarDrivingDeltaItem>,
    val trendItems: List<CarPeriodTrendItem>
)

object CarLedgerAnalyticsEngine {

    fun classifyCostCategory(log: VehicleLog): CarCostCategoryType? {
        if (log.logType == VehicleLogType.TRIP_DRIVING) return null
        if (log.fuelCost <= 0L) return null
        return when {
            log.logType == VehicleLogType.MAINTENANCE -> CarCostCategoryType.MAINTENANCE
            log.isParkingLog() -> CarCostCategoryType.PARKING
            log.logType == VehicleLogType.REFUELING && log.fuelCost >= MIN_REFUEL_AMOUNT_WON -> CarCostCategoryType.FUEL
            else -> CarCostCategoryType.PARKING
        }
    }

    fun classifyDrivingPurpose(log: VehicleLog): CarDrivingPurposeType? {
        if (log.logType != VehicleLogType.TRIP_DRIVING) return null
        if (log.isCommuteDrivingLog()) return CarDrivingPurposeType.COMMUTE
        return CarDrivingPurposeType.GENERAL
    }

    fun calculateDoublePercentChange(current: Double, baseline: Double): Float? {
        if (baseline <= 0.05) return null
        return ((current - baseline) / baseline * 100.0).toFloat()
    }

    fun formatSignedKm(deltaKm: Double): String {
        val rounded = Math.round(deltaKm * 10.0) / 10.0
        return when {
            rounded > 0.0 -> "+%.1f km".format(rounded)
            rounded < 0.0 -> "%.1f km".format(rounded)
            else -> "0.0 km"
        }
    }

    fun formatSignedPercentForDouble(pct: Float?, current: Double, baseline: Double): String {
        if (pct == null) {
            return if (current > 0.05 && baseline <= 0.05) "신규 주행" else "0.0%"
        }
        return when {
            pct > 0.04f -> "+%.1f%%".format(pct)
            pct < -0.04f -> "%.1f%%".format(pct)
            else -> "0.0%"
        }
    }

    fun buildReport(
        logs: List<VehicleLog>,
        unit: AnalyticsPeriodUnit,
        targetPeriod: PeriodSpec,
        requestedBasis: AnalyticsComparisonBasis
    ): CarLedgerComparisonReport {
        val effectiveBasis = if (unit == AnalyticsPeriodUnit.YEARLY) {
            AnalyticsComparisonBasis.YEAR_OVER_YEAR
        } else {
            requestedBasis
        }

        val previousPeriod = targetPeriod.previousSequential()
        val yoyPeriod = targetPeriod.samePeriodLastYear()
        val activeComparisonPeriod = when (effectiveBasis) {
            AnalyticsComparisonBasis.PREVIOUS_PERIOD -> previousPeriod
            AnalyticsComparisonBasis.YEAR_OVER_YEAR -> yoyPeriod
        }

        val drivingLogs = logs.filter { it.logType == VehicleLogType.TRIP_DRIVING && it.tripDistanceKm > 0.0 }
        val costLogs = logs.filter { classifyCostCategory(it) != null }

        // 기간별 필터링
        val curDriving = drivingLogs.filter { targetPeriod.contains(it.timestamp.toLocalDate()) }
        val prevDriving = drivingLogs.filter { previousPeriod.contains(it.timestamp.toLocalDate()) }
        val yoyDriving = drivingLogs.filter { yoyPeriod.contains(it.timestamp.toLocalDate()) }
        val activeCompDriving = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) prevDriving else yoyDriving

        val curCostLogs = costLogs.filter { targetPeriod.contains(it.timestamp.toLocalDate()) }
        val prevCostLogs = costLogs.filter { previousPeriod.contains(it.timestamp.toLocalDate()) }
        val yoyCostLogs = costLogs.filter { yoyPeriod.contains(it.timestamp.toLocalDate()) }
        val activeCompCostLogs = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) prevCostLogs else yoyCostLogs

        // 주행거리 합계
        val curDist = round1(curDriving.sumOf { it.tripDistanceKm })
        val prevDist = round1(prevDriving.sumOf { it.tripDistanceKm })
        val yoyDist = round1(yoyDriving.sumOf { it.tripDistanceKm })
        val activeCompDist = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) prevDist else yoyDist

        val seqDistDelta = round1(curDist - prevDist)
        val seqDistPct = calculateDoublePercentChange(curDist, prevDist)
        val yoyDistDelta = round1(curDist - yoyDist)
        val yoyDistPct = calculateDoublePercentChange(curDist, yoyDist)
        val activeDistDelta = round1(curDist - activeCompDist)
        val activeDistPct = calculateDoublePercentChange(curDist, activeCompDist)

        // 유지비 합계
        val curTotalCost = curCostLogs.sumOf { it.fuelCost }
        val prevTotalCost = prevCostLogs.sumOf { it.fuelCost }
        val yoyTotalCost = yoyCostLogs.sumOf { it.fuelCost }
        val activeCompTotalCost = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) prevTotalCost else yoyTotalCost

        val seqCostDelta = curTotalCost - prevTotalCost
        val seqCostPct = ExpenseAnalyticsEngine.calculatePercentChange(curTotalCost, prevTotalCost)
        val yoyCostDelta = curTotalCost - yoyTotalCost
        val yoyCostPct = ExpenseAnalyticsEngine.calculatePercentChange(curTotalCost, yoyTotalCost)
        val activeCostDelta = curTotalCost - activeCompTotalCost
        val activeCostPct = ExpenseAnalyticsEngine.calculatePercentChange(curTotalCost, activeCompTotalCost)

        val curCostPerKm = if (curDist >= 1.0) (curTotalCost / curDist).roundToLong() else 0L
        val activeCompCostPerKm = if (activeCompDist >= 1.0) (activeCompTotalCost / activeCompDist).roundToLong() else 0L

        val curFuelLiters = round1(
            curCostLogs.filter { classifyCostCategory(it) == CarCostCategoryType.FUEL }.sumOf { it.fuelAmountLiters }
        )
        val activeCompFuelLiters = round1(
            activeCompCostLogs.filter { classifyCostCategory(it) == CarCostCategoryType.FUEL }.sumOf { it.fuelAmountLiters }
        )

        // 1. 차량유지비 항목별(주유/충전, 주차/통행료, 정비/세차) 비교
        val curCostTotalSafe = curTotalCost.coerceAtLeast(1L)
        val compCostTotalSafe = activeCompTotalCost.coerceAtLeast(1L)

        val rawCostDeltas = CarCostCategoryType.entries.map { cType ->
            val curItems = curCostLogs.filter { classifyCostCategory(it) == cType }
            val compItems = activeCompCostLogs.filter { classifyCostCategory(it) == cType }
            val yoyItems = yoyCostLogs.filter { classifyCostCategory(it) == cType }

            val curAmt = curItems.sumOf { it.fuelCost }
            val compAmt = compItems.sumOf { it.fuelCost }
            val yoyAmt = yoyItems.sumOf { it.fuelCost }
            val deltaAmt = curAmt - compAmt
            val deltaPct = ExpenseAnalyticsEngine.calculatePercentChange(curAmt, compAmt)

            val curShare = if (curTotalCost > 0L) (curAmt.toFloat() / curCostTotalSafe * 100f) else 0f
            val compShare = if (activeCompTotalCost > 0L) (compAmt.toFloat() / compCostTotalSafe * 100f) else 0f

            val curLiters = if (cType == CarCostCategoryType.FUEL) round1(curItems.sumOf { it.fuelAmountLiters }) else 0.0
            val compLiters = if (cType == CarCostCategoryType.FUEL) round1(compItems.sumOf { it.fuelAmountLiters }) else 0.0

            val topPlaces = curItems
                .groupBy { it.gasStationName?.takeIf { s -> s.isNotBlank() } ?: cType.displayName }
                .mapValues { (_, list) -> list.sumOf { it.fuelCost } }
                .entries
                .sortedByDescending { it.value }
                .take(2)
                .joinToString(" · ") { (place, amt) -> "$place ${"%,d원".format(amt)}" }

            val causeSummary = buildCostCategoryCauseSummary(
                type = cType,
                curAmt = curAmt,
                compAmt = compAmt,
                curCnt = curItems.size,
                compCnt = compItems.size,
                curLiters = curLiters,
                compLiters = compLiters
            )

            CarCostDeltaItem(
                type = cType,
                currentAmount = curAmt,
                comparisonAmount = compAmt,
                yoyAmount = yoyAmt,
                deltaAmount = deltaAmt,
                deltaPercent = deltaPct,
                currentSharePercent = curShare,
                comparisonSharePercent = compShare,
                currentCount = curItems.size,
                comparisonCount = compItems.size,
                currentFuelLiters = curLiters,
                comparisonFuelLiters = compLiters,
                topPlacesSummary = topPlaces,
                causeSummary = causeSummary
            )
        }

        val maxCostMagType = rawCostDeltas
            .filter { it.deltaAmount != 0L }
            .maxByOrNull { abs(it.deltaAmount) }
            ?.type
        val maxCostIncType = rawCostDeltas
            .filter { it.deltaAmount > 0L }
            .maxByOrNull { it.deltaAmount }
            ?.type
        val maxCostDecType = rawCostDeltas
            .filter { it.deltaAmount < 0L }
            .minByOrNull { it.deltaAmount }
            ?.type

        val sortedCostDeltas = rawCostDeltas
            .map { item ->
                item.copy(
                    isLargestMagnitudeChange = item.type == maxCostMagType,
                    isLargestIncrease = item.type == maxCostIncType,
                    isLargestDecrease = item.type == maxCostDecType
                )
            }
            .sortedWith(
                compareByDescending<CarCostDeltaItem> { abs(it.deltaAmount) }
                    .thenByDescending { it.currentAmount }
            )

        // 2. 주행 목적별(출퇴근, 골프, 일반) 주행거리 비교
        val curDistSafe = curDist.coerceAtLeast(0.1)
        val compDistSafe = activeCompDist.coerceAtLeast(0.1)

        val rawDrivingDeltas = CarDrivingPurposeType.entries.map { pType ->
            val curTrips = curDriving.filter { classifyDrivingPurpose(it) == pType }
            val compTrips = activeCompDriving.filter { classifyDrivingPurpose(it) == pType }
            val yoyTrips = yoyDriving.filter { classifyDrivingPurpose(it) == pType }

            val curKm = round1(curTrips.sumOf { it.tripDistanceKm })
            val compKm = round1(compTrips.sumOf { it.tripDistanceKm })
            val yoyKm = round1(yoyTrips.sumOf { it.tripDistanceKm })
            val deltaKm = round1(curKm - compKm)
            val deltaPct = calculateDoublePercentChange(curKm, compKm)

            val curShare = if (curDist > 0.0) (curKm / curDistSafe * 100.0).toFloat() else 0f
            val compShare = if (activeCompDist > 0.0) (compKm / compDistSafe * 100.0).toFloat() else 0f

            val cause = when {
                curKm == 0.0 && compKm == 0.0 -> "두 기간 모두 해당 목적의 주행 기록 없음"
                compKm == 0.0 && curKm > 0.0 -> "이번 기간 신규 주행 (${curTrips.size}회, 총 %.1fkm)".format(curKm)
                curKm == 0.0 && compKm > 0.0 -> "이번 기간 주행 없음 (이전 ${compTrips.size}회 대비 전량 감소)"
                else -> {
                    val cntDiff = curTrips.size - compTrips.size
                    val cntSign = if (cntDiff >= 0) "+${cntDiff}회" else "${cntDiff}회"
                    "운행 횟수 ${compTrips.size}회 → ${curTrips.size}회($cntSign) · 1회 평균 %.1fkm".format(
                        if (curTrips.isNotEmpty()) curKm / curTrips.size else 0.0
                    )
                }
            }

            CarDrivingDeltaItem(
                purpose = pType,
                currentDistanceKm = curKm,
                comparisonDistanceKm = compKm,
                yoyDistanceKm = yoyKm,
                deltaDistanceKm = deltaKm,
                deltaPercent = deltaPct,
                currentSharePercent = curShare,
                comparisonSharePercent = compShare,
                currentTripCount = curTrips.size,
                comparisonTripCount = compTrips.size,
                causeSummary = cause
            )
        }

        val maxDrivingMagPurpose = rawDrivingDeltas
            .filter { abs(it.deltaDistanceKm) > 0.05 }
            .maxByOrNull { abs(it.deltaDistanceKm) }
            ?.purpose

        val sortedDrivingDeltas = rawDrivingDeltas
            .map { it.copy(isLargestMagnitudeChange = it.purpose == maxDrivingMagPurpose) }
            .sortedWith(
                compareByDescending<CarDrivingDeltaItem> { abs(it.deltaDistanceKm) }
                    .thenByDescending { it.currentDistanceKm }
            )

        val largestCostChange = sortedCostDeltas.firstOrNull { it.isLargestMagnitudeChange }
        val largestDrivingChange = sortedDrivingDeltas.firstOrNull { it.isLargestMagnitudeChange }

        // 3. 최근 주기별(6개월/6분기/4반기/4개년) 추이
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
            val pDrv = drivingLogs.filter { pSpec.contains(it.timestamp.toLocalDate()) }
            val pCost = costLogs.filter { pSpec.contains(it.timestamp.toLocalDate()) }
            val pFuel = pCost.filter { classifyCostCategory(it) == CarCostCategoryType.FUEL }.sumOf { it.fuelCost }
            val pPark = pCost.filter { classifyCostCategory(it) == CarCostCategoryType.PARKING }.sumOf { it.fuelCost }
            val pMaint = pCost.filter { classifyCostCategory(it) == CarCostCategoryType.MAINTENANCE }.sumOf { it.fuelCost }
            CarPeriodTrendItem(
                period = pSpec,
                totalDistanceKm = round1(pDrv.sumOf { it.tripDistanceKm }),
                totalMaintenanceCost = pCost.sumOf { it.fuelCost },
                fuelCost = pFuel,
                parkingCost = pPark,
                repairCost = pMaint,
                tripCount = pDrv.size,
                isSelectedCurrent = pSpec == targetPeriod,
                isComparisonTarget = pSpec == activeComparisonPeriod
            )
        }

        val basisName = if (effectiveBasis == AnalyticsComparisonBasis.PREVIOUS_PERIOD) {
            "${targetPeriod.sequentialBasisLabel()}(${previousPeriod.compactTitle})"
        } else {
            "${targetPeriod.yoyBasisLabel()}(${yoyPeriod.compactTitle})"
        }

        val correlationInsight = buildCorrelationInsight(
            basisName = basisName,
            curDist = curDist,
            compDist = activeCompDist,
            distDelta = activeDistDelta,
            distPct = activeDistPct,
            curCost = curTotalCost,
            compCost = activeCompTotalCost,
            costDelta = activeCostDelta,
            costPct = activeCostPct,
            largestCostChange = largestCostChange
        )

        val costHighlightInsight = buildCostHighlightInsight(
            basisName = basisName,
            totalCostDelta = activeCostDelta,
            largestCostChange = largestCostChange
        )

        val drivingAndEffInsight = buildDrivingAndEfficiencyInsight(
            curCostPerKm = curCostPerKm,
            compCostPerKm = activeCompCostPerKm,
            largestDrivingChange = largestDrivingChange,
            yoyPeriod = yoyPeriod,
            yoyDistDelta = yoyDistDelta,
            yoyCostDelta = yoyCostDelta
        )

        return CarLedgerComparisonReport(
            unit = unit,
            effectiveBasis = effectiveBasis,
            currentPeriod = targetPeriod,
            previousPeriod = previousPeriod,
            yoyPeriod = yoyPeriod,
            activeComparisonPeriod = activeComparisonPeriod,
            currentTotalDistanceKm = curDist,
            previousTotalDistanceKm = prevDist,
            yoyTotalDistanceKm = yoyDist,
            activeComparisonDistanceKm = activeCompDist,
            seqDistanceDeltaKm = seqDistDelta,
            seqDistanceDeltaPercent = seqDistPct,
            yoyDistanceDeltaKm = yoyDistDelta,
            yoyDistanceDeltaPercent = yoyDistPct,
            activeDistanceDeltaKm = activeDistDelta,
            activeDistanceDeltaPercent = activeDistPct,
            currentTripCount = curDriving.size,
            activeComparisonTripCount = activeCompDriving.size,
            currentTotalCost = curTotalCost,
            previousTotalCost = prevTotalCost,
            yoyTotalCost = yoyTotalCost,
            activeComparisonTotalCost = activeCompTotalCost,
            seqCostDeltaAmount = seqCostDelta,
            seqCostDeltaPercent = seqCostPct,
            yoyCostDeltaAmount = yoyCostDelta,
            yoyCostDeltaPercent = yoyCostPct,
            activeCostDeltaAmount = activeCostDelta,
            activeCostDeltaPercent = activeCostPct,
            currentCostCount = curCostLogs.size,
            activeComparisonCostCount = activeCompCostLogs.size,
            currentCostPerKm = curCostPerKm,
            activeComparisonCostPerKm = activeCompCostPerKm,
            currentTotalFuelLiters = curFuelLiters,
            activeComparisonTotalFuelLiters = activeCompFuelLiters,
            largestCostChangeItem = largestCostChange,
            largestDrivingChangeItem = largestDrivingChange,
            correlationInsight = correlationInsight,
            costHighlightInsight = costHighlightInsight,
            drivingAndEfficiencyInsight = drivingAndEffInsight,
            costDeltas = sortedCostDeltas,
            drivingDeltas = sortedDrivingDeltas,
            trendItems = trendItems
        )
    }

    private fun buildCostCategoryCauseSummary(
        type: CarCostCategoryType,
        curAmt: Long,
        compAmt: Long,
        curCnt: Int,
        compCnt: Int,
        curLiters: Double,
        compLiters: Double
    ): String {
        if (curAmt == 0L && compAmt == 0L) {
            return "두 기간 모두 지출 내역 없음"
        }
        if (compAmt == 0L && curAmt > 0L) {
            return if (type == CarCostCategoryType.FUEL && curLiters > 0.0) {
                "이번 기간 신규 발생 (${curCnt}회, 총 %.1fL 주유)".format(curLiters)
            } else {
                "이번 기간 신규 발생 (${curCnt}회, 회당 평균 %,d원)".format(curAmt / curCnt.coerceAtLeast(1))
            }
        }
        if (curAmt == 0L && compAmt > 0L) {
            return "이번 기간 지출 없음 (이전 ${compCnt}회 전액 절감)"
        }
        val cntDiff = curCnt - compCnt
        val cntSign = if (cntDiff >= 0) "+${cntDiff}회" else "${cntDiff}회"
        return if (type == CarCostCategoryType.FUEL && (curLiters > 0.0 || compLiters > 0.0)) {
            val literDiff = round1(curLiters - compLiters)
            val literSign = if (literDiff >= 0.0) "+%.1fL".format(literDiff) else "%.1fL".format(literDiff)
            "주유 횟수 ${compCnt}회→${curCnt}회($cntSign) · 주유량 ${compLiters}L→${curLiters}L($literSign)"
        } else {
            val curAvg = if (curCnt > 0) curAmt / curCnt else 0L
            "이용 횟수 ${compCnt}회→${curCnt}회($cntSign) · 회당 평균 %,d원".format(curAvg)
        }
    }

    private fun buildCorrelationInsight(
        basisName: String,
        curDist: Double,
        compDist: Double,
        distDelta: Double,
        distPct: Float?,
        curCost: Long,
        compCost: Long,
        costDelta: Long,
        costPct: Float?,
        largestCostChange: CarCostDeltaItem?
    ): String {
        if (curDist <= 0.0 && compDist <= 0.0 && curCost <= 0L && compCost <= 0L) {
            return "선택한 기간과 비교 기간에 기록된 주행 및 차량유지비 내역이 없습니다."
        }
        val distPctStr = formatSignedPercentForDouble(distPct, curDist, compDist)
        val costPctStr = ExpenseAnalyticsEngine.formatSignedPercent(costPct, curCost, compCost)

        return when {
            distDelta > 0.0 && costDelta > 0L ->
                "$basisName 총 주행거리가 ${formatSignedKm(distDelta)}($distPctStr) 늘어남에 따라 차량유지비도 ${ExpenseAnalyticsEngine.formatSignedAmount(costDelta)}($costPctStr) 동반 증가했습니다."
            distDelta < 0.0 && costDelta < 0L ->
                "$basisName 총 주행거리가 ${formatSignedKm(distDelta)}($distPctStr) 줄어들면서 차량유지비도 ${ExpenseAnalyticsEngine.formatSignedAmount(costDelta)}($costPctStr) 함께 절감되었습니다."
            distDelta <= 0.0 && costDelta > 0L -> {
                val reason = largestCostChange?.type?.displayName ?: "주유·정비"
                "$basisName 주행거리는 ${formatSignedKm(distDelta)}($distPctStr) 감소·유지되었으나, '$reason' 지출 증가로 인해 총 차량유지비는 ${ExpenseAnalyticsEngine.formatSignedAmount(costDelta)}($costPctStr) 늘어났습니다."
            }
            distDelta > 0.0 && costDelta <= 0L ->
                "$basisName 주행거리는 ${formatSignedKm(distDelta)}($distPctStr) 증가했지만, 주유·정비 결제 시점 차이 및 비용 절감으로 총 차량유지비는 ${ExpenseAnalyticsEngine.formatSignedAmount(costDelta)}($costPctStr) 낮아졌습니다."
            else ->
                "$basisName 주행거리(%.1fkm)와 차량유지비(%,d원)가 이전 수준을 유지하고 있습니다.".format(curDist, curCost)
        }
    }

    private fun buildCostHighlightInsight(
        basisName: String,
        totalCostDelta: Long,
        largestCostChange: CarCostDeltaItem?
    ): String {
        if (largestCostChange == null || largestCostChange.deltaAmount == 0L) {
            return "주유·주차·정비 항목 간 뚜렷한 비용 변동이 없습니다."
        }
        val dir = if (largestCostChange.deltaAmount > 0L) "증가" else "절감"
        val pctStr = ExpenseAnalyticsEngine.formatSignedPercent(
            largestCostChange.deltaPercent,
            largestCostChange.currentAmount,
            largestCostChange.comparisonAmount
        )
        val placeNote = if (largestCostChange.topPlacesSummary.isNotBlank()) {
            " · 주요 지출처: ${largestCostChange.topPlacesSummary}"
        } else ""
        return "가장 변경폭이 큰 차량유지비는 '${largestCostChange.type.displayName}' 항목으로, $basisName ${ExpenseAnalyticsEngine.formatSignedAmount(largestCostChange.deltaAmount)}($pctStr) ${dir}했습니다. (${largestCostChange.causeSummary}$placeNote)"
    }

    private fun buildDrivingAndEfficiencyInsight(
        curCostPerKm: Long,
        compCostPerKm: Long,
        largestDrivingChange: CarDrivingDeltaItem?,
        yoyPeriod: PeriodSpec,
        yoyDistDelta: Double,
        yoyCostDelta: Long
    ): String {
        val parts = mutableListOf<String>()
        if (largestDrivingChange != null && abs(largestDrivingChange.deltaDistanceKm) > 0.1) {
            val dir = if (largestDrivingChange.deltaDistanceKm > 0) "증가" else "감소"
            parts.add(
                "주행 목적 중에서는 '${largestDrivingChange.purpose.displayName}' 변동폭(${formatSignedKm(largestDrivingChange.deltaDistanceKm)} $dir)이 가장 컸습니다."
            )
        }
        if (curCostPerKm > 0L && compCostPerKm > 0L) {
            val diffPerKm = curCostPerKm - compCostPerKm
            val signStr = if (diffPerKm >= 0L) "+%,d원/km".format(diffPerKm) else "-%,d원/km".format(abs(diffPerKm))
            parts.add("1km당 차량유지비는 %,d원/km(비교 기간 대비 $signStr)입니다.".format(curCostPerKm))
        }
        if (abs(yoyDistDelta) > 0.1 || yoyCostDelta != 0L) {
            parts.add(
                "전년 동기(${yoyPeriod.compactTitle}) 대비로는 주행거리 ${formatSignedKm(yoyDistDelta)}, 유지비 ${ExpenseAnalyticsEngine.formatSignedAmount(yoyCostDelta)} 변화했습니다."
            )
        }
        return if (parts.isEmpty()) "주행 목적별 거리와 km당 유지비 효율이 안정적입니다." else parts.joinToString(" ")
    }

    private fun round1(value: Double): Double = Math.round(value * 10.0) / 10.0
}
