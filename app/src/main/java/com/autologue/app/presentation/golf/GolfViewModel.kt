package com.autologue.app.presentation.golf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer
import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.usecase.golf.ExtractScorecardOcrUseCase
import com.autologue.app.domain.usecase.golf.MatchGolfRoundPhotosUseCase
import com.autologue.app.domain.usecase.golf.ProcessGolfLockerSlipUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import java.time.LocalTime
import javax.inject.Inject

data class GolfUiState(
    val rounds: List<GolfRound> = emptyList(),
    val averageScore: Double = 0.0,
    val bestScore: Int? = null,
    val isOcrScanning: Boolean = false,
    val selectedRound: GolfRound? = null,
    val selectedPhotoPreviewUrl: String? = null,
    val isReservationDialogOpen: Boolean = false,
    val isDutchPayDialogOpen: Boolean = false,
    val dutchPayTargetRound: GolfRound? = null
) {
    val upcomingReservations: List<GolfRound>
        get() {
            val today = java.time.LocalDate.now()
            return rounds.filter {
                val roundDay = (it.startTime ?: it.roundDate).toLocalDate()
                !roundDay.isBefore(today) && (it.totalScore == null || it.totalScore == 0)
            }.sortedBy { it.startTime ?: it.roundDate }
        }

    val completedRounds: List<GolfRound>
        get() {
            val today = java.time.LocalDate.now()
            return rounds.filter {
                val roundDay = (it.startTime ?: it.roundDate).toLocalDate()
                (it.totalScore != null && it.totalScore > 0) || roundDay.isBefore(today)
            }.sortedByDescending { it.roundDate }
        }
}

