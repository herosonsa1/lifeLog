package com.autologue.app.domain.model

import com.autologue.app.util.GolfCourseDistanceUtils
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

class VehicleLogTest {

    @Test
    fun testGetDrivingRouteTitle_golfCourses() {
        val wolsongriLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 9, 19, 6, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 130.0,
            note = "월송리 CC 라운딩 왕복 주행"
        )
        assertEquals("월송리 CC 왕복 주행 (130.0 km)", wolsongriLog.getDrivingRouteTitle())

        val oakValleyLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 9, 11, 6, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 130.0,
            note = "오크밸리 CC 라운딩 왕복 주행"
        )
        assertEquals("오크밸리 CC 왕복 주행 (130.0 km)", oakValleyLog.getDrivingRouteTitle())

        val kingsdaleLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 9, 17, 5, 30),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 160.0,
            note = "킹스데일 GC 라운딩 왕복 주행"
        )
        assertEquals("킹스데일 GC 왕복 주행 (160.0 km)", kingsdaleLog.getDrivingRouteTitle())
    }

    @Test
    fun testGolfCourseDistanceUtils_estimations() {
        assertEquals(130.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("월송리 CC"), 0.01)
        assertEquals(130.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("오크밸리 CC"), 0.01)
        assertEquals(160.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("킹스데일 GC"), 0.01)
        assertEquals(120.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("필로스 GC"), 0.01)
        assertEquals(190.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("라데나 GC"), 0.01)
        assertEquals(160.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("아리지 CC"), 0.01)
        assertEquals(280.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("동강시스타 CC"), 0.01)
        assertEquals(130.0, GolfCourseDistanceUtils.getEstimatedRoundTripKm("알 수 없는 골프장"), 0.01)
    }
}
