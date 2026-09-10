package com.autologue.app.domain.usecase.golf

import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import kotlinx.coroutines.flow.first
import javax.inject.Inject

class ExtractScorecardOcrUseCase @Inject constructor(
    private val golfRepository: GolfRepository,
    private val diaryRepository: DiaryRepository
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
        tempos: List<Double> = emptyList()
    ) {
        val round = golfRepository.getGolfRoundById(roundId) ?: return

        // 코스명이 감지되었고 기존 memo에 코스 정보가 없다면 memo에 코스명 보강
        val updatedMemo = if (!courseName.isNullOrBlank() && (round.memo == null || !round.memo.contains("코스"))) {
            if (round.memo.isNullOrBlank()) "코스: $courseName" else "${round.memo} / 코스: $courseName"
        } else {
            round.memo
        }

        val finalAdjustedDrive = adjustedDriveDistance ?: if (driveDistances.size >= 3) {
            val sorted = driveDistances.sorted()
            val trimmed = sorted.subList(1, sorted.size - 1)
            (trimmed.sum() / trimmed.size * 10).toInt() / 10.0
        } else averageDriveDistance

        val updated = round.copy(
            totalScore = totalScore,
            totalPutts = totalPutts,
            holeScores = holeScores,
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
            if (steps != null) statsList.add("걸음 ${steps}보")

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
        }
    }
}
