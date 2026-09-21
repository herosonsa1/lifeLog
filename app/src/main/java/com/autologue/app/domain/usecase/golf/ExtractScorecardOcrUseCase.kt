package com.autologue.app.domain.usecase.golf

import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.util.GolfCourseDistanceUtils
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ExtractScorecardOcrUseCase @Inject constructor(
    private val golfRepository: GolfRepository,
    private val diaryRepository: DiaryRepository,
    private val vehicleRepository: VehicleRepository
) {
    suspend fun saveOcrResult(
        roundId: Long,
        totalScore: Int,
        totalPutts: Int?,
        holeScores: List<Int>,
        scorecardUri: String,
        courseName: String? = null,
        penaltyCount: Int? = null,
        girPercentage: Double? = null,
        averageDriveDistance: Double? = null,
        adjustedDriveDistance: Double? = null,
        averageTempo: Double? = null,
        steps: Int? = null,
        driveDistances: List<Double> = emptyList(),
        tempos: List<Double> = emptyList(),
        holePars: List<Int> = emptyList(),
        clubName: String? = null
    ) {
        val round = golfRepository.getGolfRoundById(roundId) ?: return

        // 1. 코스명 보강
        var memoWithCourse = if (!courseName.isNullOrBlank() && (round.memo == null || !round.memo.contains("코스"))) {
            if (round.memo.isNullOrBlank()) "코스: $courseName" else "${round.memo} / 코스: $courseName"
        } else {
            round.memo ?: ""
        }

        // [H-02] OCR 추출 실제 파 배열 우선, 없으면 표준 기본 파 폴백
        val effectivePars = if (holePars.size == holeScores.size && holePars.isNotEmpty()) holePars
        else listOf(4, 3, 5, 4, 3, 4, 5, 4, 4, 4, 4, 5, 3, 4, 5, 4, 3, 4)

        // 2. 홀별 성적 통계(버디, 파, 보기, 더블+) 태그 보강
        if (holeScores.isNotEmpty()) {
            var birdie = 0
            var par = 0
            var bogey = 0
            var doublePlus = 0
            holeScores.forEachIndexed { idx, score ->
                val expectedPar = effectivePars.getOrElse(idx) { 4 }
                val diff = score - expectedPar
                when {
                    diff <= -1 -> birdie++
                    diff == 0 -> par++
                    diff == 1 -> bogey++
                    else -> doublePlus++
                }
            }
            val statsTag = "[통계: 버디 $birdie, 파 $par, 보기 $bogey, 더블+ $doublePlus]"
            memoWithCourse = if (memoWithCourse.contains("[통계:")) {
                memoWithCourse.replace(Regex("""\[통계:[^\]]+\]"""), statsTag)
            } else if (memoWithCourse.isBlank()) {
                statsTag
            } else {
                "$memoWithCourse / $statsTag"
            }
        }

        val updatedMemo = memoWithCourse.ifBlank { null }

        val finalAdjustedDrive = adjustedDriveDistance ?: if (driveDistances.size >= 3) {
            val sorted = driveDistances.sorted()
            val trimmed = sorted.subList(1, sorted.size - 1)
            (trimmed.sum() / trimmed.size * 10).toInt() / 10.0
        } else averageDriveDistance

        val finalClub = if (!clubName.isNullOrBlank() && (round.clubName == "필드 골프장" || round.clubName.contains("일반 사진") || round.clubName.isBlank())) {
            clubName
        } else round.clubName

        val updated = round.copy(
            clubName = finalClub,
            totalScore = totalScore,
            totalPutts = totalPutts,
            holeScores = holeScores,
            // [H-02] OCR 추출 실제 파 배열 저장 — 버디/파/보기/더블+ 집계 정확도 보장
            holePars = if (holePars.isNotEmpty()) holePars else round.holePars,
            scorecardPhotoUri = scorecardUri,
            memo = updatedMemo,
            penaltyCount = penaltyCount ?: round.penaltyCount,
            girPercentage = girPercentage ?: round.girPercentage,
            averageDriveDistance = averageDriveDistance ?: round.averageDriveDistance,
            adjustedDriveDistance = finalAdjustedDrive ?: round.adjustedDriveDistance,
            averageTempo = averageTempo ?: round.averageTempo,
            steps = steps ?: round.steps,
            driveDistances = if (driveDistances.isNotEmpty()) driveDistances else round.driveDistances,
            tempos = if (tempos.isNotEmpty()) tempos else round.tempos
        )
        golfRepository.updateGolfRound(updated)

        // 다이어리 기록에도 스코어 및 세부 통계 정보 실시간 반영
        val roundDate = round.roundDate.toLocalDate()
        val diaryList = runCatching { diaryRepository.getDiaryEntriesByDateRange(roundDate, roundDate).first() }.getOrDefault(emptyList())
        val existingDiary = diaryList.firstOrNull()
        if (existingDiary != null) {
            val statsList = mutableListOf<String>()
            statsList.add("${totalScore}타")
            if (totalPutts != null) statsList.add("퍼트 ${totalPutts}P")
            if (girPercentage != null) statsList.add("GIR ${girPercentage}%")
            if (averageDriveDistance != null) {
                val adj = finalAdjustedDrive ?: round.getEffectiveAdjustedDriveDistance()
                if (adj != null && adj != averageDriveDistance) {
                    statsList.add("드라이브 ${averageDriveDistance}m (보정 ${adj}m)")
                } else {
                    statsList.add("드라이브 ${averageDriveDistance}m")
                }
            }
            if (penaltyCount != null && penaltyCount > 0) statsList.add("벌타 ${penaltyCount}타")
            if (steps != null) statsList.add("걸음 ${steps}보")
            if (holeScores.isNotEmpty()) {
                val b = holeScores.indices.count { holeScores[it] - effectivePars.getOrElse(it) { 4 } <= -1 }
                val p = holeScores.indices.count { holeScores[it] - effectivePars.getOrElse(it) { 4 } == 0 }
                val d = holeScores.indices.count { holeScores[it] - effectivePars.getOrElse(it) { 4 } >= 2 }
                statsList.add("버디 ${b}·파 ${p}·더블+ ${d}")
            }

            val scoreDesc = statsList.joinToString(" · ")
            val updatedSteps = existingDiary.routeSteps.map { s ->
                if (s.stepType == RouteStepType.GOLF || s.title.contains(round.clubName)) {
                    val baseDesc = (s.description ?: "").substringBefore(" · 스코어:").substringBefore("스코어:")
                    s.copy(
                        description = if (baseDesc.isNotBlank()) "$baseDesc · 스코어: $scoreDesc" else "스코어: $scoreDesc",
                        photoUris = (s.photoUris + listOf(scorecardUri)).distinct()
                    )
                } else s
            }
            diaryRepository.updateDiaryEntry(existingDiary.copy(routeSteps = updatedSteps))
        } else {
            // 다이어리 엔트리가 아직 없는 경우 골프 라운드 스텝과 함께 다이어리 생성
            val startTime = round.startTime ?: round.roundDate
            val golfStep = RouteStep(
                id = java.util.UUID.randomUUID().toString(),
                time = startTime,
                stepType = RouteStepType.GOLF,
                title = "${round.clubName} 라운드",
                description = "스코어: ${totalScore}타" + (if (totalPutts != null) " · 퍼트 ${totalPutts}P" else "") + (if (steps != null) " · ${steps}보" else ""),
                locationName = round.clubName,
                address = "골프장 필드 라운드",
                photoUris = listOf(scorecardUri),
                category = "골프 라운드"
            )
            val newDiary = DiaryEntry(
                date = startTime,
                title = "${round.clubName} 라운딩",
                placeName = round.clubName,
                summary = "스코어카드 자동 분석: ${totalScore}타",
                hasGolfRound = true,
                routeSteps = listOf(golfStep),
                tags = listOf("⛳ ${round.clubName}")
            )
            diaryRepository.insertDiaryEntry(newDiary)
        }
    }

    /**
     * 스코어카드 OCR 분석 결과를 바탕으로, 해당 날짜의 기존 라운드에 결합하거나
     * 기존 라운드가 없을 경우 새 GolfRound를 자동 생성하여 등록합니다.
     */
    suspend fun processScorecardResult(
        result: com.autologue.app.data.ocr.ScorecardOcrResult,
        scorecardUri: String,
        targetDate: java.time.LocalDate
    ): GolfRound? {
        val totalScore = result.totalScore ?: (if (result.holeScores.isNotEmpty()) result.holeScores.sum() else null) ?: return null
        val effectiveDate = result.playDate ?: targetDate
        val existingRound = golfRepository.getGolfRoundByDate(effectiveDate)

        // [사용자 핵심 지침] 18홀 완전한 스코어카드가 아니면 신규 등록에서 제외
        val is18HoleComplete = totalScore in 54..144 || result.holeScores.size >= 14
        if (existingRound == null && !is18HoleComplete) {
            runCatching { android.util.Log.d("ExtractScorecardOcr", "18홀 미완주 불완전 스코어카드 신규 등록 제외: score=$totalScore, holes=${result.holeScores.size} ($effectiveDate)") }
            return null
        }

        val finalRound = if (existingRound != null) {
            val candidateClub = result.clubName ?: (if (!result.courseName.isNullOrBlank()) "${result.courseName} CC" else null)
            val updatedClub = if ((existingRound.clubName == "필드 골프장" || existingRound.clubName.contains("일반 사진") || existingRound.clubName.isBlank()) && candidateClub != null) {
                candidateClub
            } else if (!result.clubName.isNullOrBlank() && (existingRound.clubName == "필드 골프장" || existingRound.clubName.contains("일반 사진") || existingRound.clubName.isBlank())) {
                result.clubName
            } else existingRound.clubName

            val updatedWithPhoto = existingRound.copy(
                clubName = updatedClub,
                scorecardPhotoUri = scorecardUri,
                matchingPhotoUris = (existingRound.matchingPhotoUris + listOf(scorecardUri)).distinct()
            )
            golfRepository.updateGolfRound(updatedWithPhoto)
            updatedWithPhoto
        } else {
            val club = result.clubName ?: (if (!result.courseName.isNullOrBlank()) "${result.courseName} CC" else "필드 골프장")
            val startTime = effectiveDate.atTime(8, 0)
            val endTime = effectiveDate.atTime(13, 30)
            val memo = if (!result.courseName.isNullOrBlank()) "코스: ${result.courseName}" else "스코어카드 자동 분석"
            val newRound = GolfRound(
                clubName = club,
                roundDate = startTime,
                golfType = com.autologue.app.domain.model.GolfType.FIELD,
                startTime = startTime,
                endTime = endTime,
                memo = memo,
                scorecardPhotoUri = scorecardUri,
                matchingPhotoUris = listOf(scorecardUri)
            )
            val newId = golfRepository.insertGolfRound(newRound)
            newRound.copy(id = newId)
        }

        saveOcrResult(
            roundId = finalRound.id,
            totalScore = totalScore,
            totalPutts = result.totalPutts,
            holeScores = result.holeScores,
            scorecardUri = scorecardUri,
            courseName = result.courseName,
            penaltyCount = result.penaltyCount,
            girPercentage = result.girPercentage,
            averageDriveDistance = result.averageDriveDistance,
            adjustedDriveDistance = result.adjustedDriveDistance,
            averageTempo = result.averageTempo,
            steps = result.steps,
            driveDistances = result.driveDistances,
            tempos = result.tempos,
            holePars = result.holePars,
            clubName = finalRound.clubName
        )

        // [골프장 주행기록 차계부 자동 연동] 18홀 라운드 확정 시 해당 날짜의 골프 주행기록 1회 자동 생성
        val targetClub = finalRound.clubName.ifBlank { result.clubName ?: "골프장" }
        if (targetClub != "필드 골프장" && !targetClub.contains("일반 사진")) {
            val estimatedDist = GolfCourseDistanceUtils.getEstimatedRoundTripKm(targetClub)
            val startTime = finalRound.startTime ?: effectiveDate.atTime(8, 0)
            val drivingTime = startTime.minusHours(2)

            val existingDriving = runCatching {
                vehicleRepository.getAllVehicleLogsFlow().first()
                    .any { it.logType == VehicleLogType.TRIP_DRIVING && it.timestamp.toLocalDate() == effectiveDate }
            }.getOrDefault(false)

            if (!existingDriving) {
                val vehicleLog = VehicleLog(
                    timestamp = drivingTime,
                    logType = VehicleLogType.TRIP_DRIVING,
                    tripDistanceKm = estimatedDist,
                    note = "$targetClub 라운딩 왕복 주행"
                )
                vehicleRepository.insertVehicleLog(vehicleLog)
            }
        }

        return golfRepository.getGolfRoundById(finalRound.id) ?: finalRound
    }
}