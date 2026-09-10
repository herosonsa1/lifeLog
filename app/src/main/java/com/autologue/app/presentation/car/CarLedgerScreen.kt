package com.autologue.app.presentation.car

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
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
import com.autologue.app.data.preferences.VehicleProfile
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.getAssignedVehicleId
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
        CommuteMapPickerDialog(
            config = uiState.commuteConfig,
            onDismiss = { viewModel.closeLocationDialog() },
            onSave = { home, homeAddr, homeLat, homeLng, comp, compAddr, compLat, compLng, dist, bt ->
                viewModel.saveCommuteConfig(
                    homeName = home,
                    homeAddress = homeAddr,
                    homeLat = homeLat,
                    homeLng = homeLng,
                    companyName = comp,
                    companyAddress = compAddr,
                    companyLat = compLat,
                    companyLng = compLng,
                    roundTripKm = dist,
                    carBt = bt
                )
            }
        )
    }

    if (uiState.showMaintenanceDialog) {
        ConsumableMaintenanceDialog(
            config = uiState.maintenanceConfig,
            currentTotalKm = uiState.totalDrivingDistanceKm,
            onDismiss = { viewModel.closeMaintenanceDialog() },
            onSave = { eKm, eDate, eInt, aKm, aDate, aInt, tKm, tDate, tInt ->
                viewModel.updateMaintenanceConfig(
                    engineOilLastKm = eKm,
                    engineOilDate = eDate,
                    engineOilInterval = eInt,
                    airconLastKm = aKm,
                    airconDate = aDate,
                    airconInterval = aInt,
                    tireLastKm = tKm,
                    tireDate = tDate,
                    tireInterval = tInt
                )
            }
        )
    }

    if (uiState.showVehicleManageDialog) {
        VehicleManageDialog(
            vehicles = uiState.vehicles,
            onDismiss = { viewModel.closeVehicleManageDialog() },
            onSaveVehicles = { car1, car2 ->
                viewModel.saveVehicleProfiles(car1, car2)
            }
        )
    }

    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                TopMenuAccentBar(color = MenuColors.carLedger)
                TopAppBar(
                    windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                    title = {
                        Column {
                            Text(
                                text = "스마트 차계부",
                                style = AppTypography.h2
                            )
                            Text(
                                text = "차량·주유 관리",
                                style = AppTypography.caption.copy(
                                    color = MenuColors.carLedger,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    },
                    actions = {
                        AutoLogueOutlinedButton(
                            text = "지도 거점 설정",
                            icon = Icons.Default.LocationOn,
                            onClick = { viewModel.openLocationDialog() },
                            contentColor = MenuColors.carLedger,
                            containerColor = MenuColors.carLedgerBg,
                            borderColor = MenuColors.carLedgerBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "주유 동기화",
                            icon = Icons.Default.Sync,
                            onClick = { viewModel.manualSyncRefueling() },
                            contentColor = MenuColors.carLedger,
                            containerColor = MenuColors.carLedgerBg,
                            borderColor = MenuColors.carLedgerBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        IconButton(onClick = { viewModel.clearAllDummyLogs() }) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "가상 주행기록 삭제",
                                tint = Slate400
                            )
                        }
                        Spacer(modifier = Modifier.width(Spacing.xs))
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
            // 다중 차량 (2대) 선택 탭 바
            item {
                VehicleSelectorBar(
                    vehicles = uiState.vehicles,
                    selectedVehicleId = uiState.selectedVehicleId,
                    onSelectVehicle = { viewModel.selectVehicle(it) },
                    onManageVehicles = { viewModel.openVehicleManageDialog() }
                )
                HairlineDivider()
            }

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

            // Commute Location & Quick Action Card
            item {
                if (!uiState.commuteConfig.isConfigured) {
                    Card(
                        shape = RoundedCornerShape(12.dp),
                        colors = CardDefaults.cardColors(containerColor = Amber50),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.sm)
                            .clickable { viewModel.openLocationDialog() }
                    ) {
                        Row(
                            modifier = Modifier.padding(Spacing.md),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(Icons.Default.AddLocationAlt, contentDescription = null, tint = MenuColors.carLedger)
                            Spacer(modifier = Modifier.width(Spacing.sm))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = "우리집 & 회사 거점 미등록",
                                    style = AppTypography.body.copy(fontWeight = FontWeight.Bold, color = Slate800)
                                )
                                Text(
                                    text = "지도를 열어 집과 회사를 지정하면 출퇴근 경로와 거리가 자동 산출됩니다.",
                                    style = AppTypography.captionMuted
                                )
                            }
                            Icon(Icons.Default.ChevronRight, contentDescription = null, tint = Slate400)
                        }
                    }
                } else {
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
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = "🚗 출퇴근 경로",
                                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "${uiState.commuteConfig.homeName} ↔ ${uiState.commuteConfig.companyName}",
                                    style = AppTypography.caption.copy(color = MenuColors.carLedger, fontWeight = FontWeight.Bold)
                                )
                            }
                            Text(
                                text = "편도 %.1fkm · 왕복 %.1fkm".format(uiState.commuteConfig.commuteOneWayKm, uiState.commuteConfig.commuteRoundTripKm),
                                style = AppTypography.captionMuted
                            )
                        }
                        if (uiState.commuteConfig.carBluetoothDevice.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "차량 블루투스 연동: ${uiState.commuteConfig.carBluetoothDevice} (탑승 시 자동 판별)",
                                style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            AutoLoguePrimaryButton(
                                text = "오늘 출근 기록",
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.recordManualCommute(isToWork = true) }
                            )
                            AutoLogueSecondaryButton(
                                text = "오늘 퇴근 기록",
                                modifier = Modifier.weight(1f),
                                onClick = { viewModel.recordManualCommute(isToWork = false) }
                            )
                        }
                    }
                }
                HairlineDivider()
            }

            // Consumable Maintenance Reminder Section
            item {
                val mCfg = uiState.maintenanceConfig
                val currentKm = uiState.totalDrivingDistanceKm

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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = "🔧 차량 소모품 교체 주기 알림",
                                style = AppTypography.caption.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "(주행 %,d km 기준)".format(currentKm.toInt()),
                                style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                            )
                        }

                        // 설정 버튼
                        androidx.compose.material3.Surface(
                            onClick = { viewModel.openMaintenanceDialog() },
                            shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
                            color = MenuColors.carLedgerBg,
                            border = androidx.compose.foundation.BorderStroke(1.dp, MenuColors.carLedgerBorder)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            ) {
                                Icon(
                                    Icons.Default.Settings,
                                    contentDescription = "교체 주기 설정",
                                    tint = MenuColors.carLedger,
                                    modifier = Modifier.size(12.dp)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = "주기/교체 설정",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = MenuColors.carLedger
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        MaintenanceItemCard(
                            title = "엔진오일",
                            remainingKm = mCfg.engineOil.getRemainingKm(currentKm),
                            ratio = mCfg.engineOil.getProgressRatio(currentKm),
                            recommendedIntervalKm = mCfg.engineOil.recommendedIntervalKm,
                            lastReplacedKm = mCfg.engineOil.lastReplacedKm,
                            lastDate = mCfg.engineOil.lastReplacedDate,
                            onClick = { viewModel.openMaintenanceDialog() },
                            modifier = Modifier.weight(1f)
                        )
                        MaintenanceItemCard(
                            title = "에어컨 필터",
                            remainingKm = mCfg.airconFilter.getRemainingKm(currentKm),
                            ratio = mCfg.airconFilter.getProgressRatio(currentKm),
                            recommendedIntervalKm = mCfg.airconFilter.recommendedIntervalKm,
                            lastReplacedKm = mCfg.airconFilter.lastReplacedKm,
                            lastDate = mCfg.airconFilter.lastReplacedDate,
                            onClick = { viewModel.openMaintenanceDialog() },
                            modifier = Modifier.weight(1f)
                        )
                        MaintenanceItemCard(
                            title = "타이어 위치 교환",
                            subLabel = "앞↔뒤 편마모 방지",
                            remainingKm = mCfg.tireRotation.getRemainingKm(currentKm),
                            ratio = mCfg.tireRotation.getProgressRatio(currentKm),
                            recommendedIntervalKm = mCfg.tireRotation.recommendedIntervalKm,
                            lastReplacedKm = mCfg.tireRotation.lastReplacedKm,
                            lastDate = mCfg.tireRotation.lastReplacedDate,
                            onClick = { viewModel.openMaintenanceDialog() },
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
                    SaaSCarLogRow(
                        log = log,
                        vehicles = uiState.vehicles,
                        onAssignVehicle = { logId, vId -> viewModel.assignVehicleToFuelLog(logId, vId) }
                    )
                    HairlineDivider()
                }
            }
        }
    }
}

