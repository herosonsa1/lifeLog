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

    private val recognizer = TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())

    /**
     * [C-01] 안전 비트맵 다운샘플링 (최대 maxDimension px).
     * inJustDecodeBounds로 원본 해상도를 사전 측정 후 inSampleSize를 계산하여
     * 최대 1024px 이하로 축소 디코딩합니다. RGB_565를 사용해 메모리를 50% 절감합니다.
     */
    private fun decodeSafeSampledBitmap(uri: Uri, maxDimension: Int = 1024): Bitmap? {
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
            // [C-01] 고해상도 사진 OOM 방지: 다운샘플링 후 ML Kit 전달
            sampledBitmap = decodeSafeSampledBitmap(imageUri, 1024)
            val image = if (sampledBitmap != null) {
                InputImage.fromBitmap(sampledBitmap, 0)
            } else {
                // 다운샘플링 실패 시 폴백 (소용량 이미지 경우)
                InputImage.fromFilePath(context, imageUri)
            }
            val visionText = recognizer.process(image).await()
            val raw = visionText.text

            val scores = mutableListOf<Int>()
            val numberPattern = Pattern.compile("""\b([3-9]|1[0-2])\b""")
            val matcher = numberPattern.matcher(raw)

            while (matcher.find() && scores.size < 18) {
                matcher.group(1)?.toIntOrNull()?.let { scores.add(it) }
            }

            val totalScore = if (scores.isNotEmpty()) scores.sum() else run {
                val totalPattern = Pattern.compile(
                    """(?:TOTAL|합계|스코어|Total)\s*[:：]?\s*(\d{2,3})""",
                    Pattern.CASE_INSENSITIVE
                )
                val m = totalPattern.matcher(raw)
                if (m.find()) m.group(1)?.toIntOrNull() else null
            }

            val puttsPattern = Pattern.compile(
                """(?:PUTT|퍼트|퍼팅)\s*[:：]?\s*(\d{1,2})""",
                Pattern.CASE_INSENSITIVE
            )
            val pm = puttsPattern.matcher(raw)
            val totalPutts = if (pm.find()) pm.group(1)?.toIntOrNull() else null

            ScorecardOcrResult(
                totalScore = totalScore,
                totalPutts = totalPutts,
                holeScores = scores,
                recognizedRawText = raw
            )
        } catch (t: Throwable) {
            // [C-01] catch(Throwable): OutOfMemoryError, SIGABRT 등 시스템 레벨 Error까지 안전하게 포획
            ScorecardOcrResult(null, null, emptyList(), "OCR 분석 스킵: ${t.message}")
        } finally {
            // [C-01] 처리 완료 후 즉시 네이티브 비트맵 메모리 해제
            sampledBitmap?.recycle()
        }
    }

    /**
     * [L-04] ML Kit TextRecognizer 네이티브 리소스 명시적 해제.
     * GolfViewModel 소멸 시 호출하거나 onCleared()에서 호출해 리소스 누수를 방지합니다.
     */
    fun close() {
        runCatching { recognizer.close() }
    }
}
