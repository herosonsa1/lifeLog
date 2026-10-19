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

const val MIN_REFUEL_AMOUNT_WON = 30_000L

/**
 * 3만원 미만의 주유/주차 카테고리 내역이거나 명시적 주차 기록인지 판별
 * (주유는 통상 3만원 이상이므로 3만원 미만은 주차로 분류·표시)
 */
fun VehicleLog.isParkingLog(): Boolean {
    if (logType == VehicleLogType.PARKING) return true
    if (logType == VehicleLogType.REFUELING && fuelCost in 1L until MIN_REFUEL_AMOUNT_WON) return true
    return false
}

/**
 * 출근(집 -> 회사) 주행 기록 여부 판별
 */
fun VehicleLog.isToWorkCommuteLog(): Boolean {
    if (logType != VehicleLogType.TRIP_DRIVING) return false
    val n = note ?: return false
    if (n.contains("출퇴근")) return false
    if (n.contains("출근")) return true
    if (n.contains("집->회사") || n.contains("집 -> 회사")) return true
    return Regex("(학암동|위례동|우리집|집)[^➔\\->]*(?:➔|->)[^\\]\\)]*(영등포동|회사)").containsMatchIn(n)
}

/**
 * 퇴근(회사 -> 집) 주행 기록 여부 판별
 */
