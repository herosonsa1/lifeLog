package com.autologue.app.presentation.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.diary.map.GoogleMapRouteView
import com.autologue.app.util.LocationDistanceUtils
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun DiaryEasyModeContent(
    uiState: DiaryUiState,
    isNotificationEnabled: Boolean,
    onOpenNotificationGuide: () -> Unit,
    onOpenDatePicker: () -> Unit,
    onPreviousDate: () -> Unit,
    onNextDate: () -> Unit,
    onSelectToday: () -> Unit,
    onSyncClick: () -> Unit,
    onExportExcelClick: () -> Unit,
    onViewModeChange: (TimelineViewMode) -> Unit,
    onMapPeriodFilterChange: (MapPeriodFilter) -> Unit,
    onSelectMapStep: (com.autologue.app.domain.model.RouteStep) -> Unit,
    onCalendarDateClicked: (LocalDate) -> Unit,
    onOpenDiaryDetail: (DiaryEntry) -> Unit,
    onPhotoClick: (String) -> Unit,
    onAddCompanionClick: (com.autologue.app.domain.model.RouteStep) -> Unit,
    onRemoveCompanion: (String, String) -> Unit,
    onJumpToEntryMap: (DiaryEntry) -> Unit
) {
    val dateTitle = remember(uiState.viewMode, uiState.selectedDate, uiState.currentYearMonth) {
        when (uiState.viewMode) {
            TimelineViewMode.MONTHLY -> uiState.currentYearMonth.format(
                DateTimeFormatter.ofPattern("yyyy년 M월", Locale.KOREA)
            )
            TimelineViewMode.WEEKLY -> {
                val start = uiState.selectedDate.minusDays(6)
                if (start.month == uiState.selectedDate.month) {
                    "${start.format(DateTimeFormatter.ofPattern("M월 d일", Locale.KOREA))}~${
                        uiState.selectedDate.format(DateTimeFormatter.ofPattern("d일", Locale.KOREA))
                    }"
                } else {
                    "${start.format(DateTimeFormatter.ofPattern("M.d", Locale.KOREA))} ~ ${
                        uiState.selectedDate.format(DateTimeFormatter.ofPattern("M.d", Locale.KOREA))
                    }"
                }
            }
            else -> uiState.selectedDate.format(
                DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)
            )
        }
    }

    val totalExpense = remember(uiState.entries) {
        uiState.entries.sumOf { entry ->
            maxOf(entry.totalExpense, DailyRouteAggregator.calculateRouteStepsExpense(entry.routeSteps))
        }
    }
    val totalDistance = remember(uiState.entries) {
        uiState.entries.sumOf {
            if (it.drivingDistanceKm > 0) it.drivingDistanceKm
            else LocationDistanceUtils.calculateRouteDrivingDistanceKm(it.routeSteps)
        }
    }
    val totalPlaces = remember(uiState.entries) {
        uiState.entries.flatMap { entry ->
            val validSteps = entry.routeSteps.filterNot { DailyRouteAggregator.isIntermediateDrivingStep(it) }
            if (validSteps.isNotEmpty()) {
                validSteps.mapNotNull {
                    it.locationName ?: it.title.takeIf { t -> !t.contains("주행") && !t.contains("촬영") }
                }
            } else {
                listOfNotNull(entry.placeName)
            }
        }.distinct().size
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(EasyTheme.bg)
    ) {
        EasyModeTopBar(
            title = "오늘의 일상",
            subtitle = "결제·사진·이동으로 자동 완성된 하루"
        )

        // 알림 권한 미허용 시 토스 스타일 소프트 안내 배너
        if (!isNotificationEnabled) {
            Surface(
                onClick = onOpenNotificationGuide,
                shape = RoundedCornerShape(16.dp),
                color = EasyTheme.tossBlueSoft,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "실시간 결제 알림 자동 기록 켜기",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = EasyTheme.textPrimary
                        )
                        Text(
                            text = "카카오페이·토스·카드 앱 알림을 자동으로 모아줍니다",
                            fontSize = 12.sp,
                            color = EasyTheme.textSecondary
                        )
                    }
                    Text(
                        text = "켜기",
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.tossBlue
                    )
                }
            }
        }

        // 상단 컨트롤 & 모드 전환 카드
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 6.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            EasySectionCard(
                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
            ) {
                EasyDateNavigatorRow(
                    title = dateTitle,
                    onPrevious = onPreviousDate,
                    onNext = onNextDate,
                    onTitleClick = onOpenDatePicker,
                    trailingAction = {
                        if (uiState.selectedDate != LocalDate.now()) {
                            Surface(
                                onClick = onSelectToday,
                                shape = RoundedCornerShape(12.dp),
                                color = EasyTheme.tossBlueSoft
                            ) {
                                Text(
                                    text = "오늘",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = EasyTheme.tossBlue,
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp)
                                )
                            }
                        }
                        Surface(
                            onClick = onSyncClick,
                            shape = RoundedCornerShape(12.dp),
                            color = EasyTheme.chipBg
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Sync,
                                    contentDescription = "기록 불러오기",
                                    tint = EasyTheme.textSecondary,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "불러오기",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = EasyTheme.textSecondary,
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                        Surface(
                            onClick = onExportExcelClick,
                            shape = CircleShape,
                            color = EasyTheme.chipBg,
                            modifier = Modifier.size(30.dp)
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                if (uiState.isExportingExcel) {
                                    CircularProgressIndicator(
                                        modifier = Modifier.size(14.dp),
                                        strokeWidth = 2.dp,
                                        color = EasyTheme.tossBlue
                                    )
                                } else {
                                    Icon(
                                        imageVector = Icons.Default.FileDownload,
                                        contentDescription = "엑셀 저장",
                                        tint = EasyTheme.textSecondary,
                                        modifier = Modifier.size(15.dp)
                                    )
                                }
                            }
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                EasySegmentedBar(
                    options = listOf(
                        TimelineViewMode.DAILY to "하루 기록",
                        TimelineViewMode.MAP_ROUTE to "이동 지도",
                        TimelineViewMode.WEEKLY to "최근 7일",
                        TimelineViewMode.MONTHLY to "월간 달력"
                    ),
                    selected = uiState.viewMode,
                    onSelect = onViewModeChange
                )

                if (uiState.viewMode == TimelineViewMode.DAILY || uiState.viewMode == TimelineViewMode.WEEKLY) {
                    Spacer(modifier = Modifier.height(16.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.Bottom
                    ) {
                        Column {
                            Text(
                                text = if (uiState.viewMode == TimelineViewMode.WEEKLY) "최근 7일 쓴 돈" else "이 날 쓴 돈",
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Medium,
                                color = EasyTheme.textSecondary
                            )
                            Spacer(modifier = Modifier.height(3.dp))
                            Text(
                                text = "%,d원".format(totalExpense),
                                fontSize = 27.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.7).sp,
                                color = if (totalExpense > 0) EasyTheme.textPrimary else EasyTheme.textMuted
                            )
                        }

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Surface(
                                onClick = { onViewModeChange(TimelineViewMode.MAP_ROUTE) },
                                shape = RoundedCornerShape(14.dp),
                                color = EasyTheme.surfaceSubtle
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "이동 거리",
                                        fontSize = 11.sp,
                                        color = EasyTheme.textMuted
                                    )
                                    Text(
                                        text = "%.1f km".format(totalDistance),
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.textPrimary
                                    )
                                }
                            }
                            Surface(
                                onClick = { onViewModeChange(TimelineViewMode.MAP_ROUTE) },
                                shape = RoundedCornerShape(14.dp),
                                color = EasyTheme.surfaceSubtle
                            ) {
                                Column(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalAlignment = Alignment.End
                                ) {
                                    Text(
                                        text = "머문 장소",
                                        fontSize = 11.sp,
                                        color = EasyTheme.textMuted
                                    )
                                    Text(
                                        text = "${totalPlaces}곳",
                                        fontSize = 14.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.tossBlue
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // 본문 영역
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            when (uiState.viewMode) {
                TimelineViewMode.MAP_ROUTE -> {
                    Surface(
                        shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
                        color = EasyTheme.surface,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(top = 4.dp)
                    ) {
                        GoogleMapRouteView(
                            routeSteps = uiState.mapRouteSteps,
                            selectedStep = uiState.selectedMapStep,
                            periodFilter = uiState.mapPeriodFilter,
                            onPeriodFilterChanged = onMapPeriodFilterChange,
                            onStepSelected = onSelectMapStep,
                            onPhotoClick = onPhotoClick,
                            onAddCompanionClick = onAddCompanionClick,
                            onRemoveCompanion = onRemoveCompanion
                        )
                    }
                }

                TimelineViewMode.MONTHLY -> {
                    Surface(
                        shape = RoundedCornerShape(22.dp),
                        color = EasyTheme.surface,
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(horizontal = 16.dp, vertical = 4.dp)
                            .swipeDateNavigation(
                                key = uiState.currentYearMonth,
                                onSwipeLeft = onNextDate,
                                onSwipeRight = onPreviousDate
                            )
                    ) {
                        MonthlyCalendarView(
                            monthlySummary = uiState.monthlyCalendarData,
                            selectedDate = uiState.selectedDate,
                            onDateClick = onCalendarDateClicked,
                            onOpenMonthPicker = onOpenDatePicker
                        )
                    }
                }

                TimelineViewMode.DAILY, TimelineViewMode.WEEKLY -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .swipeDateNavigation(
                                key = uiState.selectedDate,
                                onSwipeLeft = onNextDate,
                                onSwipeRight = onPreviousDate
                            )
                    ) {
                        if (uiState.entries.isEmpty()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(horizontal = 16.dp, vertical = 12.dp),
                                contentAlignment = Alignment.TopCenter
                            ) {
                                EasySectionCard(
                                    contentPadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp)
                                ) {
                                    Column(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalAlignment = Alignment.CenterHorizontally,
                                        verticalArrangement = Arrangement.spacedBy(10.dp)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(52.dp)
                                                .clip(CircleShape)
                                                .background(EasyTheme.tossBlueSoft),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.AutoStories,
                                                contentDescription = null,
                                                tint = EasyTheme.tossBlue,
                                                modifier = Modifier.size(24.dp)
                                            )
                                        }
                                        Text(
                                            text = if (uiState.viewMode == TimelineViewMode.WEEKLY) {
                                                "최근 7일간 기록된 일상이 없습니다"
                                            } else {
                                                "이 날 기록된 일상이 없습니다"
                                            },
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EasyTheme.textPrimary
                                        )
                                        Text(
                                            text = "결제 문자나 갤러리 사진이 있으면\n이동 경로와 하루 일과가 자동으로 정리됩니다.",
                                            fontSize = 13.5.sp,
                                            lineHeight = 19.sp,
                                            color = EasyTheme.textMuted,
                                            textAlign = TextAlign.Center
                                        )
                                        Spacer(modifier = Modifier.height(4.dp))
                                        EasyActionButton(
                                            text = "과거 기록 불러오기",
                                            icon = Icons.Default.Sync,
                                            onClick = onSyncClick,
                                            isPrimary = true
                                        )
                                    }
                                }
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(
                                    start = 16.dp,
                                    end = 16.dp,
                                    top = 4.dp,
                                    bottom = 96.dp
                                ),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(
                                    items = uiState.entries,
                                    key = { it.id }
                                ) { entry ->
                                    EasyDiaryJournalCard(
                                        entry = entry,
                                        onClick = { onOpenDiaryDetail(entry) },
                                        onPhotoClick = onPhotoClick,
                                        onMapClick = { onJumpToEntryMap(entry) }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            SmartDiaryLoadingOverlay(
                isLoading = uiState.isLoading || uiState.isExportingExcel,
                message = if (uiState.isExportingExcel) {
                    "다이어리 및 전체 라이프로그 엑셀 파일을 생성하고 있습니다..."
                } else {
                    uiState.loadingMessage
                },
                modifier = Modifier.fillMaxSize()
            )
        }
    }
}

/**
 * 애플 저널(Apple Journal) · 토스 타임라인 스타일의 하루 기록 카드
 */
@Composable
private fun EasyDiaryJournalCard(
    entry: DiaryEntry,
    onClick: () -> Unit,
    onPhotoClick: (String) -> Unit,
    onMapClick: () -> Unit
) {
    val displayExpense = remember(entry.totalExpense, entry.routeSteps) {
        maxOf(entry.totalExpense, DailyRouteAggregator.calculateRouteStepsExpense(entry.routeSteps))
    }
    val displayKm = remember(entry.drivingDistanceKm, entry.routeSteps) {
        if (entry.drivingDistanceKm > 0) entry.drivingDistanceKm
        else LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
    }

    EasySectionCard(
        onClick = onClick,
        contentPadding = PaddingValues(20.dp)
    ) {
        // 상단 날짜·시간 및 대표 장소 캡션
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = entry.date.format(DateTimeFormatter.ofPattern("M월 d일 (E) HH:mm", Locale.KOREA)),
                    fontSize = 12.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EasyTheme.textMuted
                )
                if (!entry.placeName.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = EasyTheme.tossBlueSoft
                    ) {
                        Text(
                            text = entry.placeName,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = EasyTheme.tossBlue,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }
            }

            if (displayKm > 0) {
                Text(
                    text = "%.1f km 이동".format(displayKm),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    color = EasyTheme.textSecondary
                )
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 메인 타이틀 & 지출 금액
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = entry.title,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                color = EasyTheme.textPrimary,
                modifier = Modifier.weight(1f),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (displayExpense > 0) {
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                    text = "-%,d원".format(displayExpense),
                    fontSize = 17.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = EasyTheme.textPrimary
                )
            } else if (entry.hasGolfRound) {
                Spacer(modifier = Modifier.width(12.dp))
                Surface(
                    shape = RoundedCornerShape(10.dp),
                    color = EasyTheme.appleGreenSoft
                ) {
                    Text(
                        text = "골프 라운드",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.appleGreen,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // 이동 동선 요약 스트립 (이모지 배제, 깔끔한 캡슐 체인)
        if (!entry.movementSummary.isNullOrBlank() && entry.movementSummary != "기록된 활동 없음") {
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val places = entry.movementSummary.split(" ➔ ", " -> ")
                places.forEachIndexed { idx, place ->
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = EasyTheme.surfaceSubtle
                    ) {
                        Text(
                            text = place.trim(),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = EasyTheme.textSecondary,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                    if (idx < places.lastIndex) {
                        Icon(
                            imageVector = Icons.Default.ArrowForward,
                            contentDescription = null,
                            tint = EasyTheme.textMuted,
                            modifier = Modifier.size(13.dp)
                        )
                    }
                }
            }
        }

        // 사진 썸네일 그리드
        if (entry.photoUris.isNotEmpty()) {
            Spacer(modifier = Modifier.height(14.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                val previewPhotos = entry.photoUris.take(4)
                val remaining = entry.photoUris.size - 4
                previewPhotos.forEachIndexed { index, uri ->
                    Box(
                        modifier = Modifier
                            .size(68.dp)
                            .clip(RoundedCornerShape(14.dp))
                            .border(0.5.dp, EasyTheme.borderSubtle, RoundedCornerShape(14.dp))
                            .clickable { onPhotoClick(uri) }
                    ) {
                        AsyncImage(
                            model = uri,
                            contentDescription = "일상 사진",
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
                                    color = Color.White,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }
        }

        // 요약 본문
        if (entry.summary.isNotBlank()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                text = entry.summary,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = EasyTheme.textSecondary,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.height(14.dp))
        HorizontalDivider(color = EasyTheme.divider)
        Spacer(modifier = Modifier.height(10.dp))

        // 하단 원탭 액션 행
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                Text(
                    text = "시간대별 상세 일정 · 메모 보기",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EasyTheme.tossBlue
                )
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    tint = EasyTheme.tossBlue,
                    modifier = Modifier.size(16.dp)
                )
            }

            Surface(
                onClick = onMapClick,
                shape = RoundedCornerShape(10.dp),
                color = EasyTheme.chipBg
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = null,
                        tint = EasyTheme.textSecondary,
                        modifier = Modifier.size(14.dp)
                    )
                    Text(
                        text = "지도 동선",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = EasyTheme.textSecondary
                    )
                }
            }
        }
    }
}
