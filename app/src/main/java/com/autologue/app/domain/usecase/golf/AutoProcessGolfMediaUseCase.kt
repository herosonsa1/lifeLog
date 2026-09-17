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
     */
    suspend fun processSinglePhoto(uri: Uri, photoDate: LocalDate = LocalDate.now()): GolfRound? = withContext(Dispatchers.IO) {
        try {
            // 1. 라커룸 안내지인지 우선 분석
            val slipResult = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(uri, fallbackDate = photoDate)
            if (slipResult.isLockerSlip && slipResult.clubName != "일반 사진") {
                return@withContext processGolfLockerSlipUseCase(slipResult, uri.toString())
            }

            // 2. 라커룸이 아니면 스코어카드인지 분석
            val scorecardResult = scorecardOcrAnalyzer.analyzeScorecard(uri)
            val rawUpper = scorecardResult.recognizedRawText.uppercase()
            val hasGolfKeywords = rawUpper.contains("SCORE") || rawUpper.contains("PAR") || rawUpper.contains("HOLE") ||
                    rawUpper.contains("GIR") || rawUpper.contains("PUTT") || rawUpper.contains("스코어") || rawUpper.contains("퍼트")
            val isValidScorecard = hasGolfKeywords && (
                    (scorecardResult.totalScore != null && scorecardResult.totalScore in 50..150 && scorecardResult.holeScores.size >= 9) ||
                    scorecardResult.holeScores.size == 18
            )
            if (isValidScorecard) {
                return@withContext extractScorecardOcrUseCase.processScorecardResult(scorecardResult, uri.toString(), photoDate)
            }
        } catch (t: Throwable) {
            android.util.Log.e("AutoProcessGolfMedia", "사진 분석 중 오류 발생 ($uri): ${t.message}", t)
        }
        null
    }

    /**
     * 후보 사진 목록(스크린샷 및 갤러리 사진)을 2-Pass 방식으로 자동 처리합니다.
     * Pass 1: 라커룸 전표를 먼저 감지하여 클럽명, 티오프 시간, 라커번호 기반 라운드 골격을 생성합니다.
     * Pass 2: 스코어카드를 감지하여 해당 날짜 라운드에 총타수, 퍼트수, 18홀 스코어 매트릭스를 완벽 결합합니다.
     * @return 성공적으로 등록 또는 갱신된 골프 라운드 수
     */
    suspend fun processBatchCandidates(photos: List<ScannedPhoto>): Int = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext 0

        val processedRounds = mutableSetOf<Long>()
        val remainingForScorecard = mutableListOf<ScannedPhoto>()

        // [Pass 1] 라커룸 전표 선제 분석 (최대 30장)
        for (photo in photos.take(30)) {
            if (photo.uri.isBlank()) continue
            val uri = runCatching { Uri.parse(photo.uri) }.getOrNull() ?: continue
            val photoDate = photo.time.toLocalDate()

            try {
                val slipResult = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(uri, fallbackDate = photoDate)
                if (slipResult.isLockerSlip && slipResult.clubName != "일반 사진") {
                    val round = processGolfLockerSlipUseCase(slipResult, photo.uri)
                    if (round != null) {
                        processedRounds.add(round.id)
                        android.util.Log.d("AutoProcessGolfMedia", "라커룸 전표 분석 성공: ${round.clubName} (id=${round.id})")
                    }
                } else {
                    remainingForScorecard.add(photo)
                }
            } catch (t: Throwable) {
                remainingForScorecard.add(photo)
            }
        }

        // [Pass 2] 스코어카드 분석 및 해당 날짜 라운드에 결합 (최대 30장)
        for (photo in remainingForScorecard.take(30)) {
            val uri = runCatching { Uri.parse(photo.uri) }.getOrNull() ?: continue
            val photoDate = photo.time.toLocalDate()

            try {
                val scorecardResult = scorecardOcrAnalyzer.analyzeScorecard(uri)
                val rawUpper = scorecardResult.recognizedRawText.uppercase()
                val hasGolfKeywords = rawUpper.contains("SCORE") || rawUpper.contains("PAR") || rawUpper.contains("HOLE") ||
                        rawUpper.contains("GIR") || rawUpper.contains("PUTT") || rawUpper.contains("스코어") || rawUpper.contains("퍼트")
                val isValidScorecard = hasGolfKeywords && (
                        (scorecardResult.totalScore != null && scorecardResult.totalScore in 50..150 && scorecardResult.holeScores.size >= 9) ||
                        scorecardResult.holeScores.size == 18
                )
                if (isValidScorecard) {
                    val round = extractScorecardOcrUseCase.processScorecardResult(scorecardResult, photo.uri, photoDate)
                    if (round != null) {
                        processedRounds.add(round.id)
                        android.util.Log.d("AutoProcessGolfMedia", "스코어카드 분석 성공: ${round.clubName} ${scorecardResult.totalScore}타 (id=${round.id})")
                    }
                }
            } catch (t: Throwable) {
                // 스코어카드가 아니거나 파싱 실패 시 건너뜀
            }
        }

        android.util.Log.d("AutoProcessGolfMedia", "골프 미디어 일괄 분석 완료: 총 ${processedRounds.size}건 등록/결합")
        processedRounds.size
    }
}
