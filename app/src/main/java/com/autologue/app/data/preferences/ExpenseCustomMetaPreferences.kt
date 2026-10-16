package com.autologue.app.data.preferences

import android.content.Context
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.VehicleLog
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
 * 1) 카드사 및 법인/개인 여부 지정 관리
 * 2) 내역 수정 시 1회성 저장 보호(One-Time Override) 관리
 * 3) 커스텀 카테고리 영구 추가 및 전체 카테고리(추가/수정/삭제) 관리
 * 4) 카테고리와 독립된 '사용자 분류'(직접 입력, 추가/수정/삭제, 배지 색상) 관리
 * 5) 자동 등록된 결제기록·주행기록 선택 삭제 후 재동기화 부활 방지 블랙리스트(Tombstone) 관리
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

    // 거래 ID별 / 가맹점별 세부(커스텀) 카테고리 매핑
    private val txCustomCategoryMap = ConcurrentHashMap<Long, String>()
    private val merchantCustomCategoryMap = ConcurrentHashMap<String, String>()

    // 2. 사용자 분류 목록 및 거래 ID별 / 가맹점별 사용자 분류 매핑
    private val userClassificationList = mutableListOf<UserClassificationItem>()
    private val txUserClassMap = ConcurrentHashMap<Long, String>()
    private val merchantUserClassMap = ConcurrentHashMap<String, String>()

    // 3. 법인카드 여부 및 가맹점별 카드 지정 매핑
    private val txCorporateMap = ConcurrentHashMap<Long, Boolean>()
    private val merchantCardDesignationMap = ConcurrentHashMap<String, String>()

    // 4. 1회성 수정 보호(One-Time Save) 거래 ID 및 서명 집합
    private val oneTimeEditedTxIds = ConcurrentHashMap.newKeySet<Long>()
    private val oneTimeEditedSignatures = ConcurrentHashMap.newKeySet<String>()

    // 5. 선택 삭제된 결제기록 / 주행기록 재등록 방지 블랙리스트(Tombstone)
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
            if (cat == ExpenseCategory.INCOME) continue
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
    // 1. 카드 지정 (카드사 및 법인/개인 여부)
    // =========================================================================

    fun formatCardDesignation(cardCompany: String, isCorporate: Boolean): String {
        val cleanBase = extractBaseCardName(cardCompany).ifBlank {
            if (isCorporate) "법인카드" else "신용카드"
        }
        return if (isCorporate) {
            if (cleanBase.contains("법인")) cleanBase else "$cleanBase(법인)"
        } else {
            cleanBase
        }
    }

    fun extractBaseCardName(cardOrBankName: String?): String {
        if (cardOrBankName.isNullOrBlank()) return ""
        return cardOrBankName
            .replace(Regex("""\s*\(\s*법인(?:카드)?\s*\)"""), "")
            .replace(Regex("""\s*\[\s*법인(?:카드)?\s*\]"""), "")
            .replace(Regex("""\s*\(\s*개인(?:카드)?\s*\)"""), "")
            .trim()
    }

    fun isCorporateTransaction(tx: Transaction): Boolean {
        txCorporateMap[tx.id]?.let { return it }
        if (tx.cardOrBankName.contains("법인")) return true
        if (!isOneTimeEdited(tx)) {
            val normMer = MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)
            val savedCard = merchantCardDesignationMap[normMer]
            if (savedCard != null && savedCard.contains("법인")) return true
        }
        return false
    }

    fun setTransactionCardDesignation(
        txId: Long,
        merchantName: String,
        cardCompany: String,
        isCorporate: Boolean,
        applyToMerchantRule: Boolean
    ): String {
        val formatted = formatCardDesignation(cardCompany, isCorporate)
        if (txId > 0L) {
            txCorporateMap[txId] = isCorporate
        }
        if (applyToMerchantRule && merchantName.isNotBlank()) {
            val normMer = MerchantAliasPreferences.normalizeMerchantKey(merchantName)
            if (normMer.isNotBlank()) {
                merchantCardDesignationMap[normMer] = formatted
            }
        }
        saveToPrefs()
        return formatted
    }

    fun resolveMerchantCardDesignation(merchantName: String?): String? {
        val norm = MerchantAliasPreferences.normalizeMerchantKey(merchantName.orEmpty())
        if (norm.isBlank()) return null
        return merchantCardDesignationMap[norm]
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

    fun assignCustomCategory(
        txId: Long,
        merchantName: String,
        customCategoryName: String?,
        applyToMerchantRule: Boolean
    ) {
        val cleanCat = customCategoryName?.trim().orEmpty()
        if (cleanCat.isNotBlank()) {
            addCustomCategory(cleanCat)
            if (txId > 0L) {
                txCustomCategoryMap[txId] = cleanCat
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                val normMer = MerchantAliasPreferences.normalizeMerchantKey(merchantName)
                if (normMer.isNotBlank()) {
                    merchantCustomCategoryMap[normMer] = cleanCat
                }
            }
        } else {
            if (txId > 0L) {
                txCustomCategoryMap.remove(txId)
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                val normMer = MerchantAliasPreferences.normalizeMerchantKey(merchantName)
                if (normMer.isNotBlank()) {
                    merchantCustomCategoryMap.remove(normMer)
                }
            }
        }
        saveToPrefs()
    }

    fun resolveCustomCategoryLabel(tx: Transaction): String? {
        if (tx.id > 0L) {
            txCustomCategoryMap[tx.id]?.let { return it }
        }
        if (!isOneTimeEdited(tx)) {
            val normMer = MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)
            if (normMer.isNotBlank()) {
                merchantCustomCategoryMap[normMer]?.let { return it }
            }
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
    // 4. 사용자 분류 관리 (직접입력, 추가 / 수정 / 삭제, 배지 색상)
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
        applyToMerchantRule: Boolean
    ) {
        val cleanName = userClassificationName?.trim().orEmpty()
        if (cleanName.isNotBlank()) {
            addOrGetUserClassification(cleanName)
            if (txId > 0L) {
                txUserClassMap[txId] = cleanName
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                val normMer = MerchantAliasPreferences.normalizeMerchantKey(merchantName)
                if (normMer.isNotBlank()) {
                    merchantUserClassMap[normMer] = cleanName
                }
            }
        } else {
            if (txId > 0L) {
                txUserClassMap.remove(txId)
            }
            if (applyToMerchantRule && merchantName.isNotBlank()) {
                val normMer = MerchantAliasPreferences.normalizeMerchantKey(merchantName)
                if (normMer.isNotBlank()) {
                    merchantUserClassMap.remove(normMer)
                }
            }
        }
        saveToPrefs()
    }

    @Synchronized
    fun resolveUserClassification(tx: Transaction): UserClassificationItem? {
        val assignedName = (if (tx.id > 0L) txUserClassMap[tx.id] else null)
            ?: (if (!isOneTimeEdited(tx)) {
                val normMer = MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)
                if (normMer.isNotBlank()) merchantUserClassMap[normMer] else null
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
        deletedVehicleLogSignatures.add("VLOG_${date}_${log.logType.name}_${log.fuelCost ?: 0L}_${log.tripDistanceKm ?: 0.0}")
        if (log.logType.name == "REFUELING" && (log.fuelCost ?: 0L) > 0L) {
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
        const val BADGE_COLOR_COUNT = 8

        val DEFAULT_CARD_COMPANIES = listOf(
            "신한카드", "KB국민카드", "현대카드", "삼성카드",
            "롯데카드", "우리카드", "하나카드", "NH농협카드",
            "BC카드", "카카오페이", "네이버페이", "토스페이"
        )
    }
}
