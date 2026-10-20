package com.autologue.app.util

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.MonthDay

/**
 * 대한민국 주말 및 법정공휴일(설·추석 연휴, 양력 국경일, 대체공휴일, 선거일 등) 판별 유틸리티.
 * 기본적으로 출근·퇴근 자동 판별은 주말과 공휴일을 제외한 평일에만 적용됩니다.
 */
object KoreanHolidayUtils {

    private val FIXED_SOLAR_HOLIDAYS: Set<MonthDay> = setOf(
        MonthDay.of(1, 1),   // 신정
        MonthDay.of(3, 1),   // 삼일절
        MonthDay.of(5, 5),   // 어린이날
        MonthDay.of(6, 6),   // 현충일
        MonthDay.of(8, 15),  // 광복절
        MonthDay.of(10, 3),  // 개천절
        MonthDay.of(10, 9),  // 한글날
        MonthDay.of(12, 25)  // 성탄절
    )

    // 대체공휴일 적용 대상 양력 공휴일 (토/일요일과 겹칠 경우 익주 첫 번째 비공휴일 평일이 대체공휴일)
    private val SUBSTITUTE_ELIGIBLE_SOLAR: Set<MonthDay> = setOf(
        MonthDay.of(3, 1),   // 삼일절
        MonthDay.of(5, 5),   // 어린이날
        MonthDay.of(8, 15),  // 광복절
        MonthDay.of(10, 3),  // 개천절
        MonthDay.of(10, 9),  // 한글날
        MonthDay.of(12, 25)  // 성탄절
    )

