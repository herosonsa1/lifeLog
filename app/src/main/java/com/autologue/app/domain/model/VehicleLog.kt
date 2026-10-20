package com.autologue.app.domain.model

import com.autologue.app.util.KoreanHolidayUtils
import java.time.LocalDateTime
import java.util.Locale

enum class VehicleLogType {
    REFUELING, TRIP_DRIVING, MAINTENANCE, PARKING
}

/**
 * 차계부 기록 수정 다이얼로그에서 선택·변경 가능한 세부 항목 카테고리
 */
enum class CarLogItemCategory(
    val label: String,
    val badgeLabel: String,
    val isDrivingCategory: Boolean
) {
    COMMUTE_TO_WORK("출근 주행", "출근", true),
    COMMUTE_FROM_WORK("퇴근 주행", "퇴근", true),
    GENERAL_DRIVING("일반/외출 주행", "일반주행", true),
    GOLF_DRIVING("골프장 주행", "골프주행", true),
    REFUELING("주유/충전", "주유/충전", false),
    PARKING("주차", "주차", false),
    MAINTENANCE("정비/세차", "정비/세차", false)
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
 * 사용자가 수정 모달을 통해 명시적으로 지정한 항목(유형) 오버라이드 추출
 */
fun VehicleLog.getUserTypeOverride(): CarLogItemCategory? {
    val n = note ?: return null
    val match = Regex("\\[user_type_override:([A-Z_]+)\\]").find(n) ?: return null
    return runCatching { CarLogItemCategory.valueOf(match.groupValues[1]) }.getOrNull()
}

/**
 * 사용자가 수정 모달에서 직접 입력한 주행 경로/제목 추출
 */
fun VehicleLog.getUserCustomRouteTitle(): String? {
    val n = note ?: return null
    val match = Regex("\\[custom_route:([^\\]]+)\\]").find(n) ?: return null
    return match.groupValues[1].trim().takeIf { it.isNotBlank() }
}

/**
 * 3만원 미만의 주유/주차 카테고리 내역이거나 명시적 주차 기록인지 판별
 * (단, 사용자가 직접 항목을 수정하여 지정한 경우에는 사용자 지정 항목을 최우선 적용)
 */
fun VehicleLog.isParkingLog(): Boolean {
    val override = getUserTypeOverride()
    if (override != null) {
        return override == CarLogItemCategory.PARKING
    }
    if (logType == VehicleLogType.PARKING) return true
    if (logType == VehicleLogType.REFUELING && fuelCost in 1L until MIN_REFUEL_AMOUNT_WON) return true
    return false
}

/**
 * 출근(집 -> 회사) 주행 기록 여부 판별.
 * - 기본적으로 주말 및 법정공휴일을 제외한 평일에만 출근으로 적용됩니다.
 * - 단, 사용자가 수정 기능을 통해 출근으로 직접 지정한 경우([user_type_override:COMMUTE_TO_WORK])에는 요일/공휴일과 무관하게 출근으로 인정합니다.
 */
fun VehicleLog.isToWorkCommuteLog(): Boolean {
    if (logType != VehicleLogType.TRIP_DRIVING) return false
    val override = getUserTypeOverride()
    if (override != null) {
        return override == CarLogItemCategory.COMMUTE_TO_WORK
    }
    if (!KoreanHolidayUtils.isCommuteEligibleWorkday(timestamp.toLocalDate())) {
        return false
    }
    val n = note ?: return false
    if (n.contains("출퇴근")) return false
    if (n.contains("출근")) return true
    if (n.contains("집->회사") || n.contains("집 -> 회사")) return true
    return Regex("(학암동|위례동|우리집|집)[^➔\\->]*(?:➔|->)[^\\]\\)]*(영등포동|회사)").containsMatchIn(n)
}

/**
 * 퇴근(회사 -> 집) 주행 기록 여부 판별.
 * - 기본적으로 주말 및 법정공휴일을 제외한 평일에만 퇴근으로 적용됩니다.
 * - 단, 사용자가 수정 기능을 통해 퇴근으로 직접 지정한 경우([user_type_override:COMMUTE_FROM_WORK])에는 요일/공휴일과 무관하게 퇴근으로 인정합니다.
 */
fun VehicleLog.isToHomeCommuteLog(): Boolean {
    if (logType != VehicleLogType.TRIP_DRIVING) return false
    val override = getUserTypeOverride()
    if (override != null) {
        return override == CarLogItemCategory.COMMUTE_FROM_WORK
    }
    if (!KoreanHolidayUtils.isCommuteEligibleWorkday(timestamp.toLocalDate())) {
        return false
    }
    val n = note ?: return false
    if (n.contains("출퇴근")) return false
    if (n.contains("퇴근")) return true
    if (n.contains("회사->집") || n.contains("회사 -> 집")) return true
    return Regex("(영등포동|회사)[^➔\\->]*(?:➔|->)[^\\]\\)]*(학암동|위례동|우리집|집)").containsMatchIn(n)
}

/**
 * 출근·퇴근(또는 평일 출퇴근 왕복) 주행 기록인지 통합 판별
 */
fun VehicleLog.isCommuteDrivingLog(): Boolean {
    if (logType != VehicleLogType.TRIP_DRIVING) return false
    val override = getUserTypeOverride()
    if (override != null) {
        return override == CarLogItemCategory.COMMUTE_TO_WORK || override == CarLogItemCategory.COMMUTE_FROM_WORK
    }
    if (!KoreanHolidayUtils.isCommuteEligibleWorkday(timestamp.toLocalDate())) {
        return false
    }
    if (isToWorkCommuteLog() || isToHomeCommuteLog()) return true
    val n = note ?: return false
    return n.contains("출퇴근")
}

/**
 * 출퇴근 기록을 제외한 그 외의 운행기록(외출·일반·골프·주말/공휴일 주행 등)인지 판별
 */
fun VehicleLog.isNonCommuteDrivingLog(): Boolean {
    return logType == VehicleLogType.TRIP_DRIVING && !isCommuteDrivingLog()
}

/**
 * 골프장 이동 주행 기록 여부 판별
 */
fun VehicleLog.isGolfDrivingLog(): Boolean {
    if (logType != VehicleLogType.TRIP_DRIVING) return false
    val override = getUserTypeOverride()
    if (override != null) {
        return override == CarLogItemCategory.GOLF_DRIVING
    }
    val n = note ?: return false
    return n.contains("골프") || n.contains("라운딩") || n.contains("CC") || n.contains("GC") || n.contains("C.C") || n.contains("G.C")
}

/**
 * 현재 VehicleLog의 유효 세부 항목 카테고리 반환
 */
fun VehicleLog.resolveCarLogItemCategory(): CarLogItemCategory {
    getUserTypeOverride()?.let { return it }
    if (isParkingLog()) return CarLogItemCategory.PARKING
    return when (logType) {
        VehicleLogType.REFUELING -> CarLogItemCategory.REFUELING
        VehicleLogType.MAINTENANCE -> CarLogItemCategory.MAINTENANCE
        VehicleLogType.PARKING -> CarLogItemCategory.PARKING
        VehicleLogType.TRIP_DRIVING -> when {
            isToWorkCommuteLog() -> CarLogItemCategory.COMMUTE_TO_WORK
            isToHomeCommuteLog() -> CarLogItemCategory.COMMUTE_FROM_WORK
            isGolfDrivingLog() -> CarLogItemCategory.GOLF_DRIVING
            else -> CarLogItemCategory.GENERAL_DRIVING
        }
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
    isCustom: Boolean = true,
    userTypeOverride: CarLogItemCategory? = getUserTypeOverride(),
    customRouteTitle: String? = getUserCustomRouteTitle()
): String {
    val current = note ?: ""
    // 기존 시스템 태그 제거
    val clean = current
        .replace(Regex("\\[(car_1|car_2|차량 1|차량 2)[^\\]]*\\]\\s*"), "")
        .replace(Regex("\\[unit_price:[^\\]]*\\]\\s*"), "")
        .replace(Regex("\\[custom_fuel:[^\\]]*\\]\\s*"), "")
        .replace(Regex("\\[user_type_override:[^\\]]*\\]\\s*"), "")
        .replace(Regex("\\[custom_route:[^\\]]*\\]\\s*"), "")
        .trim()

    val tags = mutableListOf<String>()
    tags.add("[$targetVehicleId: $vehicleShortName]")
    if (userTypeOverride != null) {
        tags.add("[user_type_override:${userTypeOverride.name}]")
    }
    if (!customRouteTitle.isNullOrBlank()) {
        tags.add("[custom_route:${customRouteTitle.replace("]", "").trim()}]")
    }
    if (unitPrice != null && unitPrice > 0.0) {
        tags.add("[unit_price: %.1f]".format(Locale.US, unitPrice))
    }
    if (isCustom) {
        tags.add("[custom_fuel:true]")
    }

    return (tags.joinToString(" ") + if (clean.isNotBlank()) " $clean" else "").trim()
}

/**
 * GPS-기지국 교차 수신(Ping-Pong) 노이즈로 인해 비정상적으로 부풀려진 주행 기록(예: 출퇴근 편도가 117.4 km로 기록된 건)을
 * 정상 편도/주행 거리(예: 27.1 km)로 자가 치유(Self-Healing)합니다.
 * 단, 사용자가 직접 수정한 기록([one_time_edit:true] 또는 [user_type_override:...])은 사용자의 입력 거리를 그대로 보존합니다.
 */
fun VehicleLog.normalizeInflatedDrivingLog(defaultCommuteOneWayKm: Double = 27.1): VehicleLog {
    if (logType != VehicleLogType.TRIP_DRIVING) return this
    val n = note ?: return this
    if (n.contains("[one_time_edit:true]") || getUserTypeOverride() != null || getUserCustomRouteTitle() != null) {
        return this
    }

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
        String.format(Locale.US, "%s%.1f km", prefix, healedKm)
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

private fun syncRouteTitleDistance(rawTitle: String, tripDistanceKm: Double, stripCommuteKeywords: Boolean = false): String {
    var cleaned = if (stripCommuteKeywords) {
        rawTitle
            .replace(Regex("\\(\\s*(?:출근|퇴근)\\s+([0-9.]+\\s*km)\\s*\\)"), "($1)")
            .replace(Regex("^(?:출근|퇴근)\\s*주행\\s*"), "")
            .replace("집->회사", "우리집 ➔ 회사")
            .replace("회사->집", "회사 ➔ 우리집")
            .trim()
    } else {
        rawTitle
    }
    if (tripDistanceKm <= 0.0) return cleaned
    cleaned = cleaned.replace(Regex("((?:출근\\s*|퇴근\\s*)?)\\d+(?:\\.\\d+)?\\s*km", RegexOption.IGNORE_CASE)) { m ->
        val prefix = if (stripCommuteKeywords) "" else m.groupValues[1]
        String.format(Locale.US, "%s%.1f km", prefix, tripDistanceKm)
    }
    return cleaned
}

/**
 * 주행 기록에서 메인 타이틀로 표시할 시작지점 ➔ 종료지점 경로 문자열 추출
 */
fun VehicleLog.getDrivingRouteTitle(): String? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    val normalized = normalizeInflatedDrivingLog()
    val n = normalized.note ?: return null
    val distKm = normalized.tripDistanceKm

    // 사용자가 직접 수정한 커스텀 경로/제목이 있으면 최우선 반영
    val customRoute = normalized.getUserCustomRouteTitle()
    if (!customRoute.isNullOrBlank()) {
        return if (distKm > 0.0 && !customRoute.contains("km", ignoreCase = true)) {
            "$customRoute (%.1fkm)".format(Locale.US, distKm)
        } else {
            syncRouteTitleDistance(customRoute, distKm, stripCommuteKeywords = false)
        }
    }

    // 0. 출근 / 퇴근 / 출퇴근 왕복 주행은 집->회사(~~km) / 회사->집(~~km)으로 간결하게 표기
    if (normalized.isToWorkCommuteLog()) {
        return if (distKm > 0.0) String.format(Locale.US, "집->회사(%.1fkm)", distKm) else "집->회사"
    }
    if (normalized.isToHomeCommuteLog()) {
        return if (distKm > 0.0) String.format(Locale.US, "회사->집(%.1fkm)", distKm) else "회사->집"
    }
    if (normalized.isCommuteDrivingLog() && n.contains("출퇴근")) {
        return if (distKm > 0.0) String.format(Locale.US, "집<->회사(%.1fkm)", distKm) else "집<->회사"
    }

    val shouldStripCommute = !normalized.isCommuteDrivingLog()

    // 1. 신규 포맷: "[출발지 ➔ 도착지 (거리 km)]" 대괄호 패턴 검색
    val routeBracketMatch = Regex("\\[([^\\]]+➔[^\\]]+)\\]").find(n)
    if (routeBracketMatch != null) {
        return syncRouteTitleDistance(routeBracketMatch.groupValues[1].trim(), distKm, shouldStripCommute)
    }

    // 1-1. 차량/단가 시스템 태그를 제외한 경로/주행 대괄호 타이틀 (예: "[서울 송파구 잠실동 주변 주행 (5.2 km)]")
    val allBrackets = Regex("\\[([^\\]]+)\\]").findAll(n)
    for (match in allBrackets) {
        val inside = match.groupValues[1].trim()
        val isSystemTag = inside.startsWith("car_") ||
            inside.startsWith("차량 ") ||
            inside.startsWith("unit_price") ||
            inside.startsWith("custom_fuel") ||
            inside.startsWith("user_type_override") ||
            inside.startsWith("custom_route") ||
            inside.startsWith("one_time_edit") ||
            inside.startsWith("custom_name")
        val hasRouteKeyword = inside.contains("➔") ||
            inside.contains("->") ||
            inside.contains("주행") ||
            inside.contains("출발") ||
            inside.contains("도착") ||
            inside.contains("km", ignoreCase = true)
        if (!isSystemTag && hasRouteKeyword && inside.isNotBlank()) {
            return syncRouteTitleDistance(inside, distKm, shouldStripCommute)
        }
    }

    // 2. 괄호 없는 "출발지 ➔ 도착지" 패턴 검색
    val arrowMatch = Regex("([^(\\[]+➔[^)\\]]+)").find(n)
    if (arrowMatch != null) {
        val title = arrowMatch.groupValues[1].trim()
        val synced = syncRouteTitleDistance(title, distKm, shouldStripCommute)
        return if (distKm > 0.0 && !synced.contains("km")) {
            "$synced (%.1f km)".format(Locale.US, distKm)
        } else synced
    }

    // 3-1. 골프장 라운딩 왕복 주행 패턴 (예: "월송리 CC 라운딩 왕복 주행", "오크밸리 CC 라운딩 왕복 주행")
    if (normalized.isGolfDrivingLog()) {
        val clubMatch = Regex("([가-힣A-Za-z0-9]+(?:\\s*(?:CC|GC|C\\.C|G\\.C|골프장|클럽)))").find(n)
        val clubName = clubMatch?.value?.trim() ?: run {
            n.substringBefore("라운딩").substringBefore("골프").trim().ifBlank { null }
        } ?: "골프장"
        return if (distKm > 0.0) "$clubName 왕복 주행 (%.1f km)".format(Locale.US, distKm) else "$clubName 왕복 주행"
    }

    // 4. 레거시 포맷 폴백
    if (distKm > 0.0) {
        val label = if (n.contains("자동 주행")) "자동 주행" else "일반 주행"
        return "$label (%.1f km)".format(Locale.US, distKm)
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
