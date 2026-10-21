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
    if (n.contains("집->회사") || n.contains("집 -> 회사") || n.contains("집 ➔ 회사") || n.contains("집➔회사")) return true
    return Regex("(학암동|위례동|하남시|우리집|집)[^➔\\->]*(?:➔|->)[^\\]\\)]*(영등포동|회사)").containsMatchIn(n)
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
    return Regex("(영등포동|회사)[^➔\\->]*(?:➔|->)[^\\]\\)]*(학암동|위례동|하남시|우리집|집)").containsMatchIn(n)
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

private val KNOWN_GYEONGGI_DONG_TO_SI_MAP = mapOf(
    "학암동" to "하남시", "망월동" to "하남시", "감일동" to "하남시", "풍산동" to "하남시", "신장동" to "하남시",
    "창곡동" to "성남시", "판교동" to "성남시", "백현동" to "성남시", "삼평동" to "성남시",
    "정자동" to "성남시", "서현동" to "성남시", "수내동" to "성남시", "야탑동" to "성남시"
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
 * 주행 경로의 개별 거점 지명을 간결하게 축약합니다:
 * - 서울 지역(서울특별시 / 서울 25개 자치구): '동' 이름만 표시 (예: "영등포구 영등포동" -> "영등포동", "송파구 장지동" -> "장지동")
 * - 경기도 및 그 외 시·군 지역: '시·군' 이름만 표시 (예: "하남시 학암동" -> "하남시", "성남시 분당구 판교동" -> "성남시")
 * - "집", "회사", 골프장명 등 특수 명칭은 원형 보존
 */
fun simplifyRoutePlaceName(rawPlace: String): String {
    val trimmed = rawPlace.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed == "집" || trimmed == "우리집") return "집"
    if (trimmed == "회사") return "회사"
    if (trimmed.contains("CC", ignoreCase = true) ||
        trimmed.contains("GC", ignoreCase = true) ||
        trimmed.contains("C.C", ignoreCase = true) ||
        trimmed.contains("G.C", ignoreCase = true) ||
        trimmed.contains("골프") ||
        trimmed.contains("라운딩")
    ) {
        return trimmed
    }

    // 끝부분에 "주변 주행", "인근 주행", "도착 주행", "왕복 주행" 등이 붙어 있는 경우 분리 후 지명만 축약
    val suffixMatch = Regex("^(.+?)\\s+((?:주변|인근|도착|왕복)\\s*주행)$").find(trimmed)
    if (suffixMatch != null) {
        val simplifiedBase = simplifyRoutePlaceName(suffixMatch.groupValues[1])
        return "$simplifiedBase ${suffixMatch.groupValues[2]}".trim()
    }

    // 괄호 안의 도로명 등 부가 정보 제거 (예: "서울 송파구 방이동 (위례성대로)" -> "서울 송파구 방이동")
    val withoutParens = trimmed
        .replace(Regex("\\([^)]*\\)"), " ")
        .replace(Regex("^(?:출근|퇴근|일반)\\s*주행\\s*:?\\s*"), "")
        .replace(Regex("\\s+"), " ")
        .trim()
    if (withoutParens.isBlank()) return trimmed

    val tokens = withoutParens.split(" ", ",")
        .map { it.trim() }
        .filter { it.isNotBlank() && it != "대한민국" }
    if (tokens.isEmpty()) return trimmed

    val hasSeoulPrefix = tokens.any { it == "서울" || it == "서울시" || it == "서울특별시" }
    val hasGyeonggiOrOtherPrefix = tokens.any {
        it in setOf("경기", "경기도", "강원", "강원도", "강원특별자치도", "충북", "충청북도", "충남", "충청남도", "인천", "인천광역시")
    }

    // 1. 명시적 비서울 시·군 토큰 확인 (예: "하남시 학암동" -> "하남시", "경기 성남시 분당구" -> "성남시", "양평군 양서면" -> "양평군")
    val explicitSiGun = tokens.firstOrNull { t ->
        (t.endsWith("시") || t.endsWith("군")) &&
            t !in setOf("서울시", "서울특별시", "광역시", "특별시", "특별자치시")
    }
    if (explicitSiGun != null) {
        return explicitSiGun
    }

    // 2. 경기도/비서울 일반구(분당구, 수정구, 수지구, 기흥구, 영통구 등) -> 상위 시(성남시, 용인시, 수원시 등) 변환
    val mappedSiFromGu = tokens.firstNotNullOfOrNull { NON_SEOUL_GU_TO_SI_MAP[it] }
    if (mappedSiFromGu != null) {
        return mappedSiFromGu
    }

    // 3. '시/군' 접미사가 생략된 시·군 어간 토큰 확인 (예: "성남 백현동" -> "성남시", "하남 학암동" -> "하남시")
    if (!hasSeoulPrefix) {
        val mappedSiGunFromStem = tokens.firstNotNullOfOrNull { CITY_STEM_TO_SI_GUN_MAP[it] }
        if (mappedSiGunFromStem != null) {
            return mappedSiGunFromStem
        }
    }

    // 4. 서울 여부 판별 (서울 접두어 또는 서울 25개 자치구 포함)
    val hasSeoulGu = !hasGyeonggiOrOtherPrefix && tokens.any { it in SEOUL_GU_SET }
    val dongToken = tokens.lastOrNull { t ->
        (t.endsWith("동") || t.endsWith("가")) &&
            !t.endsWith("동로") &&
            !t.endsWith("동길") &&
            !t.endsWith("운동장") &&
            t.length >= 2
    }

    if (hasSeoulPrefix || hasSeoulGu) {
        if (dongToken != null) {
            return normalizeKoreanDongToken(dongToken)
        }
        // 도로명만 있는 서울 주소 폴백
        return when {
            withoutParens.contains("테헤란로") || withoutParens.contains("역삼") -> "역삼동"
            withoutParens.contains("위례성대로") || withoutParens.contains("방이") -> "방이동"
            withoutParens.contains("영등포로") || withoutParens.contains("영등포") -> "영등포동"
            withoutParens.contains("여의대로") || withoutParens.contains("여의도") -> "여의도동"
            withoutParens.contains("가산디지털") -> "가산동"
            else -> tokens.lastOrNull { it !in setOf("서울", "서울시", "서울특별시") } ?: withoutParens
        }
    }

    // 5. 구/시 없이 동 이름만 단독으로 들어온 경우: 알려진 경기도 동(학암동->하남시, 백현동->성남시 등)이면 시 이름 반환, 아니면 동 이름 반환
    if (dongToken != null) {
        val normalizedDong = normalizeKoreanDongToken(dongToken)
        KNOWN_GYEONGGI_DONG_TO_SI_MAP[normalizedDong]?.let { return it }
        return normalizedDong
    }

    return withoutParens
}

