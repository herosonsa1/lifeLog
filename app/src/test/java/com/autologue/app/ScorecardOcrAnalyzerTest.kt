package com.autologue.app

import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import com.autologue.app.domain.model.getScorecardStats
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

        // 6. 벌타 검증 (Hill 1 + Lake 2 = 총 3벌타)
        assertEquals(3, result.penaltyCount)

        // 7. 18홀 성적 집계 검증 (버디 0개, 파 8개, 보기 5개, 더블+ 5개 100% 일치)
        val round = com.autologue.app.domain.model.GolfRound(
            clubName = "킹스데일 GC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            holeScores = result.holeScores,
            holePars = result.holePars,
            penaltyCount = result.penaltyCount
        )
        val stats = round.getScorecardStats()
        assertNotNull(stats)
        assertEquals(0, stats!!.birdieCount)
        assertEquals(8, stats.parCount)
        assertEquals(5, stats.bogeyCount)
        assertEquals(5, stats.doublePlusCount)
        assertEquals(3, stats.penaltyCount)
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
    fun parseBlackThemeScorecard_withSplitLines_extractsAllMetricsCorrectly() {
        // 모바일 OCR에서 라벨과 수치가 줄바꿈 분리된 실제 레이아웃 시뮬레이션
        val splitScorecardText = """
            스코어카드
            필로스 GC 2026.08.09
            86(+14)
            SCORE
            55.6%
            GIR
            2.2
            홀당 평균 퍼트 수
            6384
            전체 걸음 수
            
            West
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par
            4 3 5 4 3 4 5 4 4 36
            Score
            5 4 7 3 4 5 6 4 4 42
            Putt
            2 2 1 2 1 3 2 2 2 17
            Penalty
            - - 1 - 1 - - - - 2
            GIR
            Tempo
            (Tee Shot)
            3.0 - 3.2 3.4 - 3.3 2.7 2.9 3.1
            Dist.
            (Tee Shot)
            192 - 139 291 - 186 187 220 222
            
            South
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par
            4 4 5 3 4 5 4 3 4 36
            Score
            5 5 6 6 4 6 4 4 4 44
            Putt
            3 3 2 4 1 3 2 3 2 23
            Penalty
            - - - - - - - - - -
            GIR
            Tempo
            (Tee Shot)
            2.9 3.1 3.6 - 3.7 3.6 3.1 - 2.7
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(splitScorecardText)

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

        // 6. 전체 걸음수 검증 (6384 - "전체 걸음 수" 공백 처리 및 줄바꿈 지원)
        assertEquals(6384, result.steps)

        // 7. 페널티 타수 검증 (West 2 + South 0 = 2)
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

    @Test
    fun parseScorecard_withMergedHoles_prevents54Misdetection_andRecovers86Total() {
        // 1번 홀 선택선 등으로 1-2번 홀이 "54"로 뭉쳐서 인식된 사용자 실제 케이스 시뮬레이션
        val mergedSampleText = """
            스코어카드
            필로스 GC 2026.08.09
            86(+14) 55.6%
            SCORE GIR
            2.2 6384
            홀당 평균 퍼트 수 전체 걸음수
            
            West
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 3 5 4 3 4 5 4 4 36
            Score 54 7 3 4 5 6 4 4 42
            Putt 2 2 1 2 1 3 2 2 2 17
            Penalty - - 1 - 1 - - - - 2
            
            South
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par 4 4 5 3 4 5 4 3 4 36
            Score 5 5 6 6 4 6 4 4 4 44
            Putt 3 3 2 4 1 3 2 3 2 23
            Penalty - - - - - - - - - -
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(mergedSampleText)

        // 1. "54"가 총 타수로 오탐되지 않고 정확히 86타로 파싱되는지 검증
        assertEquals(86, result.totalScore)

        // 2. "54"가 5타, 4타로 분리되어 18홀 홀별 스코어가 모두 복원되었는지 검증
        assertEquals(18, result.holeScores.size)
        assertEquals(listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4), result.holeScores)

        // 3. 페널티 2타 정상 파싱 검증
        assertEquals(2, result.penaltyCount)

        // 4. 걸음수 6384 정상 파싱 검증
        assertEquals(6384, result.steps)
    }

    @Test
    fun getScorecardStats_calculatesBirdieParBogeyDoubleCorrectly() {
        val round = com.autologue.app.domain.model.GolfRound(
            clubName = "필로스 GC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            holeScores = listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4),
            penaltyCount = 2
        )

        val stats = round.getScorecardStats()
        assertNotNull(stats)
        assertEquals(1, stats!!.birdieCount)     // 4번 홀 3타 (Par 4 -> 버디)
        assertEquals(5, stats.parCount)          // West 8,9 및 South 14,16,18
        assertEquals(10, stats.bogeyCount)       // 보기 10개
        assertEquals(2, stats.doublePlusCount)   // West 3번 더블 + South 13번 트리플
        assertEquals(2, stats.penaltyCount)      // 벌타 2타
    }

    @Test
    fun numberCommaVisualTransformation_formatsNumberWithCommas() {
        val transformation = com.autologue.app.presentation.common.NumberCommaVisualTransformation()

        val input = androidx.compose.ui.text.AnnotatedString("6384")
        val transformed = transformation.filter(input)
        assertEquals("6,384", transformed.text.text)

        val largeInput = androidx.compose.ui.text.AnnotatedString("1234567")
        val largeTransformed = transformation.filter(largeInput)
        assertEquals("1,234,567", largeTransformed.text.text)

        val emptyInput = androidx.compose.ui.text.AnnotatedString("")
        val emptyTransformed = transformation.filter(emptyInput)
        assertEquals("", emptyTransformed.text.text)
    }

    @Test
    fun parseScorecard_withPinkSelectionBoxOnHole1_deducesHole1AndRecoversFull18Holes() {
        // 실제 필로스 GC에서 1번 홀 선택선으로 인해 1번 홀 스코어(5타)가 별도 분리되어
        // West 스코어 줄에 8개 홀 타수 + Total(42)만 인식된 실제 시뮬레이션
        val rawTextWithSeparatedHole1 = """
            스코어카드
            필로스 GC 2026.08.09
            86(+14) 55.6%
            SCORE GIR
            2.2 6384
            홀당 평균 퍼트 수 전체 걸음수
            
            West
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 3 5 4 3 4 5 4 4 36
            Score 4 7 3 4 5 6 4 4 42
            Putt 2 2 1 2 1 3 2 2 2 17
            Penalty - - 1 - 1 - - - - 2
            
            닫기^
            
            South
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par 4 4 5 3 4 5 4 3 4 36
            Score 5 5 6 6 4 6 4 4 4 44
            Putt 3 3 2 4 1 3 2 3 2 23
            Penalty - - - - - - - - - -
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(rawTextWithSeparatedHole1)

        // 1. 총 타수 86타 확정 (전반 42 + 후반 44 = 86)
        assertEquals(86, result.totalScore)

        // 2. 총 퍼트 수 40개 확정 (전반 17 + 후반 23 = 40)
        assertEquals(40, result.totalPutts)

        // 3. 18홀 전체 스코어 복원 (1번 홀 42 - 37 = 5타 수학적 역산 포함)
        val expectedScores = listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4)
        assertEquals(18, result.holeScores.size)
        assertEquals(expectedScores, result.holeScores)

        // 4. 코스명 종합
        assertEquals("West / South", result.courseName)

        // 5. 스코어 성적 집계 (버디 1, 파 5, 보기 10, 더블+ 2, 벌타 2)
        val stats = com.autologue.app.domain.model.GolfRound(
            clubName = result.clubName ?: "필로스 GC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            holeScores = result.holeScores,
            penaltyCount = result.penaltyCount
        ).getScorecardStats()

        assertNotNull(stats)
        assertEquals(1, stats!!.birdieCount)
        assertEquals(5, stats.parCount)
        assertEquals(10, stats.bogeyCount)
        assertEquals(2, stats.doublePlusCount)
        assertEquals(2, stats.penaltyCount)
    }

    @Test
    fun parseScorecard_preventsParRowMisdetectionAsScoreAndPutt() {
        // Score 행의 인식이 불안정하여 Par 행만 온전히 읽혔을 때,
        // Par 행(36)이 스코어나 퍼트로 오탐되지 않도록 차단하는 테스트
        val textWithParOnly = """
            스코어카드
            필로스 GC
            86(+14) SCORE
            2.2 홀당 평균 퍼트 수
            
            West
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 3 5 4 3 4 5 4 4 36
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(textWithParOnly)

        // 총 타수는 상단 대형 스코어 86으로 보장되며 36이 되지 않음
        assertEquals(86, result.totalScore)
        // Par 행이 스코어로 채택되지 않아 holeScores는 빈 리스트임
        assertEquals(0, result.holeScores.size)
        // 퍼트 역시 36으로 채택되지 않고 2.2 * 18 = 40 (또는 null)으로 안전 보장
        assertEquals(40, result.totalPutts)
    }

    @Test
    fun parseScorecard_mergesSpatialAndRawIntelligently() {
        // 9홀만 파싱되고 36타로 잘못 계산된 spatial 결과
        val badSpatialResult = com.autologue.app.data.ocr.ScorecardOcrResult(
            totalScore = 36,
            totalPutts = 36,
            holeScores = listOf(4, 3, 5, 4, 3, 4, 5, 4, 4),
            holePars = listOf(4, 3, 5, 4, 3, 4, 5, 4, 4),
            courseName = "West",
            recognizedRawText = "spatial"
        )

        // 18홀 전체와 86타를 온전히 복원한 raw 결과
        val goodRawResult = com.autologue.app.data.ocr.ScorecardOcrResult(
            totalScore = 86,
            totalPutts = 40,
            holeScores = listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4),
            holePars = listOf(4, 3, 5, 4, 3, 4, 5, 4, 4, 4, 4, 5, 3, 4, 5, 4, 3, 4),
            courseName = "West / South",
            recognizedRawText = "raw"
        )

        val merged = ScorecardOcrAnalyzer.mergeResults(badSpatialResult, goodRawResult, "raw")

        // 18홀과 86타, 40퍼트, West / South가 온전히 채택되었는지 검증
        assertEquals(86, merged.totalScore)
        assertEquals(40, merged.totalPutts)
        assertEquals(18, merged.holeScores.size)
        assertEquals("West / South", merged.courseName)
    }

    @Test
    fun parseScorecard_withColumnStyleRawText_recoversScoresAccurately() {
        // ML Kit가 라벨 열을 먼저 읽고, 그 뒤에 숫자들을 따로 읽은 파편화된 원문 시뮬레이션
        val columnFragmentedText = """
            스코어카드
            필로스 GC 2026.08.09
            86(+14) SCORE
            55.6% GIR
            2.2 홀당 평균 퍼트 수
            6384 전체 걸음 수
            
            West
            HOLE
            Par
            Score
            Putt
            Penalty
            4 3 5 4 3 4 5 4 4 36
            2 2 1 2 1 3 2 2 2 17
            - - 1 - 1 - - - - 2
            5 4 7 3 4 5 6 4 4 42
            
            South
            HOLE
            Par
            Score
            Putt
            Penalty
            4 4 5 3 4 5 4 3 4 36
            3 3 2 4 1 3 2 3 2 23
            - - - - - - - - - -
            5 5 6 6 4 6 4 4 4 44
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(columnFragmentedText)

        // 1. 총 타수 86타
        assertEquals(86, result.totalScore)
        // 2. 총 퍼트 수 40개
        assertEquals(40, result.totalPutts)
        // 3. 18홀 전체 복원
        assertEquals(18, result.holeScores.size)
        assertEquals(listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4), result.holeScores)
    }

    @Test
    fun getScorecardStats_prioritizesHoleScoresOverOldMemoTag() {
        // 메모에 이전 버그 때의 잘못된 태그([통계: 버디 0, 파 9, 보기 0, 더블+ 0])가 남아있더라도,
        // holeScores가 있으면 실제 스코어를 최우선 계산하는지 검증
        val round = com.autologue.app.domain.model.GolfRound(
            clubName = "필로스 GC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            memo = "코스: West / South / [통계: 버디 0, 파 9, 보기 0, 더블+ 0]",
            holeScores = listOf(5, 4, 7, 3, 4, 5, 6, 4, 4, 5, 5, 6, 6, 4, 6, 4, 4, 4),
            penaltyCount = 2
        )

        val stats = round.getScorecardStats()
        assertNotNull(stats)
        // 메모의 "버디 0, 파 9"에 오염되지 않고, 실제 스코어 기반 버디 1, 파 5, 보기 10, 더블+ 2가 반환되어야 함!
        assertEquals(1, stats!!.birdieCount)
        assertEquals(5, stats.parCount)
        assertEquals(10, stats.bogeyCount)
        assertEquals(2, stats.doublePlusCount)
        assertEquals(2, stats.penaltyCount)
    }

    @Test
    fun parseOakValleyScorecard_extracts18HolesPineCherryAndAllMetrics() {
        // 사용자 실제 오크밸리 CC 모바일 스코어카드 (Pine / Cherry 18홀)
        val oakValleyRawText = """
            스코어카드
            오크밸리 CC / 2026.09.11
            92(+20) 27.8%
            SCORE GIR
            2.1 6983
            홀당 평균 퍼트 수 전체 걸음수
            
            Pine
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 4 3 4 5 4 3 4 5 36
            Score 4 8 3 5 5 6 4 7 6 48
            Putt 1 3 1 2 2 3 2 3 3 20
            Penalty - 1 - - - 1 - - - 2
            GIR
            Tempo (Tee Shot) 3.0 3.0 - 3.0 3.1 2.8 - 3.8 3.1
            Dist. (Tee Shot) 167 222 - 239 241 182 - 209 199
            
            Cherry
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par 4 3 4 4 4 3 5 4 5 36
            Score 5 3 5 4 6 4 5 6 6 44
            Putt 2 2 3 1 3 2 2 1 2 18
            Penalty - - - - - - - 1 - 1
            GIR
            Tempo (Tee Shot) 3.3 - 3.0 3.2 3.1 - 2.6 3.3 2.8
            Dist. (Tee Shot) 262 - 229 195 196 - 202 187 179
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(oakValleyRawText)

        // 1. 총 타수 검증 (상단 SCORE 92 및 18홀 전후반 합산 48 + 44 = 92)
        assertEquals(92, result.totalScore)

        // 2. 총 퍼트수 검증 (전반 20 + 후반 18 = 38)
        assertEquals(38, result.totalPutts)

        // 3. 18홀 홀별 타수 검증
        val expectedHoles = listOf(4, 8, 3, 5, 5, 6, 4, 7, 6, 5, 3, 5, 4, 6, 4, 5, 6, 6)
        assertEquals(18, result.holeScores.size)
        assertEquals(expectedHoles, result.holeScores)

        // 4. 코스명 검증 (Pine / Cherry)
        assertEquals("Pine / Cherry", result.courseName)

        // 5. 골프장명 검증 (오크밸리 CC)
        assertEquals("오크밸리 CC", result.clubName)

        // 6. 경기 일자 검증 (2026.09.11)
        assertNotNull(result.playDate)
        assertEquals(java.time.LocalDate.of(2026, 9, 11), result.playDate)

        // 7. GIR 검증 (27.8%)
        assertNotNull(result.girPercentage)
        assertEquals(27.8, result.girPercentage!!, 0.01)

        // 8. 전체 걸음수 검증 (6983)
        assertEquals(6983, result.steps)

        // 9. 총 페널티 검증 (전반 2 + 후반 1 = 3)
        assertEquals(3, result.penaltyCount)

        // 10. [핵심 검증] 18홀 홀별 Par 배열 검증 (Pine 9홀 + Cherry 9홀)
        val expectedPars = listOf(4, 4, 3, 4, 5, 4, 3, 4, 5, 4, 3, 4, 4, 4, 3, 5, 4, 5)
        assertEquals(18, result.holePars.size)
        assertEquals(expectedPars, result.holePars)

        // 11. [핵심 검증] GolfRound.getScorecardStats()가 실제 holePars를 기반으로 정확히 계산하는지 검증
        // 오크밸리 스코어: [4, 8, 3, 5, 5, 6, 4, 7, 6, 5, 3, 5, 4, 6, 4, 5, 6, 6]
        // 오크밸리 파:    [4, 4, 3, 4, 5, 4, 3, 4, 5, 4, 3, 4, 4, 4, 3, 5, 4, 5]
        // 차이:          [0, +4, 0, +1, 0, +2, +1, +3, +1, +1, 0, +1, 0, +2, +1, 0, +2, +1]
        // -> 버디: 0, 파: 6, 보기: 7, 더블+: 5, 페널티: 3
        val oakValleyRound = com.autologue.app.domain.model.GolfRound(
            clubName = result.clubName ?: "오크밸리 CC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            holeScores = result.holeScores,
            holePars = result.holePars,
            penaltyCount = result.penaltyCount
        )
        val stats = oakValleyRound.getScorecardStats()
        assertNotNull(stats)
        assertEquals(0, stats!!.birdieCount)      // 버디 0개 (표준 파로 계산 시 3번 홀 파5 오탐으로 버디가 잘못 나옴)
        assertEquals(6, stats.parCount)         // 파 6개 (1, 3, 5, 11, 13, 16)
        assertEquals(7, stats.bogeyCount)       // 보기 7개 (4, 7, 9, 10, 12, 15, 18)
        assertEquals(5, stats.doublePlusCount)  // 더블+ 5개 (2, 6, 8, 14, 17)
        assertEquals(3, stats.penaltyCount)     // 벌타 3타
    }

    @Test
    fun parse_medicalReceipt_rejectedAsGolfScorecard() {
        val medicalReceiptText = """
            외래 진료비 계산서 영수증
            항목별 설명 일반사항 안내
            환자성명: 홍길동
            진료과: 내과
            급여 본인부담금: 15,000원
            비급여: 24,000원
            합계금액: 39,000원
            수납금액: 39,000원
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(medicalReceiptText)

        // 의료 영수증은 네거티브 필터로 인해 골프 점수로 파싱되지 않고 null/empty 반환되어야 함
        org.junit.Assert.assertNull(result.totalScore)
        org.junit.Assert.assertTrue(result.holeScores.isEmpty())
        org.junit.Assert.assertNull(result.courseName)
    }

    @Test
    fun hasMedicalOrReceiptNegative_detectsMedicalKeywords() {
        org.junit.Assert.assertTrue(ScorecardOcrAnalyzer.hasMedicalOrReceiptNegative("외래 진료비 계산서 영수증"))
        org.junit.Assert.assertTrue(ScorecardOcrAnalyzer.hasMedicalOrReceiptNegative("약국 처방전 조제료"))
        org.junit.Assert.assertTrue(ScorecardOcrAnalyzer.hasMedicalOrReceiptNegative("병원 수납 영수증"))
        org.junit.Assert.assertFalse(ScorecardOcrAnalyzer.hasMedicalOrReceiptNegative("오크밸리 CC 2026.09.11 Pine / Cherry SCORE 92"))
    }

    @Test
    fun parseKingsdaleScorecard_withSplitPenaltyAndPars_extracts3PenaltiesAnd0Birdies() {
        val splitText = """
            스코어카드
            킹스데일 GC 2026.09.07
            91(+19) 38.9%
            SCORE GIR
            2.2 6066
            홀당 평균 퍼트 수 전체 걸음수
            
            Hill
            HOLE
            1 2 3 4 5 6 7 8 9 Total
            Par
            4 5 4 3 4 3 4 4 5 36
            Score
            4 9 5 3 6 4 4 7 6 48
            Putt
            2 5 3 2 3 1 1 2 3 22
            Penalty
            -
            - - - - 1 - - - 1
            
            Lake
            HOLE
            10 11 12 13 14 15 16 17 18 Total
            Par
            4 4 3 4 4 5 4 3 5 36
            Score
            4 4 6 4 6 5 4 4 6 43
            Putt
            2 2 3 2 3 1 1 2 2 18
            Penalty
            - - 1 - 1 - - - - 2
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(splitText)

        assertEquals(91, result.totalScore)
        assertEquals(40, result.totalPutts)
        assertEquals(3, result.penaltyCount) // Hill 1 + Lake 2 = 3벌타

        val round = com.autologue.app.domain.model.GolfRound(
            clubName = "킹스데일 GC",
            roundDate = java.time.LocalDateTime.now(),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            holeScores = result.holeScores,
            holePars = result.holePars,
            penaltyCount = result.penaltyCount
        )
        val stats = round.getScorecardStats()
        assertNotNull(stats)
        assertEquals(0, stats!!.birdieCount)
        assertEquals(8, stats.parCount)
        assertEquals(5, stats.bogeyCount)
        assertEquals(5, stats.doublePlusCount)
        assertEquals(3, stats.penaltyCount)
    }

    @Test
    fun parseDate_extracts2026_09_11_withoutDayCorruption() {
        val oakSampleText = """
            스코어카드
            오크밸리 CC / 2026.09.11(금)
            92 (+20)
            SCORE
            27.8%
            GIR
            2.1
            홀당 평균 퍼트 수
            6983
            전체 걸음수
            
            Pine
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 4 3 4 5 4 3 4 5 36
            Score 4 8 3 5 5 6 4 7 6 48
            Putt 2 2 1 3 2 2 1 2 2 17
            Penalty - - - - - - - - 2 2
            
            Cherry
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par 4 3 4 4 4 3 5 4 5 36
            Score 5 3 5 4 6 4 5 6 6 44
            Putt 2 2 2 2 3 1 3 2 4 21
            Penalty - - - - - - - - - -
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(oakSampleText)

        // 1. 날짜가 9.01이 아닌 2026-09-11로 정확히 파싱되는지 검증
        assertNotNull(result.playDate)
        assertEquals(java.time.LocalDate.of(2026, 9, 11), result.playDate)

        // 2. 구장명 "오크밸리 CC" 검증
        assertEquals("오크밸리 CC", result.clubName)

        // 3. 코스명 "Pine / Cherry" 검증
        assertEquals("Pine / Cherry", result.courseName)

        // 4. 총 타수 92타 검증
        assertEquals(92, result.totalScore)

        // 5. 총 퍼트수 38개 검증 (전반 17 + 후반 21)
        assertEquals(38, result.totalPutts)

        // 6. 벌타 2개 검증
        assertEquals(2, result.penaltyCount)
    }

    @Test
    fun parseMobileUiText_doesNotCorruptCourseOrClubName() {
        // 사용자가 업로드한 모바일 앱 화면의 UI 텍스트가 섞인 스코어카드 시뮬레이션
        val corruptedSampleText = """
            라운드 상세 기록
            FIELD
            88
            SCORE
            44.4%
            GIR
            2.1
            홀당 평균 퍼트 수
            5500
            전체 걸음수
            
            HOLE 1 2 3 4 5 6 7 8 9 Total
            Par 4 4 3 4 5 4 3 4 5 36
            Score 5 5 4 5 6 5 4 5 5 44
            Putt 2 2 2 2 2 2 2 2 3 19
            
            기록 삭제 취소 저장하기
            HOLE 10 11 12 13 14 15 16 17 18 Total
            Par 4 4 5 3 4 4 3 5 4 36
            Score 5 5 6 4 5 5 4 5 5 44
            Putt 2 2 2 2 2 2 2 2 3 19
        """.trimIndent()

        val result = ScorecardOcrAnalyzer.parse(corruptedSampleText)

        // 1. 총 타수 88타 정상 인식 검증
        assertEquals(88, result.totalScore)

        // 2. UI 버튼/라벨 텍스트("라운드 상세 기록 FIELD", "기록 삭제 취소 저장하기")가 코스명으로 채택되지 않고 null/정상 처리되는지 검증
        org.junit.Assert.assertNotEquals("라운드 상세 기록 FIELD / 기록 삭제 취소 저장하기", result.courseName)
        org.junit.Assert.assertTrue(
            result.courseName == null || !ScorecardOcrAnalyzer.containsMobileAppUiKeyword(result.courseName!!)
        )

        // 3. 골프장명도 비정상 UI 텍스트가 채택되지 않았는지 검증
        org.junit.Assert.assertTrue(
            result.clubName == null || !ScorecardOcrAnalyzer.containsMobileAppUiKeyword(result.clubName!!)
        )
    }
}

