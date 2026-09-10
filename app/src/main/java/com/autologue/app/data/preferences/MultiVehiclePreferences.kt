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
 * 개별 차량 프로필 정보
 */
data class VehicleProfile(
    val id: String, // "car_1", "car_2"
    val name: String, // 예: "차량 1 (메인)", "차량 2 (세컨)"
    val licensePlate: String = "", // 번호판
    val fuelType: String = "가솔린", // 가솔린, 디젤, 하이브리드, 전기차, LPG
    val bluetoothDevice: String = "", // 차량 블루투스 이름
    val initialOdometerKm: Double = 0.0, // 등록 시 계기판 누적 주행거리
    val targetEfficiencyKmPerL: Double = 12.0, // 공인/목표 연비
    val customEmoji: String = "", // 브랜드 엠블럼 이모지
    val defaultGasPrice: Double = 1650.0 // 주유 단가 미입력 시 적용될 기본 유가 (원/L)
) {
    val emblemEmoji: String
        get() = customEmoji.ifBlank { com.autologue.app.util.VehicleBrandUtils.getBrandEmoji(name) }
}

@Singleton
class MultiVehiclePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("multi_vehicle_prefs", Context.MODE_PRIVATE)

    private val _vehicles = MutableStateFlow(loadVehicles())
    val vehicles: StateFlow<List<VehicleProfile>> = _vehicles.asStateFlow()

    private val _selectedVehicleId = MutableStateFlow(loadSelectedVehicleId())
    val selectedVehicleId: StateFlow<String> = _selectedVehicleId.asStateFlow()

    private fun loadSelectedVehicleId(): String {
        return prefs.getString("selected_vehicle_id", "car_1") ?: "car_1"
    }

    private fun loadVehicles(): List<VehicleProfile> {
        val car1 = VehicleProfile(
            id = "car_1",
            name = prefs.getString("car_1_name", "차량 1 (메인)") ?: "차량 1 (메인)",
            licensePlate = prefs.getString("car_1_plate", "") ?: "",
            fuelType = prefs.getString("car_1_fuel", "가솔린") ?: "가솔린",
            bluetoothDevice = prefs.getString("car_1_bt", "") ?: "",
            initialOdometerKm = prefs.getFloat("car_1_init_odo", 0f).toDouble(),
            targetEfficiencyKmPerL = prefs.getFloat("car_1_efficiency", 12.5f).toDouble(),
            customEmoji = prefs.getString("car_1_emoji", "") ?: "",
            defaultGasPrice = prefs.getFloat("car_1_default_gas_price", 1650.0f).toDouble()
        )

        val car2 = VehicleProfile(
            id = "car_2",
            name = prefs.getString("car_2_name", "차량 2 (서브)") ?: "차량 2 (서브)",
            licensePlate = prefs.getString("car_2_plate", "") ?: "",
            fuelType = prefs.getString("car_2_fuel", "하이브리드") ?: "하이브리드",
            bluetoothDevice = prefs.getString("car_2_bt", "") ?: "",
            initialOdometerKm = prefs.getFloat("car_2_init_odo", 0f).toDouble(),
            targetEfficiencyKmPerL = prefs.getFloat("car_2_efficiency", 16.0f).toDouble(),
            customEmoji = prefs.getString("car_2_emoji", "") ?: "",
            defaultGasPrice = prefs.getFloat("car_2_default_gas_price", 1650.0f).toDouble()
        )

        return listOf(car1, car2)
    }

    fun selectVehicle(vehicleId: String) {
        prefs.edit().putString("selected_vehicle_id", vehicleId).apply()
        _selectedVehicleId.value = vehicleId
    }

    fun updateVehicle(
        id: String,
        name: String,
        licensePlate: String,
        fuelType: String,
        bluetoothDevice: String,
        initialOdometerKm: Double = 0.0,
        targetEfficiencyKmPerL: Double = 12.0,
        customEmoji: String = "",
        defaultGasPrice: Double = 1650.0
    ) {
        prefs.edit()
            .putString("${id}_name", name)
            .putString("${id}_plate", licensePlate)
            .putString("${id}_fuel", fuelType)
            .putString("${id}_bt", bluetoothDevice)
            .putFloat("${id}_init_odo", initialOdometerKm.toFloat())
            .putFloat("${id}_efficiency", targetEfficiencyKmPerL.toFloat())
            .putString("${id}_emoji", customEmoji)
            .putFloat("${id}_default_gas_price", defaultGasPrice.toFloat())
            .apply()

        _vehicles.value = loadVehicles()
    }

    fun findVehicleByBluetooth(deviceName: String): VehicleProfile? {
        if (deviceName.isBlank()) return null
        return _vehicles.value.firstOrNull {
            it.bluetoothDevice.isNotBlank() && (
                deviceName.contains(it.bluetoothDevice, ignoreCase = true) ||
                it.bluetoothDevice.contains(deviceName, ignoreCase = true)
            )
        }
    }
}
