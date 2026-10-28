package com.autologue.app.service

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.autologue.app.MainActivity
import com.autologue.app.R
import com.autologue.app.domain.usecase.sync.SyncHistoricalDataUseCase
import com.autologue.app.domain.usecase.sync.SyncProgress
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * 앱을 닫거나 화면을 끈 상태에서도 전체 데이터 수집·분석(결제 문자, 갤러리 사진 위치, 다이어리·차계부 구축)이
 * OS 프로세스 절전(Cached App Freezer)에 의해 중단되지 않고 끝까지 완주되도록 보장하는 포그라운드 서비스.
 */
@AndroidEntryPoint
class DataSyncForegroundService : Service() {

    @Inject
    lateinit var syncHistoricalDataUseCase: SyncHistoricalDataUseCase

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var monitorJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private var isForegroundPromoted = false

    companion object {
        private const val TAG = "DataSyncFgService"
        const val CHANNEL_ID = "data_sync_foreground_channel"
        private const val PROGRESS_NOTIFICATION_ID = 2040
        private const val COMPLETE_NOTIFICATION_ID = 2041

        const val ACTION_START_OR_UPDATE = "com.autologue.app.action.START_DATA_SYNC_FG"
        const val ACTION_STOP = "com.autologue.app.action.STOP_DATA_SYNC_FG"

        fun startOrUpdate(context: Context) {
            val appCtx = context.applicationContext ?: context
            val intent = Intent(appCtx, DataSyncForegroundService::class.java).apply {
                action = ACTION_START_OR_UPDATE
            }
            runCatching {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    appCtx.startForegroundService(intent)
                } else {
                    appCtx.startService(intent)
                }
            }.onFailure { e ->
                // Android 12+ 백그라운드 시작 제약 시 일반 startService 폴백 시도
                runCatching { appCtx.startService(intent) }
                Log.w(TAG, " 포그라운드 서비스 시작 폴백 처리: ${e.message}")
            }
        }

        fun stop(context: Context) {
            val appCtx = context.applicationContext ?: context
            val intent = Intent(appCtx, DataSyncForegroundService::class.java).apply {
                action = ACTION_STOP
            }
            runCatching { appCtx.startService(intent) }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSyncService()
            return START_NOT_STICKY
        }

        val currentProgress = if (::syncHistoricalDataUseCase.isInitialized) {
            syncHistoricalDataUseCase.syncState.value
        } else null

        promoteToForeground(currentProgress)
        acquireWakeLock()
        startMonitoringSyncState()

        return START_REDELIVER_INTENT
    }

    private fun startMonitoringSyncState() {
        if (monitorJob?.isActive == true) return
        monitorJob = serviceScope.launch {
            if (!::syncHistoricalDataUseCase.isInitialized) {
                delay(500L)
            }
            if (!::syncHistoricalDataUseCase.isInitialized) {
                stopSyncService()
                return@launch
            }

            syncHistoricalDataUseCase.syncState.collectLatest { progress ->
                when {
                    progress != null && progress.isRunning && !progress.isDone -> {
                        updateProgressNotification(progress)
                    }
                    progress != null && progress.isDone -> {
                        showCompletionNotification(progress)
                        stopSyncService()
                    }
                    progress == null -> {
                        delay(1500L)
                        val recheck = syncHistoricalDataUseCase.syncState.value
                        if (recheck == null || !recheck.isRunning || recheck.isDone) {
                            stopSyncService()
                        }
                    }
                }
            }
        }
    }

    private fun promoteToForeground(progress: SyncProgress?) {
        val notification = buildProgressNotification(progress)
        runCatching {
            val fgType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(this, PROGRESS_NOTIFICATION_ID, notification, fgType)
            isForegroundPromoted = true
        }.onFailure { e ->
            Log.w(TAG, "startForeground 호출 예외 안전 무시: ${e.message}")
        }
    }

    private fun updateProgressNotification(progress: SyncProgress) {
        if (!isForegroundPromoted) {
            promoteToForeground(progress)
            return
        }
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.notify(PROGRESS_NOTIFICATION_ID, buildProgressNotification(progress))
        }
    }

    private fun showCompletionNotification(progress: SyncProgress) {
        runCatching {
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            val pendingIntent = createContentPendingIntent()
            val title = if (progress.overallTotalCount > 0) {
                "데이터 수집·분석 100% 완료 (총 %,d건 처리)".format(progress.overallTotalCount)
            } else {
                "데이터 수집·분석 100% 완료"
            }
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle(title)
                .setContentText(progress.stage)
                .setStyle(NotificationCompat.BigTextStyle().bigText(progress.stage))
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .setAutoCancel(true)
                .setOngoing(false)
                .setProgress(0, 0, false)
                .setContentIntent(pendingIntent)
                .build()
            nm.notify(COMPLETE_NOTIFICATION_ID, notification)
        }
    }

    private fun buildProgressNotification(progress: SyncProgress?): Notification {
        val pendingIntent = createContentPendingIntent()
        val percent = progress?.progressPercent?.coerceIn(0, 100) ?: 0
        val total = progress?.overallTotalCount ?: 0
        val current = progress?.overallProcessedCount ?: 0

        val title = when {
            progress == null -> "백그라운드 데이터 수집·분석 준비 중..."
            total > 0 -> "전체 %,d건 중 %,d건 분석 중 (%d%% 완료)".format(total, current, percent)
            else -> "백그라운드 데이터 수집·분석 중 (%d%% 완료)".format(percent)
        }

        val stageText = progress?.stage ?: "결제 문자 및 갤러리 사진을 분석하고 있습니다."
        val summaryLine = if (progress != null && (progress.syncedTxCount > 0 || progress.syncedPhotoCount > 0)) {
            buildString {
                append(stageText)
                append("\n누적 반영: 결제 ${progress.syncedTxCount}건 · 사진 ${progress.syncedPhotoCount}장")
            }
        } else {
            stageText
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(title)
            .setContentText(stageText)
            .setStyle(NotificationCompat.BigTextStyle().bigText(summaryLine))
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent, total <= 0 && percent <= 0)
            .setContentIntent(pendingIntent)
            .build()
    }

    private fun createContentPendingIntent(): PendingIntent {
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }
        return PendingIntent.getActivity(this, 0, openIntent, flags)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "백그라운드 데이터 수집 및 분석",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "결제 문자, 갤러리 사진 위치, 일상 다이어리 등 백그라운드 수집·분석 진행률을 표시합니다."
                setShowBadge(false)
            }
            val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            nm.createNotificationChannel(channel)
        }
    }

    @SuppressLint("WakelockTimeout")
    private fun acquireWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) return
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "AutoLogue:DataSyncWakeLock").apply {
                setReferenceCounted(false)
                // 최대 20분 안전 타임아웃 부여
                acquire(20 * 60 * 1000L)
            }
        }
    }

    private fun releaseWakeLock() {
        runCatching {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
            wakeLock = null
        }
    }

    private fun stopSyncService() {
        releaseWakeLock()
        runCatching {
            if (isForegroundPromoted) {
                ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
                isForegroundPromoted = false
            }
        }
        runCatching { stopSelf() }
    }

    override fun onDestroy() {
        releaseWakeLock()
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
