package com.autologue.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.presentation.common.CrashReportDialog
import com.autologue.app.presentation.common.WelcomeSplashScreen
import com.autologue.app.presentation.navigation.AppNavigation
import com.autologue.app.presentation.theme.AutoLogueTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var vehicleRepository: VehicleRepository
    @Inject lateinit var transactionRepository: TransactionRepository
    @Inject lateinit var diaryRepository: com.autologue.app.domain.repository.DiaryRepository
    @Inject lateinit var historicalDataImporter: HistoricalDataImporter
    @Inject lateinit var processTransactionUseCase: com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
    @Inject lateinit var userLocationPreferences: com.autologue.app.data.preferences.UserLocationPreferences
    @Inject lateinit var placeResolver: com.autologue.app.data.sync.PlaceResolver
    @Inject lateinit var syncCheckpointPreferences: com.autologue.app.data.preferences.SyncCheckpointPreferences
    @Inject lateinit var syncHistoricalDataUseCase: com.autologue.app.domain.usecase.sync.SyncHistoricalDataUseCase

    private val _isInitialSyncDone = MutableStateFlow(false)
    val isInitialSyncDone: StateFlow<Boolean> = _isInitialSyncDone.asStateFlow()

    private val _pendingCrashLog = MutableStateFlow<String?>(null)
    val pendingCrashLog: StateFlow<String?> = _pendingCrashLog.asStateFlow()

    private val requestBackgroundLocationLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        Log.d("AUTOLOGUE_PERM", "ACCESS_BACKGROUND_LOCATION result: $granted")
    }

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grantResults ->
        val mediaGranted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            grantResults[Manifest.permission.READ_MEDIA_IMAGES] == true
        } else {
            grantResults[Manifest.permission.READ_EXTERNAL_STORAGE] == true
        }
        if (grantResults[Manifest.permission.READ_SMS] == true || mediaGranted) {
            syncInitialData(forceSync = true)
        }
        if (grantResults[Manifest.permission.ACCESS_FINE_LOCATION] == true ||
            grantResults[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        ) {
            requestBackgroundLocationIfNeeded()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        runCatching { syncCheckpointPreferences.recordAppLaunch() }

        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            Log.e("AUTOLOGUE_INIT", "enableEdgeToEdge warning", e)
        }

        checkPreviousCrashLog()

        try {
            syncInitialData()
        } catch (e: Exception) {
            Log.e("AUTOLOGUE_INIT", "syncInitialData launch failed", e)
            _isInitialSyncDone.value = true
        }

        setContent {
            AutoLogueTheme {
                val isSyncDone by isInitialSyncDone.collectAsState()
                val crashLog by pendingCrashLog.collectAsState()
                var showWelcomeSplash by rememberSaveable { mutableStateOf(true) }

                LaunchedEffect(Unit) {
                    requestAppPermissions()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    AppNavigation()

                    AnimatedVisibility(
                        visible = showWelcomeSplash,
                        enter = fadeIn(),
                        exit = fadeOut(animationSpec = tween(durationMillis = 600)) +
                                scaleOut(targetScale = 1.05f, animationSpec = tween(durationMillis = 600))
                    ) {
                        WelcomeSplashScreen(
                            isSyncCompleted = isSyncDone,
                            onDismiss = {
                                showWelcomeSplash = false
                            }
                        )
                    }

                    if (crashLog != null) {
                        CrashReportDialog(
                            crashLog = crashLog!!,
                            onDismiss = { _pendingCrashLog.value = null },
                            onClearLog = { clearCrashLog() }
                        )
                    }
                }
            }
        }
    }

    private fun checkPreviousCrashLog() {
        try {
            val logFile = File(filesDir, "crash_log.txt")
            if (logFile.exists() && logFile.length() > 0) {
                val fullText = logFile.readText()
                val displayText = if (fullText.length > 3500) {
                    "..." + fullText.takeLast(3500)
                } else fullText
                _pendingCrashLog.value = displayText
            }
        } catch (e: Exception) {
            Log.e("AUTOLOGUE_CRASH", "Failed to read crash_log.txt", e)
        }
    }

    private fun clearCrashLog() {
        try {
            val logFile = File(filesDir, "crash_log.txt")
            if (logFile.exists()) {
                logFile.delete()
            }
            val prefs = getSharedPreferences("app_crash_state", Context.MODE_PRIVATE)
            prefs.edit().clear().apply()
            _pendingCrashLog.value = null
        } catch (e: Exception) {
            Log.e("AUTOLOGUE_CRASH", "Failed to clear crash log", e)
        }
    }

    private fun syncInitialData(forceSync: Boolean = false) {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val lastLaunch = syncCheckpointPreferences.getLastAppLaunchedAtMillis()
                val lastSync = syncCheckpointPreferences.getLastSyncCompletedAtMillis()
                Log.d(
                    "AUTOLOGUE_INIT",
                    "Starting incremental syncInitialData (lastLaunch=$lastLaunch, lastSyncCompleted=$lastSync)..."
                )

                if (!syncCheckpointPreferences.isLegacyCleanupDone()) {
                    runCatching { diaryRepository.cleanDuplicates() }
                    runCatching {
                        val cfg = userLocationPreferences.config.value
                        val homeHint = cfg.homeAddress.ifBlank { cfg.homeName }
                        val compHint = cfg.companyAddress.ifBlank { cfg.companyName }
                        val homeDong = (withTimeoutOrNull(1200L) {
                            placeResolver.resolveNeighborhoodName(
                                this@MainActivity, cfg.homeLat, cfg.homeLng, homeHint
                            )
                        } ?: "").ifBlank { cfg.homeName.ifBlank { "송파구 위례동" } }
                        val compDong = (withTimeoutOrNull(1200L) {
                            placeResolver.resolveNeighborhoodName(
                                this@MainActivity, cfg.companyLat, cfg.companyLng, compHint
                            )
                        } ?: "").ifBlank { cfg.companyName.ifBlank { "영등포구 영등포동" } }
                        val dist = if (cfg.commuteRoundTripKm > 0.0) cfg.commuteRoundTripKm else 54.2
                        vehicleRepository.cleanDuplicatesAndCorruptedLogs(homeDong, compDong, dist)
                    }
                    runCatching { transactionRepository.cleanDuplicates() }
                }

                syncHistoricalDataUseCase.startAutoSync(
                    context = applicationContext,
                    daysBack = 7,
                    force = forceSync
                )

                withTimeoutOrNull(2500L) {
                    syncHistoricalDataUseCase.syncState.first { it != null && it.isDone }
                }
            } catch (e: Exception) {
                Log.e("AUTOLOGUE_INIT", "Error during syncInitialData", e)
            } finally {
                _isInitialSyncDone.value = true
                Log.d("AUTOLOGUE_INIT", "Initial sync finished, _isInitialSyncDone set to true")
            }
        }
    }

    private fun requestAppPermissions() {
        try {
            val permissions = mutableListOf(
                Manifest.permission.CAMERA,
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            } else {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            permissions.add(Manifest.permission.READ_SMS)
            permissions.add(Manifest.permission.RECEIVE_SMS)

            val needed = permissions.filter {
                try {
                    ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
                } catch (e: Exception) {
                    false
                }
            }

            if (needed.isNotEmpty()) {
                requestPermissionLauncher.launch(needed.toTypedArray())
            } else {
                requestBackgroundLocationIfNeeded()
            }
        } catch (e: Exception) {
            Log.e("AUTOLOGUE_PERM", "Exception while requesting permissions: ${e.message}", e)
        }
    }

    private fun requestBackgroundLocationIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            try {
                val hasFineOrCoarse = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED || ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_COARSE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                val hasBgLocation = ContextCompat.checkSelfPermission(
                    this, Manifest.permission.ACCESS_BACKGROUND_LOCATION
                ) == PackageManager.PERMISSION_GRANTED

                if (hasFineOrCoarse && !hasBgLocation) {
                    val prefs = getSharedPreferences("autologue_perm_prefs", Context.MODE_PRIVATE)
                    val promptedOnce = prefs.getBoolean("bg_location_prompted", false)
                    if (!promptedOnce) {
                        prefs.edit().putBoolean("bg_location_prompted", true).apply()
                        requestBackgroundLocationLauncher.launch(Manifest.permission.ACCESS_BACKGROUND_LOCATION)
                    }
                }
            } catch (e: Exception) {
                Log.e("AUTOLOGUE_PERM", "Background location request warning: ${e.message}", e)
            }
        }
    }
}
