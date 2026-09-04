package com.autologue.app.domain.model

import java.time.LocalDateTime

enum class RouteStepType(val displayName: String, val iconEmoji: String) {
    PHOTO("사진 촬영", "📷"),
    TRANSACTION("결제 / 방문", "💳"),
    GOLF("골프 라운드", "⛳"),
    DRIVING("차량 주행", "🚗"),
    MEMO("체크인 / 메모", "📝")
}

data class RouteStep(
    val id: String = "",
    val time: LocalDateTime,
    val stepType: RouteStepType,
    val title: String,
    val description: String? = null,
    val locationName: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val amount: Long? = null,
    val photoUris: List<String> = emptyList(),
    val category: String? = null,
    val companions: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)
