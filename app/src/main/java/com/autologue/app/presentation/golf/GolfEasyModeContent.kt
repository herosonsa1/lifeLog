@file:OptIn(ExperimentalMaterial3Api::class)
package com.autologue.app.presentation.golf

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
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
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.getDisplayClubName
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.expense.AnalyticsComparisonBasis
import com.autologue.app.presentation.expense.AnalyticsPeriodUnit
import com.autologue.app.presentation.expense.PeriodSpec
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private enum class EasyGolfFilter {
    ALL, FIELD, SCREEN
}

@Composable
fun GolfEasyModeContent(
    uiState: GolfUiState,
    selectedGolfPageTab: GolfPageTab,
    onSelectGolfPageTab: (GolfPageTab) -> Unit,
    golfAnalyticsUnit: AnalyticsPeriodUnit,
    golfAnalyticsPeriod: PeriodSpec,
    golfAnalyticsBasis: AnalyticsComparisonBasis,
    golfAnalyticsSubTab: GolfAnalyticsSubTab,
    onSelectAnalyticsSubTab: (GolfAnalyticsSubTab) -> Unit,
    onSelectAnalyticsUnit: (AnalyticsPeriodUnit) -> Unit,
    onPreviousAnalyticsPeriod: () -> Unit,
    onNextAnalyticsPeriod: () -> Unit,
    onSelectAnalyticsBasis: (AnalyticsComparisonBasis) -> Unit,
    onOpenDirectAddDialog: () -> Unit,
    onOpenReservationDialog: () -> Unit,
    onLaunchLockerSlipPicker: () -> Unit,
    onLaunchScorecardPicker: () -> Unit,
    onOpenGalleryScanDialog: () -> Unit,
    onOpenStatisticsDialog: () -> Unit,
    onSelectRound: (GolfRound) -> Unit,
    onOpenDutchPayDialog: (GolfRound) -> Unit,
    onOpenWeatherDetail: (GolfRound) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var selectedTypeFilter by remember { mutableStateOf(EasyGolfFilter.ALL) }

    val baseRounds = if (uiState.completedRounds.isNotEmpty()) uiState.completedRounds else uiState.rounds
    val fieldCount = remember(baseRounds) { baseRounds.count { it.golfType == GolfType.FIELD } }
    val screenCount = remember(baseRounds) { baseRounds.count { it.golfType == GolfType.SCREEN } }
    val filteredRounds = remember(baseRounds, selectedTypeFilter) {
        when (selectedTypeFilter) {
            EasyGolfFilter.ALL -> baseRounds
            EasyGolfFilter.FIELD -> baseRounds.filter { it.golfType == GolfType.FIELD }
            EasyGolfFilter.SCREEN -> baseRounds.filter { it.golfType == GolfType.SCREEN }
        }
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = EasyTheme.CanvasBg,
        topBar = {
            EasyModeTopBar(
                title = "골프",
                subtitle = "스코어와 라운드 일정을 깔끔하게 모아봐요",
                accentColor = EasyTheme.tossBlue,
                onOpenSettings = { EasyModeManager.openSettingsSheet() },
                onQuickSwitchToStandard = { EasyModeManager.setEasyMode(context, false) }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 상단 세그먼트 탭 ([라운드 · 예약] / [기간별 비교 통계])
            item {
                val options = listOf("라운드 · 예약", "기간별 비교 통계")
                val selectedIdx = if (selectedGolfPageTab == GolfPageTab.ROUNDS) 0 else 1
                EasySegmentedBar(
                    options = options,
                    selectedIndex = selectedIdx,
                    onSelect = { idx ->
                        onSelectGolfPageTab(if (idx == 0) GolfPageTab.ROUNDS else GolfPageTab.ANALYTICS)
                    }
                )
            }

            if (selectedGolfPageTab == GolfPageTab.ANALYTICS) {
                item {
                    val golfReport = remember(
                        uiState.rounds,
                        uiState.allTransactions,
                        golfAnalyticsUnit,
                        golfAnalyticsPeriod,
                        golfAnalyticsBasis
                    ) {
                        GolfAnalyticsEngine.buildReport(
                            allRounds = uiState.rounds,
                            allTransactions = uiState.allTransactions,
                            unit = golfAnalyticsUnit,
                            targetPeriod = golfAnalyticsPeriod,
                            requestedBasis = golfAnalyticsBasis
                        )
                    }
                    EasySectionCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                        GolfAnalyticsSection(
                            report = golfReport,
                            selectedSubTab = golfAnalyticsSubTab,
                            onSelectSubTab = onSelectAnalyticsSubTab,
                            onSelectPeriodUnit = onSelectAnalyticsUnit,
                            onPreviousPeriod = onPreviousAnalyticsPeriod,
                            onNextPeriod = onNextAnalyticsPeriod,
                            onSelectComparisonBasis = onSelectAnalyticsBasis
                        )
                    }
                }
            } else {
                // 2. 골프 핵심 요약 히어로 카드 + 원탭 액션
                item {
                    EasySectionCard {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Top
                        ) {
                            Column {
                                Text(
                                    text = "평균 타수",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EasyTheme.textSecondary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = if (uiState.averageScore > 0) "%.1f타".format(uiState.averageScore) else "-타",
                                    fontSize = 30.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = EasyTheme.textPrimary,
                                    letterSpacing = (-0.8).sp
                                )
                            }

                            Surface(
                                onClick = onOpenStatisticsDialog,
                                shape = RoundedCornerShape(12.dp),
                                color = EasyTheme.SubtleBoxBg
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Assessment,
                                        contentDescription = "누적 요약 통계",
                                        tint = EasyTheme.tossBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "누적 리포트",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.textPrimary
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 서브 지표 3열 박스 (베스트 스코어 / 총 라운드 / 필드·스크린)
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
                                        text = "베스트 스코어",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = EasyTheme.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = if (uiState.bestScore != null) "${uiState.bestScore}타" else "-타",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = EasyTheme.PositiveGreen
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(28.dp)
                                        .background(EasyTheme.DividerColor)
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 14.dp)
                                ) {
                                    Text(
                                        text = "총 라운드",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = EasyTheme.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "${uiState.rounds.size}회",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = EasyTheme.textPrimary
                                    )
                                }
                                Box(
                                    modifier = Modifier
                                        .width(1.dp)
                                        .height(28.dp)
                                        .background(EasyTheme.DividerColor)
                                )
                                Column(
                                    modifier = Modifier
                                        .weight(1f)
                                        .padding(start = 14.dp)
                                ) {
                                    Text(
                                        text = "필드 · 스크린",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium,
                                        color = EasyTheme.textSecondary
                                    )
                                    Spacer(modifier = Modifier.height(3.dp))
                                    Text(
                                        text = "${fieldCount} · ${screenCount}",
                                        fontSize = 17.sp,
                                        fontWeight = FontWeight.ExtraBold,
                                        color = EasyTheme.tossBlue
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 2x2 빠른 실행 액션 버튼 그리드
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EasyActionButton(
                                text = "스코어카드 스캔",
                                icon = Icons.Default.CameraAlt,
                                onClick = onLaunchScorecardPicker,
                                isPrimary = true,
                                accentColor = EasyTheme.tossBlue,
                                modifier = Modifier.weight(1f)
                            )
                            EasyActionButton(
                                text = "기록 직접 입력",
                                icon = Icons.Default.EditCalendar,
                                onClick = onOpenDirectAddDialog,
                                isPrimary = false,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EasyActionButton(
                                text = "라커룸 스캔",
                                icon = Icons.Default.ReceiptLong,
                                onClick = onLaunchLockerSlipPicker,
                                isPrimary = false,
                                modifier = Modifier.weight(1f)
                            )
                            EasyActionButton(
                                text = "라운딩 예약 추가",
                                icon = Icons.Default.EventAvailable,
                                onClick = onOpenReservationDialog,
                                isPrimary = false,
                                modifier = Modifier.weight(1f)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        Surface(
                            onClick = onOpenGalleryScanDialog,
                            shape = RoundedCornerShape(12.dp),
                            color = EasyTheme.SubtleBoxBg,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 14.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AutoAwesome,
                                        contentDescription = null,
                                        tint = EasyTheme.tossBlue,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Text(
                                        text = "갤러리에서 골프 사진 · 스코어카드 자동 찾기",
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.textPrimary
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Default.ChevronRight,
                                    contentDescription = null,
                                    tint = EasyTheme.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    }
                }

                // 3. 다가오는 라운드 예약 카드
                if (uiState.upcomingReservations.isNotEmpty()) {
                    item {
                        EasySectionCard {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "다가오는 라운드 예약",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = EasyTheme.textPrimary
                                )
                                Surface(
                                    shape = RoundedCornerShape(999.dp),
                                    color = EasyTheme.tossBlueSoft
                                ) {
                                    Text(
                                        text = "${uiState.upcomingReservations.size}건",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.tossBlue,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(10.dp))
                            uiState.upcomingReservations.forEachIndexed { idx, res ->
                                UpcomingGolfCard(
                                    round = res,
                                    weather = uiState.weatherMap[res.id],
                                    onOpenDutchPay = { onOpenDutchPayDialog(res) },
                                    onScanLocker = onLaunchLockerSlipPicker,
                                    onViewWeatherDetail = { onOpenWeatherDetail(res) },
                                    onClick = { onSelectRound(res) }
                                )
                                if (idx < uiState.upcomingReservations.lastIndex) {
                                    Spacer(modifier = Modifier.height(8.dp))
                                }
                            }
                        }
                    }
                }

                // 4. 최근 타수 추이 카드
                if (uiState.rounds.isNotEmpty()) {
                    item {
                        EasySectionCard(contentPadding = PaddingValues(vertical = 8.dp)) {
                            RealScoreTrendCard(rounds = uiState.rounds)
                        }
                    }
                }

                // 5. 라운드 필터 캡슐 바 (전체 / 필드 / 스크린)
                item {
                    val chips = listOf(
                        Triple(EasyGolfFilter.ALL, "전체", baseRounds.size),
                        Triple(EasyGolfFilter.FIELD, "필드", fieldCount),
                        Triple(EasyGolfFilter.SCREEN, "스크린", screenCount)
                    )
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        chips.forEach { (filter, label, count) ->
                            val isSelected = selectedTypeFilter == filter
                            Surface(
                                onClick = { selectedTypeFilter = filter },
                                shape = RoundedCornerShape(999.dp),
                                color = if (isSelected) EasyTheme.textPrimary else EasyTheme.CardBg,
                                border = if (isSelected) null else BorderStroke(1.dp, EasyTheme.DividerColor)
                            ) {
                                Text(
                                    text = "$label ${count}회",
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) Color.White else EasyTheme.textSecondary,
                                    modifier = Modifier.padding(horizontal = 15.dp, vertical = 9.dp)
                                )
                            }
                        }
                    }
                }

                // 6. 라운드 기록 리스트
                if (filteredRounds.isEmpty()) {
                    item {
                        EasySectionCard {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "기록된 골프 라운드가 없습니다",
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EasyTheme.textPrimary
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                Text(
                                    text = "스코어카드 사진을 스캔하거나 직접 입력해 보세요.",
                                    fontSize = 13.sp,
                                    color = EasyTheme.textSecondary
                                )
                            }
                        }
                    }
                } else {
                    items(
                        items = filteredRounds,
                        key = { it.id }
                    ) { round ->
                        EasyGolfRoundCard(
                            round = round,
                            onClick = { onSelectRound(round) }
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

@Composable
private fun EasyGolfRoundCard(
    round: GolfRound,
    onClick: () -> Unit
) {
    val startTime = round.startTime ?: round.roundDate
    val isField = round.golfType == GolfType.FIELD
    val totalPhotos = (if (round.scorecardPhotoUri != null) 1 else 0) + round.matchingPhotoUris.size

    EasySectionCard(
        onClick = onClick,
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
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(14.dp))
                        .background(if (isField) EasyTheme.PositiveGreenBg else EasyTheme.tossBlueSoft),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.GolfCourse,
                        contentDescription = null,
                        tint = if (isField) EasyTheme.PositiveGreen else EasyTheme.tossBlue,
                        modifier = Modifier.size(22.dp)
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = if (isField) EasyTheme.PositiveGreenBg else EasyTheme.tossBlueSoft
                        ) {
                            Text(
                                text = if (isField) "필드" else "스크린",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isField) EasyTheme.PositiveGreen else EasyTheme.tossBlue,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                        Text(
                            text = startTime.format(DateTimeFormatter.ofPattern("yyyy.MM.dd(E) HH:mm", Locale.KOREA)),
                            fontSize = 12.sp,
                            color = EasyTheme.textSecondary
                        )
                    }
                    Spacer(modifier = Modifier.height(3.dp))
                    Text(
                        text = round.getDisplayClubName(),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = EasyTheme.textPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )

                    val metaParts = buildList {
                        if (round.companions.isNotEmpty()) add(round.companions.joinToString(", "))
                        if (round.greenFeeExpense > 0) add("%,d원".format(round.greenFeeExpense))
                        if (totalPhotos > 0) add("사진 ${totalPhotos}장")
                    }
                    if (metaParts.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = metaParts.joinToString(" · "),
                            fontSize = 12.sp,
                            color = EasyTheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(horizontalAlignment = Alignment.End) {
                if (round.totalScore != null && round.totalScore > 0) {
                    Text(
                        text = "${round.totalScore}타",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = EasyTheme.tossBlue
                    )
                    if (round.totalPutts != null) {
                        Text(
                            text = "${round.totalPutts}퍼트",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            color = EasyTheme.textSecondary
                        )
                    }
                } else {
                    Text(
                        text = "상세 보기",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.tossBlue
                    )
                }
            }
        }
    }
}
