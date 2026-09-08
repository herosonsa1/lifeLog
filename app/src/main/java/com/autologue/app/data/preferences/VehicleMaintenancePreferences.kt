package com.autologue.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 소모품 개별 상태 데이터
 */
data class MaintenanceItemState(
    val title: String,
    val lastReplacedKm: Int,
    val lastReplacedDate: String,
    val intervalKm: Int,
    val recommendedIntervalKm: Int,
    val recommendedGuide: String
) {
    /**
     * 마지막 교체 이후 현재까지 주행한 거리(km)
     */
    fun getDrivenSinceLast(currentTotalKm: Double): Int {
        val driven = currentTotalKm.toInt() - lastReplacedKm
        return driven.coerceAtLeast(0)
    }

    /**
     * 다음 교체까지 남은 주행거리(km)
     */
    fun getRemainingKm(currentTotalKm: Double): Int {
        val driven = getDrivenSinceLast(currentTotalKm)
        return (intervalKm - driven).coerceAtLeast(0)
    }

    /**
     * 소모 진행 비율 (0.0f ~ 1.0f)
     */
    fun getProgressRatio(currentTotalKm: Double): Float {
        if (intervalKm <= 0) return 1f
        val driven = getDrivenSinceLast(currentTotalKm)
        return (driven.toFloat() / intervalKm).coerceIn(0f, 1f)
    }
}

/**
 * 차량 소모품 종합 설정 데이터
 */
data class VehicleMaintenanceConfig(
    val engineOil: MaintenanceItemState = MaintenanceItemState(
        title = "엔진오일",
        lastReplacedKm = 0,
        lastReplacedDate = "",
        intervalKm = 8000,
        recommendedIntervalKm = 8000,
        recommendedGuide = "권장: 7,000~10,000 km (시내 정체/가혹 조건 시 5,000~7,000 km) 또는 1년마다"
    ),
    val airconFilter: MaintenanceItemState = MaintenanceItemState(
        title = "에어컨 필터",
        lastReplacedKm = 0,
        lastReplacedDate = "",
        intervalKm = 10000,
        recommendedIntervalKm = 10000,
        recommendedGuide = "권장: 5,000~10,000 km 또는 6개월마다 (봄·가을 환절기 1회 교체)"
    ),
    val tireRotation: MaintenanceItemState = MaintenanceItemState(
        title = "타이어 위치 교환",
        lastReplacedKm = 0,
        lastReplacedDate = "",
        intervalKm = 15000,
        recommendedIntervalKm = 15000,
        recommendedGuide = "권장: 10,000~15,000 km마다 앞/뒤 위치 맞바꿈 (전륜 편마모 방지 및 4륜 수명 연장)"
    )
)

/**
 * 차량 소모품 교체 주기 및 최근 교체 내역 SharedPreferences 저장소
 */
