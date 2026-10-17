package com.autologue.app.data.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

/**
 * 골프 라커룸 전표(라커 번호표) 및 스코어카드지 OCR 분석기의 엄격 검증 단위 테스트.
 * - 웹사이트 이용후기 게시판(예: xgolf.com 최근 이용후기), 부킹 목록, 단일 구장 후기 등이
 *   절대 골프 필드 라운드로 오탐(False Positive)되지 않는지 검증합니다.
 * - 실제 라커룸 안내지/영수증과 실제 18홀 스코어카드지만 통과하는지 검증합니다.
 */
class GolfOcrStrictFilterTest {

    @Test
    fun `xgolf 최근 이용후기 게시판 캡처 화면은 라커 전표 및 스코어카드에서 모두 차단되어야 한다`() {
        val xgolfReviewBoardText = """
            https://www.xgolf.com/Main/main
            최근 이용후기
            이용후기는 라운드 종료 후 2개월 이내 작성 시 XGOLF 1,000P 적립 됩니다. 후기 작성
            골프장명 입력 검색
            골프장명 총평점 후기 제목 이용일자 개인만족도 작성자 등록일자
            아리지 ★9.0 (아리지) 역시 명문 구장 답네요 [1] 2026.04.12 ★ 9.0 | 조*현 2026.04.16
            88 ★9.5 (88) 역시는 역시네요 [1] 2026.04.12 ★ 9.0 | 김*곤 2026.04.16
            솔모로 ★9.0 (솔모로) 솔모로cc 라운딩 후기 [1] 2026.04.15 ★ 8.7 | 황*식 2026.04.16
            신라 ★8.8 (신라) 코스 관리 잘 되어있고 캐디님도 좋았습니다 2026.04.10 ★ 10.0 | 이*훈 2026.04.16
            남여주 ★8.8 (남여주) 간만에 남여주 좋았어요 2026.04.15 ★ 8.7 | 이*훈 2026.04.16
            사우스스프링스 ★9.1 (사우스스프링스) 그린스피드 실화 [1] 2026.04.14 ★ 9.7 | 김*원 2026.04.16
            발리오스 ★9.0 (발리오스) 명랑골프하기 좋은구장 2026.04.14 ★ 10.0 | 김*배 2026.04.16
            양평TPC ★8.7 (양평TPC) 그린이 많이 올라왔어요 2026.04.15 ★ 8.7 | 이*훈 2026.04.16
            양주 ★8.7 (양주) 봄날의 양주cc는 벚꽃맛집입니다 2026.04.11 ★ 9.3 | 안*철 2026.04.15
        """.trimIndent()

        val lockerInfo = GolfLockerSlipOcrAnalyzer.parse(xgolfReviewBoardText)
        assertFalse("웹사이트 이용후기 게시판은 라커룸 전표로 인식되면 안 됩니다.", lockerInfo.isLockerSlip)

        val scorecardResult = ScorecardOcrAnalyzer.parse(xgolfReviewBoardText)
        assertFalse(
            "웹사이트 이용후기 게시판은 진성 스코어카드로 인식되면 안 됩니다.",
            ScorecardOcrAnalyzer.isGenuineScorecardResult(scorecardResult, xgolfReviewBoardText)
        )
    }

    @Test
    fun `단일 골프장 블로그 리뷰나 코스 관리 후기 문구도 라커 전표로 오탐되지 않아야 한다`() {
        val singleReviewText = """
            신라CC 다녀왔습니다
            2026.04.10 08:20
            코스 관리 잘 되어있고 캐디님도 좋았습니다
            남여주보다 그린 상태가 훌륭하네요 88점 드립니다
        """.trimIndent()

        val lockerInfo = GolfLockerSlipOcrAnalyzer.parse(singleReviewText)
        assertFalse("단일 구장 리뷰/후기 문구는 라커룸 전표로 인식되면 안 됩니다.", lockerInfo.isLockerSlip)
    }

    @Test
    fun `실제 골프장 라커룸 안내지(영수증)는 정상적으로 라커 전표로 인식되어야 한다`() {
        val genuineLockerSlipText = """
            여주 신라 컨트리클럽
            입장안내증
            일자: 2026-04-12
            시간: 07:32
            코스: 동코스
            성명: 홍길동 귀하
            락카번호: (남) 145
        """.trimIndent()

        val lockerInfo = GolfLockerSlipOcrAnalyzer.parse(genuineLockerSlipText)
        assertTrue("실제 라커룸 안내지는 정상 인식되어야 합니다.", lockerInfo.isLockerSlip)
        assertEquals("145", lockerInfo.lockerNumber)
        assertEquals(LocalTime.of(7, 32), lockerInfo.teeOffTime)
    }

    @Test
    fun `실제 18홀 스코어카드 텍스트는 진성 스코어카드로 정상 인식되어야 한다`() {
        val genuineScorecardText = """
            남서울 CC 스코어카드
            2026.04.12
            HOLE 1 2 3 4 5 6 7 8 9 OUT
            PAR  4 4 3 5 4 4 3 5 4 36
            SCORE 5 4 3 5 5 4 4 5 4 39
            PUTT 2 2 1 2 2 2 2 2 2 17
            HOLE 10 11 12 13 14 15 16 17 18 IN TOTAL
            PAR  4  5  3  4  4  4  3  5  4  36 72
            SCORE 4 5  4  4  5  4  3  5  4  38 77
            PUTT 2  2  2  1  2  2  1  2  2  16 33
        """.trimIndent()

        val scorecardResult = ScorecardOcrAnalyzer.parse(genuineScorecardText)
        assertTrue(
            "실제 18홀 스코어카드는 진성 스코어카드로 판정되어야 합니다.",
            ScorecardOcrAnalyzer.isGenuineScorecardResult(scorecardResult, genuineScorecardText)
        )
        assertEquals(77, scorecardResult.totalScore)
    }
}
