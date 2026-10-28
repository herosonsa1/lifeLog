package com.autologue.app.presentation.diary.map

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.presentation.theme.AppShapes
import com.autologue.app.presentation.theme.PureWhite
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.*

/**
 * 한국 행정구역(시·도 / 시·군·구 / 주요 동·신도시) 및 도로·수계 고정밀 지오레퍼런스 데이터
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

        // Tier 2: 주요 구·시·군
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

        // Tier 3: 세부 동·생활권
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
 * 구글 한국어 지도 타일(mt0~3.google.com/vt/lyrs=m&hl=ko&gl=kr&scale=2) 및 OSRM 도로 경로 인메모리 캐시 & 로더
 * WebView 없이 순수 Jetpack Compose Canvas 위에서 100% 네이티브로 선명한 지도를 렌더링합니다.
 */
private object NativeKoreaMapCache {
    private val tileCache = object : LruCache<String, ImageBitmap>(220) {}
    private val inFlightTiles = ConcurrentHashMap.newKeySet<String>()
    private val routeCache = ConcurrentHashMap<String, List<Pair<Double, Double>>>()
    private val inFlightRoutes = ConcurrentHashMap.newKeySet<String>()

    fun getTile(key: String): ImageBitmap? = synchronized(tileCache) { tileCache.get(key) }

    private fun putTile(key: String, bitmap: ImageBitmap) = synchronized(tileCache) {
        tileCache.put(key, bitmap)
    }

    suspend fun fetchTileIfNeeded(
        tileStyle: String, // "road" or "sat"
        z: Int,
        x: Int,
        y: Int,
        onLoaded: () -> Unit
    ) {
        val maxIndex = (1 shl z) - 1
        if (x !in 0..maxIndex || y !in 0..maxIndex) return
        val key = "${tileStyle}_${z}_${x}_${y}"
        if (getTile(key) != null) return
        if (!inFlightTiles.add(key)) return

        try {
            val bitmap = withContext(Dispatchers.IO) {
                val serverIdx = (x + y) and 3
                val lyrs = if (tileStyle == "sat") "y" else "m"
                val googleUrl = "https://mt$serverIdx.google.com/vt/lyrs=$lyrs&hl=ko&gl=kr&x=$x&y=$y&z=$z&scale=2"
                downloadImageBitmap(googleUrl)
                    ?: if (tileStyle == "road" && z <= 19) {
                        downloadImageBitmap("https://tile.openstreetmap.org/$z/$x/$y.png")
                    } else null
            }
            if (bitmap != null) {
                putTile(key, bitmap)
                onLoaded()
            }
        } finally {
            inFlightTiles.remove(key)
        }
    }

    private fun downloadImageBitmap(urlStr: String): ImageBitmap? {
        return runCatching {
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                connectTimeout = 4000
                readTimeout = 4000
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                )
                setRequestProperty("Accept", "image/webp,image/apng,image/*,*/*;q=0.8")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            }
            try {
                if (conn.responseCode in 200..299) {
                    conn.inputStream.use { stream ->
                        BitmapFactory.decodeStream(stream)?.asImageBitmap()
                    }
                } else null
            } finally {
                conn.disconnect()
            }
        }.getOrNull()
    }

    fun getCachedRoute(key: String): List<Pair<Double, Double>>? = routeCache[key]

    suspend fun fetchOsrmRoadRouteIfNeeded(
        steps: List<RouteStep>,
        onLoaded: (List<Pair<Double, Double>>) -> Unit
    ) {
        if (steps.size < 2) return
        val key = steps.joinToString("|") { "%.5f,%.5f".format(it.latitude, it.longitude) }
        routeCache[key]?.let {
            onLoaded(it)
            return
        }
        if (!inFlightRoutes.add(key)) return

        try {
            val roadCoords = withContext(Dispatchers.IO) {
                runCatching {
                    val coordParam = steps.joinToString(";") { "${it.longitude},${it.latitude}" }
                    val urlStr = "https://router.project-osrm.org/route/v1/driving/$coordParam?overview=full&geometries=geojson"
                    val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                        connectTimeout = 4000
                        readTimeout = 4000
                        requestMethod = "GET"
                        setRequestProperty("User-Agent", "LifeLog-Android/1.0")
                    }
                    try {
                        if (conn.responseCode in 200..299) {
                            val body = conn.inputStream.bufferedReader().use { it.readText() }
                            val json = JSONObject(body)
                            val routes = json.optJSONArray("routes")
                            if (routes != null && routes.length() > 0) {
                                val geom = routes.getJSONObject(0).optJSONObject("geometry")
                                val coordsArr = geom?.optJSONArray("coordinates")
                                if (coordsArr != null && coordsArr.length() > 1) {
                                    val list = ArrayList<Pair<Double, Double>>(coordsArr.length())
                                    for (i in 0 until coordsArr.length()) {
                                        val pt = coordsArr.getJSONArray(i)
                                        val lng = pt.getDouble(0)
                                        val lat = pt.getDouble(1)
                                        list.add(lat to lng)
                                    }
                                    list
                                } else null
                            } else null
                        } else null
                    } finally {
                        conn.disconnect()
                    }
                }.getOrNull()
            }
            if (!roadCoords.isNullOrEmpty()) {
                routeCache[key] = roadCoords
                onLoaded(roadCoords)
            }
        } finally {
            inFlightRoutes.remove(key)
        }
    }
}

/**
 * 표준 Web Mercator (EPSG:3857) 투영 변환기
 * 구글 지도 타일과 행정구역 경계선·도로 경로·마커 핀이 1픽셀의 오차도 없이 정확히 일치하도록 계산합니다.
 */
