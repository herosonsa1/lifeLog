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
import com.autologue.app.domain.model.*
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.theme.*
import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarLedgerScreen(
    viewModel: CarLedgerViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    var hasBgLocationPerm by remember {
        mutableStateOf(
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
            } else true
        )
    }

    val bgLocationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasBgLocationPerm = granted || (
            android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.ACCESS_BACKGROUND_LOCATION
            ) == PackageManager.PERMISSION_GRANTED
        )
        if (!hasBgLocationPerm) {
            runCatching {
                val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.fromParts("package", context.packageName, null)
                }
                context.startActivity(intent)
            }
        }
    }

    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val granted = results[Manifest.permission.READ_SMS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        if (granted) {
            viewModel.manualSyncRefueling()
        } else {
            viewModel.showPermissionDeniedMessage()
        }
    }

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

    if (uiState.editingRefuelLog != null) {
        RefuelDetailEditDialog(
            log = uiState.editingRefuelLog!!,
            vehicles = uiState.vehicles,
            onDismiss = { viewModel.closeRefuelEditDialog() },
            onSave = { logId, stationName, vId, cost, liters, unitPrice, isOneTimeSave ->
                viewModel.updateRefuelDetail(logId, stationName, vId, cost, liters, unitPrice, isOneTimeSave)
            },
            onDelete = { logId ->
                viewModel.deleteSingleLog(logId)
            }
        )
    }

    if (uiState.showAddRefuelDialog) {
        AddRefuelLogDialog(
            vehicles = uiState.vehicles,
            selectedVehicleId = uiState.selectedVehicleId,
            onDismiss = { viewModel.closeAddRefuelDialog() },
            onSave = { vId, station, cost, liters, unitPrice, dateTime ->
                viewModel.addManualRefuelLog(vId, station, cost, liters, unitPrice, dateTime)
            }
        )
    }

    if (uiState.showPasteSmsDialog) {
        PasteSmsDialog(
            onDismiss = { viewModel.closePasteSmsDialog() },
            onConfirm = { transactions ->
                viewModel.importPastedTransactions(transactions)
            }
        )
    }

    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.syncResultMessage) {
        val msg = uiState.syncResultMessage
        if (!msg.isNullOrBlank()) {
            if (msg.contains("권한")) {
                val result = snackbarHostState.showSnackbar(
                    message = msg,
                    actionLabel = "설정 이동",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.fromParts("package", context.packageName, null)
                    }
                    context.startActivity(intent)
                }
            } else if (msg.contains("문자 붙여넣기")) {
                val result = snackbarHostState.showSnackbar(
                    message = msg,
                    actionLabel = "문자 붙여넣기",
                    duration = SnackbarDuration.Long
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.openPasteSmsDialog()
                }
            } else {
                snackbarHostState.showSnackbar(msg)
            }
            viewModel.dismissSyncResultMessage()
        }
    }

    var isMoreMenuExpanded by remember { mutableStateOf(false) }

    // 차계부 메인 탭 (운행·주유 기록 / 주행·유지비 통계) 및 기간별 분석 상태
    var selectedMainTab by remember { mutableStateOf(CarLedgerPageTab.RECORDS) }
    var carAnalyticsUnit by remember {
        mutableStateOf(com.autologue.app.presentation.expense.AnalyticsPeriodUnit.MONTHLY)
    }
    var carAnalyticsPeriod by remember {
        mutableStateOf(
            com.autologue.app.presentation.expense.PeriodSpec.fromDate(
                com.autologue.app.presentation.expense.AnalyticsPeriodUnit.MONTHLY,
                java.time.LocalDate.now()
            )
        )
    }
    var carAnalyticsBasis by remember {
        mutableStateOf(com.autologue.app.presentation.expense.AnalyticsComparisonBasis.PREVIOUS_PERIOD)
    }
    var carAnalyticsSubTab by remember { mutableStateOf(CarLedgerAnalyticsSubTab.OVERVIEW_INSIGHT) }
    var hasSyncedCarInitialPeriod by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.logs.size) {
        if (!hasSyncedCarInitialPeriod && uiState.logs.isNotEmpty()) {
            val latestDate = uiState.logs.maxOf { it.timestamp.toLocalDate() }
            carAnalyticsPeriod = com.autologue.app.presentation.expense.PeriodSpec.fromDate(
                carAnalyticsUnit,
                latestDate
            )
            hasSyncedCarInitialPeriod = true
        }
    }

    val triggerSmsSync = {
        val hasPerm = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_SMS
        ) == PackageManager.PERMISSION_GRANTED
        if (hasPerm) {
            viewModel.manualSyncRefueling()
        } else {
            smsPermissionLauncher.launch(
                arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
            )
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = AppColors.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopMenuAccentBar(color = MenuColors.carLedger)
                AutoLogueCompactTopBar(
                    title = {
                        Column {
                            Text(
                                text = "스마트 차계부",
                                style = AppTypography.h2,
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                            Text(
                                text = "차량·주유 관리",
                                style = AppTypography.caption.copy(
                                    color = MenuColors.carLedger,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                ),
                                maxLines = 1,
                                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                            )
                        }
                    },
                    actions = {
                        AutoLogueOutlinedButton(
                            text = "주유 등록",
                            icon = Icons.Default.Add,
                            onClick = { viewModel.openAddRefuelDialog() },
                            contentColor = MenuColors.carLedger,
                            containerColor = MenuColors.carLedgerBg,
                            borderColor = MenuColors.carLedgerBorder
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        AutoLogueOutlinedButton(
                            text = "주유 동기화",
                            icon = Icons.Default.Sync,
                            onClick = triggerSmsSync,
                            contentColor = MenuColors.carLedger,
                            containerColor = MenuColors.carLedgerBg,
                            borderColor = MenuColors.carLedgerBorder
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Box {
                            Surface(
                                onClick = { isMoreMenuExpanded = true },
                                shape = RoundedCornerShape(8.dp),
                                color = MenuColors.carLedgerBg,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MenuColors.carLedgerBorder),
                                modifier = Modifier.size(32.dp)
                            ) {
                                Box(contentAlignment = Alignment.Center) {
                                    Icon(
                                        imageVector = Icons.Default.MoreVert,
                                        contentDescription = "차계부 기능 더보기",
                                        tint = MenuColors.carLedger,
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            DropdownMenu(
                                expanded = isMoreMenuExpanded,
                                onDismissRequest = { isMoreMenuExpanded = false },
                                modifier = Modifier
                                    .widthIn(min = 230.dp)
                                    .background(AppColors.surface)
                            ) {
                                Text(
                                    text = "차계부 전체 기능",
                                    style = AppTypography.caption.copy(
                                        color = Slate500,
                                        fontWeight = FontWeight.Bold,
                                        fontSize = 11.sp
                                    ),
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)
                                )
                                HorizontalDivider(color = Slate100)

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("문자 복사·붙여넣기", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Slate900)
                                            Text("카드/은행 주유 문자 직접 붙여넣기", fontSize = 11.sp, color = Slate500)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.ContentPaste, contentDescription = null, tint = MenuColors.carLedger, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        viewModel.openPasteSmsDialog()
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("출퇴근 지도 거점 설정", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Slate900)
                                            Text("집·회사 위치 및 차량 블루투스 연동", fontSize = 11.sp, color = Slate500)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.LocationOn, contentDescription = null, tint = MenuColors.carLedger, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        viewModel.openLocationDialog()
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("차량 프로필 관리", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Slate900)
                                            Text("차량 이름, 유종, 기준 유가 설정", fontSize = 11.sp, color = Slate500)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.DirectionsCar, contentDescription = null, tint = MenuColors.carLedger, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        viewModel.openVehicleManageDialog()
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Column {
                                            Text("소모품 교체 주기 설정", fontWeight = FontWeight.SemiBold, fontSize = 13.5.sp, color = Slate900)
                                            Text("엔진오일·에어컨 필터·타이어 주기", fontSize = 11.sp, color = Slate500)
                                        }
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Settings, contentDescription = null, tint = MenuColors.carLedger, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        viewModel.openMaintenanceDialog()
                                    }
                                )

                                HorizontalDivider(color = Slate100)

                                DropdownMenuItem(
                                    text = {
                                        Text("주유·정비 직접 등록", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Slate800)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Add, contentDescription = null, tint = Slate600, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        viewModel.openAddRefuelDialog()
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text("주유 문자 자동 동기화", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = Slate800)
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.Sync, contentDescription = null, tint = Slate600, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        triggerSmsSync()
                                    }
                                )

                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            if (uiState.isSelectionMode) "선택 삭제 모드 해제" else "기록 선택 삭제 모드",
                                            fontWeight = FontWeight.Medium,
                                            fontSize = 13.sp,
                                            color = Slate800
                                        )
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Slate600, modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        viewModel.toggleSelectionMode()
                                    }
                                )

                                HorizontalDivider(color = Slate100)

                                DropdownMenuItem(
                                    text = {
                                        Text("가상 주행기록 초기화", fontWeight = FontWeight.Medium, fontSize = 13.sp, color = androidx.compose.ui.graphics.Color(0xFFDC2626))
                                    },
                                    leadingIcon = {
                                        Icon(Icons.Default.DeleteOutline, contentDescription = null, tint = androidx.compose.ui.graphics.Color(0xFFDC2626), modifier = Modifier.size(18.dp))
                                    },
                                    onClick = {
                                        isMoreMenuExpanded = false
                                        viewModel.clearAllDummyLogs()
                                    }
                                )
                            }
                        }
                    },
                    containerColor = AppColors.background
                )
                HairlineDivider()
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
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

                // 차계부 메인 탭 바: [운행 · 주유 기록] | [주행 · 유지비 통계]
                item {
                    Surface(
                        color = Slate50,
                        shape = RoundedCornerShape(10.dp),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 8.dp)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(3.dp),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            CarLedgerPageTab.values().forEach { tab ->
                                val isSelected = selectedMainTab == tab
                                Surface(
                                    onClick = { selectedMainTab = tab },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) androidx.compose.ui.graphics.Color.White else androidx.compose.ui.graphics.Color.Transparent,
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, MenuColors.carLedgerBorder) else null,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = tab.label,
                                            fontSize = 12.sp,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isSelected) MenuColors.carLedger else Slate600
                                        )
                                    }
                                }
                            }
                        }
                    }
                    HairlineDivider()
                }

                if (selectedMainTab == CarLedgerPageTab.STATISTICS) {
                    item {
                        val carReport = remember(
                            uiState.logs,
                            carAnalyticsUnit,
                            carAnalyticsPeriod,
                            carAnalyticsBasis
                        ) {
                            CarLedgerAnalyticsEngine.buildReport(
                                logs = uiState.logs,
                                unit = carAnalyticsUnit,
                                targetPeriod = carAnalyticsPeriod,
                                requestedBasis = carAnalyticsBasis
                            )
                        }

                        CarLedgerAnalyticsSection(
                            report = carReport,
                            selectedSubTab = carAnalyticsSubTab,
                            onSelectSubTab = { carAnalyticsSubTab = it },
                            onSelectPeriodUnit = { newUnit ->
                                carAnalyticsUnit = newUnit
                                val anchorDate = uiState.logs.maxOfOrNull { it.timestamp.toLocalDate() }
                                    ?: java.time.LocalDate.now()
                                carAnalyticsPeriod = com.autologue.app.presentation.expense.PeriodSpec.fromDate(
                                    newUnit,
                                    anchorDate
                                )
                            },
                            onPreviousPeriod = {
                                carAnalyticsPeriod = carAnalyticsPeriod.previousSequential()
                            },
                            onNextPeriod = {
                                carAnalyticsPeriod = carAnalyticsPeriod.nextSequential()
                            },
                            onSelectComparisonBasis = { carAnalyticsBasis = it },
                            onJumpToLogTabWithFilter = { mode ->
                                viewModel.setLogFilterMode(mode)
                                selectedMainTab = CarLedgerPageTab.RECORDS
                            }
                        )
                    }
                } else {
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("평균 연비", style = AppTypography.caption)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                "%,d원/L 기준".format(uiState.selectedVehicleDefaultGasPrice.toInt()),
                                style = AppTypography.captionMuted.copy(fontSize = 10.sp)
                            )
                        }
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
                        if (!hasBgLocationPerm && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                            Spacer(modifier = Modifier.height(4.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Amber50,
                                border = androidx.compose.foundation.BorderStroke(1.dp, MenuColors.carLedgerBorder),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        bgLocationPermissionLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                                    }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "화면 꺼짐 시 실시간 GPS 수집을 위해 위치 권한을 [항상 허용]으로 설정해주세요.",
                                        style = AppTypography.caption.copy(color = Slate800, fontSize = 11.sp),
                                        modifier = Modifier.weight(1f)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "항상 허용 켜기",
                                        style = AppTypography.caption.copy(
                                            color = MenuColors.carLedger,
                                            fontWeight = FontWeight.Bold,
                                            fontSize = 11.sp
                                        )
                                    )
                                }
                            }
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
                            subLabel = "엔진 윤활·보호",
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
                            subLabel = "실내 공기질 관리",
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

            // Feed Header & Segmented Filter Tab Bar
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val currentVehicleName = uiState.vehicles.find { it.id == uiState.selectedVehicleId }?.name?.split(" ")?.firstOrNull() ?: "차량"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = if (uiState.filterCurrentVehicleOnly) "$currentVehicleName 기록" else "전체 차량 기록",
                                style = AppTypography.caption.copy(fontWeight = FontWeight.Bold)
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(
                                text = "${uiState.filteredLogs.size}건",
                                style = AppTypography.captionMuted
                            )
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            androidx.compose.material3.Surface(
                                onClick = { viewModel.toggleSelectionMode() },
                                shape = RoundedCornerShape(6.dp),
                                color = if (uiState.isSelectionMode) androidx.compose.ui.graphics.Color(0xFFFEF2F2) else Slate100,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (uiState.isSelectionMode) androidx.compose.ui.graphics.Color(0xFFDC2626) else Slate300
                                )
                            ) {
                                Text(
                                    text = if (uiState.isSelectionMode) "선택 해제" else "선택 삭제",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.isSelectionMode) androidx.compose.ui.graphics.Color(0xFFDC2626) else Slate700,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }

                            androidx.compose.material3.Surface(
                                onClick = { viewModel.toggleVehicleFilter() },
                                shape = RoundedCornerShape(6.dp),
                                color = if (uiState.filterCurrentVehicleOnly) MenuColors.carLedgerBg else Slate100,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (uiState.filterCurrentVehicleOnly) MenuColors.carLedgerBorder else Slate300
                                )
                            ) {
                                Text(
                                    text = if (uiState.filterCurrentVehicleOnly) "현재 차량만" else "전체 차량",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Medium,
                                    color = if (uiState.filterCurrentVehicleOnly) MenuColors.carLedger else Slate600,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                    }

                    if (uiState.isSelectionMode) {
                        androidx.compose.material3.Surface(
                            color = androidx.compose.ui.graphics.Color(0xFFFEF2F2),
                            border = androidx.compose.foundation.BorderStroke(1.dp, androidx.compose.ui.graphics.Color(0xFFFECACA)),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.xl, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 10.dp, vertical = 6.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    androidx.compose.material3.Surface(
                                        onClick = { viewModel.selectAllVisibleLogs() },
                                        shape = RoundedCornerShape(6.dp),
                                        color = androidx.compose.ui.graphics.Color.White,
                                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate300)
                                    ) {
                                        Text(
                                            text = "전체 선택",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = Slate700,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Text(
                                        text = "${uiState.selectedLogIds.size}건 선택됨 (재동기화 차단)",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color(0xFFB91C1C)
                                    )
                                }

                                androidx.compose.material3.Surface(
                                    onClick = { viewModel.deleteSelectedLogs() },
                                    enabled = uiState.selectedLogIds.isNotEmpty(),
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (uiState.selectedLogIds.isNotEmpty()) androidx.compose.ui.graphics.Color(0xFFDC2626) else Slate300
                                ) {
                                    Text(
                                        text = "${uiState.selectedLogIds.size}건 영구 삭제",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = androidx.compose.ui.graphics.Color.White,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }
                    }

                    // [세련된 필터 탭 바] 전체 / ⛽ 주유 기록 / 🚗 주행 기록
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        val filterOptions = listOf(
                            Triple(LogFilterMode.ALL, "전체", uiState.logs.size),
                            Triple(LogFilterMode.REFUEL_ONLY, "⛽ 주유/정비", uiState.totalRefuelCount),
                            Triple(LogFilterMode.DRIVING_ONLY, "🚗 주행", uiState.totalDrivingCount)
                        )
                        filterOptions.forEach { (mode, label, count) ->
                            val isSelected = uiState.logFilterMode == mode
                            androidx.compose.material3.Surface(
                                onClick = { viewModel.setLogFilterMode(mode) },
                                shape = RoundedCornerShape(20.dp),
                                color = if (isSelected) MenuColors.carLedger else Slate100,
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 6.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "$label ($count)",
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) androidx.compose.ui.graphics.Color.White else Slate700
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    HairlineDivider()
                }
            }

            if (uiState.filteredLogs.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xxxl),
                        contentAlignment = Alignment.Center
                    ) {
                        val emptyMsg = when (uiState.logFilterMode) {
                            LogFilterMode.ALL -> "기록된 차량 운행 및 주유·정비 내역이 없습니다."
                            LogFilterMode.REFUEL_ONLY -> "기록된 주유·정비 내역이 없습니다.\n우측 하단의 [+ 주유 기록 추가] 버튼이나 가계부 카테고리 연동으로 등록할 수 있습니다."
                            LogFilterMode.DRIVING_ONLY -> "기록된 차량 주행 내역이 없습니다."
                        }
                        Text(
                            text = emptyMsg,
                            style = AppTypography.bodySecondary,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center
                        )
                    }
                }
            } else {
                items(
                    items = uiState.filteredLogs,
                    key = { it.id }
                ) { log ->
                    SaaSCarLogRow(
                        log = log,
                        vehicles = uiState.vehicles,
                        isSelectionMode = uiState.isSelectionMode,
                        isSelected = uiState.selectedLogIds.contains(log.id),
                        onToggleSelect = { viewModel.toggleSelectLog(log.id) },
                        onDeleteLog = { viewModel.deleteSingleLog(log.id) },
                        onAssignVehicle = { logId, vId -> viewModel.assignVehicleToFuelLog(logId, vId) },
                        onEditRefuel = { viewModel.openRefuelEditDialog(it) }
                    )
                    HairlineDivider()
                }
            }
            }
        }

        com.autologue.app.presentation.common.AutoLogueLoadingOverlay(
            isLoading = uiState.isLoading || uiState.isSyncing,
            accentColor = MenuColors.carLedger,
            title = uiState.loadingTitle,
            message = uiState.loadingMessage
        )
        }
    }
}

