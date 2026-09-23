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

    companion object {
        val FUEL_KEYWORDS = listOf(
            "주유", "주유소", "충전", "충전소", "셀프주유",
            "GS칼텍스", "지에스칼텍스", "GS주유", "GS셀프",
            "SK에너지", "SK주유", "SK엔크린", "엔크린", "에스케이", "SK가스",
            "에쓰오일", "S-OIL", "SOIL", "에스오일", "구도일",
            "현대오일뱅크", "HD현대", "오일뱅크", "현대오일",
            "알뜰주유", "알뜰주유소", "알뜰셀프", "자영주유소",
            "E1", "LPG", "슈퍼차저", "전기차충전", "차지비", "파워큐브", "에버온", "채비", "모두의충전"
        )

        fun isFuelMerchant(merchantName: String): Boolean {
            val upper = merchantName.uppercase()
            return FUEL_KEYWORDS.any { upper.contains(it.uppercase()) }
        }
    }

    override suspend fun syncRefuelingFromTransactions(transactions: List<com.autologue.app.domain.model.Transaction>): Int = withContext(Dispatchers.IO) {
        var addedCount = 0
        val currentLogs = vehicleLogDao.getAllVehicleLogsSync().map { it.toDomain() }.toMutableList()

        for (tx in transactions) {
            val isFuel = tx.category == com.autologue.app.domain.model.ExpenseCategory.FUEL ||
                isFuelMerchant(tx.merchantName)

            if (isFuel && tx.amount > 0) {
                val alreadyExists = currentLogs.any {
                    it.logType == VehicleLogType.REFUELING &&
                    it.timestamp.toLocalDate() == tx.timestamp.toLocalDate() &&
                    it.fuelCost == tx.amount
                }

                if (!alreadyExists) {
                    val lastFuel = currentLogs.filter { it.logType == VehicleLogType.REFUELING }
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
                    val insertedId = vehicleLogDao.insertVehicleLog(log.toEntity())
                    currentLogs.add(log.copy(id = insertedId))
                    addedCount++
                }
            }
        }
        addedCount
    }

    override suspend fun deleteRefuelingLogByTransaction(amount: Long, date: LocalDate, merchantName: String): Int = withContext(Dispatchers.IO) {
        val logs = vehicleLogDao.getAllVehicleLogsSync().map { it.toDomain() }
        val matched = logs.filter {
            it.logType == VehicleLogType.REFUELING &&
            it.timestamp.toLocalDate() == date &&
            it.fuelCost == amount &&
            (it.gasStationName == merchantName || it.gasStationName == null || merchantName.contains(it.gasStationName ?: ""))
        }
        var deletedCount = 0
        for (log in matched) {
            vehicleLogDao.deleteVehicleLogById(log.id)
            deletedCount++
        }
        deletedCount
    }

    override suspend fun syncDrivingLogsFromDiary(
        diaryEntries: List<com.autologue.app.domain.model.DiaryEntry>
    ): Int = withContext(Dispatchers.IO) {
        // [원칙] 차량 이동은 오직 실제 차량 블루투스 연결/해제 세션에 의해서만 기록됩니다.
        // 다이어리의 일상 이동(도보, 대중교통, 식사 이동 등)은 차계부 주행 기록으로 등록하지 않습니다.
        0
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

            // 1. 다이어리 이동 동선(도보/대중교통 오인입) 레코드 전수 영구 삭제
            val isDiaryMovement = note.contains("다이어리 이동 동선") ||
                    (log.logType == VehicleLogType.TRIP_DRIVING && (note.contains("방이동") || note.contains("동작동") || note.contains("영등포동") || note.contains("잠실동")) && !note.contains("블루투스"))

            if (isDiaryMovement) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 2. OCR 쓰레기 문자열이 포함된 비정상 레코드 영구 삭제
            val isCorrupted = corruptedKeywords.any { note.contains(it, ignoreCase = true) } ||
                    (log.logType == VehicleLogType.TRIP_DRIVING && note.length > 30 && !note.contains("출근") && !note.contains("퇴근") && !note.contains("블루투스") && !note.contains("주행"))

            if (isCorrupted) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 3. 비정상 시각(새벽 00시~05시 또는 대낮 비정상 시간대)에 생성된 가짜 '출퇴근 왕복' 더미 레코드 삭제
            val hour = log.timestamp.hour
            val isUnusualCommuteTime = (hour in 0..5 || hour in 11..16) && note.contains("출퇴근 왕복")
            if (isUnusualCommuteTime && log.logType == VehicleLogType.TRIP_DRIVING) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 4. 동일 날짜 중복 출근/퇴근 주행 레코드 정리 (방향별 1건만 유지)
            val directionKey = if (note.contains("출근")) "WORK" else if (note.contains("퇴근")) "HOME" else "OTHER"
            val dateKey = "${log.timestamp.toLocalDate()}_${log.logType}_$directionKey"
            val isCommute = note.contains("출근") || note.contains("퇴근") || note.contains("출퇴근")
            if (dateKey in seenDates && log.logType == VehicleLogType.TRIP_DRIVING && isCommute) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }
            seenDates.add(dateKey)

            // 5. 과거 '출퇴근 왕복'으로 잘못 묶여 있던 레코드를 실제 출근 편도로 정상화
            if (log.logType == VehicleLogType.TRIP_DRIVING && note.contains("출퇴근 왕복")) {
                val oneWayDistance = if (commuteDistanceKm > 0.0) commuteDistanceKm / 2.0 else log.tripDistanceKm
                val updated = log.copy(
                    note = "출근 주행 ($homeName ➔ $companyName)",
                    tripDistanceKm = oneWayDistance
                )
                vehicleLogDao.updateVehicleLog(updated)
                modifiedCount++
            }

            // 6. "출발지 ➔ 도착지" 또는 0.0km로 기록된 오염 주행 로그 자가 치유(Self-Healing)
            if (log.logType == VehicleLogType.TRIP_DRIVING) {
                var needsUpdate = false
                var healedDistance = log.tripDistanceKm
                var healedNote = note

                // 6-1. 지명이 "출발지 ➔ 도착지" 또는 "출발지"가 포함된 경우 정상 지명으로 복원
                if (healedNote.contains("출발지 ➔ 도착지") || healedNote.contains("출발지") || healedNote.contains("도착지")) {
                    healedNote = if (homeName.isNotBlank() && companyName.isNotBlank()) {
                        val h = log.timestamp.hour
                        if (h in 6..12) "출근 주행 ($homeName ➔ $companyName)"
                        else if (h in 17..23) "퇴근 주행 ($companyName ➔ $homeName)"
                        else "$homeName 인근 주행"
                    } else {
                        "차량 주행 기록"
                    }
                    needsUpdate = true
                } else if (healedNote.contains(" ➔ ") && healedNote.split(" ➔ ").let { it.size == 2 && it[0].trim() == it[1].trim() }) {
                    // 동일 지점 반복("영등포로 254 ➔ 영등포로 254") -> "영등포로 254 주변 주행"
                    val place = healedNote.split(" ➔ ")[0].trim().substringBefore("(").trim()
                    healedNote = "$place 주변 주행"
                    needsUpdate = true
                }

                // 6-2. 주행거리가 0.0km 이하인 경우 운행 시간 기반 합리적 추정 거리로 복원
                if (healedDistance <= 0.0) {
                    val minMatch = Regex("(\\d+)\\s*분").find(note)
                    val durationMin = minMatch?.groupValues?.get(1)?.toDoubleOrNull() ?: 10.0
                    val estimatedKm = (durationMin * 0.3).coerceIn(1.2, 35.0)
                    healedDistance = Math.round(estimatedKm * 10.0) / 10.0
                    needsUpdate = true
                }

                if (needsUpdate) {
                    val updated = log.copy(
                        note = healedNote,
                        tripDistanceKm = healedDistance
                    )
                    vehicleLogDao.updateVehicleLog(updated)
                    modifiedCount++
                }
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

    override suspend fun syncAndCleanWithGolfRounds(
        validRounds: List<com.autologue.app.domain.model.GolfRound>
    ): Int = withContext(Dispatchers.IO) {
        val allLogs = vehicleLogDao.getAllVehicleLogsSync()
        var changeCount = 0

        // 1. 유효한 18홀 라운드만 필터링 (54~144타 또는 14홀 이상, 유효한 골프장명)
        val completeRounds = validRounds.filter { r ->
            (r.totalScore in 54..144 || r.holeScores.size >= 14) &&
                    r.clubName.isNotBlank() &&
                    r.clubName != "필드 골프장" &&
                    !r.clubName.contains("일반 사진")
        }
        val validRoundDates = completeRounds.map { it.roundDate.toLocalDate() }.toSet()

        // 2. [유령 골프 주행 레코드 및 미래 오류 레코드 삭제]
        val now = java.time.LocalDateTime.now()
        for (log in allLogs) {
            val note = log.note ?: ""
            val isGolfLog = log.logType == VehicleLogType.TRIP_DRIVING &&
                    (note.contains("골프") || note.contains("라운딩") || note.contains("CC") || note.contains("GC") || note.contains("C.C") || note.contains("G.C"))

            // 미래 시각 오류 레코드 삭제 (예: 비정상 파싱으로 미래 연도로 등록된 8.8 등)
            if (log.timestamp.isAfter(now.plusHours(2))) {
                android.util.Log.d("VehicleRepository", "미래 오류 주행 레코드 삭제: id=${log.id}, date=${log.timestamp}")
                vehicleLogDao.deleteVehicleLogById(log.id)
                changeCount++
                continue
            }

            // 실제 골프 라운드 DB에 없는 날짜의 가짜/오탐 골프 주행 기록(8.8, 9.1, 9.9, 8.28, 8.21 등) 영구 삭제
            if (isGolfLog && log.timestamp.toLocalDate() !in validRoundDates) {
                android.util.Log.d("VehicleRepository", "유령 골프 주행 레코드 삭제: id=${log.id}, date=${log.timestamp}, note=$note")
                vehicleLogDao.deleteVehicleLogById(log.id)
                changeCount++
            }
        }

        // 3. [누락된 실제 골프 라운드 주행 기록 복원/생성]
        val updatedLogs = vehicleLogDao.getAllVehicleLogsSync()
        for (round in completeRounds) {
            val rDate = round.roundDate.toLocalDate()
            val hasDrivingOnDate = updatedLogs.any {
                it.logType == VehicleLogType.TRIP_DRIVING && it.timestamp.toLocalDate() == rDate
            }

            if (!hasDrivingOnDate) {
                val distanceKm = com.autologue.app.util.GolfCourseDistanceUtils.getEstimatedRoundTripKm(round.clubName)
                val startTime = round.startTime ?: round.roundDate
                val drivingTime = startTime.minusHours(2)
                val newLog = VehicleLog(
                    timestamp = drivingTime,
                    logType = VehicleLogType.TRIP_DRIVING,
                    tripDistanceKm = distanceKm,
                    note = "${round.clubName} 라운딩 왕복 주행"
                )
                android.util.Log.d("VehicleRepository", "골프 라운드 주행 기록 자동 복원: date=$rDate, club=${round.clubName}, km=$distanceKm")
                vehicleLogDao.insertVehicleLog(newLog.toEntity())
                changeCount++
            }
        }

        changeCount
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
