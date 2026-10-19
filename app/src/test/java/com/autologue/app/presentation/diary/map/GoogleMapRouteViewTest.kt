package com.autologue.app.presentation.diary.map

import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class GoogleMapRouteViewTest {

    @Test
    fun `ensureStepCoordinates infers Korean district coordinates when GPS is missing`() {
        val homeStepWithoutGps = RouteStep(
            id = "step_home",
            time = LocalDateTime.of(2026, 4, 15, 7, 17),
            title = "집 (더샵 포레스트) 승차 (출발)",
            locationName = "집 (더샵 포레스트) 승차 (출발)",
            address = "서울특별시 강남구 광평로34길 55 (수서동)",
            latitude = null,
            longitude = null,
            stepType = RouteStepType.DRIVING
        )
        val yeongdeungpoStepWithoutGps = RouteStep(
            id = "step_ydp",
            time = LocalDateTime.of(2026, 4, 15, 8, 9),
            title = "서울 영등포구 하차",
            locationName = "서울 영등포구 하차",
            address = "서울특별시 영등포구 영등포동",
            latitude = 0.0,
            longitude = 0.0,
            stepType = RouteStepType.DRIVING
        )

        val resolvedHome = ensureStepCoordinates(homeStepWithoutGps)
        val resolvedYdp = ensureStepCoordinates(yeongdeungpoStepWithoutGps)

        assertNotNull(resolvedHome.latitude)
        assertNotNull(resolvedHome.longitude)
        assertEquals(37.4847, resolvedHome.latitude!!, 0.01)
        assertEquals(127.0945, resolvedHome.longitude!!, 0.01)

        assertNotNull(resolvedYdp.latitude)
        assertNotNull(resolvedYdp.longitude)
        assertEquals(37.5172, resolvedYdp.latitude!!, 0.01)
        assertEquals(126.9068, resolvedYdp.longitude!!, 0.01)
    }

    @Test
    fun `segmentRouteIntoTrips pairs 4-stop commute into 2 round-trip sessions and keeps all 4 stops`() {
        val steps = listOf(
            RouteStep(
                id = "1",
                time = LocalDateTime.of(2026, 4, 15, 7, 17),
                title = "집 (더샵 포레스트) 승차 (출발)",
                locationName = "집 (더샵 포레스트) 승차 (출발)",
                address = "서울특별시 강남구 광평로34길 55",
                latitude = 37.4847,
                longitude = 127.0945,
                stepType = RouteStepType.DRIVING,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "2",
                time = LocalDateTime.of(2026, 4, 15, 8, 9),
                title = "서울 영등포구 하차",
                locationName = "서울 영등포구 하차",
                address = "서울특별시 영등포구 영등포동",
                latitude = 37.5172,
                longitude = 126.9068,
                stepType = RouteStepType.DRIVING,
                tags = listOf("하차", "도착지점")
            ),
            RouteStep(
                id = "3",
                time = LocalDateTime.of(2026, 4, 15, 22, 42),
                title = "서울 영등포구 승차 (출발)",
                locationName = "서울 영등포구 승차 (출발)",
                address = "서울특별시 영등포구 영등포동",
                latitude = 37.5172,
                longitude = 126.9068,
                stepType = RouteStepType.DRIVING,
                tags = listOf("승차", "출발지점")
            ),
            RouteStep(
                id = "4",
                time = LocalDateTime.of(2026, 4, 15, 23, 18),
                title = "집 (더샵 포레스트) 하차",
                locationName = "집 (더샵 포레스트) 하차",
                address = "서울특별시 강남구 광평로34길 55",
                latitude = 37.4847,
                longitude = 127.0945,
                stepType = RouteStepType.DRIVING,
                tags = listOf("하차", "도착지점")
            )
        )

        val groups = segmentRouteIntoTrips(steps)
        assertEquals(2, groups.size)
        assertEquals(2, groups[0].steps.size)
        assertEquals(2, groups[1].steps.size)

        val endpoints = resolveEffectiveRouteEndpoints(steps)
        assertNotNull(endpoints)
        assertEquals("1", endpoints!!.first.id)
        assertTrue(endpoints.second.id == "2" || endpoints.second.id == "3")
    }
}