@Singleton
class VehicleMaintenancePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("vehicle_maintenance_prefs", Context.MODE_PRIVATE)

    private val _currentVehicleId = MutableStateFlow("car_1")

    private val _config = MutableStateFlow(loadConfig("car_1"))
    val config: StateFlow<VehicleMaintenanceConfig> = _config.asStateFlow()

    fun setCurrentVehicleId(vehicleId: String) {
        _currentVehicleId.value = vehicleId
        _config.value = loadConfig(vehicleId)
    }

    fun loadConfig(vehicleId: String = _currentVehicleId.value): VehicleMaintenanceConfig {
        val prefix = "${vehicleId}_"
        val defaultCfg = VehicleMaintenanceConfig()

        val engineOilLastKm = prefs.getInt("${prefix}engine_oil_last_km", prefs.getInt("engine_oil_last_km", defaultCfg.engineOil.lastReplacedKm))
        val engineOilDate = prefs.getString("${prefix}engine_oil_date", prefs.getString("engine_oil_date", "") ?: "") ?: ""
        val engineOilInterval = prefs.getInt("${prefix}engine_oil_interval", prefs.getInt("engine_oil_interval", defaultCfg.engineOil.intervalKm))

        val airconLastKm = prefs.getInt("${prefix}aircon_last_km", prefs.getInt("aircon_last_km", defaultCfg.airconFilter.lastReplacedKm))
        val airconDate = prefs.getString("${prefix}aircon_date", prefs.getString("aircon_date", "") ?: "") ?: ""
        val airconInterval = prefs.getInt("${prefix}aircon_interval", prefs.getInt("aircon_interval", defaultCfg.airconFilter.intervalKm))

        val tireLastKm = prefs.getInt("${prefix}tire_last_km", prefs.getInt("tire_last_km", defaultCfg.tireRotation.lastReplacedKm))
        val tireDate = prefs.getString("${prefix}tire_date", prefs.getString("tire_date", "") ?: "") ?: ""
        val tireInterval = prefs.getInt("${prefix}tire_interval", prefs.getInt("tire_interval", defaultCfg.tireRotation.intervalKm))

        return VehicleMaintenanceConfig(
            engineOil = defaultCfg.engineOil.copy(
                lastReplacedKm = engineOilLastKm,
                lastReplacedDate = engineOilDate,
                intervalKm = engineOilInterval
            ),
            airconFilter = defaultCfg.airconFilter.copy(
                lastReplacedKm = airconLastKm,
                lastReplacedDate = airconDate,
                intervalKm = airconInterval
            ),
            tireRotation = defaultCfg.tireRotation.copy(
                lastReplacedKm = tireLastKm,
                lastReplacedDate = tireDate,
                intervalKm = tireInterval
            )
        )
    }

    fun updateConfig(
        engineOilLastKm: Int,
        engineOilDate: String,
        engineOilInterval: Int,
        airconLastKm: Int,
        airconDate: String,
        airconInterval: Int,
        tireLastKm: Int,
        tireDate: String,
        tireInterval: Int,
        vehicleId: String = _currentVehicleId.value
    ) {
        val prefix = "${vehicleId}_"
        prefs.edit()
            .putInt("${prefix}engine_oil_last_km", engineOilLastKm)
            .putString("${prefix}engine_oil_date", engineOilDate)
            .putInt("${prefix}engine_oil_interval", engineOilInterval)
            .putInt("${prefix}aircon_last_km", airconLastKm)
            .putString("${prefix}aircon_date", airconDate)
            .putInt("${prefix}aircon_interval", airconInterval)
            .putInt("${prefix}tire_last_km", tireLastKm)
            .putString("${prefix}tire_date", tireDate)
            .putInt("${prefix}tire_interval", tireInterval)
            .apply()

        _config.value = loadConfig(vehicleId)
    }

    fun markReplaced(itemId: String, currentTotalKm: Int, date: String, vehicleId: String = _currentVehicleId.value) {
        val prefix = "${vehicleId}_"
        val editor = prefs.edit()
        when (itemId) {
            "engine_oil" -> {
                editor.putInt("${prefix}engine_oil_last_km", currentTotalKm)
                editor.putString("${prefix}engine_oil_date", date)
            }
            "aircon_filter" -> {
                editor.putInt("${prefix}aircon_last_km", currentTotalKm)
                editor.putString("${prefix}aircon_date", date)
            }
            "tire_rotation" -> {
                editor.putInt("${prefix}tire_last_km", currentTotalKm)
                editor.putString("${prefix}tire_date", date)
            }
        }
        editor.apply()
        _config.value = loadConfig(vehicleId)
    }

    fun resetToDefaults(vehicleId: String = _currentVehicleId.value) {
        val prefix = "${vehicleId}_"
        prefs.edit()
            .remove("${prefix}engine_oil_last_km")
            .remove("${prefix}engine_oil_date")
            .remove("${prefix}engine_oil_interval")
            .remove("${prefix}aircon_last_km")
            .remove("${prefix}aircon_date")
            .remove("${prefix}aircon_interval")
            .remove("${prefix}tire_last_km")
            .remove("${prefix}tire_date")
            .remove("${prefix}tire_interval")
            .apply()
        _config.value = loadConfig(vehicleId)
    }
}
