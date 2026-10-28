package com.autologue.app.data.preferences

import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class CategoryDefaultUserTest {

    private fun sampleTx(
        id: Long,
        merchantName: String,
        category: ExpenseCategory,
        originalText: String = "$merchantName 50,000원 승인"
    ): Transaction {
        return Transaction(
            id = id,
            amount = 50000L,
            merchantName = merchantName,
            originalText = originalText,
            timestamp = LocalDateTime.of(2026, 10, 25, 14, 0),
            paymentMethod = PaymentMethod.CREDIT_CARD,
            category = category,
            cardOrBankName = "신한카드"
        )
    }

    @Test
    fun `7종 카테고리(필드라운드_스크린골프_수입입금_이체출금_담배_보험_통신)의 기본 사용자가 항상 정선우로 초기화되는지 검증`() {
        val prefs = ExpenseCustomMetaPreferences()

        // 1. 사용자 분류 목록에 '정선우' 자동 등록 확인
        assertTrue(
            "사용자 분류 목록에 '정선우'가 포함되어야 합니다.",
            prefs.userClassificationsFlow.value.any { it.name == "정선우" }
        )

        // 2. 기본 내장 4종 카테고리 기본 사용자 '정선우' 확인
        val builtInPresets = listOf(
            ExpenseCategory.GOLF_FIELD,
            ExpenseCategory.GOLF_SCREEN,
            ExpenseCategory.INCOME,
            ExpenseCategory.TRANSFER
        )
        for ((idx, cat) in builtInPresets.withIndex()) {
            assertEquals("정선우", prefs.resolveCategoryDefaultUser(cat))
            val managedItem = prefs.managedCategoriesFlow.value.firstOrNull { it.isBuiltIn && it.baseCategory == cat }
            assertNotNull("관리 카테고리에 ${cat.displayName}이 존재해야 합니다.", managedItem)
            assertEquals("정선우", managedItem?.defaultUserName)

            val tx = sampleTx(
                id = (idx + 1).toLong(),
                merchantName = "테스트_${cat.name}",
                category = cat,
                originalText = if (cat == ExpenseCategory.INCOME) "입금 50,000원 급여" else "출금 50,000원 테스트"
            )
            assertFalse("초기 상태에서는 명시적 개별 규칙이 없어야 합니다.", prefs.hasExplicitUserClassificationRule(tx))
            assertEquals("정선우", prefs.resolveUserClassification(tx)?.name)
        }

        // 3. 커스텀 3종 카테고리('담배', '보험', '통신') 자동 등록 및 기본 사용자 '정선우' 확인
        val customPresets = listOf("담배", "보험", "통신")
        for ((idx, customName) in customPresets.withIndex()) {
            val managedCustom = prefs.managedCategoriesFlow.value.firstOrNull {
                !it.isBuiltIn && it.displayName == customName
            }
            assertNotNull("커스텀 카테고리 '$customName'이 자동 등록되어야 합니다.", managedCustom)
            assertEquals("정선우", managedCustom?.defaultUserName)
            assertEquals("정선우", prefs.resolveCategoryDefaultUser(ExpenseCategory.ETC, customName))

            val txId = 100L + idx
            val tx = sampleTx(id = txId, merchantName = "가맹점_$customName", category = ExpenseCategory.ETC)
            prefs.assignCustomCategory(
                txId = txId,
                merchantName = tx.merchantName,
                customCategoryName = customName,
                applyToMerchantRule = false
            )
            assertEquals("정선우", prefs.resolveUserClassification(tx)?.name)
        }
    }

    @Test
    fun `임의 카테고리에 기본 사용자를 새로 지정_변경_해제할 수 있는지 검증`() {
        val prefs = ExpenseCustomMetaPreferences()

        val foodItem = prefs.managedCategoriesFlow.value.first { it.isBuiltIn && it.baseCategory == ExpenseCategory.FOOD }
        assertNull(foodItem.defaultUserName)

        val foodTx = sampleTx(id = 201L, merchantName = "광화문식당", category = ExpenseCategory.FOOD)
        assertNull(prefs.resolveUserClassification(foodTx))

        // 식비 카테고리의 기본 사용자를 '가족'으로 지정
        prefs.setCategoryDefaultUser(foodItem, "가족")
        assertEquals("가족", prefs.resolveCategoryDefaultUser(ExpenseCategory.FOOD))
        assertEquals("가족", prefs.resolveUserClassification(foodTx)?.name)

        // 식비 카테고리의 기본 사용자를 해제(null)
        prefs.setCategoryDefaultUser(foodItem, null)
        assertNull(prefs.resolveCategoryDefaultUser(ExpenseCategory.FOOD))
        assertNull(prefs.resolveUserClassification(foodTx))

        // 신규 커스텀 카테고리를 추가하면서 동시에 기본 사용자를 '배우자'로 지정
        assertTrue(prefs.addCustomCategory("교육/학원", defaultUserName = "배우자"))
        assertTrue(prefs.userClassificationsFlow.value.any { it.name == "배우자" })
        assertEquals("배우자", prefs.resolveCategoryDefaultUser(ExpenseCategory.ETC, "교육/학원"))

        val eduTx = sampleTx(id = 202L, merchantName = "대치아카데미", category = ExpenseCategory.ETC)
        prefs.assignCustomCategory(202L, eduTx.merchantName, "교육/학원", applyToMerchantRule = true)
        assertEquals("배우자", prefs.resolveUserClassification(eduTx)?.name)

        // 사용자 분류 이름 수정 시 카테고리 기본 사용자도 동기화되는지 확인
        val colorIdx = prefs.userClassificationsFlow.value.first { it.name == "배우자" }.colorIndex
        assertTrue(prefs.updateUserClassification("배우자", "김영희", colorIdx))
        assertEquals("김영희", prefs.resolveCategoryDefaultUser(ExpenseCategory.ETC, "교육/학원"))
        assertEquals("김영희", prefs.resolveUserClassification(eduTx)?.name)
    }

    @Test
    fun `기본 사용자가 정선우로 지정된 카테고리라도 개별 내역이나 영구 규칙에서 다른 사용자나 미지정으로 변경 가능한지 검증`() {
        val prefs = ExpenseCustomMetaPreferences()

        // 1) 필드 라운드(기본 사용자: 정선우) 거래 생성
        val golfTx1 = sampleTx(id = 301L, merchantName = "남서울CC", category = ExpenseCategory.GOLF_FIELD)
        val golfTx2 = sampleTx(id = 302L, merchantName = "레이크사이드CC", category = ExpenseCategory.GOLF_FIELD)
        assertEquals("정선우", prefs.resolveUserClassification(golfTx1)?.name)
        assertEquals("정선우", prefs.resolveUserClassification(golfTx2)?.name)

        // golfTx1을 '가족'으로 1회성 변경 -> golfTx1은 '가족', golfTx2는 여전히 '정선우'
        prefs.markOneTimeEdited(golfTx1)
        prefs.assignUserClassification(
            txId = golfTx1.id,
            merchantName = golfTx1.merchantName,
            userClassificationName = "가족",
            applyToMerchantRule = false
        )
        assertTrue(prefs.hasExplicitUserClassificationRule(golfTx1))
        assertEquals("가족", prefs.resolveUserClassification(golfTx1)?.name)
        assertEquals("정선우", prefs.resolveUserClassification(golfTx2)?.name)

        // golfTx1을 명시적 '미지정'으로 변경 -> 카테고리 기본값 '정선우'로 되돌아가지 않고 null 유지
        prefs.assignUserClassification(
            txId = golfTx1.id,
            merchantName = golfTx1.merchantName,
            userClassificationName = null,
            applyToMerchantRule = false,
            explicitlyUnassigned = true
        )
        assertTrue(prefs.hasExplicitUserClassificationRule(golfTx1))
        assertNull("명시적 미지정 선택 시 기본 사용자(정선우) 대신 미지정(null)이 유지되어야 합니다.", prefs.resolveUserClassification(golfTx1))

        // 2) 커스텀 카테고리 '보험'(기본 사용자: 정선우) 거래를 가맹점 영구 규칙으로 '본인' 변경
        val insTx = sampleTx(id = 303L, merchantName = "삼성화재자동차보험", category = ExpenseCategory.ETC)
        prefs.assignCustomCategory(
            txId = insTx.id,
            merchantName = insTx.merchantName,
            customCategoryName = "보험",
            applyToMerchantRule = true
        )
        assertEquals("정선우", prefs.resolveUserClassification(insTx)?.name)

        prefs.assignUserClassification(
            txId = insTx.id,
            merchantName = insTx.merchantName,
            userClassificationName = "본인",
            applyToMerchantRule = true
        )
        assertEquals("본인", prefs.resolveUserClassification(insTx)?.name)

        // 동일 가맹점의 추후 수신 거래도 영구 규칙('본인')이 카테고리 기본값('정선우')보다 우선 적용되는지 검증
        val futureInsTx = sampleTx(id = 304L, merchantName = "삼성화재자동차보험", category = ExpenseCategory.ETC)
        assertEquals("본인", prefs.resolveUserClassification(futureInsTx)?.name)
    }
}
