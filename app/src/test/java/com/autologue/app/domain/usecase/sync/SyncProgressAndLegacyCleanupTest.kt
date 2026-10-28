package com.autologue.app.domain.usecase.sync

import com.autologue.app.data.export.XlsxExporter
import com.autologue.app.domain.model.CarLogItemCategory
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.getUserTypeOverride
import com.autologue.app.domain.model.resolveCarLogItemCategory
import com.autologue.app.presentation.car.CarDrivingPurposeType
import com.autologue.app.presentation.navigation.Screen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.util.zip.ZipInputStream

class SyncProgressAndLegacyCleanupTest {

    @Test
    fun `SyncProgress defaults to 3-step pipeline and renders pre-scan percentage without hanging at 4 percent`() {
        val preScanProgress = SyncProgress(
            isRunning = true,
            stage = "신규 문자(SMS/MMS)·갤러리 사진 내역을 빠르게 스캔하고 있습니다... (문자 240건 스캔)",
            progressPercent = 12
        )
        assertEquals(3, preScanProgress.totalSteps)
        assertEquals("대상 건수 집계 중 (12%)", preScanProgress.formatQuantitativeSummary())

        val step2Progress = SyncProgress(
            isRunning = true,
            stage = "[2/3단계] 갤러리 사진·위치 분석 중 (50/100장)",
            syncedTxCount = 40,
            syncedPhotoCount = 50,
            stepNumber = 2,
            totalSteps = 3,
            stepProcessedCount = 50,
            stepTotalCount = 100,
            overallProcessedCount = 90,
            overallTotalCount = 160,
            progressPercent = 56
        )
        assertEquals("전체 160건 중 90건 (56% 완료)", step2Progress.formatQuantitativeSummary())
    }

    @Test
    fun `Golf tab, RouteStepType GOLF, and CarDrivingPurposeType GOLF are removed while preserving ExpenseCategory golf enums`() {
        // 1. 하단 네비게이션 Screen sealed 하위 클래스는 4탭(Diary, Expense, CarLedger, Lifestyle)만 존재하고 Golf는 제거됨
        val screenSubclasses = Screen::class.java.declaredClasses.map { it.simpleName }.toSet()
        assertEquals(
            setOf("Diary", "Expense", "CarLedger", "Lifestyle"),
            screenSubclasses
        )
        assertFalse(screenSubclasses.contains("Golf"))

        // 2. RouteStepType에서 GOLF가 제거되고 PHOTO, TRANSACTION, DRIVING, MEMO 4종만 존재
        assertEquals(
            setOf(RouteStepType.PHOTO, RouteStepType.TRANSACTION, RouteStepType.DRIVING, RouteStepType.MEMO),
            RouteStepType.entries.toSet()
        )
        assertFalse(RouteStepType.entries.any { it.name == "GOLF" })

        // 3. 차계부 주행 목적 통계는 COMMUTE, GENERAL 2종만 유지
        assertEquals(
            listOf(CarDrivingPurposeType.COMMUTE, CarDrivingPurposeType.GENERAL),
            CarDrivingPurposeType.entries
        )

        // 4. 레거시 차계부 오버라이드 문자열(GOLF_DRIVING)은 일반 주행(GENERAL_DRIVING)으로 무손실 폴백
        val legacyVehicleLog = VehicleLog(
            id = 77L,
            timestamp = LocalDateTime.of(2026, 10, 18, 6, 30),
            logType = VehicleLogType.TRIP_DRIVING,
            tripDistanceKm = 84.0,
            note = "[user_type_override:GOLF_DRIVING] 교외 이동"
        )
        assertEquals(CarLogItemCategory.GENERAL_DRIVING, legacyVehicleLog.getUserTypeOverride())
        assertEquals(CarLogItemCategory.GENERAL_DRIVING, legacyVehicleLog.resolveCarLogItemCategory())

        // 5. 가계부 기존 DB 호환 및 기본 사용자('정선우') 분류를 위한 ExpenseCategory enum은 그대로 보존
        assertEquals("필드 라운드", ExpenseCategory.GOLF_FIELD.displayName)
        assertEquals("스크린 골프", ExpenseCategory.GOLF_SCREEN.displayName)
        assertEquals("골프 용품", ExpenseCategory.GOLF_EQUIPMENT.displayName)
    }

    @Test
    fun `XlsxExporter generates exactly 3 sheets without golf sheet or golf column`() {
        val sampleDiary = DiaryEntry(
            id = 1L,
            date = LocalDateTime.of(2026, 10, 20, 20, 0),
            title = "평일 일상 기록",
            summary = "위례동에서 영등포구 이동",
            placeName = "영등포동"
        )
        val out = ByteArrayOutputStream()
        XlsxExporter.exportToStream(
            diaryEntries = listOf(sampleDiary),
            transactions = emptyList(),
            vehicleLogs = emptyList(),
            outputStream = out
        )

        val entryNames = mutableSetOf<String>()
        var sheet1Xml = ""
        var workbookXml = ""
        ZipInputStream(ByteArrayInputStream(out.toByteArray())).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                entryNames.add(entry.name)
                when (entry.name) {
                    "xl/worksheets/sheet1.xml" -> sheet1Xml = zis.bufferedReader(Charsets.UTF_8).readText()
                    "xl/workbook.xml" -> workbookXml = zis.bufferedReader(Charsets.UTF_8).readText()
                }
                entry = zis.nextEntry
            }
        }

        assertTrue(entryNames.contains("xl/worksheets/sheet1.xml"))
        assertTrue(entryNames.contains("xl/worksheets/sheet2.xml"))
        assertTrue(entryNames.contains("xl/worksheets/sheet3.xml"))
        assertFalse("골프 시트(sheet4.xml)가 생성되지 않아야 합니다.", entryNames.contains("xl/worksheets/sheet4.xml"))
        assertFalse("workbook.xml에 골프 라운드 시트명이 없어야 합니다.", workbookXml.contains("골프"))
        assertFalse("다이어리 시트에 '골프 여부' 컬럼이 없어야 합니다.", sheet1Xml.contains("골프 여부"))
    }
}