private object WebMercator {
    fun lngToWorldX(lng: Double): Double = (lng + 180.0) / 360.0

    fun latToWorldY(lat: Double): Double {
        val clamped = lat.coerceIn(-85.05112878, 85.05112878)
        val rad = Math.toRadians(clamped)
        return (1.0 - ln(tan(rad) + 1.0 / cos(rad)) / PI) / 2.0
    }

    fun worldXToLng(worldX: Double): Double = (worldX.coerceIn(0.0, 1.0) * 360.0) - 180.0

    fun worldYToLat(worldY: Double): Double {
        val n = PI - 2.0 * PI * worldY.coerceIn(0.0, 1.0)
        return Math.toDegrees(atan(0.5 * (exp(n) - exp(-n))))
    }

    fun toScreenOffset(
        lat: Double,
        lng: Double,
        centerLat: Double,
        centerLng: Double,
        zoom: Float,
        screenW: Float,
        screenH: Float,
        baseTilePx: Float
    ): Offset {
        val worldScale = baseTilePx * 2.0.pow(zoom.toDouble())
        val cx = lngToWorldX(centerLng) * worldScale
        val cy = latToWorldY(centerLat) * worldScale
        val px = lngToWorldX(lng) * worldScale
        val py = latToWorldY(lat) * worldScale
        return Offset(
            x = (screenW / 2.0 + (px - cx)).toFloat(),
            y = (screenH / 2.0 + (py - cy)).toFloat()
        )
    }

    /**
     * 이동 경로 거점들이 지도 화면 중앙에 확대되어 한눈에 들어오도록 초기 중심점과 줌 레벨을 계산합니다.
     */
    fun computeAutoFitCamera(
        steps: List<RouteStep>,
        focusedStep: RouteStep?
    ): Pair<Pair<Double, Double>, Float> {
        if (focusedStep?.latitude != null && focusedStep.longitude != null) {
            return (focusedStep.latitude to focusedStep.longitude) to 15.2f
        }
        if (steps.isEmpty()) {
            return (37.5150 to 127.0400) to 11.2f
        }
        if (steps.size == 1) {
            val s = steps.first()
            return (s.latitude!! to s.longitude!!) to 15.0f
        }

        val minLat = steps.minOf { it.latitude!! }
        val maxLat = steps.maxOf { it.latitude!! }
        val minLng = steps.minOf { it.longitude!! }
        val maxLng = steps.maxOf { it.longitude!! }

        val centerLat = (minLat + maxLat) / 2.0
        val centerLng = (minLng + maxLng) / 2.0

        val worldSpanX = abs(lngToWorldX(maxLng) - lngToWorldX(minLng)).coerceAtLeast(1e-5)
        val worldSpanY = abs(latToWorldY(minLat) - latToWorldY(maxLat)).coerceAtLeast(1e-5)

        // 화면 폭의 약 68%(좌우 마커 캡션 여백 확보) 안에 전체 경로가 꽉 차게 들어오도록 줌 산출
        val targetTilesW = 0.85
        val targetTilesH = 0.72
        val zoomX = ln(targetTilesW / worldSpanX) / ln(2.0)
        val zoomY = ln(targetTilesH / worldSpanY) / ln(2.0)
        val bestZoom = min(zoomX, zoomY).toFloat().coerceIn(9.8f, 15.4f)

        return (centerLat to centerLng) to bestZoom
    }
}

private data class PinPlacement(
    val step: RouteStep,
    val index: Int,
    val rawPoint: Offset,
    val displayPoint: Offset,
    val labelAbove: Boolean,
    val isClustered: Boolean
)

/**
 * 지도 위 지점(마커/숫자/점) 표시 방식
 * - AUTO: 경로 지점 수에 따라 자동 전환 (1~5개: 기본 숫자+박스, 6~12개: 작은 O·숫자, 13개 이상: 점+경로만)
 * - FULL: 기본 크기 번호 원 + 강조 네모 텍스트 박스 모두 표시
 * - COMPACT: 지점 원(O)과 숫자를 작게 축소 표기 (텍스트 박스는 터치/집중 지점만 표시)
 * - DOT_ONLY: 숫자 표기를 제거하고 깔끔한 점(●)과 경로선만 표기
 */
enum class MapPointDisplayMode(val title: String) {
    AUTO("자동"),
    FULL("기본"),
    COMPACT("작은숫자"),
    DOT_ONLY("점·경로")
}

fun resolveEffectivePointDisplayMode(
    mode: MapPointDisplayMode,
    stepCount: Int
): MapPointDisplayMode {
    if (mode != MapPointDisplayMode.AUTO) return mode
    return when {
        stepCount >= 13 -> MapPointDisplayMode.DOT_ONLY
        stepCount >= 6 -> MapPointDisplayMode.COMPACT
        else -> MapPointDisplayMode.FULL
    }
}

/**
 * WebView에 의존하지 않는 100% 네이티브 Jetpack Compose 한국 상세 지도 & 행정구역 벡터 맵 뷰.
 *
 * - [상세 한국 지도 모드 (useRealMapTiles = true)]:
 *   Google 한국어 고해상도 지도 타일(mt0~3.google.com/vt/lyrs=m&hl=ko&gl=kr&scale=2) + 시·군·구 지역 구분선 + OSRM 실제 도로 경로 + 번호 지점 핀을 즉시 렌더링합니다.
 * - [행정구역 벡터 모드 (useRealMapTiles = false)]:
 *   오프라인에서도 0ms 만에 동작하는 구글지도 스타일 행정구역 다각형 + 한강/탄천 수계 + 고속도로망 + 번호 지점 핀을 렌더링합니다.
 */
