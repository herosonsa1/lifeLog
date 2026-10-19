package com.autologue.app.domain.usecase.golf

import android.net.Uri
import com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer
import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import com.autologue.app.data.preferences.SyncCheckpointPreferences
import com.autologue.app.data.sync.ScannedPhoto
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.repository.GolfRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class AutoProcessGolfMediaUseCase @Inject constructor(
    private val golfLockerSlipOcrAnalyzer: GolfLockerSlipOcrAnalyzer,
    private val scorecardOcrAnalyzer: ScorecardOcrAnalyzer,
    private val processGolfLockerSlipUseCase: ProcessGolfLockerSlipUseCase,
    private val extractScorecardOcrUseCase: ExtractScorecardOcrUseCase,
    private val golfRepository: GolfRepository,
    private val syncCheckpointPreferences: SyncCheckpointPreferences?
) {
    /** 단위 테스트 하위 호환용 생성자 */
    constructor(
        golfLockerSlipOcrAnalyzer: GolfLockerSlipOcrAnalyzer,
        scorecardOcrAnalyzer: ScorecardOcrAnalyzer,
        processGolfLockerSlipUseCase: ProcessGolfLockerSlipUseCase,
        extractScorecardOcrUseCase: ExtractScorecardOcrUseCase,
        golfRepository: GolfRepository
    ) : this(
        golfLockerSlipOcrAnalyzer,
        scorecardOcrAnalyzer,
        processGolfLockerSlipUseCase,
        extractScorecardOcrUseCase,
        golfRepository,
        null
    )

    /**
     * 단일 이미지 URI를 분석하여 라커룸 전표 또는 스코어카드인 경우 골프 라운드로 자동 등록합니다.
     * 단일 패스(1-Pass) 방식으로 1회 OCR을 통해 스코어카드와 라커룸 전표를 모두 지능형 판별합니다.
     */
    suspend fun processSinglePhoto(uri: Uri, photoDate: LocalDate = LocalDate.now()): GolfRound? = withContext(Dispatchers.IO) {
        try {
            // 1. 단 1회의 ML Kit OCR 분석 수행 (사진 촬영일/EXIF 교차 검증 연동)
            val scorecardResult = scorecardOcrAnalyzer.analyzeScorecard(uri, photoDate)
            // 1. 의료 및 일반 영수증 네거티브 가드레일 (외래 진료비, 약제비, 처방전 등 즉시 배제)
            if (ScorecardOcrAnalyzer.hasMedicalOrReceiptNegative(scorecardResult.recognizedRawText)) {
                android.util.Log.d("AutoProcessGolfMedia", "processSinglePhoto: 의료/영수증 감지로 골프 등록 거부 ($uri)")
                return@withContext null
            }

            val rawText = scorecardResult.recognizedRawText
            val rawUpper = rawText.uppercase()

            // 2. 스코어카드 유효성 점검 (진성 스코어카드 테이블 구조 + 유효 타수/홀별 스코어 필수 가드레일)
            // 웹사이트 캡처, 후기 게시판, 9홀 불완전 조각, 타수 없는 단순 사진의 단독 라운드 자동 등록 원천 차단
            val isValidScorecard = ScorecardOcrAnalyzer.isGenuineScorecardResult(scorecardResult, rawText)

            // 3. 라커룸 전표 유효성 점검 (기존 추출 텍스트 재활용 — 중복 OCR 0회)
            // 스코어카드 사진이 스코어 파싱 미흡으로 인해 라커룸 전표로 둔갑하여 빈 라운드로 등록되는 부작용 방지
            val isScorecardLike = rawUpper.contains("SCORE") || rawUpper.contains("스코어") || (rawUpper.contains("PAR") && rawUpper.contains("HOLE"))
            val slipResult = GolfLockerSlipOcrAnalyzer.parse(rawText, photoDate)
            val isValidSlip = slipResult.isLockerSlip && slipResult.clubName != "일반 사진" && !isScorecardLike

            // 4. 판별 및 안전 등록 (오직 실제 스코어카드지 또는 실제 라커룸 안내지/영수증일 때만 필드 기록 생성)
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
        val GOLF_CORE_KEYWORDS = listOf(
            "SCORE", "스코어", "PAR", "HOLE", "PUTT", "퍼트", "퍼팅", "GIR", "PENALTY", "페널티", "벌타",
            "골프", "골프장", "라운드", "라운딩", "ROUND", "클럽하우스", "그린피", "카트비", "캐디피", "티오프", "TEE OFF",
            "라커", "락커", "락카", "LOCKER", "입장번호", "SMARTSCORE", "스마트스코어", "KAKAOGOLF", "카카오골프", "GOLFZON", "골프존",
            "나의 스코어", "나의스코어", "SCORECARD"
        )

        val GOLF_AUXILIARY_KEYWORDS = listOf(
            "타수", "핸디", "핸디캡", "HANDICAP", "버디", "보기", "이글", "전반", "후반", "OUT", "IN",
            "CC", "GC", "C.C", "G.C", "코스", "COURSE",
            "PINE", "CHERRY", "OAK", "파인", "체리", "오크",
            "오크밸리", "필로스", "킹스데일", "남촌", "가평", "아난티", "레이크사이드", "골드", "태광", "안성", "용인"
        )

        val GOLF_FINGERPRINTS = GOLF_CORE_KEYWORDS + GOLF_AUXILIARY_KEYWORDS
    }

    // 이미 OCR 프로브를 수행하여 비-골프(또는 의료/영수증/웹후기)로 확인된 사진 URI 메모리 캐시 (재동기화 시 0ms 패스)
    private val nonGolfCheckedUris = ConcurrentHashMap.newKeySet<String>()
    private val deepAnalysisLock = kotlinx.coroutines.sync.Mutex()

    private fun markAsNonGolf(uri: String, batchCollector: MutableSet<String>) {
        nonGolfCheckedUris.add(uri)
        batchCollector.add(uri)
    }

    /**
     * 후보 사진 목록(스크린샷 및 갤러리 사진)을 2단계 고속 병렬 지문 OCR 엔진으로 처리합니다.
     * Stage 1: 1280px 고속 비트맵 + 3채널 코루틴 병렬(Semaphore(3))로 ML Kit 텍스트 지문만 신속 추출하여 비-골프 사진을 초고속 패스하고 영구 캐시에 기록.
     * Stage 2: 골프 지문이 확인된 사진만 2560px 고정밀 2D 공간 복원 및 18홀 파싱과 라커룸 전표 분석을 수행하여 즉시 DB에 라운드로 등록.
     * Stage 3: 이미 라커룸 안내지나 스코어카드지로 확정된 골프 필드 라운드가 존재하는 날짜에 한해, 같은 날 찍은 실제 카메라 사진(스크린샷 제외)을 해당 라운드에 자동 첨부.
     */
    suspend fun processBatchCandidates(
        photos: List<ScannedPhoto>,
        onProgress: (suspend (current: Int, total: Int, foundCount: Int) -> Unit)? = null
    ): Int = withContext(Dispatchers.IO) {
        if (photos.isEmpty()) return@withContext 0

        val processedRounds = ConcurrentHashMap.newKeySet<Long>()
        val newlyMarkedNonGolfUris = ConcurrentHashMap.newKeySet<String>()
        val maxBatchSize = 600

        val existingRegisteredUris = runCatching {
            golfRepository.getAllGolfRoundsList()
                .filter { (it.totalScore ?: 0) >= 54 && it.holeScores.size >= 14 }
                .flatMap { listOfNotNull(it.scorecardPhotoUri) + it.matchingPhotoUris }
                .toSet()
        }.getOrDefault(emptySet())

        // 이미 비-골프로 영구 판정되었거나 등록 완료된 사진은 배치 진입 전 0ms 사전 제거
        val targetPhotos = photos
            .filter { photo ->
                photo.uri.isNotBlank() &&
                    photo.uri !in nonGolfCheckedUris &&
                    syncCheckpointPreferences?.isKnownNonGolfUri(photo.uri) != true &&
                    photo.uri !in existingRegisteredUris
            }
            .take(maxBatchSize)

        if (targetPhotos.isEmpty()) {
            runCatching { onProgress?.invoke(photos.size, photos.size, 0) }
            return@withContext 0
        }

        val totalCount = targetPhotos.size
        val completedCounter = AtomicInteger(0)
        val probeSemaphore = Semaphore(3)

        android.util.Log.d("AutoProcessGolfMedia", "골프 미디어 초고속 3채널 병렬 2-Stage 분석 시작: 총 ${totalCount}장 대상")

        coroutineScope {
            targetPhotos.map { photo ->
                async(Dispatchers.IO) {
                    probeSemaphore.withPermit {
                        ensureActive()
                        val uri = runCatching { Uri.parse(photo.uri) }.getOrNull()
                        val photoDate = photo.time.toLocalDate()

                        if (uri != null) {
                            try {
                                kotlinx.coroutines.withTimeoutOrNull(4500L) {
                                    // [Stage 1: Fast Parallel Fingerprint Probe - 1280px]
                                    val probeText = scorecardOcrAnalyzer.quickProbeText(uri, maxDimension = 1280)
                                    if (probeText.isBlank() || ScorecardOcrAnalyzer.hasMedicalOrReceiptNegative(probeText)) {
                                        markAsNonGolf(photo.uri, newlyMarkedNonGolfUris)
                                        return@withTimeoutOrNull
                                    }

                                    val probeUpper = probeText.uppercase()
                                    val matchedProbeKeywords = GOLF_FINGERPRINTS.filter { probeUpper.contains(it) }

                                    // 골프 지문이 단 1개도 없는 일반 사진은 영구 캐시에 기록 후 즉시 패스
                                    if (matchedProbeKeywords.isEmpty()) {
                                        markAsNonGolf(photo.uri, newlyMarkedNonGolfUris)
                                        return@withTimeoutOrNull
                                    }

                                    android.util.Log.d("AutoProcessGolfMedia", "⛳ 골프 지문 감지: $matchedProbeKeywords ($uri)")

                                    // [Stage 2: Deep Analysis - 골프 지문 감지된 사진만 2560px 정밀 분석 및 즉시 DB 반영]
                                    val scorecardResult = scorecardOcrAnalyzer.analyzeScorecard(uri, photoDate)
                                    val rawText = scorecardResult.recognizedRawText.ifBlank { probeText }
                                    if (ScorecardOcrAnalyzer.hasMedicalOrReceiptNegative(rawText)) {
                                        markAsNonGolf(photo.uri, newlyMarkedNonGolfUris)
                                        return@withTimeoutOrNull
                                    }
                                    val rawUpper = rawText.uppercase()

                                    val isValidScorecard = ScorecardOcrAnalyzer.isGenuineScorecardResult(scorecardResult, rawText)
                                    val isScorecardLike = rawUpper.contains("SCORE") || rawUpper.contains("스코어") || (rawUpper.contains("PAR") && rawUpper.contains("HOLE"))
                                    val slipResult = GolfLockerSlipOcrAnalyzer.parse(rawText, photoDate)
                                    val isValidSlip = slipResult.isLockerSlip && slipResult.clubName != "일반 사진" && !isScorecardLike

                                    if (isValidScorecard || isValidSlip) {
                                        deepAnalysisLock.lock()
                                        try {
                                            if (isValidScorecard) {
                                                if (isValidSlip) {
                                                    processGolfLockerSlipUseCase(slipResult, photo.uri)
                                                }
                                                val round = extractScorecardOcrUseCase.processScorecardResult(scorecardResult, photo.uri, photoDate)
                                                if (round != null) {
                                                    processedRounds.add(round.id)
                                                    android.util.Log.d("AutoProcessGolfMedia", "⛳ 스코어카드 분석 성공: ${round.clubName} ${round.totalScore}타 (id=${round.id})")
                                                }
                                            } else if (isValidSlip) {
                                                val round = processGolfLockerSlipUseCase(slipResult, photo.uri)
                                                if (round != null) {
                                                    processedRounds.add(round.id)
                                                    android.util.Log.d("AutoProcessGolfMedia", "📋 라커룸 안내지 분석 성공: ${round.clubName} (id=${round.id})")
                                                }
                                            }
                                        } finally {
                                            deepAnalysisLock.unlock()
                                        }
                                    } else {
                                        markAsNonGolf(photo.uri, newlyMarkedNonGolfUris)
                                    }
                                }
                            } catch (t: Throwable) {
                                if (t is kotlinx.coroutines.CancellationException) throw t
                                android.util.Log.w("AutoProcessGolfMedia", "미디어 분석 건너뜀 (${photo.uri}): ${t.message}")
                            }
                        }

                        val currentDone = completedCounter.incrementAndGet()
                        if (currentDone % 25 == 0 && newlyMarkedNonGolfUris.isNotEmpty()) {
                            syncCheckpointPreferences?.markNonGolfUris(newlyMarkedNonGolfUris.toList())
                        }
                        if (currentDone == 1 || currentDone % 3 == 0 || currentDone == totalCount) {
                            runCatching { onProgress?.invoke(currentDone, totalCount, processedRounds.size) }
                        }
                    }
                }
            }.awaitAll()
        }

        if (newlyMarkedNonGolfUris.isNotEmpty()) {
            syncCheckpointPreferences?.markNonGolfUris(newlyMarkedNonGolfUris.toList())
        }

        // [Stage 3: 확정된 필드 라운드 날짜의 골프장 촬영 사진(비-스크린샷 카메라 사진) 자동 첨부]
        runCatching {
            val confirmedRounds = golfRepository.getAllGolfRoundsList().filter {
                com.autologue.app.data.sync.DailyRouteAggregator.isRealGolfClub(it.clubName)
            }
            for (round in confirmedRounds) {
                val roundDate = (round.startTime ?: round.roundDate).toLocalDate()
                val sameDayCameraUris = photos.filter { photo ->
                    photo.time.toLocalDate() == roundDate &&
                        !photo.tags.contains("스크린샷") &&
                        !photo.uri.lowercase().contains("screenshot") &&
                        !photo.uri.lowercase().contains("스크린샷") &&
                        photo.uri != round.scorecardPhotoUri
                }.map { it.uri }

                if (sameDayCameraUris.isNotEmpty()) {
                    val mergedPhotos = (round.matchingPhotoUris + sameDayCameraUris).distinct()
                    if (mergedPhotos.size > round.matchingPhotoUris.size) {
                        golfRepository.updateGolfRound(round.copy(matchingPhotoUris = mergedPhotos))
                    }
                }
            }
        }

        runCatching { onProgress?.invoke(totalCount, totalCount, processedRounds.size) }
        android.util.Log.d("AutoProcessGolfMedia", "골프 미디어 고속 병렬 분석 완료: 총 ${processedRounds.size}건 등록/갱신됨")
        processedRounds.size
    }
}
