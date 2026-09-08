package com.autologue.app.presentation.diary.map

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
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

    val currentIndex = remember(selectedStep, validCoordinateSteps) {
        if (selectedStep == null) 0
        else {
            val idx = validCoordinateSteps.indexOfFirst { it.id == selectedStep.id }
            if (idx >= 0) idx else 0
        }
    }
    val currentStep = validCoordinateSteps.getOrNull(currentIndex) ?: nonTransactionSteps.firstOrNull()

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
                        border = androidx.compose.foundation.BorderStroke(
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

        HairlineDivider()

        // 2. Interactive Route Map Area (Jetpack Compose Canvas)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .clipToBounds()
                .background(Color(0xFF0F172A))
        ) {
            if (validCoordinateSteps.isNotEmpty()) {
                InteractiveRouteMapView(
                    steps = validCoordinateSteps,
                    selectedIndex = currentIndex,
                    onStepSelected = { idx ->
                        if (idx in validCoordinateSteps.indices) {
                            onStepSelected(validCoordinateSteps[idx])
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
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

            // Top-left Route summary badge overlay
            if (validCoordinateSteps.isNotEmpty()) {
                Surface(
                    modifier = Modifier
                        .padding(Spacing.sm)
                        .align(Alignment.TopStart),
                    shape = AppShapes.pill,
                    color = Color(0xFF0F172A).copy(alpha = 0.9f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
                    shadowElevation = 4.dp
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(horizontal = Spacing.sm, vertical = 5.dp)
                    ) {
                        Text(
                            text = "📍 이동 동선 ${validCoordinateSteps.size}개 지점",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF60A5FA)
                        )
                    }
                }
            }
        }

        HairlineDivider()

        // 3. Selected Stop Bottom Card (Interactive Stop Details)
        if (currentStep != null) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(AppColors.surface)
                    .padding(horizontal = Spacing.lg, vertical = Spacing.md)
            ) {
                // Stop Stepper & Time Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        // Stop Number Badge
                        Box(
                            modifier = Modifier
                                .size(22.dp)
                                .clip(CircleShape)
                                .background(AppColors.primary),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "${currentIndex + 1}",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = PureWhite
                            )
                        }

                        Text(
                            text = currentStep.time.format(DateTimeFormatter.ofPattern("M월 d일 (E) a h:mm", Locale.KOREA)),
                            style = AppTypography.body.copy(fontWeight = FontWeight.Bold)
                        )

                        // Step Type Pill
                        val (typeLabel, typeBg, typeFg) = when (currentStep.stepType) {
                            RouteStepType.TRANSACTION -> Triple("💳 결제 / 방문", Emerald50, Emerald700)
                            RouteStepType.PHOTO -> Triple("📸 사진 기록", Indigo50, Indigo700)
                            RouteStepType.GOLF -> Triple("⛳ 골프 라운드", Forest50, Forest700)
                            RouteStepType.DRIVING -> Triple("🚗 차량 주행", Amber50, Amber700)
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

                    // Step Index Indicator (e.g. 1/3)
                    Text(
                        text = "${currentIndex + 1} / ${validCoordinateSteps.size}",
                        style = AppTypography.captionMuted
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))

                // Location Title
                Text(
                    text = currentStep.locationName ?: currentStep.title,
                    style = AppTypography.h3.copy(fontWeight = FontWeight.Bold, color = AppColors.textPrimary)
                )

                // Subtitle / Address / Coordinates
                val addressText = currentStep.address ?: ""
                if (addressText.isNotEmpty()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "📍 $addressText",
                            style = AppTypography.bodySecondary.copy(color = AppColors.secondary)
                        )
                    }
                }

                // Companions Tag Row (동행인 표시)
                if (currentStep.companions.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = "동행인:",
                            style = AppTypography.captionMuted,
                            fontWeight = FontWeight.Medium
                        )
                        currentStep.companions.forEach { companion ->
                            Surface(
                                shape = AppShapes.pill,
                                color = Indigo50,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, Indigo600.copy(alpha = 0.3f))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = "👤 $companion",
                                        style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold, color = Indigo700)
                                    )
                                    if (onRemoveCompanion != null) {
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Icon(
                                            imageVector = Icons.Default.Close,
                                            contentDescription = "동행인 삭제",
                                            tint = Indigo600,
                                            modifier = Modifier
                                                .size(11.dp)
                                                .clickable { onRemoveCompanion(currentStep.id, companion) }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // Photo Carousel (if photos exist)
                if (currentStep.photoUris.isNotEmpty()) {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(currentStep.photoUris) { photoUrl ->
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

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Action Buttons Row: Google Maps App Navigation & Add Companion
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // Google Maps Location Pin Button
                    if (currentStep.latitude != null && currentStep.longitude != null) {
                        OutlinedButton(
                            onClick = {
                                openGoogleMapsLocation(
                                    context = context,
                                    latitude = currentStep.latitude,
                                    longitude = currentStep.longitude,
                                    label = currentStep.locationName ?: currentStep.title,
                                    address = currentStep.address
                                )
                            },
                            shape = AppShapes.button,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, AppColors.border),
                            modifier = Modifier.weight(1f).height(34.dp),
                            contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(13.dp), tint = AppColors.primary)
                            Spacer(modifier = Modifier.width(3.dp))
                            Text("지점 핀 보기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.primary)
                        }

                        // 전체 이동 동선(2개 이상 거점)이 있는 경우 길찾기 경로 버튼 추가
                        if (validCoordinateSteps.size > 1) {
                            OutlinedButton(
                                onClick = {
                                    openGoogleMapsRoute(context, validCoordinateSteps)
                                },
                                shape = AppShapes.button,
                                border = androidx.compose.foundation.BorderStroke(0.5.dp, AppColors.border),
                                modifier = Modifier.weight(1f).height(34.dp),
                                contentPadding = PaddingValues(horizontal = Spacing.xs, vertical = 0.dp)
                            ) {
                                Icon(Icons.Default.Navigation, contentDescription = null, modifier = Modifier.size(13.dp), tint = AppColors.primary)
                                Spacer(modifier = Modifier.width(3.dp))
                                Text("전체 경로 보기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.primary)
                            }
                        }
                    }

                    // Add Companion Button
                    if (onAddCompanionClick != null) {
                        OutlinedButton(
                            onClick = { onAddCompanionClick(currentStep) },
                            shape = AppShapes.button,
                            border = androidx.compose.foundation.BorderStroke(0.5.dp, AppColors.border),
                            modifier = Modifier.height(34.dp),
                            contentPadding = PaddingValues(horizontal = Spacing.sm, vertical = 0.dp)
                        ) {
                            Icon(Icons.Default.Person, contentDescription = null, modifier = Modifier.size(13.dp), tint = AppColors.primary)
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("동행인 추가", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.primary)
                        }
                    }
                }

                // Step Navigation Controls (이전 / 다음 거점)
                if (validCoordinateSteps.size > 1) {
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = {
                                val prevIdx = if (currentIndex > 0) currentIndex - 1 else validCoordinateSteps.size - 1
                                onStepSelected(validCoordinateSteps[prevIdx])
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("← 이전 장소", fontSize = 11.sp, color = AppColors.primary)
                        }

                        TextButton(
                            onClick = {
                                val nextIdx = if (currentIndex < validCoordinateSteps.size - 1) currentIndex + 1 else 0
                                onStepSelected(validCoordinateSteps[nextIdx])
                            },
                            contentPadding = PaddingValues(0.dp)
                        ) {
                            Text("다음 장소 →", fontSize = 11.sp, color = AppColors.primary)
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

// 하위 호환성을 위해 유지
fun openGoogleMaps(context: Context, latitude: Double, longitude: Double, label: String) {
    openGoogleMapsLocation(context, latitude, longitude, label)
}
