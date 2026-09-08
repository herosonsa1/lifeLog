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

            // 2. 단일 거점 설정의 기기명 또는 일반 카오디오 키워드 매칭
            val isCarDevice = matchedVehicle != null || (if (targetDevice.isNotBlank()) {
                deviceName.contains(targetDevice, ignoreCase = true)
            } else {
                val carKeywords = listOf("car", "bt", "auto", "hyundai", "kia", "genesis", "bmw", "audi", "benz", "chevy", "k5", "k7", "k8", "k9", "sonata", "avante", "grandeur")
                carKeywords.any { deviceName.contains(it, ignoreCase = true) }
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
                    Log.d("CarBluetoothReceiver", "차량 블루투스 연결 감지: $deviceName ($activeName). 출발 지점 캡처 시작")
                    val loc = getCurrentLocation(context)
                    prefs.edit()
                        .putBoolean("is_driving", true)
                        .putString("active_car_name", activeName)
                        .putLong("start_time", System.currentTimeMillis())
                        .putFloat("start_lat", (loc?.latitude ?: 0.0).toFloat())
                        .putFloat("start_lng", (loc?.longitude ?: 0.0).toFloat())
                        .apply()
                }

            BluetoothDevice.ACTION_ACL_DISCONNECTED -> {
                Log.d("CarBluetoothReceiver", "차량 블루투스 연결 해제: $deviceName. 도착 지점 판별 시작")
                val isDriving = prefs.getBoolean("is_driving", false)
                if (!isDriving) return

                val startTime = prefs.getLong("start_time", 0L)
                val durationMin = (System.currentTimeMillis() - startTime) / 60000

                // 2분 미만의 초단기 연결 해제는 단순 시동 on/off로 무시
                if (durationMin < 2) {
                    prefs.edit().putBoolean("is_driving", false).apply()
                    return
                }

                val startLat = prefs.getFloat("start_lat", 0.0f).toDouble()
                val startLng = prefs.getFloat("start_lng", 0.0f).toDouble()

                val endLoc = getCurrentLocation(context)
                val endLat = endLoc?.latitude ?: 0.0
                val endLng = endLoc?.longitude ?: 0.0

                val activeCarName = prefs.getString("active_car_name", "차량") ?: "차량"
                prefs.edit().putBoolean("is_driving", false).apply()

                // [H-03] goAsync(): onReceive() 반환 후에도 프로세스를 살려 DB 기록 완료를 보장
                val pendingResult = goAsync()
                CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
                    try {
                        val homeLat = config.homeLat
                        val homeLng = config.homeLng
                        val compLat = config.companyLat
                        val compLng = config.companyLng

                        val distFromHomeToStart = distanceMeter(homeLat, homeLng, startLat, startLng)
                        val distFromCompToEnd = distanceMeter(compLat, compLng, endLat, endLng)

                        val distFromCompToStart = distanceMeter(compLat, compLng, startLat, startLng)
                        val distFromHomeToEnd = distanceMeter(homeLat, homeLng, endLat, endLng)

                        // 1. 집 -> 회사 출근 판별 (출발지 집 반경 800m & 도착지 회사 반경 800m)
                        if (homeLat != 0.0 && compLat != 0.0 && distFromHomeToStart < 800 && distFromCompToEnd < 800) {
                            val oneWay = if (config.commuteOneWayKm > 0) config.commuteOneWayKm else calculateRoundTripDistanceKm(homeLat, homeLng, compLat, compLng) / 2.0
                            vehicleRepository.recordCommuteTrip(
                                isToWork = true,
                                homeName = config.homeName.ifBlank { "우리집" },
                                companyName = config.companyName.ifBlank { "회사" },
                                distanceKm = oneWay
                            )
                            Log.d("CarBluetoothReceiver", "출근 주행 감지 및 자동 기록 완료: [${activeCarName}] ${oneWay}km")
                        }
                        // 2. 회사 -> 집 퇴근 판별 (출발지 회사 반경 800m & 도착지 집 반경 800m)
                        else if (homeLat != 0.0 && compLat != 0.0 && distFromCompToStart < 800 && distFromHomeToEnd < 800) {
                            val oneWay = if (config.commuteOneWayKm > 0) config.commuteOneWayKm else calculateRoundTripDistanceKm(homeLat, homeLng, compLat, compLng) / 2.0
                            vehicleRepository.recordCommuteTrip(
                                isToWork = false,
                                homeName = config.homeName.ifBlank { "우리집" },
                                companyName = config.companyName.ifBlank { "회사" },
                                distanceKm = oneWay
                            )
                            Log.d("CarBluetoothReceiver", "퇴근 주행 감지 및 자동 기록 완료: [${activeCarName}] ${oneWay}km")
                        }
                        // 3. 일반 차량 주행 판별
                        else if (startLat != 0.0 && endLat != 0.0) {
                            val straightKm = calculateStraightDistanceKm(startLat, startLng, endLat, endLng)
                            val tripKm = (straightKm * 1.25).coerceAtLeast(1.0)
                            val log = com.autologue.app.domain.model.VehicleLog(
                                timestamp = LocalDateTime.now(),
                                logType = com.autologue.app.domain.model.VehicleLogType.TRIP_DRIVING,
                                tripDistanceKm = tripKm,
                                note = "[$activeCarName] 블루투스 연동 자동 주행 ($durationMin 분 운행)"
                            )
                            vehicleRepository.insertVehicleLog(log)
                            Log.d("CarBluetoothReceiver", "일반 주행 자동 기록 완료: [${activeCarName}] ${tripKm}km")
                        }
                    } catch (t: Throwable) {
                        Log.e("CarBluetoothReceiver", "주행 기록 중 오류 발생", t)
                    } finally {
                        // [H-03] 코루틴 완료 후 PendingResult.finish() → 시스템에 작업 완료 신호
                        pendingResult.finish()
                    }
                }
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
