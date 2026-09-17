package com.autologue.app

import com.autologue.app.data.ocr.ScorecardOcrResult
import com.autologue.app.domain.model.*
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.usecase.golf.ExtractScorecardOcrUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class AutoProcessGolfMediaUseCaseTest {

    private lateinit var fakeGolfRepository: FakeGolfRepository
    private lateinit var fakeDiaryRepository: FakeDiaryRepository
    private lateinit var extractScorecardOcrUseCase: ExtractScorecardOcrUseCase

    @Before
    fun setUp() {
        fakeGolfRepository = FakeGolfRepository()
        fakeDiaryRepository = FakeDiaryRepository()
        extractScorecardOcrUseCase = ExtractScorecardOcrUseCase(
            golfRepository = fakeGolfRepository,
            diaryRepository = fakeDiaryRepository
        )
    }

    @Test
    fun processScorecardResult_createsNewRound_whenNoRoundExists() = runBlocking {
        val testDate = LocalDate.of(2026, 9, 14)
        val sampleResult = ScorecardOcrResult(
            totalScore = 86,
            totalPutts = 40,
            holeScores = listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4),
            holePars = listOf(4, 3, 5, 4, 3, 4, 5, 4, 4, 4, 4, 5, 3, 4, 5, 4, 3, 4),
            courseName = "West / South",
            clubName = "필로스 GC",
            girPercentage = 55.6,
            steps = 6384,
            penaltyCount = 2,
            averageDriveDistance = 210.5,
            adjustedDriveDistance = 205.0,
            averageTempo = 3.2,
            recognizedRawText = "필로스 GC SCORE 86"
        )

        val createdRound = extractScorecardOcrUseCase.processScorecardResult(
            result = sampleResult,
            scorecardUri = "content://media/external/images/media/999",
            targetDate = testDate
        )

        assertNotNull(createdRound)
        assertEquals(86, createdRound!!.totalScore)
        assertEquals(40, createdRound.totalPutts)
        assertEquals("필로스 GC", createdRound.clubName)
        assertEquals(18, createdRound.holeScores.size)
        assertEquals(6384, createdRound.steps)
        assertEquals(2, createdRound.penaltyCount)
        assertTrue(createdRound.memo?.contains("West / South") == true)
    }

    @Test
    fun processScorecardResult_mergesIntoExistingLockerSlipRound() = runBlocking {
        val testDate = LocalDate.of(2026, 9, 14)
        val startTime = LocalDateTime.of(2026, 9, 14, 7, 30)

        // 라커룸 전표로 먼저 생성된 라운드
        val initialRound = GolfRound(
            id = 1L,
            clubName = "필로스 CC",
            roundDate = startTime,
            golfType = GolfType.FIELD,
            startTime = startTime,
            endTime = startTime.plusHours(5),
            memo = "코스: West 코스 / 락커: 125",
            matchingPhotoUris = listOf("content://media/locker_slip.png")
        )
        fakeGolfRepository.insertGolfRound(initialRound)

        // 이후 스코어카드 OCR 분석 결과가 도착하여 결합되는 시나리오
        val scorecardResult = ScorecardOcrResult(
            totalScore = 86,
            totalPutts = 40,
            holeScores = listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4),
            courseName = "West / South",
            clubName = "필로스 GC",
            recognizedRawText = "SCORE 86"
        )

        val mergedRound = extractScorecardOcrUseCase.processScorecardResult(
            result = scorecardResult,
            scorecardUri = "content://media/scorecard.png",
            targetDate = testDate
        )

        assertNotNull(mergedRound)
        assertEquals(1L, mergedRound!!.id)
        assertEquals(86, mergedRound.totalScore)
        assertEquals(40, mergedRound.totalPutts)
        assertEquals(2, mergedRound.matchingPhotoUris.size)
        assertTrue(mergedRound.matchingPhotoUris.contains("content://media/scorecard.png"))
        assertTrue(mergedRound.matchingPhotoUris.contains("content://media/locker_slip.png"))
        // 단일 라운드로 유지되고 라운드 개수가 1개인지 확인
        assertEquals(1, fakeGolfRepository.rounds.size)
    }

    // Fake Repositories for testing
    class FakeGolfRepository : GolfRepository {
        val rounds = mutableListOf<GolfRound>()

        override fun getAllGolfRoundsFlow(): Flow<List<GolfRound>> = flowOf(rounds)
        override suspend fun getGolfRoundById(id: Long): GolfRound? = rounds.find { it.id == id }
        override suspend fun getGolfRoundByDate(date: LocalDate): GolfRound? =
            rounds.find { (it.startTime ?: it.roundDate).toLocalDate() == date }

        override suspend fun insertGolfRound(round: GolfRound): Long {
            val id = if (round.id != 0L) round.id else (rounds.size + 1).toLong()
            val newRound = round.copy(id = id)
            rounds.add(newRound)
            return id
        }

        override suspend fun updateGolfRound(round: GolfRound) {
            val idx = rounds.indexOfFirst { it.id == round.id }
            if (idx >= 0) rounds[idx] = round else rounds.add(round)
        }

        override suspend fun deleteGolfRound(id: Long) {
            rounds.removeAll { it.id == id }
        }
    }

    class FakeDiaryRepository : DiaryRepository {
        val diaries = mutableListOf<DiaryEntry>()

        override fun getDiaryEntriesFlow(): Flow<List<DiaryEntry>> = flowOf(diaries)
        override fun getDiaryEntriesByDateRange(startDate: LocalDate, endDate: LocalDate): Flow<List<DiaryEntry>> =
            flowOf(diaries.filter { it.date.toLocalDate() in startDate..endDate })

        override suspend fun getDiaryEntryById(id: Long): DiaryEntry? = diaries.find { it.id == id }

        override suspend fun insertDiaryEntry(entry: DiaryEntry): Long {
            val id = if (entry.id != 0L) entry.id else (diaries.size + 1).toLong()
            diaries.add(entry.copy(id = id))
            return id
        }

        override suspend fun updateDiaryEntry(entry: DiaryEntry) {
            val idx = diaries.indexOfFirst { it.id == entry.id }
            if (idx >= 0) diaries[idx] = entry else diaries.add(entry)
        }

        override suspend fun deleteDiaryEntry(id: Long) {
            diaries.removeAll { it.id == id }
        }

        override suspend fun cleanDuplicates(): Int = 0
        override suspend fun generateClustersForDate(date: LocalDate): List<PlaceCluster> = emptyList()
    }
}

