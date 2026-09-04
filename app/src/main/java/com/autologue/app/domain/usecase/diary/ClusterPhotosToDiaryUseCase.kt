package com.autologue.app.domain.usecase.diary

import com.autologue.app.domain.model.PlaceCluster
import com.autologue.app.domain.repository.DiaryRepository
import java.time.LocalDate
import javax.inject.Inject

class ClusterPhotosToDiaryUseCase @Inject constructor(
    private val diaryRepository: DiaryRepository
) {
    suspend operator fun invoke(targetDate: LocalDate): List<PlaceCluster> {
        return diaryRepository.generateClustersForDate(targetDate)
    }
}
