package com.autologue.app.presentation.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autologue.app.data.preferences.VehicleMaintenanceConfig
import com.autologue.app.presentation.common.AutoLoguePrimaryButton
import com.autologue.app.presentation.common.AutoLogueSecondaryButton
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter

@Composable
fun ConsumableMaintenanceDialog(
    config: VehicleMaintenanceConfig,
    currentTotalKm: Double,
    onDismiss: () -> Unit,
    onSave: (
        engineOilLastKm: Int,
        engineOilDate: String,
        engineOilInterval: Int,
        airconLastKm: Int,
        airconDate: String,
        airconInterval: Int,
        tireLastKm: Int,
        tireDate: String,
        tireInterval: Int
    ) -> Unit
) {
    val totalKmInt = currentTotalKm.toInt()
    val todayStr = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) }

    var engineOilInterval by remember { mutableStateOf(config.engineOil.intervalKm.toString()) }
    var engineOilLastKm by remember { mutableStateOf(config.engineOil.lastReplacedKm.toString()) }
    var engineOilDate by remember { mutableStateOf(config.engineOil.lastReplacedDate) }

    var airconInterval by remember { mutableStateOf(config.airconFilter.intervalKm.toString()) }
    var airconLastKm by remember { mutableStateOf(config.airconFilter.lastReplacedKm.toString()) }
    var airconDate by remember { mutableStateOf(config.airconFilter.lastReplacedDate) }

    var tireInterval by remember { mutableStateOf(config.tireRotation.intervalKm.toString()) }
    var tireLastKm by remember { mutableStateOf(config.tireRotation.lastReplacedKm.toString()) }
    var tireDate by remember { mutableStateOf(config.tireRotation.lastReplacedDate) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.94f)
                .fillMaxHeight(0.92f),
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
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Build,
                                contentDescription = null,
                                tint = MenuColors.carLedger,
                                modifier = Modifier.size(20.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "소모품 교체 주기 및 이력 설정",
                                style = AppTypography.h2
                            )
                        }
                        Text(
                            text = "현재 총 주행거리: %,d km 기준".format(totalKmInt),
                            style = AppTypography.caption.copy(color = Slate500, fontSize = 11.sp)
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(28.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = Slate400)
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.xs))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.md)
                ) {
                    // 1. 엔진오일 섹션
                    MaintenanceSettingCard(
                        title = "엔진오일",
                        icon = Icons.Default.WaterDrop,
                        iconTint = Color(0xFFD97706),
                        recommendation = "권장: 7,000 ~ 10,000 km (시내/정체 가혹주행 시 5,000~7,000 km) 또는 1년마다 교체",
                        interval = engineOilInterval,
                        onIntervalChange = { engineOilInterval = it },
                        lastKm = engineOilLastKm,
                        onLastKmChange = { engineOilLastKm = it },
                        lastDate = engineOilDate,
                        currentTotalKm = totalKmInt,
                        presets = listOf(5000, 7000, 10000),
                        onQuickReplaceToday = {
                            engineOilLastKm = totalKmInt.toString()
                            engineOilDate = todayStr
                        }
                    )

                    // 2. 에어컨 필터 섹션
                    MaintenanceSettingCard(
                        title = "에어컨 필터 (캐빈 필터)",
                        icon = Icons.Default.Air,
                        iconTint = Color(0xFF0284C7),
                        recommendation = "권장: 5,000 ~ 10,000 km 또는 6개월마다 (봄·가을 환절기 1회 교체 권장)",
                        interval = airconInterval,
                        onIntervalChange = { airconInterval = it },
                        lastKm = airconLastKm,
                        onLastKmChange = { airconLastKm = it },
                        lastDate = airconDate,
                        currentTotalKm = totalKmInt,
                        presets = listOf(5000, 10000, 15000),
                        onQuickReplaceToday = {
                            airconLastKm = totalKmInt.toString()
                            airconDate = todayStr
                        }
                    )

                    // 3. 타이어 위치 교환 섹션
                    MaintenanceSettingCard(
                        title = "타이어 위치 교환 (Tire Rotation)",
                        icon = Icons.Default.Cached,
                        iconTint = Color(0xFF16A34A),
                        recommendation = "권장: 10,000 ~ 15,000 km마다 앞/뒤 위치 맞바꿈 (편마모 방지 및 4개 타이어 수명 연장)",
                        interval = tireInterval,
                        onIntervalChange = { tireInterval = it },
                        lastKm = tireLastKm,
                        onLastKmChange = { tireLastKm = it },
                        lastDate = tireDate,
                        currentTotalKm = totalKmInt,
                        presets = listOf(10000, 15000, 20000),
                        onQuickReplaceToday = {
                            tireLastKm = totalKmInt.toString()
                            tireDate = todayStr
                        }
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.sm))

                // Bottom Actions
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(
                        onClick = {
                            engineOilInterval = "8000"
                            engineOilLastKm = "0"
                            airconInterval = "10000"
                            airconLastKm = "0"
                            tireInterval = "15000"
                            tireLastKm = "0"
                        }
                    ) {
                        Text("기본 추천값으로 초기화", fontSize = 12.sp, color = Slate500)
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                        AutoLogueSecondaryButton(
                            text = "취소",
                            onClick = onDismiss
                        )
                        AutoLoguePrimaryButton(
                            text = "설정 저장",
                            onClick = {
                                val eInterval = engineOilInterval.toIntOrNull()?.coerceAtLeast(1000) ?: 8000
                                val eLastKm = engineOilLastKm.toIntOrNull()?.coerceAtLeast(0) ?: 0
                                val aInterval = airconInterval.toIntOrNull()?.coerceAtLeast(1000) ?: 10000
                                val aLastKm = airconLastKm.toIntOrNull()?.coerceAtLeast(0) ?: 0
                                val tInterval = tireInterval.toIntOrNull()?.coerceAtLeast(1000) ?: 15000
                                val tLastKm = tireLastKm.toIntOrNull()?.coerceAtLeast(0) ?: 0

                                onSave(
                                    eLastKm, engineOilDate, eInterval,
                                    aLastKm, airconDate, aInterval,
                                    tLastKm, tireDate, tInterval
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MaintenanceSettingCard(
    title: String,
    icon: ImageVector,
    iconTint: Color,
    recommendation: String,
    interval: String,
    onIntervalChange: (String) -> Unit,
    lastKm: String,
    onLastKmChange: (String) -> Unit,
    lastDate: String,
    currentTotalKm: Int,
    presets: List<Int>,
    onQuickReplaceToday: () -> Unit
) {
    val intervalVal = interval.toIntOrNull() ?: 10000
    val lastVal = lastKm.toIntOrNull() ?: 0
    val driven = (currentTotalKm - lastVal).coerceAtLeast(0)
    val remaining = (intervalVal - driven).coerceAtLeast(0)

    Surface(
        shape = RoundedCornerShape(12.dp),
        color = Slate50,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            // Card Title + Remaining Badge
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(icon, contentDescription = null, tint = iconTint, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(title, style = AppTypography.h3)
                }

                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = if (remaining <= 1000) Color(0xFFFEE2E2) else Color(0xFFDCFCE7)
                ) {
                    Text(
                        text = "남은 거리: %,d km".format(remaining),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (remaining <= 1000) Color(0xFFDC2626) else Color(0xFF16A34A),
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Recommendation Guide Banner
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = Color(0xFFFFFBEB),
                border = BorderStroke(1.dp, Color(0xFFFDE68A)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = "💡 $recommendation",
                    fontSize = 11.sp,
                    color = Color(0xFF92400E),
                    lineHeight = 15.sp,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Inputs Row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // 교체 주기 입력
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "교체 주기 (km)",
                        style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold, color = Slate700)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = interval,
                        onValueChange = { onIntervalChange(it.filter { ch -> ch.isDigit() }) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppTypography.body.copy(fontSize = 13.sp),
                        trailingIcon = { Text("km", fontSize = 11.sp, color = Slate400, modifier = Modifier.padding(end = 6.dp)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surface,
                            unfocusedContainerColor = AppColors.surface,
                            focusedBorderColor = MenuColors.carLedger,
                            unfocusedBorderColor = Slate300
                        )
                    )
                }

                // 최근 교체 시점 입력
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "최근 교체 시점 (km)",
                        style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold, color = Slate700)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    OutlinedTextField(
                        value = lastKm,
                        onValueChange = { onLastKmChange(it.filter { ch -> ch.isDigit() }) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = AppTypography.body.copy(fontSize = 13.sp),
                        trailingIcon = { Text("km", fontSize = 11.sp, color = Slate400, modifier = Modifier.padding(end = 6.dp)) },
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = AppColors.surface,
                            unfocusedContainerColor = AppColors.surface,
                            focusedBorderColor = MenuColors.carLedger,
                            unfocusedBorderColor = Slate300
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            // Quick Interval Presets + Today Replace Button
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Presets
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    presets.forEach { presetKm ->
                        Surface(
                            onClick = { onIntervalChange(presetKm.toString()) },
                            shape = RoundedCornerShape(4.dp),
                            color = if (interval == presetKm.toString()) MenuColors.carLedgerBg else Slate200,
                            border = BorderStroke(1.dp, if (interval == presetKm.toString()) MenuColors.carLedgerBorder else Color.Transparent)
                        ) {
                            Text(
                                text = "%,dkm".format(presetKm),
                                fontSize = 10.sp,
                                fontWeight = if (interval == presetKm.toString()) FontWeight.Bold else FontWeight.Normal,
                                color = if (interval == presetKm.toString()) MenuColors.carLedger else Slate600,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // One-click Today Replace Button
                Surface(
                    onClick = onQuickReplaceToday,
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                    ) {
                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(12.dp))
                        Spacer(modifier = Modifier.width(3.dp))
                        Text(
                            text = "오늘 교체 완료",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF1D4ED8)
                        )
                    }
                }
            }

            if (lastDate.isNotBlank()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "최근 교체일: $lastDate (마지막 교체 후 %,d km 주행)".format(driven),
                    fontSize = 10.sp,
                    color = Slate500
                )
            }
        }
    }
}
