package com.autologue.app.presentation.diary

import com.autologue.app.presentation.diary.map.GoogleMapRouteView
import com.autologue.app.util.LocationDistanceUtils

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import coil.compose.AsyncImage
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.MonthlySummary
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.presentation.common.AutoLogueDangerButton
import com.autologue.app.presentation.common.AutoLogueOutlinedButton
import com.autologue.app.presentation.common.AutoLogueTextField
import com.autologue.app.presentation.common.AutoLoguePrimaryButton
import com.autologue.app.presentation.common.AutoLogueSecondaryButton
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.MetricBadge
import com.autologue.app.presentation.common.TopMenuAccentBar
import com.autologue.app.presentation.common.UnderlineTabBar
import com.autologue.app.presentation.theme.*
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

fun isNotificationAccessGranted(context: Context): Boolean {
    val pkgName = context.packageName
    val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
    return flat != null && flat.contains(pkgName)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiaryScreen(
    viewModel: DiaryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    var isNotificationEnabled by remember { mutableStateOf(isNotificationAccessGranted(context)) }
    var showNotificationGuideDialog by remember { mutableStateOf(false) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                val granted = isNotificationAccessGranted(context)
                if (granted != isNotificationEnabled) {
                    isNotificationEnabled = granted
                    if (granted) {
                        Toast.makeText(context, "카카오페이·토스 알림 자동 수집이 활성화되었습니다.", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val permissionsToRequest = remember {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.ACCESS_MEDIA_LOCATION
            )
        } else {
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.RECEIVE_SMS,
                Manifest.permission.READ_EXTERNAL_STORAGE
            )
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        val smsGranted = results[Manifest.permission.READ_SMS] == true ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val photoGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            results[Manifest.permission.READ_MEDIA_IMAGES] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            results[Manifest.permission.READ_EXTERNAL_STORAGE] == true ||
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        if (smsGranted || photoGranted) {
            viewModel.openManualSyncDialog()
        } else {
            Toast.makeText(context, "과거 기록을 불러오려면 권한 허용이 필요합니다.", Toast.LENGTH_LONG).show()
        }
    }

    val onSyncClick: () -> Unit = {
        val allGranted = permissionsToRequest.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
        if (allGranted) {
            viewModel.openManualSyncDialog()
        } else {
            permissionLauncher.launch(permissionsToRequest)
        }
    }

    val onExportExcelClick: () -> Unit = {
        viewModel.exportToExcel { result ->
            Toast.makeText(context, "엑셀 파일(${result.fileName})이 저장되었습니다.", Toast.LENGTH_LONG).show()
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                putExtra(Intent.EXTRA_STREAM, result.fileUri)
                putExtra(Intent.EXTRA_SUBJECT, "AutoLogue 라이프로그 데이터 내보내기")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(Intent.createChooser(shareIntent, "엑셀 파일 공유 및 열기"))
        }
    }

    LaunchedEffect(Unit) {
        val hasSms = ContextCompat.checkSelfPermission(context, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        val hasPhoto = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_IMAGES) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }
        if (hasSms || hasPhoto) {
            viewModel.autoSyncRecentWeek(context)
        }
    }

    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                TopMenuAccentBar(color = MenuColors.diary)
                TopAppBar(
                    windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                    title = {
                        Column {
                            Text(
                                text = "스마트 다이어리",
                                style = AppTypography.h2
                            )
                            Text(
                                text = "일상·동선 관리",
                                style = AppTypography.caption.copy(
                                    color = MenuColors.diary,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    },
                    actions = {
                        // Pill Date Capsule
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(AppShapes.pill)
                                .background(AppColors.surfaceVariant)
                                .padding(horizontal = Spacing.xs, vertical = Spacing.xxs)
                        ) {
                            IconButton(
                                onClick = { viewModel.navigatePrevious() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "이전", tint = AppColors.secondary, modifier = Modifier.size(16.dp))
                            }

                            Text(
                                text = when (uiState.viewMode) {
                                    TimelineViewMode.MONTHLY -> uiState.currentYearMonth.format(DateTimeFormatter.ofPattern("yy년 M월", Locale.KOREA))
                                    TimelineViewMode.WEEKLY -> {
                                        val start = uiState.selectedDate.minusDays(6)
                                        "${start.format(DateTimeFormatter.ofPattern("M.d", Locale.KOREA))} ~ ${uiState.selectedDate.format(DateTimeFormatter.ofPattern("M.d", Locale.KOREA))}"
                                    }
                                    else -> uiState.selectedDate.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA))
                                },
                                style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = AppColors.textPrimary),
                                modifier = Modifier.padding(horizontal = Spacing.xs)
                            )

                            IconButton(
                                onClick = { viewModel.navigateNext() },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "다음", tint = AppColors.secondary, modifier = Modifier.size(16.dp))
                            }
                        }

                        Spacer(modifier = Modifier.width(Spacing.sm))

                        IconButton(onClick = onExportExcelClick, modifier = Modifier.size(32.dp)) {
                            if (uiState.isExportingExcel) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp, color = AppColors.primary)
                            } else {
                                Icon(Icons.Default.FileDownload, contentDescription = "엑셀 내보내기", tint = AppColors.secondary, modifier = Modifier.size(18.dp))
                            }
                        }

                        IconButton(onClick = onSyncClick, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Sync, contentDescription = "과거 기록 동기화", tint = AppColors.secondary, modifier = Modifier.size(18.dp))
                        }

                        Spacer(modifier = Modifier.width(Spacing.md))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.background)
                )
                HairlineDivider()
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // Real-time Auto/Manual Syncing Banner
            if (uiState.isAutoSyncing) {
                SyncingBannerCard(stage = uiState.autoSyncStage)
            }

            // Contextual Notification Listener Bar
            if (!isNotificationEnabled) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(AppColors.surfaceVariant)
                        .clickable { showNotificationGuideDialog = true }
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "카카오페이·토스 푸시 자동 수집 활성화",
                        style = AppTypography.bodySecondary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "설정 안내 →",
                        style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.Bold)
                    )
                }
                HairlineDivider()
            }

            // Modern Underline Tab Bar
            UnderlineTabBar(
                tabs = listOf(
                    TimelineViewMode.DAILY to "타임라인",
                    TimelineViewMode.MAP_ROUTE to "지도 경로",
                    TimelineViewMode.WEEKLY to "주간 요약",
                    TimelineViewMode.MONTHLY to "월간 캘린더"
                ),
                selectedTab = uiState.viewMode,
                onTabSelected = { viewModel.setViewMode(it) }
            )
            HairlineDivider()

            when (uiState.viewMode) {
                TimelineViewMode.MAP_ROUTE -> {
                    GoogleMapRouteView(
                        routeSteps = uiState.mapRouteSteps,
                        selectedStep = uiState.selectedMapStep,
                        periodFilter = uiState.mapPeriodFilter,
                        onPeriodFilterChanged = { viewModel.setMapPeriodFilter(it) },
                        onStepSelected = { viewModel.selectMapStep(it) },
                        onPhotoClick = { viewModel.openPhotoPreview(it) },
                        onAddCompanionClick = { viewModel.openAddCompanionDialog(it) },
                        onRemoveCompanion = { stepId, name -> viewModel.removeCompanionFromStep(stepId, name) }
                    )
                }
                TimelineViewMode.MONTHLY -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .swipeDateNavigation(
                                key = uiState.currentYearMonth,
                                onSwipeLeft = { viewModel.navigateNext() },
                                onSwipeRight = { viewModel.navigatePrevious() }
                            )
                    ) {
                        MonthlyCalendarView(
                            monthlySummary = uiState.monthlyCalendarData,
                            selectedDate = uiState.selectedDate,
                            onDateClick = { viewModel.onCalendarDateClicked(it) }
                        )
                    }
                }
                TimelineViewMode.WEEKLY -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .swipeDateNavigation(
                                key = uiState.selectedDate,
                                onSwipeLeft = { viewModel.navigateNext() },
                                onSwipeRight = { viewModel.navigatePrevious() }
                            )
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = Spacing.xxxl)
                        ) {
                            item {
                                NaturalSummaryHeader(
                                    entries = uiState.entries,
                                    isWeekly = true,
                                    onOpenMapClick = { viewModel.setViewMode(TimelineViewMode.MAP_ROUTE) }
                                )
                                HairlineDivider()
                            }

                            if (uiState.entries.isEmpty()) {
                                item {
                                    EmptyTimelineState(
                                        title = "주간 다이어리가 없습니다",
                                        message = "선택한 주간에 기록된 활동이나 지출 내역이 없습니다.",
                                        onSyncClick = onSyncClick
                                    )
                                }
                            } else {
                                items(uiState.entries) { entry ->
                                    SaaSTimelineRow(
                                        entry = entry,
                                        onClick = { viewModel.openDiaryDetail(entry) },
                                        onPhotoClick = { viewModel.openPhotoPreview(it) },
                                        onMapClick = {
                                            viewModel.selectDate(entry.date.toLocalDate())
                                            viewModel.setViewMode(TimelineViewMode.MAP_ROUTE)
                                        }
                                    )
                                    HairlineDivider()
                                }
                            }
                        }
                    }
                }
                TimelineViewMode.DAILY -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .swipeDateNavigation(
                                key = uiState.selectedDate,
                                onSwipeLeft = { viewModel.navigateNext() },
                                onSwipeRight = { viewModel.navigatePrevious() }
                            )
                    ) {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(bottom = Spacing.xxxl)
                        ) {
                            item {
                                NaturalSummaryHeader(
                                    entries = uiState.entries,
                                    isWeekly = false,
                                    onOpenMapClick = { viewModel.setViewMode(TimelineViewMode.MAP_ROUTE) }
                                )
                                HairlineDivider()
                            }

                            if (uiState.entries.isEmpty()) {
                                item {
                                    EmptyTimelineState(
                                        title = "기록된 다이어리가 없습니다",
                                        message = "결제 문자나 사진이 수신되면 자동으로 이동 경로와 일상이 타임라인으로 기록됩니다.",
                                        onSyncClick = onSyncClick
                                    )
                                }
                            } else {
                                items(uiState.entries) { entry ->
                                    SaaSTimelineRow(
                                        entry = entry,
                                        onClick = { viewModel.openDiaryDetail(entry) },
                                        onPhotoClick = { viewModel.openPhotoPreview(it) },
                                        onMapClick = {
                                            viewModel.selectDate(entry.date.toLocalDate())
                                            viewModel.setViewMode(TimelineViewMode.MAP_ROUTE)
                                        }
                                    )
                                    HairlineDivider()
                                }
                            }
                        }
                    }
                }
            }
        }

        // Notification Guide Dialog
        if (showNotificationGuideDialog) {
            AlertDialog(
                onDismissRequest = { showNotificationGuideDialog = false },
                title = {
                    Text(
                        text = "알림 접근 허용 안내",
                        style = AppTypography.h2
                    )
                },
                text = {
                    Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        Text(
                            text = "카카오페이, 토스 및 카드사 앱의 결제 알림을 실시간으로 수집하여 가계부와 다이어리를 자동 작성합니다.",
                            style = AppTypography.bodySecondary
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = """
                                📌 설정 방법:
                                1. 아래 [설정으로 이동]을 누릅니다.
                                2. 설정 목록에서 'AutoLogue'를 누릅니다.
                                3. '알림 접근 허용' 스위치를 켭니다.
                            """.trimIndent(),
                            style = AppTypography.body.copy(fontWeight = FontWeight.Medium)
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showNotificationGuideDialog = false
                            try {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            } catch (e: Exception) {
                                Toast.makeText(context, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary, contentColor = PureWhite),
                        shape = AppShapes.button
                    ) {
                        Text("설정으로 이동", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showNotificationGuideDialog = false }) {
                        Text("닫기", fontSize = 12.sp, color = AppColors.secondary)
                    }
                },
                containerColor = AppColors.surface,
                shape = AppShapes.modal
            )
        }

        // Manual Sync Period Dialog
        if (uiState.isManualSyncDialogOpen) {
            ManualSyncPeriodDialog(
                onDismiss = { viewModel.closeManualSyncDialog() },
                onConfirm = { days ->
                    viewModel.executeManualSync(daysBack = days, context = context)
                }
            )
        }

        // Sync Progress Dialog
        if (uiState.isSyncDialogVisible && uiState.syncProgress != null) {
            SyncProgressDialog(
                progress = uiState.syncProgress!!,
                onDismiss = { viewModel.dismissSyncDialog() }
            )
        }

        // Diary Detail & Movement Route Dialog
        uiState.selectedDiaryDetail?.let { detailEntry ->
            DiaryDetailDialog(
                entry = detailEntry,
                onDismiss = { viewModel.closeDiaryDetail() },
                onPhotoClick = { viewModel.openPhotoPreview(it) },
                onAddCompanionClick = { viewModel.openAddCompanionDialog(it) },
                onRemoveCompanion = { stepId, name -> viewModel.removeCompanionFromStep(stepId, name) },
                onUpdateNote = { id, title, note -> viewModel.updateDiaryNote(id, title, note) }
            )
        }

        // 특정 지점 동행인 관리 팝업
        val targetStep = uiState.companionTargetStep
        if (uiState.isAddCompanionDialogOpen && targetStep != null) {
            AddCompanionDialog(
                step = targetStep,
                onAddCompanion = { viewModel.addCompanionToStep(it) },
                onRemoveCompanion = { stepId, name -> viewModel.removeCompanionFromStep(stepId, name) },
                onDismiss = { viewModel.closeAddCompanionDialog() }
            )
        }

        // Large Photo Preview Dialog with Prev/Next Navigation and Delete
        if (uiState.selectedPhotoPreviewUrl != null && uiState.photoPreviewList.isNotEmpty()) {
            PhotoPreviewDialog(
                photoList = uiState.photoPreviewList,
                currentIndex = uiState.photoPreviewIndex,
                onIndexChanged = { viewModel.setPhotoPreviewIndex(it) },
                onDeletePhoto = { viewModel.deletePhoto(it) },
                onDismiss = { viewModel.closePhotoPreview() }
            )
        }
    }
}

