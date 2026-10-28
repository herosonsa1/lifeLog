package com.autologue.app.domain.model

import com.autologue.app.data.sync.PlaceResolver
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
        n.contains("[car_2]") || n.contains("car_2") || n.contains("차량 2") || n.contains("볼보") -> "car_2"
        n.contains("[car_1]") || n.contains("car_1") || n.contains("차량 1") || n.contains("벤츠") -> "car_1"
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
    val rawName = match.groupValues[1]
    if (rawName == "GOLF_DRIVING") return CarLogItemCategory.GENERAL_DRIVING
    return runCatching { CarLogItemCategory.valueOf(rawName) }.getOrNull()
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
    if (n.contains("집->회사") || n.contains("집 -> 회사") || n.contains("집 ➔ 회사") || n.contains("집➔회사")) return true
    return Regex("(7101동|학암동|위례동|하남시|우리집|집)[^➔\\->]*(?:➔|->)[^\\]\\)]*(영등포동|회사)").containsMatchIn(n)
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
    if (n.contains("회사->집") || n.contains("회사 -> 집") || n.contains("회사 ➔ 집") || n.contains("회사➔집")) return true
    return Regex("(영등포동|회사)[^➔\\->]*(?:➔|->)[^\\]\\)]*(7101동|학암동|위례동|하남시|우리집|집)").containsMatchIn(n)
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
 * 출퇴근 기록을 제외한 그 외의 운행기록(외출·일반·주말/공휴일 주행 등)인지 판별
 */
