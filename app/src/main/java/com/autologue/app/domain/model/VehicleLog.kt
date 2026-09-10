package com.autologue.app.domain.model

import java.time.LocalDateTime

enum class VehicleLogType {
    REFUELING, TRIP_DRIVING, MAINTENANCE, PARKING
}

data class VehicleLog(
    val id: Long = 0,
    val timestamp: LocalDateTime,
    val logType: VehicleLogType,
    val fuelCost: Long = 0L,
    val fuelAmountLiters: Double = 0.0,
    val daysSinceLastFuel: Int? = null,
    val tripDistanceKm: Double = 0.0,
    val currentOdometerKm: Double? = null,
    val estimatedEfficiencyKmPerL: Double? = null,
    val gasStationName: String? = null,
    val note: String? = null
)

fun VehicleLog.getAssignedVehicleId(defaultVehicleId: String = "car_1"): String {
    val n = note ?: return defaultVehicleId
    return when {
        n.contains("[car_2]") || n.contains("car_2") || n.contains("차량 2") -> "car_2"
        n.contains("[car_1]") || n.contains("car_1") || n.contains("차량 1") -> "car_1"
        else -> defaultVehicleId
    }
}

/**
 * 주유 기록에 명시적으로 입력된 L당 주유 단가(원/L) 추출
 */
fun VehicleLog.getExplicitUnitPrice(): Double? {
    val n = note ?: return null
    val match = Regex("\\[unit_price:\\s*([0-9.]+)\\]").find(n)
    return match?.groupValues?.get(1)?.toDoubleOrNull()
}

/**
 * 사용자가 직접 L당 단가나 실주유량을 입력/수정한 정밀 데이터인지 여부
 */
fun VehicleLog.isCustomFuel(): Boolean {
    val n = note ?: ""
    return n.contains("[custom_fuel:true]") || getExplicitUnitPrice() != null || (fuelAmountLiters > 0.0 && !n.contains("가계부 결제 내역 자동 분석"))
}

/**
 * 차량 배정 태그, L당 단가 태그, 정밀 실측 태그를 반영한 note 문자열 생성
 */
fun VehicleLog.buildUpdatedNote(
    targetVehicleId: String,
    vehicleShortName: String,
    unitPrice: Double?,
    isCustom: Boolean = true
): String {
    val current = note ?: ""
    // 기존 시스템 태그 제거
    val clean = current
        .replace(Regex("\\[(car_1|car_2|차량 1|차량 2)[^\\]]*\\]\\s*"), "")
        .replace(Regex("\\[unit_price:[^\\]]*\\]\\s*"), "")
        .replace(Regex("\\[custom_fuel:[^\\]]*\\]\\s*"), "")
        .trim()

    val tags = mutableListOf<String>()
    tags.add("[$targetVehicleId: $vehicleShortName]")
    if (unitPrice != null && unitPrice > 0.0) {
        tags.add("[unit_price: %.1f]".format(java.util.Locale.US, unitPrice))
    }
    if (isCustom) {
        tags.add("[custom_fuel:true]")
    }

    return (tags.joinToString(" ") + if (clean.isNotBlank()) " $clean" else "").trim()
}


