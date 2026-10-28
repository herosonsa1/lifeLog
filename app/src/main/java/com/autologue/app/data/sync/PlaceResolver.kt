package com.autologue.app.data.sync

import android.content.Context
import android.net.Uri
import android.os.Looper
import androidx.exifinterface.media.ExifInterface
import com.autologue.app.util.LocationDistanceUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Collections
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

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
     * [M-01] geoCache 크기 제한 적용 — 약 11m 정밀도(%.4f,%.4f) 기준 LRU 캐시.
     * 아파트 정문·후문·상가주차장·인접 상가·버스정류장을 구분하면서도 OOM을 방지합니다.
     */
    private val geoCache: MutableMap<String, ResolvedLocation> = object : LinkedHashMap<String, ResolvedLocation>(
        32, 0.75f, true
    ) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, ResolvedLocation>?) =
            size > MAX_CACHE_SIZE
    }.let { lruMap ->
        Collections.synchronizedMap(lruMap)
    }

    /** 이미 최근접 POI 탐색까지 완료된 좌표 키 집합 (불필요한 반복 네트워크 재조회 방지) */
    private val poiEvaluatedKeys: MutableSet<String> = Collections.synchronizedSet( LinkedHashSet<String>() )

    data class ResolvedLocation(
        val placeName: String,
        val address: String,
        val latitude: Double? = null,
        val longitude: Double? = null
    )

    internal data class OverpassPoiResult(
        val placeName: String,
        val dongHint: String? = null,
        val distanceMeters: Double = 0.0
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

    @Volatile
    private var overpassCooldownUntilMillis: Long = 0L

    @Volatile
    private var nominatimCooldownUntilMillis: Long = 0L

    @Volatile
    private var geocoderCooldownUntilMillis: Long = 0L

    @Volatile
    private var legacyCachePurged: Boolean = false

    private fun coordCacheKey(lat: Double, lng: Double): String =
        "poi_v4_%.4f,%.4f".format(Locale.US, lat, lng)

    private fun purgeLegacyDiskCacheOnce(activeContext: Context?) {
        if (legacyCachePurged || activeContext == null || isOnMainThread()) return
        legacyCachePurged = true
        runCatching {
            activeContext.getSharedPreferences(LEGACY_PREFS_NAME_V3, Context.MODE_PRIVATE)
                .edit()
                .clear()
                .apply()
        }
    }

    private fun isOnMainThread(): Boolean {
        return runCatching {
            val mainLooper = Looper.getMainLooper()
            mainLooper != null && mainLooper.thread === Thread.currentThread()
        }.getOrDefault(false)
    }

    private fun readDiskCache(activeContext: Context?, key: String): Pair<ResolvedLocation, Boolean>? {
        val ctx = activeContext ?: return null
        purgeLegacyDiskCacheOnce(ctx)
        return runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val raw = prefs.getString(key, null) ?: return null
            val parts = raw.split(CACHE_SEP)
            if (parts.size >= 2 && parts[0].isNotBlank()) {
                val poiChecked = parts.getOrNull(2) == "1"
                val sanitizedPlace = cleanDisplayPlaceName(parts[0], parts[1]).ifBlank { parts[0] }
                val sanitizedAddr = sanitizeAddressForDisplay(parts[1]).orEmpty()
                ResolvedLocation(sanitizedPlace, sanitizedAddr) to poiChecked
            } else {
                null
            }
        }.getOrNull()
    }

    private fun writeDiskCache(activeContext: Context?, key: String, resolved: ResolvedLocation, poiEvaluated: Boolean) {
        val ctx = activeContext ?: return
        purgeLegacyDiskCacheOnce(ctx)
        runCatching {
            val prefs = ctx.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val flag = if (poiEvaluated) "1" else "0"
            prefs.edit()
                .putString(key, "${resolved.placeName}${CACHE_SEP}${resolved.address}${CACHE_SEP}$flag")
                .apply()
        }
    }

    fun isCoordinatePoiEvaluated(lat: Double, lng: Double): Boolean {
        if (!LocationDistanceUtils.isValidCoordinate(lat, lng)) return true
        val key = coordCacheKey(lat, lng)
        if (key in poiEvaluatedKeys) return true
        val disk = readDiskCache(context, key)
        if (disk != null && disk.second) {
            geoCache[key] = disk.first.copy(latitude = lat, longitude = lng)
            poiEvaluatedKeys.add(key)
            return true
        }
        return false
    }

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
        return runCatching {
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
    }

    private fun extractDongFromAddress(addr: android.location.Address): String? {
        val candidates = listOfNotNull(addr.subLocality, addr.thoroughfare, addr.featureName)
        for (c in candidates) {
            val trimmed = c.trim()
            if (isDongToken(trimmed)) {
                return normalizeDongName(trimmed)
            }
        }
        val fullLine = addr.getAddressLine(0) ?: ""
        return extractPureDongFromText(fullLine)
    }

    /**
     * Android Geocoder의 Address 객체에서 번지·도로명·행정구역이 아닌
     * 구체 건물명·아파트명(정문/후문/상가주차장 포함)·역사명·상호명을 추출합니다.
     */
    private fun extractPoiFromGeocoderAddress(addr: android.location.Address): String? {
        val fullLine = (addr.getAddressLine(0) ?: "").trim()
        if (containsHomeBuilding7101(fullLine) ||
            containsHomeBuilding7101(addr.premises) ||
            containsHomeBuilding7101(addr.featureName)
        ) {
            return "집"
        }

        val rawCandidates = mutableListOf<String>()
        addr.premises?.trim()?.takeIf { it.isNotBlank() }?.let { rawCandidates.add(it) }
        addr.featureName?.trim()?.takeIf { it.isNotBlank() }?.let { rawCandidates.add(it) }

        // 전체 주소 문자열 말미에 붙은 건물·단지·상가 명칭 추출
        // 예: "대한민국 경기도 하남시 학암동 위례대로 200 스타필드시티 위례" -> "스타필드시티 위례"
        if (fullLine.isNotBlank()) {
            val tailAfterRoadNum = Regex("(?:대로|로|길)\\s+[0-9]+(?:-[0-9]+)?\\s+(.+)$")
                .find(fullLine)?.groupValues?.get(1)?.trim()
            if (!tailAfterRoadNum.isNullOrBlank()) {
                rawCandidates.add(tailAfterRoadNum)
            } else {
                val tailAfterJibun = Regex("(?:동|읍|면|리|[0-9]+가)\\s+(?:산\\s*)?[0-9]+(?:-[0-9]+)?\\s+(.+)$")
                    .find(fullLine)?.groupValues?.get(1)?.trim()
                if (!tailAfterJibun.isNullOrBlank()) {
                    rawCandidates.add(tailAfterJibun)
                }
            }
        }

        // 아파트 단지명 + 세부 출입구/상가 조합 확인 (세대 호수는 전면 배제)
        var parentComplex: String? = null
        var subGateOrParking: String? = null
        for (raw in rawCandidates) {
            if (containsHomeBuilding7101(raw)) return "집"
            val cleaned = cleanSpecificPoiName(
                stripBuildingUnitNumbers(raw)
                    .replace(Regex("\\s*[0-9]{5}$"), "") // 우편번호 제거
                    .trim()
            )
            if (cleaned.isBlank()) continue
            if (cleaned == "집") return "집"
            if (isSubEntranceOrParkingOnly(cleaned)) {
                if (subGateOrParking == null) subGateOrParking = cleaned
                continue
            }
            if (isValidSpecificPlaceName(cleaned)) {
                if (isApartmentOrComplexName(cleaned) && parentComplex == null) {
                    parentComplex = cleaned
                } else if (parentComplex == null) {
                    return cleaned
                }
            }
        }

        if (parentComplex != null) {
            return combineComplexAndSubFeature(parentComplex, subGateOrParking)
        }
        return null
    }

    internal fun parseAddressToDisplayName(addr: android.location.Address): String {
        extractPoiFromGeocoderAddress(addr)?.let { return it }
        extractDongFromAddress(addr)?.let { return it }
        val fullLine = addr.getAddressLine(0) ?: ""
        val cleaned = cleanDisplayPlaceName(fullLine, fullLine)
        return cleaned.ifBlank { "기록된 장소" }
    }

    /**
     * 도로명 주소만 반환되었거나 오프라인 거점 문자열만 있을 때 구/시 및 순수 동 이름을 추론합니다.
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
            if (dong == null && isDongToken(t)) {
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
                clean.contains("방이로") || clean.contains("위례성대로") -> {
                    dong = "방이동"
                    if (gu == null) gu = "송파구"
                }
            }
        }

        return Pair(gu, normalizeDongName(dong))
    }

    /**
     * 단일 주소/명칭 텍스트로부터 대표 지명(구체 명칭이 있으면 해당 명칭, 없으면 순수 동 이름)을 반환합니다.
     */
    fun inferNeighborhoodFromText(text: String): String {
        if (text.isBlank()) return ""
        val cleaned = cleanDisplayPlaceName(text, text)
        if (cleaned.isNotBlank()) return cleaned
        val (gu, dong) = inferNeighborhoodFromText(null, text)
        return dong ?: gu.orEmpty()
    }

    /**
     * GPS 좌표를 해석하여 최근접 큰 건물·지하철역·버스정류장·상호명·아파트명(정문/후문/상가주차장 등)을 최우선 반환하고,
     * 명확히 대표적으로 가까운 명칭이 없을 때만 순수 동 이름(예: "학암동", "영등포동")을 반환합니다.
     */
    fun resolveNeighborhoodName(
        context: Context? = null,
        lat: Double,
        lng: Double,
        fallbackAddressHint: String = ""
    ): String {
        val hasValidCoord = LocationDistanceUtils.isValidCoordinate(lat, lng)
        if (hasValidCoord) {
            val resolved = resolveGeoLocation(context, lat, lng)
            if (resolved.placeName.isNotBlank() && !resolved.placeName.startsWith("위도 ")) {
                return resolved.placeName
            }
        }

        if (fallbackAddressHint.isNotBlank()) {
            val fromHint = inferNeighborhoodFromText(fallbackAddressHint)
            if (fromHint.isNotBlank()) return fromHint
        }
        return ""
    }

    /**
     * 차량 주행 기록 및 다이어리 이동 경로의 출발지·도착지 표기:
     * 최근접 건물·지하철역·버스정류장·상호명·아파트 세부 명칭을 최우선 반환하며, 없을 경우에만 순수 동 이름을 반환합니다.
     */
    fun resolvePreciseDrivingPlaceName(
        context: Context? = null,
        lat: Double,
        lng: Double,
        fallbackAddressHint: String = ""
    ): String {
        return resolveNeighborhoodName(context, lat, lng, fallbackAddressHint)
    }

    fun resolveGeoLocation(context: Context? = null, lat: Double, lng: Double): ResolvedLocation {
        if (!LocationDistanceUtils.isValidCoordinate(lat, lng)) {
            return ResolvedLocation("위치 정보", "위치 정보", lat, lng)
        }

        // 0. 사전 정의된 주요 방문지(80m 이내) 즉시 매칭
        findKnownPlaceByCoord(lat, lng)?.let { known ->
            return known.copy(latitude = lat, longitude = lng)
        }

        val activeContext = context ?: this.context
        val cacheKey = coordCacheKey(lat, lng)

        // 1. 메모리 및 디스크 캐시 확인 (이미 최근접 POI 탐색까지 완료된 좌표면 즉시 반환)
        geoCache[cacheKey]?.let { cached ->
            if (!cached.placeName.startsWith("위도 ") &&
                (cacheKey in poiEvaluatedKeys || isValidSpecificPlaceName(cached.placeName) || isOnMainThread() || activeContext == null)
            ) {
                return cached
            }
        }
        readDiskCache(activeContext, cacheKey)?.let { (diskLoc, poiChecked) ->
            val hydrated = diskLoc.copy(latitude = lat, longitude = lng)
            geoCache[cacheKey] = hydrated
            if (poiChecked) poiEvaluatedKeys.add(cacheKey)
            if (poiChecked || isValidSpecificPlaceName(hydrated.placeName) || isOnMainThread()) {
                return hydrated
            }
        }

        // 메인 UI 스레드에서 호출된 경우 네트워크/지오코더 I/O를 절대 수행하지 않음 (AP-ANDROID-MAIN-THREAD-BLOCKING 방어)
        if (isOnMainThread()) {
            val fallbackPlace = "위도 %.3f, 경도 %.3f".format(Locale.US, lat, lng)
            return ResolvedLocation(fallbackPlace, "위치 정보 ($fallbackPlace)", lat, lng)
        }

        var geocoderPoi: String? = null
        var geocoderDong: String? = null
        var geocoderFullAddr = ""
        var geocoderSiGunGu: String? = null

        // 2. Android Geocoder 조회 (상세 주소 확보 + 건물/아파트/역사 후보 및 법정동 확보)
        if (activeContext != null && System.currentTimeMillis() >= geocoderCooldownUntilMillis) {
            runCatching {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(activeContext, Locale.KOREAN)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 5)
                    if (!addresses.isNullOrEmpty()) {
                        for (addr in addresses) {
                            if (geocoderFullAddr.isBlank()) {
                                geocoderFullAddr = addr.getAddressLine(0).orEmpty()
                            }
                            if (geocoderPoi == null) {
                                geocoderPoi = extractPoiFromGeocoderAddress(addr)
                            }
                            if (geocoderDong == null) {
                                geocoderDong = extractDongFromAddress(addr)
                            }
                            if (geocoderSiGunGu == null) {
                                val (gu, inferredDong) = inferNeighborhoodFromText(null, addr.getAddressLine(0))
                                geocoderSiGunGu = gu
                                if (geocoderDong == null) geocoderDong = inferredDong
                            }
                        }
                    }
                }
            }.onFailure {
                geocoderCooldownUntilMillis = System.currentTimeMillis() + 30_000L
            }
        }

        // 3. Overpass API 최근접 POI 탐색 (큰 건물, 지하철역·출구, 버스정류장, 상호명, 아파트 정문/후문/상가주차장 최우선)
        var overpassResult: OverpassPoiResult? = null
        var networkPoiAttempted = false
        if (activeContext != null) {
            networkPoiAttempted = true
            overpassResult = fetchNearbyOsmPoi(lat, lng)
        }

        // 4. Overpass에서 구체 POI를 못 찾았거나 동 정보가 없을 때 Nominatim zoom=18 고정밀 역지오코딩 보조 실행
        var nominatimResult: ResolvedLocation? = null
        if (activeContext != null && (overpassResult == null && geocoderPoi == null || (geocoderDong == null && overpassResult?.dongHint == null))) {
            networkPoiAttempted = true
            nominatimResult = fetchNominatimReverseGeocode(lat, lng)
        }

        val nominatimPoi = nominatimResult?.placeName?.takeIf { isValidSpecificPlaceName(it) }
        val nominatimDong = nominatimResult?.let {
            extractPureDongFromText(it.placeName) ?: extractPureDongFromText(it.address)
        }

        val pureDong = listOfNotNull(
            geocoderDong,
            overpassResult?.dongHint,
            nominatimDong,
            extractPureDongFromText(geocoderFullAddr)
        ).mapNotNull { normalizeDongName(it) }.firstOrNull { it.isNotBlank() }

        // 최우선 순위: 7101동("집") > Overpass 최근접 POI > Nominatim 고정밀 POI > Geocoder 건물/단지 POI > 순수 동 이름
        val rawBestPlaceName: String = when {
            containsHomeBuilding7101(geocoderFullAddr) ||
                overpassResult?.placeName == "집" ||
                nominatimPoi == "집" ||
                geocoderPoi == "집" -> "집"
            overpassResult != null && overpassResult.placeName.isNotBlank() -> overpassResult.placeName
            !nominatimPoi.isNullOrBlank() -> nominatimPoi
            !geocoderPoi.isNullOrBlank() -> geocoderPoi.orEmpty()
            !pureDong.isNullOrBlank() -> pureDong
            !geocoderSiGunGu.isNullOrBlank() -> cleanDisplayPlaceName(geocoderSiGunGu, geocoderFullAddr)
            nominatimResult != null && nominatimResult.placeName.isNotBlank() -> cleanDisplayPlaceName(nominatimResult.placeName, nominatimResult.address)
            else -> ""
        }
        val bestPlaceName = cleanDisplayPlaceName(rawBestPlaceName, geocoderFullAddr).ifBlank { rawBestPlaceName }

        val rawBestAddress: String = when {
            geocoderFullAddr.isNotBlank() -> geocoderFullAddr
            nominatimResult != null && nominatimResult.address.isNotBlank() -> nominatimResult.address
            bestPlaceName.isNotBlank() -> bestPlaceName
            else -> ""
        }
        val bestAddress = sanitizeAddressForDisplay(rawBestAddress).orEmpty()

        if (bestPlaceName.isNotBlank()) {
            val resolved = ResolvedLocation(
                placeName = bestPlaceName,
                address = bestAddress.ifBlank { bestPlaceName },
                latitude = lat,
                longitude = lng
            )
            geoCache[cacheKey] = resolved
            if (networkPoiAttempted || isValidSpecificPlaceName(bestPlaceName)) {
                poiEvaluatedKeys.add(cacheKey)
                writeDiskCache(activeContext, cacheKey, resolved, poiEvaluated = true)
            } else {
                writeDiskCache(activeContext, cacheKey, resolved, poiEvaluated = false)
            }
            return resolved
        }

        val fallbackPlace = "위도 %.3f, 경도 %.3f".format(Locale.US, lat, lng)
        return ResolvedLocation(fallbackPlace, "위치 정보 ($fallbackPlace)", lat, lng)
    }

    private fun findKnownPlaceByCoord(lat: Double, lng: Double): ResolvedLocation? {
        var best: ResolvedLocation? = null
        var bestDist = Double.MAX_VALUE
        for (loc in KNOWN_PLACES.values) {
            val kLat = loc.latitude ?: continue
            val kLng = loc.longitude ?: continue
            val distM = distanceMeters(lat, lng, kLat, kLng)
            if (distM <= 85.0 && distM < bestDist) {
                bestDist = distM
                best = loc
            }
        }
        return best
    }

    private fun fetchNearbyOsmPoi(lat: Double, lng: Double): OverpassPoiResult? {
        if (System.currentTimeMillis() < overpassCooldownUntilMillis) return null
        return runCatching {
            val query = String.format(
                Locale.US,
                "[out:json][timeout:2];(nwr(around:135,%.6f,%.6f)[\"name\"];node(around:65,%.6f,%.6f)[\"entrance\"];node(around:65,%.6f,%.6f)[\"barrier\"=\"gate\"];nwr(around:70,%.6f,%.6f)[\"amenity\"~\"parking|parking_entrance\"];);out center tags qt 50;",
                lat, lng, lat, lng, lat, lng, lat, lng
            )
            val encoded = URLEncoder.encode(query, "UTF-8")
            val urlStr = "https://overpass-api.de/api/interpreter?data=$encoded"
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1800
                readTimeout = 2000
                setRequestProperty("User-Agent", "AutoLogueLifeLogApp/1.0 (Android)")
                setRequestProperty("Accept", "application/json")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            }
            try {
                if (conn.responseCode in 200..299) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    selectBestPoiFromOverpassJson(body, lat, lng)
                } else {
                    if (conn.responseCode == 429 || conn.responseCode >= 500) {
                        overpassCooldownUntilMillis = System.currentTimeMillis() + 45_000L
                    }
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            overpassCooldownUntilMillis = System.currentTimeMillis() + 30_000L
        }.getOrNull()
    }

    private fun fetchNominatimReverseGeocode(lat: Double, lng: Double): ResolvedLocation? {
        if (System.currentTimeMillis() < nominatimCooldownUntilMillis) return null
        return runCatching {
            val urlStr = String.format(
                Locale.US,
                "https://nominatim.openstreetmap.org/reverse?format=jsonv2&lat=%.6f&lon=%.6f&zoom=18&addressdetails=1&namedetails=1&accept-language=ko",
                lat, lng
            )
            val conn = (URL(urlStr).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 1600
                readTimeout = 1800
                setRequestProperty("User-Agent", "AutoLogueLifeLogApp/1.0 (Android)")
                setRequestProperty("Accept-Language", "ko-KR,ko;q=0.9")
            }
            try {
                if (conn.responseCode == 200) {
                    val body = conn.inputStream.bufferedReader().use { it.readText() }
                    parseNominatimReverseJson(body, lat, lng)
                } else {
                    nominatimCooldownUntilMillis = System.currentTimeMillis() + 60_000L
                    null
                }
            } finally {
                conn.disconnect()
            }
        }.onFailure {
            nominatimCooldownUntilMillis = System.currentTimeMillis() + 60_000L
        }.getOrNull()
    }

    /**
     * Overpass JSON 응답에서 목표 GPS 좌표에 가장 근접하고 대표성이 높은 명칭을 선택합니다.
     * - 아파트 단지 + 정문/후문/상가주차장/지하주차장 결합 지원
     * - 지하철역 및 출구 번호 결합 지원
     * - 버스정류장, 대형 빌딩/쇼핑몰/관공서/학교/병원, 일반 상호명 지원
     */
    internal fun selectBestPoiFromOverpassJson(
        jsonText: String,
        targetLat: Double,
        targetLng: Double
    ): OverpassPoiResult? {
        val root = MiniJson.parseObject(jsonText) ?: return null
        val elements = root["elements"] as? List<*> ?: return null
        if (elements.isEmpty()) return null

        data class RawElement(
            val lat: Double,
            val lng: Double,
            val distM: Double,
            val name: String,
            val tags: Map<String, String>
        )

        val parsed = mutableListOf<RawElement>()
        var dongHint: String? = null

        for (item in elements) {
            val obj = item as? Map<*, *> ?: continue
            val tagsObj = obj["tags"] as? Map<*, *> ?: emptyMap<Any?, Any?>()
            val tags = LinkedHashMap<String, String>(tagsObj.size)
            for ((k, v) in tagsObj) {
                if (k != null && v != null) tags[k.toString()] = v.toString().trim()
            }

            val centerObj = obj["center"] as? Map<*, *>
            val eLat = MiniJson.asDouble(obj["lat"]) ?: MiniJson.asDouble(centerObj?.get("lat")) ?: continue
            val eLng = MiniJson.asDouble(obj["lon"]) ?: MiniJson.asDouble(centerObj?.get("lon")) ?: continue
            val distM = distanceMeters(targetLat, targetLng, eLat, eLng)

            val rawName = (tags["name:ko"] ?: tags["name"] ?: "").trim()
            if (dongHint == null) {
                val placeType = tags["place"].orEmpty()
                if (rawName.isNotBlank() &&
                    (placeType in setOf("suburb", "quarter", "neighbourhood", "village", "town") || tags["boundary"] == "administrative") &&
                    isDongToken(rawName)
                ) {
                    dongHint = normalizeDongName(rawName)
                } else {
                    val addrDong = listOf(
                        tags["addr:quarter"],
                        tags["addr:suburb"],
                        tags["addr:neighbourhood"],
                        tags["addr:village"],
                        tags["addr:town"]
                    ).firstOrNull { !it.isNullOrBlank() && isDongToken(it) }
                    if (addrDong != null) {
                        dongHint = normalizeDongName(addrDong)
                    }
                }
            }

            parsed.add(RawElement(eLat, eLng, distM, rawName, tags))
        }

        // 1. 주변 지하철역/기차역 이름 파악 (출구 번호 결합용)
        val nearestStationName: String? = parsed
            .filter { el ->
                val railway = el.tags["railway"].orEmpty()
                val pub = el.tags["public_transport"].orEmpty()
                val station = el.tags["station"].orEmpty()
                el.name.isNotBlank() &&
                    (railway in setOf("station", "halt", "tram_stop") ||
                        (pub == "station" && station != "bus") ||
                        station == "subway")
            }
            .minByOrNull { it.distM }
            ?.let { formatStationName(it.name) }

        // 2. 주변 아파트 단지 또는 대형 복합건물 파악 (정문/후문/상가주차장 결합용)
        val parentComplex: RawElement? = parsed
            .filter { el ->
                el.distM <= 140.0 &&
                    el.name.isNotBlank() &&
                    isValidSpecificPlaceName(el.name) &&
                    (el.tags["building"] == "apartments" ||
                        el.tags["residential"] == "apartments" ||
                        (el.tags["landuse"] == "residential" && isApartmentOrComplexName(el.name)) ||
                        isApartmentOrComplexName(el.name))
            }
            .minByOrNull { it.distM }

        val hasNearbyRetailBuilding = parsed.any { el ->
            el.distM <= 45.0 && (
                el.tags["building"] in setOf("retail", "commercial") ||
                    el.tags.containsKey("shop") ||
                    el.name.contains("상가")
                )
        }

        data class ScoredCandidate(
            val displayName: String,
            val distM: Double,
            val weightedScore: Double
        )

        val candidates = mutableListOf<ScoredCandidate>()

        // 3. 아파트/단지 부속 출입구(정문/후문/북문/남문/동문/서문) 및 상가주차장/지하주차장 후보 생성
        if (parentComplex != null) {
            val cleanComplex = cleanSpecificPoiName(parentComplex.name)
            for (el in parsed) {
                if (el.distM > 62.0) continue
                val subLabel = resolveSubFeatureLabel(el.name, el.tags, hasNearbyRetailBuilding)
                if (subLabel != null) {
                    val combined = combineComplexAndSubFeature(cleanComplex, subLabel)
                    candidates.add(
                        ScoredCandidate(
                            displayName = combined,
                            distM = el.distM,
                            weightedScore = el.distM * 0.62
                        )
                    )
                }
            }
        }

        // 4. 개별 지점 평가
        for (el in parsed) {
            val tags = el.tags
            val highway = tags["highway"].orEmpty()
            val railway = tags["railway"].orEmpty()
            val pubTransport = tags["public_transport"].orEmpty()
            val building = tags["building"].orEmpty()
            val amenity = tags["amenity"].orEmpty()
            val shop = tags["shop"].orEmpty()
            val office = tags["office"].orEmpty()
            val tourism = tags["tourism"].orEmpty()
            val leisure = tags["leisure"].orEmpty()
            val boundary = tags["boundary"].orEmpty()
            val place = tags["place"].orEmpty()

            // 행정경계 및 단순 도로 선형 객체 제외
            if (boundary == "administrative") continue
            if (place in setOf("country", "province", "state", "city", "county", "borough", "municipality", "suburb", "quarter", "neighbourhood", "village", "town")) {
                continue
            }
            val isBusStop = highway == "bus_stop" ||
                (pubTransport in setOf("platform", "stop_position") && (tags["bus"] == "yes" || railway.isBlank()))
            if (highway.isNotBlank() && !isBusStop && building.isBlank() && amenity.isBlank() && shop.isBlank()) {
                continue
            }

            // (A-0) 우리집 기준 동(7101동) 감지 시 최우선으로 "집" 채택
            if (el.distM <= 85.0 && (
                    containsHomeBuilding7101(el.name) ||
                        containsHomeBuilding7101(tags["addr:housename"]) ||
                        containsHomeBuilding7101(tags["addr:unit"])
                )
            ) {
                candidates.add(ScoredCandidate("집", el.distM, el.distM * 0.50))
                continue
            }

            // (A) 지하철 출구
            if (railway == "subway_entrance" && el.distM <= 80.0) {
                val exitLabel = formatSubwayEntranceName(el.name, tags["ref"], nearestStationName)
                if (exitLabel != null) {
                    candidates.add(ScoredCandidate(exitLabel, el.distM, el.distM * 0.68))
                }
                continue
            }

            if (el.name.isBlank()) continue
            val cleanName = cleanSpecificPoiName(el.name)
            if (cleanName.isBlank()) continue
            if (cleanName == "집") {
                if (el.distM <= 85.0) {
                    candidates.add(ScoredCandidate("집", el.distM, el.distM * 0.50))
                }
                continue
            }

            // 단독 "정문"/"후문"/"상가주차장" 등은 상위 단지와 이미 결합 처리했으므로 단독 노출 제외
            if (isSubEntranceOrParkingOnly(cleanName)) {
                if (parentComplex != null && el.distM <= 62.0) {
                    val combined = combineComplexAndSubFeature(cleanSpecificPoiName(parentComplex.name), cleanName)
                    candidates.add(ScoredCandidate(combined, el.distM, el.distM * 0.62))
                }
                continue
            }

            // 단지 내 동 번호만 있는 경우(예: "104동", "3102동") 상위 아파트 단지명과 결합 (7101동은 위에서 "집"으로 처리됨)
            if (Regex("^[0-9]{2,4}동$").matches(cleanName)) {
                if (parentComplex != null && el.distM <= 70.0) {
                    val aptBase = cleanSpecificPoiName(parentComplex.name)
                    val combinedApt = if (aptBase == "집") "집" else "$aptBase $cleanName"
                    candidates.add(ScoredCandidate(combinedApt, el.distM, el.distM * 0.72))
                }
                continue
            }

            if (!isValidSpecificPlaceName(cleanName)) continue

            // (B) 지하철역·기차역
            if ((railway in setOf("station", "halt", "tram_stop") || (pubTransport == "station" && tags["bus"] != "yes") || tags["station"] == "subway") &&
                el.distM <= 145.0
            ) {
                val stName = formatStationName(cleanName)
                candidates.add(ScoredCandidate(stName, el.distM, el.distM * 0.70))
                continue
            }

            // (C) 버스정류장
            if (isBusStop && el.distM <= 70.0) {
                val stopName = formatBusStopName(cleanName)
                candidates.add(ScoredCandidate(stopName, el.distM, el.distM * 0.88))
                continue
            }

            // (D) 대형 건물 / 백화점 / 쇼핑몰 / 관공서 / 병원 / 학교 / 골프장 / 주요 공원
            if (isMajorLandmarkOrBuilding(cleanName, tags) && el.distM <= 135.0) {
                candidates.add(ScoredCandidate(cleanName, el.distM, el.distM * 0.74))
                continue
            }

            // (E) 아파트 단지
            if ((building == "apartments" || tags["residential"] == "apartments" || isApartmentOrComplexName(cleanName)) &&
                el.distM <= 125.0
            ) {
                candidates.add(ScoredCandidate(cleanName, el.distM, el.distM * 0.78))
                continue
            }

            // (F) 근접 상호명 (매장, 카페, 식당, 주유소, 병원, 약국, 은행, 오피스 등)
            if ((shop.isNotBlank() || amenity.isNotBlank() || office.isNotBlank() || tourism.isNotBlank() || leisure.isNotBlank()) &&
                el.distM <= 65.0
            ) {
                candidates.add(ScoredCandidate(cleanName, el.distM, el.distM * 0.84))
                continue
            }

            // (G) 일반 명명 건물
            if (building.isNotBlank() && el.distM <= 85.0) {
                candidates.add(ScoredCandidate(cleanName, el.distM, el.distM * 0.92))
            }
        }

        val best = candidates.minByOrNull { it.weightedScore }
        return if (best != null) {
            OverpassPoiResult(
                placeName = best.displayName,
                dongHint = dongHint,
                distanceMeters = best.distM
            )
        } else if (dongHint != null) {
            OverpassPoiResult(placeName = "", dongHint = dongHint, distanceMeters = 0.0)
        } else {
            null
        }
    }

    /**
     * Nominatim zoom=18 역지오코딩 JSON 파서:
     * 구체 건물·역·정류장·상호·아파트명이 있으면 최우선 채택하고, 없으면 순수 동 이름을 채택합니다.
     */
    internal fun parseNominatimReverseJson(
        jsonText: String,
        lat: Double,
        lng: Double
    ): ResolvedLocation? {
        val json = MiniJson.parseObject(jsonText) ?: return null
        val addrObj = json["address"] as? Map<*, *>
        val displayName = (json["display_name"] as? String).orEmpty().trim()
        val category = (json["category"] as? String).orEmpty().trim()
        val type = (json["type"] as? String).orEmpty().trim()
        val topName = (json["name"] as? String).orEmpty().trim()

        if (containsHomeBuilding7101(topName) || containsHomeBuilding7101(displayName)) {
            val cleanAddr = sanitizeAddressForDisplay(displayName) ?: "집"
            return ResolvedLocation(
                placeName = "집",
                address = cleanAddr,
                latitude = lat,
                longitude = lng
            )
        }

        val poiCandidates = mutableListOf<String>()
        if (topName.isNotBlank() && category != "boundary" && !(category == "highway" && type != "bus_stop" && type != "platform")) {
            if (category == "highway" && (type == "bus_stop" || type == "platform")) {
                poiCandidates.add(formatBusStopName(topName))
            } else if (category == "railway" && (type == "station" || type == "halt")) {
                poiCandidates.add(formatStationName(topName))
            } else {
                poiCandidates.add(topName)
            }
        }

        if (addrObj != null) {
            val poiKeys = listOf("railway", "amenity", "shop", "building", "office", "tourism", "leisure", "historic", "commercial", "retail")
            for (k in poiKeys) {
                val v = (addrObj[k] as? String)?.trim().orEmpty()
                if (v.isNotBlank()) poiCandidates.add(v)
            }
            val hwVal = (addrObj["highway"] as? String)?.trim().orEmpty()
            if (hwVal.isNotBlank() && (type == "bus_stop" || hwVal.endsWith("정류장") || hwVal.endsWith("정류소"))) {
                poiCandidates.add(formatBusStopName(hwVal))
            }
            val resVal = (addrObj["residential"] as? String)?.trim().orEmpty()
            if (resVal.isNotBlank() && isApartmentOrComplexName(resVal)) {
                poiCandidates.add(resVal)
            }
        }

        val specificPoi = poiCandidates
            .map { cleanSpecificPoiName(it) }
            .firstOrNull { isValidSpecificPlaceName(it) }

        val rawDong = if (addrObj != null) {
            listOf("quarter", "suburb", "neighbourhood", "village", "town")
                .mapNotNull { (addrObj[it] as? String)?.trim() }
                .firstOrNull { it.isNotBlank() }
        } else null

        val dong = normalizeDongName(rawDong) ?: extractPureDongFromText(displayName)
        val city = if (addrObj != null) {
            ((addrObj["city"] as? String)?.ifBlank { null }
                ?: (addrObj["county"] as? String)?.ifBlank { null }
                ?: (addrObj["borough"] as? String)?.ifBlank { null }).orEmpty().trim()
        } else ""

        val chosenPlace = when {
            !specificPoi.isNullOrBlank() -> specificPoi
            !dong.isNullOrBlank() -> dong
            city.isNotBlank() -> cleanDisplayPlaceName(city, displayName)
            else -> ""
        }

        if (chosenPlace.isBlank()) return null
        val sanitizedDisplay = sanitizeAddressForDisplay(displayName).orEmpty()
        return ResolvedLocation(
            placeName = chosenPlace,
            address = sanitizedDisplay.ifBlank { chosenPlace },
            latitude = lat,
            longitude = lng
        )
    }

    companion object {
        private const val MAX_CACHE_SIZE = 1200
        private const val PREFS_NAME = "place_poi_cache_v4"
        private const val LEGACY_PREFS_NAME_V3 = "place_poi_cache_v3"
        private const val CACHE_SEP = "\u001F"

        private val HOME_BUILDING_7101_REGEX = Regex("(^|[^0-9])7101\\s*동($|[^0-9가-힣])")
        private val BUILDING_UNIT_NUMBER_REGEX = Regex(
            "(?:(?<![0-9A-Za-z가-힣])|(?<=[동층]))(?:제\\s*)?(?:지하\\s*|B|b)?[0-9]{1,5}(?:-[0-9]{1,4})?\\s*호(?:실)?(?![가-힣])"
        )
        private val PAREN_BUILDING_UNIT_NUMBER_REGEX = Regex(
            "\\(\\s*(?:제\\s*)?(?:지하\\s*|B|b)?[0-9]{1,5}(?:-[0-9]{1,4})?\\s*호(?:실)?\\s*\\)"
        )

        private val ADMIN_PROVINCE_TOKENS = setOf(
            "대한민국", "한국",
            "서울", "서울시", "서울특별시",
            "경기", "경기도",
            "인천", "인천시", "인천광역시",
            "부산", "부산시", "부산광역시",
            "대구", "대구시", "대구광역시",
            "광주", "광주시", "광주광역시",
            "대전", "대전시", "대전광역시",
            "울산", "울산시", "울산광역시",
            "세종", "세종시", "세종특별자치시",
            "강원", "강원도", "강원특별자치도",
            "충북", "충청북", "충청북도",
            "충남", "충청남", "충청남도",
            "전북", "전라북", "전라북도", "전북특별자치도",
            "전남", "전라남", "전라남도",
            "경북", "경상북", "경상북도",
            "경남", "경상남", "경상남도",
            "제주", "제주도", "제주특별자치도"
        )

        private val PLACEHOLDER_NAMES = setOf(
            "사진 기록", "일반 사진", "일상 기록", "위치 정보", "기록된 장소",
            "결제 위치 정보", "출발 인근", "도착 인근", "출발지", "도착지"
        )

        private val APARTMENT_KEYWORDS = listOf(
            "아파트", "더샵", "힐스테이트", "자이", "래미안", "푸르지오", "아이파크",
            "롯데캐슬", "아크로", "e편한세상", "이편한세상", "호반", "우미린", "중흥",
            "포레나", "센트럴", "벨라체", "위례포레", "트리지움", "리센츠", "엘스",
            "파크리오", "헬리오시티", "파크하비오", "오피스텔", "주상복합", "타운하우스"
        )

        private val MAJOR_BUILDING_KEYWORDS = listOf(
            "스타필드", "트레이더스", "타임스퀘어", "백화점", "아울렛", "이마트", "홈플러스",
            "코스트코", "롯데몰", "롯데월드", "코엑스", "파크원", "IFC", "타워", "빌딩",
            "센터", "스퀘어", "플라자", "병원", "대학교", "학교", "시청", "구청",
            "주민센터", "행정복지센터", "경찰서", "소방서", "우체국", "도서관", "박물관",
            "미술관", "체육관", "운동장", "터미널", "공항", "킨텍스", "벡스코",
            "CC", "GC", "골프", "컨트리클럽", "리조트", "호텔"
        )

        /**
         * 사용자 거주 동('7101동')이 포함된 텍스트인지 판별합니다.
         */
        fun containsHomeBuilding7101(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            return HOME_BUILDING_7101_REGEX.containsMatchIn(text)
        }

        /**
         * 건물·아파트 세대 호수('2502호', '1604호' 등)가 포함되어 있는지 판별합니다.
         */
        fun containsBuildingUnitNumber(text: String?): Boolean {
            if (text.isNullOrBlank()) return false
            return BUILDING_UNIT_NUMBER_REGEX.containsMatchIn(text) ||
                PAREN_BUILDING_UNIT_NUMBER_REGEX.containsMatchIn(text)
        }

        /**
         * 장소명에서 아파트·건물 세대 호수('2502호', '1604호' 등)를 완전히 제거하고,
         * 7101동이 포함된 경우 즉시 '집'으로 변환합니다 ('9호선', '2호점', '호수공원'은 보존).
         */
        fun stripBuildingUnitNumbers(raw: String): String {
            if (raw.isBlank()) return ""
            if (containsHomeBuilding7101(raw)) return "집"
            return raw
                .replace(PAREN_BUILDING_UNIT_NUMBER_REGEX, " ")
                .replace(BUILDING_UNIT_NUMBER_REGEX, " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd(',', '.', '·', '/', '-')
                .trim()
        }

        /**
         * 상세 주소(address) 표시용 문자열에서 모든 호수 표기를 제거하고 7101동을 '집'으로 정제합니다.
         */
        fun sanitizeAddressForDisplay(rawAddress: String?): String? {
            if (rawAddress.isNullOrBlank()) return rawAddress
            val sanitized = rawAddress
                .replace(PAREN_BUILDING_UNIT_NUMBER_REGEX, " ")
                .replace(BUILDING_UNIT_NUMBER_REGEX, " ")
                .replace(HOME_BUILDING_7101_REGEX, "$1집$2")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd(',', '.', '·', '-')
                .trim()
            if (sanitized.isBlank() || sanitized == "대한민국") return null
            return sanitized
        }

        /**
         * 정류장·정거장·역 명칭에 2곳이 구분자('.', ',', '/', '·', 'ㆍ', '|')로 함께 병기된 경우
         * 앞쪽의 첫 번째 명칭만 남깁니다 (예: "위례포레자이.위례더샵포레스트 정류장" -> "위례포레자이 정류장").
         */
        fun splitDualStationOrStopName(raw: String, forceStopSuffix: Boolean = false): String {
            var working = raw.trim()
            if (working.isBlank()) return ""
            working = working.replace(Regex("\\s*\\([0-9]{2,3}-?[0-9]{2,5}\\)\\s*$"), "").trim()
            if (working.isBlank()) return ""

            val stopSuffixMatch = Regex("\\s*(버스정류장|버스정류소|정류장|정류소|정거장|환승센터)\\s*$").find(working)
            val hadStopSuffix = forceStopSuffix || stopSuffixMatch != null
            val normalizedStopSuffix = when {
                stopSuffixMatch != null -> when (stopSuffixMatch.groupValues[1]) {
                    "버스정류장", "버스정류소", "정거장" -> "정류장"
                    else -> stopSuffixMatch.groupValues[1]
                }
                forceStopSuffix -> "정류장"
                else -> ""
            }
            val core = if (stopSuffixMatch != null) {
                working.substring(0, stopSuffixMatch.range.first).trim()
            } else {
                working
            }
            if (core.isBlank()) return working

            // 골프장 약어(C.C / G.C) 내부 마침표는 보호
            val protectedCore = core.replace(Regex("([CcGg])\\.([Cc])(?![A-Za-z])"), "$1\u0007$2")
            val dualDelimiterRegex = Regex("(?<=\\S)\\s*(?:\\.(?![0-9])|,|/|·|ㆍ|\\|)\\s*(?=\\S)")

            val firstPart = if (dualDelimiterRegex.containsMatchIn(protectedCore)) {
                val parts = protectedCore.split(dualDelimiterRegex)
                    .map { it.replace('\u0007', '.').trim() }
                    .filter { it.isNotBlank() }
                if (parts.size >= 2 &&
                    parts[0].length >= 2 &&
                    parts[0] !in ADMIN_PROVINCE_TOKENS &&
                    !isSiGunGuToken(parts[0])
                ) {
                    parts[0]
                } else {
                    core
                }
            } else {
                core
            }

            return if (hadStopSuffix) {
                if (firstPart.endsWith("정류장") || firstPart.endsWith("정류소") ||
                    firstPart.endsWith("환승센터") || firstPart.endsWith("터미널")
                ) {
                    firstPart
                } else {
                    "$firstPart $normalizedStopSuffix".trim()
                }
            } else {
                firstPart
            }
        }

        internal fun normalizeDongName(rawDong: String?): String? {
            if (rawDong.isNullOrBlank()) return null
            val trimmed = rawDong.trim()
            val dongGaMatch = Regex("^([가-힣]{2,}동)[0-9]+가$").find(trimmed)
            if (dongGaMatch != null) {
                return dongGaMatch.groupValues[1]
            }
            val numberedDongMatch = Regex("^([가-힣]{2,})[0-9]+동$").find(trimmed)
            if (numberedDongMatch != null) {
                return "${numberedDongMatch.groupValues[1]}동"
            }
            return trimmed
        }

        internal fun isDongToken(token: String): Boolean {
            val t = token.trim()
            if (t.length < 2) return false
            if (Regex("^[0-9]+동$").matches(t)) return false // 아파트 동 번호(101동 등) 제외
            if (t.endsWith("동로") || t.endsWith("동길") || t.endsWith("운동장") || t.endsWith("공동") || t.endsWith("빌딩")) {
                return false
            }
            return t.endsWith("동") || t.endsWith("읍") || t.endsWith("면") ||
                Regex("^[가-힣]{2,}동[0-9]+가$").matches(t) ||
                Regex("^[가-힣]{2,}[0-9]+가$").matches(t)
        }

        private fun isSiGunGuToken(token: String): Boolean {
            val t = token.trim()
            if (t in ADMIN_PROVINCE_TOKENS) return true
            if (t.length < 2) return false
            if (t.endsWith("입구") || t.endsWith("출구") || t.endsWith("지구") || t.endsWith("구장") || t.endsWith("문구") || t.endsWith("가구")) {
                return false
            }
            return (t.endsWith("시") || t.endsWith("군") || t.endsWith("구")) &&
                Regex("^[가-힣]+(?:시|군|구)$").matches(t)
        }

        private fun isRoadOrLotToken(token: String): Boolean {
            val t = token.trim()
            if (t.isBlank()) return true
            if (containsHomeBuilding7101(t)) return false
            if (Regex("^(?:산\\s*|제\\s*|지하\\s*|B|b)?[0-9]+(?:-[0-9]+)?(?:번지|동|호|호실|층)?$").matches(t)) return true
            if (Regex("^[가-힣0-9]+(?:대로|로|길)(?:[0-9]*번?길)?$").matches(t) && !t.endsWith("정류장")) return true
            return false
        }

        /**
         * 임의의 주소/지명 텍스트에서 순수 동(읍·면·동) 이름만 추출합니다.
         * 예: "대한민국 서울특별시 영등포구 영등포동3가 2-21" -> "영등포동"
         *     "하남시 학암동" -> "학암동"
         */
        fun extractPureDongFromText(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val tokens = text.split(" ", ",", "(", ")", "·")
                .map { it.trim() }
                .filter { it.isNotBlank() }

            val directDong = tokens.lastOrNull { isDongToken(it) }
            if (directDong != null) {
                return normalizeDongName(directDong)
            }

            val regex = Regex("([가-힣]{2,}(?:동[0-9]+가|[0-9]*동|읍|면))(?=\\s|[0-9]|$|,|\\))")
            val matches = regex.findAll(text).map { it.groupValues[1] }.toList()
            val matched = matches.lastOrNull { isDongToken(it) }
            if (matched != null) {
                return normalizeDongName(matched)
            }
            return null
        }

        private val GENERIC_STANDALONE_FACILITY_WORDS = setOf(
            "정류장", "정류소", "버스정류장", "환승센터", "터미널",
            "주민센터", "행정복지센터", "보건소", "우체국", "파출소", "지구대",
            "경찰서", "소방서", "세무서", "시청", "구청", "군청",
            "도서관", "체육관", "운동장", "복지관", "회관", "문화센터",
            "공원", "시장", "성당", "교회", "사거리", "삼거리", "오거리", "교차로",
            "초등학교", "중학교", "고등학교", "유치원", "어린이집",
            "입구", "출구", "정문", "후문", "북문", "남문", "동문", "서문",
            "주차장", "상가주차장", "지하주차장", "상가"
        )

        private val ABBREVIATED_SI_GUN_GU_TOKENS = setOf(
            "성남", "하남", "수원", "용인", "고양", "부천", "안양", "안산", "남양주", "화성",
            "평택", "의정부", "시흥", "파주", "김포", "광명", "군포", "오산", "이천", "안성",
            "의왕", "양주", "여주", "과천", "구리", "포천", "동두천", "양평", "가평", "연천",
            "분당", "일산", "판교", "위례", "동탄",
            "강남", "서초", "송파", "강동", "영등포", "마포", "용산", "종로", "성동", "광진",
            "동대문", "중랑", "성북", "강북", "도봉", "노원", "은평", "서대문", "양천", "강서",
            "구로", "금천", "동작", "관악",
            "춘천", "원주", "강릉", "속초", "청주", "충주", "천안", "아산", "전주", "군산",
            "익산", "포항", "경주", "구미", "창원", "김해", "진주", "양산", "여수", "순천",
            "목포", "서귀포"
        )

        private fun isContextualAdminToken(tokens: List<String>, index: Int): Boolean {
            val tok = tokens[index].trimEnd(',')
            if (tok in ADMIN_PROVINCE_TOKENS || isSiGunGuToken(tok) || isDongToken(tok) || isRoadOrLotToken(tok)) {
                return true
            }
            if (tok in ABBREVIATED_SI_GUN_GU_TOKENS && index < tokens.lastIndex) {
                val next = tokens[index + 1].trimEnd(',')
                if (isSiGunGuToken(next) || isDongToken(next) || next in ABBREVIATED_SI_GUN_GU_TOKENS) {
                    return true
                }
            }
            return false
        }

        internal fun stripLeadingAdminTokens(raw: String): String {
            val tokens = raw.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
            if (tokens.size <= 1) return raw.trim()

            var idx = 0
            while (idx < tokens.lastIndex) {
                if (isContextualAdminToken(tokens, idx)) {
                    val remaining = tokens.subList(idx + 1, tokens.size)
                    // 다음 토큰만 남겼을 때 일반 시설 접미어만 남는 경우(예: "학암동 정류장", "영등포동 주민센터") 직전 지명 보존
                    if (remaining.size == 1 && remaining[0] in GENERIC_STANDALONE_FACILITY_WORDS) {
                        break
                    }
                    idx++
                } else {
                    break
                }
            }
            return tokens.subList(idx, tokens.size).joinToString(" ").trim()
        }

        internal fun cleanSpecificPoiName(raw: String): String {
            if (containsHomeBuilding7101(raw)) return "집"
            val unitStripped = stripBuildingUnitNumbers(raw)
            if (unitStripped.isBlank()) return ""
            if (unitStripped == "집") return "집"
            val adminStripped = stripLeadingAdminTokens(
                unitStripped.replace(Regex("\\s+"), " ").trim()
            )
            return splitDualStationOrStopName(adminStripped, forceStopSuffix = false)
        }

        internal fun isSubEntranceOrParkingOnly(name: String): Boolean {
            val t = name.trim()
            return t in setOf(
                "정문", "후문", "북문", "남문", "동문", "서문", "부문",
                "상가주차장", "상가 주차장", "지하주차장", "지상주차장", "방문자주차장",
                "주차장 입구", "주차장출입구", "주차장", "상가", "단지내상가", "근린상가",
                "정문 경비실", "후문 경비실"
            )
        }

        internal fun combineComplexAndSubFeature(complexName: String, subFeature: String?): String {
            if (containsHomeBuilding7101(complexName) || containsHomeBuilding7101(subFeature)) return "집"
            val base = stripBuildingUnitNumbers(complexName).trim()
            val sub = subFeature?.let { stripBuildingUnitNumbers(it) }?.trim().orEmpty()
            if (base == "집" || sub == "집") return "집"
            if (sub.isBlank()) return base
            val normalizedSub = sub.replace("상가 주차장", "상가주차장")
                .replace("정문 경비실", "정문")
                .replace("후문 경비실", "후문")
            if (base.contains(normalizedSub)) return base
            if (normalizedSub.contains(base)) return normalizedSub
            return "$base $normalizedSub"
        }

        private fun resolveSubFeatureLabel(
            rawName: String,
            tags: Map<String, String>,
            hasNearbyRetailBuilding: Boolean
        ): String? {
            val n = rawName.trim()
            if (n.isNotBlank()) {
                when {
                    n.contains("상가주차") || n.contains("상가 주차") -> return "상가주차장"
                    n.contains("지하주차") -> return "지하주차장"
                    n.contains("정문") -> return if (n.length <= 8) n.replace(" 경비실", "") else "정문"
                    n.contains("후문") -> return if (n.length <= 8) n.replace(" 경비실", "") else "후문"
                    n.contains("북문") -> return "북문"
                    n.contains("남문") -> return "남문"
                    n.contains("동문") -> return "동문"
                    n.contains("서문") -> return "서문"
                    n == "상가" || n == "단지내상가" || n == "근린상가" -> return "상가"
                }
            }
            val barrier = tags["barrier"].orEmpty()
            val entrance = tags["entrance"].orEmpty()
            val amenity = tags["amenity"].orEmpty()
            val parking = tags["parking"].orEmpty()

            if (barrier == "gate" || entrance.isNotBlank()) {
                if (entrance == "main") return "정문"
                if (entrance == "secondary") return "후문"
            }
            if (amenity == "parking" || amenity == "parking_entrance") {
                if (hasNearbyRetailBuilding) return "상가주차장"
                if (parking == "underground") return "지하주차장"
            }
            return null
        }

        private fun formatStationName(raw: String): String {
            val cleaned = splitDualStationOrStopName(
                raw
                    .replace(Regex("\\([^)]*호선\\)"), "")
                    .replace(Regex("\\s+[0-9]+호선$"), "")
                    .trim(),
                forceStopSuffix = false
            )
            if (cleaned.isBlank()) return ""
            return if (cleaned.endsWith("역") || cleaned.endsWith("터미널") || cleaned.endsWith("환승센터")) {
                cleaned
            } else {
                "${cleaned}역"
            }
        }

        private fun formatSubwayEntranceName(rawName: String, ref: String?, stationName: String?): String? {
            val n = rawName.trim()
            if (n.contains("역") && n.contains("출구")) return n
            val exitNum = Regex("([0-9]+(?:-[0-9]+)?)\\s*번?\\s*(?:출구|출입구)?").find(n.ifBlank { ref.orEmpty() })
                ?.groupValues?.get(1)
            return when {
                !stationName.isNullOrBlank() && !exitNum.isNullOrBlank() -> "$stationName ${exitNum}번출구"
                !stationName.isNullOrBlank() -> stationName
                n.contains("출구") && n.length >= 4 -> n
                else -> null
            }
        }

        private fun formatBusStopName(raw: String): String {
            return splitDualStationOrStopName(raw, forceStopSuffix = true)
        }

        internal fun isApartmentOrComplexName(name: String): Boolean {
            return APARTMENT_KEYWORDS.any { name.contains(it, ignoreCase = true) }
        }

        private fun isMajorLandmarkOrBuilding(name: String, tags: Map<String, String>): Boolean {
            if (MAJOR_BUILDING_KEYWORDS.any { name.contains(it, ignoreCase = true) }) return true
            val building = tags["building"].orEmpty()
            val amenity = tags["amenity"].orEmpty()
            val shop = tags["shop"].orEmpty()
            return building in setOf("commercial", "office", "civic", "public", "hospital", "university", "school", "hotel", "train_station", "transportation") ||
                shop in setOf("mall", "department_store", "supermarket", "wholesale") ||
                amenity in setOf("hospital", "university", "school", "townhall", "public_building", "police", "fire_station", "bus_station")
        }

        /**
         * 후보 문자열이 행정구역·도로명·번지수·호수·플러스코드·더미 문자열이 아닌
         * 실제 대표 건물/역/버스정류장/상호명/아파트명인지 검증합니다.
         */
        fun isValidSpecificPlaceName(candidate: String?): Boolean {
            if (candidate.isNullOrBlank()) return false
            val rawTrimmed = candidate.trim()
            if (containsHomeBuilding7101(rawTrimmed)) return true
            val t = stripBuildingUnitNumbers(rawTrimmed)
            if (t.length < 2) return false
            if (t in PLACEHOLDER_NAMES || t.startsWith("위도 ")) return false
            if (t == "집" || t == "우리집" || t == "회사") return true
            if (!Regex("[가-힣a-zA-Z]").containsMatchIn(t)) return false
            // Plus Code 배제 (예: "F4HW+9C", "8Q98F4HW+9C")
            if (Regex("^[23456789CFGHJMPQRVWX]{4,8}\\+[23456789CFGHJMPQRVWX]{2,}.*$", RegexOption.IGNORE_CASE).matches(t)) {
                return false
            }
            // 순수 번지수·층·호수·아파트동번호·우편번호 배제
            if (Regex("^(?:산\\s*|제\\s*|지하\\s*|B|b)?[0-9]+(?:-[0-9]+)?(?:번지|호|호실|층|동)?$").matches(t)) return false
            // 순수 도로명 또는 도로명+번지 배제 (예: "위례대로6길 20", "영등포로 254")
            if (Regex("^[가-힣0-9]+(?:대로|로|길)(?:[0-9]*번?길)?(?:\\s+[0-9]+(?:-[0-9]+)?)?$").matches(t) &&
                !t.endsWith("정류장") && !t.endsWith("역")
            ) {
                return false
            }

            // 모든 토큰이 행정구역(도/시/군/구/읍/면/동 및 약칭) 또는 도로명·번지·동/호수로만 이루어진 경우 구체 POI가 아님
            val tokens = t.split(Regex("[\\s,]+")).filter { it.isNotBlank() }
            if (tokens.isEmpty()) return false
            val allAdminOrAddress = tokens.indices.all { i -> isContextualAdminToken(tokens, i) }
            return !allAdminOrAddress
        }

        /**
         * 기존 DB나 캐시에 저장된 지명이 구체 건물/상호/역/정류장/아파트명이 아닌
         * 구형 행정구역, 미정제 7101동/세대 호수, 병기 정류장명 또는 플레이스홀더인지 판별합니다.
         */
        fun isAdminOrPlaceholderPlaceName(rawPlace: String?): Boolean {
            if (rawPlace.isNullOrBlank()) return true
            val t = rawPlace.trim()
            if (t == "집" || t == "우리집" || t == "회사") return false
            if (containsHomeBuilding7101(t) || containsBuildingUnitNumber(t)) return true
            if (splitDualStationOrStopName(t, forceStopSuffix = false) != t) return true
            return !isValidSpecificPlaceName(t)
        }

        /**
         * UI 및 집계기 전반에서 위치/항목명을 표기할 때 사용하는 표준 정제 함수:
         * 1. '7101동'은 언제나 '집'으로 표기하며, 모든 세대 호수('XXXX호') 표기를 제거
         * 2. 정류장/역 병기 명칭('.', ',', '/', '·' 등)은 앞쪽 첫 번째 명칭만 유지
         * 3. 구체 건물·역·정류장·상호·아파트(정문/후문/상가주차장 포함) 명칭이면 시·구 행정구역 접두어만 제거하고 원형 유지
         * 4. 구체 명칭이 없는 행정구역 문자열이면 시·구 접두어를 제거한 순수 '동' 이름(예: "학암동", "영등포동")만 반환
         */
        fun cleanDisplayPlaceName(rawPlace: String?, addressHint: String? = null): String {
            val raw = rawPlace?.trim().orEmpty()
            if (raw.isBlank() && addressHint.isNullOrBlank()) return ""
            if (raw == "집" || raw == "우리집") return "집"
            if (raw == "회사") return "회사"
            if (containsHomeBuilding7101(raw)) return "집"

            val unitStrippedRaw = stripBuildingUnitNumbers(raw)
            if (unitStrippedRaw == "집") return "집"

            if (!addressHint.isNullOrBlank() && containsHomeBuilding7101(addressHint)) {
                if (unitStrippedRaw.isBlank() ||
                    unitStrippedRaw in PLACEHOLDER_NAMES ||
                    !isValidSpecificPlaceName(unitStrippedRaw) ||
                    isApartmentOrComplexName(unitStrippedRaw) ||
                    Regex("^[0-9]{2,4}동$").matches(unitStrippedRaw)
                ) {
                    return "집"
                }
            }

            if (unitStrippedRaw in PLACEHOLDER_NAMES) {
                val hintDong = extractPureDongFromText(addressHint)
                return hintDong ?: unitStrippedRaw
            }

            // 1. raw 자체에 구체 POI 명칭이 들어 있는 경우
            if (unitStrippedRaw.isNotBlank() && isValidSpecificPlaceName(unitStrippedRaw)) {
                return cleanSpecificPoiName(unitStrippedRaw)
            }

            // 2. addressHint 말미에 구체 건물명이 들어 있는 경우 (예: "... 위례대로 200 스타필드시티 위례")
            if (!addressHint.isNullOrBlank()) {
                if (containsHomeBuilding7101(addressHint)) return "집"
                val sanitizedHint = stripBuildingUnitNumbers(addressHint.trim())
                val tailAfterRoad = Regex("(?:대로|로|길)\\s+[0-9]+(?:-[0-9]+)?\\s+(.+)$")
                    .find(sanitizedHint)?.groupValues?.get(1)?.trim()
                if (!tailAfterRoad.isNullOrBlank() && isValidSpecificPlaceName(tailAfterRoad)) {
                    return cleanSpecificPoiName(tailAfterRoad)
                }
            }

            // 3. 명확히 가까운 대표 명칭이 없을 경우 순수 동(읍·면·동) 이름으로 표기
            extractPureDongFromText(unitStrippedRaw)?.let { return it }
            extractPureDongFromText(addressHint)?.let { return it }

            // 4. 동 정보조차 없는 광역/시·군 단일 텍스트인 경우 도·특별시 접두어만 제거
            val tokens = unitStrippedRaw.split(Regex("[\\s,]+"))
                .map { it.trim() }
                .filter { it.isNotBlank() && it !in ADMIN_PROVINCE_TOKENS && !isRoadOrLotToken(it) }
            if (tokens.isNotEmpty()) {
                val nonRoad = tokens.lastOrNull { !Regex("^[0-9]+(?:-[0-9]+)?$").matches(it) }
                if (!nonRoad.isNullOrBlank()) return nonRoad
            }
            return unitStrippedRaw
        }

        internal fun distanceMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val r = 6_371_000.0
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLng / 2) * sin(dLng / 2)
            val c = 2 * atan2(sqrt(a), sqrt(1.0 - a))
            return r * c
        }
    }
}

