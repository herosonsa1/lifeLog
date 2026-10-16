package com.autologue.app.data.parser

import com.autologue.app.data.export.XlsxExporter
import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.data.preferences.ManagedCategoryItem
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

class ExpenseCustomMetaFeaturesTest {

    @Test
    fun `병원 및 약제 가맹점이 MEDICAL 카테고리로 정확히 자동 분류되는지 검증`() {
        val medicalMerchants = listOf(
            "서울아산병원",
            "강남세브란스병원",
            "종로온누리약국",
            "밝은성모안과의원",
            "서울바른치과의원",
            "경희부부한의원",
            "한국건강관리협회검진"
        )

        for (merchant in medicalMerchants) {
            val category = RuleMatcherEngine.classifyMerchant(merchant)
            assertEquals(
                "가맹점 '$merchant'는 병원/약제(MEDICAL)로 분류되어야 합니다.",
                ExpenseCategory.MEDICAL,
                category
            )
        }
        assertEquals("병원/약제", ExpenseCategory.MEDICAL.displayName)
    }

    @Test
    fun `카드 지정(개인 및 법인), 1회성 저장, 카테고리 관리, 사용자 분류, 선택 삭제 블랙리스트 통합 검증`() {
        val metaPrefs = ExpenseCustomMetaPreferences()

        // 1. 카드 지정 및 법인 여부 검증
        val formattedCorp = metaPrefs.formatCardDesignation("현대카드", isCorporate = true)
        assertEquals("현대카드(법인)", formattedCorp)
        metaPrefs.setTransactionCardDesignation(
            txId = 101L,
            merchantName = "한우명가",
            cardCompany = "현대카드",
            isCorporate = true,
            applyToMerchantRule = false
        )

        val sampleTx = Transaction(
            id = 101L,
            amount = 128000L,
            merchantName = "한우명가",
            originalText = "[현대카드] 128,000원 한우명가",
            timestamp = LocalDateTime.of(2026, 10, 15, 19, 10),
            paymentMethod = PaymentMethod.CREDIT_CARD,
            category = ExpenseCategory.ETC,
            cardOrBankName = formattedCorp
        )
        assertTrue(metaPrefs.isCorporateTransaction(sampleTx))

        // 2. 1회성 저장 보호(One-Time Override) 검증
        metaPrefs.markOneTimeEdited(sampleTx)
        assertTrue(metaPrefs.isOneTimeEdited(sampleTx))

        // 3. 기타 선택 시 추가 카테고리 영구 등록 및 카테고리 관리(추가/수정/삭제) 검증
        assertTrue(metaPrefs.addCustomCategory("반려동물"))
        metaPrefs.assignCustomCategory(
            txId = 101L,
            merchantName = "한우명가",
            customCategoryName = "반려동물",
            applyToMerchantRule = false
        )
        assertEquals("반려동물", metaPrefs.resolveEffectiveCategoryDisplayName(sampleTx))

        val customCatItem = ManagedCategoryItem(
            id = "CUSTOM_반려동물",
            displayName = "반려동물",
            baseCategory = ExpenseCategory.ETC,
            isBuiltIn = false
        )
        assertTrue(metaPrefs.updateCategoryName(item = customCatItem, newName = "반려견케어"))
        assertEquals("반려견케어", metaPrefs.resolveEffectiveCategoryDisplayName(sampleTx))

        // 4. 카테고리와 별개인 사용자 분류(직접 입력, 추가/수정/삭제) 검증
        val addedUserClass = metaPrefs.addOrGetUserClassification("홍길동(본인)")
        assertNotNull(addedUserClass)
        metaPrefs.assignUserClassification(
            txId = 101L,
            merchantName = "한우명가",
            userClassificationName = "홍길동(본인)",
            applyToMerchantRule = false
        )
        assertEquals("홍길동(본인)", metaPrefs.resolveUserClassification(sampleTx)?.name)

        assertTrue(metaPrefs.updateUserClassification("홍길동(본인)", "업무/법인", newColorIndex = 2))
        assertEquals("업무/법인", metaPrefs.resolveUserClassification(sampleTx)?.name)
        assertEquals(2, metaPrefs.resolveUserClassification(sampleTx)?.colorIndex)

        // 5. 선택적 영구 삭제(결제기록 및 주행기록 블랙리스트) 검증
        assertFalse(metaPrefs.isTransactionDeleted(sampleTx))
        metaPrefs.markTransactionDeleted(sampleTx)
        assertTrue(metaPrefs.isTransactionDeleted(sampleTx))

        val refuelLog = VehicleLog(
            id = 501L,
            timestamp = LocalDateTime.of(2026, 10, 14, 9, 0),
            logType = VehicleLogType.REFUELING,
            fuelCost = 75000L,
            fuelAmountLiters = 45.4,
            gasStationName = "GS칼텍스 위례주유소"
        )
        assertFalse(metaPrefs.isRefuelingSyncDeleted(refuelLog.timestamp.toLocalDate(), refuelLog.fuelCost))
        metaPrefs.markVehicleLogDeleted(refuelLog)
        assertTrue(metaPrefs.isRefuelingSyncDeleted(refuelLog.timestamp.toLocalDate(), refuelLog.fuelCost))
    }