@Composable
fun SaaSCarLogRow(
    log: VehicleLog,
    vehicles: List<VehicleProfile> = emptyList(),
    onAssignVehicle: ((Long, String) -> Unit)? = null
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isCommute = log.note?.contains("출퇴근") == true
        val isGolf = log.note?.contains("골프") == true || log.note?.contains("라운딩") == true || log.note?.contains("CC") == true
        val carTag = Regex("\\[(.*?)\\]").find(log.note ?: "")?.groupValues?.get(1)?.split(" ")?.firstOrNull()

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
                if (carTag != null) {
                    val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(log.note)
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    MetricBadge(
                        text = "$brandEmoji $carTag",
                        textColor = Indigo700,
                        backgroundColor = Indigo50
                    )
                }
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
            if (log.logType == VehicleLogType.REFUELING && vehicles.isNotEmpty() && onAssignVehicle != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    val assignedId = log.getAssignedVehicleId()
                    vehicles.forEach { v ->
                        val isSelected = assignedId == v.id
                        val shortName = v.name.split(" ").firstOrNull() ?: v.name
                        androidx.compose.material3.Surface(
                            onClick = { onAssignVehicle(log.id, v.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) MenuColors.carLedgerBg else Slate100,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) MenuColors.carLedger else Slate300
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                if (isSelected) {
                                    Icon(
                                        Icons.Default.Check,
                                        contentDescription = null,
                                        tint = MenuColors.carLedger,
                                        modifier = Modifier.size(10.dp)
                                    )
                                    Spacer(modifier = Modifier.width(3.dp))
                                }
                                Text(
                                    text = shortName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelected) MenuColors.carLedger else Slate600
                                )
                            }
                        }
                    }
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
    subLabel: String? = null,
    remainingKm: Int,
    ratio: Float,
    recommendedIntervalKm: Int,
    lastReplacedKm: Int,
    lastDate: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        remainingKm <= 1000 || ratio >= 0.9f -> androidx.compose.ui.graphics.Color(0xFFEF4444)
        remainingKm <= 2500 || ratio >= 0.75f -> androidx.compose.ui.graphics.Color(0xFFF59E0B)
        else -> androidx.compose.ui.graphics.Color(0xFF10B981)
    }

    androidx.compose.material3.Surface(
        onClick = onClick,
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
                Column {
                    Text(
                        text = title,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFF1E293B)
                    )
                    if (subLabel != null) {
                        Text(
                            text = subLabel,
                            fontSize = 8.5.sp,
                            color = Slate500
                        )
                    }
                }
                Box(
                    modifier = Modifier
                        .size(7.dp)
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

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Bottom
            ) {
                Column {
                    Text(
                        text = "%,dkm".format(remainingKm),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = if (remainingKm <= 1000) androidx.compose.ui.graphics.Color(0xFFDC2626) else androidx.compose.ui.graphics.Color(0xFF0F172A)
                    )
                    Text(
                        text = "남음",
                        fontSize = 9.sp,
                        color = androidx.compose.ui.graphics.Color(0xFF64748B)
                    )
                }

                androidx.compose.material3.Surface(
                    shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                    color = androidx.compose.ui.graphics.Color(0xFFF1F5F9)
                ) {
                    Text(
                        text = "권장 %,dkm".format(recommendedIntervalKm),
                        fontSize = 8.sp,
                        color = Slate600,
                        modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                    )
                }
            }
        }
    }
}

