package com.autologue.app.data.preferences

import android.content.Context
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
import com.autologue.app.domain.model.isBankAccountTransaction
import com.autologue.app.domain.model.isDepositTransaction
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalDate
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 사용자 분류(배지 색상 포함) 모델
 */
data class UserClassificationItem(
    val name: String,
    val colorIndex: Int
)

/**
 * 등록된 카테고리 통합 관리 항목 모델
 * - isBuiltIn == true: 기본 ExpenseCategory 항목 (이름 수정 가능, 숨김/삭제 가능)
 * - isBuiltIn == false: 사용자가 추가한 영구 커스텀 카테고리 항목 (이름 수정 및 삭제 가능)
 */
data class ManagedCategoryItem(
    val id: String,
    val displayName: String,
    val baseCategory: ExpenseCategory,
    val isBuiltIn: Boolean
)

/**
 * 가계부·차계부 확장 메타데이터 및 영속 설정 저장소:
 * 1) 카드사/은행명 자동 힌트 추출 및 법인/개인 여부 지정 관리 (입금/출금 구분 포함)
 * 2) 내역 수정 시 1회성 저장 보호(One-Time Override) 관리
 * 3) 커스텀 카테고리 영구 추가 및 전체 카테고리(추가/수정/삭제) 관리 (입금/출금 구분 포함)
 * 4) 카테고리와 독립된 '사용자 분류'(직접 입력, 추가/수정/삭제, 배지 색상) 관리 (입금/출금 구분 포함)
 * 5) 고정지출(정기지출) 수동/영구 지정 관리
 * 6) 자동 등록된 결제기록·주행기록 선택 삭제 후 재동기화 부활 방지 블랙리스트(Tombstone) 관리
 */
