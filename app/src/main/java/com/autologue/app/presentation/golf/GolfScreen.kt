package com.autologue.app.presentation.golf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.Group
import androidx.compose.material.icons.filled.Schedule

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.autologue.app.domain.model.GolfPlayWeather
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.presentation.common.AutoLogueTextField
import com.autologue.app.presentation.common.AutoLogueCompactInputRow
import com.autologue.app.presentation.common.AutoLogueActionChipButton
import com.autologue.app.presentation.common.AutoLoguePrimaryButton
import com.autologue.app.presentation.common.AutoLogueSecondaryButton
import com.autologue.app.presentation.common.AutoLogueDangerButton
import com.autologue.app.presentation.common.AutoLogueOutlinedButton
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.MetricBadge
import com.autologue.app.presentation.common.TopMenuAccentBar
import com.autologue.app.presentation.theme.*
import java.time.Duration
import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.ui.platform.LocalView
import java.time.temporal.ChronoUnit
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GolfScreen(
    viewModel: GolfViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // 화면 새로 로딩/진입 시 최신 날씨 정보 자동 갱신
    LaunchedEffect(Unit) {
        if (uiState.rounds.isNotEmpty()) {
            viewModel.loadWeatherForRounds(uiState.rounds, forceRefresh = true)
        }
    }

    // Launcher for scanning golf locker slip receipt directly from TopAppBar
    val globalLockerSlipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.scanGolfLockerSlip(uri)
        }
    }

    // Launcher for scanning scorecard photo directly from TopAppBar
    val globalScorecardPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetRound = uiState.selectedRound ?: uiState.rounds.firstOrNull()
            if (targetRound != null) {
                viewModel.scanScorecard(targetRound.id, uri)
            }
        }
    }

    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                TopMenuAccentBar(color = MenuColors.golf)
                TopAppBar(
                    windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                    title = {
                        Column {
                            Text(
                                text = "스마트 골프",
                                style = AppTypography.h2
                            )
                            Text(
                                text = "라운드·스코어 관리",
                                style = AppTypography.caption.copy(
                                    color = MenuColors.golf,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    },
                    actions = {
                        AutoLogueOutlinedButton(
                            text = "예약 추가",
                            icon = Icons.Default.AddCircle,
                            onClick = { viewModel.openReservationDialog() },
                            contentColor = MenuColors.golf,
                            containerColor = MenuColors.golfBg,
                            borderColor = MenuColors.golfBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "라커룸 스캔",
                            icon = Icons.Default.ReceiptLong,
                            onClick = {
                                globalLockerSlipPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            contentColor = MenuColors.golf,
                            containerColor = MenuColors.golfBg,
                            borderColor = MenuColors.golfBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "스코어카드 스캔",
                            icon = Icons.Default.CameraAlt,
                            onClick = {
                                globalScorecardPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            },
                            contentColor = MenuColors.golf,
                            containerColor = MenuColors.golfBg,
                            borderColor = MenuColors.golfBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.lg))
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
            // Level 1: Natural Golf Stats
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("평균 타수", style = AppTypography.caption)
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        Text(
                            if (uiState.averageScore > 0) "%.1f타".format(uiState.averageScore) else "-타",
                            style = AppTypography.display
                        )
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xl)) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text("라베(최저타)", style = AppTypography.caption)
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            Text(
                                if (uiState.bestScore != null) "${uiState.bestScore}타" else "-타",
                                style = AppTypography.h2.copy(color = AppColors.successText)
                            )
                        }

                        Column(horizontalAlignment = Alignment.End) {
                            Text("총 라운드", style = AppTypography.caption)
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            Text(
                                "${uiState.rounds.size}회",
                                style = AppTypography.h2
                            )
                        }
                    }
                }
                HairlineDivider()
            }


            // Level 1-A: Upcoming Golf Reservations with D-Day & KakaoGolf Weather
            if (uiState.upcomingReservations.isNotEmpty()) {
                item {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xs)
                    ) {
                        Text(
                            text = "⛳ 다가오는 라운드 예약 (${uiState.upcomingReservations.size}건)",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                        )
                        Spacer(modifier = Modifier.height(Spacing.xs))

                        uiState.upcomingReservations.forEach { res ->
                            UpcomingGolfCard(
                                round = res,
                                weather = uiState.weatherMap[res.id],
                                onOpenDutchPay = { viewModel.openDutchPayDialog(res) },
                                onScanLocker = {
                                    globalLockerSlipPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onViewWeatherDetail = { viewModel.openWeatherDetail(res) },
                                onClick = { viewModel.selectRound(res) }
                            )
                            Spacer(modifier = Modifier.height(Spacing.xs))
                        }
                    }
                    HairlineDivider()
                }
            }

            // Level 1-B: SmartScore Deep Analytics Dashboard
            item {
                SmartScoreAnalyticsCard()
                Spacer(modifier = Modifier.height(Spacing.xs))
                HairlineDivider()
            }

            // Score Trend & Handicap Analytics Card
            item {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = Color(0xFFF8FAFC),
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.xs)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📈 최근 라운드 스코어 트렌드",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF1E293B)
                            )
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFECFDF5),
                                border = BorderStroke(1.dp, Color(0xFFA7F3D0))
                            ) {
                                Text(
                                    text = "안정적 80대 타수 유지",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF059669),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceAround,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            val trendData = listOf(
                                Triple("남촌CC", 88, "8.31"),
                                Triple("아리지", 90, "6.20"),
                                Triple("필로스", 89, "8.09"),
                                Triple("라데나", 87, "8.21")
                            )
                            trendData.forEach { (course, score, date) ->
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.Bottom
                                ) {
                                    Text(
                                        text = "${score}타",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (score <= 88) Color(0xFF059669) else Color(0xFF2563EB)
                                    )
                                    Spacer(modifier = Modifier.height(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .width(28.dp)
                                            .height(((score - 65) * 1.8).dp)
                                            .clip(RoundedCornerShape(topStart = 6.dp, topEnd = 6.dp))
                                            .background(
                                                if (score <= 88) Color(0xFF10B981) else Color(0xFF60A5FA)
                                            )
                                    )
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = course,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = Color(0xFF475569)
                                    )
                                    Text(
                                        text = date,
                                        fontSize = 9.sp,
                                        color = Color(0xFF94A3B8)
                                    )
                                }
                            }
                        }
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.xs))
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
                        text = "라운드 기록",
                        style = AppTypography.caption
                    )
                    Text(
                        text = "항목을 눌러 스코어카드 및 사진 보기",
                        style = AppTypography.captionMuted
                    )
                }
                HairlineDivider()
            }

            val displayRounds = if (uiState.completedRounds.isNotEmpty()) uiState.completedRounds else uiState.rounds
            if (displayRounds.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xxxl),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("기록된 라운드가 없습니다.", style = AppTypography.bodySecondary)
                    }
                }
            } else {
                items(displayRounds) { round ->
                    SaaSGolfRow(
                        round = round,
                        onClick = { viewModel.selectRound(round) }
                    )
                    HairlineDivider()
                }
            }
        }

        // Round Detail Dialog
        if (uiState.selectedRound != null) {
            GolfRoundDetailDialog(
                round = uiState.selectedRound!!,
                weather = uiState.weatherMap[uiState.selectedRound!!.id],
                isOcrScanning = uiState.isOcrScanning,
                onDismiss = { viewModel.closeRoundDetail() },
                onPhotoClick = { photoUrl -> viewModel.openPhotoPreview(photoUrl) },
                onAddPhotos = { uris -> viewModel.addPhotosToRound(uiState.selectedRound!!.id, uris) },
                onRemovePhoto = { uri -> viewModel.removePhotoFromRound(uiState.selectedRound!!.id, uri) },
                onSetScorecard = { uri -> viewModel.setScorecardPhoto(uiState.selectedRound!!.id, uri) },
                onScanScorecard = { uri -> viewModel.scanScorecard(uiState.selectedRound!!.id, uri) },
                onOpenWeatherDetail = { viewModel.openWeatherDetail(uiState.selectedRound!!) },
                onSaveDetails = { name, score, putts, memo, sTime, eTime, companions ->
                    viewModel.updateRoundDetails(
                        uiState.selectedRound!!.id,
                        name, score, putts, memo, sTime, eTime, companions
                    )
                },
                onDelete = { viewModel.deleteRound(uiState.selectedRound!!.id) }
            )
        }

        // Fullscreen Photo Preview
        if (uiState.selectedPhotoPreviewUrl != null) {
            PhotoPreviewDialog(
                photoUrl = uiState.selectedPhotoPreviewUrl!!,
                onDismiss = { viewModel.closePhotoPreview() }
            )
        }

        // Golf Reservation Dialog
        if (uiState.isReservationDialogOpen) {
            AddGolfReservationDialog(
                onDismiss = { viewModel.closeReservationDialog() },
                onConfirm = { clubName, courseName, teeOffTime, companions, estimatedGreenFee, memo, lat, lng ->
                    viewModel.addGolfReservation(clubName, courseName, teeOffTime, companions, estimatedGreenFee, memo, lat, lng)
                }
            )
        }

        // Golf Dutch Pay Dialog
        if (uiState.isDutchPayDialogOpen && uiState.dutchPayTargetRound != null) {
            GolfDutchPayDialog(
                round = uiState.dutchPayTargetRound!!,
                onDismiss = { viewModel.closeDutchPayDialog() }
            )
        }

        // Golf Weather Detail Dialog (WeatherNext 3 & Hourly Rainfall)
        if (uiState.weatherDetailTarget != null) {
            GolfWeatherDetailDialog(
                weather = uiState.weatherDetailTarget!!,
                isRefreshing = uiState.isWeatherRefreshing,
                onDismiss = { viewModel.closeWeatherDetail() },
                onRefresh = {
                    val target = uiState.weatherDetailTarget!!
                    val round = uiState.rounds.find { it.clubName == target.clubName }
                    viewModel.refreshWeatherForTarget(
                        roundId = round?.id ?: 0L,
                        clubName = target.clubName,
                        roundDate = target.roundDate,
                        startTime = round?.startTime,
                        endTime = round?.endTime
                    )
                }
            )
        }
    }
}