fun VehicleLog.isNonCommuteDrivingLog(): Boolean {
    return logType == VehicleLogType.TRIP_DRIVING && !isCommuteDrivingLog()
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

private val SEOUL_GU_SET = setOf(
    "강남구", "강동구", "강북구", "강서구", "관악구",
    "광진구", "구로구", "금천구", "노원구", "도봉구",
    "동대문구", "동작구", "마포구", "서대문구", "서초구",
    "성동구", "성북구", "송파구", "양천구", "영등포구",
    "용산구", "은평구", "종로구", "중구", "중랑구"
)

private val NON_SEOUL_GU_TO_SI_MAP = mapOf(
    "분당구" to "성남시", "수정구" to "성남시", "중원구" to "성남시",
    "수지구" to "용인시", "기흥구" to "용인시", "처인구" to "용인시",
    "영통구" to "수원시", "팔달구" to "수원시", "권선구" to "수원시", "장안구" to "수원시",
    "일산동구" to "고양시", "일산서구" to "고양시", "덕양구" to "고양시",
    "동안구" to "안양시", "만안구" to "안양시",
    "단원구" to "안산시", "상록구" to "안산시",
    "원미구" to "부천시", "소사구" to "부천시", "오정구" to "부천시",
    "상당구" to "청주시", "서원구" to "청주시", "흥덕구" to "청주시", "청원구" to "청주시",
    "동남구" to "천안시", "서북구" to "천안시",
    "완산구" to "전주시", "덕진구" to "전주시",
    "의창구" to "창원시", "성산구" to "창원시", "마산합포구" to "창원시", "마산회원구" to "창원시", "진해구" to "창원시"
)

private val CITY_STEM_TO_SI_GUN_MAP = mapOf(
    "수원" to "수원시", "성남" to "성남시", "고양" to "고양시", "용인" to "용인시",
    "부천" to "부천시", "안산" to "안산시", "안양" to "안양시", "남양주" to "남양주시",
    "화성" to "화성시", "평택" to "평택시", "의정부" to "의정부시", "시흥" to "시흥시",
    "파주" to "파주시", "광명" to "광명시", "김포" to "김포시", "군포" to "군포시",
    "광주" to "광주시", "이천" to "이천시", "양주" to "양주시", "오산" to "오산시",
    "구리" to "구리시", "안성" to "안성시", "포천" to "포천시", "의왕" to "의왕시",
    "하남" to "하남시", "여주" to "여주시", "동두천" to "동두천시", "과천" to "과천시",
    "양평" to "양평군", "가평" to "가평군", "연천" to "연천군",
    "춘천" to "춘천시", "원주" to "원주시", "강릉" to "강릉시", "속초" to "속초시",
    "홍천" to "홍천군", "횡성" to "횡성군", "평창" to "평창군", "정선" to "정선군",
    "충주" to "충주시", "제천" to "제천시", "청주" to "청주시", "천안" to "천안시",
    "아산" to "아산시", "서산" to "서산시", "당진" to "당진시"
)

private fun normalizeKoreanDongToken(rawDong: String): String {
    val trimmed = rawDong.trim()
    val dongGaMatch = Regex("^([가-힣]{2,}동)[0-9]+가$").find(trimmed)
    if (dongGaMatch != null) return dongGaMatch.groupValues[1]
    val numberedDongMatch = Regex("^([가-힣]{2,})[0-9]+동$").find(trimmed)
    if (numberedDongMatch != null) return "${numberedDongMatch.groupValues[1]}동"
    return trimmed
}

/**
 * 분 단위 운행 소요시간을 간결한 한국어 시간 문자열로 포맷팅합니다.
 * 예: 42 -> "42분", 60 -> "1시간", 75 -> "1시간 15분"
 */
fun formatDrivingDurationLabel(minutes: Int): String {
    if (minutes <= 0) return ""
    val hours = minutes / 60
    val rem = minutes % 60
    return when {
        hours <= 0 -> "${minutes}분"
        rem == 0 -> "${hours}시간"
        else -> "${hours}시간 ${rem}분"
    }
}

/**
 * 메모/설명 문자열에서 실제 기록된 운행시간(분)을 추출합니다.
 */
fun extractDrivingDurationMinutesFromText(text: String?): Int? {
    if (text.isNullOrBlank()) return null
    val hourMinMatch = Regex("(\\d+)\\s*시간\\s*(\\d+)\\s*분").find(text)
    if (hourMinMatch != null) {
        val h = hourMinMatch.groupValues[1].toIntOrNull() ?: 0
        val m = hourMinMatch.groupValues[2].toIntOrNull() ?: 0
        val total = h * 60 + m
        if (total > 0) return total
    }
    val minDriveMatch = Regex("(\\d+)\\s*분\\s*운행").find(text)
    if (minDriveMatch != null) {
        val m = minDriveMatch.groupValues[1].toIntOrNull() ?: 0
        if (m > 0) return m
    }
    val hourOnlyMatch = Regex("(\\d+)\\s*시간\\s*운행").find(text)
    if (hourOnlyMatch != null) {
        val h = hourOnlyMatch.groupValues[1].toIntOrNull() ?: 0
        if (h > 0) return h * 60
    }
    val parenMinMatch = Regex("\\(\\s*(?:총\\s*)?(\\d+)\\s*분\\s*\\)").find(text)
    if (parenMinMatch != null) {
        val m = parenMinMatch.groupValues[1].toIntOrNull() ?: 0
        if (m > 0) return m
    }
    return null
}

/**
 * 구형 20분 무이동 워치독(3분 점검 7회차 = 21분)으로 인해
 * 실제 1시간 이상 소요되는 출퇴근 주행이 '21분 운행 · GPS 1~3개 지점'으로 조기 절단된 레거시 기록인지 판별합니다.
 * (비출퇴근 일반/고속도로 주행의 실제 20~22분 운행 기록은 절대 절단 대상으로 오판하지 않음)
 */
fun VehicleLog.isWatchdogTruncatedDurationLog(): Boolean {
    if (logType != VehicleLogType.TRIP_DRIVING) return false
    if (!isCommuteDrivingLog()) return false
    val n = note ?: return false
    val rawMin = extractDrivingDurationMinutesFromText(n) ?: return false
    if (rawMin !in 20..22) return false
    val gpsCount = Regex("GPS\\s*(\\d+)\\s*개\\s*지점").find(n)?.groupValues?.get(1)?.toIntOrNull()
    return gpsCount == null || gpsCount <= 3
}

/**
 * 출퇴근 주행(기본 편도 27.1km, 위례/학암 <-> 영등포/여의도 기준)의 시간대별 교통 흐름을 반영한
 * 현실적인 운행 소요시간(분)을 결정론적으로 산출합니다.
 * - 동일 시각(연·일·시·분) 기록은 차계부와 다이어리에서 항상 동일한 분 단위로 일치합니다.
 * - 출퇴근 러시아워는 1시간 이상(약 66~76분), 심야/한산 시간대는 약 60~66분으로 자연스럽게 편차를 둡니다.
 */
fun estimateRealisticCommuteMinutes(
    timestamp: LocalDateTime,
    distanceKm: Double = 27.1
): Int {
    val effectiveKm = if (distanceKm > 5.0) distanceKm else 27.1
    val scale = (effectiveKm / 27.1).coerceIn(0.45, 2.2)
    val seed = kotlin.math.abs(
        timestamp.year * 37 +
            timestamp.dayOfYear * 13 +
            timestamp.hour * 7 +
            timestamp.minute * 3
    )
    val hour = timestamp.hour
    val raw27KmMinutes = when {
        hour in 6..9 || hour in 17..19 -> 66 + (seed % 11) // 출퇴근 혼잡 시간대: 66~76분 (1시간 6분 ~ 1시간 16분)
        hour in listOf(5, 10, 16, 20) -> 63 + (seed % 8)  // 준혼잡 시간대: 63~70분 (1시간 3분 ~ 1시간 10분)
        hour >= 21 || hour <= 4 -> 60 + (seed % 7)        // 야간/심야 시간대: 60~66분 (1시간 ~ 1시간 6분)
        else -> 62 + (seed % 8)                           // 주간 평시: 62~69분
    }
    return kotlin.math.round(raw27KmMinutes * scale).toInt().coerceAtLeast(15)
}

/**
 * 비출퇴근 일반 주행 기록에 명시적 소요시간이 누락된 경우 주행거리(km)와 시간대를 바탕으로
 * 합리적인 운행 소요시간(분)을 산출합니다.
 */
fun estimateRealisticNonCommuteMinutes(
    timestamp: LocalDateTime,
    distanceKm: Double
): Int {
    val seed = kotlin.math.abs(
        timestamp.year * 19 +
            timestamp.dayOfYear * 7 +
            timestamp.hour * 5 +
            timestamp.minute * 3
    )
    val jitter = (seed % 5) - 2
    val base = when {
        distanceKm <= 0.0 -> 10
        distanceKm < 1.0 -> 5
        distanceKm < 3.0 -> kotlin.math.round(distanceKm / 16.0 * 60.0).toInt().coerceIn(6, 14)
        distanceKm < 15.0 -> kotlin.math.round(distanceKm / 22.0 * 60.0).toInt().coerceAtLeast(10)
        distanceKm < 40.0 -> kotlin.math.round(distanceKm / 28.0 * 60.0).toInt().coerceAtLeast(26)
        else -> kotlin.math.round(22.0 + (distanceKm - 10.0) / 52.0 * 60.0).toInt().coerceAtLeast(45)
    }
    return (base + jitter).coerceAtLeast(4)
}

/**
 * VehicleLog의 거리·출퇴근 여부·시각을 바탕으로 누락/절단 시 사용할 현실적 운행 소요시간(분)을 반환합니다.
 */
fun VehicleLog.estimateRealisticDrivingMinutes(): Int {
    return if (isCommuteDrivingLog()) {
        val km = if (tripDistanceKm > 5.0) tripDistanceKm else 27.1
        estimateRealisticCommuteMinutes(timestamp, km)
    } else {
        estimateRealisticNonCommuteMinutes(timestamp, tripDistanceKm)
    }
}

/**
 * VehicleLog의 note에 기록된 실제 유효 운행시간(분)을 반환합니다.
 * (구형 21분 워치독 조기 종료 버그로 절단된 출퇴근 기록은 null을 반환하여 보정 경로로 유도)
 */
fun VehicleLog.extractDrivingDurationMinutes(): Int? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    if (isWatchdogTruncatedDurationLog()) return null
    return extractDrivingDurationMinutesFromText(note)
}

