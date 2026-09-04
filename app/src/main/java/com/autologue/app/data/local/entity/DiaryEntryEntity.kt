package com.autologue.app.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.time.LocalDateTime

@Entity(tableName = "diary_entries")
data class DiaryEntryEntity(
    @PrimaryKey(autoGenerate = true)
    val id: Long = 0,
    val date: LocalDateTime,
    val title: String,
    val summary: String,
    val placeName: String?,
    val address: String?,
    val latitude: Double?,
    val longitude: Double?,
    val photoUris: List<String>,
    val totalExpense: Long,
    val drivingDistanceKm: Double,
    val hasGolfRound: Boolean,
    val tags: List<String>,
    val routeSteps: List<com.autologue.app.domain.model.RouteStep> = emptyList(),
    val movementSummary: String? = null
)
