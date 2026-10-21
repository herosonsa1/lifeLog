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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autologue.app.domain.model.GolfPlayWeather
import com.autologue.app.domain.model.HourlyGolfWeather
import com.autologue.app.domain.model.RainRiskLevel
import com.autologue.app.presentation.common.AutoLoguePrimaryButton
import com.autologue.app.presentation.common.AutoLogueSecondaryButton
import com.autologue.app.presentation.common.EasyModalModeSwitchChip
import com.autologue.app.presentation.common.EasyTheme
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.LocalEasyMode
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
    val isEasyMode = LocalEasyMode.current

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
        shape = RoundedCornerShape(if (isEasyMode) 20.dp else 12.dp),
        color = if (isEasyMode) EasyTheme.surface else riskBgColor,
        border = if (isEasyMode) null else BorderStroke(1.dp, riskBorderColor),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onViewDetail() }
    ) {
        Column(modifier = Modifier.padding(horizontal = if (isEasyMode) 18.dp else 12.dp, vertical = if (isEasyMode) 16.dp else 10.dp)) {
            // Header: WeatherNext 3 Tag & Playtime
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = if (isEasyMode) EasyTheme.surfaceSubtle else Color(0xFF1E3A8A)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Text(
                                text = "WeatherNext 3",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEasyMode) EasyTheme.accent else Color.White
                            )
                        }
                    }
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "플레이 시간대 (${weather?.playStartTime ?: "--:--"}~${weather?.playEndTime ?: "--:--"}) 날씨",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (isEasyMode) EasyTheme.textPrimary else Color(0xFF475569)
                    )
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "상세보기 →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isEasyMode) EasyTheme.accent else Color(0xFF2563EB)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

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
                        if (!isEasyMode) {
                            val icon = weather.hourlyForecast.firstOrNull { it.isPlayTime }?.weatherIcon ?: "☀️"
                            Text(icon, fontSize = 20.sp)
                            Spacer(modifier = Modifier.width(6.dp))
                        }
                        Column {
                            Text(
                                text = "%.1f°C".format(weather.avgTemperature),
                                fontSize = if (isEasyMode) 17.sp else 15.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEasyMode) EasyTheme.textPrimary else Color(0xFF0F172A)
                            )
                            Text(
                                text = "체감 %.1f°C".format(weather.avgFeelsLike),
                                fontSize = 11.sp,
                                color = if (isEasyMode) EasyTheme.textSecondary else Slate500
                            )
                        }
                    }

                    // Wind
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isEasyMode) {
                            Text("🍃", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Column {
                            Text(
                                text = "${weather.avgWindSpeed}m/s",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEasyMode) EasyTheme.textPrimary else Color(0xFF1E293B)
                            )
                            Text(
                                text = if (isEasyMode) "바람 · ${weather.mainWindDirection}" else weather.mainWindDirection,
                                fontSize = 11.sp,
                                color = if (isEasyMode) EasyTheme.textSecondary else Slate500
                            )
                        }
                    }

                    // Humidity
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!isEasyMode) {
                            Text("💧", fontSize = 14.sp)
                            Spacer(modifier = Modifier.width(3.dp))
                        }
                        Column {
                            Text(
                                text = "${weather.avgHumidity}%",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEasyMode) EasyTheme.textPrimary else Color(0xFF1E293B)
                            )
                            Text(
                                text = "습도",
                                fontSize = 11.sp,
                                color = if (isEasyMode) EasyTheme.textSecondary else Slate500
                            )
                        }
                    }

                    // Precipitation (Most important!)
                    Surface(
                        shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
                        color = if (isEasyMode) riskColor.copy(alpha = 0.08f) else riskColor.copy(alpha = 0.12f),
                        border = if (isEasyMode) null else BorderStroke(1.dp, riskColor.copy(alpha = 0.35f))
                    ) {
                        Column(
                            horizontalAlignment = Alignment.End,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (isEasyMode) "강우량" else "🌧️ 강우량",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = riskColor
                                )
                                Spacer(modifier = Modifier.width(4.dp))
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
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = weather.weatherSummary,
                    fontSize = 11.sp,
                    color = if (isEasyMode) EasyTheme.textSecondary else Color(0xFF334155),
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
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
    val isEasyMode = LocalEasyMode.current

    val maxRain = (hourlyList.maxOfOrNull { it.precipitationMm } ?: 0.0).coerceAtLeast(2.0)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = if (isEasyMode) EasyTheme.surface else Color(0xFFF8FAFC),
                shape = RoundedCornerShape(if (isEasyMode) 20.dp else 14.dp)
            )
            .let {
                if (isEasyMode) it else it.border(1.dp, Color(0xFFE2E8F0), RoundedCornerShape(14.dp))
            }
            .padding(if (isEasyMode) 18.dp else 14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (!isEasyMode) {
                    Icon(
                        imageVector = Icons.Default.BarChart,
                        contentDescription = null,
                        tint = Color(0xFF2563EB),
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                }
                Text(
                    text = "시간대별 강우량 및 강수확률 예보",
                    fontSize = if (isEasyMode) 14.sp else 13.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEasyMode) EasyTheme.textPrimary else Color(0xFF0F172A)
                )
            }
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
                HourlyRainfallColumn(item = item, maxRainReference = maxRain, isEasyMode = isEasyMode)
            }
        }
    }
}

