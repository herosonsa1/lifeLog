package com.autologue.app.util

import com.autologue.app.domain.model.RouteStep
import kotlin.math.*

/**
 * GPS 좌표 간의 직선 거리 및 실제 도로 주행거리를 계산하는 유틸리티
 */
object LocationDistanceUtils {

    /** 도로 굴곡도 계수: 국내 도로망의 실제 곡률 및 우회율을 반영 (직선 대비 약 1.25배) */
    const val DEFAULT_ROAD_CURVE_FACTOR = 1.25

    /**
     * 두 GPS 좌표 사이의 구면 삼각법(Haversine) 기반 직선 거리(km) 계산
     */
    fun calculateStraightDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 || lon1 == 0.0 || lat2 == 0.0 || lon2 == 0.0) return 0.0
        val r = 6371.0 // 지구 반지름 (km)
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    /**
     * 두 GPS 좌표 사이의 실제 도로 추정 주행거리(km) 계산 (기본 1.25배 굴곡 계수 반영)
     */
    fun calculateDrivingDistanceKm(
        lat1: Double,
        lon1: Double,
        lat2: Double,
        lon2: Double,
        roadCurveFactor: Double = DEFAULT_ROAD_CURVE_FACTOR
    ): Double {
        val straight = calculateStraightDistanceKm(lat1, lon1, lat2, lon2)
        return straight * roadCurveFactor
    }

    /**
     * 하루 동안 방문한 RouteStep 거점 목록의 연속 경로 누적 도로 주행거리(km) 계산
     * - 유효한 위경도 좌표(0.0 제외)만 선별
     * - 동일 장소 제자리 사진(50m 미만)은 이동 거리에서 제외
     */
    fun calculateRouteDrivingDistanceKm(
        steps: List<RouteStep>,
        roadCurveFactor: Double = DEFAULT_ROAD_CURVE_FACTOR
    ): Double {
        val validSteps = steps.filter {
            it.latitude != null && it.longitude != null &&
            it.latitude != 0.0 && it.longitude != 0.0
        }

        if (validSteps.size < 2) return 0.0

        var totalDistanceKm = 0.0
        var prevStep = validSteps.first()

        for (i in 1 until validSteps.size) {
            val currStep = validSteps[i]
            val dist = calculateDrivingDistanceKm(
                prevStep.latitude!!, prevStep.longitude!!,
                currStep.latitude!!, currStep.longitude!!,
                roadCurveFactor
            )

            // 제자리 반복 촬영(50m 미만)은 이동 누적에서 제외
            if (dist >= 0.05) {
                totalDistanceKm += dist
                prevStep = currStep
            }
        }

        return Math.round(totalDistanceKm * 10.0) / 10.0
    }

    /**
     * 연속된 DrivingWaypoint 목록 간의 누적 도로 주행거리(km) 계산
     * - GPS와 기지국(NETWORK) 교차 수신으로 인한 핑퐁(A -> B -> A) 스파이크 이상치 제거
     * - 비현실적 순간 이동 속도(42m/s ≈ 151km/h 초과) 점프 배제
     * - 촘촘한 연속 GPS 구간(< 800m)은 이미 도로 굴곡을 따라 수집되므로 직선 거리를 그대로 합산하고,
     *   터널 등 장거리 공백 구간(>= 800m)에만 도로 굴곡 계수(roadCurveFactor) 적용
     */
    fun calculateWaypointsDistanceKm(
        waypoints: List<DrivingWaypoint>,
        roadCurveFactor: Double = DEFAULT_ROAD_CURVE_FACTOR
    ): Double {
        val validPoints = waypoints.filter { isValidCoordinate(it.latitude, it.longitude) }
        if (validPoints.size < 2) return 0.0

        // 1단계: A -> B -> A 형태의 기지국/위성 교차 핑퐁(Zig-Zag) 노이즈 포인트 제거
        val smoothedPoints = mutableListOf<DrivingWaypoint>()
        for (pt in validPoints) {
            if (smoothedPoints.size >= 2) {
                val prev = smoothedPoints.last()
                val anchor = smoothedPoints[smoothedPoints.size - 2]
                val distAnchorToPrev = calculateStraightDistanceKm(anchor.latitude, anchor.longitude, prev.latitude, prev.longitude)
                val distPrevToCurr = calculateStraightDistanceKm(prev.latitude, prev.longitude, pt.latitude, pt.longitude)
                val distAnchorToCurr = calculateStraightDistanceKm(anchor.latitude, anchor.longitude, pt.latitude, pt.longitude)

                // 이전 점(prev)이 앞뒤 점(anchor, pt) 대비 비정상적으로 튀었다가 제자리 부근으로 돌아온 경우 prev 제거
                val isPingPongSpike = distAnchorToPrev >= 0.03 &&
                    distPrevToCurr >= 0.03 &&
                    distAnchorToCurr < minOf(distAnchorToPrev, distPrevToCurr) * 0.65
                if (isPingPongSpike) {
                    smoothedPoints.removeAt(smoothedPoints.lastIndex)
                }
            }
            smoothedPoints.add(pt)
        }

        if (smoothedPoints.size < 2) return 0.0

        // 2단계: 속도 검증 및 구간별 거리 누적
        var totalDistanceKm = 0.0
        var prev = smoothedPoints.first()

        for (i in 1 until smoothedPoints.size) {
            val curr = smoothedPoints[i]
            val straightKm = calculateStraightDistanceKm(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude
            )

            // 25m(0.025km) 미만의 미세 흔들림은 정차 중 GPS 드리프트로 간주하여 스킵
            if (straightKm < 0.025) continue

            // 타임스탬프가 존재하는 경우 순간 이동 속도 검증 (42m/s ≈ 151km/h 초과 시 GPS/기지국 점프로 배제)
            val deltaMs = curr.timestamp - prev.timestamp
            if (deltaMs > 0L) {
                val deltaSec = deltaMs / 1000.0
                val speedMps = (straightKm * 1000.0) / deltaSec
                if (speedMps > 42.0) {
                    continue
                }
            }

            // 800m 미만의 촘촘한 GPS 샘플 구간은 이미 도로 궤적을 따라가므로 1.0배 적용,
            // 800m 이상의 긴 음영/누락 구간에만 도로 굴곡 계수 적용
            val segmentKm = if (straightKm < 0.8) straightKm else straightKm * roadCurveFactor
            totalDistanceKm += segmentKm
            prev = curr
        }

        val rounded = Math.round(totalDistanceKm * 10.0) / 10.0
        return if (rounded == 0.0 && totalDistanceKm >= 0.05) 0.1 else rounded
    }

    /**
     * 유효한 위경도 좌표인지 검증 (0.0 부근 Null Island 더미 및 비정상 범위 배제)
     */
    fun isValidCoordinate(lat: Double, lon: Double): Boolean {
        if (abs(lat) < 0.0001 && abs(lon) < 0.0001) return false
        return lat in -90.0..90.0 && lon in -180.0..180.0
    }
}

/**
 * 백그라운드 GPS 위치 수집 포인트 (10분 단위 및 출발/도착)
 */
data class DrivingWaypoint(
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double,
    val longitude: Double,
    val address: String? = null,
    val isDeparture: Boolean = false,
    val isDestination: Boolean = false
)
