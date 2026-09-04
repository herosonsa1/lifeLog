package com.autologue.app.domain.usecase.golf

import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.repository.GolfRepository
import javax.inject.Inject

class ExtractScorecardOcrUseCase @Inject constructor(
    private val golfRepository: GolfRepository
) {
    suspend fun saveOcrResult(roundId: Long, totalScore: Int, totalPutts: Int?, holeScores: List<Int>, scorecardUri: String) {
        val round = golfRepository.getGolfRoundById(roundId) ?: return
        val updated = round.copy(
            totalScore = totalScore,
            totalPutts = totalPutts,
            holeScores = holeScores,
            scorecardPhotoUri = scorecardUri
        )
        golfRepository.updateGolfRound(updated)
    }
}
