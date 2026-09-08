package com.autologue.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.autologue.app.data.ocr.GolfLockerSlipOcrAnalyzer
import com.autologue.app.data.sync.HistoricalDataImporter
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.golf.ProcessGolfLockerSlipUseCase
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
import java.io.File
import java.time.LocalDateTime
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var golfLockerSlipOcrAnalyzer: GolfLockerSlipOcrAnalyzer
    @Inject lateinit var processGolfLockerSlipUseCase: ProcessGolfLockerSlipUseCase
    @Inject lateinit var vehicleRepository: VehicleRepository
    @Inject lateinit var transactionRepository: TransactionRepository
    @Inject lateinit var golfRepository: GolfRepository
    @Inject lateinit var historicalDataImporter: HistoricalDataImporter

    private val _isInitialSyncDone = MutableStateFlow(false)
    val isInitialSyncDone: StateFlow<Boolean> = _isInitialSyncDone.asStateFlow()

    private val _pendingCrashLog = MutableStateFlow<String?>(null)
    val pendingCrashLog: StateFlow<String?> = _pendingCrashLog.asStateFlow()

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled on startup
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            enableEdgeToEdge()
        } catch (e: Exception) {
            Log.e("AUTOLOGUE_INIT", "enableEdgeToEdge warning", e)
        }

        // 이전 실행 중 강제 종료 로그 검사
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
                    // 화면 렌더링이 시작된 후 안전하게 권한 요청 수행 (Window Token 안정화)
                    requestAppPermissions()
                }

                Box(modifier = Modifier.fillMaxSize()) {
                    // 본 화면 네비게이션
                    AppNavigation()

                    // 앱 구동 시 백그라운드 동기화와 연동되는 웰컴 애니메이션 오버레이
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

                    // 🚨 이전 강제 종료(Crash) 발생 시 원클릭 복사 가능한 리포트 다이얼로그 노출
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
                // 가장 최근 에러 섹션 추출 (또는 전체 로그 최대 3000자)
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

    private fun syncInitialData() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                Log.d("AUTOLOGUE_INIT", "Starting syncInitialData...")

                // 1. Seed realistic fuel transactions if not already present
                val existingTxs = transactionRepository.getAllTransactionsFlow().first()
                val hasFuelTx = existingTxs.any { it.category == ExpenseCategory.FUEL || it.merchantName.contains("주유") }
                if (!hasFuelTx) {
                    val sampleGasTxs = listOf(
                        Transaction(
                            timestamp = LocalDateTime.of(2026, 8, 12, 18, 30),
                            amount = 70000L,
                            merchantName = "SK에너지 분당주유소",
                            originalText = "[Web발신] 신한카드 승인 70,000원 08/12 18:30 SK에너지 분당주유소",
                            paymentMethod = PaymentMethod.CREDIT_CARD,
                            category = ExpenseCategory.FUEL,
                            cardOrBankName = "신한카드",
                            isAutoCategorized = true
                        ),
                        Transaction(
                            timestamp = LocalDateTime.of(2026, 8, 20, 14, 15),
                            amount = 65000L,
                            merchantName = "GS칼텍스 삼정주유소",
                            originalText = "[Web발신] 신한카드 승인 65,000원 08/20 14:15 GS칼텍스 삼정주유소",
                            paymentMethod = PaymentMethod.CREDIT_CARD,
                            category = ExpenseCategory.FUEL,
                            cardOrBankName = "신한카드",
                            isAutoCategorized = true
                        ),
                        Transaction(
                            timestamp = LocalDateTime.of(2026, 8, 28, 9, 40),
                            amount = 75000L,
                            merchantName = "S-OIL 판교주유소",
                            originalText = "[Web발신] 현대카드 승인 75,000원 08/28 09:40 S-OIL 판교주유소",
                            paymentMethod = PaymentMethod.CREDIT_CARD,
                            category = ExpenseCategory.FUEL,
                            cardOrBankName = "현대카드",
                            isAutoCategorized = true
                        ),
                        Transaction(
                            timestamp = LocalDateTime.of(2026, 9, 2, 19, 10),
                            amount = 68000L,
                            merchantName = "SK에너지 강남주유소",
                            originalText = "[Web발신] 신한카드 승인 68,000원 09/02 19:10 SK에너지 강남주유소",
                            paymentMethod = PaymentMethod.CREDIT_CARD,
                            category = ExpenseCategory.FUEL,
                            cardOrBankName = "신한카드",
                            isAutoCategorized = true
                        )
                    )
                    for (tx in sampleGasTxs) {
                        transactionRepository.insertTransaction(tx)
                    }
                    Log.d("AUTOLOGUE_INIT", "Inserted sample gas station transactions")
                }

                // Sync refueling logs to car ledger
                val allTxs = transactionRepository.getAllTransactionsFlow().first()
                val syncedCount = vehicleRepository.syncRefuelingFromTransactions(allTxs)
                Log.d("AUTOLOGUE_INIT", "Synced $syncedCount refueling logs to car ledger")
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

            // Android 12 (API 31) 이상 블루투스 연결 권한 (차량 블루투스 연동용)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
            }

            // SMS 권한 추가 (실제 단말기 정책에 따라 예외 없이 안전 요청)
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
            }
        } catch (e: Exception) {
            Log.e("AUTOLOGUE_PERM", "Exception while requesting permissions: ${e.message}", e)
        }
    }
}
