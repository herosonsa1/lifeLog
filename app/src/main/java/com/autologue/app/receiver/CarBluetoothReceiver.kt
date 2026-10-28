package com.autologue.app.receiver

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationManager
import android.util.Log
import com.autologue.app.data.preferences.MultiVehiclePreferences
import com.autologue.app.data.preferences.UserLocationPreferences
import com.autologue.app.data.preferences.VehicleMaintenancePreferences
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.model.formatDrivingDurationLabel
import com.autologue.app.domain.model.getAssignedVehicleId
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.presentation.car.calculateRoundTripDistanceKm
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject
import kotlin.math.*

@AndroidEntryPoint
class CarBluetoothReceiver : BroadcastReceiver() {

    @Inject
    lateinit var userLocationPreferences: UserLocationPreferences

    @Inject
    lateinit var vehicleRepository: VehicleRepository

    @Inject
    lateinit var multiVehiclePreferences: MultiVehiclePreferences

    @Inject
    lateinit var maintenancePreferences: VehicleMaintenancePreferences

    // [H-03] 외부 정적 Scope 제거 — goAsync() 기반 패턴으로 전환
    //   기존: private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    //   → onReceive()가 반환된 후 시스템이 프로세스를 종료할 수 있어 DB 기록 유실 위험

    @SuppressLint("MissingPermission")
    override fun onReceive(context: Context, intent: Intent) {
        try {
            val action = intent.action ?: return
            val device = runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
                }
            }.getOrNull()

            val deviceName = try { device?.name ?: "" } catch (e: Throwable) { "" }

            val config = runCatching { userLocationPreferences.config.value }.getOrNull() ?: return
            val targetDevice = config.carBluetoothDevice.trim()

            // 1. 다중 차량 프로필에서 등록된 블루투스 일치 확인 (브랜드/모델 별칭 포함)
            val matchedVehicle = if (deviceName.isNotBlank()) {
                runCatching { multiVehiclePreferences.findVehicleByBluetooth(deviceName) }.getOrNull()
            } else null

            // 2. 이어폰/워치/스피커/주변기기 등 비차량 블루투스 기기 철저 배제
            val isEarphoneOrWearable = listOf(
                "buds", "airpod", "qcy", "earphone", "headset", "headphone",
                "wh-", "wf-", "watch", "fit", "band", "jbl", "marshall", "bose",
                "speaker", "soundcore", "anker", "sony", "sennheiser", "beats",
                "shokz", "b&o", "harman", "keyboard", "mouse", "pad", "controller"
            ).any { deviceName.contains(it, ignoreCase = true) }

            val isCarAudioClass = runCatching {
                device?.bluetoothClass?.deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
            }.getOrDefault(false)

            // 짧은 일반 단어("car", "auto" 단독)로 인한 비차량 기기 오작동 방지 및 명확한 차량 인포테인먼트 키워드 매칭
            val carKeywords = listOf(
                "car audio", "my car", "car multimedia", "hyundai", "kia", "genesis", "bmw", "audi", "benz", "mercedes",
                "mb bluetooth", "mbux", "volvo", "my volvo", "xc40", "xc60", "xc90", "s60", "s90", "v60", "v90",
                "polestar", "porsche", "tesla", "lexus", "toyota", "honda", "vw", "volkswagen",
                "chevy", "chevrolet", "sonata", "avante", "grandeur",
                "tucson", "santafe", "sorento", "sportage", "carnival", "palisade", "casper",
                "ioniq", "ev6", "ev9", "gv60", "gv70", "gv80", "g70", "g80", "g90",
                "handsfree", "carplay", "android auto", "벤츠", "볼보", "제네시스", "현대", "기아"
            )

            // 3. 다중 차량 프로필, 단일 거점 기기명, 차량 오디오 클래스, 차량 인포테인먼트 키워드 통합 판별
            val isCarDevice = matchedVehicle != null ||
                (targetDevice.isNotBlank() && (deviceName.contains(targetDevice, ignoreCase = true) || targetDevice.contains(deviceName, ignoreCase = true))) ||
                (!isEarphoneOrWearable && (isCarAudioClass || carKeywords.any { deviceName.contains(it, ignoreCase = true) }))

            if (!isCarDevice) return

            // 매칭된 차량이 있으면 자동으로 해당 차량 선택 및 소모품 키 전환
            if (matchedVehicle != null) {
                multiVehiclePreferences.selectVehicle(matchedVehicle.id)
                maintenancePreferences.setCurrentVehicleId(matchedVehicle.id)
                Log.d("CarBluetoothReceiver", "다중 차량 자동 전환 감지: [${matchedVehicle.name}] (${matchedVehicle.licensePlate})")
            }

