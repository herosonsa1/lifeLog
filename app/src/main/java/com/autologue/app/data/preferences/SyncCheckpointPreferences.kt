package com.autologue.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 앱 마지막 구동 시점 및 마지막 데이터 수집·분석 완료 시점 상태 모델
 */
data class SyncCheckpointState(
    val lastAppLaunchedAtMillis: Long = 0L,
    val previousAppLaunchedAtMillis: Long = 0L,
    val lastSyncCompletedAtMillis: Long = 0L,
    val lastSmsSyncCompletedAtMillis: Long = 0L,
    val lastPhotoSyncCompletedAtMillis: Long = 0L,
    val lastGolfSyncCompletedAtMillis: Long = 0L,
    val lastDiarySyncCompletedAtMillis: Long = 0L,
    val lastLegacyCleanupVersion: Int = 0
) {
    val hasCompletedInitialSync: Boolean
        get() = lastSyncCompletedAtMillis > 0L

    fun formatLastLaunchTime(): String =
        SyncCheckpointPreferences.formatEpochMillis(lastAppLaunchedAtMillis)

    fun formatLastSyncCompletedTime(): String =
        SyncCheckpointPreferences.formatEpochMillis(lastSyncCompletedAtMillis)
}

/**
 * 앱이 가장 마지막으로 구동된 시점과 마지막으로 데이터 수집 및 분석이 끝난 시점을 영구 기록하고,
 * 이후 구동 및 동기화 시 해당 시점 이후의 증분(Incremental) 데이터만 수집·분석하도록 제어하는 저장소입니다.
 */
