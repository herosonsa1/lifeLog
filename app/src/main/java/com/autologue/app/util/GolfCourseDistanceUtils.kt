package com.autologue.app.util

/**
 * 수도권(서울/판교 거점) 기준 주요 골프장별 표준 왕복 주행거리(km) 산출 유틸리티
 */
object GolfCourseDistanceUtils {

    /**
     * 골프장 명칭에 따른 왕복 주행거리(km) 추정
     */
    fun getEstimatedRoundTripKm(clubName: String?): Double {
        if (clubName.isNullOrBlank()) return 130.0

        val name = clubName.trim()
        return when {
            // 강원 원주권 (오크밸리, 월송리, 성문안 등) -> 왕복 약 130.0 km
            name.contains("월송리") || name.contains("오크밸리") || name.contains("오크크릭") || name.contains("성문안") -> 130.0

            // 충북 충주권 (킹스데일, 대영힐스 등) -> 왕복 약 160.0 km
            name.contains("킹스데일") || name.contains("대영힐스") || name.contains("대영베이스") -> 160.0

            // 경기 여주 / 이천권 -> 왕복 약 140~160.0 km
            name.contains("아리지") || name.contains("스카이밸리") || name.contains("여주") || name.contains("신라") -> 160.0
            name.contains("해슬리") || name.contains("페럼") || name.contains("사우스스프링스") || name.contains("블랙스톤") -> 140.0

            // 경기 포천 / 철원권 -> 왕복 약 120~150.0 km
            name.contains("필로스") || name.contains("참밸리") -> 120.0
            name.contains("베어크리크") || name.contains("포천힐스") || name.contains("몽베르") || name.contains("라싸") -> 150.0

            // 강원 춘천 / 홍천권 -> 왕복 약 170~190.0 km
            name.contains("라데나") || name.contains("제이드팰리스") -> 190.0
            name.contains("라비에벨") || name.contains("더플레이어스") || name.contains("휘슬링락") || name.contains("클럽모우") || name.contains("카스카디아") -> 170.0
            name.contains("비발디파크") || name.contains("소노펠리체") -> 160.0

            // 강원 영월 / 태백 / 강릉권 -> 왕복 약 280~380.0 km
            name.contains("동강시스타") -> 280.0
            name.contains("샌드파인") || name.contains("메이플비치") || name.contains("파인리즈") -> 380.0

            // 경기 광주 / 용인 / 안성권 -> 왕복 약 90~110.0 km
            name.contains("남촌") || name.contains("이스트밸리") || name.contains("곤지암") -> 110.0
            name.contains("아시아나") || name.contains("화산") || name.contains("신원") || name.contains("레이크사이드") || name.contains("태광") -> 90.0
            name.contains("안성베네스트") || name.contains("지산") || name.contains("은화삼") -> 110.0

            // 경기 가평 / 양평권 -> 왕복 약 110~130.0 km
            name.contains("아난티") -> 110.0
            name.contains("가평베네스트") || name.contains("크리스탈밸리") || name.contains("프리스틴밸리") || name.contains("더스타휴") -> 130.0

            // 인천 / 영종 / 서구 -> 왕복 약 120.0 km
            name.contains("클럽72") || name.contains("스카이72") || name.contains("베어즈베스트") || name.contains("잭니클라우스") -> 120.0

            // 충남 천안 / 세종권 -> 왕복 약 180.0 km
            name.contains("우정힐스") || name.contains("천안상록") || name.contains("세종필드") -> 180.0

            // 기본 표준 골프장 왕복 주행거리
            else -> 130.0
        }
    }
}
