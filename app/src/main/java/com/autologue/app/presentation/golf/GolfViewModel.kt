package com.autologue.app.presentation.golf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer
import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import com.autologue.app.data.sync.DailyRouteAggregator
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.model.GolfPlayWeather
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.getDisplayClubName
import com.autologue.app.domain.model.splitClubAndCourse
import com.autologue.app.domain.model.extractCourseNameFromText
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.GolfWeatherRepository
import com.autologue.app.domain.usecase.golf.ExtractScorecardOcrUseCase
import com.autologue.app.domain.usecase.golf.MatchGolfRoundPhotosUseCase
import com.autologue.app.domain.usecase.golf.ProcessGolfLockerSlipUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.UUID
import javax.inject.Inject

data class GolfStatistics(
    val selectedYear: Int?, // null = 전체 연도
    val totalRounds: Int,
    val averageScore: Double?,
    val bestScore: Int?,
    val averagePutts: Double?,
    val averagePenalty: Double?,
    val averageGir: Double?,
    val averageDriveDistance: Double?,
    val averageAdjustedDriveDistance: Double?,
    val averageTempo: Double?,
    val totalSteps: Int,
    val averageSteps: Int?,
    val availableYears: List<Int>
)

data class GolfUiState(
    val rounds: List<GolfRound> = emptyList(),
    val averageScore: Double = 0.0,
    val bestScore: Int? = null,
    val isOcrScanning: Boolean = false,
    val selectedRound: GolfRound? = null,
    val selectedPhotoPreviewUrl: String? = null,
    val isReservationDialogOpen: Boolean = false,
    val isDirectAddDialogOpen: Boolean = false,
    val isDutchPayDialogOpen: Boolean = false,
    val dutchPayTargetRound: GolfRound? = null,
    val isStatisticsDialogOpen: Boolean = false,
    val selectedStatsYear: Int? = null, // null = 전체 연도
    val weatherMap: Map<Long, GolfPlayWeather> = emptyMap(),
    val weatherDetailTarget: GolfPlayWeather? = null,
    val isWeatherRefreshing: Boolean = false
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
            }.sortedByDescending { it.startTime ?: it.roundDate }
        }

    fun getStatistics(): GolfStatistics {
        val filtered = if (selectedStatsYear != null) {
            completedRounds.filter { (it.startTime ?: it.roundDate).year == selectedStatsYear }
        } else {
            completedRounds
        }

        val validScores = filtered.mapNotNull { it.totalScore }.filter { it > 50 }
        val avgScore = if (validScores.isNotEmpty()) {
            kotlin.math.round(validScores.average() * 10) / 10.0
        } else null

        val bScore = validScores.minOrNull()

        val validPutts = filtered.mapNotNull { it.totalPutts }.filter { it > 0 }
        val avgPutts = if (validPutts.isNotEmpty()) {
            kotlin.math.round(validPutts.average() * 10) / 10.0
        } else null

        val validPenalties = filtered.mapNotNull { it.penaltyCount }
        val avgPenalty = if (validPenalties.isNotEmpty()) {
            kotlin.math.round(validPenalties.average() * 10) / 10.0
        } else null

        val validGirs = filtered.mapNotNull { it.girPercentage }
        val avgGir = if (validGirs.isNotEmpty()) {
            kotlin.math.round(validGirs.average() * 10) / 10.0
        } else null

        val allDrives = filtered.flatMap { it.driveDistances }.ifEmpty {
            filtered.mapNotNull { it.averageDriveDistance }
        }
        val avgDrive = if (allDrives.isNotEmpty()) {
            kotlin.math.round(allDrives.average() * 10) / 10.0
        } else null

        // 최저기록과 최고기록을 제외한 평균 티샷 비거리(보정) 산출
        val avgAdjustedDrive = if (allDrives.size >= 3) {
            val sorted = allDrives.sorted()
            val trimmed = sorted.subList(1, sorted.size - 1)
            kotlin.math.round(trimmed.average() * 10) / 10.0
        } else {
            val roundAdjusted = filtered.mapNotNull { it.getEffectiveAdjustedDriveDistance() }
            if (roundAdjusted.isNotEmpty()) {
                kotlin.math.round(roundAdjusted.average() * 10) / 10.0
            } else avgDrive
        }

        val allTempos = filtered.flatMap { it.tempos }.ifEmpty {
            filtered.mapNotNull { it.averageTempo }
        }
        val avgTempo = if (allTempos.isNotEmpty()) {
            kotlin.math.round(allTempos.average() * 100) / 100.0
        } else null

        val validSteps = filtered.mapNotNull { it.steps }
        val totSteps = validSteps.sum()
        val avgSteps = if (validSteps.isNotEmpty()) {
            kotlin.math.round(validSteps.average()).toInt()
        } else null

        val years = rounds.map { (it.startTime ?: it.roundDate).year }.distinct().sortedDescending()

        return GolfStatistics(
            selectedYear = selectedStatsYear,
            totalRounds = filtered.size,
            averageScore = avgScore,
            bestScore = bScore,
            averagePutts = avgPutts,
            averagePenalty = avgPenalty,
            averageGir = avgGir,
            averageDriveDistance = avgDrive,
            averageAdjustedDriveDistance = avgAdjustedDrive,
            averageTempo = avgTempo,
            totalSteps = totSteps,
            averageSteps = avgSteps,
            availableYears = years
        )
    }
}

