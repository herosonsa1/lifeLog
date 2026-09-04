package com.autologue.app.domain.usecase.golf

import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.repository.GolfRepository
import javax.inject.Inject

class MatchGolfRoundPhotosUseCase @Inject constructor(
    private val golfRepository: GolfRepository
) {
    suspend operator fun invoke(roundId: Long, matchingUris: List<String>) {
        val round = golfRepository.getGolfRoundById(roundId) ?: return
        val updated = round.copy(matchingPhotoUris = (round.matchingPhotoUris + matchingUris).distinct())
        golfRepository.updateGolfRound(updated)
    }
}
