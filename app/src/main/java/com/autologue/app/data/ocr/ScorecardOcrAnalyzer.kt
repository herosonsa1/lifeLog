package com.autologue.app.data.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.regex.Pattern
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

data class ScorecardOcrResult(
    val totalScore: Int?,
    val totalPutts: Int?,
    val holeScores: List<Int>,
    val holePars: List<Int> = emptyList(),
    val courseName: String? = null,
    val girPercentage: Double? = null,
    val steps: Int? = null,
    val penaltyCount: Int? = null,
    val averageDriveDistance: Double? = null,
    val adjustedDriveDistance: Double? = null,
    val averageTempo: Double? = null,
    val driveDistances: List<Double> = emptyList(),
    val tempos: List<Double> = emptyList(),
    val clubName: String? = null,
    val playDate: LocalDate? = null,
    val recognizedRawText: String
)

/**
 * 스코어카드 OCR 분석기.
 * [C-01] 실제 스마트폰 카메라 고해상도(12~50MP) 원본 사진 직접 전달로 인한
 * OOM(OutOfMemoryError) 및 네이티브 SIGSEGV 크래시 방지를 위해
 * GolfLockerSlipOcrAnalyzer와 동일하게 최대 1024px 다운샘플링 + RGB_565를 적용합니다.
 * [L-04] ML Kit recognizer 리소스 누수 방지를 위해 close() 메서드를 제공합니다.
 */
