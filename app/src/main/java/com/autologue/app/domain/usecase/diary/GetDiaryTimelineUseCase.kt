package com.autologue.app.domain.usecase.diary

import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.repository.DiaryRepository
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate
import javax.inject.Inject

class GetDiaryTimelineUseCase @Inject constructor(
    private val diaryRepository: DiaryRepository
) {
    operator fun invoke(startDate: LocalDate, endDate: LocalDate): Flow<List<DiaryEntry>> {
        return diaryRepository.getDiaryEntriesByDateRange(startDate, endDate)
    }
}
