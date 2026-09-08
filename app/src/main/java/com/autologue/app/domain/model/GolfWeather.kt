package com.autologue.app.domain.model

import java.time.LocalDateTime

/**
 * 골프 라운딩 강우 위험 등급
 */
enum class RainRiskLevel(
    val label: String,
    val description: String
) {
    CLEAR("쾌적 (비 없음)", "비 소식 없이 쾌적한 라운딩이 예상됩니다."),
    DRIZZLE("약한 비 / 이슬비", "0.1~1.0mm 안팎의 미세한 비가 예상됩니다. 방수 모자나 윈드브레이커를 준비하세요."),
    RAIN("우천 대비 (1~3mm)", "시간당 1~3mm 가량의 비가 예상됩니다. 골프 우산, 방수 재킷, 여분의 장갑을 챙기세요."),
    HEAVY_RAIN("폭우 / 취소 주의 (3mm+)", "시간당 3mm 이상의 강한 비로 그린에 물이 고일 수 있습니다. 골프장 우천 취소 규정을 확인하세요.")
}

/**
 * 시간대별(Hourly) 기상 및 강우량 데이터
 */
data class HourlyGolfWeather(
    val time: String, // 예: "07:00", "08:00"
    val hourOfDay: Int, // 7, 8, ...
    val temperature: Double, // 기온 (°C)
    val feelsLikeTemperature: Double, // 체감온도 (°C)
    val precipitationMm: Double, // 강우량 (mm/h)
    val precipitationProbability: Int, // 강수 확률 (%)
    val windSpeed: Double, // 풍속 (m/s)
    val windDirection: String, // 풍향 (예: "북서", "남동")
    val humidity: Int, // 습도 (%)
    val weatherIcon: String, // "☀️", "⛅", "🌦️", "🌧️", "⛈️"
    val conditionText: String, // "맑음", "구름조금", "소나기", "약한 비"
    val isPlayTime: Boolean // 티오프 ~ 홀아웃 플레이 시간대 여부
)

/**
 * 특정 골프 라운드의 플레이 시간대 맞춤형 WeatherNext 3 기상 정보
 */
data class GolfPlayWeather(
    val clubName: String,
    val roundDate: LocalDateTime,
    val playStartTime: String, // "07:28"
    val playEndTime: String, // "13:00"
    val avgTemperature: Double, // 플레이 시간 평균 기온 (°C)
    val minTemperature: Double, // 최저 기온 (°C)
    val maxTemperature: Double, // 최고 기온 (°C)
    val avgFeelsLike: Double, // 체감온도 (°C)
    val avgWindSpeed: Double, // 평균 풍속 (m/s)
    val maxWindSpeed: Double, // 최대 돌풍 (m/s)
    val mainWindDirection: String, // 주 풍향
    val avgHumidity: Int, // 평균 습도 (%)
    val totalRainfallMm: Double, // 플레이 시간 총 예상 강우량 (mm)
    val maxRainProbability: Int, // 최대 강수 확률 (%)
    val rainRiskLevel: RainRiskLevel, // 강우 위험도
    val weatherSummary: String, // 한 줄 요약
    val geminiBriefing: String, // Gemini AI 맞춤형 골프 어드바이스
    val source: String = "Google WeatherNext 3 AI Engine", // 데이터 출처
    val lastUpdated: String = "방금 전", // 최신 갱신 시각
    val hourlyForecast: List<HourlyGolfWeather> = emptyList() // 시간대별 상세 예보
)