@Composable
private fun HourlyRainfallColumn(
    item: HourlyGolfWeather,
    maxRainReference: Double,
    isEasyMode: Boolean = false
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
            .width(54.dp)
            .background(
                color = when {
                    isEasyMode && isPlay -> EasyTheme.accentSoft
                    isPlay -> Color(0xFFEFF6FF)
                    else -> Color.Transparent
                },
                shape = RoundedCornerShape(10.dp)
            )
            .border(
                width = if (!isEasyMode && isPlay) 1.dp else 0.dp,
                color = if (!isEasyMode && isPlay) Color(0xFF93C5FD) else Color.Transparent,
                shape = RoundedCornerShape(10.dp)
            )
            .padding(vertical = 8.dp, horizontal = 3.dp)
    ) {
        // Play indicator badge
        if (isPlay) {
            Surface(
                shape = RoundedCornerShape(4.dp),
                color = if (isEasyMode) EasyTheme.accent else Color(0xFF2563EB)
            ) {
                Text(
                    text = "PLAY",
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
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
 * WeatherNext 3 및 AI 기상 분석 심층 다이얼로그
 */
@Composable
fun GolfWeatherDetailDialog(
    weather: GolfPlayWeather,
    isRefreshing: Boolean = false,
    onDismiss: () -> Unit,
    onRefresh: () -> Unit
) {
    val isEasyMode = LocalEasyMode.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.90f),
            shape = RoundedCornerShape(if (isEasyMode) 28.dp else 20.dp),
            color = if (isEasyMode) EasyTheme.bg else AppColors.surface,
            shadowElevation = if (isEasyMode) 0.dp else 8.dp
        ) {
            Column(
                modifier = Modifier.fillMaxSize()
            ) {
                // Header
                Surface(color = if (isEasyMode) EasyTheme.surface else Color.Transparent) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.lg, vertical = if (isEasyMode) 18.dp else Spacing.md),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            if (!isEasyMode) {
                                Text("⛳", fontSize = 22.sp)
                                Spacer(modifier = Modifier.width(6.dp))
                            }
                            Column {
                                Text(
                                    text = "${weather.clubName} 날씨 예보",
                                    style = AppTypography.h2.copy(
                                        color = if (isEasyMode) EasyTheme.textPrimary else AppColors.primary
                                    )
                                )
                                Text(
                                    text = "${weather.source} · ${weather.lastUpdated}",
                                    fontSize = 11.sp,
                                    color = if (isEasyMode) EasyTheme.accent else Color(0xFF2563EB),
                                    fontWeight = FontWeight.SemiBold
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            EasyModalModeSwitchChip()
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
                }

                if (!isEasyMode) {
                    HairlineDivider()
                }

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = if (isEasyMode) 16.dp else Spacing.lg, vertical = if (isEasyMode) 16.dp else Spacing.md),
                    verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 12.dp else Spacing.md)
                ) {
                    // Playtime Notice Bar
                    Surface(
                        shape = RoundedCornerShape(if (isEasyMode) 18.dp else 10.dp),
                        color = if (isEasyMode) EasyTheme.surface else Color(0xFFEFF6FF),
                        border = if (isEasyMode) null else BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(if (isEasyMode) 16.dp else 12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.Schedule, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "플레이 타임: ${weather.playStartTime} ~ ${weather.playEndTime}",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEasyMode) EasyTheme.textPrimary else Color(0xFF1E3A8A)
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

                    // AI Briefing Card
                    Surface(
                        shape = RoundedCornerShape(if (isEasyMode) 20.dp else 12.dp),
                        color = if (isEasyMode) EasyTheme.surface else Color(0xFFF1F5F9),
                        border = if (isEasyMode) null else BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(if (isEasyMode) 18.dp else 14.dp)) {
                            Text(
                                text = "AI 골프 라운딩 정밀 분석",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isEasyMode) EasyTheme.textPrimary else Color(0xFF0F172A)
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = weather.geminiBriefing,
                                fontSize = 12.sp,
                                lineHeight = 19.sp,
                                color = if (isEasyMode) EasyTheme.textSecondary else Color(0xFF334155)
                            )
                        }
                    }

                    // 4-Quadrant Key Metrics Grid
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 10.dp else 8.dp)
                    ) {
                        val rainHighlight = if (weather.totalRainfallMm >= 1.0) Color(0xFFDC2626) else Color(0xFF059669)
                        val rainBg = if (weather.totalRainfallMm >= 1.0) Color(0xFFFEF2F2) else Color(0xFFECFDF5)
                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "예상 강우량",
                            value = "%.1f mm".format(weather.totalRainfallMm),
                            subtext = "최대 ${weather.maxRainProbability}%",
                            highlightColor = rainHighlight,
                            bgColor = rainBg,
                            isEasyMode = isEasyMode
                        )

                        val tempHighlight = when {
                            weather.avgTemperature <= 10.0 -> Color(0xFF2563EB)
                            weather.avgTemperature >= 30.0 -> Color(0xFFDC2626)
                            else -> Color(0xFF2563EB)
                        }
                        val tempBg = when {
                            weather.avgTemperature >= 30.0 -> Color(0xFFFEF2F2)
                            else -> Color(0xFFEFF6FF)
                        }
                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "평균 기온 / 체감",
                            value = "%.1f°C".format(weather.avgTemperature),
                            subtext = "체감 %.1f°C".format(weather.avgFeelsLike),
                            highlightColor = tempHighlight,
                            bgColor = tempBg,
                            isEasyMode = isEasyMode
                        )
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 10.dp else 8.dp)
                    ) {
                        val windHighlight = when {
                            weather.avgWindSpeed > 6.5 -> Color(0xFFDC2626)
                            weather.avgWindSpeed >= 3.5 -> Color(0xFF2563EB)
                            else -> Color(0xFF0D9488)
                        }
                        val windBg = when {
                            weather.avgWindSpeed > 6.5 -> Color(0xFFFEF2F2)
                            weather.avgWindSpeed >= 3.5 -> Color(0xFFEFF6FF)
                            else -> Color(0xFFF0FDFA)
                        }
                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "바람 (풍속/풍향)",
                            value = "%.1f m/s".format(weather.avgWindSpeed),
                            subtext = "${weather.mainWindDirection}풍",
                            highlightColor = windHighlight,
                            bgColor = windBg,
                            isEasyMode = isEasyMode
                        )

                        MetricQuadrantCard(
                            modifier = Modifier.weight(1f),
                            title = "습도",
                            value = "${weather.avgHumidity}%",
                            subtext = "쾌적도 적정",
                            highlightColor = Color(0xFF6366F1),
                            bgColor = Color(0xFFEEF2FF),
                            isEasyMode = isEasyMode
                        )
                    }

                    // Hourly Chart Component
                    GolfHourlyRainfallChart(hourlyList = weather.hourlyForecast)

                    // Hourly Details Table
                    Surface(
                        shape = RoundedCornerShape(if (isEasyMode) 20.dp else 10.dp),
                        color = if (isEasyMode) EasyTheme.surface else Color.White,
                        border = if (isEasyMode) null else BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(if (isEasyMode) 14.dp else 0.dp)) {
                            if (isEasyMode) {
                                Text(
                                    text = "시간대별 상세 기상 데이터",
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EasyTheme.textPrimary,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                )
                            } else {
                                Text(
                                    text = "📋 시간대별 상세 기상 데이터",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E293B),
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                                )
                            }
                            // Table Header
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .background(
                                        color = if (isEasyMode) EasyTheme.surfaceSubtle else Color(0xFFF8FAFC),
                                        shape = if (isEasyMode) RoundedCornerShape(10.dp) else RoundedCornerShape(0.dp)
                                    )
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text("시간", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(45.dp))
                                Text("날씨", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(60.dp))
                                Text("강우량", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(60.dp))
                                Text("기온", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(45.dp))
                                Text("바람", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Slate600, modifier = Modifier.width(55.dp))
                            }
                            if (!isEasyMode) {
                                HairlineDivider()
                            }

                            weather.hourlyForecast.forEach { item ->
                                val rainColor = if (item.precipitationMm >= 1.0) Color(0xFFDC2626) else Slate600
                                val rainWeight = if (item.precipitationMm >= 1.0) FontWeight.Bold else FontWeight.Normal

                                val tempColor = when {
                                    item.temperature <= 10.0 -> Color(0xFF2563EB)
                                    item.temperature >= 30.0 -> Color(0xFFDC2626)
                                    else -> Color(0xFF0F172A)
                                }
                                val tempWeight = if (item.temperature <= 10.0 || item.temperature >= 30.0) FontWeight.Bold else FontWeight.Normal

                                val windColor = when {
                                    item.windSpeed > 6.5 -> Color(0xFFDC2626)
                                    item.windSpeed >= 3.5 -> Color(0xFF2563EB)
                                    else -> Slate600
                                }
                                val windWeight = if (item.windSpeed >= 3.5) FontWeight.Bold else FontWeight.Normal

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
                                            fontWeight = rainWeight,
                                            color = rainColor
                                        )
                                    }
                                    Row(modifier = Modifier.width(45.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "%.0f°C".format(item.temperature),
                                            fontSize = 11.sp,
                                            fontWeight = tempWeight,
                                            color = tempColor
                                        )
                                    }
                                    Row(modifier = Modifier.width(55.dp), verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            text = "%.1fm/s".format(item.windSpeed),
                                            fontSize = 10.sp,
                                            fontWeight = windWeight,
                                            color = windColor
                                        )
                                    }
                                }
                                HairlineDivider()
                            }
                        }
                    }
                }

                Surface(color = if (isEasyMode) EasyTheme.surface else Color.Transparent) {
                    Box(modifier = Modifier.padding(Spacing.md)) {
                        AutoLoguePrimaryButton(
                            text = "확인",
                            onClick = onDismiss,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
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
    isEasyMode: Boolean = false,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 18.dp else 10.dp),
        color = if (isEasyMode) EasyTheme.surface else bgColor,
        border = if (isEasyMode) null else BorderStroke(1.dp, highlightColor.copy(alpha = 0.25f)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(horizontal = if (isEasyMode) 14.dp else 10.dp, vertical = if (isEasyMode) 12.dp else 9.dp)) {
            Text(
                text = title,
                fontSize = 11.sp,
                color = if (isEasyMode) EasyTheme.textSecondary else Slate600,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = value,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEasyMode) EasyTheme.textPrimary else highlightColor
                )
                if (subtext.isNotBlank()) {
                    Text(
                        text = subtext,
                        fontSize = 10.sp,
                        color = highlightColor,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(bottom = 1.dp)
                    )
                }
            }
        }
    }
}
