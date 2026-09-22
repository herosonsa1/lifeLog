package com.autologue.app

import com.autologue.app.data.parser.RuleMatcherEngine
import com.autologue.app.domain.model.ExpenseCategory
import org.junit.Assert.assertEquals
import org.junit.Test

class RuleEngineTest {

    @Test
    fun ruleMatcher_classifiesStandardKeywords() {
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("SK에너지 행복주유소"))
        assertEquals(ExpenseCategory.GOLF_FIELD, RuleMatcherEngine.classifyMerchant("남촌골프클럽"))
        assertEquals(ExpenseCategory.GOLF_SCREEN, RuleMatcherEngine.classifyMerchant("골프존파크 강남점"))
        assertEquals(ExpenseCategory.CAR_MAINTENANCE, RuleMatcherEngine.classifyMerchant("오토오아시스 역삼"))
        assertEquals(ExpenseCategory.CAFE, RuleMatcherEngine.classifyMerchant("스타벅스 테헤란점"))
    }

    @Test
    fun ruleMatcher_classifiesExpandedFuelMerchants() {
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("HD현대 판교주유소"))
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("지에스칼텍스 서초셀프"))
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("SK엔크린 직영주유소"))
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("에스오일 삼보주유소"))
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("알뜰셀프주유소"))
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("문정LPG충전소"))
        assertEquals(ExpenseCategory.FUEL, RuleMatcherEngine.classifyMerchant("차지비 전기차충전"))
        
        // VehicleRepositoryImpl.isFuelMerchant 검증
        org.junit.Assert.assertTrue(com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant("현대오일뱅크 직영점"))
        org.junit.Assert.assertTrue(com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant("GS칼텍스 삼보셀프"))
        org.junit.Assert.assertTrue(com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant("SK엔크린 서초점"))
        org.junit.Assert.assertTrue(com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant("S-OIL 구도일주유소"))
        org.junit.Assert.assertTrue(com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant("알뜰주유소"))
    }
}
