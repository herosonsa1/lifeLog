package com.autologue.app

import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDate
import java.time.temporal.ChronoUnit

class FuelEconomyTest {

    @Test
    fun calculateDaysBetweenRefueling_returnsCorrectDays() {
        val lastDate = LocalDate.of(2026, 8, 15)
        val currentDate = LocalDate.of(2026, 8, 31)
        val days = ChronoUnit.DAYS.between(lastDate, currentDate)

        assertEquals(16L, days)
    }

    @Test
    fun calculateEstimatedEfficiency_returnsCorrectRatio() {
        val distanceKm = 480.0
        val fuelLiters = 40.0
        val efficiency = distanceKm / fuelLiters

        assertEquals(12.0, efficiency, 0.01)
    }
}
