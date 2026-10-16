package com.autologue.app.domain.usecase.diary

import com.autologue.app.domain.model.DaySummary
import com.autologue.app.domain.model.MonthlySummary
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.util.LocationDistanceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
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

            val monthTxsByDay = transactions.asSequence()
                .filter {
                    val d = it.timestamp.toLocalDate()
                    !d.isBefore(startDate) && !d.isAfter(endDate)
                }
                .groupBy { it.timestamp.toLocalDate() }

            val monthGolfByDay = golfRounds.asSequence()
                .filter {
                    val d = it.roundDate.toLocalDate()
                    !d.isBefore(startDate) && !d.isAfter(endDate)
                }
                .groupBy { it.roundDate.toLocalDate() }

            val monthVehiclesByDay = vehicleLogs.asSequence()
                .filter {
                    val d = it.timestamp.toLocalDate()
                    !d.isBefore(startDate) && !d.isAfter(endDate)
                }
                .groupBy { it.timestamp.toLocalDate() }

            val entriesByDay = entries.groupBy { it.date.toLocalDate() }

            var curr = startDate
            while (!curr.isAfter(endDate)) {
                val dayTxs = monthTxsByDay[curr].orEmpty()
                val dayGolf = !monthGolfByDay[curr].isNullOrEmpty()
                val dayVehicles = monthVehiclesByDay[curr].orEmpty()
                val dayRefuel = dayVehicles.any { it.logType == com.autologue.app.domain.model.VehicleLogType.REFUELING }
                val dayEntries = entriesByDay[curr].orEmpty()
                val photoCount = dayEntries.sumOf { it.photoUris.size }
                val dayEntriesExpense = dayEntries.sumOf { entry ->
                    maxOf(
                        entry.totalExpense,
                        com.autologue.app.data.sync.DailyRouteAggregator.calculateRouteStepsExpense(entry.routeSteps)
                    )
                }
                val expenseSum = maxOf(
                    dayTxs.filter { !com.autologue.app.data.sync.DailyRouteAggregator.isFinancialIncomeOrTransfer(it) }.sumOf { it.amount },
                    dayEntriesExpense
                )
                val dayVehicleDist = dayVehicles.sumOf { it.tripDistanceKm }
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

            val monthVehicleDist = monthVehiclesByDay.values.flatten().sumOf { it.tripDistanceKm }
            val monthEntriesDist = entries.sumOf { entry ->
                if (entry.drivingDistanceKm > 0.0) entry.drivingDistanceKm
                else LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
            }
            val totalMonthDistance = if (monthVehicleDist > 0.0) maxOf(monthVehicleDist, monthEntriesDist) else monthEntriesDist

            MonthlySummary(
                yearMonth = yearMonth,
                totalExpense = daysMap.values.sumOf { it.totalExpense },
                totalGolfRounds = monthGolfByDay.values.sumOf { it.size },
                totalDistanceKm = Math.round(totalMonthDistance * 10.0) / 10.0,
                days = daysMap
            )
        }.flowOn(Dispatchers.Default)
    }
}
