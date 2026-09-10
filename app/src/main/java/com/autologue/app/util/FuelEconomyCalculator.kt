package com.autologue.app.util

import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.getAssignedVehicleId
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

import com.autologue.app.domain.model.getExplicitUnitPrice
import com.autologue.app.domain.model.isCustomFuel

/**
 * 주유량 및 단가 하이브리드 계산 상세 결과
 */
data class FuelCalculationDetail(
    val liters: Double,
    val unitPrice: Double,
    val isEstimated: Boolean
)

/**
 * 차량별 Full-to-Full 주유 주기 기반 구간 연비 및 주행거리 계산 결과
 */
data class VehicleIntervalCalculationResult(
    val enrichedFuelLogs: List<VehicleLog>,
    val totalIntervalDistanceKm: Double,
    val totalIntervalLiters: Double,
    val weightedAverageEfficiencyKmPerL: Double?,
    val latestIntervalDays: Int?
)

/**
 * 전국 평균 휘발유 가격(1,650원/L) 기준 주유량 환산 및 차량별 주유 간격 실연비 계산기
 */
object FuelEconomyCalculator {
    const val DEFAULT_GAS_PRICE = 1650.0 // 전국 평균 휘발유 가격 (원/L)

    /**
     * 하이브리드 주유량 및 단가 산출:
     * 1순위: 사용자 직접 입력 실주유량 (explicitLiters > 0.0)
     * 2순위: 사용자 직접 입력 L당 단가 (explicitUnitPrice != null && explicitUnitPrice > 0.0) -> fuelCost / explicitUnitPrice
     * 3순위 (폴백): 전국 평균 유가 (기본 1,650원/L) -> fuelCost / defaultGasPrice (추정치)
     */
    fun calculateFuelDetailHybrid(
        fuelCost: Long,
        explicitLiters: Double = 0.0,
        explicitUnitPrice: Double? = null,
        isCustom: Boolean = false,
        defaultGasPrice: Double = DEFAULT_GAS_PRICE
    ): FuelCalculationDetail {
        // 1순위: 실주유량이 명시된 경우
        if (explicitLiters > 0.0) {
            val roundedLiters = Math.round(explicitLiters * 10.0) / 10.0
            val price = explicitUnitPrice ?: if (roundedLiters > 0.0 && fuelCost > 0L) {
                Math.round((fuelCost.toDouble() / roundedLiters) * 10.0) / 10.0
            } else defaultGasPrice
            return FuelCalculationDetail(
                liters = roundedLiters,
                unitPrice = price,
                isEstimated = !isCustom && explicitUnitPrice == null
            )
        }

        // 2순위: 실주유량은 없으나 L당 단가가 명시된 경우
        if (explicitUnitPrice != null && explicitUnitPrice > 0.0) {
            val calculatedLiters = if (fuelCost > 0L) {
                Math.round((fuelCost.toDouble() / explicitUnitPrice) * 10.0) / 10.0
            } else 0.0
            return FuelCalculationDetail(
                liters = calculatedLiters,
                unitPrice = explicitUnitPrice,
                isEstimated = false
            )
        }

        // 3순위: 둘 다 미입력 시 전국 평균 유가 기준 폴백
        val fallbackLiters = if (fuelCost > 0L && defaultGasPrice > 0.0) {
            Math.round((fuelCost.toDouble() / defaultGasPrice) * 10.0) / 10.0
        } else 0.0
        return FuelCalculationDetail(
            liters = fallbackLiters,
            unitPrice = defaultGasPrice,
            isEstimated = true
        )
    }

    /**
     * 주유 금액 또는 실주유량으로부터 리터(L) 계산 (소수점 1자리 반올림)
     */
    fun calculateFuelLiters(
        fuelCost: Long,
        explicitLiters: Double = 0.0,
        gasPrice: Double = DEFAULT_GAS_PRICE
    ): Double {
        return calculateFuelDetailHybrid(
            fuelCost = fuelCost,
            explicitLiters = explicitLiters,
            explicitUnitPrice = null,
            defaultGasPrice = gasPrice
        ).liters
    }

    /**
     * 주행거리와 주유량으로 구간 연비(km/L) 계산 (소수점 1자리 반올림)
     */
    fun calculateIntervalEfficiency(
        distanceKm: Double,
        fuelLiters: Double
    ): Double? {
        if (distanceKm <= 0.0 || fuelLiters <= 0.0) return null
        val eff = Math.round((distanceKm / fuelLiters) * 10.0) / 10.0
        return if (eff in 3.0..35.0) eff else null
    }

