package com.autologue.app

import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.data.sync.PlaceResolver
import com.autologue.app.data.sync.ScannedPhoto
import com.autologue.app.domain.model.RouteStepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate
import java.time.LocalDateTime

class ScreenshotFilterTest {

    @Test
    fun testIsScreenshot_detectsVariousScreenshotFileNames() {
        // 삼성 갤럭시 스크린샷 포맷
        assertTrue(HistoricalDataImporter.isScreenshot("Screenshot_20260910_134200.png"))
        assertTrue(HistoricalDataImporter.isScreenshot("Screenshot_20260910_134200.jpg"))
        assertTrue(HistoricalDataImporter.isScreenshot("screenshot_2026-09-10.png"))

        // 한글 OS 스크린샷 포맷
        assertTrue(HistoricalDataImporter.isScreenshot("스크린샷_20260910_134200.png"))
        assertTrue(HistoricalDataImporter.isScreenshot("스크린샷 2026-09-10.jpg"))

        // 캡처 및 Screen 프리픽스
        assertTrue(HistoricalDataImporter.isScreenshot("Capture_20260910.png"))
        assertTrue(HistoricalDataImporter.isScreenshot("screen_capture_123.jpg"))
        assertTrue(HistoricalDataImporter.isScreenshot("SmartScore_Screenshot.png"))

        // 일반 카메라 촬영 사진 (제외 대상 아님)
        assertFalse(HistoricalDataImporter.isScreenshot("20260910_134200.jpg"))
        assertFalse(HistoricalDataImporter.isScreenshot("IMG_20260910_134200.jpg"))
        assertFalse(HistoricalDataImporter.isScreenshot("golf_round_photo.jpg"))
        assertFalse(HistoricalDataImporter.isScreenshot("PXL_20260910_044200.jpg"))
    }

    @Test
    fun testIsScreenshot_detectsScreenshotDirectories() {
        // 안드로이드 표준 스크린샷 저장 폴더
        assertTrue(HistoricalDataImporter.isScreenshot("sample.jpg", relativePath = "Pictures/Screenshots/"))
        assertTrue(HistoricalDataImporter.isScreenshot("photo.png", dataPath = "/storage/emulated/0/DCIM/Screenshots/photo.png"))
        assertTrue(HistoricalDataImporter.isScreenshot("image.jpg", dataPath = "/storage/emulated/0/DCIM/스크린샷/image.jpg"))
        assertTrue(HistoricalDataImporter.isScreenshot("image.jpg", relativePath = "DCIM/화면캡처/"))

        // 일반 카메라 폴더 (제외 대상 아님)
        assertFalse(HistoricalDataImporter.isScreenshot("photo.jpg", relativePath = "DCIM/Camera/"))
        assertFalse(HistoricalDataImporter.isScreenshot("photo.jpg", dataPath = "/storage/emulated/0/DCIM/Camera/photo.jpg"))
    }

    @Test
    fun testIsScreenshot_respectsSystemFlag() {
        // Android 14+ IS_SCREENSHOT 플래그
        assertTrue(HistoricalDataImporter.isScreenshot("custom_name.jpg", isScreenshotFlag = true))
    }

    @Test
    fun testResolvePhotoLocation_returnsNullWhenNoExifGps() {
        val placeResolver = PlaceResolver()

        // GPS 메타데이터가 없는 스트림(예: 스크린샷)인 경우 서울 방이동이 아닌 null 반환
        val result = placeResolver.resolvePhotoLocation(streamProvider = { null })
        assertNull("EXIF 위치 정보가 없는 사진은 null을 반환해야 합니다 (서울 방이동 오염 방지)", result)
    }

    @Test
    fun testDailyRouteAggregator_doesNotContainBangyiFallbackForPhotoCluster() {
        val placeResolver = PlaceResolver()
        val aggregator = DailyRouteAggregator(placeResolver)

        val targetDate = LocalDate.of(2026, 9, 10)
        val photosWithoutGps = listOf(
            ScannedPhoto(
                uri = "content://media/external/images/media/1001",
                time = LocalDateTime.of(2026, 9, 10, 13, 42),
                placeName = null,
                address = null,
                latitude = null,
                longitude = null
            )
        )

        val diaryEntry = aggregator.aggregateForDate(
            date = targetDate,
            photos = photosWithoutGps,
            transactions = emptyList()
        )

        val photoStep = diaryEntry.routeSteps.firstOrNull { it.stepType == RouteStepType.PHOTO }
        if (photoStep != null) {
            assertNotEquals("서울 방이동", photoStep.title)
            assertNotEquals("서울 방이동", photoStep.locationName)
            assertNotEquals("서울특별시 송파구 방이동", photoStep.address)
            assertNull(photoStep.latitude)
            assertNull(photoStep.longitude)
        }
        assertNotEquals("서울 방이동", diaryEntry.placeName)
        assertNotEquals("서울 방이동 일정", diaryEntry.title)
    }
}
