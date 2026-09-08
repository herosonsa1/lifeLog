package com.autologue.app.presentation.golf

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autologue.app.domain.model.GolfPlayWeather
import com.autologue.app.domain.model.HourlyGolfWeather
import com.autologue.app.domain.model.RainRiskLevel
import com.autologue.app.presentation.common.AutoLoguePrimaryButton
import com.autologue.app.presentation.common.AutoLogueSecondaryButton
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.theme.*

/**
 * 골프 라운드 카드에 임베드되는 WeatherNext 3 요약 기상 배너
 */
@Composable
fun GolfWeatherSummaryBadge(
    weather: GolfPlayWeather?,
    isLoading: Boolean = false,
    onViewDetail: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (weather == null && !isLoading) return

    val riskColor = when (weather?.rainRiskLevel) {
        RainRiskLevel.CLEAR -> Color(0xFF059669) // Green
        RainRiskLevel.DRIZZLE -> Color(0xFFD97706) // Amber
        RainRiskLevel.RAIN -> Color(0xFFDC2626) // Red
        RainRiskLevel.HEAVY_RAIN -> Color(0xFF7F1D1D) // Dark Red
        null -> Color(0xFF2563EB)
    }

    val riskBgColor = when (weather?.rainRiskLevel) {
        RainRiskLevel.CLEAR -> Color(0xFFECFDF5)
        RainRiskLevel.DRIZZLE -> Color(0xFFFFFBEB)
        RainRiskLevel.RAIN -> Color(0xFFFEF2F2)
        RainRiskLevel.HEAVY_RAIN -> Color(0xFFFEE2E2)
        null -> Color(0xFFEFF6FF)
    }

    val riskBorderColor = when (weather?.rainRiskLevel) {
        RainRiskLevel.CLEAR -> Color(0xFFA7F3D0)
        RainRiskLevel.DRIZZLE -> Color(0xFFFDE68A)
        RainRiskLevel.RAIN -> Color(0xFFFECACA)
        RainRiskLevel.HEAVY_RAIN -> Color(0xFFFCA5A5)
        null -> Color(0xFFBFDBFE)
    }

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = riskBgColor,
        border = BorderStroke(1.dp, riskBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onViewDetail() }
    ) {
        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
            // Header: WeatherNext 3 Tag & Playtime
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(4.dp),
                        color = Color(0xFF1E3A8A)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "WeatherNext 3",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "플레이 시간대 (${weather?.playStartTime ?: "--:--"}~${weather?.playEndTime ?: "--:--"}) 날씨",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = Color(0xFF475569)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "상세보기→",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2563EB)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isLoading && weather == null) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = Color(0xFF2563EB))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("최신 기상정보 동기화 중...", fontSize = 12.sp, color = Slate500)
                }
            } else if (weather != null) {
                // Key metrics row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Temperature & Condition
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val icon = weather.hourlyForecast.firstOrNull { it.isPlayTime }?.weatherIcon ?: "☀️"
                        Text(icon, fontSize = 20.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "%.1f°C".format(weather.avgTemperature),
                                fontSize = 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF0F172A)
                            )
                            Text(
                                text = "체감 %.1f°C".format(weather.avgFeelsLike),
                                fontSize = 10.sp,
                                color = Slate500
                            )
                        }
                    }

                    // Wind
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("🍃", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(3.dp))
                        Column {
                            Text(
                                text = "${weather.avgWindSpeed}m/s",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = weather.mainWindDirection,
                                fontSize = 10.sp,
                                color = Slate500
                            )
                        }
                    }

                    // Humidity
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("💧", fontSize = 14.sp)
                        Spacer(modifier = Modifier.width(3.dp))
                        Column {
                            Text(
                                text = "${weather.avgHumidity}%",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF1E293B)
                            )
                            Text(
                                text = "습도",
                                fontSize = 10.sp,
                                color = Slate500
                            )
                        }
                    }

                    // Precipitation (Most important!)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = riskColor.copy(alpha = 0.12f),
                        border = BorderStroke(1.dp, riskColor.copy(alpha = 0.35f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "🌧️ 강우량",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = riskColor
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "%.1fmm".format(weather.totalRainfallMm),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = riskColor
                                )
                            }
                            Text(
                                text = "최고확률 ${weather.maxRainProbability}% (${weather.rainRiskLevel.label.substringBefore(" ")})",
                                fontSize = 9.sp,
                                fontWeight = FontWeight.Bold,
                                color = riskColor
                            )
                        }
                    }
                }

                // Summary single-line text
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = weather.weatherSummary,
                    fontSize = 11.sp,
                    color = Color(0xFF334155),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1
                )
            }
        }
    }
}

/**
 * 사용자가 가장 중요시하는 시간대별(Hourly) 강우량 & 기상 차트 컴포넌트
 */
