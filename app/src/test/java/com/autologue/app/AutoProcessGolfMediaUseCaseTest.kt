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

    @Test
    fun processScorecardResult_persistsHoleParsAndGeneratesAccurateStats() = runBlocking {
        // 오크밸리 CC 실제 스코어카드 데이터 (비표준 파 코스: Pine + Cherry)
        val oakValleyPars = listOf(4, 4, 3, 4, 5, 4, 3, 4, 5, 4, 3, 4, 4, 4, 3, 5, 4, 5)
        val oakValleyScores = listOf(4, 8, 3, 5, 5, 6, 4, 7, 6, 5, 3, 5, 4, 6, 4, 5, 6, 6)
        val testDate = LocalDate.of(2026, 9, 11)

        val result = ScorecardOcrResult(
            totalScore = 92,
            totalPutts = 38,
            holeScores = oakValleyScores,
            holePars = oakValleyPars,
            courseName = "Pine / Cherry",
            clubName = "오크밸리 CC",
            girPercentage = 27.8,
            steps = 6983,
            penaltyCount = 3,
            playDate = testDate,
            recognizedRawText = "오크밸리 CC / 2026.09.11 SCORE 92"
        )

        val round = extractScorecardOcrUseCase.processScorecardResult(
            result = result,
            scorecardUri = "content://media/oak_valley.png",
            targetDate = testDate
        )

        // 1. GolfRound에 holePars가 온전히 저장되었는지 검증
        assertNotNull(round)
        assertEquals(18, round!!.holePars.size)
        assertEquals(oakValleyPars, round.holePars)
        assertEquals(92, round.totalScore)
        assertEquals(38, round.totalPutts)
        assertEquals(3, round.penaltyCount)

        // 2. round.getScorecardStats()가 실제 holePars를 반영하여 정확히 집계하는지 검증
        val stats = round.getScorecardStats()
        assertNotNull(stats)
        assertEquals(0, stats!!.birdieCount)
        assertEquals(6, stats.parCount)
        assertEquals(7, stats.bogeyCount)
        assertEquals(5, stats.doublePlusCount)
        assertEquals(3, stats.penaltyCount)

        // 3. 메모에 [통계: 버디 0, 파 6, 보기 7, 더블+ 5] 태그가 반영되었는지 검증
        assertNotNull(round.memo)
        assertTrue("메모에 버디 0이 포함되어야 함: ${round.memo}", round.memo!!.contains("버디 0"))
        assertTrue("메모에 파 6이 포함되어야 함: ${round.memo}", round.memo!!.contains("파 6"))
        assertTrue("메모에 보기 7이 포함되어야 함: ${round.memo}", round.memo!!.contains("보기 7"))
        assertTrue("메모에 더블+ 5가 포함되어야 함: ${round.memo}", round.memo!!.contains("더블+ 5"))
    }

    @Test
    fun processScorecardResult_preservesValidScorecard_evenIfClubNameIsGeneric() = runBlocking {
        val testDate = LocalDate.of(2026, 9, 11)
        val result = ScorecardOcrResult(
            totalScore = 92,
            totalPutts = 38,
            holeScores = listOf(4, 8, 3, 5, 5, 6, 4, 7, 6, 5, 3, 5, 4, 6, 4, 5, 6, 6),
            clubName = null, // 클럽명 미인식 상태
            courseName = null,
            playDate = testDate,
            recognizedRawText = "SCORE 92"
        )

        val round = extractScorecardOcrUseCase.processScorecardResult(
            result = result,
            scorecardUri = "content://media/unknown_golf.png",
            targetDate = testDate
        )

        assertNotNull(round)
        assertEquals(92, round!!.totalScore)
        assertEquals(18, round.holeScores.size)
        // 유효한 스코어가 존재하므로 삭제되지 않고 보존 대상임
        assertTrue(round.totalScore in 50..144)
    }

    // Fake Repositories for testing
    class FakeGolfRepository : GolfRepository {
        val rounds = mutableListOf<GolfRound>()

        override fun getAllGolfRoundsFlow(): Flow<List<GolfRound>> = flowOf(rounds)
        override suspend fun getAllGolfRoundsList(): List<GolfRound> = rounds
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