/**
 * 모든 주행 기록(TRIP_DRIVING)에서 누락 없이 표시할 최종 유효 운행시간(분)을 반환합니다.
 * - 실제 정상 측정 시간이 있으면 그대로 사용하고,
 * - 워치독 절단('21분') 또는 시간 누락 레거시/수동 기록이면 거리·시간대 기반 현실적 소요시간으로 복원합니다.
 */
fun VehicleLog.resolveEffectiveDrivingDurationMinutes(): Int? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    if (getUserTypeOverride()?.isDrivingCategory == false) return null
    val normalized = normalizeInflatedDrivingLog()
    return normalized.extractDrivingDurationMinutes()
        ?: normalized.estimateRealisticDrivingMinutes()
}

/**
 * 차계부 주행 메모(note) 문자열에 운행시간 표기를 중복 괄호 없이 깔끔하게 삽입·갱신합니다.
 * 예: "[car_1: 벤츠] [집 ➔ 회사] (GPS 2개 지점)" -> "[car_1: 벤츠] [집 ➔ 회사] (1시간 14분 운행 · GPS 2개 지점)"
 */
fun upsertDurationIntoVehicleNote(rawNote: String, durationMinutes: Int): String {
    val durLabel = formatDrivingDurationLabel(durationMinutes)
    if (durLabel.isBlank()) return rawNote
    val formattedDur = "$durLabel 운행"
    val existingDurRegex = Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행")
    if (existingDurRegex.containsMatchIn(rawNote)) {
        return rawNote.replace(existingDurRegex, formattedDur)
    }
    val trailingMetaParenRegex = Regex("\\(([^()]*(?:GPS|지점|최근접 위치|보정)[^()]*)\\)\\s*$")
    val metaMatch = trailingMetaParenRegex.find(rawNote)
    if (metaMatch != null) {
        val inside = metaMatch.groupValues[1].trim()
        val mergedParen = if (inside.isNotBlank()) "($formattedDur · $inside)" else "($formattedDur)"
        return rawNote.replaceRange(metaMatch.range, mergedParen)
    }
    return if (rawNote.isBlank()) {
        "($formattedDur)"
    } else {
        "${rawNote.trim()} ($formattedDur)"
    }
}

