package com.autologue.app.util

/**
 * 차량 브랜드별 고유 엠블럼 이모지 및 메타데이터 판별 유틸리티
 */
object VehicleBrandUtils {

    /**
     * 차량 이름/애칭 및 모델명으로부터 브랜드 엠블럼 이모지 추출
     * - 메르세데스-벤츠: 삼각별 (Three-Pointed Star) ➔ ⭐
     * - 볼보: 아이언 마크 및 안전 방패 (Iron Mark & Shield) ➔ 🛡️
     * - BMW: 바이에른 프로펠러 라운델 ➔ 🔵
     * - 아우디: 4개의 링 ➔ 🔗
     * - 제네시스: 시그니처 윙 ➔ 🪽
     * - 포르쉐 / 페라리: 도약하는 말 ➔ 🐎
     * - 테슬라 / 순수전기차: 전동화 번개 ➔ ⚡
     * - 일반 차량 기본값 ➔ 🚗
     */
    fun getBrandEmoji(vehicleName: String?): String {
        if (vehicleName.isNullOrBlank()) return "🚗"
        val lower = vehicleName.lowercase()

        return when {
            // 1. 메르세데스-벤츠 (삼각별 엠블럼)
            lower.contains("벤츠") || lower.contains("benz") || lower.contains("mercedes") ||
            lower.contains("a클래스") || lower.contains("c클래스") || lower.contains("e클래스") ||
            lower.contains("s클래스") || lower.contains("gla") || lower.contains("glc") ||
            lower.contains("gle") || lower.contains("gls") || lower.contains("amg") -> "⭐"

            // 2. 볼보 (아이언 마크 & 안전 방패 엠블럼)
            lower.contains("볼보") || lower.contains("volvo") || lower.contains("v60") ||
            lower.contains("v90") || lower.contains("v40") || lower.contains("xc") ||
            lower.contains("s90") || lower.contains("s60") || lower.contains("polestar") ||
            lower.contains("폴스타") -> "🛡️"

            // 3. BMW (라운델)
            lower.contains("bmw") || lower.contains("비엠") || lower.contains("320d") ||
            lower.contains("520d") || lower.contains("530i") || lower.contains("m3") ||
            lower.contains("m5") || lower.contains("x3") || lower.contains("x5") -> "🔵"

            // 4. 아우디 (4링)
            lower.contains("아우디") || lower.contains("audi") || lower.contains("a4") ||
            lower.contains("a6") || lower.contains("a7") || lower.contains("a8") ||
            lower.contains("q5") || lower.contains("q7") -> "🔗"

            // 5. 제네시스 (윙 엠블럼)
            lower.contains("제네시스") || lower.contains("genesis") || lower.contains("g80") ||
            lower.contains("g90") || lower.contains("g70") || lower.contains("gv80") ||
            lower.contains("gv70") -> "🪽"

            // 6. 슈퍼카 / 스포츠카 (포르쉐, 페라리)
            lower.contains("포르쉐") || lower.contains("porsche") || lower.contains("페라리") ||
            lower.contains("ferrari") || lower.contains("911") || lower.contains("타이칸") -> "🐎"

            // 7. 테슬라 및 전기차
            lower.contains("테슬라") || lower.contains("tesla") || lower.contains("아이오닉") ||
            lower.contains("ioniq") || lower.contains("ev6") || lower.contains("ev9") -> "⚡"

            // 8. SUV / 세컨카
            lower.contains("suv") || lower.contains("트럭") || lower.contains("카니발") -> "🚙"

            else -> "🚗"
        }
    }

    /**
     * 브랜드 엠블럼의 공식 한글 설명
     */
    fun getBrandEmblemDescription(vehicleName: String?): String {
        if (vehicleName.isNullOrBlank()) return "승용차"
        val lower = vehicleName.lowercase()

        return when {
            lower.contains("벤츠") || lower.contains("benz") || lower.contains("mercedes") ||
            lower.contains("a클래스") || lower.contains("c클래스") || lower.contains("e클래스") ||
            lower.contains("s클래스") -> "벤츠 삼각별"

            lower.contains("볼보") || lower.contains("volvo") || lower.contains("v60") ||
            lower.contains("xc") || lower.contains("s90") -> "볼보 아이언마크"

            lower.contains("bmw") -> "BMW 라운델"
            lower.contains("아우디") || lower.contains("audi") -> "아우디 4링"
            lower.contains("제네시스") || lower.contains("genesis") -> "제네시스 윙"
            lower.contains("테슬라") || lower.contains("tesla") -> "테슬라 전동"
            else -> "차량"
        }
    }
}