    /**
     * 특정 차량 ID의 주유 로그들과 주행 로그들을 기반으로 Full-to-Full 구간 연비 및 통계 산출
     */
    fun calculateForVehicle(
        targetVehicleId: String,
        allLogs: List<VehicleLog>,
        additionalDrivingDistanceLookup: ((LocalDateTime?, LocalDateTime) -> Double)? = null,
        gasPrice: Double = DEFAULT_GAS_PRICE
    ): VehicleIntervalCalculationResult {
        // 1. 해당 차량 주행 로그 필터링
        val vehicleDrivingLogs = allLogs.filter {
            it.logType == VehicleLogType.TRIP_DRIVING && it.getAssignedVehicleId() == targetVehicleId
        }

        fun getDrivingDistance(start: LocalDateTime?, end: LocalDateTime): Double {
            val fromLogs = vehicleDrivingLogs.filter { log ->
                val afterStart = if (start != null) !log.timestamp.isBefore(start) else true
                val beforeEnd = !log.timestamp.isAfter(end)
                afterStart && beforeEnd
            }.sumOf { it.tripDistanceKm }

            val additional = additionalDrivingDistanceLookup?.invoke(start, end) ?: 0.0
            return if (fromLogs > 0.0) {
                if (additional > 0.0) maxOf(fromLogs, additional) else fromLogs
            } else additional
        }

        // 2. 해당 차량 주유 로그 필터링 및 시간 오름차순(과거->최신) 정렬
        val vehicleFuelLogs = allLogs.filter {
            it.logType == VehicleLogType.REFUELING && it.getAssignedVehicleId() == targetVehicleId
        }.sortedBy { it.timestamp }

        var prevFuelLog: VehicleLog? = null
        var totalIntervalDist = 0.0
        var totalIntervalLiters = 0.0
        val enrichedList = mutableListOf<VehicleLog>()

        for (currentFuel in vehicleFuelLogs) {
            val detail = calculateFuelDetailHybrid(
                fuelCost = currentFuel.fuelCost,
                explicitLiters = currentFuel.fuelAmountLiters,
                explicitUnitPrice = currentFuel.getExplicitUnitPrice(),
                isCustom = currentFuel.isCustomFuel(),
                defaultGasPrice = gasPrice
            )
            val liters = detail.liters

            val (intervalDist, daysSince) = if (prevFuelLog != null) {
                val dist = getDrivingDistance(prevFuelLog.timestamp, currentFuel.timestamp)
                val days = ChronoUnit.DAYS.between(
                    prevFuelLog.timestamp.toLocalDate(),
                    currentFuel.timestamp.toLocalDate()
                ).toInt().coerceAtLeast(1)
                Pair(dist, days)
            } else {
                val dist = getDrivingDistance(null, currentFuel.timestamp)
                Pair(dist, null)
            }

            val efficiency = calculateIntervalEfficiency(intervalDist, liters)

            if (prevFuelLog != null && intervalDist > 0.0 && liters > 0.0) {
                totalIntervalDist += intervalDist
                totalIntervalLiters += liters
            }

            val enriched = currentFuel.copy(
                fuelAmountLiters = liters,
                tripDistanceKm = Math.round(intervalDist * 10.0) / 10.0,
                daysSinceLastFuel = daysSince ?: currentFuel.daysSinceLastFuel,
                estimatedEfficiencyKmPerL = efficiency
            )
            enrichedList.add(enriched)
            prevFuelLog = currentFuel
        }

        val weightedAvg = if (totalIntervalDist > 0.0 && totalIntervalLiters > 0.0) {
            Math.round((totalIntervalDist / totalIntervalLiters) * 10.0) / 10.0
        } else null

        val latestDays = enrichedList.lastOrNull()?.daysSinceLastFuel

        return VehicleIntervalCalculationResult(
            enrichedFuelLogs = enrichedList,
            totalIntervalDistanceKm = Math.round(totalIntervalDist * 10.0) / 10.0,
            totalIntervalLiters = Math.round(totalIntervalLiters * 10.0) / 10.0,
            weightedAverageEfficiencyKmPerL = weightedAvg,
            latestIntervalDays = latestDays
        )
    }
}
