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
import java.util.regex.Pattern

data class ScorecardOcrResult(
    val totalScore: Int?,
    val totalPutts: Int?,
    val holeScores: List<Int>,
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
    val recognizedRawText: String
)

/**
 * 스코어카드 OCR 분석기.
 * [C-01] 실제 스마트폰 카메라 고해상도(12~50MP) 원본 사진 직접 전달로 인한
 * OOM(OutOfMemoryError) 및 네이티브 SIGSEGV 크래시 방지를 위해
 * GolfLockerSlipOcrAnalyzer와 동일하게 최대 1024px 다운샘플링 + RGB_565를 적용합니다.
 * [L-04] ML Kit recognizer 리소스 누수 방지를 위해 close() 메서드를 제공합니다.
 */
class ScorecardOcrAnalyzer(private val context: Context) {

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
            context.contentResolver.openInputStream(uri)?.use { stream ->
                BitmapFactory.decodeStream(stream, null, decodeOptions)
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

        // 중앙 Y 좌표 기준 행(Row) 클러스터링
        val medianHeight = elements.map { it.boundingBox!!.height() }.sorted().let { it[it.size / 2] }.coerceAtLeast(10)
        val rowTolerance = (medianHeight * 0.65).toInt().coerceIn(8, 32)

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

        // 각 행을 위에서 아래(Y 정렬), 각 행 내부를 왼쪽에서 오른쪽(X 정렬)하여 텍스트 결합
        val sortedRows = rows.sortedBy { row -> row.map { it.boundingBox!!.top }.average() }
        return sortedRows.joinToString("\n") { row ->
            row.sortedBy { it.boundingBox!!.left }.joinToString(" ") { it.text }
        }
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

            val parsedSpatial = parse(spatialRaw)
            val parsedRaw = parse(raw)
            mergeResults(parsedSpatial, parsedRaw, raw)
        } catch (t: Throwable) {
            ScorecardOcrResult(null, null, emptyList(), null, null, null, null, null, null, null, emptyList(), emptyList(), null, "OCR 분석 스킵: ${t.message}")
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

            // 1-0. 골프장명 탐지 (예: "필로스 GC", "스카이밸리 CC")
            val clubRegex = Regex("""([가-힣A-Za-z0-9\s]{2,15}\s*(?:CC|GC|C\.C|G\.C|골프클럽|컨트리클럽|클럽))""")
            for (line in lines.take(5)) {
                val m = clubRegex.find(line)
                if (m != null) {
                    detectedClubName = m.groupValues[1].trim()
                    break
                }
            }

            // 1-1. 대형 스코어 지문 탐색: "86(+14)", "86 (+14)", "91(+19)" 등 (괄호 안 오버/언더파 차이 결합형)
            val scoreWithDiffRegex = Regex("""\b(\d{2,3})\s*\([+-]?\s*\d+\)""")
            for (line in lines) {
                val m = scoreWithDiffRegex.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].toIntOrNull()
                    if (cand != null && cand in 54..144) {
                        summaryScore = cand
                        break
                    }
                }
            }

            // 1-2. 한 줄에 SCORE와 숫자가 결합된 형태 (예: "SCORE : 86", "86 SCORE")
            if (summaryScore == null) {
                val scoreWithLabelRegex = Regex("""(?:SCORE|스코어|타수)\s*[:：]?\s*(\d{2,3})|(\d{2,3})\s*(?:\([+-]?\d+\)|[+-]\d+)?\s*(?:SCORE|스코어)""", RegexOption.IGNORE_CASE)
                for (line in lines) {
                    val m = scoreWithLabelRegex.find(line)
                    if (m != null) {
                        val s = (m.groups[1]?.value ?: m.groups[2]?.value)?.toIntOrNull()
                        if (s != null && s in 54..144) {
                            summaryScore = s
                            break
                        }
                    }
                }
            }

            // 1-3. 인접 줄 SCORE 라벨 탐색
            if (summaryScore == null) {
                val standaloneNumRegex = Regex("""^(\d{2,3})$""")
                for (i in lines.indices) {
                    val m = standaloneNumRegex.find(lines[i])
                    if (m != null) {
                        val cand = m.groupValues[1].toIntOrNull()
                        if (cand != null && cand in 54..144) {
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
                }
            }

            // 1-4. GIR(Green In Regulation) 추출 (예: "55.6%", "55.6% GIR", "GIR 55.6%")
            val girRegex = Regex("""(\d{1,2}(?:\.\d+)?)\s*%\s*(?:GIR)?""", RegexOption.IGNORE_CASE)
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

            // 1-5. 홀당 평균 퍼트 수 (예: "2.2", "홀당 평균 퍼트 수")
            val avgPuttRegex = Regex("""(\d(?:\.\d+)?)\s*(?:홀당\s*평균\s*퍼트\s*수|평균\s*퍼트|AVG\s*PUTT)""", RegexOption.IGNORE_CASE)
            for (i in lines.indices) {
                val m = avgPuttRegex.find(lines[i])
                if (m != null) {
                    summaryAvgPutts = m.groupValues[1].toDoubleOrNull()
                    break
                }
                if (lines[i].contains("홀당 평균 퍼트 수") || lines[i].contains("평균 퍼트") || lines[i].contains("평균 퍼팅")) {
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

            // 2. 전반코스(Front 9) / 후반코스(Back 9) 그리드 분할 탐색
            val backCourseKeywords = listOf("후반코스", "후반", "SOUTH", "남", "IN", "LAKE", "레이크", "MOUNTAIN", "마운틴", "VALLEY", "밸리")
            var backSplitIdx = -1

            for (i in lines.indices) {
                if (i < 3) continue
                val l = lines[i].uppercase()
                if ((l.contains("HOLE") && (l.contains("10") || l.contains("11"))) || Regex("""\b10\s+11\s+12\b""").containsMatchIn(l)) {
                    val lookback = if (i > 0 && backCourseKeywords.any { lines[i-1].uppercase().contains(it) }) i - 1 else i
                    backSplitIdx = lookback
                    break
                }
            }

            if (backSplitIdx == -1) {
                for (i in lines.indices) {
                    if (i < 5) continue
                    val l = lines[i].uppercase()
                    if (backCourseKeywords.any { l == it || l.startsWith("$it ") || l.endsWith(" $it") || l.contains("$it 코스") }) {
                        val hasEarlierCourse = lines.take(i).any { prev ->
                            listOf("WEST", "서", "OUT", "HILL", "힐", "동", "EAST", "북", "NORTH", "전반").any { prev.uppercase().contains(it) } || prev.contains("HOLE")
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

            // 5. 18홀 홀별 스코어 통합
            val allHoleScores = mutableListOf<Int>()
            allHoleScores.addAll(frontGrid.scores)
            allHoleScores.addAll(backGrid.scores)

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

            // 12. 최종 확정
            val finalTotalScore: Int? = when {
                summaryScore != null -> summaryScore
                subTotals.size == 2 -> subTotals.sum()
                allHoleScores.size == 18 -> allHoleScores.sum()
                subTotals.isNotEmpty() -> subTotals.sum()
                allHoleScores.size == 9 -> allHoleScores.sum()
                fallbackTotalScore != null -> fallbackTotalScore
                else -> null
            }

            val finalTotalPutts: Int? = when {
                puttSubTotals.size == 2 -> puttSubTotals.sum()
                puttSubTotals.size == 1 && puttSubTotals[0] in 15..60 -> puttSubTotals[0]
                summaryAvgPutts != null -> Math.round(summaryAvgPutts * (if (allHoleScores.size == 9) 9 else 18)).toInt()
                fallbackTotalPutts != null -> fallbackTotalPutts
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

            return ScorecardOcrResult(
                totalScore = finalTotalScore,
                totalPutts = finalTotalPutts,
                holeScores = allHoleScores,
                courseName = finalCourseName,
                girPercentage = summaryGir,
                steps = summarySteps,
                penaltyCount = finalPenalty,
                averageDriveDistance = avgDrive,
                adjustedDriveDistance = adjustedAvgDrive,
                averageTempo = avgTempo,
                driveDistances = allDriveDistances,
                tempos = allTempos,
                clubName = detectedClubName,
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
         * 단일 코스 영역(전반 또는 후반) 내의 그리드 테이블 정밀 파싱
         */
        fun extractCourseGrid(sectionLines: List<String>, isFront: Boolean): CourseGridData {
            if (sectionLines.isEmpty()) return CourseGridData()

            val courseCandidates = listOf(
                "West", "South", "East", "North",
                "Hill", "Lake", "Valley", "Pine", "Mountain", "Ocean", "Creek", "River", "Forest",
                "Out", "In", "서", "동", "남", "북", "힐", "레이크", "밸리", "파인", "마운틴"
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
                            "out" -> "OUT"
                            "in" -> "IN"
                            else -> cand
                        }
                        break
                    }
                }
                if (detectedCourse != null) break
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
                    val candidateIndices = listOf(i, i + 1).filter { it in sectionLines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        if (cIdx != i && cLine.uppercase().contains("HOLE")) continue
                        val nums = Regex("""\b([345])\b""").findAll(cLine).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.size in 9..10) {
                            pars = nums.take(9)
                            parTotal = if (nums.size == 10) nums[9] else nums.sum()
                            break
                        }
                    }
                    if (pars.isNotEmpty()) break
                }
            }

            // 3. Score
            var scores = emptyList<Int>()
            var scoreTotal: Int? = null
            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (Regex("""(?:Score|스코어)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in sectionLines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        if (cIdx != i && (cLine.uppercase().contains("HOLE") || cLine.uppercase().contains("PAR"))) continue
                        val rest = if (cIdx == i) cLine.replace(Regex("""(?:Score|스코어)\b""", RegexOption.IGNORE_CASE), "").trim() else cLine
                        val nums = Regex("""\b([1-9]|1[0-5])\b""").findAll(rest).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.size in 9..10) {
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
                    val nums = Regex("""\b([1-9]|1[0-5])\b""").findAll(line).mapNotNull { it.value.toIntOrNull() }.toList()
                    if (nums.size == 10 && nums.take(9).sum() == nums[9]) {
                        scores = nums.take(9)
                        scoreTotal = nums[9]
                        break
                    }
                }
            }

            // 4. Putt
            var putts = emptyList<Int>()
            var puttTotal: Int? = null
            for (i in sectionLines.indices) {
                val line = sectionLines[i]
                if (line.contains("평균") || line.contains("AVG")) continue
                if (Regex("""(?:Putt|퍼트|퍼팅)\b""", RegexOption.IGNORE_CASE).containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in sectionLines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        if (cIdx != i && (cLine.uppercase().contains("HOLE") || cLine.uppercase().contains("PAR") || cLine.uppercase().contains("SCORE"))) continue
                        val rest = if (cIdx == i) cLine.replace(Regex("""(?:Putt|퍼트|퍼팅)\b""", RegexOption.IGNORE_CASE), "").trim() else cLine
                        val nums = Regex("""\b\d{1,2}\b""").findAll(rest).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.size in 9..10 && nums.take(9).all { it in 0..5 }) {
                            putts = nums.take(9)
                            puttTotal = if (nums.size == 10) nums[9] else nums.sum()
                            break
                        } else if (nums.size == 1 && nums[0] in 10..40) {
                            puttTotal = nums[0]
                            break
                        }
                    }
                    if (puttTotal != null) break
                }
            }
            if (puttTotal == null) {
                for (line in sectionLines) {
                    if (line.contains("평균") || line.contains("AVG")) continue
                    val nums = Regex("""\b\d{1,2}\b""").findAll(line).mapNotNull { it.value.toIntOrNull() }.toList()
                    if (nums.size == 10 && nums.take(9).all { it in 0..5 } && nums.take(9).sum() == nums[9]) {
                        putts = nums.take(9)
                        puttTotal = nums[9]
                        break
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
                    for (cIdx in candidateIndices) {
                        val cLine = sectionLines[cIdx]
                        if (cIdx != i && (cLine.uppercase().contains("HOLE") || cLine.uppercase().contains("PAR") || cLine.uppercase().contains("SCORE") || cLine.uppercase().contains("PUTT") || cLine.contains("퍼트"))) continue
                        val rest = if (cIdx == i) cLine.replace(Regex("""(?:Penalty|페널티|벌타)""", RegexOption.IGNORE_CASE), "").trim() else cLine
                        val nums = Regex("""\b\d{1,2}\b""").findAll(rest).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.isNotEmpty()) {
                            penaltyTotal = nums.last()
                            penalties = if (nums.size > 1) nums.dropLast(1) else emptyList()
                            break
                        } else if (cLine.contains("-")) {
                            penaltyTotal = 0
                            penalties = List(9) { 0 }
                            break
                        }
                    }
                    if (penaltyTotal != null) break
                }
            }
            if (penaltyTotal == null) {
                for (line in sectionLines) {
                    if (line.count { it == '-' } >= 3) {
                        val nums = Regex("""\b\d{1,2}\b""").findAll(line).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.isNotEmpty()) {
                            penaltyTotal = nums.last()
                            penalties = nums.dropLast(1)
                            break
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
            return ScorecardOcrResult(
                totalScore = a.totalScore ?: b.totalScore,
                totalPutts = a.totalPutts ?: b.totalPutts,
                holeScores = if (a.holeScores.isNotEmpty()) a.holeScores else b.holeScores,
                courseName = a.courseName ?: b.courseName,
                girPercentage = a.girPercentage ?: b.girPercentage,
                steps = a.steps ?: b.steps,
                penaltyCount = a.penaltyCount ?: b.penaltyCount,
                averageDriveDistance = a.averageDriveDistance ?: b.averageDriveDistance,
                adjustedDriveDistance = a.adjustedDriveDistance ?: b.adjustedDriveDistance,
                averageTempo = a.averageTempo ?: b.averageTempo,
                driveDistances = if (a.driveDistances.isNotEmpty()) a.driveDistances else b.driveDistances,
                tempos = if (a.tempos.isNotEmpty()) a.tempos else b.tempos,
                clubName = a.clubName ?: b.clubName,
                recognizedRawText = raw
            )
        }
    }
}
