package com.autologue.app.domain.usecase.sync

import android.content.Context
import com.autologue.app.data.preferences.SyncCheckpointPreferences
import com.autologue.app.data.preferences.SyncCheckpointState
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
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
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
 * 마지막으로 앱이 구동된 시점과 마지막으로 데이터 수집·분석이 완료된 시점(SyncCheckpointPreferences)을 기록해두고,
 * 그 이후 시점부터 새로 발생한 증분(Incremental) 데이터만 수집 및 분석합니다.
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

    /**
     * 앱 세션 최초 진입 시 백그라운드 자동 동기화를 시작합니다.
     * 이미 동기화가 진행 중이거나 완료된 경우 중복 실행하지 않고 기존 상태를 그대로 유지합니다.
     * 마지막 수집·분석 완료 시점이 존재하면 해당 시점 이후의 신규 데이터만 증분 수집·분석합니다.
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
                "마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncMillis)}) 이후 신규 데이터 수집 시작..."
            } else {
                "일상 기록(결제 문자 & 사진) 동기화 시작..."
            }
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = initialStage,
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
     * daysBack == 0 인 경우 마지막 분석 완료 시점 이후의 증분 데이터만 수집·분석합니다.
     */
    fun startManualSync(context: Context, daysBack: Int? = 0) {
        val appCtx = context.applicationContext ?: context
        synchronized(stateLock) {
            val previousJob = activeSyncJob
            val sessionId = syncSessionId.incrementAndGet()
            _isManualSyncDialogVisible.value = true
            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "데이터 수집 및 분석 준비 중...",
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
        val syncStartedAtMillis = System.currentTimeMillis()

        try {
            // 기존 버전에서 업데이트된 단말에 이미 수집된 DB 데이터가 있다면 해당 최신 시점을 기준점으로 자동 설정
            ensureBaselineCheckpointIfNeeded()

            val lastSyncCompletedMillis = syncCheckpointPreferences.getLastSyncCompletedAtMillis()
            val useIncremental = (!isManual && lastSyncCompletedMillis > 0L) ||
                (isManual && daysBack == 0 && lastSyncCompletedMillis > 0L)

            val periodDesc = when {
                useIncremental -> "마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncCompletedMillis)}) 이후"
                daysBack != null && daysBack > 0 -> "최근 ${daysBack}일"
                else -> "과거 전체"
            }

            // 1. 기존 중복 데이터 검사 및 가짜 골프 라운드 청소 (최초 1회 또는 수동 기간 전체 동기화 시에만 실행)
            if (!syncCheckpointPreferences.isLegacyCleanupDone() || (isManual && !useIncremental)) {
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
                withTimeoutOrNull(10_000L) {
                    runCatching { transactionRepository.cleanDuplicates() }
                    runCatching { diaryRepository.cleanDuplicates() }
                    runCatching { vehicleRepository.cleanDuplicates() }
                    cleanUpMedicalFakeRounds()
                    syncCheckpointPreferences.markLegacyCleanupDone()
                }
            }

            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "$periodDesc 신규 결제 문자 수집 및 분석 중...",
                    syncedTxCount = 0,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = false
                )
            )

            // 2. 문자 스캔 및 거래/주유 내역 동기화 (최대 30초 타임아웃 방어)
            val sinceSmsMillis = if (useIncremental) syncCheckpointPreferences.getLastSmsSyncCompletedAtMillis() else null
            val savedTransactions = mutableListOf<com.autologue.app.domain.model.Transaction>()
            withTimeoutOrNull(30_000L) {
                val smsDaysBack = when {
                    useIncremental -> null
                    daysBack == null -> null
                    else -> maxOf(daysBack, 365)
                }
                val smsLimit = if (useIncremental) 1000 else 5000
                val transactions = importer.scanHistoricalSms(
                    context = context,
                    daysBack = smsDaysBack,
                    limit = smsLimit,
                    sinceTimestampMillis = sinceSmsMillis
                )
                for (tx in transactions) {
                    runCatching {
                        val txId = processTransactionUseCase(tx)
                        if (txId > 0L) {
                            savedTransactions.add(tx)
                            txCount++
                        }
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
            }

            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = true,
                    stage = "$periodDesc 신규 갤러리 사진 및 위치 분석 중...",
                    syncedTxCount = txCount,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = false
                )
            )

            // 3. 갤러리 사진 및 위치 스캔 (최대 25초 타임아웃 방어)
            val sincePhotoMillis = if (useIncremental) syncCheckpointPreferences.getLastPhotoSyncCompletedAtMillis() else null
            val photos = withTimeoutOrNull(25_000L) {
                importer.scanHistoricalPhotos(
                    context = context,
                    daysBack = if (useIncremental) null else daysBack,
                    limit = 100,
                    sinceTimestampMillis = sincePhotoMillis
                )
            } ?: emptyList()
            photoCount = photos.size
            syncCheckpointPreferences.recordPhotoSyncCompleted(syncStartedAtMillis, updateGlobalCheckpoint = false)

            // 4. 캡처된 스크린샷 및 갤러리 사진에서 라커룸 전표 및 스코어카드 자동 감지
            val sinceGolfMillis = if (useIncremental) syncCheckpointPreferences.getLastGolfSyncCompletedAtMillis() else null
            val candidateLimit = if (useIncremental) 100 else if (!isManual && daysBack != null && daysBack <= 7) 60 else 200
            val candidateDaysBack = if (useIncremental) null else if (!isManual && daysBack != null && daysBack <= 7) 14 else daysBack
            val golfCandidates = withTimeoutOrNull(10_000L) {
                importer.scanHistoricalGolfCandidates(
                    context = context,
                    daysBack = candidateDaysBack,
                    limit = candidateLimit,
                    sinceTimestampMillis = sinceGolfMillis
                )
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
            syncCheckpointPreferences.recordGolfSyncCompleted(syncStartedAtMillis, updateGlobalCheckpoint = false)

            // 5. 통합 다이어리 생성 및 주행 기록 동기화 (최대 25초 타임아웃 방어)
            withTimeoutOrNull(25_000L) {
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
                            limit = 100,
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
                if (totalEntries > 0) {
                    updateSessionState(
                        sessionId,
                        SyncProgress(
                            isRunning = true,
                            stage = "하루 이동 경로 및 다이어리 작성 중... (1/${totalEntries}일)",
                            syncedTxCount = txCount,
                            syncedPhotoCount = photoCount,
                            syncedGolfCount = golfSyncedCount,
                            isDone = false
                        )
                    )
                }

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

            // 6. 데이터 수집 및 분석 완료 시점 영구 기록
            val completedAtMillis = System.currentTimeMillis()
            syncCheckpointPreferences.recordSyncCompleted(completedAtMillis)

            val golfSummary = if (golfSyncedCount > 0) " / ⛳ 골프 ${golfSyncedCount}건 등록" else ""
            val doneMessage = if (useIncremental && txCount == 0 && photoCount == 0 && golfSyncedCount == 0) {
                "마지막 분석(${SyncCheckpointPreferences.formatEpochMillis(lastSyncCompletedMillis)}) 이후 새로 추가된 데이터가 없습니다. (최신 상태 유지)"
            } else {
                "$periodDesc 분석 완료: 신규 결제 ${txCount}건, 사진 ${photoCount}장$golfSummary, 다이어리 ${insertedEntriesCount}일 갱신!"
            }

            updateSessionState(
                sessionId,
                SyncProgress(
                    isRunning = false,
                    stage = doneMessage,
                    syncedTxCount = txCount,
                    syncedPhotoCount = photoCount,
                    syncedGolfCount = golfSyncedCount,
                    isDone = true
                )
            )
        } catch (t: Throwable) {
            if (t is CancellationException) throw t
            android.util.Log.e("SyncHistoricalData", "백그라운드 동기화 중 예외 안전 처리", t)
            syncCheckpointPreferences.recordSyncCompleted(System.currentTimeMillis())
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