/**
 * 주행 경로 제목 끝의 중복 거리 표기("(27.1 km)") 및 시간 표기("(42분)")를 제거한 순수 경로 본문만 추출합니다.
 */
fun stripTrailingRouteMetricsParens(rawTitle: String): String {
    var result = rawTitle.trim()
    val trailingMetricsRegex = Regex(
        "\\s*\\(\\s*(?:(?:출근|퇴근|총)\\s*)?(?:[0-9]+(?:\\.[0-9]+)?\\s*km|(?:[0-9]+\\s*시간\\s*)?[0-9]+\\s*분(?:\\s*운행)?|[0-9]+\\s*시간(?:\\s*운행)?)\\s*\\)\\s*$",
        RegexOption.IGNORE_CASE
    )
    while (trailingMetricsRegex.containsMatchIn(result)) {
        result = result.replace(trailingMetricsRegex, "").trim()
    }
    return result
}

/**
 * 주행 경로의 개별 거점 지명을 규칙에 맞게 정제합니다:
 * - '7101동'이 포함된 지명은 언제나 "집"으로 표기하며, 세대 호수('2502호' 등)는 일절 표기하지 않음
 * - 병기된 정류장/역 명칭('.', ',', '/', '·' 등)은 구분자 앞쪽 첫 번째 명칭만 표기
 * - 최근접 큰 건물·지하철역·버스정류장·상호명·아파트명(정문/후문, 상가주차장 등)은 축약 없이 원형 보존
 * - 구체 대표 명칭이 없는 행정구역 문자열은 시·구 접두어를 빼고 순수 '동' 이름만 표시
 */
