package com.autologue.app.data.repository

import com.autologue.app.data.local.db.dao.VehicleLogDao
import com.autologue.app.data.local.entity.VehicleLogEntity
import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.data.preferences.MerchantAliasPreferences
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.estimateRealisticCommuteMinutes
import com.autologue.app.domain.model.formatDrivingDurationLabel
import com.autologue.app.domain.model.getUserTypeOverride
import com.autologue.app.domain.model.normalizeInflatedDrivingLog
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
    private val merchantAliasPreferences: MerchantAliasPreferences,
    private val expenseCustomMetaPreferences: ExpenseCustomMetaPreferences
) : VehicleRepository {

    constructor(
        vehicleLogDao: VehicleLogDao,
        merchantAliasPreferences: MerchantAliasPreferences = MerchantAliasPreferences()
    ) : this(vehicleLogDao, merchantAliasPreferences, ExpenseCustomMetaPreferences())

    private fun isOneTimeFuelLog(note: String?): Boolean {
        return note?.contains("[one_time_edit:true]") == true || note?.contains("[user_type_override:") == true
    }

    private fun isCorruptedOrAdVehicleLog(
        logType: VehicleLogType,
        stationName: String?,
        note: String?
    ): Boolean {
        if (logType != VehicleLogType.REFUELING &&
            logType != VehicleLogType.PARKING &&
            logType != VehicleLogType.MAINTENANCE
        ) {
            return false
        }
        val clean = stationName?.trim() ?: ""
        if (clean.isBlank()) return false
        if (com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(clean)) {
            return true
        }
        val noteStr = note ?: ""
        val isExplicitManualInput = noteStr.contains("직접 등록") || noteStr.contains("직접 입력")
        if (isExplicitManualInput && !com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(clean)) {
            return false
        }
        val isUserAliased = isOneTimeFuelLog(noteStr) || merchantAliasPreferences.isKnownAliasTarget(clean)
        return !isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(clean)
    }

    private fun isProtectedFuelStation(stationName: String, note: String?): Boolean {
        val clean = stationName.trim()
        if (clean.isBlank()) return false
        if (com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(clean) ||
            com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(clean)
        ) {
            return false
        }
        val noteStr = note ?: ""
        val isAutoSynced = noteStr.contains("가계부 결제 내역 자동 분석")
        val isManualRefuel = noteStr.contains("직접 등록") ||
            noteStr.contains("직접 입력") ||
            (!isAutoSynced && noteStr.contains("카테고리 변경 연동")) ||
            noteStr.contains("[custom_fuel:true]") ||
            noteStr.contains("[custom_name:true]") ||
            noteStr.contains("[one_time_edit:true]")
        val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(clean) ||
            merchantAliasPreferences.getOriginalNamesForTarget(clean).any { isFuelMerchant(it) }
        return isManualRefuel || isUserAliased || isFuelMerchant(clean) || clean == "주유소"
    }

    override fun getAllVehicleLogsFlow(): Flow<List<VehicleLog>> {
        return kotlinx.coroutines.flow.combine(
            vehicleLogDao.getAllVehicleLogs(),
            merchantAliasPreferences.aliasesFlow
        ) { list, _ ->
            list.asSequence()
                .filterNot { e -> isCorruptedOrAdVehicleLog(e.logType, e.gasStationName, e.note) }
                .map { e -> e.toDomain().normalizeInflatedDrivingLog() }
                .toList()
        }
    }

    override fun getRefuelingLogsFlow(): Flow<List<VehicleLog>> {
        return kotlinx.coroutines.flow.combine(
            vehicleLogDao.getRefuelingLogs(),
            merchantAliasPreferences.aliasesFlow
        ) { list, _ ->
            list.asSequence()
                .filterNot { e -> isCorruptedOrAdVehicleLog(e.logType, e.gasStationName, e.note) }
                .map { e -> e.toDomain() }
                .toList()
        }
    }

    override suspend fun insertVehicleLog(log: VehicleLog): Long = withContext(Dispatchers.IO) {
        if (isCorruptedOrAdVehicleLog(log.logType, log.gasStationName, log.note)) {
            return@withContext -1L
        }
        val normalizedTypeLog = if (log.note?.contains("[user_type_override:") != true && log.logType == VehicleLogType.REFUELING && log.fuelCost in 1L until com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON) {
            log.copy(
                logType = VehicleLogType.PARKING,
                fuelAmountLiters = 0.0,
                daysSinceLastFuel = null,
                tripDistanceKm = 0.0,
                estimatedEfficiencyKmPerL = null
            )
        } else {
            log
        }
        val isFuelOrMaint = normalizedTypeLog.logType == VehicleLogType.REFUELING ||
            normalizedTypeLog.logType == VehicleLogType.MAINTENANCE ||
            normalizedTypeLog.logType == VehicleLogType.PARKING
        val finalLog = if (isFuelOrMaint && !normalizedTypeLog.gasStationName.isNullOrBlank() && !isOneTimeFuelLog(normalizedTypeLog.note)) {
            val resolved = merchantAliasPreferences.resolveAlias(normalizedTypeLog.gasStationName, isDeposit = false)
            if (resolved.isNotBlank() && resolved != normalizedTypeLog.gasStationName) {
                normalizedTypeLog.copy(gasStationName = resolved)
            } else {
                normalizedTypeLog
            }
        } else {
            normalizedTypeLog
        }

        if (finalLog.logType == VehicleLogType.REFUELING) {
            val existing = vehicleLogDao.findExactFuelLog(finalLog.timestamp, finalLog.fuelCost, finalLog.gasStationName)
                ?: vehicleLogDao.findExactFuelLog(finalLog.timestamp, finalLog.fuelCost, log.gasStationName)
            if (existing != null) {
                if (!isOneTimeFuelLog(existing.note) && existing.gasStationName != finalLog.gasStationName && !finalLog.gasStationName.isNullOrBlank()) {
                    vehicleLogDao.updateVehicleLog(existing.copy(gasStationName = finalLog.gasStationName))
                }
                return@withContext existing.id
            }
        } else if (finalLog.logType == VehicleLogType.MAINTENANCE || finalLog.logType == VehicleLogType.PARKING) {
            val existingMaintOrPark = vehicleLogDao.getAllVehicleLogsSync().firstOrNull {
                (it.logType == VehicleLogType.MAINTENANCE || it.logType == VehicleLogType.PARKING || it.logType == VehicleLogType.REFUELING) &&
                    it.timestamp.toLocalDate() == finalLog.timestamp.toLocalDate() &&
                    it.fuelCost == finalLog.fuelCost &&
                    (it.gasStationName == finalLog.gasStationName || it.gasStationName == log.gasStationName)
            }
            if (existingMaintOrPark != null) {
                val updated = existingMaintOrPark.copy(
                    logType = finalLog.logType,
                    gasStationName = if (!isOneTimeFuelLog(existingMaintOrPark.note) && !finalLog.gasStationName.isNullOrBlank()) finalLog.gasStationName else existingMaintOrPark.gasStationName,
                    fuelAmountLiters = if (finalLog.logType == VehicleLogType.PARKING) 0.0 else existingMaintOrPark.fuelAmountLiters,
                    daysSinceLastFuel = if (finalLog.logType == VehicleLogType.PARKING) null else existingMaintOrPark.daysSinceLastFuel
                )
                if (updated != existingMaintOrPark) {
                    vehicleLogDao.updateVehicleLog(updated)
                }
                return@withContext existingMaintOrPark.id
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

    override suspend fun deleteVehicleLog(id: Long): Unit = withContext(Dispatchers.IO) {
        val entity = vehicleLogDao.getVehicleLogById(id)
        if (entity != null) {
            expenseCustomMetaPreferences.markVehicleLogDeleted(entity.toDomain())
        }
        vehicleLogDao.deleteVehicleLogById(id)
    }

    override suspend fun deleteVehicleLogs(ids: Collection<Long>): Int = withContext(Dispatchers.IO) {
        if (ids.isEmpty()) return@withContext 0
        val all = vehicleLogDao.getAllVehicleLogsSync()
        val idSet = ids.toSet()
        val targets = all.filter { it.id in idSet }
        expenseCustomMetaPreferences.markVehicleLogsDeleted(targets.map { it.toDomain() })
        var deletedCount = 0
        for (entity in targets) {
            vehicleLogDao.deleteVehicleLogById(entity.id)
            deletedCount++
        }
        deletedCount
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
            rawOriginalName = rawOriginalStationName,
            isDeposit = false
        )

        val all = vehicleLogDao.getAllVehicleLogsSync()
        var updatedCount = 0
        for (entity in all) {
            if (entity.logType != VehicleLogType.REFUELING &&
                entity.logType != VehicleLogType.MAINTENANCE &&
                entity.logType != VehicleLogType.PARKING
            ) continue
            if (isOneTimeFuelLog(entity.note)) continue

            val currentStation = entity.gasStationName?.trim().orEmpty()
            val isMatchedOld = merchantAliasPreferences.isSameMerchant(currentStation, oldStationName) ||
                (!rawOriginalStationName.isNullOrBlank() && merchantAliasPreferences.isSameMerchant(currentStation, rawOriginalStationName))
            val resolved = if (isMatchedOld) {
                targetName
            } else {
                merchantAliasPreferences.resolveAlias(currentStation, isDeposit = false)
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

        val MAINTENANCE_KEYWORDS = listOf(
            "정비", "카센터", "오토큐", "블루핸즈", "스피드메이트", "오토오아시스", "공임나라",
            "타이어", "티스테이션", "타이어뱅크", "타이어프로", "세차", "손세차", "셀프세차",
            "광택", "디테일링", "엔진오일", "와이퍼", "블랙박스", "썬팅"
        )

        fun isFuelMerchant(merchantName: String): Boolean {
            if (com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(merchantName)) return false
            val upper = merchantName.uppercase().replace(" ", "")
            return FUEL_KEYWORDS.any { upper.contains(it.uppercase().replace(" ", "")) }
        }

        fun isMaintenanceMerchant(merchantName: String): Boolean {
            if (com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(merchantName)) return false
            val upper = merchantName.uppercase().replace(" ", "")
            return MAINTENANCE_KEYWORDS.any { upper.contains(it.uppercase().replace(" ", "")) }
        }
    }

    override suspend fun syncRefuelingFromTransactions(transactions: List<com.autologue.app.domain.model.Transaction>): Int = withContext(Dispatchers.IO) {
        var addedCount = 0

        // 가계부에서 명시적으로 주유/충전 또는 차량정비/세차 카테고리로 지정된 가맹점 집합 (광고/비결제/무효 상호명 제외)
        val explicitVehicleTxKeys = transactions
            .filter { tx ->
                !com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(tx.merchantName) &&
                    !com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(tx.originalText) &&
                    (tx.category == com.autologue.app.domain.model.ExpenseCategory.FUEL ||
                        tx.category == com.autologue.app.domain.model.ExpenseCategory.CAR_MAINTENANCE)
            }
            .map { "${it.timestamp.toLocalDate()}_${it.amount}" }
            .toSet()

        // 0. 기존 차계부 DB에 잘못 들어간 광고/안내/비정상 로그 선제 삭제, 3만원 미만 주유 내역의 주차(PARKING) 전환 및 사용자 지정 별칭 자동 치환
        val initialLogs = vehicleLogDao.getAllVehicleLogsSync()
        for (entity in initialLogs) {
            if (entity.logType == VehicleLogType.REFUELING ||
                entity.logType == VehicleLogType.MAINTENANCE ||
                entity.logType == VehicleLogType.PARKING
            ) {
                val rawStation = entity.gasStationName?.trim() ?: ""
                val resolvedStation = if (isOneTimeFuelLog(entity.note)) {
                    rawStation
                } else {
                    merchantAliasPreferences.resolveAlias(rawStation, isDeposit = false)
                }
                val candidateStation = if (resolvedStation.isNotBlank()) resolvedStation else rawStation
                if (isCorruptedOrAdVehicleLog(entity.logType, rawStation, entity.note) ||
                    isCorruptedOrAdVehicleLog(entity.logType, candidateStation, entity.note)
                ) {
                    vehicleLogDao.deleteVehicleLogById(entity.id)
                    continue
                }

                val isUnderMinRefuel = entity.note?.contains("[user_type_override:") != true && entity.logType == VehicleLogType.REFUELING &&
                    entity.fuelCost in 1L until com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON
                val targetType = if (isUnderMinRefuel) VehicleLogType.PARKING else entity.logType

                val currentEntity = if ((resolvedStation.isNotBlank() && resolvedStation != rawStation) || isUnderMinRefuel) {
                    val finalStation = if (resolvedStation.isNotBlank()) resolvedStation else rawStation
                    val noteWithTag = if (resolvedStation.isNotBlank() && resolvedStation != rawStation && !(entity.note ?: "").contains("[custom_name:true]")) {
                        "${entity.note.orEmpty()} [custom_name:true]".trim()
                    } else {
                        entity.note
                    }
                    val updated = entity.copy(
                        logType = targetType,
                        gasStationName = finalStation,
                        fuelAmountLiters = if (targetType == VehicleLogType.PARKING) 0.0 else entity.fuelAmountLiters,
                        daysSinceLastFuel = if (targetType == VehicleLogType.PARKING) null else entity.daysSinceLastFuel,
                        tripDistanceKm = if (targetType == VehicleLogType.PARKING) 0.0 else entity.tripDistanceKm,
                        estimatedEfficiencyKmPerL = if (targetType == VehicleLogType.PARKING) null else entity.estimatedEfficiencyKmPerL,
                        note = noteWithTag
                    )
                    vehicleLogDao.updateVehicleLog(updated)
                    updated
                } else {
                    entity
                }

                if (currentEntity.logType == VehicleLogType.REFUELING) {
                    val station = currentEntity.gasStationName?.trim() ?: ""
                    val note = currentEntity.note ?: ""
                    val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(station, isDeposit = false)
                    val isExplicitFromTx = "${currentEntity.timestamp.toLocalDate()}_${currentEntity.fuelCost}" in explicitVehicleTxKeys
                    if ((!isUserAliased && !isOneTimeFuelLog(note) && !isExplicitFromTx && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(station)) ||
                        (!isExplicitFromTx && !isProtectedFuelStation(station, note))
                    ) {
                        vehicleLogDao.deleteVehicleLogById(currentEntity.id)
                    }
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

        // 2. 주유/충전, 주차 및 차량정비/세차 관련 거래 필터링
        val vehicleTransactions = transactions.filter { tx ->
            if (expenseCustomMetaPreferences.isTransactionDeleted(tx) ||
                expenseCustomMetaPreferences.isRefuelingSyncDeleted(tx.timestamp.toLocalDate(), tx.amount)
            ) {
                return@filter false
            }
            val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
            val isUserAliased = merchantAliasPreferences.isKnownAliasTarget(tx.merchantName, isDeposit = false) ||
                expenseCustomMetaPreferences.isOneTimeEdited(tx) ||
                expenseCustomMetaPreferences.resolveMerchantBaseCategory(tx.merchantName, isDeposit = false) != null
            if (!isManual && (
                    (!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(tx.merchantName)) ||
                    com.autologue.app.data.parser.SmsParser.isNonPaymentMessage(tx.originalText)
                )
            ) {
                return@filter false
            }
            val customCatLabel = expenseCustomMetaPreferences.resolveCustomCategoryLabel(tx).orEmpty()
            val isCustomFuelOrMaint = customCatLabel.contains("주유") ||
                customCatLabel.contains("주차") ||
                customCatLabel.contains("충전") ||
                customCatLabel.contains("정비") ||
                customCatLabel.contains("세차")

            val isCardMsg = !isManual && com.autologue.app.data.parser.SmsParser.isCardApprovalMessage(tx.originalText)
            val isAliasFromFuel = merchantAliasPreferences.getOriginalNamesForTarget(tx.merchantName, isDeposit = false).any { isFuelMerchant(it) }
            val isFuel = tx.category == com.autologue.app.domain.model.ExpenseCategory.FUEL ||
                isFuelMerchant(tx.merchantName) ||
                isAliasFromFuel ||
                (!isManual && tx.merchantName in setOf("카드 가맹점", "가맹점") && isCardMsg && isFuelMerchant(tx.originalText))
            val isMaint = tx.category == com.autologue.app.domain.model.ExpenseCategory.CAR_MAINTENANCE ||
                isCustomFuelOrMaint

            (isFuel || isMaint) && tx.amount > 0
        }

        // 날짜 및 상호별 그룹화하여 셀프주유소 가승인(15만원/10만원) 판별 및 차계부 동기화
        for (tx in vehicleTransactions) {
            val txDate = tx.timestamp.toLocalDate()
            if (expenseCustomMetaPreferences.isRefuelingSyncDeleted(txDate, tx.amount)) continue

            val customCatLabel = expenseCustomMetaPreferences.resolveCustomCategoryLabel(tx).orEmpty()
            val isMaintTx = tx.category == com.autologue.app.domain.model.ExpenseCategory.CAR_MAINTENANCE ||
                customCatLabel.contains("정비") ||
                customCatLabel.contains("세차")
            // 주유/주차 카테고리의 경우 금액이 3만원 미만이면 주차(PARKING)로 분류
            val isParkingTx = !isMaintTx && (
                tx.amount in 1L until com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON ||
                customCatLabel == "주차"
            )
            val targetLogType = when {
                isMaintTx -> VehicleLogType.MAINTENANCE
                isParkingTx -> VehicleLogType.PARKING
                else -> VehicleLogType.REFUELING
            }

            val baseStationName = if (tx.merchantName in listOf("카드 가맹점", "가맹점", "출금 내역", "계좌 이체", "정선우", "정*우") || tx.merchantName.isBlank()) {
                com.autologue.app.data.parser.SmsParser.parse(null, tx.originalText, tx.timestamp)?.merchantName
                    ?.takeIf { !com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(it) }
                    ?: FUEL_KEYWORDS.firstOrNull { tx.originalText.uppercase().contains(it.uppercase()) }
                    ?: when {
                        isMaintTx -> "차량정비/세차"
                        isParkingTx -> "주차"
                        else -> continue
                    }
            } else {
                tx.merchantName
            }
            val isTxOneTime = expenseCustomMetaPreferences.isOneTimeEdited(tx)
            val resolvedStationName = if (isTxOneTime) {
                baseStationName
            } else {
                merchantAliasPreferences.resolveAlias(baseStationName, isDeposit = false).ifBlank { baseStationName }
            }
            val isUserAliased = isTxOneTime || merchantAliasPreferences.isKnownAliasTarget(resolvedStationName, isDeposit = false)

            if (!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(resolvedStationName)) continue

            if (targetLogType == VehicleLogType.REFUELING) {
                val sameDayMerchantTxs = vehicleTransactions.filter {
                    it.category == com.autologue.app.domain.model.ExpenseCategory.FUEL &&
                        it.timestamp.toLocalDate() == txDate &&
                        (it.merchantName == tx.merchantName || isFuelMerchant(it.merchantName))
                }

                // 셀프 주유소 가승인 금액(150,000원 또는 100,000원)인데,
                // 같은 날 같은 주유소에 다른 실제 주유 금액이 있거나 취소 내역이 있으면 가승인 건은 차계부 등록 제외
                val isCommonPreAuthAmount = tx.amount == 150000L || tx.amount == 100000L
                val hasOtherRealFuelTx = sameDayMerchantTxs.any { it.amount != tx.amount }
                val hasCancellationForThis = cancelledItems.any {
                    it.timestamp.toLocalDate() == txDate && Math.abs(it.amount) == tx.amount
                }

                if (isCommonPreAuthAmount && (hasOtherRealFuelTx || hasCancellationForThis)) {
                    deleteRefuelingLogByTransaction(tx.amount, txDate, resolvedStationName)
                    continue
                }
            }

            val existingMatchIdx = currentLogs.indexOfFirst {
                (it.logType == VehicleLogType.REFUELING || it.logType == VehicleLogType.MAINTENANCE || it.logType == VehicleLogType.PARKING) &&
                    it.timestamp.toLocalDate() == txDate &&
                    it.fuelCost == tx.amount
            }

            if (existingMatchIdx >= 0) {
                val existingLog = currentLogs[existingMatchIdx]
                val needsUpdate = existingLog.gasStationName != resolvedStationName ||
                    (existingLog.note?.contains("[user_type_override:") != true && existingLog.logType != targetLogType)
                if (needsUpdate) {
                    val currentNote = existingLog.note.orEmpty()
                    val updatedNote = if (isTxOneTime && !currentNote.contains("[one_time_edit:true]")) {
                        "$currentNote [custom_name:true] [one_time_edit:true]".trim()
                    } else if (!isTxOneTime && currentNote.contains("[one_time_edit:true]")) {
                        currentNote.replace("[one_time_edit:true]", "").trim()
                    } else {
                        currentNote
                    }
                    val updatedLog = existingLog.copy(
                        logType = if (existingLog.note?.contains("[user_type_override:") == true) existingLog.logType else targetLogType,
                        gasStationName = resolvedStationName,
                        fuelAmountLiters = if (targetLogType == VehicleLogType.PARKING) 0.0 else existingLog.fuelAmountLiters,
                        daysSinceLastFuel = if (targetLogType == VehicleLogType.PARKING) null else existingLog.daysSinceLastFuel,
                        tripDistanceKm = if (targetLogType == VehicleLogType.PARKING) 0.0 else existingLog.tripDistanceKm,
                        estimatedEfficiencyKmPerL = if (targetLogType == VehicleLogType.PARKING) null else existingLog.estimatedEfficiencyKmPerL,
                        note = updatedNote
                    )
                    vehicleLogDao.updateVehicleLog(updatedLog.toEntity())
                    currentLogs[existingMatchIdx] = updatedLog
                }
            } else {
                val daysSince = if (targetLogType == VehicleLogType.REFUELING) {
                    val lastFuel = currentLogs.filter { it.logType == VehicleLogType.REFUELING && it.fuelCost >= com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON }
                        .filter { it.timestamp.isBefore(tx.timestamp) }
                        .maxByOrNull { it.timestamp }
                    if (lastFuel != null) {
                        java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), tx.timestamp.toLocalDate()).toInt()
                    } else null
                } else null

                val liters = if (targetLogType == VehicleLogType.REFUELING) {
                    String.format(java.util.Locale.US, "%.1f", tx.amount / 1650.0).toDouble()
                } else 0.0

                val log = VehicleLog(
                    timestamp = tx.timestamp,
                    logType = targetLogType,
                    fuelCost = tx.amount,
                    fuelAmountLiters = liters,
                    daysSinceLastFuel = daysSince,
                    gasStationName = resolvedStationName,
                    note = when {
                        isTxOneTime -> "가계부 카테고리 변경 연동 [custom_name:true] [one_time_edit:true]"
                        isUserAliased -> "가계부 카테고리 변경 연동 [custom_name:true]"
                        else -> "가계부 결제 내역 자동 분석 [카테고리 변경 연동]"
                    }
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
        val resolvedMerchant = merchantAliasPreferences.resolveAlias(merchantName, isDeposit = false)
        val matched = logs.filter {
            (it.logType == VehicleLogType.REFUELING || it.logType == VehicleLogType.MAINTENANCE || it.logType == VehicleLogType.PARKING) &&
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
            "일반 사진", "OCR 실패", "BMARTSCC나", "나인CC", "나인 CC", "온라인CC", "확인CC"
        )
        val today = java.time.LocalDate.now()
        val nowPlus1Hour = java.time.LocalDateTime.now().plusHours(1)

        for (rawLog in all) {
            var log = rawLog
            var note = log.note ?: ""

            // -1. 미래 날짜(오늘 이후) 또는 현재 시각보다 미래인 잘못된 일정/주행/주유 레코드 즉시 삭제 (예: 10/24 나인CC 왕복 주행 등)
            if (log.timestamp.toLocalDate().isAfter(today) || log.timestamp.isAfter(nowPlus1Hour)) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

            // 0. 광고/안내/비정상 상호명 오탐 레코드 즉시 삭제, 3만원 미만 주차(PARKING) 전환 및 별칭 자동 치환
            if (log.logType == VehicleLogType.REFUELING ||
                log.logType == VehicleLogType.PARKING ||
                log.logType == VehicleLogType.MAINTENANCE
            ) {
                val rawStation = log.gasStationName?.trim() ?: ""
                val resolvedStation = if (isOneTimeFuelLog(note)) {
                    rawStation
                } else {
                    merchantAliasPreferences.resolveAlias(rawStation)
                }
                val candidateStation = if (resolvedStation.isNotBlank()) resolvedStation else rawStation
                if (isCorruptedOrAdVehicleLog(log.logType, rawStation, note) ||
                    isCorruptedOrAdVehicleLog(log.logType, candidateStation, note)
                ) {
                    vehicleLogDao.deleteVehicleLogById(log.id)
                    modifiedCount++
                    continue
                }

                val isUnderMinRefuel = !note.contains("[user_type_override:") && log.logType == VehicleLogType.REFUELING &&
                    log.fuelCost in 1L until com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON
                if ((resolvedStation.isNotBlank() && resolvedStation != rawStation) || isUnderMinRefuel) {
                    val finalStation = if (resolvedStation.isNotBlank()) resolvedStation else rawStation
                    val updatedNote = if (resolvedStation.isNotBlank() && resolvedStation != rawStation && !note.contains("[custom_name:true]")) {
                        "$note [custom_name:true]".trim()
                    } else {
                        note
                    }
                    log = log.copy(
                        logType = if (isUnderMinRefuel) VehicleLogType.PARKING else log.logType,
                        gasStationName = finalStation,
                        fuelAmountLiters = if (isUnderMinRefuel) 0.0 else log.fuelAmountLiters,
                        daysSinceLastFuel = if (isUnderMinRefuel) null else log.daysSinceLastFuel,
                        tripDistanceKm = if (isUnderMinRefuel) 0.0 else log.tripDistanceKm,
                        estimatedEfficiencyKmPerL = if (isUnderMinRefuel) null else log.estimatedEfficiencyKmPerL,
                        note = updatedNote
                    )
                    note = updatedNote
                    vehicleLogDao.updateVehicleLog(log)
                    modifiedCount++
                }
                if (log.logType == VehicleLogType.REFUELING) {
                    val station = log.gasStationName?.trim() ?: ""
                    val isUserAliased = isOneTimeFuelLog(note) || merchantAliasPreferences.isKnownAliasTarget(station)
                    if ((!isUserAliased && com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(station)) ||
                        !isProtectedFuelStation(station, note)
                    ) {
                        vehicleLogDao.deleteVehicleLogById(log.id)
                        modifiedCount++
                        continue
                    }
                }
            }

            // 실제 블루투스/GPS 추적 서비스가 생성한 정상 주행 기록인지 판별
            val isRealCarTracking = note.contains("운행") ||
                note.contains("[car_") ||
                note.contains("블루투스") ||
                note.contains("GPS")

            // 0. 미래 날짜/시각 오류 레코드 및 레거시 골프 라운드 자동 복원('라운딩 왕복 주행', '나인CC') 레코드 영구 삭제
            val todayDate = java.time.LocalDate.now()
            val nowTime = java.time.LocalDateTime.now()
            val isLegacyGolfAutoLog = log.logType == VehicleLogType.TRIP_DRIVING && !isRealCarTracking &&
                (note.contains("라운딩 왕복 주행") || note.contains("나인CC", ignoreCase = true) || note.contains("나인 CC", ignoreCase = true))
            if (log.timestamp.toLocalDate().isAfter(todayDate) || log.timestamp.isAfter(nowTime.plusHours(1)) || isLegacyGolfAutoLog) {
                vehicleLogDao.deleteVehicleLogById(log.id)
                modifiedCount++
                continue
            }

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
                val estMin = estimateRealisticCommuteMinutes(log.timestamp, oneWayDistance)
                val durLabel = formatDrivingDurationLabel(estMin)
                val baseLabel = if (com.autologue.app.util.KoreanHolidayUtils.isCommuteEligibleWorkday(log.timestamp.toLocalDate())) {
                    "출근 주행 [$homeName ➔ $companyName]"
                } else {
                    "일반 주행 [$homeName ➔ $companyName]"
                }
                val updated = log.copy(
                    note = if (durLabel.isNotBlank()) "$baseLabel ($durLabel 운행)" else baseLabel,
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

                    when {
                        log.timestamp.hour in 5..11 && com.autologue.app.util.KoreanHolidayUtils.isCommuteEligibleWorkday(log.timestamp.toLocalDate()) -> {
                            healedDistance = oneWayKm
                            healedNote = "$carTag [$safeHome ➔ $safeComp (출근 %.1f km)] (${durationMin}분 운행 · 대략 위치 동네명 보정)".format(java.util.Locale.US, healedDistance)
                        }
                        log.timestamp.hour in 16..23 && com.autologue.app.util.KoreanHolidayUtils.isCommuteEligibleWorkday(log.timestamp.toLocalDate()) -> {
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

                // 6-1. GPS↔기지국 교차 수신(Ping-Pong) 노이즈로 비정상 부풀려진 주행거리(예: 퇴근 117.4 km) 자가 치유
                val refOneWayKm = if (commuteDistanceKm > 0.0) {
                    Math.round((commuteDistanceKm / 2.0) * 10.0) / 10.0
                } else 27.1
                val normalizedDomain = log.copy(note = healedNote, tripDistanceKm = healedDistance)
                    .toDomain()
                    .normalizeInflatedDrivingLog(refOneWayKm)
                if (normalizedDomain.tripDistanceKm != healedDistance || normalizedDomain.note != healedNote) {
                    healedDistance = normalizedDomain.tripDistanceKm
                    healedNote = normalizedDomain.note ?: healedNote
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

        val estMin = estimateRealisticCommuteMinutes(now, distanceKm)
        val durText = formatDrivingDurationLabel(estMin)
        val direction = if (isToWork) {
            String.format(java.util.Locale.US, "[집->회사(%.1fkm)] 출근 주행 (%s 운행)", distanceKm, durText)
        } else {
            String.format(java.util.Locale.US, "[회사->집(%.1fkm)] 퇴근 주행 (%s 운행)", distanceKm, durText)
        }
        val log = VehicleLog(
            timestamp = now,
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = distanceKm,
            note = direction
        )
        vehicleLogDao.insertVehicleLog(log.toEntity())
    }


    private fun VehicleLogEntity.toDomain(): VehicleLog {
        val effectiveType = if (logType == VehicleLogType.REFUELING && fuelCost in 1L until com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON) {
            VehicleLogType.PARKING
        } else {
            logType
        }
        val resolvedStation = if (
            (effectiveType == VehicleLogType.REFUELING || effectiveType == VehicleLogType.MAINTENANCE || effectiveType == VehicleLogType.PARKING) &&
            !isOneTimeFuelLog(note) &&
            !gasStationName.isNullOrBlank()
        ) {
            merchantAliasPreferences.resolveAlias(gasStationName, isDeposit = false).ifBlank {
                MerchantAliasPreferences.sanitizeSingleLine(gasStationName)
            }
        } else {
            gasStationName?.let { MerchantAliasPreferences.sanitizeSingleLine(it).ifBlank { null } }
        }
        return VehicleLog(
            id = id, timestamp = timestamp, logType = effectiveType, fuelCost = fuelCost,
            fuelAmountLiters = if (effectiveType == VehicleLogType.PARKING) 0.0 else fuelAmountLiters,
            daysSinceLastFuel = if (effectiveType == VehicleLogType.PARKING) null else daysSinceLastFuel,
            tripDistanceKm = if (effectiveType == VehicleLogType.PARKING) 0.0 else tripDistanceKm,
            currentOdometerKm = currentOdometerKm,
            estimatedEfficiencyKmPerL = if (effectiveType == VehicleLogType.PARKING) null else estimatedEfficiencyKmPerL,
            gasStationName = resolvedStation, note = note
        )
    }

    private fun VehicleLog.toEntity() = VehicleLogEntity(
        id = id, timestamp = timestamp, logType = logType, fuelCost = fuelCost,
        fuelAmountLiters = fuelAmountLiters, daysSinceLastFuel = daysSinceLastFuel,
        tripDistanceKm = tripDistanceKm, currentOdometerKm = currentOdometerKm,
        estimatedEfficiencyKmPerL = estimatedEfficiencyKmPerL,
        gasStationName = gasStationName?.let { MerchantAliasPreferences.sanitizeSingleLine(it).ifBlank { null } },
        note = note
    )
}
