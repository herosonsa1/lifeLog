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
import com.autologue.app.presentation.theme.*
import java.time.Duration
import android.content.ClipData
import android.content.ClipboardManager
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.ui.platform.LocalView
import java.time.temporal.ChronoUnit
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GolfScreen(
    viewModel: GolfViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current

    // Launcher for scanning golf locker slip receipt directly from TopAppBar
    val globalLockerSlipPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            viewModel.scanGolfLockerSlip(uri, context)
        }
    }

    // Launcher for scanning scorecard photo directly from TopAppBar
    val globalScorecardPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            val targetRound = uiState.selectedRound ?: uiState.rounds.firstOrNull()
            if (targetRound != null) {
                viewModel.scanScorecard(targetRound.id, uri, context)
            }
        }
    }

    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "골프 라이프",
                            style = AppTypography.h1
                        )
                    },
                    actions = {
                        AutoLogueOutlinedButton(
                            text = "예약 추가",
                            icon = Icons.Default.AddCircle,
                            onClick = { viewModel.openReservationDialog() }
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "라커룸 스캔",
                            icon = Icons.Default.ReceiptLong,
                            onClick = {
                                globalLockerSlipPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "스코어카드 스캔",
                            icon = Icons.Default.CameraAlt,
                            onClick = {
                                globalScorecardPicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                )
                            }
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
                                onOpenDutchPay = { viewModel.openDutchPayDialog(res) },
                                onScanLocker = {
                                    globalLockerSlipPicker.launch(
                                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                    )
                                },
                                onClick = { viewModel.selectRound(res, context) }
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
                        onClick = { viewModel.selectRound(round, context) }
                    )
                    HairlineDivider()
                }
            }
        }

        // Round Detail Dialog
        if (uiState.selectedRound != null) {
            GolfRoundDetailDialog(
                round = uiState.selectedRound!!,
                isOcrScanning = uiState.isOcrScanning,
                onDismiss = { viewModel.closeRoundDetail() },
                onPhotoClick = { photoUrl -> viewModel.openPhotoPreview(photoUrl) },
                onAddPhotos = { uris -> viewModel.addPhotosToRound(uiState.selectedRound!!.id, uris) },
                onRemovePhoto = { uri -> viewModel.removePhotoFromRound(uiState.selectedRound!!.id, uri) },
                onSetScorecard = { uri -> viewModel.setScorecardPhoto(uiState.selectedRound!!.id, uri) },
                onScanScorecard = { uri -> viewModel.scanScorecard(uiState.selectedRound!!.id, uri, context) },
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
                onConfirm = { clubName, courseName, teeOffTime, companions, estimatedGreenFee, memo ->
                    viewModel.addGolfReservation(clubName, courseName, teeOffTime, companions, estimatedGreenFee, memo)
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
    isOcrScanning: Boolean,
    onDismiss: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onAddPhotos: (List<String>) -> Unit,
    onRemovePhoto: (String) -> Unit,
    onSetScorecard: (String) -> Unit,
    onScanScorecard: (Uri) -> Unit,
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
    onOpenDutchPay: () -> Unit,
    onScanLocker: () -> Unit,
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

            Surface(
                shape = RoundedCornerShape(10.dp),
                color = Color(0xFFEFF6FF),
                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("☀️ 맑음 21°C", fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF1D4ED8))
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("🍃 풍속 1.5m/s (잔잔)", fontSize = 11.sp, color = Color(0xFF2563EB))
                    }
                    Text("💧 강수 0%", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = Color(0xFF059669))
                }
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

@Composable
fun AddGolfReservationDialog(
    onDismiss: () -> Unit,
    onConfirm: (clubName: String, courseName: String, teeOffTime: LocalDateTime, companions: List<String>, estimatedGreenFee: Long, memo: String) -> Unit
) {
    var clubName by remember { mutableStateOf("아난티 코드 GC") }
    var courseName by remember { mutableStateOf("잣나무 / 자작나무 코스") }
    var yearText by remember { mutableStateOf("2026") }
    var monthText by remember { mutableStateOf("9") }
    var dayText by remember { mutableStateOf("12") }
    var hourText by remember { mutableStateOf("7") }
    var minuteText by remember { mutableStateOf("28") }
    var companionsText by remember { mutableStateOf("김프로, 박대표, 이이사") }
    var feeText by remember { mutableStateOf("240000") }
    var memoText by remember { mutableStateOf("주말 친목 모임 라운딩") }

    val quickClubs = listOf("아난티 코드", "스카이밸리 CC", "남촌 CC", "라데나 GC", "필로스 CC")

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("⛳ 골프 라운드 예약 등록", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("예약된 골프장과 티오프 일정을 등록하면 D-Day와 날씨를 관리해 드립니다.", fontSize = 12.sp, color = Color(0xFF64748B))

                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    quickClubs.forEach { qc ->
                        Surface(
                            onClick = { clubName = qc },
                            shape = RoundedCornerShape(6.dp),
                            color = if (clubName.contains(qc)) Color(0xFFEFF6FF) else Color(0xFFF1F5F9),
                            border = BorderStroke(1.dp, if (clubName.contains(qc)) Color(0xFF93C5FD) else Color(0xFFE2E8F0))
                        ) {
                            Text(
                                text = qc,
                                fontSize = 11.sp,
                                color = if (clubName.contains(qc)) Color(0xFF1D4ED8) else Color(0xFF475569),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                OutlinedTextField(
                    value = clubName,
                    onValueChange = { clubName = it },
                    label = { Text("골프장명") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = courseName,
                    onValueChange = { courseName = it },
                    label = { Text("코스명 (선택)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                Text("티오프 일시", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFF334155))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = yearText,
                        onValueChange = { yearText = it.filter { c -> c.isDigit() } },
                        label = { Text("년") },
                        modifier = Modifier.weight(1.3f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = monthText,
                        onValueChange = { monthText = it.filter { c -> c.isDigit() } },
                        label = { Text("월") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = dayText,
                        onValueChange = { dayText = it.filter { c -> c.isDigit() } },
                        label = { Text("일") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    OutlinedTextField(
                        value = hourText,
                        onValueChange = { hourText = it.filter { c -> c.isDigit() } },
                        label = { Text("시 (0~23)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                    OutlinedTextField(
                        value = minuteText,
                        onValueChange = { minuteText = it.filter { c -> c.isDigit() } },
                        label = { Text("분 (0~59)") },
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )
                }

                OutlinedTextField(
                    value = companionsText,
                    onValueChange = { companionsText = it },
                    label = { Text("동반자 명단 (쉼표 구분)") },
                    placeholder = { Text("예: 김프로, 박대표, 이이사") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = feeText,
                    onValueChange = { feeText = it.filter { c -> c.isDigit() } },
                    label = { Text("예상 1인 그린피 (원)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                OutlinedTextField(
                    value = memoText,
                    onValueChange = { memoText = it },
                    label = { Text("메모 (준비물 등)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val y = yearText.toIntOrNull() ?: 2026
                    val m = monthText.toIntOrNull() ?: 9
                    val d = dayText.toIntOrNull() ?: 12
                    val h = hourText.toIntOrNull() ?: 7
                    val min = minuteText.toIntOrNull() ?: 28
                    val teeOff = LocalDateTime.of(y, m, d, h, min)
                    val comps = companionsText.split(",").map { it.trim() }.filter { it.isNotBlank() }
                    val fee = feeText.toLongOrNull() ?: 0L
                    onConfirm(clubName, courseName, teeOff, comps, fee, memoText)
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