@Singleton
class SyncCheckpointPreferences @Inject constructor(
    @ApplicationContext context: Context
) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(loadState())
    val state: StateFlow<SyncCheckpointState> = _state.asStateFlow()

    private fun loadState(): SyncCheckpointState {
        return SyncCheckpointState(
            lastAppLaunchedAtMillis = prefs.getLong(KEY_LAST_APP_LAUNCHED_AT, 0L),
            previousAppLaunchedAtMillis = prefs.getLong(KEY_PREVIOUS_APP_LAUNCHED_AT, 0L),
            lastSyncCompletedAtMillis = prefs.getLong(KEY_LAST_SYNC_COMPLETED_AT, 0L),
            lastSmsSyncCompletedAtMillis = prefs.getLong(KEY_LAST_SMS_SYNC_COMPLETED_AT, 0L),
            lastPhotoSyncCompletedAtMillis = prefs.getLong(KEY_LAST_PHOTO_SYNC_COMPLETED_AT, 0L),
            lastGolfSyncCompletedAtMillis = prefs.getLong(KEY_LAST_GOLF_SYNC_COMPLETED_AT, 0L),
            lastDiarySyncCompletedAtMillis = prefs.getLong(KEY_LAST_DIARY_SYNC_COMPLETED_AT, 0L),
            lastLegacyCleanupVersion = prefs.getInt(KEY_LAST_LEGACY_CLEANUP_VERSION, 0)
        )
    }

    /**
     * 앱 구동 시 호출하여 마지막 앱 구동 시점을 기록합니다.
     */
    @Synchronized
    fun recordAppLaunch(timestampMillis: Long = System.currentTimeMillis()) {
        val currentLastLaunch = prefs.getLong(KEY_LAST_APP_LAUNCHED_AT, 0L)
        prefs.edit()
            .putLong(KEY_PREVIOUS_APP_LAUNCHED_AT, currentLastLaunch)
            .putLong(KEY_LAST_APP_LAUNCHED_AT, timestampMillis)
            .apply()
        _state.value = loadState()
    }

    /**
     * 전체 데이터 수집 및 분석 파이프라인이 완료된 시점을 기록합니다.
     */
    @Synchronized
    fun recordSyncCompleted(timestampMillis: Long = System.currentTimeMillis()) {
        prefs.edit()
            .putLong(KEY_LAST_SYNC_COMPLETED_AT, timestampMillis)
            .putLong(KEY_LAST_SMS_SYNC_COMPLETED_AT, timestampMillis)
            .putLong(KEY_LAST_PHOTO_SYNC_COMPLETED_AT, timestampMillis)
            .putLong(KEY_LAST_GOLF_SYNC_COMPLETED_AT, timestampMillis)
            .putLong(KEY_LAST_DIARY_SYNC_COMPLETED_AT, timestampMillis)
            .apply()
        _state.value = loadState()
    }

    @Synchronized
    fun recordSmsSyncCompleted(
        timestampMillis: Long = System.currentTimeMillis(),
        updateGlobalCheckpoint: Boolean = true
    ) {
        val editor = prefs.edit().putLong(KEY_LAST_SMS_SYNC_COMPLETED_AT, timestampMillis)
        if (updateGlobalCheckpoint) {
            editor.putLong(KEY_LAST_SYNC_COMPLETED_AT, timestampMillis)
        }
        editor.apply()
        _state.value = loadState()
    }

    @Synchronized
    fun recordPhotoSyncCompleted(
        timestampMillis: Long = System.currentTimeMillis(),
        updateGlobalCheckpoint: Boolean = false
    ) {
        val editor = prefs.edit().putLong(KEY_LAST_PHOTO_SYNC_COMPLETED_AT, timestampMillis)
        if (updateGlobalCheckpoint) {
            editor.putLong(KEY_LAST_SYNC_COMPLETED_AT, timestampMillis)
        }
        editor.apply()
        _state.value = loadState()
    }

    @Synchronized
    fun recordGolfSyncCompleted(
        timestampMillis: Long = System.currentTimeMillis(),
        updateGlobalCheckpoint: Boolean = false
    ) {
        val editor = prefs.edit().putLong(KEY_LAST_GOLF_SYNC_COMPLETED_AT, timestampMillis)
        if (updateGlobalCheckpoint) {
            editor.putLong(KEY_LAST_SYNC_COMPLETED_AT, timestampMillis)
        }
        editor.apply()
        _state.value = loadState()
    }

    @Synchronized
    fun recordDiarySyncCompleted(
        timestampMillis: Long = System.currentTimeMillis(),
        updateGlobalCheckpoint: Boolean = true
    ) {
        val editor = prefs.edit().putLong(KEY_LAST_DIARY_SYNC_COMPLETED_AT, timestampMillis)
        if (updateGlobalCheckpoint) {
            editor.putLong(KEY_LAST_SYNC_COMPLETED_AT, timestampMillis)
        }
        editor.apply()
        _state.value = loadState()
    }

    /**
     * 기존 버전에서 업데이트된 단말기의 경우, DB에 이미 수집·분석된 데이터가 존재하면
     * 가장 최근 데이터의 타임스탬프를 초기 기준점으로 설정하여 불필요한 과거 전체 재스캔을 방지합니다.
     */
    @Synchronized
    fun ensureBaselineCheckpointFromExistingData(latestDataTimestampMillis: Long) {
        if (latestDataTimestampMillis <= 0L) return
        val safeMillis = latestDataTimestampMillis.coerceAtMost(System.currentTimeMillis())
        val currentGlobal = prefs.getLong(KEY_LAST_SYNC_COMPLETED_AT, 0L)
        if (currentGlobal <= 0L) {
            prefs.edit()
                .putLong(KEY_LAST_SYNC_COMPLETED_AT, safeMillis)
                .putLong(KEY_LAST_SMS_SYNC_COMPLETED_AT, safeMillis)
                .putLong(KEY_LAST_PHOTO_SYNC_COMPLETED_AT, safeMillis)
                .putLong(KEY_LAST_GOLF_SYNC_COMPLETED_AT, safeMillis)
                .putLong(KEY_LAST_DIARY_SYNC_COMPLETED_AT, safeMillis)
                .apply()
            _state.value = loadState()
        }
    }

    fun getLastAppLaunchedAtMillis(): Long = prefs.getLong(KEY_LAST_APP_LAUNCHED_AT, 0L)
    fun getPreviousAppLaunchedAtMillis(): Long = prefs.getLong(KEY_PREVIOUS_APP_LAUNCHED_AT, 0L)
    fun getLastSyncCompletedAtMillis(): Long = prefs.getLong(KEY_LAST_SYNC_COMPLETED_AT, 0L)

    fun getLastSmsSyncCompletedAtMillis(): Long {
        val sms = prefs.getLong(KEY_LAST_SMS_SYNC_COMPLETED_AT, 0L)
        return if (sms > 0L) sms else getLastSyncCompletedAtMillis()
    }

    fun getLastPhotoSyncCompletedAtMillis(): Long {
        val photo = prefs.getLong(KEY_LAST_PHOTO_SYNC_COMPLETED_AT, 0L)
        return if (photo > 0L) photo else getLastSyncCompletedAtMillis()
    }

    fun getLastGolfSyncCompletedAtMillis(): Long {
        val golf = prefs.getLong(KEY_LAST_GOLF_SYNC_COMPLETED_AT, 0L)
        return if (golf > 0L) golf else getLastSyncCompletedAtMillis()
    }

    fun getLastDiarySyncCompletedAtMillis(): Long {
        val diary = prefs.getLong(KEY_LAST_DIARY_SYNC_COMPLETED_AT, 0L)
        return if (diary > 0L) diary else getLastSyncCompletedAtMillis()
    }

    fun isLegacyCleanupDone(targetVersion: Int = CURRENT_CLEANUP_VERSION): Boolean {
        return prefs.getInt(KEY_LAST_LEGACY_CLEANUP_VERSION, 0) >= targetVersion
    }

    @Synchronized
    fun markLegacyCleanupDone(targetVersion: Int = CURRENT_CLEANUP_VERSION) {
        prefs.edit().putInt(KEY_LAST_LEGACY_CLEANUP_VERSION, targetVersion).apply()
        _state.value = loadState()
    }

    companion object {
        private const val PREFS_NAME = "sync_checkpoint_prefs"
        private const val KEY_LAST_APP_LAUNCHED_AT = "last_app_launched_at_millis"
        private const val KEY_PREVIOUS_APP_LAUNCHED_AT = "previous_app_launched_at_millis"
        private const val KEY_LAST_SYNC_COMPLETED_AT = "last_sync_completed_at_millis"
        private const val KEY_LAST_SMS_SYNC_COMPLETED_AT = "last_sms_sync_completed_at_millis"
        private const val KEY_LAST_PHOTO_SYNC_COMPLETED_AT = "last_photo_sync_completed_at_millis"
        private const val KEY_LAST_GOLF_SYNC_COMPLETED_AT = "last_golf_sync_completed_at_millis"
        private const val KEY_LAST_DIARY_SYNC_COMPLETED_AT = "last_diary_sync_completed_at_millis"
        private const val KEY_LAST_LEGACY_CLEANUP_VERSION = "last_legacy_cleanup_version"

        const val CURRENT_CLEANUP_VERSION = 27

        private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("MM/dd HH:mm", Locale.KOREA)
        private val FULL_DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy.MM.dd HH:mm", Locale.KOREA)

        fun formatEpochMillis(epochMillis: Long, fullDate: Boolean = false): String {
            if (epochMillis <= 0L) return "기록 없음 (최초 실행)"
            val ldt = LocalDateTime.ofInstant(
                Instant.ofEpochMilli(epochMillis),
                ZoneId.systemDefault()
            )
            return if (fullDate) ldt.format(FULL_DISPLAY_FORMATTER) else ldt.format(DISPLAY_FORMATTER)
        }
    }
}
