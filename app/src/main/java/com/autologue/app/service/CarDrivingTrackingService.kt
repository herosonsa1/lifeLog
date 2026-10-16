package com.autologue.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.autologue.app.MainActivity
import com.autologue.app.R
import com.autologue.app.data.preferences.MultiVehiclePreferences
import com.autologue.app.data.preferences.UserLocationPreferences
import com.autologue.app.data.sync.PlaceResolver
import com.autologue.app.domain.model.DiaryEntry
import com.autologue.app.domain.model.RouteStep
import com.autologue.app.domain.model.RouteStepType
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.repository.DiaryRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.util.DrivingWaypoint
import com.autologue.app.util.LocationDistanceUtils
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDateTime
import java.util.Collections
import java.util.UUID
import javax.inject.Inject

/**
 * 차량 블루투스 연결(탑승) 시 백그라운드에서 동작하여 GPS 위치를 수집하고
 * 하차(연결 해제) 시 정밀 이동경로 및 실주행거리를 자동 산출·기록하는 포그라운드 서비스
 */
@AndroidEntryPoint
class CarDrivingTrackingService : Service() {

    @Inject
    lateinit var vehicleRepository: VehicleRepository

    @Inject
    lateinit var userLocationPreferences: UserLocationPreferences

    @Inject
    lateinit var multiVehiclePreferences: MultiVehiclePreferences

    @Inject
    lateinit var diaryRepository: DiaryRepository

    @Inject
    lateinit var placeResolver: PlaceResolver

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var trackingJob: Job? = null

    private val stateLock = Any()
    private var isTracking = false
    private var isStopping = false
    private var activeVehicleId: String = "car_1"
    private var activeVehicleName: String = "차량"
    private var activeLicensePlate: String = ""
    private var startTimeMillis: Long = 0L

    private var wakeLock: PowerManager.WakeLock? = null
    private var locationListener: android.location.LocationListener? = null
    @Volatile
    private var latestLocation: Location? = null
    @Volatile
    private var lastValidLocation: Location? = null
    @Volatile
    private var cumulativeDistanceKm: Double = 0.0
    @Volatile
    private var firstDeparturePointRecorded: Boolean = false
    @Volatile
    private var departureAccuracyMeters: Float = 9999f
    private var lastRecordedLat: Double = 0.0
    private var lastRecordedLng: Double = 0.0
    private var lastRecordedWaypointTime: Long = 0L

    private val waypoints = Collections.synchronizedList(mutableListOf<DrivingWaypoint>())