@HiltViewModel
class GolfViewModel @Inject constructor(
    private val golfRepository: GolfRepository,
    private val extractScorecardOcrUseCase: ExtractScorecardOcrUseCase,
    private val matchGolfRoundPhotosUseCase: MatchGolfRoundPhotosUseCase,
    private val historicalDataImporter: HistoricalDataImporter,
    private val diaryRepository: DiaryRepository,
    private val golfLockerSlipOcrAnalyzer: GolfLockerSlipOcrAnalyzer,
    private val processGolfLockerSlipUseCase: ProcessGolfLockerSlipUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(GolfUiState())
    val uiState: StateFlow<GolfUiState> = _uiState.asStateFlow()

    init {
        seedSampleRoundIfNeeded()
        loadRounds()
    }

    private fun seedSampleRoundIfNeeded() {
        viewModelScope.launch {
            val list = golfRepository.getAllGolfRoundsFlow().first()
            if (list.none { it.clubName == "아난티 코드 GC" }) {
                val sampleUpcoming = GolfRound(
                    clubName = "아난티 코드 GC",
                    roundDate = LocalDateTime.of(2026, 9, 12, 7, 28),
                    golfType = GolfType.FIELD,
                    greenFeeExpense = 240000L,
                    memo = "[코스: 잣나무 / 자작나무] 주말 친목 라운딩",
                    startTime = LocalDateTime.of(2026, 9, 12, 7, 28),
                    endTime = LocalDateTime.of(2026, 9, 12, 13, 0),
                    companions = listOf("정성우", "김프로", "박대표"),
                    totalScore = null
                )
                golfRepository.insertGolfRound(sampleUpcoming)
            }
        }
    }

    private fun loadRounds() {
        viewModelScope.launch {
            golfRepository.getAllGolfRoundsFlow().collectLatest { list ->
                val scoredList = list.mapNotNull { it.totalScore }
                val avg = if (scoredList.isNotEmpty()) scoredList.average() else 0.0
                val best = scoredList.minOrNull()

                val currentSelected = _uiState.value.selectedRound
                val updatedSelected = if (currentSelected != null) {
                    list.find { it.id == currentSelected.id } ?: currentSelected
                } else null

                _uiState.value = _uiState.value.copy(
                    rounds = list,
                    averageScore = avg,
                    bestScore = best,
                    selectedRound = updatedSelected
                )
            }
        }
    }

    fun selectRound(round: GolfRound, context: Context? = null) {
        _uiState.value = _uiState.value.copy(selectedRound = round)
        if (context != null && round.matchingPhotoUris.isEmpty()) {
            autoDiscoverPhotosForRound(round, context)
        }
    }

    fun closeRoundDetail() {
        _uiState.value = _uiState.value.copy(selectedRound = null)
    }

    fun openPhotoPreview(photoUrl: String) {
        _uiState.value = _uiState.value.copy(selectedPhotoPreviewUrl = photoUrl)
    }

    fun closePhotoPreview() {
        _uiState.value = _uiState.value.copy(selectedPhotoPreviewUrl = null)
    }

    fun updateRound(round: GolfRound) {
        viewModelScope.launch {
            golfRepository.updateGolfRound(round)
            _uiState.value = _uiState.value.copy(selectedRound = round)
        }
    }

    fun updateRoundDetails(
        roundId: Long,
        clubName: String,
        totalScore: Int?,
        totalPutts: Int?,
        memo: String?,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        companions: List<String>
    ) {
        viewModelScope.launch {
            val round = golfRepository.getGolfRoundById(roundId) ?: return@launch
            val updated = round.copy(
                clubName = clubName,
                totalScore = totalScore,
                totalPutts = totalPutts,
                memo = memo,
                startTime = startTime,
                endTime = endTime,
                companions = companions
            )
            golfRepository.updateGolfRound(updated)
            if (_uiState.value.selectedRound?.id == roundId) {
                _uiState.value = _uiState.value.copy(selectedRound = updated)
            }
        }
    }

    fun addPhotosToRound(roundId: Long, photoUris: List<String>) {
        viewModelScope.launch {
            val round = golfRepository.getGolfRoundById(roundId) ?: return@launch
            val updatedPhotos = (round.matchingPhotoUris + photoUris).distinct()
            val updated = round.copy(matchingPhotoUris = updatedPhotos)
            golfRepository.updateGolfRound(updated)
            _uiState.value = _uiState.value.copy(selectedRound = updated)
        }
    }

    fun removePhotoFromRound(roundId: Long, photoUri: String) {
        viewModelScope.launch {
            val round = golfRepository.getGolfRoundById(roundId) ?: return@launch
            val updatedPhotos = round.matchingPhotoUris.filter { it != photoUri }
            val updatedScorecard = if (round.scorecardPhotoUri == photoUri) null else round.scorecardPhotoUri
            val updated = round.copy(matchingPhotoUris = updatedPhotos, scorecardPhotoUri = updatedScorecard)
            golfRepository.updateGolfRound(updated)
            _uiState.value = _uiState.value.copy(selectedRound = updated)
        }
    }

    fun setScorecardPhoto(roundId: Long, scorecardUri: String) {
        viewModelScope.launch {
            val round = golfRepository.getGolfRoundById(roundId) ?: return@launch
            val updated = round.copy(scorecardPhotoUri = scorecardUri)
            golfRepository.updateGolfRound(updated)
            _uiState.value = _uiState.value.copy(selectedRound = updated)
        }
    }

    fun scanScorecard(roundId: Long, imageUri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOcrScanning = true)
            val analyzer = ScorecardOcrAnalyzer(context)
            val result = analyzer.analyzeScorecard(imageUri)
            val totalScore = result.totalScore ?: 90
            extractScorecardOcrUseCase.saveOcrResult(
                roundId = roundId,
                totalScore = totalScore,
                totalPutts = result.totalPutts,
                holeScores = result.holeScores,
                scorecardUri = imageUri.toString()
            )
            val updated = golfRepository.getGolfRoundById(roundId)
            _uiState.value = _uiState.value.copy(
                isOcrScanning = false,
                selectedRound = updated
            )
        }
    }

    fun scanGolfLockerSlip(imageUri: Uri, context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOcrScanning = true)
            val result = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(imageUri)
            val round = processGolfLockerSlipUseCase(result, imageUri.toString())
            _uiState.value = _uiState.value.copy(
                isOcrScanning = false,
                selectedRound = round
            )
        }
    }

    fun scanAllLockerSlipsFromGallery(context: Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isOcrScanning = true)
            val photos = historicalDataImporter.scanHistoricalPhotos(context, daysBack = null)
            for (photo in photos) {
                val uri = Uri.parse(photo.uri)
                val result = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(uri, fallbackDate = photo.time.toLocalDate())
                if (result.isLockerSlip) {
                    processGolfLockerSlipUseCase(result, photo.uri)
                }
            }
            _uiState.value = _uiState.value.copy(isOcrScanning = false)
        }
    }

    fun deleteRound(roundId: Long) {
        viewModelScope.launch {
            golfRepository.deleteGolfRound(roundId)
            _uiState.value = _uiState.value.copy(selectedRound = null)
        }
    }


    fun openReservationDialog() {
        _uiState.value = _uiState.value.copy(isReservationDialogOpen = true)
    }

    fun closeReservationDialog() {
        _uiState.value = _uiState.value.copy(isReservationDialogOpen = false)
    }

    fun openDutchPayDialog(targetRound: GolfRound? = null) {
        _uiState.value = _uiState.value.copy(
            isDutchPayDialogOpen = true,
            dutchPayTargetRound = targetRound ?: _uiState.value.upcomingReservations.firstOrNull() ?: _uiState.value.rounds.firstOrNull()
        )
    }

    fun closeDutchPayDialog() {
        _uiState.value = _uiState.value.copy(isDutchPayDialogOpen = false)
    }

    fun addGolfReservation(
        clubName: String,
        courseName: String,
        teeOffTime: LocalDateTime,
        companions: List<String>,
        estimatedGreenFee: Long,
        memo: String
    ) {
        viewModelScope.launch {
            val formattedMemo = if (courseName.isNotBlank()) "[코스: $courseName] $memo".trim() else memo.trim()
            val newRound = GolfRound(
                clubName = clubName.trim(),
                roundDate = teeOffTime,
                golfType = GolfType.FIELD,
                greenFeeExpense = estimatedGreenFee,
                memo = formattedMemo.ifBlank { null },
                startTime = teeOffTime,
                endTime = teeOffTime.plusHours(5).plusMinutes(30),
                companions = companions.filter { it.isNotBlank() },
                totalScore = null
            )
            golfRepository.insertGolfRound(newRound)
            closeReservationDialog()
        }
    }

    private fun autoDiscoverPhotosForRound(round: GolfRound, context: Context) {
        viewModelScope.launch {
            try {
                val roundDate = round.roundDate.toLocalDate()
                val photos = historicalDataImporter.scanHistoricalPhotos(context, daysBack = 30)
                val sameDayPhotos = photos.filter { it.time.toLocalDate() == roundDate }
                if (sameDayPhotos.isNotEmpty()) {
                    val uris = sameDayPhotos.map { it.uri }
                    val companions = sameDayPhotos.flatMap { it.companions }.distinct()
                    val updated = round.copy(
                        matchingPhotoUris = (round.matchingPhotoUris + uris).distinct(),
                        companions = (round.companions + companions).distinct()
                    )
                    golfRepository.updateGolfRound(updated)
                    _uiState.value = _uiState.value.copy(selectedRound = updated)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
