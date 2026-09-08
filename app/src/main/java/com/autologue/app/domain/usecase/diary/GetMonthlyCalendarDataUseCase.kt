package com.autologue.app.domain.usecase.diary

import com.autologue.app.domain.model.DaySummary
import com.autologue.app.domain.model.MonthlySummary
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.util.LocationDistanceUtils
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate
import java.time.YearMonth
import javax.inject.Inject

class GetMonthlyCalendarDataUseCase @Inject constructor(
    private val diaryRepository: DiaryRepository,
    private val transactionRepository: TransactionRepository,
    private val golfRepository: GolfRepository,
    private val vehicleRepository: VehicleRepository
) {
    operator fun invoke(yearMonth: YearMonth): Flow<MonthlySummary> {
        val startDate = yearMonth.atDay(1)
        val endDate = yearMonth.atEndOfMonth()

        return combine(
            diaryRepository.getDiaryEntriesByDateRange(startDate, endDate),
            transactionRepository.getAllTransactionsFlow(),
            golfRepository.getAllGolfRoundsFlow(),
            vehicleRepository.getAllVehicleLogsFlow()
        ) { entries, transactions, golfRounds, vehicleLogs ->
            val daysMap = mutableMapOf<LocalDate, DaySummary>()

            val monthTxs = transactions.filter {
                val d = it.timestamp.toLocalDate()
                !d.isBefore(startDate) && !d.isAfter(endDate)
            }
            val monthGolf = golfRounds.filter {
                val d = it.roundDate.toLocalDate()
                !d.isBefore(startDate) && !d.isAfter(endDate)
            }
            val monthVehicles = vehicleLogs.filter {
                val d = it.timestamp.toLocalDate()
                !d.isBefore(startDate) && !d.isAfter(endDate)
            }

            var curr = startDate
            while (!curr.isAfter(endDate)) {
                val dayTxs = monthTxs.filter { it.timestamp.toLocalDate() == curr }
                val dayGolf = monthGolf.any { it.roundDate.toLocalDate() == curr }
                val dayRefuel = monthVehicles.any { it.timestamp.toLocalDate() == curr && it.logType == com.autologue.app.domain.model.VehicleLogType.REFUELING }
                val dayEntries = entries.filter { it.date.toLocalDate() == curr }
                val photoCount = dayEntries.sumOf { it.photoUris.size }
                val expenseSum = dayTxs.sumOf { it.amount }
                val dayVehicleDist = monthVehicles.filter { it.timestamp.toLocalDate() == curr }.sumOf { it.tripDistanceKm }
                val dayEntriesDist = dayEntries.sumOf { entry ->
                    if (entry.drivingDistanceKm > 0.0) entry.drivingDistanceKm
                    else LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
                }
                val dayDistance = if (dayVehicleDist > 0.0) maxOf(dayVehicleDist, dayEntriesDist) else dayEntriesDist

                daysMap[curr] = DaySummary(
                    date = curr,
                    totalExpense = expenseSum,
                    hasGolfRound = dayGolf,
                    hasRefueling = dayRefuel,
                    photoCount = photoCount,
                    entryCount = dayEntries.size,
                    totalDistanceKm = dayDistance
                )
                curr = curr.plusDays(1)
            }

            val monthVehicleDist = monthVehicles.sumOf { it.tripDistanceKm }
            val monthEntriesDist = entries.sumOf { entry ->
                if (entry.drivingDistanceKm > 0.0) entry.drivingDistanceKm
                else LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
            }
            val totalMonthDistance = if (monthVehicleDist > 0.0) maxOf(monthVehicleDist, monthEntriesDist) else monthEntriesDist

            MonthlySummary(
                yearMonth = yearMonth,
                totalExpense = monthTxs.sumOf { it.amount },
                totalGolfRounds = monthGolf.size,
                totalDistanceKm = Math.round(totalMonthDistance * 10.0) / 10.0,
                days = daysMap
            )
        }
    }
}