            val prefs = context.getSharedPreferences("car_trip_tracking", Context.MODE_PRIVATE)
            val servicePrefs = context.getSharedPreferences("car_driving_service_prefs", Context.MODE_PRIVATE)

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val fallbackId = multiVehiclePreferences.selectedVehicleId.value
                    val fallbackProfile = multiVehiclePreferences.vehicles.value.find { it.id == fallbackId }
                    val activeProfile = matchedVehicle ?: fallbackProfile

                    val activeName = activeProfile?.name ?: "차량"
                    val activePlate = activeProfile?.licensePlate ?: ""
                    val vehicleId = activeProfile?.id ?: fallbackId
                    Log.d("CarBluetoothReceiver", "차량 블루투스 연결 감지: $deviceName ($activeName, $activePlate). 백그라운드 추적 서비스 시작")

                    // 탑승 시점 신선한 현재 위치만 확보 (3분 이내 유효 위치만 허용하여 점심/외출 주행에 아침 집 좌표가 혼입되는 현상 차단)
                    val now = System.currentTimeMillis()
                    val loc = getCurrentLocation(context, maxFallbackAgeMs = 3 * 60 * 1000L)
                    val initialStartLat = if (loc != null && loc.latitude != 0.0) loc.latitude else 0.0
                    val initialStartLng = if (loc != null && loc.longitude != 0.0) loc.longitude else 0.0

                    prefs.edit()
                        .putBoolean("is_driving", true)
                        .putString("active_car_id", vehicleId)
                        .putString("active_car_name", activeName)
                        .putString("active_car_plate", activePlate)
                        .putLong("start_time", now)
                        .putLong("last_connect_time", now)
                        .putLong("closed_trip_start_time", 0L)
                        .putFloat("start_lat", initialStartLat.toFloat())
                        .putFloat("start_lng", initialStartLng.toFloat())
                        .putFloat("end_lat", 0.0f)
                        .putFloat("end_lng", 0.0f)
                        .apply()

