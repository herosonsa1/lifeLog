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
}
