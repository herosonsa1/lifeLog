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

/**
 * 주행 기록에서 메인 타이틀로 표시할 시작지점 ➔ 종료지점 경로 문자열 추출
 */
fun VehicleLog.getDrivingRouteTitle(): String? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    val n = note ?: return null

    // 1. 신규 포맷: "[출발지 ➔ 도착지 (거리 km)]" 대괄호 패턴 검색
    val routeBracketMatch = Regex("\\[([^\\]]+➔[^\\]]+)\\]").find(n)
    if (routeBracketMatch != null) {
        return routeBracketMatch.groupValues[1].trim()
    }

    // 2. 괄호 없는 "출발지 ➔ 도착지" 패턴 검색
    val arrowMatch = Regex("([^(\\[]+➔[^)\\]]+)").find(n)
    if (arrowMatch != null) {
        val title = arrowMatch.groupValues[1].trim()
        return if (tripDistanceKm > 0.0 && !title.contains("km")) {
            "$title (%.1f km)".format(tripDistanceKm)
        } else title
    }

    // 3. 기존 출퇴근 패턴
    if (n.contains("출근")) {
        return if (tripDistanceKm > 0.0) "출근 주행 (%.1f km)".format(tripDistanceKm) else "출근 주행"
    }
    if (n.contains("퇴근")) {
        return if (tripDistanceKm > 0.0) "퇴근 주행 (%.1f km)".format(tripDistanceKm) else "퇴근 주행"
    }

    // 4. 레거시 포맷 폴백
    if (tripDistanceKm > 0.0) {
        return "자동 주행 (%.1f km)".format(tripDistanceKm)
    }

    return "차량 주행 완료"
}

/**
 * 주행 기록의 상세 보조 정보 (운행 시간, GPS 지점 수 등) 추출
 */
fun VehicleLog.getDrivingDetailSubtitle(): String? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    val n = note ?: return null

    // 소괄호 안의 운행시간/GPS 지점 정보 추출
    val parenMatch = Regex("\\(([0-9]+분 운행[^)]*)\\)").find(n)
    if (parenMatch != null) {
        return parenMatch.groupValues[1]
            .replace(", 10분 주기 GPS 추적,", " · GPS")
            .replace(", GPS 추적,", " · GPS")
            .replace("10분 주기 GPS 추적,", "GPS")
            .replace("GPS 추적,", "GPS")
            .replace("10분 주기 GPS", "GPS")
            .replace(",", " ·")
            .replace(Regex("\\s+"), " ")
            .trim()
    }

    return null
}

/**
 * UI 리스트에서 최상단 헤드라인으로 표출할 대표 타이틀
 */
fun VehicleLog.displayTitle(): String {
    return when (logType) {
        VehicleLogType.REFUELING -> gasStationName ?: "주유"
        VehicleLogType.TRIP_DRIVING -> getDrivingRouteTitle() ?: (note ?: "주행 완료")
        VehicleLogType.MAINTENANCE -> note ?: "차량 정비"
        VehicleLogType.PARKING -> note ?: "주차 기록"
    }
}


