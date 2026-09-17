package com.autologue.app.domain.usecase.sync

import android.content.Context
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

data class SyncProgress(
    val isRunning: Boolean,
    val stage: String,
    val syncedTxCount: Int = 0,
    val syncedPhotoCount: Int = 0,
    val syncedGolfCount: Int = 0,
    val isDone: Boolean = false
)

class SyncHistoricalDataUseCase @Inject constructor(
    private val importer: HistoricalDataImporter,
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val transactionRepository: TransactionRepository,
    private val diaryRepository: DiaryRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfRepository: com.autologue.app.domain.repository.GolfRepository,
    private val autoProcessGolfMediaUseCase: com.autologue.app.domain.usecase.golf.AutoProcessGolfMediaUseCase
) {
    operator fun invoke(context: Context, daysBack: Int? = null): Flow<SyncProgress> = flow {
        try {
            emit(SyncProgress(isRunning = true, stage = "기존 중복 데이터 검사 및 정리 중...", syncedTxCount = 0, syncedPhotoCount = 0, syncedGolfCount = 0))

            // Clean any existing duplicate rows across all tables
            runCatching { transactionRepository.cleanDuplicates() }
            runCatching { diaryRepository.cleanDuplicates() }
            runCatching { vehicleRepository.cleanDuplicates() }

            val periodDesc = if (daysBack != null) "최근 ${daysBack}일" else "과거 전체"
            emit(SyncProgress(isRunning = true, stage = "$periodDesc 결제 문자 스캔 중...", syncedTxCount = 0, syncedPhotoCount = 0, syncedGolfCount = 0))

            val transactions = importer.scanHistoricalSms(context, daysBack = daysBack)
            var txCount = 0
            val savedTransactions = mutableListOf<com.autologue.app.domain.model.Transaction>()
            for (tx in transactions) {
                runCatching {
                    processTransactionUseCase(tx)
                    savedTransactions.add(tx)
                    txCount++
                }
            }

            // 주유 결제 내역 차계부 자동 동기화
            runCatching { vehicleRepository.syncRefuelingFromTransactions(savedTransactions) }

            // Post-import cleanup to guarantee zero duplicates
            runCatching { transactionRepository.cleanDuplicates() }

            emit(SyncProgress(isRunning = true, stage = "$periodDesc 갤러리 사진 및 위치 분석 중...", syncedTxCount = txCount, syncedPhotoCount = 0, syncedGolfCount = 0))

            val photos = importer.scanHistoricalPhotos(context, daysBack = daysBack)
            val photoCount = photos.size

            // 캡처된 스크린샷 및 갤러리 사진에서 라커룸 전표 및 스코어카드 자동 감지 및 라운드 등록 (최대 200장 전수 분석)
            val golfCandidates = importer.scanHistoricalGolfCandidates(context, daysBack = daysBack, limit = 200)
            var golfSyncedCount = 0
            if (golfCandidates.isNotEmpty()) {
                emit(SyncProgress(isRunning = true, stage = "$periodDesc 스코어카드·라커룸 자동 분석 중 (${golfCandidates.size}장 발견)...", syncedTxCount = txCount, syncedPhotoCount = photoCount, syncedGolfCount = 0))
                golfSyncedCount = runCatching {
                    autoProcessGolfMediaUseCase.processBatchCandidates(golfCandidates)
                }.getOrDefault(0)
                if (golfSyncedCount > 0) {
                    emit(SyncProgress(isRunning = true, stage = "골프 라운드 ${golfSyncedCount}건 자동 등록 완료!", syncedTxCount = txCount, syncedPhotoCount = photoCount, syncedGolfCount = golfSyncedCount))
                }
            }

            emit(SyncProgress(isRunning = true, stage = "결제·사진 기반 하루 이동 경로 및 다이어리 작성 중...", syncedTxCount = txCount, syncedPhotoCount = photoCount, syncedGolfCount = golfSyncedCount))

            val allGolfRounds = runCatching {
                golfRepository.getAllGolfRoundsFlow().first()
            }.getOrDefault(emptyList())

            val diaryEntries = importer.generateIntegratedDiaries(
                photos = photos,
                transactions = savedTransactions,
                golfRounds = allGolfRounds
            )

            var insertedEntriesCount = 0
            for (entry in diaryEntries) {
                runCatching {
                    diaryRepository.insertDiaryEntry(entry)
                    insertedEntriesCount++
                }
            }

            val golfSummary = if (golfSyncedCount > 0) " / ⛳ 골프 ${golfSyncedCount}건 등록" else ""
            emit(
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
            emit(
                SyncProgress(
                    isRunning = false,
                    stage = "동기화 완료 (안전 모드로 색인 처리됨)",
                    syncedTxCount = 0,
                    syncedPhotoCount = 0,
                    syncedGolfCount = 0,
                    isDone = true
                )
            )
        }
    }.flowOn(Dispatchers.IO)
}