@Composable
fun NaturalSummaryHeader(
    entries: List<DiaryEntry>,
    isWeekly: Boolean = false,
    onOpenMapClick: (() -> Unit)? = null
) {
    // [M-04] 무거운 집계 연산을 remember(entries)로 캐싱
    //   entries가 변경될 때만 재계산되고, 부모 리컴포지션 시에는 캐시된 값을 재사용합니다.
    val totalExpense = remember(entries) { entries.sumOf { it.totalExpense } }
    val totalDistance = remember(entries) {
        entries.sumOf {
            if (it.drivingDistanceKm > 0) it.drivingDistanceKm
            else LocationDistanceUtils.calculateRouteDrivingDistanceKm(it.routeSteps)
        }
    }
    val totalPlaces = remember(entries) {
        entries.flatMap { entry ->
            if (entry.routeSteps.isNotEmpty()) {
                entry.routeSteps.mapNotNull { it.locationName ?: it.title }
            } else {
                listOfNotNull(entry.placeName)
            }
        }.distinct().size
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.background)
            .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(if (isWeekly) "주간 총 지출" else "오늘의 총 지출", style = AppTypography.caption)
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                text = "%,d원".format(totalExpense),
                style = AppTypography.display.copy(
                    color = if (totalExpense > 0) AppColors.textPrimary else AppColors.textMuted
                )
            )
        }

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
            Column(horizontalAlignment = Alignment.End) {
                Text("주행 거리", style = AppTypography.caption)
                Spacer(modifier = Modifier.height(Spacing.xxs))
                Text(
                    text = "%.1f km".format(totalDistance),
                    style = AppTypography.h2
                )
            }

            Column(horizontalAlignment = Alignment.End) {
                Text("방문 장소", style = AppTypography.caption)
                Spacer(modifier = Modifier.height(Spacing.xxs))
                Text(
                    text = "%d곳".format(totalPlaces),
                    style = AppTypography.h2
                )
            }
        }
    }
}

