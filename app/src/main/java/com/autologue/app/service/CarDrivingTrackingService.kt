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
        val action = intent?.action ?: return START_NOT_STICKY

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
            lastRecordedLat = 0.0
            lastRecordedLng = 0.0
            lastRecordedWaypointTime = 0L
            latestLocation = null
        }

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
                    val fgsType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    } else {
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                    }
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

        // 3. 출발지 GPS 즉시 수집
        serviceScope.launch {
            captureCurrentWaypoint(isDeparture = true, isDestination = false)
        }

        // 4. 백그라운드 보조 점검 및 알림 갱신 코루틴 가동 (3분 주기)
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

                // 연속 Waypoint 기반 실제 도로 주행거리 정밀 계산
                val calculatedDistanceKm = LocationDistanceUtils.calculateWaypointsDistanceKm(waypoints.toList())
                val firstPoint = waypoints.firstOrNull { it.latitude != 0.0 }
                val lastPoint = waypoints.lastOrNull { it.latitude != 0.0 }
                val directDistanceKm = if (firstPoint != null && lastPoint != null) {
                    LocationDistanceUtils.calculateDrivingDistanceKm(firstPoint.latitude, firstPoint.longitude, lastPoint.latitude, lastPoint.longitude)
                } else 0.0

                // 유효 주행거리 결정 (연속 궤적 우선, 단일 구간 폴백)
                val finalTripKm = when {
                    calculatedDistanceKm > 0.05 -> calculatedDistanceKm
                    directDistanceKm > 0.05 -> directDistanceKm
                    else -> 0.0
                }

                val startLat = firstPoint?.latitude ?: 0.0
                val startLng = firstPoint?.longitude ?: 0.0
                val endLat = lastPoint?.latitude ?: 0.0
                val endLng = lastPoint?.longitude ?: 0.0

                // 출발지 및 도착지 지명 역지오코딩 해석
                val startPlace = resolveLocationName(startLat, startLng, isDeparture = true)
                val endPlace = resolveLocationName(endLat, endLng, isDeparture = false)

                val routeTitle = if (startPlace.isNotBlank() && endPlace.isNotBlank()) {
                    "$startPlace ➔ $endPlace (%.1f km)".format(finalTripKm)
                } else if (endPlace.isNotBlank()) {
                    "$endPlace 도착 (%.1f km)".format(finalTripKm)
                } else {
                    "주행 완료 (%.1f km)".format(finalTripKm)
                }

                val carTag = "[$activeVehicleId: $activeVehicleName]"
                val detailSub = "(${durationMin}분 운행 · GPS ${waypoints.size}개 지점)"

                val config = userLocationPreferences.config.value
                val homeLat = config.homeLat
                val homeLng = config.homeLng
                val compLat = config.companyLat
                val compLng = config.companyLng

                val distHomeToStart = distanceMeter(homeLat, homeLng, startLat, startLng)
                val distCompToEnd = distanceMeter(compLat, compLng, endLat, endLng)
                val distCompToStart = distanceMeter(compLat, compLng, startLat, startLng)
                val distHomeToEnd = distanceMeter(homeLat, homeLng, endLat, endLng)

                val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""

                // 3. 출퇴근 주행 판별 및 저장
                if (homeLat != 0.0 && compLat != 0.0 && distHomeToStart < 800 && distCompToEnd < 800) {
                    val oneWay = if (config.commuteOneWayKm > 0) config.commuteOneWayKm else finalTripKm
                    val commuteTitle = "${config.homeName.ifBlank { "우리집" }} ➔ ${config.companyName.ifBlank { "회사" }} (출근 %.1f km)".format(oneWay)
                    val log = VehicleLog(
                        timestamp = LocalDateTime.now(),
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = oneWay,
                        note = "$carTag [$commuteTitle] $detailSub"
                    )
                    vehicleRepository.insertVehicleLog(log)
                    Log.d(TAG, "출근 주행 기록 완료: [$activeVehicleName$carPlatePrefix] $commuteTitle")
                } else if (homeLat != 0.0 && compLat != 0.0 && distCompToStart < 800 && distHomeToEnd < 800) {
                    val oneWay = if (config.commuteOneWayKm > 0) config.commuteOneWayKm else finalTripKm
                    val commuteTitle = "${config.companyName.ifBlank { "회사" }} ➔ ${config.homeName.ifBlank { "우리집" }} (퇴근 %.1f km)".format(oneWay)
                    val log = VehicleLog(
                        timestamp = LocalDateTime.now(),
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = oneWay,
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

                // 다이어리 이동동선에 10분 주기 GPS Waypoint들을 RouteStep으로 저장
                saveWaypointsToDiary(finalTripKm, durationMin)

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
     * 수집된 10분 주기 GPS Waypoint들을 지명 역지오코딩 및 브랜드 엠블럼과 결합하여
     * 당일 다이어리의 RouteStep 목록으로 자동 영구 저장합니다.
     * 구글 지도 웹뷰(GoogleMapRouteView)에 마커 핀과 경로로 즉시 표시됩니다.
     */
    private suspend fun saveWaypointsToDiary(finalTripKm: Double, durationMin: Long) {
        try {
            val validList = synchronized(waypoints) {
                waypoints.filter { it.latitude != 0.0 && it.longitude != 0.0 }
            }
            if (validList.isEmpty()) {
                Log.d(TAG, "유효한 GPS 좌표가 없어 다이어리 RouteStep 저장을 스킵합니다.")
                return
            }

            val config = runCatching { userLocationPreferences.config.value }.getOrNull()
            val homeLat = config?.homeLat ?: 0.0
            val homeLng = config?.homeLng ?: 0.0
            val compLat = config?.companyLat ?: 0.0
            val compLng = config?.companyLng ?: 0.0

            val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
            val vehicleTag = activeVehicleName.split(" ").firstOrNull() ?: activeVehicleName
            val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""

            val newRouteSteps = mutableListOf<RouteStep>()

            validList.forEachIndexed { index, wp ->
                val time = java.time.LocalDateTime.ofInstant(
                    java.time.Instant.ofEpochMilli(wp.timestamp),
                    java.time.ZoneId.systemDefault()
                )
                val resolved = placeResolver.resolveGeoLocation(this@CarDrivingTrackingService, wp.latitude, wp.longitude)

                val distHome = distanceMeter(homeLat, homeLng, wp.latitude, wp.longitude)
                val distComp = distanceMeter(compLat, compLng, wp.latitude, wp.longitude)

                val (stepTitle, locName, tags) = when {
                    validList.size == 1 -> {
                        // 단 1개 지점만 수집된 경우
                        val name = when {
                            homeLat != 0.0 && distHome < 800 -> config?.homeName?.ifBlank { "우리집" } ?: "우리집"
                            compLat != 0.0 && distComp < 800 -> config?.companyName?.ifBlank { "회사" } ?: "회사"
                            else -> resolved.placeName.ifBlank { "주행 거점" }
                        }
                        Triple(
                            "$brandEmoji [$activeVehicleName] 주행 기록 (단일 거점)",
                            name,
                            listOf("차량주행", "$brandEmoji $vehicleTag", "주행거점")
                        )
                    }
                    index == 0 -> {
                        // 출발 지점
                        val name = when {
                            homeLat != 0.0 && distHome < 800 -> config?.homeName?.ifBlank { "우리집" } ?: "우리집"
                            compLat != 0.0 && distComp < 800 -> config?.companyName?.ifBlank { "회사" } ?: "회사"
                            else -> resolved.placeName.ifBlank { "출발 지점" }
                        }
                        Triple(
                            "$brandEmoji [$activeVehicleName] 출발",
                            name,
                            listOf("차량주행", "$brandEmoji $vehicleTag", "출발지점")
                        )
                    }
                    index == validList.lastIndex -> {
                        // 최종 도착 지점
                        val name = when {
                            compLat != 0.0 && distComp < 800 -> config?.companyName?.ifBlank { "회사" } ?: "회사"
                            homeLat != 0.0 && distHome < 800 -> config?.homeName?.ifBlank { "우리집" } ?: "우리집"
                            else -> resolved.placeName.ifBlank { "도착 지점" }
                        }
                        Triple(
                            "$brandEmoji [$activeVehicleName] 도착 (총 %.1f km)".format(finalTripKm),
                            name,
                            listOf("차량주행", "$brandEmoji $vehicleTag", "도착지점")
                        )
                    }
                    else -> {
                        // 중간 10분 주기 경유 지점
                        val elapsed = index * 10
                        Triple(
                            "$brandEmoji [$activeVehicleName] 주행 경유 (${elapsed}분 경과)",
                            resolved.placeName.ifBlank { "주행 경유지 $index" },
                            listOf("차량주행", "$brandEmoji $vehicleTag", "10분GPS추적")
                        )
                    }
                }

                newRouteSteps.add(
                    RouteStep(
                        id = UUID.randomUUID().toString(),
                        time = time,
                        stepType = RouteStepType.DRIVING,
                        title = stepTitle,
                        description = "10분 단위 GPS 백그라운드 수신 · 위치: (%.4f, %.4f)".format(wp.latitude, wp.longitude),
                        locationName = locName,
                        address = resolved.address,
                        latitude = wp.latitude,
                        longitude = wp.longitude,
                        category = "차계부",
                        tags = tags
                    )
                )
            }

            val today = java.time.LocalDate.now()
            val diaryEntry = DiaryEntry(
                date = today.atTime(java.time.LocalTime.now()),
                title = "$today 일상 및 주행 기록",
                summary = "$brandEmoji [$activeVehicleName$carPlatePrefix] 블루투스 연동 자동 주행 (${durationMin}분 운행, 10분 주기 GPS ${validList.size}개 지점 추적 완료)",
                drivingDistanceKm = finalTripKm,
                routeSteps = newRouteSteps,
                tags = listOf("차량주행", "$brandEmoji $vehicleTag")
            )

            diaryRepository.insertDiaryEntry(diaryEntry)
            Log.d(TAG, "다이어리 RouteStep ${newRouteSteps.size}개 자동 영구 저장 완료: [$activeVehicleName]")
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
     * 캐시된 오래된 좌표가 아닌, 실제 현재 시점의 신선한 GPS 위치를 비동기로 요청 및 획득합니다.
     * Android 11+ (API 30+) getCurrentLocation 지원, 하위 버전은 가장 최신 시간의 위치로 폴백.
     */
    @SuppressLint("MissingPermission")
    private suspend fun getFreshLocation(timeoutMs: Long = 4000L): Location? {
        // 1. 30초 이내의 유효한 실시간 최신 위치가 이미 있으면 즉시 반환
        latestLocation?.let { loc ->
            if (System.currentTimeMillis() - loc.time < 30_000L && loc.latitude != 0.0 && loc.longitude != 0.0) {
                return loc
            }
        }

        val lm = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return latestLocation

        // 2. Android 11+ (API 30+) getCurrentLocation 비동기 요청 (신선한 실제 GPS 위치 획득)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val provider = when {
                lm.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
                lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
                else -> null
            }
            if (provider != null) {
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
                if (fresh != null && fresh.latitude != 0.0 && fresh.longitude != 0.0) {
                    latestLocation = fresh
                    return fresh
                }
            }
        }

        // 3. 폴백: getLastKnownLocation 중 가장 최신의 위치
        return try {
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.PASSIVE_PROVIDER,
                LocationManager.NETWORK_PROVIDER
            )
            var best: Location? = null
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && (best == null || loc.time > best.time)) {
                        best = loc
                    }
                }
            }
            best ?: latestLocation
        } catch (e: Exception) {
            latestLocation
        }
    }

    /**
     * 거점(집/회사) 또는 PlaceResolver 역지오코딩을 통해 직관적인 지명을 도출합니다.
     */
    private suspend fun resolveLocationName(lat: Double, lng: Double, isDeparture: Boolean): String {
        if (lat == 0.0 || lng == 0.0) return if (isDeparture) "출발지" else "도착지"

        val config = runCatching { userLocationPreferences.config.value }.getOrNull()
        val homeLat = config?.homeLat ?: 0.0
        val homeLng = config?.homeLng ?: 0.0
        val compLat = config?.companyLat ?: 0.0
        val compLng = config?.companyLng ?: 0.0

        // 1. 거점(집/회사) 800m 이내 우선 매칭
        if (homeLat != 0.0 && distanceMeter(homeLat, homeLng, lat, lng) < 800) {
            return config?.homeName?.ifBlank { "우리집" } ?: "우리집"
        }
        if (compLat != 0.0 && distanceMeter(compLat, compLng, lat, lng) < 800) {
            return config?.companyName?.ifBlank { "회사" } ?: "회사"
        }

        // 2. PlaceResolver 역지오코딩 (동/읍/면 또는 장소명)
        return try {
            val resolved = placeResolver.resolveGeoLocation(this@CarDrivingTrackingService, lat, lng)
            when {
                resolved.placeName.isNotBlank() -> resolved.placeName
                resolved.address.isNotBlank() -> {
                    val parts = resolved.address.split(" ")
                    val dong = parts.findLast { p: String -> p.endsWith("동") || p.endsWith("읍") || p.endsWith("면") || p.endsWith("로") || p.endsWith("길") }
                    val gu = parts.findLast { p: String -> p.endsWith("구") || p.endsWith("군") || p.endsWith("시") }
                    if (dong != null && gu != null && !dong.contains(gu)) "$gu $dong" else dong ?: gu ?: resolved.address
                }
                else -> if (isDeparture) "출발지" else "도착지"
            }
        } catch (e: Throwable) {
            if (isDeparture) "출발지" else "도착지"
        }
    }

    private suspend fun captureCurrentWaypoint(isDeparture: Boolean, isDestination: Boolean) {
        val loc = getFreshLocation()
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0

        // 제자리 정차 중 동일 좌표 중복 누적 방지 (출발/도착이 아닌 중간 샘플링 시)
        if (!isDeparture && !isDestination && lastRecordedLat != 0.0 && lastRecordedLng != 0.0) {
            val distFromLast = distanceMeter(lastRecordedLat, lastRecordedLng, lat, lng)
            if (distFromLast < 15.0) {
                Log.d(TAG, "제자리 정차로 인한 중복 좌표 수집 스킵 (이동거리 ${distFromLast.toInt()}m)")
                return
            }
        }

        if (lat != 0.0 && lng != 0.0) {
            lastRecordedLat = lat
            lastRecordedLng = lng
            lastRecordedWaypointTime = System.currentTimeMillis()
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
            val currentDist = LocationDistanceUtils.calculateWaypointsDistanceKm(waypoints.toList())
            val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""
            val content = if (currentDist > 0.0) {
                "실시간 주행 중 · %.1f km (운행 ${elapsedMin}분, %d개 지점 수집)".format(currentDist, waypoints.size)
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
            if (loc.latitude == 0.0 && loc.longitude == 0.0) return@LocationListener
            if (loc.hasAccuracy() && loc.accuracy > 150f) {
                Log.d(TAG, "낮은 정확도(${loc.accuracy}m) GPS 신호 무시")
                return@LocationListener
            }

            latestLocation = loc

            // 실시간 궤적 누적: 이전 기록 위치 대비 30m 이상 이동했거나, 마지막 기록 후 3분 경과 시(15m 이상 이동) 기록
            val distFromLast = if (lastRecordedLat != 0.0 && lastRecordedLng != 0.0) {
                distanceMeter(lastRecordedLat, lastRecordedLng, loc.latitude, loc.longitude)
            } else 9999.0

            val timeSinceLast = System.currentTimeMillis() - lastRecordedWaypointTime

            if (distFromLast >= 30.0 || (timeSinceLast >= 3 * 60 * 1000L && distFromLast >= 15.0)) {
                lastRecordedLat = loc.latitude
                lastRecordedLng = loc.longitude
                lastRecordedWaypointTime = System.currentTimeMillis()

                val wp = DrivingWaypoint(
                    timestamp = System.currentTimeMillis(),
                    latitude = loc.latitude,
                    longitude = loc.longitude,
                    isDeparture = false,
                    isDestination = false
                )
                waypoints.add(wp)
                saveWaypointsToPrefs()
                Log.d(TAG, "실시간 이동 궤적 Waypoint 수집: (lat=${loc.latitude}, lng=${loc.longitude}, dist=${distFromLast.toInt()}m, 총 ${waypoints.size}개)")
                updateOngoingNotification()
            }
        }
        locationListener = listener

        // 1. 고정밀 GPS 공급자 (3초 간격 또는 10m 이동 시)
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

        // 2. 내비게이션(티맵/카카오내비 등) 패시브 공급자 (2초 간격 또는 10m 이동 시)
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

        // 3. 네트워크 기지국/Wi-Fi 공급자 (10초 간격 또는 50m 이동 시)
        if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                lm.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    10000L,
                    50f,
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
            prefs.edit().putString("waypoints_json", jsonArray.toString()).apply()
        } catch (e: Throwable) {
            Log.e(TAG, "Waypoint 저장 실패", e)
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
