package com.autologue.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class CommuteConfig(
    val isConfigured: Boolean = false,
    val homeName: String = "",
    val homeAddress: String = "",
    val homeLat: Double = 0.0,
    val homeLng: Double = 0.0,
    val companyName: String = "",
    val companyAddress: String = "",
    val companyLat: Double = 0.0,
    val companyLng: Double = 0.0,
    val commuteOneWayKm: Double = 0.0,
    val commuteRoundTripKm: Double = 0.0,
    val carBluetoothDevice: String = ""
)

@Singleton
class UserLocationPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_commute_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<CommuteConfig> = _config.asStateFlow()

    private fun loadConfig(): CommuteConfig {
        val isConfigured = prefs.getBoolean("is_configured", false)
        val homeName = prefs.getString("home_name", "") ?: ""
        val homeAddress = prefs.getString("home_address", "") ?: ""
        val homeLat = prefs.getFloat("home_lat", 0.0f).toDouble()
        val homeLng = prefs.getFloat("home_lng", 0.0f).toDouble()

        val companyName = prefs.getString("company_name", "") ?: ""
        val companyAddress = prefs.getString("company_address", "") ?: ""
        val companyLat = prefs.getFloat("company_lat", 0.0f).toDouble()
        val companyLng = prefs.getFloat("company_lng", 0.0f).toDouble()

        val roundTripKm = prefs.getFloat("commute_round_trip_km", 0.0f).toDouble()
        val oneWayKm = if (roundTripKm > 0.0) roundTripKm / 2.0 else 0.0
        val bluetoothDevice = prefs.getString("car_bluetooth_device", "") ?: ""

        return CommuteConfig(
            isConfigured = isConfigured,
            homeName = homeName,
            homeAddress = homeAddress,
            homeLat = homeLat,
            homeLng = homeLng,
            companyName = companyName,
            companyAddress = companyAddress,
            companyLat = companyLat,
            companyLng = companyLng,
            commuteOneWayKm = oneWayKm,
            commuteRoundTripKm = roundTripKm,
            carBluetoothDevice = bluetoothDevice
        )
    }

    fun updateConfig(
        homeName: String,
        homeAddress: String,
        homeLat: Double,
        homeLng: Double,
        companyName: String,
        companyAddress: String,
        companyLat: Double,
        companyLng: Double,
        roundTripKm: Double,
        carBluetoothDevice: String = ""
    ) {
        val hasConfig = homeName.isNotBlank() && companyName.isNotBlank() && roundTripKm > 0.0
        prefs.edit()
            .putBoolean("is_configured", hasConfig)
            .putString("home_name", homeName.trim())
            .putString("home_address", homeAddress.trim())
            .putFloat("home_lat", homeLat.toFloat())
            .putFloat("home_lng", homeLng.toFloat())
            .putString("company_name", companyName.trim())
            .putString("company_address", companyAddress.trim())
            .putFloat("company_lat", companyLat.toFloat())
            .putFloat("company_lng", companyLng.toFloat())
            .putFloat("commute_round_trip_km", roundTripKm.toFloat())
            .putString("car_bluetooth_device", carBluetoothDevice.trim())
            .apply()

        _config.value = loadConfig()
    }

    fun resetConfig() {
        prefs.edit().clear().apply()
        _config.value = CommuteConfig()
    }
}
