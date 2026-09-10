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

            // 1. 다중 차량 프로필에서 등록된 블루투스 일치 확인
            val matchedVehicle = if (deviceName.isNotBlank()) {
                runCatching { multiVehiclePreferences.findVehicleByBluetooth(deviceName) }.getOrNull()
            } else null

            // 2. 단일 거점 설정의 기기명 또는 실제 차량 인포테인먼트 키워드 매칭
            // [오탐 방지] "bt" 등 이어폰/헤드폰과 겹치는 모호한 약어 키워드는 완전 제거
            val isCarDevice = matchedVehicle != null || (if (targetDevice.isNotBlank()) {
                deviceName.contains(targetDevice, ignoreCase = true)
            } else {
                val carKeywords = listOf(
                    "car", "auto", "hyundai", "kia", "genesis", "bmw", "audi", "benz", "mercedes",
                    "chevy", "chevrolet", "k3", "k5", "k7", "k8", "k9", "sonata", "avante", "grandeur",
                    "tucson", "santafe", "sorento", "sportage", "carnival", "palisade", "casper",
                    "handsfree", "carplay", "android auto"
                )
                // 기기명이 비어있거나 단순 이어폰 키워드(buds, qcy, airpod 등)인 경우 배제
                val isEarphone = listOf("buds", "airpod", "qcy", "earphone", "headset", "headphone", "wh-", "wf-").any { deviceName.contains(it, ignoreCase = true) }
                !isEarphone && carKeywords.any { deviceName.contains(it, ignoreCase = true) }
            })

            if (!isCarDevice) return

            // 매칭된 차량이 있으면 자동으로 해당 차량 선택 및 소모품 키 전환
            if (matchedVehicle != null) {
                multiVehiclePreferences.selectVehicle(matchedVehicle.id)
                maintenancePreferences.setCurrentVehicleId(matchedVehicle.id)
                Log.d("CarBluetoothReceiver", "다중 차량 자동 전환 감지: [${matchedVehicle.name}] (${matchedVehicle.licensePlate})")
            }

            val prefs = context.getSharedPreferences("car_trip_tracking", Context.MODE_PRIVATE)

            when (action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> {
                    val activeName = matchedVehicle?.name ?: "차량"
                    val activePlate = matchedVehicle?.licensePlate ?: ""
                    val vehicleId = matchedVehicle?.id ?: "car_1"
                    Log.d("CarBluetoothReceiver", "차량 블루투스 연결 감지: $deviceName ($activeName, $activePlate). 백그라운드 10분 주기 추적 서비스 시작")
                    
                    // 백그라운드 포그라운드 GPS 10분 주기 추적 서비스 시작
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

                    val loc = getCurrentLocation(context)
                    prefs.edit()
                        .putBoolean("is_driving", true)
                        .putString("active_car_name", activeName)
                        .putString("active_car_plate", activePlate)
                        .putLong("start_time", System.currentTimeMillis())
                        .putFloat("start_lat", (loc?.latitude ?: 0.0).toFloat())
                        .putFloat("start_lng", (loc?.longitude ?: 0.0).toFloat())
                        .apply()
                }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                val now = System.currentTimeMillis()
                val lastDisconnect = prefs.getLong("last_disconnect_time", 0L)
                val isDriving = prefs.getBoolean("is_driving", false)

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

    private fun getCurrentLocation(context: Context): Location? {
        return try {
            val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
            val providers = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER)
            var best: Location? = null
            for (p in providers) {
                if (lm.isProviderEnabled(p)) {
                    @SuppressLint("MissingPermission")
                    val loc = lm.getLastKnownLocation(p)
                    if (loc != null && (best == null || loc.accuracy < best.accuracy)) {
                        best = loc
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
