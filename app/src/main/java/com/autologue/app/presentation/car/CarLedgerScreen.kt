package com.autologue.app.presentation.car

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Business
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.autologue.app.data.preferences.CommuteConfig
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.theme.*
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarLedgerScreen(
    viewModel: CarLedgerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    if (uiState.showLocationDialog) {
        CommuteSettingsDialog(
            config = uiState.commuteConfig,
            onDismiss = { viewModel.closeLocationDialog() },
            onSave = { home, homeAddr, comp, compAddr, dist ->
                viewModel.saveCommuteConfig(home, homeAddr, comp, compAddr, dist)
            }
        )
    }

    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "스마트 차계부",
                            style = AppTypography.h1
                        )
                    },
                    actions = {
                        AutoLogueOutlinedButton(
                            text = "집/회사 설정",
                            icon = Icons.Default.LocationOn,
                            onClick = { viewModel.openLocationDialog() }
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "주유 동기화",
                            icon = Icons.Default.Sync,
                            onClick = { viewModel.manualSyncRefueling() }
                        )
                        Spacer(modifier = Modifier.width(Spacing.md))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.background)
                )
                HairlineDivider()
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxxl)
        ) {
            // Level 1: Natural Metrics Grid
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("평균 연비", style = AppTypography.caption)
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        Text(
                            "%.1f km/L".format(uiState.averageEfficiencyKmPerL),
                            style = AppTypography.display
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("총 주행", style = AppTypography.caption)
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            Text(
                                "%.1f km".format(uiState.totalDrivingDistanceKm),
                                style = AppTypography.h2
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("총 주유비", style = AppTypography.caption)
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            Text(
                                "%,d원".format(uiState.totalFuelExpense),
                                style = AppTypography.h2.copy(color = AppColors.primary)
                            )
                        }
                    }
                }
                HairlineDivider()
            }

            // Consumable Maintenance Reminder Section
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "🔧 차량 소모품 교체 주기 알림",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "주행 %d km 기준".format(uiState.totalDrivingDistanceKm.toInt()),
                            style = AppTypography.captionMuted
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MaintenanceItemCard(
                            title = "엔진오일",
                            intervalKm = 7000,
                            currentKm = (uiState.totalDrivingDistanceKm % 7000).toInt(),
                            modifier = Modifier.weight(1f)
                        )
                        MaintenanceItemCard(
                            title = "에어컨 필터",
                            intervalKm = 10000,
                            currentKm = (uiState.totalDrivingDistanceKm % 10000).toInt(),
                            modifier = Modifier.weight(1f)
                        )
                        MaintenanceItemCard(
                            title = "타이어 위치",
                            intervalKm = 20000,
                            currentKm = (uiState.totalDrivingDistanceKm % 20000).toInt(),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
                HairlineDivider()
            }

            // Feed Header
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "운행 및 주유 기록",
                        style = AppTypography.caption
                    )
                    Text(
                        text = "${uiState.logs.size}건",
                        style = AppTypography.captionMuted
                    )
                }
                HairlineDivider()
            }

            if (uiState.logs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xxxl),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("기록된 차량 운행 및 주유 내역이 없습니다.", style = AppTypography.bodySecondary)
                    }
                }
            } else {
                items(uiState.logs) { log ->
                    SaaSCarLogRow(log = log)
                    HairlineDivider()
                }
            }
        }
    }
}

@Composable
fun SaaSCarLogRow(log: VehicleLog) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isCommute = log.note?.contains("출퇴근") == true
        val isGolf = log.note?.contains("골프") == true || log.note?.contains("라운딩") == true || log.note?.contains("CC") == true

        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = if (log.logType == VehicleLogType.REFUELING) (log.gasStationName ?: "주유") else (log.note ?: "주행 완료"),
                    style = AppTypography.h2
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                MetricBadge(
                    text = when {
                        log.logType == VehicleLogType.REFUELING -> "주유"
                        isCommute -> "출퇴근"
                        isGolf -> "골프주행"
                        else -> "주행"
                    },
                    textColor = when {
                        log.logType == VehicleLogType.REFUELING -> Amber700
                        isCommute -> Emerald700
                        isGolf -> Indigo700
                        else -> Slate700
                    },
                    backgroundColor = when {
                        log.logType == VehicleLogType.REFUELING -> Amber50
                        isCommute -> Emerald50
                        isGolf -> Indigo50
                        else -> Slate100
                    }
                )
            }
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                Text(
                    text = log.timestamp.format(DateTimeFormatter.ofPattern("M.d(E) HH:mm", Locale.KOREA)),
                    style = AppTypography.captionMuted
                )
                if (log.daysSinceLastFuel != null && log.daysSinceLastFuel > 0) {
                    Text("·", style = AppTypography.captionMuted)
                    Text(
                        text = "${log.daysSinceLastFuel}일 만에 주유",
                        style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        }

        if (log.logType == VehicleLogType.REFUELING) {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%,d원".format(log.fuelCost),
                    style = AppTypography.h2.copy(fontWeight = FontWeight.Bold)
                )
                if (log.fuelAmountLiters > 0) {
                    Text("%.1f L".format(log.fuelAmountLiters), style = AppTypography.captionMuted)
                }
            }
        } else {
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    text = "%.1f km".format(log.tripDistanceKm),
                    style = AppTypography.h2.copy(color = Slate800, fontWeight = FontWeight.SemiBold)
                )
                Text(
                    text = when {
                        isCommute -> "출퇴근 왕복"
                        isGolf -> "골프장 왕복"
                        else -> "왕복 주행"
                    },
                    style = AppTypography.captionMuted
                )
            }
        }
    }
}

