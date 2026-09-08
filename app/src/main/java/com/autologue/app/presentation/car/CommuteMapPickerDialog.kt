package com.autologue.app.presentation.car

import android.annotation.SuppressLint
import android.content.Context
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autologue.app.data.preferences.CommuteConfig
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.*

enum class CommutePickerTab {
    HOME, COMPANY, ROUTE
}

data class CommuteLocationSuggestion(
    val name: String,
    val address: String,
    val latitude: Double,
    val longitude: Double
)

@Composable
fun CommuteMapPickerDialog(
    config: CommuteConfig,
    onDismiss: () -> Unit,
    onSave: (
        homeName: String,
        homeAddr: String,
        homeLat: Double,
        homeLng: Double,
        compName: String,
        compAddr: String,
        compLat: Double,
        compLng: Double,
        roundTripKm: Double,
        carBt: String
    ) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var activeTab by remember { mutableStateOf(CommutePickerTab.HOME) }

    // 기본 거점 좌표: 기존 설정 또는 수도권 기준 (집: 방이동 37.5145, 127.1060 / 회사: 판교 37.4020, 127.1086)
    var homeName by remember(config) { mutableStateOf(if (config.homeName.isNotBlank()) config.homeName else "우리집") }
    var homeAddress by remember(config) { mutableStateOf(config.homeAddress) }
    var homeLat by remember(config) { mutableDoubleStateOf(if (config.homeLat != 0.0) config.homeLat else 37.5145) }
    var homeLng by remember(config) { mutableDoubleStateOf(if (config.homeLng != 0.0) config.homeLng else 127.1060) }

    var compName by remember(config) { mutableStateOf(if (config.companyName.isNotBlank()) config.companyName else "회사") }
    var compAddress by remember(config) { mutableStateOf(config.companyAddress) }
    var compLat by remember(config) { mutableDoubleStateOf(if (config.companyLat != 0.0) config.companyLat else 37.4020) }
    var compLng by remember(config) { mutableDoubleStateOf(if (config.companyLng != 0.0) config.companyLng else 127.1086) }

    var distanceText by remember(config) {
        val initialDist = if (config.commuteRoundTripKm > 0) {
            config.commuteRoundTripKm
        } else {
            calculateRoundTripDistanceKm(homeLat, homeLng, compLat, compLng)
        }
        mutableStateOf("%.1f".format(Locale.US, initialDist))
    }
    var carBtDevice by remember(config) { mutableStateOf(config.carBluetoothDevice) }

    // 지도 카메라 중심 좌표
    var mapCenterLat by remember { mutableDoubleStateOf((homeLat + compLat) / 2.0) }
    var mapCenterLng by remember { mutableDoubleStateOf((homeLng + compLng) / 2.0) }
    var zoomLevel by remember { mutableFloatStateOf(1.0f) }

    fun updateCalculatedDistance() {
        val calculated = calculateRoundTripDistanceKm(homeLat, homeLng, compLat, compLng)
        distanceText = "%.1f".format(Locale.US, calculated)
    }

    // 구글 지도 실시간 장소/주소 검색 상태
    var homeSearchQuery by remember { mutableStateOf(homeAddress) }
    var compSearchQuery by remember { mutableStateOf(compAddress) }
    var homeSuggestions by remember { mutableStateOf<List<CommuteLocationSuggestion>>(emptyList()) }
    var compSuggestions by remember { mutableStateOf<List<CommuteLocationSuggestion>>(emptyList()) }
    var showHomeSuggestions by remember { mutableStateOf(false) }
    var showCompSuggestions by remember { mutableStateOf(false) }

    // 집 위치 실시간 Geocoder 검색
    LaunchedEffect(homeSearchQuery) {
        val q = homeSearchQuery.trim()
        if (q.length >= 2 && q != homeAddress) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (Geocoder.isPresent()) {
                        val geocoder = Geocoder(context, Locale.KOREA)
                        @Suppress("DEPRECATION")
                        val addrs = geocoder.getFromLocationName(q, 5)
                        if (!addrs.isNullOrEmpty()) {
                            val results = addrs.mapNotNull { addr ->
                                val fullAddr = addr.getAddressLine(0) ?: ""
                                val cleanAddr = fullAddr
                                    .replace("대한민국 ", "")
                                    .replace(Regex("\\bKR\\b"), "")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                                val placeName = when {
                                    !addr.featureName.isNullOrBlank() && !addr.featureName.matches(Regex("^[0-9\\-]+$")) -> addr.featureName
                                    cleanAddr.isNotBlank() -> cleanAddr.split(" ").takeLast(2).joinToString(" ")
                                    else -> q
                                }
                                CommuteLocationSuggestion(
                                    name = placeName,
                                    address = cleanAddr.ifBlank { fullAddr },
                                    latitude = addr.latitude,
                                    longitude = addr.longitude
                                )
                            }.distinctBy { "${it.latitude},${it.longitude}" }
                            withContext(Dispatchers.Main) {
                                homeSuggestions = results
                                showHomeSuggestions = results.isNotEmpty()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                showHomeSuggestions = false
                            }
                        }
                    }
                }
            }
        } else {
            showHomeSuggestions = false
        }
    }

    // 회사 위치 실시간 Geocoder 검색
    LaunchedEffect(compSearchQuery) {
        val q = compSearchQuery.trim()
        if (q.length >= 2 && q != compAddress) {
            withContext(Dispatchers.IO) {
                runCatching {
                    if (Geocoder.isPresent()) {
                        val geocoder = Geocoder(context, Locale.KOREA)
                        @Suppress("DEPRECATION")
                        val addrs = geocoder.getFromLocationName(q, 5)
                        if (!addrs.isNullOrEmpty()) {
                            val results = addrs.mapNotNull { addr ->
                                val fullAddr = addr.getAddressLine(0) ?: ""
                                val cleanAddr = fullAddr
                                    .replace("대한민국 ", "")
                                    .replace(Regex("\\bKR\\b"), "")
                                    .replace(Regex("\\s+"), " ")
                                    .trim()
                                val placeName = when {
                                    !addr.featureName.isNullOrBlank() && !addr.featureName.matches(Regex("^[0-9\\-]+$")) -> addr.featureName
                                    cleanAddr.isNotBlank() -> cleanAddr.split(" ").takeLast(2).joinToString(" ")
                                    else -> q
                                }
                                CommuteLocationSuggestion(
                                    name = placeName,
                                    address = cleanAddr.ifBlank { fullAddr },
                                    latitude = addr.latitude,
                                    longitude = addr.longitude
                                )
                            }.distinctBy { "${it.latitude},${it.longitude}" }
                            withContext(Dispatchers.Main) {
                                compSuggestions = results
                                showCompSuggestions = results.isNotEmpty()
                            }
                        } else {
                            withContext(Dispatchers.Main) {
                                showCompSuggestions = false
                            }
                        }
                    }
                }
            }
        } else {
            showCompSuggestions = false
        }
    }

    fun selectHomeLocation(suggestion: CommuteLocationSuggestion) {
        homeLat = suggestion.latitude
        homeLng = suggestion.longitude
        homeAddress = suggestion.address
        if (homeName.isBlank() || homeName == "우리집") {
            homeName = suggestion.name
        }
        homeSearchQuery = suggestion.name
        mapCenterLat = suggestion.latitude
        mapCenterLng = suggestion.longitude
        showHomeSuggestions = false
        updateCalculatedDistance()
    }

    fun selectCompLocation(suggestion: CommuteLocationSuggestion) {
        compLat = suggestion.latitude
        compLng = suggestion.longitude
        compAddress = suggestion.address
        if (compName.isBlank() || compName == "회사") {
            compName = suggestion.name
        }
        compSearchQuery = suggestion.name
        mapCenterLat = suggestion.latitude
        mapCenterLng = suggestion.longitude
        showCompSuggestions = false
        updateCalculatedDistance()
    }

    // 역지오코딩: 위경도로부터 도로명/지번 주소 자동 변환
    fun reverseGeocode(lat: Double, lng: Double, onResult: (String) -> Unit) {
        scope.launch(Dispatchers.IO) {
            try {
                val geocoder = Geocoder(context, Locale.KOREA)
                @Suppress("DEPRECATION")
                val addresses = geocoder.getFromLocation(lat, lng, 1)
                val addressText = addresses?.firstOrNull()?.let { addr ->
                    val line = addr.getAddressLine(0) ?: ""
                    line.replace("대한민국 ", "").trim()
                } ?: ""
                withContext(Dispatchers.Main) {
                    if (addressText.isNotBlank()) {
                        onResult(addressText)
                    }
                }
            } catch (e: Exception) {
                // 오프라인/네트워크 부재 시 무시
            }
        }
    }

    // GPS 현재 위치 가져오기
    fun fetchCurrentLocationToTab() {
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var bestLoc: Location? = null
            for (p in providers) {
                if (locationManager.isProviderEnabled(p)) {
                    @SuppressLint("MissingPermission")
                    val loc = locationManager.getLastKnownLocation(p)
                    if (loc != null && (bestLoc == null || loc.accuracy < bestLoc.accuracy)) {
                        bestLoc = loc
                    }
                }
            }

            bestLoc?.let { loc ->
                val lat = loc.latitude
                val lng = loc.longitude
                if (activeTab == CommutePickerTab.HOME) {
                    homeLat = lat
                    homeLng = lng
                    reverseGeocode(lat, lng) { 
                        homeAddress = it
                        homeSearchQuery = it
                    }
                } else if (activeTab == CommutePickerTab.COMPANY) {
                    compLat = lat
                    compLng = lng
                    reverseGeocode(lat, lng) { 
                        compAddress = it
                        compSearchQuery = it
                    }
                }
                mapCenterLat = lat
                mapCenterLng = lng
                updateCalculatedDistance()
            }
        } catch (e: Exception) {
            // 권한 미부여 시 무시
        }
    }

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
                shape = RoundedCornerShape(16.dp),
                color = AppColors.surface,
                shadowElevation = 12.dp,
                modifier = Modifier
                    .fillMaxWidth(0.95f)
                    .fillMaxHeight(0.92f)
            ) {
            Column(modifier = Modifier.fillMaxSize().padding(Spacing.md)) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = MenuColors.carLedger,
                            modifier = Modifier.size(26.dp)
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        Column {
                            Text(
                                text = "출퇴근 지도 거점 및 경로 설정",
                                style = AppTypography.h2.copy(fontSize = 17.sp)
                            )
                            Text(
                                text = "지도를 탭하거나 드래그하여 거점을 이동하세요",
                                style = AppTypography.captionMuted
                            )
                        }
                    }

                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "닫기", tint = Slate500)
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xs))

                // Tab Selector
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Slate100, RoundedCornerShape(8.dp))
                        .padding(4.dp),
                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    TabButton(
                        text = "1. 우리집 위치 (🏠)",
                        selected = activeTab == CommutePickerTab.HOME,
                        color = MenuColors.carLedger,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activeTab = CommutePickerTab.HOME
                            mapCenterLat = homeLat
                            mapCenterLng = homeLng
                        }
                    )
                    TabButton(
                        text = "2. 회사 위치 (🏢)",
                        selected = activeTab == CommutePickerTab.COMPANY,
                        color = MenuColors.diary,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activeTab = CommutePickerTab.COMPANY
                            mapCenterLat = compLat
                            mapCenterLng = compLng
                        }
                    )
                    TabButton(
                        text = "3. 경로·거리 (🚗)",
                        selected = activeTab == CommutePickerTab.ROUTE,
                        color = MenuColors.golf,
                        modifier = Modifier.weight(1f),
                        onClick = {
                            activeTab = CommutePickerTab.ROUTE
                            mapCenterLat = (homeLat + compLat) / 2.0
                            mapCenterLng = (homeLng + compLng) / 2.0
                            updateCalculatedDistance()
                        }
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.xs))

                // Standalone 100% Offline Interactive Canvas Map
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color(0xFFF1F5F9))
                        .border(1.dp, Slate300, RoundedCornerShape(12.dp))
                ) {
                    Canvas(
                        modifier = Modifier
                            .fillMaxSize()
                            .clipToBounds()
                            .pointerInput(activeTab) {
                                detectTapGestures { offset ->
                                    val (tapLat, tapLng) = pixelToLatLng(
                                        pixelX = offset.x,
                                        pixelY = offset.y,
                                        width = size.width.toFloat(),
                                        height = size.height.toFloat(),
                                        centerLat = mapCenterLat,
                                        centerLng = mapCenterLng,
                                        zoom = zoomLevel
                                    )
                                    if (activeTab == CommutePickerTab.HOME) {
                                        homeLat = tapLat
                                        homeLng = tapLng
                                        reverseGeocode(tapLat, tapLng) { 
                                            homeAddress = it
                                            homeSearchQuery = it
                                        }
                                    } else if (activeTab == CommutePickerTab.COMPANY) {
                                        compLat = tapLat
                                        compLng = tapLng
                                        reverseGeocode(tapLat, tapLng) { 
                                            compAddress = it
                                            compSearchQuery = it
                                        }
                                    }
                                    updateCalculatedDistance()
                                }
                            }
                            .pointerInput(Unit) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    // 드래그로 지도 팬(Pan) 이동
                                    val latDelta = (dragAmount.y / 1500f) * (0.15 / zoomLevel)
                                    val lngDelta = (-dragAmount.x / 1500f) * (0.15 / zoomLevel)
                                    mapCenterLat += latDelta
                                    mapCenterLng += lngDelta
                                }
                            }
                    ) {
                        drawGridMap(
                            centerLat = mapCenterLat,
                            centerLng = mapCenterLng,
                            zoom = zoomLevel,
                            homeLat = homeLat,
                            homeLng = homeLng,
                            compLat = compLat,
                            compLng = compLng,
                            activeTab = activeTab
                        )
                    }

                    // Map Overlay Zoom & Reset Controls
                    Column(
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        SmallIconButton(icon = Icons.Default.Add, contentDescription = "확대") {
                            zoomLevel = (zoomLevel * 1.3f).coerceAtMost(4.0f)
                        }
                        SmallIconButton(icon = Icons.Default.Remove, contentDescription = "축소") {
                            zoomLevel = (zoomLevel / 1.3f).coerceAtLeast(0.5f)
                        }
                        SmallIconButton(icon = Icons.Default.CenterFocusStrong, contentDescription = "경로 맞춤") {
                            mapCenterLat = (homeLat + compLat) / 2.0
                            mapCenterLng = (homeLng + compLng) / 2.0
                            zoomLevel = 1.0f
                        }
                    }

                    // Floating GPS Button
                    SmallFloatingActionButton(
                        onClick = { fetchCurrentLocationToTab() },
                        containerColor = AppColors.surface,
                        contentColor = MenuColors.carLedger,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(12.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.MyLocation, contentDescription = "현재 위치", modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("현재 위치로", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold))
                        }
                    }

                    // Map Status Guide Label
                    Surface(
                        color = Color(0xCC0F172A),
                        shape = RoundedCornerShape(6.dp),
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(10.dp)
                    ) {
                        Text(
                            text = if (activeTab == CommutePickerTab.HOME) "🏠 집 핀 위치 선택 중 (지도를 탭하세요)"
                            else if (activeTab == CommutePickerTab.COMPANY) "🏢 회사 핀 위치 선택 중 (지도를 탭하세요)"
                            else "🚗 경로 및 예상 거리 확인 중",
                            style = AppTypography.caption.copy(color = Color.White, fontSize = 11.sp),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Bottom Content / Inputs based on Tab
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                ) {
                    when (activeTab) {
                        CommutePickerTab.HOME -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🏠 우리집 설정 (지도 탭 또는 구글지도 검색)", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = MenuColors.carLedger))
                                Text("위도: %.4f, 경도: %.4f".format(homeLat, homeLng), style = AppTypography.captionMuted)
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            // [구글지도 실시간 장소/주소 검색창] 골프장 검색 스타일 1:1 탑재
                            OutlinedTextField(
                                value = homeSearchQuery,
                                onValueChange = { homeSearchQuery = it },
                                label = { Text("우리집 위치 / 구글 지도 검색") },
                                placeholder = { Text("장소명이나 주소 검색 (예: 잠실 롯데타워, 방이동)") },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = MenuColors.carLedger)
                                },
                                trailingIcon = {
                                    if (homeSearchQuery.isNotBlank()) {
                                        IconButton(onClick = {
                                            homeSearchQuery = ""
                                            showHomeSuggestions = false
                                        }) {
                                            Icon(imageVector = Icons.Default.Clear, contentDescription = "지우기", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 위치 추천 캡슐 바 (골프장 검색과 100% 동일한 구글 캘린더 스타일)
                            if (showHomeSuggestions && homeSuggestions.isNotEmpty()) {
                                LocationSuggestionView(
                                    suggestions = homeSuggestions,
                                    accentColor = MenuColors.carLedger,
                                    onSelect = { selectHomeLocation(it) }
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                AutoLogueTextField(
                                    value = homeName,
                                    onValueChange = { homeName = it },
                                    label = "집 거점 명칭",
                                    modifier = Modifier.weight(1f)
                                )
                                AutoLogueTextField(
                                    value = homeAddress,
                                    onValueChange = { homeAddress = it },
                                    label = "집 주소 (선택/수동)",
                                    modifier = Modifier.weight(1.5f)
                                )
                            }
                        }
                        CommutePickerTab.COMPANY -> {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("🏢 직장/회사 설정 (지도 탭 또는 구글지도 검색)", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = MenuColors.diary))
                                Text("위도: %.4f, 경도: %.4f".format(compLat, compLng), style = AppTypography.captionMuted)
                            }
                            Spacer(modifier = Modifier.height(4.dp))

                            // [구글지도 실시간 장소/주소 검색창] 골프장 검색 스타일 1:1 탑재
                            OutlinedTextField(
                                value = compSearchQuery,
                                onValueChange = { compSearchQuery = it },
                                label = { Text("회사 위치 / 구글 지도 검색") },
                                placeholder = { Text("회사명이나 주소 검색 (예: 판교역, 테헤란로 152)") },
                                leadingIcon = {
                                    Icon(imageVector = Icons.Default.Search, contentDescription = null, tint = MenuColors.diary)
                                },
                                trailingIcon = {
                                    if (compSearchQuery.isNotBlank()) {
                                        IconButton(onClick = {
                                            compSearchQuery = ""
                                            showCompSuggestions = false
                                        }) {
                                            Icon(imageVector = Icons.Default.Clear, contentDescription = "지우기", modifier = Modifier.size(16.dp))
                                        }
                                    }
                                },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )

                            // 위치 추천 캡슐 바 (골프장 검색과 100% 동일한 구글 캘린더 스타일)
                            if (showCompSuggestions && compSuggestions.isNotEmpty()) {
                                LocationSuggestionView(
                                    suggestions = compSuggestions,
                                    accentColor = MenuColors.diary,
                                    onSelect = { selectCompLocation(it) }
                                )
                            }

                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                AutoLogueTextField(
                                    value = compName,
                                    onValueChange = { compName = it },
                                    label = "회사 거점 명칭",
                                    modifier = Modifier.weight(1f)
                                )
                                AutoLogueTextField(
                                    value = compAddress,
                                    onValueChange = { compAddress = it },
                                    label = "회사 주소 (선택/수동)",
                                    modifier = Modifier.weight(1.5f)
                                )
                            }
                        }
                        CommutePickerTab.ROUTE -> {
                            Text("🚗 출퇴근 경로 거리 및 자동 감지 옵션", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = MenuColors.golf))
                            Spacer(modifier = Modifier.height(4.dp))
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                AutoLogueTextField(
                                    value = distanceText,
                                    onValueChange = { distanceText = it },
                                    label = "왕복 기준 거리 (km)",
                                    placeholder = "30.0",
                                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                                    modifier = Modifier.weight(1f)
                                )
                                AutoLogueTextField(
                                    value = carBtDevice,
                                    onValueChange = { carBtDevice = it },
                                    label = "차량 블루투스 이름 (선택)",
                                    placeholder = "예: Genesis, K5_BT 등",
                                    modifier = Modifier.weight(1.5f)
                                )
                            }
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "💡 지도의 두 거점 좌표를 기반으로 도로 굴곡률(1.25배)이 반영된 왕복 거리가 자동 산출되었습니다. 차량 블루투스 이름을 입력해 두시면 내 차에 탔을 때 출퇴근 주행이 100% 자동 판별됩니다.",
                                style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // Action Buttons
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AutoLogueSecondaryButton(
                            text = "취소",
                            onClick = onDismiss
                        )
                        Spacer(modifier = Modifier.width(Spacing.sm))
                        AutoLoguePrimaryButton(
                            text = "저장 및 적용",
                            onClick = {
                                val dist = distanceText.toDoubleOrNull() ?: calculateRoundTripDistanceKm(homeLat, homeLng, compLat, compLng)
                                onSave(
                                    homeName.trim().ifBlank { "우리집" },
                                    homeAddress.trim(),
                                    homeLat,
                                    homeLng,
                                    compName.trim().ifBlank { "회사" },
                                    compAddress.trim(),
                                    compLat,
                                    compLng,
                                    dist,
                                    carBtDevice.trim()
                                )
                            }
                        )
                    }
                }
            }
        }
    }
}
}

