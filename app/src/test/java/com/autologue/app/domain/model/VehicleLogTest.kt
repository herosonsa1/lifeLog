package com.autologue.app.domain.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class VehicleLogTest {

    @Test
    fun testGetDrivingRouteTitle_omitsDurationFromTitleAndKeepsDurationInSubtitle() {
        val routeLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 9, 21, 9, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[송파구 위례동 ➔ 영등포구 영등포동 (출근 27.1 km)] (75분 운행 · GPS 14개 지점)"
        )
        assertEquals("집 ➔ 회사", routeLog.getDrivingRouteTitle())
        assertEquals("1시간 15분 운행 · GPS 14개 지점", routeLog.getDrivingDetailSubtitle())

        val weekendOutingLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 9, 20, 14, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 18.5,
            note = "송파구 위례동 ➔ 하남시 신장동"
        )
        assertEquals("위례동 ➔ 신장동", weekendOutingLog.getDrivingRouteTitle())

        val poiOutingLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 9, 20, 16, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 7.8,
            note = "[위례더샵포레스트 정문 ➔ 스타필드 하남 (7.8 km)] (22분 운행 · GPS 6개 지점)"
        )
        assertEquals("위례더샵포레스트 정문 ➔ 스타필드 하남", poiOutingLog.getDrivingRouteTitle())
        assertEquals("22분 운행 · GPS 6개 지점", poiOutingLog.getDrivingDetailSubtitle())
    }

    @Test
    fun testHome7101DongUnitStrippingSinglePointAndWatchdogSuppression() {
        val homeToStopLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 10, 25, 11, 0), // 일요일 외출
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 4.3,
            note = "[car_1: 벤츠] [7101동 2502호 ➔ 위례포레자이.위례더샵포레스트 정류장] (13분 운행 · GPS 4개 지점)"
        )
        assertEquals("집 ➔ 위례포레자이 정류장", homeToStopLog.getDrivingRouteTitle())
        assertFalse(homeToStopLog.displayTitle().contains("2502호"))
        assertEquals("13분 운행 · GPS 4개 지점", homeToStopLog.getDrivingDetailSubtitle())

        // GPS 1개 지점 폴백('X ➔ X')은 가짜 왕복 대신 '인근 주행'으로 표기
        val singlePointLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 10, 28, 12, 5),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 4.3,
            note = "[car_1: 벤츠] [영등포역,타임스퀘어 정류장 ➔ 영등포역,타임스퀘어 정류장] (13분 운행 · GPS 1개 지점)"
        )
        assertEquals("영등포역 정류장 인근 주행", singlePointLog.getDrivingRouteTitle())
        assertEquals("13분 운행 · GPS 1개 지점", singlePointLog.getDrivingDetailSubtitle())

        // 구형 21분 워치독 조기 절단 버그 출퇴근 기록은 잘못된 '21분 운행'을 숨기는 데 그치지 않고
        // 현실적인 1시간 이상 출퇴근 운행시간으로 자동 복원하여 빈칸이 생기지 않도록 보장
        val truncatedCommuteLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 10, 28, 6, 49),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[car_1: 벤츠] [집 ➔ 회사] 출근 (21분 운행 · GPS 2개 지점)"
        )
        assertTrue(truncatedCommuteLog.isWatchdogTruncatedDurationLog())
        assertNull(truncatedCommuteLog.extractDrivingDurationMinutes())
        val effectiveMin = truncatedCommuteLog.resolveEffectiveDrivingDurationMinutes()
        assertNotNull(effectiveMin)
        assertTrue(effectiveMin!! >= 60)
        assertEquals("집 ➔ 회사", truncatedCommuteLog.getDrivingRouteTitle())
        val restoredSubtitle = truncatedCommuteLog.getDrivingDetailSubtitle()
        assertNotNull(restoredSubtitle)
        assertFalse(restoredSubtitle!!.contains("21분"))
        assertTrue(restoredSubtitle.contains("1시간"))
        assertTrue(restoredSubtitle.endsWith("운행 · GPS 2개 지점"))

        // 이전 버전에서 운행시간 문자열이 제거되어 '(GPS 2개 지점)'만 남았던 레코드도 즉시 복원
        val wipedCommuteLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 10, 27, 17, 38),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[car_1: 벤츠] [회사 ➔ 집] 퇴근 (GPS 2개 지점)"
        )
        val wipedSub = wipedCommuteLog.getDrivingDetailSubtitle()
        assertNotNull(wipedSub)
        assertTrue(wipedSub!!.contains("1시간"))
        assertTrue(wipedSub.endsWith("운행 · GPS 2개 지점"))
        assertNotEquals(restoredSubtitle, wipedSub)

        val healedNote = upsertDurationIntoVehicleNote(
            wipedCommuteLog.note!!,
            wipedCommuteLog.resolveEffectiveDrivingDurationMinutes()!!
        )
        assertTrue(healedNote.contains("1시간"))
        assertTrue(healedNote.contains("운행 · GPS 2개 지점)"))
        assertFalse(healedNote.contains(") ("))

        // 비출퇴근 일반 외곽/고속도로 주행의 실제 21분 운행은 절단으로 오판하지 않고 그대로 보존
        val realHighway21MinLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 10, 25, 14, 20),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 18.5,
            note = "[car_1: 벤츠] [위례더샵포레스트 정문 ➔ 미사경정공원] (21분 운행 · GPS 2개 지점)"
        )
        assertFalse(realHighway21MinLog.isWatchdogTruncatedDurationLog())
        assertEquals("21분 운행 · GPS 2개 지점", realHighway21MinLog.getDrivingDetailSubtitle())

        // 시간 표기가 아예 없던 수동/레거시 주행 기록도 반드시 운행시간 표기 보장
        val manualNoDurationLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 10, 23, 19, 10),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 8.4,
            note = "[위례동 ➔ 문정동]"
        )
        val manualSub = manualNoDurationLog.getDrivingDetailSubtitle()
        assertNotNull(manualSub)
        assertTrue(manualSub!!.contains("운행"))
    }

    @Test
    fun testLegacyGolfDrivingOverride_fallsBackToGeneralDriving() {
        val legacyOverrideLog = VehicleLog(
            timestamp = LocalDateTime.of(2026, 9, 19, 6, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 65.0,
            note = "[user_type_override:GOLF_DRIVING] 일반 외출 주행"
        )
        assertEquals(CarLogItemCategory.GENERAL_DRIVING, legacyOverrideLog.getUserTypeOverride())
        assertEquals(CarLogItemCategory.GENERAL_DRIVING, legacyOverrideLog.resolveCarLogItemCategory())
    }
}
