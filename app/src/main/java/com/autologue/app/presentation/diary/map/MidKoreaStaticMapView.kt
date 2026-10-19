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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min

/**
 * 한국 행정구역(시·도 / 시·군·구 / 주요 동·신도시) 및 도로·수계 고정밀 지오레퍼런스 데이터
 * - Google Maps 스타일의 밝고 선명한 행정구역 구분선, 한강/탄천/중랑천 수계, 고속도로망 제공
 * - 사용자의 이동 경로(steps) 범위에 맞춰 자동으로 확대(Auto-Fit Viewport)되어 시작하며,
 *   핀치 줌 시에도 글자 크기나 선 굵기가 비정상적으로 커지지 않고 선명하게 유지됩니다.
 */
object MidKoreaBounds {
    const val MIN_LAT = 36.00
    const val MAX_LAT = 38.45
    const val MIN_LNG = 126.00
    const val MAX_LNG = 129.40

    val LAT_SPAN = MAX_LAT - MIN_LAT
    val LNG_SPAN = MAX_LNG - MIN_LNG

    fun toNormalized(lat: Double, lng: Double): Offset {
        val clampedLat = lat.coerceIn(MIN_LAT, MAX_LAT)
        val clampedLng = lng.coerceIn(MIN_LNG, MAX_LNG)
        val nx = ((clampedLng - MIN_LNG) / LNG_SPAN).toFloat()
        val ny = (1.0 - (clampedLat - MIN_LAT) / LAT_SPAN).toFloat()
        return Offset(nx, ny)
    }

    data class LandmarkAnchor(
        val name: String,
        val lat: Double,
        val lng: Double,
        val tier: Int, // 1: 광역/특별시·도, 2: 시·군·구, 3: 주요 동·지구(위례·학암·판교·여의도 등)
        val parentRegion: String = "METRO"
    )

    data class DistrictBoundary(
        val name: String,
        val regionGroup: String, // "SEOUL", "GYEONGGI_EAST", "GYEONGGI_SOUTH", "GYEONGGI_WEST", "INCHEON", "OTHER"
        val polygon: List<Pair<Double, Double>>
    )

