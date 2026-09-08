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
    val scorecardPhotoUri: String? = null,
    val matchingPhotoUris: List<String> = emptyList(),
    val greenFeeExpense: Long = 0L,
    val memo: String? = null,
    val startTime: LocalDateTime? = null,
    val endTime: LocalDateTime? = null,
    val companions: List<String> = emptyList()
)

/**
 * 골프장명과 코스명을 직관적으로 결합하여 UI에 표기하는 헬퍼 확장 함수
 * 예: "오크밸리 CC (잣나무 코스)"
 */
fun GolfRound.getDisplayClubName(): String {
    if (clubName.contains("(") && clubName.contains(")")) {
        return clubName
    }
    if (!memo.isNullOrBlank() && memo.contains("코스")) {
        val coursePart = memo.substringAfter("[").substringBefore("]")
            .replace("코스", "")
            .replace(":", "")
            .trim()
        if (coursePart.isNotBlank()) {
            val suffix = if (coursePart.endsWith("코스")) coursePart else "$coursePart 코스"
            return "$clubName ($suffix)"
        }
    }
    return clubName
}