    // 연도별 음력 공휴일(설날 연휴, 부처님오신날, 추석 연휴), 대체공휴일, 임시공휴일 및 공직선거일 테이블 (2023~2030)
    private val YEAR_SPECIFIC_HOLIDAYS: Map<Int, Set<LocalDate>> = mapOf(
        2023 to setOf(
            LocalDate.of(2023, 1, 21), LocalDate.of(2023, 1, 22), LocalDate.of(2023, 1, 23), LocalDate.of(2023, 1, 24), // 설날 연휴 및 대체공휴일
            LocalDate.of(2023, 5, 27), LocalDate.of(2023, 5, 29), // 부처님오신날 및 대체공휴일
            LocalDate.of(2023, 9, 28), LocalDate.of(2023, 9, 29), LocalDate.of(2023, 9, 30), LocalDate.of(2023, 10, 2) // 추석 연휴 및 임시공휴일
        ),
        2024 to setOf(
            LocalDate.of(2024, 2, 9), LocalDate.of(2024, 2, 10), LocalDate.of(2024, 2, 11), LocalDate.of(2024, 2, 12), // 설날 연휴 및 대체공휴일
            LocalDate.of(2024, 4, 10), // 제22대 국회의원선거
            LocalDate.of(2024, 5, 6),  // 어린이날 대체공휴일
            LocalDate.of(2024, 5, 15), // 부처님오신날
            LocalDate.of(2024, 9, 16), LocalDate.of(2024, 9, 17), LocalDate.of(2024, 9, 18), // 추석 연휴
            LocalDate.of(2024, 10, 1)  // 국군의날 임시공휴일
        ),
        2025 to setOf(
            LocalDate.of(2025, 1, 27), LocalDate.of(2025, 1, 28), LocalDate.of(2025, 1, 29), LocalDate.of(2025, 1, 30), // 설날 연휴 및 임시공휴일
            LocalDate.of(2025, 3, 3),  // 삼일절 대체공휴일
            LocalDate.of(2025, 5, 5), LocalDate.of(2025, 5, 6), // 어린이날·부처님오신날 및 대체공휴일
            LocalDate.of(2025, 6, 3),  // 대통령선거일
            LocalDate.of(2025, 10, 5), LocalDate.of(2025, 10, 6), LocalDate.of(2025, 10, 7), LocalDate.of(2025, 10, 8) // 추석 연휴 및 대체공휴일
        ),
        2026 to setOf(
            LocalDate.of(2026, 2, 16), LocalDate.of(2026, 2, 17), LocalDate.of(2026, 2, 18), // 설날 연휴
            LocalDate.of(2026, 3, 2),  // 삼일절 대체공휴일
            LocalDate.of(2026, 5, 24), LocalDate.of(2026, 5, 25), // 부처님오신날 및 대체공휴일
            LocalDate.of(2026, 6, 3),  // 제9회 전국동시지방선거
            LocalDate.of(2026, 8, 17), // 광복절 대체공휴일
            LocalDate.of(2026, 9, 24), LocalDate.of(2026, 9, 25), LocalDate.of(2026, 9, 26), // 추석 연휴
            LocalDate.of(2026, 10, 5)  // 개천절 대체공휴일
        ),
        2027 to setOf(
            LocalDate.of(2027, 2, 6), LocalDate.of(2027, 2, 7), LocalDate.of(2027, 2, 8), LocalDate.of(2027, 2, 9), // 설날 연휴 및 대체공휴일
            LocalDate.of(2027, 5, 13), // 부처님오신날
            LocalDate.of(2027, 8, 16), // 광복절 대체공휴일
            LocalDate.of(2027, 9, 14), LocalDate.of(2027, 9, 15), LocalDate.of(2027, 9, 16), // 추석 연휴
            LocalDate.of(2027, 10, 4), // 개천절 대체공휴일
            LocalDate.of(2027, 10, 11), // 한글날 대체공휴일
            LocalDate.of(2027, 12, 27) // 성탄절 대체공휴일
        ),
        2028 to setOf(
            LocalDate.of(2028, 1, 26), LocalDate.of(2028, 1, 27), LocalDate.of(2028, 1, 28), // 설날 연휴
            LocalDate.of(2028, 4, 12), // 제23대 국회의원선거
            LocalDate.of(2028, 5, 2),  // 부처님오신날
            LocalDate.of(2028, 10, 2), LocalDate.of(2028, 10, 3), LocalDate.of(2028, 10, 4), LocalDate.of(2028, 10, 5) // 추석 연휴 및 개천절 대체공휴일
        ),
        2029 to setOf(
            LocalDate.of(2029, 2, 12), LocalDate.of(2029, 2, 13), LocalDate.of(2029, 2, 14), // 설날 연휴
            LocalDate.of(2029, 5, 7),  // 어린이날 대체공휴일
            LocalDate.of(2029, 5, 20), LocalDate.of(2029, 5, 21), // 부처님오신날 및 대체공휴일
            LocalDate.of(2029, 9, 21), LocalDate.of(2029, 9, 22), LocalDate.of(2029, 9, 23), LocalDate.of(2029, 9, 24) // 추석 연휴 및 대체공휴일
        ),
        2030 to setOf(
            LocalDate.of(2030, 2, 2), LocalDate.of(2030, 2, 3), LocalDate.of(2030, 2, 4), LocalDate.of(2030, 2, 5), // 설날 연휴 및 대체공휴일
            LocalDate.of(2030, 5, 6),  // 어린이날 대체공휴일
            LocalDate.of(2030, 5, 9),  // 부처님오신날
            LocalDate.of(2030, 6, 12), // 제10회 전국동시지방선거
            LocalDate.of(2030, 9, 11), LocalDate.of(2030, 9, 12), LocalDate.of(2030, 9, 13) // 추석 연휴
        )
    )

    /**
     * 토요일 또는 일요일(주말) 여부
     */
    fun isWeekend(date: LocalDate): Boolean {
        return date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY
    }

    /**
     * 대한민국 법정공휴일(양력 국경일, 음력 명절, 대체공휴일 등) 여부
     */
    fun isKoreanPublicHoliday(date: LocalDate): Boolean {
        val monthDay = MonthDay.from(date)
        if (monthDay in FIXED_SOLAR_HOLIDAYS) return true

        val yearSet = YEAR_SPECIFIC_HOLIDAYS[date.year]
        if (yearSet != null && date in yearSet) return true

        // 양력 공휴일이 토요일 또는 일요일과 겹쳐 월요일이 대체공휴일이 되는 일반 규칙 판별
        if (date.dayOfWeek == DayOfWeek.MONDAY) {
            val prevSun = MonthDay.from(date.minusDays(1))
            val prevSat = MonthDay.from(date.minusDays(2))
            if (prevSun in SUBSTITUTE_ELIGIBLE_SOLAR || prevSat in SUBSTITUTE_ELIGIBLE_SOLAR) {
                return true
            }
        }
        return false
    }

    /**
     * 출근·퇴근 기본 적용 대상인 평일(주말 및 공휴일 제외)인지 판별
     */
    fun isCommuteEligibleWorkday(date: LocalDate): Boolean {
        return !isWeekend(date) && !isKoreanPublicHoliday(date)
    }
}
