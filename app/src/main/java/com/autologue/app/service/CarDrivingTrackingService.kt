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
 * 차량 블루투스 연결(탑승) 시 백그라운드에서 동작하여 10분 단위로 GPS 위치를 수집하고
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

    private var isTracking = false
    private var activeVehicleId: String = "car_1"
    private var activeVehicleName: String = "차량"
    private var activeLicensePlate: String = ""
    private var startTimeMillis: Long = 0L

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
            context.startService(intent)
        }

        /**
         * 테스트 또는 즉시 위치 기록 트리거
         */
        fun recordPoint(context: Context) {
            val intent = Intent(context, CarDrivingTrackingService::class.java).apply {
                action = ACTION_RECORD_POINT
            }
            context.startService(intent)
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
        if (isTracking) {
            Log.d(TAG, "이미 주행 추적 중입니다: $activeVehicleName")
            return
        }

        isTracking = true
        activeVehicleId = vehicleId
        activeVehicleName = vehicleName
        activeLicensePlate = licensePlate
        startTimeMillis = System.currentTimeMillis()
        waypoints.clear()

        // 1. 포그라운드 서비스 알림 등록 (Android 14+ 위치 타입 명시)
        val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
        val initialNotification = buildNotification("$brandEmoji [$activeVehicleName] 탑승 운행 시작", "블루투스 감지 탑승 중 · 10분 주기 GPS 경로 수집 시작")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                initialNotification,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                } else {
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
                }
            )
        } else {
            startForeground(NOTIFICATION_ID, initialNotification)
        }

        // 2. 출발지 GPS 즉시 수집
        serviceScope.launch {
            captureCurrentWaypoint(isDeparture = true, isDestination = false)
        }

        // 3. 10분 주기 백그라운드 GPS 위치 수집 코루틴 가동 (600,000ms = 10분)
        trackingJob?.cancel()
        trackingJob = serviceScope.launch {
            Log.d(TAG, "10분 주기 GPS 위치 수집 루프 시작: [$activeVehicleName] ($activeLicensePlate)")
            while (isActive && isTracking) {
                delay(10 * 60 * 1000L) // 10분 대기
                if (!isTracking) break

                captureCurrentWaypoint(isDeparture = false, isDestination = false)
                updateOngoingNotification()
            }
        }
    }

    private fun stopTrackingInternal() {
        if (!isTracking) {
            Log.d(TAG, "추적 중이 아닙니다.")
            stopSelf()
            return
        }

        isTracking = false
        trackingJob?.cancel()

        serviceScope.launch {
            try {
                // 1. 도착지 GPS 수집
                captureCurrentWaypoint(isDeparture = false, isDestination = true)

                // 2. 총 운행 시간 및 거리 산출
                val durationMin = ((System.currentTimeMillis() - startTimeMillis) / 60000).coerceAtLeast(1)

                // 2분 미만의 초단기 연결 해제는 단순 시동 켬/끔으로 간주하여 무시
                if (durationMin < 2 && waypoints.size <= 2) {
                    Log.d(TAG, "운행 시간 2분 미만 (${durationMin}분)으로 주행 기록 스킵")
                    clearSavedWaypoints()
                    return@launch
                }

                // 연속 Waypoint 기반 실제 도로 주행거리 정밀 계산
                val calculatedDistanceKm = LocationDistanceUtils.calculateWaypointsDistanceKm(waypoints.toList())
                val finalTripKm = if (calculatedDistanceKm > 0.0) {
                    calculatedDistanceKm
                } else {
                    // 단일 구간 직선거리 폴백
                    val first = waypoints.firstOrNull { it.latitude != 0.0 }
                    val last = waypoints.lastOrNull { it.latitude != 0.0 }
                    if (first != null && last != null) {
                        (LocationDistanceUtils.calculateDrivingDistanceKm(first.latitude, first.longitude, last.latitude, last.longitude)).coerceAtLeast(1.0)
                    } else 1.0
                }

                val firstPoint = waypoints.firstOrNull { it.latitude != 0.0 }
                val lastPoint = waypoints.lastOrNull { it.latitude != 0.0 }
                val startLat = firstPoint?.latitude ?: 0.0
                val startLng = firstPoint?.longitude ?: 0.0
                val endLat = lastPoint?.latitude ?: 0.0
                val endLng = lastPoint?.longitude ?: 0.0

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
                    vehicleRepository.recordCommuteTrip(
                        isToWork = true,
                        homeName = config.homeName.ifBlank { "우리집" },
                        companyName = config.companyName.ifBlank { "회사" },
                        distanceKm = oneWay
                    )
                    Log.d(TAG, "출근 주행 기록 완료: [$activeVehicleName$carPlatePrefix] ${oneWay}km")
                } else if (homeLat != 0.0 && compLat != 0.0 && distCompToStart < 800 && distHomeToEnd < 800) {
                    val oneWay = if (config.commuteOneWayKm > 0) config.commuteOneWayKm else finalTripKm
                    vehicleRepository.recordCommuteTrip(
                        isToWork = false,
                        homeName = config.homeName.ifBlank { "우리집" },
                        companyName = config.companyName.ifBlank { "회사" },
                        distanceKm = oneWay
                    )
                    Log.d(TAG, "퇴근 주행 기록 완료: [$activeVehicleName$carPlatePrefix] ${oneWay}km")
                } else {
                    // 일반 차량 주행 기록 저장
                    val log = VehicleLog(
                        timestamp = LocalDateTime.now(),
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = finalTripKm,
                        note = "[$activeVehicleName$carPlatePrefix] 블루투스 연동 자동 주행 (${durationMin}분 운행, 10분 주기 GPS 추적, ${waypoints.size}개 지점)"
                    )
                    vehicleRepository.insertVehicleLog(log)
                    Log.d(TAG, "일반 주행 자동 기록 완료: [$activeVehicleName$carPlatePrefix] ${finalTripKm}km (${waypoints.size}개 좌표)")
                }

                // 다이어리 이동동선에 10분 주기 GPS Waypoint들을 RouteStep으로 저장
                saveWaypointsToDiary(finalTripKm, durationMin)

                // 완료 알림 표출
                showTripCompleteNotification(finalTripKm, durationMin)
                clearSavedWaypoints()

            } catch (t: Throwable) {
                Log.e(TAG, "주행 종료 처리 중 오류", t)
            } finally {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
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

            val config = userLocationPreferences.config.value
            val homeLat = config.homeLat
            val homeLng = config.homeLng
            val compLat = config.companyLat
            val compLng = config.companyLng

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
                    index == 0 -> {
                        // 출발 지점
                        val name = when {
                            homeLat != 0.0 && distHome < 800 -> config.homeName.ifBlank { "우리집" }
                            compLat != 0.0 && distComp < 800 -> config.companyName.ifBlank { "회사" }
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
                            compLat != 0.0 && distComp < 800 -> config.companyName.ifBlank { "회사" }
                            homeLat != 0.0 && distHome < 800 -> config.homeName.ifBlank { "우리집" }
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

    private fun captureCurrentWaypoint(isDeparture: Boolean, isDestination: Boolean) {
        val loc = getCurrentLocation()
        val lat = loc?.latitude ?: 0.0
        val lng = loc?.longitude ?: 0.0

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
        val elapsedMin = ((System.currentTimeMillis() - startTimeMillis) / 60000).coerceAtLeast(1)
        val currentDist = LocationDistanceUtils.calculateWaypointsDistanceKm(waypoints.toList())
        val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""
        val content = "블루투스 감지 탑승 중 · ${elapsedMin}분 경과 (현재까지 약 %.1f km, %d개 위치 기록)".format(currentDist, waypoints.size)

        val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
        val notification = buildNotification(
            "$brandEmoji [$activeVehicleName$carPlatePrefix] 주행 기록 중",
            content
        )
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID, notification)
    }

    private fun showTripCompleteNotification(distanceKm: Double, durationMin: Long) {
        val carPlatePrefix = if (activeLicensePlate.isNotBlank()) " ($activeLicensePlate)" else ""
        val brandEmoji = com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(activeVehicleName)
        val title = "$brandEmoji [$activeVehicleName$carPlatePrefix] 주행 기록 완료"
        val content = "총 ${durationMin}분 운행 · %.1f km 자동 기록 및 차계부 반영 완료".format(distanceKm)

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(content)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        nm.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun buildNotification(title: String, content: String): Notification {
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
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
                description = "차량 블루투스 연결 시 10분 주기 GPS 경로 및 주행거리를 기록하는 알림"
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    @SuppressLint("MissingPermission")
    private fun getCurrentLocation(): Location? {
        return try {
            val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && (best == null || loc.accuracy < best.accuracy)) {
                        best = loc
                    }
                }
            }
            best
        } catch (e: Exception) {
            Log.e(TAG, "GPS 위치 획득 실패", e)
            null
        }
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
        serviceScope.cancel()
        Log.d(TAG, "CarDrivingTrackingService 종료")
    }
}