/**
 * Android 프레임워크의 org.json.JSONObject 의존성 없이
 * Android 런타임과 로컬 JVM 단위 테스트 양쪽에서 동일하게 동작하는 경량 JSON 파서.
 */
internal object MiniJson {
    fun parseObject(text: String): Map<String, Any?>? {
        return runCatching { Parser(text).parseRoot() as? Map<String, Any?> }.getOrNull()
    }

    fun asDouble(value: Any?): Double? = when (value) {
        is Number -> value.toDouble()
        is String -> value.toDoubleOrNull()
        else -> null
    }

    private class Parser(private val src: String) {
        private var pos = 0
        private val len = src.length

        fun parseRoot(): Any? {
            skipWs()
            return if (pos < len) parseValue() else null
        }

        private fun skipWs() {
            while (pos < len && src[pos].isWhitespace()) pos++
        }

        private fun parseValue(): Any? {
            skipWs()
            if (pos >= len) return null
            return when (src[pos]) {
                '{' -> parseObj()
                '[' -> parseArr()
                '"' -> parseStr()
                't', 'f' -> parseBool()
                'n' -> parseNull()
                else -> parseNum()
            }
        }

        private fun parseObj(): Map<String, Any?> {
            val map = LinkedHashMap<String, Any?>()
            pos++ // '{'
            skipWs()
            if (pos < len && src[pos] == '}') {
                pos++
                return map
            }
            while (pos < len) {
                skipWs()
                if (pos >= len || src[pos] != '"') break
                val key = parseStr()
                skipWs()
                if (pos < len && src[pos] == ':') pos++
                val value = parseValue()
                map[key] = value
                skipWs()
                if (pos < len && src[pos] == ',') {
                    pos++
                } else if (pos < len && src[pos] == '}') {
                    pos++
                    break
                } else {
                    break
                }
            }
            return map
        }