fun simplifyRoutePlaceName(rawPlace: String): String {
    val trimmed = stripTrailingRouteMetricsParens(rawPlace)
    if (trimmed.isBlank()) return ""
    if (trimmed == "집" || trimmed == "우리집" || PlaceResolver.containsHomeBuilding7101(trimmed)) return "집"
    if (trimmed == "회사") return "회사"
    if (trimmed.contains("CC", ignoreCase = true) ||
        trimmed.contains("GC", ignoreCase = true) ||
        trimmed.contains("C.C", ignoreCase = true) ||
        trimmed.contains("G.C", ignoreCase = true) ||
        trimmed.contains("골프") ||
        trimmed.contains("라운딩")
    ) {
        return PlaceResolver.cleanDisplayPlaceName(trimmed).ifBlank { trimmed }
    }

    // 끝부분에 "주변 주행", "인근 주행", "도착 주행", "왕복 주행" 등이 붙어 있는 경우 분리 후 지명만 정제
    val suffixMatch = Regex("^(.+?)\\s+((?:주변|인근|도착|왕복)\\s*주행)$").find(trimmed)
    if (suffixMatch != null) {
        val simplifiedBase = simplifyRoutePlaceName(suffixMatch.groupValues[1])
        return if (simplifiedBase.isNotBlank()) "$simplifiedBase ${suffixMatch.groupValues[2]}".trim() else suffixMatch.groupValues[2].trim()
    }

    // 세대 호수 제거 후 괄호 안 세부 출입구/주차장(정문, 후문, 상가주차장 등) 보존
    val unitStripped = PlaceResolver.stripBuildingUnitNumbers(trimmed)
    if (unitStripped.isBlank()) return ""
    if (unitStripped == "집" || unitStripped == "우리집") return "집"

    val preservedSubFeature = unitStripped.replace(Regex("\\(\\s*([^)]*?(?:정문|후문|북문|남문|동문|서문|주차장|상가|출구))\\s*\\)")) { m ->
        " ${m.groupValues[1].trim()}"
    }
    val withoutParens = preservedSubFeature
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("^(?:출근|퇴근|일반)\\s*주행\\s*:?\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (withoutParens.isBlank()) return unitStripped
    if (PlaceResolver.containsHomeBuilding7101(withoutParens)) return "집"

    // 구체 건물·역·버스정류장·상호·아파트명(정문/후문/상가주차장 포함)이면 행정구역 접두어 및 병기 정류장 뒷부분만 정리하고 반환
    if (PlaceResolver.isValidSpecificPlaceName(withoutParens)) {
        return PlaceResolver.cleanDisplayPlaceName(withoutParens).ifBlank { withoutParens }
    }

    // 구체 명칭이 없는 행정구역 문자열인 경우 시·구 구분 없이 순수 동 이름(예: 학암동, 영등포동, 방이동, 백현동) 추출
    PlaceResolver.extractPureDongFromText(withoutParens)?.let { pureDong ->
        return normalizeKoreanDongToken(pureDong)
    }

    // 도로명만 있는 주소 폴백
    when {
        withoutParens.contains("테헤란로") || withoutParens.contains("역삼") -> return "역삼동"
        withoutParens.contains("위례성대로") || withoutParens.contains("방이") -> return "방이동"
        withoutParens.contains("영등포로") || withoutParens.contains("영등포") -> return "영등포동"
        withoutParens.contains("여의대로") || withoutParens.contains("여의도") -> return "여의도동"
        withoutParens.contains("가산디지털") -> return "가산동"
    }

    val tokens = withoutParens.split(" ", ",")
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "대한민국" }
    if (tokens.isEmpty()) return unitStripped

    // 동 정보가 전혀 없는 구형 단일 시·군 데이터 폴백
    val mappedSiFromGu = tokens.firstNotNullOfOrNull { NON_SEOUL_GU_TO_SI_MAP[it] }
    if (mappedSiFromGu != null) return mappedSiFromGu
    val mappedSiGunFromStem = tokens.firstNotNullOfOrNull { CITY_STEM_TO_SI_GUN_MAP[it] }
    if (mappedSiGunFromStem != null) return mappedSiGunFromStem

    return PlaceResolver.cleanDisplayPlaceName(withoutParens).ifBlank { withoutParens }
}

/**
 * 경로 문자열에서 중복 거리·시간 괄호를 제거하고 출발지·도착지 지명을 정제하여 화살표를 통일합니다.
 */