/**
 * 경로 문자열("출발지 ➔ 도착지 (거리 km)")에서 출발지·도착지 지명을 규칙에 맞게 간소화하고 화살표를 통일합니다.
 */
fun simplifyDrivingRouteTitle(rawTitle: String): String {
    val trimmed = rawTitle.trim()
    if (trimmed.isBlank()) return trimmed

    val cleanedPrefix = trimmed
        .replace(Regex("^(?:출근|퇴근|일반)\\s*주행\\s*:?\\s*"), "")
        .replace("집->회사", "집 ➔ 회사")
        .replace("집 -> 회사", "집 ➔ 회사")
        .replace("우리집 ➔ 회사", "집 ➔ 회사")
        .replace("회사->집", "회사 ➔ 집")
        .replace("회사 -> 집", "회사 ➔ 집")
        .replace("회사 ➔ 우리집", "회사 ➔ 집")
        .trim()

    // 끝부분의 (27.2 km) 또는 (27.2km) 거리 표기 분리
    val distSuffixMatch = Regex("^(.*?)\\s*(\\(\\s*(?:출근\\s*|퇴근\\s*)?[0-9]+(?:\\.[0-9]+)?\\s*km\\s*\\))$", RegexOption.IGNORE_CASE)
        .find(cleanedPrefix)
    val routeBody = (distSuffixMatch?.groupValues?.get(1) ?: cleanedPrefix).trim()
    val distSuffix = distSuffixMatch?.groupValues?.get(2)?.let { rawDist ->
        val numMatch = Regex("([0-9]+(?:\\.[0-9]+)?)").find(rawDist)
        if (numMatch != null) "(${numMatch.groupValues[1]} km)" else rawDist
    }

    val normalizedArrowBody = routeBody.replace(Regex("\\s*(?:➔|->|→)\\s*"), " ➔ ")
    val simplifiedBody = if (normalizedArrowBody.contains(" ➔ ")) {
        normalizedArrowBody
            .split(" ➔ ")
            .map { simplifyRoutePlaceName(it) }
            .filter { it.isNotBlank() }
            .joinToString(" ➔ ")
    } else {
        simplifyRoutePlaceName(normalizedArrowBody)
    }

    return if (!distSuffix.isNullOrBlank()) {
        "$simplifiedBody $distSuffix".trim()
    } else {
        simplifiedBody
    }
}

