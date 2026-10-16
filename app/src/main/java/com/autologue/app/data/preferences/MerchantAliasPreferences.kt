package com.autologue.app.data.preferences

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 사용자가 가계부 또는 차계부에서 한 번 수정한 결제명(가맹점명/주유소명/이체처명)을 기억하여,
 * 동일 결제처로부터 등록된 기존 내역 및 향후 새로 수신·동기화되는 모든 결제 내역을
 * 자동으로 수정된 결제명으로 치환하는 영속 매핑 저장소입니다.
 */
@Singleton
class MerchantAliasPreferences private constructor(
    private val prefs: android.content.SharedPreferences?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    // 단위 테스트용 인메모리 생성자
    constructor() : this(null)

    // key: 정규화된 원본 결제처명, value: 사용자가 지정한 새 결제명
    private val aliasMap = ConcurrentHashMap<String, String>()

    private val _aliasesFlow = MutableStateFlow<Map<String, String>>(emptyMap())
    val aliasesFlow: StateFlow<Map<String, String>> = _aliasesFlow.asStateFlow()

    init {
        loadFromPrefs()
    }

    private fun loadFromPrefs() {
        runCatching {
            val rawJson = prefs?.getString(KEY_ALIAS_MAP_JSON, null) ?: return
            val json = JSONObject(rawJson)
            aliasMap.clear()
            val keys = json.keys()
            while (keys.hasNext()) {
                val k = keys.next()
                val v = json.optString(k, "").trim()
                if (k.isNotBlank() && v.isNotBlank()) {
                    aliasMap[k] = v
                }
            }
            _aliasesFlow.value = aliasMap.toMap()
        }
    }

    private fun saveToPrefs() {
        runCatching {
            val json = JSONObject()
            for ((k, v) in aliasMap) {
                if (k.isNotBlank() && v.isNotBlank()) {
                    json.put(k, v)
                }
            }
            prefs?.edit()?.putString(KEY_ALIAS_MAP_JSON, json.toString())?.apply()
            _aliasesFlow.value = aliasMap.toMap()
        }
    }

    /**
     * 결제처명 수정 시 호출:
     * - oldName -> newName 매핑 등록
     * - 원문(originalText)에서 파싱된 원본 가맹점명(rawOriginalName)이 있으면 함께 매핑 등록
     * - 이전에 X -> oldName으로 등록되어 있던 체인이 있으면 X -> newName으로 일괄 갱신
     */
    fun registerAlias(
        oldName: String,
        newName: String,
        rawOriginalName: String? = null
    ) {
        val target = newName.trim()
        if (target.isBlank()) return

        val cleanOld = oldName.trim()
        val normOld = normalizeMerchantKey(cleanOld)
        val cleanRaw = rawOriginalName?.trim().orEmpty()
        val normRaw = normalizeMerchantKey(cleanRaw)

        // 기존 체인(A -> oldName)을 모두 (A -> target)으로 연쇄 갱신
        for ((k, existingVal) in aliasMap.entries) {
            if (existingVal.equals(cleanOld, ignoreCase = true) ||
                normalizeMerchantKey(existingVal) == normOld
            ) {
                if (k.equals(target, ignoreCase = true)) {
                    aliasMap.remove(k)
                } else {
                    aliasMap[k] = target
                }
            }
        }

        if (cleanOld.isNotBlank() && !cleanOld.equals(target, ignoreCase = true)) {
            aliasMap[cleanOld] = target
            if (normOld.isNotBlank() && !normOld.equals(normalizeMerchantKey(target), ignoreCase = true)) {
                aliasMap[normOld] = target
            }
        }

        if (cleanRaw.isNotBlank() && !cleanRaw.equals(target, ignoreCase = true)) {
            aliasMap[cleanRaw] = target
            if (normRaw.isNotBlank() && !normRaw.equals(normalizeMerchantKey(target), ignoreCase = true)) {
                aliasMap[normRaw] = target
            }
        }

        saveToPrefs()
    }

    /**
     * 입력된 결제처명(merchantName)에 대해 사용자가 지정한 치환 결제명이 있으면 반환하고,
     * 없으면 원본 결제처명을 그대로 반환합니다.
     */
    fun resolveAlias(merchantName: String?): String {
        val raw = merchantName?.trim() ?: return ""
        if (raw.isBlank()) return ""

        // 1. 정확 일치 조회
        aliasMap[raw]?.let { return it }

        // 2. 공백/대소문자 정규화 키 조회
        val norm = normalizeMerchantKey(raw)
        if (norm.isNotBlank()) {
            aliasMap[norm]?.let { return it }
        }

        // 3. 대소문자 무시 탐색
        for ((k, v) in aliasMap) {
            if (k.equals(raw, ignoreCase = true) || normalizeMerchantKey(k) == norm) {
                return v
            }
        }

        return raw
    }

    /**
     * 해당 이름이 사용자에 의해 치환된 결과값(새 결제명)인지 확인합니다.
     */
    fun isKnownAliasTarget(name: String?): Boolean {
        val clean = name?.trim() ?: return false
        if (clean.isBlank()) return false
        val norm = normalizeMerchantKey(clean)
        return aliasMap.values.any {
            it.equals(clean, ignoreCase = true) || normalizeMerchantKey(it) == norm
        }
    }

    /**
     * 치환된 새 결제명(targetName)에 매핑되어 있는 원본 결제처명 목록을 반환합니다.
     */
    fun getOriginalNamesForTarget(targetName: String?): List<String> {
        val clean = targetName?.trim() ?: return emptyList()
        if (clean.isBlank()) return emptyList()
        val norm = normalizeMerchantKey(clean)
        return aliasMap.entries
            .filter { it.value.equals(clean, ignoreCase = true) || normalizeMerchantKey(it.value) == norm }
            .map { it.key }
    }

    /**
     * 두 결제처명이 동일 결제처(정확 일치 또는 공백 무시 정규화 일치)인지 판별합니다.
     */
    fun isSameMerchant(nameA: String?, nameB: String?): Boolean {
        val a = nameA?.trim().orEmpty()
        val b = nameB?.trim().orEmpty()
        if (a.isBlank() || b.isBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        return normalizeMerchantKey(a) == normalizeMerchantKey(b)
    }

    fun getAllAliases(): Map<String, String> = aliasMap.toMap()

    companion object {
        private const val PREFS_NAME = "merchant_alias_prefs"
        private const val KEY_ALIAS_MAP_JSON = "alias_map_json"

        fun normalizeMerchantKey(name: String): String {
            return name.trim()
                .replace(Regex("\\s+"), "")
                .uppercase()
        }
    }
}
