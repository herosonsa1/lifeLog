package com.autologue.app

import android.Manifest
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
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
import com.autologue.app.presentation.navigation.AppNavigation
import com.autologue.app.presentation.theme.AutoLogueTheme
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { _ ->
        // Permissions handled on startup
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestAppPermissions()
        syncInitialData()

        setContent {
            AutoLogueTheme {
                AppNavigation()
            }
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

                // 2. Scan photos via MediaStore ContentResolver
                val photos = historicalDataImporter.scanHistoricalPhotos(this@MainActivity, daysBack = null)
                Log.d("AUTOLOGUE_INIT", "Found ${photos.size} photos in MediaStore")
                for (photo in photos) {
                    val uri = Uri.parse(photo.uri)
                    val result = golfLockerSlipOcrAnalyzer.analyzeLockerSlip(uri, fallbackDate = photo.time.toLocalDate())
                    Log.d("AUTOLOGUE_INIT", "OCR Result for ${photo.uri}: isSlip=${result.isLockerSlip}, club=${result.clubName}, locker=${result.lockerNumber}")
                    if (result.isLockerSlip) {
                        processGolfLockerSlipUseCase(result, photo.uri)
                    }
                }
            } catch (e: Exception) {
                Log.e("AUTOLOGUE_INIT", "Error during syncInitialData", e)
            }
        }
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.READ_SMS,
            Manifest.permission.RECEIVE_SMS,
            Manifest.permission.CAMERA,
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            permissions.add(Manifest.permission.ACCESS_MEDIA_LOCATION)
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }

        val needed = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (needed.isNotEmpty()) {
            requestPermissionLauncher.launch(needed.toTypedArray())
        }
    }
}
