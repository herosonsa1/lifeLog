@file:OptIn(ExperimentalMaterial3Api::class)
package com.autologue.app.presentation.car

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.autologue.app.data.preferences.MultiVehiclePreferences
import com.autologue.app.data.preferences.VehicleProfile
import com.autologue.app.domain.model.*
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.expense.AnalyticsComparisonBasis
import com.autologue.app.presentation.expense.AnalyticsPeriodUnit
import com.autologue.app.presentation.expense.PeriodSpec
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CarLedgerEasyModeContent(
    uiState: CarLedgerUiState,
    snackbarHostState: SnackbarHostState,
    selectedMainTab: CarLedgerPageTab,
    onSelectMainTab: (CarLedgerPageTab) -> Unit,
    carAnalyticsUnit: AnalyticsPeriodUnit,
    carAnalyticsPeriod: PeriodSpec,
    carAnalyticsBasis: AnalyticsComparisonBasis,
    carAnalyticsSubTab: CarLedgerAnalyticsSubTab,
    onSelectAnalyticsSubTab: (CarLedgerAnalyticsSubTab) -> Unit,
    onSelectAnalyticsUnit: (AnalyticsPeriodUnit) -> Unit,
    onPreviousAnalyticsPeriod: () -> Unit,
    onNextAnalyticsPeriod: () -> Unit,
    onSelectAnalyticsBasis: (AnalyticsComparisonBasis) -> Unit,
    onJumpToLogTabWithFilter: (LogFilterMode) -> Unit,
    onSelectVehicle: (String) -> Unit,
    onOpenAddRefuel: () -> Unit,
    onTriggerSmsSync: () -> Unit,
    onOpenPasteSms: () -> Unit,
    onOpenLocationDialog: () -> Unit,
    onOpenVehicleManageDialog: () -> Unit,
    onOpenMaintenanceDialog: () -> Unit,
    onSetLogFilterMode: (LogFilterMode) -> Unit,
    onToggleVehicleFilter: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onSelectAllVisibleLogs: () -> Unit,
    onDeleteSelectedLogs: () -> Unit,
    onToggleSelectLog: (Long) -> Unit,
    onDeleteSingleLog: (Long) -> Unit,
    onAssignVehicle: (Long, String) -> Unit,
    onEditRefuel: (VehicleLog) -> Unit,
    onClearDummyLogs: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var isCarToolsSheetOpen by remember { mutableStateOf(false) }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = EasyTheme.CanvasBg,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            EasyModeTopBar(
                title = "내 차",
                subtitle = "연비·주유·소모품을 한눈에 관리해요",
                accentColor = EasyTheme.tossBlue,
                onOpenSettings = { EasyModeManager.openSettingsSheet() },
                onQuickSwitchToStandard = { EasyModeManager.setEasyMode(context, false) }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                // 1. 상단 세그먼트 탭 ([운행 · 주유 기록] / [주행 · 유지비 통계])
                item {
                    val tabs = listOf("운행 · 주유 기록", "주행 · 유지비 통계")
                    val selectedIdx = if (selectedMainTab == CarLedgerPageTab.RECORDS) 0 else 1
                    EasySegmentedBar(
                        options = tabs,
                        selectedIndex = selectedIdx,
                        onSelect = { idx ->
                            onSelectMainTab(if (idx == 0) CarLedgerPageTab.RECORDS else CarLedgerPageTab.STATISTICS)
                        }
                    )
                }

                // 2. 차량 선택 캡슐 바
                item {
                    EasyVehicleCapsuleSelector(
                        vehicles = uiState.vehicles,
                        selectedVehicleId = uiState.selectedVehicleId,
                        onSelectVehicle = onSelectVehicle,
                        onManageVehicles = onOpenVehicleManageDialog
                    )
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
                        EasySectionCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                            CarLedgerAnalyticsSection(
                                report = carReport,
                                selectedSubTab = carAnalyticsSubTab,
                                onSelectSubTab = onSelectAnalyticsSubTab,
                                onSelectPeriodUnit = onSelectAnalyticsUnit,
                                onPreviousPeriod = onPreviousAnalyticsPeriod,
                                onNextPeriod = onNextAnalyticsPeriod,
                                onSelectComparisonBasis = onSelectAnalyticsBasis,
                                onJumpToLogTabWithFilter = onJumpToLogTabWithFilter
                            )
                        }
                    }
                } else {
                    // 3. 내 차 요약 히어로 카드 (평균 연비 + 총 주행거리 + 총 주유비 + 원탭 액션)
                    item {
                        EasySectionCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.Top
                            ) {
                                Column {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        Text(
                                            text = "평균 연비",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.SemiBold,
                                            color = EasyTheme.textSecondary
                                        )
                                        Surface(
                                            shape = RoundedCornerShape(999.dp),
                                            color = EasyTheme.SubtleBoxBg
                                        ) {
                                            Text(
                                                text = "%,d원/L 기준".format(uiState.selectedVehicleDefaultGasPrice.toInt()),
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = EasyTheme.textSecondary,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Text(
                                        text = "%.1f km/L".format(uiState.averageEfficiencyKmPerL),
                                        fontSize = 30.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = EasyTheme.textPrimary,
                                        letterSpacing = (-0.8).sp
                                    )
                                }

                                Surface(
                                    onClick = { isCarToolsSheetOpen = true },
                                    shape = RoundedCornerShape(12.dp),
                                    color = EasyTheme.SubtleBoxBg
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Tune,
                                            contentDescription = "차량 설정 및 도구",
                                            tint = EasyTheme.textPrimary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Text(
                                            text = "차량 설정",
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EasyTheme.textPrimary
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 서브 지표 2열 박스 (총 주행거리 / 누적 주유비)
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = EasyTheme.SubtleBoxBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 14.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = "누적 주행거리",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = EasyTheme.textSecondary
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "%,.1f km".format(uiState.totalDrivingDistanceKm),
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = EasyTheme.textPrimary
                                        )
                                    }
                                    Box(
                                        modifier = Modifier
                                            .width(1.dp)
                                            .height(30.dp)
                                            .background(EasyTheme.DividerColor)
                                    )
                                    Column(
                                        modifier = Modifier
                                            .weight(1f)
                                            .padding(start = 16.dp)
                                    ) {
                                        Text(
                                            text = "누적 주유·유지비",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = EasyTheme.textSecondary
                                        )
                                        Spacer(modifier = Modifier.height(3.dp))
                                        Text(
                                            text = "%,d원".format(uiState.totalFuelExpense),
                                            fontSize = 17.sp,
                                            fontWeight = FontWeight.ExtraBold,
                                            color = EasyTheme.tossBlue
                                        )
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(14.dp))

                            // 원탭 빠른 작업 버튼 2개 (주유 등록 / 주유 문자 동기화)
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                EasyActionButton(
                                    text = "주유·정비 등록",
                                    icon = Icons.Default.Add,
                                    onClick = onOpenAddRefuel,
                                    isPrimary = true,
                                    accentColor = EasyTheme.tossBlue,
                                    modifier = Modifier.weight(1f)
                                )
                                EasyActionButton(
                                    text = "주유 문자 동기화",
                                    icon = Icons.Default.Sync,
                                    onClick = onTriggerSmsSync,
                                    isPrimary = false,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // 4. 소모품 점검 상태 카드 (엔진오일 · 에어컨 필터 · 타이어 위치 교환)
                    item {
                        val mCfg = uiState.maintenanceConfig
                        val currentKm = uiState.totalDrivingDistanceKm
                        val oilRem = mCfg.engineOil.getRemainingKm(currentKm)
                        val oilRatio = mCfg.engineOil.getProgressRatio(currentKm)
                        val airconRem = mCfg.airconFilter.getRemainingKm(currentKm)
                        val airconRatio = mCfg.airconFilter.getProgressRatio(currentKm)
                        val tireRem = mCfg.tireRotation.getRemainingKm(currentKm)
                        val tireRatio = mCfg.tireRotation.getProgressRatio(currentKm)

                        val dueCount = listOf(
                            oilRem <= 1000 || oilRatio >= 0.9f,
                            airconRem <= 1000 || airconRatio >= 0.9f,
                            tireRem <= 1000 || tireRatio >= 0.9f
                        ).count { it }

                        EasySectionCard(onClick = onOpenMaintenanceDialog) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "소모품 교체 주기",
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = EasyTheme.textPrimary
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(999.dp),
                                        color = if (dueCount > 0) EasyTheme.NegativeRedBg else EasyTheme.PositiveGreenBg
                                    ) {
                                        Text(
                                            text = if (dueCount > 0) "점검 필요 ${dueCount}건" else "전체 양호",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (dueCount > 0) EasyTheme.NegativeRed else EasyTheme.PositiveGreen,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                        )
                                    }
                                }
                                Text(
                                    text = "주기 설정",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EasyTheme.tossBlue
                                )
                            }

                            Spacer(modifier = Modifier.height(12.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                EasyConsumableMiniTile(
                                    title = "엔진오일",
                                    remainingKm = oilRem,
                                    ratio = oilRatio,
                                    modifier = Modifier.weight(1f)
                                )
                                EasyConsumableMiniTile(
                                    title = "에어컨 필터",
                                    remainingKm = airconRem,
                                    ratio = airconRatio,
                                    modifier = Modifier.weight(1f)
                                )
                                EasyConsumableMiniTile(
                                    title = "타이어 교환",
                                    remainingKm = tireRem,
                                    ratio = tireRatio,
                                    modifier = Modifier.weight(1f)
                                )
                            }
                        }
                    }

                    // 5. 출퇴근 거점 요약 배너
                    item {
                        EasySectionCard(
                            onClick = onOpenLocationDialog,
                            contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(38.dp)
                                            .clip(RoundedCornerShape(12.dp))
                                            .background(EasyTheme.tossBlueSoft),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.LocationOn,
                                            contentDescription = null,
                                            tint = EasyTheme.tossBlue,
                                            modifier = Modifier.size(20.dp)
                                        )
                                    }
                                    Column {
                                        if (uiState.commuteConfig.isConfigured) {
                                            Text(
                                                text = "${uiState.commuteConfig.homeName} ↔ ${uiState.commuteConfig.companyName}",
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.textPrimary
                                            )
                                            Text(
                                                text = "편도 %.1fkm · 왕복 %.1fkm 자동 인식".format(
                                                    uiState.commuteConfig.commuteOneWayKm,
                                                    uiState.commuteConfig.commuteRoundTripKm
                                                ),
                                                fontSize = 12.sp,
                                                color = EasyTheme.textSecondary
                                            )
                                        } else {
                                            Text(
                                                text = "집·회사 출퇴근 거점 등록하기",
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.textPrimary
                                            )
                                            Text(
                                                text = "지도로 거점을 지정하면 출퇴근 운행이 자동 기록돼요",
                                                fontSize = 12.sp,
                                                color = EasyTheme.textSecondary
                                            )
                                        }
                                    }
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = EasyTheme.textMuted
                                )
                            }
                        }
                    }

                    // 6. 기록 필터 캡슐 바
                    item {
                        val filterItems = listOf(
                            Triple(LogFilterMode.ALL, "전체", uiState.logs.size),
                            Triple(LogFilterMode.REFUEL_ONLY, "주유 · 정비", uiState.totalRefuelCount),
                            Triple(LogFilterMode.NON_COMMUTE_DRIVING, "외출 · 일반", uiState.nonCommuteDrivingCount),
                            Triple(LogFilterMode.DRIVING_ONLY, "전체 운행", uiState.totalDrivingCount)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            filterItems.forEach { (mode, label, count) ->
                                val isSelected = uiState.logFilterMode == mode
                                Surface(
                                    onClick = { onSetLogFilterMode(mode) },
                                    shape = RoundedCornerShape(999.dp),
                                    color = if (isSelected) EasyTheme.textPrimary else EasyTheme.CardBg,
                                    border = if (isSelected) null else BorderStroke(1.dp, EasyTheme.DividerColor)
                                ) {
                                    Text(
                                        text = "$label ${count}건",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) Color.White else EasyTheme.textSecondary,
                                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
                                    )
                                }
                            }
                        }
                    }

                    // 7. 선택 삭제 모드 배너 (활성화 시)
                    if (uiState.isSelectionMode) {
                        item {
                            Surface(
                                shape = RoundedCornerShape(16.dp),
                                color = EasyTheme.NegativeRedBg,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 16.dp, vertical = 12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Surface(
                                            onClick = onSelectAllVisibleLogs,
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.White
                                        ) {
                                            Text(
                                                text = "전체 선택",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.textPrimary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                        Text(
                                            text = "${uiState.selectedLogIds.size}건 선택됨",
                                            fontSize = 13.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EasyTheme.NegativeRed
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                        Surface(
                                            onClick = onToggleSelectionMode,
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color.White
                                        ) {
                                            Text(
                                                text = "취소",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.textSecondary,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                        Surface(
                                            onClick = onDeleteSelectedLogs,
                                            enabled = uiState.selectedLogIds.isNotEmpty(),
                                            shape = RoundedCornerShape(8.dp),
                                            color = EasyTheme.NegativeRed
                                        ) {
                                            Text(
                                                text = "삭제",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color.White,
                                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 8. 운행 · 주유 내역 리스트
                    if (uiState.filteredLogs.isEmpty()) {
                        item {
                            EasySectionCard {
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 24.dp),
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    Text(
                                        text = "표시할 차량 기록이 없습니다",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.textPrimary
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = "주유·정비 등록이나 문자 동기화로 기록을 채워보세요.",
                                        fontSize = 13.sp,
                                        color = EasyTheme.textSecondary
                                    )
                                }
                            }
                        }
                    } else {
                        items(
                            items = uiState.filteredLogs,
                            key = { it.id }
                        ) { log ->
                            EasyCarLogCard(
                                log = log,
                                vehicles = uiState.vehicles,
                                isSelectionMode = uiState.isSelectionMode,
                                isSelected = uiState.selectedLogIds.contains(log.id),
                                onToggleSelect = { onToggleSelectLog(log.id) },
                                onEdit = { onEditRefuel(log) }
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(72.dp))
                }
            }
        }
    }

    if (isCarToolsSheetOpen) {
        ModalBottomSheet(
            onDismissRequest = { isCarToolsSheetOpen = false },
            containerColor = EasyTheme.CardBg,
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 22.dp)
                    .padding(bottom = 32.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "차계부 설정 및 도구",
                    fontSize = 19.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EasyTheme.textPrimary
                )
                Text(
                    text = "차량 프로필, 출퇴근 거점, 소모품 주기를 간편하게 관리하세요.",
                    fontSize = 13.sp,
                    color = EasyTheme.textSecondary
                )
                Spacer(modifier = Modifier.height(4.dp))

                EasyCarToolSheetItem(
                    icon = Icons.Default.DirectionsCar,
                    title = "차량 프로필 관리",
                    subtitle = "차량 이름, 유종, 기준 유가 설정",
                    onClick = {
                        isCarToolsSheetOpen = false
                        onOpenVehicleManageDialog()
                    }
                )
                EasyCarToolSheetItem(
                    icon = Icons.Default.LocationOn,
                    title = "출퇴근 지도 거점 설정",
                    subtitle = "집·회사 위치 및 차량 블루투스 연동",
                    onClick = {
                        isCarToolsSheetOpen = false
                        onOpenLocationDialog()
                    }
                )
                EasyCarToolSheetItem(
                    icon = Icons.Default.Build,
                    title = "소모품 교체 주기 설정",
                    subtitle = "엔진오일 · 에어컨 필터 · 타이어 교체 주기",
                    onClick = {
                        isCarToolsSheetOpen = false
                        onOpenMaintenanceDialog()
                    }
                )
                EasyCarToolSheetItem(
                    icon = Icons.Default.ContentPaste,
                    title = "주유 문자 복사 · 붙여넣기",
                    subtitle = "카드/은행 주유 결제 문자를 직접 붙여넣어 등록",
                    onClick = {
                        isCarToolsSheetOpen = false
                        onOpenPasteSms()
                    }
                )
                EasyCarToolSheetItem(
                    icon = Icons.Default.CheckCircleOutline,
                    title = if (uiState.isSelectionMode) "선택 삭제 모드 해제" else "기록 선택 삭제 모드",
                    subtitle = "여러 건의 기록을 선택해 한 번에 정리",
                    onClick = {
                        isCarToolsSheetOpen = false
                        onToggleSelectionMode()
                    }
                )
                EasyCarToolSheetItem(
                    icon = Icons.Default.Tune,
                    title = "고급모드로 전환 (기존 상세 UI/UX)",
                    subtitle = "다중 필터 바와 상세 배지가 포함된 기존 화면으로 전환",
                    onClick = {
                        isCarToolsSheetOpen = false
                        EasyModeManager.setEasyMode(context, false)
                    }
                )
            }
        }
    }
}

private fun getEasyVehicleBadgeColors(vehicleId: String, vehicleName: String): Pair<Color, Color> {
    val norm = vehicleName.trim().lowercase()
    return when {
        norm.contains("볼보") || norm.contains("volvo") || norm.contains("xc") || vehicleId == "car_2" -> {
            Color(0xFF047857) to Color(0xFFD1FAE5) // 볼보 / 차량 2: 딥 에메랄드 그린
        }
        norm.contains("벤츠") || norm.contains("benz") || vehicleId == "car_1" -> {
            Color(0xFF1B64DA) to Color(0xFFE8F3FF) // 벤츠 / 차량 1: 코발트 블루
        }
        else -> {
            Color(0xFF6D28D9) to Color(0xFFEDE9FE) // 추가 차량: 로얄 퍼플
        }
    }
}

@Composable
private fun EasyVehicleCapsuleSelector(
    vehicles: List<VehicleProfile>,
    selectedVehicleId: String,
    onSelectVehicle: (String) -> Unit,
    onManageVehicles: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val isAllSelected = selectedVehicleId == MultiVehiclePreferences.ALL_VEHICLES_ID
        Surface(
            onClick = { onSelectVehicle(MultiVehiclePreferences.ALL_VEHICLES_ID) },
            shape = RoundedCornerShape(999.dp),
            color = if (isAllSelected) EasyTheme.textPrimary else EasyTheme.CardBg,
            border = if (isAllSelected) null else BorderStroke(1.dp, EasyTheme.DividerColor)
        ) {
            Text(
                text = "전체 차량",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                color = if (isAllSelected) Color.White else EasyTheme.textSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 9.dp)
            )
        }

        vehicles.forEach { vehicle ->
            val isSelected = selectedVehicleId == vehicle.id
            val (vFg, vBg) = getEasyVehicleBadgeColors(vehicle.id, vehicle.name)
            Surface(
                onClick = { onSelectVehicle(vehicle.id) },
                shape = RoundedCornerShape(999.dp),
                color = if (isSelected) vFg else vBg.copy(alpha = 0.65f),
                border = if (isSelected) null else BorderStroke(1.dp, vFg.copy(alpha = 0.25f))
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(7.dp)
                            .clip(CircleShape)
                            .background(if (isSelected) Color.White else vFg)
                    )
                    Text(
                        text = vehicle.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isSelected) Color.White else vFg
                    )
                    Text(
                        text = vehicle.fuelType,
                        fontSize = 11.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = if (isSelected) Color.White.copy(alpha = 0.85f) else vFg.copy(alpha = 0.8f)
                    )
                }
            }
        }

        Surface(
            onClick = onManageVehicles,
            shape = RoundedCornerShape(999.dp),
            color = EasyTheme.CardBg,
            border = BorderStroke(1.dp, EasyTheme.DividerColor)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.Edit,
                    contentDescription = "차량 편집",
                    tint = EasyTheme.textSecondary,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = "차량 관리",
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EasyTheme.textSecondary
                )
            }
        }
    }
}

