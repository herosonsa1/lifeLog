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

    fun resolveGeoLocation(context: Context? = null, lat: Double, lng: Double): ResolvedLocation {
        val cacheKey = "%.3f,%.3f".format(java.util.Locale.US, lat, lng)
        geoCache[cacheKey]?.let { return it }

        val activeContext = context ?: this.context

        // 1. Android Geocoder (구글 맵 공식 역지오코딩) 최우선 실행
        if (activeContext != null) {
            runCatching {
                if (android.location.Geocoder.isPresent()) {
                    val geocoder = android.location.Geocoder(activeContext, java.util.Locale.KOREAN)
                    @Suppress("DEPRECATION")
                    val addresses = geocoder.getFromLocation(lat, lng, 1)
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

        // 2. Geocoder 실패 또는 오프라인 환경일 때 안전 폴백
        val fallbackPlace = "위도 %.3f, 경도 %.3f".format(java.util.Locale.US, lat, lng)
        val fallback = ResolvedLocation(fallbackPlace, "위치 정보 ($fallbackPlace)", lat, lng)
        geoCache[cacheKey] = fallback
        return fallback
    }

    companion object {
        /** [M-01] geoCache LRU 최대 항목 수 — 초과 시 가장 오래된 항목 자동 제거 */
        private const val MAX_CACHE_SIZE = 1000
    }
}
