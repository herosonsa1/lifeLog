package com.autologue.app.presentation.diary.map

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.rememberTransformableState
import androidx.compose.foundation.gestures.transformable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.domain.model.RouteStep
import kotlin.math.max
import kotlin.math.min

/**
 * 중부권 광역 지오레퍼런싱 바운딩 박스 상수 (수도권 + 인천 + 강원 + 충청 전역)
 */
object MidKoreaBounds {
    const val MIN_LAT = 36.00 // 충청 남단: 대전·논산·영동·금산
    const val MAX_LAT = 38.45 // 강원 북단: 철원·화천·양구·고성
    const val MIN_LNG = 126.00 // 인천/서해안: 영종도·강화·태안
    const val MAX_LNG = 129.40 // 강원 동해안: 삼척·동해·강릉·속초

    val LAT_SPAN = MAX_LAT - MIN_LAT
    val LNG_SPAN = MAX_LNG - MIN_LNG

    /**
     * GPS 위경도를 0.0 ~ 1.0 비율의 정규화 좌표(UV)로 변환
     */
    fun toNormalized(lat: Double, lng: Double): Offset {
        val clampedLat = lat.coerceIn(MIN_LAT, MAX_LAT)
        val clampedLng = lng.coerceIn(MIN_LNG, MAX_LNG)
        val nx = ((clampedLng - MIN_LNG) / LNG_SPAN).toFloat()
        val ny = (1.0 - (clampedLat - MIN_LAT) / LAT_SPAN).toFloat()
        return Offset(nx, ny)
    }

    /**
     * 중부권 주요 거점 앵커 정의 (도시명, 위도, 경도, 지역 구분)
     */
    data class LandmarkAnchor(
        val name: String,
        val lat: Double,
        val lng: Double,
        val regionType: String // "METRO", "INCHEON", "GANGWON", "CHUNGCHEONG"
    )

    val ANCHORS = listOf(
        // 수도권 (서울 / 경기)
        LandmarkAnchor("서울", 37.5665, 126.9780, "METRO"),
        LandmarkAnchor("판교·분당", 37.3950, 127.1120, "METRO"),
        LandmarkAnchor("수원", 37.2636, 127.0286, "METRO"),
        LandmarkAnchor("용인", 37.2410, 127.1775, "METRO"),
        LandmarkAnchor("고양", 37.6584, 126.8320, "METRO"),
        LandmarkAnchor("하남·위례", 37.4900, 127.1700, "METRO"),
        LandmarkAnchor("평택", 36.9921, 127.1129, "METRO"),
        LandmarkAnchor("이천", 37.2723, 127.4410, "METRO"),

        // 인천 & 서해
        LandmarkAnchor("인천", 37.4563, 126.7052, "INCHEON"),
        LandmarkAnchor("송도", 37.3850, 126.6500, "INCHEON"),
        LandmarkAnchor("영종도", 37.4600, 126.4400, "INCHEON"),
        LandmarkAnchor("강화", 37.7460, 126.4880, "INCHEON"),

        // 강원권
        LandmarkAnchor("춘천", 37.8813, 127.7298, "GANGWON"),
        LandmarkAnchor("원주", 37.3422, 127.9202, "GANGWON"),
        LandmarkAnchor("강릉", 37.7519, 128.8761, "GANGWON"),
        LandmarkAnchor("속초", 38.2070, 128.5918, "GANGWON"),
        LandmarkAnchor("평창", 37.3705, 128.3902, "GANGWON"),
        LandmarkAnchor("정선", 37.3806, 128.6608, "GANGWON"),
        LandmarkAnchor("홍천", 37.6970, 127.8886, "GANGWON"),
        LandmarkAnchor("삼척", 37.4499, 129.1650, "GANGWON"),
        LandmarkAnchor("철원", 38.1468, 127.3134, "GANGWON"),

        // 충청권
        LandmarkAnchor("천안", 36.8151, 127.1139, "CHUNGCHEONG"),
        LandmarkAnchor("청주", 36.6424, 127.4890, "CHUNGCHEONG"),
        LandmarkAnchor("충주", 36.9910, 127.9260, "CHUNGCHEONG"),
        LandmarkAnchor("제천", 37.1326, 128.2141, "CHUNGCHEONG"),
        LandmarkAnchor("세종", 36.4800, 127.2890, "CHUNGCHEONG"),
        LandmarkAnchor("대전", 36.3504, 127.3845, "CHUNGCHEONG"),
        LandmarkAnchor("서산·당진", 36.8300, 126.5500, "CHUNGCHEONG"),
        LandmarkAnchor("태안", 36.7450, 126.2970, "CHUNGCHEONG")
    )
}

