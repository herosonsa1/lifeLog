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
 * 영구저장된 결제명 자동 치환 그룹 모델
 * - targetName: 치환되어 표시되는 새 결제명
 * - isDeposit: 입금(true) / 출금(false) 방향
 * - sourceNames: 이 결제명으로 자동 치환되는 원본 결제처명 목록
 */
data class MerchantAliasRuleGroup(
    val targetName: String,
    val isDeposit: Boolean,
    val sourceNames: List<String>
)

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
                    // 로드 시점에 정규화 키도 함께 인덱싱하여 resolveAlias O(1) 조회 보장
                    when {
                        k.startsWith(PREFIX_IN) -> {
                            val norm = normalizeMerchantKey(k.removePrefix(PREFIX_IN))
                            if (norm.isNotBlank()) aliasMap.putIfAbsent("$PREFIX_IN$norm", v)
                        }
                        k.startsWith(PREFIX_OUT) -> {
                            val norm = normalizeMerchantKey(k.removePrefix(PREFIX_OUT))
                            if (norm.isNotBlank()) aliasMap.putIfAbsent("$PREFIX_OUT$norm", v)
                        }
                        else -> {
                            val norm = normalizeMerchantKey(k)
                            if (norm.isNotBlank()) aliasMap.putIfAbsent(norm, v)
                        }
                    }
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

        // 입금(isDeposit == true)일 때 보험사 다중 채널 표기("한화정선우" <-> "한화손해보험", "DB정선우" <-> "DB손해보험" 등) 동시 매핑
        if (isDeposit) {
            val synonyms = getDepositInstitutionSynonyms(cleanOld, cleanRaw, target)
            for (syn in synonyms) {
                putDirectionalPair(syn)
            }
        }

        saveToPrefs()
    }

    /**
     * 입력된 결제처명(merchantName)과 입금/출금 구분(isDeposit)에 대해
     * 사용자가 지정한 치환 결제명이 있으면 반환하고, 없으면 원본 결제처명을 그대로 반환합니다. (O(1) 해시맵 조회)
     */
    fun resolveAlias(merchantName: String?, isDeposit: Boolean = false): String {
        val raw = merchantName?.trim() ?: return ""
        if (raw.isBlank() || aliasMap.isEmpty()) return raw

        val prefix = directionPrefix(isDeposit)
        // 1. 해당 입금/출금 방향의 정확 일치 조회
        aliasMap["$prefix$raw"]?.let { return it }

        val norm = normalizeMerchantKey(raw)
        if (norm.isNotBlank()) {
            aliasMap["$prefix$norm"]?.let { return it }
        }

        val baseRaw = stripTxDirectionSuffix(raw)
        if (baseRaw.isNotBlank() && baseRaw != raw) {
            aliasMap["$prefix$baseRaw"]?.let { return it }
            val normBase = normalizeMerchantKey(baseRaw)
            if (normBase.isNotBlank() && normBase != norm) {
                aliasMap["$prefix$normBase"]?.let { return it }
            }
        }

        // 입금(isDeposit == true)일 때 통장 축약 인자명("한화정선우", "DB정선우" 등)과 보험사 공식명 동의어 그룹 교차 조회
        if (isDeposit) {
            val synonyms = getDepositInstitutionSynonyms(raw, baseRaw)
            for (syn in synonyms) {
                val synNorm = normalizeMerchantKey(syn)
                aliasMap["$prefix$syn"]?.let { return it }
                if (synNorm.isNotBlank()) {
                    aliasMap["$prefix$synNorm"]?.let { return it }
                }
            }
        }

        // 레거시 무접두사 키 폴백
        aliasMap[raw]?.let { return it }
        if (norm.isNotBlank()) {
            aliasMap[norm]?.let { return it }
        }

        return raw
    }

    private fun getDepositInstitutionSynonyms(vararg names: String?): List<String> {
        val combined = names.filterNotNull().joinToString(" ").uppercase()
        if (combined.isBlank()) return emptyList()
        return when {
            combined.contains("한화손") || combined.contains("한화정선우") || combined.contains("한화실비") ->
                listOf("한화손해보험", "한화손보", "한화손해", "한화정선우", "한화정*우")
            combined.contains("DB손") || combined.contains("디비손") || combined.contains("DB정선우") ||
                combined.contains("디비정선우") || combined.contains("동부화재") ->
                listOf("DB손해보험", "DB손보", "디비손해보험", "디비손보", "DB정선우", "디비정선우", "DB정*우")
            combined.contains("삼성화재") || combined.contains("삼성정선우") ->
                listOf("삼성화재", "삼성화재해상보험", "삼성정선우")
            combined.contains("현대해상") || combined.contains("현대정선우") ->
                listOf("현대해상", "현대해상화재보험", "현대정선우")
            combined.contains("메리츠") || combined.contains("메리츠정선우") ->
                listOf("메리츠화재", "메리츠손해보험", "메리츠손보", "메리츠정선우")
            combined.contains("KB손") || combined.contains("KB정선우") ->
                listOf("KB손해보험", "KB손보", "KB정선우")
            else -> emptyList()
        }
    }

    /**
     * 해당 이름이 사용자에 의해 치환된 결과값(새 결제명)인지 확인합니다.
     * isDeposit이 지정되면 해당 입금/출금 방향에서 치환된 값인지 확인합니다.
     */
    fun isKnownAliasTarget(name: String?, isDeposit: Boolean? = null): Boolean {
        val clean = name?.trim() ?: return false
        if (clean.isBlank() || aliasMap.isEmpty()) return false
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
        if (clean.isBlank() || aliasMap.isEmpty()) return emptyList()
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

    /**
     * 영구저장된 결제명 자동 치환 규칙을 (입출금 구분 + 치환 결과 결제명) 단위로 묶어 반환합니다.
     */
    fun getAliasRuleGroups(): List<MerchantAliasRuleGroup> {
        if (aliasMap.isEmpty()) return emptyList()
        data class GroupKey(val isDeposit: Boolean, val normTarget: String)
        val grouped = LinkedHashMap<GroupKey, MutableList<Pair<String, String>>>()

        for ((rawKey, targetVal) in aliasMap.entries) {
            val cleanTarget = targetVal.trim()
            if (cleanTarget.isBlank()) continue
            val isDeposit = rawKey.startsWith(PREFIX_IN)
            val sourceRaw = stripDirectionPrefix(rawKey).trim()
            if (sourceRaw.isBlank()) continue
            val normSource = normalizeMerchantKey(sourceRaw)
            val normTarget = normalizeMerchantKey(cleanTarget)
            if (normSource.isBlank() || normSource == normTarget) continue

            val gKey = GroupKey(isDeposit = isDeposit, normTarget = normTarget)
            grouped.getOrPut(gKey) { mutableListOf() }.add(sourceRaw to cleanTarget)
        }

        return grouped.map { (gKey, pairs) ->
            val displayTarget = pairs.first().second
            // 정규화 키가 동일한 원본명 중 공백/원래 표기가 살아있는 이름을 우선 채택
            val dedupedSources = pairs
                .map { it.first }
                .groupBy { normalizeMerchantKey(it) }
                .map { (_, candidates) ->
                    candidates.maxByOrNull { cand ->
                        cand.count { it.isWhitespace() } * 10 + cand.length
                    } ?: candidates.first()
                }
                .distinct()
                .sorted()
            MerchantAliasRuleGroup(
                targetName = displayTarget,
                isDeposit = gKey.isDeposit,
                sourceNames = dedupedSources
            )
        }.sortedWith(compareBy<MerchantAliasRuleGroup> { it.isDeposit }.thenBy { it.targetName })
    }

    /**
     * 특정 결제처(원본명 또는 치환된 새 결제명)와 관련된 자동 치환 규칙을 해제(삭제)합니다.
     * 반환값: 해제된 규칙에 포함되어 있던 모든 원본/대상 결제처명 목록 (기존 내역 원본 복원용)
     */
    fun removeAliasByMerchant(merchantOrSourceName: String, isDeposit: Boolean? = null): List<String> {
        val clean = merchantOrSourceName.trim()
        if (clean.isBlank() || aliasMap.isEmpty()) return emptyList()

        val norm = normalizeMerchantKey(clean)
        val normBase = normalizeMerchantKey(stripTxDirectionSuffix(clean))
        val targetNormSet = setOfNotNull(
            norm.takeIf { it.isNotBlank() },
            normBase.takeIf { it.isNotBlank() }
        )
        if (targetNormSet.isEmpty()) return emptyList()

        val prefix = isDeposit?.let { directionPrefix(it) }
        val affectedNames = LinkedHashSet<String>()
        affectedNames.add(clean)

        val keysToRemove = mutableListOf<String>()
        for ((k, v) in aliasMap.entries) {
            val dirMatch = prefix == null ||
                k.startsWith(prefix) ||
                (!k.startsWith(PREFIX_IN) && !k.startsWith(PREFIX_OUT))
            if (!dirMatch) continue

            val rawSource = stripDirectionPrefix(k).trim()
            val normSource = normalizeMerchantKey(rawSource)
            val normBaseSource = normalizeMerchantKey(stripTxDirectionSuffix(rawSource))
            val normVal = normalizeMerchantKey(v)
            val normBaseVal = normalizeMerchantKey(stripTxDirectionSuffix(v))

            if (normSource in targetNormSet ||
                normBaseSource in targetNormSet ||
                normVal in targetNormSet ||
                normBaseVal in targetNormSet
            ) {
                keysToRemove.add(k)
                if (rawSource.isNotBlank()) affectedNames.add(rawSource)
                if (v.isNotBlank()) affectedNames.add(v.trim())
            }
        }

        if (keysToRemove.isNotEmpty()) {
            keysToRemove.forEach { aliasMap.remove(it) }
            saveToPrefs()
        }
        return affectedNames.toList()
    }

    /**
     * 등록된 모든 결제명 자동 치환 영구 규칙을 일괄 초기화합니다.
     */
    fun clearAllAliases() {
        if (aliasMap.isEmpty()) return
        aliasMap.clear()
        saveToPrefs()
    }

    companion object {
        private const val PREFS_NAME = "merchant_alias_prefs"
        private const val KEY_ALIAS_MAP_JSON = "alias_map_json"
        const val PREFIX_IN = "IN::"
        const val PREFIX_OUT = "OUT::"

        private val DIRECTION_SUFFIX_REGEX = Regex("""\s*\((?:출금|입금)\)\s*$""")

        fun directionPrefix(isDeposit: Boolean): String = if (isDeposit) PREFIX_IN else PREFIX_OUT

        fun stripDirectionPrefix(key: String): String {
            return key.removePrefix(PREFIX_IN).removePrefix(PREFIX_OUT)
        }

        fun stripTxDirectionSuffix(name: String): String {
            val trimmed = name.trim()
            if (!trimmed.endsWith(")")) return trimmed
            return trimmed.replace(DIRECTION_SUFFIX_REGEX, "").trim()
        }

        fun normalizeMerchantKey(name: String): String {
            if (name.isEmpty()) return ""
            val sb = StringBuilder(name.length)
            for (i in 0 until name.length) {
                val c = name[i]
                if (!c.isWhitespace()) {
                    sb.append(c.uppercaseChar())
                }
            }
            return sb.toString()
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
