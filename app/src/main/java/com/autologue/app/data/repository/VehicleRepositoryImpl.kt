package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.VehicleLogDao
import com.autologue.app.data.local.entity.VehicleLogEntity
import com.autologue.app.data.preferences.MerchantAliasPreferences
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
    private val vehicleLogDao: VehicleLogDao,
    private val merchantAliasPreferences: MerchantAliasPreferences
) : VehicleRepository {

    constructor(vehicleLogDao: VehicleLogDao) : this(vehicleLogDao, MerchantAliasPreferences())

    private fun isProtectedFuelStation(stationName: String, note: String?): Boolean {
        val clean = stationName.trim()
        if (clean.isBlank()) return false
        val noteStr = note ?: ""
        val isManualRefuel = noteStr.contains("직접 등록") ||
            noteStr.contains("직접 입력") ||
            noteStr.contains("카테고리 변경 연동") ||
            noteStr.contains("[custom_fuel:true]") ||
            noteStr.contains("[custom_name:true]")
        val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(clean) ||
            merchantAliasPreferences.getOriginalNamesForTarget(clean).any { isFuelMerchant(it) }
        return isManualRefuel || isUserAliased || isFuelMerchant(clean) || clean == "주유소"
    }

    override fun getAllVehicleLogsFlow(): Flow<List<VehicleLog>> {
        return vehicleLogDao.getAllVehicleLogs().map { it.map { e -> e.toDomain() } }
    }

    override fun getRefuelingLogsFlow(): Flow<List<VehicleLog>> {
        return vehicleLogDao.getRefuelingLogs().map { it.map { e -> e.toDomain() } }
    }

    override suspend fun insertVehicleLog(log: VehicleLog): Long = withContext(Dispatchers.IO) {
        val finalLog = if (log.logType == VehicleLogType.REFUELING && !log.gasStationName.isNullOrBlank()) {
            val resolved = merchantAliasPreferences.resolveAlias(log.gasStationName)
            if (resolved.isNotBlank() && resolved != log.gasStationName) {
                log.copy(gasStationName = resolved)
            } else {
                log
            }
        } else {
            log
        }

        if (finalLog.logType == VehicleLogType.REFUELING) {
            val existing = vehicleLogDao.findExactFuelLog(finalLog.timestamp, finalLog.fuelCost, finalLog.gasStationName)
                ?: vehicleLogDao.findExactFuelLog(finalLog.timestamp, finalLog.fuelCost, log.gasStationName)
            if (existing != null) {
                if (existing.gasStationName != finalLog.gasStationName && !finalLog.gasStationName.isNullOrBlank()) {
                    vehicleLogDao.updateVehicleLog(existing.copy(gasStationName = finalLog.gasStationName))
                }
                return@withContext existing.id
            }
        }
        vehicleLogDao.insertVehicleLog(finalLog.toEntity())
    }

    override suspend fun getVehicleLogById(id: Long): VehicleLog? = withContext(Dispatchers.IO) {
        vehicleLogDao.getVehicleLogById(id)?.toDomain()
    }

    override suspend fun updateVehicleLog(log: VehicleLog): Unit = withContext(Dispatchers.IO) {
        vehicleLogDao.updateVehicleLog(log.toEntity())
    }

    override suspend fun renameGasStationGlobally(
        oldStationName: String,
        newStationName: String,
        rawOriginalStationName: String?
    ): Int = withContext(Dispatchers.IO) {
        val targetName = newStationName.trim()
        if (targetName.isBlank()) return@withContext 0

        merchantAliasPreferences.registerAlias(
            oldName = oldStationName,
            newName = targetName,
            rawOriginalName = rawOriginalStationName
        )

        val all = vehicleLogDao.getAllVehicleLogsSync()
        var updatedCount = 0
        for (entity in all) {
            if (entity.logType != VehicleLogType.REFUELING) continue
            val currentStation = entity.gasStationName?.trim().orEmpty()
            val isMatchedOld = merchantAliasPreferences.isSameMerchant(currentStation, oldStationName) ||
                (!rawOriginalStationName.isNullOrBlank() && merchantAliasPreferences.isSameMerchant(currentStation, rawOriginalStationName))
            val resolved = if (isMatchedOld) {
                targetName
            } else {
                merchantAliasPreferences.resolveAlias(currentStation)
            }

            if (resolved.isNotBlank() && resolved != currentStation) {
                val currentNote = entity.note.orEmpty()
                val updatedNote = if (currentNote.contains("[custom_name:true]")) {
                    currentNote
                } else {
                    "$currentNote [custom_name:true]".trim()
                }
                vehicleLogDao.updateVehicleLog(
                    entity.copy(
                        gasStationName = resolved,
                        note = updatedNote
                    )
                )
                updatedCount++
            }
        }
        updatedCount
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
            "주유", "주유소", "충전소", "셀프주유", "주유셀프", "직영주유", "셀프충전", "주유타운",
            "GS칼텍스", "지에스칼텍스", "GS주유", "GS셀프", "GS충전", "GS이엠", "지에스이엠", "지에스이앤알", "지에스엠비즈",
            "SK에너지", "SK주유", "SK엔크린", "엔크린", "SK가스", "SK충전", "SK셀프", "SK네트웍스",
            "에쓰오일", "S-OIL", "SOIL", "에스오일", "구도일", "S오일", "에쓰-오일", "쓰오일",
            "현대오일뱅크", "HD현대오일뱅크", "HD현대", "오일뱅크", "현대오일", "현대셀프",
            "알뜰주유", "알뜰주유소", "알뜰셀프", "자영주유소", "농협주유", "NH주유", "NH-OIL", "NHOIL", "하나로주유", "농협하나로주유",
            "한국도로공사", "한국도로공사하", "한국도로공사상", "도로공사주유", "도로공사충전",
            "ex-oil", "EX-OIL", "EX주유소", "ex주유소", "휴게소주유", "휴게소충전", "만남의광장주유",
            "유진주유", "광신석유", "대성산업",
            "LPG충전", "엘피지충전", "슈퍼차저", "전기차충전", "차지비", "파워큐브", "에버온", "채비", "모두의충전", "일렉링크", "수소충전"
        )

        fun isFuelMerchant(merchantName: String): Boolean {
            if (com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(merchantName)) return false
            val upper = merchantName.uppercase().replace(" ", "")
            return FUEL_KEYWORDS.any { upper.contains(it.uppercase().replace(" ", "")) }
        }
    }

    override suspend fun syncRefuelingFromTransactions(transactions: List<com.autologue.app.domain.model.Transaction>): Int = withContext(Dispatchers.IO) {
        var addedCount = 0

        // 0. 기존 차계부 DB에 잘못 들어간 '제목' 등 비정상 주유 로그 선제 정리 및 사용자 지정 별칭 자동 치환
        val initialLogs = vehicleLogDao.getAllVehicleLogsSync()
        for (entity in initialLogs) {
            if (entity.logType == VehicleLogType.REFUELING) {
                val rawStation = entity.gasStationName?.trim() ?: ""
                val resolvedStation = merchantAliasPreferences.resolveAlias(rawStation)
                val currentEntity = if (resolvedStation.isNotBlank() && resolvedStation != rawStation) {
                    val noteWithTag = if ((entity.note ?: "").contains("[custom_name:true]")) {
                        entity.note
                    } else {
                        "${entity.note.orEmpty()} [custom_name:true]".trim()
                    }
                    val updated = entity.copy(gasStationName = resolvedStation, note = noteWithTag)
                    vehicleLogDao.updateVehicleLog(updated)
                    updated
                } else {
                    entity
                }

                val station = currentEntity.gasStationName?.trim() ?: ""
                val note = currentEntity.note ?: ""
                val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(station)
                if ((!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(station)) ||
                    !isProtectedFuelStation(station, note)
                ) {
                    vehicleLogDao.deleteVehicleLogById(currentEntity.id)
                }
            }
        }

        // 1. 승인취소(amount < 0 또는 [승인취소] 태그) 거래가 있는 경우 차계부에서 해당 가승인 로그 즉시 선제 삭제
        val cancelledItems = transactions.filter { it.amount < 0 || it.transferMemo?.contains("승인취소") == true }
        for (c in cancelledItems) {
            val cancelAmt = Math.abs(c.amount)
            deleteRefuelingLogByTransaction(cancelAmt, c.timestamp.toLocalDate(), c.merchantName)
        }

        val currentLogs = vehicleLogDao.getAllVehicleLogsSync().map { it.toDomain() }.toMutableList()

        // 2. 주유 관련 거래 필터링 (비결제/무효 상호명 원천 차단 및 실제 주유소/별칭 매핑 주유소 가맹점 선별)
        val fuelTransactions = transactions.filter { tx ->
            val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
            val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(tx.merchantName)
            if (!isManual && (
                    (!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(tx.merchantName)) ||
                    com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(tx.originalText)
                )
            ) {
                return@filter false
            }
            val isCardMsg = !isManual && com.autologue.app.data.parser.SmsParser.isCardApprovalMessage(tx.originalText)
            val isAliasFromFuel = merchantAliasPreferences.getOriginalNamesForTarget(tx.merchantName).any { isFuelMerchant(it) }
            val isFuel = (isManual && tx.category == com.autologue.app.domain.model.ExpenseCategory.FUEL) ||
                isFuelMerchant(tx.merchantName) ||
                isAliasFromFuel ||
                (tx.category == com.autologue.app.domain.model.ExpenseCategory.FUEL && (isUserAliased || isFuelMerchant(tx.originalText))) ||
                (!isManual && tx.merchantName in setOf("카드 가맹점", "가맹점") && isCardMsg && isFuelMerchant(tx.originalText))
            isFuel && tx.amount > 0
        }

        // 날짜 및 상호별 그룹화하여 셀프주유소 가승인(15만원/10만원) 판별
        for (tx in fuelTransactions) {
            val txDate = tx.timestamp.toLocalDate()
            val baseStationName = if (tx.merchantName in listOf("카드 가맹점", "가맹점", "출금 내역", "계좌 이체", "정선우", "정*우") || tx.merchantName.isBlank()) {
                com.autologue.app.data.parser.SmsParser.parse(null, tx.originalText, tx.timestamp)?.merchantName
                    ?.takeIf { !com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(it) && isFuelMerchant(it) }
                    ?: FUEL_KEYWORDS.firstOrNull { tx.originalText.uppercase().contains(it.uppercase()) }
                    ?: continue
            } else {
                tx.merchantName
            }
            val resolvedStationName = merchantAliasPreferences.resolveAlias(baseStationName)
            val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(resolvedStationName)

            if (!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(resolvedStationName)) continue

            val sameDayMerchantTxs = fuelTransactions.filter {
                it.timestamp.toLocalDate() == txDate &&
                (it.merchantName == tx.merchantName || isFuelMerchant(it.merchantName))
            }

            // 셀프 주유소 가승인 금액(150,000원 또는 100,000원)인데,
            // 같은 날 같은 주유소에 다른 실제 주유 금액(예: 75,449원, 61,000원 등)이 있거나 취소 내역이 있으면 가승인 건은 차계부 등록 제외
            val isCommonPreAuthAmount = tx.amount == 150000L || tx.amount == 100000L
            val hasOtherRealFuelTx = sameDayMerchantTxs.any { it.amount != tx.amount }
            val hasCancellationForThis = cancelledItems.any {
                it.timestamp.toLocalDate() == txDate && Math.abs(it.amount) == tx.amount
            }

            if (isCommonPreAuthAmount && (hasOtherRealFuelTx || hasCancellationForThis)) {
                // 이미 DB에 가승인 로그가 들어있다면 정리
                deleteRefuelingLogByTransaction(tx.amount, txDate, resolvedStationName)
                continue
            }

            val existingMatchIdx = currentLogs.indexOfFirst {
                it.logType == VehicleLogType.REFUELING &&
                it.timestamp.toLocalDate() == txDate &&
                it.fuelCost == tx.amount
            }

            if (existingMatchIdx >= 0) {
                val existingLog = currentLogs[existingMatchIdx]
                if (existingLog.gasStationName != resolvedStationName && isUserAliased) {
                    val updatedLog = existingLog.copy(gasStationName = resolvedStationName)
                    vehicleLogDao.updateVehicleLog(updatedLog.toEntity())
                    currentLogs[existingMatchIdx] = updatedLog
                }
            } else {
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
                    gasStationName = resolvedStationName,
                    note = if (isUserAliased) "가계부 결제 내역 자동 분석 [custom_name:true]" else "가계부 결제 내역 자동 분석"
                )
                val insertedId = vehicleLogDao.insertVehicleLog(log.toEntity())
                currentLogs.add(log.copy(id = insertedId))
                addedCount++
            }
        }
        addedCount
    }

    override suspend fun deleteRefuelingLogByTransaction(amount: Long, date: LocalDate, merchantName: String): Int = withContext(Dispatchers.IO) {
        val logs = vehicleLogDao.getAllVehicleLogsSync().map { it.toDomain() }
        val resolvedMerchant = merchantAliasPreferences.resolveAlias(merchantName)
        val matched = logs.filter {
            it.logType == VehicleLogType.REFUELING &&
            it.timestamp.toLocalDate() == date &&
            it.fuelCost == amount &&
            (it.gasStationName == merchantName ||
                it.gasStationName == resolvedMerchant ||
                it.gasStationName == null ||
                merchantName.contains(it.gasStationName ?: "") ||
                resolvedMerchant.contains(it.gasStationName ?: ""))
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
        val seenCommuteDates = mutableSetOf<String>()
        val seenMinuteTimestamps = mutableSetOf<String>()

        val corruptedKeywords = listOf(
            "GOVERNMENT", "WARNING", "ACCORDING", "TO THE SU",
            "Tou can acce", "an acCes", "크n acCE", "LL 7nt",
            "일반 사진", "OCR 실패", "BMARTSCC나"
        )

        for (rawLog in all) {
            var log = rawLog
            var note = log.note ?: ""

            // 0. '제목' 등 비결제 MMS 제목 컬럼 오탐으로 생성된 가짜 주유 레코드 삭제 및 별칭 자동 치환
            if (log.logType == VehicleLogType.REFUELING) {
                val rawStation = log.gasStationName?.trim() ?: ""
                val resolvedStation = merchantAliasPreferences.resolveAlias(rawStation)
                if (resolvedStation.isNotBlank() && resolvedStation != rawStation) {
                    val updatedNote = if (note.contains("[custom_name:true]")) note else "$note [custom_name:true]".trim()
                    log = log.copy(gasStationName = resolvedStation, note = updatedNote)
                    note = updatedNote
                    vehicleLogDao.updateVehicleLog(log)
                    modifiedCount++
                }
                val station = log.gasStationName?.trim() ?: ""
                val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(station)
                if ((!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(station)) ||
                    !isProtectedFuelStation(station, note)
                ) {
                    vehicleLogDao.deleteVehicleLogById(log.id)
                    modifiedCount++
                    continue
                }
            }

            // 실제 블루투스/GPS 추적 서비스가 생성한 정상 주행 기록인지 판별
            val isRealCarTracking = note.contains("운행") ||
                note.contains("[car_") ||
                note.contains("블루투스") ||
                note.contains("GPS")

            // 1. 다이어리 이동 동선(도보/대중교통 오인입) 레코드 전수 영구 삭제 (단, 실제 차량 GPS/블루투스 주행 기록은 보존)
            val isDiaryMovement = note.contains("다이어리 이동 동선") ||
                (log.logType == VehicleLogType.TRIP_DRIVING && !isRealCarTracking &&
                    (note.contains("방이동") || note.contains("동작동") || note.contains("영등포동") || note.contains("잠실동")))

            if (isDiaryMovement) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 2. OCR 쓰레기 문자열이 포함된 비정상 레코드 영구 삭제 (정상 차량 추적 기록 및 '➔' 경로 포함 주행 기록은 절대 삭제하지 않음)
            val isCorrupted = corruptedKeywords.any { note.contains(it, ignoreCase = true) } ||
                (log.logType == VehicleLogType.TRIP_DRIVING && note.length > 30 &&
                    !isRealCarTracking && !note.contains("➔") &&
                    !note.contains("출근") && !note.contains("퇴근") && !note.contains("주행"))

            if (isCorrupted) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 3. 비정상 시각(새벽 00시~05시 또는 대낮 비정상 시간대)에 생성된 가짜 '출퇴근 왕복' 더미 레코드 삭제
            //    또한 저녁 시간대(17시~23시)에 GPS 추적 없이 수동/더미로 생성된 '출근 주행' 오기록 삭제
            val hour = log.timestamp.hour
            val isUnusualCommuteTime = ((hour in 0..5 || hour in 11..16) && note.contains("출퇴근 왕복")) ||
                (!isRealCarTracking && hour in 17..23 && note.startsWith("출근 주행"))
            if (isUnusualCommuteTime && log.logType == VehicleLogType.TRIP_DRIVING) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 4. 동일 날짜 중복 출근/퇴근 주행 레코드 정리 (출근 1건, 퇴근 1건만 중복 방지하며 일반 주행은 하루 여러 건 모두 보존)
            if (log.logType == VehicleLogType.TRIP_DRIVING) {
                val isToWork = note.contains("출근") && !note.contains("출퇴근")
                val isToHome = note.contains("퇴근") && !note.contains("출퇴근")
                val isCommuteRound = note.contains("출퇴근")
                if (isToWork || isToHome || isCommuteRound) {
                    val directionKey = when {
                        isToWork -> "WORK"
                        isToHome -> "HOME"
                        else -> "ROUND"
                    }
                    val dateKey = "${log.timestamp.toLocalDate()}_${log.logType}_$directionKey"
                    if (dateKey in seenCommuteDates) {
                        vehicleLogDao.deleteVehicleLogById(log.id)
                        modifiedCount++
                        continue
                    }
                    seenCommuteDates.add(dateKey)
                } else {
                    // 일반 주행은 정확히 같은 분(minute) + 같은 거리로 중복 삽입된 경우만 제거하고 하루 다회 주행은 모두 보존
                    val minuteKey = "${log.timestamp.withSecond(0).withNano(0)}_${log.tripDistanceKm}"
                    if (minuteKey in seenMinuteTimestamps) {
                        vehicleLogDao.deleteVehicleLogById(log.id)
                        modifiedCount++
                        continue
                    }
                    seenMinuteTimestamps.add(minuteKey)
                }
            }

            // 5. 과거 '출퇴근 왕복'으로 잘못 묶여 있던 레코드를 실제 출근 편도로 정상화
            if (log.logType == VehicleLogType.TRIP_DRIVING && note.contains("출퇴근 왕복")) {
                val oneWayDistance = if (commuteDistanceKm > 0.0) Math.round((commuteDistanceKm / 2.0) * 10.0) / 10.0 else log.tripDistanceKm
                val updated = log.copy(
                    note = "출근 주행 ($homeName ➔ $companyName)",
                    tripDistanceKm = oneWayDistance
                )
                vehicleLogDao.updateVehicleLog(updated)
                modifiedCount++
                continue
            }

            // 6. "위치 미확인", "GPS 0개 지점", "출발지 ➔ 도착지" 또는 0.0km로 기록된 오염 주행 로그 자가 치유(Self-Healing)
            if (log.logType == VehicleLogType.TRIP_DRIVING) {
                if ((note.contains("출발지 ➔ 도착지") || note == "차량 주행 기록") && log.tripDistanceKm <= 0.0 && !note.contains("운행")) {
                    vehicleLogDao.deleteVehicleLogById(log.id)
                    modifiedCount++
                    continue
                }

                var needsUpdate = false
                var healedDistance = log.tripDistanceKm
                var healedNote = note

                val hasUnknownPlace = healedNote.contains("위치 미확인") ||
                    healedNote.contains("출발 지점") ||
                    healedNote.contains("도착 지점") ||
                    healedNote.contains("출발지 ➔ 도착지") ||
                    healedNote.contains("차량 주행 완료") ||
                    healedNote.contains("GPS 0개 지점")

                if (hasUnknownPlace) {
                    val carTag = Regex("^(\\[[^\\]]+\\])").find(healedNote.trim())?.groupValues?.get(1) ?: "[car_1: 벤츠]"
                    val durationMin = Regex("(\\d+)\\s*분").find(healedNote)?.groupValues?.get(1)?.toIntOrNull() ?: 60
                    val oneWayKm = when {
                        commuteDistanceKm > 0.0 -> Math.round((commuteDistanceKm / 2.0) * 10.0) / 10.0
                        log.tripDistanceKm > 0.0 -> log.tripDistanceKm
                        else -> 27.1
                    }
                    val safeHome = homeName.ifBlank { "송파구 위례동" }
                    val safeComp = companyName.ifBlank { "영등포구 영등포동" }

                    when (log.timestamp.hour) {
                        in 5..11 -> {
                            healedDistance = oneWayKm
                            healedNote = "$carTag [$safeHome ➔ $safeComp (출근 %.1f km)] (${durationMin}분 운행 · 대략 위치 동네명 보정)".format(java.util.Locale.US, healedDistance)
                        }
                        in 16..23 -> {
                            healedDistance = oneWayKm
                            healedNote = "$carTag [$safeComp ➔ $safeHome (퇴근 %.1f km)] (${durationMin}분 운행 · 대략 위치 동네명 보정)".format(java.util.Locale.US, healedDistance)
                        }
                        else -> {
                            if (healedDistance <= 0.0) healedDistance = oneWayKm
                            healedNote = "$carTag [$safeHome ➔ $safeComp (%.1f km)] (${durationMin}분 운행 · 대략 위치 동네명 보정)".format(java.util.Locale.US, healedDistance)
                        }
                    }
                    needsUpdate = true
                }

                // 도로명 주소가 그대로 노출된 기존 주행 로그도 동네명(시/구 + 동)으로 일관되게 정규화
                if (homeName.isNotBlank() && healedNote.contains("위례대로6길 20") && homeName != "위례대로6길 20") {
                    healedNote = healedNote.replace("위례대로6길 20", homeName)
                    needsUpdate = true
                }
                if (companyName.isNotBlank() && healedNote.contains("영등포로 254") && companyName != "영등포로 254") {
                    healedNote = healedNote.replace("영등포로 254", companyName)
                    needsUpdate = true
                }

                // 주행거리가 0.0km 이하인 경우 운행 시간 기반 합리적 추정 거리로 복원
                if (healedDistance <= 0.0) {
                    val minMatch = Regex("(\\d+)\\s*분").find(healedNote)
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
