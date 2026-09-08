package com.autologue.app.presentation.diary.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.diary.MapPeriodFilter
import com.autologue.app.presentation.theme.*
import java.time.format.DateTimeFormatter
import java.util.*

/**
 * 개별 운행 주행 세션(Trip) 또는 일자별 이동 경로 그룹
 */
data class RouteTripGroup(
    val id: String,
    val title: String,
    val subtitle: String,
    val brandEmoji: String,
    val date: java.time.LocalDate,
    val steps: List<RouteStep>,
    val totalDistanceKm: Double = 0.0
)

fun segmentRouteIntoTrips(steps: List<RouteStep>): List<RouteTripGroup> {
    val valid = steps.filter { it.latitude != null && it.longitude != null }.sortedBy { it.time }
    if (valid.isEmpty()) return emptyList()

    val groups = mutableListOf<RouteTripGroup>()
    var currentGroupSteps = mutableListOf<RouteStep>()

    for (step in valid) {
        val lastStep = currentGroupSteps.lastOrNull()
        val isDifferentDay = lastStep != null && lastStep.time.toLocalDate() != step.time.toLocalDate()
        val isTimeGap = lastStep != null && java.time.Duration.between(lastStep.time, step.time).abs().toMinutes() > 40
        val isNewDeparture = step.tags.contains("출발지점") || step.title.contains("출발")

        if (currentGroupSteps.isNotEmpty() && (isDifferentDay || isTimeGap || (isNewDeparture && lastStep?.tags?.contains("도착지점") == true))) {
            groups.add(createTripGroup(currentGroupSteps))
            currentGroupSteps = mutableListOf()
        }

        currentGroupSteps.add(step)

        if (step.tags.contains("도착지점") || step.title.contains("도착")) {
            groups.add(createTripGroup(currentGroupSteps))
            currentGroupSteps = mutableListOf()
        }
    }

    if (currentGroupSteps.isNotEmpty()) {
        groups.add(createTripGroup(currentGroupSteps))
    }

    return groups
}

private fun createTripGroup(steps: List<RouteStep>): RouteTripGroup {
    val first = steps.first()
    val last = steps.last()
    val date = first.time.toLocalDate()

    val brandEmoji = steps.mapNotNull { s ->
        val tag = s.tags.firstOrNull {
            it.startsWith("⭐") || it.startsWith("🛡️") || it.startsWith("🔵") ||
            it.startsWith("🔗") || it.startsWith("🪽") || it.startsWith("🚗")
        }
        tag ?: com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(s.title)
    }.firstOrNull() ?: "🚗"

    val hasGolf = steps.any { it.stepType == RouteStepType.GOLF || it.title.contains("골프") || it.title.contains("CC") }
    val isCommuteToWork = steps.any { it.title.contains("회사") && (it.title.contains("도착") || it.tags.contains("도착지점")) }
    val isCommuteToHome = steps.any { it.title.contains("집") && (it.title.contains("도착") || it.tags.contains("도착지점")) }

    val tripName = when {
        hasGolf -> "골프 라운드"
        isCommuteToWork -> "출근 주행"
        isCommuteToHome -> "퇴근/귀가"
        first.time.hour < 11 -> "오전 주행"
        first.time.hour < 17 -> "오후 이동"
        else -> "야간 주행"
    }

    val distKm = com.autologue.app.util.LocationDistanceUtils.calculateRouteDrivingDistanceKm(steps)
    val startPlace = first.locationName ?: first.title.replace(Regex("\\[.*?\\]"), "").trim()
    val endPlace = last.locationName ?: last.title.replace(Regex("\\[.*?\\]"), "").trim()

    val subtitle = if (steps.size > 1) "$startPlace ➔ $endPlace" else startPlace
    val timeStr = first.time.format(java.time.format.DateTimeFormatter.ofPattern("M/d a h:mm", java.util.Locale.KOREAN))
    val title = "$timeStr $tripName"

    return RouteTripGroup(
        id = "${date}_${first.time}_${steps.size}_${first.id}",
        title = title,
        subtitle = subtitle,
        brandEmoji = brandEmoji,
        date = date,
        steps = steps,
        totalDistanceKm = distKm
    )
}