@Composable
fun SaaSCarLogRow(
    log: VehicleLog,
    vehicles: List<VehicleProfile> = emptyList(),
    isSelectionMode: Boolean = false,
    isSelected: Boolean = false,
    onToggleSelect: (() -> Unit)? = null,
    onDeleteLog: (() -> Unit)? = null,
    onAssignVehicle: ((Long, String) -> Unit)? = null,
    onEditRefuel: ((VehicleLog) -> Unit)? = null
) {
    val isParking = log.isParkingLog()
    val isFuelOrMaint = log.logType == VehicleLogType.REFUELING || log.logType == VehicleLogType.MAINTENANCE || isParking
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) androidx.compose.ui.graphics.Color(0xFFFEF2F2) else androidx.compose.ui.graphics.Color.Transparent)
            .then(
                when {
                    isSelectionMode && onToggleSelect != null -> Modifier.clickable { onToggleSelect() }
                    isFuelOrMaint && onEditRefuel != null -> Modifier.clickable { onEditRefuel(log) }
                    else -> Modifier
                }
            )
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode && onToggleSelect != null) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onToggleSelect() },
                colors = CheckboxDefaults.colors(checkedColor = androidx.compose.ui.graphics.Color(0xFFDC2626)),
                modifier = Modifier.padding(end = 8.dp)
            )
        }

        val isToWork = log.isToWorkCommuteLog()
        val isToHome = log.isToHomeCommuteLog()
        val isCommuteRound = log.note?.contains("출퇴근") == true
        val isGolf = log.note?.contains("골프") == true || log.note?.contains("라운딩") == true || log.note?.contains("CC") == true
        val isCustom = log.isCustomFuel()
        val isOneTimeEdited = log.note?.contains("[one_time_edit:true]") == true
        val explicitUnitPrice = log.getExplicitUnitPrice()

        val assignedVehicleId = log.getAssignedVehicleId()
        val matchedVehicle = vehicles.find { it.id == assignedVehicleId }
        val vehicleName = matchedVehicle?.name?.split(" ")?.firstOrNull()
            ?: Regex("\\[(car_[12]:?\\s*|차량\\s*[12]:?\\s*)([^\\]]+)\\]").find(log.note ?: "")?.groupValues?.get(2)?.trim()
            ?: Regex("\\[(G80|쏘렌토|그랜저|아반떼|카니발|[A-Za-z0-9가-힣]+)\\]").find(log.note ?: "")?.groupValues?.get(1)
            ?: (if (assignedVehicleId == "car_2") "차량 2" else "차량 1")

        val vehicleEmoji = matchedVehicle?.emblemEmoji
            ?: com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(vehicleName ?: log.note)

        val isCar2 = assignedVehicleId == "car_2"
        val drivingSubtitle = log.getDrivingDetailSubtitle()

        Column(modifier = Modifier.weight(1f).padding(end = Spacing.sm)) {
            // 상단 뱃지 영역: 차량 식별 뱃지 + 주행/주유/주차/정비 유형 뱃지
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                // 1. 차량 식별 뱃지 (블루투스 연동 차량 정보)
                MetricBadge(
                    text = "$vehicleEmoji $vehicleName",
                    textColor = if (isCar2) Emerald700 else Indigo700,
                    backgroundColor = if (isCar2) Emerald50 else Indigo50
                )

                // 2. 주행 유형 / 주유 / 주차 / 정비 뱃지
                MetricBadge(
                    text = when {
                        isParking -> "주차"
                        log.logType == VehicleLogType.REFUELING -> "주유/충전"
                        log.logType == VehicleLogType.MAINTENANCE -> "정비/세차"
                        isToWork -> "출근"
                        isToHome -> "퇴근"
                        isCommuteRound -> "출퇴근"
                        isGolf -> "골프주행"
                        else -> "일반주행"
                    },
                    textColor = when {
                        isParking -> Indigo700
                        log.logType == VehicleLogType.REFUELING -> Amber700
                        log.logType == VehicleLogType.MAINTENANCE -> Indigo700
                        isToWork || isToHome || isCommuteRound -> Emerald700
                        isGolf -> Indigo700
                        else -> Slate700
                    },
                    backgroundColor = when {
                        isParking -> Indigo50
                        log.logType == VehicleLogType.REFUELING -> Amber50
                        log.logType == VehicleLogType.MAINTENANCE -> Indigo50
                        isToWork || isToHome || isCommuteRound -> Emerald50
                        isGolf -> Indigo50
                        else -> Slate100
                    }
                )

                if (isOneTimeEdited) {
                    MetricBadge(
                        text = "1회성",
                        textColor = Amber700,
                        backgroundColor = Amber50
                    )
                }

                if (log.logType == VehicleLogType.REFUELING && !isParking) {
                    if (isCustom) {
                        MetricBadge(
                            text = "정밀 실측",
                            textColor = Emerald700,
                            backgroundColor = Emerald50
                        )
                    } else {
                        val defPrice = matchedVehicle?.defaultGasPrice ?: 1650.0
                        MetricBadge(
                            text = "%,d원/L 추정".format(defPrice.toInt()),
                            textColor = Slate600,
                            backgroundColor = Slate100
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(Spacing.xs))

            // 메인 헤드라인: 집->회사(~~km) / 시작지점 ➔ 종료지점 (거리 km) 또는 주유소명/주차장명/정비소명
            Text(
                text = log.displayTitle(),
                style = AppTypography.h2
            )

            Spacer(modifier = Modifier.height(Spacing.xxs))

            // 날짜 및 세부 운행 정보(시간, 지점수) 서브 캡션
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = log.timestamp.format(DateTimeFormatter.ofPattern("M.d(E) HH:mm", Locale.KOREA)),
                    style = AppTypography.captionMuted
                )
                drivingSubtitle?.takeIf { it.isNotBlank() }?.let { sub ->
                    Text("·", style = AppTypography.captionMuted)
                    Text(
                        text = sub,
                        style = AppTypography.captionMuted
                    )
                }
                if (!isParking && log.daysSinceLastFuel != null && log.daysSinceLastFuel > 0) {
                    Text("·", style = AppTypography.captionMuted)
                    Text(
                        text = "${log.daysSinceLastFuel}일 만에 주유",
                        style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.SemiBold)
                    )
                }
            }

            // 주유 시점 간 주행거리 및 구간 실연비 뱃지 (주차 내역 제외)
            if (!isParking && log.logType == VehicleLogType.REFUELING && (log.tripDistanceKm > 0.0 || log.estimatedEfficiencyKmPerL != null)) {
                Spacer(modifier = Modifier.height(Spacing.xxs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    if (log.tripDistanceKm > 0.0) {
                        MetricBadge(
                            text = "🚗 이전 주유 후 %.1f km".format(log.tripDistanceKm),
                            textColor = Slate700,
                            backgroundColor = Slate100
                        )
                    }
                    if (log.estimatedEfficiencyKmPerL != null) {
                        MetricBadge(
                            text = "⛽ 실연비 %.1f km/L".format(log.estimatedEfficiencyKmPerL),
                            textColor = Emerald700,
                            backgroundColor = Emerald50
                        )
                    }
                }
            }

            if (isFuelOrMaint && vehicles.isNotEmpty() && onAssignVehicle != null) {
                Spacer(modifier = Modifier.height(Spacing.xs))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    val assignedId = log.getAssignedVehicleId()
                    vehicles.forEach { v ->
                        val isSelectedVehicle = assignedId == v.id
                        val shortName = v.name.split(" ").firstOrNull() ?: v.name
                        androidx.compose.material3.Surface(
                            onClick = { onAssignVehicle(log.id, v.id) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelectedVehicle) MenuColors.carLedgerBg else Slate100,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelectedVehicle) MenuColors.carLedger else Slate300
                            )
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            ) {
                                if (isSelectedVehicle) {
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
                                    fontWeight = if (isSelectedVehicle) FontWeight.Bold else FontWeight.Normal,
                                    color = if (isSelectedVehicle) MenuColors.carLedger else Slate600
                                )
                            }
                        }
                    }
                }
            }
        }

        if (isFuelOrMaint) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "%,d원".format(log.fuelCost),
                        style = AppTypography.h2.copy(fontWeight = FontWeight.Bold)
                    )
                    if (isParking) {
                        Text(
                            text = "주차 요금",
                            style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                        )
                    } else if (log.logType == VehicleLogType.REFUELING && log.fuelAmountLiters > 0) {
                        val assignedVehicle = vehicles.find { it.id == log.getAssignedVehicleId() }
                        val defPrice = assignedVehicle?.defaultGasPrice ?: 1650.0
                        val unitPrice = explicitUnitPrice ?: if (log.fuelAmountLiters > 0) log.fuelCost / log.fuelAmountLiters else defPrice
                        Text(
                            text = if (isCustom) "%.1f L (%,d원/L)".format(log.fuelAmountLiters, unitPrice.toInt())
                                   else "%.1f L (%,d원/L 추정)".format(log.fuelAmountLiters, defPrice.toInt()),
                            style = if (isCustom) AppTypography.caption.copy(color = Emerald700, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                                   else AppTypography.captionMuted.copy(fontSize = 11.sp)
                        )
                    } else if (log.logType == VehicleLogType.MAINTENANCE) {
                        Text(
                            text = "차량정비·세차 지출",
                            style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                        )
                    }
                }
                if (onEditRefuel != null) {
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    IconButton(
                        onClick = { onEditRefuel(log) },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = "상세 설정",
                            tint = MenuColors.carLedger,
                            modifier = Modifier.size(15.dp)
                        )
                    }
                }
            }
        } else {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "%.1f km".format(log.tripDistanceKm),
                        style = AppTypography.h2.copy(color = Slate800, fontWeight = FontWeight.SemiBold)
                    )
                    Text(
                        text = when {
                            isToWork -> "출근 편도"
                            isToHome -> "퇴근 편도"
                            isCommuteRound -> "출퇴근 왕복"
                            isGolf -> "골프장 이동"
                            else -> "일반 주행"
                        },
                        style = AppTypography.captionMuted
                    )
                }
                if (onDeleteLog != null) {
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    IconButton(
                        onClick = onDeleteLog,
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.DeleteOutline,
                            contentDescription = "주행 기록 삭제",
                            tint = Slate400,
                            modifier = Modifier.size(16.dp)
                        )
                    }
                }
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
                verticalAlignment = Alignment.Top
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = androidx.compose.ui.graphics.Color(0xFF1E293B),
                        maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                    if (subLabel != null) {
                        Text(
                            text = subLabel,
                            fontSize = 8.5.sp,
                            color = Slate500,
                            maxLines = 1,
                            overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                        )
                    }
                }
                Spacer(modifier = Modifier.width(4.dp))
                Box(
                    modifier = Modifier
                        .padding(top = 4.dp)
                        .size(7.dp)
                        .clip(androidx.compose.foundation.shape.CircleShape)
                        .background(statusColor)
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
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
                text = "%,dkm 남음".format(remainingKm),
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (remainingKm <= 1000) androidx.compose.ui.graphics.Color(0xFFDC2626) else androidx.compose.ui.graphics.Color(0xFF0F172A),
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(3.dp))
            androidx.compose.material3.Surface(
                shape = androidx.compose.foundation.shape.RoundedCornerShape(4.dp),
                color = androidx.compose.ui.graphics.Color(0xFFF1F5F9)
            ) {
                Text(
                    text = "권장 %,dkm".format(recommendedIntervalKm),
                    fontSize = 8.5.sp,
                    color = Slate600,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                )
            }
        }
    }
}

