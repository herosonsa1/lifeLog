package com.autologue.app.data.ocr

import android.content.Context
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
    val recognizedRawText: String
)

class ScorecardOcrAnalyzer(private val context: Context) {

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    suspend fun analyzeScorecard(imageUri: Uri): ScorecardOcrResult = withContext(Dispatchers.IO) {
        try {
            val image = InputImage.fromFilePath(context, imageUri)
            val visionText = recognizer.process(image).await()
            val raw = visionText.text

            val scores = mutableListOf<Int>()
            val numberPattern = Pattern.compile("""\b([3-9]|1[0-2])\b""")
            val matcher = numberPattern.matcher(raw)

            while (matcher.find() && scores.size < 18) {
                matcher.group(1)?.toIntOrNull()?.let { scores.add(it) }
            }

            val totalScore = if (scores.isNotEmpty()) scores.sum() else run {
                val totalPattern = Pattern.compile("""(?:TOTAL|합계|스코어|Total)\s*[:：]?\s*(\d{2,3})""", Pattern.CASE_INSENSITIVE)
                val m = totalPattern.matcher(raw)
                if (m.find()) m.group(1)?.toIntOrNull() else null
            }

            val puttsPattern = Pattern.compile("""(?:PUTT|퍼트|퍼팅)\s*[:：]?\s*(\d{1,2})""", Pattern.CASE_INSENSITIVE)
            val pm = puttsPattern.matcher(raw)
            val totalPutts = if (pm.find()) pm.group(1)?.toIntOrNull() else null

            ScorecardOcrResult(
                totalScore = totalScore,
                totalPutts = totalPutts,
                holeScores = scores,
                recognizedRawText = raw
            )
        } catch (e: Exception) {
            ScorecardOcrResult(null, null, emptyList(), "OCR 분석 오류: ${e.message}")
        }
    }
}
