package com.autologue.app.data.sync

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceResolver internal constructor(
    private val context: Context?,
    @Suppress("UNUSED_PARAMETER") dummy: Unit
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(context, Unit)

    /** 단위 테스트(JVM) 전용 무인자 생성자 */
    constructor() : this(null, Unit)

    /**
     * [M-01] geoCache 크기 제한 적용 — 무제한 ConcurrentHashMap 대신 LruCache 래퍼 사용.
     * 장기 사용 시 수천~수만 개 좌표 항목이 영구 축적되어 OOM이 발생하는 것을 방지합니다.
     * 최대 1000개 항목 초과 시 가장 오래된 항목부터 자동 eviction (LRU 정책).
     */
    private val geoCache: MutableMap<String, ResolvedLocation> = object : LinkedHashMap<String, ResolvedLocation>(
        16, 0.75f, true // accessOrder=true → LRU 순서 유지
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedLocation>?) =
            size > MAX_CACHE_SIZE
    }.let { lruMap ->
        // ConcurrentHashMap으로 래핑하여 thread-safety 보장
        java.util.Collections.synchronizedMap(lruMap)
    }

    data class ResolvedLocation(
        val placeName: String,
        val address: String,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    private val KNOWN_PLACES = mapOf(
        "하늘공원" to ResolvedLocation("상암동 하늘공원", "서울특별시 마포구 하늘공원로 95 (상암동)", 37.5684, 126.8972),
        "월드컵공원" to ResolvedLocation("상암동 월드컵공원", "서울특별시 마포구 월드컵로 240", 37.5682, 126.8985),
        "난지천공원" to ResolvedLocation("난지천공원", "서울특별시 마포구 상암동 487-355", 37.5710, 126.8920),
        "평화의공원" to ResolvedLocation("평화의공원", "서울특별시 마포구 월드컵로 251", 37.5630, 126.8990),
        "트레이더스위례점" to ResolvedLocation("트레이더스 위례점", "경기도 하남시 위례대로 200 스타필드시티 위례 B1", 37.4789, 127.1438),
        "트레이더스 위례점" to ResolvedLocation("트레이더스 위례점", "경기도 하남시 위례대로 200 스타필드시티 위례 B1", 37.4789, 127.1438),
        "(주)천재교과서" to ResolvedLocation("(주)천재교과서", "서울특별시 금천구 가산디지털1로 16", 37.4765, 126.8835),
        "천재교과서" to ResolvedLocation("(주)천재교과서", "서울특별시 금천구 가산디지털1로 16", 37.4765, 126.8835),
        "아이스크림살래(힐스점)" to ResolvedLocation("아이스크림살래 힐스테이트점", "경기도 하남시 위례순환로 220 힐스테이트 상가", 37.4756, 127.1442),
        "커피인류 위례중앙타워점" to ResolvedLocation("커피인류 위례중앙타워점", "경기도 성남시 수정구 위례광장로 300 위례중앙타워 1층", 37.4740, 127.1430),
        "(주)지에스리테일 GS수퍼 위례중앙점" to ResolvedLocation("GS더프레시 위례중앙점", "경기도 성남시 수정구 위례광장로 310", 37.4745, 127.1435),
        "GS수퍼 위례중앙점" to ResolvedLocation("GS더프레시 위례중앙점", "경기도 성남시 수정구 위례광장로 310", 37.4745, 127.1435),
        "남촌CC그늘집" to ResolvedLocation("남촌CC", "경기도 광주시 곤지암읍 도척윗로 500 남촌CC", 37.3321, 127.3524),
        "남촌CC" to ResolvedLocation("남촌CC", "경기도 광주시 곤지암읍 도척윗로 500 남촌CC", 37.3321, 127.3524),
        "스타벅스 판교점" to ResolvedLocation("스타벅스 판교역점", "경기도 성남시 분당구 판교역로 145 알파리움", 37.3948, 127.1119),
        "GS칼텍스 삼평주유소" to ResolvedLocation("GS칼텍스 삼평주유소", "경기도 성남시 분당구 판교로 255", 37.4020, 127.1050),
        "골프존파크 판교역점" to ResolvedLocation("골프존파크 판교역점", "경기도 성남시 분당구 판교역로 192", 37.3980, 127.1125),
        "넷플릭스 스탠다드" to ResolvedLocation("넷플릭스", "온라인 디지털 구독 서비스"),
        "넷플릭스" to ResolvedLocation("넷플릭스", "온라인 디지털 구독 서비스"),
        "LGUPLUS 통신요금자" to ResolvedLocation("LG U+", "통신요금 자동이체"),
        "LGUPLUS" to ResolvedLocation("LG U+", "통신요금 자동이체"),
        "배민클럽_우아한형제" to ResolvedLocation("배달의민족", "온라인 배달 서비스"),
        "네이버플러스 멤버" to ResolvedLocation("네이버플러스", "네이버 디지털 구독 서비스"),
        "(주)쿠프마케팅" to ResolvedLocation("쿠프마케팅", "모바일 쿠폰 플랫폼"),
        "쿠팡" to ResolvedLocation("쿠팡", "온라인 쇼핑 결제")
    )

    fun resolveMerchantLocation(merchantName: String): ResolvedLocation {
        val clean = merchantName.trim()
        KNOWN_PLACES[clean]?.let { return it }

        for ((key, loc) in KNOWN_PLACES) {
            if (clean.contains(key) || key.contains(clean)) {
                return loc
            }
        }
        return ResolvedLocation(clean, "결제 위치 정보")
    }

    fun resolvePhotoLocation(
        context: Context? = null,
        uri: Uri? = null,
        streamProvider: () -> InputStream?
    ): ResolvedLocation? {
        val fromExif = runCatching {
            streamProvider()?.use { inputStream ->
                val exif = ExifInterface(inputStream)
                val latLong = FloatArray(2)
                if (exif.getLatLong(latLong)) {
                    val lat = latLong[0].toDouble()
                    val lng = latLong[1].toDouble()
                    resolveGeoLocation(context, lat, lng)
                } else {
                    null
                }
            }
        }.getOrNull()

        // EXIF 위치 정보(GPS)가 없으면 임의의 위치(방이동 등)로 왜곡하지 않고 null을 반환합니다.
        return fromExif
    }

    private fun shortenAdminArea(admin: String?): String {
        if (admin.isNullOrBlank()) return ""
        val trimmed = admin.trim()
        return when {
            trimmed.contains("서울") -> "서울"
            trimmed.contains("경기") -> "경기"
            trimmed.contains("충청북") || trimmed.contains("충북") -> "충북"
            trimmed.contains("충청남") || trimmed.contains("충남") -> "충남"
            trimmed.contains("전라북") || trimmed.contains("전북") -> "전북"
            trimmed.contains("전라남") || trimmed.contains("전남") -> "전남"
            trimmed.contains("경상북") || trimmed.contains("경북") -> "경북"
            trimmed.contains("경상남") || trimmed.contains("경남") -> "경남"
            trimmed.contains("강원") -> "강원"
            trimmed.contains("제주") -> "제주"
            trimmed.contains("세종") -> "세종"
            trimmed.contains("인천") -> "인천"
            trimmed.contains("부산") -> "부산"
            trimmed.contains("대구") -> "대구"
            trimmed.contains("광주") -> "광주"
            trimmed.contains("대전") -> "대전"
            trimmed.contains("울산") -> "울산"
            else -> trimmed.replace("특별시", "").replace("광역시", "").replace("특별자치시", "").replace("도", "")
        }
    }

    private fun extractDong(addr: android.location.Address): String? {
        val candidates = listOfNotNull(addr.thoroughfare, addr.subLocality, addr.featureName)
        for (c in candidates) {
            val trimmed = c.trim()
            if ((trimmed.endsWith("동") || trimmed.endsWith("읍") || trimmed.endsWith("면") || trimmed.endsWith("리") || trimmed.endsWith("가")) &&
                !trimmed.endsWith("동로") && !trimmed.endsWith("동길") && !trimmed.endsWith("운동장")
            ) {
                return trimmed
            }
        }
        val fullLine = addr.getAddressLine(0) ?: ""
        val regex = Regex("([가-힣0-9]+(?:동|읍|면|리|가))(?=\\s|\\d|$)")
        val matches = regex.findAll(fullLine).map { it.groupValues[1] }.toList()
        return matches.lastOrNull { !it.endsWith("동로") && !it.endsWith("동길") && !it.endsWith("운동장") }
    }

    private fun extractRoadAndNumber(addr: android.location.Address): String? {
        val fullLine = addr.getAddressLine(0) ?: ""
        val tokens = fullLine.split(" ", ",").map { it.trim() }.filter { it.isNotBlank() }
        val roadIdx = tokens.indexOfFirst { t ->
            (t.endsWith("로") || t.endsWith("길") || t.endsWith("대로")) &&
                t.length >= 2 &&
                !t.endsWith("동로") // 예: 일반 행정동이 아닌 실제 도로명만 매칭하되 '영등포로', '위례대로6길' 등 포함
        }.takeIf { it >= 0 } ?: tokens.indexOfFirst { t ->
            t.endsWith("로") || t.endsWith("길") || t.endsWith("대로")
        }

        if (roadIdx >= 0) {
            val road = tokens[roadIdx]
            val nextToken = tokens.getOrNull(roadIdx + 1)
            return if (nextToken != null && nextToken.matches(Regex("^[0-9]+(-[0-9]+)?$"))) {
                "$road $nextToken"
            } else {
                road
            }
        }

        val th = addr.thoroughfare?.trim()
        if (!th.isNullOrBlank() && (th.endsWith("로") || th.endsWith("길") || th.endsWith("대로"))) {
            val subTh = addr.subThoroughfare?.trim()
            return if (!subTh.isNullOrBlank() && subTh.matches(Regex("^[0-9]+(-[0-9]+)?$"))) {
                "$th $subTh"
            } else {
                th
            }
        }
        return null
    }

    internal fun parseAddressToDisplayName(addr: android.location.Address): String {
        val admin = shortenAdminArea(addr.adminArea)
        val fullLine = addr.getAddressLine(0) ?: ""

        // fullLine(예: "대한민국 경기도 하남시 학암동 위례순환로 123")에서 시/군/구 토큰 추출
        val tokens = fullLine.split(" ", ",").map { it.trim() }.filter { it.isNotBlank() && it != "대한민국" }

        var siGunGu: String? = null
        for (t in tokens) {
            if (t.endsWith("시") || t.endsWith("군") || t.endsWith("구")) {
                if (siGunGu == null) {
                    siGunGu = t
                } else if (siGunGu.endsWith("시") && t.endsWith("구")) {
                    siGunGu = "$siGunGu $t" // 예: 성남시 수정구
                }
            }
        }

        // fallback: Address 객체의 subLocality, locality, subAdminArea에서 구/시 탐색
        if (siGunGu == null) {
            val candidateGu = listOfNotNull(addr.subLocality, addr.locality, addr.subAdminArea)
                .map { it.trim() }
                .firstOrNull { it.endsWith("시") || it.endsWith("군") || it.endsWith("구") }
            if (candidateGu != null) {
                siGunGu = candidateGu
            }
        }

        val dong = extractDong(addr)

        return when {
            admin == "서울" -> {
                when {
                    !dong.isNullOrBlank() -> "서울 $dong"
                    !siGunGu.isNullOrBlank() -> "서울 $siGunGu"
                    else -> "서울"
                }
            }
            admin.isNotBlank() -> {
                when {
                    !dong.isNullOrBlank() && !siGunGu.isNullOrBlank() -> "$admin $siGunGu $dong"
                    !dong.isNullOrBlank() -> "$admin $dong"
                    !siGunGu.isNullOrBlank() -> "$admin $siGunGu"
                    else -> admin
                }
            }
            !siGunGu.isNullOrBlank() -> {
                if (!dong.isNullOrBlank()) "$siGunGu $dong" else siGunGu
            }
            !dong.isNullOrBlank() -> dong
            fullLine.isNotBlank() -> fullLine
            else -> "기록된 장소"
        }
    }

    /**
     * 차량 주행 기록의 출발지·도착지 표기를 위한 고정밀 지명 해석:
     * 1) Android Geocoder에서 최대 3개 주소 후보를 조회하여 시/군/구 + 행정동 + 도로명(번지)을 결합
     * 2) 백그라운드에서 Geocoder gRPC 타임아웃/실패 시 OpenStreetMap Nominatim 한국어 역지오코딩으로 즉시 폴백
     */
    fun resolvePreciseDrivingPlaceName(context: Context? = null, lat: Double, lng: Double): String {
        val cacheKey = "precise_%.4f,%.4f".format(java.util.Locale.US, lat, lng)
        geoCache[cacheKey]?.let {
            if (!it.placeName.startsWith("위도 ")) return it.placeName
        }

        val activeContext = context ?: this.context

        // 1. Android Geocoder 다중 결과(도로명 + 지번 동시 추출)
        if (activeContext != null) {
            runCatching {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(activeContext, java.util.Locale.KOREAN)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 3)
                    if (!addresses.isNullOrEmpty()) {
                        var siGunGu: String? = null
                        var dong: String? = null
                        var road: String? = null
                        var fullAddr = ""

                        for (addr in addresses) {
                            if (fullAddr.isBlank()) fullAddr = addr.getAddressLine(0) ?: ""
                            val lineTokens = (addr.getAddressLine(0) ?: "").split(" ", ",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() && it != "대한민국" }

                            if (siGunGu == null) {
                                for (t in lineTokens) {
                                    if (t.endsWith("시") || t.endsWith("군") || t.endsWith("구")) {
                                        if (siGunGu == null) {
                                            siGunGu = t
                                        } else if (siGunGu.endsWith("시") && t.endsWith("구")) {
                                            siGunGu = "$siGunGu $t"
                                        }
                                    }
                                }
                            }
                            if (dong == null) {
                                dong = extractDong(addr)
                            }
                            if (road == null) {
                                road = extractRoadAndNumber(addr)
                            }
                        }

                        val preciseName = buildPrecisePlaceString(siGunGu, dong, road)
                            .ifBlank { parseAddressToDisplayName(addresses[0]) }
                        if (preciseName.isNotBlank() && !preciseName.startsWith("위도 ")) {
                            geoCache[cacheKey] = ResolvedLocation(preciseName, fullAddr.ifBlank { preciseName }, lat, lng)
                            return preciseName
                        }
                    }
                }
            }
        }

        // 2. Geocoder 실패 시 OpenStreetMap Nominatim 역지오코딩 폴백 (백그라운드 gRPC 오류 완벽 방어)
        val nominatimResult = fetchNominatimReverseGeocode(lat, lng)
        if (nominatimResult != null) {
            geoCache[cacheKey] = nominatimResult
            return nominatimResult.placeName
        }

        // 3. 기존 일반 캐시 조회
        val general = resolveGeoLocation(activeContext, lat, lng)
        if (!general.placeName.startsWith("위도 ")) {
            return general.placeName
        }

        return ""
    }

    private fun buildPrecisePlaceString(siGunGu: String?, dong: String?, road: String?): String {
        val cleanGu = siGunGu?.trim().orEmpty()
        val cleanDong = dong?.trim().orEmpty()
        val cleanRoad = road?.trim().orEmpty()

        return when {
            cleanGu.isNotBlank() && cleanDong.isNotBlank() && cleanRoad.isNotBlank() -> {
                "$cleanGu $cleanDong $cleanRoad"
            }
            cleanGu.isNotBlank() && cleanRoad.isNotBlank() -> {
                "$cleanGu $cleanRoad"
            }
            cleanGu.isNotBlank() && cleanDong.isNotBlank() -> {
                "$cleanGu $cleanDong"
            }
            cleanDong.isNotBlank() && cleanRoad.isNotBlank() -> {
                "$cleanDong $cleanRoad"
            }
            cleanRoad.isNotBlank() -> cleanRoad
            cleanDong.isNotBlank() -> cleanDong
            cleanGu.isNotBlank() -> cleanGu
            else -> ""
        }
    }

    private fun fetchNominatimReverseGeocode(lat: Double, lng: Double): ResolvedLocation? {
        return runCatching {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=18&addressdetails=1&accept-language=ko"
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 2500
                readTimeout = 2500
                setRequestProperty("User-Agent", "AutoLogueLifeLogApp/1.0 (Android)")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            }
            if (conn.responseCode == 200) {
                val body = conn.inputStream.bufferedReader().use { it.readText() }
                val json = org.json.JSONObject(body)
                val addrObj = json.optJSONObject("address")
                val displayName = json.optString("display_name", "")
                if (addrObj != null) {
                    val city = addrObj.optString("city", "").ifBlank { addrObj.optString("county", "") }
                    val borough = addrObj.optString("borough", "").ifBlank { addrObj.optString("city_district", "") }
                    val siGunGu = when {
                        city.isNotBlank() && borough.isNotBlank() && city != borough && !city.contains("서울") -> "$city $borough"
                        borough.isNotBlank() -> borough
                        city.isNotBlank() -> city
                        else -> ""
                    }
                    val dong = listOf("suburb", "quarter", "neighbourhood", "village", "town")
                        .map { addrObj.optString(it, "").trim() }
                        .firstOrNull { it.isNotBlank() }
                    val roadName = addrObj.optString("road", "").trim()
                    val houseNo = addrObj.optString("house_number", "").trim()
                    val road = if (roadName.isNotBlank() && houseNo.isNotBlank()) "$roadName $houseNo" else roadName

                    val precise = buildPrecisePlaceString(siGunGu, dong, road)
                    if (precise.isNotBlank()) {
                        return@runCatching ResolvedLocation(
                            placeName = precise,
                            address = displayName.ifBlank { precise },
                            latitude = lat,
                            longitude = lng
                        )
                    }
                }
            }
            null
        }.getOrNull()
    }

    fun resolveGeoLocation(context: Context? = null, lat: Double, lng: Double): ResolvedLocation {
        val cacheKey = "%.3f,%.3f".format(java.util.Locale.US, lat, lng)
        geoCache[cacheKey]?.let {
            if (!it.placeName.startsWith("위도 ")) return it
        }

        val activeContext = context ?: this.context

        // 1. Android Geocoder (구글 맵 공식 역지오코딩) 최우선 실행
        if (activeContext != null) {
            runCatching {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(activeContext, java.util.Locale.KOREAN)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 2)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val shortName = parseAddressToDisplayName(addr)
                        val fullAddress = addr.getAddressLine(0) ?: shortName
                        val res = ResolvedLocation(
                            placeName = if (shortName.isNotBlank()) shortName else fullAddress,
                            address = fullAddress.ifBlank { "위치 정보" },
                            latitude = lat,
                            longitude = lng
                        )
                        geoCache[cacheKey] = res
                        return res
                    }
                }
            }
        }

        // 2. Geocoder 실패 시 Nominatim 역지오코딩 폴백 시도
        fetchNominatimReverseGeocode(lat, lng)?.let { nom ->
            geoCache[cacheKey] = nom
            return nom
        }

        // 3. 오프라인 환경일 때 안전 폴백 (단, 임시 실패이므로 geoCache에는 저장하지 않음)
        val fallbackPlace = "위도 %.3f, 경도 %.3f".format(java.util.Locale.US, lat, lng)
        return ResolvedLocation(fallbackPlace, "위치 정보 ($fallbackPlace)", lat, lng)
    }

    companion object {
        /** [M-01] geoCache LRU 최대 항목 수 — 초과 시 가장 오래된 항목 자동 제거 */
        private const val MAX_CACHE_SIZE = 1000
    }
}
