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
}
