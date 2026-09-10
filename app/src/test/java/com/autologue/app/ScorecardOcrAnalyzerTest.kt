package com.autologue.app

import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class ScorecardOcrAnalyzerTest {

    @Test
    fun parseMobileSmartScorecard_extractsAccurateScoresAndPutts() {
        // 사용자가 제공한 킹스데일(Hill/Lake) 모바일 스코어카드 OCR 원문 텍스트
        val sampleText = """
            스코어카드
            91 (+19)
            SCORE
            38.9%
            GIR
            2.2
            홀당 평균 퍼트 수
            6066
            전체 걸음수
            
            Hill
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 5 4 3 4 3 4 4 5 36
            Score 4 9 5 3 6 4 4 7 6 48
            Putt 2 5 3 2 3 1 1 2 3 22
            Penalty - - - - - 1 - - - 1
            
            Lake
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par 4 4 3 4 4 5 4 3 5 36
            Score 4 4 6 4 6 5 4 4 6 43
            Putt 2 2 3 2 3 1 1 2 2 18
            Penalty - - 1 - 1 - - - - 2
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(sampleText)

        // 1. 총 타수 검증 (상단 SCORE 91 및 18홀 타수 합산 91)
        assertEquals(91, result.totalScore)

        // 2. 총 퍼트 수 검증 (전반 22 + 후반 18 = 40)
        assertEquals(40, result.totalPutts)

        // 3. 18홀 홀별 타수 검증
        val expectedHoles = listOf(4, 9, 5, 3, 6, 4, 4, 7, 6, 4, 4, 6, 4, 6, 5, 4, 4, 6)
        assertEquals(18, result.holeScores.size)
        assertEquals(expectedHoles, result.holeScores)

        // 4. 코스명 검증 (Hill / Lake)
        assertEquals("Hill / Lake", result.courseName)

        // 5. 부가 메트릭 검증
        assertNotNull(result.girPercentage)
        assertEquals(38.9, result.girPercentage!!, 0.01)
        assertEquals(6066, result.steps)
    }

    @Test
    fun parsePaperScorecard_extractsTotalAndPutts() {
        val paperText = """
            남촌 CC
            Tee-Off: 07:30
            TOTAL : 86
            PUTT : 32
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(paperText)

        assertEquals(86, result.totalScore)
        assertEquals(32, result.totalPutts)
    }

    @Test
    fun parseBlackThemeScorecard_extractsAllMetricsCorrectly() {
        // 필로스 GC 다크 테마 스코어카드 OCR 원문 텍스트 시뮬레이션
        val darkScorecardText = """
            스코어카드
            필로스 GC 2026.08.09
            86(+14) 55.6%
            SCORE GIR
            2.2 6384
            홀당 평균 퍼트 수 전체 걸음수
            
            West
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 3 5 4 3 4 5 4 4 36
            Score 5 4 7 3 4 5 6 4 4 42
            Putt 2 2 1 2 1 3 2 2 2 17
            Penalty - - 1 - 1 - - - - 2
            Tempo (Tee Shot) 3.0 - 3.2 3.4 - 3.3 2.7 2.9 3.1
            Dist. (Tee Shot) 192 - 139 291 - 186 187 220 222
            
            South
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par 4 4 5 3 4 5 4 3 4 36
            Score 5 5 6 6 4 6 4 4 4 44
            Putt 3 3 2 4 1 3 2 3 2 23
            Penalty - - - - - - - - - -
            Tempo (Tee Shot) 2.9 3.1 3.6 - 3.7 3.6 3.1 - 2.7
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(darkScorecardText)

        // 1. 총 타수 검증 (86)
        assertEquals(86, result.totalScore)

        // 2. 총 퍼트 수 검증 (전반 17 + 후반 23 = 40)
        assertEquals(40, result.totalPutts)

        // 3. 코스명 검증 (West / South)
        assertEquals("West / South", result.courseName)

        // 4. 골프장명 검증 (필로스 GC)
        assertEquals("필로스 GC", result.clubName)

        // 5. GIR 검증 (55.6%)
        assertNotNull(result.girPercentage)
        assertEquals(55.6, result.girPercentage!!, 0.01)

        // 6. 전체 걸음수 검증 (6384)
        assertEquals(6384, result.steps)

        // 7. 페널티 타수 검증 (2)
        assertEquals(2, result.penaltyCount)

        // 8. 파3 제외 티샷 평균 비거리 검증 (1437 / 7 = 205.2m)
        assertNotNull(result.averageDriveDistance)
        assertEquals(205.2, result.averageDriveDistance!!, 0.1)

        // 8-1. 최저(139m) 및 최고(291m) 제외 보정 평균 비거리 검증 (1007 / 5 = 201.4m)
        assertNotNull(result.adjustedDriveDistance)
        assertEquals(201.4, result.adjustedDriveDistance!!, 0.1)

        // 9. 평균 템포 검증 (West 7개 + South 7개 = 14개 합 43.6 / 14 = 3.1)
        assertNotNull(result.averageTempo)
        assertEquals(3.1, result.averageTempo!!, 0.1)

        // 10. 18홀 홀별 스코어 검증
        assertEquals(18, result.holeScores.size)
        assertEquals(listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4), result.holeScores)
    }

    @Test
    fun golfRound_formattedDriveDistance_displaysBothAverageAndAdjusted() {
        val round = com.autologue.app.domain.model.GolfRound(
            clubName = "필로스 GC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            averageDriveDistance = 205.2,
            adjustedDriveDistance = 201.4,
            driveDistances = listOf(192.0, 139.0, 291.0, 186.0, 187.0, 220.0, 222.0)
        )

        assertEquals("205.2m (201.4m)", round.getFormattedDriveDistance())
        assertEquals(201.4, round.getEffectiveAdjustedDriveDistance()!!, 0.01)
    }
}
