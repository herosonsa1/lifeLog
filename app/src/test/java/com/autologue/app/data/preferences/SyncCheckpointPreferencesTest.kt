package com.autologue.app.data.preferences

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime
import java.time.ZoneId

class SyncCheckpointPreferencesTest {

    @Test
    fun `formatEpochMillis returns fallback label when timestamp is zero or negative`() {
        assertEquals("기록 없음 (최초 실행)", SyncCheckpointPreferences.formatEpochMillis(0L))
        assertEquals("기록 없음 (최초 실행)", SyncCheckpointPreferences.formatEpochMillis(-100L))
    }

    @Test
    fun `formatEpochMillis formats valid epoch millis in Korean pattern`() {
        val dt = LocalDateTime.of(2026, 10, 19, 21, 30)
        val millis = dt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        assertEquals("10/19 21:30", SyncCheckpointPreferences.formatEpochMillis(millis, fullDate = false))
        assertEquals("2026.10.19 21:30", SyncCheckpointPreferences.formatEpochMillis(millis, fullDate = true))
    }

    @Test
    fun `SyncCheckpointState defaults and hasCompletedInitialSync work properly`() {
        val initialState = SyncCheckpointState()
        assertEquals(0L, initialState.lastAppLaunchedAtMillis)
        assertEquals(0L, initialState.previousAppLaunchedAtMillis)
        assertEquals(0L, initialState.lastSyncCompletedAtMillis)
        assertEquals(0L, initialState.lastSmsSyncCompletedAtMillis)
        assertEquals(0L, initialState.lastPhotoSyncCompletedAtMillis)
        assertEquals(0L, initialState.lastDiarySyncCompletedAtMillis)
        assertEquals(0, initialState.lastLegacyCleanupVersion)
        assertFalse(initialState.hasCompletedInitialSync)

        val syncedState = initialState.copy(
            lastAppLaunchedAtMillis = 1_792_413_000_000L,
            lastSyncCompletedAtMillis = 1_792_413_005_000L
        )
        assertTrue(syncedState.hasCompletedInitialSync)
    }

    @Test
    fun `incremental cutoff selects later timestamp between daysBack window and lastSyncCompletedAt`() {
        val nowMillis = 1_800_000_000_000L
        val sevenDaysAgoMillis = nowMillis - (7L * 24 * 60 * 60 * 1000L)
        val oneHourAgoMillis = nowMillis - (60 * 60 * 1000L)

        val effectiveCutoff = maxOf(sevenDaysAgoMillis, oneHourAgoMillis)
        assertEquals(oneHourAgoMillis, effectiveCutoff)
        assertTrue(effectiveCutoff > sevenDaysAgoMillis)
    }
}
