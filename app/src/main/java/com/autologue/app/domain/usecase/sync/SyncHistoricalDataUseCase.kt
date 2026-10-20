package com.autologue.app.domain.usecase.sync

import android.content.Context
import com.autologue.app.data.preferences.SyncCheckpointPreferences
import com.autologue.app.data.preferences.SyncCheckpointState
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import com.autologue.app.service.DataSyncForegroundService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton

data class SyncProgress(
    val isRunning: Boolean,
    val stage: String,
    val syncedTxCount: Int = 0,
    val syncedPhotoCount: Int = 0,
    val syncedGolfCount: Int = 0,
    val isDone: Boolean = false,
    val stepNumber: Int = 1,
    val totalSteps: Int = 4,
    val stepProcessedCount: Int = 0,
    val stepTotalCount: Int = 0,
    val overallProcessedCount: Int = 0,
    val overallTotalCount: Int = 0,
    val progressPercent: Int = if (isDone) 100 else 0
) {
    /**
     * 앱 상단 전역 진행 바 및 알림창에 표시할 정량적 건수·퍼센트 요약 문자열을 생성합니다.
     * 예: "전체 1,240건 중 780건 (63% 완료)"
     */
    fun formatQuantitativeSummary(): String {
        val displayTotal = when {
            overallTotalCount > 0 -> overallTotalCount
            stepTotalCount > 0 -> stepTotalCount
            else -> 0
        }
        val displayCurrent = when {
            overallTotalCount > 0 -> overallProcessedCount.coerceIn(0, overallTotalCount)
            stepTotalCount > 0 -> stepProcessedCount.coerceIn(0, stepTotalCount)
            else -> 0
        }
        val displayPercent = when {
            isDone -> 100
            progressPercent in 1..99 -> progressPercent
            displayTotal > 0 -> ((displayCurrent * 100.0) / displayTotal).toInt().coerceIn(1, 99)
            else -> 0
        }
        return when {
            isDone && displayTotal > 0 ->
                String.format(Locale.KOREA, "전체 %,d건 중 %,d건 (100%% 완료)", displayTotal, displayTotal)
            isDone ->
                "전체 100% 완료"
            displayTotal > 0 ->
                String.format(Locale.KOREA, "전체 %,d건 중 %,d건 (%d%% 완료)", displayTotal, displayCurrent, displayPercent)
            else ->
                String.format(Locale.KOREA, "대상 건수 집계 중 (%d%%)", displayPercent)
        }
    }
}

/**
 * 화면(ViewModel / NavBackStackEntry) 생명주기에 종속되지 않고
 * 앱 전역 백그라운드(Application Scope + DataSyncForegroundService)에서 독립적으로 동작하는 통합 동기화 엔진.
 * 앱을 닫거나 화면을 꺼도 포그라운드 서비스와 증분 체크포인트를 통해 중단 없이 끝까지 완주합니다.
 */
