package com.autologue.app

import com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer
import com.autologue.app.domain.model.getDisplayClubName
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime

class GolfLockerSlipOcrAnalyzerTest {

    @Test
    fun parseKingsdaleLockerSlip_withBottomClubNameAnd8DigitDate() {
        // 사용자가 제공한 킹스데일 라커룸 영수증 OCR 텍스트
        val sampleText = """
            정선우 고객님
            54(남)
            예약고객: 김정진
            날  짜 : 20260907
            코  스 : Hill 0651
            ※ 과도한 문신 고객은 다른 분들의 불편을 초래할 수 있으니 탈의실이나 샤워실 이용시 가려주시기 바랍니다.
            ※ 비밀번호 4자리 설정 후 #버튼을 누르시면 잠김니다.
              예) 7777#
            ※ 라운드 종료 후 고객님의 골프백을 실어드리니 꼭 자동차키를 소지해 주시기 바랍니다.
            킹스데일(을)를 방문해 주셔서 감사합니다.
        """.trimIndent()

        val result = GolfLockerSlipOcrAnalyzer.parse(sampleText)

        // 1. 라커 슬립 판별
        assertTrue("라커 슬립으로 정상 판별되어야 함", result.isLockerSlip)

        // 2. 최하단 방문 감사 문구로부터 골프장명 정규화 추출
        assertEquals("킹스데일 GC", result.clubName)

        // 3. 라커 번호 및 성별
        assertEquals("54", result.lockerNumber)
        assertEquals("남", result.gender)

        // 4. 플레이어 이름 ('고객' 오탐 없이 '정선우' 추출)
        assertEquals("정선우", result.playerName)

        // 5. 구분자 없는 8자리 날짜 (20260907 -> 2026-09-07)
        assertEquals(LocalDate.of(2026, 9, 7), result.date)

        // 6. 코스명 및 4자리 시간 (Hill 0651 -> 코스: Hill, 티오프: 06:51)
        assertEquals("Hill", result.courseName)
        assertNotNull("티오프 시간이 추출되어야 함", result.teeOffTime)
        assertEquals(LocalTime.of(6, 51), result.teeOffTime)
    }

    @Test
    fun parseStandardLockerSlip_extractsCorrectFields() {
        val standardText = """
            스카이밸리 CC
            락카번호: 245
            Tee-Off: 07:45
            코스: 마운틴
            2026-05-20
            홍길동 님
            남성
        """.trimIndent()

        val result = GolfLockerSlipOcrAnalyzer.parse(standardText)

        assertTrue(result.isLockerSlip)
        assertEquals("스카이밸리 CC", result.clubName)
        assertEquals("245", result.lockerNumber)
        assertEquals(LocalTime.of(7, 45), result.teeOffTime)
        assertEquals("마운틴", result.courseName)
        assertEquals(LocalDate.of(2026, 5, 20), result.date)
        assertEquals("홍길동", result.playerName)
        assertEquals("남", result.gender)
    }

    @Test
    fun splitClubAndCourse_withVariousCourseFormats_preservesCourseName() {
        // 1. 괄호형
        val (c1, co1) = com.autologue.app.domain.model.splitClubAndCourse("스카이밸리 CC (마운틴 코스)")
        assertEquals("스카이밸리 CC", c1)
        assertEquals("마운틴 코스", co1)

        val (c2, co2) = com.autologue.app.domain.model.splitClubAndCourse("스카이밸리 CC (Hill)")
        assertEquals("스카이밸리 CC", c2)
        assertEquals("Hill 코스", co2)

        // 2. 공백 및 코스 접미사형
        val (c3, co3) = com.autologue.app.domain.model.splitClubAndCourse("아리지 CC 햇님 코스")
        assertEquals("아리지 CC", c3)
        assertEquals("햇님 코스", co3)

        // 3. CC 뒤 코스명 바로 인입형
        val (c4, co4) = com.autologue.app.domain.model.splitClubAndCourse("아리지 CC 햇님")
        assertEquals("아리지 CC", c4)
        assertEquals("햇님 코스", co4)

        // 4. 코스명 없는 일반 구장
        val (c5, co5) = com.autologue.app.domain.model.splitClubAndCourse("안양 CC")
        assertEquals("안양 CC", c5)
        assertEquals("", co5)
    }

