package com.autologue.app.domain.usecase.sync

import android.content.Context
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import javax.inject.Inject

data class SyncProgress(
    val isRunning: Boolean,
    val stage: String,
    val syncedTxCount: Int = 0,
    val syncedPhotoCount: Int = 0,
    val isDone: Boolean = false
)

class SyncHistoricalDataUseCase @Inject constructor(
    private val importer: HistoricalDataImporter,
    private val processTransactionUseCase: ProcessTransactionUseCase,
    private val transactionRepository: TransactionRepository,
    private val diaryRepository: DiaryRepository,
    private val vehicleRepository: VehicleRepository,
    private val golfLockerSlipOcrAnalyzer: com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer,
    private val processGolfLockerSlipUseCase: com.autologue.app.domain.usecase.golf.ProcessGolfLockerSlipUseCase
) {
    operator fun invoke(context: Context, daysBack: Int? = null): Flow<SyncProgress> = flow {
        emit(SyncProgress(isRunning = true, stage = "기존 중복 데이터 검사 및 정리 중...", syncedTxCount = 0, syncedPhotoCount = 0))

        // Clean any existing duplicate rows across all tables
        transactionRepository.cleanDuplicates()
        diaryRepository.cleanDuplicates()
        vehicleRepository.cleanDuplicates()

        val periodDesc = if (daysBack != null) "최근 ${daysBack}일" else "과거 전체"
        emit(SyncProgress(isRunning = true, stage = "$periodDesc 결제 문자 스캔 중...", syncedTxCount = 0, syncedPhotoCount = 0))

        val transactions = importer.scanHistoricalSms(context, daysBack = daysBack)
        var txCount = 0
        val savedTransactions = mutableListOf<com.autologue.app.domain.model.Transaction>()
        for (tx in transactions) {
            processTransactionUseCase(tx)
            savedTransactions.add(tx)
            txCount++
        }

        // 주유 결제 내역 차계부 자동 동기화
        vehicleRepository.syncRefuelingFromTransactions(savedTransactions)

        // Post-import cleanup to guarantee zero duplicates
        transactionRepository.cleanDuplicates()

        emit(SyncProgress(isRunning = true, stage = "$periodDesc 갤러리 사진 및 위치 분석 중...", syncedTxCount = txCount, syncedPhotoCount = 0))

        val photos = importer.scanHistoricalPhotos(context, daysBack = daysBack)
        val photoCount = photos.size

        // 라커룸 안내지 사진 자동 OCR 감지 및 전역 적재 (최대 10장 제한으로 과부하 방지)
        val golfCandidates = photos.filter { photo ->
            photo.uri.contains("Golf", ignoreCase = true) ||
            photo.uri.contains("locker", ignoreCase = true) ||
            (photo.placeName ?: "").contains("골프") ||
            photo.tags.any { it.contains("골프") }
        }.take(10)

        for (photo in golfCandidates) {
            runCatching {
                val uri = android.net.Uri.parse(photo.uri)
                val slipResult = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(uri, fallbackDate = photo.time.toLocalDate())
                if (slipResult.isLockerSlip) {
                    processGolfLockerSlipUseCase(slipResult, photo.uri)
                }
            }
        }

        emit(SyncProgress(isRunning = true, stage = "결제·사진 기반 하루 이동 경로 및 다이어리 작성 중...", syncedTxCount = txCount, syncedPhotoCount = photoCount))

        val diaryEntries = importer.generateIntegratedDiaries(
            photos = photos,
            transactions = savedTransactions
        )

        for (entry in diaryEntries) {
            diaryRepository.insertDiaryEntry(entry)
        }

        emit(
            SyncProgress(
                isRunning = false,
                stage = "동기화 완료: 결제 ${txCount}건, 사진 ${photoCount}장 색인 및 다이어리 ${diaryEntries.size}일 구축 완료!",
                syncedTxCount = txCount,
                syncedPhotoCount = photoCount,
                isDone = true
            )
        )
    }.flowOn(Dispatchers.IO)
}
