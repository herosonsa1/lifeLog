package com.autologue.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autologue.app.data.parser.NotificationParser
import com.autologue.app.domain.model.GolfRound
import com.autologue.app.domain.model.GolfType
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.repository.GolfRepository
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

@AndroidEntryPoint
class LifelogNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var processTransactionUseCase: ProcessTransactionUseCase

    @Inject
    lateinit var vehicleRepository: VehicleRepository

    @Inject
    lateinit var golfRepository: GolfRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        val sbnNonNull = sbn ?: return
        val extras = sbnNonNull.notification.extras ?: return
        val title = extras.getString("android.title") ?: ""
        val text = extras.getCharSequence("android.text")?.toString() ?: ""
        val packageName = sbnNonNull.packageName ?: ""

        val result = NotificationParser.parse(packageName, title, text) ?: return

        scope.launch {
            when (result) {
                is NotificationParser.ParsedNotificationResult.TxResult -> {
                    processTransactionUseCase(result.transaction)
                }
                is NotificationParser.ParsedNotificationResult.GolfzonResult -> {
                    val round = GolfRound(
                        clubName = result.storeName,
                        roundDate = LocalDateTime.now(),
                        golfType = GolfType.SCREEN,
                        totalScore = result.score,
                        memo = "골프존 알림 연동 자동 기록"
                    )
                    golfRepository.insertGolfRound(round)
                }
                is NotificationParser.ParsedNotificationResult.TmapResult -> {
                    val vehicleLog = VehicleLog(
                        timestamp = LocalDateTime.now(),
                        logType = VehicleLogType.TRIP_DRIVING,
                        tripDistanceKm = result.distanceKm,
                        note = "TMAP 주행 완료 연동"
                    )
                    vehicleRepository.insertVehicleLog(vehicleLog)
                }
            }
        }
    }
}
