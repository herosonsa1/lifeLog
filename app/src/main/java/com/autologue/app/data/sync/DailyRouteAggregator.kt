package com.autologue.app.data.sync

import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
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
        golfRounds: List<GolfRound> = emptyList(),
        vehicleLogs: List<VehicleLog> = emptyList()
    ): DiaryEntry {
        val steps = mutableListOf<RouteStep>()

        // 1. Cluster photos by time (~45 min) and place
        val sortedPhotos = photos.sortedBy { it.time }
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
            val repPlace = cluster.firstNotNullOfOrNull { it.placeName?.takeIf { p -> !p.contains("사진 촬영") && !p.contains("촬영 장소") } }
                ?: "서울 방이동"
            val repAddress = cluster.firstNotNullOfOrNull { it.address } ?: "서울특별시 송파구 방이동"
            val repLat = cluster.firstNotNullOfOrNull { it.latitude } ?: 37.5145
            val repLng = cluster.firstNotNullOfOrNull { it.longitude } ?: 127.1058
            val uris = cluster.map { it.uri }
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

        // 2. Map Transactions (입출금·송금·계좌이체는 지역 이동 및 방문 장소와 무관하므로 동선 및 타임라인에서 완전 제외)
        val validExpenseTxs = transactions.filter { !isFinancialIncomeOrTransfer(it) }
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

        // 3. Map Golf Rounds
        for (golf in golfRounds) {
            val typeStr = if (golf.golfType == GolfType.FIELD) "필드" else "스크린"
            steps.add(
                RouteStep(
                    id = UUID.randomUUID().toString(),
                    time = golf.roundDate,
                    stepType = RouteStepType.GOLF,
                    title = "${golf.clubName} 라운드",
                    description = "총 타수 ${golf.totalScore ?: 0}타 ($typeStr)",
                    locationName = golf.clubName,
                    address = "골프장 필드 라운드",
                    category = "골프 라운드"
                )
            )
        }

        // 4. Map Vehicle Logs
        for (vLog in vehicleLogs) {
            val note = vLog.note ?: ""
            // [차량명 (차량번호)] 파싱 (예: "[벤츠 A클래스 (169저7737)]" 또는 "[벤츠 A클래스]")
            val matchedBracket = Regex("\\[(.*?)\\]").find(note)?.groupValues?.get(1)
            val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(matchedBracket ?: note)
            val carDisplayTitle = if (matchedBracket != null) {
                "차량 주행 $brandEmoji [$matchedBracket]"
            } else {
                "차량 주행"
            }
            val stepDesc = when {
                note.isNotBlank() -> note
                else -> "주행 거리 %.1f km".format(vLog.tripDistanceKm)
            }
            val vehicleTag = matchedBracket ?: "차량"

            steps.add(
                RouteStep(
                    id = UUID.randomUUID().toString(),
                    time = vLog.timestamp,
                    stepType = RouteStepType.DRIVING,
                    title = carDisplayTitle,
                    description = stepDesc,
                    category = "차계부",
                    tags = listOf("차량주행", "$brandEmoji $vehicleTag")
                )
            )
        }

        // Sort all steps chronologically
        steps.sortBy { it.time }

        // Extract Distinct Place Names for Itinerary Chain (결제 정보는 지도 이동 경로에서 제외하고 실제 방문 장소만 추출)
        val distinctPlaces = steps
            .filter { it.stepType != RouteStepType.TRANSACTION }
            .mapNotNull { it.locationName ?: it.title.takeIf { t -> !t.contains("주행") && !t.contains("촬영") } }
            .filter { !it.contains("촬영") }
            .distinct()

        val movementSummary = if (distinctPlaces.isNotEmpty()) {
            distinctPlaces.joinToString(" ➔ ")
        } else {
            "기록된 활동 없음"
        }

        // Generate Smart Title
        val hasGolf = golfRounds.isNotEmpty() || steps.any { it.stepType == RouteStepType.GOLF || it.title.contains("CC") }
        val repStep = steps.firstOrNull { it.stepType != RouteStepType.TRANSACTION && it.latitude != null }
        val mainPlace = distinctPlaces.firstOrNull() ?: repStep?.locationName ?: "서울 방이동"

        val title = when {
            hasGolf -> {
                val golfPlace = steps.firstOrNull { it.stepType == RouteStepType.GOLF || it.title.contains("CC") }?.title ?: "골프 라운드"
                val other = distinctPlaces.firstOrNull { !it.contains("CC") && !it.contains("골프") }
                if (other != null) "$golfPlace & $other" else "$golfPlace 기록"
            }
            distinctPlaces.size >= 2 -> "${distinctPlaces.first()} & ${distinctPlaces[1]}"
            distinctPlaces.size == 1 -> "${distinctPlaces.first()} 일정"
            photos.isNotEmpty() -> "${mainPlace} 일정"
            else -> "${date.monthValue}월 ${date.dayOfMonth}일의 다이어리"
        }

        // Extract All Photos, Companions, Tags
        val allPhotoUris = photos.map { it.uri }
        val totalExpense = validExpenseTxs.sumOf { it.amount }
        val vehicleLogDistance = vehicleLogs.sumOf { it.tripDistanceKm }
        val estimatedRouteDistance = LocationDistanceUtils.calculateRouteDrivingDistanceKm(steps)
        val totalDistance = if (vehicleLogDistance > 0) vehicleLogDistance else estimatedRouteDistance

        // Generate Smart Summary Paragraph
        val summary = buildSummaryNarrative(date, steps, distinctPlaces, photos.size, totalExpense)

        val tags = mutableListOf<String>()
        val allCompanions = steps.flatMap { it.companions }.distinct()
        allCompanions.forEach { tags.add("👤 $it") }

        if (hasGolf) tags.add("골프")
        if (totalExpense > 0) tags.add("지출기록")
        if (photos.isNotEmpty()) tags.add("사진 ${photos.size}장")
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
            date = date.atTime(steps.firstOrNull()?.time?.toLocalTime() ?: java.time.LocalTime.of(12, 0)),
            title = title,
            summary = summary,
            placeName = distinctPlaces.firstOrNull(),
            address = repStep?.address,
            latitude = repStep?.latitude,
            longitude = repStep?.longitude,
            photoUris = allPhotoUris,
            totalExpense = totalExpense,
            drivingDistanceKm = totalDistance,
            hasGolfRound = hasGolf,
            tags = tags,
            routeSteps = steps,
            movementSummary = movementSummary
        )
    }

    private fun buildSummaryNarrative(
        date: LocalDate,
        steps: List<RouteStep>,
        places: List<String>,
        photoCount: Int,
        totalExpense: Long
    ): String {
        if (steps.isEmpty()) {
            return "${date.monthValue}월 ${date.dayOfMonth}일에 기록된 활동 내역이 없습니다."
        }

        val timeFmt = DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)
        val parts = mutableListOf<String>()

        val firstStep = steps.first()
        parts.add("${firstStep.time.format(timeFmt)} ${firstStep.title}")

        val middleSteps = steps.drop(1).dropLast(1)
        if (middleSteps.isNotEmpty()) {
            val keyMiddle = middleSteps.filter { it.stepType == RouteStepType.GOLF || it.amount != null || it.photoUris.isNotEmpty() }
            if (keyMiddle.isNotEmpty()) {
                val midDesc = keyMiddle.joinToString(", ") { "${it.time.format(timeFmt)} ${it.title}" }
                parts.add(midDesc)
            }
        }

        if (steps.size > 1) {
            val lastStep = steps.last()
            parts.add("${lastStep.time.format(timeFmt)} ${lastStep.title}")
        }

        val routeText = parts.distinct().joinToString(" ➔ ")

        val statText = buildString {
            if (places.isNotEmpty()) append("방문 장소 ${places.size}곳")
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

    companion object {
        /**
         * 입금, 출금, 이체, 송금 등 비이동성 금융 거래 여부 판별.
         * 지역 이동 및 방문 장소와 무관하므로 다이어리 동선 및 타임라인에서 제외함.
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
    }
}