                    // 백그라운드 포그라운드 GPS 추적 서비스 시작
                    try {
                        com.autologue.app.service.CarDrivingTrackingService.startTracking(
                            context = context,
                            vehicleId = vehicleId,
                            vehicleName = activeName,
                            licensePlate = activePlate
                        )
                    } catch (e: Throwable) {
                        Log.e("CarBluetoothReceiver", "CarDrivingTrackingService 시작 실패", e)
                    }
                }

                BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                    val now = System.currentTimeMillis()
                    val lastDisconnect = prefs.getLong("last_disconnect_time", 0L)
                    val isDriving = prefs.getBoolean("is_driving", false) || servicePrefs.getBoolean("is_tracking", false)

                    // 1. 3초 이내에 동일/멀티 프로필(A2DP, HFP 등)로부터 연이어 수신된 중복 disconnect 브로드캐스트는 무시 (Debounce 3,000ms)
                    if (now - lastDisconnect < 3000L) {
                        Log.d("CarBluetoothReceiver", "차량 블루투스 연결 해제 디바운스 스킵 (연속 수신 방어): $deviceName (${now - lastDisconnect}ms 전 수신됨)")
                        return
                    }
                    prefs.edit().putLong("last_disconnect_time", now).apply()

                    val startTime = prefs.getLong("start_time", 0L)
                    val closedTripStartTime = prefs.getLong("closed_trip_start_time", 0L)
                    val activeCarId = prefs.getString("active_car_id", null) ?: multiVehiclePreferences.selectedVehicleId.value

                    // 2. 만약 백그라운드 제약/워치독으로 서비스가 먼저 종료되었더라도, 실제 차량 블루투스 해제 시각 기준으로 총 소요시간을 정확히 보정
                    if (!isDriving) {
                        if (startTime > 0L &&
                            closedTripStartTime == startTime &&
                            (now - startTime) in (22 * 60_000L)..(240 * 60_000L)
                        ) {
                            val realDurationMin = ((now - startTime) / 60_000L).toInt().coerceAtLeast(1)
                            val durLabel = formatDrivingDurationLabel(realDurationMin)
                            prefs.edit().putLong("closed_trip_start_time", 0L).apply()
                            val pendingResult = goAsync()
                            CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                                try {
                                    val sessionStartLocal = java.time.Instant.ofEpochMilli(startTime)
                                        .atZone(java.time.ZoneId.systemDefault())
                                        .toLocalDateTime()
                                    val candidate = vehicleRepository.getAllVehicleLogsFlow().first()
                                        .filter {
                                            it.logType == VehicleLogType.TRIP_DRIVING &&
                                                it.getAssignedVehicleId(activeCarId) == activeCarId &&
                                                !it.timestamp.isBefore(sessionStartLocal)
                                        }
                                        .maxByOrNull { it.timestamp }
                                    if (candidate != null) {
                                        val oldNote = candidate.note.orEmpty()
                                        val updatedNote = if (Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행").containsMatchIn(oldNote)) {
                                            oldNote.replace(
                                                Regex("(?:\\d+\\s*시간\\s*)?\\d+\\s*분\\s*운행|\\d+\\s*시간\\s*운행"),
                                                "$durLabel 운행"
                                            )
                                        } else {
                                            "$oldNote (${durLabel} 운행)".trim()
                                        }
                                        vehicleRepository.updateVehicleLog(
                                            candidate.copy(
                                                timestamp = LocalDateTime.now(),
                                                note = updatedNote
                                            )
                                        )
                                        Log.i("CarBluetoothReceiver", "지연된 블루투스 해제 기반 실제 주행시간 보정 완료: ${durLabel} 운행")
                                    }
                                } catch (e: Throwable) {
                                    Log.e("CarBluetoothReceiver", "지연 주행시간 보정 실패", e)
                                } finally {
                                    pendingResult.finish()
                                }
                            }
                            return
                        }
                        Log.d("CarBluetoothReceiver", "차량 블루투스 연결 해제 수신되었으나 주행 중 상태가 아니므로 무시: $deviceName")
                        return
                    }

                    // 하차 시점 신선한 도착 위치만 확보 (주행 시작 이후 & 3분 이내 수신된 위치만 인정)
                    val endLoc = getCurrentLocation(context, maxFallbackAgeMs = 3 * 60 * 1000L)
                        ?.takeIf { startTime <= 0L || it.time >= startTime }
                    Log.d("CarBluetoothReceiver", "차량 블루투스 연결 해제: $deviceName. 도착 지점 및 주행 완료 처리 시작")
                    prefs.edit()
                        .putBoolean("is_driving", false)
                        .putFloat("end_lat", (endLoc?.latitude ?: 0.0).toFloat())
                        .putFloat("end_lng", (endLoc?.longitude ?: 0.0).toFloat())
                        .apply()

                    // 백그라운드 GPS 추적 서비스에 정지 및 기록 신호 안전 전송
                    try {
                        com.autologue.app.service.CarDrivingTrackingService.stopTracking(context)
                    } catch (e: Throwable) {
                        Log.e("CarBluetoothReceiver", "CarDrivingTrackingService 정지 신호 전송 실패", e)
                    }

                    Log.d("CarBluetoothReceiver", "CarDrivingTrackingService에 주행 종료 및 정밀 거리 기록 위임 완료")
                }
            }
        } catch (t: Throwable) {
            Log.e("CarBluetoothReceiver", "onReceive 중 예기치 못한 에러 안전 포획", t)
        }
    }

    /**
     * FUSED / NETWORK / PASSIVE / GPS 공급자에서 신선한 현재 위치를 확보합니다.
     * 24시간 전의 묵은 캐시 좌표(예: 아침 집 좌표)가 낮/저녁 주행에 재사용되지 않도록 기본 허용 연한을 3분으로 제한합니다.
     */
    private fun getCurrentLocation(
        context: Context,
        maxFallbackAgeMs: Long = 3 * 60 * 1000L
    ): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = mutableListOf<String>()
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S &&
                lm.allProviders.contains(LocationManager.FUSED_PROVIDER)
            ) {
                providers.add(LocationManager.FUSED_PROVIDER)
            }
            providers.addAll(
                listOf(
                    LocationManager.GPS_PROVIDER,
                    LocationManager.NETWORK_PROVIDER,
                    LocationManager.PASSIVE_PROVIDER
                )
            )
            val now = System.currentTimeMillis()
            val freshAgeMs = 90 * 1000L
            var bestFresh: Location? = null
            var bestFallback: Location? = null

            for (p in providers) {
                runCatching {
                    @SuppressLint("MissingPermission")
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0) {
                        val age = (now - loc.time).coerceAtLeast(0L)
                        if (age <= freshAgeMs) {
                            if (bestFresh == null || loc.time > bestFresh!!.time) {
                                bestFresh = loc
                            }
                        } else if (age <= maxFallbackAgeMs) {
                            if (bestFallback == null || loc.time > bestFallback!!.time) {
                                bestFallback = loc
                            }
                        }
                    }
                }
            }
            bestFresh ?: bestFallback
        } catch (e: Exception) {
            null
        }
    }

    private fun distanceMeter(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        if (lat1 == 0.0 || lon1 == 0.0 || lat2 == 0.0 || lon2 == 0.0) return 999999.0
        val results = FloatArray(1)
        Location.distanceBetween(lat1, lon1, lat2, lon2, results)
        return results[0].toDouble()
    }

    private fun calculateStraightDistanceKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2).pow(2.0) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2).pow(2.0)
        return r * (2 * atan2(sqrt(a), sqrt(1 - a)))
    }
}
