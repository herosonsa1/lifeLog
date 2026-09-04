package com.autologue.app.domain.repository

import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.PlaceCluster
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

interface DiaryRepository {
    fun getDiaryEntriesFlow(): Flow<List<DiaryEntry>>
    fun getDiaryEntriesByDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DiaryEntry>>
    suspend fun getDiaryEntryById(id: Long): DiaryEntry?
    suspend fun insertDiaryEntry(entry: DiaryEntry): Long
    suspend fun updateDiaryEntry(entry: DiaryEntry)
    suspend fun deleteDiaryEntry(id: Long)
    suspend fun cleanDuplicates(): Int
    suspend fun generateClustersForDate(date: LocalDate): List<PlaceCluster>
}