    // 서울·수도권 핵심 시·군·구 행정구역 경계 폴리곤 (실제 지도처럼 지역 구분선 표시용)
    val DISTRICT_BOUNDARIES = listOf(
        // 서울 강남·서초·송파·강동
        DistrictBoundary(
            "송파구", "SEOUL",
            listOf(37.538 to 127.080, 37.542 to 127.125, 37.495 to 127.160, 37.472 to 127.145, 37.474 to 127.110, 37.505 to 127.075)
        ),
        DistrictBoundary(
            "강동구", "SEOUL",
            listOf(37.542 to 127.125, 37.575 to 127.175, 37.540 to 127.182, 37.515 to 127.160, 37.495 to 127.160)
        ),
        DistrictBoundary(
            "강남구", "SEOUL",
            listOf(37.532 to 127.015, 37.538 to 127.080, 37.505 to 127.075, 37.474 to 127.110, 37.460 to 127.095, 37.472 to 127.040, 37.505 to 127.015)
        ),
        DistrictBoundary(
            "서초구", "SEOUL",
            listOf(37.515 to 126.975, 37.532 to 127.015, 37.505 to 127.015, 37.472 to 127.040, 37.435 to 127.065, 37.445 to 126.985)
        ),
        // 서울 영등포·동작·관악·구로·양천·강서·마포·용산·성동·광진·종로·중구
        DistrictBoundary(
            "영등포구", "SEOUL",
            listOf(37.550 to 126.885, 37.535 to 126.945, 37.492 to 126.925, 37.495 to 126.885)
        ),
        DistrictBoundary(
            "동작구", "SEOUL",
            listOf(37.518 to 126.935, 37.515 to 126.982, 37.475 to 126.985, 37.485 to 126.925)
        ),
        DistrictBoundary(
            "관악구", "SEOUL",
            listOf(37.485 to 126.905, 37.475 to 126.985, 37.445 to 126.985, 37.445 to 126.915)
        ),
        DistrictBoundary(
            "마포구", "SEOUL",
            listOf(37.580 to 126.875, 37.565 to 126.960, 37.538 to 126.945, 37.555 to 126.875)
        ),
        DistrictBoundary(
            "용산구", "SEOUL",
            listOf(37.555 to 126.955, 37.550 to 127.018, 37.515 to 127.012, 37.518 to 126.950)
        ),
        DistrictBoundary(
            "성동·광진구", "SEOUL",
            listOf(37.570 to 127.018, 37.565 to 127.110, 37.530 to 127.110, 37.535 to 127.018)
        ),
        DistrictBoundary(
            "강서·양천구", "SEOUL",
            listOf(37.585 to 126.795, 37.555 to 126.885, 37.505 to 126.885, 37.515 to 126.815)
        ),
        DistrictBoundary(
            "구로·금천구", "SEOUL",
            listOf(37.505 to 126.815, 37.505 to 126.905, 37.440 to 126.915, 37.450 to 126.825)
        ),
        DistrictBoundary(
            "종로·중구", "SEOUL",
            listOf(37.605 to 126.955, 37.595 to 127.022, 37.552 to 127.018, 37.555 to 126.955)
        ),
        DistrictBoundary(
            "성북·동대문·중랑", "SEOUL",
            listOf(37.625 to 126.990, 37.620 to 127.110, 37.565 to 127.110, 37.570 to 127.018)
        ),
        DistrictBoundary(
            "노원·도봉·강북", "SEOUL",
            listOf(37.695 to 127.010, 37.685 to 127.095, 37.620 to 127.105, 37.625 to 126.990)
        ),
        DistrictBoundary(
            "은평·서대문구", "SEOUL",
            listOf(37.655 to 126.900, 37.625 to 126.975, 37.565 to 126.960, 37.580 to 126.890)
        ),

        // 경기 동부·남부 핵심 (하남시, 성남 수정/중원/분당, 과천, 구리, 남양주, 광주, 수원, 용인)
        DistrictBoundary(
            "하남시", "GYEONGGI_EAST",
            listOf(37.575 to 127.175, 37.565 to 127.255, 37.475 to 127.245, 37.468 to 127.142, 37.495 to 127.160, 37.540 to 127.182)
        ),
        DistrictBoundary(
            "성남 수정·중원구", "GYEONGGI_SOUTH",
            listOf(37.474 to 127.110, 37.472 to 127.145, 37.468 to 127.175, 37.415 to 127.175, 37.415 to 127.090, 37.460 to 127.095)
        ),
        DistrictBoundary(
            "성남 분당구", "GYEONGGI_SOUTH",
            listOf(37.415 to 127.065, 37.415 to 127.175, 37.335 to 127.165, 37.335 to 127.065)
        ),
        DistrictBoundary(
            "과천시", "GYEONGGI_SOUTH",
            listOf(37.460 to 126.975, 37.445 to 127.040, 37.405 to 127.035, 37.410 to 126.975)
        ),
        DistrictBoundary(
            "안양·의왕·군포", "GYEONGGI_SOUTH",
            listOf(37.435 to 126.895, 37.410 to 126.975, 37.405 to 127.035, 37.315 to 127.015, 37.325 to 126.905)
        ),
        DistrictBoundary(
            "광명시", "GYEONGGI_WEST",
            listOf(37.495 to 126.835, 37.485 to 126.895, 37.415 to 126.895, 37.415 to 126.835)
        ),
        DistrictBoundary(
            "구리시", "GYEONGGI_EAST",
            listOf(37.620 to 127.110, 37.615 to 127.165, 37.565 to 127.165, 37.565 to 127.110)
        ),
        DistrictBoundary(
            "남양주시", "GYEONGGI_EAST",
            listOf(37.720 to 127.145, 37.700 to 127.350, 37.550 to 127.320, 37.565 to 127.165, 37.615 to 127.165)
        ),
        DistrictBoundary(
            "광주시", "GYEONGGI_EAST",
            listOf(37.475 to 127.175, 37.475 to 127.360, 37.320 to 127.350, 37.335 to 127.165)
        ),
        DistrictBoundary(
            "용인시", "GYEONGGI_SOUTH",
            listOf(37.335 to 127.065, 37.335 to 127.320, 37.120 to 127.320, 37.150 to 127.065)
        ),
        DistrictBoundary(
            "수원시", "GYEONGGI_SOUTH",
            listOf(37.325 to 126.945, 37.325 to 127.065, 37.215 to 127.075, 37.220 to 126.945)
        ),
        DistrictBoundary(
            "고양시", "GYEONGGI_WEST",
            listOf(37.720 to 126.700, 37.700 to 126.930, 37.580 to 126.890, 37.595 to 126.700)
        ),
        DistrictBoundary(
            "김포·부천시", "GYEONGGI_WEST",
            listOf(37.680 to 126.580, 37.595 to 126.795, 37.460 to 126.825, 37.465 to 126.650)
        ),
        DistrictBoundary(
            "인천광역시", "INCHEON",
            listOf(37.580 to 126.380, 37.560 to 126.740, 37.360 to 126.730, 37.350 to 126.380)
        )
    )