/**
 * 중부권 광역(수도권·인천·강원·충청) 고화질 정적 지오레퍼런스 맵 뷰
 * - 외부 네트워크 통신 및 API Key 발급 0원
 * - 0.001초 즉시 렌더링, 오프라인 100% 지원
 * - 핀치 줌(1.0x~5.0x) 및 드래그 팬, 더블탭 확대 지원
 */
@Composable
fun MidKoreaStaticMapView(
    steps: List<RouteStep>,
    focusedStep: RouteStep? = null,
    onStepClick: (RouteStep) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var selectedStep by remember(focusedStep) { mutableStateOf(focusedStep) }

    // 유효한 위경도 좌표 거점 추출
    val validSteps = remember(steps) {
        steps.filter { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 }
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        scale = (scale * zoomChange).coerceIn(1f, 5f)
        val maxOffset = 500f * (scale - 1f)
        offset = Offset(
            x = (offset.x + panChange.x).coerceIn(-maxOffset, maxOffset),
            y = (offset.y + panChange.y).coerceIn(-maxOffset, maxOffset)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF0F172A)) // 딥 슬레이트 다크 네이비 테마
            .border(1.dp, Color(0xFF334155), RoundedCornerShape(16.dp))
            .clipToBounds()
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformableState)
                .pointerInput(validSteps, scale, offset) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            // 핀 터치 감지 (터치 영역 30dp 역산)
                            val w = size.width
                            val h = size.height
                            val pad = 24.dp.toPx()

                            var hitStep: RouteStep? = null
                            var minHitDist = 36.dp.toPx()

                            validSteps.forEach { step ->
                                val norm = MidKoreaBounds.toNormalized(step.latitude!!, step.longitude!!)
                                val basePt = Offset(
                                    x = pad + norm.x * (w - pad * 2),
                                    y = pad + norm.y * (h - pad * 2)
                                )
                                val transformedPt = Offset(
                                    x = (basePt.x - w / 2f) * scale + w / 2f + offset.x,
                                    y = (basePt.y - h / 2f) * scale + h / 2f + offset.y
                                )
                                val dist = (transformedPt - tapOffset).getDistance()
                                if (dist < minHitDist) {
                                    minHitDist = dist
                                    hitStep = step
                                }
                            }

                            val foundStep = hitStep
                            if (foundStep != null) {
                                selectedStep = foundStep
                                onStepClick(foundStep)
                            } else {
                                selectedStep = null
                            }
                        },
                        onDoubleTap = {
                            scale = if (scale > 1.5f) 1f else 2.5f
                            offset = Offset.Zero
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            val pad = 24.dp.toPx()

            translate(offset.x, offset.y) {
                scale(scale, Offset(w / 2f, h / 2f)) {
                    // 1. 중부권 베이스 지형 및 해안선/수계 배경 렌더링
                    drawMidKoreaBaseMap(w, h, pad, textMeasurer)

                    // 2. 주행 경로선 (Polyline) 렌더링
                    if (validSteps.size > 1) {
                        val path = Path()
                        val screenPoints = validSteps.map { step ->
                            val norm = MidKoreaBounds.toNormalized(step.latitude!!, step.longitude!!)
                            Offset(
                                x = pad + norm.x * (w - pad * 2),
                                y = pad + norm.y * (h - pad * 2)
                            )
                        }

                        path.moveTo(screenPoints.first().x, screenPoints.first().y)
                        for (i in 1 until screenPoints.size) {
                            path.lineTo(screenPoints[i].x, screenPoints[i].y)
                        }

                        // 외곽 발광 네온 블루
                        drawPath(
                            path = path,
                            color = Color(0xFF3B82F6).copy(alpha = 0.35f),
                            style = Stroke(
                                width = 7.dp.toPx() / scale.coerceAtLeast(1f),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round
                            )
                        )

                        // 중앙 선명한 도로 경로선 (대시 스타일)
                        drawPath(
                            path = path,
                            color = Color(0xFF60A5FA),
                            style = Stroke(
                                width = 3.dp.toPx() / scale.coerceAtLeast(1f),
                                cap = StrokeCap.Round,
                                join = StrokeJoin.Round,
                                pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 8f), 0f)
                            )
                        )
                    }

                    // 3. 거점 번호 마커 핀 오버레이
                    validSteps.forEachIndexed { index, step ->
                        val norm = MidKoreaBounds.toNormalized(step.latitude!!, step.longitude!!)
                        val pt = Offset(
                            x = pad + norm.x * (w - pad * 2),
                            y = pad + norm.y * (h - pad * 2)
                        )

                        val isFocus = step.id == selectedStep?.id || step.id == focusedStep?.id
                        val isStart = index == 0
                        val isEnd = index == validSteps.lastIndex

                        val pinColor = when {
                            isFocus -> Color(0xFFEA580C) // 오렌지 포커스
                            isStart -> Color(0xFF16A34A) // 녹색 출발
                            isEnd -> Color(0xFFDC2626)   // 빨강 도착
                            else -> Color(0xFF2563EB)    // 파랑 경유
                        }

                        val radius = if (isFocus) 14.dp.toPx() / scale.coerceAtLeast(1f) else 11.dp.toPx() / scale.coerceAtLeast(1f)

                        // 외곽 흰색 테두리
                        drawCircle(
                            color = Color.White,
                            radius = radius + 2.dp.toPx() / scale.coerceAtLeast(1f),
                            center = pt
                        )
                        // 채움 원
                        drawCircle(
                            color = pinColor,
                            radius = radius,
                            center = pt
                        )

                        // 번호 텍스트
                        val numStr = (index + 1).toString()
                        val numLayout = textMeasurer.measure(
                            text = AnnotatedString(numStr),
                            style = TextStyle(
                                color = Color.White,
                                fontSize = (10 / scale.coerceAtLeast(1f)).sp,
                                fontWeight = FontWeight.Bold,
                                textAlign = TextAlign.Center
                            )
                        )
                        drawText(
                            textLayoutResult = numLayout,
                            topLeft = Offset(
                                pt.x - numLayout.size.width / 2f,
                                pt.y - numLayout.size.height / 2f
                            )
                        )

                        // 장소명 캡슐 라벨 (미선택 시 축약 표기)
                        if (!isFocus) {
                            val placeLabel = (step.locationName ?: step.title).take(6)
                            val labelLayout = textMeasurer.measure(
                                text = AnnotatedString(placeLabel),
                                style = TextStyle(
                                    color = Color(0xFFE2E8F0),
                                    fontSize = (8 / scale.coerceAtLeast(1f)).sp,
                                    fontWeight = FontWeight.Medium
                                )
                            )
                            val labelW = labelLayout.size.width + 10f
                            val labelH = labelLayout.size.height + 4f
                            val labelTopLeft = Offset(pt.x - labelW / 2f, pt.y + radius + 3f)

                            drawRoundRect(
                                color = Color(0xFF1E293B).copy(alpha = 0.9f),
                                topLeft = labelTopLeft,
                                size = androidx.compose.ui.geometry.Size(labelW, labelH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f)
                            )
                            drawRoundRect(
                                color = Color(0xFF475569),
                                topLeft = labelTopLeft,
                                size = androidx.compose.ui.geometry.Size(labelW, labelH),
                                cornerRadius = androidx.compose.ui.geometry.CornerRadius(4f, 4f),
                                style = Stroke(width = 1f)
                            )
                            drawText(
                                textLayoutResult = labelLayout,
                                topLeft = Offset(labelTopLeft.x + 5f, labelTopLeft.y + 2f)
                            )
                        }
                    }
                }
            }
        }

        // 좌측 상단: 권역 안내 뱃지
        Surface(
            color = Color(0xFF1E293B).copy(alpha = 0.92f),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF334155)),
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
            ) {
                Text(
                    text = "🗺️ 중부권 광역 맵",
                    color = Color(0xFF93C5FD),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = "수도권 · 인천 · 강원 · 충청",
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp
                )
            }
        }

        // 우측 하단: 줌 컨트롤러 (+ / - / 리셋)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(12.dp)
        ) {
            FloatingActionButton(
                onClick = { scale = (scale * 1.3f).coerceAtMost(5f) },
                containerColor = Color(0xFF1E293B),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "확대", modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            FloatingActionButton(
                onClick = { scale = (scale / 1.3f).coerceAtLeast(1f) },
                containerColor = Color(0xFF1E293B),
                contentColor = Color.White,
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "축소", modifier = Modifier.size(18.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            FloatingActionButton(
                onClick = {
                    scale = 1f
                    offset = Offset.Zero
                    selectedStep = null
                },
                containerColor = Color(0xFF1E293B),
                contentColor = Color(0xFF60A5FA),
                shape = CircleShape,
                modifier = Modifier.size(36.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "초기화", modifier = Modifier.size(18.dp))
            }
        }

        // 선택된 거점 팝업 카드 (하단 오버레이)
        AnimatedVisibility(
            visible = selectedStep != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(12.dp)
                .fillMaxWidth(0.72f)
        ) {
            selectedStep?.let { step ->
                Surface(
                    color = Color(0xFF0F172A).copy(alpha = 0.95f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6)),
                    shadowElevation = 8.dp
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                tint = Color(0xFFF97316),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = step.locationName ?: step.title,
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        if (!step.address.isNullOrBlank()) {
                            Text(
                                text = step.address,
                                color = Color(0xFF94A3B8),
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Row(
                            modifier = Modifier.padding(top = 6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "⏰ %02d:%02d".format(step.time.hour, step.time.minute),
                                color = Color(0xFF60A5FA),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "(%.4f, %.4f)".format(step.latitude, step.longitude),
                                color = Color(0xFF64748B),
                                fontSize = 10.sp
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * 중부권 광역 베이스 지형/수계/고속도로 및 랜드마크 앵커를 Canvas에 고화질 벡터로 드로잉
 */
private fun DrawScope.drawMidKoreaBaseMap(
    w: Float,
    h: Float,
    pad: Float,
    textMeasurer: TextMeasurer
) {
    // 1. 서해(황해) 및 동해 바다 영역 표현
    val westSeaWidth = pad + 0.16f * (w - pad * 2)
    drawRect(
        color = Color(0xFF0A192F), // 짙은 바다 감청색
        topLeft = Offset.Zero,
        size = androidx.compose.ui.geometry.Size(westSeaWidth, h)
    )
    val eastSeaStart = pad + 0.88f * (w - pad * 2)
    drawRect(
        color = Color(0xFF0A192F),
        topLeft = Offset(eastSeaStart, 0f),
        size = androidx.compose.ui.geometry.Size(w - eastSeaStart, h)
    )

    // 해안선 경계선 (서해안 / 동해안)
    drawLine(
        color = Color(0xFF1E293B),
        start = Offset(westSeaWidth, 0f),
        end = Offset(westSeaWidth, h),
        strokeWidth = 2f
    )
    drawLine(
        color = Color(0xFF1E293B),
        start = Offset(eastSeaStart, 0f),
        end = Offset(eastSeaStart, h),
        strokeWidth = 2f
    )

    // 2. 주요 수계 (한강 본류 & 북한강·남한강)
    val hanRiverPath = Path()
    val hanStart = MidKoreaBounds.toNormalized(37.45, 126.70) // 인천/김포 한강하구
    val seoulPt = MidKoreaBounds.toNormalized(37.54, 127.00)  // 서울 한강
    val yangpyeongPt = MidKoreaBounds.toNormalized(37.50, 127.45) // 양평 두물머리
    val chuncheonPt = MidKoreaBounds.toNormalized(37.88, 127.73)  // 북한강 춘천
    val chungjuPt = MidKoreaBounds.toNormalized(36.99, 127.93)    // 남한강 충주호

    fun toScreen(n: Offset) = Offset(pad + n.x * (w - pad * 2), pad + n.y * (h - pad * 2))

    val p1 = toScreen(hanStart)
    val p2 = toScreen(seoulPt)
    val p3 = toScreen(yangpyeongPt)
    val p4 = toScreen(chuncheonPt)
    val p5 = toScreen(chungjuPt)

    hanRiverPath.moveTo(p1.x, p1.y)
    hanRiverPath.quadraticBezierTo(p2.x, p2.y, p3.x, p3.y)

    // 한강 본류
    drawPath(
        path = hanRiverPath,
        color = Color(0xFF1E3A8A).copy(alpha = 0.6f),
        style = Stroke(width = 4f, cap = StrokeCap.Round)
    )
    // 북한강 지류
    drawLine(
        color = Color(0xFF1E3A8A).copy(alpha = 0.45f),
        start = p3,
        end = p4,
        strokeWidth = 2.5f
    )
    // 남한강 지류
    drawLine(
        color = Color(0xFF1E3A8A).copy(alpha = 0.45f),
        start = p3,
        end = p5,
        strokeWidth = 2.5f
    )

    // 3. 주요 광역 고속도로 간선망 가이드라인
    val seoul = toScreen(MidKoreaBounds.toNormalized(37.5665, 126.9780))
    val pangyo = toScreen(MidKoreaBounds.toNormalized(37.3950, 127.1120))
    val incheon = toScreen(MidKoreaBounds.toNormalized(37.4563, 126.7052))
    val suwon = toScreen(MidKoreaBounds.toNormalized(37.2636, 127.0286))
    val cheonan = toScreen(MidKoreaBounds.toNormalized(36.8151, 127.1139))
    val daejeon = toScreen(MidKoreaBounds.toNormalized(36.3504, 127.3845))
    val wonju = toScreen(MidKoreaBounds.toNormalized(37.3422, 127.9202))
    val gangneung = toScreen(MidKoreaBounds.toNormalized(37.7519, 128.8761))
    val sokcho = toScreen(MidKoreaBounds.toNormalized(38.2070, 128.5918))
    val seosan = toScreen(MidKoreaBounds.toNormalized(36.8300, 126.5500))

    val highwayColor = Color(0xFF334155).copy(alpha = 0.5f)
    val highwayStroke = Stroke(width = 1.8f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(6f, 6f), 0f))

    // 경부선 (서울 ➔ 판교 ➔ 수원 ➔ 천안 ➔ 대전)
    val gyeongbuPath = Path().apply {
        moveTo(seoul.x, seoul.y)
        lineTo(pangyo.x, pangyo.y)
        lineTo(suwon.x, suwon.y)
        lineTo(cheonan.x, cheonan.y)
        lineTo(daejeon.x, daejeon.y)
    }
    drawPath(gyeongbuPath, highwayColor, style = highwayStroke)

    // 영동선 (인천 ➔ 판교 ➔ 원주 ➔ 강릉)
    val yeongdongPath = Path().apply {
        moveTo(incheon.x, incheon.y)
        lineTo(pangyo.x, pangyo.y)
        lineTo(wonju.x, wonju.y)
        lineTo(gangneung.x, gangneung.y)
    }
    drawPath(yeongdongPath, highwayColor, style = highwayStroke)

    // 서울양양/동해선 (서울 ➔ 춘천 ➔ 속초 ➔ 강릉)
    val eastPath = Path().apply {
        moveTo(seoul.x, seoul.y)
        lineTo(p4.x, p4.y)
        lineTo(sokcho.x, sokcho.y)
        lineTo(gangneung.x, gangneung.y)
    }
    drawPath(eastPath, highwayColor, style = highwayStroke)

    // 서해안선 (인천 ➔ 수원서쪽 ➔ 서산/당진)
    val westCoastPath = Path().apply {
        moveTo(incheon.x, incheon.y)
        lineTo(seosan.x, seosan.y)
    }
    drawPath(westCoastPath, highwayColor, style = highwayStroke)

    // 4. 주요 권역별 랜드마크 앵커 점 및 라벨 출력
    MidKoreaBounds.ANCHORS.forEach { anchor ->
        val norm = MidKoreaBounds.toNormalized(anchor.lat, anchor.lng)
        val pt = toScreen(norm)

        val isMajor = anchor.name in listOf("서울", "인천", "판교·분당", "강릉", "대전", "천안", "춘천", "원주")
        val dotRadius = if (isMajor) 3.5f else 2.5f
        val dotColor = when (anchor.regionType) {
            "METRO" -> Color(0xFF93C5FD)
            "INCHEON" -> Color(0xFF67E8F9)
            "GANGWON" -> Color(0xFF86EFAC)
            "CHUNGCHEONG" -> Color(0xFFFDE047)
            else -> Color(0xFF94A3B8)
        }

        drawCircle(color = dotColor.copy(alpha = 0.8f), radius = dotRadius, center = pt)

        val labelStyle = TextStyle(
            color = if (isMajor) Color(0xFFE2E8F0) else Color(0xFF94A3B8),
            fontSize = if (isMajor) 9.sp else 8.sp,
            fontWeight = if (isMajor) FontWeight.Bold else FontWeight.Normal
        )
        val layout = textMeasurer.measure(AnnotatedString(anchor.name), style = labelStyle)
        drawText(
            textLayoutResult = layout,
            topLeft = Offset(pt.x + 5f, pt.y - layout.size.height / 2f)
        )
    }
}
