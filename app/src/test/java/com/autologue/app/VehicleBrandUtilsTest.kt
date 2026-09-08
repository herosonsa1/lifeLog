package com.autologue.app

import com.autologue.app.util.VehicleBrandUtils
import org.junit.Assert.assertEquals
import org.junit.Test

class VehicleBrandUtilsTest {

    @Test
    fun testMercedesBenzReturnsThreePointedStar() {
        assertEquals("⭐", VehicleBrandUtils.getBrandEmoji("벤츠 A클래스"))
        assertEquals("⭐", VehicleBrandUtils.getBrandEmoji("Mercedes-Benz E300"))
        assertEquals("⭐", VehicleBrandUtils.getBrandEmoji("A클래스"))
        assertEquals("⭐", VehicleBrandUtils.getBrandEmoji("벤츠"))
        assertEquals("⭐", VehicleBrandUtils.getBrandEmoji("GLC 300 4MATIC"))
        assertEquals("벤츠 삼각별", VehicleBrandUtils.getBrandEmblemDescription("벤츠 A클래스"))
    }

    @Test
    fun testVolvoReturnsIronMarkShield() {
        assertEquals("🛡️", VehicleBrandUtils.getBrandEmoji("볼보 V60cc"))
        assertEquals("🛡️", VehicleBrandUtils.getBrandEmoji("Volvo XC90"))
        assertEquals("🛡️", VehicleBrandUtils.getBrandEmoji("V60 Cross Country"))
        assertEquals("🛡️", VehicleBrandUtils.getBrandEmoji("볼보"))
        assertEquals("볼보 아이언마크", VehicleBrandUtils.getBrandEmblemDescription("볼보 V60cc"))
    }

    @Test
    fun testOtherBrandsReturnDistinctEmblems() {
        assertEquals("🔵", VehicleBrandUtils.getBrandEmoji("BMW 520d"))
        assertEquals("🔗", VehicleBrandUtils.getBrandEmoji("아우디 A6"))
        assertEquals("🪽", VehicleBrandUtils.getBrandEmoji("제네시스 GV80"))
        assertEquals("⚡", VehicleBrandUtils.getBrandEmoji("테슬라 모델Y"))
        assertEquals("🐎", VehicleBrandUtils.getBrandEmoji("포르쉐 911"))
    }

    @Test
    fun testDefaultVehicleReturnsCarEmoji() {
        assertEquals("🚗", VehicleBrandUtils.getBrandEmoji("아반떼"))
        assertEquals("🚗", VehicleBrandUtils.getBrandEmoji("소나타"))
        assertEquals("🚗", VehicleBrandUtils.getBrandEmoji(null))
        assertEquals("🚗", VehicleBrandUtils.getBrandEmoji(""))
    }
}