@Composable
fun GolfHourlyRainfallChart(
    hourlyList: List<HourlyGolfWeather>,
    modifier: Modifier = Modifier
) {
    if (hourlyList.isEmpty()) return

    val maxRain = (hourlyList.maxOfOrNull { it.precipitationMm } ?: 0.0).coerceAtLeast(2.0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color(0xFFF8FAFC), RoundedCornerShape(14.dp))
            .border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.BarChart,
                    contentDescription = null,
                    tint = Color(0xFF2563EB),
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "시간대별 강우량 & 강수확률 예보",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color(0xFF0F172A)
                )
            }

            Text(
                text = "🟦 플레이 시간대 하이라이트",
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                color = Color(0xFF2563EB)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // Horizontal scrollable chart
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalAlignment = Alignment.Bottom
        ) {
            hourlyList.forEach { item ->
                HourlyRainfallColumn(item = item, maxRainReference = maxRain)
            }
        }
    }
}

@Composable
private fun HourlyRainfallColumn(
    item: HourlyGolfWeather,
    maxRainReference: Double
) {
    val isPlay = item.isPlayTime
    val hasRain = item.precipitationMm > 0.0

    val barHeightRatio = if (maxRainReference > 0) (item.precipitationMm / maxRainReference).toFloat().coerceIn(0.08f, 1.0f) else 0.08f
    val barHeightDp = (50.dp * barHeightRatio)

    val barColor = when {
        item.precipitationMm >= 3.0 -> Color(0xFFDC2626)
        item.precipitationMm >= 1.0 -> Color(0xFFEA580C)
        item.precipitationMm > 0.0 -> Color(0xFF2563EB)
        else -> Color(0xFFE2E8F0)
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(52.dp)
            .background(
                color = if (isPlay) Color(0xFFEFF6FF) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .border(
                width = if (isPlay) 1.dp else 0.dp,
                color = if (isPlay) Color(0xFF93C5FD) else Color.Transparent,
                shape = RoundedCornerShape(8.dp)
            )
            .padding(vertical = 6.dp, horizontal = 2.dp)
    ) {
        // Play indicator badge
        if (isPlay) {
            Surface(
                shape = RoundedCornerShape(3.dp),
                color = Color(0xFF2563EB)
            ) {
                Text(
                    text = "PLAY",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 3.dp, vertical = 1.dp)
                )
            }
        } else {
            Spacer(modifier = Modifier.height(14.dp))
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Time
        Text(
            text = item.time,
            fontSize = 11.sp,
            fontWeight = if (isPlay) FontWeight.Bold else FontWeight.Normal,
            color = if (isPlay) Color(0xFF1E3A8A) else Slate600
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Weather Icon
        Text(item.weatherIcon, fontSize = 16.sp)

        Spacer(modifier = Modifier.height(6.dp))

        // Rainfall Bar (Visual Graph)
        Box(
            modifier = Modifier
                .width(18.dp)
                .height(55.dp),
            contentAlignment = Alignment.BottomCenter
        ) {
            // Background track
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFFF1F5F9))
            )
            // Active rain bar
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(if (hasRain) barHeightDp else 4.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(barColor)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Rainfall amount (mm)
        Text(
            text = if (hasRain) "%.1fmm".format(item.precipitationMm) else "0mm",
            fontSize = 10.sp,
            fontWeight = if (hasRain) FontWeight.ExtraBold else FontWeight.SemiBold,
            color = if (hasRain) barColor else Slate500
        )

        // Probability (%)
        Text(
            text = "${item.precipitationProbability}%",
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            color = if (item.precipitationProbability >= 40) Color(0xFF2563EB) else Slate400
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Temperature
        Text(
            text = "%.0f°".format(item.temperature),
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A)
        )

        // Wind
        Text(
            text = "%.1fm/s".format(item.windSpeed),
            fontSize = 8.sp,
            color = Slate500
        )
    }
}

/**
 * WeatherNext 3 및 Gemini AI 기상 분석 심층 다이얼로그
 */
@Composable
fun GolfWeatherDetailDialog(
    weather: GolfPlayWeather,
    isRefreshing: Boolean = false,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(20.dp),
            color = AppColors.surface,
            shadowElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⛳", fontSize = 22.sp)
                        Spacer(modifier = Modifier.width(6.dp))
                        Column {
                            Text(
                                text = "${weather.clubName} 날씨 예보",
                                style = AppTypography.h2
                            )
                            Text(
                                text = "${weather.source} · ${weather.lastUpdated}",
                                fontSize = 11.sp,
                                color = Color(0xFF2563EB),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = onRefresh, enabled = !isRefreshing) {
                            if (isRefreshing) {
                                CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            } else {
                                Icon(Icons.Default.Refresh, contentDescription = "새로고침", tint = Color(0xFF2563EB))
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "닫기", tint = Slate400)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.xs))

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    // Playtime Notice Bar
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFEFF6FF),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "플레이 타임: ${weather.playStartTime} ~ ${weather.playEndTime}",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E3A8A)
                                )
                            }
                            Text(
                                text = weather.rainRiskLevel.label,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (weather.totalRainfallMm > 0) Color(0xFFDC2626) else Color(0xFF059669)
                            )
                        }
                    }

                    // Gemini AI Briefing Card
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFF1F5F9),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(14.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("✨", fontSize = 16.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Gemini AI 골프 라운딩 정밀 분석",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = weather.geminiBriefing,
                                fontSize = 12.sp,
                                lineHeight = 18.sp,
                                color = Color(0xFF334155)
                            )
                        }
                    }

                    // 4-Quadrant Key Metrics Grid (Rainfall First!)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 1. 강우량 카드 (가장 중요)
                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "예상 강우량 (최우선)",
                            value = "%.1f mm".format(weather.totalRainfallMm),
                            subtext = "최대 강수확률 ${weather.maxRainProbability}%",
                            highlightColor = if (weather.totalRainfallMm > 0) Color(0xFFDC2626) else Color(0xFF059669),
                            bgColor = if (weather.totalRainfallMm > 0) Color(0xFFFEF2F2) else Color(0xFFECFDF5)
                        )

                        // 2. 기온 카드
                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "평균 기온 / 체감",
                            value = "%.1f°C".format(weather.avgTemperature),
                            subtext = "체감 %.1f°C (최고 %.1f°C)".format(weather.avgFeelsLike, weather.maxTemperature),
                            highlightColor = Color(0xFF2563EB),
                            bgColor = Color(0xFFEFF6FF)
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // 3. 바람 카드
                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "바람 (풍속/풍향)",
                            value = "%.1f m/s".format(weather.avgWindSpeed),
                            subtext = "${weather.mainWindDirection}풍 (돌풍 %.1fm/s)".format(weather.maxWindSpeed),
                            highlightColor = Color(0xFF0D9488),
                            bgColor = Color(0xFFF0FDFA)
                        )

                        // 4. 습도 카드
                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "습도",
                            value = "${weather.avgHumidity}%",
                            subtext = "쾌적도 적정",
                            highlightColor = Color(0xFF6366F1),
                            bgColor = Color(0xFFEEF2FF)
                        )
                    }

                    // Hourly Chart Component (시간대별 강우량 & 기상 차트)
                    GolfHourlyRainfallChart(hourlyList = weather.hourlyForecast)

                    // Hourly Details Table
                    Text(
                        text = "📋 시간대별 상세 기상 데이터",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1E293B)
                    )

                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            // Table Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(Color(0xFFF8FAFC))
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("시간", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(45.dp))
                                Text("날씨", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(60.dp))
                                Text("강우량", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626), modifier = Modifier.width(60.dp))
                                Text("기온", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(45.dp))
                                Text("바람", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(55.dp))
                            }
                            HairlineDivider()

                            weather.hourlyForecast.forEach { item ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(if (item.isPlayTime) Color(0xFFF0F9FF) else Color.Transparent)
                                        .padding(horizontal = 12.dp, vertical = 7.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(modifier = Modifier.width(45.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(item.time, fontSize = 11.sp, fontWeight = if (item.isPlayTime) FontWeight.Bold else FontWeight.Normal)
                                    }
                                    Row(modifier = Modifier.width(60.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("${item.weatherIcon} ${item.conditionText}", fontSize = 10.sp)
                                    }
                                    Row(modifier = Modifier.width(60.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = if (item.precipitationMm > 0) "%.1fmm (%d%%)".format(item.precipitationMm, item.precipitationProbability) else "0mm",
                                            fontSize = 10.sp,
                                            fontWeight = if (item.precipitationMm > 0) FontWeight.Bold else FontWeight.Normal,
                                            color = if (item.precipitationMm > 0) Color(0xFFDC2626) else Slate600
                                        )
                                    }
                                    Row(modifier = Modifier.width(45.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("%.0f°C".format(item.temperature), fontSize = 11.sp)
                                    }
                                    Row(modifier = Modifier.width(55.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text("%.1fm/s".format(item.windSpeed), fontSize = 10.sp, color = Slate600)
                                    }
                                }
                                HairlineDivider()
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                AutoLoguePrimaryButton(
                    text = "확인",
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun MetricQuadrantCard(
    title: String,
    value: String,
    subtext: String,
    highlightColor: Color,
    bgColor: Color,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = bgColor,
        border = BorderStroke(1.dp, highlightColor.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Text(title, fontSize = 10.sp, color = Slate600, fontWeight = FontWeight.Medium)
            Spacer(modifier = Modifier.height(3.dp))
            Text(value, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = highlightColor)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtext, fontSize = 9.sp, color = Slate500)
        }
    }
}
