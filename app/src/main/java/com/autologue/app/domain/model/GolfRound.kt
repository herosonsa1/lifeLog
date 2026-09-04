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