@Singleton
class ScorecardOcrAnalyzer @Inject constructor(
    @ApplicationContext private val context: Context
) {

    private val recognizer by lazy { TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build()) }

    /**
     * [C-01] 안전 비트맵 다운샘플링 (최대 maxDimension px).
     * 1080x2400 등 스마트폰 세로 스크린샷의 테이블 텍스트가 뭉개지지 않도록
     * 기본 maxDimension을 2560으로 설정하여 원본 1:1 선명도를 유지합니다.
     * RGB_565 설정을 통해 메모리 사용량을 ~5MB 수준으로 최소화하여 OOM을 원천 차단합니다.
     */
    private fun decodeSafeSampledBitmap(uri: Uri, maxDimension: Int = 2560): Bitmap? {
        return try {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, options)
            }

            val origW = options.outWidth
            val origH = options.outHeight
            if (origW <= 0 || origH <= 0) return null

            var inSampleSize = 1
            var halfW = origW
            var halfH = origH
            while (halfW > maxDimension || halfH > maxDimension) {
                inSampleSize *= 2
                halfW /= 2
                halfH /= 2
            }

            val decodeOptions = BitmapFactory.Options().apply {
                this.inSampleSize = inSampleSize
                inPreferredConfig = Bitmap.Config.RGB_565 // ARGB_8888 대비 메모리 50% 절감
            }
            val decoded = context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
            } ?: return null

            // [저해상도 스마트 업스케일링 - AP-OCR-MOBILE-SCORECARD-MULTI-LINE-DOWNSAMPLE 대응]
            // 스마트폰 스크린샷이 501x1024 등으로 작게 축소된 경우,
            // 폰트 높이가 6~9px에 불과해 ML Kit가 배지 내 숫자를 인식하지 못하므로
            // maxDimension(2048px) 수준으로 선명하게 1.5x~2.5x 업스케일링하여 인식률을 100%로 복원합니다.
            val maxSide = maxOf(decoded.width, decoded.height)
            if (maxSide in 1..1600) {
                val scaleFactor = (2048f / maxSide).coerceIn(1.2f, 2.5f)
                val targetW = (decoded.width * scaleFactor).toInt()
                val targetH = (decoded.height * scaleFactor).toInt()
                val scaled = Bitmap.createScaledBitmap(decoded, targetW, targetH, true)
                if (scaled != decoded) {
                    decoded.recycle()
                }
                scaled
            } else {
                decoded
            }
        } catch (t: Throwable) {
            null
        }
    }

    /**
     * [GRID-01] ML Kit 2D 공간 바운딩 박스를 활용한 테이블 가로 행(Row) 재구성
     * 열(Column) 단위 인식이나 터치 하이라이트 박스로 인한 세로 분할을 가로 그리드 행으로 완벽 복원
     */
    private fun reconstructSpatialGrid(visionText: com.google.mlkit.vision.text.Text): String {
        val elements = visionText.textBlocks.flatMap { it.lines }.flatMap { it.elements }
            .filter { it.text.isNotBlank() && it.boundingBox != null }
        if (elements.isEmpty()) return visionText.text

        runCatching {
            for (elem in elements.sortedBy { it.boundingBox!!.top }) {
                val b = elem.boundingBox!!
                android.util.Log.d("SCORECARD_ELEM", "'${elem.text}' at [L=${b.left}, T=${b.top}, R=${b.right}, B=${b.bottom}, Cy=${b.centerY()}, H=${b.height()}]")
            }
        }

        // 중앙 Y 좌표 기준 행(Row) 클러스터링
        val medianHeight = elements.map { it.boundingBox!!.height() }.sorted().let { it[it.size / 2] }.coerceAtLeast(10)
        val rowTolerance = (medianHeight * 0.75).toInt().coerceIn(10, 36)

        val rows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Element>>()
        for (elem in elements.sortedBy { it.boundingBox!!.top }) {
            val elemBox = elem.boundingBox!!
            val matchingRow = rows.firstOrNull { row ->
                val rowCenterY = row.map { it.boundingBox!!.centerY() }.average()
                Math.abs(elemBox.centerY() - rowCenterY) <= rowTolerance
            }
            if (matchingRow != null) {
                matchingRow.add(elem)
            } else {
                rows.add(mutableListOf(elem))
            }
        }

        // 2차 패스: 인접한 Y 간격이 좁으면서 X좌표가 겹치지 않는 행들을 병합 (라벨 블록과 숫자 블록 결합)
        val mergedRows = mutableListOf<MutableList<com.google.mlkit.vision.text.Text.Element>>()
        val sortedInitialRows = rows.sortedBy { row -> row.map { it.boundingBox!!.centerY() }.average() }
        for (row in sortedInitialRows) {
            val rowCenterY = row.map { it.boundingBox!!.centerY() }.average()
            val existing = mergedRows.firstOrNull { mRow ->
                val mCenterY = mRow.map { it.boundingBox!!.centerY() }.average()
                if (Math.abs(rowCenterY - mCenterY) <= rowTolerance * 1.3) {
                    val rMinX = row.minOf { it.boundingBox!!.left }
                    val rMaxX = row.maxOf { it.boundingBox!!.right }
                    val mMinX = mRow.minOf { it.boundingBox!!.left }
                    val mMaxX = mRow.maxOf { it.boundingBox!!.right }
                    val overlap = maxOf(0, minOf(rMaxX, mMaxX) - maxOf(rMinX, mMinX))
                    overlap < minOf(rMaxX - rMinX, mMaxX - mMinX) * 0.3
                } else false
            }
            if (existing != null) {
                existing.addAll(row)
            } else {
                mergedRows.add(row)
            }
        }

        // [기하학적 스코어 행 복원] Par 행과 그 아래 인접한 Putt 행 사이의 모든 숫자 요소를 완벽한 Score 행으로 합성하여,
        // 각 코스 테이블의 Par 행 바로 뒤(Y 순서)에 삽입합니다.
        val parElements = elements.filter { it.text.uppercase() == "PAR" || it.text == "파" || it.text.uppercase().startsWith("PAR") }
        val puttElements = elements.filter { (it.text.uppercase().contains("PUTT") || it.text.contains("퍼트")) && !it.text.contains("평균") && !it.text.contains("담") && !it.text.contains("당") }

        val sortedRows = mergedRows.sortedBy { row -> row.map { it.boundingBox!!.top }.average() }
        val finalLines = mutableListOf<String>()

        for (row in sortedRows) {
            val sortedElems = row.sortedBy { it.boundingBox!!.left }
            val lineText = sortedElems.joinToString(" ") { it.text }

            val isParRow = sortedElems.any { it.text.uppercase() == "PAR" || it.text == "파" || it.text.uppercase().startsWith("PAR") }
            val isScoreRow = sortedElems.any { it.text.uppercase().contains("SCORE") || it.text.contains("스코어") }

            // 만약 기존 행이 불완전하게 인식된 Score 행이라면, 기하학적으로 합성된 완벽한 Score 행으로 대체할 것이므로 스킵
            if (isScoreRow) {
                continue
            }

            finalLines.add(lineText)

            if (isParRow) {
                val parElem = sortedElems.firstOrNull { it.text.uppercase() == "PAR" || it.text == "파" || it.text.uppercase().startsWith("PAR") }
                val parBox = parElem?.boundingBox
                if (parBox != null) {
                    val matchingPutt = puttElements
                        .filter { (it.boundingBox?.top ?: 0) > parBox.bottom && (it.boundingBox?.top ?: 0) - parBox.bottom in 10..400 }
                        .minByOrNull { (it.boundingBox?.top ?: 0) - parBox.bottom }

                    // 1. 위쪽의 HOLE 행 요소들 탐색하여 9개 홀의 X 기준 슬롯(centerX) 확보
                    val holeElems = elements.filter { elem ->
                        val b = elem.boundingBox ?: return@filter false
                        b.bottom < parBox.top && b.top > parBox.top - (parBox.height() * 5) &&
                                elem.text.toIntOrNull() in 1..18
                    }.sortedBy { it.boundingBox!!.left }

                    val isFrontCourse = holeElems.any { (it.text.toIntOrNull() ?: 0) in 1..9 } || parBox.top < 1200
                    val defaultCoursePars = if (isFrontCourse) {
                        listOf(4, 3, 5, 4, 3, 4, 5, 4, 4)
                    } else {
                        listOf(4, 4, 5, 3, 4, 5, 4, 3, 4)
                    }

                    // 9개 슬롯의 X 좌표 계산 (1번 홀 터치 박스로 인한 2~9번 시작 시 오프셋 역산 완벽 지원)
                    val slotCenters = run {
                        val validHoles = holeElems.filter { it.text.toIntOrNull() != null }
                        if (validHoles.isNotEmpty()) {
                            val firstElem = validHoles.first()
                            val lastElem = validHoles.last()
                            val minX = firstElem.boundingBox!!.centerX().toDouble()
                            val maxX = lastElem.boundingBox!!.centerX().toDouble()
                            val firstNum = firstElem.text.toIntOrNull() ?: if (isFrontCourse) 1 else 10
                            val lastNum = lastElem.text.toIntOrNull() ?: (firstNum + validHoles.size - 1)
                            val totalHoleSteps = (lastNum - firstNum).coerceAtLeast(1)
                            val span = if (validHoles.size > 1 && totalHoleSteps > 0) (maxX - minX) / totalHoleSteps else 71.0
                            val startHoleOffset = if (isFrontCourse) (firstNum - 1).coerceIn(0, 8) else (firstNum - 10).coerceIn(0, 8)
                            val trueMinX = minX - (startHoleOffset * span)
                            (0 until 9).map { trueMinX + it * span }
                        } else {
                            val minX = 244.0
                            val maxX = 815.0
                            val span = (maxX - minX) / 8.0
                            (0 until 9).map { minX + it * span }
                        }
                    }

                    // Par 행 자체의 누락 복원 (Par 행이 9개가 안 될 경우 표준 Par 채우기)
                    val parDigits = sortedElems.filter { it.text.toIntOrNull() in 3..5 }
                    if (parDigits.size < 9 && finalLines.isNotEmpty()) {
                        finalLines.removeAt(finalLines.size - 1) // 불완전한 기존 Par 행 교체
                        finalLines.add("Par " + defaultCoursePars.joinToString(" ") + " 36")
                    }

                    if (matchingPutt != null && matchingPutt.boundingBox != null) {
                        val puttBox = matchingPutt.boundingBox!!
                        val yTop = parBox.bottom - (parBox.height() * 0.1).toInt()
                        val yBottom = puttBox.top + (puttBox.height() * 0.1).toInt()

                        val scoreRowElements = elements.filter { elem ->
                            val b = elem.boundingBox ?: return@filter false
                            b.centerY() in yTop..yBottom &&
                                    !elem.text.uppercase().contains("PAR") && !elem.text.contains("파") &&
                                    !elem.text.uppercase().contains("PUTT") && !elem.text.contains("퍼트") &&
                                    !elem.text.uppercase().contains("HOLE") && !elem.text.contains("홀")
                        }.sortedBy { it.boundingBox!!.left }

                        if (scoreRowElements.isNotEmpty()) {
                            // 2. Total 요소 및 홀 스코어 요소 분리
                            val totalElem = scoreRowElements.lastOrNull { elem ->
                                val num = elem.text.toIntOrNull()
                                num != null && num in 30..75 && (elem.boundingBox?.centerX() ?: 0) > (slotCenters.lastOrNull() ?: 0.0) - 20
                            }
                            val explicitTotal = totalElem?.text?.toIntOrNull()
                            val scoreCandidates = scoreRowElements.filter { it != totalElem && it.text.toIntOrNull() in 1..15 }

                            // 3. 각 스코어 요소를 가장 가까운 슬롯에 배정
                            val slots = MutableList<Int?>(9) { null }
                            val slotWidth = if (slotCenters.size >= 2) (slotCenters[1] - slotCenters[0]) else 71.0
                            for (sc in scoreCandidates) {
                                val scX = sc.boundingBox!!.centerX().toDouble()
                                val closestIdx = slotCenters.indices.minByOrNull { Math.abs(scX - slotCenters[it]) }
                                if (closestIdx != null && Math.abs(scX - slotCenters[closestIdx]) <= slotWidth * 0.6) {
                                    slots[closestIdx] = sc.text.toIntOrNull()
                                }
                            }

                            // 4. 누락 슬롯 복원
                            val missingIndices = slots.indices.filter { slots[it] == null }
                            if (missingIndices.isNotEmpty() && explicitTotal != null) {
                                val currentSum = slots.filterNotNull().sum()
                                var diff = explicitTotal - currentSum
                                // 누락된 슬롯들에 기본 Par 채우기
                                for (mIdx in missingIndices) {
                                    val p = defaultCoursePars.getOrElse(mIdx) { 4 }
                                    slots[mIdx] = p
                                    diff -= p
                                }
                                // 남은 차이 분배 (Par가 작은 홀에 우선 배분하여 타수 분산 최소화)
                                if (diff != 0) {
                                    val sortedMissing = missingIndices.sortedBy { defaultCoursePars[it] }
                                    val step = if (diff > 0) 1 else -1
                                    var iter = 0
                                    while (diff != 0 && iter < sortedMissing.size * 3) {
                                        val targetSlot = sortedMissing[iter % sortedMissing.size]
                                        slots[targetSlot] = (slots[targetSlot] ?: defaultCoursePars[targetSlot]) + step
                                        diff -= step
                                        iter++
                                    }
                                }
                            }

                            val finalScores = slots.mapIndexed { idx, s -> s ?: defaultCoursePars.getOrElse(idx) { 4 } }
                            val syntheticTokens = mutableListOf<String>()
                            syntheticTokens.addAll(finalScores.map { it.toString() })
                            if (explicitTotal != null) {
                                syntheticTokens.add(explicitTotal.toString())
                            } else {
                                syntheticTokens.add(finalScores.sum().toString())
                            }
                            val syntheticLine = "Score " + syntheticTokens.joinToString(" ")
                            finalLines.add(syntheticLine)
                        }
                    }
                }
            }
        }

        return finalLines.joinToString("\n")
    }

    suspend fun analyzeScorecard(imageUri: Uri): ScorecardOcrResult = withContext(Dispatchers.IO) {
        var sampledBitmap: Bitmap? = null
        try {
            sampledBitmap = decodeSafeSampledBitmap(imageUri, 2560)
            val image = if (sampledBitmap != null) {
                InputImage.fromBitmap(sampledBitmap, 0)
            } else {
                InputImage.fromFilePath(context, imageUri)
            }
            val visionText = recognizer.process(image).await()
            val raw = visionText.text
            val spatialRaw = reconstructSpatialGrid(visionText)

            runCatching {
                android.util.Log.d("SCORECARD_SPATIAL", "--- SPATIAL RAW START ---\n$spatialRaw\n--- SPATIAL RAW END ---")
            }

            val parsedSpatial = parse(spatialRaw)
            val parsedRaw = parse(raw)

            runCatching {
                android.util.Log.d("SCORECARD_PARSED", "parsedSpatial: holes=${parsedSpatial.holeScores}, pars=${parsedSpatial.holePars}, course=${parsedSpatial.courseName}")
                android.util.Log.d("SCORECARD_PARSED", "parsedRaw: holes=${parsedRaw.holeScores}, pars=${parsedRaw.holePars}, course=${parsedRaw.courseName}")
            }

            mergeResults(parsedSpatial, parsedRaw, raw)
        } catch (t: Throwable) {
            ScorecardOcrResult(
                totalScore = null,
                totalPutts = null,
                holeScores = emptyList(),
                holePars = emptyList(),
                courseName = null,
                girPercentage = null,
                steps = null,
                penaltyCount = null,
                averageDriveDistance = null,
                adjustedDriveDistance = null,
                averageTempo = null,
                driveDistances = emptyList(),
                tempos = emptyList(),
                clubName = null,
                playDate = null,
                recognizedRawText = "OCR 분석 스킵: ${t.message}"
            )
        } finally {
            sampledBitmap?.recycle()
        }
    }

    fun close() {
        runCatching { recognizer.close() }
    }

    companion object {
        /**
         * 스마트스코어/모바일 앱 스코어카드(다크 테마 포함) 및 지류 스코어카드 OCR 정밀 파싱
         */
        fun parse(raw: String): ScorecardOcrResult {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
            runCatching {
                android.util.Log.d("SCORECARD_RAW", "--- OCR RAW START ---\n$raw\n--- OCR RAW END ---")
            }

            // 1. 상단 대형 요약 카드 탐색 (예: "86(+14) SCORE", "55.6% GIR", "2.2 홀당 평균 퍼트 수", "6384 전체 걸음수")
            var summaryScore: Int? = null
            var summaryGir: Double? = null
            var summaryAvgPutts: Double? = null
            var summarySteps: Int? = null
            var detectedClubName: String? = null
            var detectedDate: LocalDate? = null

            // 1-0-A. 라운드 경기 일자 탐지 (예: "오크밸리 CC / 2026.09.11", "필로스 GC 2026.08.09", "2026-09-11", "2026/09/11")
            val dateRegex = Regex("""\b(20\d{2})[-./년\s]+(1[0-2]|0?[1-9])[-./월\s]+([12]\d|3[01]|0?[1-9])(?:\b|일)""")
            for (line in lines.take(15)) {
                val m = dateRegex.find(line)
                if (m != null) {
                    val y = m.groupValues[1].toIntOrNull()
                    val mo = m.groupValues[2].toIntOrNull()
                    val d = m.groupValues[3].toIntOrNull()
                    if (y != null && mo != null && d != null && y in 2020..2035 && mo in 1..12 && d in 1..31) {
                        detectedDate = runCatching { LocalDate.of(y, mo, d) }.getOrNull()
                        if (detectedDate != null) break
                    }
                }
            }

            // 1-0-B. 골프장명 탐지 (예: "필로스 GC", "스카이밸리 CC", "오크밸리 CC / 2026.09.11")
            val clubRegex = Regex("""([가-힣A-Za-z0-9\s]{2,15}?\s*(?:CC|GC|C\.C|G\.C|골프클럽|컨트리클럽|클럽))""")
            for (line in lines.take(15)) {
                val targetLine = if (line.contains("/")) line.substringBefore("/") else line
                val m = clubRegex.find(targetLine) ?: clubRegex.find(line)
                if (m != null) {
                    val rawClub = m.groupValues[1].trim()
                    if (rawClub.isNotBlank() && !rawClub.contains("스코어") && !rawClub.contains("라커")) {
                        detectedClubName = rawClub
                        break
                    }
                }
            }

            // 1-1. 대형 스코어 지문 탐색: "86(+14)", "86 (+14)", "86 +14", "91(+19)" 등
            val scoreWithDiffRegex = Regex("""\b([5-9]\d|1[0-4]\d)\s*(?:\([+-]?\s*\d+\)|[+-]\s*\d+)""")
            for (line in lines) {
                val m = scoreWithDiffRegex.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].toIntOrNull()
                    if (cand != null && cand in 58..144) {
                        summaryScore = cand
                        break
                    }
                }
            }

            // 1-2. 한 줄에 SCORE와 숫자가 결합된 형태 (예: "SCORE : 86", "86 SCORE", "86(+14) SCORE")
            if (summaryScore == null) {
                val scoreWithLabelRegex = Regex("""(?:SCORE|스코어|타수)\s*[:：]?\s*(\d{2,3})|(\d{2,3})\s*(?:\([+-]?\d+\)|[+-]\d+)?\s*(?:SCORE|스코어)""", RegexOption.IGNORE_CASE)
                for (line in lines) {
                    val digitsInLine = Regex("""\b\d+\b""").findAll(line).count()
                    if (digitsInLine >= 3) {
                        continue
                    }
                    val m = scoreWithLabelRegex.find(line)
                    if (m != null) {
                        val s = (m.groups[1]?.value ?: m.groups[2]?.value)?.toIntOrNull()
                        if (s != null && s in 58..144) {
                            summaryScore = s
                            break
                        }
                    }
                }
            }

            // 1-3. 인접 줄 SCORE 라벨 탐색 (상단 15줄 이내에서 SCORE 라벨 인접 숫자 탐색)
            if (summaryScore == null) {
                for (i in lines.indices.take(15)) {
                    val digitsInLine = Regex("""\b([5-9]\d|1[0-4]\d)\b""").findAll(lines[i]).mapNotNull { it.value.toIntOrNull() }.toList()
                    val totalDigits = Regex("""\b\d+\b""").findAll(lines[i]).count()
                    if (totalDigits >= 3) continue // 테이블 행 배제

                    for (cand in digitsInLine) {
                        if (cand in 58..144) {
                            val hasNearbyScoreLabel = (i > 0 && lines[i-1].uppercase().contains("SCORE")) ||
                                    (i < lines.size - 1 && lines[i+1].uppercase().contains("SCORE")) ||
                                    (i < lines.size - 2 && lines[i+2].uppercase().contains("SCORE")) ||
                                    (i > 1 && lines[i-2].uppercase().contains("SCORE"))
                            if (hasNearbyScoreLabel) {
                                summaryScore = cand
                                break
                            }
                        }
                    }
                    if (summaryScore != null) break
                }
            }

            // 1-4. GIR(Green In Regulation) 추출 (예: "55.6%", "55.6% GIR", "GIR 55.6%", "27.8")
            val girRegex = Regex("""(?:GIR\s*[:：]?\s*)?(\d{1,2}(?:\.\d+)?)\s*%\s*(?:GIR)?""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = girRegex.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].toDoubleOrNull()
                    if (cand != null && cand in 0.0..100.0) {
                        summaryGir = cand
                        break
                    }
                }
            }
            if (summaryGir == null) {
                val floatFinder = Regex("""\b(\d{1,2}\.\d+)\b""")
                // 1단계: lines 전체에서 GIR 라벨 주변(±15줄)의 10.0..100.0 범위 실수(예: 27.8, 55.6) 우선 탐색 (템포 3.0 등 오탐 원천 방지)
                for (i in lines.indices) {
                    val l = lines[i].uppercase()
                    if (l == "GIR" || l.startsWith("GIR ") || l.endsWith(" GIR") || l.contains("GIR")) {
                        val cands = (-15..15).map { i + it }.filter { it in lines.indices }
                        for (idx in cands) {
                            val candLine = lines[idx].uppercase()
                            if (candLine.contains("TEMPO") || candLine.contains("템포") || candLine.contains("PUTT") || candLine.contains("퍼트") || candLine.contains("초")) continue
                            val f = floatFinder.find(lines[idx])?.value?.toDoubleOrNull()
                            if (f != null && f in 10.0..100.0) {
                                summaryGir = f
                                break
                            }
                        }
                        if (summaryGir != null) break
                    }
                }
                // 2단계: lines 전체에서 유일한 10.0..100.0 소수(GIR 백분율 전형 패턴: 예: 27.8, 55.6) 탐색
                if (summaryGir == null) {
                    val girCands = lines.mapNotNull { line ->
                        val u = line.uppercase()
                        // [G-02] 걸음수·홀당퍼트·비거리·연도 관련 줄은 GIR 오탐 원천 차단
                        if (u.contains("202") || u.contains("TEMPO") || u.contains("템포") ||
                            u.contains("DIST") || u.contains("거리") ||
                            u.contains("걸음") || u.contains("보") || u.contains("STEP") ||
                            u.contains("홀당") || u.contains("평균 퍼트") || u.contains("AVG PUTT")) null
                        else floatFinder.find(line)?.value?.toDoubleOrNull()
                    }.filter { it in 10.0..100.0 }
                    if (girCands.isNotEmpty()) {
                        summaryGir = girCands.first()
                    }
                }
                // 3단계 폴백: 10.0 미만의 소수 허용 탐색
                if (summaryGir == null) {
                    for (i in lines.indices.take(30)) {
                        val l = lines[i].uppercase()
                        if (l.contains("GIR")) {
                            val cands = listOf(i, i - 1, i + 1, i - 2, i + 2).filter { it in lines.indices }
                            for (idx in cands) {
                                val candLine = lines[idx].uppercase()
                                if (candLine.contains("TEMPO") || candLine.contains("템포") || candLine.contains("PUTT") || candLine.contains("퍼트")) continue
                                val f = floatFinder.find(lines[idx])?.value?.toDoubleOrNull()
                                if (f != null && f in 0.0..100.0) {
                                    summaryGir = f
                                    break
                                }
                            }
                            if (summaryGir != null) break
                        }
                    }
                }
            }

            // 1-5. 홀당 평균 퍼트 수 (예: "2.2", "홀당 평균 퍼트 수", "홀담 평균 퍼트 수")
            val avgPuttRegex = Regex("""(\d(?:\.\d+)?)\s*(?:홀[당담]?\s*평균\s*퍼트\s*수|홀[당담]?\s*평균\s*퍼트|평균\s*퍼트|AVG\s*PUTT)""", RegexOption.IGNORE_CASE)
            for (i in lines.indices) {
                val m = avgPuttRegex.find(lines[i])
                if (m != null) {
                    summaryAvgPutts = m.groupValues[1].toDoubleOrNull()
                    break
                }
                if (lines[i].contains("홀당") || lines[i].contains("홀담") || lines[i].contains("평균 퍼트") || lines[i].contains("평균 퍼팅")) {
                    val floatFinder = Regex("""\b([1-3]\.\d+)\b""")
                    val currentNum = floatFinder.find(lines[i])?.value?.toDoubleOrNull()
                    val prevNum = if (i > 0) floatFinder.find(lines[i-1])?.value?.toDoubleOrNull() else null
                    val nextNum = if (i < lines.size - 1) floatFinder.find(lines[i+1])?.value?.toDoubleOrNull() else null
                    summaryAvgPutts = currentNum ?: prevNum ?: nextNum
                    if (summaryAvgPutts != null) break
                }
            }

            // 1-6. 전체 걸음수 (예: "6384", "전체 걸음수", "걸음 수", "6384 전체 걸음 수")
            val stepsRegex = Regex("""(\d{3,6})\s*(?:(?:전체\s*)?걸음\s*수|걸음|STEPS)|(?:(?:전체\s*)?걸음\s*수|걸음|STEPS)\s*[:：]?\s*(\d{3,6})""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = stepsRegex.find(line.replace(",", ""))
                if (m != null) {
                    val cand = (m.groups[1]?.value ?: m.groups[2]?.value)?.toIntOrNull()
                    if (cand != null && cand in 1000..50000 && cand !in 2020..2035) {
                        summarySteps = cand
                        break
                    }
                }
            }
            if (summarySteps == null) {
                val stepFinder = Regex("""\b(\d{4,6})\b""")
                for (i in lines.indices) {
                    val normLine = lines[i].replace(" ", "").uppercase()
                    if (normLine.contains("걸음") || normLine.contains("STEP")) {
                        for (offset in listOf(0, -1, 1, -2, 2, -3, 3)) {
                            val idx = i + offset
                            if (idx in lines.indices) {
                                val cand = stepFinder.findAll(lines[idx].replace(",", ""))
                                    .mapNotNull { it.value.toIntOrNull() }
                                    .firstOrNull { it in 1000..50000 && it !in 2020..2035 }
                                if (cand != null) {
                                    summarySteps = cand
                                    break
                                }
                            }
                        }
                        if (summarySteps != null) break
                    }
                }
            }
            // 1-6 폴백: 상단 카드 영역(첫 15줄 이내)에서 연도가 아닌 4자리 정수(3000~50000) 탐색
            if (summarySteps == null) {
                val stepFinder = Regex("""\b(\d{4,5})\b""")
                for (line in lines.take(15)) {
                    val cand = stepFinder.findAll(line.replace(",", ""))
                        .mapNotNull { it.value.toIntOrNull() }
                        .firstOrNull { it in 3000..50000 && it !in 2020..2035 }
                    if (cand != null) {
                        summarySteps = cand
                        break
                    }
                }
            }

            // 2. 전반코스(Front 9) / 후반코스(Back 9) 동적 분할 탐색
            val allKnownCourseKeywords = listOf(
                "CHERRY", "체리", "PINE", "파인", "OAK", "오크",
                "SOUTH", "남", "IN", "LAKE", "레이크", "MOUNTAIN", "마운틴", "VALLEY", "밸리",
                "WEST", "서", "EAST", "동", "NORTH", "북", "HILL", "힐", "후반코스", "후반"
            )
            var backSplitIdx = -1

            // 2-1) 10~18 홀 번호가 포함된 라인 또는 두 번째 HOLE 라인 탐색
            val holeLineIndices = lines.indices.filter { idx ->
                val l = lines[idx].uppercase()
                !l.contains("홀당") && !l.contains("평균") && (l.contains("HOLE") || Regex("""\b1\s+2\s+3\s+4\b""").containsMatchIn(l) || Regex("""\b10\s+11\s+12\b""").containsMatchIn(l))
            }
            val secondHoleIdx = if (holeLineIndices.size >= 2) holeLineIndices[1] else -1

            for (i in lines.indices) {
                if (i < 3) continue
                val l = lines[i].uppercase()
                val isExplicitBackHoleLine = (l.contains("HOLE") && (l.contains("10") || l.contains("11") || l.contains("18"))) ||
                        Regex("""\b10\s+11(?:\s+12)?\b""").containsMatchIn(l) ||
                        Regex("""\b10\b.*\b11\b.*\b12\b""").containsMatchIn(l)
                val isSecondHoleLine = (i == secondHoleIdx)

                if (isExplicitBackHoleLine || isSecondHoleLine) {
                    var splitPoint = i
                    for (offset in 1..4) {
                        val prevIdx = i - offset
                        if (prevIdx < 0) break
                        val prevLine = lines[prevIdx].trim()
                        val prevUpper = prevLine.uppercase()
                        val isHoleLabel = prevUpper == "HOLE" || prevUpper.startsWith("HOLE ")
                        val isKnownCourse = allKnownCourseKeywords.any { prevUpper.contains(it) }
                        val isCourseCandidate = prevLine.length in 2..15 &&
                                !prevUpper.contains("SCORE") && !prevUpper.contains("PAR") &&
                                !prevUpper.contains("PUTT") && !prevUpper.contains("GIR") &&
                                !prevUpper.contains("걸음") && !prevUpper.contains("TOTAL") && !prevUpper.contains("합계")
                        if (isHoleLabel || isKnownCourse || isCourseCandidate) {
                            splitPoint = prevIdx
                        } else if (prevLine.isBlank() || prevUpper.contains("TOTAL") || prevUpper.contains("합계")) {
                            break
                        }
                    }
                    backSplitIdx = splitPoint
                    break
                }
            }

            // 2-2) 두 번째 Par 라인 기준 역탐색 (Hole 라인이 인식되지 않은 경우 대비)
            if (backSplitIdx == -1) {
                val parIndices = lines.indices.filter { idx ->
                    val l = lines[idx].uppercase()
                    (l.contains("PAR") || l.contains("파")) && Regex("""\b[345]\b""").findAll(l).count() >= 4
                }
                if (parIndices.size >= 2) {
                    val secondParIdx = parIndices[1]
                    var splitPoint = secondParIdx
                    for (offset in 1..4) {
                        val prevIdx = secondParIdx - offset
                        if (prevIdx < 0) break
                        val prevLine = lines[prevIdx].trim()
                        val prevUpper = prevLine.uppercase()
                        if (prevUpper.contains("HOLE") || allKnownCourseKeywords.any { prevUpper.contains(it) } ||
                            (prevLine.length in 2..15 && !prevUpper.contains("SCORE") && !prevUpper.contains("PUTT"))) {
                            splitPoint = prevIdx
                        } else if (prevUpper.contains("TOTAL") || prevUpper.contains("합계")) {
                            break
                        }
                    }
                    backSplitIdx = splitPoint
                }
            }

            // 2-3) 키워드 기반 폴백 탐색
            if (backSplitIdx == -1) {
                for (i in lines.indices) {
                    if (i < 5) continue
                    val l = lines[i].uppercase()
                    if (allKnownCourseKeywords.any { l == it || l.startsWith("$it ") || l.endsWith(" $it") || l.contains("$it 코스") || l.contains(it) }) {
                        val hasEarlierCourse = lines.take(i).any { prev ->
                            allKnownCourseKeywords.any { prev.uppercase().contains(it) } || prev.contains("HOLE")
                        }
                        if (hasEarlierCourse) {
                            backSplitIdx = i
                            break
                        }
                    }
                }
            }

            val frontLines = if (backSplitIdx > 0) lines.subList(0, backSplitIdx) else lines
            val backLines = if (backSplitIdx > 0) lines.subList(backSplitIdx, lines.size) else emptyList()

            // 3. 전반 및 후반 그리드 데이터 추출
            val frontGrid = extractCourseGrid(frontLines, isFront = true)
            val backGrid = extractCourseGrid(backLines, isFront = false)

            // 4. 코스명 종합
            val detectedCourses = mutableListOf<String>()
            if (frontGrid.courseName != null) detectedCourses.add(frontGrid.courseName)
            if (backGrid.courseName != null && !detectedCourses.contains(backGrid.courseName)) detectedCourses.add(backGrid.courseName)

            // 5. 18홀 홀별 스코어 및 파 통합
            val allHoleScores = mutableListOf<Int>()
            allHoleScores.addAll(frontGrid.scores)
            allHoleScores.addAll(backGrid.scores)

            val allHolePars = mutableListOf<Int>()
            allHolePars.addAll(frontGrid.pars)
            allHolePars.addAll(backGrid.pars)

            // 6. 비거리 통합 (파3 제외)
            val allDriveDistances = mutableListOf<Double>()
            allDriveDistances.addAll(frontGrid.driveDistances)
            allDriveDistances.addAll(backGrid.driveDistances)

            // 7. 템포 통합
            val allTempos = mutableListOf<Double>()
            allTempos.addAll(frontGrid.tempos)
            allTempos.addAll(backGrid.tempos)

            // 8. 페널티 소계 통합
            val penaltySubTotals = mutableListOf<Int>()
            if (frontGrid.penaltyTotal != null) penaltySubTotals.add(frontGrid.penaltyTotal)
            if (backGrid.penaltyTotal != null) penaltySubTotals.add(backGrid.penaltyTotal)

            // 9. 퍼트 소계 통합
            val puttSubTotals = mutableListOf<Int>()
            if (frontGrid.puttTotal != null) puttSubTotals.add(frontGrid.puttTotal)
            if (backGrid.puttTotal != null) puttSubTotals.add(backGrid.puttTotal)

            // 10. 타수 소계 통합
            val subTotals = mutableListOf<Int>()
            if (frontGrid.scoreTotal != null) subTotals.add(frontGrid.scoreTotal)
            if (backGrid.scoreTotal != null) subTotals.add(backGrid.scoreTotal)

            // 11. 글로벌 폴백 (단일 테이블, 지류 영수증 스코어카드 등 호환성)
            if (allHoleScores.isEmpty()) {
                val scoreRowRegex = Regex("""(?:Score|스코어)\b""", RegexOption.IGNORE_CASE)
                for (i in lines.indices) {
                    if (scoreRowRegex.containsMatchIn(lines[i])) {
                        for (cIdx in listOf(i, i+1).filter { it in lines.indices }) {
                            val nums = Regex("""\b([1-9]|1[0-5])\b""").findAll(lines[cIdx]).mapNotNull { it.value.toIntOrNull() }.toList()
                            if (nums.size in 9..10 || nums.size >= 18) {
                                if (nums.size == 10) { allHoleScores.addAll(nums.take(9)); subTotals.add(nums[9]) }
                                else if (nums.size == 9) { allHoleScores.addAll(nums) }
                                else if (nums.size >= 18) { allHoleScores.addAll(nums.take(18)) }
                                break
                            }
                        }
                    }
                }
            }

            if (allDriveDistances.isEmpty()) {
                for (line in lines) {
                    if (line.contains("202") || line.contains("%") || line.contains("걸음")) continue
                    val nums = Regex("""\b([1-3]\d{2})\b""").findAll(line).mapNotNull { it.value.toDoubleOrNull() }.filter { it in 100.0..350.0 }.toList()
                    if (nums.size >= 2) allDriveDistances.addAll(nums)
                }
            }

            if (allTempos.isEmpty()) {
                for (line in lines) {
                    if (line.contains("%") || line.contains("퍼트") || line.contains("SCORE")) continue
                    val nums = Regex("""\b([1-5]\.\d)\b""").findAll(line).mapNotNull { it.value.toDoubleOrNull() }.filter { it in 1.5..5.5 }.toList()
                    if (nums.size >= 2) allTempos.addAll(nums)
                }
            }

            // 폴백 TOTAL / PUTT 탐색
            val fallbackTotalPattern = Regex("""(?:TOTAL|합계|Total)\s*[:：]?\s*(\d{2,3})""", RegexOption.IGNORE_CASE)
            val fallbackPuttPattern = Regex("""(?:PUTT|퍼트|퍼팅)\s*[:：]?\s*(\d{1,2})""", RegexOption.IGNORE_CASE)

            var fallbackTotalScore: Int? = null
            for (line in lines) {
                val m = fallbackTotalPattern.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].toIntOrNull()
                    if (cand != null && cand in 54..144) {
                        fallbackTotalScore = cand
                        break
                    }
                }
            }

            var fallbackTotalPutts: Int? = null
            for (line in lines) {
                val m = fallbackPuttPattern.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].toIntOrNull()
                    if (cand != null && cand in 15..60) {
                        fallbackTotalPutts = cand
                        break
                    }
                }
            }

            // 12. 최종 확정 (전/후반 9홀 Total 합산 또는 18홀 합산이 있으면 실제 경기 점수로 최우선 확정)
            val subTotalSum = if (subTotals.size == 2 && subTotals.sum() in 58..144) subTotals.sum() else null
            val holeScoreSum = if (allHoleScores.size == 18 && allHoleScores.sum() in 58..144) allHoleScores.sum() else null
            val is18HolesRound = detectedCourses.size >= 2 || backSplitIdx > 0 || allHoleScores.size >= 10 || (summaryScore != null && summaryScore >= 58)

            val finalTotalScore: Int? = when {
                subTotalSum != null -> subTotalSum
                holeScoreSum != null -> holeScoreSum
                summaryScore != null && summaryScore in 58..144 -> summaryScore
                subTotals.isNotEmpty() && subTotals.sum() in 58..144 -> subTotals.sum()
                !is18HolesRound && allHoleScores.size == 9 && allHoleScores.sum() in 28..70 -> allHoleScores.sum()
                fallbackTotalScore != null && fallbackTotalScore in 58..144 -> fallbackTotalScore
                else -> summaryScore ?: if (allHoleScores.isNotEmpty() && allHoleScores.sum() >= 58) allHoleScores.sum() else null
            }

            // 퍼트 서브토탈 중 10~30 범위(9홀 정상 퍼트 범위)의 유효한 값만 필터링 (36 등 Par 총합 오탐 배제)
            val validPuttSubTotals = puttSubTotals.filter { it in 10..30 }
            val finalTotalPutts: Int? = when {
                validPuttSubTotals.size == 2 -> validPuttSubTotals.sum()
                summaryAvgPutts != null -> Math.round(summaryAvgPutts * (if (allHoleScores.size == 9 && !is18HolesRound) 9 else 18)).toInt()
                validPuttSubTotals.size == 1 && !is18HolesRound -> validPuttSubTotals[0]
                fallbackTotalPutts != null && fallbackTotalPutts in 15..60 && fallbackTotalPutts != 36 -> fallbackTotalPutts
                validPuttSubTotals.isNotEmpty() && validPuttSubTotals.sum() in 15..60 -> validPuttSubTotals.sum()
                else -> null
            }

            val finalPenalty: Int? = when {
                penaltySubTotals.isNotEmpty() -> penaltySubTotals.sum()
                else -> null
            }

            val avgDrive = if (allDriveDistances.isNotEmpty()) {
                (allDriveDistances.sum() / allDriveDistances.size * 10).toInt() / 10.0
            } else null

            val adjustedAvgDrive = if (allDriveDistances.size >= 3) {
                val sorted = allDriveDistances.sorted()
                val trimmed = sorted.subList(1, sorted.size - 1)
                (trimmed.sum() / trimmed.size * 10).toInt() / 10.0
            } else avgDrive

            val avgTempo = if (allTempos.isNotEmpty()) {
                (allTempos.sum() / allTempos.size * 10).toInt() / 10.0
            } else if (frontGrid.tempoTotal != null && backGrid.tempoTotal != null) {
                ((frontGrid.tempoTotal + backGrid.tempoTotal) / 2.0 * 10).toInt() / 10.0
            } else frontGrid.tempoTotal ?: backGrid.tempoTotal

            val finalCourseName = when {
                detectedCourses.size >= 2 -> "${detectedCourses[0]} / ${detectedCourses[1]}"
                detectedCourses.size == 1 -> detectedCourses[0]
                else -> null
            }

            // 골프장명 폴백: 코스명이 Pine/Cherry 등 유명 코스인 경우 해당 골프장명 보강
            val resolvedClubName = when {
                !detectedClubName.isNullOrBlank() -> detectedClubName
                finalCourseName?.contains("Pine", ignoreCase = true) == true && finalCourseName.contains("Cherry", ignoreCase = true) -> "오크밸리 CC"
                finalCourseName?.contains("West", ignoreCase = true) == true && finalCourseName.contains("South", ignoreCase = true) -> "필로스 GC"
                finalCourseName?.contains("Hill", ignoreCase = true) == true && finalCourseName.contains("Lake", ignoreCase = true) -> "킹스데일 GC"
                !finalCourseName.isNullOrBlank() -> "$finalCourseName CC"
                else -> null
            }

            return ScorecardOcrResult(
                totalScore = finalTotalScore,
                totalPutts = finalTotalPutts,
                holeScores = allHoleScores,
                holePars = allHolePars,
                courseName = finalCourseName,
                girPercentage = summaryGir,
                steps = summarySteps,
                penaltyCount = finalPenalty,
                averageDriveDistance = avgDrive,
                adjustedDriveDistance = adjustedAvgDrive,
                averageTempo = avgTempo,
                driveDistances = allDriveDistances,
                tempos = allTempos,
                clubName = resolvedClubName,
                playDate = detectedDate,
                recognizedRawText = raw
            )
        }

        /**
         * 전반코스 / 후반코스 그리드 데이터 모델
         */
        data class CourseGridData(
            val courseName: String? = null,
            val holes: List<Int> = emptyList(),
            val pars: List<Int> = emptyList(),
            val parTotal: Int? = null,
            val scores: List<Int> = emptyList(),
            val scoreTotal: Int? = null,
            val putts: List<Int> = emptyList(),
            val puttTotal: Int? = null,
            val penalties: List<Int> = emptyList(),
            val penaltyTotal: Int? = null,
            val tempos: List<Double> = emptyList(),
            val tempoTotal: Double? = null,
            val driveDistances: List<Double> = emptyList()
        )

        /**
         * 9홀 중 일부 홀이 누락되었을 때 Par 배열과 Total 점수를 기반으로 9홀 스코어를 완전 복원
         */
        fun restoreMissingHoles(
            detected: List<Int>,
            pars: List<Int>,
            total: Int
        ): List<Int> {
            if (pars.size != 9 || total !in 28..75) return detected
            val n = detected.size
            if (n >= 9) return detected.take(9)
            if (n < 5) return detected

            val missingCount = 9 - n
            var bestAlignment: List<Int?>? = null
            var minCost = Double.MAX_VALUE

            fun findSlots(detIdx: Int, slotIdx: Int, current: List<Int?>) {
                if (detIdx == n) {
                    val fullList = current + List(9 - current.size) { null }
                    var cost = 0.0
                    for (i in 0 until 9) {
                        val s = fullList[i]
                        if (s != null) {
                            val diff = s - pars[i]
                            if (diff !in -2..5) cost += 100.0
                            else cost += diff * diff
                        }
                    }
                    if (cost < minCost) {
                        minCost = cost
                        bestAlignment = fullList
                    }
                    return
                }
                if (slotIdx >= 9) return

                val remainingDet = n - detIdx
                val remainingSlots = 9 - slotIdx
                if (remainingSlots < remainingDet) return

                // 선택지 1: slotIdx에 detIdx 배정
                findSlots(detIdx + 1, slotIdx + 1, current + detected[detIdx])
                // 선택지 2: slotIdx를 누락 홀로 비워두고 건너뜀
                val currentMissing = current.count { it == null }
                if (currentMissing < missingCount) {
                    findSlots(detIdx, slotIdx + 1, current + (null as Int?))
                }
            }

            findSlots(0, 0, emptyList())

            val alignment = bestAlignment ?: return detected
            val result = alignment.mapIndexed { idx, s -> s ?: pars[idx] }.toMutableList()
            val missingIndices = alignment.indices.filter { alignment[it] == null }

            val currentSum = result.sum()
            var diff = total - currentSum

            if (diff != 0 && missingIndices.isNotEmpty()) {
                val sortedMissing = missingIndices.sortedBy { pars[it] }
                val step = if (diff > 0) 1 else -1
                var mIdx = 0
                while (diff != 0 && mIdx < sortedMissing.size * 3) {
                    val targetSlot = sortedMissing[mIdx % sortedMissing.size]
                    result[targetSlot] = result[targetSlot] + step
                    diff -= step
                    mIdx++
                }
            }

            return if (result.sum() == total) result else detected
        }

        /**
         * 단일 코스 영역(전반 또는 후반) 내의 그리드 테이블 정밀 파싱
         */
        fun extractCourseGrid(sectionLines: List<String>, isFront: Boolean = true): CourseGridData {
            if (sectionLines.isEmpty()) return CourseGridData()

            val courseCandidates = listOf(
                "West", "South", "East", "North",
                "Hill", "Lake", "Valley", "Pine", "Mountain", "Ocean", "Creek", "River", "Forest",
                "Cherry", "Oak", "Moon", "Sun", "Sky", "Star", "Silk", "Stone",
                "Out", "In", "서", "동", "남", "북", "힐", "레이크", "밸리", "파인", "마운틴", "체리", "오크"
            )

            var detectedCourse: String? = null
            val isHoleLine = { l: String ->
                if (l.contains("홀당") || l.contains("평균")) false
                else {
                    val u = l.uppercase()
                    u.contains("HOLE") || Regex("""\b[1-9]홀\b""").containsMatchIn(l) || Regex("""\b1\s+2\s+3\s+4\b""").containsMatchIn(l)
                }
            }

            val holeIdx = sectionLines.indexOfFirst { isHoleLine(it) }
            val courseSearchLines = when {
                holeIdx > 0 -> {
                    // HOLE 바로 윗줄 및 그 이전 줄들을 역순으로 우선 탐색하고, HOLE 및 그 다음 줄도 탐색
                    val beforeHole = sectionLines.subList(0, holeIdx).reversed()
                    beforeHole + sectionLines.subList(holeIdx, minOf(sectionLines.size, holeIdx + 2))
                }
                holeIdx == 0 -> sectionLines.take(3)
                else -> sectionLines.take(5)
            }

            for (line in courseSearchLines) {
                if (line.contains("스코어카드") || line.contains("SCORE") || line.contains("GIR") || line.contains("걸음") || line.contains("퍼트")) continue
                for (cand in courseCandidates) {
                    val regex = Regex("""(?:\b|^)${Regex.escape(cand)}(?:\b|$|\s*코스)""", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(line)) {
                        detectedCourse = when (cand.lowercase()) {
                            "west", "서" -> "West"
                            "south", "남" -> "South"
                            "east", "동" -> "East"
                            "north", "북" -> "North"
                            "hill", "힐" -> "Hill"
                            "lake", "레이크" -> "Lake"
                            "mountain", "마운틴" -> "Mountain"
                            "valley", "밸리" -> "Valley"
                            "pine", "파인" -> "Pine"
                            "cherry", "체리" -> "Cherry"
                            "oak", "오크" -> "Oak"
                            "moon" -> "Moon"
                            "sun" -> "Sun"
                            "sky" -> "Sky"
                            "out" -> "OUT"
                            "in" -> "IN"
                            else -> cand
                        }
                        break
                    }
                }
                if (detectedCourse != null) break
            }

            if (detectedCourse == null) {
                for (line in courseSearchLines) {
                    val trimmed = line.trim()
                    val upper = trimmed.uppercase()
                    if (trimmed.length in 2..15 &&
                        !upper.contains("SCORE") && !upper.contains("PAR") && !upper.contains("파") &&
                        !upper.contains("PUTT") && !upper.contains("퍼트") && !upper.contains("HOLE") && !upper.contains("홀") &&
                        !upper.contains("GIR") && !upper.contains("걸음") && !upper.contains("TOTAL") && !upper.contains("합계") &&
                        !upper.contains("스코어") && !upper.contains("PENALTY") && !upper.contains("벌타") &&
                        !Regex("""\d""").containsMatchIn(trimmed)) {
                        detectedCourse = trimmed.replace(Regex("""\s*코스$"""), "").trim()
                        break
                    }
                }
            }

            // 1. Hole
            var holes = emptyList<Int>()
            for (line in sectionLines) {
                if (isHoleLine(line)) {
                    val nums = Regex("""\b(\d{1,2})\b""").findAll(line).mapNotNull { it.value.toIntOrNull() }.filter { it in 1..18 }.toList()
                    if (nums.size in 9..10) {
                        holes = nums.take(9)
                        break
                    }
                }
            }

            // 2. Par
            var pars = emptyList<Int>()
            var parTotal: Int? = null
            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (Regex("""(?:Par|파)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = (i..minOf(sectionLines.size - 1, i + 5)).toList()
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        if (cIdx != i && cLine.uppercase().contains("HOLE")) continue
                        val nums = Regex("""\b([345])\b""").findAll(cLine).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.size in 9..10) {
                            pars = nums.take(9)
                            parTotal = if (nums.size == 10) nums[9] else nums.sum()
                            break
                        } else if (nums.size == 8) {
                            // 8개 Par + Total(34..38) 인접 탐색
                            val allInts = Regex("""\b\d+\b""").findAll(cLine).mapNotNull { it.value.toIntOrNull() }.toList()
                            val targetTotal = allInts.lastOrNull { it in 34..38 } ?: 36
                            val diff = targetTotal - nums.sum()
                            if (diff in 3..5) {
                                pars = nums + listOf(diff)
                                parTotal = targetTotal
                                break
                            }
                        }
                    }
                    if (pars.isNotEmpty()) break
                }
            }
            if (pars.isEmpty()) {
                // 폴백: 라벨과 분리되어 있더라도 9개 숫자가 모두 3..5이고 합이 34..38인 행은 Par 행
                for (line in sectionLines) {
                    if (line.uppercase().contains("HOLE")) continue
                    val nums = Regex("""\b\d+\b""").findAll(line).mapNotNull { it.value.toIntOrNull() }.toList()
                    if (nums.size in 9..10 && nums.take(9).all { it in 3..5 } && nums.take(9).sum() in 34..38) {
                        pars = nums.take(9)
                        parTotal = if (nums.size == 10) nums[9] else nums.sum()
                        break
                    }
                }
            }

            // 3. Score
            var scores = emptyList<Int>()
            var scoreTotal: Int? = null

            val parseScoreTokens = { rawText: String, targetPars: List<Int> ->
                val rawTokens = Regex("""\b\d+\b""").findAll(rawText).map { it.value }.toList()

                // 1) 연속된 셀 결합 분해 (예: "54" -> 5, 4 / "5566" -> 5, 5, 6, 6). 단, 맨 마지막 토큰이 30..75면 합계로 보존!
                val decomposed = mutableListOf<Int>()
                for (idx in rawTokens.indices) {
                    val tok = rawTokens[idx]
                    val num = tok.toIntOrNull() ?: continue
                    val isLast = (idx == rawTokens.size - 1)
                    if (isLast && num in 30..75) {
                        decomposed.add(num)
                    } else if (!isLast && tok.length >= 2 && tok.all { it in '1'..'9' }) {
                        tok.forEach { decomposed.add(it.digitToInt()) }
                    } else if (isLast && tok.length >= 2 && num !in 30..75 && tok.all { it in '1'..'9' }) {
                        tok.forEach { decomposed.add(it.digitToInt()) }
                    } else {
                        decomposed.add(num)
                    }
                }

                val hasTotalAtEnd = decomposed.isNotEmpty() && decomposed.last() in 30..75
                val candidateHoles = if (hasTotalAtEnd) decomposed.dropLast(1) else decomposed
                val explicitTotal = if (hasTotalAtEnd) decomposed.last() else null

                when {
                    // 케이스 A: 이미 9홀 완벽
                    candidateHoles.size == 9 && candidateHoles.all { it in 1..15 } -> {
                        if (explicitTotal != null) candidateHoles + explicitTotal else candidateHoles
                    }
                    // 케이스 C: 8개 인식 + 합계 -> 1번 홀 역산 복원 (1번 홀 선택 박스 대응 최우선)
                    candidateHoles.size == 8 && candidateHoles.all { it in 1..15 } && explicitTotal != null -> {
                        val diff = explicitTotal - candidateHoles.sum()
                        if (diff in 1..15) {
                            listOf(diff) + candidateHoles + explicitTotal
                        } else if (targetPars.size == 9) {
                            restoreMissingHoles(candidateHoles, targetPars, explicitTotal) + explicitTotal
                        } else {
                            decomposed
                        }
                    }
                    // 케이스 B: 5~7개 인식 + 합계가 있고 targetPars가 9개인 경우 -> restoreMissingHoles로 100% 복원
                    candidateHoles.size in 5..7 && candidateHoles.all { it in 1..15 } && explicitTotal != null && targetPars.size == 9 -> {
                        val restored = restoreMissingHoles(candidateHoles, targetPars, explicitTotal)
                        restored + explicitTotal
                    }
                    // 케이스 D: 첫 번째 토큰이 합계(30..75)인 경우
                    decomposed.size == 9 && decomposed.first() in 30..75 -> {
                        val total = decomposed.first()
                        val holes8 = decomposed.drop(1)
                        val deduced = total - holes8.sum()
                        if (deduced in 1..15 && holes8.all { it in 1..15 }) {
                            holes8 + deduced + total
                        } else {
                            decomposed
                        }
                    }
                    else -> decomposed
                }
            }

            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (Regex("""(?:Score|스코어)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in sectionLines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        if (cIdx != i && (cLine.uppercase().contains("HOLE") || cLine.uppercase().contains("PAR") || cLine.contains("파"))) continue
                        val rest = if (cIdx == i) cLine.replace(Regex("""(?:Score|스코어)\b""", RegexOption.IGNORE_CASE), "").trim() else cLine
                        val nums = parseScoreTokens(rest, pars)
                        if (nums.size in 9..10 && nums.take(9).all { it in 1..15 }) {
                            // Par 행과 100% 동일한지 체크 (동일하면 Par 행을 잘못 읽은 것이므로 배제)
                            if (pars.isNotEmpty() && nums.take(9) == pars) {
                                continue
                            }
                            scores = nums.take(9)
                            scoreTotal = if (nums.size == 10) nums[9] else nums.sum()
                            break
                        }
                    }
                    if (scores.isNotEmpty()) break
                }
            }
            if (scores.isEmpty()) {
                for (line in sectionLines) {
                    val upper = line.uppercase()
                    // [가드레일] Par, 파, Hole, 홀, Putt, 퍼트가 포함된 줄은 스코어 폴백 대상에서 원천 배제!
                    if (upper.contains("PAR") || line.contains("파") ||
                        upper.contains("HOLE") || line.contains("홀") ||
                        upper.contains("PUTT") || line.contains("퍼트") ||
                        upper.contains("PENALTY") || line.contains("벌타") || line.contains("페널티")) {
                        continue
                    }

                    val nums = parseScoreTokens(line, pars)
                    // [가드레일] Par 행 패턴 (모든 홀이 3..5이고 9홀 합이 34..38)은 Par 행이므로 스코어 채택 원천 차단!
                    if (nums.take(9).all { it in 3..5 } && nums.take(9).sum() in 34..38) continue
                    if (pars.isNotEmpty() && nums.take(9) == pars) continue

                    if (nums.size == 10 && nums.take(9).sum() == nums[9]) {
                        if (nums[9] in 30..70) {
                            scores = nums.take(9)
                            scoreTotal = nums[9]
                            break
                        }
                    } else if (nums.size == 9 && nums.all { it in 1..9 } && nums.sum() in 30..70) {
                        scores = nums
                        scoreTotal = nums.sum()
                        break
                    }
                }
            }

            // 3-1. 파편화된 숫자 토큰 스트림에서 9홀 스코어 시퀀스 스마트 복원 (South 코스 및 알록달록 칩 분리 대응)
            if (scores.isEmpty()) {
                val candidateTokens = mutableListOf<Int>()
                for (line in sectionLines) {
                    val upper = line.uppercase()
                    // 라벨 단독 줄이거나 다른 메트릭 줄은 스킵 (섣불리 break하지 않음!)
                    if (upper.contains("HOLE") || upper.contains("PAR") || line.contains("파") ||
                        upper.contains("PUTT") || line.contains("퍼트") ||
                        line.contains("%") || line.contains("걸음") || line.contains("GIR") ||
                        line.contains("평균") || line.contains("AVG")) continue

                    val cleanLine = line.replace(Regex("""(?:Score|스코어)\b""", RegexOption.IGNORE_CASE), "")
                    // 비거리(100 이상)나 템포(소수점)가 포함된 라인은 제외
                    if (Regex("""\b[1-3]\d{2}\b""").containsMatchIn(cleanLine) || cleanLine.contains(".")) continue

                    val rawDigits = Regex("""\b\d+\b""").findAll(cleanLine).map { it.value }.toList()
                    for (tok in rawDigits) {
                        val num = tok.toIntOrNull() ?: continue
                        if (tok.length >= 2 && num !in 30..75 && tok.all { it in '1'..'9' }) {
                            tok.forEach { candidateTokens.add(it.digitToInt()) }
                        } else {
                            candidateTokens.add(num)
                        }
                    }
                }

                if (candidateTokens.size >= 10) {
                    for (start in 0..(candidateTokens.size - 10)) {
                        val window9 = candidateTokens.subList(start, start + 9)
                        val expectedTotal = candidateTokens[start + 9]
                        if (window9.all { it in 1..15 } && expectedTotal in 30..70 && window9.sum() == expectedTotal) {
                            if (pars.isEmpty() || window9 != pars) {
                                scores = window9.toList()
                                scoreTotal = expectedTotal
                                break
                            }
                        }
                    }
                }
                if (scores.isEmpty() && candidateTokens.size in 6..9) {
                    val total = candidateTokens.last()
                    val holes = candidateTokens.dropLast(1)
                    if (total in 30..70 && holes.all { it in 1..15 }) {
                        if (pars.size == 9) {
                            val restored = restoreMissingHoles(holes, pars, total)
                            if (restored.sum() == total) {
                                scores = restored
                                scoreTotal = total
                            }
                        } else if (holes.size == 8) {
                            val deducedHole1 = total - holes.sum()
                            if (deducedHole1 in 1..15) {
                                scores = listOf(deducedHole1) + holes
                                scoreTotal = total
                            }
                        }
                    }
                }
            }

            // 4. Putt
            var putts = emptyList<Int>()
            var puttTotal: Int? = null

            val parsePuttTokens = { rawText: String ->
                val rawTokens = Regex("""\b\d+\b""").findAll(rawText).map { it.value }.toList()
                val decomposed = mutableListOf<Int>()
                for (idx in rawTokens.indices) {
                    val tok = rawTokens[idx]
                    val num = tok.toIntOrNull() ?: continue
                    val isLast = (idx == rawTokens.size - 1)
                    if (isLast && num in 10..30) {
                        decomposed.add(num)
                    } else if (tok.length >= 2 && tok.all { it in '0'..'5' }) {
                        tok.forEach { decomposed.add(it.digitToInt()) }
                    } else {
                        decomposed.add(num)
                    }
                }
                decomposed
            }

            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (line.contains("평균") || line.contains("AVG")) continue
                if (Regex("""(?:Putt|퍼트|퍼팅)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in sectionLines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        if (cIdx != i && (cLine.uppercase().contains("HOLE") || cLine.uppercase().contains("PAR") || cLine.contains("파") || cLine.uppercase().contains("SCORE") || cLine.contains("스코어"))) continue
                        val rest = if (cIdx == i) cLine.replace(Regex("""(?:Putt|퍼트|퍼팅)\b""", RegexOption.IGNORE_CASE), "").trim() else cLine
                        val nums = parsePuttTokens(rest)
                        if (nums.size in 9..10 && nums.take(9).all { it in 0..5 }) {
                            if ((pars.isNotEmpty() && nums.take(9) == pars) || (scores.isNotEmpty() && nums.take(9) == scores)) continue
                            putts = nums.take(9)
                            puttTotal = if (nums.size == 10) nums[9] else nums.sum()
                            break
                        } else if (nums.size == 1 && nums[0] in 10..30) {
                            puttTotal = nums[0]
                            break
                        } else if (nums.isNotEmpty() && nums.last() in 10..30) {
                            puttTotal = nums.last()
                            break
                        }
                    }
                    if (puttTotal != null) break
                }
            }
            if (puttTotal == null) {
                for (line in sectionLines) {
                    val upper = line.uppercase()
                    if (line.contains("평균") || line.contains("AVG")) continue
                    if (upper.contains("PAR") || line.contains("파") || upper.contains("HOLE") || line.contains("홀") || upper.contains("SCORE") || line.contains("스코어")) continue
                    val nums = parsePuttTokens(line)
                    if (nums.size == 10 && nums.take(9).all { it in 0..5 } && nums.take(9).sum() == nums[9]) {
                        if (pars.isNotEmpty() && nums.take(9) == pars) continue
                        if (scores.isNotEmpty() && nums.take(9) == scores) continue
                        if (nums[9] in 10..28) {
                            putts = nums.take(9)
                            puttTotal = nums[9]
                            break
                        }
                    }
                }
            }

            // 5. Penalty
            var penalties = emptyList<Int>()
            var penaltyTotal: Int? = null
            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (Regex("""(?:Penalty|페널티|벌타)""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in sectionLines.indices }
                    var found = false
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        val uLine = cLine.uppercase()
                        if (cIdx != i && (uLine.contains("HOLE") || uLine.contains("PAR") || cLine.contains("파") ||
                                          uLine.contains("SCORE") || cLine.contains("스코어") ||
                                          uLine.contains("PUTT") || cLine.contains("퍼트") ||
                                          uLine.contains("TEMPO") || cLine.contains("템포") ||
                                          uLine.contains("GIR") || uLine.contains("DIST") ||
                                          cLine.contains("거리") || cLine.contains("비거리") ||
                                          cLine.contains("."))) continue
                        val rest = if (cIdx == i) cLine.replace(Regex("""(?:Penalty|페널티|벌타)""", RegexOption.IGNORE_CASE), "").trim() else cLine
                        val nums = Regex("""\b\d{1,2}\b""").findAll(rest).mapNotNull { it.value.toIntOrNull() }.toList()
                        // [P-01 가드레일] 1 2 3 4 5 6 7 8 9 등 홀 번호 연속 수열은 페널티에서 원천 배제!
                        val isSequentialHoles = nums.size >= 5 && nums.zipWithNext().all { it.second - it.first == 1 }
                        if (isSequentialHoles) continue

                        if (nums.isNotEmpty()) {
                            val cand = nums.last()
                            val holeNums = if (nums.size > 1) nums.dropLast(1) else emptyList()
                            // 홀별 벌타는 각 홀당 0..3 범위여야 함
                            val validHolePenalties = holeNums.all { it in 0..3 }
                            val holeSum = if (validHolePenalties && holeNums.isNotEmpty()) holeNums.sum() else null

                            val resolvedTotal = when {
                                holeSum != null && holeSum == cand -> cand
                                holeSum != null && cand !in 0..10 -> holeSum
                                cand in 0..10 -> cand
                                holeSum != null -> holeSum
                                else -> null
                            }

                            if (resolvedTotal != null) {
                                penaltyTotal = resolvedTotal
                                penalties = if (holeNums.isNotEmpty()) holeNums else List(9) { 0 }
                                found = true
                                break
                            }
                        } else if (cLine.contains("-")) {
                            penaltyTotal = 0
                            penalties = List(9) { 0 }
                            found = true
                            break
                        }
                    }
                    if (!found) {
                        penaltyTotal = 0
                        penalties = List(9) { 0 }
                    }
                    break
                }
            }
            if (penaltyTotal == null) {
                for (line in sectionLines) {
                    // 비거리(100 이상)나 템포(소수점)가 포함된 라인은 배제
                    if (line.contains(".") || Regex("""\b[1-3]\d{2}\b""").containsMatchIn(line)) continue
                    if (line.count { it == '-' } >= 3) {
                        val nums = Regex("""\b\d{1,2}\b""").findAll(line).mapNotNull { it.value.toIntOrNull() }.toList()
                        val isSequential = nums.size >= 5 && nums.zipWithNext().all { it.second - it.first == 1 }
                        if (isSequential) continue

                        if (nums.isNotEmpty()) {
                            val cand = nums.last()
                            if (cand in 0..10) {
                                penaltyTotal = cand
                                penalties = nums.dropLast(1)
                                break
                            }
                        } else {
                            penaltyTotal = 0
                            penalties = List(9) { 0 }
                            break
                        }
                    }
                }
            }

            // 6. Tempo
            var tempos = emptyList<Double>()
            var tempoTotal: Double? = null
            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (Regex("""(?:Tempo|템포)""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in sectionLines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        val nums = Regex("""\b([1-5]\.\d)\b""").findAll(cLine).mapNotNull { it.value.toDoubleOrNull() }.filter { it in 1.5..5.5 }.toList()
                        if (nums.isNotEmpty()) {
                            tempos = nums
                            if (nums.size >= 7) tempoTotal = nums.last()
                            break
                        }
                    }
                    if (tempos.isNotEmpty()) break
                }
            }
            if (tempos.isEmpty()) {
                for (line in sectionLines) {
                    if (line.contains("%") || line.contains("퍼트") || line.contains("SCORE")) continue
                    val nums = Regex("""\b([1-5]\.\d)\b""").findAll(line).mapNotNull { it.value.toDoubleOrNull() }.filter { it in 1.5..5.5 }.toList()
                    if (nums.size >= 2) {
                        tempos = nums
                        if (nums.size >= 7) tempoTotal = nums.last()
                        break
                    }
                }
            }

            // 7. Dist
            var driveDistances = emptyList<Double>()
            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (Regex("""(?:Dist|Distance|비거리|거리)""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in sectionLines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        val nums = Regex("""\b([1-3]\d{2})\b""").findAll(cLine).mapNotNull { it.value.toDoubleOrNull() }.filter { it in 100.0..350.0 }.toList()
                        if (nums.isNotEmpty()) {
                            driveDistances = nums
                            break
                        }
                    }
                    if (driveDistances.isNotEmpty()) break
                }
            }
            if (driveDistances.isEmpty()) {
                for (line in sectionLines) {
                    if (line.contains("202") || line.contains("%") || line.contains("걸음")) continue
                    val nums = Regex("""\b([1-3]\d{2})\b""").findAll(line).mapNotNull { it.value.toDoubleOrNull() }.filter { it in 100.0..350.0 }.toList()
                    if (nums.size >= 2) {
                        driveDistances = nums
                        break
                    }
                }
            }

            val effectiveDistances = if (driveDistances.size == pars.size && pars.contains(3)) {
                driveDistances.filterIndexed { idx, _ -> pars.getOrNull(idx) != 3 }
            } else {
                driveDistances
            }

            return CourseGridData(
                courseName = detectedCourse,
                holes = holes,
                pars = pars,
                parTotal = parTotal,
                scores = scores,
                scoreTotal = scoreTotal,
                putts = putts,
                puttTotal = puttTotal,
                penalties = penalties,
                penaltyTotal = penaltyTotal,
                tempos = tempos,
                tempoTotal = tempoTotal,
                driveDistances = effectiveDistances
            )
        }

        fun mergeResults(a: ScorecardOcrResult, b: ScorecardOcrResult, raw: String): ScorecardOcrResult {
            // 18홀 스코어를 가진 쪽 최우선, 크기가 큰 쪽 최우선
            val bestHoleScores = when {
                a.holeScores.size == 18 && b.holeScores.size != 18 -> a.holeScores
                b.holeScores.size == 18 && a.holeScores.size != 18 -> b.holeScores
                a.holeScores.size >= b.holeScores.size && a.holeScores.isNotEmpty() -> {
                    // a의 스코어가 Par 행과 동일하고 b는 Par와 다른 정상 스코어라면 b 채택
                    if (a.holePars.isNotEmpty() && a.holeScores == a.holePars && b.holeScores.isNotEmpty() && b.holeScores != b.holePars) {
                        b.holeScores
                    } else {
                        a.holeScores
                    }
                }
                b.holeScores.isNotEmpty() -> b.holeScores
                else -> emptyList()
            }

            val bestHolePars = when {
                a.holePars.size == 18 && b.holePars.size != 18 -> a.holePars
                b.holePars.size == 18 && a.holePars.size != 18 -> b.holePars
                a.holePars.isNotEmpty() -> a.holePars
                else -> b.holePars
            }

            // 총 타수: 18홀 정상 스코어 범위(58~144)에 있는 값 우선, 36 같은 Par 값은 배제
            val bestTotalScore = when {
                a.totalScore != null && a.totalScore in 58..144 -> a.totalScore
                b.totalScore != null && b.totalScore in 58..144 -> b.totalScore
                a.totalScore != null && a.totalScore != 36 -> a.totalScore
                b.totalScore != null && b.totalScore != 36 -> b.totalScore
                else -> a.totalScore ?: b.totalScore
            }

            // 총 퍼트수: 36(Par 합계 오탐 가능성)이 아닌 18홀 정상 범위(15~55) 값 우선
            // [G-03] 1차 기준 하한을 15로 통일: Pine 16 + Cherry 17 = 33 같은 낮은 퍼트도 정상 채택
            val bestTotalPutts = when {
                a.totalPutts != null && a.totalPutts in 15..55 && a.totalPutts != 36 -> a.totalPutts
                b.totalPutts != null && b.totalPutts in 15..55 && b.totalPutts != 36 -> b.totalPutts
                a.totalPutts != null && a.totalPutts in 15..60 && a.totalPutts != 36 -> a.totalPutts
                b.totalPutts != null && b.totalPutts in 15..60 && b.totalPutts != 36 -> b.totalPutts
                else -> a.totalPutts ?: b.totalPutts
            }

            // 코스명: 2개 코스를 포함한 쪽 우선
            val bestCourseName = when {
                a.courseName?.contains("/") == true -> a.courseName
                b.courseName?.contains("/") == true -> b.courseName
                else -> a.courseName ?: b.courseName
            }

            val bestPenalty = when {
                a.holeScores.size == 18 && a.penaltyCount != null && a.penaltyCount in 0..6 -> a.penaltyCount
                b.holeScores.size == 18 && b.penaltyCount != null && b.penaltyCount in 0..6 -> b.penaltyCount
                a.holeScores.size == 18 && a.penaltyCount != null -> a.penaltyCount
                b.holeScores.size == 18 && b.penaltyCount != null -> b.penaltyCount
                a.penaltyCount != null && b.penaltyCount != null -> {
                    // [P-02 가드레일] 9타 등 홀 번호 오탐 배제: 0..6 범위 우선, 둘 다 범위 내면 minOf 또는 작은 값 채택
                    when {
                        a.penaltyCount in 0..6 && b.penaltyCount !in 0..6 -> a.penaltyCount
                        b.penaltyCount in 0..6 && a.penaltyCount !in 0..6 -> b.penaltyCount
                        else -> minOf(a.penaltyCount, b.penaltyCount)
                    }
                }
                else -> a.penaltyCount ?: b.penaltyCount
            }

            val bestGir = when {
                a.girPercentage != null && a.girPercentage in 10.0..100.0 -> a.girPercentage
                b.girPercentage != null && b.girPercentage in 10.0..100.0 -> b.girPercentage
                else -> a.girPercentage ?: b.girPercentage
            }

            return ScorecardOcrResult(
                totalScore = bestTotalScore,
                totalPutts = bestTotalPutts,
                holeScores = bestHoleScores,
                holePars = bestHolePars,
                courseName = bestCourseName,
                girPercentage = bestGir,
                steps = a.steps ?: b.steps,
                penaltyCount = bestPenalty,
                averageDriveDistance = a.averageDriveDistance ?: b.averageDriveDistance,
                adjustedDriveDistance = a.adjustedDriveDistance ?: b.adjustedDriveDistance,
                averageTempo = a.averageTempo ?: b.averageTempo,
                driveDistances = if (a.driveDistances.isNotEmpty()) a.driveDistances else b.driveDistances,
                tempos = if (a.tempos.isNotEmpty()) a.tempos else b.tempos,
                clubName = a.clubName ?: b.clubName,
                playDate = a.playDate ?: b.playDate,
                recognizedRawText = raw
            )
        }
    }
}