@Composable
private fun EasyConsumableMiniTile(
    title: String,
    remainingKm: Int,
    ratio: Float,
    modifier: Modifier = Modifier
) {
    val statusColor = when {
        remainingKm <= 1000 || ratio >= 0.9f -> EasyTheme.NegativeRed
        remainingKm <= 2500 || ratio >= 0.75f -> EasyTheme.WarningAmber
        else -> EasyTheme.PositiveGreen
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = EasyTheme.SubtleBoxBg,
        modifier = modifier
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = EasyTheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Box(
                    modifier = Modifier
                        .size(7.dp)
                        .clip(CircleShape)
                        .background(statusColor)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            LinearProgressIndicator(
                progress = { ratio.coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(5.dp)
                    .clip(RoundedCornerShape(999.dp)),
                color = statusColor,
                trackColor = EasyTheme.DividerColor
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = "%,dkm 남음".format(remainingKm),
                fontSize = 11.5.sp,
                fontWeight = FontWeight.ExtraBold,
                color = if (remainingKm <= 1000) EasyTheme.NegativeRed else EasyTheme.textSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
private fun EasyCarLogCard(
    log: VehicleLog,
    vehicles: List<VehicleProfile>,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onToggleSelect: () -> Unit,
    onEdit: () -> Unit
) {
    val isParking = log.isParkingLog()
    val isFuelOrMaint = log.logType == VehicleLogType.REFUELING ||
        log.logType == VehicleLogType.MAINTENANCE ||
        isParking
    val assignedId = log.getAssignedVehicleId()
    val matchedVehicle = vehicles.find { it.id == assignedId }
    val vehicleLabel = matchedVehicle?.name?.split(" ")?.firstOrNull()
        ?: Regex("\\[(car_[12]:?\\s*|차량\\s*[12]:?\\s*)([^\\]]+)\\]").find(log.note ?: "")?.groupValues?.get(2)?.trim()?.split(" ")?.firstOrNull()
        ?: if (assignedId == "car_2") "차량 2" else "차량 1"
    val (vehicleBadgeFg, vehicleBadgeBg) = getEasyVehicleBadgeColors(assignedId, vehicleLabel)

    val badgeLabel = when {
        isParking -> "주차"
        log.logType == VehicleLogType.REFUELING -> "주유"
        log.logType == VehicleLogType.MAINTENANCE -> "정비 · 세차"
        log.isToWorkCommuteLog() -> "출근"
        log.isToHomeCommuteLog() -> "퇴근"
        log.isCommuteDrivingLog() -> "출퇴근"
        log.isGolfDrivingLog() -> "골프장 이동"
        else -> "일반 주행"
    }

    val iconBg = when {
        log.logType == VehicleLogType.REFUELING -> EasyTheme.WarningAmberBg
        isParking || log.logType == VehicleLogType.MAINTENANCE -> Color(0xFFF3E8FF)
        else -> vehicleBadgeBg
    }
    val iconTint = when {
        log.logType == VehicleLogType.REFUELING -> EasyTheme.WarningAmber
        isParking || log.logType == VehicleLogType.MAINTENANCE -> Color(0xFF6D28D9)
        else -> vehicleBadgeFg
    }
    val iconVec = when {
        log.logType == VehicleLogType.REFUELING -> Icons.Default.LocalGasStation
        isParking -> Icons.Default.LocalParking
        log.logType == VehicleLogType.MAINTENANCE -> Icons.Default.Build
        else -> Icons.Default.DirectionsCar
    }

    EasySectionCard(
        onClick = if (isSelectionMode) onToggleSelect else onEdit,
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                if (isSelectionMode) {
                    Checkbox(
                        checked = isSelected,
                        onCheckedChange = { onToggleSelect() },
                        colors = CheckboxDefaults.colors(checkedColor = EasyTheme.tossBlue)
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(13.dp))
                            .background(iconBg),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = iconVec,
                            contentDescription = null,
                            tint = iconTint,
                            modifier = Modifier.size(21.dp)
                        )
                    }
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = vehicleBadgeBg
                        ) {
                            Text(
                                text = vehicleLabel,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = vehicleBadgeFg,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = "·",
                            fontSize = 11.5.sp,
                            color = EasyTheme.textMuted
                        )
                        Text(
                            text = badgeLabel,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = EasyTheme.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = log.displayTitle(),
                        fontSize = 15.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = log.timestamp.format(DateTimeFormatter.ofPattern("M월 d일(E) HH:mm", Locale.KOREA)),
                        fontSize = 12.sp,
                        color = EasyTheme.textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (isFuelOrMaint) {
                    Text(
                        text = "%,d원".format(log.fuelCost),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = EasyTheme.textPrimary
                    )
                    if (!isParking && log.logType == VehicleLogType.REFUELING && log.fuelAmountLiters > 0) {
                        Text(
                            text = "%.1f L".format(log.fuelAmountLiters),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = EasyTheme.textSecondary
                        )
                    }
                } else {
                    Text(
                        text = "%.1f km".format(log.tripDistanceKm),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = EasyTheme.textPrimary
                    )
                    Text(
                        text = badgeLabel,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = EasyTheme.textSecondary
                    )
                }
            }
        }
    }
}

@Composable
private fun EasyCarToolSheetItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = EasyTheme.SubtleBoxBg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(RoundedCornerShape(11.dp))
                        .background(Color.White),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = icon,
                        contentDescription = null,
                        tint = EasyTheme.tossBlue,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Column {
                    Text(
                        text = title,
                        fontSize = 14.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.textPrimary
                    )
                    Text(
                        text = subtitle,
                        fontSize = 12.sp,
                        color = EasyTheme.textSecondary
                    )
                }
            }
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = EasyTheme.textMuted
            )
        }
    }
}