        private fun parseArr(): List<Any?> {
            val list = ArrayList<Any?>()
            pos++ // '['
            skipWs()
            if (pos < len && src[pos] == ']') {
                pos++
                return list
            }
            while (pos < len) {
                list.add(parseValue())
                skipWs()
                if (pos < len && src[pos] == ',') {
                    pos++
                } else if (pos < len && src[pos] == ']') {
                    pos++
                    break
                } else {
                    break
                }
            }
            return list
        }

        private fun parseStr(): String {
            pos++ // '"'
            val sb = StringBuilder()
            while (pos < len) {
                val ch = src[pos++]
                if (ch == '"') break
                if (ch == '\\' && pos < len) {
                    when (val esc = src[pos++]) {
                        '"', '\\', '/' -> sb.append(esc)
                        'b' -> sb.append('\b')
                        'f' -> sb.append('\u000C')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'u' -> {
                            if (pos + 4 <= len) {
                                val hex = src.substring(pos, pos + 4)
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                pos += 4
                            }
                        }
                        else -> sb.append(esc)
                    }
                } else {
                    sb.append(ch)
                }
            }
            return sb.toString()
        }

        private fun parseBool(): Boolean {
            return if (src.startsWith("true", pos)) {
                pos += 4
                true
            } else {
                pos = (pos + 5).coerceAtMost(len)
                false
            }
        }

        private fun parseNull(): Any? {
            pos = (pos + 4).coerceAtMost(len)
            return null
        }

        private fun parseNum(): Number? {
            val start = pos
            while (pos < len && src[pos] in "0123456789+-eE.") pos++
            val raw = src.substring(start, pos)
            return raw.toDoubleOrNull()
        }
    }
}
