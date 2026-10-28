package com.autologue.app.data.sync

import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class PlaceResolverPoiTest {

    private val resolver = PlaceResolver()

    @Test
    fun selectBestPoiFromOverpassJson_combinesApartmentComplexWithGateAndMallParking() {
        val mainGateJson = """
            {
              "elements": [
                {
                  "type": "way",
                  "center": { "lat": 37.47855, "lon": 127.14660 },
                  "tags": {
                    "name": "위례더샵포레스트",
                    "building": "apartments",
                    "addr:quarter": "학암동"
                  }
                },
                {
                  "type": "node",
                  "lat": 37.47842,
                  "lon": 127.14642,
                  "tags": {
                    "barrier": "gate",
                    "entrance": "main",
                    "name": "정문 경비실"
                  }
                }
              ]
            }
        """.trimIndent()

        val gateResult = resolver.selectBestPoiFromOverpassJson(mainGateJson, 37.47840, 127.14640)
        assertNotNull(gateResult)
        assertEquals("위례더샵포레스트 정문", gateResult!!.placeName)
        assertEquals("학암동", gateResult.dongHint)

        val mallParkingJson = """
            {
              "elements": [
                {
                  "type": "way",
                  "center": { "lat": 37.47860, "lon": 127.14670 },
                  "tags": {
                    "name": "위례더샵포레스트",
                    "landuse": "residential"
                  }
                },
                {
                  "type": "way",
                  "center": { "lat": 37.47825, "lon": 127.14620 },
                  "tags": {
                    "building": "retail",
                    "name": "단지내상가"
                  }
                },
                {
                  "type": "node",
                  "lat": 37.47822,
                  "lon": 127.14618,
                  "tags": {
                    "amenity": "parking_entrance"
                  }
                }
              ]
            }
        """.trimIndent()

        val parkingResult = resolver.selectBestPoiFromOverpassJson(mallParkingJson, 37.47821, 127.14619)
        assertNotNull(parkingResult)
        assertEquals("위례더샵포레스트 상가주차장", parkingResult!!.placeName)
    }

    @Test
    fun selectBestPoiFromOverpassJson_handlesSubwayExitBusStopAndMajorLandmark() {
        val subwayJson = """
            {
              "elements": [
                {
                  "type": "node",
                  "lat": 37.51560,
                  "lon": 126.90740,
                  "tags": {
                    "railway": "station",
                    "station": "subway",
                    "name": "영등포(1호선)"
                  }
                },
                {
                  "type": "node",
                  "lat": 37.51595,
                  "lon": 126.90710,
                  "tags": {
                    "railway": "subway_entrance",
                    "ref": "5"
                  }
                }
              ]
            }
        """.trimIndent()

        val subwayResult = resolver.selectBestPoiFromOverpassJson(subwayJson, 37.51596, 126.90711)
        assertNotNull(subwayResult)
        assertEquals("영등포역 5번출구", subwayResult!!.placeName)

        val busStopJson = """
            {
              "elements": [
                {
                  "type": "node",
                  "lat": 37.47910,
                  "lon": 127.14520,
                  "tags": {
                    "highway": "bus_stop",
                    "name": "위례더샵포레스트 (28512)"
                  }
                }
              ]
            }
        """.trimIndent()

        val busResult = resolver.selectBestPoiFromOverpassJson(busStopJson, 37.47912, 127.14521)
        assertNotNull(busResult)
        assertEquals("위례더샵포레스트 정류장", busResult!!.placeName)

        val landmarkJson = """
            {
              "elements": [
                {
                  "type": "way",
                  "center": { "lat": 37.51710, "lon": 126.90320 },
                  "tags": {
                    "shop": "mall",
                    "name": "타임스퀘어"
                  }
                }
              ]
            }
        """.trimIndent()

        val landmarkResult = resolver.selectBestPoiFromOverpassJson(landmarkJson, 37.51705, 126.90325)
        assertNotNull(landmarkResult)
        assertEquals("타임스퀘어", landmarkResult!!.placeName)
    }

    @Test
    fun selectBestPoiFromOverpassJson_returnsNullWhenOnlyAdminOrNoNamedPoiExists() {
        val emptyPoiJson = """
            {
              "elements": [
                {
                  "type": "way",
                  "center": { "lat": 37.47800, "lon": 127.14600 },
                  "tags": {
                    "highway": "residential",
                    "name": "위례대로6길"
                  }
                }
              ]
            }
        """.trimIndent()

        val result = resolver.selectBestPoiFromOverpassJson(emptyPoiJson, 37.47800, 127.14600)
        assertNull(result)
    }

    @Test
    fun cleanDisplayPlaceName_prioritizesSpecificPlaceAndFallsBackToPureDong() {
        assertEquals("학암동", PlaceResolver.cleanDisplayPlaceName("하남시 학암동", "경기도 하남시 학암동 652"))
        assertEquals("영등포동", PlaceResolver.cleanDisplayPlaceName("서울 영등포구", "대한민국 서울특별시 영등포구 영등포동3가 2-21"))
        assertEquals("방이동", PlaceResolver.cleanDisplayPlaceName("서울 방이동", null))
        assertEquals("위례더샵포레스트 후문", PlaceResolver.cleanDisplayPlaceName("경기도 하남시 학암동 위례더샵포레스트 후문", null))
        assertEquals("학암동 정류장", PlaceResolver.cleanDisplayPlaceName("하남시 학암동 정류장", null))
        assertEquals("스타필드 하남", PlaceResolver.cleanDisplayPlaceName("스타필드 하남", "경기도 하남시 미사대로 750"))
    }

    @Test
    fun normalizeStepForDisplay_and_enrichStepPairDurations_replaceDuplicateKmWithDurationAndPoi() {
        val rawSteps = listOf(
            RouteStep(
                id = "dep_1",
                time = LocalDateTime.of(2026, 10, 22, 8, 10),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 승차 (출발)",
                locationName = "위례더샵포레스트 정문",
                address = "경기도 하남시 학암동 652",
                latitude = 37.4784,
                longitude = 127.1464,
                tags = listOf("차량주행", "승차", "출발지점")
            ),
            RouteStep(
                id = "arr_1",
                time = LocalDateTime.of(2026, 10, 22, 8, 56),
                stepType = RouteStepType.DRIVING,
                title = "⭐ [벤츠] 하차 (총 27.1 km)",
                locationName = "타임스퀘어",
                address = "서울특별시 영등포구 영중로 15",
                latitude = 37.5171,
                longitude = 126.9032,
                tags = listOf("차량주행", "하차", "도착지점")
            )
        )

        val normalized = DailyRouteAggregator.enrichStepPairDurations(
            rawSteps.map { DailyRouteAggregator.normalizeStepForDisplay(it) }
        )

        assertEquals("⭐ [벤츠] 승차 · 위례더샵포레스트 정문", normalized[0].title)
        assertEquals("⭐ [벤츠] 하차 · 타임스퀘어", normalized[1].title)
        assertFalse(normalized[1].title.contains("km"))
        assertFalse(normalized[1].title.contains("46분"))
        assertTrue(normalized[1].description?.contains("46분 운행") == true)
    }

    @Test
    fun home7101Dong_unitStripping_and_dualStationSplitting_workAsSpecified() {
        // 1. 7101동 = 집 표기 & 호수 표기 전면 금지
        assertEquals("집", PlaceResolver.cleanDisplayPlaceName("7101동"))
        assertEquals("집", PlaceResolver.cleanDisplayPlaceName("7101동 2502호"))
        assertEquals("집", PlaceResolver.cleanDisplayPlaceName("7101동 1604호"))
        assertEquals("집", PlaceResolver.cleanDisplayPlaceName("위례더샵포레스트 7101동 2502호"))
        assertEquals("집", PlaceResolver.cleanDisplayPlaceName("학암동", "경기도 하남시 학암동 7101동 2502호"))

        // 2. 타 건물/단지 세대 호수('2502호' 등) 배제 및 오표기 차단 (단, 9호선·2호점·호수공원 보존)
        assertFalse(PlaceResolver.isValidSpecificPlaceName("2502호"))
        assertFalse(PlaceResolver.isValidSpecificPlaceName("104동 2502호"))
        assertEquals("위례더샵포레스트", PlaceResolver.cleanDisplayPlaceName("위례더샵포레스트 2502호"))
        assertEquals("성심당 2호점", PlaceResolver.cleanDisplayPlaceName("성심당 2호점"))
        assertEquals("일산호수공원", PlaceResolver.cleanDisplayPlaceName("일산호수공원"))
        assertEquals("경기도 하남시 학암동 집", PlaceResolver.sanitizeAddressForDisplay("경기도 하남시 학암동 7101동 2502호"))

        // 3. 정거장/정류장 2곳 병기 시 구분자('.', ',', '/', '·', '|') 앞쪽 명칭만 표기 (C.C 골프장 약어는 보호)
        assertEquals("위례포레자이 정류장", PlaceResolver.cleanDisplayPlaceName("위례포레자이.위례더샵포레스트 정류장"))
        assertEquals("수서역 정류장", PlaceResolver.splitDualStationOrStopName("수서역,KT수서지점", forceStopSuffix = true))
        assertEquals("영등포역 정류소", PlaceResolver.cleanDisplayPlaceName("영등포역/타임스퀘어 정류소"))
        assertEquals("가락시장 정류장", PlaceResolver.cleanDisplayPlaceName("가락시장·가락시장역 버스정류장"))
        assertEquals("남촌C.C", PlaceResolver.cleanDisplayPlaceName("남촌C.C"))

        // 4. 하단 설명란 소요시간 검정색 강조 AnnotatedString 검증
        val annotated = com.autologue.app.presentation.common.buildHighlightedDurationText("1시간 15분 운행 · GPS 12개 지점")
        assertEquals("1시간 15분 운행 · GPS 12개 지점", annotated.text)
        assertEquals(1, annotated.spanStyles.size)
        assertEquals(0, annotated.spanStyles[0].start)
        assertEquals("1시간 15분 운행".length, annotated.spanStyles[0].end)
    }
}
