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

    companion object {
        private val GOLF_FINGERPRINTS = listOf(
            "SCORE", "스코어", "PAR", "HOLE", "PUTT", "퍼트", "퍼팅", "GIR", "PENALTY", "페널티", "벌타",
            "타수", "핸디", "버디", "보기", "이글", "전반", "후반", "OUT", "IN",
            "CC", "GC", "C.C", "G.C", "골프", "라운드", "라운딩", "클럽하우스", "그린피", "카트비", "캐디피", "코스", "COURSE",
            "라커", "락커", "정산", "안내서", "LOCKER", "TEE OFF", "티오프",
            "PINE", "CHERRY", "OAK", "파인", "체리", "오크",
            "오크밸리", "필로스", "킹스데일", "남촌", "가평", "아난티", "레이크사이드", "골드", "태광", "안성", "용인"
        )
    }

    /**
     * 후보 사진 목록(스크린샷 및 갤러리 사진)을 2단계 고속 지문 OCR 엔진(Two-Stage Fast Fingerprint OCR Pipeline)으로 처리합니다.
     * Stage 1: 경량 비트맵(1024px)으로 ML Kit 텍스트 인식만 수행하여 골프 지문이 없는 비-골프 사진은 0.05초 만에 즉시 패스.
     * Stage 2: 골프 지문이 확인된 사진만 2560px 고정밀 2D 공간 복원 및 18홀 파싱과 라커룸 전표 분석을 수행하여 DB에 라운드로 등록.
     * @param photos 대상 사진 목록
     * @param onProgress 진행률 콜백 (현재 처리 장수, 전체 장수, 등록된 골프 건수)
     * @return 성공적으로 등록 또는 갱신된 골프 라운드 수
     */
    suspend fun processBatchCandidates(
        photos: List<ScannedPhoto>,
        onProgress: (suspend (current: Int, total: Int, foundCount: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext 0

        val processedRounds = mutableSetOf<Long>()
        val maxBatchSize = 300
        val targetPhotos = photos.take(maxBatchSize)

        android.util.Log.d("AutoProcessGolfMedia", "골프 미디어 고속 2-Stage 일괄 분석 시작: 총 ${targetPhotos.size}장 대상")

        for ((idx, photo) in targetPhotos.withIndex()) {
            val currentIdx = idx + 1
            onProgress?.invoke(currentIdx, targetPhotos.size, processedRounds.size)

            if (photo.uri.isBlank()) continue
            val uri = runCatching { Uri.parse(photo.uri) }.getOrNull() ?: continue
            val photoDate = photo.time.toLocalDate()

            try {
                // [Stage 1: Fast Fingerprint Probe - ~50ms]
                // 경량 비트맵으로 ML Kit 텍스트만 신속 추출하여 골프 지문 존재 여부 1차 검사
                val probeText = scorecardOcrAnalyzer.quickProbeText(uri, maxDimension = 1024)
                if (probeText.isBlank()) continue

                val probeUpper = probeText.uppercase()
                val hasFingerprint = GOLF_FINGERPRINTS.any { probeUpper.contains(it) }

                // 골프 지문이 단 1개도 없는 일반 사진은 0.05초 만에 즉시 패스 (전체 시간 90% 절약)
                if (!hasFingerprint) {
                    continue
                }

                // [Stage 2: Deep Analysis - 골프 지문 감지된 사진만 정밀 분석]
                // 2560px 2D 공간 그리드 복원 및 18홀 정밀 파싱
                val scorecardResult = scorecardOcrAnalyzer.analyzeScorecard(uri)
                val rawText = scorecardResult.recognizedRawText.ifBlank { probeText }
                val rawUpper = rawText.uppercase()

                // A. 스코어카드 검증
                val matchedKeywords = GOLF_FINGERPRINTS.count { rawUpper.contains(it) }
                val isValidScorecard = matchedKeywords >= 1 && (
                        (scorecardResult.totalScore != null && scorecardResult.totalScore in 50..144) ||
                        scorecardResult.holeScores.isNotEmpty()
                )

                // B. 라커룸 안내지 검증
                val slipResult = GolfLockerSlipOcrAnalyzer.parse(rawText, photoDate)
                val isValidSlip = slipResult.isLockerSlip && slipResult.clubName != "일반 사진"

                if (isValidScorecard) {
                    if (isValidSlip) {
                        processGolfLockerSlipUseCase(slipResult, photo.uri)
                    }
                    val round = extractScorecardOcrUseCase.processScorecardResult(scorecardResult, photo.uri, photoDate)
                    if (round != null) {
                        processedRounds.add(round.id)
                        android.util.Log.d("AutoProcessGolfMedia", "[$currentIdx/${targetPhotos.size}] ⛳ 스코어카드 분석 성공: ${round.clubName} ${round.totalScore}타 (id=${round.id})")
                        onProgress?.invoke(currentIdx, targetPhotos.size, processedRounds.size)
                    }
                } else if (isValidSlip) {
                    val round = processGolfLockerSlipUseCase(slipResult, photo.uri)
                    if (round != null) {
                        processedRounds.add(round.id)
                        android.util.Log.d("AutoProcessGolfMedia", "[$currentIdx/${targetPhotos.size}] 📋 라커룸 안내지 분석 성공: ${round.clubName} (id=${round.id})")
                        onProgress?.invoke(currentIdx, targetPhotos.size, processedRounds.size)
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.w("AutoProcessGolfMedia", "[$currentIdx/${targetPhotos.size}] 미디어 분석 건너뜀 (${photo.uri}): ${t.message}")
            }
        }

        onProgress?.invoke(targetPhotos.size, targetPhotos.size, processedRounds.size)
        android.util.Log.d("AutoProcessGolfMedia", "골프 미디어 고속 분석 완료: 총 ${processedRounds.size}건 등록/갱신됨")
        processedRounds.size
    }
}