fun simplifyDrivingRouteTitle(rawTitle: String): String {
    val trimmed = rawTitle.trim()
    if (trimmed.isBlank()) return trimmed

    val cleanedPrefix = stripTrailingRouteMetricsParens(
        trimmed
            .replace(Regex("^(?:출근|퇴근|일반)\\s*주행\\s*:?\\s*"), "")
            .replace("집->회사", "집 ➔ 회사")
            .replace("집 -> 회사", "집 ➔ 회사")
            .replace("우리집 ➔ 회사", "집 ➔ 회사")
            .replace("회사->집", "회사 ➔ 집")
            .replace("회사 -> 집", "회사 ➔ 집")
            .replace("회사 ➔ 우리집", "회사 ➔ 집")
            .trim()
    )

    val normalizedArrowBody = cleanedPrefix.replace(Regex("\\s*(?:➔|->|→)\\s*"), " ➔ ")
    return if (normalizedArrowBody.contains(" ➔ ")) {
        val parts = normalizedArrowBody
            .split(" ➔ ")
            .map { simplifyRoutePlaceName(it) }
            .filter { it.isNotBlank() }
        when {
            parts.isEmpty() -> "일반 주행"
            parts.size == 1 -> "${parts[0]} 인근 주행"
            else -> parts.joinToString(" ➔ ")
        }
    } else {
        simplifyRoutePlaceName(normalizedArrowBody)
    }
}

private fun formatRouteBaseBody(
    rawTitle: String,
    stripCommuteKeywords: Boolean = false,
    simplifyPlaces: Boolean = true,
    singleGpsPointFallback: Boolean = false
): String {
    val cleaned = stripTrailingRouteMetricsParens(
        if (stripCommuteKeywords) {
            rawTitle
                .replace(Regex("\\(\\s*(?:출근|퇴근)\\s+([0-9.]+\\s*km)\\s*\\)"), "")
                .replace(Regex("^(?:출근|퇴근)\\s*주행\\s*:?\\s*"), "")
                .replace("집->회사", "집 ➔ 회사")
                .replace("회사->집", "회사 ➔ 집")
                .trim()
        } else {
            rawTitle
                .replace("집->회사", "집 ➔ 회사")
                .replace("회사->집", "회사 ➔ 집")
                .trim()
        }
    )
    val formatted = if (simplifyPlaces) simplifyDrivingRouteTitle(cleaned) else cleaned
    // 단일 GPS 지점(GPS 1개 지점)만 수신되어 출발지 좌표를 도착지에 그대로 복사한 기록("X ➔ X")은 "X 인근 주행"으로 정직하게 표기
    if (singleGpsPointFallback && formatted.contains(" ➔ ")) {
        val parts = formatted.split(" ➔ ").map { it.trim() }.filter { it.isNotBlank() }
        if (parts.size == 2 && parts[0] == parts[1]) {
            return "${parts[0]} 인근 주행"
        }
    }
    return formatted
}

/**
 * 거리·소요시간 괄호를 제외한 순수 주행 경로 제목을 반환합니다 (리스트 대표 제목 및 수정 모달 초기값 공통).
 */
fun VehicleLog.getDrivingRouteBaseTitle(): String? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    val normalized = normalizeInflatedDrivingLog()
    val n = normalized.note ?: return null
    val distKm = normalized.tripDistanceKm

    // 사용자가 직접 수정한 커스텀 경로/제목이 있으면 최우선 반영
    val customRoute = normalized.getUserCustomRouteTitle()
    if (!customRoute.isNullOrBlank()) {
        val normalizedCustom = customRoute
            .replace("집->회사", "집 ➔ 회사")
            .replace("회사->집", "회사 ➔ 집")
        return formatRouteBaseBody(normalizedCustom, stripCommuteKeywords = false, simplifyPlaces = false)
    }

    // 0. 출근 / 퇴근 / 출퇴근 왕복 주행은 화살표(집 ➔ 회사 / 회사 ➔ 집)로 표기
    if (normalized.isToWorkCommuteLog()) {
        return "집 ➔ 회사"
    }
    if (normalized.isToHomeCommuteLog()) {
        return "회사 ➔ 집"
    }
    if (normalized.isCommuteDrivingLog() && n.contains("출퇴근")) {
        return "집 ↔ 회사"
    }

    val shouldStripCommute = !normalized.isCommuteDrivingLog()
    val gpsPointCount = Regex("GPS\\s*(\\d+)\\s*개\\s*지점").find(n)?.groupValues?.get(1)?.toIntOrNull()
    val isSinglePointFallback = gpsPointCount == 1

    // 1. 대괄호 "[출발지 ➔ 도착지]" 패턴 검색
    val routeBracketMatch = Regex("\\[([^\\]]+(?:➔|->)[^\\]]+)\\]").find(n)
    if (routeBracketMatch != null) {
        return formatRouteBaseBody(
            rawTitle = routeBracketMatch.groupValues[1].trim(),
            stripCommuteKeywords = shouldStripCommute,
            simplifyPlaces = true,
            singleGpsPointFallback = isSinglePointFallback
        )
    }

    // 1-1. 차량/단가 시스템 태그를 제외한 경로/주행 대괄호 타이틀
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
            return formatRouteBaseBody(
                rawTitle = inside,
                stripCommuteKeywords = shouldStripCommute,
                simplifyPlaces = true,
                singleGpsPointFallback = isSinglePointFallback
            )
        }
    }

    // 2. 괄호 없는 "출발지 ➔ 도착지" 패턴 검색
    val arrowMatch = Regex("([^(\\[]+➔[^)\\]]+)").find(n)
    if (arrowMatch != null) {
        return formatRouteBaseBody(
            rawTitle = arrowMatch.groupValues[1].trim(),
            stripCommuteKeywords = shouldStripCommute,
            simplifyPlaces = true,
            singleGpsPointFallback = isSinglePointFallback
        )
    }

    // 3. 레거시 포맷 폴백
    if (distKm > 0.0) {
        return if (n.contains("자동 주행")) "자동 주행" else "일반 주행"
    }

    return "차량 주행 완료"
}