@Singleton
class SyncHistoricalDataUseCase @Inject constructor(
    private val importer: HistoricalDataImporter,
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val transactionRepository: TransactionRepository,
    private val diaryRepository: DiaryRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfRepository: com.autologue.app.domain.repository.GolfRepository,
    private val autoProcessGolfMediaUseCase: com.autologue.app.domain.usecase.golf.AutoProcessGolfMediaUseCase,
    private val syncCheckpointPreferences: SyncCheckpointPreferences
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

    val checkpointState: StateFlow<SyncCheckpointState> = syncCheckpointPreferences.state

    private fun updateSessionState(sessionId: Long, progress: SyncProgress) {
        if (syncSessionId.get() == sessionId) {
            _syncState.value = progress
        }
    }

    private fun computeProgressPercent(
        processed: Int,
        total: Int,
        minFloor: Int = 1,
        maxCap: Int = 99
    ): Int {
        if (total <= 0) return minFloor.coerceIn(0, maxCap)
        val raw = ((processed.coerceAtLeast(0).toDouble() / total.toDouble()) * 100.0).toInt()
        return raw.coerceIn(minFloor, maxCap)
    }

    /**
     * 개별 탭(스마트 골프 갤러리 스캔, 스마트 가계부 문자 동기화 등)에서 시작된 백그라운드 수집 작업도
     * 앱 상단 전역 진행률 바와 포그라운드 서비스에 통합 표출되도록 상태를 발행합니다.
     */
    fun publishExternalSyncProgress(context: Context, progress: SyncProgress) {
        val appCtx = context.applicationContext ?: context
        if (activeSyncJob?.isActive == true) {
            // 이미 전체 통합 파이프라인이 가동 중이면 전체 파이프라인 진행률을 우선 유지
            return
        }
        _syncState.value = progress
        if (progress.isRunning && !progress.isDone) {
            DataSyncForegroundService.startOrUpdate(appCtx)
        }
    }

    /**
     * 완료된 상단 배너 또는 수동 동기화 다이얼로그를 닫습니다.
     */
    fun dismissSyncBanner() {
        _isManualSyncDialogVisible.value = false
        val current = _syncState.value
        if (current != null && (current.isDone || !current.isRunning)) {
            _syncState.value = null
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
            val lastSyncMillis = syncCheckpointPreferences.getLastSyncCompletedAtMillis()
            val initialStage = if (lastSyncMillis > 0L) {
                "[1/4단계] 마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncMillis)}) 이후 신규 데이터 집계 중..."
            } else {
                "[1/4단계] 일상 기록(결제 문자·사진·골프) 대상 건수 집계 중..."
            }
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = initialStage,
                    isDone = false,
                    stepNumber = 1,
                    totalSteps = 4,
                    progressPercent = 1
                )
            )
            DataSyncForegroundService.startOrUpdate(appCtx)
            activeSyncJob = backgroundScope.launch {
                executeSyncPipeline(sessionId, appCtx, daysBack = daysBack, isManual = false)
            }
        }
    }

    /**
     * 사용자가 명시적으로 요청한 기간별 수동 동기화를 백그라운드에서 시작합니다.
     * 포그라운드 서비스와 연동되어 앱을 닫거나 화면을 꺼도 백그라운드에서 끝까지 진행됩니다.
     */
    fun startManualSync(context: Context, daysBack: Int? = 0, runInBackground: Boolean = true) {
        val appCtx = context.applicationContext ?: context
        synchronized(stateLock) {
            val previousJob = activeSyncJob
            val sessionId = syncSessionId.incrementAndGet()
            _isManualSyncDialogVisible.value = !runInBackground
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "[1/4단계] 백그라운드 데이터 수집·분석 대상 건수 확인 중...",
                    isDone = false,
                    stepNumber = 1,
                    totalSteps = 4,
                    progressPercent = 1
                )
            )
            DataSyncForegroundService.startOrUpdate(appCtx)
            activeSyncJob = backgroundScope.launch {
                runCatching { previousJob?.cancel() }
                executeSyncPipeline(sessionId, appCtx, daysBack = daysBack, isManual = true)
            }
        }
    }

    /**
     * 수동 동기화 다이얼로그를 닫습니다.
     */
    fun dismissManualSyncDialog() {
        dismissSyncBanner()
    }

    /**
     * 기존 Flow 수집 방식 호출부(LifestyleViewModel 등)와의 완벽한 하위 호환을 제공합니다.
     */
    operator fun invoke(context: Context, daysBack: Int? = null): Flow<SyncProgress> = flow {
        val appCtx = context.applicationContext ?: context
        val shouldStart = synchronized(stateLock) {
            activeSyncJob?.isActive != true
        }
        if (shouldStart) {
            startAutoSync(appCtx, daysBack = daysBack ?: 7, force = true)
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

    private suspend fun ensureBaselineCheckpointIfNeeded() {
        if (syncCheckpointPreferences.getLastSyncCompletedAtMillis() > 0L) return
        runCatching {
            val zone = ZoneId.systemDefault()
            val latestTxMillis = transactionRepository.getAllTransactionsFlow().first()
                .maxOfOrNull { it.timestamp.atZone(zone).toInstant().toEpochMilli() } ?: 0L
            val latestDiaryMillis = diaryRepository.getDiaryEntriesFlow().first()
                .maxOfOrNull { it.date.atZone(zone).toInstant().toEpochMilli() } ?: 0L
            val latestGolfMillis = golfRepository.getAllGolfRoundsList()
                .maxOfOrNull { (it.startTime ?: it.roundDate).atZone(zone).toInstant().toEpochMilli() } ?: 0L
            val maxExistingMillis = maxOf(latestTxMillis, latestDiaryMillis, latestGolfMillis)
            if (maxExistingMillis > 0L) {
                syncCheckpointPreferences.ensureBaselineCheckpointFromExistingData(maxExistingMillis)
            }
        }
    }

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
        var grandTotalCount = 0
        var grandProcessedCount = 0
        val syncStartedAtMillis = System.currentTimeMillis()

        try {
            ensureBaselineCheckpointIfNeeded()

            val needsLegacyCleanup = !syncCheckpointPreferences.isLegacyCleanupDone()
            val lastSyncCompletedMillis = syncCheckpointPreferences.getLastSyncCompletedAtMillis()
            val useIncremental = (!isManual && lastSyncCompletedMillis > 0L) ||
                (isManual && daysBack == 0 && lastSyncCompletedMillis > 0L)
            val useIncrementalSms = useIncremental && !needsLegacyCleanup

            val periodDesc = when {
                useIncremental -> "마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncCompletedMillis)}) 이후"
                daysBack != null && daysBack > 0 -> "최근 ${daysBack}일"
                else -> "과거 전체"
            }

            // 0. 기존 중복 데이터 검사 및 가짜 골프 라운드 청소 (최초 1회 또는 수동 기간 전체 동기화 시에만 실행)
            if (needsLegacyCleanup || (isManual && !useIncremental)) {
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[준비] 기존 중복 데이터 검사 및 정리 중...",
                        syncedTxCount = 0,
                        syncedPhotoCount = 0,
                        syncedGolfCount = 0,
                        isDone = false,
                        stepNumber = 1,
                        totalSteps = 4,
                        progressPercent = 2
                    )
                )
                withTimeoutOrNull(10_000L) {
                    runCatching { transactionRepository.cleanDuplicates() }
                    runCatching { diaryRepository.cleanDuplicates() }
                    runCatching { vehicleRepository.cleanDuplicates() }
                    cleanUpMedicalFakeRounds()
                    syncCheckpointPreferences.markLegacyCleanupDone()
                }
            }

            // 1. 전체 수집 대상(결제 문자 + 갤러리 일반 사진 + 골프 OCR 후보) 고속 사전 스캔 및 전체 모수(N건) 산출
            val sinceSmsMillis = if (useIncrementalSms) syncCheckpointPreferences.getLastSmsSyncCompletedAtMillis() else null
            val smsDaysBack = when {
                useIncrementalSms -> null
                daysBack == null -> null
                else -> maxOf(daysBack, 365)
            }
            val smsLimit = when {
                useIncrementalSms -> 1000
                daysBack == null -> 10000
                else -> 5000
            }

            val sincePhotoMillis = if (useIncremental) syncCheckpointPreferences.getLastPhotoSyncCompletedAtMillis() else null
            val photoLimit = when {
                useIncremental -> 200
                daysBack == null -> 1500
                daysBack >= 60 -> 600
                else -> 300
            }

            val sinceGolfMillis = if (useIncremental) syncCheckpointPreferences.getLastGolfSyncCompletedAtMillis() else null
            val candidateLimit = when {
                useIncremental -> 150
                !isManual && daysBack != null && daysBack <= 7 -> 60
                daysBack == null -> 600
                else -> 300
            }
            val candidateDaysBack = if (useIncremental) null else if (!isManual && daysBack != null && daysBack <= 7) 14 else daysBack

            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "[1/4단계] $periodDesc 문자·사진·골프 분석 대상 건수 집계 중...",
                    syncedTxCount = 0,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = false,
                    stepNumber = 1,
                    totalSteps = 4,
                    progressPercent = 4
                )
            )

            val (transactions, initialPhotoTotal, golfCandidates) = coroutineScope {
                val smsDef = async(Dispatchers.IO) {
                    runCatching {
                        importer.scanHistoricalSms(
                            context = context,
                            daysBack = smsDaysBack,
                            limit = smsLimit,
                            sinceTimestampMillis = sinceSmsMillis
                        )
                    }.getOrDefault(emptyList())
                }
                val photoCountDef = async(Dispatchers.IO) {
                    runCatching {
                        importer.countHistoricalPhotoCandidates(
                            context = context,
                            daysBack = if (useIncremental) null else daysBack,
                            limit = photoLimit,
                            sinceTimestampMillis = sincePhotoMillis
                        )
                    }.getOrDefault(0)
                }
                val golfDef = async(Dispatchers.IO) {
                    runCatching {
                        importer.scanHistoricalGolfCandidates(
                            context = context,
                            daysBack = candidateDaysBack,
                            limit = candidateLimit,
                            sinceTimestampMillis = sinceGolfMillis
                        )
                    }.getOrDefault(emptyList())
                }
                Triple(smsDef.await(), photoCountDef.await(), golfDef.await())
            }

            val txTotal = transactions.size
            var photoTotal = initialPhotoTotal
            var golfTotal = golfCandidates.size
            val rawCandidateSum = txTotal + photoTotal + golfTotal
            val estimatedDiaryDays = if (rawCandidateSum > 0) (rawCandidateSum / 10).coerceIn(1, 90) else 0
            grandTotalCount = (rawCandidateSum + estimatedDiaryDays).coerceAtLeast(1)

            // [Stage 1/4] 문자 스캔 결과 DB 반영 및 주유 내역 동기화
            val savedTransactions = mutableListOf<com.autologue.app.domain.model.Transaction>()
            if (txTotal > 0) {
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[1/4단계] $periodDesc 결제 내역 반영 시작 (0/${txTotal}건)",
                        syncedTxCount = 0,
                        syncedPhotoCount = 0,
                        syncedGolfCount = 0,
                        isDone = false,
                        stepNumber = 1,
                        totalSteps = 4,
                        stepProcessedCount = 0,
                        stepTotalCount = txTotal,
                        overallProcessedCount = 0,
                        overallTotalCount = grandTotalCount,
                        progressPercent = computeProgressPercent(0, grandTotalCount, minFloor = 5)
                    )
                )
            }

            for ((idx, tx) in transactions.withIndex()) {
                runCatching {
                    val txId = processTransactionUseCase(tx)
                    if (txId > 0L) {
                        savedTransactions.add(tx)
                        txCount++
                    }
                }
                val stepDone = idx + 1
                grandProcessedCount = stepDone
                if (stepDone == 1 || stepDone % 15 == 0 || stepDone == txTotal) {
                    updateSessionState(
                        sessionId,
                        SyncProgress(
                            isRunning = true,
                            stage = "[1/4단계] $periodDesc 결제 내역 반영 중 (${stepDone}/${txTotal}건 · 신규 ${txCount}건)",
                            syncedTxCount = txCount,
                            syncedPhotoCount = 0,
                            syncedGolfCount = 0,
                            isDone = false,
                            stepNumber = 1,
                            totalSteps = 4,
                            stepProcessedCount = stepDone,
                            stepTotalCount = txTotal,
                            overallProcessedCount = grandProcessedCount,
                            overallTotalCount = grandTotalCount,
                            progressPercent = computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 5)
                        )
                    )
                }
            }

            if (savedTransactions.isNotEmpty() || !useIncremental) {
                runCatching { transactionRepository.cleanDuplicates() }
                runCatching {
                    val targetTxs = if (useIncremental) {
                        savedTransactions
                    } else {
                        transactionRepository.getAllTransactionsFlow().first()
                    }
                    if (targetTxs.isNotEmpty()) {
                        vehicleRepository.syncRefuelingFromTransactions(targetTxs)
                    }
                }
            }
            syncCheckpointPreferences.recordSmsSyncCompleted(syncStartedAtMillis, updateGlobalCheckpoint = false)

            // [Stage 2/4] 갤러리 사진 및 위치 스캔 (6채널 병렬 + 개별 1.8초 타임아웃 + 25장 주기 캐시 플러시)
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "[2/4단계] $periodDesc 갤러리 사진·위치 병렬 분석 중 (0/${photoTotal}장)",
                    syncedTxCount = txCount,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = false,
                    stepNumber = 2,
                    totalSteps = 4,
                    stepProcessedCount = 0,
                    stepTotalCount = photoTotal,
                    overallProcessedCount = txTotal,
                    overallTotalCount = grandTotalCount,
                    progressPercent = computeProgressPercent(txTotal, grandTotalCount, minFloor = 12)
                )
            )

            val photos = importer.scanHistoricalPhotos(
                context = context,
                daysBack = if (useIncremental) null else daysBack,
                limit = photoLimit,
                sinceTimestampMillis = sincePhotoMillis
            ) { cur, tot ->
                photoTotal = tot
                grandTotalCount = (txTotal + photoTotal + golfTotal + estimatedDiaryDays).coerceAtLeast(1)
                grandProcessedCount = txTotal + cur
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[2/4단계] $periodDesc 갤러리 사진·위치 분석 중 (${cur}/${tot}장)",
                        syncedTxCount = txCount,
                        syncedPhotoCount = cur,
                        syncedGolfCount = 0,
                        isDone = false,
                        stepNumber = 2,
                        totalSteps = 4,
                        stepProcessedCount = cur,
                        stepTotalCount = tot,
                        overallProcessedCount = grandProcessedCount,
                        overallTotalCount = grandTotalCount,
                        progressPercent = computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 12)
                    )
                )
            }
            photoCount = photos.size
            photoTotal = photoCount
            grandTotalCount = (txTotal + photoTotal + golfTotal + estimatedDiaryDays).coerceAtLeast(1)
            grandProcessedCount = txTotal + photoCount
            syncCheckpointPreferences.recordPhotoSyncCompleted(syncStartedAtMillis, updateGlobalCheckpoint = false)

            // [Stage 3/4] 캡처된 스크린샷 및 갤러리 사진에서 라커룸 전표 및 스코어카드 자동 감지 (3채널 병렬 지문 OCR)
            if (golfCandidates.isNotEmpty()) {
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[3/4단계] $periodDesc 갤러리 골프 OCR 분석 시작 (0/${golfTotal}장)",
                        syncedTxCount = txCount,
                        syncedPhotoCount = photoCount,
                        syncedGolfCount = 0,
                        isDone = false,
                        stepNumber = 3,
                        totalSteps = 4,
                        stepProcessedCount = 0,
                        stepTotalCount = golfTotal,
                        overallProcessedCount = grandProcessedCount,
                        overallTotalCount = grandTotalCount,
                        progressPercent = computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 35)
                    )
                )

                golfSyncedCount = runCatching {
                    autoProcessGolfMediaUseCase.processBatchCandidates(golfCandidates) { current, total, foundCount ->
                        golfTotal = total
                        grandTotalCount = (txTotal + photoTotal + golfTotal + estimatedDiaryDays).coerceAtLeast(1)
                        grandProcessedCount = txTotal + photoTotal + current
                        val foundMsg = if (foundCount > 0) " · 골프 ${foundCount}건 발견" else ""
                        updateSessionState(
                            sessionId,
                            SyncProgress(
                                isRunning = true,
                                stage = "[3/4단계] 갤러리 골프 OCR 분석 중 (${current}/${total}장$foundMsg)",
                                syncedTxCount = txCount,
                                syncedPhotoCount = photoCount,
                                syncedGolfCount = foundCount,
                                isDone = false,
                                stepNumber = 3,
                                totalSteps = 4,
                                stepProcessedCount = current,
                                stepTotalCount = total,
                                overallProcessedCount = grandProcessedCount,
                                overallTotalCount = grandTotalCount,
                                progressPercent = computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 35)
                            )
                        )
                    }
                }.onFailure { t ->
                    if (t is CancellationException) throw t
                    android.util.Log.e("SyncHistoricalData", "골프 미디어 일괄 OCR 분석 중 오류", t)
                }.getOrDefault(0)
            }
            grandProcessedCount = txTotal + photoTotal + golfTotal
            syncCheckpointPreferences.recordGolfSyncCompleted(syncStartedAtMillis, updateGlobalCheckpoint = false)

            // [Stage 4/4] 통합 다이어리 생성 및 주행 기록 동기화
            val allGolfRounds = runCatching {
                golfRepository.getAllGolfRoundsList()
            }.getOrDefault(emptyList())

            val allDbTxs = runCatching {
                transactionRepository.getAllTransactionsFlow().first()
            }.getOrDefault(savedTransactions)

            val diaryEntries = if (useIncremental) {
                val affectedDates = mutableSetOf<LocalDate>()
                photos.forEach { affectedDates.add(it.time.toLocalDate()) }
                savedTransactions.forEach { affectedDates.add(it.timestamp.toLocalDate()) }
                if (golfSyncedCount > 0 && sinceGolfMillis != null && sinceGolfMillis > 0L) {
                    val sinceGolfDate = Instant.ofEpochMilli(sinceGolfMillis)
                        .atZone(ZoneId.systemDefault())
                        .toLocalDate()
                    allGolfRounds
                        .filter { !it.roundDate.toLocalDate().isBefore(sinceGolfDate) }
                        .forEach { affectedDates.add(it.roundDate.toLocalDate()) }
                }

                if (affectedDates.isEmpty()) {
                    emptyList()
                } else {
                    val minAffectedStartMillis = affectedDates.minOf {
                        it.atStartOfDay(ZoneId.systemDefault()).toInstant().toEpochMilli()
                    }
                    val fullDayPhotos = (photos + importer.scanHistoricalPhotos(
                        context = context,
                        daysBack = null,
                        limit = 200,
                        sinceTimestampMillis = (minAffectedStartMillis - 1L).coerceAtLeast(1L)
                    )).distinctBy { it.uri }

                    importer.generateIntegratedDiaries(
                        photos = fullDayPhotos,
                        transactions = allDbTxs.ifEmpty { savedTransactions },
                        golfRounds = allGolfRounds,
                        targetDates = affectedDates
                    )
                }
            } else {
                importer.generateIntegratedDiaries(
                    photos = photos,
                    transactions = allDbTxs.ifEmpty { savedTransactions },
                    golfRounds = allGolfRounds,
                    targetDates = null
                )
            }

            val totalEntries = diaryEntries.size
            val baseProcessedBeforeDiary = txTotal + photoTotal + golfTotal
            grandTotalCount = (baseProcessedBeforeDiary + totalEntries).coerceAtLeast(1)

            if (totalEntries > 0) {
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[4/4단계] 하루 이동 경로 및 다이어리 작성 중 (0/${totalEntries}일)",
                        syncedTxCount = txCount,
                        syncedPhotoCount = photoCount,
                        syncedGolfCount = golfSyncedCount,
                        isDone = false,
                        stepNumber = 4,
                        totalSteps = 4,
                        stepProcessedCount = 0,
                        stepTotalCount = totalEntries,
                        overallProcessedCount = baseProcessedBeforeDiary,
                        overallTotalCount = grandTotalCount,
                        progressPercent = computeProgressPercent(baseProcessedBeforeDiary, grandTotalCount, minFloor = 80)
                    )
                )
            }

            for ((idx, entry) in diaryEntries.withIndex()) {
                runCatching {
                    diaryRepository.insertDiaryEntry(entry)
                    insertedEntriesCount++
                }

                val stepDone = idx + 1
                grandProcessedCount = baseProcessedBeforeDiary + stepDone
                if (stepDone == 1 || stepDone % 3 == 0 || stepDone == totalEntries) {
                    updateSessionState(
                        sessionId,
                        SyncProgress(
                            isRunning = true,
                            stage = "[4/4단계] 하루 이동 경로 및 다이어리 작성 중 (${stepDone}/${totalEntries}일)",
                            syncedTxCount = txCount,
                            syncedPhotoCount = photoCount,
                            syncedGolfCount = golfSyncedCount,
                            isDone = false,
                            stepNumber = 4,
                            totalSteps = 4,
                            stepProcessedCount = stepDone,
                            stepTotalCount = totalEntries,
                            overallProcessedCount = grandProcessedCount,
                            overallTotalCount = grandTotalCount,
                            progressPercent = computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 80)
                        )
                    )
                }
                yield()
            }

            if (diaryEntries.isNotEmpty()) {
                runCatching {
                    vehicleRepository.syncDrivingLogsFromDiary(diaryEntries)
                }
            }

            // 5. 데이터 수집 및 분석 완료 시점 영구 기록
            val completedAtMillis = System.currentTimeMillis()
            syncCheckpointPreferences.recordSyncCompleted(completedAtMillis)

            val golfSummary = if (golfSyncedCount > 0) " · 골프 ${golfSyncedCount}건 등록" else ""
            val doneMessage = if (useIncremental && txCount == 0 && photoCount == 0 && golfSyncedCount == 0) {
                "마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncCompletedMillis)}) 이후 새로 추가된 데이터가 없습니다."
            } else {
                "$periodDesc 분석 완료: 결제 ${txCount}건 · 사진 ${photoCount}장$golfSummary · 다이어리 ${insertedEntriesCount}일 갱신"
            }

            val finalTotal = if (rawCandidateSum + totalEntries > 0) rawCandidateSum + totalEntries else 0
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = false,
                    stage = doneMessage,
                    syncedTxCount = txCount,
                    syncedPhotoCount = photoCount,
                    syncedGolfCount = golfSyncedCount,
                    isDone = true,
                    stepNumber = 4,
                    totalSteps = 4,
                    stepProcessedCount = totalEntries,
                    stepTotalCount = totalEntries,
                    overallProcessedCount = finalTotal,
                    overallTotalCount = finalTotal,
                    progressPercent = 100
                )
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            android.util.Log.e("SyncHistoricalData", "백그라운드 동기화 중 예외 안전 처리", t)
            syncCheckpointPreferences.recordSyncCompleted(System.currentTimeMillis())
            val finalTotal = grandTotalCount.coerceAtLeast(grandProcessedCount)
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = false,
                    stage = "동기화 완료: 결제 ${txCount}건 · 사진 ${photoCount}장 색인 완료",
                    syncedTxCount = txCount,
                    syncedPhotoCount = photoCount,
                    syncedGolfCount = golfSyncedCount,
                    isDone = true,
                    stepNumber = 4,
                    totalSteps = 4,
                    overallProcessedCount = finalTotal,
                    overallTotalCount = finalTotal,
                    progressPercent = 100
                )
            )
        } finally {
            withContext(NonCancellable) {
                if (syncSessionId.get() == sessionId) {
                    val current = _syncState.value
                    if (current == null || current.isRunning || !current.isDone) {
                        val golfSummary = if (golfSyncedCount > 0) " · 골프 ${golfSyncedCount}건 등록" else ""
                        val finalTotal = grandTotalCount.coerceAtLeast(grandProcessedCount)
                        updateSessionState(
                            sessionId,
                            SyncProgress(
                                isRunning = false,
                                stage = "동기화 완료: 결제 ${txCount}건 · 사진 ${photoCount}장 색인$golfSummary · 다이어리 ${insertedEntriesCount}일 구축 완료",
                                syncedTxCount = txCount,
                                syncedPhotoCount = photoCount,
                                syncedGolfCount = golfSyncedCount,
                                isDone = true,
                                stepNumber = 4,
                                totalSteps = 4,
                                overallProcessedCount = finalTotal,
                                overallTotalCount = finalTotal,
                                progressPercent = 100
                            )
                        )
                    }
                }
            }
        }
    }

    private suspend fun cleanUpMedicalFakeRounds() {
        runCatching {
            val today = java.time.LocalDate.now()
            val rounds = golfRepository.getAllGolfRoundsList()
            for (r in rounds) {
                val rDay = (r.startTime ?: r.roundDate).toLocalDate()
                val isFakeNineCc = r.clubName.contains("나인CC", ignoreCase = true) ||
                        r.clubName.contains("나인 CC", ignoreCase = true) ||
                        r.clubName == "나인" ||
                        r.clubName.contains("온라인CC", ignoreCase = true) ||
                        r.clubName.contains("확인CC", ignoreCase = true)
                val isFutureFalseOcr = rDay.isAfter(today) && (
                        r.totalScore != null ||
                        r.holeScores.isNotEmpty() ||
                        !r.scorecardPhotoUri.isNullOrBlank() ||
                        r.matchingPhotoUris.isNotEmpty() ||
                        isFakeNineCc ||
                        rDay == java.time.LocalDate.of(2026, 10, 24)
                )
                val isWebReviewFake = r.clubName.contains("관리 코스") ||
                        r.memo?.contains("관리 코스") == true ||
                        r.memo?.contains("코스: 관리") == true ||
                        r.companions.any { it.contains("캐디") || it.contains("매니저") } ||
                        r.memo?.contains("락커: 88") == true ||
                        r.memo?.contains("락커: 72") == true ||
                        r.memo?.contains("락커: 52") == true ||
                        r.memo?.contains("락커: 27") == true ||
                        r.clubName.contains("이용후기") ||
                        r.clubName.contains("XGOLF", ignoreCase = true)
                val isMedicalOrFake = isFakeNineCc ||
                        isFutureFalseOcr ||
                        isWebReviewFake ||
                        r.clubName.contains("외래") ||
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
                if (isMedicalOrFake) {
                    golfRepository.deleteGolfRound(r.id)
                    val baseClub = com.autologue.app.domain.model.splitClubAndCourse(r.clubName).first.ifBlank { r.clubName }
                    val diaries = runCatching { diaryRepository.getDiaryEntriesByDateRange(rDay, rDay).first() }.getOrDefault(emptyList())
                    for (diary in diaries) {
                        val cleanSteps = diary.routeSteps.filter { step ->
                            !(step.stepType == com.autologue.app.domain.model.RouteStepType.GOLF && (step.title.contains(baseClub) || step.locationName?.contains(baseClub) == true)) &&
                            !(step.stepType == com.autologue.app.domain.model.RouteStepType.DRIVING && (step.title.contains(baseClub) || step.locationName?.contains(baseClub) == true || step.description?.contains("골프장 왕복 주행") == true))
                        }
                        if (cleanSteps.isEmpty() && diary.photoUris.isEmpty()) {
                            diaryRepository.deleteDiaryEntry(diary.id)
                        } else if (cleanSteps.size != diary.routeSteps.size || diary.hasGolfRound) {
                            val cleanTitle = if (diary.title.contains(baseClub) || diary.title.contains("라운딩")) {
                                "${rDay.monthValue}월 ${rDay.dayOfMonth}일의 다이어리"
                            } else diary.title
                            diaryRepository.updateDiaryEntry(
                                diary.copy(
                                    title = cleanTitle,
                                    hasGolfRound = cleanSteps.any { it.stepType == com.autologue.app.domain.model.RouteStepType.GOLF },
                                    routeSteps = cleanSteps,
                                    tags = diary.tags.filter { !it.contains("골프") && !it.contains(baseClub) && !it.contains("캐디") }
                                )
                            )
                        }
                    }
                }
            }
            diaryRepository.cleanDuplicates()
            val remainingRounds = golfRepository.getAllGolfRoundsList()
            vehicleRepository.syncAndCleanWithGolfRounds(remainingRounds)
        }
    }
}
