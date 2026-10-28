package com.autologue.app.domain.model

import com.autologue.app.util.KoreanHolidayUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class VehicleLogHolidayAndCategoryTest {

    @Test
    fun `평일_비공휴일에는_출근과_퇴근이_정상_적용된다`() {
        // 2026-10-20 (화요일, 평일)
        val weekdayMorning = LocalDateTime.of(2026, 10, 20, 8, 15)
        val toWorkLog = VehicleLog(
            id = 1L,
            timestamp = weekdayMorning,
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[car_1: G80] [출근 주행: 송파구 위례동 ➔ 영등포구 영등포동 (27.1 km)]"
        )
        assertTrue(KoreanHolidayUtils.isCommuteEligibleWorkday(weekdayMorning.toLocalDate()))
        assertTrue(toWorkLog.isToWorkCommuteLog())
        assertTrue(toWorkLog.isCommuteDrivingLog())
        assertFalse(toWorkLog.isNonCommuteDrivingLog())
        assertEquals(CarLogItemCategory.COMMUTE_TO_WORK, toWorkLog.resolveCarLogItemCategory())
        assertEquals("집 ➔ 회사", toWorkLog.displayTitle())

        val toWorkLogWithDuration = toWorkLog.copy(
            note = "[car_1: G80] [출근 주행: 송파구 위례동 ➔ 영등포구 영등포동 (27.1 km)] (45분 운행)"
        )
        assertEquals("집 ➔ 회사", toWorkLogWithDuration.displayTitle())
        assertEquals("45분 운행", toWorkLogWithDuration.getDrivingDetailSubtitle())
    }

    @Test
    fun `주말과_법정공휴일에는_기본적으로_출퇴근이_적용되지_않고_일반주행으로_분류된다`() {
        // 1. 2026-10-09 (금요일, 한글날 법정공휴일)
        val hangulDayLog = VehicleLog(
            id = 2L,
            timestamp = LocalDateTime.of(2026, 10, 9, 8, 30),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[car_1: G80] [출근 주행: 송파구 위례동 ➔ 영등포구 영등포동 (27.1 km)]"
        )
        assertTrue(KoreanHolidayUtils.isKoreanPublicHoliday(LocalDate.of(2026, 10, 9)))
        assertFalse(KoreanHolidayUtils.isCommuteEligibleWorkday(LocalDate.of(2026, 10, 9)))
        assertFalse(hangulDayLog.isToWorkCommuteLog())
        assertFalse(hangulDayLog.isCommuteDrivingLog())
        assertTrue(hangulDayLog.isNonCommuteDrivingLog())
        assertEquals(CarLogItemCategory.GENERAL_DRIVING, hangulDayLog.resolveCarLogItemCategory())

        // 2. 2026-10-05 (월요일, 개천절 대체공휴일)
        val subHolidayDate = LocalDate.of(2026, 10, 5)
        assertTrue(KoreanHolidayUtils.isKoreanPublicHoliday(subHolidayDate))
        assertFalse(KoreanHolidayUtils.isCommuteEligibleWorkday(subHolidayDate))

        // 3. 주말 (토요일: 2026-10-17)
        val saturdayLog = VehicleLog(
            id = 3L,
            timestamp = LocalDateTime.of(2026, 10, 17, 18, 30),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[car_1: G80] [퇴근 주행: 영등포구 영등포동 ➔ 송파구 위례동 (27.1 km)]"
        )
        assertFalse(saturdayLog.isToHomeCommuteLog())
        assertFalse(saturdayLog.isCommuteDrivingLog())
        assertTrue(saturdayLog.isNonCommuteDrivingLog())
    }

    @Test
    fun `사용자가_수정기능으로_항목을_변경하면_공휴일이나_평일_여부와_무관하게_사용자_지정_항목으로_적용된다`() {
        // 공휴일(2026-10-09 한글날) 주행 기록을 사용자가 '출근 주행'으로 수정한 경우
        val holidayLog = VehicleLog(
            id = 10L,
            timestamp = LocalDateTime.of(2026, 10, 9, 8, 30),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 27.1,
            note = "[car_1: G80] [송파구 위례동 ➔ 영등포구 영등포동 (27.1 km)]"
        )
        val updatedNoteToWork = holidayLog.buildUpdatedNote(
            targetVehicleId = "car_1",
            vehicleShortName = "G80",
            unitPrice = null,
            isCustom = false,
            userTypeOverride = CarLogItemCategory.COMMUTE_TO_WORK,
            customRouteTitle = null
        )
        val overriddenHolidayLog = holidayLog.copy(note = updatedNoteToWork)

        assertTrue(overriddenHolidayLog.isToWorkCommuteLog())
        assertTrue(overriddenHolidayLog.isCommuteDrivingLog())
        assertFalse(overriddenHolidayLog.isNonCommuteDrivingLog())
        assertEquals(CarLogItemCategory.COMMUTE_TO_WORK, overriddenHolidayLog.resolveCarLogItemCategory())
        assertEquals("집 ➔ 회사", overriddenHolidayLog.displayTitle())

        // 평일 출근 기록을 사용자가 '일반/외출 주행' 및 커스텀 경로로 수정한 경우
        val weekdayCommuteLog = VehicleLog(
            id = 11L,
            timestamp = LocalDateTime.of(2026, 10, 20, 8, 30),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 18.5,
            note = "[car_1: G80] [출근 주행: 송파구 위례동 ➔ 영등포구 영등포동 (27.1 km)]"
        )
        val updatedNoteGeneral = weekdayCommuteLog.buildUpdatedNote(
            targetVehicleId = "car_1",
            vehicleShortName = "G80",
            unitPrice = null,
            isCustom = false,
            userTypeOverride = CarLogItemCategory.GENERAL_DRIVING,
            customRouteTitle = "송파구 위례동 ➔ 강남구 역삼동 외출"
        )
        val overriddenGeneralLog = weekdayCommuteLog.copy(note = updatedNoteGeneral)

        assertFalse(overriddenGeneralLog.isToWorkCommuteLog())
        assertFalse(overriddenGeneralLog.isCommuteDrivingLog())
        assertTrue(overriddenGeneralLog.isNonCommuteDrivingLog())
        assertEquals(CarLogItemCategory.GENERAL_DRIVING, overriddenGeneralLog.resolveCarLogItemCategory())
        assertEquals("송파구 위례동 ➔ 강남구 역삼동 외출", overriddenGeneralLog.displayTitle())
    }
}