    val ANCHORS = listOf(
        // Tier 1: 광역 거점
        LandmarkAnchor("서울특별시", 37.5665, 126.9780, 1, "METRO"),
        LandmarkAnchor("인천광역시", 37.4563, 126.7052, 1, "INCHEON"),
        LandmarkAnchor("수원시", 37.2636, 127.0286, 1, "METRO"),
        LandmarkAnchor("성남시", 37.4200, 127.1265, 1, "METRO"),
        LandmarkAnchor("하남시", 37.5393, 127.2148, 1, "METRO"),
        LandmarkAnchor("고양시", 37.6584, 126.8320, 1, "METRO"),
        LandmarkAnchor("용인시", 37.2410, 127.1775, 1, "METRO"),
        LandmarkAnchor("춘천시", 37.8813, 127.7298, 1, "GANGWON"),
        LandmarkAnchor("원주시", 37.3422, 127.9202, 1, "GANGWON"),
        LandmarkAnchor("강릉시", 37.7519, 128.8761, 1, "GANGWON"),
        LandmarkAnchor("천안시", 36.8151, 127.1139, 1, "CHUNGCHEONG"),
        LandmarkAnchor("대전광역시", 36.3504, 127.3845, 1, "CHUNGCHEONG"),
        LandmarkAnchor("세종특별자치시", 36.4800, 127.2890, 1, "CHUNGCHEONG"),

        // Tier 2: 주요 구·시·군 (확대 시 상세 표출)
        LandmarkAnchor("송파구", 37.5145, 127.1060, 2, "METRO"),
        LandmarkAnchor("강동구", 37.5301, 127.1238, 2, "METRO"),
        LandmarkAnchor("강남구", 37.5172, 127.0473, 2, "METRO"),
        LandmarkAnchor("서초구", 37.4837, 127.0324, 2, "METRO"),
        LandmarkAnchor("영등포구", 37.5264, 126.8962, 2, "METRO"),
        LandmarkAnchor("마포구", 37.5663, 126.9019, 2, "METRO"),
        LandmarkAnchor("용산구", 37.5326, 126.9900, 2, "METRO"),
        LandmarkAnchor("성동구", 37.5633, 127.0371, 2, "METRO"),
        LandmarkAnchor("광진구", 37.5385, 127.0823, 2, "METRO"),
        LandmarkAnchor("동작구", 37.5124, 126.9393, 2, "METRO"),
        LandmarkAnchor("관악구", 37.4784, 126.9516, 2, "METRO"),
        LandmarkAnchor("강서구", 37.5509, 126.8495, 2, "METRO"),
        LandmarkAnchor("양천구", 37.5169, 126.8664, 2, "METRO"),
        LandmarkAnchor("구로구", 37.4954, 126.8874, 2, "METRO"),
        LandmarkAnchor("종로구", 37.5730, 126.9794, 2, "METRO"),
        LandmarkAnchor("중구", 37.5641, 126.9979, 2, "METRO"),
        LandmarkAnchor("분당구", 37.3827, 127.1189, 2, "METRO"),
        LandmarkAnchor("수정구", 37.4504, 127.1456, 2, "METRO"),
        LandmarkAnchor("중원구", 37.4306, 127.1372, 2, "METRO"),
        LandmarkAnchor("과천시", 37.4292, 126.9876, 2, "METRO"),
        LandmarkAnchor("구리시", 37.5943, 127.1296, 2, "METRO"),
        LandmarkAnchor("남양주시", 37.6360, 127.2165, 2, "METRO"),
        LandmarkAnchor("광주시", 37.4095, 127.2550, 2, "METRO"),
        LandmarkAnchor("안양시", 37.3943, 126.9568, 2, "METRO"),
        LandmarkAnchor("광명시", 37.4786, 126.8646, 2, "METRO"),
        LandmarkAnchor("부천시", 37.5034, 126.7660, 2, "METRO"),
        LandmarkAnchor("김포시", 37.6152, 126.7156, 2, "METRO"),

        // Tier 3: 세부 동·생활권 (확대 시 구글지도처럼 상세 동 이름 표출)
        LandmarkAnchor("위례·학암동", 37.4785, 127.1465, 3, "METRO"),
        LandmarkAnchor("감일동", 37.5080, 127.1520, 3, "METRO"),
        LandmarkAnchor("미사강변", 37.5630, 127.1920, 3, "METRO"),
        LandmarkAnchor("문정·장지", 37.4850, 127.1220, 3, "METRO"),
        LandmarkAnchor("잠실", 37.5133, 127.1001, 3, "METRO"),
        LandmarkAnchor("올림픽공원", 37.5209, 127.1230, 3, "METRO"),
        LandmarkAnchor("삼성·코엑스", 37.5115, 127.0595, 3, "METRO"),
        LandmarkAnchor("역삼·테헤란로", 37.5006, 127.0365, 3, "METRO"),
        LandmarkAnchor("양재·개포", 37.4845, 127.0345, 3, "METRO"),
        LandmarkAnchor("반포·고속터미널", 37.5048, 127.0049, 3, "METRO"),
        LandmarkAnchor("여의도", 37.5219, 126.9245, 3, "METRO"),
        LandmarkAnchor("문래·당산", 37.5180, 126.8950, 3, "METRO"),
        LandmarkAnchor("노량진·흑석", 37.5080, 126.9520, 3, "METRO"),
        LandmarkAnchor("판교테크노밸리", 37.4020, 127.1086, 3, "METRO"),
        LandmarkAnchor("정자·수내", 37.3660, 127.1080, 3, "METRO")
    )
}

/**
 * 경로 중심 자동 확대(Auto-Fit) 및 구글지도 스타일 행정구역 경계선·수계·도로망을 제공하는 고정밀 벡터 지도 뷰
 */