@Composable
fun GoogleMapRouteView(
    routeSteps: List<RouteStep>,
    selectedStep: RouteStep?,
    periodFilter: MapPeriodFilter,
    onPeriodFilterChanged: (MapPeriodFilter) -> Unit,
    onStepSelected: (RouteStep) -> Unit,
    onPhotoClick: (String) -> Unit,
    onAddCompanionClick: ((RouteStep) -> Unit)? = null,
    onRemoveCompanion: ((String, String) -> Unit)? = null
) {
    val context = LocalContext.current
    val nonTransactionSteps = remember(routeSteps) {
        routeSteps.filter {
            it.stepType != RouteStepType.TRANSACTION &&
            !it.title.contains("입금") && !it.title.contains("출금") &&
            !it.title.contains("이체") && !it.title.contains("송금")
        }
    }
    val validCoordinateSteps = remember(nonTransactionSteps) {
        nonTransactionSteps.filter { it.latitude != null && it.longitude != null }
    }

    var mapFocusStep by remember { mutableStateOf<RouteStep?>(selectedStep) }
    var selectedTripGroupId by remember { mutableStateOf<String?>(null) }
    var isCanvasMapMode by remember { mutableStateOf(false) }
    var isWebViewLoading by remember { mutableStateOf(false) }

    val tripGroups = remember(validCoordinateSteps) { segmentRouteIntoTrips(validCoordinateSteps) }
    val selectedTripGroup = remember(tripGroups, selectedTripGroupId) {
        tripGroups.firstOrNull { it.id == selectedTripGroupId }
    }
    val activeDisplaySteps = remember(validCoordinateSteps, selectedTripGroup) {
        selectedTripGroup?.steps ?: validCoordinateSteps
    }

    LaunchedEffect(periodFilter) {
        selectedTripGroupId = null
        mapFocusStep = null
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.background)) {
        // 1. Top Period Filter Chips Bar
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                modifier = Modifier.horizontalScroll(rememberScrollState()).weight(1f)
            ) {
                MapPeriodFilter.values().forEach { filter ->
                    val isSelected = filter == periodFilter
                    Surface(
                        shape = AppShapes.pill,
                        color = if (isSelected) AppColors.primary else AppColors.surfaceVariant,
                        border = BorderStroke(
                            width = 0.5.dp,
                            color = if (isSelected) AppColors.primary else AppColors.border
                        ),
                        modifier = Modifier.clickable { onPeriodFilterChanged(filter) }
                    ) {
                        Text(
                            text = filter.title,
                            modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 6.dp),
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PureWhite else AppColors.textSecondary
                        )
                    }
                }
            }

            // Google Maps Open Button (전체 이동 경로 또는 거점 핀 열기)
            if (validCoordinateSteps.isNotEmpty()) {
                IconButton(
                    onClick = {
                        if (validCoordinateSteps.size > 1) {
                            openGoogleMapsRoute(context, validCoordinateSteps)
                        } else {
                            val step = validCoordinateSteps.first()
                            openGoogleMapsLocation(
                                context = context,
                                latitude = step.latitude!!,
                                longitude = step.longitude!!,
                                label = step.locationName ?: step.title,
                                address = step.address
                            )
                        }
                    },
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "Google 지도 앱에서 전체 이동 경로 열기",
                        tint = AppColors.primary,
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }

        // 1-2. 스마트 운행 트립(Trip) & 일자별 세그먼트 칩 바 (원하는 주행 경로만 분리 확인)
        if (tripGroups.size > 1) {
            Surface(
                color = Color(0xFF0F172A),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // 전체 모아보기 칩
                    val isAllSelected = selectedTripGroupId == null
                    Surface(
                        shape = AppShapes.pill,
                        color = if (isAllSelected) Color(0xFF2563EB) else Color(0xFF1E293B),
                        border = BorderStroke(
                            1.dp,
                            if (isAllSelected) Color(0xFF60A5FA) else Color(0xFF334155)
                        ),
                        modifier = Modifier.clickable {
                            selectedTripGroupId = null
                            mapFocusStep = null
                        }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Text(
                                text = "🌐 전체 모아보기 (${validCoordinateSteps.size}개)",
                                fontSize = 11.sp,
                                fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isAllSelected) PureWhite else Color(0xFF94A3B8)
                            )
                        }
                    }

                    // 개별 트립/일자별 칩들
                    tripGroups.forEach { group ->
                        val isSelected = selectedTripGroupId == group.id
                        Surface(
                            shape = AppShapes.pill,
                            color = if (isSelected) Color(0xFF2563EB) else Color(0xFF1E293B),
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) Color(0xFF60A5FA) else Color(0xFF334155)
                            ),
                            modifier = Modifier.clickable {
                                selectedTripGroupId = group.id
                                mapFocusStep = null
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            ) {
                                Text(
                                    text = "${group.brandEmoji} ${group.title} · ${group.steps.size}지점",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PureWhite else Color(0xFFE2E8F0)
                                )
                                if (group.totalDistanceKm > 0) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "(%.1fkm)".format(group.totalDistanceKm),
                                        fontSize = 10.sp,
                                        color = if (isSelected) Color(0xFFBFDBFE) else Color(0xFF64748B)
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        HairlineDivider()

        // 2. Interactive Google Maps Embed Area (에뮬레이터 100% 렌더링 HTML5 Leaflet 맵 / 순수 Compose 벡터 레이더 듀얼 모드)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(260.dp)
                .clipToBounds()
                .background(Color(0xFF0F172A))
        ) {
            if (activeDisplaySteps.isNotEmpty()) {
                if (isCanvasMapMode) {
                    // 순수 Compose 캔버스 레이더 지도
                    val focusIndex = remember(activeDisplaySteps, mapFocusStep) {
                        if (mapFocusStep != null) {
                            activeDisplaySteps.indexOfFirst { it.id == mapFocusStep?.id }.coerceAtLeast(0)
                        } else 0
                    }
                    InteractiveRouteMapView(
                        steps = activeDisplaySteps,
                        selectedIndex = focusIndex,
                        onStepSelected = { idx ->
                            mapFocusStep = activeDisplaySteps.getOrNull(idx)
                            mapFocusStep?.let { onStepSelected(it) }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    // 인라인 HTML5 Leaflet + CartoDB 지도 웹뷰 (에뮬레이터/실기기 100% 번호 핀 및 경로선 렌더링)
                    val targetMapHtml = remember(activeDisplaySteps, mapFocusStep) {
                        buildInteractiveHtmlMap(activeDisplaySteps, mapFocusStep)
                    }

                    AndroidView(
                        factory = { ctx ->
                            WebView(ctx).apply {
                                @SuppressLint("SetJavaScriptEnabled")
                                settings.javaScriptEnabled = true
                                settings.domStorageEnabled = true
                                settings.loadWithOverviewMode = true
                                settings.useWideViewPort = true
                                settings.setSupportZoom(true)
                                settings.builtInZoomControls = true
                                settings.displayZoomControls = false
                                webViewClient = object : WebViewClient() {
                                    override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                        isWebViewLoading = true
                                    }
                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        isWebViewLoading = false
                                    }
                                    @Deprecated("Deprecated in Java")
                                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                                        return false
                                    }
                                }
                                loadDataWithBaseURL("https://unpkg.com", targetMapHtml, "text/html", "UTF-8", null)
                            }
                        },
                        update = { webView ->
                            webView.loadDataWithBaseURL("https://unpkg.com", targetMapHtml, "text/html", "UTF-8", null)
                        },
                        modifier = Modifier.fillMaxSize()
                    )

                    if (isWebViewLoading) {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF0F172A).copy(alpha = 0.4f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier
                                    .background(Color(0xFF1E293B), RoundedCornerShape(20.dp))
                                    .padding(horizontal = 14.dp, vertical = 8.dp)
                            ) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(14.dp),
                                    strokeWidth = 2.dp,
                                    color = Color(0xFF60A5FA)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text("지도 불러오는 중...", fontSize = 11.sp, color = Color(0xFFE2E8F0))
                            }
                        }
                    }
                }

                // Top Status Badge Overlay (좌측 상단: 선택된 트립/지점 상태)
                Surface(
                    modifier = Modifier
                        .padding(Spacing.sm)
                        .align(Alignment.TopStart),
                    shape = AppShapes.pill,
                    color = Color(0xFF0F172A).copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 5.dp)
                    ) {
                        val badgeText = when {
                            mapFocusStep != null -> "📍 ${mapFocusStep?.locationName ?: mapFocusStep?.title} (지점 포커스)"
                            selectedTripGroup != null -> "${selectedTripGroup.brandEmoji} [${selectedTripGroup.title}] ${selectedTripGroup.subtitle} (${activeDisplaySteps.size}개 지점)"
                            else -> "🚗 조회된 차량 이동 동선 (${activeDisplaySteps.size}개 지점)"
                        }
                        Text(
                            text = badgeText,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF60A5FA)
                        )
                        if (mapFocusStep != null || selectedTripGroup != null) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFF2563EB).copy(alpha = 0.25f),
                                modifier = Modifier.clickable {
                                    if (mapFocusStep != null) {
                                        mapFocusStep = null
                                    } else {
                                        selectedTripGroupId = null
                                    }
                                }
                            ) {
                                Text(
                                    text = if (mapFocusStep != null) "경로 복귀" else "전체 복귀",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF93C5FD),
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    }
                }

                // Top Mode Switcher Toggle (우측 상단: 실시간 지도 ↔ 레이더 지도 전환)
                Surface(
                    modifier = Modifier
                        .padding(Spacing.sm)
                        .align(Alignment.TopEnd)
                        .clickable { isCanvasMapMode = !isCanvasMapMode },
                    shape = AppShapes.pill,
                    color = if (isCanvasMapMode) Color(0xFF2563EB) else Color(0xFF1E293B).copy(alpha = 0.92f),
                    border = BorderStroke(1.dp, if (isCanvasMapMode) Color(0xFF60A5FA) else Color(0xFF334155)),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    ) {
                        Text(
                            text = if (isCanvasMapMode) "🧭 레이더 맵" else "🗺️ 실시간 지도",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                    }
                }

                // Floating Google Maps App Open Button (우측 하단: 공식 구글맵 앱에서 열기)
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Color(0xFF0F172A).copy(alpha = 0.9f),
                    border = BorderStroke(1.dp, Color(0xFF334155)),
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp)
                        .clickable {
                            if (mapFocusStep != null) {
                                openGoogleMapsLocation(
                                    context = context,
                                    latitude = mapFocusStep!!.latitude!!,
                                    longitude = mapFocusStep!!.longitude!!,
                                    label = mapFocusStep!!.locationName ?: mapFocusStep!!.title,
                                    address = mapFocusStep!!.address
                                )
                            } else {
                                openGoogleMapsRoute(context, activeDisplaySteps)
                            }
                        }
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.OpenInNew,
                            contentDescription = "앱에서 열기",
                            tint = Color(0xFF60A5FA),
                            modifier = Modifier.size(13.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = if (mapFocusStep != null) "지점 앱에서 열기" else "전체 경로 앱에서 열기",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                    }
                }
            } else {
                // Empty Coordinates State
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("📍", fontSize = 36.sp)
                        Spacer(modifier = Modifier.height(Spacing.xs))
                        Text(
                            text = "해당 기간에 등록된 GPS 위치 정보가 없습니다.",
                            style = AppTypography.bodySecondary.copy(color = Color(0xFF94A3B8))
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "사진(GPS) 또는 실제 이동 기록의 위치가 자동으로 표시됩니다.",
                            style = AppTypography.captionMuted.copy(color = Color(0xFF64748B))
                        )
                    }
                }
            }
        }

        HairlineDivider()

        // 3. Staggered Vertical List: 지점 1, 2, 3... 모든 항목 아래로 펼쳐진 리스트
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .background(AppColors.background),
            contentPadding = PaddingValues(vertical = Spacing.sm)
        ) {
            // Header: 전체 경로보기 마스터 버튼 (조회된 결과 전체 차량 이동경로 명확화)
            if (activeDisplaySteps.size > 1) {
                item {
                    Surface(
                        shape = RoundedCornerShape(12.dp),
                        color = Color(0xFFEFF6FF),
                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.md, vertical = Spacing.xs)
                            .clickable {
                                openGoogleMapsRoute(context, activeDisplaySteps)
                            }
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF2563EB)),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.DirectionsCar,
                                    contentDescription = null,
                                    tint = PureWhite,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                val masterTitle = if (selectedTripGroup != null) {
                                    "${selectedTripGroup.brandEmoji} [${selectedTripGroup.title}] 앱에서 내비 안내 (${activeDisplaySteps.size}개 지점)"
                                } else {
                                    "조회된 전체 이동경로 안내 (차량 ${activeDisplaySteps.size}개 지점)"
                                }
                                Text(
                                    text = masterTitle,
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1E3A8A)
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                val masterDesc = if (selectedTripGroup != null) {
                                    "${selectedTripGroup.subtitle} 구간을 순서대로 경유하는 구글맵 내비게이션을 실행합니다."
                                } else {
                                    "현재 조회된 ${activeDisplaySteps.size}개 지점 전체를 순서대로 경유하는 차량 이동경로를 안내합니다."
                                }
                                Text(
                                    text = masterDesc,
                                    fontSize = 11.sp,
                                    color = Color(0xFF3B82F6)
                                )
                            }
                            Icon(
                                imageVector = Icons.Default.ChevronRight,
                                contentDescription = null,
                                tint = Color(0xFF2563EB),
                                modifier = Modifier.size(18.dp)
                            )
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                }
            }

            // Staggered Items: 지점 1, 2, 3...
            itemsIndexed(activeDisplaySteps) { index, step ->
                val isFocused = mapFocusStep?.id == step.id || (mapFocusStep == null && selectedStep?.id == step.id)

                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isFocused) Color(0xFFF8FAFC) else AppColors.surface,
                    border = BorderStroke(
                        width = if (isFocused) 1.5.dp else 1.dp,
                        color = if (isFocused) AppColors.primary else Color(0xFFE2E8F0)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.md, vertical = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        // Top Header: Number Badge, Time, Type Pill
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(24.dp)
                                        .clip(CircleShape)
                                        .background(if (isFocused) AppColors.primary else Color(0xFF64748B)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        text = "${index + 1}",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PureWhite
                                    )
                                }

                                Text(
                                    text = step.time.format(DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREA)),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = AppColors.textPrimary
                                )

                                val (typeLabel, typeBg, typeFg) = when (step.stepType) {
                                    RouteStepType.TRANSACTION -> Triple("💳 결제/방문", Emerald50, Emerald700)
                                    RouteStepType.PHOTO -> Triple("📸 사진 기록", Indigo50, Indigo700)
                                    RouteStepType.GOLF -> Triple("⛳ 골프 라운드", Forest50, Forest700)
                                    RouteStepType.DRIVING -> {
                                        val carTag = step.tags.firstOrNull { it != "차량주행" && it != "차량" }
                                        val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(carTag ?: step.title)
                                        if (carTag != null) {
                                            val cleanTag = if (carTag.startsWith("⭐") || carTag.startsWith("🛡️") || carTag.startsWith("🚗")) carTag else "$brandEmoji $carTag"
                                            Triple(cleanTag, Amber50, Amber700)
                                        } else {
                                            Triple("$brandEmoji 차량 주행", Amber50, Amber700)
                                        }
                                    }
                                    RouteStepType.MEMO -> Triple("📝 메모 기록", Slate100, Slate700)
                                    else -> Triple("📍 이동 거점", Slate100, Slate700)
                                }

                                Box(
                                    modifier = Modifier
                                        .clip(AppShapes.pill)
                                        .background(typeBg)
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(text = typeLabel, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = typeFg)
                                }
                            }

                            Text(
                                text = "지점 ${index + 1} / ${activeDisplaySteps.size}",
                                fontSize = 11.sp,
                                color = Color(0xFF64748B)
                            )
                        }

                        Spacer(modifier = Modifier.height(8.dp))

                        // Location Title
                        Text(
                            text = step.locationName ?: step.title,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A)
                        )

                        // Address
                        val addr = step.address ?: ""
                        if (addr.isNotBlank()) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "📍 $addr",
                                fontSize = 12.sp,
                                color = Color(0xFF64748B)
                            )
                        }

                        // Companions Tag Row
                        if (step.companions.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(6.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text("동행인:", fontSize = 11.sp, color = Color(0xFF64748B), fontWeight = FontWeight.Medium)
                                step.companions.forEach { companion ->
                                    Surface(
                                        shape = AppShapes.pill,
                                        color = Indigo50,
                                        border = BorderStroke(0.5.dp, Indigo600.copy(alpha = 0.3f))
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                        ) {
                                            Text(
                                                text = "👤 $companion",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Indigo700
                                            )
                                            if (onRemoveCompanion != null) {
                                                Spacer(modifier = Modifier.width(4.dp))
                                                Icon(
                                                    imageVector = Icons.Default.Close,
                                                    contentDescription = "동행인 삭제",
                                                    tint = Indigo600,
                                                    modifier = Modifier
                                                        .size(11.dp)
                                                        .clickable { onRemoveCompanion(step.id, companion) }
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Photo Carousel
                        if (step.photoUris.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(8.dp))
                            LazyRow(
                                horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                items(step.photoUris) { photoUrl ->
                                    Box(
                                        modifier = Modifier
                                            .size(56.dp)
                                            .clip(RoundedCornerShape(6.dp))
                                            .background(AppColors.surfaceVariant)
                                            .clickable { onPhotoClick(photoUrl) }
                                    ) {
                                        AsyncImage(
                                            model = photoUrl,
                                            contentDescription = "장소 사진",
                                            contentScale = ContentScale.Crop,
                                            modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // Action Buttons Row
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedButton(
                                onClick = {
                                    mapFocusStep = step
                                    onStepSelected(step)
                                },
                                shape = AppShapes.button,
                                border = BorderStroke(0.5.dp, if (isFocused) AppColors.primary else Color(0xFFCBD5E1)),
                                modifier = Modifier.weight(1f).height(34.dp),
                                contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(13.dp), tint = AppColors.primary)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("지도에서 보기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.primary)
                            }

                            if (step.latitude != null && step.longitude != null) {
                                OutlinedButton(
                                    onClick = {
                                        openGoogleMapsLocation(
                                            context = context,
                                            latitude = step.latitude,
                                            longitude = step.longitude,
                                            label = step.locationName ?: step.title,
                                            address = step.address
                                        )
                                    },
                                    shape = AppShapes.button,
                                    border = BorderStroke(0.5.dp, Color(0xFFCBD5E1)),
                                    modifier = Modifier.weight(1f).height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Map, contentDescription = null, modifier = Modifier.size(13.dp), tint = Color(0xFF2563EB))
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("구글맵 앱 열기", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2563EB))
                                }
                            }

                            if (onAddCompanionClick != null) {
                                OutlinedButton(
                                    onClick = { onAddCompanionClick(step) },
                                    shape = AppShapes.button,
                                    border = BorderStroke(0.5.dp, Color(0xFFCBD5E1)),
                                    modifier = Modifier.height(34.dp),
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 0.dp)
                                ) {
                                    Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(13.dp), tint = AppColors.primary)
                                    Spacer(modifier = Modifier.width(3.dp))
                                    Text("동행인", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.primary)
                                }
                            }
                        }
                    }
                }

                // Intermediate Route Indicator (도로 주행 이동 뱃지)
                if (index < activeDisplaySteps.size - 1) {
                    val nextStep = activeDisplaySteps[index + 1]
                    Surface(
                        shape = AppShapes.pill,
                        color = Color(0xFFF1F5F9),
                        border = BorderStroke(0.5.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = 3.dp)
                            .clickable {
                                // 두 지점 사이의 구간으로 지도 포커스 리셋
                                mapFocusStep = null
                            }
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                            horizontalArrangement = Arrangement.Center,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "↓ 지점 ${index + 1} ➔ 지점 ${index + 2} 차량 이동 구간",
                                fontSize = 10.sp,
                                color = Color(0xFF475569),
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 순수 Jetpack Compose 기반 Interactive Route Map (레이더 & 지형 그리드 캔버스)
 */
@Composable
fun InteractiveRouteMapView(
    steps: List<RouteStep>,
    selectedIndex: Int,
    onStepSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }
    var panOffset by remember { mutableStateOf(Offset.Zero) }

    // Pulsing radar animation for selected waypoint
    val infiniteTransition = rememberInfiniteTransition(label = "RadarPulse")
    val pulseRadius by infiniteTransition.animateFloat(
        initialValue = 16f,
        targetValue = 60f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseRadius"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.8f,
        targetValue = 0.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(1500, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulseAlpha"
    )

    val coordinates = remember(steps) {
        steps.mapNotNull {
            if (it.latitude != null && it.longitude != null) {
                it to (it.latitude to it.longitude)
            } else null
        }
    }

    BoxWithConstraints(
        modifier = modifier
            .fillMaxSize()
            .clipToBounds()
            .background(Color(0xFF0F172A))
            .pointerInput(Unit) {
                detectTransformGestures { _, pan: Offset, zoom: Float, _ ->
                    zoomLevel = (zoomLevel * zoom).coerceIn(0.5f, 4.0f)
                    panOffset = Offset(panOffset.x + pan.x, panOffset.y + pan.y)
                }
            }
    ) {
        val widthPx = constraints.maxWidth.toFloat()
        val heightPx = constraints.maxHeight.toFloat()
        val centerX = widthPx / 2f + panOffset.x
        val centerY = heightPx / 2f + panOffset.y

        // Calculate points in screen space
        val pointOffsets = remember(coordinates, zoomLevel, panOffset, widthPx, heightPx) {
            if (coordinates.isEmpty()) emptyList()
            else {
                val minLat = coordinates.minOf { it.second.first }
                val maxLat = coordinates.maxOf { it.second.first }
                val minLng = coordinates.minOf { it.second.second }
                val maxLng = coordinates.maxOf { it.second.second }

                val latSpan = (maxLat - minLat).coerceAtLeast(0.008)
                val lngSpan = (maxLng - minLng).coerceAtLeast(0.008)

                val mapW = widthPx * 0.7f * zoomLevel
                val mapH = heightPx * 0.6f * zoomLevel

                coordinates.mapIndexed { idx, (_, latLng) ->
                    val normX = if (maxLng == minLng) 0.5f else ((latLng.second - minLng) / lngSpan).toFloat()
                    val normY = if (maxLat == minLat) 0.5f else (1f - ((latLng.first - minLat) / latSpan).toFloat())

                    val ptX = centerX + (normX - 0.5f) * mapW
                    val ptY = centerY + (normY - 0.5f) * mapH
                    idx to Offset(ptX, ptY)
                }
            }
        }

        // [GC 최적화] 펄스 레이더 애니메이션에 의해 매 프레임 실행되는 Canvas 내부에서
        // Path() 및 PathEffect 객체가 수백 회 무한 생성되어 GC 랙을 일으키는 현상을 원천 방어
        val cachedRoutePath = remember(pointOffsets) {
            if (pointOffsets.size > 1) {
                Path().apply {
                    pointOffsets.forEachIndexed { i, (_, pt) ->
                        if (i == 0) moveTo(pt.x, pt.y) else lineTo(pt.x, pt.y)
                    }
                }
            } else null
        }
        val radarDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f) }
        val routeDashEffect = remember { PathEffect.dashPathEffect(floatArrayOf(16f, 10f), 0f) }

        // 1. Vector Map Canvas (Blueprint grid, radar circles, route path)
        // clipToBounds 및 clipRect를 적용하여 동심원/경로선이 지도 영역 밖(상단 메뉴/필터바)으로 침범하지 않도록 완벽 차단
        Canvas(modifier = Modifier.fillMaxSize().clipToBounds()) {
            clipRect {
                // Draw Coordinate Grid
                val gridSize = 36.dp.toPx() * zoomLevel
                val startX = ((panOffset.x % gridSize) + gridSize) % gridSize
                val startY = ((panOffset.y % gridSize) + gridSize) % gridSize

                var gx = startX
                while (gx < widthPx) {
                    drawLine(
                        color = Color(0xFF1E293B),
                        start = Offset(gx, 0f),
                        end = Offset(gx, heightPx),
                        strokeWidth = 1f
                    )
                    gx += gridSize
                }

                var gy = startY
                while (gy < heightPx) {
                    drawLine(
                        color = Color(0xFF1E293B),
                        start = Offset(0f, gy),
                        end = Offset(widthPx, gy),
                        strokeWidth = 1f
                    )
                    gy += gridSize
                }

                // Radar concentric circles around center
                for (r in 1..4) {
                    drawCircle(
                        color = Color(0xFF334155).copy(alpha = 0.4f),
                        radius = r * 70.dp.toPx() * zoomLevel,
                        center = Offset(centerX, centerY),
                        style = Stroke(
                            width = 1.2f,
                            pathEffect = radarDashEffect
                        )
                    )
                }

                // Draw Route Polyline (캐시된 Path 재사용)
                if (cachedRoutePath != null) {
                    // Ambient Route Glow
                    drawPath(
                        path = cachedRoutePath,
                        color = Color(0xFF2563EB).copy(alpha = 0.35f),
                        style = Stroke(
                            width = 10.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    // Crisp Dashed Vector Path
                    drawPath(
                        path = cachedRoutePath,
                        color = Color(0xFF60A5FA),
                        style = Stroke(
                            width = 3.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = routeDashEffect
                        )
                    )
                }

                // Draw Pulsing Radar Circle on Selected Waypoint
                val selectedOffset = pointOffsets.getOrNull(selectedIndex)?.second ?: pointOffsets.firstOrNull()?.second
                if (selectedOffset != null) {
                    drawCircle(
                        color = Color(0xFFF59E0B).copy(alpha = pulseAlpha),
                        radius = pulseRadius * zoomLevel,
                        center = selectedOffset
                    )
                    drawCircle(
                        color = Color(0xFF3B82F6).copy(alpha = pulseAlpha * 0.4f),
                        radius = pulseRadius * 1.6f * zoomLevel,
                        center = selectedOffset
                    )
                }
            }
        }

        // 2. Interactive Pin Badges Overlay
        pointOffsets.forEach { (idx, pt) ->
            val step = steps.getOrNull(idx) ?: return@forEach
            val isSelected = idx == selectedIndex
            val num = idx + 1
            val title = step.locationName ?: step.title

            val density = LocalDensity.current
            val xDp = with(density) { pt.x.toDp() }
            val yDp = with(density) { pt.y.toDp() }

            Box(
                modifier = Modifier
                    .offset(x = xDp - 50.dp, y = yDp - 20.dp)
                    .clickable { onStepSelected(idx) }
            ) {
                Surface(
                    shape = AppShapes.pill,
                    color = if (isSelected) Color(0xFF0F172A) else Color(0xFF1E293B),
                    border = androidx.compose.foundation.BorderStroke(
                        width = if (isSelected) 2.dp else 1.dp,
                        color = if (isSelected) Color(0xFFF59E0B) else Color(0xFF475569)
                    ),
                    shadowElevation = if (isSelected) 8.dp else 3.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        // Badge Number Dot
                        Box(
                            modifier = Modifier
                                .size(18.dp)
                                .clip(CircleShape)
                                .background(if (isSelected) Color(0xFFF59E0B) else Color(0xFF3B82F6)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "$num",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = if (isSelected) Color(0xFF0F172A) else PureWhite
                            )
                        }

                        Spacer(modifier = Modifier.width(6.dp))

                        Text(
                            text = title,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = PureWhite
                        )
                    }
                }
            }
        }

        // 3. Floating Zoom & Navigation Controls
        Column(
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.md),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            horizontalAlignment = Alignment.End
        ) {
            // Recenter Button
            SmallFloatingActionButton(
                onClick = {
                    zoomLevel = 1.0f
                    panOffset = Offset.Zero
                },
                containerColor = Color(0xFF1E293B),
                contentColor = PureWhite,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.MyLocation, contentDescription = "중심 맞추기", modifier = Modifier.size(18.dp))
            }

            // Zoom In Button
            SmallFloatingActionButton(
                onClick = { zoomLevel = (zoomLevel * 1.3f).coerceAtMost(4.0f) },
                containerColor = Color(0xFF1E293B),
                contentColor = PureWhite,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "확대", modifier = Modifier.size(18.dp))
            }

            // Zoom Out Button
            SmallFloatingActionButton(
                onClick = { zoomLevel = (zoomLevel / 1.3f).coerceAtLeast(0.5f) },
                containerColor = Color(0xFF1E293B),
                contentColor = PureWhite,
                shape = RoundedCornerShape(8.dp),
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "축소", modifier = Modifier.size(18.dp))
            }
        }
    }
}