@Composable
private fun MaintenanceItemCard(
    title: String,
    intervalKm: Int,
    currentKm: Int,
    modifier: Modifier = Modifier
) {
    val remainingKm = (intervalKm - currentKm).coerceAtLeast(0)
    val ratio = (currentKm.toFloat() / intervalKm).coerceIn(0f, 1f)
    val statusColor = when {
        ratio >= 0.9f -> androidx.compose.ui.graphics.Color(0xFFEF4444)
        ratio >= 0.75f -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        else -> androidx.compose.ui.graphics.Color(0xFF10B981)
    }

    androidx.compose.material3.Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
        color = androidx.compose.ui.graphics.Color(0xFFF8FAFC),
        border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFE2E8F0)),
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = androidx.compose.ui.graphics.Color(0xFF1E293B)
                )
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(statusColor)
                )
            }
            Spacer(modifier = Modifier.height(4.dp))
            LinearProgressIndicator(
                progress = { ratio },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(4.dp)
                    .clip(androidx.compose.foundation.shape.RoundedCornerShape(2.dp)),
                color = statusColor,
                trackColor = androidx.compose.ui.graphics.Color(0xFFE2E8F0)
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "${remainingKm}km",
                fontSize = 11.sp,
                fontWeight = FontWeight.SemiBold,
                color = androidx.compose.ui.graphics.Color(0xFF0F172A)
            )
            Text(
                text = "남음",
                fontSize = 9.sp,
                color = androidx.compose.ui.graphics.Color(0xFF64748B)
            )
        }
    }
}

@Composable
fun CommuteSettingsDialog(
    config: CommuteConfig,
    onDismiss: () -> Unit,
    onSave: (homeName: String, homeAddr: String, compName: String, compAddr: String, dist: Double) -> Unit
) {
    var homeLocationName by remember(config) { mutableStateOf(config.homeName) }
    var homeAddress by remember(config) { mutableStateOf(config.homeAddress) }
    var companyLocationName by remember(config) { mutableStateOf(config.companyName) }
    var companyAddress by remember(config) { mutableStateOf(config.companyAddress) }
    var distanceKmText by remember(config) { mutableStateOf(config.commuteRoundTripKm.toString()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = AppColors.surface,
            shadowElevation = 8.dp,
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .wrapContentHeight()
        ) {
            Column(
                modifier = Modifier
                    .padding(Spacing.lg)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = AppColors.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(
                        text = "출퇴근 거점 및 경로 설정",
                        style = AppTypography.h2
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))
                Text(
                    text = "우리집과 회사 거점을 등록하면 평일 주행 시 골프장 오탐 없이 정확한 출퇴근 경로로 자동 정제 및 분류됩니다.",
                    style = AppTypography.caption.copy(color = Slate500)
                )

                Spacer(modifier = Modifier.height(Spacing.md))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.md))

                // 집 설정
                Text(
                    text = "우리집 설정",
                    style = AppTypography.h3.copy(fontWeight = FontWeight.Bold, color = Slate800)
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                AutoLogueTextField(
                    value = homeLocationName,
                    onValueChange = { homeLocationName = it },
                    label = "집 거점 명칭",
                    placeholder = "예: 서울 방이동, 판교 푸르지오 등",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                AutoLogueTextField(
                    value = homeAddress,
                    onValueChange = { homeAddress = it },
                    label = "집 상세 주소 (선택)",
                    placeholder = "예: 서울특별시 송파구 위례성대로...",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(Spacing.md))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.md))

                // 회사 설정
                Text(
                    text = "직장 / 회사 설정",
                    style = AppTypography.h3.copy(fontWeight = FontWeight.Bold, color = Slate800)
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                AutoLogueTextField(
                    value = companyLocationName,
                    onValueChange = { companyLocationName = it },
                    label = "회사 거점 명칭",
                    placeholder = "예: 판교 테크노밸리, 강남파이낸스센터 등",
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                AutoLogueTextField(
                    value = companyAddress,
                    onValueChange = { companyAddress = it },
                    label = "회사 상세 주소 (선택)",
                    placeholder = "예: 경기도 성남시 분당구 판교역로...",
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(Spacing.md))
                HairlineDivider()
                Spacer(modifier = Modifier.height(Spacing.md))

                // 출퇴근 왕복 거리 설정
                Text(
                    text = "출퇴근 왕복 주행거리",
                    style = AppTypography.h3.copy(fontWeight = FontWeight.Bold, color = Slate800)
                )
                Spacer(modifier = Modifier.height(Spacing.xs))
                AutoLogueTextField(
                    value = distanceKmText,
                    onValueChange = { distanceKmText = it },
                    label = "왕복 기준 거리 (km)",
                    placeholder = "37.0",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(Spacing.xl))

                // 액션 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutoLogueSecondaryButton(
                        text = "취소",
                        onClick = onDismiss
                    )
                    Spacer(modifier = Modifier.width(Spacing.sm))
                    AutoLoguePrimaryButton(
                        text = "저장 및 동기화",
                        onClick = {
                            val dist = distanceKmText.toDoubleOrNull() ?: 37.0
                            onSave(
                                homeLocationName.trim(),
                                homeAddress.trim(),
                                companyLocationName.trim(),
                                companyAddress.trim(),
                                dist
                            )
                        }
                    )
                }
            }
        }
    }
}