@Composable
fun MidKoreaStaticMapView(
    steps: List<RouteStep>,
    focusedStep: RouteStep? = null,
    onStepClick: (RouteStep) -> Unit = {},
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()

    var userZoom by remember { mutableFloatStateOf(1f) }
    var userPan by remember { mutableStateOf(Offset.Zero) }
    var selectedStep by remember(focusedStep) { mutableStateOf(focusedStep) }

    val validSteps = remember(steps) {
        steps.filter { it.latitude != null && it.longitude != null && it.latitude != 0.0 && it.longitude != 0.0 }
    }

    // 경로나 포커스 지점이 바뀔 때 사용자 줌/팬 초기화 (자동 확대 뷰포트가 즉시 적용되도록)
    LaunchedEffect(validSteps, focusedStep) {
        userZoom = 1f
        userPan = Offset.Zero
    }

    // 조회된 경로 거점들을 화면 중앙에 크게 확대해 보여주는 동적 위경도 바운딩 박스 계산
    val viewportBounds = remember(validSteps, focusedStep) {
        if (focusedStep?.latitude != null && focusedStep.longitude != null) {
            val cLat = focusedStep.latitude
            val cLng = focusedStep.longitude
            val halfLat = 0.022 // 동/도로 단위 상세 줌
            val halfLng = 0.028
            ViewportGeoBounds(cLat - halfLat, cLat + halfLat, cLng - halfLng, cLng + halfLng)
        } else if (validSteps.isNotEmpty()) {
            val minLat = validSteps.minOf { it.latitude!! }
            val maxLat = validSteps.maxOf { it.latitude!! }
            val minLng = validSteps.minOf { it.longitude!! }
            val maxLng = validSteps.maxOf { it.longitude!! }

            val cLat = (minLat + maxLat) / 2.0
            val cLng = (minLng + maxLng) / 2.0

            // 최소 스팬 보장 + 여백 35% 부여로 경로가 화면에 꽉 차고 주변 시·군·구 경계가 함께 보이도록 설정
            val latSpan = max(maxLat - minLat, 0.035) * 1.45
            val lngSpan = max(maxLng - minLng, 0.045) * 1.45

            ViewportGeoBounds(
                minLat = cLat - latSpan / 2.0,
                maxLat = cLat + latSpan / 2.0,
                minLng = cLng - lngSpan / 2.0,
                maxLng = cLng + lngSpan / 2.0
            )
        } else {
            // 기본 수도권 중심 뷰
            ViewportGeoBounds(37.35, 37.68, 126.80, 127.25)
        }
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        userZoom = (userZoom * zoomChange).coerceIn(0.6f, 8f)
        val maxPan = 900f * max(userZoom, 1f)
        userPan = Offset(
            x = (userPan.x + panChange.x).coerceIn(-maxPan, maxPan),
            y = (userPan.y + panChange.y).coerceIn(-maxPan, maxPan)
        )
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFEEF2F6)) // 구글 지도 스타일 밝은 베이스 배경
            .clipToBounds()
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformableState)
                .pointerInput(validSteps, viewportBounds, userZoom, userPan) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val pinPositions = computeScreenPinPositions(validSteps, viewportBounds, w, h, userZoom, userPan)

                            var hitStep: RouteStep? = null
                            var minHitDist = 38.dp.toPx()

                            pinPositions.forEach { (step, pt) ->
                                val dist = (pt - tapOffset).getDistance()
                                if (dist < minHitDist) {
                                    minHitDist = dist
                                    hitStep = step
                                }
                            }

                            if (hitStep != null) {
                                selectedStep = hitStep
                                onStepClick(hitStep!!)
                            } else {
                                selectedStep = null
                            }
                        },
                        onDoubleTap = {
                            if (userZoom > 2.2f) {
                                userZoom = 1f
                                userPan = Offset.Zero
                            } else {
                                userZoom = (userZoom * 1.8f).coerceAtMost(6f)
                            }
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height

            // 1. 구글지도 스타일 행정구역 다각형·수계(한강/탄천)·고속도로·지역명 드로잉 (줌 배율 독립형 선명 텍스트)
            drawGoogleStyleKoreaBaseMap(
                w = w,
                h = h,
                bounds = viewportBounds,
                userZoom = userZoom,
                userPan = userPan,
                textMeasurer = textMeasurer
            )

            // 2. 주행 경로선(Polyline) 및 진행 방향 화살표 렌더링
            val pinPositions = computeScreenPinPositions(validSteps, viewportBounds, w, h, userZoom, userPan)
            if (pinPositions.size > 1) {
                val routePath = Path()
                val rawPoints = validSteps.map { step ->
                    viewportBounds.toScreenOffset(step.latitude!!, step.longitude!!, w, h, userZoom, userPan)
                }
                routePath.moveTo(rawPoints.first().x, rawPoints.first().y)
                for (i in 1 until rawPoints.size) {
                    routePath.lineTo(rawPoints[i].x, rawPoints[i].y)
                }

                // 외곽 진파랑 케이싱 (구글맵 내비게이션 경로 스타일)
                drawPath(
                    path = routePath,
                    color = Color(0xFF1D4ED8).copy(alpha = 0.85f),
                    style = Stroke(
                        width = 8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                // 내부 밝은 블루 경로선
                drawPath(
                    path = routePath,
                    color = Color(0xFF3B82F6),
                    style = Stroke(
                        width = 5.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round
                    )
                )
                // 중앙 화이트 대시 가이드라인
                drawPath(
                    path = routePath,
                    color = Color.White.copy(alpha = 0.75f),
                    style = Stroke(
                        width = 1.8.dp.toPx(),
                        cap = StrokeCap.Round,
                        join = StrokeJoin.Round,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                    )
                )
            }

            // 3. 거점 번호 마커 핀 오버레이 (모든 승차/출발·하차/도착 지점을 번호 핀으로 표시 + 겹침 자동 분리)
            pinPositions.forEachIndexed { index, (step, pt) ->
                val isFocus = step.id == selectedStep?.id || step.id == focusedStep?.id
                val isStart = index == 0
                val isEnd = index == pinPositions.lastIndex && pinPositions.size > 1
                val isBoardingOrAlighting = step.tags.any {
                    it.contains("출발") || it.contains("도착") || it.contains("승차") || it.contains("하차")
                } || step.title.contains("출발") || step.title.contains("도착") ||
                    step.title.contains("승차") || step.title.contains("하차") ||
                    !com.autologue.app.data.sync.DailyRouteAggregator.isIntermediateDrivingStep(step)

                val isKeyPoint = isStart || isEnd || isFocus || isBoardingOrAlighting || step.stepType != RouteStepType.DRIVING

                if (isKeyPoint) {
                    val pinColor = when {
                        isFocus -> Color(0xFFEA580C)
                        isStart -> Color(0xFF16A34A)
                        isEnd -> Color(0xFFDC2626)
                        else -> Color(0xFF2563EB)
                    }

                    val radius = if (isFocus) 14.dp.toPx() else 11.5.dp.toPx()

                    // 그림자 및 흰색 외곽선
                    drawCircle(
                        color = Color.Black.copy(alpha = 0.22f),
                        radius = radius + 3.dp.toPx(),
                        center = Offset(pt.x, pt.y + 2.dp.toPx())
                    )
                    drawCircle(
                        color = Color.White,
                        radius = radius + 2.dp.toPx(),
                        center = pt
                    )
                    drawCircle(
                        color = pinColor,
                        radius = radius,
                        center = pt
                    )

                    val numStr = "${index + 1}"
                    val numLayout = textMeasurer.measure(
                        text = AnnotatedString(numStr),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.ExtraBold,
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

                    val rawLabel = step.locationName ?: step.title
                    val cleanPlace = rawLabel
                        .replace(Regex("\\[.*?\\]"), "")
                        .replace("승차 (출발)", "")
                        .replace("하차", "")
                        .trim()
                        .ifBlank { rawLabel.take(8) }
                    val rolePrefix = when {
                        isStart -> "출발·"
                        isEnd -> "도착·"
                        else -> ""
                    }
                    val placeLabel = (rolePrefix + cleanPlace).take(10)

                    val labelLayout = textMeasurer.measure(
                        text = AnnotatedString(placeLabel),
                        style = TextStyle(
                            color = if (isFocus) Color(0xFF9A3412) else Color(0xFF0F172A),
                            fontSize = 9.5.sp,
                            fontWeight = FontWeight.Bold
                        )
                    )
                    val labelW = labelLayout.size.width + 12f
                    val labelH = labelLayout.size.height + 6f
                    val labelTopLeft = Offset(pt.x - labelW / 2f, pt.y + radius + 4f)

                    drawRoundRect(
                        color = Color.White.copy(alpha = 0.95f),
                        topLeft = labelTopLeft,
                        size = Size(labelW, labelH),
                        cornerRadius = CornerRadius(6f, 6f)
                    )
                    drawRoundRect(
                        color = if (isFocus) Color(0xFFEA580C) else pinColor.copy(alpha = 0.7f),
                        topLeft = labelTopLeft,
                        size = Size(labelW, labelH),
                        cornerRadius = CornerRadius(6f, 6f),
                        style = Stroke(width = 1.5f)
                    )
                    drawText(
                        textLayoutResult = labelLayout,
                        topLeft = Offset(labelTopLeft.x + 6f, labelTopLeft.y + 3f)
                    )
                } else {
                    val dotRadius = 4.5.dp.toPx()
                    drawCircle(color = Color.White, radius = dotRadius + 1.5.dp.toPx(), center = pt)
                    drawCircle(color = Color(0xFF2563EB), radius = dotRadius, center = pt)
                }
            }
        }

        // 우측 하단 줌 컨트롤러 (+ / - / 경로 맞춤)
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { userZoom = (userZoom * 1.35f).coerceAtMost(8f) },
                containerColor = Color.White.copy(alpha = 0.95f),
                contentColor = Color(0xFF0F172A),
                shape = CircleShape,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "확대", modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            SmallFloatingActionButton(
                onClick = { userZoom = (userZoom / 1.35f).coerceAtLeast(0.6f) },
                containerColor = Color.White.copy(alpha = 0.95f),
                contentColor = Color(0xFF0F172A),
                shape = CircleShape,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.Remove, contentDescription = "축소", modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            SmallFloatingActionButton(
                onClick = {
                    userZoom = 1f
                    userPan = Offset.Zero
                    selectedStep = null
                },
                containerColor = Color.White.copy(alpha = 0.95f),
                contentColor = Color(0xFF2563EB),
                shape = CircleShape,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.Refresh, contentDescription = "경로 맞춤", modifier = Modifier.size(16.dp))
            }
        }

        // 선택된 거점 상세 팝업 카드
        AnimatedVisibility(
            visible = selectedStep != null,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(10.dp)
                .fillMaxWidth(0.68f)
        ) {
            selectedStep?.let { step ->
                Surface(
                    color = Color.White.copy(alpha = 0.97f),
                    shape = RoundedCornerShape(10.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color(0xFF3B82F6)),
                    shadowElevation = 6.dp
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Place,
                                contentDescription = null,
                                tint = Color(0xFFEA580C),
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = step.locationName ?: step.title,
                                color = Color(0xFF0F172A),
                                fontSize = 12.5.sp,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1
                            )
                        }
                        if (!step.address.isNullOrBlank()) {
                            Text(
                                text = step.address,
                                color = Color(0xFF475569),
                                fontSize = 11.sp,
                                maxLines = 1,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Text(
                            text = "⏰ %02d:%02d".format(step.time.hour, step.time.minute),
                            color = Color(0xFF2563EB),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            }
        }
    }
}

private data class ViewportGeoBounds(
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double
) {
    val centerLat: Double get() = (minLat + maxLat) / 2.0
    val centerLng: Double get() = (minLng + maxLng) / 2.0
    val latSpan: Double get() = (maxLat - minLat).coerceAtLeast(0.01)
    val lngSpan: Double get() = (maxLng - minLng).coerceAtLeast(0.01)

    fun toScreenOffset(
        lat: Double,
        lng: Double,
        w: Float,
        h: Float,
        zoom: Float,
        pan: Offset
    ): Offset {
        // 위도 37.5도 기준 경도-위도 종횡비 보정 (cos(37.5°) ≈ 0.793)
        val cosLat = cos(Math.toRadians(centerLat)).coerceAtLeast(0.75)
        val geoAspect = (lngSpan * cosLat) / latSpan
        val screenAspect = (w / h).toDouble().coerceAtLeast(0.5)

        val effectiveLatSpan: Double
        val effectiveLngSpan: Double
        if (geoAspect > screenAspect) {
            effectiveLngSpan = lngSpan
            effectiveLatSpan = (lngSpan * cosLat) / screenAspect
        } else {
            effectiveLatSpan = latSpan
            effectiveLngSpan = (latSpan * screenAspect) / cosLat
        }

        val normX = ((lng - centerLng) / effectiveLngSpan).toFloat()
        val normY = ((centerLat - lat) / effectiveLatSpan).toFloat()

        val baseX = w / 2f + normX * w * 0.82f
        val baseY = h / 2f + normY * h * 0.78f

        return Offset(
            x = (baseX - w / 2f) * zoom + w / 2f + pan.x,
            y = (baseY - h / 2f) * zoom + h / 2f + pan.y
        )
    }
}

/**
 * 동일하거나 매우 인접한 좌표(예: 집 출발 핀 1과 귀가 도착 핀 4)가 서로 겹치지 않도록
 * 화면 좌표를 좌우로 자동 분리(Spiderfy Offset)하여 계산합니다.
 */
private fun computeScreenPinPositions(
    steps: List<RouteStep>,
    bounds: ViewportGeoBounds,
    w: Float,
    h: Float,
    zoom: Float,
    pan: Offset
): List<Pair<RouteStep, Offset>> {
    val rawList = steps.map { step ->
        step to bounds.toScreenOffset(step.latitude!!, step.longitude!!, w, h, zoom, pan)
    }
    if (rawList.size <= 1) return rawList

    val adjusted = rawList.toMutableList()
    val thresholdPx = 22f

    for (i in adjusted.indices) {
        val (stepI, ptI) = adjusted[i]
        val clusterIndices = mutableListOf<Int>()
        for (j in adjusted.indices) {
            if ((adjusted[j].second - ptI).getDistance() < thresholdPx) {
                clusterIndices.add(j)
            }
        }
        if (clusterIndices.size > 1) {
            val posInCluster = clusterIndices.indexOf(i)
            val totalInCluster = clusterIndices.size
            val spread = 26f
            val offsetX = (posInCluster - (totalInCluster - 1) / 2f) * spread
            val offsetY = if (posInCluster % 2 == 0) -4f else 4f
            adjusted[i] = stepI to Offset(ptI.x + offsetX, ptI.y + offsetY)
        }
    }
    return adjusted
}

/**
 * 구글 지도 스타일의 행정구역 다각형(시·군·구 경계), 한강/탄천 수계, 고속도로망, 계층형 지역명 렌더링
 */
private fun DrawScope.drawGoogleStyleKoreaBaseMap(
    w: Float,
    h: Float,
    bounds: ViewportGeoBounds,
    userZoom: Float,
    userPan: Offset,
    textMeasurer: TextMeasurer
) {
    fun pt(lat: Double, lng: Double): Offset = bounds.toScreenOffset(lat, lng, w, h, userZoom, userPan)

    // 1. 행정구역(시·군·구) 폴리곤 채우기 및 경계선 (실제 구글지도처럼 지역 구분 표시)
    val districtBorderEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 5f), 0f)
    MidKoreaBounds.DISTRICT_BOUNDARIES.forEach { district ->
        if (district.polygon.size >= 3) {
            val polyPath = Path()
            val screenPts = district.polygon.map { (lat, lng) -> pt(lat, lng) }
            polyPath.moveTo(screenPts.first().x, screenPts.first().y)
            for (i in 1 until screenPts.size) {
                polyPath.lineTo(screenPts[i].x, screenPts[i].y)
            }
            polyPath.close()

            val fillColor = when (district.regionGroup) {
                "SEOUL" -> Color(0xFFF8FAFC)
                "GYEONGGI_EAST" -> Color(0xFFF1F5F9)
                "GYEONGGI_SOUTH" -> Color(0xFFF4F6F9)
                "INCHEON" -> Color(0xFFEFF6FF)
                else -> Color(0xFFF3F4F6)
            }

            drawPath(path = polyPath, color = fillColor, style = Fill)
            // 시·군·구 행정경계선
            drawPath(
                path = polyPath,
                color = Color(0xFF94A3B8).copy(alpha = 0.75f),
                style = Stroke(width = 1.4.dp.toPx(), pathEffect = districtBorderEffect)
            )
        }
    }

    // 서울특별시-경기도 광역 시·도 경계선 강조
    val seoulMetroBorder = listOf(
        37.585 to 126.795, 37.655 to 126.900, 37.695 to 127.010, 37.685 to 127.095,
        37.575 to 127.175, 37.540 to 127.182, 37.495 to 127.160, 37.472 to 127.145,
        37.460 to 127.095, 37.435 to 127.065, 37.445 to 126.985, 37.440 to 126.900,
        37.450 to 126.825, 37.515 to 126.815, 37.585 to 126.795
    )
    val seoulBorderPath = Path().apply {
        val pts = seoulMetroBorder.map { (lat, lng) -> pt(lat, lng) }
        moveTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(
        path = seoulBorderPath,
        color = Color(0xFF64748B).copy(alpha = 0.85f),
        style = Stroke(width = 2.2.dp.toPx())
    )

    // 2. 한강 본류 및 주요 지류(탄천, 안양천, 중랑천) 수계 렌더링
    val hanRiverCoords = listOf(
        37.650 to 126.680, 37.580 to 126.810, 37.545 to 126.895, 37.528 to 126.925,
        37.515 to 126.960, 37.525 to 127.010, 37.530 to 127.065, 37.522 to 127.105,
        37.555 to 127.145, 37.575 to 127.195, 37.545 to 127.255, 37.520 to 127.320,
        37.500 to 127.450
    )
    val hanPath = Path().apply {
        val pts = hanRiverCoords.map { (lat, lng) -> pt(lat, lng) }
        moveTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(
        path = hanPath,
        color = Color(0xFF93C5FD).copy(alpha = 0.85f),
        style = Stroke(width = 9.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 탄천 (잠실 한강 합류점 ➔ 송파/강남 경계 ➔ 성남 수정/분당 ➔ 용인)
    val tancheonCoords = listOf(
        37.522 to 127.075, 37.495 to 127.095, 37.470 to 127.115, 37.435 to 127.125,
        37.385 to 127.115, 37.330 to 127.110
    )
    val tancheonPath = Path().apply {
        val pts = tancheonCoords.map { (lat, lng) -> pt(lat, lng) }
        moveTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(
        path = tancheonPath,
        color = Color(0xFF93C5FD).copy(alpha = 0.7f),
        style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 안양천 (염창 한강 합류점 ➔ 영등포/양천 ➔ 구로/광명 ➔ 안양)
    val anyangcheonCoords = listOf(
        37.550 to 126.875, 37.520 to 126.885, 37.485 to 126.875, 37.440 to 126.890, 37.395 to 126.925
    )
    val anyangPath = Path().apply {
        val pts = anyangcheonCoords.map { (lat, lng) -> pt(lat, lng) }
        moveTo(pts.first().x, pts.first().y)
        for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
    }
    drawPath(
        path = anyangPath,
        color = Color(0xFF93C5FD).copy(alpha = 0.7f),
        style = Stroke(width = 3.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

    // 3. 주요 간선도로·고속도로망 (올림픽대로, 강변북로, 수도권제1순환, 경부, 서해안, 분당수서로)
    val roadColor = Color(0xFFFDE68A).copy(alpha = 0.9f)
    val roadBorderColor = Color(0xFFF59E0B).copy(alpha = 0.55f)

    fun drawExpressway(coords: List<Pair<Double, Double>>) {
        val roadPath = Path().apply {
            val pts = coords.map { (lat, lng) -> pt(lat, lng) }
            moveTo(pts.first().x, pts.first().y)
            for (i in 1 until pts.size) lineTo(pts[i].x, pts[i].y)
        }
        drawPath(roadPath, roadBorderColor, style = Stroke(width = 4.2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(roadPath, roadColor, style = Stroke(width = 2.4.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // 올림픽대로 (강서 ➔ 여의도 ➔ 동작 ➔ 반포 ➔ 잠실 ➔ 강동 ➔ 하남 미사)
    drawExpressway(
        listOf(
            37.575 to 126.815, 37.540 to 126.895, 37.515 to 126.940, 37.508 to 126.995,
            37.518 to 127.060, 37.522 to 127.105, 37.550 to 127.155, 37.560 to 127.205
        )
    )
    // 수도권제1순환고속도로 동부·남부 (구리 ➔ 강동 ➔ 하남 서하남IC ➔ 송파IC/위례 ➔ 성남IC ➔ 판교IC ➔ 청계/평촌 ➔ 광명/부천)
    drawExpressway(
        listOf(
            37.610 to 127.145, 37.555 to 127.175, 37.515 to 127.185, 37.482 to 127.138,
            37.445 to 127.135, 37.405 to 127.102, 37.395 to 127.025, 37.380 to 126.940,
            37.450 to 126.780, 37.550 to 126.750
        )
    )
    // 경부고속도로 (한남 ➔ 반포 ➔ 양재 ➔ 판교 ➔ 기흥/수원 ➔ 동탄/평택 ➔ 천안)
    drawExpressway(
        listOf(
            37.530 to 127.012, 37.495 to 127.020, 37.460 to 127.042, 37.395 to 127.102,
            37.270 to 127.105, 37.150 to 127.095, 36.815 to 127.150
        )
    )
    // 송파대로·위례중앙로·분당수서로 (잠실 ➔ 가락/문정 ➔ 위례·학암동 ➔ 성남/분당)
    drawExpressway(
        listOf(
            37.513 to 127.100, 37.485 to 127.120, 37.478 to 127.145, 37.445 to 127.140, 37.385 to 127.118
        )
    )

    // 4. 계층형 행정구역 및 동/생활권 지명 라벨 (확대 배율에 따라 겹침 없이 선명하게 표출)
    val effectiveSpan = bounds.latSpan / userZoom
    val placedRects = mutableListOf<androidx.compose.ui.geometry.Rect>()

    MidKoreaBounds.ANCHORS.sortedBy { it.tier }.forEach { anchor ->
        // 현재 줌 수준에 맞는 계층의 지명만 선택
        val shouldShow = when (anchor.tier) {
            1 -> true
            2 -> effectiveSpan < 0.70
            3 -> effectiveSpan < 0.38
            else -> false
        }
        if (!shouldShow) return@forEach

        val screenPt = pt(anchor.lat, anchor.lng)
        if (screenPt.x < 16f || screenPt.x > w - 16f || screenPt.y < 16f || screenPt.y > h - 16f) {
            return@forEach
        }

        val fontSize = when (anchor.tier) {
            1 -> 11.sp
            2 -> 10.sp
            else -> 9.sp
        }
        val textColor = when (anchor.tier) {
            1 -> Color(0xFF1E293B)
            2 -> Color(0xFF334155)
            else -> Color(0xFF475569)
        }
        val fontWeight = when (anchor.tier) {
            1 -> FontWeight.ExtraBold
            2 -> FontWeight.Bold
            else -> FontWeight.SemiBold
        }

        val layout = textMeasurer.measure(
            text = AnnotatedString(anchor.name),
            style = TextStyle(
                color = textColor,
                fontSize = fontSize,
                fontWeight = fontWeight
            )
        )

        val topLeft = Offset(
            screenPt.x - layout.size.width / 2f,
            screenPt.y - layout.size.height / 2f
        )
        val candidateRect = androidx.compose.ui.geometry.Rect(
            left = topLeft.x - 6f,
            top = topLeft.y - 3f,
            right = topLeft.x + layout.size.width + 6f,
            bottom = topLeft.y + layout.size.height + 3f
        )

        // 라벨 간 겹침(충돌) 방지
        if (placedRects.any { it.overlaps(candidateRect) }) {
            return@forEach
        }
        placedRects.add(candidateRect)

        // 가독성을 위한 반투명 화이트 헤일로 배경
        drawRoundRect(
            color = Color.White.copy(alpha = 0.78f),
            topLeft = Offset(topLeft.x - 3f, topLeft.y - 1f),
            size = Size(layout.size.width + 6f, layout.size.height + 2f),
            cornerRadius = CornerRadius(4f, 4f)
        )
        drawText(textLayoutResult = layout, topLeft = topLeft)
    }
}
