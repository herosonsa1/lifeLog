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

    private fun normalizeDongName(rawDong: String?): String? {
        if (rawDong.isNullOrBlank()) return null
        val trimmed = rawDong.trim()
        // 예: "영등포동3가" -> "영등포동", "문래동3가" -> "문래동", "성수동1가" -> "성수동"
        val dongGaMatch = Regex("^([가-힣]{2,}동)[0-9]+가$").find(trimmed)
        if (dongGaMatch != null) {
            return dongGaMatch.groupValues[1]
        }
        // 예: "거여1동" -> "거여동", "방이2동" -> "방이동", "역삼1동" -> "역삼동"
        val numberedDongMatch = Regex("^([가-힣]{2,})[0-9]+동$").find(trimmed)
        if (numberedDongMatch != null) {
            return "${numberedDongMatch.groupValues[1]}동"
        }
        return trimmed
    }

    /**
     * 도로명 주소만 반환되었거나 오프라인 거점 문자열만 있을 때 동네명(시/구 + 동)을 추론합니다.
     * 예: "위례대로6길 20" -> ("송파구", "위례동"), "영등포로 254" -> ("영등포구", "영등포동")
     */
    internal fun inferNeighborhoodFromText(existingGu: String?, text: String?): Pair<String?, String?> {
        if (text.isNullOrBlank()) return Pair(existingGu, null)
        val clean = text.trim()
        val tokens = clean.split(" ", ",", "(", ")").map { it.trim() }.filter { it.isNotBlank() && it != "대한민국" }

        var gu = existingGu
        var dong: String? = null

        for (t in tokens) {
            if (gu == null && (t.endsWith("시") || t.endsWith("군") || t.endsWith("구"))) {
                gu = t
            } else if (gu != null && gu.endsWith("시") && t.endsWith("구") && !gu.contains(t)) {
                gu = "$gu $t"
            }
            if (dong == null && (t.endsWith("동") || t.endsWith("읍") || t.endsWith("면")) &&
                !t.endsWith("동로") && !t.endsWith("동길") && !t.endsWith("운동장")
            ) {
                dong = normalizeDongName(t)
            }
        }

        if (dong == null) {
            when {
                clean.contains("위례") -> {
                    dong = when {
                        gu?.contains("하남") == true -> "학암동"
                        gu?.contains("성남") == true || gu?.contains("수정") == true -> "창곡동"
                        else -> "위례동"
                    }
                    if (gu == null) gu = "송파구"
                }
                clean.contains("영등포로") || clean.contains("영등포") -> {
                    dong = "영등포동"
                    if (gu == null) gu = "영등포구"
                }
                clean.contains("여의대로") || clean.contains("여의나루") || clean.contains("여의도") -> {
                    dong = "여의도동"
                    if (gu == null) gu = "영등포구"
                }
                clean.contains("판교") -> {
                    dong = "판교동"
                    if (gu == null) gu = "분당구"
                }
                clean.contains("테헤란로") || clean.contains("역삼") -> {
                    dong = "역삼동"
                    if (gu == null) gu = "강남구"
                }
                clean.contains("가산디지털") -> {
                    dong = "가산동"
                    if (gu == null) gu = "금천구"
                }
                else -> {
                    // 일반 도로명(예: "방이로", "잠실대로", "문정로")에서 번호 제거
                    val roadToken = tokens.firstOrNull { it.endsWith("로") || it.endsWith("길") || it.endsWith("대로") }
                    if (roadToken != null) {
                        val prefixMatch = Regex("^([가-힣]{2,})(?:로|대로|길).*$").find(roadToken)
                        val stem = prefixMatch?.groupValues?.get(1)
                        dong = if (!stem.isNullOrBlank() && stem.length in 2..3 && !stem.endsWith("대")) {
                            roadToken
                        } else {
                            roadToken
                        }
                    }
                }
            }
        }

        return Pair(gu, normalizeDongName(dong))
    }

    /**
     * 단일 주소/도로명 텍스트로부터 동네명(예: "송파구 위례동", "영등포구 영등포동") 문자열을 추론합니다.
     */
    fun inferNeighborhoodFromText(text: String): String {
        if (text.isBlank()) return ""
        val (gu, dong) = inferNeighborhoodFromText(null, text)
        return buildNeighborhoodPlaceString(gu, dong, text)
    }

    /**
     * 대략적인 출발지·도착지 좌표(또는 거점 주소 힌트)를 역지오코딩하여
     * 깔끔한 동네명(예: "송파구 위례동", "하남시 학암동", "영등포구 영등포동")으로 반환합니다.
     */
    fun resolveNeighborhoodName(
        context: Context? = null,
        lat: Double,
        lng: Double,
        fallbackAddressHint: String = ""
    ): String {
        val hasValidCoord = com.autologue.app.util.LocationDistanceUtils.isValidCoordinate(lat, lng)
        val cacheKey = "dong_%.3f,%.3f".format(java.util.Locale.US, lat, lng)
        if (hasValidCoord) {
            geoCache[cacheKey]?.let {
                if (it.placeName.isNotBlank() && !it.placeName.startsWith("위도 ")) return it.placeName
            }
        }

        val activeContext = context ?: this.context
        var siGunGu: String? = null
        var dong: String? = null
        var road: String? = null
        var fullAddr = ""

        // 1. Android Geocoder에서 최대 5개 주소 후보를 조회하여 법정동/행정동 우선 추출
        if (hasValidCoord && activeContext != null) {
            runCatching {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(activeContext, java.util.Locale.KOREAN)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 5)
                    if (!addresses.isNullOrEmpty()) {
                        for (addr in addresses) {
                            if (fullAddr.isBlank()) fullAddr = addr.getAddressLine(0) ?: ""
                            val lineTokens = (addr.getAddressLine(0) ?: "").split(" ", ",")
                                .map { it.trim() }
                                .filter { it.isNotBlank() && it != "대한민국" }

                            if (siGunGu == null) {
                                for (t in lineTokens) {
                                    if (t.endsWith("시") || t.endsWith("군") || t.endsWith("구")) {
                                        val currentGu = siGunGu
                                        if (currentGu == null) {
                                            siGunGu = t
                                        } else if (currentGu.endsWith("시") && t.endsWith("구") && !currentGu.contains(t)) {
                                            siGunGu = "$currentGu $t"
                                        }
                                    }
                                }
                                if (siGunGu == null) {
                                    siGunGu = listOfNotNull(addr.subLocality, addr.locality, addr.subAdminArea)
                                        .map { it.trim() }
                                        .firstOrNull { it.endsWith("시") || it.endsWith("군") || it.endsWith("구") }
                                }
                            }
                            if (dong == null) {
                                dong = normalizeDongName(extractDong(addr))
                            }
                            if (road == null) {
                                road = extractRoadAndNumber(addr)
                            }
                        }
                    }
                }
            }
        }

        // 2. Geocoder에서 동(동/읍/면)이 누락된 경우 OpenStreetMap Nominatim에서 동네명 보충
        if (hasValidCoord && dong == null) {
            val nominatimResult = fetchNominatimReverseGeocode(lat, lng)
            if (nominatimResult != null && nominatimResult.placeName.isNotBlank()) {
                if (hasValidCoord) {
                    geoCache[cacheKey] = nominatimResult
                }
                return nominatimResult.placeName
            }
        }

        // 3. 도로명 또는 fallbackAddressHint로부터 동네명 보완 추론
        if (dong == null) {
            val (inferredGu, inferredDong) = inferNeighborhoodFromText(
                siGunGu,
                fallbackAddressHint.ifBlank { fullAddr.ifBlank { road } }
            )
            if (siGunGu == null) siGunGu = inferredGu
            if (dong == null) dong = inferredDong
        }

        val neighborhood = buildNeighborhoodPlaceString(siGunGu, dong, road)
        if (neighborhood.isNotBlank()) {
            if (hasValidCoord) {
                geoCache[cacheKey] = ResolvedLocation(neighborhood, fullAddr.ifBlank { neighborhood }, lat, lng)
            }
            return neighborhood
        }

        if (fallbackAddressHint.isNotBlank()) {
            val (hintGu, hintDong) = inferNeighborhoodFromText(null, fallbackAddressHint)
            val fromHint = buildNeighborhoodPlaceString(hintGu, hintDong, fallbackAddressHint)
            if (fromHint.isNotBlank()) return fromHint
        }

        return ""
    }

    /**
     * 차량 주행 기록의 출발지·도착지 표기를 위한 동네명 해석:
     * 대략적인 출발위치와 도착위치를 역지오코딩하여 직관적인 동네명(시/군/구 + 동/읍/면)으로 표시합니다.
     */
    fun resolvePreciseDrivingPlaceName(
        context: Context? = null,
        lat: Double,
        lng: Double,
        fallbackAddressHint: String = ""
    ): String {
        return resolveNeighborhoodName(context, lat, lng, fallbackAddressHint)
    }

    private fun buildNeighborhoodPlaceString(siGunGu: String?, dong: String?, road: String?): String {
        val rawGu = siGunGu?.trim().orEmpty()
        // "성남시 수정구" -> "수정구" 또는 "성남시 수정구" 중 간결한 구/시 명칭 유지
        val cleanGu = if (rawGu.contains(" ")) {
            val parts = rawGu.split(" ").filter { it.isNotBlank() }
            if (parts.size >= 2 && parts.last().endsWith("구")) parts.last() else rawGu
        } else {
            rawGu
        }
        val cleanDong = normalizeDongName(dong)?.trim().orEmpty()
        val cleanRoadOnly = road?.trim()?.replace(Regex("\\s+[0-9]+(-[0-9]+)?$"), "").orEmpty()

        return when {
            cleanGu.isNotBlank() && cleanDong.isNotBlank() -> {
                if (cleanDong.contains(cleanGu)) cleanDong else "$cleanGu $cleanDong"
            }
            cleanDong.isNotBlank() -> cleanDong
            cleanGu.isNotBlank() && cleanRoadOnly.isNotBlank() -> "$cleanGu $cleanRoadOnly"
            cleanRoadOnly.isNotBlank() -> cleanRoadOnly
            cleanGu.isNotBlank() -> cleanGu
            else -> ""
        }
    }

    @Volatile
    private var nominatimCooldownUntilMillis: Long = 0L

    @Volatile
    private var geocoderCooldownUntilMillis: Long = 0L

    private fun fetchNominatimReverseGeocode(lat: Double, lng: Double): ResolvedLocation? {
        if (System.currentTimeMillis() < nominatimCooldownUntilMillis) return null
        return runCatching {
            val urlStr = "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=$lat&lon=$lng&zoom=16&addressdetails=1&accept-language=ko"
            val conn = (java.net.URL(urlStr).openConnection() as java.net.HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1500
                readTimeout = 1500
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
                        borough.isNotBlank() -> borough
                        city.isNotBlank() -> city
                        else -> ""
                    }
                    val rawDong = listOf("suburb", "quarter", "neighbourhood", "village", "town")
                        .map { addrObj.optString(it, "").trim() }
                        .firstOrNull { it.isNotBlank() }
                    val dong = normalizeDongName(rawDong)
                    val roadName = addrObj.optString("road", "").trim()

                    val neighborhood = buildNeighborhoodPlaceString(siGunGu, dong, roadName)
                    if (neighborhood.isNotBlank()) {
                        return@runCatching ResolvedLocation(
                            placeName = neighborhood,
                            address = displayName.ifBlank { neighborhood },
                            latitude = lat,
                            longitude = lng
                        )
                    }
                }
            } else {
                nominatimCooldownUntilMillis = System.currentTimeMillis() + 60_000L
            }
            null
        }.onFailure {
            nominatimCooldownUntilMillis = System.currentTimeMillis() + 60_000L
        }.getOrNull()
    }


    fun resolveGeoLocation(context: Context? = null, lat: Double, lng: Double): ResolvedLocation {
        val cacheKey = "%.3f,%.3f".format(java.util.Locale.US, lat, lng)
        geoCache[cacheKey]?.let {
            if (!it.placeName.startsWith("위도 ")) return it
        }

        val activeContext = context ?: this.context

        // 1. Android Geocoder (구글 맵 공식 역지오코딩) 최우선 실행 (연속 타임아웃 방지를 위한 쿨다운 가드 포함)
        if (activeContext != null && System.currentTimeMillis() >= geocoderCooldownUntilMillis) {
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
            }.onFailure {
                geocoderCooldownUntilMillis = System.currentTimeMillis() + 30_000L
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