fun VehicleLog.isToHomeCommuteLog(): Boolean {
    if (logType != VehicleLogType.TRIP_DRIVING) return false
    val n = note ?: return false
    if (n.contains("출퇴근")) return false
    if (n.contains("퇴근")) return true
    if (n.contains("회사->집") || n.contains("회사 -> 집")) return true
    return Regex("(영등포동|회사)[^➔\\->]*(?:➔|->)[^\\]\\)]*(학암동|위례동|우리집|집)").containsMatchIn(n)
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
    if (isParkingLog()) return false
    val n = note ?: ""
    val isAutoSyncedNote = n.contains("가계부 결제 내역 자동 분석") || n.contains("가계부 카테고리 변경 연동")
    return n.contains("[custom_fuel:true]") || getExplicitUnitPrice() != null || (fuelAmountLiters > 0.0 && !isAutoSyncedNote)
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
 * GPS-기지국 교차 수신(Ping-Pong) 노이즈로 인해 비정상적으로 부풀려진 주행 기록(예: 출퇴근 편도가 117.4 km로 기록된 건)을
 * 정상 편도/주행 거리(예: 27.1 km)로 자가 치유(Self-Healing)합니다.
 */
fun VehicleLog.normalizeInflatedDrivingLog(defaultCommuteOneWayKm: Double = 27.1): VehicleLog {
    if (logType != VehicleLogType.TRIP_DRIVING) return this
    val n = note ?: return this

    val isGolf = n.contains("골프") || n.contains("라운딩") || n.contains("CC") || n.contains("GC") || n.contains("C.C") || n.contains("G.C")
    if (isGolf) return this

    val isCommuteRoute = (n.contains("출근") && !n.contains("출퇴근")) ||
        (n.contains("퇴근") && !n.contains("출퇴근")) ||
        (n.contains("영등포동") && (n.contains("학암동") || n.contains("위례동")))

    val refOneWayKm = if (defaultCommuteOneWayKm > 0.0) {
        Math.round(defaultCommuteOneWayKm * 10.0) / 10.0
    } else 27.1

    val durationMin = Regex("(\\d+)\\s*분\\s*운행").find(n)?.groupValues?.get(1)?.toIntOrNull()
    val gpsPointCount = Regex("GPS\\s*(\\d+)\\s*개\\s*지점").find(n)?.groupValues?.get(1)?.toIntOrNull()

    val isInflatedCommute = isCommuteRoute && tripDistanceKm > refOneWayKm * 1.35
    val isInflatedByDuration = durationMin != null && durationMin > 0 && tripDistanceKm > durationMin * 1.15
    val isInflatedByPingPongPoints = gpsPointCount != null && durationMin != null && gpsPointCount > durationMin * 4 && tripDistanceKm > 40.0

    if (!isInflatedCommute && !isInflatedByDuration && !isInflatedByPingPongPoints) {
        return this
    }

    val healedKm = when {
        isCommuteRoute -> refOneWayKm
        durationMin != null && durationMin > 0 -> Math.round((durationMin * 0.35).coerceIn(1.5, 45.0) * 10.0) / 10.0
        else -> refOneWayKm
    }

    var healedNote = n.replace(Regex("((?:출근\\s*|퇴근\\s*)?)\\d+(?:\\.\\d+)?\\s*km", RegexOption.IGNORE_CASE)) { m ->
        val prefix = m.groupValues[1]
        String.format(java.util.Locale.US, "%s%.1f km", prefix, healedKm)
    }

    if (gpsPointCount != null && durationMin != null && gpsPointCount > durationMin * 4) {
        val normalPoints = (durationMin / 2).coerceAtLeast(2)
        healedNote = healedNote.replace(Regex("GPS\\s*\\d+\\s*개\\s*지점"), "GPS ${normalPoints}개 지점")
    }

    return copy(
        tripDistanceKm = healedKm,
        note = healedNote
    )
}

private fun syncRouteTitleDistance(rawTitle: String, tripDistanceKm: Double): String {
    if (tripDistanceKm <= 0.0) return rawTitle
    return rawTitle.replace(Regex("((?:출근\\s*|퇴근\\s*)?)\\d+(?:\\.\\d+)?\\s*km", RegexOption.IGNORE_CASE)) { m ->
        val prefix = m.groupValues[1]
        String.format(java.util.Locale.US, "%s%.1f km", prefix, tripDistanceKm)
    }
}

/**
 * 주행 기록에서 메인 타이틀로 표시할 시작지점 ➔ 종료지점 경로 문자열 추출
 */
fun VehicleLog.getDrivingRouteTitle(): String? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    val normalized = normalizeInflatedDrivingLog()
    val n = normalized.note ?: return null
    val distKm = normalized.tripDistanceKm

    // 0. 출근 / 퇴근 / 출퇴근 왕복 주행은 동네명(~시/구 ~동) 대신 집->회사(~~km) / 회사->집(~~km)으로 간결하게 표기
    if (normalized.isToWorkCommuteLog()) {
        return if (distKm > 0.0) String.format(java.util.Locale.US, "집->회사(%.1fkm)", distKm) else "집->회사"
    }
    if (normalized.isToHomeCommuteLog()) {
        return if (distKm > 0.0) String.format(java.util.Locale.US, "회사->집(%.1fkm)", distKm) else "회사->집"
    }
    if (n.contains("출퇴근")) {
        return if (distKm > 0.0) String.format(java.util.Locale.US, "집<->회사(%.1fkm)", distKm) else "집<->회사"
    }

    // 1. 신규 포맷: "[출발지 ➔ 도착지 (거리 km)]" 대괄호 패턴 검색
    val routeBracketMatch = Regex("\\[([^\\]]+➔[^\\]]+)\\]").find(n)
    if (routeBracketMatch != null) {
        return syncRouteTitleDistance(routeBracketMatch.groupValues[1].trim(), distKm)
    }

    // 1-1. 차량/단가 시스템 태그를 제외한 경로/주행 대괄호 타이틀 (예: "[서울 송파구 잠실동 주변 주행 (5.2 km)]")
    val allBrackets = Regex("\\[([^\\]]+)\\]").findAll(n)
    for (match in allBrackets) {
        val inside = match.groupValues[1].trim()
        val isSystemTag = inside.startsWith("car_") ||
            inside.startsWith("차량 ") ||
            inside.startsWith("unit_price") ||
            inside.startsWith("custom_fuel")
        val hasRouteKeyword = inside.contains("➔") ||
            inside.contains("->") ||
            inside.contains("주행") ||
            inside.contains("출발") ||
            inside.contains("도착") ||
            inside.contains("km", ignoreCase = true)
        if (!isSystemTag && hasRouteKeyword && inside.isNotBlank()) {
            return syncRouteTitleDistance(inside, distKm)
        }
    }

    // 2. 괄호 없는 "출발지 ➔ 도착지" 패턴 검색
    val arrowMatch = Regex("([^(\\[]+➔[^)\\]]+)").find(n)
    if (arrowMatch != null) {
        val title = arrowMatch.groupValues[1].trim()
        return if (distKm > 0.0 && !title.contains("km")) {
            "$title (%.1f km)".format(distKm)
        } else syncRouteTitleDistance(title, distKm)
    }

    // 3-1. 골프장 라운딩 왕복 주행 패턴 (예: "월송리 CC 라운딩 왕복 주행", "오크밸리 CC 라운딩 왕복 주행")
    if (n.contains("골프") || n.contains("라운딩") || n.contains("CC") || n.contains("GC") || n.contains("C.C") || n.contains("G.C")) {
        val clubMatch = Regex("([가-힣A-Za-z0-9]+(?:\\s*(?:CC|GC|C\\.C|G\\.C|골프장|클럽)))").find(n)
        val clubName = clubMatch?.value?.trim() ?: run {
            n.substringBefore("라운딩").substringBefore("골프").trim().ifBlank { null }
        } ?: "골프장"
        return if (distKm > 0.0) "$clubName 왕복 주행 (%.1f km)".format(distKm) else "$clubName 왕복 주행"
    }

    // 4. 레거시 포맷 폴백
    if (distKm > 0.0) {
        return "자동 주행 (%.1f km)".format(distKm)
    }

    return "차량 주행 완료"
}

/**
 * 주행 기록의 상세 보조 정보 (운행 시간, GPS 지점 수 등) 추출
 */
fun VehicleLog.getDrivingDetailSubtitle(): String? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    val n = normalizeInflatedDrivingLog().note ?: return null

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
    if (isParkingLog()) {
        return gasStationName?.takeIf { it.isNotBlank() } ?: (note ?: "주차")
    }
    return when (logType) {
        VehicleLogType.REFUELING -> gasStationName?.takeIf { it.isNotBlank() } ?: "주유"
        VehicleLogType.TRIP_DRIVING -> getDrivingRouteTitle() ?: (note ?: "주행 완료")
        VehicleLogType.MAINTENANCE -> gasStationName?.takeIf { it.isNotBlank() } ?: (note ?: "차량정비/세차")
        VehicleLogType.PARKING -> gasStationName?.takeIf { it.isNotBlank() } ?: (note ?: "주차")
    }
}



