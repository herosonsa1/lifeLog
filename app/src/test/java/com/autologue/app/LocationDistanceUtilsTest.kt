package com.autologue.app

import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.util.LocationDistanceUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class LocationDistanceUtilsTest {

    @Test
    fun testStraightAndDrivingDistanceBetweenJudeokAndBangyi() {
        // 충청북도 충주시 주덕읍 화곡리 좌표 (예: 킹스데일 GC 부근)
        val lat1 = 36.974
        val lon1 = 127.798

        // 서울특별시 송파구 방이동 좌표
        val lat2 = 37.5145
        val lon2 = 127.1058

        val straightKm = LocationDistanceUtils.calculateStraightDistanceKm(lat1, lon1, lat2, lon2)
        val drivingKm = LocationDistanceUtils.calculateDrivingDistanceKm(lat1, lon1, lat2, lon2)

        // 직선 거리는 약 80~100km 내외
        assertTrue("직선 거리는 80km 이상 100km 이하여야 함 (실제: $straightKm)", straightKm in 80.0..100.0)

        // 도로 굴곡도 1.25배 적용 시 약 100~130km 내외 (실제 고속도로 이동거리 약 115km)
        assertTrue("추정 도로 주행거리는 100km 이상 130km 이하여야 함 (실제: $drivingKm)", drivingKm in 100.0..130.0)
    }

    @Test
    fun testRouteDrivingDistanceAccumulation() {
        val steps = listOf(
            RouteStep(
                id = "1",
                time = LocalDateTime.of(2026, 9, 7, 6, 30),
                stepType = RouteStepType.PHOTO,
                title = "충청북 주덕읍",
                locationName = "충청북 주덕읍",
                address = "충청북도 충주시 주덕읍 화곡리 1091",
                latitude = 36.974,
                longitude = 127.798
            ),
            RouteStep(
                id = "2",
                time = LocalDateTime.of(2026, 9, 7, 7, 0),
                stepType = RouteStepType.PHOTO,
                title = "충청북 주덕읍 제자리 사진",
                locationName = "충청북 주덕읍",
                address = "충청북도 충주시 주덕읍 화곡리 1091",
                latitude = 36.97401, // 10미터 이내 제자리 촬영
                longitude = 127.79801
            ),
            RouteStep(
                id = "3",
                time = LocalDateTime.of(2026, 9, 7, 12, 26),
                stepType = RouteStepType.PHOTO,
                title = "서울 방이동",
                locationName = "서울 방이동",
                address = "서울특별시 송파구 방이동",
                latitude = 37.5145,
                longitude = 127.1058
            )
        )

        val totalDist = LocationDistanceUtils.calculateRouteDrivingDistanceKm(steps)

        // 제자리 촬영은 누적되지 않고 주덕읍 -> 방이동 1회 도로 주행거리만 합산되어야 함
        assertTrue("누적 주행거리는 100km 이상 130km 이하여야 함 (실제: $totalDist)", totalDist in 100.0..130.0)
    }

    @Test
    fun testPeriodicWaypointsDistanceCalculation() {
        // 서울 송파구 잠실 ➔ 성남 판교 ➔ 용인 수지 (10분 간격 GPS 수집 시뮬레이션)
        val waypoints = listOf(
            com.autologue.app.util.DrivingWaypoint(
                timestamp = 1000L,
                latitude = 37.5133,
                longitude = 127.1001,
                isDeparture = true
            ),
            com.autologue.app.util.DrivingWaypoint(
                timestamp = 601000L, // 10분 후: 성남 분당/판교 부근
                latitude = 37.3948,
                longitude = 127.1112
            ),
            com.autologue.app.util.DrivingWaypoint(
                timestamp = 1201000L, // 20분 후: 용인 수지 부근 (도착)
                latitude = 37.3236,
                longitude = 127.0987,
                isDestination = true
            )
        )

        val totalDist = LocationDistanceUtils.calculateWaypointsDistanceKm(waypoints)

        // 잠실 -> 판교 (약 13km) + 판교 -> 수지 (약 8km) = 약 21km * 1.25(곡률) = 약 25~30km
        assertTrue("10분 주기 GPS 누적 거리는 20km 이상 35km 이하여야 함 (실제: $totalDist)", totalDist in 20.0..35.0)
    }

    @Test
    fun testParkingWaypointsNotAccumulated() {
        // 제자리 정차/신호대기 중(50m 미만) 10분 간격 반복 수신 시 거리 중복 누적 방지 검증
        val waypoints = listOf(
            com.autologue.app.util.DrivingWaypoint(timestamp = 1000L, latitude = 37.5133, longitude = 127.1001, isDeparture = true),
            com.autologue.app.util.DrivingWaypoint(timestamp = 601000L, latitude = 37.51332, longitude = 127.10011), // 2m 이동 (신호대기)
            com.autologue.app.util.DrivingWaypoint(timestamp = 1201000L, latitude = 37.51331, longitude = 127.10012, isDestination = true) // 3m 이동
        )

        val totalDist = LocationDistanceUtils.calculateWaypointsDistanceKm(waypoints)
        assertEquals(0.0, totalDist, 0.01)
    }
}
