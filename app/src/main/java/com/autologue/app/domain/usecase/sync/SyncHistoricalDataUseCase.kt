package com.autologue.app.domain.usecase.sync

import android.content.Context
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class SyncProgress(
    val isRunning: Boolean,
    val stage: String,
    val syncedTxCount: Int = 0,
    val syncedPhotoCount: Int = 0,
    val syncedGolfCount: Int = 0,
    val isDone: Boolean = false
)

/**
 * 화면(ViewModel / NavBackStackEntry) 생명주기에 종속되지 않고
 * 앱 전역 백그라운드(Application Scope)에서 독립적으로 동작하는 통합 동기화 엔진.
 * 동기화 도중 다른 메뉴(가계부, 차계부, 골프, 라이프)로 이동했다가 복귀하더라도
 * 중단이나 무한 로딩 고착 없이 실시간 진행률을 공유하고 끝까지 완료(isDone = true)됩니다.
 */
@Singleton
class SyncHistoricalDataUseCase @Inject constructor(
    private val importer: HistoricalDataImporter,
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val transactionRepository: TransactionRepository,
    private val diaryRepository: DiaryRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfRepository: com.autologue.app.domain.repository.GolfRepository,
    private val autoProcessGolfMediaUseCase: com.autologue.app.domain.usecase.golf.AutoProcessGolfMediaUseCase
) {
    private val backgroundScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val stateLock = Any()
    private val syncSessionId = AtomicLong(0L)

    @Volatile
    private var activeSyncJob: Job? = null

    @Volatile
    private var hasAutoSyncedInSession: Boolean = false

    private val _syncState = MutableStateFlow<SyncProgress?>(null)
    val syncState: StateFlow<SyncProgress?> = _syncState.asStateFlow()

    private val _isManualSyncDialogVisible = MutableStateFlow(false)
    val isManualSyncDialogVisible: StateFlow<Boolean> = _isManualSyncDialogVisible.asStateFlow()

    private fun updateSessionState(sessionId: Long, progress: SyncProgress) {
        if (syncSessionId.get() == sessionId) {
            _syncState.value = progress
        }
    }

    /**
     * 앱 세션 최초 진입 시 백그라운드 자동 동기화를 시작합니다.
     * 이미 동기화가 진행 중이거나 완료된 경우 중복 실행하지 않고 기존 상태를 그대로 유지합니다.
     */
    fun startAutoSync(context: Context, daysBack: Int = 7, force: Boolean = false) {
        val appCtx = context.applicationContext ?: context
        synchronized(stateLock) {
            if (activeSyncJob?.isActive == true) {
                return
            }
            if (hasAutoSyncedInSession && !force) {
                return
            }
            hasAutoSyncedInSession = true
            val sessionId = syncSessionId.incrementAndGet()
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "최근 ${daysBack}일 일상 기록(결제 문자 & 사진) 동기화 시작...",
                    isDone = false
                )
            )
            activeSyncJob = backgroundScope.launch {
                executeSyncPipeline(sessionId, appCtx, daysBack = daysBack, isManual = false)
            }
        }
    }

    /**
     * 사용자가 명시적으로 요청한 기간별 수동 동기화를 백그라운드에서 시작합니다.
     * 진행 중인 자동 동기화가 있다면 안전하게 중단하고 새로운 기간으로 즉시 전환합니다.
     */
    fun startManualSync(context: Context, daysBack: Int? = null) {
        val appCtx = context.applicationContext ?: context
        synchronized(stateLock) {
            val previousJob = activeSyncJob
            val sessionId = syncSessionId.incrementAndGet()
            _isManualSyncDialogVisible.value = true
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "과거 기록 동기화 준비 중...",
                    isDone = false
                )
            )
            activeSyncJob = backgroundScope.launch {
                runCatching { previousJob?.cancel() }
                executeSyncPipeline(sessionId, appCtx, daysBack = daysBack, isManual = true)
            }
        }
    }

    /**
     * 수동 동기화 다이얼로그를 닫습니다.
     * 만약 동기화가 아직 진행 중이라면 다이얼로그만 닫고 백그라운드 동기화는 상단 배너를 통해 계속 진행됩니다.
     */
    fun dismissManualSyncDialog() {
        _isManualSyncDialogVisible.value = false
        val current = _syncState.value
        if (current != null && current.isDone) {
            _syncState.value = null
        }
    }

    /**
     * 기존 Flow 수집 방식 호출부(LifestyleViewModel 등)와의 완벽한 하위 호환을 제공합니다.
     * 내부적으로 전역 백그라운드 작업을 시작/구독하며 완료 시 자동 종료됩니다.
     */
    operator fun invoke(context: Context, daysBack: Int? = null): Flow<SyncProgress> = flow {
        val appCtx = context.applicationContext ?: context
        val shouldStart = synchronized(stateLock) {
            activeSyncJob?.isActive != true
        }
        if (shouldStart) {
            startAutoSync(appCtx, daysBack = daysBack ?: 30, force = true)
        }
        syncState
            .filterNotNull()
            .transformWhile { progress ->
                emit(progress)
                !progress.isDone
            }
            .collect { progress ->
                emit(progress)
            }
    }.flowOn(Dispatchers.IO)

    private suspend fun executeSyncPipeline(
        sessionId: Long,
        context: Context,
        daysBack: Int?,
        isManual: Boolean
    ) {
        var txCount = 0
        var photoCount = 0
        var golfSyncedCount = 0
        var insertedEntriesCount = 0

        try {
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "기존 중복 데이터 검사 및 정리 중...",
                    syncedTxCount = 0,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = false
                )
            )

            // 1. 기존 중복 데이터 검사 및 의료/영수증 가짜 골프 라운드 선제 청소 (최대 10초 타임아웃 방어)
            withTimeoutOrNull(10_000L) {
                runCatching { transactionRepository.cleanDuplicates() }
                runCatching { diaryRepository.cleanDuplicates() }
                runCatching { vehicleRepository.cleanDuplicates() }
                cleanUpMedicalFakeRounds()
            }

            val periodDesc = if (daysBack != null) "최근 ${daysBack}일" else "과거 전체"
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "$periodDesc 결제 문자 스캔 중...",
                    syncedTxCount = 0,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = false
                )
            )

            // 2. 문자 스캔 및 거래/주유 내역 동기화 (최대 30초 타임아웃 방어)
            val savedTransactions = mutableListOf<com.autologue.app.domain.model.Transaction>()
            withTimeoutOrNull(30_000L) {
                val smsDaysBack = if (daysBack == null) null else maxOf(daysBack, 365)
                val transactions = importer.scanHistoricalSms(context, daysBack = smsDaysBack, limit = 5000)
                for (tx in transactions) {
                    runCatching {
                        processTransactionUseCase(tx)
                        savedTransactions.add(tx)
                        txCount++
                    }
                }
                runCatching { transactionRepository.cleanDuplicates() }
                runCatching {
                    val allDbTxs = transactionRepository.getAllTransactionsFlow().first()
                    vehicleRepository.syncRefuelingFromTransactions(allDbTxs)
                }
            }

            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "$periodDesc 갤러리 사진 및 위치 분석 중...",
                    syncedTxCount = txCount,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = false
                )
            )

            // 3. 갤러리 사진 및 위치 스캔 (최대 25초 타임아웃 방어)
            val photos = withTimeoutOrNull(25_000L) {
                importer.scanHistoricalPhotos(context, daysBack = daysBack)
            } ?: emptyList()
            photoCount = photos.size

            // 4. 캡처된 스크린샷 및 갤러리 사진에서 라커룸 전표 및 스코어카드 자동 감지
            val candidateLimit = if (!isManual && daysBack != null && daysBack <= 7) 60 else 200
            val candidateDaysBack = if (!isManual && daysBack != null && daysBack <= 7) 14 else daysBack
            val golfCandidates = withTimeoutOrNull(10_000L) {
                importer.scanHistoricalGolfCandidates(context, daysBack = candidateDaysBack, limit = candidateLimit)
            } ?: emptyList()

            if (golfCandidates.isNotEmpty()) {
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "$periodDesc 갤러리 사진 OCR 스캔 준비 중 (${golfCandidates.size}장)...",
                        syncedTxCount = txCount,
                        syncedPhotoCount = photoCount,
                        syncedGolfCount = 0,
                        isDone = false
                    )
                )
                val ocrTimeoutMs = if (isManual) 60_000L else 35_000L
                golfSyncedCount = withTimeoutOrNull(ocrTimeoutMs) {
                    runCatching {
                        autoProcessGolfMediaUseCase.processBatchCandidates(golfCandidates) { current, total, foundCount ->
                            if (current == 1 || current % 2 == 0 || current == total || foundCount > 0) {
                                val foundMsg = if (foundCount > 0) " (⛳ 골프 ${foundCount}건 발견)" else ""
                                updateSessionState(
                                    sessionId,
                                    SyncProgress(
                                        isRunning = true,
                                        stage = "갤러리 사진 OCR 분석 중... (${current}/${total}장$foundMsg)",
                                        syncedTxCount = txCount,
                                        syncedPhotoCount = photoCount,
                                        syncedGolfCount = foundCount,
                                        isDone = false
                                    )
                                )
                            }
                        }
                    }.onFailure { t ->
                        if (t is CancellationException) throw t
                        android.util.Log.e("SyncHistoricalData", "골프 미디어 일괄 OCR 분석 중 오류", t)
                    }.getOrDefault(0)
                } ?: 0

                if (golfSyncedCount > 0) {
                    updateSessionState(
                        sessionId,
                        SyncProgress(
                            isRunning = true,
                            stage = "골프 라운드 ${golfSyncedCount}건 자동 등록 완료!",
                            syncedTxCount = txCount,
                            syncedPhotoCount = photoCount,
                            syncedGolfCount = golfSyncedCount,
                            isDone = false
                        )
                    )
                }
            }

            // 5. 통합 다이어리 생성 및 주행 기록 동기화 (최대 25초 타임아웃 방어)
            withTimeoutOrNull(25_000L) {
                val allGolfRounds = runCatching {
                    golfRepository.getAllGolfRoundsList()
                }.getOrDefault(emptyList())

                val diaryEntries = importer.generateIntegratedDiaries(
                    photos = photos,
                    transactions = savedTransactions,
                    golfRounds = allGolfRounds
                )

                val totalEntries = diaryEntries.size
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = if (totalEntries > 0) "하루 이동 경로 및 다이어리 작성 중... (1/${totalEntries}일)" else "다이어리 데이터 정제 중...",
                        syncedTxCount = txCount,
                        syncedPhotoCount = photoCount,
                        syncedGolfCount = golfSyncedCount,
                        isDone = false
                    )
                )

                for ((idx, entry) in diaryEntries.withIndex()) {
                    runCatching {
                        diaryRepository.insertDiaryEntry(entry)
                        insertedEntriesCount++
                    }

                    if ((idx + 1) % 2 == 0 || (idx + 1) == totalEntries) {
                        updateSessionState(
                            sessionId,
                            SyncProgress(
                                isRunning = true,
                                stage = "하루 이동 경로 및 다이어리 작성 중... (${idx + 1}/${totalEntries}일)",
                                syncedTxCount = txCount,
                                syncedPhotoCount = photoCount,
                                syncedGolfCount = golfSyncedCount,
                                isDone = false
                            )
                        )
                    }
                    yield()
                }

                // 생성된 다이어리 이동 경로를 바탕으로 차계부 주행 기록도 백그라운드에서 자동 누적
                if (diaryEntries.isNotEmpty()) {
                    runCatching {
                        vehicleRepository.syncDrivingLogsFromDiary(diaryEntries)
                    }
                }
            }

            val golfSummary = if (golfSyncedCount > 0) " / ⛳ 골프 ${golfSyncedCount}건 등록" else ""
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = false,
                    stage = "동기화 완료: 결제 ${txCount}건, 사진 ${photoCount}장 색인$golfSummary 및 다이어리 ${insertedEntriesCount}일 구축 완료!",
                    syncedTxCount = txCount,
                    syncedPhotoCount = photoCount,
                    syncedGolfCount = golfSyncedCount,
                    isDone = true
                )
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            android.util.Log.e("SyncHistoricalData", "백그라운드 동기화 중 예외 안전 처리", t)
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = false,
                    stage = "동기화 완료: 결제 ${txCount}건, 사진 ${photoCount}장 색인 완료",
                    syncedTxCount = txCount,
                    syncedPhotoCount = photoCount,
                    syncedGolfCount = golfSyncedCount,
                    isDone = true
                )
            )
        } finally {
            withContext(NonCancellable) {
                if (syncSessionId.get() == sessionId) {
                    val current = _syncState.value
                    if (current == null || current.isRunning || !current.isDone) {
                        val golfSummary = if (golfSyncedCount > 0) " / ⛳ 골프 ${golfSyncedCount}건 등록" else ""
                        updateSessionState(
                            sessionId,
                            SyncProgress(
                                isRunning = false,
                                stage = "동기화 완료: 결제 ${txCount}건, 사진 ${photoCount}장 색인$golfSummary 및 다이어리 ${insertedEntriesCount}일 구축 완료!",
                                syncedTxCount = txCount,
                                syncedPhotoCount = photoCount,
                                syncedGolfCount = golfSyncedCount,
                                isDone = true
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun cleanUpMedicalFakeRounds() {
        runCatching {
            val rounds = golfRepository.getAllGolfRoundsList()
            for (r in rounds) {
                val isMedical = r.clubName.contains("외래") ||
                        r.clubName.contains("진료비") ||
                        r.clubName.contains("계산서") ||
                        r.clubName.contains("영수증") ||
                        r.clubName.contains("일반사항") ||
                        r.clubName.contains("안내 CC") ||
                        r.clubName.contains("처방") ||
                        r.clubName.contains("환자") ||
                        r.clubName.contains("약국") ||
                        r.clubName.contains("병원") ||
                        r.memo?.contains("외래 코스") == true ||
                        r.memo?.contains("진료비") == true ||
                        (r.totalScore != null && r.totalScore < 50 && r.holeScores.size < 8)
                if (isMedical) {
                    golfRepository.deleteGolfRound(r.id)
                }
            }
        }
    }
}