@Composable
fun SaaSGolfRow(
    round: GolfRound,
    onClick: () -> Unit
) {
    val startTime = round.startTime ?: round.roundDate
    val endTime = round.endTime ?: startTime.plusHours(if (round.golfType == GolfType.FIELD) 5 else 2).plusMinutes(30)
    val duration = Duration.between(startTime, endTime)
    val hours = duration.toHours()
    val minutes = duration.toMinutes() % 60
    val durationStr = if (hours > 0) "${hours}시간 ${minutes}분" else "${minutes}분"

    val totalPhotos = (if (round.scorecardPhotoUri != null) 1 else 0) + round.matchingPhotoUris.size

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = round.clubName,
                    style = AppTypography.h2
                )
                Spacer(modifier = Modifier.width(Spacing.sm))
                MetricBadge(
                    text = if (round.golfType == GolfType.FIELD) "FIELD" else "SCREEN",
                    textColor = if (round.golfType == GolfType.FIELD) Forest700 else Indigo700,
                    backgroundColor = if (round.golfType == GolfType.FIELD) Forest50 else Indigo50
                )
            }

            if (round.totalScore != null && round.totalScore > 0) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${round.totalScore}타",
                        style = AppTypography.h2.copy(color = AppColors.primary, fontWeight = FontWeight.Bold)
                    )
                    if (round.totalPutts != null) {
                        Spacer(modifier = Modifier.width(4.dp))
                        Text("(${round.totalPutts}P)", style = AppTypography.captionMuted)
                    }
                }
            } else {
                Text(
                    text = "기록 보기 →",
                    fontSize = 11.sp,
                    color = AppColors.primary,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Time Range & Date with Day of Week
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "${startTime.format(DateTimeFormatter.ofPattern("yyyy.MM.dd(E)", Locale.KOREA))} · ${startTime.format(DateTimeFormatter.ofPattern("HH:mm"))} ~ ${endTime.format(DateTimeFormatter.ofPattern("HH:mm"))}",
                    style = AppTypography.caption.copy(color = Slate600)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = "($durationStr)",
                    style = AppTypography.captionMuted
                )
            }

            if (totalPhotos > 0) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .clip(AppShapes.tag)
                        .background(Slate100)
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        text = "📷 사진 $totalPhotos",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Medium,
                        color = Slate700
                    )
                }
            }
        }

        // Companions & Green fee summary if present
        if (round.companions.isNotEmpty() || round.greenFeeExpense > 0) {
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                if (round.companions.isNotEmpty()) {
                    Text(
                        text = "👤 ${round.companions.joinToString(", ")}",
                        style = AppTypography.captionMuted
                    )
                }
                if (round.greenFeeExpense > 0) {
                    Text(
                        text = "· 💳 %,d원".format(round.greenFeeExpense),
                        style = AppTypography.captionMuted
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GolfRoundDetailDialog(
    round: GolfRound,
    weather: GolfPlayWeather? = null,
    isOcrScanning: Boolean,
    onDismiss: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onAddPhotos: (List<String>) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onSetScorecard: (String) -> Unit,
    onScanScorecard: (Uri) -> Unit,
    onOpenWeatherDetail: () -> Unit = {},
    onSaveDetails: (clubName: String, totalScore: Int?, totalPutts: Int?, memo: String?, startTime: LocalDateTime?, endTime: LocalDateTime?, companions: List<String>) -> Unit,
    onDelete: () -> Unit
) {
    var clubName by remember { mutableStateOf(round.clubName) }
    var totalScoreText by remember { mutableStateOf(round.totalScore?.toString() ?: "") }
    var totalPuttsText by remember { mutableStateOf(round.totalPutts?.toString() ?: "") }
    var memo by remember { mutableStateOf(round.memo ?: "") }
    var companionsList by remember { mutableStateOf(round.companions) }
    var newCompanionText by remember { mutableStateOf("") }

    // Start and End Time calculations
    val initialStart = round.startTime ?: round.roundDate
    val initialEnd = round.endTime ?: initialStart.plusHours(if (round.golfType == GolfType.FIELD) 5 else 2).plusMinutes(30)

    var startHour by remember { mutableIntStateOf(initialStart.hour) }
    var startMinute by remember { mutableIntStateOf(initialStart.minute) }
    var endHour by remember { mutableIntStateOf(initialEnd.hour) }
    var endMinute by remember { mutableIntStateOf(initialEnd.minute) }
    var showTimeEditDialog by remember { mutableStateOf(false) }

    // Photo pickers
    val scorecardPhotoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            onScanScorecard(uri)
        }
    }

    val roundPhotosPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris: List<Uri> ->
        if (uris.isNotEmpty()) {
            onAddPhotos(uris.map { it.toString() })
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.92f),
            shape = AppShapes.modal,
            color = AppColors.surface,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // 1. Header
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("⛳ 라운드 상세 기록", style = AppTypography.h2)
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        MetricBadge(
                            text = if (round.golfType == GolfType.FIELD) "FIELD" else "SCREEN",
                            textColor = if (round.golfType == GolfType.FIELD) Forest700 else Indigo700,
                            backgroundColor = if (round.golfType == GolfType.FIELD) Forest50 else Indigo50
                        )
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = AppColors.secondary)
                    }
                }

                HairlineDivider()

                // 2. Scrollable Body
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(Spacing.xl),
                    verticalArrangement = Arrangement.spacedBy(Spacing.lg)
                ) {
                    // Club Name & Date Section
                    AutoLogueTextField(
                        value = clubName,
                        onValueChange = { clubName = it },
                        label = "골프장 / 코스명",
                        placeholder = "예: 남촌CC, 안양CC",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // Round Time & Duration Box
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Slate50,
                        shadowElevation = 2.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("🕒 라운딩 일시 및 소요 시간", style = AppTypography.body.copy(fontWeight = FontWeight.Bold))
                                }
                                AutoLogueActionChipButton(
                                    text = "시간 수정",
                                    icon = Icons.Default.Edit,
                                    onClick = { showTimeEditDialog = true },
                                    containerColor = Color(0xFFEFF6FF),
                                    borderColor = Color(0xFF93C5FD),
                                    contentColor = Color(0xFF1D4ED8)
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))

                            val currentStartTime = initialStart.withHour(startHour).withMinute(startMinute)
                            val currentEndTime = initialEnd.withHour(endHour).withMinute(endMinute)
                            val duration = Duration.between(currentStartTime, currentEndTime)
                            val durHours = duration.toHours()
                            val durMinutes = (duration.toMinutes() % 60).coerceAtLeast(0)

                            Text(
                                text = "${currentStartTime.format(DateTimeFormatter.ofPattern("yyyy년 M월 d일 (E)", Locale.KOREA))}",
                                style = AppTypography.bodySecondary.copy(fontWeight = FontWeight.Medium)
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "시작 %02d:%02d ~ 종료 %02d:%02d (%d시간 %d분 소요)".format(
                                    startHour, startMinute, endHour, endMinute, durHours, durMinutes
                                ),
                                style = AppTypography.h3.copy(color = AppColors.primary, fontWeight = FontWeight.Bold)
                            )
                        }
                    }

                    // WeatherNext 3 날씨 & 시간대별 강우량 섹션
                    if (weather != null) {
                        GolfWeatherSummaryBadge(
                            weather = weather,
                            onViewDetail = onOpenWeatherDetail
                        )
                        if (weather.hourlyForecast.isNotEmpty()) {
                            GolfHourlyRainfallChart(
                                hourlyList = weather.hourlyForecast,
                                modifier = Modifier.clickable { onOpenWeatherDetail() }
                            )
                        }
                    }

                    // Scorecard Section
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Slate50,
                        shadowElevation = 2.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text("📊 스코어카드", style = AppTypography.body.copy(fontWeight = FontWeight.Bold))
                                AutoLogueActionChipButton(
                                    text = "스코어카드 스캔(OCR)",
                                    icon = Icons.Default.CameraAlt,
                                    onClick = {
                                        scorecardPhotoPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    containerColor = Color(0xFFEFF6FF),
                                    borderColor = Color(0xFF93C5FD),
                                    contentColor = Color(0xFF1D4ED8)
                                )
                            }

                            Spacer(modifier = Modifier.height(Spacing.sm))

                            // Score Inputs
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(Spacing.md)
                            ) {
                                AutoLogueTextField(
                                    value = totalScoreText,
                                    onValueChange = { totalScoreText = it.filter { ch -> ch.isDigit() } },
                                    label = "총 타수 (타)",
                                    placeholder = "예: 88",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )

                                AutoLogueTextField(
                                    value = totalPuttsText,
                                    onValueChange = { totalPuttsText = it.filter { ch -> ch.isDigit() } },
                                    label = "총 퍼트수 (P)",
                                    placeholder = "예: 32",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                    singleLine = true,
                                    modifier = Modifier.weight(1f)
                                )
                            }

                            // Hole Scores Table (if present)
                            if (round.holeScores.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text("18홀 스코어 매트릭스", style = AppTypography.captionMuted)
                                Spacer(modifier = Modifier.height(4.dp))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .horizontalScroll(rememberScrollState()),
                                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                                ) {
                                    round.holeScores.forEachIndexed { idx, score ->
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Slate100,
                                            modifier = Modifier.width(32.dp)
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(vertical = 4.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text("${idx + 1}H", fontSize = 9.sp, color = Slate500)
                                                Text("$score", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = AppColors.primary)
                                            }
                                        }
                                    }
                                }
                            }

                            // Scorecard Photo Thumbnail (if attached)
                            if (round.scorecardPhotoUri != null) {
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                Text("스코어카드 원본 이미지 (터치하여 확대)", style = AppTypography.captionMuted)
                                Spacer(modifier = Modifier.height(4.dp))
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(140.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(Slate100)
                                        .clickable { onPhotoClick(round.scorecardPhotoUri) }
                                ) {
                                    AsyncImage(
                                        model = round.scorecardPhotoUri,
                                        contentDescription = "스코어카드 사진",
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        modifier = Modifier
                                            .align(Alignment.BottomEnd)
                                            .padding(6.dp),
                                        shape = AppShapes.pill,
                                        color = Color.Black.copy(alpha = 0.6f)
                                    ) {
                                        Text(
                                            text = "🔍 크게 보기",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                                            fontSize = 10.sp,
                                            color = PureWhite
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // Matching Round Photos Gallery Section
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Slate50,
                        shadowElevation = 2.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📸 라운딩 현장 사진 (${round.matchingPhotoUris.size}장)",
                                    style = AppTypography.body.copy(fontWeight = FontWeight.Bold)
                                )
                                AutoLogueActionChipButton(
                                    text = "사진 추가",
                                    icon = Icons.Default.AddPhotoAlternate,
                                    onClick = {
                                        roundPhotosPicker.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    },
                                    containerColor = Color(0xFFEEF2FF),
                                    borderColor = Color(0xFFA5B4FC),
                                    contentColor = Color(0xFF4338CA)
                                )
                            }

                            Spacer(modifier = Modifier.height(Spacing.sm))

                            if (round.matchingPhotoUris.isNotEmpty()) {
                                LazyRow(
                                    horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    items(round.matchingPhotoUris) { uri ->
                                        Box(
                                            modifier = Modifier
                                                .size(80.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(Slate100)
                                                .clickable { onPhotoClick(uri) }
                                        ) {
                                            AsyncImage(
                                                model = uri,
                                                contentDescription = "라운딩 사진",
                                                contentScale = ContentScale.Crop,
                                                modifier = Modifier.fillMaxSize()
                                            )
                                            IconButton(
                                                onClick = { onRemovePhoto(uri) },
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .align(Alignment.TopEnd)
                                                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                                            ) {
                                                Icon(Icons.Default.Close, contentDescription = "삭제", tint = PureWhite, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }
                            } else {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.md),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "연결된 라운딩 사진이 없습니다. 사진을 추가해보세요.",
                                        style = AppTypography.captionMuted
                                    )
                                }
                            }
                        }
                    }

                    // Companions Section
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        color = Slate50,
                        shadowElevation = 2.dp,
                        border = androidx.compose.foundation.BorderStroke(1.dp, Slate200)
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Text("👥 동반자 (동행인)", style = AppTypography.body.copy(fontWeight = FontWeight.Bold))
                            Spacer(modifier = Modifier.height(Spacing.xs))

                            // Companion Chips
                            if (companionsList.isNotEmpty()) {
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    companionsList.forEach { companion ->
                                        Surface(
                                            shape = AppShapes.pill,
                                            color = Slate100,
                                            border = androidx.compose.foundation.BorderStroke(0.5.dp, Slate300)
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            ) {
                                                Text("👤 $companion", fontSize = 11.sp, fontWeight = FontWeight.Medium, color = AppColors.primary)
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    Icons.Default.Close,
                                                    contentDescription = "동반자 삭제",
                                                    modifier = Modifier
                                                        .size(12.dp)
                                                        .clickable { companionsList = companionsList.filter { it != companion } },
                                                    tint = Slate500
                                                )
                                            }
                                        }
                                    }
                                }
                                Spacer(modifier = Modifier.height(Spacing.xs))
                            }

                            // Add Companion Input
                            AutoLogueCompactInputRow(
                                value = newCompanionText,
                                onValueChange = { newCompanionText = it },
                                onAddClick = {
                                    if (newCompanionText.isNotBlank() && !companionsList.contains(newCompanionText.trim())) {
                                        companionsList = companionsList + newCompanionText.trim()
                                        newCompanionText = ""
                                    }
                                },
                                placeholder = "동반자 이름 입력 (예: 홍길동)",
                                buttonText = "추가"
                            )
                        }
                    }

                    // Memo Section
                    AutoLogueTextField(
                        value = memo,
                        onValueChange = { memo = it },
                        label = "라운딩 메모 / 코스 후기",
                        placeholder = "코스 난이도, 날씨, 샷 감각 등 메모를 남겨보세요",
                        singleLine = false,
                        minLines = 2,
                        maxLines = 4,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                HairlineDivider()

                // 3. Footer Action Buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    AutoLogueDangerButton(
                        text = "기록 삭제",
                        onClick = onDelete
                    )

                    Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                        AutoLogueSecondaryButton(
                            text = "취소",
                            onClick = onDismiss
                        )

                        AutoLoguePrimaryButton(
                            text = "저장하기",
                            onClick = {
                                val sTime = initialStart.withHour(startHour).withMinute(startMinute)
                                val eTime = initialEnd.withHour(endHour).withMinute(endMinute)
                                onSaveDetails(
                                    clubName,
                                    totalScoreText.toIntOrNull(),
                                    totalPuttsText.toIntOrNull(),
                                    memo,
                                    sTime,
                                    eTime,
                                    companionsList
                                )
                                onDismiss()
                            }
                        )
                    }
                }
            }
        }
    }

    // Round Time Edit Sub-Dialog
    if (showTimeEditDialog) {
        var tempStartHour by remember { mutableStateOf(startHour.toString()) }
        var tempStartMinute by remember { mutableStateOf(startMinute.toString()) }
        var tempEndHour by remember { mutableStateOf(endHour.toString()) }
        var tempEndMinute by remember { mutableStateOf(endMinute.toString()) }

        AlertDialog(
            onDismissRequest = { showTimeEditDialog = false },
            title = { Text("라운딩 시간 설정", style = AppTypography.h2) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                    Text("시작 시간 (시 : 분)", style = AppTypography.caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoLogueTextField(
                            value = tempStartHour,
                            onValueChange = { tempStartHour = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = "시(0~23)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        AutoLogueTextField(
                            value = tempStartMinute,
                            onValueChange = { tempStartMinute = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = "분(0~59)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    Text("종료 시간 (시 : 분)", style = AppTypography.caption)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        AutoLogueTextField(
                            value = tempEndHour,
                            onValueChange = { tempEndHour = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = "시(0~23)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                        AutoLogueTextField(
                            value = tempEndMinute,
                            onValueChange = { tempEndMinute = it.filter { ch -> ch.isDigit() }.take(2) },
                            label = "분(0~59)",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                AutoLoguePrimaryButton(
                    text = "확인",
                    onClick = {
                        val sh = tempStartHour.toIntOrNull()?.coerceIn(0, 23) ?: startHour
                        val sm = tempStartMinute.toIntOrNull()?.coerceIn(0, 59) ?: startMinute
                        val eh = tempEndHour.toIntOrNull()?.coerceIn(0, 23) ?: endHour
                        val em = tempEndMinute.toIntOrNull()?.coerceIn(0, 59) ?: endMinute

                        startHour = sh
                        startMinute = sm
                        endHour = eh
                        endMinute = em
                        showTimeEditDialog = false
                    }
                )
            },
            dismissButton = {
                AutoLogueSecondaryButton(
                    text = "취소",
                    onClick = { showTimeEditDialog = false }
                )
            }
        )
    }
}

@Composable
fun PhotoPreviewDialog(
    photoUrl: String,
    onDismiss: () -> Unit
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.95f))
                .clickable(onClick = onDismiss),
            contentAlignment = Alignment.Center
        ) {
            AsyncImage(
                model = photoUrl,
                contentDescription = "확대 사진",
                contentScale = ContentScale.Fit,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(Spacing.md)
            )

            IconButton(
                onClick = onDismiss,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(Spacing.xl)
                    .background(Color.Black.copy(alpha = 0.5f), CircleShape)
            ) {
                Icon(
                    Icons.Default.Close,
                    contentDescription = "닫기",
                    tint = PureWhite,
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}


// ==========================================
// ⛳ SmartScore & KakaoGolf Commercial Upgrades
// ==========================================

@Composable
fun UpcomingGolfCard(
    round: GolfRound,
    weather: GolfPlayWeather? = null,
    onOpenDutchPay: () -> Unit,
    onScanLocker: () -> Unit,
    onViewWeatherDetail: () -> Unit = {},
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val today = java.time.LocalDate.now()
    val roundDay = (round.startTime ?: round.roundDate).toLocalDate()
    val daysLeft = ChronoUnit.DAYS.between(today, roundDay)

    val dDayBadgeText = when {
        daysLeft > 0 -> "D-$daysLeft"
        daysLeft == 0L -> "D-Day (오늘!)"
        else -> "D+${-daysLeft}"
    }

    val dDayBgColor = when {
        daysLeft == 0L -> Color(0xFF10B981)
        daysLeft <= 3 -> Color(0xFFF59E0B)
        else -> Color(0xFF2563EB)
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = dDayBgColor
                    ) {
                        Text(
                            text = dDayBadgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = round.clubName,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF0F172A)
                    )
                }

                Text(
                    text = "상세 보기 →",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF2563EB)
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val sTime = round.startTime ?: round.roundDate
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Schedule,
                    contentDescription = null,
                    tint = Color(0xFF64748B),
                    modifier = Modifier.size(15.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = sTime.format(DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREA)),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color(0xFF334155)
                )
                if (!round.memo.isNullOrBlank() && round.memo.contains("코스")) {
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "· " + round.memo.substringAfter("[").substringBefore("]"),
                        fontSize = 12.sp,
                        color = Color(0xFF0D9488),
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            if (round.companions.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Group,
                        contentDescription = null,
                        tint = Color(0xFF64748B),
                        modifier = Modifier.size(15.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "동반자: ${round.companions.joinToString(", ")} (${round.companions.size + 1}인)",
                        fontSize = 12.sp,
                        color = Color(0xFF64748B)
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // WeatherNext 3 실시간 날씨 배너
            GolfWeatherSummaryBadge(
                weather = weather,
                onViewDetail = onViewWeatherDetail
            )

            // 시간대별 강우량 & 강수확률 퀵 차트 (인라인 미리보기)
            if (weather != null && weather.hourlyForecast.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                GolfHourlyRainfallChart(
                    hourlyList = weather.hourlyForecast
                )
            }

            Spacer(modifier = Modifier.height(10.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = onOpenDutchPay,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFEFF6FF), contentColor = Color(0xFF1D4ED8)),
                    border = BorderStroke(1.dp, Color(0xFF93C5FD)),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.Calculate, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("동반자 1/N 정산", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }

                Button(
                    onClick = onScanLocker,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669), contentColor = Color.White),
                    shape = RoundedCornerShape(8.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.weight(1f).height(36.dp)
                ) {
                    Icon(imageVector = Icons.Default.ReceiptLong, contentDescription = null, modifier = Modifier.size(15.dp))
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("라커룸 스캔", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun SmartScoreAnalyticsCard(modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(16.dp),
        color = Color(0xFFF8FAFC),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.xl, vertical = Spacing.xs)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("📊 스마트스코어 심층 분석", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                }
                Surface(
                    shape = RoundedCornerShape(6.dp),
                    color = Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, Color(0xFF93C5FD))
                ) {
                    Text(
                        text = "16.2 HDCP (보기 플레이어)",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF1D4ED8),
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                StatItemBox(title = "GIR(파온율)", value = "38.5%", desc = "평균 6.9홀", modifier = Modifier.weight(1f))
                StatItemBox(title = "FIR(안착률)", value = "62.0%", desc = "평균 8.7홀", modifier = Modifier.weight(1f))
                StatItemBox(title = "평균 퍼트수", value = "32.4P", desc = "홀당 1.8개", modifier = Modifier.weight(1f))
                StatItemBox(title = "베스트 라베", value = "87타", desc = "라데나 GC", modifier = Modifier.weight(1f))
            }

            Spacer(modifier = Modifier.height(10.dp))

            Column {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("스코어 분포 비율", fontSize = 10.sp, color = Color(0xFF64748B))
                    Text("버디 5% · 파 45% · 보기 35% · 더블+ 15%", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF334155))
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp))
                ) {
                    Box(modifier = Modifier.weight(0.05f).fillMaxHeight().background(Color(0xFFE11D48)))
                    Box(modifier = Modifier.weight(0.45f).fillMaxHeight().background(Color(0xFF10B981)))
                    Box(modifier = Modifier.weight(0.35f).fillMaxHeight().background(Color(0xFF3B82F6)))
                    Box(modifier = Modifier.weight(0.15f).fillMaxHeight().background(Color(0xFF94A3B8)))
                }
            }
        }
    }
}

