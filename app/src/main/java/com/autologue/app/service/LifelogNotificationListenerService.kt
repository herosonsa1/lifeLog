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

    @Inject
    lateinit var golfRepository: GolfRepository

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    companion object {
        // [중복 방어] 안드로이드 시스템 알림 업데이트로 인한 10초 이내 동일 알림 중복 처리 원천 차단
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
            // 10초 이내 동일 알림 재호출은 즉시 스킵
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
            } catch (t: Throwable) {
                android.util.Log.e("NotificationListener", "알림 처리 중 예외 안전 포획", t)
            }
        }
    }

    /**
     * 삼성 메시지(채팅+ RCS), 구글 메시지, 카드사 앱의 멀티라인 알림(bigText, textLines, MessagingStyle messages) 본문을
     * 누락 없이 전수 추출합니다.
     */
    private fun extractFullNotificationText(extras: android.os.Bundle): String {
        val parts = mutableListOf<String>()

        val text = extras.getCharSequence("android.text")?.toString()?.trim() ?: ""
        val bigText = extras.getCharSequence("android.bigText")?.toString()?.trim() ?: ""

        if (bigText.isNotBlank() && bigText.length >= text.length) {
            parts.add(bigText)
        } else if (text.isNotBlank()) {
            parts.add(text)
        }

        // InboxStyle textLines 추출
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

        // MessagingStyle (RCS 채팅+ 등) android.messages 배열 추출
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

    /**
     * [M-05] 서비스 종료 시 코루틴 스코프를 명시적으로 취소하여 잔존 코루틴 누수 방지.
     * 미호출 시 서비스 종료 후에도 scope 내 코루틴이 계속 실행되어 메모리가 해제되지 않습니다.
     */
    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }
}