@Composable
fun MidKoreaStaticMapView(
    steps: List<RouteStep>,
    focusedStep: RouteStep? = null,
    zoomToFocusedStep: Boolean = false,
    pointDisplayMode: MapPointDisplayMode = MapPointDisplayMode.AUTO,
    onStepClick: (RouteStep) -> Unit = {},
    useRealMapTiles: Boolean = false,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val density = LocalDensity.current
    val baseTilePx = with(density) { 256.dp.toPx() }
    val coroutineScope = rememberCoroutineScope()

    val validSteps = remember(steps) {
        steps
            .map { ensureStepCoordinates(it) }
            .filter { it.latitude != null && it.longitude != null && (it.latitude != 0.0 || it.longitude != 0.0) }
    }

    val effectiveDisplayMode = remember(pointDisplayMode, validSteps.size) {
        resolveEffectivePointDisplayMode(pointDisplayMode, validSteps.size)
    }

    val effectiveCameraStep = if (zoomToFocusedStep) focusedStep else null

    val initialCamera = remember(validSteps, effectiveCameraStep) {
        WebMercator.computeAutoFitCamera(validSteps, effectiveCameraStep)
    }

    var centerLat by remember { mutableDoubleStateOf(initialCamera.first.first) }
    var centerLng by remember { mutableDoubleStateOf(initialCamera.first.second) }
    var zoomLevel by remember { mutableFloatStateOf(initialCamera.second) }
    var selectedStep by remember(effectiveCameraStep) { mutableStateOf(effectiveCameraStep) }

    // 타일 스타일 ("road": 구글 한국지도, "sat": 위성+지명) 및 지역경계선 표시 토글
    var tileStyle by remember { mutableStateOf("road") }
    var showDistrictBorders by remember { mutableStateOf(true) }
    var tileRedrawTick by remember { mutableIntStateOf(0) }
    var roadGeometry by remember(validSteps) { mutableStateOf<List<Pair<Double, Double>>?>(null) }

    // 경로나 포커스 지점이 바뀌면 카메라를 자동으로 해당 구역(기본값: 전체 경로)에 맞춰 확대
    LaunchedEffect(validSteps, effectiveCameraStep) {
        val (center, z) = WebMercator.computeAutoFitCamera(validSteps, effectiveCameraStep)
        centerLat = center.first
        centerLng = center.second
        zoomLevel = z
    }

    // 실제 한국 도로망(OSRM) 경로 비동기 로드 (로드 전에도 기본 경로선이 즉시 표시됨)
    LaunchedEffect(validSteps) {
        roadGeometry = null
        if (validSteps.size >= 2) {
            NativeKoreaMapCache.fetchOsrmRoadRouteIfNeeded(validSteps) { coords ->
                roadGeometry = coords
            }
        }
    }

    val transformableState = rememberTransformableState { zoomChange, panChange, _ ->
        val newZoom = (zoomLevel + (ln(zoomChange.toDouble()) / ln(2.0)).toFloat()).coerceIn(7.5f, 17.8f)
        val worldScale = baseTilePx * 2.0.pow(newZoom.toDouble())
        val newWorldX = WebMercator.lngToWorldX(centerLng) - (panChange.x / worldScale)
        val newWorldY = WebMercator.latToWorldY(centerLat) - (panChange.y / worldScale)

        zoomLevel = newZoom
        centerLng = WebMercator.worldXToLng(newWorldX).coerceIn(124.5, 131.0)
        centerLat = WebMercator.worldYToLat(newWorldY).coerceIn(33.0, 38.9)
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color(0xFFEEF2F6))
            .clipToBounds()
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .transformable(state = transformableState)
                .pointerInput(validSteps, centerLat, centerLng, zoomLevel, effectiveDisplayMode) {
                    detectTapGestures(
                        onTap = { tapOffset ->
                            val w = size.width.toFloat()
                            val h = size.height.toFloat()
                            val placements = computePinPlacements(
                                steps = validSteps,
                                centerLat = centerLat,
                                centerLng = centerLng,
                                zoom = zoomLevel,
                                w = w,
                                h = h,
                                baseTilePx = baseTilePx,
                                displayMode = effectiveDisplayMode
                            )

                            var hitStep: RouteStep? = null
                            var minHitDist = 42.dp.toPx()

                            placements.forEach { p ->
                                val dist = (p.displayPoint - tapOffset).getDistance()
                                if (dist < minHitDist) {
                                    minHitDist = dist
                                    hitStep = p.step
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
                            zoomLevel = (zoomLevel + 1.0f).coerceAtMost(17.5f)
                        }
                    )
                }
        ) {
            val w = size.width
            val h = size.height
            // tileRedrawTick 읽기로 타일 다운로드 완료 시 즉시 리컴포지션/드로잉 트리거
            val tick = tileRedrawTick
            if (tick < 0) return@Canvas

            val placements = computePinPlacements(
                steps = validSteps,
                centerLat = centerLat,
                centerLng = centerLng,
                zoom = zoomLevel,
                w = w,
                h = h,
                baseTilePx = baseTilePx,
                displayMode = effectiveDisplayMode
            )
            val pinAvoidRects = placements.mapNotNull { p ->
                val isManuallySelected = p.step.id == selectedStep?.id || (zoomToFocusedStep && p.step.id == focusedStep?.id)
                val showBox = effectiveDisplayMode == MapPointDisplayMode.FULL || isManuallySelected
                if (!showBox) return@mapNotNull null
                val boxHalfW = 72.dp.toPx()
                val boxHalfH = 44.dp.toPx()
                androidx.compose.ui.geometry.Rect(
                    left = p.displayPoint.x - boxHalfW,
                    top = p.displayPoint.y - boxHalfH,
                    right = p.displayPoint.x + boxHalfW,
                    bottom = p.displayPoint.y + boxHalfH
                )
            }

            // 1. 베이스로 구글지도 스타일 행정구역 다각형·수계·고속도로 벡터 맵을 먼저 그려서
            //    타일 로딩 중이거나 오프라인일 때도 빈 화면이 절대 발생하지 않도록 보장
            drawGoogleStyleKoreaBaseMap(
                w = w,
                h = h,
                centerLat = centerLat,
                centerLng = centerLng,
                zoom = zoomLevel,
                baseTilePx = baseTilePx,
                textMeasurer = textMeasurer,
                drawLabelsAndBorders = !useRealMapTiles,
                avoidRects = pinAvoidRects
            )

            // 2. [상세 한국 지도 모드] 실제 구글 한국어 고해상도 지도 타일 렌더링
            if (useRealMapTiles && w > 0f && h > 0f) {
                val tileZ = zoomLevel.roundToInt().coerceIn(7, 18)
                val scaleFromTileZ = 2.0.pow((zoomLevel - tileZ).toDouble()).toFloat()
                val tileScreenPx = baseTilePx * scaleFromTileZ
                val numTiles = 1 shl tileZ

                val centerTileX = WebMercator.lngToWorldX(centerLng) * numTiles
                val centerTileY = WebMercator.latToWorldY(centerLat) * numTiles

                val halfTilesX = (w / (2f * tileScreenPx)) + 1.2f
                val halfTilesY = (h / (2f * tileScreenPx)) + 1.2f

                val minTx = floor(centerTileX - halfTilesX).toInt().coerceIn(0, numTiles - 1)
                val maxTx = floor(centerTileX + halfTilesX).toInt().coerceIn(0, numTiles - 1)
                val minTy = floor(centerTileY - halfTilesY).toInt().coerceIn(0, numTiles - 1)
                val maxTy = floor(centerTileY + halfTilesY).toInt().coerceIn(0, numTiles - 1)

                for (tx in minTx..maxTx) {
                    for (ty in minTy..maxTy) {
                        val leftPx = (w / 2f + (tx - centerTileX).toFloat() * tileScreenPx).roundToInt()
                        val topPx = (h / 2f + (ty - centerTileY).toFloat() * tileScreenPx).roundToInt()
                        val rightPx = (w / 2f + (tx + 1 - centerTileX).toFloat() * tileScreenPx).roundToInt()
                        val bottomPx = (h / 2f + (ty + 1 - centerTileY).toFloat() * tileScreenPx).roundToInt()

                        val key = "${tileStyle}_${tileZ}_${tx}_${ty}"
                        val bitmap = NativeKoreaMapCache.getTile(key)
                        if (bitmap != null) {
                            drawImage(
                                image = bitmap,
                                dstOffset = IntOffset(leftPx, topPx),
                                dstSize = IntSize((rightPx - leftPx).coerceAtLeast(1), (bottomPx - topPx).coerceAtLeast(1))
                            )
                        } else {
                            coroutineScope.launch {
                                NativeKoreaMapCache.fetchTileIfNeeded(
                                    tileStyle = tileStyle,
                                    z = tileZ,
                                    x = tx,
                                    y = ty,
                                    onLoaded = { tileRedrawTick++ }
                                )
                            }
                        }
                    }
                }
            }

            // 3. 시·군·구 지역 구분 경계선 오버레이 (상세 한국 지도 위에서도 지역 구분선이 선명하게 표시됨)
            if (showDistrictBorders) {
                drawDistrictBoundariesOverlay(
                    w = w,
                    h = h,
                    centerLat = centerLat,
                    centerLng = centerLng,
                    zoom = zoomLevel,
                    baseTilePx = baseTilePx,
                    textMeasurer = textMeasurer,
                    isOverTileMap = useRealMapTiles,
                    isSatellite = useRealMapTiles && tileStyle == "sat",
                    avoidRects = pinAvoidRects
                )
            }

            // 4. 주행 경로선(Polyline) 렌더링 (OSRM 실제 도로망 경로가 로드되면 실제 도로 굴곡으로, 로드 전에는 즉시 직선/곡선 경로로 표시)

            if (validSteps.size > 1) {
                val pathCoords = roadGeometry ?: validSteps.map { it.latitude!! to it.longitude!! }
                val screenRoutePts = pathCoords.map { (lat, lng) ->
                    WebMercator.toScreenOffset(lat, lng, centerLat, centerLng, zoomLevel, w, h, baseTilePx)
                }

                if (screenRoutePts.size > 1) {
                    val routePath = Path().apply {
                        moveTo(screenRoutePts.first().x, screenRoutePts.first().y)
                        for (i in 1 until screenRoutePts.size) {
                            lineTo(screenRoutePts[i].x, screenRoutePts[i].y)
                        }
                    }

                    // 외곽 진파랑 케이싱
                    drawPath(
                        path = routePath,
                        color = Color(0xFF1E3A8A).copy(alpha = 0.90f),
                        style = Stroke(
                            width = 8.5.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    // 내부 밝은 블루 경로선
                    drawPath(
                        path = routePath,
                        color = Color(0xFF3B82F6),
                        style = Stroke(
                            width = 5.2.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round
                        )
                    )
                    // 중앙 화이트 대시 가이드라인
                    drawPath(
                        path = routePath,
                        color = Color.White.copy(alpha = 0.85f),
                        style = Stroke(
                            width = 1.8.dp.toPx(),
                            cap = StrokeCap.Round,
                            join = StrokeJoin.Round,
                            pathEffect = PathEffect.dashPathEffect(floatArrayOf(12f, 10f), 0f)
                        )
                    )
                }
            }

            // 5. 거점 마커 핀 오버레이 (표시 모드: FULL 기본 숫자+박스 / COMPACT 작은 O·숫자 / DOT_ONLY 숫자 제거 점+경로만)
            placements.forEach { placement ->
                val step = placement.step
                val index = placement.index
                val pt = placement.displayPoint
                val rawPt = placement.rawPoint

                val isFocus = step.id == selectedStep?.id || step.id == focusedStep?.id
                val isManuallySelected = step.id == selectedStep?.id || (zoomToFocusedStep && step.id == focusedStep?.id)
                val isStopover = DailyRouteAggregator.isStopoverWaypointStep(step)
                val isStart = isDepartureStep(step) || (index == 0 && !isStopover && !isArrivalStep(step))
                val isEnd = isArrivalStep(step) || (index == placements.lastIndex && placements.size > 1 && !isStart && !isStopover)

                val pinColor = when {
                    isFocus -> Color(0xFFEA580C)
                    isStart -> Color(0xFF16A34A)
                    isStopover -> Color(0xFF2563EB)
                    isEnd -> Color(0xFFDC2626)
                    else -> Color(0xFF475569)
                }

                // 동일 좌표에서 분리된 마커인 경우 실제 GPS 위치(rawPt)와 마커(pt) 사이에 지시선 표시
                if (placement.isClustered && effectiveDisplayMode != MapPointDisplayMode.DOT_ONLY) {
                    drawLine(
                        color = Color.White,
                        start = rawPt,
                        end = pt,
                        strokeWidth = 3.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawLine(
                        color = pinColor,
                        start = rawPt,
                        end = pt,
                        strokeWidth = 1.6.dp.toPx(),
                        cap = StrokeCap.Round
                    )
                    drawCircle(
                        color = Color.White,
                        radius = 3.5.dp.toPx(),
                        center = rawPt
                    )
                    drawCircle(
                        color = pinColor,
                        radius = 2.2.dp.toPx(),
                        center = rawPt
                    )
                }

                val radius = when (effectiveDisplayMode) {
                    MapPointDisplayMode.DOT_ONLY -> when {
                        isFocus -> 7.2.dp.toPx()
                        isStart || isEnd -> 6.0.dp.toPx()
                        else -> 4.8.dp.toPx()
                    }
                    MapPointDisplayMode.COMPACT -> if (isFocus) 11.0.dp.toPx() else 9.0.dp.toPx()
                    else -> if (isFocus) 15.dp.toPx() else 13.5.dp.toPx()
                }

                val whiteBorderWidth = when (effectiveDisplayMode) {
                    MapPointDisplayMode.DOT_ONLY -> 2.0.dp.toPx()
                    MapPointDisplayMode.COMPACT -> 2.0.dp.toPx()
                    else -> 2.5.dp.toPx()
                }

                // 마커 그림자 및 흰색 테두리 + 원형 배지(또는 도트 점)
                drawCircle(
                    color = Color.Black.copy(alpha = 0.32f),
                    radius = radius + whiteBorderWidth + 0.8.dp.toPx(),
                    center = Offset(pt.x, pt.y + 1.8.dp.toPx())
                )
                drawCircle(
                    color = Color.White,
                    radius = radius + whiteBorderWidth,
                    center = pt
                )
                drawCircle(
                    color = pinColor,
                    radius = radius,
                    center = pt
                )

                if (effectiveDisplayMode == MapPointDisplayMode.DOT_ONLY) {
                    // 점+경로만 모드: 숫자 표기 없이 출발/도착/선택 지점 중심에 깔끔한 미니 코어(◎)만 표시
                    if (isStart || isEnd || isFocus) {
                        drawCircle(
                            color = Color.White,
                            radius = 2.0.dp.toPx(),
                            center = pt
                        )
                    }
                } else {
                    val numStr = "${index + 1}"
                    val numFontSize = if (effectiveDisplayMode == MapPointDisplayMode.COMPACT) {
                        if (numStr.length >= 2) 8.5.sp else 9.5.sp
                    } else {
                        12.5.sp
                    }
                    val numLayout = textMeasurer.measure(
                        text = AnnotatedString(numStr),
                        style = TextStyle(
                            color = Color.White,
                            fontSize = numFontSize,
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
                }

                // 네모 텍스트 박스는 FULL(기본) 모드이거나 사용자가 직접 터치/선택한 지점일 때만 표시
                val shouldDrawLabelBox = effectiveDisplayMode == MapPointDisplayMode.FULL || isManuallySelected
                if (shouldDrawLabelBox) {
                    val cleanPlace = DailyRouteAggregator.resolveStepDisplayPlaceName(step)
                    val rolePrefix = when {
                        isStart -> "출발·"
                        isStopover -> "경유·"
                        isEnd -> "도착·"
                        else -> ""
                    }
                    val placeLabel = (rolePrefix + cleanPlace).take(16)

                    val labelTextColor = when {
                        isFocus -> Color(0xFF9A3412)
                        isStart -> Color(0xFF14532D)
                        isStopover -> Color(0xFF1E3A8A)
                        isEnd -> Color(0xFF991B1B)
                        else -> Color(0xFF0F172A)
                    }
                    val labelLayout = textMeasurer.measure(
                        text = AnnotatedString(placeLabel),
                        style = TextStyle(
                            color = labelTextColor,
                            fontSize = 12.5.sp,
                            fontWeight = FontWeight.ExtraBold
                        )
                    )
                    val padH = 9.dp.toPx()
                    val padV = 4.5.dp.toPx()
                    val labelW = labelLayout.size.width + padH * 2f
                    val labelH = labelLayout.size.height + padV * 2f
                    val gapY = 5.dp.toPx()
                    val screenMargin = 6.dp.toPx()
                    val labelTopY = if (placement.labelAbove) {
                        pt.y - radius - labelH - gapY
                    } else {
                        pt.y + radius + gapY
                    }
                    val labelTopLeft = Offset(
                        x = (pt.x - labelW / 2f).coerceIn(screenMargin, (w - labelW - screenMargin).coerceAtLeast(screenMargin)),
                        y = labelTopY.coerceIn(screenMargin, (h - labelH - screenMargin).coerceAtLeast(screenMargin))
                    )
                    val boxCorner = CornerRadius(6.5.dp.toPx(), 6.5.dp.toPx())
                    val haloExpand = 2.2.dp.toPx()
                    val borderColor = if (isFocus) Color(0xFFEA580C) else pinColor
                    val boxBgColor = when {
                        isFocus -> Color(0xFFFFF7ED)
                        isStart -> Color(0xFFF0FDF4)
                        isEnd -> Color(0xFFFEF2F2)
                        else -> Color.White
                    }

                    // 1) 네모 텍스트 박스 하단 그림자 (지도 배경과 입체 분리)
                    drawRoundRect(
                        color = Color(0xFF0F172A).copy(alpha = 0.36f),
                        topLeft = Offset(labelTopLeft.x - 1.dp.toPx(), labelTopLeft.y + 2.5.dp.toPx()),
                        size = Size(labelW + 2.dp.toPx(), labelH + 1.dp.toPx()),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                    // 2) 외곽 순백색 헤일로(Halo) 테두리 (도로선·행정경계 점선이 겹쳐도 텍스트 박스가 선명하게 부각됨)
                    drawRoundRect(
                        color = Color.White,
                        topLeft = Offset(labelTopLeft.x - haloExpand, labelTopLeft.y - haloExpand),
                        size = Size(labelW + haloExpand * 2f, labelH + haloExpand * 2f),
                        cornerRadius = CornerRadius(8.dp.toPx(), 8.dp.toPx())
                    )
                    // 3) 고대비 불투명 배경 채우기
                    drawRoundRect(
                        color = boxBgColor,
                        topLeft = labelTopLeft,
                        size = Size(labelW, labelH),
                        cornerRadius = boxCorner
                    )
                    // 4) 굵은 컬러 강조 테두리 (2.4dp)
                    drawRoundRect(
                        color = borderColor,
                        topLeft = labelTopLeft,
                        size = Size(labelW, labelH),
                        cornerRadius = boxCorner,
                        style = Stroke(width = 2.4.dp.toPx())
                    )
                    // 5) 굵고 선명한 지점명 텍스트
                    drawText(
                        textLayoutResult = labelLayout,
                        topLeft = Offset(labelTopLeft.x + padH, labelTopLeft.y + padV)
                    )
                }
            }
        }

        // 좌측 하단: 레이어 스위치 바 ([구글 한국지도] | [위성+지명] | [지역경계선])
        Surface(
            shape = AppShapes.pill,
            color = Color(0xFF0F172A).copy(alpha = 0.90f),
            border = BorderStroke(1.dp, Color(0xFF334155)),
            shadowElevation = 4.dp,
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(start = 10.dp, bottom = 10.dp)
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(3.dp)
            ) {
                if (useRealMapTiles) {
                    Surface(
                        shape = AppShapes.pill,
                        color = if (tileStyle == "road") Color(0xFF2563EB) else Color.Transparent,
                        modifier = Modifier.clickable { tileStyle = "road" }
                    ) {
                        Text(
                            text = "구글지도",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (tileStyle == "road") PureWhite else Color(0xFFCBD5E1),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                    Surface(
                        shape = AppShapes.pill,
                        color = if (tileStyle == "sat") Color(0xFF2563EB) else Color.Transparent,
                        modifier = Modifier.clickable { tileStyle = "sat" }
                    ) {
                        Text(
                            text = "위성",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (tileStyle == "sat") PureWhite else Color(0xFFCBD5E1),
                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(2.dp))
                }
                Surface(
                    shape = AppShapes.pill,
                    color = if (showDistrictBorders) Color(0xFF059669) else Color.Transparent,
                    modifier = Modifier.clickable { showDistrictBorders = !showDistrictBorders }
                ) {
                    Text(
                        text = if (showDistrictBorders) "지역경계 ON" else "지역경계 OFF",
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (showDistrictBorders) PureWhite else Color(0xFF94A3B8),
                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                    )
                }
            }
        }

        // 우측 중앙: 확대(+) / 축소(-) / 경로 맞춤 버튼
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 10.dp)
        ) {
            SmallFloatingActionButton(
                onClick = { zoomLevel = (zoomLevel + 0.85f).coerceAtMost(17.8f) },
                containerColor = Color.White.copy(alpha = 0.95f),
                contentColor = Color(0xFF0F172A),
                shape = CircleShape,
                modifier = Modifier.size(34.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = "확대", modifier = Modifier.size(17.dp))
            }
            Spacer(modifier = Modifier.height(6.dp))
            SmallFloatingActionButton(
                onClick = { zoomLevel = (zoomLevel - 0.85f).coerceAtLeast(7.5f) },
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
                    val (center, z) = WebMercator.computeAutoFitCamera(validSteps, null)
                    centerLat = center.first
                    centerLng = center.second
                    zoomLevel = z
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
                .padding(start = 10.dp, bottom = 44.dp)
                .fillMaxWidth(0.66f)
        ) {
            selectedStep?.let { step ->
                Surface(
                    color = Color.White.copy(alpha = 0.97f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFF3B82F6)),
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
                                text = DailyRouteAggregator.resolveStepDisplayPlaceName(step),
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

/**
 * 동일하거나 인접한 좌표(예: 집 출발 핀 1과 귀가 도착 핀 4, 또는 영등포 하차 핀 2와 승차 핀 3)가
 * 서로 겹치지 않도록 좌상단(라벨 위)/우하단(라벨 아래)으로 대각선 분리하여 배치합니다.
 */
private fun computePinPlacements(
    steps: List<RouteStep>,
    centerLat: Double,
    centerLng: Double,
    zoom: Float,
    w: Float,
    h: Float,
    baseTilePx: Float,
    displayMode: MapPointDisplayMode = MapPointDisplayMode.FULL
): List<PinPlacement> {
    val rawPoints = steps.map { step ->
        WebMercator.toScreenOffset(
            lat = step.latitude!!,
            lng = step.longitude!!,
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            screenW = w,
            screenH = h,
            baseTilePx = baseTilePx
        )
    }
    if (steps.size <= 1) {
        return steps.mapIndexed { idx, s ->
            PinPlacement(s, idx, rawPoints[idx], rawPoints[idx], labelAbove = false, isClustered = false)
        }
    }

    val clusterRadiusPx = when (displayMode) {
        MapPointDisplayMode.DOT_ONLY -> 14f
        MapPointDisplayMode.COMPACT -> 26f
        else -> 42f
    }
    val spreadX = when (displayMode) {
        MapPointDisplayMode.DOT_ONLY -> 8f
        MapPointDisplayMode.COMPACT -> 15f
        else -> 24f
    }
    val spreadY = when (displayMode) {
        MapPointDisplayMode.DOT_ONLY -> 5f
        MapPointDisplayMode.COMPACT -> 7f
        else -> 11f
    }
    val result = ArrayList<PinPlacement>(steps.size)

    for (i in steps.indices) {
        val ptI = rawPoints[i]
        val group = mutableListOf<Int>()
        for (j in steps.indices) {
            if ((rawPoints[j] - ptI).getDistance() < clusterRadiusPx) {
                group.add(j)
            }
        }
        if (group.size <= 1) {
            result.add(
                PinPlacement(
                    step = steps[i],
                    index = i,
                    rawPoint = ptI,
                    displayPoint = ptI,
                    labelAbove = false,
                    isClustered = false
                )
            )
        } else {
            val posInGroup = group.indexOf(i)
            val total = group.size
            // 짝수 순번은 위쪽+좌측(라벨 위), 홀수 순번은 아래쪽+우측(라벨 아래)으로 분리해 캡션 충돌 원천 방지
            val offsetX = (posInGroup - (total - 1) / 2f) * spreadX
            val isUpper = posInGroup % 2 == 0
            val offsetY = if (isUpper) -spreadY else spreadY
            result.add(
                PinPlacement(
                    step = steps[i],
                    index = i,
                    rawPoint = ptI,
                    displayPoint = Offset(ptI.x + offsetX, ptI.y + offsetY),
                    labelAbove = isUpper,
                    isClustered = true
                )
            )
        }
    }
    return result
}

/**
 * 시·군·구 지역 구분 경계선 및 자치구/시 이름 오버레이 렌더링
 */
private fun DrawScope.drawDistrictBoundariesOverlay(
    w: Float,
    h: Float,
    centerLat: Double,
    centerLng: Double,
    zoom: Float,
    baseTilePx: Float,
    textMeasurer: TextMeasurer,
    isOverTileMap: Boolean,
    isSatellite: Boolean,
    avoidRects: List<androidx.compose.ui.geometry.Rect> = emptyList()
) {
    fun pt(lat: Double, lng: Double): Offset =
        WebMercator.toScreenOffset(lat, lng, centerLat, centerLng, zoom, w, h, baseTilePx)

    val dashEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 6f), 0f)

    MidKoreaBounds.DISTRICT_BOUNDARIES.forEach { district ->
        if (district.polygon.size >= 3) {
            val screenPts = district.polygon.map { (lat, lng) -> pt(lat, lng) }
            // 화면 밖 폴리곤 스킵
            val minX = screenPts.minOf { it.x }
            val maxX = screenPts.maxOf { it.x }
            val minY = screenPts.minOf { it.y }
            val maxY = screenPts.maxOf { it.y }
            if (maxX < -50f || minX > w + 50f || maxY < -50f || minY > h + 50f) return@forEach

            val polyPath = Path().apply {
                moveTo(screenPts.first().x, screenPts.first().y)
                for (i in 1 until screenPts.size) {
                    lineTo(screenPts[i].x, screenPts[i].y)
                }
                close()
            }

            val isSeoul = district.regionGroup == "SEOUL"
            val borderColor = when {
                isSatellite -> if (isSeoul) Color(0xFFFCA5A5) else Color(0xFF93C5FD)
                isOverTileMap -> if (isSeoul) Color(0xFFDC2626).copy(alpha = 0.72f) else Color(0xFF334155).copy(alpha = 0.72f)
                else -> if (isSeoul) Color(0xFFDC2626).copy(alpha = 0.55f) else Color(0xFF64748B).copy(alpha = 0.70f)
            }

            if (isOverTileMap) {
                // 타일 지도 위에서 경계선이 뚜렷하게 보이도록 얇은 화이트 케이싱 후 점선 표시
                drawPath(
                    path = polyPath,
                    color = Color.White.copy(alpha = 0.55f),
                    style = Stroke(width = 2.8.dp.toPx())
                )
            }
            drawPath(
                path = polyPath,
                color = borderColor,
                style = Stroke(width = 1.5.dp.toPx(), pathEffect = dashEffect)
            )
        }
    }

    // 서울특별시-경기도 광역 경계선 강조
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
        color = if (isSatellite) Color(0xFFFef08a).copy(alpha = 0.85f) else Color(0xFFB91C1C).copy(alpha = 0.65f),
        style = Stroke(width = 2.2.dp.toPx())
    )

    // 계층형 시·군·구 및 동 라벨 렌더링 (거점 네모 텍스트 박스와 겹치는 배경 지명은 자동 숨김)
    val placedRects = mutableListOf<androidx.compose.ui.geometry.Rect>().apply {
        addAll(avoidRects)
    }
    MidKoreaBounds.ANCHORS.sortedBy { it.tier }.forEach { anchor ->
        val shouldShow = when (anchor.tier) {
            1 -> zoom <= 12.8f
            2 -> zoom in 10.0f..15.2f
            3 -> zoom >= 11.4f
            else -> false
        }
        if (!shouldShow) return@forEach

        val screenPt = pt(anchor.lat, anchor.lng)
        if (screenPt.x < 20f || screenPt.x > w - 20f || screenPt.y < 20f || screenPt.y > h - 20f) {
            return@forEach
        }

        val fontSize = when (anchor.tier) {
            1 -> 11.sp
            2 -> 10.sp
            else -> 9.sp
        }
        val textColor = when (anchor.tier) {
            1 -> Color(0xFF0F172A)
            2 -> Color(0xFF1E3A8A)
            else -> Color(0xFF334155)
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

        if (placedRects.any { it.overlaps(candidateRect) }) {
            return@forEach
        }
        placedRects.add(candidateRect)

        drawRoundRect(
            color = Color.White.copy(alpha = if (isOverTileMap) 0.84f else 0.80f),
            topLeft = Offset(topLeft.x - 4f, topLeft.y - 1.5f),
            size = Size(layout.size.width + 8f, layout.size.height + 3f),
            cornerRadius = CornerRadius(4f, 4f)
        )
        drawText(textLayoutResult = layout, topLeft = topLeft)
    }
}

/**
 * 구글 지도 스타일의 행정구역 다각형(시·군·구), 한강/탄천 수계, 고속도로망 베이스 렌더링
 */
private fun DrawScope.drawGoogleStyleKoreaBaseMap(
    w: Float,
    h: Float,
    centerLat: Double,
    centerLng: Double,
    zoom: Float,
    baseTilePx: Float,
    textMeasurer: TextMeasurer,
    drawLabelsAndBorders: Boolean,
    avoidRects: List<androidx.compose.ui.geometry.Rect> = emptyList()
) {
    fun pt(lat: Double, lng: Double): Offset =
        WebMercator.toScreenOffset(lat, lng, centerLat, centerLng, zoom, w, h, baseTilePx)

    // 1. 행정구역(시·군·구) 폴리곤 채우기
    MidKoreaBounds.DISTRICT_BOUNDARIES.forEach { district ->
        if (district.polygon.size >= 3) {
            val screenPts = district.polygon.map { (lat, lng) -> pt(lat, lng) }
            val polyPath = Path().apply {
                moveTo(screenPts.first().x, screenPts.first().y)
                for (i in 1 until screenPts.size) {
                    lineTo(screenPts[i].x, screenPts[i].y)
                }
                close()
            }

            val fillColor = when (district.regionGroup) {
                "SEOUL" -> Color(0xFFF8FAFC)
                "GYEONGGI_EAST" -> Color(0xFFF1F5F9)
                "GYEONGGI_SOUTH" -> Color(0xFFF4F6F9)
                "INCHEON" -> Color(0xFFEFF6FF)
                else -> Color(0xFFF3F4F6)
            }
            drawPath(path = polyPath, color = fillColor, style = Fill)
        }
    }

    // 2. 한강 본류 및 주요 지류(탄천, 안양천) 수계 렌더링
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
        style = Stroke(width = 10.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round)
    )

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

    // 3. 주요 간선도로·고속도로망
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

    drawExpressway(
        listOf(
            37.575 to 126.815, 37.540 to 126.895, 37.515 to 126.940, 37.508 to 126.995,
            37.518 to 127.060, 37.522 to 127.105, 37.550 to 127.155, 37.560 to 127.205
        )
    )
    drawExpressway(
        listOf(
            37.610 to 127.145, 37.555 to 127.175, 37.515 to 127.185, 37.482 to 127.138,
            37.445 to 127.135, 37.405 to 127.102, 37.395 to 127.025, 37.380 to 126.940,
            37.450 to 126.780, 37.550 to 126.750
        )
    )
    drawExpressway(
        listOf(
            37.530 to 127.012, 37.495 to 127.020, 37.460 to 127.042, 37.395 to 127.102,
            37.270 to 127.105, 37.150 to 127.095, 36.815 to 127.150
        )
    )
    drawExpressway(
        listOf(
            37.513 to 127.100, 37.485 to 127.120, 37.478 to 127.145, 37.445 to 127.140, 37.385 to 127.118
        )
    )

    if (drawLabelsAndBorders) {
        drawDistrictBoundariesOverlay(
            w = w,
            h = h,
            centerLat = centerLat,
            centerLng = centerLng,
            zoom = zoom,
            baseTilePx = baseTilePx,
            textMeasurer = textMeasurer,
            isOverTileMap = false,
            isSatellite = false,
            avoidRects = avoidRects
        )
    }
}
