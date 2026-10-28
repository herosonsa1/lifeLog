package com.autologue.app.data.sync

import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.estimateRealisticCommuteMinutes
import com.autologue.app.domain.model.estimateRealisticNonCommuteMinutes
import com.autologue.app.domain.model.extractDrivingDurationMinutes
import com.autologue.app.domain.model.extractDrivingDurationMinutesFromText
import com.autologue.app.domain.model.formatDrivingDurationLabel
import com.autologue.app.domain.model.resolveEffectiveDrivingDurationMinutes
import com.autologue.app.domain.model.simplifyRoutePlaceName
import com.autologue.app.domain.model.stripTrailingRouteMetricsParens
import com.autologue.app.util.KoreanHolidayUtils
import com.autologue.app.util.LocationDistanceUtils
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

data class ScannedPhoto(
    val uri: String,
    val time: LocalDateTime,
    val placeName: String? = null,
    val address: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    val companions: List<String> = emptyList(),
    val tags: List<String> = emptyList()
)

@Singleton
class DailyRouteAggregator @Inject constructor(
    private val placeResolver: PlaceResolver
) {

    fun aggregateForDate(
        date: LocalDate,
        photos: List<ScannedPhoto>,
        transactions: List<Transaction>,
        vehicleLogs: List<VehicleLog> = emptyList(),
        existingRouteSteps: List<RouteStep> = emptyList()
    ): DiaryEntry {
        val steps = mutableListOf<RouteStep>()

        // 1. Cluster photos by time (~45 min) and place (중복 사진 URI 사전 제거)
        val cleanPhotos = photos.distinctBy { it.uri }
        val sortedPhotos = cleanPhotos.sortedBy { it.time }
        val photoClusters = mutableListOf<MutableList<ScannedPhoto>>()

        for (photo in sortedPhotos) {
            val lastCluster = photoClusters.lastOrNull()
            if (lastCluster != null) {
                val lastPhoto = lastCluster.last()
                val minutesDiff = java.time.Duration.between(lastPhoto.time, photo.time).abs().toMinutes()
                val samePlace = lastPhoto.placeName == photo.placeName && !photo.placeName.isNullOrBlank()

                if (minutesDiff <= 45 || samePlace) {
                    lastCluster.add(photo)
                    continue
                }
            }
            photoClusters.add(mutableListOf(photo))
        }

        for (cluster in photoClusters) {
            val repTime = cluster.first().time
            val repAddress = PlaceResolver.sanitizeAddressForDisplay(cluster.firstNotNullOfOrNull { it.address })
            val repLat = cluster.firstNotNullOfOrNull { it.latitude }
            val repLng = cluster.firstNotNullOfOrNull { it.longitude }
            val rawRepPlace = cluster.firstNotNullOfOrNull {
                it.placeName?.takeIf { p ->
                    !p.contains("사진 촬영") && !p.contains("촬영 장소") && p != "사진 기록"
                }
            }
            val repPlace = when {
                rawRepPlace != null -> PlaceResolver.cleanDisplayPlaceName(rawRepPlace, repAddress).ifBlank { rawRepPlace }
                !repAddress.isNullOrBlank() -> PlaceResolver.cleanDisplayPlaceName("", repAddress).ifBlank { "사진 기록" }
                else -> "사진 기록"
            }
            val uris = cluster.map { it.uri }.distinct()
            val companions = cluster.flatMap { it.companions }.distinct()
            val tags = cluster.flatMap { it.tags }.distinct()

            steps.add(
                RouteStep(
                    id = UUID.randomUUID().toString(),
                    time = repTime,
                    stepType = RouteStepType.PHOTO,
                    title = repPlace,
                    description = buildString {
                        append("사진 ${uris.size}장 촬영")
                        if (companions.isNotEmpty()) {
                            append(" · 동행: ${companions.joinToString(", ")}")
                        }
                    },
                    locationName = repPlace,
                    address = repAddress,
                    latitude = repLat,
                    longitude = repLng,
                    photoUris = uris,
                    category = "사진 기록",
                    companions = companions,
                    tags = tags
                )
            )
        }

        // 2. Map Transactions (입출금·송금·계좌이체 및 누적이용금액/결제예정금액/총사용금액 오인입 내역은 동선 및 타임라인에서 완전 제외)
        val validExpenseTxs = transactions.filter {
            !isFinancialIncomeOrTransfer(it) &&
                !com.autologue.app.data.parser.SmsParser.isNonUsageOrCorruptedTransaction(
                    it.merchantName,
                    it.cardOrBankName,
                    it.originalText
                )
        }
        for (tx in validExpenseTxs) {
            val desc = buildString {
                append("%,d원 결제".format(tx.amount))
                append(" · ${tx.category.displayName}")
                if (tx.cardOrBankName.isNotBlank()) {
                    append(" (${tx.cardOrBankName})")
                }
                if (!tx.transferMemo.isNullOrBlank()) {
                    append(" · 메모: ${tx.transferMemo}")
                }
            }

            steps.add(
                RouteStep(
                    id = UUID.randomUUID().toString(),
                    time = tx.timestamp,
                    stepType = RouteStepType.TRANSACTION,
                    title = tx.merchantName,
                    description = desc,
                    locationName = null,
                    address = null,
                    latitude = null,
                    longitude = null,
                    amount = tx.amount,
                    category = tx.category.displayName
                )
            )
        }

        // 기존 routeSteps에 이미 존재하는 유효 TRANSACTION 스텝만 보존 (deduplicateRouteSteps에서 중복 자동 병합)
        val existingTxSteps = existingRouteSteps.filter {
            it.stepType == RouteStepType.TRANSACTION &&
                !isIncomeOrTransferStep(it) &&
                !isInvalidTransactionStep(it)
        }
        steps.addAll(existingTxSteps)

        // 3. Map Vehicle Logs (승차/하차 GPS 스텝이 이미 존재하면 보존·시간 보강하고, 누락된 주행 건은 vLog 기반으로 생성)
        val existingDrivingGpsSteps = existingRouteSteps.filter {
            it.stepType == RouteStepType.DRIVING &&
                !isIntermediateDrivingStep(it) &&
                (it.latitude != null && it.longitude != null || it.title.contains("승차") || it.title.contains("하차") || it.title.contains("출발") || it.title.contains("도착") || it.title.contains("주행"))
        }.map { normalizeDrivingStep(it) }

        val drivingVehicleLogs = vehicleLogs.filter { it.logType == VehicleLogType.TRIP_DRIVING }

        if (existingDrivingGpsSteps.isNotEmpty()) {
            val syncedExistingSteps = existingDrivingGpsSteps.map { step ->
                val isArrivalOrStandalone = isAlightingOnlyStep(step) || (!isBoardingOnlyStep(step) && !isStopoverWaypointStep(step))
                if (!isArrivalOrStandalone) {
                    step
                } else {
                    val matchedVLog = drivingVehicleLogs
                        .minByOrNull { kotlin.math.abs(java.time.Duration.between(it.timestamp, step.time).toMinutes()) }
                        ?.takeIf { kotlin.math.abs(java.time.Duration.between(it.timestamp, step.time).toMinutes()) <= 90L }
                    val curMin = extractDrivingDurationMinutesFromText(step.description)
                    val vLogMin = matchedVLog?.resolveEffectiveDrivingDurationMinutes()
                    if (vLogMin != null && vLogMin > 0 && (curMin == null || (curMin in 20..22 && vLogMin >= 45))) {
                        step.copy(description = upsertStepDurationDescription(step.description, vLogMin))
                    } else {
                        step
                    }
                }
            }
            steps.addAll(syncedExistingSteps)

            for (vLog in drivingVehicleLogs) {
                val hasMatchingStep = syncedExistingSteps.any { existing ->
                    kotlin.math.abs(java.time.Duration.between(existing.time, vLog.timestamp).toMinutes()) <= 90L
                }
                if (!hasMatchingStep) {
                    steps.addAll(buildRouteStepsFromVehicleLog(vLog))
                }
            }
        } else {
            for (vLog in drivingVehicleLogs) {
                steps.addAll(buildRouteStepsFromVehicleLog(vLog))
            }
        }

        // 4. 지능형 중복 제거 및 시간순 정렬 (중간 주행 경유지, 금융 입출금 및 누적/예정/총사용 오인입 스텝 제외)
        val validSteps = steps.filter {
            !isIncomeOrTransferStep(it) &&
            !isInvalidTransactionStep(it) &&
            !isIntermediateDrivingStep(it)
        }.map { normalizeStepForDisplay(it) }
        val uniqueSteps = deduplicateRouteSteps(validSteps)

        // Extract Distinct Place Names for Itinerary Chain (결제 정보 및 단순 경유 주행은 제외하고 승차/하차 및 실제 방문 장소만 추출)
        val invalidPlaceKeywords = listOf("촬영", "일반 사진", "사진 기록", "사진", "기록", "더미", "OCR", "일상 기록", "출발 인근", "도착 인근")
        val distinctPlaces = uniqueSteps
            .filter { step ->
                step.stepType != RouteStepType.TRANSACTION &&
                !isIntermediateDrivingStep(step) &&
                (step.stepType != RouteStepType.DRIVING || isBoardingOrAlightingStep(step))
            }
            .mapNotNull { step ->
                val raw = step.locationName
                    ?: step.title.takeIf { t -> !t.contains("주행") && !t.contains("승차") && !t.contains("하차") && !t.contains("촬영") }
                raw?.let { PlaceResolver.cleanDisplayPlaceName(it, step.address) }
            }
            .map { it.trim() }
            .filter { place ->
                place.isNotBlank() && invalidPlaceKeywords.none { place.contains(it) }
            }
            .distinct()

        val movementSummary = if (distinctPlaces.isNotEmpty()) {
            distinctPlaces.joinToString(" ➔ ")
        } else {
            "기록된 활동 없음"
        }

        // Generate Smart Title
        val repStep = uniqueSteps.firstOrNull { it.stepType != RouteStepType.TRANSACTION && it.latitude != null }
        val mainPlace = distinctPlaces.firstOrNull() ?: repStep?.locationName ?: "일상 기록"

        val title = when {
            distinctPlaces.size >= 2 -> "${distinctPlaces.first()} & ${distinctPlaces.last()}"
            distinctPlaces.size == 1 -> "${distinctPlaces.first()} 일정"
            cleanPhotos.isNotEmpty() -> if (mainPlace == "일상 기록") "오늘의 사진 기록" else "${mainPlace} 일정"
            else -> "${date.monthValue}월 ${date.dayOfMonth}일의 다이어리"
        }

        // Extract All Photos, Companions, Tags (시간순 정렬 사진 보장)
        val allPhotoUris = sortedPhotos.map { it.uri }.distinct()
        val stepsExpenseSum = calculateRouteStepsExpense(uniqueSteps)
        val totalExpense = maxOf(validExpenseTxs.sumOf { it.amount }, stepsExpenseSum)
        val vehicleLogDistance = vehicleLogs.sumOf { it.tripDistanceKm }
        val estimatedRouteDistance = LocationDistanceUtils.calculateRouteDrivingDistanceKm(uniqueSteps)
        val totalDistance = if (vehicleLogDistance > 0) vehicleLogDistance else estimatedRouteDistance

        // Generate Smart Summary Paragraph
        val summary = buildSummaryNarrative(date, uniqueSteps, distinctPlaces, cleanPhotos.size, totalExpense, totalDistance)

        val tags = mutableListOf<String>()
        val allCompanions = uniqueSteps.flatMap { it.companions }.distinct()
        allCompanions.forEach { tags.add("👤 $it") }

        if (totalExpense > 0) tags.add("지출기록")
        if (cleanPhotos.isNotEmpty()) tags.add("사진 ${cleanPhotos.size}장")
        val vehicleNames = vehicleLogs.mapNotNull { vLog ->
            val raw = Regex("\\[(.*?)\\]").find(vLog.note ?: "")?.groupValues?.get(1)
            if (raw != null) {
                val emoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(raw)
                val shortName = raw.split(" ").firstOrNull() ?: raw
                "$emoji $shortName"
            } else null
        }.distinct()
        vehicleNames.forEach { tags.add(it) }
        if (totalDistance > 0) tags.add("%.1fkm 주행".format(totalDistance))

        return DiaryEntry(
            date = date.atTime(uniqueSteps.firstOrNull()?.time?.toLocalTime() ?: java.time.LocalTime.of(12, 0)),
            title = title,
            summary = summary,
            placeName = distinctPlaces.firstOrNull(),
            address = repStep?.address,
            latitude = repStep?.latitude,
            longitude = repStep?.longitude,
            photoUris = allPhotoUris,
            totalExpense = totalExpense,
            drivingDistanceKm = totalDistance,
            hasGolfRound = false,
            tags = tags,
            routeSteps = uniqueSteps,
            movementSummary = movementSummary
        )
    }

    private fun buildRouteStepsFromVehicleLog(vLog: VehicleLog): List<RouteStep> {
        val result = mutableListOf<RouteStep>()
        val note = vLog.note ?: ""
        val matchedVehicle = Regex("\\[(?:[^:\\[\\]]*?:\\s*)?([^\\[\\]]+)\\]").find(note)?.groupValues?.get(1)?.trim()
        val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(matchedVehicle ?: note)
        val vehicleTag = (matchedVehicle ?: "차량").split(" ").firstOrNull() ?: "차량"
        val vehicleDisplayName = matchedVehicle ?: "차량"

        val effectiveDurationMin = vLog.resolveEffectiveDrivingDurationMinutes()
        val durationLabel = effectiveDurationMin?.let { formatDrivingDurationLabel(it) }?.takeIf { it.isNotBlank() }

        val routeMatch = Regex("\\[([^\\[\\]]*?(?:➔|->)[^\\[\\]]*?)\\]").find(note)?.groupValues?.get(1)
        if (routeMatch != null) {
            val delimiter = if (routeMatch.contains("➔")) "➔" else "->"
            val startPlace = simplifyRoutePlaceName(routeMatch.substringBefore(delimiter).trim())
            val endPlace = simplifyRoutePlaceName(routeMatch.substringAfterLast(delimiter).substringBefore("(").trim())
            val depOffsetMin = (effectiveDurationMin ?: 20).toLong().coerceAtLeast(1L)
            val depTime = vLog.timestamp.minusMinutes(depOffsetMin)

            if (startPlace.isNotBlank()) {
                result.add(
                    RouteStep(
                        id = UUID.randomUUID().toString(),
                        time = depTime,
                        stepType = RouteStepType.DRIVING,
                        title = "$brandEmoji [$vehicleDisplayName] 승차 · $startPlace",
                        description = if (endPlace.isNotBlank()) "승차: $startPlace ➔ $endPlace" else "승차 위치: $startPlace",
                        locationName = startPlace,
                        category = "차계부",
                        tags = listOf("차량주행", "$brandEmoji $vehicleTag", "승차", "출발지점")
                    )
                )
            }
            val alightingTitle = if (endPlace.isNotBlank()) {
                "$brandEmoji [$vehicleDisplayName] 하차 · $endPlace"
            } else {
                "$brandEmoji [$vehicleDisplayName] 하차"
            }
            result.add(
                RouteStep(
                    id = UUID.randomUUID().toString(),
                    time = vLog.timestamp,
                    stepType = RouteStepType.DRIVING,
                    title = alightingTitle,
                    description = if (startPlace.isNotBlank() && endPlace.isNotBlank()) {
                        if (durationLabel != null) {
                            "$startPlace ➔ $endPlace (${durationLabel} 운행 · %.1f km)".format(vLog.tripDistanceKm)
                        } else {
                            "$startPlace ➔ $endPlace (%.1f km)".format(vLog.tripDistanceKm)
                        }
                    } else if (durationLabel != null) {
                        "${durationLabel} 운행 · %.1f km".format(vLog.tripDistanceKm)
                    } else {
                        "주행 거리 %.1f km".format(vLog.tripDistanceKm)
                    },
                    locationName = endPlace.ifBlank { null },
                    category = "차계부",
                    tags = listOf("차량주행", "$brandEmoji $vehicleTag", "하차", "도착지점")
                )
            )
        } else {
            val fallbackTitle = "$brandEmoji [$vehicleDisplayName] 하차"
            val fallbackDesc = when {
                durationLabel != null && vLog.tripDistanceKm > 0.0 ->
                    "${durationLabel} 운행 · %.1f km".format(vLog.tripDistanceKm)
                durationLabel != null ->
                    "${durationLabel} 운행"
                note.isNotBlank() -> note
                else -> "주행 거리 %.1f km".format(vLog.tripDistanceKm)
            }
            result.add(
                RouteStep(
                    id = UUID.randomUUID().toString(),
                    time = vLog.timestamp,
                    stepType = RouteStepType.DRIVING,
                    title = fallbackTitle,
                    description = fallbackDesc,
                    category = "차계부",
                    tags = listOf("차량주행", "$brandEmoji $vehicleTag", "하차", "도착지점")
                )
            )
        }
        return result
    }

    private fun buildSummaryNarrative(
        date: LocalDate,
        steps: List<RouteStep>,
        places: List<String>,
        photoCount: Int,
        totalExpense: Long,
        drivingDistanceKm: Double = 0.0
    ): String = Companion.buildSummaryNarrative(date, steps, places, photoCount, totalExpense, drivingDistanceKm)

    companion object {
        fun extractDistanceKmFromText(text: String?): Double? {
            if (text.isNullOrBlank()) return null
            val match = Regex("(\\d+(?:\\.\\d+)?)\\s*km", RegexOption.IGNORE_CASE).find(text) ?: return null
            return match.groupValues[1].toDoubleOrNull()?.takeIf { it > 0.0 }
        }

        private fun isCommercialOutingLandmark(text: String): Boolean {
            return listOf("타임스퀘어", "스타필드", "백화점", "아울렛", "코엑스", "이마트", "롯데몰", "병원", "공항").any {
                text.contains(it)
            }
        }

        private fun isExplicitHomeEndpoint(text: String): Boolean {
            if (isCommercialOutingLandmark(text)) return false
            return text.contains("7101동") || text.contains("학암동") || text.contains("위례동") ||
                text.contains("우리집") ||
                Regex("(^|[^가-힣a-zA-Z0-9])집($|[^가-힣a-zA-Z0-9])").containsMatchIn(text)
        }

        private fun isExplicitOfficeEndpoint(text: String): Boolean {
            if (isCommercialOutingLandmark(text)) return false
            return text.contains("영등포동") || text.contains("여의도동") ||
                text.contains("회사") || text.contains("직장") || text.contains("사옥")
        }

        fun isCommuteDrivingStepPair(depStep: RouteStep?, arrStep: RouteStep): Boolean {
            if (arrStep.stepType != RouteStepType.DRIVING) return false
            if (!KoreanHolidayUtils.isCommuteEligibleWorkday(arrStep.time.toLocalDate())) return false
            val combinedArr = listOfNotNull(arrStep.title, arrStep.description, arrStep.locationName, arrStep.address).joinToString(" ")
            if (isCommercialOutingLandmark(combinedArr)) return false
            if (combinedArr.contains("출근") || combinedArr.contains("퇴근") ||
                combinedArr.contains("집 ➔ 회사") || combinedArr.contains("회사 ➔ 집") ||
                combinedArr.contains("집->회사") || combinedArr.contains("회사->집")
            ) {
                return true
            }
            if (depStep != null) {
                val combinedDep = listOfNotNull(depStep.title, depStep.description, depStep.locationName, depStep.address).joinToString(" ")
                if (isCommercialOutingLandmark(combinedDep)) return false
                val depHome = isExplicitHomeEndpoint(combinedDep)
                val depOffice = isExplicitOfficeEndpoint(combinedDep)
                val arrHome = isExplicitHomeEndpoint(combinedArr)
                val arrOffice = isExplicitOfficeEndpoint(combinedArr)
                if ((depHome && arrOffice) || (depOffice && arrHome)) return true
            }
            return false
        }

        fun upsertStepDurationDescription(rawDesc: String?, durationMinutes: Int): String {
            val durLabel = formatDrivingDurationLabel(durationMinutes)
            if (durLabel.isBlank()) return rawDesc.orEmpty()
            val durText = "$durLabel 운행"
            val durTokenRegex = Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행")
            if (rawDesc.isNullOrBlank()) return durText
            if (durTokenRegex.containsMatchIn(rawDesc)) {
                return rawDesc.replace(durTokenRegex, durText)
            }
            val trailingKmParen = Regex("\\((\\s*\\d+(?:\\.\\d+)?\\s*km\\s*)\\)\\s*$", RegexOption.IGNORE_CASE).find(rawDesc)
            if (trailingKmParen != null) {
                return rawDesc.replaceRange(trailingKmParen.range, "($durText · ${trailingKmParen.groupValues[1].trim()})")
            }
            return "$rawDesc · $durText"
        }

        fun calculateTotalDrivingDurationMinutes(
            steps: List<RouteStep>,
            drivingDistanceKm: Double = 0.0,
            referenceDate: LocalDate? = null
        ): Int {
            val enriched = enrichStepPairDurations(consolidateGpsDrivingSteps(steps))
            val summed = enriched
                .filter {
                    it.stepType == RouteStepType.DRIVING &&
                        !isIntermediateDrivingStep(it) &&
                        !isBoardingOnlyStep(it) &&
                        !isStopoverWaypointStep(it)
                }
                .sumOf { step ->
                    extractDrivingDurationMinutesFromText(step.description)
                        ?: extractDrivingDurationMinutesFromText(step.title)
                        ?: 0
                }
            if (summed > 0) return summed

            val hasAnyDriving = enriched.any { it.stepType == RouteStepType.DRIVING } || drivingDistanceKm > 0.0
            if (!hasAnyDriving) return 0
            val refTime = enriched.lastOrNull { it.stepType == RouteStepType.DRIVING }?.time
                ?: (referenceDate ?: LocalDate.now()).atTime(12, 0)
            return estimateRealisticNonCommuteMinutes(refTime, if (drivingDistanceKm > 0.0) drivingDistanceKm else 3.5)
        }

        /**
         * 기존 DB의 다이어리 요약문(summary)에서도 주행 활동이 있는 날에는
         * 운행시간 표기('XX분 운행' / 'X시간 Y분 운행')가 절대 빠지지 않도록 보정합니다.
         */
        fun ensureSummaryIncludesDrivingDuration(
            rawSummary: String,
            steps: List<RouteStep>,
            drivingDistanceKm: Double = 0.0,
            referenceDate: LocalDate? = null
        ): String {
            if (rawSummary.isBlank()) return rawSummary
            val cleanedInlineParens = rawSummary.replace(
                Regex("\\(\\s*(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*\\)(?=\\s*(?:➔|\\())"),
                ""
            ).replace(Regex("\\s{2,}"), " ").trim()

            val totalDriveMin = calculateTotalDrivingDurationMinutes(steps, drivingDistanceKm, referenceDate)
            if (totalDriveMin <= 0) return cleanedInlineParens
            val durLabel = formatDrivingDurationLabel(totalDriveMin)
            if (durLabel.isBlank()) return cleanedInlineParens
            val durToken = "$durLabel 운행"

            val existingDriveTokenRegex = Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행")
            val existingMin = extractDrivingDurationMinutesFromText(cleanedInlineParens)
            if (existingDriveTokenRegex.containsMatchIn(cleanedInlineParens)) {
                return if (existingMin != null && existingMin in 20..22 && totalDriveMin >= 45) {
                    cleanedInlineParens.replace(existingDriveTokenRegex, durToken)
                } else {
                    cleanedInlineParens
                }
            }

            val trailingStatsParen = Regex("\\(([^()]+)\\)\\s*$").find(cleanedInlineParens)
            return if (trailingStatsParen != null) {
                val inside = trailingStatsParen.groupValues[1].trim()
                cleanedInlineParens.replaceRange(trailingStatsParen.range, "($durToken, $inside)")
            } else {
                "$cleanedInlineParens ($durToken)"
            }
        }

        fun buildSummaryNarrative(
            date: LocalDate,
            steps: List<RouteStep>,
            places: List<String>,
            photoCount: Int,
            totalExpense: Long,
            drivingDistanceKm: Double = 0.0
        ): String {
            if (steps.isEmpty()) {
                return "${date.monthValue}월 ${date.dayOfMonth}일에 기록된 활동 내역이 없습니다."
            }

            val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)
            val parts = mutableListOf<String>()

            val firstStep = steps.first()
            parts.add("${firstStep.time.format(timeFmt)} ${stripTrailingRouteMetricsParens(firstStep.title)}")

            val middleSteps = steps.drop(1).dropLast(1)
            if (middleSteps.isNotEmpty()) {
                val keyMiddle = middleSteps.filter { it.amount != null || it.photoUris.isNotEmpty() }
                if (keyMiddle.isNotEmpty()) {
                    val midDesc = keyMiddle.joinToString(", ") {
                        "${it.time.format(timeFmt)} ${stripTrailingRouteMetricsParens(it.title)}"
                    }
                    parts.add(midDesc)
                }
            }

            if (steps.size > 1) {
                val lastStep = steps.last()
                parts.add("${lastStep.time.format(timeFmt)} ${stripTrailingRouteMetricsParens(lastStep.title)}")
            }

            val routeText = parts.distinct().joinToString(" ➔ ")
            val totalDriveMin = calculateTotalDrivingDurationMinutes(steps, drivingDistanceKm, date)

            val statText = buildString {
                if (totalDriveMin > 0) {
                    val durLabel = formatDrivingDurationLabel(totalDriveMin)
                    if (durLabel.isNotBlank()) append("$durLabel 운행")
                }
                if (places.isNotEmpty()) {
                    if (isNotEmpty()) append(", ")
                    append("방문 장소 ${places.size}곳")
                }
                if (totalExpense > 0) {
                    if (isNotEmpty()) append(", ")
                    append("총 지출 %,d원".format(totalExpense))
                }
                if (photoCount > 0) {
                    if (isNotEmpty()) append(", ")
                    append("사진 ${photoCount}장")
                }
            }

            return if (statText.isNotBlank()) {
                "$routeText ($statText)"
            } else {
                routeText
            }
        }

        /**
         * RouteStep 리스트 내 결제(TRANSACTION) 스텝들의 유효 지출 합계를 계산합니다.
         */
        fun calculateRouteStepsExpense(steps: List<RouteStep>): Long {
            return steps
                .filter {
                    it.stepType == RouteStepType.TRANSACTION &&
                        !isIncomeOrTransferStep(it) &&
                        !isInvalidTransactionStep(it)
                }
                .sumOf { step ->
                    val amt = step.amount ?: 0L
                    if (amt > 0L) {
                        amt
                    } else {
                        val desc = step.description ?: ""
                        val match = Regex("([0-9,]+)\\s*원").find(desc)
                        match?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: 0L
                    }
                }
        }

        /**
         * RouteStep이 누적이용금액, 결제예정금액, 총사용금액 또는 '금액' 등 비실사용 오인입 결제 스텝인지 판별합니다.
         */
        fun isInvalidTransactionStep(step: RouteStep): Boolean {
            if (step.stepType != RouteStepType.TRANSACTION) return false
            if (com.autologue.app.data.parser.SmsParser.isInvalidMerchantName(step.title)) return true
            val desc = step.description.orEmpty()
            if (desc.contains("((결제)") ||
                desc.contains("([결제]") ||
                desc.contains("누적이용금액") ||
                desc.contains("결제예정금액") ||
                desc.contains("총사용금액") ||
                desc.contains("총이용금액")
            ) {
                return true
            }
            return false
        }

        /**
         * 입금, 출금, 이체, 송금 등 비이동성 금융 거래 여부 판별.
         */
        fun isFinancialIncomeOrTransfer(tx: Transaction): Boolean {
            if (tx.category == ExpenseCategory.INCOME || tx.category == ExpenseCategory.TRANSFER) {
                return true
            }
            val text = "${tx.merchantName} ${tx.transferMemo ?: ""} ${tx.originalText}".lowercase()
            val financialKeywords = listOf(
                "입금", "출금", "이체", "송금", "급여", "환불", "취소",
                "타행이체", "당행이체", "계좌이체", "자동이체", "통지수수료", "이체수수료", "체크출금"
            )
            return financialKeywords.any { text.contains(it) }
        }

        /**
         * RouteStep이 입출금/금융성 스텝인지 판별.
         */
        fun isIncomeOrTransferStep(step: RouteStep): Boolean {
            if (step.stepType == RouteStepType.TRANSACTION) {
                val cat = step.category ?: ""
                if (cat.contains("수입") || cat.contains("입금") || cat.contains("이체") || cat.contains("출금")) {
                    return true
                }
                val text = "${step.title} ${step.description ?: ""}".lowercase()
                val financialKeywords = listOf("입금", "출금", "이체", "송금", "급여", "타행이체", "당행이체", "계좌이체", "자동이체")
                if (financialKeywords.any { text.contains(it) }) {
                    return true
                }
            }
            return false
        }

        /**
         * 차량 이동 중 중간 경유(10분/30m 주기 GPS 샘플링) 스텝인지 판별합니다.
         */
        fun isIntermediateDrivingStep(step: RouteStep): Boolean {
            if (step.stepType != RouteStepType.DRIVING) return false
            if ("주행경유" in step.tags || "주행거점" in step.tags) return true
            if (step.title.contains("주행 경유") || step.title.contains("주행경유") || step.title.contains("단일 거점")) return true
            val isLegacyPeriodicGps = step.description?.contains("GPS 백그라운드 수신") == true &&
                !step.title.contains("출발") && !step.title.contains("도착") &&
                !step.title.contains("승차") && !step.title.contains("하차")
            return isLegacyPeriodicGps
        }

        /**
         * 하차한 동일 위치에서 1시간 이내에 재출발하여 하나로 통합된 '경유지' 스텝인지 판별합니다.
         */
        fun isStopoverWaypointStep(step: RouteStep): Boolean {
            if (step.stepType != RouteStepType.DRIVING) return false
            if (isIntermediateDrivingStep(step)) return false
            return "경유지" in step.tags ||
                step.title.contains("경유지 ·") ||
                step.title.startsWith("경유지")
        }

        /**
         * 차량 스텝이 순수 출발(승차) 스텝인지 판별합니다.
         */
        fun isBoardingOnlyStep(step: RouteStep): Boolean {
            if (step.stepType != RouteStepType.DRIVING) return false
            if (isIntermediateDrivingStep(step) || isStopoverWaypointStep(step)) return false
            return step.title.contains("승차") || step.title.contains("출발") ||
                "승차" in step.tags || "출발지점" in step.tags
        }

        /**
         * 차량 스텝이 순수 도착(하차) 스텝인지 판별합니다.
         */
        fun isAlightingOnlyStep(step: RouteStep): Boolean {
            if (step.stepType != RouteStepType.DRIVING) return false
            if (isIntermediateDrivingStep(step) || isStopoverWaypointStep(step) || isBoardingOnlyStep(step)) return false
            return step.title.contains("하차") || step.title.contains("도착") ||
                "하차" in step.tags || "도착지점" in step.tags
        }

        /**
         * 차량 스텝이 승차(출발), 하차(도착), 또는 1시간 이내 동일 위치 재출발로 확정된 경유지인지 판별합니다.
         */
        fun isBoardingOrAlightingStep(step: RouteStep): Boolean {
            if (step.stepType != RouteStepType.DRIVING) return false
            if (isIntermediateDrivingStep(step)) return false
            return isBoardingOnlyStep(step) || isAlightingOnlyStep(step) || isStopoverWaypointStep(step)
        }

        /**
         * RouteStep에서 차량 태그·승하차/경유지 접두어·거리/시간 괄호를 제거하고
         * 최근접 대표 명칭(건물/역/정류장/상호/아파트 세부 명칭) 또는 순수 동 이름만 추출합니다.
         */
        fun resolveStepDisplayPlaceName(step: RouteStep): String {
            val rawCandidate = when {
                !step.locationName.isNullOrBlank() -> step.locationName
                step.stepType == RouteStepType.DRIVING && step.title.contains(" · ") ->
                    step.title.substringAfter(" · ")
                else -> step.title
            }
            val stripped = stripTrailingRouteMetricsParens(
                rawCandidate
                    .replace(Regex("\\[[^\\[\\]]*\\]"), "")
                    .replace("승차 (출발)", "")
                    .replace("하차 (도착)", "")
                    .replace(Regex("^(?:[⭐🛡️🔵🔗🪽🚗📍]\\s*)+"), "")
                    .replace(Regex("^(?:승차|하차|출발|도착|경유지|경유)\\s*·?\\s*"), "")
                    .replace(Regex("\\s*(?:승차|하차|출발|도착|경유지)$"), "")
                    .trim()
            )
            val cleaned = PlaceResolver.cleanDisplayPlaceName(stripped, step.address)
            return cleaned.ifBlank { stripped.ifBlank { step.title } }
        }

        /**
         * PHOTO 및 DRIVING 스텝을 포함한 모든 RouteStep의 항목명(title), 위치명(locationName), 주소(address)를
         * 최신 표기 원칙(7101동 -> 집, 호수 미표기, 병기 정류장 앞쪽 명칭, 제목 시간 제거 및 하단 설명란 소요시간 표기)으로 정규화합니다.
         */
        fun normalizeStepForDisplay(step: RouteStep): RouteStep {
            val cleanAddr = PlaceResolver.sanitizeAddressForDisplay(step.address)
            return when (step.stepType) {
                RouteStepType.DRIVING -> normalizeDrivingStep(step)
                RouteStepType.PHOTO -> {
                    val cleanLoc = step.locationName
                        ?.takeIf { it.isNotBlank() && it != "사진 기록" }
                        ?.let { PlaceResolver.cleanDisplayPlaceName(it, cleanAddr ?: step.address) }
                        ?: cleanAddr?.takeIf { it.isNotBlank() }
                            ?.let { PlaceResolver.cleanDisplayPlaceName("", it) }
                            ?.takeIf { it.isNotBlank() }
                        ?: step.locationName
                    val shouldUpdateTitle = step.title.isBlank() ||
                        step.title == step.locationName ||
                        PlaceResolver.isAdminOrPlaceholderPlaceName(step.title)
                    val newTitle = if (shouldUpdateTitle && !cleanLoc.isNullOrBlank()) {
                        cleanLoc
                    } else {
                        PlaceResolver.stripBuildingUnitNumbers(step.title).ifBlank { step.title }
                    }
                    if (newTitle != step.title || cleanLoc != step.locationName || cleanAddr != step.address) {
                        step.copy(title = newTitle, locationName = cleanLoc, address = cleanAddr)
                    } else {
                        step
                    }
                }
                else -> {
                    if (cleanAddr != step.address) step.copy(address = cleanAddr) else step
                }
            }
        }

        /**
         * 기존 DB에 저장되어 있던 차량 주행 스텝에서 제목 내 중복 거리·소요시간 괄호를 모두 제거하고,
         * 최근접 위치명(폴백: 순수 동 이름)과 하단 설명란 운행 소요시간 표기로 정규화합니다.
         */
        fun normalizeDrivingStep(step: RouteStep): RouteStep {
            if (step.stepType != RouteStepType.DRIVING) return step

            val cleanAddr = PlaceResolver.sanitizeAddressForDisplay(step.address)
            val isStopover = isStopoverWaypointStep(step)
            val isBoarding = !isStopover && (
                step.title.contains("승차") || step.title.contains("출발") ||
                    "승차" in step.tags || "출발지점" in step.tags
                )
            val isAlighting = !isStopover && !isBoarding && (
                step.title.contains("하차") || step.title.contains("도착") ||
                    "하차" in step.tags || "도착지점" in step.tags
                )

            // 스텝의 위치명 정제 (locationName -> title 내 '·' 뒤 -> description 순서로 추출 후 정제)
            val rawLocFromDesc = step.description?.let { desc ->
                when {
                    isBoarding && desc.contains("➔") -> desc.substringAfter("승차:").substringBefore("➔").trim()
                    isBoarding && desc.startsWith("승차 위치:") -> desc.substringAfter("승차 위치:").trim()
                    isAlighting && desc.contains("➔") -> desc.substringAfterLast("➔").substringBefore("(").trim()
                    isAlighting && desc.startsWith("하차 위치:") -> desc.substringAfter("하차 위치:").substringBefore("(").trim()
                    else -> null
                }
            }
            val rawLocCandidate = step.locationName?.takeIf { it.isNotBlank() }
                ?: step.title.takeIf { it.contains(" · ") }?.substringAfter(" · ")?.let { stripTrailingRouteMetricsParens(it) }
                ?: rawLocFromDesc
            val cleanLoc = rawLocCandidate
                ?.replace(Regex("\\[[^\\[\\]]*\\]"), "")
                ?.replace("승차 (출발)", "")
                ?.replace("하차 (도착)", "")
                ?.replace(Regex("^(?:승차|하차|출발|도착|경유지|경유)\\s*·?\\s*"), "")
                ?.replace(Regex("\\s*(?:승차|하차|출발|도착|경유지)$"), "")
                ?.trim()
                ?.takeIf { it.isNotBlank() && it != "출발 인근" && it != "도착 인근" }
                ?.let { PlaceResolver.cleanDisplayPlaceName(it, cleanAddr ?: step.address) }
                ?.takeIf { it.isNotBlank() }

            var newDesc = step.description
            if (newDesc != null && newDesc.contains("GPS 백그라운드 수신")) {
                val place = cleanLoc ?: cleanAddr.orEmpty()
                newDesc = when {
                    isBoarding -> if (place.isNotBlank()) "승차 위치: $place" else "차량 승차"
                    isAlighting -> if (place.isNotBlank()) "하차 위치: $place" else "차량 하차"
                    isStopover -> if (place.isNotBlank()) "경유 위치: $place" else "경유지"
                    else -> place.ifBlank { null }
                }
            }
            val descSnapshot = newDesc
            if (descSnapshot != null) {
                val strippedDesc = PlaceResolver.stripBuildingUnitNumbers(descSnapshot).ifBlank { descSnapshot }
                newDesc = strippedDesc.replace(Regex("(\\d+(?:\\.\\d+)?)\\s*km", RegexOption.IGNORE_CASE)) { m ->
                    val km = m.groupValues[1].toDoubleOrNull() ?: 0.0
                    if (km > 65.0) "27.1 km" else m.value
                }
            }

            val durMin = extractDrivingDurationMinutesFromText(newDesc)
                ?: extractDrivingDurationMinutesFromText(step.title)
            val durLabel = durMin?.let { formatDrivingDurationLabel(it) }?.takeIf { it.isNotBlank() }

            if (!isBoarding && !isStopover && durLabel != null) {
                val durTokenRegex = Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행")
                val currentDesc = newDesc
                newDesc = when {
                    currentDesc.isNullOrBlank() -> "${durLabel} 운행"
                    durTokenRegex.containsMatchIn(currentDesc) -> currentDesc.replace(durTokenRegex, "${durLabel} 운행")
                    extractDrivingDurationMinutesFromText(currentDesc) == null -> "$currentDesc · ${durLabel} 운행"
                    else -> currentDesc
                }
            }

            val closeBracketIdx = step.title.indexOf(']')
            val newTitle = if (closeBracketIdx >= 0) {
                val vehiclePrefix = step.title.substring(0, closeBracketIdx + 1).trim()
                when {
                    isStopover -> if (cleanLoc != null) "$vehiclePrefix 경유지 · $cleanLoc" else "$vehiclePrefix 경유지"
                    isBoarding -> if (cleanLoc != null) "$vehiclePrefix 승차 · $cleanLoc" else "$vehiclePrefix 승차"
                    isAlighting -> if (cleanLoc != null) "$vehiclePrefix 하차 · $cleanLoc" else "$vehiclePrefix 하차"
                    else -> stripTrailingRouteMetricsParens(step.title)
                }
            } else {
                if (isStopover) {
                    if (cleanLoc != null) "경유지 · $cleanLoc" else stripTrailingRouteMetricsParens(step.title)
                } else {
                    stripTrailingRouteMetricsParens(
                        step.title
                            .replace("승차 (출발)", "승차")
                            .replace("하차 (도착)", "하차")
                    )
                }
            }

            val finalLoc = cleanLoc ?: step.locationName?.let { PlaceResolver.cleanDisplayPlaceName(it, cleanAddr).ifBlank { null } }
            return if (newTitle != step.title || newDesc != step.description || finalLoc != step.locationName || cleanAddr != step.address) {
                step.copy(title = newTitle, description = newDesc, locationName = finalLoc, address = cleanAddr)
            } else {
                step
            }
        }

        /**
         * 승차 ➔ 하차 쌍 및 단독 하차/단독 주행 스텝에서 하단 설명란에 운행시간 표기가 빠지지 않도록 보강합니다:
         * - 자정 경유 주행(23:50 -> 00:15) 및 1분 미만 단거리 주행도 누락 없이 최소 1분 이상으로 산출
         * - 구형 21분 워치독으로 절단된 평일 출퇴근 스텝(20~22분)은 현실적 출퇴근 소요시간으로 복원
         * - 선행 승차 스텝이 없는 단독 하차·단독 주행 스텝도 거리·시간대 기반 운행시간을 설명란에 보장
         */
        fun enrichStepPairDurations(sortedSteps: List<RouteStep>): List<RouteStep> {
            if (sortedSteps.isEmpty()) return sortedSteps
            var pendingBoarding: RouteStep? = null
            return sortedSteps.map { step ->
                if (step.stepType != RouteStepType.DRIVING || isIntermediateDrivingStep(step)) {
                    step
                } else {
                    val isBoarding = isBoardingOnlyStep(step)
                    val isStopover = isStopoverWaypointStep(step)
                    val isAlighting = isAlightingOnlyStep(step)
                    when {
                        isBoarding || isStopover -> {
                            pendingBoarding = step
                            step
                        }
                        isAlighting || (!isBoarding && !isStopover) -> {
                            val dep = if (isAlighting) pendingBoarding else null
                            if (isAlighting) {
                                pendingBoarding = null
                            }
                            val cleanTitle = stripTrailingRouteMetricsParens(step.title)
                            val descMin = extractDrivingDurationMinutesFromText(step.description)
                            val titleMin = extractDrivingDurationMinutesFromText(step.title)
                            val pairedMin = dep
                                ?.takeIf { !it.time.isAfter(step.time) }
                                ?.let { java.time.Duration.between(it.time, step.time).toMinutes().toInt() }
                                ?.takeIf { it in 0..360 }
                                ?.coerceAtLeast(1)

                            val rawCandidate = descMin ?: titleMin ?: pairedMin
                            val isCommutePair = isCommuteDrivingStepPair(dep, step)
                            val isTruncatedCommute = isCommutePair && rawCandidate != null && rawCandidate in 20..22

                            val resolvedMin: Int = when {
                                isTruncatedCommute -> estimateRealisticCommuteMinutes(
                                    step.time,
                                    extractDistanceKmFromText(step.description) ?: 27.1
                                )
                                rawCandidate != null && rawCandidate > 0 -> rawCandidate
                                isCommutePair -> estimateRealisticCommuteMinutes(
                                    step.time,
                                    extractDistanceKmFromText(step.description) ?: 27.1
                                )
                                else -> estimateRealisticNonCommuteMinutes(
                                    step.time,
                                    extractDistanceKmFromText(step.description) ?: 3.5
                                )
                            }

                            val shouldUpdateDesc = descMin == null || isTruncatedCommute
                            val newDesc = if (shouldUpdateDesc && resolvedMin > 0) {
                                upsertStepDurationDescription(step.description, resolvedMin)
                            } else {
                                step.description
                            }
                            if (cleanTitle != step.title || newDesc != step.description) {
                                step.copy(title = cleanTitle, description = newDesc)
                            } else {
                                step
                            }
                        }
                        else -> step
                    }
                }
            }
        }

        /**
         * 하차 스텝과 뒤이은 재출발(승차) 스텝이 동일한 위치인지 판별합니다.
         * - 유효 GPS 좌표가 모두 존재할 때 직선거리 450m 이내이면 동일 위치로 인정하고,
         *   750m를 초과하면 별개 위치로 판정합니다.
         * - 좌표가 없거나 근접 경계 구간(450~750m)인 경우 정제된 대표 장소명이 일치하면 동일 위치로 인정합니다.
         */
        fun isSameStopLocation(alighting: RouteStep, nextBoarding: RouteStep): Boolean {
            val latA = alighting.latitude
            val lngA = alighting.longitude
            val latB = nextBoarding.latitude
            val lngB = nextBoarding.longitude
            val hasValidCoords = latA != null && lngA != null && latB != null && lngB != null &&
                LocationDistanceUtils.isValidCoordinate(latA, lngA) &&
                LocationDistanceUtils.isValidCoordinate(latB, lngB)

            val placeA = resolveStepDisplayPlaceName(alighting).trim()
            val placeB = resolveStepDisplayPlaceName(nextBoarding).trim()
            val invalidGeneric = setOf("", "차량 주행", "차량 승차", "차량 하차", "승차", "하차", "출발", "도착", "경유지")
            val hasSamePlaceName = placeA !in invalidGeneric && placeB !in invalidGeneric && placeA == placeB

            if (hasValidCoords) {
                val distKm = LocationDistanceUtils.calculateStraightDistanceKm(latA!!, lngA!!, latB!!, lngB!!)
                if (distKm <= 0.45) return true
                if (distKm > 0.75) return false
                return hasSamePlaceName
            }
            return hasSamePlaceName
        }

        /**
         * GPS 주행 기록을 사용자 원칙에 따라 정제합니다 (100% 멱등 보장):
         * 1) 중간 주행 GPS 샘플 및 동일 운행 중 중복 승차를 제거하여 기본적으로 [최초 출발지]와 [최종 도착지]만 남깁니다.
         * 2) 실제로 하차한 동일 위치에서 1시간(3,600초) 이내에 다시 운행(승차)이 시작된 경우에만
         *    하차와 재출발 두 항목을 단 1개의 [경유지] 항목으로 병합합니다.
         * 3) 1시간을 초과해 체류한 뒤 재출발하거나 다른 위치에서 출발한 경우에는 병합하지 않고
         *    각 운행의 출발지·도착지로 분리 표기합니다.
         */
        fun consolidateGpsDrivingSteps(steps: List<RouteStep>): List<RouteStep> {
            if (steps.isEmpty()) return emptyList()

            val sorted = steps
                .filterNot { isIntermediateDrivingStep(it) }
                .sortedBy { it.time }

            val otherSteps = sorted.filter {
                it.stepType != RouteStepType.DRIVING ||
                    (!isBoardingOnlyStep(it) && !isAlightingOnlyStep(it) && !isStopoverWaypointStep(it))
            }
            val drivingSteps = sorted.filter {
                it.stepType == RouteStepType.DRIVING &&
                    (isBoardingOnlyStep(it) || isAlightingOnlyStep(it) || isStopoverWaypointStep(it))
            }
            if (drivingSteps.isEmpty()) return otherSteps

            val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)
            val consolidatedDriving = mutableListOf<RouteStep>()
            var activeTripOpen = false
            var legStartStep: RouteStep? = null
            var accumulatedDriveMin = 0
            var newlyMergedStopoverInTrip = false

            var i = 0
            while (i < drivingSteps.size) {
                val cur = drivingSteps[i]
                when {
                    isStopoverWaypointStep(cur) -> {
                        // 이미 이전 패스에서 통합 완료된 경유지 스텝은 그대로 보존 (멱등성 보장)
                        consolidatedDriving.add(cur)
                        activeTripOpen = true
                        legStartStep = cur
                        i += 1
                    }

                    isBoardingOnlyStep(cur) -> {
                        val prevStart = legStartStep
                        val isNewSessionAfterUnclosed = activeTripOpen && prevStart != null && (
                            prevStart.time.toLocalDate() != cur.time.toLocalDate() ||
                                java.time.Duration.between(prevStart.time, cur.time).abs().toMinutes() > 240L
                            )
                        if (!activeTripOpen || isNewSessionAfterUnclosed) {
                            consolidatedDriving.add(cur)
                            activeTripOpen = true
                            legStartStep = cur
                            accumulatedDriveMin = 0
                            newlyMergedStopoverInTrip = false
                        }
                        // 이미 활성 주행 중일 때 하차 없이 찍힌 중간 중복 승차 GPS 샘플은 제거 (최초 출발지만 유지)
                        i += 1
                    }

                    isAlightingOnlyStep(cur) -> {
                        // 승차 없이 연속으로 중복 수신된 인접 하차 스텝이 있으면 마지막 하차 시각을 대표로 통합
                        var effectiveAlighting = cur
                        while (i + 1 < drivingSteps.size && isAlightingOnlyStep(drivingSteps[i + 1])) {
                            val dupNext = drivingSteps[i + 1]
                            val gapMin = java.time.Duration.between(effectiveAlighting.time, dupNext.time).abs().toMinutes()
                            if (gapMin <= 15L && isSameStopLocation(effectiveAlighting, dupNext)) {
                                effectiveAlighting = mergeSteps(effectiveAlighting, dupNext).copy(time = dupNext.time)
                                i += 1
                            } else {
                                break
                            }
                        }

                        val explicitLegMin = extractDrivingDurationMinutesFromText(effectiveAlighting.description)
                            ?: extractDrivingDurationMinutesFromText(effectiveAlighting.title)
                        val calcLegMin = legStartStep
                            ?.takeIf { !it.time.isAfter(effectiveAlighting.time) }
                            ?.let {
                                java.time.Duration.between(it.time, effectiveAlighting.time).toMinutes().toInt()
                                    .takeIf { m -> m in 0..360 }
                                    ?.coerceAtLeast(1)
                            }
                        val rawLegMin = explicitLegMin ?: calcLegMin
                        val isTruncatedCommuteLeg = rawLegMin != null && rawLegMin in 20..22 &&
                            isCommuteDrivingStepPair(legStartStep, effectiveAlighting)
                        val legMin = if (isTruncatedCommuteLeg) {
                            estimateRealisticCommuteMinutes(
                                effectiveAlighting.time,
                                extractDistanceKmFromText(effectiveAlighting.description) ?: 27.1
                            )
                        } else {
                            rawLegMin ?: 0
                        }

                        val nextStep = drivingSteps.getOrNull(i + 1)
                        val gapSeconds = if (nextStep != null && isBoardingOnlyStep(nextStep)) {
                            java.time.Duration.between(effectiveAlighting.time, nextStep.time).seconds
                        } else {
                            Long.MAX_VALUE
                        }

                        if (nextStep != null &&
                            isBoardingOnlyStep(nextStep) &&
                            gapSeconds in 0L..3600L &&
                            isSameStopLocation(effectiveAlighting, nextStep)
                        ) {
                            // 실제 하차한 동일 위치에서 1시간(3,600초) 이내에 재출발한 경우: 단일 '경유지'로 통합
                            val stopPlace = resolveStepDisplayPlaceName(effectiveAlighting)
                                .takeIf { it.isNotBlank() }
                                ?: resolveStepDisplayPlaceName(nextStep)
                            val closeBracketIdx = effectiveAlighting.title.indexOf(']')
                                .takeIf { it >= 0 }
                                ?: nextStep.title.indexOf(']').takeIf { it >= 0 }
                            val vehiclePrefix = when {
                                effectiveAlighting.title.indexOf(']') >= 0 ->
                                    effectiveAlighting.title.substring(0, effectiveAlighting.title.indexOf(']') + 1).trim()
                                closeBracketIdx != null ->
                                    nextStep.title.substring(0, closeBracketIdx + 1).trim()
                                else -> ""
                            }
                            val stopTitle = when {
                                vehiclePrefix.isNotBlank() && stopPlace.isNotBlank() -> "$vehiclePrefix 경유지 · $stopPlace"
                                vehiclePrefix.isNotBlank() -> "$vehiclePrefix 경유지"
                                stopPlace.isNotBlank() -> "경유지 · $stopPlace"
                                else -> "경유지"
                            }
                            val stayMin = (gapSeconds / 60L).toInt()
                            val stopDesc = if (stayMin >= 1) {
                                "경유 체류 ${stayMin}분 (${effectiveAlighting.time.format(timeFmt)} 하차 · ${nextStep.time.format(timeFmt)} 재출발)"
                            } else {
                                "경유 정차 후 재출발 (${effectiveAlighting.time.format(timeFmt)})"
                            }
                            val stopTags = (effectiveAlighting.tags + nextStep.tags)
                                .filterNot { it in setOf("승차", "하차", "출발지점", "도착지점") }
                                .plus("경유지")
                                .distinct()

                            val mergedStopover = effectiveAlighting.copy(
                                title = stopTitle,
                                description = stopDesc,
                                locationName = stopPlace.ifBlank { effectiveAlighting.locationName ?: nextStep.locationName },
                                address = PlaceResolver.sanitizeAddressForDisplay(effectiveAlighting.address ?: nextStep.address),
                                latitude = effectiveAlighting.latitude ?: nextStep.latitude,
                                longitude = effectiveAlighting.longitude ?: nextStep.longitude,
                                photoUris = (effectiveAlighting.photoUris + nextStep.photoUris).distinct(),
                                companions = (effectiveAlighting.companions + nextStep.companions).distinct(),
                                tags = stopTags
                            )

                            consolidatedDriving.add(mergedStopover)
                            accumulatedDriveMin += legMin
                            newlyMergedStopoverInTrip = true
                            activeTripOpen = true
                            legStartStep = nextStep
                            i += 2
                        } else {
                            // 최종 도착지로 확정 (제목에서는 소요시간을 제외하고 설명란에 총 운행시간 반영)
                            val totalDriveMin = accumulatedDriveMin + legMin
                            val cleanArrivalTitle = stripTrailingRouteMetricsParens(effectiveAlighting.title)
                            val durTokenRegex = Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행")
                            val finalArrival = if ((newlyMergedStopoverInTrip || isTruncatedCommuteLeg) && totalDriveMin > 0) {
                                val durLabel = formatDrivingDurationLabel(totalDriveMin)
                                val durText = if (durLabel.isNotBlank()) "${durLabel} 운행" else ""
                                val rawDesc = effectiveAlighting.description
                                val updatedDesc = when {
                                    durText.isBlank() -> rawDesc
                                    rawDesc.isNullOrBlank() -> durText
                                    durTokenRegex.containsMatchIn(rawDesc) -> rawDesc.replace(durTokenRegex, durText)
                                    else -> "$rawDesc · $durText"
                                }
                                effectiveAlighting.copy(title = cleanArrivalTitle, description = updatedDesc)
                            } else if (totalDriveMin > 0 &&
                                extractDrivingDurationMinutesFromText(effectiveAlighting.description) == null
                            ) {
                                val durLabel = formatDrivingDurationLabel(totalDriveMin)
                                val durText = if (durLabel.isNotBlank()) "${durLabel} 운행" else ""
                                val updatedDesc = when {
                                    durText.isBlank() -> effectiveAlighting.description
                                    effectiveAlighting.description.isNullOrBlank() -> durText
                                    else -> "${effectiveAlighting.description} · $durText"
                                }
                                effectiveAlighting.copy(title = cleanArrivalTitle, description = updatedDesc)
                            } else if (cleanArrivalTitle != effectiveAlighting.title) {
                                effectiveAlighting.copy(title = cleanArrivalTitle)
                            } else {
                                effectiveAlighting
                            }

                            consolidatedDriving.add(finalArrival)
                            activeTripOpen = false
                            legStartStep = null
                            accumulatedDriveMin = 0
                            newlyMergedStopoverInTrip = false
                            i += 1
                        }
                    }

                    else -> {
                        i += 1
                    }
                }
            }

            return (otherSteps + consolidatedDriving).sortedBy { it.time }
        }

        /**
         * RouteStep 리스트의 중복을 지능적으로 통합 및 제거하여 단일 정제 리스트로 반환합니다.
         */
        fun deduplicateRouteSteps(steps: List<RouteStep>): List<RouteStep> {
            if (steps.isEmpty()) return emptyList()

            val result = mutableListOf<RouteStep>()
            for (rawStep in steps) {
                if (isIntermediateDrivingStep(rawStep) || isInvalidTransactionStep(rawStep)) continue
                val step = normalizeStepForDisplay(rawStep)
                val matchIndex = result.indexOfFirst { existing -> isDuplicateStep(existing, step) }
                if (matchIndex >= 0) {
                    result[matchIndex] = mergeSteps(result[matchIndex], step)
                } else {
                    result.add(step)
                }
            }
            val consolidated = consolidateGpsDrivingSteps(result.sortedBy { it.time })
            return enrichStepPairDurations(consolidated)
        }

        private fun isDuplicateStep(a: RouteStep, b: RouteStep): Boolean {
            if (a.stepType != b.stepType) return false
            if (a.id.isNotBlank() && a.id == b.id) return true

            when (a.stepType) {
                RouteStepType.PHOTO -> {
                    if (a.photoUris.isNotEmpty() && b.photoUris.isNotEmpty()) {
                        val hasCommonPhoto = a.photoUris.any { it in b.photoUris }
                        if (hasCommonPhoto) return true
                    }
                    val sameDay = a.time.toLocalDate() == b.time.toLocalDate()
                    if (!sameDay) return false

                    val minDiff = java.time.Duration.between(a.time, b.time).abs().toMinutes()
                    val cleanTitleA = a.title.replace("사진 촬영", "").trim()
                    val cleanTitleB = b.title.replace("사진 촬영", "").trim()
                    val sameTitleOrPlace = (cleanTitleA.isNotBlank() && cleanTitleA == cleanTitleB) ||
                        (!a.locationName.isNullOrBlank() && a.locationName == b.locationName)
                    if (sameTitleOrPlace && minDiff <= 30) return true

                    if (a.latitude != null && a.longitude != null && b.latitude != null && b.longitude != null) {
                        val latDiff = kotlin.math.abs(a.latitude - b.latitude)
                        val lngDiff = kotlin.math.abs(a.longitude - b.longitude)
                        if (latDiff < 0.002 && lngDiff < 0.002 && minDiff <= 30) return true
                    }
                    return false
                }
                RouteStepType.TRANSACTION -> {
                    val sameTime = a.time.toLocalDate() == b.time.toLocalDate() &&
                        a.time.hour == b.time.hour && a.time.minute == b.time.minute
                    return sameTime && a.title == b.title && a.amount == b.amount
                }
                RouteStepType.DRIVING -> {
                    val minDiff = java.time.Duration.between(a.time, b.time).abs().toMinutes()
                    if (a.title == b.title && minDiff <= 15) return true
                    if (a.latitude != null && a.longitude != null && b.latitude != null && b.longitude != null) {
                        val latDiff = kotlin.math.abs(a.latitude - b.latitude)
                        val lngDiff = kotlin.math.abs(a.longitude - b.longitude)
                        val secDiff = java.time.Duration.between(a.time, b.time).abs().seconds
                        return latDiff < 0.0005 && lngDiff < 0.0005 && secDiff <= 300
                    }
                    return a.time.toLocalDate() == b.time.toLocalDate() && a.title == b.title && minDiff <= 15
                }
                RouteStepType.MEMO -> {
                    return a.time == b.time && a.title == b.title
                }
            }
        }

        private fun mergeSteps(existing: RouteStep, incoming: RouteStep): RouteStep {
            val mergedPhotos = (existing.photoUris + incoming.photoUris).distinct()
            val mergedCompanions = (existing.companions + incoming.companions).distinct()
            val mergedTags = (existing.tags + incoming.tags).distinct()
            val earliestTime = if (existing.time.isBefore(incoming.time)) existing.time else incoming.time

            val updatedDescription = when (existing.stepType) {
                RouteStepType.PHOTO -> {
                    buildString {
                        append("사진 ${mergedPhotos.size}장 촬영")
                        if (mergedCompanions.isNotEmpty()) {
                            append(" · 동행: ${mergedCompanions.joinToString(", ")}")
                        }
                    }
                }
                else -> existing.description ?: incoming.description
            }

            return existing.copy(
                time = earliestTime,
                title = if (existing.title.isNotBlank()) existing.title else incoming.title,
                description = updatedDescription,
                locationName = existing.locationName ?: incoming.locationName,
                address = existing.address ?: incoming.address,
                latitude = existing.latitude ?: incoming.latitude,
                longitude = existing.longitude ?: incoming.longitude,
                amount = existing.amount ?: incoming.amount,
                photoUris = mergedPhotos,
                companions = mergedCompanions,
                tags = mergedTags
            )
        }
    }
}