/**
 * 주행 기록에서 메인 리스트 타이틀로 표시할 문자열 추출:
 * 중복 거리 괄호 및 소요시간 괄호를 모두 제외하고 깔끔한 경로 제목만 반환합니다
 * (소요시간은 하단 설명란에서 검정색 강조 텍스트로 표기).
 */
fun VehicleLog.getDrivingRouteTitle(): String? {
    return getDrivingRouteBaseTitle()
}

/**
 * 주행 기록의 하단 설명(힌트) 보조 정보 (운행 시간, GPS 지점 수 등) 추출:
 * - 모든 주행 항목(TRIP_DRIVING)에 대해 운행시간 표기 누락이 없도록 보장
 * - 60분 이상 운행 시 '1시간 15분 운행' 형태로 포맷팅
 * - 구형 21분 워치독 조기 절단 또는 시간 미기록 건도 현실적 운행시간으로 자동 복원하여 표기
 */
fun VehicleLog.getDrivingDetailSubtitle(): String? {
    if (logType != VehicleLogType.TRIP_DRIVING) return null
    if (getUserTypeOverride()?.isDrivingCategory == false) return null
    val normalized = normalizeInflatedDrivingLog()
    val n = normalized.note.orEmpty()

    val validDurationMin = normalized.resolveEffectiveDrivingDurationMinutes()
    val formattedDurationPart = if (validDurationMin != null && validDurationMin > 0) {
        "${formatDrivingDurationLabel(validDurationMin)} 운행"
    } else {
        null
    }

    val parenMatch = Regex("\\(([^()]*(?:운행|GPS|지점|최근접 위치)[^()]*)\\)").findAll(n).lastOrNull()
    val extraMetaParts = if (parenMatch != null) {
        val rawInside = parenMatch.groupValues[1]
            .replace(", 10분 주기 GPS 추적,", " · GPS")
            .replace(", GPS 추적,", " · GPS")
            .replace("10분 주기 GPS 추적,", "GPS")
            .replace("GPS 추적,", "GPS")
            .replace("10분 주기 GPS", "GPS")
            .replace(",", " · ")
        rawInside.split("·")
            .map { it.trim() }
            .filter { token ->
                token.isNotBlank() &&
                    !Regex("^(?:\\d+\\s*시간\\s*)?(?:\\d+\\s*분\\s*)?운행$").matches(token) &&
                    !Regex("^\\d+\\s*시간\\s*운행$").matches(token)
            }
    } else {
        emptyList()
    }

    val combined = buildList {
        if (!formattedDurationPart.isNullOrBlank()) add(formattedDurationPart)
        addAll(extraMetaParts)
    }
    return combined.joinToString(" · ").takeIf { it.isNotBlank() }
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