    companion object {
        private const val TAG = "CarDrivingTracking"
        const val CHANNEL_ID = "car_driving_tracking_channel"
        private const val NOTIFICATION_ID = 2026

        const val ACTION_START_TRACKING = "com.autologue.app.action.START_CAR_TRACKING"
        const val ACTION_STOP_TRACKING = "com.autologue.app.action.STOP_CAR_TRACKING"
        const val ACTION_RECORD_POINT = "com.autologue.app.action.RECORD_CAR_POINT"

        const val EXTRA_VEHICLE_ID = "extra_vehicle_id"
        const val EXTRA_VEHICLE_NAME = "extra_vehicle_name"
        const val EXTRA_LICENSE_PLATE = "extra_license_plate"

        /**
         * 백그라운드 주행 추적 서비스 시작
         */
        fun startTracking(
            context: Context,
            vehicleId: String,
            vehicleName: String,
            licensePlate: String
        ) {
            val intent = Intent(context, CarDrivingTrackingService::class.java).apply {
                action = ACTION_START_TRACKING
                putExtra(EXTRA_VEHICLE_ID, vehicleId)
                putExtra(EXTRA_VEHICLE_NAME, vehicleName)
                putExtra(EXTRA_LICENSE_PLATE, licensePlate)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /**
         * 백그라운드 주행 추적 서비스 종료 및 결과 기록
         */
        fun stopTracking(context: Context) {
            val intent = Intent(context, CarDrivingTrackingService::class.java).apply {
                action = ACTION_STOP_TRACKING
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "stopTracking startService 실패", e)
            }
        }

        /**
         * 테스트 또는 즉시 위치 기록 트리거
         */
        fun recordPoint(context: Context) {
            val intent = Intent(context, CarDrivingTrackingService::class.java).apply {
                action = ACTION_RECORD_POINT
            }
            try {
                context.startService(intent)
            } catch (e: Throwable) {
                Log.e(TAG, "recordPoint startService 실패", e)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == null) {
            // OS에 의해 START_STICKY로 서비스가 재기동된 경우 이전 주행 세션 자동 복원
            if (!isTracking && restoreTrackingStateFromPrefs()) {
                Log.i(TAG, "START_STICKY 서비스 재기동 감지: 저장된 주행 추적 세션 복원 ($activeVehicleName)")
                startForegroundAndLocationLoop()
                return START_STICKY
            }
            return START_NOT_STICKY
        }

        when (action) {
            ACTION_START_TRACKING -> {
                val vId = intent.getStringExtra(EXTRA_VEHICLE_ID) ?: "car_1"
                val vName = intent.getStringExtra(EXTRA_VEHICLE_NAME) ?: "차량"
                val plate = intent.getStringExtra(EXTRA_LICENSE_PLATE) ?: ""
                startTrackingInternal(vId, vName, plate)
            }

            ACTION_STOP_TRACKING -> {
                stopTrackingInternal()
            }

            ACTION_RECORD_POINT -> {
                if (!isTracking) restoreTrackingStateFromPrefs()
                serviceScope.launch {
                    captureCurrentWaypoint(isDeparture = false, isDestination = false)
                }
            }
        }

        return START_STICKY
    }

    private fun startTrackingInternal(vehicleId: String, vehicleName: String, licensePlate: String) {
        synchronized(stateLock) {
            if (isTracking) {
                Log.d(TAG, "이미 주행 추적 중입니다: $activeVehicleName")
                return
            }
            if (isStopping) {
                Log.d(TAG, "이전 주행 종료 정리 중이므로 새로운 추적을 잠시 대기합니다.")
                return
            }
            isTracking = true
            isStopping = false
            activeVehicleId = vehicleId
            activeVehicleName = vehicleName
            activeLicensePlate = licensePlate
            startTimeMillis = System.currentTimeMillis()
            waypoints.clear()
            cumulativeDistanceKm = 0.0
            lastValidLocation = null
            firstDeparturePointRecorded = false
            departureAccuracyMeters = 9999f
            lastRecordedLat = 0.0
            lastRecordedLng = 0.0
            lastRecordedWaypointTime = 0L
            latestLocation = null
            saveWaypointsToPrefs()
        }

        startForegroundAndLocationLoop()

        // 출발지 GPS 즉시 수집
        serviceScope.launch {
            captureCurrentWaypoint(isDeparture = true, isDestination = false)
        }
    }

    private fun startForegroundAndLocationLoop() {
        // 0. 화면 꺼짐 시 Doze/CPU 절전 방지를 위한 WakeLock 획득 (최대 10시간 타임아웃)
        try {
            val pm = getSystemService(Context.POWER_SERVICE) as? PowerManager
            wakeLock = pm?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoLogue:CarTrackingWakeLock")?.apply {
                setReferenceCounted(false)
                acquire(10 * 60 * 60 * 1000L)
            }
            Log.d(TAG, "WakeLock 획득 완료 (화면 꺼짐 시 실시간 GPS 백그라운드 수신 보장)")
        } catch (e: Throwable) {
            Log.e(TAG, "WakeLock 획득 실패", e)
        }

        // 1. 포그라운드 서비스 알림 등록 (Android 14+ 위치 타입 명시 및 SecurityException 안전 폴백)
        val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
        val initialNotification = buildNotification("$brandEmoji [$activeVehicleName] 탑승 운행 시작", "블루투스 감지 탑승 중 · 실시간 GPS 경로 수집 시작")

        var isForegroundStarted = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val hasFineLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_FINE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
            val hasCoarseLocation = androidx.core.content.ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.ACCESS_COARSE_LOCATION
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            // 1단계: 위치 권한 보유 시 FOREGROUND_SERVICE_TYPE_LOCATION 시도
            if (hasFineLocation || hasCoarseLocation) {
                try {
                    val fgsType = ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, fgsType)
                    isForegroundStarted = true
                    Log.d(TAG, "포그라운드 서비스 location 타입 기동 성공")
                } catch (sec: SecurityException) {
                    Log.w(TAG, "Android 14 백그라운드 FGS location 타입 기동 제한 감지. dataSync 또는 일반 타입으로 다운그레이드", sec)
                } catch (t: Throwable) {
                    Log.e(TAG, "FGS location 기동 중 예외 발생", t)
                }
            }

            // 2단계: 1단계 실패 시 FOREGROUND_SERVICE_TYPE_DATA_SYNC 시도 (Android 14)
            if (!isForegroundStarted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                try {
                    ServiceCompat.startForeground(this, NOTIFICATION_ID, initialNotification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
                    isForegroundStarted = true
                    Log.d(TAG, "포그라운드 서비스 dataSync 타입 안전 기동 성공")
                } catch (sec: SecurityException) {
                    Log.w(TAG, "FGS dataSync 기동 제한 감지. 기본 타입으로 폴백", sec)
                } catch (t: Throwable) {
                    Log.e(TAG, "FGS dataSync 기동 예외", t)
                }
            }
        }

        // 3단계: 최후의 안전 폴백 (기본 startForeground)
        if (!isForegroundStarted) {
            try {
                startForeground(NOTIFICATION_ID, initialNotification)
                isForegroundStarted = true
                Log.d(TAG, "포그라운드 서비스 기본 타입 기동 성공")
            } catch (t: Throwable) {
                Log.e(TAG, "기본 startForeground 호출 실패. 크래시 방지 및 서비스 안전 종료", t)
                synchronized(stateLock) {
                    isTracking = false
                    isStopping = false
                }
                stopSelf()
                return
            }
        }

        // 2. 실시간 GPS LocationListener 가동 (GPS + 패시브 내비게이션 + 네트워크)
        startLocationUpdates()

        // 3. 백그라운드 보조 점검 및 알림 갱신 코루틴 가동 (3분 주기)
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            Log.d(TAG, "GPS 백그라운드 주기적 점검 루프 시작: [$activeVehicleName] ($activeLicensePlate)")
            while (isActive && isTracking) {
                delay(3 * 60 * 1000L) // 3분 대기
                if (!isTracking) break

                // 실시간 리스너로 최근 3분간 새 waypoint가 기록되지 않은 경우 강제 샘플링
                if (System.currentTimeMillis() - lastRecordedWaypointTime >= 3 * 60 * 1000L) {
                    captureCurrentWaypoint(isDeparture = false, isDestination = false)
                }
                updateOngoingNotification()
            }
        }
    }

    private fun stopTrackingInternal() {
        synchronized(stateLock) {
            if (isStopping) {
                Log.d(TAG, "이미 주행 종료 처리가 진행 중입니다. (중복 stop 명령 무시)")
                return
            }
            if (!isTracking) {
                // 프로세스 재시작 등으로 인메모리 상태가 초기화된 경우 SharedPreferences에서 주행 세션 복원 시도
                val restored = restoreTrackingStateFromPrefs()
                if (!restored) {
                    Log.d(TAG, "추적 중이 아닙니다. 서비스 안전 종료")
                    stopLocationUpdates()
                    releaseWakeLock()
                    serviceScope.launch(Dispatchers.Main) {
                        try {
                            ServiceCompat.stopForeground(this@CarDrivingTrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                            stopSelf()
                        } catch (e: Throwable) {
                            Log.e(TAG, "안전 종료 실패", e)
                        }
                    }
                    return
                }
                Log.i(TAG, "프로세스 재시작 후 영속 저장소에서 주행 세션을 성공적으로 복원하여 주행 종료 기록을 진행합니다: $activeVehicleName (waypoints=${waypoints.size})")
            }
            isStopping = true
            isTracking = false
        }

        stopLocationUpdates()
        trackingJob?.cancel()

        serviceScope.launch {
            try {
                // 1. 도착지 GPS 수집
                captureCurrentWaypoint(isDeparture = false, isDestination = true)

                // 2. 총 운행 시간 및 거리 산출
                val nowTime = System.currentTimeMillis()
                val durationMin = if (startTimeMillis > 0L) {
                    ((nowTime - startTimeMillis) / 60000).coerceAtLeast(1)
                } else 1L

                // 2분 미만의 초단기 연결 해제는 단순 시동 켬/끔으로 간주하여 무시
                if (durationMin < 2 && waypoints.size <= 2) {
                    Log.d(TAG, "운행 시간 2분 미만 (${durationMin}분)으로 주행 기록 스킵")
                    clearSavedWaypoints()
                    return@launch
                }

                // 1. 유효한 GPS 포인트 필터링 (0.0 더미 배제)
                val validWaypoints = synchronized(waypoints) {
                    waypoints.filter { LocationDistanceUtils.isValidCoordinate(it.latitude, it.longitude) }
                }

                // 2. 출발지 및 도착지 좌표 확정 (GPS 최초/최종 지점 + 블루투스 리시버 캡처 좌표 + 거점 시간대 폴백)
                val prefs = getSharedPreferences("car_trip_tracking", Context.MODE_PRIVATE)
                val prefStartLat = prefs.getFloat("start_lat", 0.0f).toDouble()
                val prefStartLng = prefs.getFloat("start_lng", 0.0f).toDouble()
                val prefEndLat = prefs.getFloat("end_lat", 0.0f).toDouble()
                val prefEndLng = prefs.getFloat("end_lng", 0.0f).toDouble()

                val firstPoint = validWaypoints.firstOrNull()
                val lastPoint = validWaypoints.lastOrNull()

                var startLat = when {
                    firstPoint != null -> firstPoint.latitude
                    LocationDistanceUtils.isValidCoordinate(prefStartLat, prefStartLng) -> prefStartLat
                    latestLocation != null && LocationDistanceUtils.isValidCoordinate(latestLocation!!.latitude, latestLocation!!.longitude) -> latestLocation!!.latitude
                    else -> 0.0
                }
                var startLng = when {
                    firstPoint != null -> firstPoint.longitude
                    LocationDistanceUtils.isValidCoordinate(prefStartLat, prefStartLng) -> prefStartLng
                    latestLocation != null && LocationDistanceUtils.isValidCoordinate(latestLocation!!.latitude, latestLocation!!.longitude) -> latestLocation!!.longitude
                    else -> 0.0
                }

                var endLat = when {
                    lastPoint != null && (validWaypoints.size >= 2 || lastPoint.isDestination) -> lastPoint.latitude
                    LocationDistanceUtils.isValidCoordinate(prefEndLat, prefEndLng) -> prefEndLat
                    latestLocation != null && LocationDistanceUtils.isValidCoordinate(latestLocation!!.latitude, latestLocation!!.longitude) -> latestLocation!!.latitude
                    lastPoint != null -> lastPoint.latitude
                    else -> startLat
                }
                var endLng = when {
                    lastPoint != null && (validWaypoints.size >= 2 || lastPoint.isDestination) -> lastPoint.longitude
                    LocationDistanceUtils.isValidCoordinate(prefEndLat, prefEndLng) -> prefEndLng
                    latestLocation != null && LocationDistanceUtils.isValidCoordinate(latestLocation!!.latitude, latestLocation!!.longitude) -> latestLocation!!.longitude
                    lastPoint != null -> lastPoint.longitude
                    else -> startLng
                }

                val config = userLocationPreferences.config.value
                val homeLat = config.homeLat
                val homeLng = config.homeLng
                val compLat = config.companyLat
                val compLng = config.companyLng
                val hasCommuteProfile = config.isConfigured ||
                    (homeLat != 0.0 && compLat != 0.0) ||
                    (config.homeName.isNotBlank() && config.companyName.isNotBlank())

                val startHour = if (startTimeMillis > 0L) {
                    java.time.Instant.ofEpochMilli(startTimeMillis)
                        .atZone(java.time.ZoneId.systemDefault()).hour
                } else LocalDateTime.now().minusMinutes(durationMin).hour
                val endHour = LocalDateTime.now().hour

                // 거점 반경 1.5km(1500m) 매칭 (지하주차장 기지국/Wi-Fi 대략 위치 오차 수용)
                val distHomeToStart = distanceMeter(homeLat, homeLng, startLat, startLng)
                val distCompToEnd = distanceMeter(compLat, compLng, endLat, endLng)
                val distCompToStart = distanceMeter(compLat, compLng, startLat, startLng)
                val distHomeToEnd = distanceMeter(homeLat, homeLng, endLat, endLng)

                val nearHomeStart = homeLat != 0.0 && distHomeToStart < 1500.0
                val nearCompEnd = compLat != 0.0 && distCompToEnd < 1500.0
                val nearCompStart = compLat != 0.0 && distCompToStart < 1500.0
                val nearHomeEnd = homeLat != 0.0 && distHomeToEnd < 1500.0

                val isEndpointsMissingOrSame = (startLat == 0.0 || endLat == 0.0 || distanceMeter(startLat, startLng, endLat, endLng) < 400.0)
                val isMorningCommuteWindow = (startHour in 5..10 || endHour in 6..11) && durationMin >= 15
                val isEveningCommuteWindow = (startHour in 16..23 || endHour in 16..23) && durationMin >= 15

                val isToWorkCommute = hasCommuteProfile && (
                    (nearHomeStart && nearCompEnd) ||
                    ((nearHomeStart || nearCompEnd) && isMorningCommuteWindow && !nearCompStart) ||
                    (isEndpointsMissingOrSame && isMorningCommuteWindow && !nearCompStart && !nearHomeEnd)
                )
                val isToHomeCommute = !isToWorkCommute && hasCommuteProfile && (
                    (nearCompStart && nearHomeEnd) ||
                    ((nearCompStart || nearHomeEnd) && isEveningCommuteWindow && !nearHomeStart) ||
                    (isEndpointsMissingOrSame && isEveningCommuteWindow && !nearHomeStart && !nearCompEnd)
                )

                // 출퇴근으로 판별되었는데 지하주차장/권한 제한으로 출발·도착 중 일부 좌표가 누락된 경우 거점 좌표로 보완
                if (isToWorkCommute) {
                    if (homeLat != 0.0 && (startLat == 0.0 || !nearHomeStart)) {
                        startLat = homeLat
                        startLng = homeLng
                    }
                    if (compLat != 0.0 && (endLat == 0.0 || !nearCompEnd || distanceMeter(startLat, startLng, endLat, endLng) < 400.0)) {
                        endLat = compLat
                        endLng = compLng
                    }
                } else if (isToHomeCommute) {
                    if (compLat != 0.0 && (startLat == 0.0 || !nearCompStart)) {
                        startLat = compLat
                        startLng = compLng
                    }
                    if (homeLat != 0.0 && (endLat == 0.0 || !nearHomeEnd || distanceMeter(startLat, startLng, endLat, endLng) < 400.0)) {
                        endLat = homeLat
                        endLng = homeLng
                    }
                }

                // 3. 주행거리 정밀 산출: 스파이크가 제거된 궤적 누적거리(calculatedDistanceKm)와 출발-도착 도로거리(directDistanceKm) 대조
                val calculatedDistanceKm = LocationDistanceUtils.calculateWaypointsDistanceKm(validWaypoints)
                val directDistanceKm = if (startLat != 0.0 && endLat != 0.0 && (startLat != endLat || startLng != endLng)) {
                    LocationDistanceUtils.calculateDrivingDistanceKm(startLat, startLng, endLat, endLng)
                } else 0.0

                val rawTrajectoryKm = if (calculatedDistanceKm > 0.0) calculatedDistanceKm else cumulativeDistanceKm

                var finalTripKm = if (isToWorkCommute || isToHomeCommute) {
                    val refCommuteKm = when {
                        config.commuteOneWayKm > 0.0 -> config.commuteOneWayKm
                        directDistanceKm > 0.0 -> directDistanceKm
                        else -> 27.1
                    }
                    when {
                        // 궤적 거리가 기준 출퇴근 편도 거리의 정상 오차 범위(75% ~ 125%) 내이면 실측 궤적 채택
                        rawTrajectoryKm in (refCommuteKm * 0.75)..(refCommuteKm * 1.25) -> rawTrajectoryKm
                        // 출발-도착 도로거리가 정상 범위 내이면 출발-도착 도로거리 채택
                        directDistanceKm in (refCommuteKm * 0.75)..(refCommuteKm * 1.25) -> directDistanceKm
                        // 그 외(기지국 핑퐁으로 과다 누적되었거나 지하주차장 음영으로 부족한 경우) 기준 편도 거리 채택
                        else -> refCommuteKm
                    }
                } else {
                    var candidateKm = maxOf(rawTrajectoryKm, directDistanceKm)
                    // 단일 편도 이동(출발지 != 도착지)에서 GPS/기지국 노이즈로 직선 기반 도로거리 대비 1.35배를 초과한 경우 보정
                    if (directDistanceKm >= 2.0 && distanceMeter(startLat, startLng, endLat, endLng) >= 1500.0 && candidateKm > directDistanceKm * 1.35) {
                        candidateKm = directDistanceKm
                    }
                    // 운행 시간 대비 물리적으로 불가능한 도심 과속 누적치(분당 1.1km 초과) 상한 방어
                    val maxByDurationKm = (durationMin * 1.1).coerceAtLeast(directDistanceKm)
                    if (durationMin > 0 && candidateKm > maxByDurationKm && directDistanceKm > 0.0) {
                        candidateKm = directDistanceKm
                    }
                    candidateKm
                }
                finalTripKm = Math.round(finalTripKm * 10.0) / 10.0

                // [안전망] 3분 이상 운행했으나 GPS 음영(지하 등)으로 0.0km인 경우, 시내 평균속도 기준 최소 주행거리 추정 반영
                if (finalTripKm == 0.0 && durationMin >= 3) {
                    val estimatedKm = Math.round((durationMin / 60.0 * 20.0) * 10.0) / 10.0
                    finalTripKm = estimatedKm.coerceIn(0.5, 30.0)
                    Log.d(TAG, "GPS 미수신 구간 운행(${durationMin}분) 추정 거리 반영: ${finalTripKm}km")
                }

                // 4. 출발지 및 도착지 대략 위치를 주소 변환하여 깔끔한 동네명(시/구 + 동)으로 해석
                val startHint = when {
                    isToWorkCommute || nearHomeStart -> config.homeAddress.ifBlank { config.homeName }
                    isToHomeCommute || nearCompStart -> config.companyAddress.ifBlank { config.companyName }
                    else -> ""
                }
                val endHint = when {
                    isToWorkCommute || nearCompEnd -> config.companyAddress.ifBlank { config.companyName }
                    isToHomeCommute || nearHomeEnd -> config.homeAddress.ifBlank { config.homeName }
                    else -> ""
                }

                val startPlace = resolveLocationName(startLat, startLng, isDeparture = true, fallbackAddressHint = startHint)
                val endPlace = resolveLocationName(endLat, endLng, isDeparture = false, fallbackAddressHint = endHint)

                val routeTitle = when {
                    startPlace.isNotBlank() && endPlace.isNotBlank() && startPlace != endPlace -> {
                        "$startPlace ➔ $endPlace (%.1f km)".format(finalTripKm)
                    }
                    startPlace.isNotBlank() && startPlace == endPlace -> {
                        // 동일 지점(왕복 등) 주행 시 가장 멀리 이동했던 경유 지점이 있으면 경유지 포함 표시
                        val furthestWp = validWaypoints.maxByOrNull { wp ->
                            distanceMeter(startLat, startLng, wp.latitude, wp.longitude)
                        }
                        val maxDist = if (furthestWp != null) {
                            distanceMeter(startLat, startLng, furthestWp.latitude, furthestWp.longitude)
                        } else 0.0
                        val midPlace = if (furthestWp != null && maxDist >= 400.0) {
                            resolveLocationName(furthestWp.latitude, furthestWp.longitude, isDeparture = false)
                        } else ""

                        if (midPlace.isNotBlank() && midPlace != startPlace) {
                            "$startPlace ➔ $midPlace ➔ $endPlace (%.1f km)".format(finalTripKm)
                        } else {
                            "$startPlace ➔ $endPlace (%.1f km)".format(finalTripKm)
                        }
                    }
                    startPlace.isNotBlank() -> {
                        "$startPlace 인근 주행 (%.1f km)".format(finalTripKm)
                    }
                    endPlace.isNotBlank() -> {
                        "$endPlace 도착 주행 (%.1f km)".format(finalTripKm)
                    }
                    else -> {
                        "차량 주행 완료 (%.1f km)".format(finalTripKm)
                    }
                }

                val carTag = "[$activeVehicleId: $activeVehicleName]"
                val pointCount = validWaypoints.size.coerceAtLeast(if (startLat != 0.0 && endLat != 0.0 && startLat != endLat) 2 else if (startLat != 0.0) 1 else 0)
                val detailSub = if (validWaypoints.isNotEmpty()) {
                    "(${durationMin}분 운행 · GPS ${pointCount}개 지점)"
                } else {
                    "(${durationMin}분 운행 · 대략 위치 동네명 반영)"
                }

                val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""

                // 5. 출퇴근 주행 또는 일반 주행 기록 저장
                if (isToWorkCommute) {
                    val tripKm = if (config.commuteOneWayKm > 0.0 && finalTripKm < config.commuteOneWayKm * 0.6) config.commuteOneWayKm else finalTripKm
                    val commuteTitle = "$startPlace ➔ $endPlace (출근 %.1f km)".format(tripKm)
                    val log = VehicleLog(
                        timestamp = LocalDateTime.now(),
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = tripKm,
                        note = "$carTag [$commuteTitle] $detailSub"
                    )
                    vehicleRepository.insertVehicleLog(log)
                    Log.d(TAG, "출근 주행 기록 완료: [$activeVehicleName$carPlatePrefix] $commuteTitle")
                } else if (isToHomeCommute) {
                    val tripKm = if (config.commuteOneWayKm > 0.0 && finalTripKm < config.commuteOneWayKm * 0.6) config.commuteOneWayKm else finalTripKm
                    val commuteTitle = "$startPlace ➔ $endPlace (퇴근 %.1f km)".format(tripKm)
                    val log = VehicleLog(
                        timestamp = LocalDateTime.now(),
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = tripKm,
                        note = "$carTag [$commuteTitle] $detailSub"
                    )
                    vehicleRepository.insertVehicleLog(log)
                    Log.d(TAG, "퇴근 주행 기록 완료: [$activeVehicleName$carPlatePrefix] $commuteTitle")
                } else {
                    // 일반 차량 주행 기록 저장
                    val log = VehicleLog(
                        timestamp = LocalDateTime.now(),
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = finalTripKm,
                        note = "$carTag [$routeTitle] $detailSub"
                    )
                    vehicleRepository.insertVehicleLog(log)
                    Log.d(TAG, "일반 주행 자동 기록 완료: [$activeVehicleName$carPlatePrefix] $routeTitle")
                }

                // 다이어리 이동동선에 승차(출발) 및 하차(도착) 시점의 RouteStep만 저장 (중간 주행 경유지 제외)
                saveWaypointsToDiary(
                    finalTripKm = finalTripKm,
                    durationMin = durationMin,
                    startLat = startLat,
                    startLng = startLng,
                    startPlace = startPlace,
                    endLat = endLat,
                    endLng = endLng,
                    endPlace = endPlace
                )

                // 완료 알림 표출
                showTripCompleteNotification(finalTripKm, durationMin)
                clearSavedWaypoints()

            } catch (t: Throwable) {
                Log.e(TAG, "주행 종료 처리 중 오류", t)
            } finally {
                releaseWakeLock()
                // [안정성 보장] 메인 UI 스레드로 전환하여 안전하게 서비스 포그라운드 해제 및 종료
                withContext(Dispatchers.Main) {
                    try {
                        ServiceCompat.stopForeground(this@CarDrivingTrackingService, ServiceCompat.STOP_FOREGROUND_REMOVE)
                        stopSelf()
                        Log.d(TAG, "CarDrivingTrackingService 안전 정상 종료 완료")
                    } catch (e: Throwable) {
                        Log.e(TAG, "서비스 stopSelf/stopForeground 중 오류", e)
                    } finally {
                        synchronized(stateLock) {
                            isStopping = false
                        }
                    }
                }
            }
        }
    }

    /**
     * 차량 이동 중 수집된 중간 GPS 경유 지점(주행 경유)은 주행거리 산출에만 사용하고 방문경로 항목에서 제외하며,
     * 오직 승차(출발) 및 하차(도착) 시점에만 당일 다이어리의 RouteStep으로 등록합니다.
     */
    private suspend fun saveWaypointsToDiary(
        finalTripKm: Double,
        durationMin: Long,
        startLat: Double,
        startLng: Double,
        startPlace: String,
        endLat: Double,
        endLng: Double,
        endPlace: String
    ) {
        try {
            val validList = synchronized(waypoints) {
                waypoints.filter { LocationDistanceUtils.isValidCoordinate(it.latitude, it.longitude) }
            }
            val hasValidStart = LocationDistanceUtils.isValidCoordinate(startLat, startLng)
            val hasValidEnd = LocationDistanceUtils.isValidCoordinate(endLat, endLng)

            if (validList.isEmpty() && !hasValidStart && !hasValidEnd) {
                Log.d(TAG, "유효한 승차/하차 GPS 좌표가 없어 다이어리 RouteStep 저장을 스킵합니다.")
                return
            }

            val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
            val vehicleTag = activeVehicleName.split(" ").firstOrNull() ?: activeVehicleName
            val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""

            val firstWp = validList.firstOrNull()
            val lastWp = validList.lastOrNull()

            val depLat = if (hasValidStart) startLat else firstWp?.latitude ?: 0.0
            val depLng = if (hasValidStart) startLng else firstWp?.longitude ?: 0.0
            val arrLat = if (hasValidEnd) endLat else lastWp?.latitude ?: depLat
            val arrLng = if (hasValidEnd) endLng else lastWp?.longitude ?: depLng

            val depTimeMillis = when {
                firstWp != null && firstWp.timestamp > 0L -> firstWp.timestamp
                startTimeMillis > 0L -> startTimeMillis
                else -> System.currentTimeMillis() - (durationMin * 60_000L)
            }
            val arrTimeMillis = when {
                lastWp != null && lastWp.timestamp > depTimeMillis -> lastWp.timestamp
                else -> System.currentTimeMillis().coerceAtLeast(depTimeMillis + 60_000L)
            }

            val depTime = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(depTimeMillis),
                java.time.ZoneId.systemDefault()
            )
            val arrTime = java.time.LocalDateTime.ofInstant(
                java.time.Instant.ofEpochMilli(arrTimeMillis),
                java.time.ZoneId.systemDefault()
            )

            val depResolved = if (LocationDistanceUtils.isValidCoordinate(depLat, depLng)) {
                placeResolver.resolveGeoLocation(this@CarDrivingTrackingService, depLat, depLng)
            } else null
            val arrResolved = if (LocationDistanceUtils.isValidCoordinate(arrLat, arrLng)) {
                placeResolver.resolveGeoLocation(this@CarDrivingTrackingService, arrLat, arrLng)
            } else null

            val depPlaceName = startPlace.ifBlank { depResolved?.placeName.orEmpty() }
            val arrPlaceName = endPlace.ifBlank { arrResolved?.placeName.orEmpty() }
            val depAddress = depResolved?.address?.ifBlank { depPlaceName } ?: depPlaceName
            val arrAddress = arrResolved?.address?.ifBlank { arrPlaceName } ?: arrPlaceName

            val newRouteSteps = mutableListOf<RouteStep>()

            // 1. 승차(출발) 스텝
            if (LocationDistanceUtils.isValidCoordinate(depLat, depLng) || depPlaceName.isNotBlank()) {
                val boardingDesc = when {
                    depPlaceName.isNotBlank() && arrPlaceName.isNotBlank() && depPlaceName != arrPlaceName ->
                        "승차: $depPlaceName ➔ $arrPlaceName"
                    depPlaceName.isNotBlank() ->
                        "승차 위치: $depPlaceName"
                    else -> "차량 승차 (출발)"
                }
                newRouteSteps.add(
                    RouteStep(
                        id = UUID.randomUUID().toString(),
                        time = depTime,
                        stepType = RouteStepType.DRIVING,
                        title = "$brandEmoji [$activeVehicleName] 승차 (출발)",
                        description = boardingDesc,
                        locationName = depPlaceName.ifBlank { null },
                        address = depAddress.ifBlank { null },
                        latitude = if (LocationDistanceUtils.isValidCoordinate(depLat, depLng)) depLat else null,
                        longitude = if (LocationDistanceUtils.isValidCoordinate(depLat, depLng)) depLng else null,
                        category = "차계부",
                        tags = listOf("차량주행", "$brandEmoji $vehicleTag", "승차", "출발지점")
                    )
                )
            }

            // 2. 하차(도착) 스텝
            if (LocationDistanceUtils.isValidCoordinate(arrLat, arrLng) || arrPlaceName.isNotBlank()) {
                val alightingDesc = when {
                    depPlaceName.isNotBlank() && arrPlaceName.isNotBlank() ->
                        "$depPlaceName ➔ $arrPlaceName (${durationMin}분 운행 · %.1f km)".format(finalTripKm)
                    arrPlaceName.isNotBlank() ->
                        "하차 위치: $arrPlaceName (${durationMin}분 운행 · %.1f km)".format(finalTripKm)
                    else ->
                        "차량 하차 (${durationMin}분 운행 · %.1f km)".format(finalTripKm)
                }
                newRouteSteps.add(
                    RouteStep(
                        id = UUID.randomUUID().toString(),
                        time = if (arrTime.isAfter(depTime)) arrTime else depTime.plusMinutes(durationMin.coerceAtLeast(1)),
                        stepType = RouteStepType.DRIVING,
                        title = "$brandEmoji [$activeVehicleName] 하차 (총 %.1f km)".format(finalTripKm),
                        description = alightingDesc,
                        locationName = arrPlaceName.ifBlank { null },
                        address = arrAddress.ifBlank { null },
                        latitude = if (LocationDistanceUtils.isValidCoordinate(arrLat, arrLng)) arrLat else null,
                        longitude = if (LocationDistanceUtils.isValidCoordinate(arrLat, arrLng)) arrLng else null,
                        category = "차계부",
                        tags = listOf("차량주행", "$brandEmoji $vehicleTag", "하차", "도착지점")
                    )
                )
            }

            val today = java.time.LocalDate.now()
            val diaryTitle = when {
                depPlaceName.isNotBlank() && arrPlaceName.isNotBlank() && depPlaceName != arrPlaceName ->
                    "$depPlaceName & $arrPlaceName"
                arrPlaceName.isNotBlank() -> "$arrPlaceName 일정"
                depPlaceName.isNotBlank() -> "$depPlaceName 일정"
                else -> "${today.monthValue}월 ${today.dayOfMonth}일의 다이어리"
            }
            val diaryEntry = DiaryEntry(
                date = today.atTime(java.time.LocalTime.now()),
                title = diaryTitle,
                summary = "$brandEmoji [$activeVehicleName$carPlatePrefix] $depPlaceName ➔ $arrPlaceName (${durationMin}분 운행, 총 %.1f km)".format(finalTripKm),
                drivingDistanceKm = finalTripKm,
                routeSteps = newRouteSteps,
                tags = listOf("차량주행", "$brandEmoji $vehicleTag")
            )

            diaryRepository.insertDiaryEntry(diaryEntry)
            Log.d(TAG, "다이어리 승차/하차 RouteStep ${newRouteSteps.size}개 자동 저장 완료: [$activeVehicleName]")
        } catch (e: Throwable) {
            Log.e(TAG, "다이어리 RouteStep 저장 중 오류 발생", e)
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
                Log.d(TAG, "WakeLock 안전 해제 완료")
            }
        } catch (e: Throwable) {
            Log.e(TAG, "WakeLock 해제 중 예외", e)
        } finally {
            wakeLock = null
        }
    }

    /**
     * 현재 시점의 GPS 또는 기지국/Wi-Fi 대략 위치를 비동기로 획득하며,
     * 지하주차장 등 위성 음영 지역에서는 기기의 최근 마지막 위치(LastKnownLocation)까지 다단계로 폴백합니다.
     */
    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(
        timeoutMs: Long = 2500L,
        allowStaleFallback: Boolean = true
    ): Location? {
        val now = System.currentTimeMillis()
        // 1. 60초 이내의 유효한 실시간 최신 위치가 이미 있으면 즉시 반환
        latestLocation?.let { loc ->
            if (now - loc.time in 0..60_000L && LocationDistanceUtils.isValidCoordinate(loc.latitude, loc.longitude)) {
                return loc
            }
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return latestLocation

        // 2. Android 11+ (API 30+) getCurrentLocation 비동기 요청 (FUSED -> NETWORK -> GPS 순으로 지하주차장에서도 빠른 응답 유도)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val candidateProviders = mutableListOf<String>()
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                candidateProviders.add(LocationManager.FUSED_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                candidateProviders.add(LocationManager.NETWORK_PROVIDER)
            }
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                candidateProviders.add(LocationManager.GPS_PROVIDER)
            }

            for (provider in candidateProviders) {
                val fresh = withTimeoutOrNull(timeoutMs) {
                    suspendCancellableCoroutine<Location?> { cont ->
                        val cancelSignal = android.os.CancellationSignal()
                        cont.invokeOnCancellation { cancelSignal.cancel() }
                        try {
                            lm.getCurrentLocation(
                                provider,
                                cancelSignal,
                                androidx.core.content.ContextCompat.getMainExecutor(this@CarDrivingTrackingService)
                            ) { loc ->
                                if (cont.isActive) cont.resume(loc) {}
                            }
                        } catch (t: Throwable) {
                            if (cont.isActive) cont.resume(null) {}
                        }
                    }
                }
                if (fresh != null && LocationDistanceUtils.isValidCoordinate(fresh.latitude, fresh.longitude)) {
                    latestLocation = fresh
                    return fresh
                }
            }
        }

        // 3. 폴백: getLastKnownLocation (1차: 30분 이내 신선 위치 -> 2차: 지하주차장 주차 위치 보존을 위해 24시간 이내 마지막 위치)
        return try {
            val providers = mutableListOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                providers.add(0, LocationManager.FUSED_PROVIDER)
            }

            var bestRecent: Location? = null
            var bestFallback: Location? = null
            for (p in providers) {
                val loc = runCatching { lm.getLastKnownLocation(p) }.getOrNull()
                if (loc != null && LocationDistanceUtils.isValidCoordinate(loc.latitude, loc.longitude)) {
                    val age = (now - loc.time).coerceAtLeast(0L)
                    if (age <= 30 * 60 * 1000L) {
                        if (bestRecent == null || loc.time > bestRecent.time) {
                            bestRecent = loc
                        }
                    } else if (allowStaleFallback && age <= 24 * 60 * 60 * 1000L) {
                        if (bestFallback == null || loc.time > bestFallback.time) {
                            bestFallback = loc
                        }
                    }
                }
            }
            bestRecent ?: bestFallback ?: latestLocation
        } catch (e: Exception) {
            latestLocation
        }
    }

    /**
     * 출발지/도착지 대략 위치(위경도) 및 거점 주소 힌트를 역지오코딩하여
     * 깔끔한 동네명(예: "송파구 위례동", "영등포구 영등포동", "강남구 역삼동")으로 변환합니다.
     */
    private suspend fun resolveLocationName(
        lat: Double,
        lng: Double,
        isDeparture: Boolean,
        fallbackAddressHint: String = ""
    ): String {
        val config = runCatching { userLocationPreferences.config.value }.getOrNull()
        val homeLat = config?.homeLat ?: 0.0
        val homeLng = config?.homeLng ?: 0.0
        val compLat = config?.companyLat ?: 0.0
        val compLng = config?.companyLng ?: 0.0

        // 거점 반경 1.5km 이내 매칭 시 거점 주소 힌트 자동 결합
        val effectiveHint = when {
            fallbackAddressHint.isNotBlank() -> fallbackAddressHint
            LocationDistanceUtils.isValidCoordinate(lat, lng) && homeLat != 0.0 && distanceMeter(homeLat, homeLng, lat, lng) < 1500.0 -> {
                config?.homeAddress?.ifBlank { config.homeName } ?: ""
            }
            LocationDistanceUtils.isValidCoordinate(lat, lng) && compLat != 0.0 && distanceMeter(compLat, compLng, lat, lng) < 1500.0 -> {
                config?.companyAddress?.ifBlank { config.companyName } ?: ""
            }
            else -> ""
        }

        // 1순위: PlaceResolver 동네명(시/구 + 동/읍/면) 해석
        val neighborhood = runCatching {
            placeResolver.resolveNeighborhoodName(
                context = this@CarDrivingTrackingService,
                lat = lat,
                lng = lng,
                fallbackAddressHint = effectiveHint
            )
        }.getOrDefault("")

        if (neighborhood.isNotBlank()) {
            return neighborhood
        }

        // 2순위: 고정밀 역지오코딩 폴백
        if (LocationDistanceUtils.isValidCoordinate(lat, lng)) {
            val precisePlace = runCatching {
                placeResolver.resolvePreciseDrivingPlaceName(this@CarDrivingTrackingService, lat, lng)
            }.getOrDefault("")
            if (precisePlace.isNotBlank() && !precisePlace.startsWith("위도 ")) {
                return precisePlace
            }
        }

        // 3순위: 거점 힌트 또는 기본 동네명 폴백 ('위치 미확인' 문구 영구 배제)
        if (effectiveHint.isNotBlank()) {
            return placeResolver.inferNeighborhoodFromText(effectiveHint).ifBlank { effectiveHint }
        }
        return if (isDeparture) "출발 인근" else "도착 인근"
    }

    private suspend fun captureCurrentWaypoint(isDeparture: Boolean, isDestination: Boolean) {
        val loc = getFreshLocation(
            timeoutMs = if (isDeparture || isDestination) 2500L else 3500L,
            allowStaleFallback = (isDeparture || isDestination)
        )
        var lat = loc?.latitude ?: 0.0
        var lng = loc?.longitude ?: 0.0

        // 출발/도착 지점인데 GPS/LastKnown이 모두 비어있는 경우 CarBluetoothReceiver가 캡처한 좌표 폴백
        if (!LocationDistanceUtils.isValidCoordinate(lat, lng) && (isDeparture || isDestination)) {
            val prefs = getSharedPreferences("car_trip_tracking", Context.MODE_PRIVATE)
            val pLat = prefs.getFloat(if (isDeparture) "start_lat" else "end_lat", 0.0f).toDouble()
            val pLng = prefs.getFloat(if (isDeparture) "start_lng" else "end_lng", 0.0f).toDouble()
            if (LocationDistanceUtils.isValidCoordinate(pLat, pLng)) {
                lat = pLat
                lng = pLng
            }
        }

        // 1. 유효하지 않은 더미 좌표(0.0 등) 완전 차단 -> 0km/출발지-도착지 오염 근본 방지
        if (!LocationDistanceUtils.isValidCoordinate(lat, lng)) {
            Log.w(TAG, "유효하지 않은 GPS 좌표 수집 건너뜀: (lat=$lat, lng=$lng, dep=$isDeparture, dest=$isDestination)")
            return
        }

        // 2. 제자리 정차 중 동일 좌표 중복 누적 방지 (출발/도착이 아닌 중간 샘플링 시)
        if (!isDeparture && !isDestination && lastRecordedLat != 0.0 && lastRecordedLng != 0.0) {
            val distFromLast = distanceMeter(lastRecordedLat, lastRecordedLng, lat, lng)
            if (distFromLast < 15.0) {
                Log.d(TAG, "제자리 정차로 인한 중복 좌표 수집 스킵 (이동거리 ${distFromLast.toInt()}m)")
                return
            }
        }

        // 3. 실시간 주행 거리 누적 (고정밀 위치 및 정상 속도 범위인 경우에만 누적)
        val locAccuracy = if (loc?.hasAccuracy() == true) loc.accuracy else 100f
        val isNetworkProvider = loc?.provider == LocationManager.NETWORK_PROVIDER
        if (!isNetworkProvider && locAccuracy <= 65f) {
            lastValidLocation?.let { prevLoc ->
                val distMeters = distanceMeter(prevLoc.latitude, prevLoc.longitude, lat, lng)
                val deltaSec = ((System.currentTimeMillis() - prevLoc.time).coerceAtLeast(1000L)) / 1000.0
                val speedMps = distMeters / deltaSec
                if (distMeters >= 25.0 && distMeters < 3000.0 && speedMps <= 42.0) {
                    val factor = if (distMeters < 800.0) 1.0 else LocationDistanceUtils.DEFAULT_ROAD_CURVE_FACTOR
                    cumulativeDistanceKm += (distMeters / 1000.0) * factor
                    lastValidLocation = loc ?: Location("").apply {
                        latitude = lat
                        longitude = lng
                        time = System.currentTimeMillis()
                    }
                    Log.d(TAG, "Waypoint 이동 거리 실시간 누적: +${String.format(java.util.Locale.US, "%.2f", (distMeters / 1000.0) * factor)} km (누적: ${String.format(java.util.Locale.US, "%.2f", cumulativeDistanceKm)} km)")
                }
            } ?: run {
                lastValidLocation = loc ?: Location("").apply {
                    latitude = lat
                    longitude = lng
                    time = System.currentTimeMillis()
                }
            }
        }

        lastRecordedLat = lat
        lastRecordedLng = lng
        lastRecordedWaypointTime = System.currentTimeMillis()

        if (isDeparture || !firstDeparturePointRecorded) {
            firstDeparturePointRecorded = true
            departureAccuracyMeters = locAccuracy
        }

        val waypoint = DrivingWaypoint(
            timestamp = System.currentTimeMillis(),
            latitude = lat,
            longitude = lng,
            isDeparture = isDeparture,
            isDestination = isDestination
        )
        waypoints.add(waypoint)
        saveWaypointsToPrefs()
        Log.d(TAG, "GPS 위치 수집 완료: (lat=$lat, lng=$lng, dep=$isDeparture, dest=$isDestination, 총 ${waypoints.size}개)")
    }

    private fun updateOngoingNotification() {
        runCatching {
            val elapsedMin = ((System.currentTimeMillis() - startTimeMillis) / 60000).coerceAtLeast(1)
            val waypointDist = LocationDistanceUtils.calculateWaypointsDistanceKm(waypoints.toList())
            val currentDist = if (waypointDist > 0.0) waypointDist else cumulativeDistanceKm
            val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""
            val content = if (currentDist > 0.0) {
                "실시간 주행 중 · %.1f km (운행 ${elapsedMin}분)".format(currentDist)
            } else if (elapsedMin >= 3 && waypoints.size >= 2) {
                // 수집된 지점이 있으나 미세 정체 구간일 때 최소 추정치 표시
                val estimatedKm = (elapsedMin * 0.3).coerceIn(0.1, 15.0)
                "실시간 주행 중 · 약 %.1f km (운행 ${elapsedMin}분)".format(estimatedKm)
            } else {
                "블루투스 감지 탑승 중 · ${elapsedMin}분 경과 (GPS 위치 수집 중)"
            }

            val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
            val notification = buildNotification(
                "$brandEmoji [$activeVehicleName$carPlatePrefix] 주행 기록 중",
                content
            )
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID, notification)
        }.onFailure { e ->
            Log.e(TAG, "진행 중 알림 갱신 실패", e)
        }
    }

    private fun showTripCompleteNotification(distanceKm: Double, durationMin: Long) {
        runCatching {
            val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""
            val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
            val title = "$brandEmoji [$activeVehicleName$carPlatePrefix] 주행 기록 완료"
            val content = "총 ${durationMin}분 운행 · %.1f km 자동 기록 및 차계부 반영 완료".format(distanceKm)

            val pendingIntent = PendingIntent.getActivity(
                this,
                0,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_menu_compass)
                .setContentTitle(title)
                .setContentText(content)
                .setContentIntent(pendingIntent)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)
                .build()

            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(NOTIFICATION_ID + 1, notification)
        }.onFailure { e ->
            Log.e(TAG, "주행 완료 알림 표출 실패", e)
        }
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_menu_compass)
            .setContentTitle(title)
            .setContentText(content)
            .setOngoing(true)
            .setContentIntent(pendingIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "차량 주행 기록 알림",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "차량 블루투스 연결 시 GPS 경로 및 주행거리를 기록하는 알림"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun startLocationUpdates() {
        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return
        val hasFine = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_FINE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        val hasCoarse = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.ACCESS_COARSE_LOCATION
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        if (!hasFine && !hasCoarse) {
            Log.w(TAG, "위치 권한이 없어 실시간 LocationListener 등록을 건너뜁니다.")
            return
        }

        stopLocationUpdates()

        val listener = android.location.LocationListener { loc ->
            if (!LocationDistanceUtils.isValidCoordinate(loc.latitude, loc.longitude)) return@LocationListener
            // 지하주차장 및 기지국/Wi-Fi 대략 위치(200m~1,500m)를 버리지 않고 출발/도착 동네명 판별에 활용 (2.5km 초과만 무시)
            if (loc.hasAccuracy() && loc.accuracy > 2500f) {
                Log.d(TAG, "초저정밀(${loc.accuracy}m) 위치 신호 무시")
                return@LocationListener
            }

            latestLocation = loc
            val now = System.currentTimeMillis()
            val locAccuracy = if (loc.hasAccuracy()) loc.accuracy else 100f
            val isNetworkProvider = loc.provider == LocationManager.NETWORK_PROVIDER

            // 1. 최초 유효 좌표가 들어왔을 때, 출발 지점으로 등록되지 않았다면 즉시 출발지(isDeparture = true)로 확정 등록
            if (!firstDeparturePointRecorded) {
                firstDeparturePointRecorded = true
                departureAccuracyMeters = locAccuracy
                if (!isNetworkProvider && locAccuracy <= 65f) {
                    lastValidLocation = loc
                }
                lastRecordedLat = loc.latitude
                lastRecordedLng = loc.longitude
                lastRecordedWaypointTime = now

                val depWp = DrivingWaypoint(
                    timestamp = now,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    isDeparture = true,
                    isDestination = false
                )
                waypoints.add(depWp)
                saveWaypointsToPrefs()
                Log.i(TAG, "GPS/기지국 최초 유효 좌표를 출발 지점으로 자동 확정: (lat=${loc.latitude}, lng=${loc.longitude}, acc=${locAccuracy}m)")
                updateOngoingNotification()
                return@LocationListener
            }

            // 2. [지하주차장 출차 보정] 주행 시작 후 5분 이내에 저정밀 기지국 좌표(accuracy > 80m)로 잡혔던 출발지가
            // 출차 직후 고정밀 위성 GPS(accuracy <= 50m)로 수신되면 실제 출차 지점으로 출발지 좌표를 정밀 보정
            if (now - startTimeMillis <= 5 * 60 * 1000L &&
                departureAccuracyMeters > 80f &&
                locAccuracy <= 50f &&
                waypoints.size == 1
            ) {
                departureAccuracyMeters = locAccuracy
                lastValidLocation = loc
                lastRecordedLat = loc.latitude
                lastRecordedLng = loc.longitude
                lastRecordedWaypointTime = now
                synchronized(waypoints) {
                    if (waypoints.isNotEmpty()) {
                        waypoints[0] = DrivingWaypoint(
                            timestamp = now,
                            latitude = loc.latitude,
                            longitude = loc.longitude,
                            isDeparture = true,
                            isDestination = false
                        )
                    }
                }
                saveWaypointsToPrefs()
                Log.i(TAG, "지하주차장 출차 후 고정밀 GPS 수신으로 출발지 좌표 자동 보정 완료: (lat=${loc.latitude}, lng=${loc.longitude}, acc=${locAccuracy}m)")
                updateOngoingNotification()
                return@LocationListener
            }

            // 주행 중 중간 궤적 및 거리 누적은 기지국(NETWORK_PROVIDER) 및 저정밀(accuracy > 65m) 신호를 원천 배제하여
            // GPS↔기지국 교차 수신에 의한 100~300m 핑퐁 거리 부풀림(예: 27.1km가 117.4km로 뻥튀기되는 현상)을 원천 차단
            if (isNetworkProvider || locAccuracy > 65f) {
                return@LocationListener
            }

            // 3. 실시간 주행 거리 즉각 누적: 고정밀(65m 이내) 위치이고 이전 유효 위치 대비 35m 이상 & 정상 속도 범위 내 이동 시
            lastValidLocation?.let { prevLoc ->
                val distMeters = distanceMeter(prevLoc.latitude, prevLoc.longitude, loc.latitude, loc.longitude)
                val deltaSec = ((now - prevLoc.time).coerceAtLeast(1000L)) / 1000.0
                val speedMps = distMeters / deltaSec
                if (distMeters >= 35.0 && distMeters < 3000.0 && speedMps <= 42.0) {
                    val factor = if (distMeters < 800.0) 1.0 else LocationDistanceUtils.DEFAULT_ROAD_CURVE_FACTOR
                    cumulativeDistanceKm += (distMeters / 1000.0) * factor
                    lastValidLocation = loc
                    saveWaypointsToPrefs()
                    updateOngoingNotification()
                }
            } ?: run {
                lastValidLocation = loc
            }

            // 4. 실시간 궤적 누적: 이전 기록 위치 대비 50m 이상 이동했거나, 마지막 기록 후 3분 경과 시(35m 이상 이동) 기록
            val distFromLast = if (lastRecordedLat != 0.0 && lastRecordedLng != 0.0) {
                distanceMeter(lastRecordedLat, lastRecordedLng, loc.latitude, loc.longitude)
            } else 9999.0

            val timeSinceLast = (now - lastRecordedWaypointTime).coerceAtLeast(1000L)
            val speedFromLastMps = distFromLast / (timeSinceLast / 1000.0)

            if (speedFromLastMps <= 42.0 && (distFromLast >= 50.0 || (timeSinceLast >= 3 * 60 * 1000L && distFromLast >= 35.0))) {
                lastRecordedLat = loc.latitude
                lastRecordedLng = loc.longitude
                lastRecordedWaypointTime = now

                val wp = DrivingWaypoint(
                    timestamp = now,
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    isDeparture = false,
                    isDestination = false
                )
                waypoints.add(wp)
                saveWaypointsToPrefs()
                Log.d(TAG, "실시간 이동 궤적 Waypoint 수집: (lat=${loc.latitude}, lng=${loc.longitude}, dist=${distFromLast.toInt()}m, 총 ${waypoints.size}개, 누적거리=%.2fkm)".format(cumulativeDistanceKm))
                updateOngoingNotification()
            }
        }
        locationListener = listener

        // 1. Android 12+ Fused Provider (시스템 레벨 최적 융합 공급자, minDistance=0f로 즉시 초기 콜백 유도)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                if (lm.allProviders.contains(LocationManager.FUSED_PROVIDER)) {
                    lm.requestLocationUpdates(
                        LocationManager.FUSED_PROVIDER,
                        3000L,
                        0f,
                        listener,
                        android.os.Looper.getMainLooper()
                    )
                    Log.d(TAG, "LocationListener: FUSED_PROVIDER 등록 성공")
                }
            }.onFailure { Log.e(TAG, "FUSED_PROVIDER 등록 실패", it) }
        }

        // 2. 고정밀 GPS 공급자 (3초 간격 또는 10m 이동 시)
        if (hasFine && lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    3000L,
                    10f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
                Log.d(TAG, "LocationListener: GPS_PROVIDER 등록 성공")
            }.onFailure { Log.e(TAG, "GPS_PROVIDER 등록 실패", it) }
        }

        // 3. 내비게이션(티맵/카카오내비 등) 패시브 공급자 (2초 간격 또는 10m 이동 시)
        if (hasFine && lm.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.PASSIVE_PROVIDER,
                    2000L,
                    10f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
                Log.d(TAG, "LocationListener: PASSIVE_PROVIDER (내비게이션 연동) 등록 성공")
            }.onFailure { Log.e(TAG, "PASSIVE_PROVIDER 등록 실패", it) }
        }

        // 4. 네트워크 기지국/Wi-Fi 공급자 (5초 간격, minDistance=0f로 지하주차장/실내 즉시 대략 위치 수신)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    5000L,
                    0f,
                    listener,
                    android.os.Looper.getMainLooper()
                )
                Log.d(TAG, "LocationListener: NETWORK_PROVIDER 등록 성공")
            }.onFailure { Log.e(TAG, "NETWORK_PROVIDER 등록 실패", it) }
        }
    }

    private fun stopLocationUpdates() {
        locationListener?.let { listener ->
            runCatching {
                val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager
                lm?.removeUpdates(listener)
                Log.d(TAG, "LocationListener 해제 완료")
            }
        }
        locationListener = null
    }

    private fun distanceMeter(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 || lon1 == 0.0 || lat2 == 0.0 || lon2 == 0.0) return 999999.0
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun saveWaypointsToPrefs() {
        try {
            val prefs = getSharedPreferences("car_driving_service_prefs", Context.MODE_PRIVATE)
            val jsonArray = JSONArray()
            synchronized(waypoints) {
                for (wp in waypoints) {
                    val obj = JSONObject().apply {
                        put("t", wp.timestamp)
                        put("lat", wp.latitude)
                        put("lng", wp.longitude)
                        put("dep", wp.isDeparture)
                        put("dest", wp.isDestination)
                    }
                    jsonArray.put(obj)
                }
            }
            prefs.edit()
                .putBoolean("is_tracking", isTracking)
                .putLong("start_time_millis", startTimeMillis)
                .putString("active_vehicle_id", activeVehicleId)
                .putString("active_vehicle_name", activeVehicleName)
                .putString("active_license_plate", activeLicensePlate)
                .putFloat("cumulative_distance_km", cumulativeDistanceKm.toFloat())
                .putBoolean("first_departure_recorded", firstDeparturePointRecorded)
                .putFloat("departure_accuracy_meters", departureAccuracyMeters)
                .putString("waypoints_json", jsonArray.toString())
                .apply()
        } catch (e: Throwable) {
            Log.e(TAG, "Waypoint 및 주행 상태 저장 실패", e)
        }
    }

    /**
     * 백그라운드에서 OS 메모리 회수 등으로 프로세스가 재생성되었을 때,
     * SharedPreferences에 영속화된 주행 세션 및 Waypoint 궤적을 복원합니다.
     */
    private fun restoreTrackingStateFromPrefs(): Boolean {
        return try {
            val prefs = getSharedPreferences("car_driving_service_prefs", Context.MODE_PRIVATE)
            val wasTracking = prefs.getBoolean("is_tracking", false)
            val savedStart = prefs.getLong("start_time_millis", 0L)
            val now = System.currentTimeMillis()

            // 최대 12시간 이내의 유효한 주행 세션만 복원
            if (!wasTracking || savedStart <= 0L || (now - savedStart) > 12 * 60 * 60 * 1000L) {
                false
            } else {
                activeVehicleId = prefs.getString("active_vehicle_id", "car_1") ?: "car_1"
                activeVehicleName = prefs.getString("active_vehicle_name", "차량") ?: "차량"
                activeLicensePlate = prefs.getString("active_license_plate", "") ?: ""
                startTimeMillis = savedStart
                cumulativeDistanceKm = prefs.getFloat("cumulative_distance_km", 0f).toDouble()
                firstDeparturePointRecorded = prefs.getBoolean("first_departure_recorded", false)
                departureAccuracyMeters = prefs.getFloat("departure_accuracy_meters", 9999f)

                val jsonStr = prefs.getString("waypoints_json", null)
                synchronized(waypoints) {
                    waypoints.clear()
                    if (!jsonStr.isNullOrBlank()) {
                        val arr = JSONArray(jsonStr)
                        for (i in 0 until arr.length()) {
                            val obj = arr.optJSONObject(i) ?: continue
                            val wp = DrivingWaypoint(
                                timestamp = obj.optLong("t", savedStart),
                                latitude = obj.optDouble("lat", 0.0),
                                longitude = obj.optDouble("lng", 0.0),
                                isDeparture = obj.optBoolean("dep", i == 0),
                                isDestination = obj.optBoolean("dest", false)
                            )
                            if (LocationDistanceUtils.isValidCoordinate(wp.latitude, wp.longitude)) {
                                waypoints.add(wp)
                            }
                        }
                    }
                    waypoints.lastOrNull()?.let { lastWp ->
                        lastRecordedLat = lastWp.latitude
                        lastRecordedLng = lastWp.longitude
                        lastRecordedWaypointTime = lastWp.timestamp
                        lastValidLocation = Location("").apply {
                            latitude = lastWp.latitude
                            longitude = lastWp.longitude
                            time = lastWp.timestamp
                        }
                    }
                }
                isTracking = true
                true
            }
        } catch (e: Throwable) {
            Log.e(TAG, "저장된 주행 상태 복원 실패", e)
            false
        }
    }

    private fun clearSavedWaypoints() {
        val prefs = getSharedPreferences("car_driving_service_prefs", Context.MODE_PRIVATE)
        prefs.edit().clear().apply()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopLocationUpdates()
        releaseWakeLock()
        serviceScope.cancel()
        Log.d(TAG, "CarDrivingTrackingService 종료")
    }
}
