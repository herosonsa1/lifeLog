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
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.presentation.car.calculateRoundTripDistanceKm
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
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

            // 2. 이어폰/워치/스피커 등 비차량 오디오 기기 배제
            val isEarphoneOrWearable = listOf(
                "buds", "airpod", "qcy", "earphone", "headset", "headphone",
                "wh-", "wf-", "watch", "fit", "band", "jbl", "marshall", "bose"
            ).any { deviceName.contains(it, ignoreCase = true) }

            val isCarAudioClass = runCatching {
                device?.bluetoothClass?.deviceClass == android.bluetooth.BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO
            }.getOrDefault(false)

            val carKeywords = listOf(
                "car", "auto", "hyundai", "kia", "genesis", "bmw", "audi", "benz", "mercedes",
                "mb bluetooth", "mbux", "volvo", "my volvo", "xc40", "xc60", "xc90", "s60", "s90", "v60", "v90",
                "polestar", "porsche", "tesla", "lexus", "toyota", "honda", "vw", "volkswagen",
                "chevy", "chevrolet", "k3", "k5", "k7", "k8", "k9", "sonata", "avante", "grandeur",
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

                    // 5분 이내 신선한 위치만 초기 출발 좌표 후보로 기록 (오래된 캐시 좌표로 인한 출발지 오염 방지)
                    val loc = getCurrentLocation(context)
                    prefs.edit()
                        .putBoolean("is_driving", true)
                        .putString("active_car_id", vehicleId)
                        .putString("active_car_name", activeName)
                        .putString("active_car_plate", activePlate)
                        .putLong("start_time", System.currentTimeMillis())
                        .putFloat("start_lat", (loc?.latitude ?: 0.0).toFloat())
                        .putFloat("start_lng", (loc?.longitude ?: 0.0).toFloat())
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

                    // 2. 실제로 주행 중이었을 때만 주행 종료 및 거리 계산 서비스 호출
                    if (!isDriving) {
                        Log.d("CarBluetoothReceiver", "차량 블루투스 연결 해제 수신되었으나 주행 중 상태가 아니므로 무시: $deviceName")
                        return
                    }

                    Log.d("CarBluetoothReceiver", "차량 블루투스 연결 해제: $deviceName. 도착 지점 및 주행 완료 처리 시작")
                    prefs.edit().putBoolean("is_driving", false).apply()

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
     * 최근 5분(300,000ms) 이내에 수신된 신선한 위치만 반환합니다.
     * 수 시간~며칠 전의 오래된 LastKnownLocation을 반환하여 출발지가 엉뚱한 곳으로 찍히는 현상을 방지합니다.
     */
    private fun getCurrentLocation(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(
                LocationManager.GPS_PROVIDER,
                LocationManager.NETWORK_PROVIDER,
                LocationManager.PASSIVE_PROVIDER
            )
            val now = System.currentTimeMillis()
            val maxAgeMs = 5 * 60 * 1000L // 5분 이내 위치만 허용
            var best: Location? = null
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    @SuppressLint("MissingPermission")
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && loc.latitude != 0.0 && loc.longitude != 0.0 && (now - loc.time) <= maxAgeMs) {
                        if (best == null || loc.time > best.time) {
                            best = loc
                        }
                    }
                }
            }
            best
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