private fun syncRouteTitleDistance(rawTitle: String, tripDistanceKm: Double, stripCommuteKeywords: Boolean = false, simplifyPlaces: Boolean = true): String {
    var cleaned = if (stripCommuteKeywords) {
        rawTitle
            .replace(Regex("\\(\\s*(?:출근|퇴근)\\s+([0-9.]+\\s*km)\\s*\\)"), "($1)")
            .replace(Regex("^(?:출근|퇴근)\\s*주행\\s*:?\\s*"), "")
            .replace("집->회사", "집 ➔ 회사")
            .replace("회사->집", "회사 ➔ 집")
            .trim()
    } else {
        rawTitle
            .replace("집->회사", "집 ➔ 회사")
            .replace("회사->집", "회사 ➔ 집")
    }
    if (tripDistanceKm > 0.0) {
        cleaned = cleaned.replace(Regex("((?:출근\\s*|퇴근\\s*)?)\\d+(?:\\.\\d+)?\\s*km", RegexOption.IGNORE_CASE)) { m ->
            val prefix = if (stripCommuteKeywords) "" else m.groupValues[1]
            String.format(Locale.US, "%s%.1f km", prefix, tripDistanceKm)
        }
    }
    return if (simplifyPlaces) simplifyDrivingRouteTitle(cleaned) else cleaned
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
        val normalizedCustom = customRoute
            .replace("집->회사", "집 ➔ 회사")
            .replace("회사->집", "회사 ➔ 집")
        return if (distKm > 0.0 && !normalizedCustom.contains("km", ignoreCase = true)) {
            "$normalizedCustom (%.1fkm)".format(Locale.US, distKm)
        } else {
            syncRouteTitleDistance(normalizedCustom, distKm, stripCommuteKeywords = false, simplifyPlaces = false)
        }
    }

    // 0. 출근 / 퇴근 / 출퇴근 왕복 주행은 다른 항목과 동일하게 화살표(집 ➔ 회사 / 회사 ➔ 집)로 표기
    if (normalized.isToWorkCommuteLog()) {
        return if (distKm > 0.0) String.format(Locale.US, "집 ➔ 회사 (%.1f km)", distKm) else "집 ➔ 회사"
    }
    if (normalized.isToHomeCommuteLog()) {
        return if (distKm > 0.0) String.format(Locale.US, "회사 ➔ 집 (%.1f km)", distKm) else "회사 ➔ 집"
    }
    if (normalized.isCommuteDrivingLog() && n.contains("출퇴근")) {
        return if (distKm > 0.0) String.format(Locale.US, "집 ↔ 회사 (%.1f km)", distKm) else "집 ↔ 회사"
    }

    val shouldStripCommute = !normalized.isCommuteDrivingLog()

    // 1. 신규 포맷: "[출발지 ➔ 도착지 (거리 km)]" 대괄호 패턴 검색
    val routeBracketMatch = Regex("\\[([^\\]]+(?:➔|->)[^\\]]+)\\]").find(n)
    if (routeBracketMatch != null) {
        return syncRouteTitleDistance(routeBracketMatch.groupValues[1].trim(), distKm, shouldStripCommute, simplifyPlaces = true)
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
            return syncRouteTitleDistance(inside, distKm, shouldStripCommute, simplifyPlaces = true)
        }
    }

    // 2. 괄호 없는 "출발지 ➔ 도착지" 패턴 검색
    val arrowMatch = Regex("([^(\\[]+➔[^)\\]]+)").find(n)
    if (arrowMatch != null) {
        val title = arrowMatch.groupValues[1].trim()
        val synced = syncRouteTitleDistance(title, distKm, shouldStripCommute, simplifyPlaces = true)
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