    @Test
    fun `엑셀 내보내기 시 사용자 분류 및 법인카드 구분과 커스텀 카테고리가 정상 포함되는지 검증`() {
        val metaPrefs = ExpenseCustomMetaPreferences()

        val tx1 = Transaction(
            id = 101L,
            amount = 45000L,
            merchantName = "서울아산병원",
            originalText = "[신한카드] 45,000원 서울아산병원",
            timestamp = LocalDateTime.of(2026, 10, 15, 14, 30),
            paymentMethod = PaymentMethod.CREDIT_CARD,
            category = ExpenseCategory.MEDICAL,
            cardOrBankName = "신한카드",
            transferMemo = "진료비",
            isAutoCategorized = true
        )
        val tx2 = Transaction(
            id = 102L,
            amount = 128000L,
            merchantName = "한우명가",
            originalText = "[현대카드(법인)] 128,000원 한우명가",
            timestamp = LocalDateTime.of(2026, 10, 15, 19, 10),
            paymentMethod = PaymentMethod.CREDIT_CARD,
            category = ExpenseCategory.ETC,
            cardOrBankName = "현대카드(법인)",
            transferMemo = "팀 프로젝트 회식",
            isAutoCategorized = false
        )

        metaPrefs.addCustomCategory("부서회식비")
        metaPrefs.assignCustomCategory(102L, "한우명가", "부서회식비", applyToMerchantRule = false)
        metaPrefs.assignUserClassification(101L, "서울아산병원", "가족", applyToMerchantRule = false)
        metaPrefs.assignUserClassification(102L, "한우명가", "업무/법인", applyToMerchantRule = false)
        metaPrefs.setTransactionCardDesignation(102L, "한우명가", "현대카드", isCorporate = true, applyToMerchantRule = false)

        val out = ByteArrayOutputStream()
        XlsxExporter.exportToStream(
            diaryEntries = emptyList(),
            transactions = listOf(tx1, tx2),
            vehicleLogs = emptyList(),
            golfRounds = emptyList(),
            outputStream = out,
            expenseCustomMetaPreferences = metaPrefs
        )

        val xlsxBytes = out.toByteArray()
        assertTrue("생성된 XLSX 바이트 배열이 비어있지 않아야 합니다.", xlsxBytes.isNotEmpty())

        // ZIP 엔트리 내 sheet2.xml(가계부 시트) 텍스트에 '사용자 분류', '법인 / 개인 구분', '법인카드', '부서회식비', '업무/법인' 포함 여부 확인
        var sheet2Xml = ""
        ZipInputStream(ByteArrayInputStream(xlsxBytes)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (entry.name == "xl/worksheets/sheet2.xml") {
                    sheet2Xml = zis.bufferedReader(Charsets.UTF_8).readText()
                    break
                }
                entry = zis.nextEntry
            }
        }

        assertTrue("sheet2.xml(가계부 시트)이 존재해야 합니다.", sheet2Xml.isNotEmpty())
        assertTrue("엑셀 헤더에 '사용자 분류' 컬럼이 포함되어야 합니다.", sheet2Xml.contains("사용자 분류"))
        assertTrue("엑셀 헤더에 '법인 / 개인 구분' 컬럼이 포함되어야 합니다.", sheet2Xml.contains("법인 / 개인 구분"))
        assertTrue("엑셀 데이터에 '병원/약제' 카테고리가 표기되어야 합니다.", sheet2Xml.contains("병원/약제"))
        assertTrue("엑셀 데이터에 커스텀 카테고리 '부서회식비'가 표기되어야 합니다.", sheet2Xml.contains("부서회식비"))
        assertTrue("엑셀 데이터에 사용자 분류 '가족' 및 '업무/법인'이 표기되어야 합니다.", sheet2Xml.contains("가족") && sheet2Xml.contains("업무/법인"))
        assertTrue("엑셀 데이터에 '법인카드' 및 '개인카드' 구분이 표기되어야 합니다.", sheet2Xml.contains("법인카드") && sheet2Xml.contains("개인카드"))
    }
}