@Composable
fun SaaSTimelineRow(
    entry: DiaryEntry,
    onClick: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onMapClick: (() -> Unit)? = null
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(AppColors.background)
            .clickable { onClick() }
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
    ) {
        // Title & Amount/Badge Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.title,
                style = AppTypography.h2,
                modifier = Modifier.weight(1f)
            )

            if (entry.totalExpense > 0) {
                Text(
                    text = "-%,d원".format(entry.totalExpense),
                    style = AppTypography.h3
                )
            } else if (entry.hasGolfRound) {
                MetricBadge(text = "골프 라운드", textColor = AppColors.successText, backgroundColor = AppColors.successBg)
            }
        }

        Spacer(modifier = Modifier.height(Spacing.xxs))

        // Time, Date & Rep Location Row
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = entry.date.format(DateTimeFormatter.ofPattern("M.d(E) HH:mm", Locale.KOREA)),
                    style = AppTypography.captionMuted
                )
                if (!entry.placeName.isNullOrEmpty()) {
                    Text(
                        text = " · ${entry.placeName}",
                        style = AppTypography.caption
                    )
                }
            }

            val displayKm = if (entry.drivingDistanceKm > 0) entry.drivingDistanceKm else LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
            if (displayKm > 0) {
                Text(
                    text = "%.1f km 주행".format(displayKm),
                    style = AppTypography.caption
                )
            }
        }

        // Movement Flow Strip (Itinerary Chain)
        if (!entry.movementSummary.isNullOrBlank() && entry.movementSummary != "기록된 활동 없음") {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                val places = entry.movementSummary.split(" ➔ ")
                places.forEachIndexed { idx, place ->
                    Box(
                        modifier = Modifier
                            .clip(AppShapes.tag)
                            .background(AppColors.surfaceVariant)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text(
                            text = "📍 $place",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = AppColors.secondary
                        )
                    }
                    if (idx < places.size - 1) {
                        Text("➔", fontSize = 10.sp, color = AppColors.textMuted)
                    }
                }
            }
        }

        // Photo Thumbnail Row (Up to 4 thumbnails)
        if (entry.photoUris.isNotEmpty()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val previewPhotos = entry.photoUris.take(4)
                val remaining = entry.photoUris.size - 4
                previewPhotos.forEachIndexed { index, uri ->
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .border(0.5.dp, AppColors.border, RoundedCornerShape(8.dp))
                            .clickable { onPhotoClick(uri) }
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = "사진 썸네일",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        if (index == 3 && remaining > 0) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(Color.Black.copy(alpha = 0.55f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "+$remaining",
                                    color = PureWhite,
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // Narrative Summary
        if (entry.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(Spacing.xs))
            Text(
                text = entry.summary,
                style = AppTypography.bodySecondary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = "상세 일정 및 이동 경로 확인 →",
            style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.SemiBold)
        )
    }
}

@Composable
fun DiaryDetailDialog(
    entry: DiaryEntry,
    onDismiss: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onAddCompanionClick: ((RouteStep) -> Unit)? = null,
    onRemoveCompanion: ((String, String) -> Unit)? = null,
    onUpdateNote: (Long, String, String) -> Unit
) {
    var isEditing by remember { mutableStateOf(false) }
    var editTitle by remember { mutableStateOf(entry.title) }
    var editSummary by remember { mutableStateOf(entry.summary) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .imePadding()
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .fillMaxHeight(0.88f)
                    .clip(AppShapes.modal)
                    .background(AppColors.surface),
                color = AppColors.surface,
                shape = AppShapes.modal,
                tonalElevation = 6.dp
            ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.lg)
            ) {
                // Modal Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = entry.date.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREA)),
                            style = AppTypography.captionMuted
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        if (isEditing) {
                            OutlinedTextField(
                                value = editTitle,
                                onValueChange = { editTitle = it },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        } else {
                            Text(
                                text = entry.title,
                                style = AppTypography.h2
                            )
                        }
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier.size(32.dp)
                    ) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = AppColors.textSecondary)
                    }
                }

                HairlineDivider(modifier = Modifier.padding(vertical = Spacing.sm))

                // Scrollable Body
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // Quick Stats Row
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(AppShapes.card)
                            .background(Slate50)
                            .padding(vertical = Spacing.sm, horizontal = Spacing.md),
                        horizontalArrangement = Arrangement.SpaceAround
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("지출 금액", style = AppTypography.caption)
                            Text("%,d원".format(entry.totalExpense), style = AppTypography.h3.copy(color = if (entry.totalExpense > 0) AppColors.errorText else AppColors.textPrimary))
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("방문 장소", style = AppTypography.caption)
                            Text("${entry.routeSteps.size}곳", style = AppTypography.h3)
                        }
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            val displayKm = if (entry.drivingDistanceKm > 0) entry.drivingDistanceKm else LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
                            Text("주행거리", style = AppTypography.caption)
                            Text("%.1f km".format(displayKm), style = AppTypography.h3)
                        }
                    }

                    // Movement Summary Chain
                    if (!entry.movementSummary.isNullOrBlank() && entry.movementSummary != "기록된 활동 없음") {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(AppShapes.card)
                                .border(0.5.dp, AppColors.border, AppShapes.card)
                                .padding(Spacing.sm)
                        ) {
                            Text("🗺️ 오늘의 이동 동선", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = entry.movementSummary,
                                style = AppTypography.body.copy(color = AppColors.primary, fontWeight = FontWeight.Medium)
                            )
                        }
                    }

                    // Chronological Vertical Timeline Stepper
                    Text("📍 시간대별 상세 이동 경로 및 활동", style = AppTypography.h3)

                    if (entry.routeSteps.isEmpty()) {
                        Text(
                            text = "세부 경로 데이터가 없습니다. 상단 동기화 버튼을 눌러 다시 색인해보세요.",
                            style = AppTypography.bodySecondary
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            entry.routeSteps.forEachIndexed { index, step ->
                                VerticalTimelineStepItem(
                                    step = step,
                                    isLast = index == entry.routeSteps.size - 1,
                                    onPhotoClick = onPhotoClick,
                                    onAddCompanionClick = onAddCompanionClick,
                                    onRemoveCompanion = onRemoveCompanion
                                )
                            }
                        }
                    }

                    // Notes / Summary Section
                    HairlineDivider(modifier = Modifier.padding(vertical = Spacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("📝 다이어리 메모", style = AppTypography.h3)
                        TextButton(onClick = {
                            if (isEditing) {
                                onUpdateNote(entry.id, editTitle, editSummary)
                                isEditing = false
                            } else {
                                isEditing = true
                            }
                        }) {
                            Text(if (isEditing) "저장 완료" else "수정하기", fontSize = 12.sp, color = AppColors.primary)
                        }
                    }

                    if (isEditing) {
                        OutlinedTextField(
                            value = editSummary,
                            onValueChange = { editSummary = it },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 3
                        )
                    } else {
                        Text(
                            text = entry.summary.ifBlank { "작성된 다이어리 메모가 없습니다." },
                            style = AppTypography.bodySecondary
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
fun VerticalTimelineStepItem(
    step: RouteStep,
    isLast: Boolean,
    onPhotoClick: (String) -> Unit,
    onAddCompanionClick: ((RouteStep) -> Unit)? = null,
    onRemoveCompanion: ((String, String) -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
    ) {
        // Left Column: Node Icon & Vertical Connector Line
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.width(28.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(
                        when (step.stepType) {
                            RouteStepType.TRANSACTION -> AppColors.primary
                            RouteStepType.PHOTO -> Emerald500
                            RouteStepType.GOLF -> Emerald600
                            RouteStepType.DRIVING -> Slate600
                            RouteStepType.MEMO -> Color(0xFFF59E0B)
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(step.stepType.iconEmoji, fontSize = 11.sp)
            }

            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(64.dp)
                        .background(Slate200)
                )
            }
        }

        // Right Column: Step Content
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(bottom = if (isLast) Spacing.sm else Spacing.md)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = step.title,
                    style = AppTypography.body.copy(fontWeight = FontWeight.Bold)
                )
                Text(
                    text = step.time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)),
                    style = AppTypography.captionMuted
                )
            }

            if (!step.description.isNullOrBlank()) {
                Text(
                    text = step.description,
                    style = AppTypography.bodySecondary.copy(fontSize = 12.sp)
                )
            }

            if (!step.address.isNullOrBlank()) {
                Text(
                    text = "📍 ${step.address}",
                    style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                )
            }

            // Companions and Add Companion button for this specific step
            Row(
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 3.dp)
            ) {
                step.companions.forEach { companion ->
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(AppShapes.pill)
                            .background(Indigo50)
                            .border(0.5.dp, Indigo600.copy(alpha = 0.3f), AppShapes.pill)
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("👤", fontSize = 9.sp)
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(companion, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Indigo700)
                        if (onRemoveCompanion != null) {
                            Spacer(modifier = Modifier.width(3.dp))
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "동행인 삭제",
                                tint = Indigo600,
                                modifier = Modifier
                                    .size(10.dp)
                                    .clickable { onRemoveCompanion(step.id, companion) }
                            )
                        }
                    }
                }

                // 특정 지점에 동행인 추가하는 버튼
                if (onAddCompanionClick != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .clip(AppShapes.pill)
                            .background(Slate100)
                            .clickable { onAddCompanionClick(step) }
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAdd,
                            contentDescription = "동행인 추가",
                            modifier = Modifier.size(11.dp),
                            tint = AppColors.primary
                        )
                        Spacer(modifier = Modifier.width(2.dp))
                        Text(
                            text = if (step.companions.isEmpty()) "+ 동행인" else "+",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = AppColors.primary
                        )
                    }
                }
            }

            // Photos associated with this step
            if (step.photoUris.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    items(step.photoUris) { uri ->
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .border(0.5.dp, AppColors.border, RoundedCornerShape(6.dp))
                                .clickable { onPhotoClick(uri) }
                        ) {
                            AsyncImage(
                                model = uri,
                                contentDescription = "타임라인 사진",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun PhotoPreviewDialog(
    photoList: List<String>,
    currentIndex: Int,
    onIndexChanged: (Int) -> Unit,
    onDeletePhoto: ((String) -> Unit)? = null,
    onDismiss: () -> Unit
) {
    if (photoList.isEmpty()) return

    val totalCount = photoList.size
    val safeIndex = currentIndex.coerceIn(0, totalCount - 1)
    val currentPhotoUrl = photoList[safeIndex]

    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.88f)
                .clip(RoundedCornerShape(16.dp))
                .background(Color(0xFF0A0A0A))
                .pointerInput(safeIndex, totalCount) {
                    detectHorizontalDragGestures { _, dragAmount ->
                        if (dragAmount > 50) {
                            // 오른쪽 드래그 -> 이전 사진
                            if (totalCount > 1) {
                                val prevIdx = if (safeIndex - 1 < 0) totalCount - 1 else safeIndex - 1
                                onIndexChanged(prevIdx)
                            }
                        } else if (dragAmount < -50) {
                            // 왼쪽 드래그 -> 다음 사진
                            if (totalCount > 1) {
                                val nextIdx = (safeIndex + 1) % totalCount
                                onIndexChanged(nextIdx)
                            }
                        }
                    }
                }
        ) {
            // 메인 사진 뷰어
            AsyncImage(
                model = currentPhotoUrl,
                contentDescription = "사진 원본 확대 (${safeIndex + 1}/$totalCount)",
                modifier = Modifier
                    .fillMaxSize()
                    .padding(vertical = 52.dp, horizontal = 8.dp),
                contentScale = ContentScale.Fit
            )

            // 상단 컨트롤 바 (사진 순번 카운터, 삭제 휴지통 버튼 & 닫기 X 버튼)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopCenter)
                    .background(
                        Brush.verticalGradient(
                            colors = listOf(Color.Black.copy(alpha = 0.75f), Color.Transparent)
                        )
                    )
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // 사진 번호 뱃지 (예: 2 / 5)
                if (totalCount > 1) {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color.White.copy(alpha = 0.2f),
                        contentColor = PureWhite
                    ) {
                        Text(
                            text = "${safeIndex + 1} / $totalCount",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                } else {
                    Spacer(modifier = Modifier.width(1.dp))
                }

                // 우측 버튼 모음 (삭제 휴지통 버튼 & 닫기 X 버튼)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 사진 삭제 휴지통 버튼
                    if (onDeletePhoto != null) {
                        IconButton(
                            onClick = { showDeleteConfirmDialog = true },
                            modifier = Modifier
                                .size(36.dp)
                                .background(Color.Red.copy(alpha = 0.35f), CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.DeleteOutline,
                                contentDescription = "사진 삭제 및 영구 제외",
                                tint = PureWhite,
                                modifier = Modifier.size(20.dp)
                            )
                        }
                    }

                    // 닫기 (X) 버튼
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .background(Color.White.copy(alpha = 0.25f), CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "닫기",
                            tint = PureWhite,
                            modifier = Modifier.size(20.dp)
                        )
                    }
                }
            }

            // 삭제 확인 대화상자
            if (showDeleteConfirmDialog && onDeletePhoto != null) {
                AlertDialog(
                    onDismissRequest = { showDeleteConfirmDialog = false },
                    title = {
                        Text(
                            text = "사진 기록 삭제",
                            style = AppTypography.h3,
                            color = AppColors.textPrimary
                        )
                    },
                    text = {
                        Text(
                            text = "이 사진을 다이어리 기록에서 삭제하시겠습니까?\n\n※ 불필요한 캡처 화면이나 영수증 등을 삭제하면, 추후 재동기화가 진행되더라도 다시 추가되지 않도록 영구 제외 처리됩니다.",
                            style = AppTypography.bodySecondary,
                            color = AppColors.textSecondary
                        )
                    },
                    confirmButton = {
                        TextButton(
                            onClick = {
                                showDeleteConfirmDialog = false
                                onDeletePhoto(currentPhotoUrl)
                            }
                        ) {
                            Text(
                                text = "삭제",
                                color = AppColors.errorText,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    },
                    dismissButton = {
                        TextButton(onClick = { showDeleteConfirmDialog = false }) {
                            Text(text = "취소", color = AppColors.textMuted)
                        }
                    },
                    shape = RoundedCornerShape(16.dp),
                    containerColor = PureWhite
                )
            }

            // 좌측 이전 사진 (<) 버튼
            if (totalCount > 1) {
                IconButton(
                    onClick = {
                        val prevIdx = if (safeIndex - 1 < 0) totalCount - 1 else safeIndex - 1
                        onIndexChanged(prevIdx)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterStart)
                        .padding(start = 12.dp)
                        .size(46.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "이전 사진",
                        tint = PureWhite,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }

            // 우측 다음 사진 (>) 버튼
            if (totalCount > 1) {
                IconButton(
                    onClick = {
                        val nextIdx = (safeIndex + 1) % totalCount
                        onIndexChanged(nextIdx)
                    },
                    modifier = Modifier
                        .align(Alignment.CenterEnd)
                        .padding(end = 12.dp)
                        .size(46.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape)
                        .border(1.dp, Color.White.copy(alpha = 0.25f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "다음 사진",
                        tint = PureWhite,
                        modifier = Modifier.size(30.dp)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddCompanionDialog(
    step: RouteStep,
    onAddCompanion: (String) -> Unit,
    onRemoveCompanion: (String, String) -> Unit,
    onDismiss: () -> Unit
) {
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(decorFitsSystemWindows = false),
        modifier = Modifier.imePadding(),
        title = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = AppColors.primary,
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("특정 지점 동행인 관리", style = AppTypography.h2)
                }
                Spacer(modifier = Modifier.height(6.dp))
                // 특정 지점 정보 명시
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Slate100,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)) {
                        Text(
                            text = "📍 ${step.locationName ?: step.title} (${step.time.format(DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA))})",
                            style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.Bold)
                        )
                        if (!step.address.isNullOrBlank()) {
                            Text(
                                text = step.address,
                                style = AppTypography.captionMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = "※ 해당 지점(방문 장소)에 함께 있었던 동행인만 개별 등록됩니다.",
                    style = AppTypography.captionMuted
                )
                Spacer(modifier = Modifier.height(Spacing.sm))

                // 현재 등록된 동행인 목록
                if (step.companions.isNotEmpty()) {
                    Text("현재 등록된 동행인:", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                    Spacer(modifier = Modifier.height(4.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        step.companions.forEach { companion ->
                            Surface(
                                shape = AppShapes.pill,
                                color = Indigo50,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, Indigo600.copy(alpha = 0.4f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                ) {
                                    Text("👤 $companion", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Indigo700)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Icon(
                                        imageVector = Icons.Default.Close,
                                        contentDescription = "삭제",
                                        tint = Indigo600,
                                        modifier = Modifier
                                            .size(12.dp)
                                            .clickable { onRemoveCompanion(step.id, companion) }
                                    )
                                }
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.md))
                }

                // 새 동행인 추가 입력창
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        placeholder = { Text("동행인 이름 (예: 김철수, 대표님)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                        textStyle = AppTypography.body
                    )
                    Button(
                        onClick = {
                            if (newName.isNotBlank()) {
                                onAddCompanion(newName.trim())
                                newName = ""
                            }
                        },
                        enabled = newName.isNotBlank(),
                        shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 0.dp),
                        modifier = Modifier.height(48.dp)
                    ) {
                        Text("추가", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", style = AppTypography.body.copy(fontWeight = FontWeight.Bold, color = AppColors.primary))
            }
        },
        shape = RoundedCornerShape(16.dp),
        containerColor = PureWhite
    )
}


@Composable
fun MonthlyCalendarView(
    monthlySummary: MonthlySummary?,
    selectedDate: LocalDate,
    onDateClick: (LocalDate) -> Unit
) {
    if (monthlySummary == null) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = AppColors.primary, strokeWidth = 2.dp)
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(AppColors.background)
    ) {
        // Summary Strip
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.md),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text("월간 총 지출", style = AppTypography.caption)
                Text(
                    "%,d원".format(monthlySummary.totalExpense),
                    style = AppTypography.h1
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.lg)) {
                Column(horizontalAlignment = Alignment.End) {
                    Text("라운드", style = AppTypography.caption)
                    Text("${monthlySummary.totalGolfRounds}회", style = AppTypography.h2)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text("주행 거리", style = AppTypography.caption)
                    Text("%.1f km".format(monthlySummary.totalDistanceKm), style = AppTypography.h2)
                }
            }
        }
        HairlineDivider()

        // Weekday Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = Spacing.sm),
            horizontalArrangement = Arrangement.SpaceAround
        ) {
            listOf("일", "월", "화", "수", "목", "금", "토").forEachIndexed { idx, day ->
                Text(
                    text = day,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = if (idx == 0) AppColors.errorText else AppColors.textMuted,
                    modifier = Modifier.weight(1f),
                    textAlign = TextAlign.Center
                )
            }
        }
        HairlineDivider()

        val yearMonth = monthlySummary.yearMonth
        val firstDayOfMonth = yearMonth.atDay(1)
        val firstDayOfWeek = firstDayOfMonth.dayOfWeek.value % 7
        val daysInMonth = yearMonth.lengthOfMonth()

        val calendarCells = mutableListOf<LocalDate?>()
        repeat(firstDayOfWeek) { calendarCells.add(null) }
        for (day in 1..daysInMonth) {
            calendarCells.add(yearMonth.atDay(day))
        }
        while (calendarCells.size % 7 != 0) {
            calendarCells.add(null)
        }

        val weeks = calendarCells.chunked(7)

        // Dynamically fill 100% of remaining vertical screen space
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = Spacing.xs, vertical = 4.dp)
        ) {
            weeks.forEach { week ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                ) {
                    week.forEach { date ->
                        if (date != null) {
                            val summary = monthlySummary.days[date]
                            val isSelected = date == selectedDate
                            val isToday = date == LocalDate.now()

                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                                    .border(0.5.dp, Slate100)
                                    .background(if (isToday && !isSelected) Slate50 else PureWhite)
                                    .clickable { onDateClick(date) }
                                    .padding(horizontal = 2.dp, vertical = 4.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.SpaceBetween,
                                    modifier = Modifier.fillMaxSize()
                                ) {
                                    // Circular Selection Badge
                                    Box(
                                        modifier = Modifier
                                            .size(22.dp)
                                            .clip(CircleShape)
                                            .background(if (isSelected) AppColors.primary else Color.Transparent),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "${date.dayOfMonth}",
                                            fontSize = 11.sp,
                                            fontWeight = if (isSelected || isToday) FontWeight.Bold else FontWeight.Medium,
                                            color = when {
                                                isSelected -> PureWhite
                                                date.dayOfWeek == DayOfWeek.SUNDAY -> AppColors.errorText
                                                else -> AppColors.textPrimary
                                            }
                                        )
                                    }

                                    if (summary != null && summary.totalExpense > 0) {
                                        val text = if (summary.totalExpense >= 10000) {
                                            "%.1f만".format(summary.totalExpense / 10000.0)
                                        } else {
                                            "%,d".format(summary.totalExpense)
                                        }
                                        Text(
                                            text = text,
                                            fontSize = 9.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = AppColors.primary
                                        )
                                    } else {
                                        Spacer(modifier = Modifier.height(1.dp))
                                    }

                                    Row(
                                        horizontalArrangement = Arrangement.Center,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (summary?.hasGolfRound == true) Text("⛳", fontSize = 9.sp)
                                        if (summary?.hasRefueling == true) Text("⛽", fontSize = 9.sp)
                                    }
                                }
                            }
                        } else {
                            Spacer(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxHeight()
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyTimelineState(
    title: String = "기록된 다이어리가 없습니다",
    message: String = "결제 문자나 사진이 수신되면 자동으로 이동 경로와 일상이 타임라인으로 기록됩니다.",
    onSyncClick: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = Spacing.xxxl, horizontal = Spacing.xxl),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = title,
            style = AppTypography.h2
        )
        Spacer(modifier = Modifier.height(Spacing.xs))
        Text(
            text = message,
            style = AppTypography.bodySecondary,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(Spacing.lg))
        AutoLogueOutlinedButton(
            text = "과거 기록 불러오기",
            icon = Icons.Default.Sync,
            onClick = onSyncClick
        )
    }
}

fun Modifier.swipeDateNavigation(
    key: Any? = Unit,
    enabled: Boolean = true,
    onSwipeLeft: () -> Unit,
    onSwipeRight: () -> Unit
): Modifier = if (!enabled) this else pointerInput(key) {
    var totalDrag = 0f
    val threshold = 50.dp.toPx()
    detectHorizontalDragGestures(
        onDragStart = { totalDrag = 0f },
        onDragEnd = {
            if (totalDrag < -threshold) {
                onSwipeLeft()
            } else if (totalDrag > threshold) {
                onSwipeRight()
            }
            totalDrag = 0f
        },
        onDragCancel = { totalDrag = 0f },
        onHorizontalDrag = { _, dragAmount ->
            totalDrag += dragAmount
        }
    )
}

@Composable
fun SyncProgressDialog(
    progress: com.autologue.app.domain.usecase.sync.SyncProgress,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                if (progress.isDone) "동기화 완료" else "동기화 진행 중",
                style = AppTypography.h2
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                if (!progress.isDone) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth(),
                        color = AppColors.primary,
                        trackColor = Slate100
                    )
                }
                Text(progress.stage, style = AppTypography.bodySecondary)
                if (progress.syncedTxCount > 0 || progress.syncedPhotoCount > 0) {
                    Text(
                        "• 결제 ${progress.syncedTxCount}건 / 사진 ${progress.syncedPhotoCount}장 색인됨",
                        style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold)
                    )
                }
            }
        },
        confirmButton = {
            if (progress.isDone) {
                Button(
                    onClick = onDismiss,
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary, contentColor = PureWhite),
                    shape = AppShapes.button
                ) {
                    Text("확인", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        },
        containerColor = AppColors.surface,
        shape = AppShapes.modal
    )
}

