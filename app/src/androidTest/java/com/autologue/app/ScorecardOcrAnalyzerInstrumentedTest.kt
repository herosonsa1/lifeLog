package com.autologue.app

import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.getScorecardStats
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ScorecardOcrAnalyzerInstrumentedTest {

    @Test
    fun testRealScorecardImageOnEmulator() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val analyzer = ScorecardOcrAnalyzer(context)
        val file = File("/data/local/tmp/scorecard.png")
        assertTrue("테스트용 스코어카드 이미지 파일이 존재해야 합니다", file.exists())

        val result = analyzer.analyzeScorecard(Uri.fromFile(file))

        println("INSTRUMENTED_TEST_RESULT: totalScore=${result.totalScore}, totalPutts=${result.totalPutts}, course=${result.courseName}")
        println("INSTRUMENTED_TEST_RESULT: holeScores=${result.holeScores}")
        println("INSTRUMENTED_TEST_RESULT: holePars=${result.holePars}")
        println("INSTRUMENTED_TEST_RESULT: penalty=${result.penaltyCount}, steps=${result.steps}, gir=${result.girPercentage}")

        // 1. 총 타수 86타 확정
        assertEquals(86, result.totalScore)

        // 2. 총 퍼트 수 40개 확정
        assertEquals(40, result.totalPutts)

        // 3. 18홀 스코어 매트릭스 확정
        val expectedScores = listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4)
        assertEquals(18, result.holeScores.size)
        assertEquals(expectedScores, result.holeScores)

        // 4. 코스명 종합
        assertEquals("West / South", result.courseName)

        // 5. 벌타 2타
        assertEquals(2, result.penaltyCount)

        // 6. 걸음수 6384
        assertEquals(6384, result.steps)

        // 7. GIR 55.6%
        assertEquals(55.6, result.girPercentage ?: 0.0, 0.1)

        // 8. 스코어 성적 집계 검증 (버디 1, 파 5, 보기 10, 더블+ 2, 벌타 2)
        val round = GolfRound(
            clubName = result.clubName ?: "필로스 GC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = GolfType.FIELD,
            holeScores = result.holeScores,
            penaltyCount = result.penaltyCount ?: 0
        )
        val stats = round.getScorecardStats()
        assertNotNull(stats)
        assertEquals(1, stats!!.birdieCount)
        assertEquals(5, stats.parCount)
        assertEquals(10, stats.bogeyCount)
        assertEquals(2, stats.doublePlusCount)
        assertEquals(2, stats.penaltyCount)
    }

    @Test
    fun testRealOakValleyScorecardOnEmulator() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val analyzer = ScorecardOcrAnalyzer(context)
        val file = File("/data/local/tmp/test_oak_valley.png")
        assertTrue("오크밸리 테스트용 스코어카드 이미지 파일이 존재해야 합니다", file.exists())

        val result = analyzer.analyzeScorecard(Uri.fromFile(file))

        println("OAK_VALLEY_REAL_RESULT: totalScore=${result.totalScore}, totalPutts=${result.totalPutts}, course=${result.courseName}")
        println("OAK_VALLEY_REAL_RESULT: holeScores=${result.holeScores}")
        println("OAK_VALLEY_REAL_RESULT: holePars=${result.holePars}")
        println("OAK_VALLEY_REAL_RESULT: penalty=${result.penaltyCount}, steps=${result.steps}, gir=${result.girPercentage}")
        println("OAK_VALLEY_RAW_LINES:\n${result.recognizedRawText}")

        // 1. 총 타수 92타 확정
        assertEquals(92, result.totalScore)

        // 2. 총 퍼트수 38개 확정
        assertEquals(38, result.totalPutts)

        // 3. 18홀 스코어 매트릭스 확정 (1번 홀 분홍색 박스 역산 포함 18홀 전수 복원)
        val expectedScores = listOf(4, 8, 3, 5, 5, 6, 4, 7, 6, 5, 3, 5, 4, 6, 4, 5, 6, 6)
        assertEquals(18, result.holeScores.size)
        assertEquals(expectedScores, result.holeScores)

        // 4. 코스명 종합 (Pine / Cherry)
        assertEquals("Pine / Cherry", result.courseName)

        // 5. 총 페널티 2타 (Pine 2타 확정)
        assertEquals(2, result.penaltyCount)

        // 6. 전체 걸음수 6983
        assertEquals(6983, result.steps)

        // 7. GIR 27.8% (% 기호 누락 라벨 분리 감지 및 템포 오탐 방지)
        assertNotNull(result.girPercentage)
        assertEquals(27.8, result.girPercentage!!, 0.1)

        // 8. 라운드 성적 통계 집계 검증 (92타 = 파 36+36=72 대비 +20타)
        val round = GolfRound(
            clubName = result.clubName ?: "오크밸리 CC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = GolfType.FIELD,
            holeScores = result.holeScores,
            penaltyCount = result.penaltyCount ?: 0
        )
        val stats = round.getScorecardStats()
        assertNotNull(stats)
        assertEquals(92, result.holeScores.sum())
        assertEquals(2, stats!!.penaltyCount)
    }
}