/**
 * 특정 거점(스텝)을 Google 지도에 마커(Pin)와 장소명으로 정확하게 표기하여 엽니다.
 * geo:0,0?q=latitude,longitude(label) 표준 사양을 적용하여 핀이 지도 정중앙에 선명하게 꽂힙니다.
 */
fun openGoogleMapsLocation(
    context: Context,
    latitude: Double,
    longitude: Double,
    label: String,
    address: String? = null
) {
    try {
        val pinTitle = buildString {
            append(label)
            if (!address.isNullOrBlank() && address != label) {
                append(" (").append(address).append(")")
            }
        }
        val encodedTitle = Uri.encode(pinTitle)

        // Google Maps 공식 마커 핀 생성 URI 사양:
        // geo:0,0?q=latitude,longitude(label)
        // geo:lat,lng는 마커 없이 카메라만 이동하지만, geo:0,0?q=lat,lng(label)은 해당 좌표에 핀을 꽂고 라벨을 표시합니다.
        val geoUri = Uri.parse("geo:0,0?q=$latitude,$longitude($encodedTitle)")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(mapIntent)
        } catch (e: Exception) {
            // Google Maps 앱이 없거나 실행 실패 시 웹 브라우저 Google 지도 열기
            val webQuery = Uri.encode("$latitude,$longitude ($pinTitle)")
            val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$webQuery")
            val webIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * 하루 동안 이동한 여러 거점들을 Google 지도의 길찾기(Directions)로 열어 전체 이동 경로와 지점들을 표시합니다.
 */
fun openGoogleMapsRoute(context: Context, steps: List<RouteStep>) {
    try {
        val validSteps = steps.filter { it.latitude != null && it.longitude != null && (it.latitude != 0.0 || it.longitude != 0.0) }
        if (validSteps.isEmpty()) return

        if (validSteps.size == 1) {
            val step = validSteps.first()
            openGoogleMapsLocation(
                context = context,
                latitude = step.latitude!!,
                longitude = step.longitude!!,
                label = step.locationName ?: step.title,
                address = step.address
            )
            return
        }

        val origin = "${validSteps.first().latitude},${validSteps.first().longitude}"
        val destination = "${validSteps.last().latitude},${validSteps.last().longitude}"

        val waypoints = if (validSteps.size > 2) {
            // 구글 지도는 최대 8~9개 경유지를 지원하므로 중간 스텝들 중 최대 8개 선별
            val intermediate = validSteps.drop(1).dropLast(1).take(8)
            intermediate.joinToString("|") { "${it.latitude},${it.longitude}" }
        } else null

        val directionsUrl = buildString {
            append("https://www.google.com/maps/dir/?api=1")
            append("&origin=").append(origin)
            append("&destination=").append(destination)
            if (!waypoints.isNullOrBlank()) {
                append("&waypoints=").append(Uri.encode(waypoints))
            }
            append("&travelmode=driving")
            append("&dirflg=d")
        }

        val routeUri = Uri.parse(directionsUrl)
        val mapIntent = Intent(Intent.ACTION_VIEW, routeUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(mapIntent)
        } catch (e: Exception) {
            val webIntent = Intent(Intent.ACTION_VIEW, routeUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(webIntent)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }
}

/**
 * 인앱 WebView에서 구글 지도를 로드하기 위한 iframe/embed 표준 URL을 생성합니다.
 * - 단일 지점 포커스 시: 해당 지점 마커 핀 중심 표시 (zoom 16)
 * - 전체 경로 표시 시: 출발지(saddr), 경유지/도착지(daddr) 차량 경로(dirflg=d) 임베드
 */
fun buildGoogleMapsEmbedUrl(steps: List<RouteStep>, focusStep: RouteStep? = null): String {
    val validSteps = steps.filter {
        it.latitude != null && it.longitude != null &&
        (it.latitude != 0.0 || it.longitude != 0.0)
    }

    if (validSteps.isEmpty()) {
        return "about:blank"
    }

    // 단일 지점 포커스인 경우
    if (focusStep != null && focusStep.latitude != null && focusStep.longitude != null) {
        val lat = focusStep.latitude!!
        val lng = focusStep.longitude!!
        val label = Uri.encode(focusStep.locationName ?: focusStep.title)
        return "https://maps.google.com/maps?q=$lat,$lng($label)&hl=ko&z=16&output=embed"
    }

    // 전체 유효 지점이 1개인 경우
    if (validSteps.size == 1) {
        val single = validSteps.first()
        val lat = single.latitude
        val lng = single.longitude
        val label = Uri.encode(single.locationName ?: single.title)
        return "https://maps.google.com/maps?q=$lat,$lng($label)&hl=ko&z=15&output=embed"
    }

    // 전체 경로(출발지 ~ 경유지 ~ 도착지) 차량 이동 모드 임베드
    val origin = "${validSteps.first().latitude},${validSteps.first().longitude}"
    val remaining = validSteps.drop(1)
    val destAndWaypoints = remaining.joinToString("+to:") { "${it.latitude},${it.longitude}" }

    return "https://maps.google.com/maps?saddr=$origin&daddr=$destAndWaypoints&dirflg=d&hl=ko&output=embed"
}

// 하위 호환성을 위해 유지
fun openGoogleMaps(context: Context, latitude: Double, longitude: Double, label: String) {
    openGoogleMapsLocation(context, latitude, longitude, label)
}

/**
 * 에뮬레이터 및 실기기에서 쿠키 차단/CSP/WebGL 미지원 문제를 원천 해결하는
 * 인라인 HTML5 Leaflet + CartoDB 인터랙티브 지도 생성 함수.
 * - 번호 핀([1], [2], [3]...) 및 파란색 경로선(Polyline) 100% 렌더링
 * - 자동 fitBounds 지원으로 모든 이동 거점이 한 화면에 선명하게 정렬됨
 */
fun buildInteractiveHtmlMap(steps: List<RouteStep>, focusStep: RouteStep? = null): String {
    val validSteps = steps.filter {
        it.latitude != null && it.longitude != null &&
        (it.latitude != 0.0 || it.longitude != 0.0)
    }

    if (validSteps.isEmpty()) {
        return """
            <!DOCTYPE html>
            <html>
            <head><meta charset="utf-8"/><style>body{margin:0;background:#0f172a;color:#94a3b8;display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;font-size:14px;}</style></head>
            <body><div>등록된 위치 정보가 없습니다.</div></body>
            </html>
        """.trimIndent()
    }

    val focusIndex = if (focusStep != null) {
        validSteps.indexOfFirst { it.id == focusStep.id || (it.latitude == focusStep.latitude && it.longitude == focusStep.longitude) }
    } else -1

    val pointsJson = buildString {
        append("[")
        validSteps.forEachIndexed { index, step ->
            if (index > 0) append(",")
            val title = (step.locationName ?: step.title)
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("'", "\\'")
            val timeStr = step.time.format(DateTimeFormatter.ofPattern("M/d a h:mm", Locale.KOREAN))
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            val isFocus = (index == focusIndex)
            append("""{"lat":${step.latitude},"lng":${step.longitude},"title":"$title","time":"$timeStr","num":${index + 1},"isFocus":$isFocus}""")
        }
        append("]")
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" crossorigin="" />
          <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js" crossorigin=""></script>
          <style>
            html, body, #map { width: 100%; height: 100%; margin: 0; padding: 0; background: #0f172a; }
            .leaflet-container { background: #0f172a; font-family: -apple-system, BlinkMacSystemFont, "Segoe UI", Roboto, sans-serif; }
            .custom-pin {
              display: flex;
              align-items: center;
              justify-content: center;
              width: 26px;
              height: 26px;
              border-radius: 50%;
              background: #2563eb;
              color: #ffffff;
              font-weight: 800;
              font-size: 12px;
              box-shadow: 0 3px 8px rgba(0,0,0,0.5);
              border: 2px solid #ffffff;
              transition: transform 0.2s ease;
            }
            .custom-pin.start {
              background: #16a34a;
              border-color: #bbf7d0;
            }
            .custom-pin.end {
              background: #dc2626;
              border-color: #fecaca;
            }
            .custom-pin.focus {
              background: #ea580c;
              border-color: #fef08a;
              box-shadow: 0 0 14px #f97316;
              transform: scale(1.25);
            }
            .leaflet-popup-content-wrapper {
              background: #1e293b;
              color: #f8fafc;
              border-radius: 10px;
              border: 1px solid #334155;
              box-shadow: 0 8px 20px rgba(0,0,0,0.4);
              padding: 4px;
            }
            .leaflet-popup-tip {
              background: #1e293b;
            }
            .popup-card {
              padding: 4px 6px;
            }
            .popup-num {
              display: inline-block;
              background: #2563eb;
              color: #fff;
              font-size: 10px;
              font-weight: bold;
              padding: 1px 6px;
              border-radius: 999px;
              margin-bottom: 4px;
            }
            .popup-title {
              font-weight: 700;
              font-size: 13px;
              color: #60a5fa;
              line-height: 1.3;
            }
            .popup-time {
              font-size: 11px;
              color: #94a3b8;
              margin-top: 3px;
            }
            .leaflet-control-attribution {
              background: rgba(15, 23, 42, 0.75) !important;
              color: #64748b !important;
              font-size: 9px !important;
            }
            .leaflet-control-attribution a {
              color: #94a3b8 !important;
            }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <script>
            try {
              var points = $pointsJson;
              var focusIdx = $focusIndex;

              var initialCenter = points.length > 0 ? [points[0].lat, points[0].lng] : [37.5665, 126.9780];
              var map = L.map('map', {
                center: initialCenter,
                zoom: 13,
                zoomControl: false,
                attributionControl: false
              });

              L.control.zoom({ position: 'bottomleft' }).addTo(map);

              L.tileLayer('https://{s}.basemaps.cartocdn.com/rastertiles/voyager/{z}/{x}/{y}{r}.png', {
                maxZoom: 19,
                subdomains: 'abcd',
                timeout: 8000
              }).addTo(map);

              var latlngs = [];
              var markers = [];

              points.forEach(function(p, i) {
                var pos = [p.lat, p.lng];
                latlngs.push(pos);

                var pinClass = 'custom-pin';
                if (p.isFocus) {
                  pinClass += ' focus';
                } else if (i === 0) {
                  pinClass += ' start';
                } else if (i === points.length - 1) {
                  pinClass += ' end';
                }

                var icon = L.divIcon({
                  className: '',
                  html: '<div class="' + pinClass + '">' + p.num + '</div>',
                  iconSize: [26, 26],
                  iconAnchor: [13, 13]
                });

                var marker = L.marker(pos, { icon: icon }).addTo(map);
                var content = '<div class="popup-card">' +
                              '<span class="popup-num">지점 ' + p.num + '</span>' +
                              '<div class="popup-title">' + p.title + '</div>' +
                              (p.time ? '<div class="popup-time">⏰ ' + p.time + '</div>' : '') +
                              '</div>';
                marker.bindPopup(content);
                markers.push(marker);

                if (p.isFocus) {
                  marker.openPopup();
                }
              });

              if (latlngs.length > 1) {
                L.polyline(latlngs, {
                  color: '#3b82f6',
                  weight: 4,
                  opacity: 0.88,
                  dashArray: '8, 6',
                  lineJoin: 'round'
                }).addTo(map);
              }

              if (focusIdx >= 0 && focusIdx < latlngs.length) {
                map.setView(latlngs[focusIdx], 16);
              } else if (latlngs.length === 1) {
                map.setView(latlngs[0], 15);
              } else if (latlngs.length > 1) {
                map.fitBounds(L.latLngBounds(latlngs), { padding: [35, 35] });
              }
            } catch (e) {
              console.error("Map initialization error:", e);
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
