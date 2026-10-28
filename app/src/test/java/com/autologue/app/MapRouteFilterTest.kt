package com.autologue.app

import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.presentation.diary.map.MapPointDisplayMode
import com.autologue.app.presentation.diary.map.filterOutCommuteSteps
import com.autologue.app.presentation.diary.map.isCommuteStep
import com.autologue.app.presentation.diary.map.resolveEffectivePointDisplayMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class MapRouteFilterTest {

    @Test
    fun `resolveEffectivePointDisplayMode switches automatically by step density`() {
        assertEquals(
            MapPointDisplayMode.FULL,
            resolveEffectivePointDisplayMode(MapPointDisplayMode.AUTO, 2)
        )
        assertEquals(
            MapPointDisplayMode.FULL,
            resolveEffectivePointDisplayMode(MapPointDisplayMode.AUTO, 5)
        )
        assertEquals(
            MapPointDisplayMode.COMPACT,
            resolveEffectivePointDisplayMode(MapPointDisplayMode.AUTO, 6)
        )
        assertEquals(
            MapPointDisplayMode.COMPACT,
            resolveEffectivePointDisplayMode(MapPointDisplayMode.AUTO, 12)
        )
        assertEquals(
            MapPointDisplayMode.DOT_ONLY,
            resolveEffectivePointDisplayMode(MapPointDisplayMode.AUTO, 13)
        )
        assertEquals(
            MapPointDisplayMode.DOT_ONLY,
            resolveEffectivePointDisplayMode(MapPointDisplayMode.DOT_ONLY, 2)
        )
    }

    @Test
    fun `filterOutCommuteSteps excludes home-office commute and preserves outing and photo steps`() {
        val morningDep = RouteStep(
            id = "c1",
            time = LocalDateTime.of(2026, 10, 20, 6, 26),
            stepType = RouteStepType.DRIVING,
            title = "⭐ [벤츠] 승차 (출발)",
            description = "승차: 하남시 학암동 ➔ 영등포구 영등포동",
            locationName = "하남시 학암동",
            address = "대한민국 경기도 하남시 학암동 652",
            latitude = 37.4785,
            longitude = 127.1465,
            tags = listOf("차량주행", "⭐ 벤츠", "승차", "출발지점")
        )
        val morningArr = RouteStep(
            id = "c2",
            time = LocalDateTime.of(2026, 10, 20, 7, 12),
            stepType = RouteStepType.DRIVING,
            title = "⭐ [벤츠] 하차 (총 27.1 km)",
            description = "하남시 학암동 ➔ 영등포구 영등포동 (46분 운행 · 27.1 km)",
            locationName = "영등포구 영등포동",
            address = "서울특별시 영등포구 영등포로 254",
            latitude = 37.5172,
            longitude = 126.9068,
            tags = listOf("차량주행", "⭐ 벤츠", "하차", "도착지점")
        )
        val outingDep = RouteStep(
            id = "o1",
            time = LocalDateTime.of(2026, 10, 21, 11, 0),
            stepType = RouteStepType.DRIVING,
            title = "⭐ [벤츠] 승차 (출발)",
            description = "승차: 하남시 학암동 ➔ 강남구 역삼동",
            locationName = "하남시 학암동",
            latitude = 37.4785,
            longitude = 127.1465,
            tags = listOf("차량주행", "승차")
        )
        val outingArr = RouteStep(
            id = "o2",
            time = LocalDateTime.of(2026, 10, 21, 11, 35),
            stepType = RouteStepType.DRIVING,
            title = "⭐ [벤츠] 하차 (총 12.4 km)",
            description = "하남시 학암동 ➔ 강남구 역삼동 (35분 운행 · 12.4 km)",
            locationName = "강남구 역삼동",
            latitude = 37.5006,
            longitude = 127.0365,
            tags = listOf("차량주행", "하차")
        )
        val photoStep = RouteStep(
            id = "p1",
            time = LocalDateTime.of(2026, 10, 18, 14, 30),
            stepType = RouteStepType.PHOTO,
            title = "춘천 나들이 사진",
            locationName = "강원 춘천시",
            latitude = 37.8813,
            longitude = 127.7298
        )

        val allSteps = listOf(morningDep, morningArr, outingDep, outingArr, photoStep)

        assertTrue(isCommuteStep(morningDep, allSteps))
        assertTrue(isCommuteStep(morningArr, allSteps))
        assertFalse(isCommuteStep(outingDep, allSteps))
        assertFalse(isCommuteStep(outingArr, allSteps))
        assertFalse(isCommuteStep(photoStep, allSteps))

        val filtered = filterOutCommuteSteps(allSteps)
        assertEquals(listOf("o1", "o2", "p1"), filtered.map { it.id })
    }
}
