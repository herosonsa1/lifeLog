package com.autologue.app.presentation.golf

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer
import com.autologue.app.data.ocr.ScorecardOcrAnalyzer
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.model.GolfPlayWeather
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.getDisplayClubName
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
    val dutchPayTargetRound: GolfRound? = null,
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
            }.sortedByDescending { it.roundDate }
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
        seedSampleRoundIfNeeded()
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

    fun scanScorecard(roundId: Long, imageUri: Uri) {
        // [H-01] Activity Context 대신 appContext(ApplicationContext) 사용
        // [H-02] viewModelScope.launch(Dispatchers.IO)로 OCR을 IO 스레드에서 격리 → ANR 방지
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.value = _uiState.value.copy(isOcrScanning = true)
            // [L-04] 매번 new 대신 ViewModel 멤버 scorecardOcrAnalyzer 재사용
            val result = scorecardOcrAnalyzer.analyzeScorecard(imageUri)
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
        val norm = q.replace(Regex("[\\s·_\\-.,/]+"), "")
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
            Triple("베어크리크 포천", Pair(37.8750, 127.2850), listOf("베어크리크", "베어크리크포천"))
        )

        for ((official, coords, aliases) in knownCourses) {
            val normOfficial = official.replace(Regex("[\\s·_\\-.,/]+"), "").lowercase(java.util.Locale.KOREA)
            if (normOfficial == norm || aliases.any { it == norm || it == clean }) {
                return Pair(official, coords)
            }
        }
        return Pair(q, null)
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
