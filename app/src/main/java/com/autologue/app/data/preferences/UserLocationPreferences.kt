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
    val homeName: String = "서울 방이동",
    val homeAddress: String = "서울특별시 송파구 방이동",
    val companyName: String = "판교 테크노밸리",
    val companyAddress: String = "경기도 성남시 분당구 판교역로",
    val commuteOneWayKm: Double = 18.5,
    val commuteRoundTripKm: Double = 37.0
)

@Singleton
class UserLocationPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("user_commute_prefs", Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfig())
    val config: StateFlow<CommuteConfig> = _config.asStateFlow()

    private fun loadConfig(): CommuteConfig {
        val homeName = prefs.getString("home_name", "서울 방이동") ?: "서울 방이동"
        val homeAddress = prefs.getString("home_address", "서울특별시 송파구 방이동") ?: "서울특별시 송파구 방이동"
        val companyName = prefs.getString("company_name", "판교 테크노밸리") ?: "판교 테크노밸리"
        val companyAddress = prefs.getString("company_address", "경기도 성남시 분당구 판교역로") ?: "경기도 성남시 분당구 판교역로"
        val roundTripKm = prefs.getFloat("commute_round_trip_km", 37.0f).toDouble()
        val oneWayKm = roundTripKm / 2.0

        return CommuteConfig(
            homeName = homeName,
            homeAddress = homeAddress,
            companyName = companyName,
            companyAddress = companyAddress,
            commuteOneWayKm = oneWayKm,
            commuteRoundTripKm = roundTripKm
        )
    }

    fun updateConfig(
        homeName: String,
        homeAddress: String,
        companyName: String,
        companyAddress: String,
        roundTripKm: Double
    ) {
        prefs.edit()
            .putString("home_name", homeName.trim().ifBlank { "서울 방이동" })
            .putString("home_address", homeAddress.trim().ifBlank { "서울특별시 송파구 방이동" })
            .putString("company_name", companyName.trim().ifBlank { "판교 테크노밸리" })
            .putString("company_address", companyAddress.trim().ifBlank { "경기도 성남시 분당구 판교역로" })
            .putFloat("commute_round_trip_km", roundTripKm.toFloat().coerceAtLeast(1.0f))
            .apply()

        _config.value = loadConfig()
    }
}
