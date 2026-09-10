package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.VehicleLogDao
import com.autologue.app.data.local.entity.VehicleLogEntity
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.util.LocationDistanceUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject

class VehicleRepositoryImpl @Inject constructor(
    private val vehicleLogDao: VehicleLogDao
) : VehicleRepository {

    override fun getAllVehicleLogsFlow(): Flow<List<VehicleLog>> {
        return vehicleLogDao.getAllVehicleLogs().map { it.map { e -> e.toDomain() } }
    }

    override fun getRefuelingLogsFlow(): Flow<List<VehicleLog>> {
        return vehicleLogDao.getRefuelingLogs().map { it.map { e -> e.toDomain() } }
    }

    override suspend fun insertVehicleLog(log: VehicleLog): Long = withContext(Dispatchers.IO) {
        if (log.logType == VehicleLogType.REFUELING) {
            val existing = vehicleLogDao.findExactFuelLog(log.timestamp, log.fuelCost, log.gasStationName)
            if (existing != null) {
                return@withContext existing.id
            }
        }
        vehicleLogDao.insertVehicleLog(log.toEntity())
    }

    override suspend fun getVehicleLogById(id: Long): VehicleLog? = withContext(Dispatchers.IO) {
        vehicleLogDao.getVehicleLogById(id)?.toDomain()
    }

    override suspend fun updateVehicleLog(log: VehicleLog): Unit = withContext(Dispatchers.IO) {
        vehicleLogDao.updateVehicleLog(log.toEntity())
    }

    override suspend fun getLatestRefuelingLog(): VehicleLog? = withContext(Dispatchers.IO) {
        vehicleLogDao.getLatestRefuelingLog()?.toDomain()
    }

    override suspend fun getDrivingDistanceBetween(start: LocalDate, end: LocalDate): Double = withContext(Dispatchers.IO) {
        val startMilli = start.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        val endMilli = end.plusDays(1).atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
        vehicleLogDao.getDrivingDistanceBetween(startMilli, endMilli)
    }

    override suspend fun syncRefuelingFromTransactions(transactions: List<com.autologue.app.domain.model.Transaction>): Int = withContext(Dispatchers.IO) {
        val fuelKeywords = listOf(
            "주유", "주유소", "충전", "충전소", "GS칼텍스", "GS주유", "지에스칼텍스",
            "SK에너지", "SK주유", "SK엔크린", "에스케이", "에쓰오일", "S-OIL", "SOIL", "에스오일",
            "현대오일뱅크", "오일뱅크", "HD현대", "알뜰주유", "E1", "LPG", "슈퍼차저"
        )

        var addedCount = 0
        val existingLogs = vehicleLogDao.getAllVehicleLogsSync().map { it.toDomain() }

        for (tx in transactions) {
            val isFuel = tx.category == com.autologue.app.domain.model.ExpenseCategory.FUEL ||
                fuelKeywords.any { tx.merchantName.contains(it, ignoreCase = true) }

            if (isFuel && tx.amount > 0) {
                val alreadyExists = existingLogs.any {
                    it.logType == VehicleLogType.REFUELING &&
                    it.timestamp.toLocalDate() == tx.timestamp.toLocalDate() &&
                    it.fuelCost == tx.amount
                }

                if (!alreadyExists) {
                    val lastFuel = existingLogs.filter { it.logType == VehicleLogType.REFUELING }
                        .filter { it.timestamp.isBefore(tx.timestamp) }
                        .maxByOrNull { it.timestamp }

                    val daysSince = if (lastFuel != null) {
                        java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), tx.timestamp.toLocalDate()).toInt()
                    } else null

                    val log = VehicleLog(
                        timestamp = tx.timestamp,
                        logType = VehicleLogType.REFUELING,
                        fuelCost = tx.amount,
                        fuelAmountLiters = String.format(java.util.Locale.US, "%.1f", tx.amount / 1650.0).toDouble(),
                        daysSinceLastFuel = daysSince,
                        gasStationName = tx.merchantName,
                        note = "가계부 결제 내역 자동 분석"
                    )
                    vehicleLogDao.insertVehicleLog(log.toEntity())
                    addedCount++
                }
            }
        }
        addedCount
    }

    override suspend fun syncDrivingLogsFromDiary(
        diaryEntries: List<com.autologue.app.domain.model.DiaryEntry>
    ): Int = withContext(Dispatchers.IO) {
        var addedCount = 0
        val existingLogs = vehicleLogDao.getAllVehicleLogsSync().map { it.toDomain() }

        for (entry in diaryEntries) {
            val dist = if (entry.drivingDistanceKm > 0.0) {
                entry.drivingDistanceKm
            } else {
                LocationDistanceUtils.calculateRouteDrivingDistanceKm(entry.routeSteps)
            }

            if (dist > 0.0) {
                val date = entry.date.toLocalDate()
                val alreadyHasDrivingLog = existingLogs.any {
                    it.logType == VehicleLogType.TRIP_DRIVING &&
                    it.timestamp.toLocalDate() == date &&
                    it.tripDistanceKm > 0.0
                }

                if (!alreadyHasDrivingLog) {
                    val noteText = if (!entry.movementSummary.isNullOrBlank() && entry.movementSummary != "기록된 활동 없음") {
                        "다이어리 이동 동선 (${entry.movementSummary})"
                    } else {
                        "${entry.title} 이동"
                    }

                    val log = VehicleLog(
                        timestamp = entry.date,
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = dist,
                        note = noteText
                    )
                    vehicleLogDao.insertVehicleLog(log.toEntity())
                    addedCount++
                }
            }
        }
        addedCount
    }

    override suspend fun cleanDuplicates(): Int = withContext(Dispatchers.IO) {
        val all = vehicleLogDao.getAllVehicleLogsSync()
        val seen = mutableSetOf<String>()
        var deletedCount = 0
        for (log in all) {
            val key = if (log.logType == VehicleLogType.REFUELING) {
                "${log.timestamp.toLocalDate()}_${log.fuelCost}_${log.gasStationName}"
            } else {
                "${log.timestamp.toLocalDate()}_${log.logType}_${log.note}"
            }
            if (key in seen) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                deletedCount++
            } else {
                seen.add(key)
            }
        }
        deletedCount
    }

    override suspend fun cleanDuplicatesAndCorruptedLogs(
        homeName: String,
        companyName: String,
        commuteDistanceKm: Double
    ): Int = withContext(Dispatchers.IO) {
        val all = vehicleLogDao.getAllVehicleLogsSync()
        var modifiedCount = 0
        val seenDates = mutableSetOf<String>()

        val corruptedKeywords = listOf(
            "GOVERNMENT", "WARNING", "ACCORDING", "TO THE SU",
            "Tou can acce", "an acCes", "크n acCE", "LL 7nt",
            "일반 사진", "OCR 실패", "BMARTSCC나"
        )

        for (log in all) {
            val note = log.note ?: ""

            // 1. OCR 쓰레기 문자열이 포함된 비정상 레코드 영구 삭제
            val isCorrupted = corruptedKeywords.any { note.contains(it, ignoreCase = true) } ||
                    (log.logType == VehicleLogType.TRIP_DRIVING && note.length > 30 && !note.contains("출퇴근"))

            if (isCorrupted) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 2. 동일 날짜 중복/근접 주행 레코드 정리 (최초 1건만 유지)
            val dateKey = "${log.timestamp.toLocalDate()}_${log.logType}"
            val isCommute = note.contains("출근") || note.contains("퇴근") || note.contains("출퇴근")
            if (dateKey in seenDates && log.logType == VehicleLogType.TRIP_DRIVING && isCommute) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }
            seenDates.add(dateKey)

            // 3. 평일(월~금) 잘못 생성된 골프장 주행 -> 출퇴근 왕복 주행으로 정상 복구
            val dayOfWeek = log.timestamp.dayOfWeek
            val isWeekday = dayOfWeek !in listOf(java.time.DayOfWeek.SATURDAY, java.time.DayOfWeek.SUNDAY)
            val isFakeGolf = note.contains("필드 골프장") || note.contains("동강시스타") || note.contains("라운딩 왕복")

            if (isWeekday && isFakeGolf && log.logType == VehicleLogType.TRIP_DRIVING) {
                val updated = log.copy(
                    note = "출퇴근 왕복 주행 ($homeName ↔ $companyName)",
                    tripDistanceKm = commuteDistanceKm
                )
                vehicleLogDao.updateVehicleLog(updated)
                modifiedCount++
            } else if (log.logType == VehicleLogType.TRIP_DRIVING && note.contains("출퇴근") && commuteDistanceKm > 0.0 && log.tripDistanceKm != commuteDistanceKm) {
                val updated = log.copy(
                    note = "출퇴근 왕복 주행 ($homeName ↔ $companyName)",
                    tripDistanceKm = commuteDistanceKm
                )
                vehicleLogDao.updateVehicleLog(updated)
                modifiedCount++
            }
        }
        modifiedCount
    }

    override suspend fun clearTripDrivingLogs(): Int = withContext(Dispatchers.IO) {
        vehicleLogDao.deleteAllTripDrivingLogs()
    }

    override suspend fun recordCommuteTrip(
        isToWork: Boolean,
        homeName: String,
        companyName: String,
        distanceKm: Double
    ): Long = withContext(Dispatchers.IO) {
        val now = java.time.LocalDateTime.now()
        // [중복 방어] 30분 이내에 이미 출근/퇴근 주행 기록이 있으면 중복 삽입 차단
        val directionTag = if (isToWork) "출근" else "퇴근"
        val existingRecent = vehicleLogDao.getAllVehicleLogsSync()
            .filter { it.logType == VehicleLogType.TRIP_DRIVING && it.timestamp.toLocalDate() == now.toLocalDate() }
            .filter { it.note?.contains(directionTag) == true }
            .any { 
                java.time.Duration.between(it.timestamp, now).abs().toMinutes() < 30
            }

        if (existingRecent) {
            android.util.Log.d("VehicleRepository", "동일 방향 30분 이내 출퇴근 중복 기록 스킵: $directionTag")
            return@withContext -1L
        }

        val direction = if (isToWork) "출근 주행 ($homeName ➔ $companyName)" else "퇴근 주행 ($companyName ➔ $homeName)"
        val log = VehicleLog(
            timestamp = now,
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = distanceKm,
            note = direction
        )
        vehicleLogDao.insertVehicleLog(log.toEntity())
    }

    private fun VehicleLogEntity.toDomain() = VehicleLog(
        id = id, timestamp = timestamp, logType = logType, fuelCost = fuelCost,
        fuelAmountLiters = fuelAmountLiters, daysSinceLastFuel = daysSinceLastFuel,
        tripDistanceKm = tripDistanceKm, currentOdometerKm = currentOdometerKm,
        estimatedEfficiencyKmPerL = estimatedEfficiencyKmPerL,
        gasStationName = gasStationName, note = note
    )

    private fun VehicleLog.toEntity() = VehicleLogEntity(
        id = id, timestamp = timestamp, logType = logType, fuelCost = fuelCost,
        fuelAmountLiters = fuelAmountLiters, daysSinceLastFuel = daysSinceLastFuel,
        tripDistanceKm = tripDistanceKm, currentOdometerKm = currentOdometerKm,
        estimatedEfficiencyKmPerL = estimatedEfficiencyKmPerL,
        gasStationName = gasStationName, note = note
    )
}
