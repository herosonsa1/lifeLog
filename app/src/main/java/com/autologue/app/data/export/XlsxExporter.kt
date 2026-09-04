package com.autologue.app.data.export

import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import java.io.OutputStream
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object XlsxExporter {

    private val DATE_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.KOREA)
    private val DATE_ONLY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.KOREA)

    fun exportToStream(
        diaryEntries: List<DiaryEntry>,
        transactions: List<Transaction>,
        vehicleLogs: List<VehicleLog>,
        golfRounds: List<GolfRound>,
        outputStream: OutputStream
    ) {
        ZipOutputStream(outputStream).use { zip ->
            // 1. [Content_Types].xml
            addZipEntry(zip, "[Content_Types].xml", buildContentTypesXml())

            // 2. _rels/.rels
            addZipEntry(zip, "_rels/.rels", buildRelsXml())

            // 3. xl/_rels/workbook.xml.rels
            addZipEntry(zip, "xl/_rels/workbook.xml.rels", buildWorkbookRelsXml())

            // 4. xl/workbook.xml
            addZipEntry(zip, "xl/workbook.xml", buildWorkbookXml())

            // 5. xl/styles.xml
            addZipEntry(zip, "xl/styles.xml", buildStylesXml())

            // 6. Sheets
            addZipEntry(zip, "xl/worksheets/sheet1.xml", buildDiarySheetXml(diaryEntries))
            addZipEntry(zip, "xl/worksheets/sheet2.xml", buildExpenseSheetXml(transactions))
            addZipEntry(zip, "xl/worksheets/sheet3.xml", buildCarSheetXml(vehicleLogs))
            addZipEntry(zip, "xl/worksheets/sheet4.xml", buildGolfSheetXml(golfRounds))
        }
    }

    private fun addZipEntry(zip: ZipOutputStream, path: String, content: String) {
        val entry = ZipEntry(path)
        zip.putNextEntry(entry)
        zip.write(content.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun escapeXml(str: String?): String {
        if (str == null) return ""
        return str.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&apos;")
    }

    private fun buildContentTypesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet3.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/worksheets/sheet4.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
</Types>"""

    private fun buildRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun buildWorkbookRelsXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>
  <Relationship Id="rId3" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet3.xml"/>
  <Relationship Id="rId4" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet4.xml"/>
  <Relationship Id="rId5" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private fun buildWorkbookXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="다이어리 타임라인" sheetId="1" r:id="rId1"/>
    <sheet name="스마트 가계부" sheetId="2" r:id="rId2"/>
    <sheet name="스마트 차계부" sheetId="3" r:id="rId3"/>
    <sheet name="골프 라이프" sheetId="4" r:id="rId4"/>
  </sheets>
</workbook>"""

    private fun buildStylesXml(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="2">
    <font>
      <sz val="10"/>
      <name val="맑은 고딕"/>
    </font>
    <font>
      <b/>
      <sz val="11"/>
      <color rgb="FFFFFFFF"/>
      <name val="맑은 고딕"/>
    </font>
  </fonts>
  <fills count="3">
    <fill><patternFill patternType="none"/></fill>
    <fill><patternFill patternType="gray125"/></fill>
    <fill>
      <patternFill patternType="solid">
        <fgColor rgb="FF0F172A"/>
      </patternFill>
    </fill>
  </fills>
  <borders count="1">
    <border>
      <left/><right/><top/><bottom/><diagonal/>
    </border>
  </borders>
  <cellStyleXfs count="1">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0"/>
  </cellStyleXfs>
  <cellXfs count="2">
    <xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
    <xf numFmtId="0" fontId="1" fillId="2" borderId="0" xfId="0" applyFont="1" applyFill="1"/>
  </cellXfs>
</styleSheet>"""

    private fun buildDiarySheetXml(entries: List<DiaryEntry>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
""")
        // Header
        sb.append(makeRow(1, listOf("일시", "제목", "방문 장소", "상세 주소", "총 지출액(원)", "주행거리(km)", "골프 여부", "사진 수", "요약 내용")))

        entries.forEachIndexed { idx, e ->
            sb.append(makeRow(
                idx + 2,
                listOf(
                    e.date.format(DATE_FORMATTER),
                    e.title,
                    e.placeName ?: "-",
                    e.address ?: "-",
                    e.totalExpense,
                    e.drivingDistanceKm,
                    if (e.hasGolfRound) "⛳ 라운드" else "-",
                    e.photoUris.size,
                    e.summary
                )
            ))
        }

        sb.append("""  </sheetData>
</worksheet>""")
        return sb.toString()
    }

    private fun buildExpenseSheetXml(transactions: List<Transaction>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
""")
        // Header
        sb.append(makeRow(1, listOf("일시", "가맹점 / 내용", "결제 금액(원)", "지출 카테고리", "결제 수단", "카드 / 은행명", "메모 / 비고", "자동 분류")))

        transactions.forEachIndexed { idx, tx ->
            sb.append(makeRow(
                idx + 2,
                listOf(
                    tx.timestamp.format(DATE_FORMATTER),
                    tx.merchantName,
                    tx.amount,
                    tx.category.displayName,
                    tx.paymentMethod.name,
                    tx.cardOrBankName ?: "-",
                    tx.transferMemo ?: "-",
                    if (tx.isAutoCategorized) "자동" else "수동"
                )
            ))
        }

        sb.append("""  </sheetData>
</worksheet>""")
        return sb.toString()
    }

    private fun buildCarSheetXml(vehicleLogs: List<VehicleLog>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
""")
        // Header
        sb.append(makeRow(1, listOf("일시", "유형", "주행거리(km)", "주유량(L)", "주유금액(원)", "추정연비(km/L)", "주유소명", "메모")))

        vehicleLogs.forEachIndexed { idx, v ->
            sb.append(makeRow(
                idx + 2,
                listOf(
                    v.timestamp.format(DATE_FORMATTER),
                    when (v.logType) {
                        VehicleLogType.REFUELING -> "주유"
                        VehicleLogType.TRIP_DRIVING -> "운행"
                        VehicleLogType.MAINTENANCE -> "정비"
                        VehicleLogType.PARKING -> "주차"
                    },
                    v.tripDistanceKm,
                    v.fuelAmountLiters,
                    v.fuelCost,
                    v.estimatedEfficiencyKmPerL ?: 0.0,
                    v.gasStationName ?: "-",
                    v.note ?: "-"
                )
            ))
        }

        sb.append("""  </sheetData>
</worksheet>""")
        return sb.toString()
    }

    private fun buildGolfSheetXml(golfRounds: List<GolfRound>): String {
        val sb = StringBuilder()
        sb.append("""<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <sheetData>
""")
        // Header
        sb.append(makeRow(1, listOf("일자", "골프장명", "유형", "총 타수(Score)", "총 퍼트수", "그린피 지출(원)", "메모")))

        golfRounds.forEachIndexed { idx, g ->
            sb.append(makeRow(
                idx + 2,
                listOf(
                    g.roundDate.format(DATE_FORMATTER),
                    g.clubName,
                    if (g.golfType == com.autologue.app.domain.model.GolfType.SCREEN) "스크린" else "필드",
                    g.totalScore ?: 0,
                    g.totalPutts ?: 0,
                    g.greenFeeExpense,
                    g.memo ?: "-"
                )
            ))
        }

        sb.append("""  </sheetData>
</worksheet>""")
        return sb.toString()
    }

    private fun makeRow(rowNum: Int, values: List<Any>): String {
        val sb = StringBuilder()
        val isHeader = rowNum == 1
        val styleAttr = if (isHeader) " s=\"1\"" else ""

        sb.append("    <row r=\"$rowNum\">\n")
        values.forEachIndexed { colIdx, value ->
            val colLetter = getColumnLetter(colIdx)
            val cellRef = "$colLetter$rowNum"

            when (value) {
                is Number -> {
                    sb.append("      <c r=\"$cellRef\"$styleAttr><v>$value</v></c>\n")
                }
                else -> {
                    val str = escapeXml(value.toString())
                    sb.append("      <c r=\"$cellRef\" t=\"inlineStr\"$styleAttr><is><t>$str</t></is></c>\n")
                }
            }
        }
        sb.append("    </row>\n")
        return sb.toString()
    }

    private fun getColumnLetter(colIdx: Int): String {
        var col = colIdx
        var result = ""
        while (col >= 0) {
            result = ('A'.code + (col % 26)).toChar() + result
            col = (col / 26) - 1
        }
        return result
    }
}