@Singleton
class ExpenseCustomMetaPreferences private constructor(
    private val prefs: android.content.SharedPreferences?
) {
    @Inject
    constructor(@ApplicationContext context: Context) : this(
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    )

    // 단위 테스트용 인메모리 생성자
    constructor() : this(null)

    // 1. 커스텀 카테고리 목록 및 기본 카테고리 이름 변경/삭제 상태
    private val customCategoryList = mutableListOf<String>()
    private val renamedDefaultCategories = ConcurrentHashMap<String, String>()
    private val deletedDefaultCategories = ConcurrentHashMap.newKeySet<String>()

    // 거래 ID별 / (입출금구분+가맹점)별 기본 및 세부(커스텀) 카테고리 매핑
    private val txCustomCategoryMap = ConcurrentHashMap<Long, String>()
    private val merchantCustomCategoryMap = ConcurrentHashMap<String, String>()
    private val merchantBaseCategoryMap = ConcurrentHashMap<String, String>()

    // 2. 사용자 분류 목록 및 거래 ID별 / (입출금구분+가맹점)별 사용자 분류 매핑
    private val userClassificationList = mutableListOf<UserClassificationItem>()
    private val txUserClassMap = ConcurrentHashMap<Long, String>()
    private val merchantUserClassMap = ConcurrentHashMap<String, String>()

    // 3. 법인카드 여부 및 (입출금구분+가맹점)별 카드/은행 지정 매핑
    private val txCorporateMap = ConcurrentHashMap<Long, Boolean>()
    private val merchantCardDesignationMap = ConcurrentHashMap<String, String>()

    // 4. 고정지출(정기지출) 지정 상태 (거래 ID별 및 가맹점 영구 규칙별)
    private val txFixedExpenseMap = ConcurrentHashMap<Long, Boolean>()
    private val merchantFixedExpenseMap = ConcurrentHashMap<String, String>()

    // 5. 1회성 수정 보호(One-Time Save) 거래 ID 및 서명 집합
    private val oneTimeEditedTxIds = ConcurrentHashMap.newKeySet<Long>()
    private val oneTimeEditedSignatures = ConcurrentHashMap.newKeySet<String>()

    // 6. 선택 삭제된 결제기록 / 주행기록 재등록 방지 블랙리스트(Tombstone)
    private val deletedTxSignatures = ConcurrentHashMap.newKeySet<String>()
    private val deletedVehicleLogSignatures = ConcurrentHashMap.newKeySet<String>()

    private val _managedCategoriesFlow = MutableStateFlow<List<ManagedCategoryItem>>(emptyList())
    val managedCategoriesFlow: StateFlow<List<ManagedCategoryItem>> = _managedCategoriesFlow.asStateFlow()

    private val _userClassificationsFlow = MutableStateFlow<List<UserClassificationItem>>(emptyList())
    val userClassificationsFlow: StateFlow<List<UserClassificationItem>> = _userClassificationsFlow.asStateFlow()

    private val _metaVersionFlow = MutableStateFlow(0L)
    val metaVersionFlow: StateFlow<Long> = _metaVersionFlow.asStateFlow()

    init {
        loadFromPrefs()
        refreshFlows()
    }

    private fun putDirectionalRule(
        map: ConcurrentHashMap<String, String>,
        merchantName: String,
        isDeposit: Boolean,
        value: String,
        vararg extraMerchantNames: String?
    ) {
        val candidates = listOfNotNull(merchantName, *extraMerchantNames)
        for (name in candidates) {
            val key = MerchantAliasPreferences.buildDirectionalKey(name, isDeposit)
            val baseKey = MerchantAliasPreferences.buildDirectionalBaseKey(name, isDeposit)
            if (key.isNotBlank()) map[key] = value
            if (baseKey.isNotBlank()) map[baseKey] = value
        }
    }

    private fun removeDirectionalRule(
        map: ConcurrentHashMap<String, String>,
        merchantName: String,
        isDeposit: Boolean,
        vararg extraMerchantNames: String?
    ) {
        val candidates = listOfNotNull(merchantName, *extraMerchantNames)
        for (name in candidates) {
            val key = MerchantAliasPreferences.buildDirectionalKey(name, isDeposit)
            val baseKey = MerchantAliasPreferences.buildDirectionalBaseKey(name, isDeposit)
            if (key.isNotBlank()) map.remove(key)
            if (baseKey.isNotBlank()) map.remove(baseKey)
        }
    }

    private fun lookupDirectionalRule(
        map: ConcurrentHashMap<String, String>,
        merchantName: String?,
        isDeposit: Boolean
    ): String? {
        val raw = merchantName?.trim().orEmpty()
        if (raw.isBlank()) return null
        val key = MerchantAliasPreferences.buildDirectionalKey(raw, isDeposit)
        if (key.isNotBlank()) {
            map[key]?.let { return it }
        }
        val baseKey = MerchantAliasPreferences.buildDirectionalBaseKey(raw, isDeposit)
        if (baseKey.isNotBlank() && baseKey != key) {
            map[baseKey]?.let { return it }
        }
        // 레거시 무접두사 키 폴백 (단, 반대 방향 전용 키가 없을 때만 허용)
        val norm = MerchantAliasPreferences.normalizeMerchantKey(raw)
        if (norm.isNotBlank()) {
            map[norm]?.let { return it }
        }
        return null
    }

    @Synchronized
    private fun loadFromPrefs() {
        runCatching {
            val raw = prefs?.getString(KEY_META_JSON, null)
            if (raw.isNullOrBlank()) {
                // 초기 기본 사용자 분류 세팅
                if (userClassificationList.isEmpty()) {
                    userClassificationList.add(UserClassificationItem("본인", 0))
                    userClassificationList.add(UserClassificationItem("가족", 1))
                    userClassificationList.add(UserClassificationItem("업무/법인", 2))
                }
                return
            }
            val root = JSONObject(raw)

            customCategoryList.clear()
            root.optJSONArray("customCategories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotBlank() && item !in customCategoryList) {
                        customCategoryList.add(item)
                    }
                }
            }

            renamedDefaultCategories.clear()
            root.optJSONObject("renamedDefaultCategories")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) {
                        renamedDefaultCategories[k] = v
                    }
                }
            }

            deletedDefaultCategories.clear()
            root.optJSONArray("deletedDefaultCategories")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val item = arr.optString(i).trim()
                    if (item.isNotBlank()) deletedDefaultCategories.add(item)
                }
            }

            txCustomCategoryMap.clear()
            root.optJSONObject("txCustomCategoryMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toLongOrNull() ?: continue
                    val v = obj.optString(k).trim()
                    if (v.isNotBlank()) txCustomCategoryMap[id] = v
                }
            }

            merchantCustomCategoryMap.clear()
            root.optJSONObject("merchantCustomCategoryMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) merchantCustomCategoryMap[k] = v
                }
            }

            merchantBaseCategoryMap.clear()
            root.optJSONObject("merchantBaseCategoryMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) merchantBaseCategoryMap[k] = v
                }
            }

            userClassificationList.clear()
            val ucArr = root.optJSONArray("userClassifications")
            if (ucArr != null) {
                for (i in 0 until ucArr.length()) {
                    val o = ucArr.optJSONObject(i) ?: continue
                    val name = o.optString("name").trim()
                    val colorIndex = o.optInt("colorIndex", i % BADGE_COLOR_COUNT)
                    if (name.isNotBlank() && userClassificationList.none { it.name.equals(name, ignoreCase = true) }) {
                        userClassificationList.add(UserClassificationItem(name, colorIndex))
                    }
                }
            } else {
                userClassificationList.add(UserClassificationItem("본인", 0))
                userClassificationList.add(UserClassificationItem("가족", 1))
                userClassificationList.add(UserClassificationItem("업무/법인", 2))
            }

            txUserClassMap.clear()
            root.optJSONObject("txUserClassMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toLongOrNull() ?: continue
                    val v = obj.optString(k).trim()
                    if (v.isNotBlank()) txUserClassMap[id] = v
                }
            }

            merchantUserClassMap.clear()
            root.optJSONObject("merchantUserClassMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) merchantUserClassMap[k] = v
                }
            }

            txCorporateMap.clear()
            root.optJSONObject("txCorporateMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toLongOrNull() ?: continue
                    txCorporateMap[id] = obj.optBoolean(k, false)
                }
            }

            merchantCardDesignationMap.clear()
            root.optJSONObject("merchantCardDesignationMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) merchantCardDesignationMap[k] = v
                }
            }

            txFixedExpenseMap.clear()
            root.optJSONObject("txFixedExpenseMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val id = k.toLongOrNull() ?: continue
                    txFixedExpenseMap[id] = obj.optBoolean(k, false)
                }
            }

            merchantFixedExpenseMap.clear()
            root.optJSONObject("merchantFixedExpenseMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next()
                    val v = obj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) merchantFixedExpenseMap[k] = v
                }
            }

            oneTimeEditedTxIds.clear()
            root.optJSONArray("oneTimeEditedTxIds")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val id = arr.optLong(i, -1L)
                    if (id > 0L) oneTimeEditedTxIds.add(id)
                }
            }

            oneTimeEditedSignatures.clear()
            root.optJSONArray("oneTimeEditedSignatures")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sig = arr.optString(i).trim()
                    if (sig.isNotBlank()) oneTimeEditedSignatures.add(sig)
                }
            }

            deletedTxSignatures.clear()
            root.optJSONArray("deletedTxSignatures")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sig = arr.optString(i).trim()
                    if (sig.isNotBlank()) deletedTxSignatures.add(sig)
                }
            }

            deletedVehicleLogSignatures.clear()
            root.optJSONArray("deletedVehicleLogSignatures")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val sig = arr.optString(i).trim()
                    if (sig.isNotBlank()) deletedVehicleLogSignatures.add(sig)
                }
            }
        }
    }

    @Synchronized
    private fun saveToPrefs() {
        runCatching {
            val root = JSONObject()

            val customCatArr = JSONArray()
            customCategoryList.forEach { customCatArr.put(it) }
            root.put("customCategories", customCatArr)

            val renamedObj = JSONObject()
            renamedDefaultCategories.forEach { (k, v) -> renamedObj.put(k, v) }
            root.put("renamedDefaultCategories", renamedObj)

            val deletedDefArr = JSONArray()
            deletedDefaultCategories.forEach { deletedDefArr.put(it) }
            root.put("deletedDefaultCategories", deletedDefArr)

            val txCatObj = JSONObject()
            txCustomCategoryMap.forEach { (k, v) -> txCatObj.put(k.toString(), v) }
            root.put("txCustomCategoryMap", txCatObj)

            val merCatObj = JSONObject()
            merchantCustomCategoryMap.forEach { (k, v) -> merCatObj.put(k, v) }
            root.put("merchantCustomCategoryMap", merCatObj)

            val merBaseCatObj = JSONObject()
            merchantBaseCategoryMap.forEach { (k, v) -> merBaseCatObj.put(k, v) }
            root.put("merchantBaseCategoryMap", merBaseCatObj)

            val ucArr = JSONArray()
            userClassificationList.forEach { item ->
                val o = JSONObject()
                o.put("name", item.name)
                o.put("colorIndex", item.colorIndex)
                ucArr.put(o)
            }
            root.put("userClassifications", ucArr)

            val txUcObj = JSONObject()
            txUserClassMap.forEach { (k, v) -> txUcObj.put(k.toString(), v) }
            root.put("txUserClassMap", txUcObj)

            val merUcObj = JSONObject()
            merchantUserClassMap.forEach { (k, v) -> merUcObj.put(k, v) }
            root.put("merchantUserClassMap", merUcObj)

            val txCorpObj = JSONObject()
            txCorporateMap.forEach { (k, v) -> txCorpObj.put(k.toString(), v) }
            root.put("txCorporateMap", txCorpObj)

            val merCardObj = JSONObject()
            merchantCardDesignationMap.forEach { (k, v) -> merCardObj.put(k, v) }
            root.put("merchantCardDesignationMap", merCardObj)

            val txFixedObj = JSONObject()
            txFixedExpenseMap.forEach { (k, v) -> txFixedObj.put(k.toString(), v) }
            root.put("txFixedExpenseMap", txFixedObj)

            val merFixedObj = JSONObject()
            merchantFixedExpenseMap.forEach { (k, v) -> merFixedObj.put(k, v) }
            root.put("merchantFixedExpenseMap", merFixedObj)

            val oneTimeIdsArr = JSONArray()
            oneTimeEditedTxIds.forEach { oneTimeIdsArr.put(it) }
            root.put("oneTimeEditedTxIds", oneTimeIdsArr)

            val oneTimeSigsArr = JSONArray()
            oneTimeEditedSignatures.forEach { oneTimeSigsArr.put(it) }
            root.put("oneTimeEditedSignatures", oneTimeSigsArr)

            val delTxArr = JSONArray()
            deletedTxSignatures.forEach { delTxArr.put(it) }
            root.put("deletedTxSignatures", delTxArr)

            val delVehArr = JSONArray()
            deletedVehicleLogSignatures.forEach { delVehArr.put(it) }
            root.put("deletedVehicleLogSignatures", delVehArr)

            prefs?.edit()?.putString(KEY_META_JSON, root.toString())?.apply()
        }
        refreshFlows()
    }

    private fun refreshFlows() {
        val managed = mutableListOf<ManagedCategoryItem>()
        for (cat in ExpenseCategory.values()) {
            if (cat.name in deletedDefaultCategories && cat != ExpenseCategory.ETC) continue
            val label = renamedDefaultCategories[cat.name] ?: cat.displayName
            managed.add(
                ManagedCategoryItem(
                    id = "BUILTIN_${cat.name}",
                    displayName = label,
                    baseCategory = cat,
                    isBuiltIn = true
                )
            )
        }
        synchronized(this) {
            for (customName in customCategoryList) {
                managed.add(
                    ManagedCategoryItem(
                        id = "CUSTOM_$customName",
                        displayName = customName,
                        baseCategory = ExpenseCategory.ETC,
                        isBuiltIn = false
                    )
                )
            }
            _userClassificationsFlow.value = userClassificationList.toList()
        }
        _managedCategoriesFlow.value = managed
        _metaVersionFlow.value = _metaVersionFlow.value + 1L
    }

    // =========================================================================
    // 1. 카드사 / 은행명 힌트 자동 추출 및 지정 (입금/출금 방향 구분 포함)
    // =========================================================================

    fun formatCardDesignation(
        cardOrBankName: String,
        isCorporate: Boolean,
        isBankAccountTx: Boolean = false
    ): String {
        if (isBankAccountTx) {
            return extractBaseBankName(cardOrBankName).ifBlank { "NH농협은행" }
        }
        val cleanBase = extractBaseCardName(cardOrBankName).ifBlank {
            if (isCorporate) "법인카드" else "신한카드"
        }
        return if (isCorporate) {
            if (cleanBase.contains("법인")) cleanBase else "$cleanBase(법인)"
        } else {
            cleanBase
        }
    }

    /**
     * 카드 힌트 문자열(예: "하나0*2*", "NH농협", "우리(1234)", "KB국민체크(8*4*)" 등)에서
     * 표준 카드회사 이름("하나카드", "NH농협카드", "우리카드", "KB국민카드" 등)을 자동 추출합니다.
     */
    fun extractBaseCardName(cardOrBankName: String?, fallbackOriginalText: String? = null): String {
        return inferCanonicalCardCompany(cardOrBankName, fallbackOriginalText)
    }

    /**
     * 출금/이체/입금 계좌 내역의 힌트 문자열(예: "NH농협", "312-...", "KB국민", "하나", "우리" 등)에서
     * 표준 은행명("NH농협은행", "KB국민은행", "하나은행", "우리은행" 등)을 자동 추출합니다.
     */
    fun extractBaseBankName(
        cardOrBankName: String?,
        fallbackOriginalText: String? = null,
        fallbackMemo: String? = null
    ): String {
        return inferCanonicalBankName(cardOrBankName, fallbackOriginalText, fallbackMemo)
    }

    /**
     * 거래 내역의 성격(카드 결제 vs 출금/이체/입금 계좌 거래) 및 영구저장 규칙에 맞춰
     * 화면에 표시할 최종 카드사명 또는 은행명을 반환합니다.
     */
    fun resolveDisplayCardOrBankName(tx: Transaction): String {
        val isBankTx = tx.isBankAccountTransaction()
        if (!isOneTimeEdited(tx)) {
            val savedRule = lookupDirectionalRule(
                merchantCardDesignationMap,
                tx.merchantName,
                tx.isDepositTransaction()
            )
            if (!savedRule.isNullOrBlank()) {
                return if (isBankTx) {
                    inferCanonicalBankName(savedRule, tx.originalText, tx.transferMemo)
                } else {
                    val isCorp = isCorporateTransaction(tx) || savedRule.contains("법인")
                    formatCardDesignation(savedRule, isCorp, isBankAccountTx = false)
                }
            }
        }
        return if (isBankTx) {
            inferCanonicalBankName(tx.cardOrBankName, tx.originalText, tx.transferMemo)
        } else {
            val isCorp = isCorporateTransaction(tx)
            val canonicalCard = inferCanonicalCardCompany(tx.cardOrBankName, tx.originalText)
            formatCardDesignation(canonicalCard, isCorp, isBankAccountTx = false)
        }
    }

    fun isCorporateTransaction(tx: Transaction): Boolean {
        if (tx.isBankAccountTransaction()) return false
        txCorporateMap[tx.id]?.let { return it }
        if (tx.cardOrBankName.contains("법인")) return true
        if (!isOneTimeEdited(tx)) {
            val savedCard = lookupDirectionalRule(
                merchantCardDesignationMap,
                tx.merchantName,
                tx.isDepositTransaction()
            )
            if (savedCard != null && savedCard.contains("법인")) return true
        }
        return false
    }

    fun setTransactionCardDesignation(
        txId: Long,
        merchantName: String,
        cardCompany: String,
        isCorporate: Boolean,
        applyToMerchantRule: Boolean,
        isDeposit: Boolean = false,
        vararg extraMerchantNames: String?,
        isBankAccountTx: Boolean = false
    ): String {
        val effectiveCorp = if (isBankAccountTx) false else isCorporate
        val formatted = formatCardDesignation(cardCompany, effectiveCorp, isBankAccountTx = isBankAccountTx)
        if (txId > 0L) {
            txCorporateMap[txId] = effectiveCorp
        }
        if (applyToMerchantRule && merchantName.isNotBlank()) {
            putDirectionalRule(
                merchantCardDesignationMap,
                merchantName,
                isDeposit,
                formatted,
                *extraMerchantNames
            )
        }
        saveToPrefs()
        return formatted
    }

    fun resolveMerchantCardDesignation(merchantName: String?, isDeposit: Boolean = false): String? {
        return lookupDirectionalRule(merchantCardDesignationMap, merchantName, isDeposit)
    }

    // =========================================================================
    // 1-2. 고정지출(정기지출) 수동 및 영구 지정
    // =========================================================================

    fun setFixedExpense(
        txId: Long,
        merchantName: String,
        isFixed: Boolean,
        applyToMerchantRule: Boolean,
        isDeposit: Boolean = false,
        vararg extraMerchantNames: String?
    ) {
        if (txId > 0L) {
            txFixedExpenseMap[txId] = isFixed
        }
        if (applyToMerchantRule && merchantName.isNotBlank()) {
            putDirectionalRule(
                merchantFixedExpenseMap,
                merchantName,
                isDeposit,
                if (isFixed) "true" else "false",
                *extraMerchantNames
            )
        }
        saveToPrefs()
    }

    fun resolveIsFixedExpense(tx: Transaction, autoRecurringIds: Set<Long> = emptySet()): Boolean {
        if (tx.id > 0L && txFixedExpenseMap.containsKey(tx.id)) {
            return txFixedExpenseMap[tx.id] == true
        }
        if (!isOneTimeEdited(tx)) {
            lookupDirectionalRule(
                merchantFixedExpenseMap,
                tx.merchantName,
                tx.isDepositTransaction()
            )?.let { return it == "true" }
        }
        return tx.id in autoRecurringIds
    }

    // =========================================================================
    // 2. 1회성 저장 보호 (One-Time Save Override)
    // =========================================================================

    private fun buildTxSignature(tx: Transaction): String {
        val cleanOrig = tx.originalText.trim()
        return if (cleanOrig.isNotBlank()) {
            "ORIG_${cleanOrig.hashCode()}"
        } else {
            "TIME_${tx.timestamp.withSecond(0).withNano(0)}_${tx.amount}"
        }
    }

    fun markOneTimeEdited(tx: Transaction) {
        if (tx.id > 0L) {
            oneTimeEditedTxIds.add(tx.id)
        }
        oneTimeEditedSignatures.add(buildTxSignature(tx))
        saveToPrefs()
    }

    fun clearOneTimeEdited(tx: Transaction) {
        if (tx.id > 0L) {
            oneTimeEditedTxIds.remove(tx.id)
        }
        oneTimeEditedSignatures.remove(buildTxSignature(tx))
        saveToPrefs()
    }

    fun isOneTimeEdited(tx: Transaction): Boolean {
        if (tx.id > 0L && tx.id in oneTimeEditedTxIds) return true
        return buildTxSignature(tx) in oneTimeEditedSignatures
    }

    fun isOneTimeEditedId(txId: Long): Boolean {
        return txId > 0L && txId in oneTimeEditedTxIds
    }

    // =========================================================================
    // 3. 카테고리 관리 (커스텀 카테고리 영구 추가 / 수정 / 삭제 + 기본 카테고리 수정/삭제)
    // =========================================================================

    @Synchronized
    fun addCustomCategory(categoryName: String): Boolean {
        val clean = categoryName.trim()
        if (clean.isBlank()) return false
        // 만약 숨겨진 기본 카테고리 이름과 일치하면 복원
        val matchedBuiltIn = ExpenseCategory.values().firstOrNull {
            it.displayName.equals(clean, ignoreCase = true) ||
                (renamedDefaultCategories[it.name]?.equals(clean, ignoreCase = true) == true)
        }
        if (matchedBuiltIn != null) {
            deletedDefaultCategories.remove(matchedBuiltIn.name)
            saveToPrefs()
            return true
        }
        if (customCategoryList.any { it.equals(clean, ignoreCase = true) }) {
            return false
        }
        customCategoryList.add(clean)
        saveToPrefs()
        return true
    }

    @Synchronized
    fun updateCategoryName(item: ManagedCategoryItem, newName: String): Boolean {
        val cleanNew = newName.trim()
        if (cleanNew.isBlank()) return false
        if (item.isBuiltIn) {
            renamedDefaultCategories[item.baseCategory.name] = cleanNew
        } else {
            val oldName = item.displayName
            val idx = customCategoryList.indexOfFirst { it.equals(oldName, ignoreCase = true) }
            if (idx >= 0) {
                customCategoryList[idx] = cleanNew
            }
            for ((txId, catName) in txCustomCategoryMap.entries) {
                if (catName.equals(oldName, ignoreCase = true)) {
                    txCustomCategoryMap[txId] = cleanNew
                }
            }
            for ((merKey, catName) in merchantCustomCategoryMap.entries) {
                if (catName.equals(oldName, ignoreCase = true)) {
                    merchantCustomCategoryMap[merKey] = cleanNew
                }
            }
        }
        saveToPrefs()
        return true
    }

    @Synchronized
    fun deleteCategory(item: ManagedCategoryItem): Boolean {
        if (item.isBuiltIn) {
            if (item.baseCategory == ExpenseCategory.ETC) return false // 기타는 최소 기본 폴백으로 유지
            deletedDefaultCategories.add(item.baseCategory.name)
            renamedDefaultCategories.remove(item.baseCategory.name)
        } else {
            val target = item.displayName
            customCategoryList.removeAll { it.equals(target, ignoreCase = true) }
            txCustomCategoryMap.entries.removeIf { it.value.equals(target, ignoreCase = true) }
            merchantCustomCategoryMap.entries.removeIf { it.value.equals(target, ignoreCase = true) }
        }
        saveToPrefs()
        return true
    }

    @Synchronized
    fun restoreDefaultCategories() {
        deletedDefaultCategories.clear()
        renamedDefaultCategories.clear()
        saveToPrefs()
    }

    fun assignBaseCategory(
        merchantName: String,
        category: ExpenseCategory,
        applyToMerchantRule: Boolean,
        isDeposit: Boolean = false,
        vararg extraMerchantNames: String?
    ) {
        if (applyToMerchantRule && merchantName.isNotBlank()) {
            putDirectionalRule(
                merchantBaseCategoryMap,
                merchantName,
                isDeposit,
                category.name,
                *extraMerchantNames
            )
            saveToPrefs()
        }
    }

    fun resolveMerchantBaseCategory(merchantName: String?, isDeposit: Boolean = false): ExpenseCategory? {
        val catName = lookupDirectionalRule(merchantBaseCategoryMap, merchantName, isDeposit) ?: return null
        return runCatching { ExpenseCategory.valueOf(catName) }.getOrNull()
    }

    fun assignCustomCategory(
        txId: Long,
        merchantName: String,
        customCategoryName: String?,
        applyToMerchantRule: Boolean,
        isDeposit: Boolean = false,
        vararg extraMerchantNames: String?
    ) {
        val cleanCat = customCategoryName?.trim().orEmpty()
        if (cleanCat.isNotBlank()) {
            addCustomCategory(cleanCat)
            if (txId > 0L) {
                txCustomCategoryMap[txId] = cleanCat
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                putDirectionalRule(
                    merchantCustomCategoryMap,
                    merchantName,
                    isDeposit,
                    cleanCat,
                    *extraMerchantNames
                )
            }
        } else {
            if (txId > 0L) {
                txCustomCategoryMap.remove(txId)
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                removeDirectionalRule(
                    merchantCustomCategoryMap,
                    merchantName,
                    isDeposit,
                    *extraMerchantNames
                )
            }
        }
        saveToPrefs()
    }

    fun resolveCustomCategoryLabel(tx: Transaction): String? {
        if (tx.id > 0L) {
            txCustomCategoryMap[tx.id]?.let { return it }
        }
        if (!isOneTimeEdited(tx)) {
            lookupDirectionalRule(
                merchantCustomCategoryMap,
                tx.merchantName,
                tx.isDepositTransaction()
            )?.let { return it }
        }
        return null
    }

    fun resolveEffectiveCategoryDisplayName(tx: Transaction): String {
        resolveCustomCategoryLabel(tx)?.let { return it }
        return getBuiltInCategoryDisplayName(tx.category)
    }

    fun getBuiltInCategoryDisplayName(category: ExpenseCategory): String {
        return renamedDefaultCategories[category.name] ?: category.displayName
    }

    @Synchronized
    fun getCustomCategories(): List<String> = customCategoryList.toList()

    // =========================================================================
    // 4. 사용자 분류 관리 (직접입력, 추가 / 수정 / 삭제, 배지 색상) - 입금/출금 구분 포함
    // =========================================================================

    @Synchronized
    fun addOrGetUserClassification(name: String, preferredColorIndex: Int? = null): UserClassificationItem? {
        val clean = name.trim()
        if (clean.isBlank()) return null
        val existing = userClassificationList.firstOrNull { it.name.equals(clean, ignoreCase = true) }
        if (existing != null) return existing

        val nextColor = preferredColorIndex ?: (userClassificationList.size % BADGE_COLOR_COUNT)
        val newItem = UserClassificationItem(clean, nextColor)
        userClassificationList.add(newItem)
        saveToPrefs()
        return newItem
    }

    @Synchronized
    fun updateUserClassification(oldName: String, newName: String, newColorIndex: Int): Boolean {
        val cleanOld = oldName.trim()
        val cleanNew = newName.trim()
        if (cleanOld.isBlank() || cleanNew.isBlank()) return false

        val idx = userClassificationList.indexOfFirst { it.name.equals(cleanOld, ignoreCase = true) }
        if (idx < 0) return false

        userClassificationList[idx] = UserClassificationItem(cleanNew, newColorIndex.coerceIn(0, BADGE_COLOR_COUNT - 1))

        for ((txId, uName) in txUserClassMap.entries) {
            if (uName.equals(cleanOld, ignoreCase = true)) {
                txUserClassMap[txId] = cleanNew
            }
        }
        for ((merKey, uName) in merchantUserClassMap.entries) {
            if (uName.equals(cleanOld, ignoreCase = true)) {
                merchantUserClassMap[merKey] = cleanNew
            }
        }
        saveToPrefs()
        return true
    }

    @Synchronized
    fun deleteUserClassification(name: String): Boolean {
        val clean = name.trim()
        if (clean.isBlank()) return false
        val removed = userClassificationList.removeAll { it.name.equals(clean, ignoreCase = true) }
        txUserClassMap.entries.removeIf { it.value.equals(clean, ignoreCase = true) }
        merchantUserClassMap.entries.removeIf { it.value.equals(clean, ignoreCase = true) }
        if (removed) saveToPrefs()
        return removed
    }

    fun assignUserClassification(
        txId: Long,
        merchantName: String,
        userClassificationName: String?,
        applyToMerchantRule: Boolean,
        isDeposit: Boolean = false,
        vararg extraMerchantNames: String?
    ) {
        val cleanName = userClassificationName?.trim().orEmpty()
        if (cleanName.isNotBlank()) {
            addOrGetUserClassification(cleanName)
            if (txId > 0L) {
                txUserClassMap[txId] = cleanName
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                putDirectionalRule(
                    merchantUserClassMap,
                    merchantName,
                    isDeposit,
                    cleanName,
                    *extraMerchantNames
                )
            }
        } else {
            if (txId > 0L) {
                txUserClassMap.remove(txId)
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                removeDirectionalRule(
                    merchantUserClassMap,
                    merchantName,
                    isDeposit,
                    *extraMerchantNames
                )
            }
        }
        saveToPrefs()
    }

    @Synchronized
    fun resolveUserClassification(tx: Transaction): UserClassificationItem? {
        val assignedName = (if (tx.id > 0L) txUserClassMap[tx.id] else null)
            ?: (if (!isOneTimeEdited(tx)) {
                lookupDirectionalRule(
                    merchantUserClassMap,
                    tx.merchantName,
                    tx.isDepositTransaction()
                )
            } else null)
            ?: return null

        return userClassificationList.firstOrNull { it.name.equals(assignedName, ignoreCase = true) }
            ?: UserClassificationItem(assignedName, (assignedName.hashCode().and(0x7fffffff)) % BADGE_COLOR_COUNT)
    }

    @Synchronized
    fun getUserClassifications(): List<UserClassificationItem> = userClassificationList.toList()

    // =========================================================================
    // 5. 선택적 항목 제거 및 자동 재등록 영구 차단 블랙리스트 (Tombstone)
    // =========================================================================

    fun markTransactionDeleted(tx: Transaction) {
        val cleanOrig = tx.originalText.trim()
        if (cleanOrig.isNotBlank()) {
            deletedTxSignatures.add("ORIG_${cleanOrig.hashCode()}")
        }
        deletedTxSignatures.add("TIME_${tx.timestamp.withSecond(0).withNano(0)}_${tx.amount}")
        if (tx.id > 0L) {
            txCustomCategoryMap.remove(tx.id)
            txUserClassMap.remove(tx.id)
            txCorporateMap.remove(tx.id)
            txFixedExpenseMap.remove(tx.id)
            oneTimeEditedTxIds.remove(tx.id)
        }
        saveToPrefs()
    }

    fun markTransactionsDeleted(txList: Collection<Transaction>) {
        for (tx in txList) {
            val cleanOrig = tx.originalText.trim()
            if (cleanOrig.isNotBlank()) {
                deletedTxSignatures.add("ORIG_${cleanOrig.hashCode()}")
            }
            deletedTxSignatures.add("TIME_${tx.timestamp.withSecond(0).withNano(0)}_${tx.amount}")
            if (tx.id > 0L) {
                txCustomCategoryMap.remove(tx.id)
                txUserClassMap.remove(tx.id)
                txCorporateMap.remove(tx.id)
                txFixedExpenseMap.remove(tx.id)
                oneTimeEditedTxIds.remove(tx.id)
            }
        }
        saveToPrefs()
    }

    fun isTransactionDeleted(tx: Transaction): Boolean {
        val cleanOrig = tx.originalText.trim()
        if (cleanOrig.isNotBlank() && "ORIG_${cleanOrig.hashCode()}" in deletedTxSignatures) {
            return true
        }
        if ("TIME_${tx.timestamp.withSecond(0).withNano(0)}_${tx.amount}" in deletedTxSignatures) {
            return true
        }
        return false
    }

    fun markVehicleLogDeleted(log: VehicleLog) {
        addVehicleLogSignatureInternal(log)
        saveToPrefs()
    }

    fun markVehicleLogsDeleted(logs: Collection<VehicleLog>) {
        for (log in logs) {
            addVehicleLogSignatureInternal(log)
        }
        saveToPrefs()
    }

    private fun addVehicleLogSignatureInternal(log: VehicleLog) {
        val date = log.timestamp.toLocalDate()
        deletedVehicleLogSignatures.add("VLOG_${date}_${log.logType.name}_${log.fuelCost}_${log.tripDistanceKm}")
        if ((log.logType.name == "REFUELING" || log.logType.name == "MAINTENANCE") && log.fuelCost > 0L) {
            deletedVehicleLogSignatures.add("REFUEL_${date}_${log.fuelCost}")
        }
        if (log.logType.name == "TRIP_DRIVING" && log.note?.contains("골프") == true) {
            deletedVehicleLogSignatures.add("GOLF_DRIVE_$date")
        }
    }

    fun isRefuelingSyncDeleted(date: LocalDate, fuelCost: Long): Boolean {
        return "REFUEL_${date}_$fuelCost" in deletedVehicleLogSignatures
    }

    fun isGolfDrivingSyncDeleted(date: LocalDate): Boolean {
        return "GOLF_DRIVE_$date" in deletedVehicleLogSignatures
    }

    companion object {
        private const val PREFS_NAME = "expense_custom_meta_prefs"
        private const val KEY_META_JSON = "expense_custom_meta_json"
        const val BADGE_COLOR_COUNT = 10

        val DEFAULT_CARD_COMPANIES = listOf(
            "신한카드", "KB국민카드", "현대카드", "삼성카드",
            "롯데카드", "우리카드", "하나카드", "NH농협카드",
            "BC카드", "카카오페이", "네이버페이", "토스페이"
        )

        val DEFAULT_BANK_NAMES = listOf(
            "NH농협은행", "KB국민은행", "신한은행", "우리은행",
            "하나은행", "기업은행", "카카오뱅크", "토스뱅크",
            "새마을금고", "우체국", "SC제일은행", "케이뱅크"
        )

        /**
         * 카드 결제 힌트 문자열(예: "하나0*2*", "NH0*2*", "우리(1234)", "KB국민체크(8*4*)" 등)에서
         * 표준 카드사명을 자동 추출하여 반환합니다.
         */
        fun inferCanonicalCardCompany(
            rawCardOrHint: String?,
            fallbackOriginalText: String? = null,
            fallbackSecondText: String? = null
        ): String {
            val cleaned = rawCardOrHint.orEmpty()
                .replace(Regex("""\s*\(\s*법인(?:카드)?\s*\)"""), "")
                .replace(Regex("""\s*\[\s*법인(?:카드)?\s*\]"""), "")
                .replace(Regex("""\s*\(\s*개인(?:카드)?\s*\)"""), "")
                .trim()

            // 이미 표준 카드사 목록과 정확히 일치하는 경우 즉시 반환
            if (cleaned in DEFAULT_CARD_COMPANIES) return cleaned

            fun matchCardFromText(text: String): String? {
                if (text.isBlank()) return null
                val upper = text.uppercase()
                return when {
                    text.startsWith("하나") || text.contains("하나카드") || text.contains("하나체크") || upper.contains("HANA") || Regex("""하나\s*\(?[0-9*]{2,}""").containsMatchIn(text) || Regex("""(?:신용|체크)\s*\(\s*,?\s*[0-9*]{2,}\s*\)""").containsMatchIn(text) -> "하나카드"
                    upper.startsWith("NH") || text.startsWith("농협") || upper.contains("NH농협") || text.contains("농협카드") || text.contains("농협체크") || text.contains("채움") -> "NH농협카드"
                    text.startsWith("우리") || text.contains("우리카드") || text.contains("우리체크") || upper.contains("WOORI") || Regex("""우리\s*\(?[0-9*]{2,}""").containsMatchIn(text) -> "우리카드"
                    text.startsWith("신한") || upper.contains("SOL페이") || text.contains("신한카드") || text.contains("신한체크") || upper.contains("SHINHAN") || Regex("""신한\s*\(?[0-9*]{2,}""").containsMatchIn(text) -> "신한카드"
                    upper.startsWith("KB") || text.startsWith("국민") || upper.contains("KB국민") || text.contains("국민카드") || text.contains("국민체크") || upper.contains("KOOKMIN") -> "KB국민카드"
                    text.startsWith("현대") || text.contains("현대카드") || upper.contains("HYUNDAI") || Regex("""현대\s*\(?[0-9*]{2,}""").containsMatchIn(text) -> "현대카드"
                    text.startsWith("삼성") || text.contains("삼성카드") || upper.contains("SAMSUNG") || Regex("""삼성\s*\(?[0-9*]{2,}""").containsMatchIn(text) -> "삼성카드"
                    text.startsWith("롯데") || text.contains("롯데카드") || upper.contains("LOTTE") || Regex("""롯데\s*\(?[0-9*]{2,}""").containsMatchIn(text) -> "롯데카드"
                    upper.startsWith("BC") || text.startsWith("비씨") || upper.contains("BC카드") || text.contains("비씨카드") || text.contains("페이북") || upper.contains("PAYBOOC") -> "BC카드"
                    text.contains("카카오페이") || text.startsWith("카카오") || upper.contains("KAKAOPAY") -> "카카오페이"
                    text.contains("네이버페이") || text.startsWith("네이버") || upper.contains("NAVERPAY") -> "네이버페이"
                    text.contains("토스페이") || text.startsWith("토스") || upper.contains("TOSS") -> "토스페이"
                    text.contains("하나") -> "하나카드"
                    upper.contains("NH") || text.contains("농협") -> "NH농협카드"
                    text.contains("우리") -> "우리카드"
                    text.contains("신한") -> "신한카드"
                    upper.contains("KB") || text.contains("국민") -> "KB국민카드"
                    text.contains("현대") -> "현대카드"
                    text.contains("삼성") -> "삼성카드"
                    text.contains("롯데") -> "롯데카드"
                    upper.contains("BC") || text.contains("비씨") -> "BC카드"
                    else -> null
                }
            }

            matchCardFromText(cleaned)?.let { return it }
            if (!fallbackOriginalText.isNullOrBlank()) {
                matchCardFromText(fallbackOriginalText)?.let { return it }
            }
            if (!fallbackSecondText.isNullOrBlank()) {
                matchCardFromText(fallbackSecondText)?.let { return it }
            }

            // 마스킹 번호(0*2* 등)가 섞여 있거나 "(결제)" 같은 비카드명이면 기본값 반환
            if (cleaned.isBlank() || cleaned == "카드" || cleaned == "신용카드" || cleaned == "체크카드" ||
                cleaned.startsWith("(결제)") || cleaned.contains("*")
            ) {
                return "신한카드"
            }
            return cleaned
        }

        /**
         * 계좌 출금/이체/입금 내역의 힌트 문자열(예: "NH농협", "312-...", "KB국민", "우리", "하나" 등)에서
         * 표준 은행명을 자동 추출하여 반환합니다.
         */
        fun inferCanonicalBankName(
            rawBankOrHint: String?,
            fallbackOriginalText: String? = null,
            fallbackMemo: String? = null
        ): String {
            val cleaned = rawBankOrHint.orEmpty()
                .replace(Regex("""\s*\(\s*법인(?:카드)?\s*\)"""), "")
                .replace(Regex("""\s*\[\s*법인(?:카드)?\s*\]"""), "")
                .trim()

            if (cleaned in DEFAULT_BANK_NAMES) return cleaned

            fun matchBankFromText(text: String): String? {
                if (text.isBlank()) return null
                val upper = text.uppercase()
                return when {
                    text.contains("카카오뱅크") || text.startsWith("카카오") -> "카카오뱅크"
                    text.contains("토스뱅크") || text.startsWith("토스") -> "토스뱅크"
                    text.contains("케이뱅크") || upper.contains("K뱅크") -> "케이뱅크"
                    upper.contains("NH") || text.contains("농협") || text.contains("312-") || text.contains("302-") || text.contains("351-") || text.contains("352-") -> "NH농협은행"
                    upper.contains("KB") || text.contains("국민") || text.contains("0749") -> "KB국민은행"
                    text.contains("신한") -> "신한은행"
                    text.contains("우리") -> "우리은행"
                    text.contains("하나") -> "하나은행"
                    text.contains("기업") || upper.contains("IBK") -> "기업은행"
                    text.contains("새마을") -> "새마을금고"
                    text.contains("우체국") -> "우체국"
                    upper.contains("SC") || text.contains("제일은행") -> "SC제일은행"
                    else -> null
                }
            }

            matchBankFromText(cleaned)?.let { return it }
            if (!fallbackOriginalText.isNullOrBlank()) {
                matchBankFromText(fallbackOriginalText)?.let { return it }
            }
            if (!fallbackMemo.isNullOrBlank()) {
                matchBankFromText(fallbackMemo)?.let { return it }
            }

            if (cleaned.isNotBlank() && !cleaned.contains("카드") && cleaned !in setOf("은행 이체", "결제", "출금", "입금")) {
                return cleaned
            }
            return "NH농협은행"
        }
    }
}

