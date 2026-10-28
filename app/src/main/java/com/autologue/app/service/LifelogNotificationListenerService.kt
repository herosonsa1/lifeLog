package com.autologue.app.service

import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import com.autologue.app.data.parser.NotificationParser
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.VehicleLogType
import com.autologue.app.domain.repository.VehicleRepository
import com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.time.LocalDateTime
import javax.inject.Inject

@AndroidEntryPoint
class LifelogNotificationListenerService : NotificationListenerService() {

    @Inject
    lateinit var processTransactionUseCase: ProcessTransactionUseCase

    @Inject
    lateinit var vehicleRepository: VehicleRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        private val recentNotificationCache = java.util.concurrent.ConcurrentHashMap<String, Long>()
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        runCatching {
            activeNotifications?.forEach { sbn ->
                handleStatusBarNotification(sbn)
            }
        }
    }

    override fun onNotificationPosted(sbn: StatusBarNotification?) {
        super.onNotificationPosted(sbn)
        if (sbn != null) {
            handleStatusBarNotification(sbn)
        }
    }

    private fun handleStatusBarNotification(sbn: StatusBarNotification) {
        val extras = sbn.notification?.extras ?: return
        val packageName = sbn.packageName ?: ""
        if (packageName == applicationContext.packageName) return

        val title = (extras.getCharSequence("android.title") ?: extras.getCharSequence("android.conversationTitle"))?.toString() ?: ""
        val fullText = extractFullNotificationText(extras)
        if (title.isBlank() && fullText.isBlank()) return

        val cacheKey = "${packageName}_${title.trim()}_${fullText.trim()}"
        val now = System.currentTimeMillis()
        val lastTime = recentNotificationCache[cacheKey]
        if (lastTime != null && (now - lastTime) < 10_000L) {
            return
        }
        recentNotificationCache[cacheKey] = now
        if (recentNotificationCache.size > 50) {
            recentNotificationCache.entries.removeIf { (now - it.value) > 30_000L }
        }

        val result = NotificationParser.parse(packageName, title, fullText) ?: return

        scope.launch {
            try {
                when (result) {
                    is NotificationParser.ParsedNotificationResult.TxResult -> {
                        processTransactionUseCase(result.transaction)
                        runCatching {
                            vehicleRepository.syncRefuelingFromTransactions(listOf(result.transaction))
                        }
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
            } catch (t: Throwable) {
                android.util.Log.e("NotificationListener", "알림 처리 중 예외 안전 포획", t)
            }
        }
    }

    private fun extractFullNotificationText(extras: android.os.Bundle): String {
        val parts = mutableListOf<String>()

        val text = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""

        if (bigText.isNotBlank() && bigText.length >= text.length) {
            parts.add(bigText)
        } else if (text.isNotBlank()) {
            parts.add(text)
        }

        runCatching {
            val textLines = extras.getCharSequenceArray("android.textLines")
            if (!textLines.isNullOrEmpty()) {
                val joinedLines = textLines.mapNotNull { it?.toString()?.trim() }
                    .filter { it.isNotBlank() }
                    .joinToString("\n")
                if (joinedLines.isNotBlank() && !parts.any { it.contains(joinedLines) }) {
                    parts.add(joinedLines)
                }
            }
        }

        runCatching {
            @Suppress("DEPRECATION")
            val messages = extras.getParcelableArray("android.messages")
            if (!messages.isNullOrEmpty()) {
                val msgTexts = messages.mapNotNull { item ->
                    (item as? android.os.Bundle)?.getCharSequence("text")?.toString()?.trim()
                }.filter { it.isNotBlank() }
                if (msgTexts.isNotEmpty()) {
                    val lastMsg = msgTexts.last()
                    if (!parts.any { it.contains(lastMsg) }) {
                        parts.add(lastMsg)
                    }
                }
            }
        }

        return parts.joinToString("\n").trim()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
