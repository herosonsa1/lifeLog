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
     * - 10분 단위 GPS 수신 위치들을 순차 연결
     * - 50m 미만의 제자리 정차/신호대기 오차는 중복 합산 방지
     */
    fun calculateWaypointsDistanceKm(
        waypoints: List<DrivingWaypoint>,
        roadCurveFactor: Double = DEFAULT_ROAD_CURVE_FACTOR
    ): Double {
        val validPoints = waypoints.filter {
            it.latitude != 0.0 && it.longitude != 0.0
        }
        if (validPoints.size < 2) return 0.0

        var totalDistanceKm = 0.0
        var prev = validPoints.first()

        for (i in 1 until validPoints.size) {
            val curr = validPoints[i]
            val dist = calculateDrivingDistanceKm(
                prev.latitude, prev.longitude,
                curr.latitude, curr.longitude,
                roadCurveFactor
            )
            if (dist >= 0.05) {
                totalDistanceKm += dist
                prev = curr
            }
        }
        return Math.round(totalDistanceKm * 10.0) / 10.0
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
