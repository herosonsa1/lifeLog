package com.autologue.app.domain.model

import java.time.LocalDateTime

enum class GolfType { FIELD, SCREEN }

data class GolfRound(
    val id: Long = 0,
    val clubName: String,
    val roundDate: LocalDateTime,
    val golfType: GolfType,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val totalScore: Int? = null,
    val totalPutts: Int? = null,
    val holeScores: List<Int> = emptyList(),
    // [H-02] OCR 추출 실제 홀별 파 정보 — getScorecardStats() 정확한 집계에 사용
    val holePars: List<Int> = emptyList(),
    val scorecardPhotoUri: String? = null,
    val matchingPhotoUris: List<String> = emptyList(),
    val greenFeeExpense: Long = 0L,
    val memo: String? = null,
    val startTime: LocalDateTime? = null,
    val endTime: LocalDateTime? = null,
    val companions: List<String> = emptyList(),
    val penaltyCount: Int? = null,
    val girPercentage: Double? = null,
    val averageDriveDistance: Double? = null,
    val adjustedDriveDistance: Double? = null,
    val averageTempo: Double? = null,
    val steps: Int? = null,
    val driveDistances: List<Double> = emptyList(),
    val tempos: List<Double> = emptyList()
) {
    /**
     * 최저/최고 기록을 제외한 유효 보정 평균 티샷 비거리 반환 (파3 제외)
     */
    fun getEffectiveAdjustedDriveDistance(): Double? {
        if (adjustedDriveDistance != null) return adjustedDriveDistance
        if (driveDistances.size >= 3) {
            val sorted = driveDistances.sorted()
            val trimmed = sorted.subList(1, sorted.size - 1)
            return (trimmed.sum() / trimmed.size * 10).toInt() / 10.0
        }
        return averageDriveDistance
    }

    /**
     * "평균 비거리(보정 평균 비거리)" 포맷 문자열 반환 (예: "205.2m (201.4m)" 또는 "205m")
     */
    fun getFormattedDriveDistance(): String? {
        val avg = averageDriveDistance ?: return null
        val adj = getEffectiveAdjustedDriveDistance()
        return if (adj != null && adj != avg) {
            "${avg}m (${adj}m)"
        } else {
            "${avg}m"
        }
    }
}

/**
 * 스코어카드 홀별 성적 집계 (버디, 파, 보기, 더블이상, 페널티)
 */
data class ScorecardStats(
    val birdieCount: Int = 0,
    val parCount: Int = 0,
    val bogeyCount: Int = 0,
    val doublePlusCount: Int = 0,
    val penaltyCount: Int = 0
)

/**
 * 스코어카드 통계 집계 반환 (holeScores 기반 자동 계산 최우선, 없으면 memo에 저장된 태그 폴백)
 */
fun GolfRound.getScorecardStats(): ScorecardStats? {
    // 1. holeScores 기반 자동 계산 (실제 스코어가 항상 최우선 진실의 원천)
    if (holeScores.isNotEmpty()) {
        // [H-02] OCR로 저장된 실제 홀별 파 배열 우선 사용, 없으면 표준 18홀 기본 파 폴백
        val standardPars = listOf(4, 3, 5, 4, 3, 4, 5, 4, 4, 4, 4, 5, 3, 4, 5, 4, 3, 4)
        val effectivePars = if (holePars.size == holeScores.size && holePars.isNotEmpty()) holePars
                           else standardPars
        var birdie = 0
        var par = 0
        var bogey = 0
        var doublePlus = 0

        holeScores.forEachIndexed { idx, score ->
            val expectedPar = effectivePars.getOrElse(idx) { 4 }
            val diff = score - expectedPar
            when {
                diff <= -1 -> birdie++
                diff == 0 -> par++
                diff == 1 -> bogey++
                else -> doublePlus++
            }
        }
        return ScorecardStats(
            birdieCount = birdie,
            parCount = par,
            bogeyCount = bogey,
            doublePlusCount = doublePlus,
            penaltyCount = this.penaltyCount ?: 0
        )
    }

    // 2. 메모 내 [통계: 버디 X, 파 Y, 보기 Z, 더블+ W] 정규식 파싱 (holeScores가 없는 과거 기록 폴백)
    val memoText = memo ?: ""
    val statsRegex = Regex("""\[통계\s*[:：]?\s*버디\s*(\d+)[\s,·]*파\s*(\d+)[\s,·]*보기\s*(\d+)[\s,·]*더블\+?\s*(\d+)\]""")
    val match = statsRegex.find(memoText)
    if (match != null) {
        val b = match.groupValues[1].toIntOrNull() ?: 0
        val p = match.groupValues[2].toIntOrNull() ?: 0
        val bogey = match.groupValues[3].toIntOrNull() ?: 0
        val d = match.groupValues[4].toIntOrNull() ?: 0
        return ScorecardStats(
            birdieCount = b,
            parCount = p,
            bogeyCount = bogey,
            doublePlusCount = d,
            penaltyCount = this.penaltyCount ?: 0
        )
    }

    return null
}

