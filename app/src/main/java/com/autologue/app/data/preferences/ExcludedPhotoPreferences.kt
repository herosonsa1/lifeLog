package com.autologue.app.data.preferences

import android.content.Context
import android.content.SharedPreferences
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 사용자가 삭제하여 기록에서 제외된 사진(스크린샷, 영수증 등)의 URI를 영구 보관하는 저장소입니다.
 * Room DB 스키마 마이그레이션 위험 없이 SharedPreferences를 활용하여 기존 데이터 손실을 원천 방어합니다.
 */
@Singleton
class ExcludedPhotoPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val prefs: SharedPreferences = context.getSharedPreferences("excluded_photos_prefs", Context.MODE_PRIVATE)

    private val _excludedUris = MutableStateFlow(loadExcludedUris())
    val excludedUris: StateFlow<Set<String>> = _excludedUris.asStateFlow()

    private fun loadExcludedUris(): Set<String> {
        return prefs.getStringSet(KEY_EXCLUDED_URIS, emptySet())?.toSet() ?: emptySet()
    }

    /**
     * 해당 URI가 제외 목록에 포함되어 있는지 O(1)로 확인합니다.
     */
    fun isExcluded(uri: String): Boolean {
        val trimmed = uri.trim()
        return _excludedUris.value.contains(trimmed) || loadExcludedUris().contains(trimmed)
    }

    /**
     * 특정 사진 URI를 제외 목록에 영구 추가합니다.
     */
    fun excludePhoto(uri: String) {
        val trimmed = uri.trim()
        if (trimmed.isBlank()) return

        val current = loadExcludedUris().toMutableSet()
        if (current.add(trimmed)) {
            prefs.edit().putStringSet(KEY_EXCLUDED_URIS, current).apply()
            _excludedUris.value = current
            android.util.Log.d("ExcludedPhotoPreferences", "사진 영구 제외 등록 완료: $trimmed (총 ${current.size}개)")
        }
    }

    /**
     * 제외 목록에서 특정 사진 URI를 제거(복구)합니다.
     */
    fun unexcludePhoto(uri: String) {
        val trimmed = uri.trim()
        val current = loadExcludedUris().toMutableSet()
        if (current.remove(trimmed)) {
            prefs.edit().putStringSet(KEY_EXCLUDED_URIS, current).apply()
            _excludedUris.value = current
            android.util.Log.d("ExcludedPhotoPreferences", "사진 제외 해제 완료: $trimmed")
        }
    }

    /**
     * 전체 제외 목록을 반환합니다.
     */
    fun getExcludedUris(): Set<String> {
        return _excludedUris.value
    }

    /**
     * 모든 제외 목록을 초기화합니다.
     */
    fun clearAllExcluded() {
        prefs.edit().remove(KEY_EXCLUDED_URIS).apply()
        _excludedUris.value = emptySet()
    }

    companion object {
        private const val KEY_EXCLUDED_URIS = "excluded_uris"
    }
}
