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
    val isDone: Boolean = false,
    val stepNumber: Int = 1,
    val totalSteps: Int = 3,
    val stepProcessedCount: Int = 0,
    val stepTotalCount: Int = 0,
    val overallProcessedCount: Int = 0,
    val overallTotalCount: Int = 0,
    val progressPercent: Int = if (isDone) 100 else 0
) {
    /**
     * 앱 상단 전역 진행 바 및 알림창에 표시할 정량적 건수·퍼센트 요약 문자열을 생성합니다.
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
 * 화면 생명주기에 종속되지 않고 앱 전역 백그라운드(Application Scope + DataSyncForegroundService)에서
 * 독립적으로 동작하는 3단계 통합 동기화 엔진 (결제 문자 -> 갤러리 사진/위치 -> 하루 이동 경로 및 일상 다이어리).
 */
@Singleton
class SyncHistoricalDataUseCase @Inject constructor(
    private val importer: HistoricalDataImporter,
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val transactionRepository: TransactionRepository,
    private val diaryRepository: DiaryRepository,
    private val vehicleRepository: VehicleRepository,
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
     * 개별 탭(스마트 가계부 문자 동기화 등)에서 시작된 백그라운드 수집 작업도
     * 앱 상단 전역 진행률 바와 포그라운드 서비스에 통합 표출되도록 상태를 발행합니다.
     */
    fun publishExternalSyncProgress(context: Context, progress: SyncProgress) {
        val appCtx = context.applicationContext ?: context
        if (activeSyncJob?.isActive == true) {
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
     * 앱 세션 최초 진입 또는 권한 승인 직후 백그라운드 자동 동기화를 시작합니다.
     */
    fun startAutoSync(context: Context, daysBack: Int = 7, force: Boolean = false) {
        val appCtx = context.applicationContext ?: context
        synchronized(stateLock) {
            if (activeSyncJob?.isActive == true && !force) {
                return
            }
            if (hasAutoSyncedInSession && !force) {
                return
            }
            val previousJob = activeSyncJob
            hasAutoSyncedInSession = true
            val sessionId = syncSessionId.incrementAndGet()
            val lastSyncMillis = syncCheckpointPreferences.getLastSyncCompletedAtMillis()
            val initialStage = if (lastSyncMillis > 0L) {
                "[1/3단계] 마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncMillis)}) 이후 신규 데이터 집계 중..."
            } else {
                "[1/3단계] 일상 기록(결제 문자·갤러리 사진) 대상 건수 집계 중..."
            }
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = initialStage,
                    isDone = false,
                    stepNumber = 1,
                    totalSteps = 3,
                    progressPercent = 2
                )
            )
            DataSyncForegroundService.startOrUpdate(appCtx)
            activeSyncJob = backgroundScope.launch {
                runCatching { previousJob?.cancel() }
                executeSyncPipeline(sessionId, appCtx, daysBack = daysBack, isManual = false)
            }
        }
    }

    /**
     * 사용자가 명시적으로 요청한 기간별 수동 동기화를 백그라운드에서 시작합니다.
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
                    stage = "[1/3단계] 백그라운드 데이터 수집·분석 대상 건수 확인 중...",
                    isDone = false,
                    stepNumber = 1,
                    totalSteps = 3,
                    progressPercent = 2
                )
            )
            DataSyncForegroundService.startOrUpdate(appCtx)
            activeSyncJob = backgroundScope.launch {
                runCatching { previousJob?.cancel() }
                executeSyncPipeline(sessionId, appCtx, daysBack = daysBack, isManual = true)
            }
        }
    }

    fun dismissManualSyncDialog() {
        dismissSyncBanner()
    }

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
        withTimeoutOrNull(2000L) {
            runCatching {
                val zone = ZoneId.systemDefault()
                val latestTxMillis = transactionRepository.getAllTransactionsFlow().first()
                    .maxOfOrNull { it.timestamp.atZone(zone).toInstant().toEpochMilli() } ?: 0L
                val latestDiaryMillis = diaryRepository.getDiaryEntriesFlow().first()
                    .maxOfOrNull { it.date.atZone(zone).toInstant().toEpochMilli() } ?: 0L
                val maxExistingMillis = maxOf(latestTxMillis, latestDiaryMillis)
                if (maxExistingMillis > 0L) {
                    syncCheckpointPreferences.ensureBaselineCheckpointFromExistingData(maxExistingMillis)
                }
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
                daysBack == 0 -> "최근 30일"
                else -> "과거 전체"
            }

            // 0. 기존 중복 데이터 및 레거시 기록 정리 (최초 1회 또는 수동 전체 동기화 시)
            if (needsLegacyCleanup || (isManual && !useIncremental)) {
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[준비] 기존 중복 데이터 검사 및 정리 중...",
                        syncedTxCount = 0,
                        syncedPhotoCount = 0,
                        isDone = false,
                        stepNumber = 1,
                        totalSteps = 3,
                        progressPercent = 3
                    )
                )
                withTimeoutOrNull(6_000L) {
                    runCatching { transactionRepository.cleanDuplicates() }
                    runCatching { diaryRepository.cleanDuplicates() }
                    runCatching { vehicleRepository.cleanDuplicates() }
                    syncCheckpointPreferences.markLegacyCleanupDone()
                }
            }

            // 1. 수집 대상(결제 문자 + 갤러리 사진) 고속 사전 스캔 및 실시간 진행률 갱신
            val sinceSmsMillis = if (useIncrementalSms) syncCheckpointPreferences.getLastSmsSyncCompletedAtMillis() else null
            val smsDaysBack = when {
                useIncrementalSms -> null
                !isManual -> if (lastSyncCompletedMillis <= 0L) 90 else maxOf(daysBack ?: 7, 14)
                daysBack == null -> null
                daysBack <= 0 -> 30
                else -> daysBack
            }
            val smsLimit = when {
                useIncrementalSms -> 1000
                daysBack == null -> 10000
                else -> 5000
            }

            val sincePhotoMillis = if (useIncremental) syncCheckpointPreferences.getLastPhotoSyncCompletedAtMillis() else null
            val effectivePhotoDaysBack = when {
                useIncremental -> null
                daysBack != null && daysBack == 0 -> 30
                else -> daysBack
            }
            val photoLimit = when {
                useIncremental -> 200
                effectivePhotoDaysBack == null -> 1500
                effectivePhotoDaysBack >= 60 -> 600
                else -> 300
            }

            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "[1/3단계] $periodDesc 결제 문자·사진 대상 건수 집계 중...",
                    syncedTxCount = 0,
                    syncedPhotoCount = 0,
                    isDone = false,
                    stepNumber = 1,
                    totalSteps = 3,
                    progressPercent = 5
                )
            )

            val (transactions, initialPhotoTotal) = coroutineScope {
                val smsDef = async(Dispatchers.IO) {
                    withTimeoutOrNull(15_000L) {
                        try {
                            importer.scanHistoricalSms(
                                context = context,
                                daysBack = smsDaysBack,
                                limit = smsLimit,
                                sinceTimestampMillis = sinceSmsMillis,
                                onProgress = { scanned, hint ->
                                    val preScanPct = (5 + (scanned / 150)).coerceIn(6, 16)
                                    updateSessionState(
                                        sessionId,
                                        SyncProgress(
                                            isRunning = true,
                                            stage = "[1/3단계] $hint (${scanned}건 확인됨)",
                                            syncedTxCount = 0,
                                            syncedPhotoCount = 0,
                                            isDone = false,
                                            stepNumber = 1,
                                            totalSteps = 3,
                                            progressPercent = preScanPct
                                        )
                                    )
                                }
                            )
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            android.util.Log.e("SyncHistoricalData", "문자 사전 스캔 예외 안전 복구", t)
                            emptyList()
                        }
                    } ?: emptyList()
                }
                val photoCountDef = async(Dispatchers.IO) {
                    withTimeoutOrNull(6_000L) {
                        try {
                            importer.countHistoricalPhotoCandidates(
                                context = context,
                                daysBack = effectivePhotoDaysBack,
                                limit = photoLimit,
                                sinceTimestampMillis = sincePhotoMillis
                            )
                        } catch (t: Throwable) {
                            if (t is CancellationException) throw t
                            0
                        }
                    } ?: 0
                }
                Pair(smsDef.await(), photoCountDef.await())
            }

            val txTotal = transactions.size
            var photoTotal = initialPhotoTotal
            val rawCandidateSum = txTotal + photoTotal
            val estimatedDiaryDays = if (rawCandidateSum > 0) (rawCandidateSum / 8).coerceIn(1, 90) else 0
            grandTotalCount = (rawCandidateSum + estimatedDiaryDays).coerceAtLeast(1)

            // [Stage 1/3] 문자 스캔 결과 DB 반영 및 주유 내역 동기화
            val savedTransactions = mutableListOf<com.autologue.app.domain.model.Transaction>()
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = if (txTotal > 0) {
                        "[1/3단계] $periodDesc 결제 내역 반영 시작 (0/${txTotal}건)"
                    } else {
                        "[1/3단계] $periodDesc 신규 결제 문자 확인 완료"
                    },
                    syncedTxCount = 0,
                    syncedPhotoCount = 0,
                    isDone = false,
                    stepNumber = 1,
                    totalSteps = 3,
                    stepProcessedCount = 0,
                    stepTotalCount = txTotal,
                    overallProcessedCount = 0,
                    overallTotalCount = grandTotalCount,
                    progressPercent = computeProgressPercent(0, grandTotalCount, minFloor = 18, maxCap = 35)
                )
            )

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
                    val stage1Pct = (18 + ((stepDone.toDouble() / txTotal.coerceAtLeast(1)) * 17.0).toInt())
                        .coerceIn(18, 35)
                    updateSessionState(
                        sessionId,
                        SyncProgress(
                            isRunning = true,
                            stage = "[1/3단계] $periodDesc 결제 내역 반영 중 (${stepDone}/${txTotal}건 · 신규 ${txCount}건)",
                            syncedTxCount = txCount,
                            syncedPhotoCount = 0,
                            isDone = false,
                            stepNumber = 1,
                            totalSteps = 3,
                            stepProcessedCount = stepDone,
                            stepTotalCount = txTotal,
                            overallProcessedCount = grandProcessedCount,
                            overallTotalCount = grandTotalCount,
                            progressPercent = maxOf(
                                stage1Pct,
                                computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 18, maxCap = 35)
                            )
                        )
                    )
                }
            }

            if (savedTransactions.isNotEmpty() || !useIncremental) {
                runCatching { transactionRepository.cleanDuplicates() }
                withTimeoutOrNull(4_000L) {
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
            }
            syncCheckpointPreferences.recordSmsSyncCompleted(syncStartedAtMillis, updateGlobalCheckpoint = false)

            // [Stage 2/3] 갤러리 사진 및 위치 병렬 분석
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "[2/3단계] $periodDesc 갤러리 사진·위치 병렬 분석 중 (0/${photoTotal}장)",
                    syncedTxCount = txCount,
                    syncedPhotoCount = 0,
                    isDone = false,
                    stepNumber = 2,
                    totalSteps = 3,
                    stepProcessedCount = 0,
                    stepTotalCount = photoTotal,
                    overallProcessedCount = txTotal,
                    overallTotalCount = grandTotalCount,
                    progressPercent = computeProgressPercent(txTotal, grandTotalCount, minFloor = 38, maxCap = 78)
                )
            )

            val photos = importer.scanHistoricalPhotos(
                context = context,
                daysBack = effectivePhotoDaysBack,
                limit = photoLimit,
                sinceTimestampMillis = sincePhotoMillis
            ) { cur, tot ->
                photoTotal = tot
                grandTotalCount = (txTotal + photoTotal + estimatedDiaryDays).coerceAtLeast(1)
                grandProcessedCount = txTotal + cur
                val stage2Pct = (38 + ((cur.toDouble() / tot.coerceAtLeast(1)) * 40.0).toInt())
                    .coerceIn(38, 78)
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[2/3단계] $periodDesc 갤러리 사진·위치 분석 중 (${cur}/${tot}장)",
                        syncedTxCount = txCount,
                        syncedPhotoCount = cur,
                        isDone = false,
                        stepNumber = 2,
                        totalSteps = 3,
                        stepProcessedCount = cur,
                        stepTotalCount = tot,
                        overallProcessedCount = grandProcessedCount,
                        overallTotalCount = grandTotalCount,
                        progressPercent = maxOf(
                            stage2Pct,
                            computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 38, maxCap = 78)
                        )
                    )
                )
            }
            photoCount = photos.size
            photoTotal = photoCount
            grandTotalCount = (txTotal + photoTotal + estimatedDiaryDays).coerceAtLeast(1)
            grandProcessedCount = txTotal + photoCount
            syncCheckpointPreferences.recordPhotoSyncCompleted(syncStartedAtMillis, updateGlobalCheckpoint = false)

            // [Stage 3/3] 통합 다이어리 생성 및 주행 기록 동기화
            val allDbTxs = withTimeoutOrNull(3_000L) {
                runCatching { transactionRepository.getAllTransactionsFlow().first() }.getOrNull()
            } ?: savedTransactions

            val diaryEntries = if (useIncremental) {
                val affectedDates = mutableSetOf<LocalDate>()
                photos.forEach { affectedDates.add(it.time.toLocalDate()) }
                savedTransactions.forEach { affectedDates.add(it.timestamp.toLocalDate()) }

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
                        targetDates = affectedDates
                    )
                }
            } else {
                importer.generateIntegratedDiaries(
                    photos = photos,
                    transactions = allDbTxs.ifEmpty { savedTransactions },
                    targetDates = null
                )
            }

            val totalEntries = diaryEntries.size
            val baseProcessedBeforeDiary = txTotal + photoTotal
            grandTotalCount = (baseProcessedBeforeDiary + totalEntries).coerceAtLeast(1)

            if (totalEntries > 0) {
                updateSessionState(
                    sessionId,
                    SyncProgress(
                        isRunning = true,
                        stage = "[3/3단계] 하루 이동 경로 및 다이어리 작성 중 (0/${totalEntries}일)",
                        syncedTxCount = txCount,
                        syncedPhotoCount = photoCount,
                        isDone = false,
                        stepNumber = 3,
                        totalSteps = 3,
                        stepProcessedCount = 0,
                        stepTotalCount = totalEntries,
                        overallProcessedCount = baseProcessedBeforeDiary,
                        overallTotalCount = grandTotalCount,
                        progressPercent = computeProgressPercent(baseProcessedBeforeDiary, grandTotalCount, minFloor = 80, maxCap = 98)
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
                    val stage3Pct = (80 + ((stepDone.toDouble() / totalEntries.coerceAtLeast(1)) * 18.0).toInt())
                        .coerceIn(80, 99)
                    updateSessionState(
                        sessionId,
                        SyncProgress(
                            isRunning = true,
                            stage = "[3/3단계] 하루 이동 경로 및 다이어리 작성 중 (${stepDone}/${totalEntries}일)",
                            syncedTxCount = txCount,
                            syncedPhotoCount = photoCount,
                            isDone = false,
                            stepNumber = 3,
                            totalSteps = 3,
                            stepProcessedCount = stepDone,
                            stepTotalCount = totalEntries,
                            overallProcessedCount = grandProcessedCount,
                            overallTotalCount = grandTotalCount,
                            progressPercent = maxOf(
                                stage3Pct,
                                computeProgressPercent(grandProcessedCount, grandTotalCount, minFloor = 80, maxCap = 99)
                            )
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

            val completedAtMillis = System.currentTimeMillis()
            syncCheckpointPreferences.recordSyncCompleted(completedAtMillis)

            val doneMessage = if (useIncremental && txCount == 0 && photoCount == 0) {
                "마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncCompletedMillis)}) 이후 새로 추가된 데이터가 없습니다."
            } else {
                "$periodDesc 분석 완료: 결제 ${txCount}건 · 사진 ${photoCount}장 · 다이어리 ${insertedEntriesCount}일 갱신"
            }

            val finalTotal = if (rawCandidateSum + totalEntries > 0) rawCandidateSum + totalEntries else 0
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = false,
                    stage = doneMessage,
                    syncedTxCount = txCount,
                    syncedPhotoCount = photoCount,
                    isDone = true,
                    stepNumber = 3,
                    totalSteps = 3,
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
                    isDone = true,
                    stepNumber = 3,
                    totalSteps = 3,
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
                        val finalTotal = grandTotalCount.coerceAtLeast(grandProcessedCount)
                        updateSessionState(
                            sessionId,
                            SyncProgress(
                                isRunning = false,
                                stage = "동기화 완료: 결제 ${txCount}건 · 사진 ${photoCount}장 색인 · 다이어리 ${insertedEntriesCount}일 구축 완료",
                                syncedTxCount = txCount,
                                syncedPhotoCount = photoCount,
                                isDone = true,
                                stepNumber = 3,
                                totalSteps = 3,
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
}