@Composable
private fun SmallIconButton(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    contentDescription: String,
    onClick: () -> Unit
) {
    Surface(
        color = Color.White,
        shape = CircleShape,
        shadowElevation = 4.dp,
        modifier = Modifier.size(32.dp).clickable(onClick = onClick)
    ) {
        Box(contentAlignment = Alignment.Center) {
            Icon(imageVector = icon, contentDescription = contentDescription, tint = Slate700, modifier = Modifier.size(18.dp))
        }
    }
}

@Composable
private fun TabButton(
    text: String,
    selected: Boolean,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (selected) color else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            style = AppTypography.caption.copy(
                fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                color = if (selected) Color.White else Slate700,
                fontSize = 11.sp
            ),
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 픽셀 좌표 -> 위경도 변환 유틸리티
 */
fun pixelToLatLng(
    pixelX: Float,
    pixelY: Float,
    width: Float,
    height: Float,
    centerLat: Double,
    centerLng: Double,
    zoom: Float
): Pair<Double, Double> {
    val scale = 2200f * zoom
    val deltaX = pixelX - (width / 2f)
    val deltaY = pixelY - (height / 2f)

    val lng = centerLng + (deltaX / scale)
    val lat = centerLat - (deltaY / scale)
    return Pair(lat, lng)
}

/**
 * 위경도 -> 픽셀 좌표 변환 유틸리티
 */
fun latLngToPixel(
    lat: Double,
    lng: Double,
    width: Float,
    height: Float,
    centerLat: Double,
    centerLng: Double,
    zoom: Float
): Offset {
    val scale = 2200f * zoom
    val x = (width / 2f) + ((lng - centerLng) * scale).toFloat()
    val y = (height / 2f) - ((lat - centerLat) * scale).toFloat()
    return Offset(x, y)
}

/**
 * 100% 무설치 고성능 네이티브 Canvas 벡터 지도 렌더러
 */
fun DrawScope.drawGridMap(
    centerLat: Double,
    centerLng: Double,
    zoom: Float,
    homeLat: Double,
    homeLng: Double,
    compLat: Double,
    compLng: Double,
    activeTab: CommutePickerTab
) {
    // [AP-COMPOSE-CANVAS-OVERFLOW-NOCLIP 방어] 캔버스 영역 밖으로 선/동심원이 침범하지 않도록 clipRect 강제
    clipRect {
        val w = size.width
        val h = size.height

        // 1. 그리드 및 주요 도로망 시뮬레이션 라인 그리기
        val gridColor = Color(0xFFE2E8F0)
        val mainRoadColor = Color(0xFFCBD5E1)
        val riverColor = Color(0xFFBFDBFE)

        // 가로/세로 좌표 격자선
        val gridStep = 60f * zoom
        val startX = (w / 2f) % gridStep
        val startY = (h / 2f) % gridStep

        var currX = startX
        while (currX < w) {
            drawLine(color = gridColor, start = Offset(currX, 0f), end = Offset(currX, h), strokeWidth = 1f)
            currX += gridStep
        }

        var currY = startY
        while (currY < h) {
            drawLine(color = gridColor, start = Offset(0f, currY), end = Offset(w, currY), strokeWidth = 1f)
            currY += gridStep
        }

        // 도심 한강/간선도로 실루엣 곡선
        val riverPath = Path().apply {
            moveTo(0f, h * 0.42f)
            quadraticBezierTo(w * 0.4f, h * 0.35f, w * 0.7f, h * 0.48f)
            quadraticBezierTo(w * 0.85f, h * 0.52f, w, h * 0.45f)
        }
        drawPath(path = riverPath, color = riverColor, style = Stroke(width = 14f))

        // 주요 도로망 가이드
        val roadPath = Path().apply {
            moveTo(w * 0.2f, 0f)
            quadraticBezierTo(w * 0.35f, h * 0.5f, w * 0.8f, h)
        }
        drawPath(path = roadPath, color = mainRoadColor, style = Stroke(width = 4f))

        // 2. 집과 회사 픽셀 좌표 계산
        val homeOffset = latLngToPixel(homeLat, homeLng, w, h, centerLat, centerLng, zoom)
        val compOffset = latLngToPixel(compLat, compLng, w, h, centerLat, centerLng, zoom)

        // 3. 두 거점 간의 주행 연결 경로(점선 도로)
        val routePath = Path().apply {
            moveTo(homeOffset.x, homeOffset.y)
            // 자연스러운 도로 곡선 경로
            val midX = (homeOffset.x + compOffset.x) / 2f + 30f
            val midY = (homeOffset.y + compOffset.y) / 2f - 20f
            quadraticBezierTo(midX, midY, compOffset.x, compOffset.y)
        }
        drawPath(
            path = routePath,
            color = Color(0xFF15803D), // Forest Green
            style = Stroke(
                width = 5f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 0f)
            )
        )

        // 4. 마커 그리기 (홈 마커: 앰버 골드, 회사 마커: 로열 블루)
        // 홈 마커 그림자 및 펄스
        drawCircle(color = Color(0x33D97706), radius = 26f, center = homeOffset)
        drawCircle(color = Color(0xFFD97706), radius = 18f, center = homeOffset)
        drawCircle(color = Color.White, radius = 7f, center = homeOffset)

        // 회사 마커 그림자 및 펄스
        drawCircle(color = Color(0x332563EB), radius = 26f, center = compOffset)
        drawCircle(color = Color(0xFF2563EB), radius = 18f, center = compOffset)
        drawCircle(color = Color.White, radius = 7f, center = compOffset)

        // 현재 활성 탭에 따른 포커스 링
        val focusOffset = if (activeTab == CommutePickerTab.HOME) homeOffset else if (activeTab == CommutePickerTab.COMPANY) compOffset else null
        focusOffset?.let {
            drawCircle(
                color = if (activeTab == CommutePickerTab.HOME) Color(0xFFD97706) else Color(0xFF2563EB),
                radius = 34f,
                center = it,
                style = Stroke(width = 3f, pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 6f), 0f))
            )
        }
    }
}

