package com.autologue.app.domain.model

import java.time.LocalDateTime

data class DiaryEntry(
    val id: Long = 0,
    val date: LocalDateTime,
    val title: String,
    val summary: String,
    val placeName: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val photoUris: List<String> = emptyList(),
    val totalExpense: Long = 0L,
    val drivingDistanceKm: Double = 0.0,
    val hasGolfRound: Boolean = false,
    val tags: List<String> = emptyList(),
    val routeSteps: List<RouteStep> = emptyList(),
    val movementSummary: String? = null
)

data class PlaceCluster(
    val placeName: String,
    val address: String?,
    val latitude: Double,
    val longitude: Double,
    val startTime: LocalDateTime,
    val endTime: LocalDateTime,
    val photoUris: List<String>
)
