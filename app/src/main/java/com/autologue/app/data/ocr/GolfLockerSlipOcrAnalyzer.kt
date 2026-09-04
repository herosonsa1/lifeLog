package com.autologue.app.data.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalTime
import javax.inject.Inject
import javax.inject.Singleton

data class GolfLockerSlipResult(
    val isLockerSlip: Boolean,
    val clubName: String,
    val lockerNumber: String?,
    val teeOffTime: LocalTime?,
    val courseName: String?,
    val date: LocalDate,
    val playerName: String?,
    val gender: String?,
    val rawText: String
)

@Singleton
class GolfLockerSlipOcrAnalyzer @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val context: Context
) {
    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    suspend fun analyzeLockerSlip(imageUri: Uri, fallbackDate: LocalDate? = null): GolfLockerSlipResult = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            val visionText = recognizer.process(image).await()
            val raw = visionText.text

            parseLockerSlipText(raw, fallbackDate)
        } catch (e: Exception) {
            GolfLockerSlipResult(
                isLockerSlip = false,
                clubName = "필드 골프장",
                lockerNumber = null,
                teeOffTime = null,
                courseName = null,
                date = fallbackDate ?: LocalDate.now(),
                playerName = null,
                gender = null,
                rawText = "OCR 실패: ${e.message}"
            )
        }
    }

    fun parseLockerSlipText(raw: String, fallbackDate: LocalDate? = null): GolfLockerSlipResult {
        val lines = raw.lines().map { it.trim() }.filter { it.isNotBlank() }
        val upperRaw = raw.uppercase()
        android.util.Log.d("SLIP_RAW", "--- OCR RAW START ---\n$raw\n--- OCR RAW END ---")

        // 1. Is this a Golf Locker Slip? (단어 단위 독립 검사 및 영문 오탐 방지)
        val ccGcRegex = Regex("""\b(CC|GC|C\.C|G\.C|COUNTRY\s*CLUB|GOLF\s*CLUB)\b""", RegexOption.IGNORE_CASE)
        val hasCcGc = ccGcRegex.containsMatchIn(raw)
        val hasExplicitLocker = upperRaw.contains("락카") || upperRaw.contains("라커") || upperRaw.contains("락커") || upperRaw.contains("LOCKER")
        val hasGolfTerms = upperRaw.contains("골프") || upperRaw.contains("GOLF") || upperRaw.contains("TEE-OFF") || upperRaw.contains("TEE OFF") ||
            upperRaw.contains("티오프") || upperRaw.contains("티업") || upperRaw.contains("그린피") || upperRaw.contains("골프장") || upperRaw.contains("골프백")

        // 2. Club Name Extraction
        var clubName: String? = null
        val knownClubs = mapOf(
            "SKY VALLEY" to "스카이밸리 CC",
            "스카이밸리" to "스카이밸리 CC",
            "ARIJICC" to "아리지 CC",
            "ARIJI" to "아리지 CC",
            "아리지" to "아리지 CC",
            "PHILOS" to "필로스 CC",
            "필로스" to "필로스 CC",
            "LADENA" to "라데나 GC",
            "라데나" to "라데나 GC",
            "남촌" to "남촌 CC",
            "동강시스타" to "동강시스타 CC",
            "해슬리" to "해슬리 나인브릿지",
            "베어크리크" to "베어크리크 GC",
            "라비에벨" to "라비에벨 CC",
            "아난티" to "아난티 클럽"
        )

        val hasKnownClub = knownClubs.keys.any { upperRaw.contains(it.uppercase()) }
        for ((kw, name) in knownClubs) {
            if (upperRaw.contains(kw.uppercase())) {
                clubName = name
                break
            }
        }

        if (clubName == null && hasCcGc) {
            for (line in lines.take(4)) {
                val cleaned = line.replace(Regex("""[^가-힣A-Za-z0-9\s\.\-_]"""), "").trim()
                if (ccGcRegex.containsMatchIn(cleaned) || cleaned.contains("골프") || cleaned.contains("클럽")) {
                    clubName = cleaned
                    break
                }
            }
        }

        // 3. Locker Number Extraction
        var lockerNumber: String? = null
        val genderLockerRegex = Regex("""(?:\([남여]\)|\[[남여]\]|[남여])\s*([A-Za-z]?\s*[-–]?\s*\d{2,4})|([A-Za-z]?\s*[-–]?\s*\d{2,4})\s*(?:\([남여]\)|\[[남여]\])""")
        for (line in lines) {
            val m = genderLockerRegex.find(line)
            if (m != null) {
                val matched = (m.groups[1]?.value ?: m.groups[2]?.value)?.replace(" ", "")?.trim()
                if (!matched.isNullOrBlank() && matched != "7777" && !matched.startsWith("202")) {
                    lockerNumber = matched
                    break
                }
            }
        }
        if (lockerNumber == null) {
            for (i in lines.indices) {
                val line = lines[i].trim()
                val isNearbyGender = line.contains("남") || line.contains("여") ||
                    (i > 0 && (lines[i-1].contains("남") || lines[i-1].contains("여"))) ||
                    (i < lines.size - 1 && (lines[i+1].contains("남") || lines[i+1].contains("여")))

                val standaloneMatch = Regex("""^[A-Za-z]?\s*[-–]?\s*\d{2,4}$""").find(line)
                if (standaloneMatch != null) {
                    val cand = standaloneMatch.value.replace(" ", "").trim()
                    if (cand != "7777" && cand != "0000" && !cand.startsWith("202")) {
                        lockerNumber = cand
                        if (isNearbyGender) break
                    }
                }
            }
        }
        if (lockerNumber == null) {
            val labelLockerRegex = Regex("""(?:락카|라커|락커|LOCKER)\s*[:#번호\s]*([A-Za-z]?\s*[-–]?\s*\d{2,4})""", RegexOption.IGNORE_CASE)
            for (line in lines) {
                val m = labelLockerRegex.find(line)
                if (m != null) {
                    val cand = m.groupValues[1].replace(" ", "").trim()
                    if (cand != "7777" && !cand.startsWith("202")) {
                        lockerNumber = cand
                        break
                    }
                }
            }
        }

        // 4. Tee-off Time Extraction
        var teeOffTime: LocalTime? = null
        val timeWithLabel = Regex("""(?:Tee-Off|Tee\s*Off|T/O|시간|티오프|티업|달님|마운틴|서|동|남|북)\s*[:：|]?\s*([0-2]?\d:[0-5]\d)""", RegexOption.IGNORE_CASE)
        for (line in lines) {
            val m = timeWithLabel.find(line)
            if (m != null) {
                runCatching {
                    val parts = m.groupValues[1].split(":")
                    teeOffTime = LocalTime.of(parts[0].toInt(), parts[1].toInt())
                }
                if (teeOffTime != null) break
            }
        }
        if (teeOffTime == null) {
            val genericTime = Regex("""\b([0-2]?\d:[0-5]\d)\b""")
            for (line in lines) {
                val m = genericTime.find(line)
                if (m != null) {
                    runCatching {
                        val parts = m.groupValues[1].split(":")
                        teeOffTime = LocalTime.of(parts[0].toInt(), parts[1].toInt())
                    }
                    if (teeOffTime != null) break
                }
            }
        }

        // 5. Course Name Extraction
        var courseName: String? = null
        val courseRegex = Regex("""(?:코스|Course)\s*[:：]?\s*([가-힣A-Za-z0-9]+)""", RegexOption.IGNORE_CASE)
        for (line in lines) {
            val m = courseRegex.find(line)
            if (m != null) {
                courseName = m.groupValues[1].trim()
                break
            }
        }
        if (courseName == null) {
            val knownCourses = listOf("마운틴", "달님", "해님", "별님", "레이크", "힐", "밸리", "파인", "서", "동", "남", "북")
            for (course in knownCourses) {
                if (raw.contains(course)) {
                    courseName = course
                    break
                }
            }
        }

        // 6. Date Extraction
        var parsedDate: LocalDate? = null
        val dateRegex = Regex("""(20\d{2})[-./년]\s*(\d{1,2})[-./월]\s*(\d{1,2})""")
        for (line in lines) {
            val m = dateRegex.find(line)
            if (m != null) {
                val y = m.groupValues[1].toInt()
                val month = m.groupValues[2].toInt()
                val d = m.groupValues[3].toInt()
                runCatching {
                    parsedDate = LocalDate.of(y, month, d)
                }
                if (parsedDate != null) break
            }
        }
        val finalDate = parsedDate ?: (fallbackDate ?: LocalDate.now())

        // 7. Player Name & Gender Extraction
        var playerName: String? = null
        val playerRegex = Regex("""([가-힣]{2,4})\s*님""")
        for (line in lines) {
            val m = playerRegex.find(line)
            if (m != null) {
                playerName = m.groupValues[1].trim()
                break
            }
        }

        var gender: String? = null
        if (raw.contains("(남)") || raw.contains("남성") || raw.contains("락카(남)")) gender = "남"
        else if (raw.contains("(여)") || raw.contains("여성") || raw.contains("락카(여)")) gender = "여"

        // 8. Final Slip Decision (영문 오탐 배제 및 복합 검증 조건)
        val isLockerSlip = (hasExplicitLocker && (hasGolfTerms || hasCcGc || hasKnownClub || lockerNumber != null)) ||
                (hasKnownClub && (lockerNumber != null || teeOffTime != null)) ||
                (lockerNumber != null && teeOffTime != null && (hasGolfTerms || hasCcGc))

        return GolfLockerSlipResult(
            isLockerSlip = isLockerSlip,
            clubName = if (isLockerSlip) (clubName ?: "필드 골프장") else "일반 사진",
            lockerNumber = lockerNumber,
            teeOffTime = teeOffTime,
            courseName = courseName,
            date = finalDate,
            playerName = playerName,
            gender = gender,
            rawText = raw
        )
    }
}
