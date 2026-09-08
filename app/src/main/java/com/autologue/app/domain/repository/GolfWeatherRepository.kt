package com.autologue.app.domain.repository

import com.autologue.app.domain.model.GolfPlayWeather
import java.time.LocalDateTime

interface GolfWeatherRepository {
    /**
     * 특정 골프장의 플레이 일시 및 시간대에 특화된 WeatherNext 3 날씨 및 시간대별 강우량을 조회합니다.
     */
    suspend fun getGolfPlayWeather(
        clubName: String,
        roundDate: LocalDateTime,
        startTime: LocalDateTime? = null,
        endTime: LocalDateTime? = null,
        forceRefresh: Boolean = false
    ): GolfPlayWeather
}