@Composable
fun RefuelDetailEditDialog(
    log: VehicleLog,
    vehicles: List<VehicleProfile>,
    onDismiss: () -> Unit,
    onSave: (logId: Long, stationName: String, vehicleId: String, cost: Long, liters: Double?, unitPrice: Double?, isOneTimeSave: Boolean) -> Unit,
    onDelete: (logId: Long) -> Unit = {}
) {
    var stationNameText by remember(log.id) { mutableStateOf(log.gasStationName ?: "주유소") }
    var selectedVehicleId by remember(log.id) { mutableStateOf(log.getAssignedVehicleId()) }
    var costText by remember(log.id) { mutableStateOf(if (log.fuelCost > 0) log.fuelCost.toString() else "") }
    var isOneTimeSave by remember(log.id) { mutableStateOf(true) }
    var unitPriceText by remember(log.id) {
        mutableStateOf(
            log.getExplicitUnitPrice()?.let { "%.1f".format(Locale.US, it) } ?: ""
        )
    }
    var litersText by remember(log.id) {
        mutableStateOf(
            if (log.fuelAmountLiters > 0.0) "%.1f".format(Locale.US, log.fuelAmountLiters) else ""
        )
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0x80000000))
                .imePadding()
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .padding(Spacing.xl)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 헤더
                    val isParkingEdit = log.isParkingLog() || (costText.toLongOrNull() ?: log.fuelCost) in 1L until MIN_REFUEL_AMOUNT_WON
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = when {
                                    log.logType == VehicleLogType.MAINTENANCE -> "정비/세차 결제명 및 상세 설정"
                                    isParkingEdit -> "주차 결제명 및 상세 설정"
                                    else -> "주유 결제명 및 상세 설정"
                                },
                                style = AppTypography.h2
                            )
                            Text(
                                text = log.timestamp.format(DateTimeFormatter.ofPattern("yyyy.MM.dd(E) HH:mm", Locale.KOREA)),
                                style = AppTypography.captionMuted
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "닫기", tint = Slate500)
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 결제명 / 주유소·주차장·정비소 상호명 수정
                    Text(
                        text = when {
                            log.logType == VehicleLogType.MAINTENANCE -> "결제명 (정비소 / 세차장 상호명)"
                            isParkingEdit -> "결제명 (주차장 상호명)"
                            else -> "결제명 (주유소 상호명)"
                        },
                        style = AppTypography.caption.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    OutlinedTextField(
                        value = stationNameText,
                        onValueChange = { stationNameText = it },
                        placeholder = { Text("예: GS칼텍스 위례주유소, 서울어린이대공원, 스피드메이트", style = AppTypography.captionMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 차량 선택
                    Text("대상 차량 선택", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        vehicles.forEach { v ->
                            val isSel = selectedVehicleId == v.id
                            Surface(
                                onClick = { selectedVehicleId = v.id },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) MenuColors.carLedgerBg else Slate100,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSel) MenuColors.carLedger else Slate300
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSel) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MenuColors.carLedger, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = v.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) MenuColors.carLedger else Slate700
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 총 결제 금액
                    Text(
                        text = when {
                            log.logType == VehicleLogType.MAINTENANCE -> "정비/세차 총 결제 금액 (원)"
                            isParkingEdit -> "주차 총 결제 금액 (원) - 3만원 미만은 주차로 분류"
                            else -> "주유 총 결제 금액 (원)"
                        },
                        style = AppTypography.caption.copy(fontWeight = FontWeight.Bold)
                    )
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    OutlinedTextField(
                        value = costText,
                        onValueChange = { costText = it.filter { c -> c.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        trailingIcon = { Text("원", modifier = Modifier.padding(end = 12.dp), style = AppTypography.captionMuted) }
                    )

                    if (log.logType == VehicleLogType.REFUELING && !isParkingEdit) {
                        Spacer(modifier = Modifier.height(Spacing.md))

                        // L당 단가
                        val currentVehicle = vehicles.find { it.id == selectedVehicleId }
                        val currentDefaultPrice = currentVehicle?.defaultGasPrice ?: 1650.0

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("L당 주유 단가 (원/L) - 선택", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                            Text(
                                text = "기본 설정: %,d원".format(currentDefaultPrice.toInt()),
                                style = AppTypography.captionMuted.copy(fontSize = 10.sp)
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        OutlinedTextField(
                            value = unitPriceText,
                            onValueChange = { text ->
                                unitPriceText = text
                                val cost = costText.toLongOrNull() ?: 0L
                                val price = text.toDoubleOrNull() ?: 0.0
                                if (cost > 0 && price > 0.0) {
                                    litersText = "%.1f".format(Locale.US, cost / price)
                                }
                            },
                            placeholder = { Text("예: 1680 (미입력 시 %,d원 기준)".format(currentDefaultPrice.toInt()), style = AppTypography.captionMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            trailingIcon = { Text("원/L", modifier = Modifier.padding(end = 12.dp), style = AppTypography.captionMuted) }
                        )

                        Spacer(modifier = Modifier.height(Spacing.md))

                        // 실제 주유량 (L)
                        Text("실제 주유량 (L) - 선택", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        OutlinedTextField(
                            value = litersText,
                            onValueChange = { text ->
                                litersText = text
                                val cost = costText.toLongOrNull() ?: 0L
                                val liters = text.toDoubleOrNull() ?: 0.0
                                if (cost > 0 && liters > 0.0) {
                                    unitPriceText = "%.1f".format(Locale.US, cost / liters)
                                }
                            },
                            placeholder = { Text("예: 32.5 (미입력 시 자동 계산)", style = AppTypography.captionMuted) },
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                            singleLine = true,
                            trailingIcon = { Text("L", modifier = Modifier.padding(end = 12.dp), style = AppTypography.captionMuted) }
                        )

                        Spacer(modifier = Modifier.height(Spacing.sm))

                        // 자동 계산 도우미 버튼들
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                        ) {
                            Surface(
                                onClick = {
                                    val cost = costText.toLongOrNull() ?: 0L
                                    unitPriceText = "%.1f".format(Locale.US, currentDefaultPrice)
                                    if (cost > 0) {
                                        litersText = "%.1f".format(Locale.US, cost / currentDefaultPrice)
                                    }
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = Slate100,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "기본(%,d원) 기준 채움".format(currentDefaultPrice.toInt()),
                                    fontSize = 11.sp,
                                    color = Slate700,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }

                            Surface(
                                onClick = {
                                    unitPriceText = ""
                                    litersText = ""
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = Slate100,
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "선택 항목 비우기",
                                    fontSize = 11.sp,
                                    color = Slate700,
                                    modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.lg))

                    // 저장 버튼 바로 위에 배치된 [1회성 저장 / 영구저장] 선택 버튼
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (isOneTimeSave) androidx.compose.ui.graphics.Color(0xFFFFFBEB) else MenuColors.carLedgerBg,
                        border = androidx.compose.foundation.BorderStroke(
                            1.dp,
                            if (isOneTimeSave) androidx.compose.ui.graphics.Color(0xFFFDE68A) else MenuColors.carLedgerBorder
                        ),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(Spacing.sm),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Surface(
                                    onClick = { isOneTimeSave = true },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isOneTimeSave) androidx.compose.ui.graphics.Color(0xFFD97706) else androidx.compose.ui.graphics.Color.White,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (isOneTimeSave) androidx.compose.ui.graphics.Color(0xFFB45309) else Slate300),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "1회성 저장",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isOneTimeSave) androidx.compose.ui.graphics.Color.White else Slate700,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                                Surface(
                                    onClick = { isOneTimeSave = false },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (!isOneTimeSave) MenuColors.carLedger else androidx.compose.ui.graphics.Color.White,
                                    border = androidx.compose.foundation.BorderStroke(1.dp, if (!isOneTimeSave) MenuColors.carLedger else Slate300),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = "영구저장",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!isOneTimeSave) androidx.compose.ui.graphics.Color.White else Slate700,
                                        modifier = Modifier.padding(vertical = 8.dp),
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                }
                            }
                            Text(
                                text = if (isOneTimeSave) {
                                    "이번 내역만 단독으로 수정되며, 다른 내역에 영향을 주지 않고 고정 보호됩니다."
                                } else {
                                    "결제명을 수정하면 가계부 및 차계부의 동일 결제처(출금) 내역이 자동으로 영구 치환됩니다."
                                },
                                style = AppTypography.caption.copy(
                                    color = if (isOneTimeSave) androidx.compose.ui.graphics.Color(0xFFB45309) else MenuColors.carLedger,
                                    fontSize = 10.5.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 삭제, 취소, 단일 저장 버튼
                    var isSavingLocal by remember { mutableStateOf(false) }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AutoLogueDangerButton(
                            text = "삭제",
                            enabled = !isSavingLocal,
                            onClick = { onDelete(log.id) }
                        )
                        AutoLogueSecondaryButton(
                            text = "취소",
                            onClick = { if (!isSavingLocal) onDismiss() }
                        )
                        Button(
                            onClick = {
                                if (!isSavingLocal) {
                                    isSavingLocal = true
                                    val defaultName = if (log.logType == VehicleLogType.MAINTENANCE) "차량정비/세차" else "주유소"
                                    val finalStation = stationNameText.trim().ifBlank { log.gasStationName ?: defaultName }
                                    val cost = costText.toLongOrNull() ?: log.fuelCost
                                    val unitPrice = unitPriceText.toDoubleOrNull()
                                    val liters = litersText.toDoubleOrNull()
                                    onSave(log.id, finalStation, selectedVehicleId, cost, liters, unitPrice, isOneTimeSave)
                                }
                            },
                            enabled = !isSavingLocal,
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = androidx.compose.ui.graphics.Color(0xFF2563EB)),
                            shape = RoundedCornerShape(10.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                        ) {
                            if (isSavingLocal) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(15.dp),
                                    strokeWidth = 2.dp,
                                    color = PureWhite
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text("저장 중...", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            } else {
                                Text("저장", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AddRefuelLogDialog(
    vehicles: List<VehicleProfile>,
    selectedVehicleId: String,
    onDismiss: () -> Unit,
    onSave: (vehicleId: String, station: String, cost: Long, liters: Double?, unitPrice: Double?, dateTime: LocalDateTime) -> Unit
) {
    val initialVehicleId = if (selectedVehicleId == com.autologue.app.data.preferences.MultiVehiclePreferences.ALL_VEHICLES_ID) {
        vehicles.firstOrNull()?.id ?: "car_1"
    } else {
        selectedVehicleId
    }
    var curVehicleId by remember { mutableStateOf(initialVehicleId) }
    var stationText by remember { mutableStateOf("") }
    var costText by remember { mutableStateOf("") }
    var unitPriceText by remember { mutableStateOf("") }
    var litersText by remember { mutableStateOf("") }
    var refuelDateTime by remember { mutableStateOf(LocalDateTime.now()) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(androidx.compose.ui.graphics.Color(0x80000000))
                .imePadding()
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = AppColors.surface,
                modifier = Modifier
                    .fillMaxWidth(0.92f)
                    .clip(RoundedCornerShape(16.dp))
            ) {
                Column(
                    modifier = Modifier
                        .padding(Spacing.xl)
                        .verticalScroll(rememberScrollState())
                ) {
                    // 헤더
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(
                                text = "주유 기록 직접 추가",
                                style = AppTypography.h2
                            )
                            Text(
                                text = "차계부 및 가계부에 동시 반영",
                                style = AppTypography.captionMuted
                            )
                        }
                        IconButton(onClick = onDismiss) {
                            Icon(Icons.Default.Close, contentDescription = "닫기", tint = Slate500)
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // 안내 뱃지
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MenuColors.carLedgerBg,
                        border = androidx.compose.foundation.BorderStroke(1.dp, MenuColors.carLedgerBorder),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(modifier = Modifier.padding(Spacing.sm), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Info, contentDescription = null, tint = MenuColors.carLedger, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(
                                text = "주유 금액을 입력하면 실연비와 가계부 지출이 자동 계산/누적됩니다.",
                                style = AppTypography.caption.copy(color = MenuColors.carLedger, fontSize = 11.sp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 1. 차량 선택
                    Text("주유 차량 선택", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        vehicles.forEach { v ->
                            val isSel = curVehicleId == v.id
                            Surface(
                                onClick = { curVehicleId = v.id },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSel) MenuColors.carLedgerBg else Slate100,
                                border = androidx.compose.foundation.BorderStroke(
                                    1.dp,
                                    if (isSel) MenuColors.carLedger else Slate300
                                ),
                                modifier = Modifier.weight(1f)
                            ) {
                                Row(
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    if (isSel) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = MenuColors.carLedger, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                    }
                                    Text(
                                        text = v.name,
                                        fontSize = 12.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSel) MenuColors.carLedger else Slate700
                                    )
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 2. 주유소 상호명
                    Text("주유소 상호명", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    OutlinedTextField(
                        value = stationText,
                        onValueChange = { stationText = it },
                        placeholder = { Text("예: GS칼텍스 판교주유소, SK에너지 서초셀프", style = AppTypography.captionMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 3. 주유 총금액
                    Text("주유 총 결제 금액 (원) *필수", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    OutlinedTextField(
                        value = costText,
                        onValueChange = {
                            costText = it.filter { c -> c.isDigit() }
                            val cost = costText.toLongOrNull() ?: 0L
                            val price = unitPriceText.toDoubleOrNull() ?: 0.0
                            if (cost > 0 && price > 0.0) {
                                litersText = "%.1f".format(Locale.US, cost / price)
                            }
                        },
                        placeholder = { Text("예: 50000", style = AppTypography.captionMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        trailingIcon = { Text("원", modifier = Modifier.padding(end = 12.dp), style = AppTypography.captionMuted) }
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 4. L당 단가
                    val currentVehicle = vehicles.find { it.id == curVehicleId }
                    val currentDefaultPrice = currentVehicle?.defaultGasPrice ?: 1650.0

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("L당 주유 단가 (원/L) - 선택", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                        Text(
                            text = "차량 기본 유가: %,d원".format(currentDefaultPrice.toInt()),
                            style = AppTypography.captionMuted.copy(fontSize = 10.sp)
                        )
                    }
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    OutlinedTextField(
                        value = unitPriceText,
                        onValueChange = { text ->
                            unitPriceText = text
                            val cost = costText.toLongOrNull() ?: 0L
                            val price = text.toDoubleOrNull() ?: 0.0
                            if (cost > 0 && price > 0.0) {
                                litersText = "%.1f".format(Locale.US, cost / price)
                            }
                        },
                        placeholder = { Text("예: 1680 (미입력 시 %,d원 기준)".format(currentDefaultPrice.toInt()), style = AppTypography.captionMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        trailingIcon = { Text("원/L", modifier = Modifier.padding(end = 12.dp), style = AppTypography.captionMuted) }
                    )

                    Spacer(modifier = Modifier.height(Spacing.md))

                    // 5. 실제 주유량 (L)
                    Text("주유량 (L) - 선택", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(Spacing.xxs))
                    OutlinedTextField(
                        value = litersText,
                        onValueChange = { text ->
                            litersText = text
                            val cost = costText.toLongOrNull() ?: 0L
                            val liters = text.toDoubleOrNull() ?: 0.0
                            if (cost > 0 && liters > 0.0) {
                                unitPriceText = "%.1f".format(Locale.US, cost / liters)
                            }
                        },
                        placeholder = { Text("예: 30.0 (미입력 시 자동 계산)", style = AppTypography.captionMuted) },
                        modifier = Modifier.fillMaxWidth(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        singleLine = true,
                        trailingIcon = { Text("L", modifier = Modifier.padding(end = 12.dp), style = AppTypography.captionMuted) }
                    )

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // 자동 계산 도우미 버튼들
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Surface(
                            onClick = {
                                val cost = costText.toLongOrNull() ?: 0L
                                unitPriceText = "%.1f".format(Locale.US, currentDefaultPrice)
                                if (cost > 0) {
                                    litersText = "%.1f".format(Locale.US, cost / currentDefaultPrice)
                                }
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "기본(%,d원) 기준 채움".format(currentDefaultPrice.toInt()),
                                fontSize = 11.sp,
                                color = Slate700,
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }

                        Surface(
                            onClick = {
                                unitPriceText = ""
                                litersText = ""
                            },
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100,
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "선택 항목 비우기",
                                fontSize = 11.sp,
                                color = Slate700,
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.xl))

                    // 저장 및 취소 버튼
                    val isSaveValid = (costText.toLongOrNull() ?: 0L) > 0L
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        AutoLogueSecondaryButton(
                            text = "취소",
                            modifier = Modifier.weight(1f),
                            onClick = onDismiss
                        )
                        AutoLoguePrimaryButton(
                            text = "주유 기록 등록",
                            modifier = Modifier.weight(1f),
                            enabled = isSaveValid,
                            onClick = {
                                val cost = costText.toLongOrNull() ?: 0L
                                val unitPrice = unitPriceText.toDoubleOrNull()
                                val liters = litersText.toDoubleOrNull()
                                val finalStation = stationText.trim().ifBlank { "주유소" }
                                onSave(curVehicleId, finalStation, cost, liters, unitPrice, refuelDateTime)
                            }
                        )
                    }
                }
            }
        }
    }
}