@Composable
fun SyncingBannerCard(
    stage: String,
    modifier: Modifier = Modifier
) {
    Surface(
        color = AppColors.surfaceVariant,
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
        ) {
            CircularProgressIndicator(
                modifier = Modifier.size(16.dp),
                strokeWidth = 2.dp,
                color = AppColors.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "일상 기록 동기화 중...",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = AppColors.textPrimary)
                )
                Text(
                    text = stage.ifBlank { "데이터를 분석하고 있습니다..." },
                    style = AppTypography.captionMuted,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
    HairlineDivider()
}

@Composable
fun ManualSyncPeriodDialog(
    onDismiss: () -> Unit,
    onConfirm: (Int) -> Unit
) {
    var selectedDays by remember { mutableStateOf(7) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "과거 기록 동기화",
                style = AppTypography.h2
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.md)) {
                Text(
                    text = "결제 문자(SMS) 및 사진 위치 정보를 분석하여 타임라인과 이동 경로를 복원합니다.\n동기화할 기간을 선택하세요.",
                    style = AppTypography.bodySecondary
                )

                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(Spacing.xs)
                ) {
                    val periods = listOf(
                        7 to "최근 7일 (추천 · 빠르고 안전)",
                        14 to "최근 14일 (2주치 일상 복원)",
                        30 to "최근 30일 (최대 1달치 분석)"
                    )

                    periods.forEach { (days, label) ->
                        val isSelected = selectedDays == days
                        Surface(
                            shape = AppShapes.card,
                            color = if (isSelected) AppColors.primary.copy(alpha = 0.08f) else AppColors.surface,
                            border = androidx.compose.foundation.BorderStroke(
                                1.dp,
                                if (isSelected) AppColors.primary else AppColors.border
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { selectedDays = days }
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.md, vertical = Spacing.sm),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { selectedDays = days },
                                    colors = RadioButtonDefaults.colors(
                                        selectedColor = AppColors.primary,
                                        unselectedColor = AppColors.secondary
                                    )
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(
                                    text = label,
                                    style = AppTypography.body.copy(
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                        color = if (isSelected) AppColors.primary else AppColors.textPrimary
                                    )
                                )
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(selectedDays) },
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary, contentColor = PureWhite),
                shape = AppShapes.button
            ) {
                Text("동기화 시작", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", fontSize = 12.sp, color = AppColors.secondary)
            }
        },
        containerColor = AppColors.surface,
        shape = AppShapes.modal
    )
}

