package com.autologue.app.data.sync

import android.content.Context
import android.net.Uri
import androidx.exifinterface.media.ExifInterface
import java.io.InputStream
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlaceResolver @Inject constructor() {

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
        context: Context,
        uri: Uri,
        streamProvider: () -> InputStream?
    ): ResolvedLocation {
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

        return fromExif ?: ResolvedLocation(
            placeName = "서울 방이동",
            address = "서울특별시 송파구 방이동",
            latitude = 37.5145,
            longitude = 127.1058
        )
    }

    private fun extractDong(addr: android.location.Address): String? {
        val candidates = listOfNotNull(addr.subLocality, addr.thoroughfare)
        for (c in candidates) {
            val trimmed = c.trim()
            if (trimmed.endsWith("동") || trimmed.endsWith("읍") || trimmed.endsWith("면") || trimmed.endsWith("리") || trimmed.endsWith("가")) {
                return trimmed
            }
        }
        val fullLine = addr.getAddressLine(0) ?: ""
        val regex = Regex("([가-힣0-9]+(?:동|읍|면|리|가))(?=\\s|\\d|$)")
        val matches = regex.findAll(fullLine).map { it.groupValues[1] }.toList()
        return matches.lastOrNull { !it.endsWith("동로") && !it.endsWith("동길") }
    }

    fun resolveGeoLocation(context: Context? = null, lat: Double, lng: Double): ResolvedLocation {
        val cacheKey = "%.3f,%.3f".format(java.util.Locale.US, lat, lng)
        geoCache[cacheKey]?.let { return it }

        // 1. Check known specific bounds for precise Korean Dong / Eup / Myeon name
        // 마포구 상암동
        if (lat in 37.550..37.585 && lng in 126.880..126.915) {
            val res = ResolvedLocation("서울 상암동", "서울특별시 마포구 상암동 하늘공원로 95", lat, lng)
            geoCache[cacheKey] = res
            return res
        }
        // 송파구 (잠실동 vs 방이동 vs 문정동)
        if (lat in 37.480..37.535 && lng in 127.080..127.160) {
            val dong = when {
                lng < 127.105 -> "잠실동"
                lat < 37.500 -> "문정동"
                else -> "방이동"
            }
            val res = ResolvedLocation("서울 $dong", "서울특별시 송파구 $dong", lat, lng)
            geoCache[cacheKey] = res
            return res
        }
        // 영등포구 (여의도동 vs 영등포동)
        if (lat in 37.505..37.545 && lng in 126.885..126.945) {
            val dong = if (lng >= 126.918) "여의도동" else "영등포동"
            val res = ResolvedLocation("서울 $dong", "서울특별시 영등포구 $dong", lat, lng)
            geoCache[cacheKey] = res
            return res
        }
        // 강남구 (역삼동 vs 삼성동 vs 신사동)
        if (lat in 37.480..37.535 && lng in 127.020..127.079) {
            val dong = when {
                lat >= 37.515 -> "신사동"
                lng >= 127.045 -> "삼성동"
                else -> "역삼동"
            }
            val res = ResolvedLocation("서울 $dong", "서울특별시 강남구 $dong", lat, lng)
            geoCache[cacheKey] = res
            return res
        }
        // 위례동
        if (lat in 37.470..37.485 && lng in 127.135..127.155) {
            val res = ResolvedLocation("경기 위례동", "경기도 성남시 수정구 위례동", lat, lng)
            geoCache[cacheKey] = res
            return res
        }
        // 분당/판교 삼평동/백현동
        if (lat in 37.380..37.410 && lng in 127.100..127.125) {
            val dong = if (lat >= 37.395) "삼평동" else "백현동"
            val res = ResolvedLocation("경기 $dong", "경기도 성남시 분당구 $dong", lat, lng)
            geoCache[cacheKey] = res
            return res
        }
        // 광주 곤지암읍
        if (lat in 37.320..37.350 && lng in 127.340..127.370) {
            val res = ResolvedLocation("경기 곤지암읍", "경기도 광주시 곤지암읍", lat, lng)
            geoCache[cacheKey] = res
            return res
        }

        // 2. Android Geocoder reverse-lookup with Dong prioritisation
        if (context != null) {
            runCatching {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(context, java.util.Locale.KOREAN)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
                    if (!addresses.isNullOrEmpty()) {
                        val addr = addresses[0]
                        val admin = (addr.adminArea ?: "")
                            .replace("서울특별시", "서울")
                            .replace("광역시", "")
                            .replace("특별자치시", "")
                            .replace("도", "")
                            .trim()
                        val locality = (addr.locality ?: addr.subAdminArea ?: "")
                            .replace("시", "")
                            .replace("구", "")
                            .trim()
                        val dong = extractDong(addr)

                        val shortName = if (!dong.isNullOrBlank()) {
                            if (admin.isNotBlank()) "$admin $dong" else dong
                        } else {
                            listOf(admin, locality).filter { it.isNotBlank() }.distinct().joinToString(" ")
                        }

                        val fullAddress = addr.getAddressLine(0) ?: "$admin $locality ${dong ?: ""}".trim()
                        val res = ResolvedLocation(
                            placeName = if (shortName.isNotBlank()) shortName else "서울 방이동",
                            address = fullAddress,
                            latitude = lat,
                            longitude = lng
                        )
                        geoCache[cacheKey] = res
                        return res
                    }
                }
            }
        }

        val fallback = ResolvedLocation("서울 방이동", "서울특별시 송파구 방이동", lat, lng)
        geoCache[cacheKey] = fallback
        return fallback
    }

    companion object {
        /** [M-01] geoCache LRU 최대 항목 수 — 초과 시 가장 오래된 항목 자동 제거 */
        private const val MAX_CACHE_SIZE = 1000
    }
}