/**
 * 골프장명과 코스명을 직관적으로 결합하여 UI에 표기하는 헬퍼 확장 함수
 * 예: "오크밸리 CC (잣나무 코스)"
 */
fun GolfRound.getDisplayClubName(): String {
    if (clubName.contains("(") && clubName.contains(")")) {
        return clubName
    }
    val courseFromMemo = extractCourseNameFromText(memo ?: "")
    if (!courseFromMemo.isNullOrBlank() && !clubName.contains(courseFromMemo)) {
        return "$clubName ($courseFromMemo)"
    }
    return clubName
}

/**
 * 텍스트 또는 메모에서 코스명을 안전하게 추출하는 유틸 함수
 */
fun extractCourseNameFromText(text: String): String? {
    if (text.isBlank()) return null

    // 1. [코스: XXX] 또는 코스: XXX 패턴
    val bracketMatch = Regex("""\[코스\s*[:：]?\s*([^\]]+)\]""").find(text)
    if (bracketMatch != null) {
        val raw = bracketMatch.groupValues[1].trim()
        if (raw.isNotBlank()) {
            return if (raw.endsWith("코스")) raw else "$raw 코스"
        }
    }

    val colonMatch = Regex("""(?:코스|Course)\s*[:：]\s*([가-힣A-Za-z0-9/]+)""", RegexOption.IGNORE_CASE).find(text)
    if (colonMatch != null) {
        val raw = colonMatch.groupValues[1].trim()
        if (raw.isNotBlank()) {
            return if (raw.endsWith("코스")) raw else "$raw 코스"
        }
    }

    // 2. 괄호 안의 코스명 (예: "(마운틴 코스)", "(Hill)", "(오크)")
    val parenMatch = Regex("""\(([^)]+)\)""").find(text)
    if (parenMatch != null) {
        val cand = parenMatch.groupValues[1].trim()
        if (cand.isNotBlank() && !cand.contains("CC") && !cand.contains("GC") && !cand.contains("골프")) {
            return if (cand.endsWith("코스")) cand else "$cand 코스"
        }
    }

    // 3. "XXX 코스" 패턴
    val suffixMatch = Regex("""([가-힣A-Za-z0-9/]{1,10})\s*코스""").find(text)
    if (suffixMatch != null) {
        val cand = suffixMatch.groupValues[1].trim()
        if (cand.isNotBlank() && !cand.contains("CC") && !cand.contains("GC") && !cand.contains("골프")) {
            return "$cand 코스"
        }
    }

    return null
}

/**
 * 골프장명 문자열에서 골프장명과 코스명을 지능적으로 분리하는 함수
 * 예: "스카이밸리 CC (마운틴 코스)" -> Pair("스카이밸리 CC", "마운틴 코스")
 *     "스카이밸리 CC 마운틴" -> Pair("스카이밸리 CC", "마운틴 코스")
 *     "아리지 CC 햇님 코스" -> Pair("아리지 CC", "햇님 코스")
 *     "안양 CC" -> Pair("안양 CC", "")
 */
fun splitClubAndCourse(input: String): Pair<String, String> {
    val trimmed = input.trim()
    if (trimmed.isBlank()) return Pair("", "")

    // Case 1: 괄호로 감싸진 코스명: "구장명 (코스명)"
    val parenMatch = Regex("""^(.*?)\s*\(([^)]+)\)$""").find(trimmed)
    if (parenMatch != null) {
        val club = parenMatch.groupValues[1].trim()
        val course = parenMatch.groupValues[2].trim()
        val cleanCourse = if (course.endsWith("코스")) course else "$course 코스"
        return Pair(club, cleanCourse)
    }

    // Case 2: 구장 접미사(CC, GC, 골프장 등) 뒤에 코스명이 붙은 형태: "구장명 CC 마운틴 코스" 또는 "구장명 CC 마운틴"
    val afterClubMatch = Regex("""^(.*?(?:CC|GC|C\.C|G\.C|골프장|클럽|컨트리클럽|골프존파크|파크))\s+([가-힣A-Za-z0-9/]+(?:\s*코스)?)$""", RegexOption.IGNORE_CASE).find(trimmed)
    if (afterClubMatch != null) {
        val club = afterClubMatch.groupValues[1].trim()
        val rawCourse = afterClubMatch.groupValues[2].trim()
        val cleanCourse = if (rawCourse.endsWith("코스")) rawCourse else "$rawCourse 코스"
        return Pair(club, cleanCourse)
    }

    // Case 3: "XXX 코스" 접미사가 문장 끝에 붙어있는 경우
    val genericCourseMatch = Regex("""^(.*?)\s+([가-힣A-Za-z0-9/]+\s*코스)$""").find(trimmed)
    if (genericCourseMatch != null) {
        val club = genericCourseMatch.groupValues[1].trim()
        val course = genericCourseMatch.groupValues[2].trim()
        return Pair(club, course)
    }

    return Pair(trimmed, "")
}