/**
 * Haversine 공식을 사용한 구면 거리 계산 + 한국 도로 굴곡도 계수(1.25배) 반영
 */
fun calculateRoundTripDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    if (lat1 == 0.0 || lon1 == 0.0 || lat2 == 0.0 || lon2 == 0.0) return 0.0
    val r = 6371.0 // 지구 반지름 (km)
    val dLat = Math.toRadians(lat2 - lat1)
    val dLon = Math.toRadians(lon2 - lon1)
    val a = sin(dLat / 2).pow(2.0) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
            sin(dLon / 2).pow(2.0)
    val c = 2 * atan2(sqrt(a), sqrt(1 - a))
    val straightDistanceKm = r * c
    val roadCurveFactor = 1.25 // 직선 대비 도로 실제 주행거리 계수
    val oneWayKm = straightDistanceKm * roadCurveFactor
    return (oneWayKm * 2.0).coerceAtLeast(1.0)
}

/**
 * 구글 캘린더 스타일의 위치 추천 캡슐 바 (골프장 검색과 100% 동일한 패밀리룩 UI)
 */
@Composable
private fun LocationSuggestionView(
    suggestions: List<CommuteLocationSuggestion>,
    accentColor: Color,
    onSelect: (CommuteLocationSuggestion) -> Unit
) {
    if (suggestions.isEmpty()) return
    val main = suggestions.first()
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        // 메인 추천 캡슐 바
        Surface(
            shape = RoundedCornerShape(24.dp),
            color = Color(0xFFF1F5F9),
            border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
            modifier = Modifier
                .fillMaxWidth()
                .clickable { onSelect(main) }
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 9.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .clip(CircleShape)
                        .background(accentColor.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Place,
                        contentDescription = null,
                        tint = accentColor,
                        modifier = Modifier.size(16.dp)
                    )
                }
                Spacer(modifier = Modifier.width(10.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = main.name,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF0F172A)
                    )
                    Text(
                        text = main.address,
                        fontSize = 10.sp,
                        color = Color(0xFF64748B),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = accentColor.copy(alpha = 0.12f)
                ) {
                    Text(
                        text = "선택",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = accentColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
            }
        }

        // 2순위 이상 서브 추천 목록 (가로 스크롤 칩)
        if (suggestions.size > 1) {
            Spacer(modifier = Modifier.height(6.dp))
            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                items(suggestions.drop(1)) { subItem ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                        modifier = Modifier.clickable { onSelect(subItem) }
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = accentColor,
                                modifier = Modifier.size(12.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = subItem.name,
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = Color(0xFF334155)
                            )
                        }
                    }
                }
            }
        }
    }
}


