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

    private fun migrateLegacyUnprefixedKeys() {
        val legacyEntries = aliasMap.entries.filter { (k, _) ->
            !k.startsWith(PREFIX_IN) && !k.startsWith(PREFIX_OUT)
        }.map { it.key to it.value }

        if (legacyEntries.isEmpty()) return

        for ((legacyKey, legacyVal) in legacyEntries) {
            aliasMap.remove(legacyKey)
            val inKey = "$PREFIX_IN$legacyKey"
            val outKey = "$PREFIX_OUT$legacyKey"
            aliasMap.putIfAbsent(inKey, legacyVal)
            aliasMap.putIfAbsent(outKey, legacyVal)
        }
    }

    /**
     * 결제처명 수정 시 호출:
     * - 입금(isDeposit == true -> IN::)과 출금(isDeposit == false -> OUT::)을 구분하여 별칭 매핑 등록
     * - oldName -> newName 매핑 등록
     * - 원문(originalText)에서 파싱된 원본 가맹점명(rawOriginalName)이 있으면 함께 매핑 등록
     * - 동일 입금/출금 방향 내에서 이전에 X -> oldName으로 등록되어 있던 체인이 있으면 X -> newName으로 일괄 갱신
     */
    fun registerAlias(
        oldName: String,
        newName: String,
        rawOriginalName: String? = null,
        isDeposit: Boolean = false
    ) {
        val target = newName.trim()
        if (target.isBlank()) return

        migrateLegacyUnprefixedKeys()

        val prefix = directionPrefix(isDeposit)
        val cleanOld = oldName.trim()
        val baseOld = stripTxDirectionSuffix(cleanOld)
        val normOld = normalizeMerchantKey(cleanOld)
        val normBaseOld = normalizeMerchantKey(baseOld)

        val cleanRaw = rawOriginalName?.trim().orEmpty()
        val baseRaw = stripTxDirectionSuffix(cleanRaw)
        val normRaw = normalizeMerchantKey(cleanRaw)
        val normBaseRaw = normalizeMerchantKey(baseRaw)

        // 동일 방향(입금 또는 출금)의 기존 체인(A -> oldName)만 (A -> target)으로 연쇄 갱신
        for ((k, existingVal) in aliasMap.entries.toList()) {
            if (!k.startsWith(prefix)) continue
            val rawKeyPart = stripDirectionPrefix(k)
            if (existingVal.equals(cleanOld, ignoreCase = true) ||
                normalizeMerchantKey(existingVal) == normOld ||
                (normBaseOld.isNotBlank() && normalizeMerchantKey(existingVal) == normBaseOld)
            ) {
                if (rawKeyPart.equals(target, ignoreCase = true) ||
                    normalizeMerchantKey(rawKeyPart) == normalizeMerchantKey(target)
                ) {
                    aliasMap.remove(k)
                } else {
                    aliasMap[k] = target
                }
            }
        }

        fun putDirectionalPair(candidate: String) {
            val c = candidate.trim()
            if (c.isBlank()) return
            val n = normalizeMerchantKey(c)
            val normTarget = normalizeMerchantKey(target)
            if (!c.equals(target, ignoreCase = true)) {
                aliasMap["$prefix$c"] = target
            } else {
                aliasMap.remove("$prefix$c")
            }
            if (n.isNotBlank()) {
                if (!n.equals(normTarget, ignoreCase = true)) {
                    aliasMap["$prefix$n"] = target
                } else {
                    aliasMap.remove("$prefix$n")
                }
            }
        }

        putDirectionalPair(cleanOld)
        if (baseOld != cleanOld) putDirectionalPair(baseOld)
        if (cleanRaw.isNotBlank()) {
            putDirectionalPair(cleanRaw)
            if (baseRaw != cleanRaw) putDirectionalPair(baseRaw)
        }

        saveToPrefs()
    }

    /**
     * 입력된 결제처명(merchantName)과 입금/출금 구분(isDeposit)에 대해
     * 사용자가 지정한 치환 결제명이 있으면 반환하고, 없으면 원본 결제처명을 그대로 반환합니다.
     */
    fun resolveAlias(merchantName: String?, isDeposit: Boolean = false): String {
        val raw = merchantName?.trim() ?: return ""
        if (raw.isBlank()) return ""

        val prefix = directionPrefix(isDeposit)
        val baseRaw = stripTxDirectionSuffix(raw)
        val norm = normalizeMerchantKey(raw)
        val normBase = normalizeMerchantKey(baseRaw)

        // 1. 해당 입금/출금 방향의 정확 일치 조회
        aliasMap["$prefix$raw"]?.let { return it }
        if (baseRaw.isNotBlank() && baseRaw != raw) {
            aliasMap["$prefix$baseRaw"]?.let { return it }
        }

        // 2. 해당 입금/출금 방향의 공백/대소문자 정규화 키 조회
        if (norm.isNotBlank()) {
            aliasMap["$prefix$norm"]?.let { return it }
        }
        if (normBase.isNotBlank() && normBase != norm) {
            aliasMap["$prefix$normBase"]?.let { return it }
        }

        // 3. 해당 입금/출금 방향 내에서 대소문자 무시 탐색
        for ((k, v) in aliasMap) {
            if (!k.startsWith(prefix)) continue
            val keyBody = stripDirectionPrefix(k)
            val normKeyBody = normalizeMerchantKey(keyBody)
            if (keyBody.equals(raw, ignoreCase = true) ||
                normKeyBody == norm ||
                (normBase.isNotBlank() && normKeyBody == normBase)
            ) {
                return v
            }
        }

        // 4. 레거시 무접두사 키 폴백 (마이그레이션 전 데이터가 남아있는 경우)
        aliasMap[raw]?.let { return it }
        if (norm.isNotBlank()) {
            aliasMap[norm]?.let { return it }
        }

        return raw
    }

    /**
     * 해당 이름이 사용자에 의해 치환된 결과값(새 결제명)인지 확인합니다.
     * isDeposit이 지정되면 해당 입금/출금 방향에서 치환된 값인지 확인합니다.
     */
    fun isKnownAliasTarget(name: String?, isDeposit: Boolean? = null): Boolean {
        val clean = name?.trim() ?: return false
        if (clean.isBlank()) return false
        val norm = normalizeMerchantKey(clean)
        val prefix = isDeposit?.let { directionPrefix(it) }
        return aliasMap.entries.any { (k, v) ->
            val dirMatch = prefix == null ||
                k.startsWith(prefix) ||
                (!k.startsWith(PREFIX_IN) && !k.startsWith(PREFIX_OUT))
            dirMatch && (v.equals(clean, ignoreCase = true) || normalizeMerchantKey(v) == norm)
        }
    }

    /**
     * 치환된 새 결제명(targetName)에 매핑되어 있는 원본 결제처명 목록을 반환합니다.
     */
    fun getOriginalNamesForTarget(targetName: String?, isDeposit: Boolean? = null): List<String> {
        val clean = targetName?.trim() ?: return emptyList()
        if (clean.isBlank()) return emptyList()
        val norm = normalizeMerchantKey(clean)
        val prefix = isDeposit?.let { directionPrefix(it) }
        return aliasMap.entries
            .filter { (k, v) ->
                val dirMatch = prefix == null ||
                    k.startsWith(prefix) ||
                    (!k.startsWith(PREFIX_IN) && !k.startsWith(PREFIX_OUT))
                dirMatch && (v.equals(clean, ignoreCase = true) || normalizeMerchantKey(v) == norm)
            }
            .map { stripDirectionPrefix(it.key) }
            .distinct()
    }

    /**
     * 두 결제처명이 동일 결제처(정확 일치 또는 공백/입출금 접미사 무시 정규화 일치)인지 판별합니다.
     */
    fun isSameMerchant(nameA: String?, nameB: String?): Boolean {
        val a = nameA?.trim().orEmpty()
        val b = nameB?.trim().orEmpty()
        if (a.isBlank() || b.isBlank()) return false
        if (a.equals(b, ignoreCase = true)) return true
        val normA = normalizeMerchantKey(a)
        val normB = normalizeMerchantKey(b)
        if (normA == normB) return true
        val baseA = normalizeMerchantKey(stripTxDirectionSuffix(a))
        val baseB = normalizeMerchantKey(stripTxDirectionSuffix(b))
        return baseA.isNotBlank() && baseA == baseB
    }

    fun getAllAliases(): Map<String, String> = aliasMap.toMap()

    companion object {
        private const val PREFS_NAME = "merchant_alias_prefs"
        private const val KEY_ALIAS_MAP_JSON = "alias_map_json"
        const val PREFIX_IN = "IN::"
        const val PREFIX_OUT = "OUT::"

        fun directionPrefix(isDeposit: Boolean): String = if (isDeposit) PREFIX_IN else PREFIX_OUT

        fun stripDirectionPrefix(key: String): String {
            return key.removePrefix(PREFIX_IN).removePrefix(PREFIX_OUT)
        }

        fun stripTxDirectionSuffix(name: String): String {
            return name.trim().replace(Regex("""\s*\((?:출금|입금)\)\s*$"""), "").trim()
        }

        fun normalizeMerchantKey(name: String): String {
            return name.trim()
                .replace(Regex("\\s+"), "")
                .uppercase()
        }

        fun buildDirectionalKey(name: String, isDeposit: Boolean): String {
            val norm = normalizeMerchantKey(name)
            if (norm.isBlank()) return ""
            return "${directionPrefix(isDeposit)}$norm"
        }

        fun buildDirectionalBaseKey(name: String, isDeposit: Boolean): String {
            val norm = normalizeMerchantKey(stripTxDirectionSuffix(name))
            if (norm.isBlank()) return ""
            return "${directionPrefix(isDeposit)}$norm"
        }
    }
}
