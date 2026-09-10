package com.autologue.app

import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.util.FuelEconomyCalculator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime
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

    @Test
    fun calculateFuelLiters_basedOnDefaultGasPrice_returnsAccurateLiters() {
        // 50,000원 주유 시 (1,650원/L 기준) -> 30.3 L
        val liters50k = FuelEconomyCalculator.calculateFuelLiters(50000L)
        assertEquals(30.3, liters50k, 0.01)

        // 70,000원 주유 시 (1,650원/L 기준) -> 42.4 L
        val liters70k = FuelEconomyCalculator.calculateFuelLiters(70000L)
        assertEquals(42.4, liters70k, 0.01)

        // 명시적 리터(영수증 OCR 등)가 있으면 우선 사용
        val explicit = FuelEconomyCalculator.calculateFuelLiters(50000L, explicitLiters = 31.5)
        assertEquals(31.5, explicit, 0.01)
    }

    @Test
    fun calculateIntervalEfficiency_withValidDistanceAndFuel_returnsCorrectRatio() {
        // 400 km 주행, 30.3 L 주유 -> 13.2 km/L
        val eff = FuelEconomyCalculator.calculateIntervalEfficiency(400.0, 30.3)
        assertNotNull(eff)
        assertEquals(13.2, eff!!, 0.01)

        // 0 km 주행 또는 0 L는 null 반환
        assertNull(FuelEconomyCalculator.calculateIntervalEfficiency(0.0, 30.3))
        assertNull(FuelEconomyCalculator.calculateIntervalEfficiency(400.0, 0.0))
    }

    @Test
    fun calculateForVehicle_separatesVehiclesCompletely_andCalculatesIntervalDistanceAndEfficiency() {
        val car1Fuel1 = VehicleLog(
            id = 1L,
            timestamp = LocalDateTime.of(2026, 8, 1, 10, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 50000L,
            note = "[car_1] 주유"
        )
        val car1Driving1 = VehicleLog(
            id = 2L,
            timestamp = LocalDateTime.of(2026, 8, 5, 9, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 150.0,
            note = "[car_1] 출퇴근"
        )
        val car1Driving2 = VehicleLog(
            id = 3L,
            timestamp = LocalDateTime.of(2026, 8, 10, 14, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 250.0,
            note = "[car_1] 주말 주행"
        )
        val car1Fuel2 = VehicleLog(
            id = 4L,
            timestamp = LocalDateTime.of(2026, 8, 15, 11, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 50000L,
            note = "[car_1] 주유"
        )

        // car_2 로그가 섞여 있는 상황
        val car2Driving = VehicleLog(
            id = 5L,
            timestamp = LocalDateTime.of(2026, 8, 7, 10, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 500.0,
            note = "[car_2] 장거리 주행"
        )
        val car2Fuel = VehicleLog(
            id = 6L,
            timestamp = LocalDateTime.of(2026, 8, 12, 10, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 80000L,
            note = "[car_2] 주유"
        )

        val allLogs = listOf(car1Fuel1, car1Driving1, car1Driving2, car1Fuel2, car2Driving, car2Fuel)

        // car_1 연산 수행
        val resultCar1 = FuelEconomyCalculator.calculateForVehicle("car_1", allLogs)

        assertEquals(2, resultCar1.enrichedFuelLogs.size)

        // 8/1 첫 주유 로그: 이전 주유가 없으므로 interval 거리 0.0, efficiency null
        val firstFuel = resultCar1.enrichedFuelLogs[0]
        assertEquals(30.3, firstFuel.fuelAmountLiters, 0.01)

        // 8/15 두 번째 주유 로그:
        // 8/1 ~ 8/15 사이 car_1 주행거리 = 150.0 + 250.0 = 400.0 km (car_2의 500km는 철저히 배제됨)
        val secondFuel = resultCar1.enrichedFuelLogs[1]
        assertEquals(400.0, secondFuel.tripDistanceKm, 0.01)
        assertEquals(30.3, secondFuel.fuelAmountLiters, 0.01)
        assertEquals(14, secondFuel.daysSinceLastFuel)
        // 구간 연비: 400.0 / 30.3 = 13.2 km/L
        assertNotNull(secondFuel.estimatedEfficiencyKmPerL)
        assertEquals(13.2, secondFuel.estimatedEfficiencyKmPerL!!, 0.01)

        // 가중 평균 연비 확인
        assertEquals(13.2, resultCar1.weightedAverageEfficiencyKmPerL!!, 0.01)
        assertEquals(400.0, resultCar1.totalIntervalDistanceKm, 0.01)
        assertEquals(30.3, resultCar1.totalIntervalLiters, 0.01)

        // car_2 독립 연산 수행
        val resultCar2 = FuelEconomyCalculator.calculateForVehicle("car_2", allLogs)
        assertEquals(1, resultCar2.enrichedFuelLogs.size)
        val car2FuelResult = resultCar2.enrichedFuelLogs[0]
        // 80,000 / 1650 = 48.5 L
        assertEquals(48.5, car2FuelResult.fuelAmountLiters, 0.01)
        // car_2의 8/12 주유 이전 주행거리 500km 반영
        assertEquals(500.0, car2FuelResult.tripDistanceKm, 0.01)
        // 500.0 / 48.5 = 10.3 km/L
        assertEquals(10.3, car2FuelResult.estimatedEfficiencyKmPerL!!, 0.01)
    }

    @Test
    fun calculateFuelDetailHybrid_withExplicitLiters_prioritizesExplicitLiters() {
        // 50,000원 결제, 실제 주유량 32.5 L 직접 입력한 경우
        val detail = FuelEconomyCalculator.calculateFuelDetailHybrid(
            fuelCost = 50000L,
            explicitLiters = 32.5,
            explicitUnitPrice = null,
            isCustom = true
        )
        assertEquals(32.5, detail.liters, 0.01)
        // 단가는 50000 / 32.5 = 1538.5원/L
        assertEquals(1538.5, detail.unitPrice, 0.1)
        assertEquals(false, detail.isEstimated)
    }

    @Test
    fun calculateFuelDetailHybrid_withExplicitUnitPrice_calculatesExactLiters() {
        // 51,000원 결제, L당 단가 1,700원/L 입력한 경우 -> 30.0 L
        val detail = FuelEconomyCalculator.calculateFuelDetailHybrid(
            fuelCost = 51000L,
            explicitLiters = 0.0,
            explicitUnitPrice = 1700.0,
            isCustom = true
        )
        assertEquals(30.0, detail.liters, 0.01)
        assertEquals(1700.0, detail.unitPrice, 0.01)
        assertEquals(false, detail.isEstimated)
    }

    @Test
    fun calculateFuelDetailHybrid_withoutInputs_fallsBackToDefaultGasPrice() {
        // 미입력 시: 전국 평균 유가 1,650원/L 적용 -> 30.3 L
        val detail = FuelEconomyCalculator.calculateFuelDetailHybrid(
            fuelCost = 50000L,
            explicitLiters = 0.0,
            explicitUnitPrice = null,
            isCustom = false
        )
        assertEquals(30.3, detail.liters, 0.01)
        assertEquals(1650.0, detail.unitPrice, 0.01)
        assertEquals(true, detail.isEstimated)
    }

    @Test
    fun calculateForVehicle_withHybridRefuelLogs_calculatesAccurateIntervalEfficiency() {
        val car1Fuel1 = VehicleLog(
            id = 1L,
            timestamp = LocalDateTime.of(2026, 8, 1, 10, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 50000L,
            note = "[car_1] 주유" // 미입력 -> 1,650원 기준
        )
        val car1Driving = VehicleLog(
            id = 2L,
            timestamp = LocalDateTime.of(2026, 8, 8, 10, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 400.0,
            note = "[car_1] 주행"
        )
        val car1Fuel2 = VehicleLog(
            id = 3L,
            timestamp = LocalDateTime.of(2026, 8, 15, 11, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 54000L,
            fuelAmountLiters = 0.0,
            note = "[car_1: G80] [unit_price: 1800.0] [custom_fuel:true] 주유" // 단가 1,800원 -> 주유량 30.0 L
        )

        val result = FuelEconomyCalculator.calculateForVehicle("car_1", listOf(car1Fuel1, car1Driving, car1Fuel2))
        assertEquals(2, result.enrichedFuelLogs.size)

        val secondFuel = result.enrichedFuelLogs[1]
        // 54000 / 1800 = 30.0 L
        assertEquals(30.0, secondFuel.fuelAmountLiters, 0.01)
        // 주행거리 = 400.0 km
        assertEquals(400.0, secondFuel.tripDistanceKm, 0.01)
        // 구간 실연비 = 400.0 / 30.0 = 13.3 km/L
        assertEquals(13.3, secondFuel.estimatedEfficiencyKmPerL!!, 0.01)
    }

    @Test
    fun calculateForVehicle_withCustomDefaultGasPrice_usesCustomPriceForFallbacks() {
        // 단가가 미입력된 주유 결제 52,500원인 경우
        // 기본 유가가 1,750원/L로 설정된 디젤/고급유 차량이라면 -> 52,500 / 1,750 = 30.0 L
        val carFuel1 = VehicleLog(
            id = 1L,
            timestamp = LocalDateTime.of(2026, 8, 1, 10, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 52500L,
            note = "[car_1] 주유"
        )
        val carDriving = VehicleLog(
            id = 2L,
            timestamp = LocalDateTime.of(2026, 8, 8, 10, 0),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 360.0,
            note = "[car_1] 주행"
        )
        val carFuel2 = VehicleLog(
            id = 3L,
            timestamp = LocalDateTime.of(2026, 8, 15, 11, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 52500L,
            note = "[car_1] 주유" // 미입력 -> 1,750원 적용 -> 30.0 L
        )

        val result = FuelEconomyCalculator.calculateForVehicle(
            targetVehicleId = "car_1",
            allLogs = listOf(carFuel1, carDriving, carFuel2),
            gasPrice = 1750.0
        )

        assertEquals(2, result.enrichedFuelLogs.size)
        val secondFuel = result.enrichedFuelLogs[1]
        // 52,500 / 1,750 = 30.0 L
        assertEquals(30.0, secondFuel.fuelAmountLiters, 0.01)
        // 360.0 km / 30.0 L = 12.0 km/L
        assertEquals(12.0, secondFuel.estimatedEfficiencyKmPerL!!, 0.01)
        assertEquals(12.0, result.weightedAverageEfficiencyKmPerL!!, 0.01)
    }
}