@Composable
private fun StatItemBox(
    title: String,
    value: String,
    desc: String,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = Color(0xFFFFFFFF),
        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
        modifier = modifier
    ) {
        Column(
            modifier = Modifier.padding(8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = title, fontSize = 10.sp, color = Color(0xFF64748B))
            Spacer(modifier = Modifier.height(2.dp))
            Text(text = value, fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
            Spacer(modifier = Modifier.height(1.dp))
            Text(text = desc, fontSize = 9.sp, color = Color(0xFF94A3B8))
        }
    }
}

@Composable
fun GolfDutchPayDialog(
    round: GolfRound?,
    onDismiss: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val view = LocalView.current

    var greenFeeText by remember { mutableStateOf("960000") }
    var cartFeeText by remember { mutableStateOf("100000") }
    var caddieFeeText by remember { mutableStateOf("150000") }
    var foodFeeText by remember { mutableStateOf("80000") }
    var playerCount by remember { mutableIntStateOf(4) }

    val greenFee = greenFeeText.toLongOrNull() ?: 0L
    val cartFee = cartFeeText.toLongOrNull() ?: 0L
    val caddieFee = caddieFeeText.toLongOrNull() ?: 0L
    val foodFee = foodFeeText.toLongOrNull() ?: 0L

    val totalExpense = greenFee + cartFee + caddieFee + foodFee
    val perPersonExpense = if (playerCount > 0) totalExpense / playerCount else 0L

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("💸 동반자 1/N 정산기", fontWeight = FontWeight.Bold, fontSize = 17.sp)
                Spacer(modifier = Modifier.weight(1f))
                Text(
                    text = round?.clubName ?: "골프 라운딩",
                    fontSize = 12.sp,
                    color = Color(0xFF2563EB),
                    fontWeight = FontWeight.SemiBold
                )
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("총 라운딩 비용을 입력하시면 1인당 정산 금액이 자동 계산됩니다.", fontSize = 12.sp, color = Color(0xFF64748B))

                OutlinedTextField(
                    value = greenFeeText,
                    onValueChange = { greenFeeText = it.filter { c -> c.isDigit() } },
                    label = { Text("그린피 총액 (원)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = cartFeeText,
                        onValueChange = { cartFeeText = it.filter { c -> c.isDigit() } },
                        label = { Text("카트비 (원)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = caddieFeeText,
                        onValueChange = { caddieFeeText = it.filter { c -> c.isDigit() } },
                        label = { Text("캐디피 (원)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }

                OutlinedTextField(
                    value = foodFeeText,
                    onValueChange = { foodFeeText = it.filter { c -> c.isDigit() } },
                    label = { Text("그늘집 / 식음료 (원)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("정산 인원수", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedButton(
                            onClick = { if (playerCount > 1) playerCount-- },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(32.dp)
                        ) { Text("-", fontSize = 16.sp, fontWeight = FontWeight.Bold) }

                        Text(
                            text = "${playerCount}명",
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 12.dp)
                        )

                        OutlinedButton(
                            onClick = { playerCount++ },
                            contentPadding = PaddingValues(0.dp),
                            modifier = Modifier.size(32.dp)
                        ) { Text("+", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                }

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("총 라운딩 비용", fontSize = 12.sp, color = Color(0xFF64748B))
                            Text("%,d원".format(totalExpense), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF0F172A))
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("1인당 정산 금액", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = Color(0xFF1E293B))
                            Text("%,d원".format(perPersonExpense), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color(0xFF2563EB))
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val club = round?.clubName ?: "골프 라운딩"
                    val shareText = """[AutoLogue 라운딩 1/N 정산 안내]
⛳ $club
• 총 비용: %,d원 (${playerCount}인 기준)
  - 그린피: %,d원
  - 카트비: %,d원
  - 캐디피: %,d원
  - 그늘집: %,d원
👉 1인당 정산 금액: %,d원

즐거운 라운딩이었습니다! 정산 부탁드립니다 🏌️‍♂️""".trimIndent().format(
                        totalExpense, greenFee, cartFee, caddieFee, foodFee, perPersonExpense
                    )
                    val clip = ClipData.newPlainText("골프 정산 안내", shareText)
                    clipboard.setPrimaryClip(clip)
                    Toast.makeText(context, "카카오톡 정산 메시지가 클립보드에 복사되었습니다!", Toast.LENGTH_LONG).show()
                    onDismiss()
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
            ) {
                Icon(imageVector = Icons.Default.Share, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("카카오톡 정산 메시지 복사", color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("닫기", color = Color(0xFF64748B))
            }
        }
    )
}

data class GolfCoursePreset(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

private val GOLF_COURSE_PRESETS = listOf(
    GolfCoursePreset("아난티 코드 GC", "경기도 가평군 설악면 유명로 961-345", 37.7126, 127.5312),
    GolfCoursePreset("가평베네스트 GC", "경기도 가평군 상면 물골길 102", 37.8420, 127.4320),
    GolfCoursePreset("크리스탈밸리 CC", "경기도 가평군 상면 대보간선로 602-111", 37.8020, 127.4120),
    GolfCoursePreset("프리스틴밸리 GC", "경기도 가평군 설악면 유명로 1243-199", 37.7080, 127.4520),
    GolfCoursePreset("남촌 CC", "경기도 광주시 곤지암읍 도척윗로 500", 37.3321, 127.3524),
    GolfCoursePreset("이스트밸리 CC", "경기도 광주시 곤지암읍 건업길 92", 37.3195, 127.3482),
    GolfCoursePreset("곤지암 GC", "경기도 광주시 도척면 도척윗로 278-1", 37.3412, 127.3025),
    GolfCoursePreset("중부 CC", "경기도 광주시 곤지암읍 경충대로 451", 37.3620, 127.3080),
    GolfCoursePreset("뉴서울 CC", "경기도 광주시 삼동 순암로 298", 37.3910, 127.2450),
    GolfCoursePreset("레이크사이드 CC", "경기도 용인시 처인구 모현읍 능원로 181", 37.3150, 127.1850),
    GolfCoursePreset("화산 CC", "경기도 용인시 처인구 이동읍 화산로 239", 37.1650, 127.2410),
    GolfCoursePreset("신원 CC", "경기도 용인시 처인구 이동읍 이원로 225", 37.1420, 127.2350),
    GolfCoursePreset("아시아나 CC", "경기도 용인시 처인구 양지면 양지로 290", 37.1720, 127.2850),
    GolfCoursePreset("지산 CC", "경기도 용인시 처인구 원삼면 맹리로 63", 37.1780, 127.2510),
    GolfCoursePreset("글렌로스 GC", "경기도 용인시 처인구 포곡읍 에버랜드로 562번길 69", 37.2950, 127.2050),
    GolfCoursePreset("수원 CC", "경기도 용인시 기흥구 중부대로 495", 37.2850, 127.1050),
    GolfCoursePreset("태광 CC", "경기도 용인시 기흥구 흥덕4로 77", 37.2750, 127.0980),
    GolfCoursePreset("한성 CC", "경기도 용인시 기흥구 구흥로 115", 37.3050, 127.1250),
    GolfCoursePreset("사우스스프링스 CC", "경기도 이천시 모가면 남이천로 150", 37.1524, 127.4215),
    GolfCoursePreset("웰링턴 CC", "경기도 이천시 모가면 사실로 725", 37.1820, 127.4650),
    GolfCoursePreset("블랙스톤 이천 GC", "경기도 이천시 장호원읍 장감로 130번길 135", 37.1950, 127.5210),
    GolfCoursePreset("비에이비스타 CC", "경기도 이천시 모가면 어농로 272", 37.1250, 127.4850),
    GolfCoursePreset("안양 CC", "경기도 군포시 군포로 364", 37.3712, 126.9620),
    GolfCoursePreset("자유 CC", "경기도 여주시 가남읍 자유로 390", 37.2145, 127.6012),
    GolfCoursePreset("트리니티 클럽", "경기도 여주시 가남읍 삼군1길 53", 37.2340, 127.5920),
    GolfCoursePreset("해슬리 나인브릿지", "경기도 여주시 점동면 헤슬리로드 176", 37.2280, 127.6150),
    GolfCoursePreset("블루헤런 GC", "경기도 여주시 대신면 고달사로 67", 37.3820, 127.5850),
    GolfCoursePreset("페럼클럽", "경기도 여주시 점동면 점동로 392", 37.1750, 127.5850),
    GolfCoursePreset("솔모로 CC", "경기도 여주시 가남읍 솔모로그린길 80", 37.1550, 127.5950),
    GolfCoursePreset("금강 CC", "경기도 여주시 가남읍 여주남로 541", 37.1450, 127.6050),
    GolfCoursePreset("스카이72 / 클럽72", "인천광역시 중구 공항동로 135", 37.4912, 126.4812),
    GolfCoursePreset("잭니클라우스 GC", "인천광역시 연수구 아카데미로 209", 37.3750, 126.6320),
    GolfCoursePreset("베어즈베스트 청라 GC", "인천광역시 서구 청라대로 377번길 26", 37.5450, 126.6520),
    GolfCoursePreset("일동레이크 GC", "경기도 포천시 일동면 화동로 738", 37.9540, 127.3210),
    GolfCoursePreset("몽베르 CC", "경기도 포천시 영북면 산정호수로 359-12", 38.0820, 127.3150),
    GolfCoursePreset("라비에벨 CC", "강원특별자치도 춘천시 동산면 종자리로 436", 37.8120, 127.7850),
    GolfCoursePreset("제이드팰리스 GC", "강원특별자치도 춘천시 남산면 북한강변길 398", 37.8250, 127.5750),
    GolfCoursePreset("더플레이어스 GC", "강원특별자치도 춘천시 동산면 사암리 131", 37.7820, 127.7210),
    GolfCoursePreset("세이지우드 홍천", "강원특별자치도 홍천군 두촌면 광석로 898-87", 37.7950, 127.9820),
    GolfCoursePreset("오크밸리 CC", "강원특별자치도 원주시 지정면 오크밸리1길 66", 37.4150, 127.8250),
    GolfCoursePreset("오크밸리 GC", "강원특별자치도 원주시 지정면 오크밸리1길 66", 37.4150, 127.8250),
    GolfCoursePreset("오크밸리 클럽하우스", "강원특별자치도 원주시 지정면 오크밸리1길 66", 37.4150, 127.8250),
    GolfCoursePreset("오크크릭 GC", "강원특별자치도 원주시 지정면 오크밸리2길 58", 37.4210, 127.8320),
    GolfCoursePreset("성문안 CC", "강원특별자치도 원주시 지정면 월송석화로 430", 37.4080, 127.8180),
    GolfCoursePreset("센추리21 CC", "강원특별자치도 원주시 문막읍 궁촌리 산 77", 37.4050, 127.7750),
    GolfCoursePreset("휘슬링락 CC", "강원특별자치도 춘천시 남산면 김유정로 430", 37.8550, 127.8250),
    GolfCoursePreset("카스카디아 CC", "강원특별자치도 홍천군 북방면 노일로 340", 37.8950, 127.8650),
    GolfCoursePreset("핀크스 GC", "제주특별자치도 서귀포시 안덕면 산록남로 863", 33.3250, 126.3980),
    GolfCoursePreset("나인브릿지 제주", "제주특별자치도 서귀포시 안덕면 광평로 34-156", 33.3420, 126.4150),
    GolfCoursePreset("블랙스톤 제주", "제주특별자치도 제주시 한림읍 한창로 925-122", 33.3650, 126.2950),
    GolfCoursePreset("사우스링스 영암", "전라남도 영암군 삼호읍 에프원로 130", 34.7850, 126.5420),
    GolfCoursePreset("골프존파크 판교점", "경기도 성남시 분당구 판교역로 192", 37.3980, 127.1125)
)

@Composable
fun AddGolfReservationDialog(
    onDismiss: () -> Unit,
    onConfirm: (clubName: String, courseName: String, teeOffTime: LocalDateTime, companions: List<String>, estimatedGreenFee: Long, memo: String, latitude: Double?, longitude: Double?) -> Unit
) {
    val context = LocalContext.current
    var clubName by remember { mutableStateOf("") }
    var courseName by remember { mutableStateOf("") }
    var companionsText by remember { mutableStateOf("") }
    var feeText by remember { mutableStateOf("") }
    var memoText by remember { mutableStateOf("") }

    // 위치 좌표 및 주소 상태
    var selectedLatitude by remember { mutableStateOf<Double?>(null) }
    var selectedLongitude by remember { mutableStateOf<Double?>(null) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }

    // 검색 추천 리스트
    var suggestions by remember { mutableStateOf<List<GolfCoursePreset>>(emptyList()) }
    var showSuggestions by remember { mutableStateOf(false) }

    // 날짜 및 시간 선택 상태 (기본값: 내일 오전 7:30)
    var selectedDate by remember { mutableStateOf(LocalDate.now().plusDays(1)) }
    var selectedTime by remember { mutableStateOf(LocalTime.of(7, 30)) }

    // 구글 캘린더 스타일의 골프장 장소 실시간 검색 (프리셋 + Geocoder)
    LaunchedEffect(clubName) {
        val q = clubName.trim()
        if (q.length >= 2 && (selectedAddress == null || !clubName.contains(selectedAddress?.take(4) ?: "###"))) {
            val matchedPresets = GOLF_COURSE_PRESETS.filter {
                it.name.contains(q, ignoreCase = true) || it.address.contains(q, ignoreCase = true)
            }
            if (matchedPresets.isNotEmpty()) {
                suggestions = matchedPresets.take(6)
                showSuggestions = true
            } else {
                withContext(Dispatchers.IO) {
                    runCatching {
                        if (android.location.Geocoder.isPresent()) {
                            val geocoder = android.location.Geocoder(context, Locale.KOREA)
                            @Suppress("DEPRECATION")
                            val addrs = geocoder.getFromLocationName("$q 골프장", 5)
                            if (!addrs.isNullOrEmpty()) {
                                val dynamicSuggestions = mutableListOf<GolfCoursePreset>()
                                val firstAddr = addrs[0]
                                val rawFullAddr = firstAddr.getAddressLine(0) ?: ""
                                val cleanAddr = rawFullAddr
                                    .replace("대한민국 ", "")
                                    .replace(Regex("\\bKR\\b"), "")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()

                                // [핵심 수정] Geocoder의 featureName('KR', 지번 번호 등) 오염 원천 차단
                                // 사용자 검색어(q)를 기반으로 실제 구장명 옵션(CC, GC, 클럽하우스)을 자동 생성
                                val baseName = q.replace(Regex("(CC|GC|C\\.C|G\\.C|골프장|클럽하우스|컨트리클럽)", RegexOption.IGNORE_CASE), "").trim()
                                val candidates = if (q.contains("CC", ignoreCase = true) || q.contains("GC", ignoreCase = true) || q.contains("클럽하우스")) {
                                    listOf(q)
                                } else {
                                    listOf(
                                        "$baseName CC",
                                        "$baseName GC",
                                        "$baseName 클럽하우스"
                                    )
                                }

                                for (cand in candidates) {
                                    dynamicSuggestions.add(
                                        GolfCoursePreset(cand, cleanAddr, firstAddr.latitude, firstAddr.longitude)
                                    )
                                }
                                suggestions = dynamicSuggestions
                                showSuggestions = true
                            } else {
                                showSuggestions = false
                            }
                        }
                    }
                }
            }
        } else {
            showSuggestions = false
        }
    }

    val datePickerDialog = remember {
        android.app.DatePickerDialog(
            context,
            { _, y, m, d ->
                selectedDate = LocalDate.of(y, m + 1, d)
            },
            selectedDate.year,
            selectedDate.monthValue - 1,
            selectedDate.dayOfMonth
        )
    }

    val timePickerDialog = remember {
        android.app.TimePickerDialog(
            context,
            { _, h, min ->
                selectedTime = LocalTime.of(h, min)
            },
            selectedTime.hour,
            selectedTime.minute,
            false
        )
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⛳ 골프 라운드 예약 등록", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text("골프장명과 일정을 등록하면 실시간 D-Day 및 정확한 날씨 예보를 제공합니다.", fontSize = 12.sp, color = Color(0xFF64748B))

                // 골프장명 입력 필드 (구글 캘린더 스타일 위치 검색)
                Column(modifier = Modifier.fillMaxWidth()) {
                    OutlinedTextField(
                        value = clubName,
                        onValueChange = {
                            clubName = it
                            if (selectedAddress != null && !it.contains(clubName)) {
                                selectedAddress = null
                                selectedLatitude = null
                                selectedLongitude = null
                            }
                        },
                        label = { Text("골프장명 (위치 검색)") },
                        placeholder = { Text("골프장명을 검색하세요 (예: 아난티 코드)") },
                        leadingIcon = {
                            Icon(imageVector = Icons.Default.Place, contentDescription = null, tint = Color(0xFF059669))
                        },
                        trailingIcon = {
                            if (clubName.isNotBlank()) {
                                IconButton(onClick = {
                                    clubName = ""
                                    selectedAddress = null
                                    selectedLatitude = null
                                    selectedLongitude = null
                                    showSuggestions = false
                                }) {
                                    Icon(imageVector = Icons.Default.Clear, contentDescription = "지우기", modifier = Modifier.size(16.dp))
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 좌표/주소 확정 배지
                    if (!selectedAddress.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFECFDF5),
                            border = BorderStroke(1.dp, Color(0xFFA7F3D0))
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Icon(imageVector = Icons.Default.CheckCircle, contentDescription = null, tint = Color(0xFF059669), modifier = Modifier.size(13.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "위치 확정: $selectedAddress",
                                    fontSize = 11.sp,
                                    color = Color(0xFF065F46),
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    }

                    // 구글 캘린더 스타일 실시간 장소 검색 추천 리스트
                    if (showSuggestions && suggestions.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color.White,
                            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                            shadowElevation = 4.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column {
                                suggestions.forEachIndexed { index, item ->
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable {
                                                clubName = item.name
                                                selectedAddress = item.address
                                                selectedLatitude = item.latitude
                                                selectedLongitude = item.longitude
                                                showSuggestions = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Icon(imageVector = Icons.Default.LocationOn, contentDescription = null, tint = Color(0xFF2563EB), modifier = Modifier.size(16.dp))
                                        Spacer(modifier = Modifier.width(8.dp))
                                        Column {
                                            Text(item.name, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF0F172A))
                                            Text(item.address, fontSize = 11.sp, color = Color(0xFF64748B))
                                        }
                                    }
                                    if (index < suggestions.size - 1) {
                                        HorizontalDivider(color = Color(0xFFF1F5F9))
                                    }
                                }
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = { Text("코스명 (선택)") },
                    placeholder = { Text("예: 잣나무 / 자작나무 코스") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 티오프 일시 (달력 및 시간 피커)
                Text("티오프 일시 (달력/시간 선택)", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    // 날짜 선택 버튼 (달력 팝업)
                    Surface(
                        onClick = { datePickerDialog.show() },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.CalendarToday,
                                contentDescription = null,
                                tint = Color(0xFF059669),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text("예약 날짜", fontSize = 10.sp, color = Color(0xFF64748B))
                                Text(
                                    text = selectedDate.format(DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }
                        }
                    }

                    // 시간 선택 버튼 (시간 팝업)
                    Surface(
                        onClick = { timePickerDialog.show() },
                        shape = RoundedCornerShape(10.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier.weight(0.9f)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 11.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                imageVector = Icons.Default.Schedule,
                                contentDescription = null,
                                tint = Color(0xFF2563EB),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Column {
                                Text("티오프 시간", fontSize = 10.sp, color = Color(0xFF64748B))
                                Text(
                                    text = selectedTime.format(DateTimeFormatter.ofPattern("a h:mm", Locale.KOREA)),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A)
                                )
                            }
                        }
                    }
                }

                OutlinedTextField(
                    value = companionsText,
                    onValueChange = { companionsText = it },
                    label = { Text("동반자 명단 (선택, 쉼표 구분)") },
                    placeholder = { Text("예: 김프로, 박대표, 이이사") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it.filter { c -> c.isDigit() } },
                    label = { Text("예상 1인 그린피 (원, 선택)") },
                    placeholder = { Text("예: 240000") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = memoText,
                    onValueChange = { memoText = it },
                    label = { Text("메모 (선택)") },
                    placeholder = { Text("예: 주말 친목 모임 라운딩, 준비물 등") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val finalClubName = clubName.trim()
                    if (finalClubName.isBlank()) {
                        Toast.makeText(context, "골프장명을 입력하거나 검색해 주세요.", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val teeOff = LocalDateTime.of(selectedDate, selectedTime)
                    val comps = companionsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val fee = feeText.toLongOrNull() ?: 0L
                    onConfirm(finalClubName, courseName.trim(), teeOff, comps, fee, memoText.trim(), selectedLatitude, selectedLongitude)
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669))
            ) {
                Text("예약 등록", color = Color.White)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("취소", color = Color(0xFF64748B))
            }
        }
    )
}