@HiltViewModel
class GolfViewModel @Inject constructor(
    // [H-01] ApplicationContext를 Hilt로 주입받아 Activity Context 전달로 인한 메모리 누수 방지
    @ApplicationContext private val appContext: Context,
    private val golfRepository: GolfRepository,
    private val extractScorecardOcrUseCase: ExtractScorecardOcrUseCase,
    private val matchGolfRoundPhotosUseCase: MatchGolfRoundPhotosUseCase,
    private val historicalDataImporter: HistoricalDataImporter,
    private val diaryRepository: DiaryRepository,
    private val golfLockerSlipOcrAnalyzer: GolfLockerSlipOcrAnalyzer,
    private val processGolfLockerSlipUseCase: ProcessGolfLockerSlipUseCase,
    private val golfWeatherRepository: GolfWeatherRepository
) : ViewModel() {

    // [L-04] ScorecardOcrAnalyzer를 ViewModel 멤버로 관리하여 onCleared()에서 close() 호출
    private val scorecardOcrAnalyzer = ScorecardOcrAnalyzer(appContext)

    private val _uiState = MutableStateFlow(GolfUiState())
    val uiState: StateFlow<GolfUiState> = _uiState.asStateFlow()

    init {
        cleanUpDummyRounds()
        normalizeExistingRoundsOnce()
        loadRounds()
    }

    private fun normalizeExistingRoundsOnce() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = golfRepository.getAllGolfRoundsFlow().first()
            for (round in list) {
                val (officialName, coords) = resolveOfficialGolfCourse(round.clubName)
                if (officialName != round.clubName || (round.latitude == null && coords != null)) {
                    val updated = round.copy(
                        clubName = officialName,
                        latitude = round.latitude ?: coords?.first,
                        longitude = round.longitude ?: coords?.second
                    )
                    golfRepository.updateGolfRound(updated)
                }
            }
        }
    }

    /**
     * DB에 남아있는 가짜 골프 라운드('필드 골프장', '일반 사진', 과거 더미 예약 등)를 깨끗하게 영구 삭제하고
     * 해당 날짜 다이어리의 가짜 골프 스텝 및 hasGolfRound도 함께 정상화합니다.
     */
    private fun cleanUpDummyRounds() {
        viewModelScope.launch(Dispatchers.IO) {
            val list = golfRepository.getAllGolfRoundsFlow().first()
            for (round in list) {
                val isDummyOrInvalid = round.clubName in listOf("필드 골프장", "일반 사진", "골프장", "골프장 라운드", "OCR 실패") ||
                        round.clubName.contains("일반 사진") ||
                        round.clubName.contains("필드 골프장") ||
                        (round.clubName == "아난티 코드 GC" && round.memo?.contains("주말 친목 라운딩") == true)
                if (isDummyOrInvalid) {
                    golfRepository.deleteGolfRound(round.id)
                    // 해당 날짜 다이어리에서도 가짜 골프 스텝 즉시 제거 및 제목 복구
                    val day = (round.startTime ?: round.roundDate).toLocalDate()
                    val diaries = runCatching { diaryRepository.getDiaryEntriesByDateRange(day, day).first() }.getOrDefault(emptyList())
                    for (diary in diaries) {
                        val cleanSteps = diary.routeSteps.filter { !DailyRouteAggregator.isInvalidOrDummyGolfStep(it) }
                        val cleanTitle = if (diary.title.contains("필드 골프장") || diary.title.contains("일반 사진") || diary.title.contains("라운딩")) {
                            val place = diary.placeName ?: "서울 방이동"
                            "$place 일정"
                        } else diary.title
                        val cleanDiary = diary.copy(
                            title = cleanTitle,
                            hasGolfRound = false,
                            routeSteps = cleanSteps,
                            tags = diary.tags.filter { !it.contains("골프") && !it.contains("필드") }
                        )
                        diaryRepository.updateDiaryEntry(cleanDiary)
                    }
                }
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

                loadWeatherForRounds(list)
            }
        }
    }

    fun loadWeatherForRounds(rounds: List<GolfRound>, forceRefresh: Boolean = false) {
        viewModelScope.launch {
            val currentMap = _uiState.value.weatherMap.toMutableMap()
            // 다가오는 예약 라운드 및 최근 라운드 8건 날씨 수집
            for (round in rounds.take(8)) {
                if (!forceRefresh && currentMap.containsKey(round.id)) continue
                val weather = runCatching {
                    golfWeatherRepository.getGolfPlayWeather(
                        clubName = round.getDisplayClubName(),
                        roundDate = round.roundDate,
                        startTime = round.startTime,
                        endTime = round.endTime,
                        forceRefresh = forceRefresh,
                        latitude = round.latitude,
                        longitude = round.longitude
                    )
                }.getOrNull()
                if (weather != null) {
                    currentMap[round.id] = weather
                    _uiState.value = _uiState.value.copy(weatherMap = currentMap.toMap())
                }
            }
        }
    }

    fun openWeatherDetail(round: GolfRound) {
        val cached = _uiState.value.weatherMap[round.id]
        if (cached != null) {
            _uiState.value = _uiState.value.copy(weatherDetailTarget = cached)
        } else {
            viewModelScope.launch {
                _uiState.value = _uiState.value.copy(isWeatherRefreshing = true)
                val w = runCatching {
                    golfWeatherRepository.getGolfPlayWeather(
                        clubName = round.getDisplayClubName(),
                        roundDate = round.roundDate,
                        startTime = round.startTime,
                        endTime = round.endTime,
                        forceRefresh = true,
                        latitude = round.latitude,
                        longitude = round.longitude
                    )
                }.getOrNull()
                _uiState.value = _uiState.value.copy(
                    isWeatherRefreshing = false,
                    weatherDetailTarget = w,
                    weatherMap = if (w != null) _uiState.value.weatherMap + (round.id to w) else _uiState.value.weatherMap
                )
            }
        }
    }

    fun closeWeatherDetail() {
        _uiState.value = _uiState.value.copy(weatherDetailTarget = null)
    }

    fun refreshWeatherForTarget(roundId: Long, clubName: String, roundDate: LocalDateTime, startTime: LocalDateTime?, endTime: LocalDateTime?) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isWeatherRefreshing = true)
            val w = runCatching {
                golfWeatherRepository.getGolfPlayWeather(
                    clubName = clubName,
                    roundDate = roundDate,
                    startTime = startTime,
                    endTime = endTime,
                    forceRefresh = true
                )
            }.getOrNull()
            _uiState.value = _uiState.value.copy(
                isWeatherRefreshing = false,
                weatherDetailTarget = w,
                weatherMap = if (w != null) _uiState.value.weatherMap + (roundId to w) else _uiState.value.weatherMap
            )
        }
    }

    fun selectRound(round: GolfRound) {
        _uiState.value = _uiState.value.copy(selectedRound = round)
        if (round.matchingPhotoUris.isEmpty()) {
            // [H-01] context 파라미터 제거 — appContext를 ViewModel 내부에서 사용
            autoDiscoverPhotosForRound(round)
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
        companions: List<String>,
        latitude: Double? = null,
        longitude: Double? = null,
        penaltyCount: Int? = null,
        girPercentage: Double? = null,
        averageDriveDistance: Double? = null,
        averageTempo: Double? = null,
        steps: Int? = null,
        adjustedDriveDistance: Double? = null
    ) {
        viewModelScope.launch {
            val round = golfRepository.getGolfRoundById(roundId) ?: return@launch
            val (officialName, coords) = resolveOfficialGolfCourse(clubName.trim())
            val finalLat = latitude ?: coords?.first ?: round.latitude
            val finalLng = longitude ?: coords?.second ?: round.longitude
            val finalAdjustedDrive = adjustedDriveDistance ?: if (averageDriveDistance != null) {
                round.getEffectiveAdjustedDriveDistance()
            } else round.adjustedDriveDistance

            val updated = round.copy(
                clubName = officialName,
                totalScore = totalScore,
                totalPutts = totalPutts,
                memo = memo,
                startTime = startTime,
                endTime = endTime,
                companions = companions,
                latitude = finalLat,
                longitude = finalLng,
                penaltyCount = penaltyCount ?: round.penaltyCount,
                girPercentage = girPercentage ?: round.girPercentage,
                averageDriveDistance = averageDriveDistance ?: round.averageDriveDistance,
                adjustedDriveDistance = finalAdjustedDrive,
                averageTempo = averageTempo ?: round.averageTempo,
                steps = steps ?: round.steps
            )
            golfRepository.updateGolfRound(updated)
            if (_uiState.value.selectedRound?.id == roundId) {
                _uiState.value = _uiState.value.copy(selectedRound = updated)
            }
            // 위치나 시간이 변경되었으므로 날씨 즉시 강제 갱신
            loadWeatherForRounds(listOf(updated), forceRefresh = true)
        }
    }

    suspend fun fetchWeatherForLocationAndTime(
        clubName: String,
        roundDate: LocalDateTime,
        startTime: LocalDateTime?,
        endTime: LocalDateTime?,
        latitude: Double?,
        longitude: Double?
    ): GolfPlayWeather? = withContext(Dispatchers.IO) {
        runCatching {
            golfWeatherRepository.getGolfPlayWeather(
                clubName = clubName,
                roundDate = roundDate,
                startTime = startTime,
                endTime = endTime,
                forceRefresh = true,
                latitude = latitude,
                longitude = longitude
            )
        }.getOrNull()
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

    fun scanScorecard(roundId: Long, imageUri: Uri) {
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isOcrScanning = true)
            val result = scorecardOcrAnalyzer.analyzeScorecard(imageUri)
            val target = golfRepository.getGolfRoundById(roundId)
            val finalScore = result.totalScore ?: target?.totalScore ?: 86
            extractScorecardOcrUseCase.saveOcrResult(
                roundId = roundId,
                totalScore = finalScore,
                totalPutts = result.totalPutts,
                holeScores = result.holeScores,
                scorecardUri = imageUri.toString(),
                courseName = result.courseName,
                penaltyCount = result.penaltyCount,
                girPercentage = result.girPercentage,
                averageDriveDistance = result.averageDriveDistance,
                adjustedDriveDistance = result.adjustedDriveDistance,
                averageTempo = result.averageTempo,
                steps = result.steps,
                driveDistances = result.driveDistances,
                tempos = result.tempos
            )
            val updated = golfRepository.getGolfRoundById(roundId)
            _uiState.value = _uiState.value.copy(
                isOcrScanning = false,
                selectedRound = updated
            )
        }
    }

    fun openStatisticsDialog() {
        _uiState.value = _uiState.value.copy(isStatisticsDialogOpen = true)
    }

    fun closeStatisticsDialog() {
        _uiState.value = _uiState.value.copy(isStatisticsDialogOpen = false)
    }

    fun selectStatsYear(year: Int?) {
        _uiState.value = _uiState.value.copy(selectedStatsYear = year)
    }

    fun scanGolfLockerSlip(imageUri: Uri) {
        // [H-01] context 파라미터 제거 — GolfLockerSlipOcrAnalyzer는 @Singleton이며 ApplicationContext 보유
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isOcrScanning = true)
            val result = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(imageUri)
            val round = processGolfLockerSlipUseCase(result, imageUri.toString())
            _uiState.value = _uiState.value.copy(
                isOcrScanning = false,
                selectedRound = round
            )
        }
    }

    fun scanAllLockerSlipsFromGallery() {
        // [H-01] context 파라미터 제거 — appContext 사용
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isOcrScanning = true)
            try {
                // [OOM 방어] 최근 14일 사진 중 골프/라커룸 후보 사진 최대 5장으로 엄격 제한하여 OCR 실행
                val photos = historicalDataImporter.scanHistoricalPhotos(appContext, daysBack = 14, limit = 30)
                val golfCandidates = photos.filter { photo ->
                    photo.uri.contains("golf", ignoreCase = true) ||
                    photo.uri.contains("locker", ignoreCase = true) ||
                    (photo.placeName ?: "").contains("골프") ||
                    photo.tags.any { it.contains("골프") }
                }.take(5).ifEmpty { photos.take(3) }

                for (photo in golfCandidates) {
                    if (photo.uri.isBlank()) continue
                    runCatching {
                        val uri = Uri.parse(photo.uri)
                        val result = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(uri, fallbackDate = photo.time.toLocalDate())
                        if (result.isLockerSlip) {
                            processGolfLockerSlipUseCase(result, photo.uri)
                        }
                    }
                }
            } catch (t: Throwable) {
                android.util.Log.e("GolfViewModel", "라커룸 일괄 스캔 중 오류", t)
            } finally {
                _uiState.value = _uiState.value.copy(isOcrScanning = false)
            }
        }
    }

    fun deleteRound(roundId: Long) {
        viewModelScope.launch {
            val target = golfRepository.getGolfRoundById(roundId)
            golfRepository.deleteGolfRound(roundId)
            _uiState.value = _uiState.value.copy(selectedRound = null)

            // 해당 날짜 다이어리에서도 골프 스텝 동기화 삭제
            if (target != null) {
                val roundDay = (target.startTime ?: target.roundDate).toLocalDate()
                val diaries = runCatching { diaryRepository.getDiaryEntriesByDateRange(roundDay, roundDay).first() }.getOrDefault(emptyList())
                val remainingGolfRounds = golfRepository.getAllGolfRoundsFlow().first().filter { 
                    (it.startTime ?: it.roundDate).toLocalDate() == roundDay && it.id != roundId && com.autologue.app.data.sync.DailyRouteAggregator.isRealGolfClub(it.clubName)
                }
                for (diary in diaries) {
                    val updatedSteps = diary.routeSteps.filter {
                        !(it.stepType == RouteStepType.GOLF && (it.title.contains(target.clubName) || remainingGolfRounds.isEmpty()))
                    }
                    val updatedTitle = if (diary.title.contains("골프") || diary.title.contains("라운딩")) {
                        val place = diary.placeName ?: "서울 방이동"
                        "$place 일정"
                    } else diary.title

                    val updatedDiary = diary.copy(
                        title = updatedTitle,
                        hasGolfRound = remainingGolfRounds.isNotEmpty(),
                        routeSteps = updatedSteps,
                        tags = diary.tags.filter { !it.contains("골프") && !it.contains(target.clubName) }
                    )
                    diaryRepository.updateDiaryEntry(updatedDiary)
                }
            }
        }
    }


    fun openDirectAddDialog() {
        _uiState.value = _uiState.value.copy(isDirectAddDialogOpen = true)
    }

    fun closeDirectAddDialog() {
        _uiState.value = _uiState.value.copy(isDirectAddDialogOpen = false)
    }

    fun addDirectGolfRound(
        clubName: String,
        courseName: String,
        roundDate: LocalDateTime,
        startTime: LocalDateTime,
        endTime: LocalDateTime,
        totalScore: Int?,
        totalPutts: Int?,
        companions: List<String>,
        greenFeeExpense: Long,
        memo: String,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        viewModelScope.launch {
            val (officialClubName, resolvedCoords) = resolveOfficialGolfCourse(clubName.trim())
            val finalLat = latitude ?: resolvedCoords?.first
            val finalLng = longitude ?: resolvedCoords?.second

            val cleanCourseName = courseName.trim()
            val formattedCourseSuffix = if (cleanCourseName.isNotBlank()) {
                val cName = if (cleanCourseName.endsWith("코스")) cleanCourseName else "$cleanCourseName 코스"
                " ($cName)"
            } else ""

            val finalClubNameWithCourse = if (formattedCourseSuffix.isNotBlank() && !officialClubName.contains(cleanCourseName)) {
                "$officialClubName$formattedCourseSuffix"
            } else {
                officialClubName
            }

            val formattedMemo = if (courseName.isNotBlank()) "[코스: $courseName] $memo".trim() else memo.trim()
            val newRound = GolfRound(
                clubName = finalClubNameWithCourse,
                roundDate = roundDate,
                golfType = GolfType.FIELD,
                latitude = finalLat,
                longitude = finalLng,
                totalScore = totalScore,
                totalPutts = totalPutts,
                greenFeeExpense = greenFeeExpense,
                memo = formattedMemo.ifBlank { null },
                startTime = startTime,
                endTime = endTime,
                companions = companions.filter { it.isNotBlank() }
            )
            golfRepository.insertGolfRound(newRound)

            // 다이어리 동기화 (해당 라운드 일자의 다이어리에 골프 스텝 반영)
            val playDate = roundDate.toLocalDate()
            val diaryList = runCatching { diaryRepository.getDiaryEntriesByDateRange(playDate, playDate).first() }.getOrDefault(emptyList())
            val existingDiary = diaryList.firstOrNull()

            val golfStep = RouteStep(
                id = UUID.randomUUID().toString(),
                time = startTime,
                stepType = RouteStepType.GOLF,
                title = "$finalClubNameWithCourse 라운드",
                description = "티오프 ${startTime.toLocalTime()} · 스코어: ${totalScore?.let { "${it}타" } ?: "기록없음"}",
                locationName = officialClubName,
                address = "골프장 필드 라운드",
                latitude = finalLat,
                longitude = finalLng,
                category = "골프 라운드",
                companions = companions.filter { it.isNotBlank() }
            )

            if (existingDiary != null) {
                val hasGolf = existingDiary.routeSteps.any { it.stepType == RouteStepType.GOLF || it.title.contains(officialClubName) }
                val updatedSteps = if (!hasGolf) {
                    (listOf(golfStep) + existingDiary.routeSteps).sortedBy { it.time }
                } else {
                    existingDiary.routeSteps.map { s ->
                        if (s.stepType == RouteStepType.GOLF || s.title.contains(officialClubName)) {
                            s.copy(
                                title = "$finalClubNameWithCourse 라운드",
                                description = "티오프 ${startTime.toLocalTime()} · 스코어: ${totalScore?.let { "${it}타" } ?: "기록없음"}",
                                companions = (s.companions + companions.filter { it.isNotBlank() }).distinct()
                            )
                        } else s
                    }
                }
                val updatedDiary = existingDiary.copy(
                    title = if (existingDiary.title.isBlank() || existingDiary.title.contains("일상") || existingDiary.title.contains("하루")) "$finalClubNameWithCourse 라운딩" else existingDiary.title,
                    hasGolfRound = true,
                    routeSteps = updatedSteps,
                    tags = (existingDiary.tags + listOf("⛳ $officialClubName") + companions.filter { it.isNotBlank() }.map { "👤 $it" }).distinct()
                )
                diaryRepository.updateDiaryEntry(updatedDiary)
            } else {
                val newDiary = DiaryEntry(
                    date = startTime,
                    title = "$finalClubNameWithCourse 라운딩",
                    placeName = officialClubName,
                    summary = "티오프 ${startTime.toLocalTime()}, $finalClubNameWithCourse 라운딩 기록",
                    hasGolfRound = true,
                    latitude = finalLat,
                    longitude = finalLng,
                    routeSteps = listOf(golfStep),
                    tags = (listOf("⛳ $officialClubName") + companions.filter { it.isNotBlank() }.map { "👤 $it" }).distinct()
                )
                diaryRepository.insertDiaryEntry(newDiary)
            }

            closeDirectAddDialog()
            loadWeatherForRounds(listOf(newRound), forceRefresh = true)
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
        memo: String,
        latitude: Double? = null,
        longitude: Double? = null
    ) {
        viewModelScope.launch {
            // [정규화 보정] "오크밸리", "오크밸리cc" 등 비공식 입력이 들어오더라도 공식 명칭 및 좌표로 자동 보정
            val (officialClubName, resolvedCoords) = resolveOfficialGolfCourse(clubName.trim())
            val finalLat = latitude ?: resolvedCoords?.first
            val finalLng = longitude ?: resolvedCoords?.second

            // 코스명이 입력되었을 때 구장명 옆에 코스명을 결합 (예: "오크밸리 CC (잣나무 코스)")
            val cleanCourseName = courseName.trim()
            val formattedCourseSuffix = if (cleanCourseName.isNotBlank()) {
                val cName = if (cleanCourseName.endsWith("코스")) cleanCourseName else "$cleanCourseName 코스"
                " ($cName)"
            } else ""

            val finalClubNameWithCourse = if (formattedCourseSuffix.isNotBlank() && !officialClubName.contains(cleanCourseName)) {
                "$officialClubName$formattedCourseSuffix"
            } else {
                officialClubName
            }

            val formattedMemo = if (courseName.isNotBlank()) "[코스: $courseName] $memo".trim() else memo.trim()
            val newRound = GolfRound(
                clubName = finalClubNameWithCourse,
                roundDate = teeOffTime,
                golfType = GolfType.FIELD,
                latitude = finalLat,
                longitude = finalLng,
                greenFeeExpense = estimatedGreenFee,
                memo = formattedMemo.ifBlank { null },
                startTime = teeOffTime,
                endTime = teeOffTime.plusHours(5).plusMinutes(30),
                companions = companions.filter { it.isNotBlank() },
                totalScore = null
            )
            golfRepository.insertGolfRound(newRound)
            closeReservationDialog()
            // 등록 직후 최신 날씨 즉시 수집
            loadWeatherForRounds(listOf(newRound), forceRefresh = true)
        }
    }

    // [정규화 엔진] 사용자가 입력한 구장명을 공식 등록 구장명(예: "오크밸리 CC") 및 정밀 좌표로 자동 변환
    private fun resolveOfficialGolfCourse(rawName: String): Pair<String, Pair<Double, Double>?> {
        val q = rawName.trim()
        val (baseClub, courseFromInput) = splitClubAndCourse(q)
        val targetName = baseClub.ifBlank { q }

        val norm = targetName.replace(Regex("[\\s·_\\-.,/]+"), "")
            .replace("골프장", "", ignoreCase = true)
            .replace("컨트리클럽", "", ignoreCase = true)
            .replace("클럽하우스", "", ignoreCase = true)
            .lowercase(java.util.Locale.KOREA)
        val clean = norm.replace("cc", "").replace("gc", "")

        val knownCourses = listOf(
            Triple("오크밸리 CC", Pair(37.4150, 127.8250), listOf("오크밸리", "오크밸리cc", "오크밸리gc")),
            Triple("코리아 CC", Pair(37.1510, 127.2080), listOf("코리아", "코리아cc", "코리아gc")),
            Triple("골드 CC", Pair(37.2180, 127.1350), listOf("골드", "골드cc", "골드gc")),
            Triple("아난티 코드 GC", Pair(37.7126, 127.5312), listOf("아난티", "아난티코드", "아난티코드gc")),
            Triple("남촌 CC", Pair(37.3321, 127.3524), listOf("남촌", "남촌cc")),
            Triple("이스트밸리 CC", Pair(37.3195, 127.3482), listOf("이스트밸리", "이스트밸리cc")),
            Triple("곤지암 GC", Pair(37.3412, 127.3025), listOf("곤지암", "곤지암gc")),
            Triple("레이크사이드 CC", Pair(37.3150, 127.1850), listOf("레이크사이드", "레이크사이드cc")),
            Triple("수원 CC", Pair(37.2850, 127.1050), listOf("수원", "수원cc")),
            Triple("태광 CC", Pair(37.2750, 127.0980), listOf("태광", "태광cc")),
            Triple("한성 CC", Pair(37.3050, 127.1250), listOf("한성", "한성cc")),
            Triple("기흥 CC", Pair(37.2110, 127.1280), listOf("기흥", "기흥cc")),
            Triple("한원 CC", Pair(37.1680, 127.1290), listOf("한원", "한원cc")),
            Triple("리베라 CC", Pair(37.1980, 127.1190), listOf("리베라", "리베라cc")),
            Triple("플라자 CC 용인", Pair(37.1450, 127.1550), listOf("플라자", "플라자용인", "플라자cc")),
            Triple("서원밸리 CC", Pair(37.7850, 126.9250), listOf("서원밸리", "서원밸리cc")),
            Triple("송추 CC", Pair(37.7650, 126.9450), listOf("송추", "송추cc")),
            Triple("라데나 CC", Pair(37.8450, 127.7050), listOf("라데나", "라데나cc")),
            Triple("사우스스프링스 CC", Pair(37.1524, 127.4215), listOf("사우스스프링스", "사우스스프링스cc")),
            Triple("블랙스톤 이천 GC", Pair(37.1950, 127.5210), listOf("블랙스톤", "블랙스톤이천")),
            Triple("스카이72 / 클럽72", Pair(37.4912, 126.4812), listOf("스카이72", "클럽72")),
            Triple("잭니클라우스 GC", Pair(37.3750, 126.6320), listOf("잭니클라우스", "잭니클라우스gc")),
            Triple("베어크리크 포천", Pair(37.8750, 127.2850), listOf("베어크리크", "베어크리크포천")),
            Triple("스카이밸리 CC", Pair(37.3680, 127.6720), listOf("스카이밸리", "스카이밸리cc")),
            Triple("아리지 CC", Pair(37.1850, 127.6180), listOf("아리지", "아리지cc")),
            Triple("필로스 CC", Pair(37.9150, 127.2580), listOf("필로스", "필로스cc")),
            Triple("킹스데일 GC", Pair(37.0320, 127.8820), listOf("킹스데일", "킹스데일gc"))
        )

        for ((official, coords, aliases) in knownCourses) {
            val normOfficial = official.replace(Regex("[\\s·_\\-.,/]+"), "").lowercase(java.util.Locale.KOREA)
            if (normOfficial == norm || aliases.any { it == norm || it == clean }) {
                val finalName = if (courseFromInput.isNotBlank() && !official.contains(courseFromInput)) {
                    "$official ($courseFromInput)"
                } else {
                    official
                }
                return Pair(finalName, coords)
            }
        }
        val fallbackName = if (courseFromInput.isNotBlank() && !targetName.contains(courseFromInput)) {
            "$targetName ($courseFromInput)"
        } else {
            q
        }
        return Pair(fallbackName, null)
    }

    private fun autoDiscoverPhotosForRound(round: GolfRound) {
        // [H-01] context 파라미터 제거 — appContext 사용
        // [H-06] Dispatchers.IO로 IO 작업 격리 → ANR 방지
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val roundDate = round.roundDate.toLocalDate()
                val photos = historicalDataImporter.scanHistoricalPhotos(appContext, daysBack = 30)
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
            } catch (t: Throwable) {
                // [H-06] catch(Throwable): OOM 등 시스템 레벨 오류까지 안전하게 포획
                android.util.Log.e("GolfViewModel", "사진 자동 발굴 중 오류", t)
            }
        }
    }

    /**
     * [L-04] ViewModel 소멸 시 ScorecardOcrAnalyzer의 ML Kit 네이티브 리소스 해제.
     */
    override fun onCleared() {
        super.onCleared()
        scorecardOcrAnalyzer.close()
    }
}
