package com.autologue.app.domain.usecase.golf

import com.autologue.app.data.ocr.GolfLockerSlipResult
import com.autologue.app.domain.model.*
import com.autologue.app.domain.repository.*
import kotlinx.coroutines.flow.first
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class ProcessGolfLockerSlipUseCase @Inject constructor(
    private val golfRepository: GolfRepository,
    private val diaryRepository: DiaryRepository,
    private val vehicleRepository: VehicleRepository,
    private val transactionRepository: TransactionRepository
) {
    suspend operator fun invoke(result: GolfLockerSlipResult, photoUri: String?): GolfRound? {
        // [유효성 검증] 유효한 라커 슬립이 아니거나 일반 사진인 경우 골프 라운드 및 다이어리 스텝 생성 거부
        if (!result.isLockerSlip || result.clubName == "일반 사진") {
            return null
        }

        val teeOff = result.teeOffTime ?: LocalTime.of(7, 30)
        val startTime = LocalDateTime.of(result.date, teeOff)
        val endTime = startTime.plusHours(5).plusMinutes(30)

        // 1. Save or Update GolfRound
        val existingRound = golfRepository.getGolfRoundByDate(result.date)
        val (baseClub, courseFromClub) = splitClubAndCourse(result.clubName)
        val rawCourse = result.courseName?.trim()?.ifBlank { null } ?: courseFromClub.ifBlank { null }
        val cleanCourseSuffix = if (!rawCourse.isNullOrBlank()) {
            if (rawCourse.endsWith("코스")) rawCourse else "$rawCourse 코스"
        } else null

        val finalClubName = if (cleanCourseSuffix != null && !baseClub.contains(cleanCourseSuffix)) {
            "$baseClub ($cleanCourseSuffix)"
        } else {
            result.clubName
        }

        val memoParts = mutableListOf<String>()
        if (!cleanCourseSuffix.isNullOrBlank()) memoParts.add("코스: $cleanCourseSuffix")
        if (!result.lockerNumber.isNullOrBlank()) memoParts.add("락커: ${result.lockerNumber}")
        val memoStr = if (memoParts.isNotEmpty()) memoParts.joinToString(" / ") else "라커룸 안내지 분석"

        val photoList = listOfNotNull(photoUri).distinct()
        val companionsList = listOfNotNull(result.playerName).distinct()

        val golfRound = if (existingRound != null) {
            val updated = existingRound.copy(
                clubName = if (finalClubName != "필드 골프장") finalClubName else existingRound.clubName,
                startTime = startTime,
                endTime = endTime,
                memo = memoStr,
                matchingPhotoUris = (existingRound.matchingPhotoUris + photoList).distinct(),
                scorecardPhotoUri = existingRound.scorecardPhotoUri ?: photoUri,
                companions = (existingRound.companions + companionsList).distinct()
            )
            golfRepository.updateGolfRound(updated)
            updated
        } else {
            val newRound = GolfRound(
                clubName = finalClubName,
                roundDate = startTime,
                golfType = GolfType.FIELD,
                startTime = startTime,
                endTime = endTime,
                memo = memoStr,
                scorecardPhotoUri = photoUri,
                matchingPhotoUris = photoList,
                companions = companionsList
            )
            val newId = golfRepository.insertGolfRound(newRound)
            newRound.copy(id = newId)
        }

        // 2. Reflect in Diary (다이어리 적재)
        val diaryList = runCatching { diaryRepository.getDiaryEntriesByDateRange(result.date, result.date).first() }.getOrDefault(emptyList())
        val existingDiary = diaryList.firstOrNull()

        val golfStep = RouteStep(
            id = UUID.randomUUID().toString(),
            time = startTime,
            stepType = RouteStepType.GOLF,
            title = "${result.clubName} 라운드",
            description = "티오프 ${teeOff} (소요 5시간 30분) · $memoStr",
            locationName = result.clubName,
            address = "골프장 필드 라운드",
            photoUris = photoList,
            category = "골프 라운드",
            companions = companionsList
        )

        val drivingStep = RouteStep(
            id = UUID.randomUUID().toString(),
            time = startTime.minusHours(2),
            stepType = RouteStepType.DRIVING,
            title = "${result.clubName} 이동",
            description = "골프장 왕복 주행 (약 150km)",
            locationName = result.clubName,
            category = "차량 주행"
        )

        if (existingDiary != null) {
            val hasGolf = existingDiary.routeSteps.any { it.stepType == RouteStepType.GOLF || it.title.contains(result.clubName) }
            val updatedSteps = if (!hasGolf) {
                (listOf(drivingStep, golfStep) + existingDiary.routeSteps).sortedBy { it.time }
            } else {
                existingDiary.routeSteps.map { s: RouteStep ->
                    if (s.stepType == RouteStepType.GOLF || s.title.contains(result.clubName)) {
                        s.copy(
                            title = "${result.clubName} 라운드",
                            description = "티오프 ${teeOff} · $memoStr",
                            photoUris = (s.photoUris + photoList).distinct(),
                            companions = (s.companions + companionsList).distinct()
                        )
                    } else s
                }
            }
            val updatedDiary = existingDiary.copy(
                title = "${result.clubName} 라운딩",
                placeName = result.clubName,
                hasGolfRound = true,
                routeSteps = updatedSteps,
                tags = (existingDiary.tags + listOf("⛳ ${result.clubName}") + companionsList.map { "👤 $it" }).distinct()
            )
            diaryRepository.updateDiaryEntry(updatedDiary)
        } else {
            val newDiary = DiaryEntry(
                date = startTime,
                title = "${result.clubName} 라운딩",
                placeName = result.clubName,
                summary = "티오프 ${teeOff}, $memoStr 현장 라운딩 기록",
                hasGolfRound = true,
                routeSteps = listOf(drivingStep, golfStep),
                tags = (listOf("⛳ ${result.clubName}") + companionsList.map { "👤 $it" }).distinct()
            )
            diaryRepository.insertDiaryEntry(newDiary)
        }

        // 3. Reflect in Vehicle (유효한 골프장 안내지일 때만 주행 기록 1회 생성)
        val isCorruptedClubName = result.clubName.contains("GOVERNMENT", ignoreCase = true) ||
                result.clubName.contains("acce", ignoreCase = true) ||
                result.clubName == "일반 사진" ||
                result.clubName.length > 25

        if (result.isLockerSlip && !isCorruptedClubName) {
            val estimatedDistance = when {
                result.clubName.contains("아리지") || result.clubName.contains("스카이밸리") -> 160.0
                result.clubName.contains("필로스") -> 120.0
                result.clubName.contains("라데나") -> 190.0
                result.clubName.contains("동강시스타") -> 280.0
                result.clubName.contains("남촌") -> 110.0
                result.clubName.contains("해슬리") -> 140.0
                result.clubName.contains("베어크리크") -> 150.0
                result.clubName.contains("라비에벨") -> 170.0
                result.clubName.contains("아난티") -> 110.0
                else -> 130.0
            }

            val existingDriving = runCatching {
                vehicleRepository.getAllVehicleLogsFlow().first()
                    .any { it.logType == VehicleLogType.TRIP_DRIVING && it.timestamp.toLocalDate() == startTime.toLocalDate() }
            }.getOrDefault(false)

            if (!existingDriving) {
                val vehicleLog = VehicleLog(
                    timestamp = startTime.minusHours(2),
                    logType = VehicleLogType.TRIP_DRIVING,
                    tripDistanceKm = estimatedDistance,
                    note = "${result.clubName} 라운딩 왕복 주행"
                )
                vehicleRepository.insertVehicleLog(vehicleLog)
            }
        }

        return golfRound
    }
}
