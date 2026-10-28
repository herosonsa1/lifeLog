package com.autologue.app.presentation.diary.map

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import coil.compose.AsyncImage
import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.data.sync.PlaceResolver
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.model.estimateRealisticCommuteMinutes
import com.autologue.app.domain.model.estimateRealisticNonCommuteMinutes
import com.autologue.app.domain.model.extractDrivingDurationMinutesFromText
import com.autologue.app.domain.model.formatDrivingDurationLabel
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.buildHighlightedDurationText
import com.autologue.app.presentation.common.LocalEasyMode
import com.autologue.app.presentation.diary.MapPeriodFilter
import com.autologue.app.presentation.theme.*
import com.autologue.app.util.LocationDistanceUtils
import kotlinx.coroutines.delay
import java.time.format.DateTimeFormatter
import java.util.*

enum class MapListSheetMode(val label: String) {
    LIST_EXPANDED("목록 크게"),
    SPLIT("반반"),
    MAP_EXPANDED("지도 크게")
}

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
    val totalDistanceKm: Double = 0.0,
    val durationMinutes: Int? = null,
    val isCommuteTrip: Boolean = false
)

fun isStopoverStep(step: RouteStep): Boolean {
    return DailyRouteAggregator.isStopoverWaypointStep(step)
}

fun isDepartureStep(step: RouteStep): Boolean {
    if (isStopoverStep(step)) return false
    return step.tags.any { it.contains("출발") || it.contains("승차") } ||
        step.title.contains("출발") || step.title.contains("승차")
}

fun isArrivalStep(step: RouteStep): Boolean {
    if (isStopoverStep(step) || isDepartureStep(step)) return false
    return step.tags.any { it.contains("도착") || it.contains("하차") } ||
        step.title.contains("도착") || step.title.contains("하차")
}

/**
 * 집(거주지: 하남시 학암동/위례동/수서/더샵 등) 텍스트 또는 좌표(반경 2.3km) 여부 판별
 */
fun isHomePlaceOrCoord(text: String, lat: Double? = null, lng: Double? = null): Boolean {
    if (text.contains("7101동") || text.contains("학암") || text.contains("위례") || text.contains("우리집") ||
        text.contains("수서") || text.contains("더샵") || text.contains("광평로") ||
        text.contains("자곡") || text.contains("세곡") ||
        Regex("(^|[^가-힣a-zA-Z0-9])집($|[^가-힣a-zA-Z0-9])").containsMatchIn(text)
    ) {
        return true
    }
    if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
        val distWirye = LocationDistanceUtils.calculateStraightDistanceKm(lat, lng, 37.4785, 127.1465)
        val distSuseo = LocationDistanceUtils.calculateStraightDistanceKm(lat, lng, 37.4847, 127.0945)
        if (distWirye <= 2.3 || distSuseo <= 2.3) return true
    }
    return false
}

/**
 * 직장(회사: 영등포구 영등포동/여의도/문래/당산 등) 텍스트 또는 좌표(반경 2.5km) 여부 판별
 */
fun isOfficePlaceOrCoord(text: String, lat: Double? = null, lng: Double? = null): Boolean {
    if (text.contains("영등포") || text.contains("여의도") || text.contains("문래") ||
        text.contains("당산") || text.contains("회사") || text.contains("직장") || text.contains("사옥")
    ) {
        return true
    }
    if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
        val distYdp = LocationDistanceUtils.calculateStraightDistanceKm(lat, lng, 37.5172, 126.9068)
        val distYeouido = LocationDistanceUtils.calculateStraightDistanceKm(lat, lng, 37.5219, 126.9245)
        if (distYdp <= 2.5 || distYeouido <= 2.5) return true
    }
    return false
}

/**
 * 개별 RouteStep이 출퇴근(출근/퇴근/왕복) 주행 지점인지 판별합니다.
 * - 제3의 외출 목적지(강남, 분당, 춘천 등)로 향하는 주행은 출퇴근에서 제외하여 보존합니다.
 */
fun isCommuteStep(step: RouteStep, contextSteps: List<RouteStep> = emptyList()): Boolean {
    val fullText = buildString {
        append(step.title).append(" ")
        if (!step.description.isNullOrBlank()) append(step.description).append(" ")
        if (!step.locationName.isNullOrBlank()) append(step.locationName).append(" ")
        if (!step.address.isNullOrBlank()) append(step.address).append(" ")
        append(step.tags.joinToString(" "))
    }
    if (fullText.contains("출근") || fullText.contains("퇴근") || fullText.contains("출퇴근") ||
        fullText.contains("집->회사") || fullText.contains("회사->집") ||
        fullText.contains("집 ➔ 회사") || fullText.contains("회사 ➔ 집")
    ) {
        return true
    }
    if (step.stepType != RouteStepType.DRIVING) return false

    // 1. 설명/제목에 화살표(➔ 또는 ->)가 있는 경우 출발지와 도착지가 [집 <-> 회사] 조합인지 직접 확인
    val routeSource = step.description?.takeIf { it.contains("➔") || it.contains("->") }
        ?: step.title.takeIf { it.contains("➔") || it.contains("->") }
    if (routeSource != null) {
        val delimiter = if (routeSource.contains("➔")) "➔" else "->"
        val startPart = routeSource.substringBefore(delimiter)
        val endPart = routeSource.substringAfter(delimiter).substringBefore("(")
        val startIsHome = isHomePlaceOrCoord(startPart)
        val startIsOffice = isOfficePlaceOrCoord(startPart)
        val endIsHome = isHomePlaceOrCoord(endPart)
        val endIsOffice = isOfficePlaceOrCoord(endPart)
        if ((startIsHome && endIsOffice) || (startIsOffice && endIsHome)) {
            return true
        }
        // 화살표 양쪽이 명확히 다른 제3의 장소(예: 학암동 ➔ 강남구 역삼동)인 경우 출퇴근이 아님
        if (startPart.isNotBlank() && endPart.isNotBlank() && !endIsOffice && !startIsOffice) {
            return false
        }
    }

    val selfIsHome = isHomePlaceOrCoord(fullText, step.latitude, step.longitude)
    val selfIsOffice = isOfficePlaceOrCoord(fullText, step.latitude, step.longitude)
    if (!selfIsHome && !selfIsOffice) return false

    // 2. 동일 일자의 주행 컨텍스트에서 집<->회사 이동 세션인지 판별
    val sameDayDriving = contextSteps.filter {
        it.stepType == RouteStepType.DRIVING && it.time.toLocalDate() == step.time.toLocalDate()
    }
    if (sameDayDriving.isNotEmpty()) {
        val nearbySteps = sameDayDriving.filter {
            it.id != step.id && java.time.Duration.between(it.time, step.time).abs().toMinutes() <= 240L
        }
        if (nearbySteps.isNotEmpty()) {
            val pairedWithOffice = selfIsHome && nearbySteps.any {
                val t = listOfNotNull(it.title, it.description, it.locationName, it.address).joinToString(" ")
                isOfficePlaceOrCoord(t, it.latitude, it.longitude)
            }
            val pairedWithHome = selfIsOffice && nearbySteps.any {
                val t = listOfNotNull(it.title, it.description, it.locationName, it.address).joinToString(" ")
                isHomePlaceOrCoord(t, it.latitude, it.longitude)
            }
            if (pairedWithOffice || pairedWithHome) return true

            val pairedWithNonCommute = nearbySteps.any {
                val t = listOfNotNull(it.title, it.description, it.locationName, it.address).joinToString(" ")
                !isHomePlaceOrCoord(t, it.latitude, it.longitude) && !isOfficePlaceOrCoord(t, it.latitude, it.longitude)
            }
            if (pairedWithNonCommute) return false
        }

        val dayHasOffice = sameDayDriving.any {
            val t = listOfNotNull(it.title, it.description, it.locationName, it.address).joinToString(" ")
            isOfficePlaceOrCoord(t, it.latitude, it.longitude)
        }
        val dayAllHomeOrOffice = sameDayDriving.all {
            val t = listOfNotNull(it.title, it.description, it.locationName, it.address).joinToString(" ")
            isHomePlaceOrCoord(t, it.latitude, it.longitude) || isOfficePlaceOrCoord(t, it.latitude, it.longitude)
        }
        if (dayHasOffice && dayAllHomeOrOffice) return true
    }

    return selfIsOffice && com.autologue.app.util.KoreanHolidayUtils.isCommuteEligibleWorkday(step.time.toLocalDate())
}

/**
 * 전체 거점 리스트에서 출퇴근 경로에 해당하는 지점들을 제외한 리스트를 반환합니다.
 */
fun filterOutCommuteSteps(steps: List<RouteStep>): List<RouteStep> {
    if (steps.isEmpty()) return emptyList()
    return steps.filterNot { isCommuteStep(it, steps) }
}

