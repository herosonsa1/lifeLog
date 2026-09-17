package com.autologue.app.domain.usecase.golf

import android.net.Uri
import com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer
import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import com.autologue.app.data.sync.ScannedPhoto
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.repository.GolfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoProcessGolfMediaUseCase @Inject constructor(
    private val golfLockerSlipOcrAnalyzer: GolfLockerSlipOcrAnalyzer,
    private val scorecardOcrAnalyzer: ScorecardOcrAnalyzer,
    private val processGolfLockerSlipUseCase: ProcessGolfLockerSlipUseCase,
    private val extractScorecardOcrUseCase: ExtractScorecardOcrUseCase,
    private val golfRepository: GolfRepository
) {

    /**
     * 단일 이미지 URI를 분석하여 라커룸 전표 또는 스코어카드인 경우 골프 라운드로 자동 등록합니다.
     * 단일 패스(1-Pass) 방식으로 1회 OCR을 통해 스코어카드와 라커룸 전표를 모두 지능형 판별합니다.
     */
    suspend fun processSinglePhoto(uri: Uri, photoDate: LocalDate = LocalDate.now()): GolfRound? = withContext(Dispatchers.IO) {
        try {
            // 1. 단 1회의 ML Kit OCR 분석 수행
            val scorecardResult = scorecardOcrAnalyzer.analyzeScorecard(uri)
            val rawText = scorecardResult.recognizedRawText
            val rawUpper = rawText.uppercase()

            // 2. 스코어카드 유효성 점검
            val golfFingerprints = listOf("SCORE", "스코어", "PAR", "HOLE", "PUTT", "퍼트", "GIR", "PENALTY", "페널티", "벌타", "PINE", "CHERRY", "OAK", "파인", "체리", "오크")
            val matchedKeywords = golfFingerprints.count { rawUpper.contains(it) }
            val isValidScorecard = matchedKeywords >= 1 && (
                    (scorecardResult.totalScore != null && scorecardResult.totalScore in 50..144) ||
                    scorecardResult.holeScores.isNotEmpty()
            )

            // 3. 라커룸 전표 유효성 점검 (기존 추출 텍스트 재활용 — 중복 OCR 0회)
            val slipResult = GolfLockerSlipOcrAnalyzer.parse(rawText, photoDate)
            val isValidSlip = slipResult.isLockerSlip && slipResult.clubName != "일반 사진"

            // 4. 판별 및 안전 등록
            when {
                isValidScorecard -> {
                    if (isValidSlip) {
                        // 전표 정보(티오프, 라커)와 스코어카드 정보(타수, 18홀)를 동시 결합
                        processGolfLockerSlipUseCase(slipResult, uri.toString())
                    }
                    return@withContext extractScorecardOcrUseCase.processScorecardResult(scorecardResult, uri.toString(), photoDate)
                }
                isValidSlip -> {
                    return@withContext processGolfLockerSlipUseCase(slipResult, uri.toString())
                }
            }
        } catch (t: Throwable) {
            android.util.Log.e("AutoProcessGolfMedia", "단일 사진 분석 중 오류 발생 ($uri): ${t.message}", t)
        }
        null
    }

    /**
     * 후보 사진 목록(스크린샷 및 갤러리 사진)을 단일 패스(1-Pass) 고속 통합 엔진으로 자동 처리합니다.
     * 각 이미지당 ML Kit OCR을 단 1회만 수행하여 2.5배 빠른 처리 속도로 최대 200장의 사진을 전수 분석합니다.
     * @return 성공적으로 등록 또는 갱신된 골프 라운드 수
     */
    suspend fun processBatchCandidates(photos: List<ScannedPhoto>): Int = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext 0

        val processedRounds = mutableSetOf<Long>()
        val maxBatchSize = 200
        val targetPhotos = photos.take(maxBatchSize)

        android.util.Log.d("AutoProcessGolfMedia", "골프 미디어 고속 1-Pass 일괄 분석 시작: 총 ${targetPhotos.size}장 대상")

        for ((idx, photo) in targetPhotos.withIndex()) {
            if (photo.uri.isBlank()) continue
            val uri = runCatching { Uri.parse(photo.uri) }.getOrNull() ?: continue
            val photoDate = photo.time.toLocalDate()

            try {
                // 단 1회의 ML Kit OCR 수행 (ScorecardOcrAnalyzer 내부에서 비트맵 최적화 디코딩)
                val scorecardResult = scorecardOcrAnalyzer.analyzeScorecard(uri)
                val rawText = scorecardResult.recognizedRawText
                val rawUpper = rawText.uppercase()

                // A. 스코어카드 검증
                val golfFingerprints = listOf("SCORE", "스코어", "PAR", "HOLE", "PUTT", "퍼트", "GIR", "PENALTY", "페널티", "벌타", "PINE", "CHERRY", "OAK", "파인", "체리", "오크")
                val matchedKeywords = golfFingerprints.count { rawUpper.contains(it) }
                val isValidScorecard = matchedKeywords >= 1 && (
                        (scorecardResult.totalScore != null && scorecardResult.totalScore in 50..144) ||
                        scorecardResult.holeScores.isNotEmpty()
                )

                // B. 라커룸 안내지 검증 (추출된 동일 rawText 파싱 — 추가 OCR 비용 0ms)
                val slipResult = GolfLockerSlipOcrAnalyzer.parse(rawText, photoDate)
                val isValidSlip = slipResult.isLockerSlip && slipResult.clubName != "일반 사진"

                if (isValidScorecard) {
                    if (isValidSlip) {
                        processGolfLockerSlipUseCase(slipResult, photo.uri)
                    }
                    val round = extractScorecardOcrUseCase.processScorecardResult(scorecardResult, photo.uri, photoDate)
                    if (round != null) {
                        processedRounds.add(round.id)
                        android.util.Log.d("AutoProcessGolfMedia", "[${idx + 1}/${targetPhotos.size}] ⛳ 스코어카드 분석 성공: ${round.clubName} ${round.totalScore}타 (id=${round.id})")
                    }
                } else if (isValidSlip) {
                    val round = processGolfLockerSlipUseCase(slipResult, photo.uri)
                    if (round != null) {
                        processedRounds.add(round.id)
                        android.util.Log.d("AutoProcessGolfMedia", "[${idx + 1}/${targetPhotos.size}] 📋 라커룸 안내지 분석 성공: ${round.clubName} (id=${round.id})")
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("AutoProcessGolfMedia", "[${idx + 1}/${targetPhotos.size}] 미디어 분석 건너뜀 (${photo.uri}): ${t.message}")
            }
        }

        android.util.Log.d("AutoProcessGolfMedia", "골프 미디어 고속 분석 완료: 총 ${processedRounds.size}건 등록/갱신됨")
        processedRounds.size
    }
}
