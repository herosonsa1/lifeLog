package com.autologue.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.autologue.app.domain.model.GolfType
import java.time.LocalDateTime

@Entity(tableName = "golf_rounds")
data class GolfRoundEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val clubName: String,
    val roundDate: LocalDateTime,
    val golfType: GolfType,
    val latitude: Double?,
    val longitude: Double?,
    val totalScore: Int?,
    val totalPutts: Int?,
    val holeScores: List<Int>,
    val scorecardPhotoUri: String?,
    val matchingPhotoUris: List<String>,
    val greenFeeExpense: Long,
    val memo: String?,
    val startTime: LocalDateTime? = null,
    val endTime: LocalDateTime? = null,
    val companions: List<String> = emptyList()
)
