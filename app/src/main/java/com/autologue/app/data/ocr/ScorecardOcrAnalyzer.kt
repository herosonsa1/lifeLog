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

            parse(raw)
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

            // 2. 코스명 탐색 (West, South, East, North, Hill, Lake, Mountain, Out, In 등 완벽 지원)
            val detectedCourses = mutableListOf<String>()
            val courseCandidates = listOf(
                "West", "South", "East", "North",
                "Hill", "Lake", "Valley", "Pine", "Mountain", "Ocean", "Creek", "River", "Forest",
                "Out", "In", "서", "동", "남", "북", "힐", "레이크", "밸리", "파인", "마운틴"
            )
            for (line in lines) {
                for (cand in courseCandidates) {
                    val regex = Regex("""(?:\b|^)${Regex.escape(cand)}(?:\b|$|\s*코스)""", RegexOption.IGNORE_CASE)
                    if (regex.containsMatchIn(line)) {
                        val stdName = when (cand.lowercase()) {
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
                        if (!detectedCourses.contains(stdName)) {
                            detectedCourses.add(stdName)
                        }
                    }
                }
            }

            // 3. 전반 / 후반 테이블 행(Score, Putt, Penalty, Dist, Tempo) 정밀 탐색
            val allHoleScores = mutableListOf<Int>()
            val subTotals = mutableListOf<Int>()
            val puttSubTotals = mutableListOf<Int>()
            val penaltySubTotals = mutableListOf<Int>()
            val allDriveDistances = mutableListOf<Double>()
            val allTempos = mutableListOf<Double>()
            val parList = mutableListOf<Int>() // 파3 제외 비거리 산출을 위한 각 홀별 Par 목록

            // 3-0. Par 라인 추출 (예: "Par 4 3 5 4 3 4 5 4 4 36")
            val parRowRegex = Regex("""(?:Par|파)\b""", RegexOption.IGNORE_CASE)
            for (i in lines.indices) {
                val line = lines[i]
                if (parRowRegex.containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1).filter { it in lines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = lines[cIdx]
                        val nums = Regex("""\b([345])\b""").findAll(cLine).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.size in 9..10) {
                            parList.addAll(nums.take(9))
                            break
                        }
                    }
                }
            }

            // 3-1. Score 라인 추출 ("Score 5 4 7 3 4 5 6 4 4 42" 또는 줄바꿈 분리)
            val scoreRowRegex = Regex("""(?:Score|스코어)\b""", RegexOption.IGNORE_CASE)
            val processedScoreLines = mutableSetOf<Int>()

            for (i in lines.indices) {
                val line = lines[i]
                if (scoreRowRegex.containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in lines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = lines[cIdx]
                        val rest = if (cIdx == i) cLine.replace(scoreRowRegex, "").trim() else cLine
                        val nums = Regex("""\b([1-9]|1[0-5])\b""").findAll(rest)
                            .mapNotNull { it.value.toIntOrNull() }
                            .toList()
                        if (nums.size in 9..10 || nums.size >= 18) {
                            if (nums.size == 10) {
                                allHoleScores.addAll(nums.take(9))
                                subTotals.add(nums[9])
                            } else if (nums.size == 9) {
                                allHoleScores.addAll(nums)
                            } else if (nums.size >= 18) {
                                allHoleScores.clear()
                                allHoleScores.addAll(nums.take(18))
                                if (nums.size >= 19) subTotals.add(nums[18])
                            }
                            processedScoreLines.add(cIdx)
                            break
                        }
                    }
                }
            }

            // 3-2. Putt 라인 추출 ("Putt 2 2 1 2 1 3 2 2 2 17" 또는 줄바꿈 분리)
            val puttRowRegex = Regex("""(?:Putt|퍼트|퍼팅)\b""", RegexOption.IGNORE_CASE)
            val processedPuttLines = mutableSetOf<Int>()

            for (i in lines.indices) {
                val line = lines[i]
                if (puttRowRegex.containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in lines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = lines[cIdx]
                        val rest = if (cIdx == i) cLine.replace(puttRowRegex, "").trim() else cLine
                        val nums = Regex("""\b\d{1,2}\b""").findAll(rest)
                            .mapNotNull { it.value.toIntOrNull() }
                            .toList()
                        if (nums.size in 9..10 || nums.size == 1) {
                            if (nums.size == 10) {
                                puttSubTotals.add(nums[9])
                            } else if (nums.size == 1 && nums[0] in 10..60) {
                                puttSubTotals.add(nums[0])
                            }
                            processedPuttLines.add(cIdx)
                            break
                        }
                    }
                }
            }

            // 3-3. Penalty 라인 추출 ("Penalty - - 1 - 1 - - - - 2" 또는 줄바꿈 분리)
            val penaltyRowRegex = Regex("""(?:Penalty|페널티|벌타)""", RegexOption.IGNORE_CASE)
            for (i in lines.indices) {
                val line = lines[i]
                if (penaltyRowRegex.containsMatchIn(line)) {
                    val rest = line.replace(penaltyRowRegex, "").trim()
                    val targetLine = if (rest.contains("-") || Regex("""\d""").containsMatchIn(rest)) {
                        rest
                    } else if (i + 1 < lines.size && (lines[i+1].contains("-") || Regex("""\d""").containsMatchIn(lines[i+1]))) {
                        lines[i+1]
                    } else if (i + 2 < lines.size && (lines[i+2].contains("-") || Regex("""\d""").containsMatchIn(lines[i+2]))) {
                        lines[i+2]
                    } else ""

                    if (targetLine.isNotBlank()) {
                        val nums = Regex("""\b\d{1,2}\b""").findAll(targetLine).mapNotNull { it.value.toIntOrNull() }.toList()
                        if (nums.isNotEmpty()) {
                            penaltySubTotals.add(nums.last())
                        } else if (targetLine.contains("-")) {
                            penaltySubTotals.add(0)
                        }
                    }
                }
            }

            // 3-4. Dist (Tee Shot) 라인 추출 (파3 제외 비거리, 줄바꿈 분리 지원)
            val distRowRegex = Regex("""(?:Dist|Distance|비거리|거리)""", RegexOption.IGNORE_CASE)
            val processedDistLines = mutableSetOf<Int>()

            for (i in lines.indices) {
                val line = lines[i]
                if (distRowRegex.containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in lines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = lines[cIdx]
                        val nums = Regex("""\b([1-3]\d{2})\b""").findAll(cLine)
                            .mapNotNull { it.value.toDoubleOrNull() }
                            .filter { it in 100.0..350.0 }
                            .toList()
                        if (nums.isNotEmpty()) {
                            allDriveDistances.addAll(nums)
                            processedDistLines.add(cIdx)
                            break
                        }
                    }
                }
            }

            // 폴백: Dist 라벨 누락 시에도 120~350 사이의 3자리 정수가 2개 이상 나열된 행 감지
            for (i in lines.indices) {
                if (i in processedDistLines) continue
                val line = lines[i]
                if (line.contains("202") || line.contains("%") || line.contains("걸음")) continue
                val nums = Regex("""\b([1-3]\d{2})\b""").findAll(line)
                    .mapNotNull { it.value.toDoubleOrNull() }
                    .filter { it in 100.0..350.0 }
                    .toList()
                if (nums.size >= 2) {
                    allDriveDistances.addAll(nums)
                    processedDistLines.add(i)
                }
            }

            // 3-5. Tempo (Tee Shot) 라인 추출 (예: "Tempo 3.0 - 3.2 3.4 - 3.3 2.7 2.9 3.1" 또는 줄바꿈 분리)
            val tempoRowRegex = Regex("""(?:Tempo|템포)""", RegexOption.IGNORE_CASE)
            val processedTempoLines = mutableSetOf<Int>()

            for (i in lines.indices) {
                val line = lines[i]
                if (tempoRowRegex.containsMatchIn(line)) {
                    val candidateIndices = listOf(i, i + 1, i + 2).filter { it in lines.indices }
                    for (cIdx in candidateIndices) {
                        val cLine = lines[cIdx]
                        val nums = Regex("""\b([1-5]\.\d)\b""").findAll(cLine)
                            .mapNotNull { it.value.toDoubleOrNull() }
                            .filter { it in 1.5..5.5 }
                            .toList()
                        if (nums.isNotEmpty()) {
                            allTempos.addAll(nums)
                            processedTempoLines.add(cIdx)
                            break
                        }
                    }
                }
            }

            // 폴백: Tempo 라벨 누락 시에도 1.5~5.5 사이 소수(x.x)가 2개 이상 나열된 행 감지 (GIR % 행 제외)
            for (i in lines.indices) {
                if (i in processedTempoLines) continue
                val line = lines[i]
                if (line.contains("%") || line.contains("퍼트") || line.contains("SCORE")) continue
                val nums = Regex("""\b([1-5]\.\d)\b""").findAll(line)
                    .mapNotNull { it.value.toDoubleOrNull() }
                    .filter { it in 1.5..5.5 }
                    .toList()
                if (nums.size >= 2) {
                    allTempos.addAll(nums)
                    processedTempoLines.add(i)
                }
            }

            // 파3 홀 제외 필터링 (parList가 홀별로 추출되었고 거리 수와 일치할 때)
            val effectiveDriveDistances = if (allDriveDistances.size == parList.size && parList.contains(3)) {
                allDriveDistances.filterIndexed { index, _ -> parList.getOrNull(index) != 3 }
            } else {
                allDriveDistances
            }

            // 4. 단일 폴백 TOTAL / PUTT 탐색
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
                    fallbackTotalPutts = m.groupValues[1].toIntOrNull()
                    if (fallbackTotalPutts != null && fallbackTotalPutts in 15..60) break
                }
            }

            // 5. 총 타수(totalScore) 최종 확정 (상단 86(+14) 대형 스코어 및 42+44 소계 합산 최우선)
            val finalTotalScore: Int? = when {
                summaryScore != null -> summaryScore // 상단 요약 카드의 SCORE (86)
                subTotals.size == 2 -> subTotals.sum() // 전반 소계 + 후반 소계 (42 + 44 = 86)
                allHoleScores.size == 18 -> allHoleScores.sum() // 18홀 스코어 합계
                subTotals.isNotEmpty() -> subTotals.sum()
                allHoleScores.size == 9 -> allHoleScores.sum()
                fallbackTotalScore != null -> fallbackTotalScore
                else -> null
            }

            // 6. 총 퍼트 수(totalPutts) 최종 확정
            val finalTotalPutts: Int? = when {
                puttSubTotals.size == 2 -> puttSubTotals.sum() // 전반 17 + 후반 23 = 40
                puttSubTotals.size == 1 && puttSubTotals[0] in 15..60 -> puttSubTotals[0]
                summaryAvgPutts != null -> Math.round(summaryAvgPutts * (if (allHoleScores.size == 9) 9 else 18)).toInt() // 2.2 * 18 = 40
                fallbackTotalPutts != null -> fallbackTotalPutts
                else -> null
            }

            // 7. 페널티 타수(penaltyCount) 확정
            val finalPenalty: Int? = when {
                penaltySubTotals.isNotEmpty() -> penaltySubTotals.sum() // West 2 + South 0 = 2
                else -> null
            }

            // 8. 평균 비거리 및 평균 템포 계산 (파3 제외)
            val avgDrive = if (effectiveDriveDistances.isNotEmpty()) {
                (effectiveDriveDistances.sum() / effectiveDriveDistances.size * 10).toInt() / 10.0
            } else null

            // 최저기록과 최고기록을 제외한 평균 티샷 비거리(보정) 산출
            val adjustedAvgDrive = if (effectiveDriveDistances.size >= 3) {
                val sorted = effectiveDriveDistances.sorted()
                val trimmed = sorted.subList(1, sorted.size - 1)
                (trimmed.sum() / trimmed.size * 10).toInt() / 10.0
            } else avgDrive

            val avgTempo = if (allTempos.isNotEmpty()) {
                (allTempos.sum() / allTempos.size * 10).toInt() / 10.0
            } else null

            // 9. 코스명 종합 ("West / South")
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
                driveDistances = effectiveDriveDistances,
                tempos = allTempos,
                clubName = detectedClubName,
                recognizedRawText = raw
            )
        }
    }
}