fun segmentRouteIntoTrips(steps: List<RouteStep>): List<RouteTripGroup> {
    val consolidated = DailyRouteAggregator.consolidateGpsDrivingSteps(steps)
    val valid = consolidated.filter { it.latitude != null && it.longitude != null }.sortedBy { it.time }
    if (valid.isEmpty()) return emptyList()

    val groups = mutableListOf<RouteTripGroup>()
    var currentGroupSteps = mutableListOf<RouteStep>()

    for (step in valid) {
        val lastStep = currentGroupSteps.lastOrNull()
        val isDifferentDay = lastStep != null && lastStep.time.toLocalDate() != step.time.toLocalDate()
        val minutesGap = if (lastStep != null) {
            java.time.Duration.between(lastStep.time, step.time).abs().toMinutes()
        } else 0L

        val isNewDeparture = isDepartureStep(step)
        val isCurrentStopover = isStopoverStep(step)
        val isCurrentArrival = isArrivalStep(step)
        val isLastDriveAnchor = lastStep != null && (isDepartureStep(lastStep) || isStopoverStep(lastStep))

        // 출발(승차) ➔ (1시간 이내 동일 위치 경유지) ➔ 도착(하차)으로 이어지는 연속 주행 세션은
        // 하나의 주행 트립으로 유지하고 중간에서 쪼개지지 않도록 보장
        val isPairedDriveSession = isLastDriveAnchor && (isCurrentArrival || isCurrentStopover) && !isDifferentDay && minutesGap <= 300L
        val isTimeGap = lastStep != null && !isPairedDriveSession && minutesGap > 60L

        if (currentGroupSteps.isNotEmpty() &&
            (isDifferentDay || isTimeGap || (isNewDeparture && lastStep != null && isArrivalStep(lastStep)))
        ) {
            groups.add(createTripGroup(currentGroupSteps))
            currentGroupSteps = mutableListOf()
        }

        currentGroupSteps.add(step)

        if (isCurrentArrival) {
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

    val firstText = listOfNotNull(first.title, first.description, first.locationName, first.address).joinToString(" ")
    val lastText = listOfNotNull(last.title, last.description, last.locationName, last.address).joinToString(" ")
    val firstIsHome = isHomePlaceOrCoord(firstText, first.latitude, first.longitude)
    val firstIsOffice = isOfficePlaceOrCoord(firstText, first.latitude, first.longitude)
    val lastIsHome = isHomePlaceOrCoord(lastText, last.latitude, last.longitude)
    val lastIsOffice = isOfficePlaceOrCoord(lastText, last.latitude, last.longitude)

    val isCommuteToWork =
        steps.any { (it.title.contains("회사") || it.title.contains("출근")) && isArrivalStep(it) } ||
            (firstIsHome && lastIsOffice) ||
            (steps.size == 1 && firstIsOffice && first.time.hour < 12)
    val isCommuteToHome =
        steps.any { (it.title.contains("집") || it.title.contains("퇴근")) && isArrivalStep(it) } ||
            (firstIsOffice && lastIsHome)
    val isCommuteGroup = isCommuteToWork || isCommuteToHome || steps.all { isCommuteStep(it, steps) }

    val tripName = when {
        isCommuteToWork -> "출근 주행"
        isCommuteToHome -> "퇴근/귀가"
        first.time.hour < 11 -> "오전 주행"
        first.time.hour < 17 -> "오후 이동"
        else -> "야간 주행"
    }

    val distKm = LocationDistanceUtils.calculateRouteDrivingDistanceKm(steps)
    val startPlace = DailyRouteAggregator.resolveStepDisplayPlaceName(first)
    val endPlace = DailyRouteAggregator.resolveStepDisplayPlaceName(last)
    val stopoverSteps = steps.filter { isStopoverStep(it) }
    val explicitDuration = steps.asReversed().firstNotNullOfOrNull {
        if (isArrivalStep(it)) {
            extractDrivingDurationMinutesFromText(it.description) ?: extractDrivingDurationMinutesFromText(it.title)
        } else null
    }
    val spanMinutes = if (steps.size >= 2) {
        java.time.Duration.between(first.time, last.time).toMinutes().toInt().takeIf { it in 1..600 }
    } else null
    val tripDurationMin = explicitDuration ?: spanMinutes

    val subtitle = when {
        stopoverSteps.size == 1 && steps.size >= 3 -> {
            val stopPlace = DailyRouteAggregator.resolveStepDisplayPlaceName(stopoverSteps.first())
            "$startPlace ➔ $stopPlace(경유) ➔ $endPlace"
        }
        stopoverSteps.size >= 2 && steps.size >= 3 -> "$startPlace ➔ 경유 ${stopoverSteps.size}곳 ➔ $endPlace"
        steps.size > 1 -> "$startPlace ➔ $endPlace"
        else -> startPlace
    }
    val timeStr = first.time.format(DateTimeFormatter.ofPattern("M/d a h:mm", Locale.KOREAN))
    val title = "$timeStr $tripName"

    return RouteTripGroup(
        id = "${date}_${first.time}_${steps.size}_${first.id}",
        title = title,
        subtitle = subtitle,
        brandEmoji = brandEmoji,
        date = date,
        steps = steps,
        totalDistanceKm = distKm,
        durationMinutes = tripDurationMin,
        isCommuteTrip = isCommuteGroup
    )
}

/**
 * 경로 열기 시 왕복 동선(집 출발 ➔ 목적지 ➔ 집 도착)으로 인해
 * 출발지(first)와 최종 도착지(last)가 동일 구역(반경 400m 이내)인 경우,
 * 가장 멀리 이동한 주요 방문 거점을 목적지(destination)로 자동 선택하여
 * 지도 앱에서 "출발지와 도착지가 같음 / 경로를 찾을 수 없음" 오류가 발생하지 않도록 보정합니다.
 */
fun resolveEffectiveRouteEndpoints(steps: List<RouteStep>): Pair<RouteStep, RouteStep>? {
    val validSteps = steps.filter {
        it.latitude != null && it.longitude != null &&
            (it.latitude != 0.0 || it.longitude != 0.0)
    }
    if (validSteps.size < 2) return null

    val startStep = validSteps.first()
    val rawEndStep = validSteps.last()

    val startToLastKm = LocationDistanceUtils.calculateStraightDistanceKm(
        startStep.latitude!!, startStep.longitude!!,
        rawEndStep.latitude!!, rawEndStep.longitude!!
    )

    // 출발지와 마지막 지점이 400m 이상 떨어져 있으면 정상 편도/이동 경로
    if (startToLastKm >= 0.4) {
        return startStep to rawEndStep
    }

    // 출발지와 마지막 지점이 400m 이내(예: 집 출발 -> 직장/방문지 -> 집 귀가 왕복 동선)인 경우,
    // 출발지로부터 가장 멀리 떨어진 핵심 방문 지점을 도착지로 선정
    val furthestStep = validSteps.maxByOrNull { step ->
        LocationDistanceUtils.calculateStraightDistanceKm(
            startStep.latitude, startStep.longitude,
            step.latitude!!, step.longitude!!
        )
    } ?: rawEndStep

    val maxDistKm = LocationDistanceUtils.calculateStraightDistanceKm(
        startStep.latitude, startStep.longitude,
        furthestStep.latitude!!, furthestStep.longitude!!
    )

    // 모든 지점이 반경 200m 이내(실제 이동 없이 한 장소에 머문 경우)이면 null 반환 -> 단일 위치 핀 열기로 유도
    if (maxDistKm < 0.2) {
        return null
    }

    return startStep to furthestStep
}

/**
 * GPS 좌표가 누락(null 또는 0.0)된 주행/방문 거점이 있더라도
 * 장소명·주소·제목의 한국 지역 키워드를 기반으로 좌표를 보강하여 지도에서 거점이 누락되지 않도록 합니다.
 */
fun ensureStepCoordinates(step: RouteStep): RouteStep {
    val lat = step.latitude
    val lng = step.longitude
    if (lat != null && lng != null && (lat != 0.0 || lng != 0.0)) {
        return step
    }

    val text = listOfNotNull(step.locationName, step.address, step.title).joinToString(" ")
    val inferred: Pair<Double, Double>? = when {
        text.contains("7101동") || text.contains("위례") || text.contains("학암") -> 37.4785 to 127.1465
        text.contains("수서") || text.contains("더샵") || text.contains("광평로") || text.contains("자곡") || text.contains("세곡") -> 37.4847 to 127.0945
        text.contains("집") -> 37.4785 to 127.1465
        text.contains("영등포") || text.contains("문래") || text.contains("당산") -> 37.5172 to 126.9068
        text.contains("여의도") -> 37.5219 to 126.9245
        text.contains("감일") || text.contains("하남") || text.contains("미사") -> 37.5393 to 127.2149
        text.contains("송파") || text.contains("잠실") || text.contains("문정") || text.contains("가락") -> 37.5145 to 127.1060
        text.contains("강남") || text.contains("역삼") || text.contains("삼성") || text.contains("테헤란") || text.contains("청담") || text.contains("논현") || text.contains("대치") || text.contains("개포") || text.contains("일원") -> 37.5085 to 127.0550
        text.contains("서초") || text.contains("양재") || text.contains("반포") || text.contains("방배") -> 37.4837 to 127.0324
        text.contains("강동") || text.contains("천호") || text.contains("고덕") -> 37.5301 to 127.1238
        text.contains("성남") || text.contains("분당") || text.contains("판교") || text.contains("정자") || text.contains("수정구") || text.contains("중원구") -> 37.4020 to 127.1086
        text.contains("용인") || text.contains("수지") || text.contains("기흥") || text.contains("처인") -> 37.2747 to 127.1320
        text.contains("수원") || text.contains("광교") || text.contains("영통") -> 37.2636 to 127.0286
        text.contains("마포") || text.contains("상암") || text.contains("홍대") || text.contains("합정") -> 37.5663 to 126.9019
        text.contains("용산") || text.contains("이태원") || text.contains("한남") -> 37.5326 to 126.9900
        text.contains("성동") || text.contains("성수") || text.contains("왕십리") -> 37.5446 to 127.0560
        text.contains("광진") || text.contains("건대") || text.contains("구의") -> 37.5385 to 127.0823
        text.contains("종로") || text.contains("광화문") || text.contains("중구") || text.contains("명동") || text.contains("을지로") -> 37.5665 to 126.9780
        text.contains("강서") || text.contains("마곡") || text.contains("김포공항") -> 37.5509 to 126.8495
        text.contains("양천") || text.contains("목동") -> 37.5169 to 126.8664
        text.contains("구로") || text.contains("가산") || text.contains("금천") || text.contains("신도림") -> 37.4954 to 126.8874
        text.contains("동작") || text.contains("사당") || text.contains("관악") -> 37.4950 to 126.9530
        text.contains("과천") -> 37.4292 to 126.9876
        text.contains("안양") || text.contains("평촌") || text.contains("군포") || text.contains("의왕") -> 37.3943 to 126.9568
        text.contains("광명") -> 37.4786 to 126.8646
        text.contains("부천") -> 37.5034 to 126.7660
        text.contains("고양") || text.contains("일산") -> 37.6584 to 126.8320
        text.contains("인천") || text.contains("송도") || text.contains("청라") || text.contains("영종") -> 37.4563 to 126.7052
        text.contains("화성") || text.contains("동탄") -> 37.2006 to 127.0970
        text.contains("여주") -> 37.2983 to 127.6374
        text.contains("이천") -> 37.2720 to 127.4350
        text.contains("광주") && !text.contains("광역시") -> 37.4095 to 127.2550
        text.contains("춘천") || text.contains("홍천") -> 37.8813 to 127.7298
        text.contains("원주") -> 37.3422 to 127.9202
        else -> null
    }

    return if (inferred != null) {
        step.copy(latitude = inferred.first, longitude = inferred.second)
    } else {
        step
    }
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
    val density = LocalDensity.current
    val isEasyMode = LocalEasyMode.current

    val nonTransactionSteps = remember(routeSteps) {
        routeSteps.filter {
            it.stepType != RouteStepType.TRANSACTION &&
                !it.title.contains("입금") && !it.title.contains("출금") &&
                !it.title.contains("이체") && !it.title.contains("송금")
        }
    }
    val validCoordinateSteps = remember(nonTransactionSteps) {
        nonTransactionSteps
            .map { ensureStepCoordinates(it) }
            .map { DailyRouteAggregator.normalizeStepForDisplay(it) }
            .let { DailyRouteAggregator.consolidateGpsDrivingSteps(it) }
            .let { DailyRouteAggregator.enrichStepPairDurations(it) }
            .filter { it.latitude != null && it.longitude != null && (it.latitude != 0.0 || it.longitude != 0.0) }
    }

    var isCommuteExcluded by remember { mutableStateOf(false) }
    var pointDisplayMode by remember { mutableStateOf(MapPointDisplayMode.AUTO) }

    val commuteStepCount = remember(validCoordinateSteps) {
        validCoordinateSteps.count { isCommuteStep(it, validCoordinateSteps) }
    }
    val filteredCoordinateSteps = remember(validCoordinateSteps, isCommuteExcluded) {
        if (isCommuteExcluded) {
            filterOutCommuteSteps(validCoordinateSteps)
        } else {
            validCoordinateSteps
        }
    }

    var mapFocusStep by remember(filteredCoordinateSteps) {
        val candidate = selectedStep?.takeIf { s -> filteredCoordinateSteps.any { it.id == s.id } }
        mutableStateOf<RouteStep?>(candidate ?: filteredCoordinateSteps.firstOrNull())
    }
    var isSingleStepZoomed by remember(filteredCoordinateSteps, periodFilter, isCommuteExcluded) {
        mutableStateOf(false)
    }
    var selectedTripGroupId by remember { mutableStateOf<String?>(null) }
    var isOnlineMapMode by remember { mutableStateOf(true) }
    // 기본값: 지도와 하단 이동 지점 리스트를 동시에 볼 수 있는 균형 분할(SPLIT)
    var sheetMode by remember { mutableStateOf(MapListSheetMode.SPLIT) }
    var customMapHeightDp by remember { mutableStateOf<Float?>(null) }
    val listState = rememberLazyListState()

    val tripGroups = remember(filteredCoordinateSteps) { segmentRouteIntoTrips(filteredCoordinateSteps) }
    val selectedTripGroup = remember(tripGroups, selectedTripGroupId) {
        tripGroups.firstOrNull { it.id == selectedTripGroupId }
    }
    val activeDisplaySteps = remember(filteredCoordinateSteps, selectedTripGroup) {
        selectedTripGroup?.steps ?: filteredCoordinateSteps
    }

    // GPS 주행 스텝은 [최초 출발지], [1시간 이내 동일 위치 재출발 경유지], [최종 도착지], [단독 주행 거점]을 표시하고
    // 중간 주행 GPS 샘플은 전면 배제합니다.
    val bottomDisplaySteps = remember(activeDisplaySteps) {
        val keySteps = activeDisplaySteps.filter { step ->
            step.stepType != RouteStepType.DRIVING ||
                isDepartureStep(step) ||
                isStopoverStep(step) ||
                isArrivalStep(step) ||
                !DailyRouteAggregator.isIntermediateDrivingStep(step)
        }
        if (keySteps.isNotEmpty()) {
            keySteps.sortedBy { it.time }
        } else {
            activeDisplaySteps.filterNot { DailyRouteAggregator.isIntermediateDrivingStep(it) }.sortedBy { it.time }
        }
    }

    val effectivePointDisplayMode = remember(pointDisplayMode, bottomDisplaySteps.size) {
        resolveEffectivePointDisplayMode(pointDisplayMode, bottomDisplaySteps.size)
    }

    LaunchedEffect(periodFilter, isCommuteExcluded) {
        selectedTripGroupId = null
        isSingleStepZoomed = false
    }

    Column(modifier = Modifier.fillMaxSize().background(AppColors.background)) {
        // 1. 상단 기간 필터 + (쉬운모드에서는 출퇴근 제외 통합) + 외부 지도 열기 버튼
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.md, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically,
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
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp),
                            fontSize = 11.5.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) PureWhite else AppColors.textSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                if (isEasyMode) {
                    val commuteBtnText = when {
                        isCommuteExcluded && commuteStepCount > 0 -> "출퇴근 제외됨 ($commuteStepCount)"
                        isCommuteExcluded -> "출퇴근 제외됨"
                        commuteStepCount > 0 -> "출퇴근 제외 ($commuteStepCount)"
                        else -> "출퇴근 제외"
                    }
                    Surface(
                        shape = AppShapes.pill,
                        color = if (isCommuteExcluded) Color(0xFF1D4ED8) else Color(0xFFF1F5F9),
                        border = BorderStroke(
                            width = 1.dp,
                            color = if (isCommuteExcluded) Color(0xFF1D4ED8) else Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.clickable { isCommuteExcluded = !isCommuteExcluded }
                    ) {
                        Text(
                            text = commuteBtnText,
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isCommuteExcluded) PureWhite else Color(0xFF334155),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }
            }

            if (bottomDisplaySteps.isNotEmpty()) {
                IconButton(
                    onClick = {
                        if (bottomDisplaySteps.size > 1) {
                            openGoogleMapsRoute(context, bottomDisplaySteps)
                        } else {
                            val step = bottomDisplaySteps.first()
                            openGoogleMapsLocation(
                                context = context,
                                latitude = step.latitude!!,
                                longitude = step.longitude!!,
                                label = step.locationName ?: step.title,
                                address = step.address
                            )
                        }
                    },
                    modifier = Modifier.size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Map,
                        contentDescription = "지도 앱에서 전체 이동 경로 열기",
                        tint = AppColors.primary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        // 1-1. 고급모드 전용: 출퇴근 경로 제외 토글 + 지점 표시 모드 스위치
        if (!isEasyMode) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.md, vertical = 3.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Surface(
                    shape = AppShapes.pill,
                    color = if (isCommuteExcluded) Color(0xFF1D4ED8) else Color(0xFFF1F5F9),
                    border = BorderStroke(
                        width = 1.dp,
                        color = if (isCommuteExcluded) Color(0xFF1D4ED8) else Color(0xFFCBD5E1)
                    ),
                    modifier = Modifier.clickable { isCommuteExcluded = !isCommuteExcluded }
                ) {
                    val commuteBtnText = when {
                        isCommuteExcluded && commuteStepCount > 0 -> "출퇴근 제외됨 ($commuteStepCount)"
                        isCommuteExcluded -> "출퇴근 제외됨"
                        commuteStepCount > 0 -> "출퇴근 제외 ($commuteStepCount)"
                        else -> "출퇴근 제외"
                    }
                    Text(
                        text = commuteBtnText,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isCommuteExcluded) PureWhite else Color(0xFF334155),
                        maxLines = 1,
                        softWrap = false,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                    )
                }

                Spacer(modifier = Modifier.width(6.dp))

                Surface(
                    shape = AppShapes.pill,
                    color = Color(0xFFF1F5F9),
                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                    modifier = Modifier.weight(1f, fill = false)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier
                            .horizontalScroll(rememberScrollState())
                            .padding(2.dp)
                    ) {
                        MapPointDisplayMode.values().forEach { mode ->
                            val isModeSelected = pointDisplayMode == mode
                            val labelText = if (mode == MapPointDisplayMode.AUTO && isModeSelected) {
                                when (effectivePointDisplayMode) {
                                    MapPointDisplayMode.DOT_ONLY -> "자동·점만"
                                    MapPointDisplayMode.COMPACT -> "자동·작게"
                                    else -> "자동·기본"
                                }
                            } else {
                                mode.title
                            }
                            Surface(
                                shape = AppShapes.pill,
                                color = if (isModeSelected) Color(0xFF0F172A) else Color.Transparent,
                                modifier = Modifier.clickable { pointDisplayMode = mode }
                            ) {
                                Text(
                                    text = labelText,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isModeSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isModeSelected) PureWhite else Color(0xFF475569),
                                    maxLines = 1,
                                    softWrap = false,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // 1-2. 운행 세션(Trip) 세그먼트 칩 바
        if (tripGroups.size > 1) {
            Surface(
                color = Color(0xFFF8FAFC),
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .horizontalScroll(rememberScrollState())
                        .padding(horizontal = Spacing.md, vertical = 5.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isAllSelected = selectedTripGroupId == null
                    val totalPointsCount = filteredCoordinateSteps.count {
                        it.stepType != RouteStepType.DRIVING || isDepartureStep(it) || isStopoverStep(it) || isArrivalStep(it)
                    }
                    Surface(
                        shape = AppShapes.pill,
                        color = if (isAllSelected) AppColors.primary else PureWhite,
                        border = BorderStroke(
                            1.dp,
                            if (isAllSelected) AppColors.primary else Color(0xFFCBD5E1)
                        ),
                        modifier = Modifier.clickable {
                            selectedTripGroupId = null
                            mapFocusStep = null
                        }
                    ) {
                        Text(
                            text = "전체 동선 (${totalPointsCount}곳)",
                            fontSize = 11.sp,
                            fontWeight = if (isAllSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isAllSelected) PureWhite else Color(0xFF334155),
                            maxLines = 1,
                            softWrap = false,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }

                    tripGroups.forEach { group ->
                        val isSelected = selectedTripGroupId == group.id
                        Surface(
                            shape = AppShapes.pill,
                            color = if (isSelected) AppColors.primary else PureWhite,
                            border = BorderStroke(
                                1.dp,
                                if (isSelected) AppColors.primary else Color(0xFFCBD5E1)
                            ),
                            modifier = Modifier.clickable {
                                selectedTripGroupId = group.id
                                mapFocusStep = null
                            }
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text(
                                    text = "${group.brandEmoji} ${group.title}",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PureWhite else Color(0xFF1E293B),
                                    maxLines = 1,
                                    softWrap = false
                                )
                            }
                        }
                    }
                }
            }
        }

        HairlineDivider()

        // 2 & 3. 적응형 분할 영역 (지도 + 드래그/원터치 분할 컨트롤 바 + 하단 이동 지점 리스트)
        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
        ) {
            val totalBodyDp = maxHeight
            val handleBarDp = 42.dp
            val splitMapDp = (totalBodyDp * 0.42f).coerceIn(144.dp, 232.dp)
            val expandedMapDp = (totalBodyDp - handleBarDp - 128.dp).coerceAtLeast(splitMapDp)
            val maxDragMapDp = (totalBodyDp - handleBarDp - 88.dp).coerceAtLeast(140.dp)

            val presetMapDp = when (sheetMode) {
                MapListSheetMode.LIST_EXPANDED -> 0.dp
                MapListSheetMode.SPLIT -> splitMapDp
                MapListSheetMode.MAP_EXPANDED -> expandedMapDp
            }
            val rawTargetDp = customMapHeightDp?.dp?.coerceIn(0.dp, maxDragMapDp) ?: presetMapDp
            val animatedMapHeight by animateDpAsState(
                targetValue = rawTargetDp,
                animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
                label = "adaptiveMapHeight"
            )
            val currentMapHeight = if (customMapHeightDp != null) rawTargetDp else animatedMapHeight

            val listScrollConnection = remember(sheetMode, customMapHeightDp, splitMapDp) {
                object : NestedScrollConnection {
                    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                        if (source == NestedScrollSource.Drag && available.y < -18f) {
                            if (sheetMode == MapListSheetMode.MAP_EXPANDED ||
                                (customMapHeightDp != null && customMapHeightDp!! > splitMapDp.value + 24f)
                            ) {
                                customMapHeightDp = null
                                sheetMode = MapListSheetMode.SPLIT
                            }
                        }
                        return Offset.Zero
                    }
                }
            }

            Column(modifier = Modifier.fillMaxSize()) {
                // 2. 인터랙티브 지도 영역 (목록 크게 모드일 때는 0dp로 접혀 리스트 전체 확인 가능)
                if (currentMapHeight > 4.dp) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(currentMapHeight)
                            .clipToBounds()
                            .background(Color(0xFFEEF2F6))
                    ) {
                        if (bottomDisplaySteps.isNotEmpty()) {
                            MidKoreaStaticMapView(
                                steps = bottomDisplaySteps,
                                focusedStep = mapFocusStep,
                                zoomToFocusedStep = isSingleStepZoomed,
                                pointDisplayMode = pointDisplayMode,
                                onStepClick = { step ->
                                    mapFocusStep = step
                                    isSingleStepZoomed = true
                                    onStepSelected(step)
                                },
                                useRealMapTiles = isOnlineMapMode,
                                modifier = Modifier.fillMaxSize()
                            )

                            // 좌측 상단: 현재 포커스 지점 / 동선 배지 (말줄임 및 최대 폭 제한으로 우측 스위치와 충돌 방지)
                            Surface(
                                shape = AppShapes.pill,
                                color = Color(0xFF0F172A).copy(alpha = 0.88f),
                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .widthIn(max = 220.dp)
                                    .align(Alignment.TopStart)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp)
                                ) {
                                    val badgeText = when {
                                        mapFocusStep != null -> DailyRouteAggregator.resolveStepDisplayPlaceName(mapFocusStep!!)
                                        selectedTripGroup != null -> selectedTripGroup.subtitle
                                        else -> "이동 동선 ${bottomDisplaySteps.size}곳"
                                    }
                                    Text(
                                        text = badgeText,
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFFBAE6FD),
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        modifier = Modifier.weight(1f, fill = false)
                                    )
                                    if (mapFocusStep != null || selectedTripGroup != null) {
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Surface(
                                            shape = RoundedCornerShape(8.dp),
                                            color = Color(0xFF2563EB),
                                            modifier = Modifier.clickable {
                                                if (mapFocusStep != null) {
                                                    mapFocusStep = null
                                                    isSingleStepZoomed = false
                                                } else {
                                                    selectedTripGroupId = null
                                                    isSingleStepZoomed = false
                                                }
                                            }
                                        ) {
                                            Text(
                                                text = "전체",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PureWhite,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 우측 상단: [상세지도 | 벡터] 간결 전환 스위치
                            Surface(
                                shape = AppShapes.pill,
                                color = Color(0xFF0F172A).copy(alpha = 0.88f),
                                border = BorderStroke(1.dp, Color(0xFF334155)),
                                modifier = Modifier
                                    .padding(8.dp)
                                    .align(Alignment.TopEnd)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(2.5.dp)
                                ) {
                                    Surface(
                                        shape = AppShapes.pill,
                                        color = if (isOnlineMapMode) Color(0xFF2563EB) else Color.Transparent,
                                        modifier = Modifier.clickable { isOnlineMapMode = true }
                                    ) {
                                        Text(
                                            text = "상세지도",
                                            fontSize = 10.5.sp,
                                            fontWeight = if (isOnlineMapMode) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isOnlineMapMode) PureWhite else Color(0xFFCBD5E1),
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Surface(
                                        shape = AppShapes.pill,
                                        color = if (!isOnlineMapMode) Color(0xFF2563EB) else Color.Transparent,
                                        modifier = Modifier.clickable { isOnlineMapMode = false }
                                    ) {
                                        Text(
                                            text = "벡터",
                                            fontSize = 10.5.sp,
                                            fontWeight = if (!isOnlineMapMode) FontWeight.Bold else FontWeight.Medium,
                                            color = if (!isOnlineMapMode) PureWhite else Color(0xFFCBD5E1),
                                            maxLines = 1,
                                            softWrap = false,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.5.dp)
                                        )
                                    }
                                }
                            }

                            // 우측 하단: 외부 내비/구글맵 바로 열기
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            ) {
                                if (mapFocusStep == null && bottomDisplaySteps.size > 1) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFF1D4ED8).copy(alpha = 0.94f),
                                        border = BorderStroke(1.dp, Color(0xFF60A5FA)),
                                        modifier = Modifier.clickable {
                                            openCarNavigationApp(context, bottomDisplaySteps)
                                        }
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.DirectionsCar,
                                                contentDescription = "자동차 내비 길찾기",
                                                tint = PureWhite,
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Spacer(modifier = Modifier.width(4.dp))
                                            Text(
                                                text = "자동차 길찾기",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PureWhite,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }

                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFF0F172A).copy(alpha = 0.90f),
                                    border = BorderStroke(1.dp, Color(0xFF334155)),
                                    modifier = Modifier.clickable {
                                        if (mapFocusStep != null) {
                                            openGoogleMapsLocation(
                                                context = context,
                                                latitude = mapFocusStep!!.latitude!!,
                                                longitude = mapFocusStep!!.longitude!!,
                                                label = mapFocusStep!!.locationName ?: mapFocusStep!!.title,
                                                address = mapFocusStep!!.address
                                            )
                                        } else {
                                            openGoogleMapsRoute(context, bottomDisplaySteps)
                                        }
                                    }
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.OpenInNew,
                                            contentDescription = "앱에서 열기",
                                            tint = Color(0xFF60A5FA),
                                            modifier = Modifier.size(13.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = if (mapFocusStep != null) "지점 열기" else "구글맵 경로",
                                            fontSize = 10.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PureWhite,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        } else {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                if (isCommuteExcluded && commuteStepCount > 0) {
                                    MidKoreaStaticMapView(
                                        steps = emptyList(),
                                        useRealMapTiles = isOnlineMapMode,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    Surface(
                                        shape = RoundedCornerShape(14.dp),
                                        color = Color.White.copy(alpha = 0.96f),
                                        border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                        modifier = Modifier.padding(horizontal = 24.dp)
                                    ) {
                                        Column(
                                            horizontalAlignment = Alignment.CenterHorizontally,
                                            modifier = Modifier.padding(horizontal = 18.dp, vertical = 14.dp)
                                        ) {
                                            Text(
                                                text = "출퇴근 경로 ${commuteStepCount}개 지점이 제외되었습니다",
                                                fontSize = 13.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF0F172A)
                                            )
                                            Spacer(modifier = Modifier.height(8.dp))
                                            Button(
                                                onClick = { isCommuteExcluded = false },
                                                shape = AppShapes.pill,
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF1D4ED8)),
                                                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 6.dp)
                                            ) {
                                                Text(
                                                    text = "출퇴근 포함 보기",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PureWhite
                                                )
                                            }
                                        }
                                    }
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Text(
                                            text = "해당 기간에 등록된 GPS 위치 기록이 없습니다.",
                                            style = AppTypography.bodySecondary.copy(color = Color(0xFF475569))
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                // 2-1. 드래그 핸들 & 원터치 분할 뷰 컨트롤 바 ([목록 크게] | [반반] | [지도 크게])
                Surface(
                    color = PureWhite,
                    border = BorderStroke(0.5.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(handleBarDp)
                        .draggable(
                            orientation = Orientation.Vertical,
                            state = rememberDraggableState { deltaPx ->
                                val deltaDp = with(density) { deltaPx.toDp().value }
                                val baseDp = customMapHeightDp ?: currentMapHeight.value
                                customMapHeightDp = (baseDp + deltaDp).coerceIn(0f, maxDragMapDp.value)
                            },
                            onDragStopped = {
                                val dragged = customMapHeightDp
                                if (dragged != null && dragged < 64f) {
                                    customMapHeightDp = null
                                    sheetMode = MapListSheetMode.LIST_EXPANDED
                                }
                            }
                        )
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        // 상단 중앙 드래그 핸들 인디케이터 바
                        Box(
                            modifier = Modifier
                                .align(Alignment.TopCenter)
                                .padding(top = 4.dp)
                                .size(width = 34.dp, height = 3.5.dp)
                                .clip(CircleShape)
                                .background(Color(0xFFCBD5E1))
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(start = Spacing.md, end = Spacing.md, top = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "이동 지점",
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF0F172A),
                                    maxLines = 1,
                                    softWrap = false
                                )
                                Surface(
                                    shape = AppShapes.pill,
                                    color = Color(0xFFEFF6FF)
                                ) {
                                    Text(
                                        text = "${bottomDisplaySteps.size}곳",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = AppColors.primary,
                                        maxLines = 1,
                                        softWrap = false,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 2.dp)
                                    )
                                }
                            }

                            // 원터치 비율 전환 스위치
                            Surface(
                                shape = AppShapes.pill,
                                color = Color(0xFFF1F5F9),
                                border = BorderStroke(0.5.dp, Color(0xFFCBD5E1))
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(2.dp)
                                ) {
                                    MapListSheetMode.values().forEach { mode ->
                                        val isSelected = customMapHeightDp == null && sheetMode == mode
                                        Surface(
                                            shape = AppShapes.pill,
                                            color = if (isSelected) AppColors.primary else Color.Transparent,
                                            modifier = Modifier.clickable {
                                                customMapHeightDp = null
                                                sheetMode = mode
                                            }
                                        ) {
                                            Text(
                                                text = mode.label,
                                                fontSize = 11.sp,
                                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                                color = if (isSelected) PureWhite else Color(0xFF475569),
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 9.dp, vertical = 3.5.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // 3. 하단 이동 지점 리스트 (출발지 · 1시간 이내 동일 위치 경유지 · 도착지)
                if (bottomDisplaySteps.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .background(AppColors.background),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (isCommuteExcluded && commuteStepCount > 0) {
                                "출퇴근 외 이동 지점이 없습니다."
                            } else {
                                "표시할 이동 지점 목록이 없습니다."
                            },
                            style = AppTypography.bodySecondary.copy(color = Color(0xFF64748B))
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier
                            .fillMaxWidth()
                            .weight(1f)
                            .nestedScroll(listScrollConnection)
                            .background(AppColors.background),
                        contentPadding = PaddingValues(top = 6.dp, bottom = 92.dp)
                    ) {
                        itemsIndexed(
                            items = bottomDisplaySteps,
                            key = { idx, item -> "${item.id}_$idx" }
                        ) { index, step ->
                            val isFocused = mapFocusStep?.id == step.id || (mapFocusStep == null && selectedStep?.id == step.id)
                            val isStopover = isStopoverStep(step)
                            val isDeparture = isDepartureStep(step)
                            val isArrival = isArrivalStep(step)

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
                                    // Top Header: Number Badge, Time, Vehicle/Type Pill, Role Label
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            modifier = Modifier
                                                .weight(1f)
                                                .padding(end = 8.dp)
                                        ) {
                                            val badgeBg = when {
                                                isFocused -> AppColors.primary
                                                isDeparture -> Color(0xFF16A34A)
                                                isStopover -> Color(0xFF2563EB)
                                                isArrival -> Color(0xFFDC2626)
                                                else -> Color(0xFF475569)
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .size(22.dp)
                                                    .clip(CircleShape)
                                                    .background(badgeBg),
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Text(
                                                    text = "${index + 1}",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = PureWhite,
                                                    maxLines = 1,
                                                    softWrap = false
                                                )
                                            }

                                            Text(
                                                text = step.time.format(DateTimeFormatter.ofPattern("M/d(E) a h:mm", Locale.KOREA)),
                                                fontSize = 12.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = AppColors.textPrimary,
                                                maxLines = 1,
                                                softWrap = false
                                            )

                                            val (typeLabel, typeBg, typeFg) = when (step.stepType) {
                                                RouteStepType.TRANSACTION -> Triple("결제/방문", Emerald50, Emerald700)
                                                RouteStepType.PHOTO -> Triple("사진 기록", Indigo50, Indigo700)
                                                RouteStepType.DRIVING -> {
                                                    val carTag = step.tags.firstOrNull {
                                                        it != "차량주행" && it != "차량" && it != "승차" && it != "하차" &&
                                                            it != "출발지점" && it != "도착지점" && it != "경유지"
                                                    }
                                                    val cleanCar = carTag
                                                        ?.replace(Regex("^(?:[⭐🛡️🔵🔗🪽🚗📍]\\s*)+"), "")
                                                        ?.trim()
                                                    Triple(cleanCar?.ifBlank { "차량 주행" } ?: "차량 주행", Amber50, Amber700)
                                                }
                                                RouteStepType.MEMO -> Triple("메모 기록", Slate100, Slate700)
                                            }

                                            Box(
                                                modifier = Modifier
                                                    .clip(AppShapes.pill)
                                                    .background(typeBg)
                                                    .padding(horizontal = 6.dp, vertical = 2.dp)
                                            ) {
                                                Text(
                                                    text = typeLabel,
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = typeFg,
                                                    maxLines = 1,
                                                    softWrap = false,
                                                    overflow = TextOverflow.Ellipsis
                                                )
                                            }
                                        }

                                        val (pointSubtitle, pointSubtitleColor) = when {
                                            isStopover -> "경유지" to Color(0xFF2563EB)
                                            isDeparture -> "출발 지점" to Color(0xFF16A34A)
                                            isArrival -> "도착 지점" to Color(0xFFDC2626)
                                            step.stepType == RouteStepType.DRIVING -> "주행 지점" to Color(0xFFDC2626)
                                            step.stepType == RouteStepType.PHOTO -> "사진 방문지" to Color(0xFF4F46E5)
                                            else -> "방문 지점" to Color(0xFF475569)
                                        }

                                        Surface(
                                            shape = AppShapes.pill,
                                            color = pointSubtitleColor.copy(alpha = 0.10f)
                                        ) {
                                            Text(
                                                text = pointSubtitle,
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = pointSubtitleColor,
                                                maxLines = 1,
                                                softWrap = false,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                            )
                                        }
                                    }

                                    Spacer(modifier = Modifier.height(8.dp))

                                    // Location Title (제목에는 소요시간을 제외하고 최근접 대표 명칭 또는 순수 동 이름만 표기)
                                    val basePlaceTitle = DailyRouteAggregator.resolveStepDisplayPlaceName(step)
                                    val isStandaloneDriving = step.stepType == RouteStepType.DRIVING && !isDeparture && !isStopover && !isArrival
                                    val needsDrivingDuration = isArrival || isStandaloneDriving
                                    val stepFinalMinutes: Int? = if (needsDrivingDuration) {
                                        val explicitMin = extractDrivingDurationMinutesFromText(step.description)
                                            ?: extractDrivingDurationMinutesFromText(step.title)
                                        val prevDep = if (isArrival && index > 0) {
                                            bottomDisplaySteps.subList(0, index)
                                                .lastOrNull {
                                                    (isDepartureStep(it) || isStopoverStep(it)) &&
                                                        !it.time.isAfter(step.time) &&
                                                        java.time.Duration.between(it.time, step.time).toMinutes() in 0..360
                                                }
                                        } else null
                                        val pairedMin = if (explicitMin == null && prevDep != null) {
                                            java.time.Duration.between(prevDep.time, step.time).toMinutes().toInt()
                                                .coerceAtLeast(1)
                                        } else null
                                        val rawMin = explicitMin ?: pairedMin
                                        val isCommutePoint = isCommuteStep(step, bottomDisplaySteps)
                                        when {
                                            isCommutePoint && rawMin != null && rawMin in 20..22 &&
                                                DailyRouteAggregator.isCommuteDrivingStepPair(prevDep, step) ->
                                                estimateRealisticCommuteMinutes(
                                                    step.time,
                                                    DailyRouteAggregator.extractDistanceKmFromText(step.description) ?: 27.1
                                                )
                                            rawMin != null && rawMin > 0 -> rawMin
                                            isCommutePoint -> estimateRealisticCommuteMinutes(
                                                step.time,
                                                DailyRouteAggregator.extractDistanceKmFromText(step.description) ?: 27.1
                                            )
                                            else -> estimateRealisticNonCommuteMinutes(
                                                step.time,
                                                DailyRouteAggregator.extractDistanceKmFromText(step.description) ?: 3.5
                                            )
                                        }
                                    } else null
                                    Text(
                                        text = basePlaceTitle,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF0F172A)
                                    )

                                    val hintDesc = when {
                                        isStopover && !step.description.isNullOrBlank() -> step.description
                                        needsDrivingDuration && stepFinalMinutes != null && stepFinalMinutes > 0 ->
                                            DailyRouteAggregator.upsertStepDurationDescription(step.description, stepFinalMinutes)
                                        else -> null
                                    }
                                    if (!hintDesc.isNullOrBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = buildHighlightedDurationText(hintDesc, Color(0xFF111827)),
                                            fontSize = 12.sp,
                                            color = Color(0xFF64748B)
                                        )
                                    }

                                    // Address (호수 제거 및 7101동 -> 집 정제)
                                    val addr = PlaceResolver.sanitizeAddressForDisplay(step.address).orEmpty()
                                    if (addr.isNotBlank()) {
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(
                                            text = addr,
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
                                                            text = companion,
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
                                                if (sheetMode == MapListSheetMode.LIST_EXPANDED ||
                                                    (customMapHeightDp != null && customMapHeightDp!! < 110f)
                                                ) {
                                                    customMapHeightDp = null
                                                    sheetMode = MapListSheetMode.SPLIT
                                                }
                                                mapFocusStep = step
                                                isSingleStepZoomed = true
                                                onStepSelected(step)
                                            },
                                            shape = AppShapes.button,
                                            border = BorderStroke(0.5.dp, if (isFocused) AppColors.primary else Color(0xFFCBD5E1)),
                                            modifier = Modifier.weight(1f).height(34.dp),
                                            contentPadding = PaddingValues(horizontal = 4.dp, vertical = 0.dp)
                                        ) {
                                            Icon(Icons.Default.Place, contentDescription = null, modifier = Modifier.size(13.dp), tint = AppColors.primary)
                                            Spacer(modifier = Modifier.width(3.dp))
                                            Text("지도에서 보기", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.primary, maxLines = 1, softWrap = false)
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
                                                Text("구글맵 앱 열기", fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF2563EB), maxLines = 1, softWrap = false)
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
                                                Text("동행인", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = AppColors.primary, maxLines = 1, softWrap = false)
                                            }
                                        }
                                    }
                                }
                            }

                            // 동일 주행 트립 내 연속 이동 구간 표시 (별개 운행의 도착지 ➔ 다음 운행 출발지 사이에는 불필요한 연결 배너 생략)
                            if (index < bottomDisplaySteps.size - 1) {
                                val nextStep = bottomDisplaySteps[index + 1]
                                val isSeparatedTrips = isArrival && isDepartureStep(nextStep)
                                if (!isSeparatedTrips) {
                                    val segKm = if (step.latitude != null && step.longitude != null &&
                                        nextStep.latitude != null && nextStep.longitude != null
                                    ) {
                                        LocationDistanceUtils.calculateDrivingDistanceKm(
                                            step.latitude, step.longitude,
                                            nextStep.latitude, nextStep.longitude
                                        )
                                    } else 0.0

                                    Surface(
                                        shape = AppShapes.pill,
                                        color = Color(0xFFF1F5F9),
                                        border = BorderStroke(0.5.dp, Color(0xFFCBD5E1)),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Spacing.xl, vertical = 3.dp)
                                            .clickable {
                                                mapFocusStep = null
                                                openGoogleMapsRoute(context, listOf(step, nextStep))
                                            }
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 12.dp, vertical = 5.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = if (segKm >= 0.1) {
                                                    "↓ 지점 ${index + 1} ➔ 지점 ${index + 2} 이동 구간 (약 %.1f km)".format(segKm)
                                                } else {
                                                    "↓ 지점 ${index + 1} ➔ 지점 ${index + 2} 이동 구간"
                                                },
                                                fontSize = 10.5.sp,
                                                color = Color(0xFF334155),
                                                fontWeight = FontWeight.SemiBold,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.weight(1f).padding(end = 6.dp)
                                            )
                                            Text(
                                                text = "구간 경로 →",
                                                fontSize = 10.sp,
                                                color = Color(0xFF2563EB),
                                                fontWeight = FontWeight.Bold,
                                                maxLines = 1,
                                                softWrap = false
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 순수 Jetpack Compose 기반 Interactive Route Map (하위 호환 유지)
 */
@Composable
fun InteractiveRouteMapView(
    steps: List<RouteStep>,
    selectedIndex: Int,
    onStepSelected: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val focused = steps.getOrNull(selectedIndex)
    MidKoreaStaticMapView(
        steps = steps,
        focusedStep = focused,
        onStepClick = { clicked ->
            val idx = steps.indexOfFirst { it.id == clicked.id }
            if (idx >= 0) onStepSelected(idx)
        },
        modifier = modifier
    )
}

/**
 * 특정 거점(스텝)을 Google 지도에 마커(Pin)와 장소명으로 정확하게 표기하여 엽니다.
 */
fun openGoogleMapsLocation(
    context: Context,
    latitude: Double,
    longitude: Double,
    label: String,
    address: String? = null
) {
    try {
        val cleanLabel = label.replace(Regex("\\[.*?\\]"), "").trim().ifBlank { "방문 위치" }
        val pinTitle = buildString {
            append(cleanLabel)
            if (!address.isNullOrBlank() && address != cleanLabel) {
                append(" (").append(address).append(")")
            }
        }
        val encodedTitle = Uri.encode(pinTitle)

        val geoUri = Uri.parse("geo:0,0?q=$latitude,$longitude($encodedTitle)")
        val mapIntent = Intent(Intent.ACTION_VIEW, geoUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        try {
            context.startActivity(mapIntent)
        } catch (e: Exception) {
            val webQuery = Uri.encode("$latitude,$longitude")
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
 * 이동 거점들을 외부 Google 지도 앱의 경로(Directions)로 엽니다.
 *
 * [핵심 수정 사항]
 * 1. 대한민국 내에서 Google 지도는 법적·정책적 제한으로 자동차(travelmode=driving) 및 도보 길찾기를 제공하지 않으며,
 *    travelmode=driving 호출 시 100% "자동차 — 경로를 찾을 수 없음" 오류가 발생합니다.
 *    따라서 한국 Google 지도에서 유일하게 정상 경로 탐색이 지원되는 대중교통 경로 모드(travelmode=transit&dirflg=r)로 호출하며,
 *    Google 지도 대중교통 모드에서 오류를 유발하는 waypoints 파라미터를 제거해 즉시 경로가 표시되도록 합니다.
 * 2. 아침 집 출발 ➔ 직장/방문지 ➔ 저녁 집 도착처럼 첫 지점과 마지막 지점이 동일 구역(왕복 동선)인 경우,
 *    resolveEffectiveRouteEndpoints()를 통해 가장 멀리 이동한 주요 목적지를 도착지로 자동 선정합니다.
 * 3. 모든 지점이 반경 200m 이내의 동일 장소인 경우 길찾기 오류 대신 해당 위치 마커 핀 화면으로 엽니다.
 */
fun openGoogleMapsRoute(context: Context, steps: List<RouteStep>) {
    try {
        val validSteps = steps.filter {
            it.latitude != null && it.longitude != null &&
                (it.latitude != 0.0 || it.longitude != 0.0)
        }
        if (validSteps.isEmpty()) return

        val endpoints = resolveEffectiveRouteEndpoints(validSteps)
        if (endpoints == null) {
            val single = validSteps.first()
            openGoogleMapsLocation(
                context = context,
                latitude = single.latitude!!,
                longitude = single.longitude!!,
                label = single.locationName ?: single.title,
                address = single.address
            )
            return
        }

        val (startStep, endStep) = endpoints
        val origin = "${startStep.latitude},${startStep.longitude}"
        val destination = "${endStep.latitude},${endStep.longitude}"

        // 한국 구글 지도에서 100% 경로가 인식되는 공식 URL (travelmode=transit&dirflg=r)
        val directionsUrl = "https://www.google.com/maps/dir/?api=1&origin=$origin&destination=$destination&travelmode=transit&dirflg=r"

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
 * 한국 실제 도로 자동차 길찾기(카카오맵 / 네이버지도 / 티맵 앱 또는 카카오맵 웹 자동차 경로)를 실행합니다.
 * 국내에서는 구글맵이 자동차 길찾기를 지원하지 않으므로, [자동차 길찾기] 버튼 클릭 시
 * 설치된 국내 내비게이션 앱(카카오맵·네이버지도·티맵) 또는 카카오맵 자동차 길찾기 웹 화면으로 실제 차량 주행 경로를 즉시 보여줍니다.
 */
fun openCarNavigationApp(context: Context, steps: List<RouteStep>) {
    try {
        val validSteps = steps.filter {
            it.latitude != null && it.longitude != null &&
                (it.latitude != 0.0 || it.longitude != 0.0)
        }
        if (validSteps.isEmpty()) return

        val endpoints = resolveEffectiveRouteEndpoints(validSteps)
        if (endpoints == null) {
            val single = validSteps.first()
            openGoogleMapsLocation(
                context = context,
                latitude = single.latitude!!,
                longitude = single.longitude!!,
                label = single.locationName ?: single.title,
                address = single.address
            )
            return
        }

        val (startStep, endStep) = endpoints
        val sLat = startStep.latitude!!
        val sLng = startStep.longitude!!
        val eLat = endStep.latitude!!
        val eLng = endStep.longitude!!

        val sName = (startStep.locationName ?: startStep.title)
            .replace(Regex("\\[.*?\\]"), "")
            .replace(",", " ")
            .trim()
            .ifBlank { "출발지" }
        val eName = (endStep.locationName ?: endStep.title)
            .replace(Regex("\\[.*?\\]"), "")
            .replace(",", " ")
            .trim()
            .ifBlank { "도착지" }

        // 1순위: 카카오맵 앱 자동차 길찾기 스킴
        val kakaoAppUri = Uri.parse("kakaomap://route?sp=$sLat,$sLng&ep=$eLat,$eLng&by=CAR")
        val kakaoIntent = Intent(Intent.ACTION_VIEW, kakaoAppUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (kakaoIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(kakaoIntent)
            return
        }

        // 2순위: 네이버 지도 앱 자동차 길찾기 스킴
        val naverAppUri = Uri.parse(
            "nmap://route/car?slat=$sLat&slng=$sLng&sname=${Uri.encode(sName)}&dlat=$eLat&dlng=$eLng&dname=${Uri.encode(eName)}&appname=${context.packageName}"
        )
        val naverIntent = Intent(Intent.ACTION_VIEW, naverAppUri).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        if (naverIntent.resolveActivity(context.packageManager) != null) {
            context.startActivity(naverIntent)
            return
        }

        // 3순위: 무설치 카카오맵 웹 자동차 길찾기 (별도 앱 설치 없이도 한국 자동차 도로 경로 100% 표시)
        val kakaoWebUrl = "https://map.kakao.com/link/from/${Uri.encode(sName)},$sLat,$sLng/to/${Uri.encode(eName)},$eLat,$eLng"
        val webIntent = Intent(Intent.ACTION_VIEW, Uri.parse(kakaoWebUrl)).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(webIntent)
    } catch (e: Exception) {
        Toast.makeText(context, "자동차 길찾기 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
    }
}

/**
 * 인앱 WebView에서 구글 지도를 로드하기 위한 iframe/embed 표준 URL을 생성합니다.
 */
fun buildGoogleMapsEmbedUrl(steps: List<RouteStep>, focusStep: RouteStep? = null): String {
    val validSteps = steps.filter {
        it.latitude != null && it.longitude != null &&
            (it.latitude != 0.0 || it.longitude != 0.0)
    }

    if (validSteps.isEmpty()) {
        return "about:blank"
    }

    if (focusStep != null && focusStep.latitude != null && focusStep.longitude != null) {
        val lat = focusStep.latitude
        val lng = focusStep.longitude
        val label = Uri.encode(focusStep.locationName ?: focusStep.title)
        return "https://maps.google.com/maps?q=$lat,$lng($label)&hl=ko&z=16&output=embed"
    }

    val endpoints = resolveEffectiveRouteEndpoints(validSteps)
    if (endpoints == null) {
        val single = validSteps.first()
        val lat = single.latitude
        val lng = single.longitude
        val label = Uri.encode(single.locationName ?: single.title)
        return "https://maps.google.com/maps?q=$lat,$lng($label)&hl=ko&z=16&output=embed"
    }

    val (startStep, endStep) = endpoints
    val origin = "${startStep.latitude},${startStep.longitude}"
    val destination = "${endStep.latitude},${endStep.longitude}"

    return "https://maps.google.com/maps?saddr=$origin&daddr=$destination&dirflg=r&hl=ko&output=embed"
}

// 하위 호환성을 위해 유지
fun openGoogleMaps(context: Context, latitude: Double, longitude: Double, label: String) {
    openGoogleMapsLocation(context, latitude, longitude, label)
}

/**
 * 유료 Google Maps API Key 없이 실제 구글 한국어 지도 타일(mt0~3.google.com/vt/lyrs=m&hl=ko&gl=kr&scale=2)과
 * 시·군·구 행정구역 경계선 오버레이, 그리고 OSRM 실제 도로망 주행 경로(Road Polyline)를 결합한
 * 고정밀 한국 인터랙티브 지도 HTML 생성기.
 *
 * - 처음 열릴 때 이동 경로 구역이 한눈에 확대되어 보이도록 tight fitBounds (maxZoom 16) 적용
 * - 출발지(1)와 귀가 도착지(4) 등 동일/인접 좌표 핀이 겹치지 않도록 자동 오프셋 분리
 * - [일반지도] / [위성지도] / [지역경계선 ON·OFF] 실시간 레이어 스위치 내장
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
            <head><meta charset="utf-8"/><style>body{margin:0;background:#f1f5f9;color:#475569;display:flex;align-items:center;justify-content:center;height:100vh;font-family:sans-serif;font-size:14px;}</style></head>
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
            val rawName = DailyRouteAggregator.resolveStepDisplayPlaceName(step)
            val title = rawName
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("'", "\\'")
            val addressStr = (step.address ?: "")
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", " ")
                .replace("\r", "")
                .replace("'", "\\'")
            val timeStr = step.time.format(DateTimeFormatter.ofPattern("M/d a h:mm", Locale.KOREAN))
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
            val isFocus = (index == focusIndex)
            val isKey = (index == 0) ||
                (index == validSteps.lastIndex) ||
                isFocus ||
                isDepartureStep(step) ||
                isArrivalStep(step) ||
                step.stepType != RouteStepType.DRIVING
            append("""{"lat":${step.latitude},"lng":${step.longitude},"title":"$title","addr":"$addressStr","time":"$timeStr","num":${index + 1},"isFocus":$isFocus,"isKey":$isKey}""")
        }
        append("]")
    }

    // 수도권 및 주요 시·군·구 행정구역 경계 폴리곤 JSON (지도 위에 지역 구분선을 선명하게 표시)
    val districtsJson = buildString {
        append("[")
        MidKoreaBounds.DISTRICT_BOUNDARIES.forEachIndexed { idx, d ->
            if (idx > 0) append(",")
            val coordsStr = d.polygon.joinToString(",") { "[${it.first},${it.second}]" }
            append("""{"name":"${d.name}","group":"${d.regionGroup}","coords":[$coordsStr]}""")
        }
        append("]")
    }

    return """
        <!DOCTYPE html>
        <html>
        <head>
          <meta charset="utf-8" />
          <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
          <link rel="stylesheet" href="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.css" />
          <script src="https://cdnjs.cloudflare.com/ajax/libs/leaflet/1.9.4/leaflet.min.js"></script>
          <style>
            html, body, #map { width: 100%; height: 100%; margin: 0; padding: 0; background: #eef2f6; overflow: hidden; }
            .leaflet-container { background: #eef2f6; font-family: -apple-system, BlinkMacSystemFont, "Malgun Gothic", "Apple SD Gothic Neo", sans-serif; }
            .pin-wrapper {
              position: relative;
              display: flex;
              flex-direction: column;
              align-items: center;
              pointer-events: auto;
            }
            .custom-pin {
              display: flex;
              align-items: center;
              justify-content: center;
              width: 28px;
              height: 28px;
              border-radius: 50%;
              background: #2563eb;
              color: #ffffff;
              font-weight: 800;
              font-size: 12px;
              box-shadow: 0 3px 8px rgba(15,23,42,0.45);
              border: 2.5px solid #ffffff;
              transition: transform 0.2s ease;
            }
            .custom-pin.start { background: #16a34a; }
            .custom-pin.end { background: #dc2626; }
            .custom-pin.focus {
              background: #ea580c;
              border-color: #fef08a;
              box-shadow: 0 0 14px rgba(234,88,12,0.85);
              transform: scale(1.2);
            }
            .pin-caption {
              margin-top: 4px;
              background: #ffffff;
              color: #0f172a;
              font-size: 12.5px;
              font-weight: 800;
              padding: 4px 9px;
              border-radius: 6px;
              border: 2.4px solid #2563eb;
              white-space: nowrap;
              box-shadow: 0 0 0 2px #ffffff, 0 4px 10px rgba(15,23,42,0.42);
            }
            .pin-caption.start { border-color: #16a34a; color: #14532d; background: #f0fdf4; }
            .pin-caption.end { border-color: #dc2626; color: #991b1b; background: #fef2f2; }
            .pin-caption.focus { border-color: #ea580c; color: #9a3412; background: #fff7ed; }
            .custom-pin-dot {
              width: 10px;
              height: 10px;
              border-radius: 50%;
              background: #2563eb;
              border: 2px solid #ffffff;
              box-shadow: 0 1px 4px rgba(0,0,0,0.4);
            }
            .district-label {
              color: #475569;
              font-size: 10px;
              font-weight: 700;
              text-shadow: -1px -1px 0 #fff, 1px -1px 0 #fff, -1px 1px 0 #fff, 1px 1px 0 #fff;
              white-space: nowrap;
              pointer-events: none;
              opacity: 0.82;
            }
            .map-layer-bar {
              position: absolute;
              bottom: 10px;
              left: 52px;
              z-index: 1000;
              display: flex;
              gap: 4px;
              background: rgba(15, 23, 42, 0.88);
              padding: 3px;
              border-radius: 999px;
              border: 1px solid #334155;
              box-shadow: 0 4px 10px rgba(0,0,0,0.25);
            }
            .layer-btn {
              border: none;
              background: transparent;
              color: #cbd5e1;
              font-size: 10px;
              font-weight: 700;
              padding: 4px 8px;
              border-radius: 999px;
              cursor: pointer;
            }
            .layer-btn.active {
              background: #2563eb;
              color: #ffffff;
            }
            .leaflet-popup-content-wrapper {
              background: #ffffff;
              color: #0f172a;
              border-radius: 10px;
              border: 1px solid #cbd5e1;
              box-shadow: 0 8px 20px rgba(15,23,42,0.22);
              padding: 2px;
            }
            .leaflet-popup-tip { background: #ffffff; }
            .popup-card { padding: 4px 6px; }
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
            .popup-title { font-weight: 800; font-size: 13px; color: #0f172a; line-height: 1.3; }
            .popup-addr { font-size: 11px; color: #475569; margin-top: 2px; }
            .popup-time { font-size: 11px; color: #2563eb; font-weight: 600; margin-top: 3px; }
          </style>
        </head>
        <body>
          <div id="map"></div>
          <div class="map-layer-bar">
            <button id="btnRoad" class="layer-btn active" onclick="switchTile('road')">구글 한국지도</button>
            <button id="btnSat" class="layer-btn" onclick="switchTile('sat')">위성+지명</button>
            <button id="btnBorder" class="layer-btn active" onclick="toggleBorders()">지역경계선</button>
          </div>
          <script>
            var points = $pointsJson;
            var districts = $districtsJson;
            var focusIdx = $focusIndex;
            var mapInitialized = false;
            var mapInstance = null;
            var googleRoadLayer = null;
            var googleSatLayer = null;
            var osmFallbackLayer = null;
            var borderLayerGroup = null;
            var bordersVisible = true;

            function switchTile(mode) {
              if (!mapInstance) return;
              var btnRoad = document.getElementById('btnRoad');
              var btnSat = document.getElementById('btnSat');
              if (mode === 'sat') {
                if (googleRoadLayer && mapInstance.hasLayer(googleRoadLayer)) mapInstance.removeLayer(googleRoadLayer);
                if (osmFallbackLayer && mapInstance.hasLayer(osmFallbackLayer)) mapInstance.removeLayer(osmFallbackLayer);
                if (googleSatLayer && !mapInstance.hasLayer(googleSatLayer)) googleSatLayer.addTo(mapInstance);
                if (btnRoad) btnRoad.className = 'layer-btn';
                if (btnSat) btnSat.className = 'layer-btn active';
              } else {
                if (googleSatLayer && mapInstance.hasLayer(googleSatLayer)) mapInstance.removeLayer(googleSatLayer);
                if (googleRoadLayer && !mapInstance.hasLayer(googleRoadLayer)) googleRoadLayer.addTo(mapInstance);
                if (btnRoad) btnRoad.className = 'layer-btn active';
                if (btnSat) btnSat.className = 'layer-btn';
              }
            }

            function toggleBorders() {
              if (!mapInstance || !borderLayerGroup) return;
              var btnBorder = document.getElementById('btnBorder');
              if (bordersVisible) {
                mapInstance.removeLayer(borderLayerGroup);
                bordersVisible = false;
                if (btnBorder) btnBorder.className = 'layer-btn';
              } else {
                borderLayerGroup.addTo(mapInstance);
                bordersVisible = true;
                if (btnBorder) btnBorder.className = 'layer-btn active';
              }
            }

            // 동일하거나 인접한 좌표(예: 집 출발 1번 핀과 귀가 도착 4번 핀)가 겹치지 않도록 미세 분리
            function computeDisplayLatLngs(pts) {
              var result = pts.map(function(p) { return { lat: p.lat, lng: p.lng, origLat: p.lat, origLng: p.lng, p: p }; });
              var eps = 0.0006; // 약 55m 이내
              for (var i = 0; i < result.length; i++) {
                var group = [];
                for (var j = 0; j < result.length; j++) {
                  var dLat = Math.abs(result[i].origLat - result[j].origLat);
                  var dLng = Math.abs(result[i].origLng - result[j].origLng);
                  if (dLat < eps && dLng < eps) {
                    group.push(j);
                  }
                }
                if (group.length > 1) {
                  var pos = group.indexOf(i);
                  var offsetIdx = pos - (group.length - 1) / 2.0;
                  result[i].lng = result[i].origLng + offsetIdx * 0.00055;
                  result[i].lat = result[i].origLat + ((pos % 2 === 0) ? 0.00018 : -0.00018);
                }
              }
              return result;
            }

            function initMap() {
              if (mapInitialized) return;
              if (typeof L === 'undefined') return;
              try {
                mapInitialized = true;
                var initialCenter = points.length > 0 ? [points[0].lat, points[0].lng] : [37.5265, 127.0480];
                var map = L.map('map', {
                  center: initialCenter,
                  zoom: 14,
                  zoomControl: false,
                  attributionControl: false
                });
                mapInstance = map;

                L.control.zoom({ position: 'bottomleft' }).addTo(map);

                // 1. Google 한국어 표준 상세 지도 타일 (API 키 불필요, 시·군·구·동/도로/건물/지하철 한글 완벽 표시, 고해상도 scale=2)
                googleRoadLayer = L.tileLayer('https://mt{s}.google.com/vt/lyrs=m&hl=ko&gl=kr&x={x}&y={y}&z={z}&scale=2', {
                  maxZoom: 20,
                  subdomains: ['0', '1', '2', '3']
                }).addTo(map);

                // 2. Google 한국어 위성+지명 하이브리드 타일
                googleSatLayer = L.tileLayer('https://mt{s}.google.com/vt/lyrs=y&hl=ko&gl=kr&x={x}&y={y}&z={z}&scale=2', {
                  maxZoom: 20,
                  subdomains: ['0', '1', '2', '3']
                });

                // 3. 타일 로드 실패 대비 OpenStreetMap 폴백
                osmFallbackLayer = L.tileLayer('https://tile.openstreetmap.org/{z}/{x}/{y}.png', { maxZoom: 19 });
                var errorCount = 0;
                googleRoadLayer.on('tileerror', function() {
                  errorCount++;
                  if (errorCount > 6 && !map.hasLayer(osmFallbackLayer)) {
                    osmFallbackLayer.addTo(map);
                  }
                });

                // 4. 시·군·구 지역 구분 경계선 오버레이 레이어 (구글지도 지역 경계 스타일)
                borderLayerGroup = L.layerGroup().addTo(map);
                districts.forEach(function(d) {
                  if (!d.coords || d.coords.length < 3) return;
                  var isSeoul = d.group === 'SEOUL';
                  L.polygon(d.coords, {
                    color: isSeoul ? '#dc2626' : '#475569',
                    weight: isSeoul ? 1.6 : 1.3,
                    opacity: 0.65,
                    dashArray: '5, 5',
                    fillColor: isSeoul ? '#fee2e2' : '#e2e8f0',
                    fillOpacity: 0.05,
                    interactive: false
                  }).addTo(borderLayerGroup);
                });

                var displayPts = computeDisplayLatLngs(points);
                var rawLatLngs = points.map(function(p) { return [p.lat, p.lng]; });

                // 5. 기본 경로선 (즉시 렌더링) + OSRM 실제 도로망 주행 경로 비동기 업데이트
                var routeCasing = null;
                var routeCore = null;
                var routeDash = null;

                function drawRoutePolylines(coords) {
                  if (routeCasing) map.removeLayer(routeCasing);
                  if (routeCore) map.removeLayer(routeCore);
                  if (routeDash) map.removeLayer(routeDash);

                  routeCasing = L.polyline(coords, {
                    color: '#1e3a8a',
                    weight: 8,
                    opacity: 0.85,
                    lineCap: 'round',
                    lineJoin: 'round'
                  }).addTo(map);

                  routeCore = L.polyline(coords, {
                    color: '#3b82f6',
                    weight: 5,
                    opacity: 0.95,
                    lineCap: 'round',
                    lineJoin: 'round'
                  }).addTo(map);

                  routeDash = L.polyline(coords, {
                    color: '#ffffff',
                    weight: 1.8,
                    opacity: 0.8,
                    dashArray: '8, 10',
                    lineCap: 'round',
                    lineJoin: 'round'
                  }).addTo(map);
                }

                if (rawLatLngs.length > 1) {
                  drawRoutePolylines(rawLatLngs);

                  // OSRM 공개 라우팅 서버를 통해 실제 한국 도로망(고속도로/간선도로) 주행 궤적으로 정밀 보정
                  var osrmCoords = points.map(function(p) { return p.lng + ',' + p.lat; }).join(';');
                  var osrmUrl = 'https://router.project-osrm.org/route/v1/driving/' + osrmCoords + '?overview=full&geometries=geojson';
                  fetch(osrmUrl)
                    .then(function(res) { return res.json(); })
                    .then(function(data) {
                      if (data && data.routes && data.routes.length > 0 && data.routes[0].geometry && data.routes[0].geometry.coordinates) {
                        var roadLatLngs = data.routes[0].geometry.coordinates.map(function(c) { return [c[1], c[0]]; });
                        if (roadLatLngs.length > 1) {
                          drawRoutePolylines(roadLatLngs);
                        }
                      }
                    })
                    .catch(function() {});
                }

                // 6. 거점 번호 핀 및 장소명 라벨 렌더링
                displayPts.forEach(function(item, i) {
                  var p = item.p;
                  var pos = [item.lat, item.lng];
                  var isStart = (i === 0);
                  var isEnd = (i === displayPts.length - 1 && displayPts.length > 1);

                  var icon;
                  if (p.isKey) {
                    var pinClass = 'custom-pin';
                    var capClass = 'pin-caption';
                    if (p.isFocus) {
                      pinClass += ' focus';
                      capClass += ' focus';
                    } else if (isStart) {
                      pinClass += ' start';
                      capClass += ' start';
                    } else if (isEnd) {
                      pinClass += ' end';
                      capClass += ' end';
                    }
                    var shortTitle = (p.title || '').substring(0, 14);
                    var prefix = isStart ? '출발·' : (isEnd ? '도착·' : '');
                    var html = '<div class="pin-wrapper">' +
                               '<div class="' + pinClass + '">' + p.num + '</div>' +
                               '<div class="' + capClass + '">' + prefix + shortTitle + '</div>' +
                               '</div>';
                    icon = L.divIcon({
                      className: '',
                      html: html,
                      iconSize: [140, 48],
                      iconAnchor: [70, 14]
                    });
                  } else {
                    icon = L.divIcon({
                      className: '',
                      html: '<div class="custom-pin-dot"></div>',
                      iconSize: [10, 10],
                      iconAnchor: [5, 5]
                    });
                  }

                  var marker = L.marker(pos, { icon: icon }).addTo(map);
                  var subtitle = isStart ? '🟢 출발 지점 (' + p.num + ')' : (isEnd ? '🔴 최종 도착지 (' + p.num + ')' : ('🔵 이동 거점 (' + p.num + ')'));
                  var content = '<div class="popup-card">' +
                                '<span class="popup-num">' + subtitle + '</span>' +
                                '<div class="popup-title">' + p.title + '</div>' +
                                (p.addr ? '<div class="popup-addr">📍 ' + p.addr + '</div>' : '') +
                                (p.time ? '<div class="popup-time">⏰ ' + p.time + '</div>' : '') +
                                '</div>';
                  marker.bindPopup(content);

                  if (p.isFocus) {
                    marker.openPopup();
                  }
                });

                // 7. 초기 카메라 확대 (사용자 요청: 지도를 좀 더 확대된 채로 표시)
                if (focusIdx >= 0 && focusIdx < displayPts.length) {
                  map.setView([displayPts[focusIdx].lat, displayPts[focusIdx].lng], 16);
                } else if (rawLatLngs.length === 1) {
                  map.setView(rawLatLngs[0], 16);
                } else if (rawLatLngs.length > 1) {
                  var bounds = L.latLngBounds(rawLatLngs);
                  var sw = bounds.getSouthWest();
                  var ne = bounds.getNorthEast();
                  var latDiff = Math.abs(ne.lat - sw.lat);
                  var lngDiff = Math.abs(ne.lng - sw.lng);
                  if (latDiff < 0.005 && lngDiff < 0.005) {
                    // 모든 지점이 인근 동네(500m 이내)에 모여 있으면 동/아파트 단위가 보이도록 줌 16으로 즉시 확대
                    map.setView(bounds.getCenter(), 16);
                  } else {
                    map.fitBounds(bounds, { padding: [26, 26], maxZoom: 16 });
                  }
                }
              } catch (e) {
                console.error("Map init error:", e);
              }
            }

            setTimeout(function() { if (!mapInitialized) initMap(); }, 400);
            if (document.readyState === 'complete' || document.readyState === 'interactive') {
              initMap();
            } else {
              window.addEventListener('DOMContentLoaded', initMap);
              window.addEventListener('load', initMap);
            }
          </script>
        </body>
        </html>
    """.trimIndent()
}
