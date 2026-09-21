package com.autologue.app

import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.data.sync.PlaceResolver
import com.autologue.app.data.sync.ScannedPhoto
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import java.time.LocalDate

class DailyRouteAggregatorTest {

    private lateinit var placeResolver: PlaceResolver
    private lateinit var aggregator: DailyRouteAggregator

    @Before
    fun setUp() {
        placeResolver = PlaceResolver()
        aggregator = DailyRouteAggregator(placeResolver)
    }

    @Test
    fun isRealGolfClub_blocksMobileAppUiKeywords() {
        assertFalse(DailyRouteAggregator.isRealGolfClub("라운드 상세 기록 FIELD / 기록 삭제 취소 저장하기 CC"))
        assertFalse(DailyRouteAggregator.isRealGolfClub("기록 삭제 취소 저장하기"))
        assertFalse(DailyRouteAggregator.isRealGolfClub("라운드 상세 기록 FIELD"))
        assertFalse(DailyRouteAggregator.isRealGolfClub("필드 골프장"))
        assertFalse(DailyRouteAggregator.isRealGolfClub("일반 사진"))

        assertTrue(DailyRouteAggregator.isRealGolfClub("킹스데일 GC"))
        assertTrue(DailyRouteAggregator.isRealGolfClub("오크밸리 CC"))
        assertTrue(DailyRouteAggregator.isRealGolfClub("월송리 CC"))
        assertTrue(DailyRouteAggregator.isRealGolfClub("필로스 GC"))
    }

    @Test
    fun aggregateForDate_excludesPhotoRecordFromMovementSummary() {
        val targetDate = LocalDate.of(2026, 9, 21)

        // 10:46 스코어카드 사진 (위치 없음 -> "사진 기록")
        val photo1 = ScannedPhoto(
            uri = "content://media/1",
            time = targetDate.atTime(10, 46),
            latitude = null,
            longitude = null,
            placeName = "사진 기록"
        )
        // 19:36 문정동 사진 (위치 있음 -> "서울 문정동")
        val photo2 = ScannedPhoto(
            uri = "content://media/2",
            time = targetDate.atTime(19, 36),
            latitude = 37.4858,
            longitude = 127.1225,
            placeName = "서울 문정동"
        )

        val diary = aggregator.aggregateForDate(
            date = targetDate,
            photos = listOf(photo1, photo2),
            transactions = emptyList(),
            golfRounds = emptyList(),
            vehicleLogs = emptyList()
        )

        // "사진 기록"이 동선 체인에서 제외되고 유효 장소인 "서울 문정동"만 표출되는지 검증
        assertFalse(diary.movementSummary?.contains("사진 기록") == true)
        assertEquals("서울 문정동", diary.movementSummary)
        assertEquals("서울 문정동 일정", diary.title)
    }

    @Test
    fun aggregateForDate_golfWithZeroVehicleLog_fallsBackToStandardRoundTripDistance() {
        val targetDate = LocalDate.of(2026, 9, 21)

        val golfRound = GolfRound(
            clubName = "킹스데일 GC",
            roundDate = targetDate.atTime(7, 0),
            golfType = GolfType.FIELD,
            totalScore = 88
        )

        val diary = aggregator.aggregateForDate(
            date = targetDate,
            photos = emptyList(),
            transactions = emptyList(),
            golfRounds = listOf(golfRound),
            vehicleLogs = emptyList()
        )

        // 골프 라운드가 있을 때 0.0km가 아니라 킹스데일 표준 왕복거리 160.0km로 자동 연동 검증
        assertEquals(160.0, diary.drivingDistanceKm, 0.1)
        assertEquals("킹스데일 GC 라운딩", diary.title)
    }

    @Test
    fun aggregateForDate_golfAndFamilyPhotos_combinesCleanTitle() {
        val targetDate = LocalDate.of(2026, 9, 21)

        val golfRound = GolfRound(
            clubName = "킹스데일 GC",
            roundDate = targetDate.atTime(7, 0),
            golfType = GolfType.FIELD,
            totalScore = 88
        )

        val familyPhoto = ScannedPhoto(
            uri = "content://media/family",
            time = targetDate.atTime(19, 36),
            latitude = 37.4858,
            longitude = 127.1225,
            placeName = "서울 문정동"
        )

        val diary = aggregator.aggregateForDate(
            date = targetDate,
            photos = listOf(familyPhoto),
            transactions = emptyList(),
            golfRounds = listOf(golfRound),
            vehicleLogs = emptyList()
        )

        // 골프 일정과 문정동 일상이 조화롭게 결합된 타이틀 검증
        assertEquals("킹스데일 GC 라운딩 & 서울 문정동", diary.title)
        assertFalse(diary.movementSummary?.contains("사진 기록") == true)
        assertEquals("킹스데일 GC ➔ 서울 문정동", diary.movementSummary)
    }
}