    @Test
    fun extractCourseNameFromText_and_getDisplayClubName_safety() {
        val course1 = com.autologue.app.domain.model.extractCourseNameFromText("락커: 1234 / 코스: 마운틴")
        assertEquals("마운틴 코스", course1)

        val course2 = com.autologue.app.domain.model.extractCourseNameFromText("[코스: 잣나무 코스] 페어웨이 양호")
        assertEquals("잣나무 코스", course2)

        val round = com.autologue.app.domain.model.GolfRound(
            clubName = "오크밸리 CC",
            roundDate = java.time.LocalDateTime.of(2026, 9, 10, 7, 30),
            golfType = com.autologue.app.domain.model.GolfType.FIELD,
            memo = "락커: 54 / 코스: Hill"
        )
        val display = round.getDisplayClubName()
        assertEquals("오크밸리 CC (Hill 코스)", display)
    }

    @Test
    fun parseLockerSlip_withEnglishHeader_SkyValley() {
        val sampleText = """
            SKY VALLEY COUNTRY CLUB
            NO. 152
            GUEST : 홍길동
            COURSE : OUT / IN 07:20
            DATE : 2026-09-15
        """.trimIndent()

        val result = GolfLockerSlipOcrAnalyzer.parse(sampleText)

        assertTrue("영문 상단 라커 슬립이어야 함", result.isLockerSlip)
        assertEquals("스카이밸리 CC", result.clubName)
        assertEquals("152", result.lockerNumber)
        assertEquals("홍길동", result.playerName)
        assertEquals("OUT / IN", result.courseName)
        assertEquals(LocalTime.of(7, 20), result.teeOffTime)
        assertEquals(LocalDate.of(2026, 9, 15), result.date)
    }

    @Test
    fun parseLockerSlip_withEnglishBottomClubName_Ladena() {
        val sampleText = """
            회원명: 정선우
            라커번호: 88(남)
            T/O: 06:40
            코스: Lake
            2026-09-20
            WELCOME TO LADENA GOLF CLUB
            TEL : 033-260-1000
        """.trimIndent()

        val result = GolfLockerSlipOcrAnalyzer.parse(sampleText)

        assertTrue("하단 영문 환영 문구 라커 슬립이어야 함", result.isLockerSlip)
        assertEquals("라데나 GC", result.clubName)
        assertEquals("88", result.lockerNumber)
        assertEquals("남", result.gender)
        assertEquals("정선우", result.playerName)
        assertEquals("Lake", result.courseName)
        assertEquals(LocalTime.of(6, 40), result.teeOffTime)
        assertEquals(LocalDate.of(2026, 9, 20), result.date)
    }

    @Test
    fun parseLockerSlip_withBottomCorp_Philos() {
        val sampleText = """
            라커: 104
            시간: 07:15
            일자: 2026.09.12
            비밀번호 4자리를 설정해 주세요.
            (주)필로스
            경기도 포천시 일동면
        """.trimIndent()

        val result = GolfLockerSlipOcrAnalyzer.parse(sampleText)

        assertTrue("하단 법인명 라커 슬립이어야 함", result.isLockerSlip)
        assertEquals("필로스 CC", result.clubName)
        assertEquals("104", result.lockerNumber)
        assertEquals(LocalTime.of(7, 15), result.teeOffTime)
        assertEquals(LocalDate.of(2026, 9, 12), result.date)
    }

    @Test
    fun parseLockerSlip_withBottomThankYouNotice_BearCreek() {
        val sampleText = """
            락커: 215
            티오프: 08:00
            코스: Mountain
            2026-10-05
            저희 베어크리크 골프클럽을 찾아주셔서 감사합니다.
        """.trimIndent()

        val result = GolfLockerSlipOcrAnalyzer.parse(sampleText)

        assertTrue(result.isLockerSlip)
        assertEquals("베어크리크 GC", result.clubName)
        assertEquals("215", result.lockerNumber)
        assertEquals("Mountain", result.courseName)
        assertEquals(LocalTime.of(8, 0), result.teeOffTime)
    }

    @Test
    fun parseRestaurantReceipt_shouldBeRejected() {
        val sampleText = """
            김밥천국 역삼점
            주문번호: 12
            야채김밥 1 4,000
            라면 1 4,500
            합계금액 8,500
            2026-09-10 12:30:15
            감사합니다 또 찾아주세요
        """.trimIndent()

        val result = GolfLockerSlipOcrAnalyzer.parse(sampleText)

        org.junit.Assert.assertFalse("일반 식당 영수증은 골프 라커 슬립으로 오탐되지 않아야 함", result.isLockerSlip)
        assertEquals("일반 사진", result.clubName)
    }
}
