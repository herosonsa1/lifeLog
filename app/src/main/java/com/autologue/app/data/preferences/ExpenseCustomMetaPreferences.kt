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
 * 영구저장 시 선택적으로 지정하여 저장·일괄 적용할 항목 옵션
 * - 금액(amount)은 동일 항목 취급 기준 및 일괄 변경 대상에서 항상 제외됩니다.
 */
data class PermanentSaveOptions(
    val applyMerchantName: Boolean = true,
    val applyCategory: Boolean = true,
    val applyUserClassification: Boolean = true,
    val applyCardAndCorporate: Boolean = true,
    val applyFixedExpense: Boolean = true,
    val applyMemo: Boolean = false,
    val selectedExistingTxIds: Set<Long>? = null,
    val fixedExpenseMatchPrefix: String? = null
)

/**
 * 영구저장된 가맹점(결제처) 규칙 중 개별 해제 가능한 속성 구분
 */
enum class PermanentRuleAttribute {
    ALIAS_NAME,           // 결제명 자동 치환 규칙
    CATEGORY,             // 기본/커스텀 카테고리 규칙
    USER_CLASSIFICATION,  // 사용자 분류 규칙
    CARD_DESIGNATION,     // 카드/은행 및 법인 지정 규칙
    FIXED_EXPENSE         // 고정지출(정기지출) 지정 규칙
}

/**
 * 영구저장된 가맹점(결제처) 통합 규칙 요약 모델
 */
data class PermanentMerchantRuleSummary(
    val ruleKey: String,
    val merchantName: String,
    val isDeposit: Boolean,
    val sourceMerchantNames: List<String> = emptyList(),
    val baseCategory: ExpenseCategory? = null,
    val categoryDisplayName: String? = null,
    val customCategoryName: String? = null,
    val userClassificationName: String? = null,
    val cardDesignation: String? = null,
    val isFixedExpense: Boolean? = null,
    val fixedExpenseMatchPrefix: String? = null
) {
    val hasAliasRule: Boolean get() = sourceMerchantNames.isNotEmpty()
    val effectiveCategoryLabel: String?
        get() = customCategoryName?.takeIf { it.isNotBlank() } ?: categoryDisplayName
}

/**
 * 카드별 법인/개인 영구 지정 규칙 모델
 */
data class PermanentCardCorporateRuleItem(
    val cardKey: String,
    val isCorporate: Boolean
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
    private val customCategoryList = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val renamedDefaultCategories = ConcurrentHashMap<String, String>()
    private val deletedDefaultCategories = ConcurrentHashMap.newKeySet<String>()

    // 거래 ID별 / (입출금구분+가맹점)별 기본 및 세부(커스텀) 카테고리 매핑
    private val txCustomCategoryMap = ConcurrentHashMap<Long, String>()
    private val merchantCustomCategoryMap = ConcurrentHashMap<String, String>()
    private val merchantBaseCategoryMap = ConcurrentHashMap<String, String>()

    // 2. 사용자 분류 목록 및 거래 ID별 / (입출금구분+가맹점)별 사용자 분류 매핑
    private val userClassificationList = java.util.concurrent.CopyOnWriteArrayList<UserClassificationItem>()
    private val txUserClassMap = ConcurrentHashMap<Long, String>()
    private val merchantUserClassMap = ConcurrentHashMap<String, String>()

    // 3. 법인카드 여부 (거래 ID별, 추출 카드정보/카드사 기준 영구 규칙별, 입출금구분+가맹점별)
    private val txCorporateMap = ConcurrentHashMap<Long, Boolean>()
    private val cardCorporateRuleMap = ConcurrentHashMap<String, Boolean>()
    private val merchantCardDesignationMap = ConcurrentHashMap<String, String>()

    // 4. 고정지출(정기지출) 지정 상태 (거래 ID별, 가맹점 영구 규칙별, 제목 앞자리 텍스트 일치 규칙별)
    private val txFixedExpenseMap = ConcurrentHashMap<Long, Boolean>()
    private val merchantFixedExpenseMap = ConcurrentHashMap<String, String>()
    private val merchantFixedPrefixRuleMap = ConcurrentHashMap<String, String>()

    // 5. 1회성 수정 보호(One-Time Save) 거래 ID 및 서명 집합
    private val oneTimeEditedTxIds = ConcurrentHashMap.newKeySet<Long>()
    private val oneTimeEditedSignatures = ConcurrentHashMap.newKeySet<String>()

    // 6. 선택 삭제된 결제기록 / 주행기록 재등록 방지 블랙리스트(Tombstone)
    private val deletedTxSignatures = ConcurrentHashMap.newKeySet<String>()
    private val deletedVehicleLogSignatures = ConcurrentHashMap.newKeySet<String>()

    // 일괄 수정 시 불필요한 다중 JSON 직렬화 및 Flow 중복 발행을 방지하는 배치 깊이 카운터
    private val batchDepth = java.util.concurrent.atomic.AtomicInteger(0)
    private val batchDirty = java.util.concurrent.atomic.AtomicBoolean(false)

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

    /**
     * 여러 메타데이터 규칙을 연속으로 변경할 때 JSON 직렬화 및 Flow 갱신을 마지막 1회로 병합합니다.
     */
    fun <T> runBatchUpdate(block: () -> T): T {
        batchDepth.incrementAndGet()
        try {
            return block()
        } finally {
            if (batchDepth.decrementAndGet() == 0 && batchDirty.compareAndSet(true, false)) {
                saveToPrefs()
            }
        }
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

            cardCorporateRuleMap.clear()
            root.optJSONObject("cardCorporateRuleMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim()
                    if (k.isNotBlank()) {
                        cardCorporateRuleMap[k] = obj.optBoolean(k, false)
                    }
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

            merchantFixedPrefixRuleMap.clear()
            root.optJSONObject("merchantFixedPrefixRuleMap")?.let { obj ->
                val keys = obj.keys()
                while (keys.hasNext()) {
                    val k = keys.next().trim()
                    val v = obj.optString(k).trim()
                    if (k.isNotBlank() && v.isNotBlank()) merchantFixedPrefixRuleMap[k] = v
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
        if (batchDepth.get() > 0) {
            batchDirty.set(true)
            return
        }
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

            val cardCorpObj = JSONObject()
            cardCorporateRuleMap.forEach { (k, v) -> cardCorpObj.put(k, v) }
            root.put("cardCorporateRuleMap", cardCorpObj)

            val merCardObj = JSONObject()
            merchantCardDesignationMap.forEach { (k, v) -> merCardObj.put(k, v) }
            root.put("merchantCardDesignationMap", merCardObj)

            val txFixedObj = JSONObject()
            txFixedExpenseMap.forEach { (k, v) -> txFixedObj.put(k.toString(), v) }
            root.put("txFixedExpenseMap", txFixedObj)

            val merFixedObj = JSONObject()
            merchantFixedExpenseMap.forEach { (k, v) -> merFixedObj.put(k, v) }
            root.put("merchantFixedExpenseMap", merFixedObj)

            val merFixedPrefixObj = JSONObject()
            merchantFixedPrefixRuleMap.forEach { (k, v) -> merFixedPrefixObj.put(k, v) }
            root.put("merchantFixedPrefixRuleMap", merFixedPrefixObj)

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
            if (cat == ExpenseCategory.ETC) continue
            if (cat.name in deletedDefaultCategories) continue
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
        // '기타'(ExpenseCategory.ETC) 항목은 항상 커스텀 카테고리들보다도 뒤인 제일 하단(마지막)에 배치
        val etcLabel = renamedDefaultCategories[ExpenseCategory.ETC.name] ?: ExpenseCategory.ETC.displayName
        managed.add(
            ManagedCategoryItem(
                id = "BUILTIN_${ExpenseCategory.ETC.name}",
                displayName = etcLabel,
                baseCategory = ExpenseCategory.ETC,
                isBuiltIn = true
            )
        )
        _managedCategoriesFlow.value = managed
        _metaVersionFlow.value = _metaVersionFlow.value + 1L
    }

    // =========================================================================
    // 1. 카드사 / 은행명 힌트 자동 추출 및 지정 (입금/출금 방향 구분 + 카드 기준 법인여부 영구저장)
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
     * 추출 가능한 카드사 정보(원본 카드 토큰 "하나0*2*", "신용(,0*2*)" 및 표준 카드사명 "하나카드" 등)를
     * 기준으로 영구저장된 법인카드 여부를 조회합니다.
     */
    fun resolveCardCorporateRule(tx: Transaction, overrideCardCompany: String? = null): Boolean? {
        val keys = extractCardCorporateKeys(
            cardCompany = overrideCardCompany ?: tx.cardOrBankName,
            rawCardOrBankName = tx.cardOrBankName,
            originalText = tx.originalText
        )
        for (key in keys) {
            cardCorporateRuleMap[key]?.let { return it }
        }
        return null
    }

    fun resolveCardCorporateByCardName(
        cardCompany: String?,
        rawCardOrBankName: String? = null,
        originalText: String? = null
    ): Boolean? {
        val keys = extractCardCorporateKeys(
            cardCompany = cardCompany,
            rawCardOrBankName = rawCardOrBankName ?: cardCompany,
            originalText = originalText
        )
        for (key in keys) {
            cardCorporateRuleMap[key]?.let { return it }
        }
        return null
    }

    /**
     * 거래 내역의 성격(카드 결제 vs 출금/이체/입금 계좌 거래) 및 영구저장 규칙에 맞춰
     * 화면에 표시할 최종 카드사명 또는 은행명을 반환합니다.
     */
    fun resolveDisplayCardOrBankName(tx: Transaction, fallbackMerchantName: String? = null): String {
        val isBankTx = tx.isBankAccountTransaction()
        if (!isOneTimeEdited(tx)) {
            val isDep = tx.isDepositTransaction()
            val savedRule = lookupDirectionalRule(
                merchantCardDesignationMap,
                tx.merchantName,
                isDep
            ) ?: fallbackMerchantName?.takeIf { it.isNotBlank() && it != tx.merchantName }?.let { fbMer ->
                lookupDirectionalRule(merchantCardDesignationMap, fbMer, isDep)
            }
            if (!savedRule.isNullOrBlank()) {
                return if (isBankTx) {
                    inferCanonicalBankName(savedRule, tx.originalText, tx.transferMemo)
                } else {
                    val isCorp = isCorporateTransaction(tx)
                    // 문자 원문에 명시적인 타 카드사 힌트가 있는 경우 해당 카드사를 유지하고 법인 여부만 반영
                    val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
                    val explicitFromOrig = if (!isManual) {
                        extractExplicitCardCompanyFromOriginal(null, tx.originalText)
                            ?: extractExplicitCardCompanyFromOriginal(tx.cardOrBankName, tx.originalText)
                    } else {
                        extractExplicitCardCompanyFromOriginal(tx.cardOrBankName, null)
                    }
                    val baseCard = explicitFromOrig ?: savedRule
                    formatCardDesignation(baseCard, isCorp, isBankAccountTx = false)
                }
            }
        }
        return if (isBankTx) {
            inferCanonicalBankName(tx.cardOrBankName, tx.originalText, tx.transferMemo)
        } else {
            val isCorp = isCorporateTransaction(tx)
            val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
            val canonicalCard = if (!isManual) {
                extractExplicitCardCompanyFromOriginal(null, tx.originalText)
                    ?: inferCanonicalCardCompany(tx.cardOrBankName, tx.originalText)
            } else {
                inferCanonicalCardCompany(tx.cardOrBankName, tx.originalText)
            }
            formatCardDesignation(canonicalCard, isCorp, isBankAccountTx = false)
        }
    }

    fun isCorporateTransaction(tx: Transaction): Boolean {
        if (tx.isBankAccountTransaction()) return false
        // 1회성 저장된 거래는 해당 거래의 개별 법인 여부를 최우선 적용
        if (isOneTimeEdited(tx)) {
            txCorporateMap[tx.id]?.let { return it }
        }
        // 1. 추출 가능한 카드사 정보(카드 토큰 / 표준 카드사명) 기준 영구저장된 법인카드 여부
        resolveCardCorporateRule(tx)?.let { return it }

        // 2. 카테고리 또는 사용자 분류가 '업무/법인'으로 선택된 경우 기본값은 법인카드(true)
        val userClassName = resolveUserClassification(tx)?.name
        val customCatName = resolveCustomCategoryLabel(tx)
        val builtInCatName = getBuiltInCategoryDisplayName(tx.category)
        if (isCorporateCategoryOrClassification(userClassName) ||
            isCorporateCategoryOrClassification(customCatName) ||
            isCorporateCategoryOrClassification(builtInCatName)
        ) {
            if (tx.id <= 0L || txCorporateMap[tx.id] != false) {
                return true
            }
        }

        // 3. 개별 거래 ID에 저장된 법인 여부
        txCorporateMap[tx.id]?.let { return it }

        // 4. 가맹점 기준 저장된 카드 지정 규칙 및 원문 '법인' 키워드
        if (!isOneTimeEdited(tx)) {
            val savedCard = lookupDirectionalRule(
                merchantCardDesignationMap,
                tx.merchantName,
                tx.isDepositTransaction()
            )
            if (savedCard != null && savedCard.contains("법인")) return true
        }
        if (tx.cardOrBankName.contains("법인") || tx.originalText.contains("법인")) return true
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
        isBankAccountTx: Boolean = false,
        rawCardOrBankName: String? = null,
        originalText: String? = null,
        targetTxIds: Collection<Long> = emptyList()
    ): String {
        val effectiveCorp = if (isBankAccountTx) false else isCorporate
        val formatted = formatCardDesignation(cardCompany, effectiveCorp, isBankAccountTx = isBankAccountTx)
        if (txId > 0L) {
            txCorporateMap[txId] = effectiveCorp
        }
        for (targetId in targetTxIds) {
            if (targetId > 0L) {
                txCorporateMap[targetId] = effectiveCorp
            }
        }
        if (applyToMerchantRule) {
            if (!isBankAccountTx) {
                val cardKeys = extractCardCorporateKeys(
                    cardCompany = cardCompany,
                    rawCardOrBankName = rawCardOrBankName ?: cardCompany,
                    originalText = originalText
                )
                for (key in cardKeys) {
                    if (key.isNotBlank()) {
                        cardCorporateRuleMap[key] = effectiveCorp
                    }
                }
                // 1회성 저장이 아닌 일반 거래들의 이전 개별 캐시를 정리하여 카드 기준 영구설정이 즉시 일괄 반영되도록 처리
                txCorporateMap.keys.removeIf { id -> id != txId && id !in targetTxIds && id !in oneTimeEditedTxIds }
            }
            if (merchantName.isNotBlank()) {
                putDirectionalRule(
                    merchantCardDesignationMap,
                    merchantName,
                    isDeposit,
                    formatted,
                    *extraMerchantNames
                )
            }
        }
        saveToPrefs()
        return formatted
    }

    fun resolveMerchantCardDesignation(merchantName: String?, isDeposit: Boolean = false): String? {
        return lookupDirectionalRule(merchantCardDesignationMap, merchantName, isDeposit)
    }

    // =========================================================================
    // 1-2. 고정항목(고정지출·고정수입) 수동 및 영구 지정 (앞자리 텍스트 일치 규칙 포함)
    // =========================================================================

    fun setFixedExpense(
        txId: Long,
        merchantName: String,
        isFixed: Boolean,
        applyToMerchantRule: Boolean,
        isDeposit: Boolean = false,
        vararg extraMerchantNames: String?,
        targetTxIds: Collection<Long> = emptyList(),
        matchPrefix: String? = null
    ) {
        if (txId > 0L) {
            txFixedExpenseMap[txId] = isFixed
        }
        for (targetId in targetTxIds) {
            if (targetId > 0L) {
                txFixedExpenseMap[targetId] = isFixed
            }
        }
        val cleanPrefix = MerchantAliasPreferences.sanitizeSingleLine(matchPrefix)
        val dirPrefix = MerchantAliasPreferences.directionPrefix(isDeposit)
        if (cleanPrefix.isNotBlank()) {
            // 앞자리 텍스트 기준이 명시된 경우 고정항목 앞자리 규칙으로 영구 등록/해제
            val prefixRuleKey = "$dirPrefix$cleanPrefix"
            if (isFixed) {
                merchantFixedPrefixRuleMap[prefixRuleKey] = "true"
            } else {
                merchantFixedPrefixRuleMap.remove(prefixRuleKey)
            }
        } else if (!isFixed && applyToMerchantRule && merchantName.isNotBlank()) {
            // 일반 항목으로 영구 해제할 때 해당 가맹점에 걸려 있던 앞자리 고정 규칙도 함께 해제
            merchantFixedPrefixRuleMap.keys.removeIf { rawKey ->
                rawKey.startsWith(dirPrefix) &&
                    matchesFixedExpensePrefix(merchantName, MerchantAliasPreferences.stripDirectionPrefix(rawKey))
            }
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

    /**
     * 해당 결제명에 적용 중인 고정항목 앞자리(접두어) 일치 규칙 텍스트를 반환합니다.
     */
    fun resolveFixedExpenseMatchPrefix(merchantName: String?, isDeposit: Boolean = false): String? {
        val raw = MerchantAliasPreferences.sanitizeSingleLine(merchantName)
        if (raw.isBlank()) return null
        val dirPrefix = MerchantAliasPreferences.directionPrefix(isDeposit)
        var bestPrefix: String? = null
        for ((rawKey, value) in merchantFixedPrefixRuleMap.entries) {
            if (!rawKey.startsWith(dirPrefix)) continue
            if (value != "true") continue
            val prefix = MerchantAliasPreferences.sanitizeSingleLine(
                MerchantAliasPreferences.stripDirectionPrefix(rawKey)
            )
            if (prefix.isNotBlank() && matchesFixedExpensePrefix(raw, prefix)) {
                if (bestPrefix == null || prefix.length > bestPrefix.length) {
                    bestPrefix = prefix
                }
            }
        }
        return bestPrefix
    }

    private fun lookupFixedPrefixRule(merchantName: String?, isDeposit: Boolean): Boolean? {
        val raw = MerchantAliasPreferences.sanitizeSingleLine(merchantName)
        if (raw.isBlank()) return null
        val dirPrefix = MerchantAliasPreferences.directionPrefix(isDeposit)
        var bestLen = -1
        var bestVal: Boolean? = null
        for ((rawKey, value) in merchantFixedPrefixRuleMap.entries) {
            if (!rawKey.startsWith(dirPrefix)) continue
            val prefix = MerchantAliasPreferences.sanitizeSingleLine(
                MerchantAliasPreferences.stripDirectionPrefix(rawKey)
            )
            if (prefix.isNotBlank() && matchesFixedExpensePrefix(raw, prefix)) {
                if (prefix.length > bestLen) {
                    bestLen = prefix.length
                    bestVal = (value == "true")
                }
            }
        }
        return bestVal
    }

    fun resolveIsFixedExpense(tx: Transaction, autoRecurringIds: Set<Long> = emptySet()): Boolean {
        val isDeposit = tx.isDepositTransaction() || tx.category == ExpenseCategory.INCOME
        if (isOneTimeEdited(tx)) {
            if (tx.id > 0L && txFixedExpenseMap.containsKey(tx.id)) {
                return txFixedExpenseMap[tx.id] == true
            }
        } else {
            lookupDirectionalRule(
                merchantFixedExpenseMap,
                tx.merchantName,
                isDeposit
            )?.let { return it == "true" }
        }
        // 앞자리(접두어) 텍스트 지정 고정항목 규칙 확인 (예: "예별손" -> "예별손090154", "동양" -> "동양09-155")
        lookupFixedPrefixRule(tx.merchantName, isDeposit)?.let { return it }
        if (!isOneTimeEdited(tx)) {
            if (tx.id > 0L && txFixedExpenseMap.containsKey(tx.id)) {
                return txFixedExpenseMap[tx.id] == true
            }
            lookupDirectionalRule(
                merchantFixedExpenseMap,
                tx.merchantName,
                !isDeposit
            )?.let { return it == "true" }
            lookupFixedPrefixRule(tx.merchantName, !isDeposit)?.let { return it }
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
        vararg extraMerchantNames: String?,
        targetTxIds: Collection<Long> = emptyList()
    ) {
        val cleanCat = customCategoryName?.trim().orEmpty()
        if (cleanCat.isNotBlank()) {
            addCustomCategory(cleanCat)
            if (txId > 0L) {
                txCustomCategoryMap[txId] = cleanCat
            }
            for (targetId in targetTxIds) {
                if (targetId > 0L) {
                    txCustomCategoryMap[targetId] = cleanCat
                }
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
            for (targetId in targetTxIds) {
                if (targetId > 0L) {
                    txCustomCategoryMap.remove(targetId)
                }
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
        if (isOneTimeEdited(tx)) {
            if (tx.id > 0L) {
                txCustomCategoryMap[tx.id]?.let { return it }
            }
            return null
        }
        lookupDirectionalRule(
            merchantCustomCategoryMap,
            tx.merchantName,
            tx.isDepositTransaction()
        )?.let { return it }
        if (tx.id > 0L) {
            txCustomCategoryMap[tx.id]?.let { return it }
        }
        return null
    }

    fun resolveEffectiveCategoryDisplayName(tx: Transaction): String {
        val customLabel = resolveCustomCategoryLabel(tx)
        if (!customLabel.isNullOrBlank()) {
            if (tx.amount in 1L until com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON &&
                (customLabel.contains("주유") || customLabel.contains("주차"))
            ) {
                return "주차"
            }
            return customLabel
        }
        val builtInLabel = getBuiltInCategoryDisplayName(tx.category)
        if (tx.amount in 1L until com.autologue.app.domain.model.MIN_REFUEL_AMOUNT_WON &&
            (tx.category == ExpenseCategory.FUEL || builtInLabel.contains("주유") || builtInLabel.contains("주차"))
        ) {
            return "주차"
        }
        return builtInLabel
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
        vararg extraMerchantNames: String?,
        targetTxIds: Collection<Long> = emptyList()
    ) {
        val cleanName = userClassificationName?.trim().orEmpty()
        if (cleanName.isNotBlank()) {
            addOrGetUserClassification(cleanName)
            if (txId > 0L) {
                txUserClassMap[txId] = cleanName
            }
            for (targetId in targetTxIds) {
                if (targetId > 0L) {
                    txUserClassMap[targetId] = cleanName
                }
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
            for (targetId in targetTxIds) {
                if (targetId > 0L) {
                    txUserClassMap.remove(targetId)
                }
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

    fun resolveUserClassification(tx: Transaction): UserClassificationItem? {
        val assignedName = if (isOneTimeEdited(tx)) {
            if (tx.id > 0L) txUserClassMap[tx.id] else null
        } else {
            lookupDirectionalRule(
                merchantUserClassMap,
                tx.merchantName,
                tx.isDepositTransaction()
            ) ?: (if (tx.id > 0L) txUserClassMap[tx.id] else null)
        } ?: return null

        return userClassificationList.firstOrNull { it.name.equals(assignedName, ignoreCase = true) }
            ?: UserClassificationItem(assignedName, (assignedName.hashCode().and(0x7fffffff)) % BADGE_COLOR_COUNT)
    }

    fun getUserClassifications(): List<UserClassificationItem> = userClassificationList.toList()

    // =========================================================================
    // 5. 선택적 항목 제거 및 자동 재등록 영구 차단 블랙리스트 (Tombstone)
    // =========================================================================

    private fun addDeletedTransactionSignatureInternal(tx: Transaction) {
        if (tx.id > 0L) {
            deletedTxSignatures.add("ID_${tx.id}")
            txCustomCategoryMap.remove(tx.id)
            txUserClassMap.remove(tx.id)
            txCorporateMap.remove(tx.id)
            txFixedExpenseMap.remove(tx.id)
            oneTimeEditedTxIds.remove(tx.id)
        }
        val cleanOrig = tx.originalText.trim()
        if (cleanOrig.isNotBlank()) {
            deletedTxSignatures.add("ORIG_${cleanOrig.hashCode()}")
        }
        deletedTxSignatures.add("TIME_${tx.timestamp.withSecond(0).withNano(0)}_${tx.amount}")
        val normMer = MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)
        if (normMer.isNotBlank()) {
            deletedTxSignatures.add("DAY_AMT_MER_${tx.timestamp.toLocalDate()}_${tx.amount}_$normMer")
        }
    }

    fun markTransactionDeleted(tx: Transaction) {
        addDeletedTransactionSignatureInternal(tx)
        saveToPrefs()
    }

    fun markTransactionsDeleted(txList: Collection<Transaction>) {
        for (tx in txList) {
            addDeletedTransactionSignatureInternal(tx)
        }
        saveToPrefs()
    }

    fun unmarkManualTransactionDeleted(tx: Transaction) {
        val cleanOrig = tx.originalText.trim()
        if (cleanOrig.isNotBlank()) {
            deletedTxSignatures.remove("ORIG_${cleanOrig.hashCode()}")
        }
        deletedTxSignatures.remove("TIME_${tx.timestamp.withSecond(0).withNano(0)}_${tx.amount}")
        val normMer = MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)
        if (normMer.isNotBlank()) {
            deletedTxSignatures.remove("DAY_AMT_MER_${tx.timestamp.toLocalDate()}_${tx.amount}_$normMer")
        }
        saveToPrefs()
    }

    fun isTransactionDeleted(tx: Transaction): Boolean {
        if (deletedTxSignatures.isEmpty()) return false
        if (tx.id > 0L && "ID_${tx.id}" in deletedTxSignatures) {
            return true
        }
        val cleanOrig = tx.originalText.trim()
        if (cleanOrig.isNotBlank() && "ORIG_${cleanOrig.hashCode()}" in deletedTxSignatures) {
            return true
        }
        if ("TIME_${tx.timestamp.withSecond(0).withNano(0)}_${tx.amount}" in deletedTxSignatures) {
            return true
        }
        val normMer = MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)
        if (normMer.isNotBlank() && "DAY_AMT_MER_${tx.timestamp.toLocalDate()}_${tx.amount}_$normMer" in deletedTxSignatures) {
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

    // =========================================================================
    // 6. 영구저장 데이터 통합 조회 및 해제/초기화 관리
    // =========================================================================

    /**
     * 영구저장된 결제명 자동 치환(MerchantAliasPreferences)과
     * 가맹점별 메타 규칙(카테고리, 커스텀 카테고리, 사용자 분류, 카드/은행·법인 지정, 고정지출)을
     * (입출금 구분 + 대표 결제명) 단위로 통합하여 반환합니다.
     */
    fun getPermanentMerchantRuleSummaries(
        aliasPreferences: MerchantAliasPreferences,
        knownMerchantNames: Collection<String> = emptyList()
    ): List<PermanentMerchantRuleSummary> {
        val aliasGroups = aliasPreferences.getAliasRuleGroups()
        // 정규화 키 -> 가독성 높은 원래 표기명 매핑 사전 구축
        val prettyNameByNorm = HashMap<String, String>()
        for (name in knownMerchantNames) {
            val clean = MerchantAliasPreferences.sanitizeSingleLine(name)
            if (clean.isBlank()) continue
            val norm = MerchantAliasPreferences.normalizeMerchantKey(clean)
            if (norm.isNotBlank()) {
                val existing = prettyNameByNorm[norm]
                if (existing == null || clean.count { it == ' ' } > existing.count { it == ' ' }) {
                    prettyNameByNorm[norm] = clean
                }
            }
        }
        for (group in aliasGroups) {
            val cleanTarget = MerchantAliasPreferences.sanitizeSingleLine(group.targetName)
            val normTarget = MerchantAliasPreferences.normalizeMerchantKey(cleanTarget)
            if (normTarget.isNotBlank()) {
                prettyNameByNorm[normTarget] = cleanTarget
            }
            for (src in group.sourceNames) {
                val cleanSrc = MerchantAliasPreferences.sanitizeSingleLine(src)
                val normSrc = MerchantAliasPreferences.normalizeMerchantKey(cleanSrc)
                if (normSrc.isNotBlank()) {
                    prettyNameByNorm.putIfAbsent(normSrc, cleanSrc)
                }
            }
        }

        data class BucketKey(val isDeposit: Boolean, val canonicalNorm: String)
        val bucketDisplayNames = LinkedHashMap<BucketKey, String>()
        val bucketSourceSets = LinkedHashMap<BucketKey, LinkedHashSet<String>>()

        // 1) 결제명 자동 치환 그룹 먼저 등록
        for (group in aliasGroups) {
            val cleanTarget = MerchantAliasPreferences.sanitizeSingleLine(group.targetName)
            val normTarget = MerchantAliasPreferences.normalizeMerchantKey(cleanTarget)
            if (normTarget.isBlank()) continue
            val bKey = BucketKey(isDeposit = group.isDeposit, canonicalNorm = normTarget)
            bucketDisplayNames[bKey] = cleanTarget
            val srcSet = bucketSourceSets.getOrPut(bKey) { LinkedHashSet() }
            group.sourceNames.forEach { src ->
                val cleanSrc = MerchantAliasPreferences.sanitizeSingleLine(src)
                if (!cleanSrc.equals(cleanTarget, ignoreCase = true) &&
                    MerchantAliasPreferences.normalizeMerchantKey(cleanSrc) != normTarget
                ) {
                    srcSet.add(cleanSrc)
                }
            }
        }

        // 2) 5대 가맹점 메타 규칙 맵 및 앞자리 고정 규칙 맵의 모든 키를 스캔하여 통합 버킷에 병합
        val allMetaKeys = LinkedHashSet<String>().apply {
            addAll(merchantBaseCategoryMap.keys)
            addAll(merchantCustomCategoryMap.keys)
            addAll(merchantUserClassMap.keys)
            addAll(merchantCardDesignationMap.keys)
            addAll(merchantFixedExpenseMap.keys)
            addAll(merchantFixedPrefixRuleMap.keys)
        }

        for (rawKey in allMetaKeys) {
            val isDeposit = rawKey.startsWith(MerchantAliasPreferences.PREFIX_IN)
            val stripped = MerchantAliasPreferences.sanitizeSingleLine(MerchantAliasPreferences.stripDirectionPrefix(rawKey))
            if (stripped.isBlank()) continue

            val resolvedTarget = MerchantAliasPreferences.sanitizeSingleLine(
                aliasPreferences.resolveAlias(stripped, isDeposit).ifBlank { stripped }
            )
            val canonicalNorm = MerchantAliasPreferences.normalizeMerchantKey(resolvedTarget)
            if (canonicalNorm.isBlank()) continue

            val bKey = BucketKey(isDeposit = isDeposit, canonicalNorm = canonicalNorm)
            if (!bucketDisplayNames.containsKey(bKey)) {
                val bestDisplay = prettyNameByNorm[canonicalNorm]
                    ?: resolvedTarget.takeIf { it != canonicalNorm }
                    ?: stripped
                bucketDisplayNames[bKey] = MerchantAliasPreferences.sanitizeSingleLine(bestDisplay)
            }
            bucketSourceSets.putIfAbsent(bKey, LinkedHashSet())
        }

        val summaries = mutableListOf<PermanentMerchantRuleSummary>()
        for ((bKey, displayMerchant) in bucketDisplayNames.entries) {
            val isDep = bKey.isDeposit
            val sources = bucketSourceSets[bKey]?.toList().orEmpty()
            val candidateLookupNames = buildList {
                add(displayMerchant)
                add(bKey.canonicalNorm)
                addAll(sources)
            }.distinct()

            fun lookupFirstIn(map: ConcurrentHashMap<String, String>): String? {
                for (cand in candidateLookupNames) {
                    lookupDirectionalRule(map, cand, isDep)?.takeIf { it.isNotBlank() }?.let { return it }
                }
                return null
            }

            val baseCatRaw = lookupFirstIn(merchantBaseCategoryMap)
            val baseCat = baseCatRaw?.let { runCatching { ExpenseCategory.valueOf(it) }.getOrNull() }
            val customCat = lookupFirstIn(merchantCustomCategoryMap)
            val userClass = lookupFirstIn(merchantUserClassMap)
            val cardDesig = lookupFirstIn(merchantCardDesignationMap)
            val fixedPrefixMatched = candidateLookupNames.firstNotNullOfOrNull { cand ->
                resolveFixedExpenseMatchPrefix(cand, isDep)
            }
            val fixedRaw = lookupFirstIn(merchantFixedExpenseMap)
                ?: if (!fixedPrefixMatched.isNullOrBlank()) "true" else null
            val isFixed = when (fixedRaw) {
                "true" -> true
                "false" -> false
                else -> null
            }

            if (sources.isEmpty() &&
                baseCat == null &&
                customCat.isNullOrBlank() &&
                userClass.isNullOrBlank() &&
                cardDesig.isNullOrBlank() &&
                isFixed == null
            ) {
                continue
            }

            val dirPrefix = MerchantAliasPreferences.directionPrefix(isDep)
            summaries.add(
                PermanentMerchantRuleSummary(
                    ruleKey = "$dirPrefix${bKey.canonicalNorm}",
                    merchantName = displayMerchant,
                    isDeposit = isDep,
                    sourceMerchantNames = sources,
                    baseCategory = baseCat,
                    categoryDisplayName = baseCat?.let { getBuiltInCategoryDisplayName(it) },
                    customCategoryName = customCat,
                    userClassificationName = userClass,
                    cardDesignation = cardDesig,
                    isFixedExpense = isFixed,
                    fixedExpenseMatchPrefix = fixedPrefixMatched
                )
            )
        }

        return summaries.sortedWith(
            compareByDescending<PermanentMerchantRuleSummary> { it.hasAliasRule }
                .thenBy { it.isDeposit }
                .thenBy { it.merchantName }
        )
    }

    /**
     * 특정 가맹점(결제처)에 영구저장되어 있는 모든 메타데이터 규칙(카테고리, 커스텀 카테고리, 사용자 분류, 카드 지정, 고정지출)을
     * 일괄 해제(삭제)합니다.
     */
    fun removePermanentMerchantRule(
        merchantName: String,
        isDeposit: Boolean,
        extraMerchantNames: Collection<String> = emptyList(),
        affectedTxIds: Collection<Long> = emptyList()
    ) {
        val extras = extraMerchantNames.toTypedArray()
        removeDirectionalRule(merchantBaseCategoryMap, merchantName, isDeposit, *extras)
        removeDirectionalRule(merchantCustomCategoryMap, merchantName, isDeposit, *extras)
        removeDirectionalRule(merchantUserClassMap, merchantName, isDeposit, *extras)
        removeDirectionalRule(merchantCardDesignationMap, merchantName, isDeposit, *extras)
        removeDirectionalRule(merchantFixedExpenseMap, merchantName, isDeposit, *extras)
        val dirPrefix = MerchantAliasPreferences.directionPrefix(isDeposit)
        val allNames = listOf(merchantName) + extraMerchantNames
        merchantFixedPrefixRuleMap.keys.removeIf { rawKey ->
            if (!rawKey.startsWith(dirPrefix)) return@removeIf false
            val prefix = MerchantAliasPreferences.stripDirectionPrefix(rawKey)
            allNames.any { name ->
                name.equals(prefix, ignoreCase = true) || matchesFixedExpensePrefix(name, prefix)
            }
        }

        for (txId in affectedTxIds) {
            if (txId > 0L && txId !in oneTimeEditedTxIds) {
                txCustomCategoryMap.remove(txId)
                txUserClassMap.remove(txId)
                txCorporateMap.remove(txId)
                txFixedExpenseMap.remove(txId)
            }
        }
        saveToPrefs()
    }

    /**
     * 특정 가맹점(결제처)의 영구저장 규칙 중 선택한 단일 속성만 개별 해제합니다.
     */
    fun removePermanentMerchantAttribute(
        attribute: PermanentRuleAttribute,
        merchantName: String,
        isDeposit: Boolean,
        extraMerchantNames: Collection<String> = emptyList(),
        affectedTxIds: Collection<Long> = emptyList()
    ) {
        val extras = extraMerchantNames.toTypedArray()
        when (attribute) {
            PermanentRuleAttribute.ALIAS_NAME -> {
                // 별칭 치환 해제는 MerchantAliasPreferences에서 처리하며,
                // 만약 원본명(extraMerchantNames)으로 메타 규칙을 이관해야 하는 경우 이관 보존
                for (orig in extraMerchantNames) {
                    if (orig.isBlank()) continue
                    lookupDirectionalRule(merchantBaseCategoryMap, merchantName, isDeposit)?.let {
                        putDirectionalRule(merchantBaseCategoryMap, orig, isDeposit, it)
                    }
                    lookupDirectionalRule(merchantCustomCategoryMap, merchantName, isDeposit)?.let {
                        putDirectionalRule(merchantCustomCategoryMap, orig, isDeposit, it)
                    }
                    lookupDirectionalRule(merchantUserClassMap, merchantName, isDeposit)?.let {
                        putDirectionalRule(merchantUserClassMap, orig, isDeposit, it)
                    }
                    lookupDirectionalRule(merchantCardDesignationMap, merchantName, isDeposit)?.let {
                        putDirectionalRule(merchantCardDesignationMap, orig, isDeposit, it)
                    }
                    lookupDirectionalRule(merchantFixedExpenseMap, merchantName, isDeposit)?.let {
                        putDirectionalRule(merchantFixedExpenseMap, orig, isDeposit, it)
                    }
                }
            }
            PermanentRuleAttribute.CATEGORY -> {
                removeDirectionalRule(merchantBaseCategoryMap, merchantName, isDeposit, *extras)
                removeDirectionalRule(merchantCustomCategoryMap, merchantName, isDeposit, *extras)
                for (txId in affectedTxIds) {
                    if (txId > 0L && txId !in oneTimeEditedTxIds) {
                        txCustomCategoryMap.remove(txId)
                    }
                }
            }
            PermanentRuleAttribute.USER_CLASSIFICATION -> {
                removeDirectionalRule(merchantUserClassMap, merchantName, isDeposit, *extras)
                for (txId in affectedTxIds) {
                    if (txId > 0L && txId !in oneTimeEditedTxIds) {
                        txUserClassMap.remove(txId)
                    }
                }
            }
            PermanentRuleAttribute.CARD_DESIGNATION -> {
                removeDirectionalRule(merchantCardDesignationMap, merchantName, isDeposit, *extras)
                for (txId in affectedTxIds) {
                    if (txId > 0L && txId !in oneTimeEditedTxIds) {
                        txCorporateMap.remove(txId)
                    }
                }
            }
            PermanentRuleAttribute.FIXED_EXPENSE -> {
                removeDirectionalRule(merchantFixedExpenseMap, merchantName, isDeposit, *extras)
                val dirPrefix = MerchantAliasPreferences.directionPrefix(isDeposit)
                val allNames = listOf(merchantName) + extraMerchantNames
                merchantFixedPrefixRuleMap.keys.removeIf { rawKey ->
                    if (!rawKey.startsWith(dirPrefix)) return@removeIf false
                    val prefix = MerchantAliasPreferences.stripDirectionPrefix(rawKey)
                    allNames.any { name ->
                        name.equals(prefix, ignoreCase = true) || matchesFixedExpensePrefix(name, prefix)
                    }
                }
                for (txId in affectedTxIds) {
                    if (txId > 0L && txId !in oneTimeEditedTxIds) {
                        txFixedExpenseMap.remove(txId)
                    }
                }
            }
        }
        saveToPrefs()
    }

    /**
     * 카드별 영구저장된 법인/개인 지정 규칙 목록을 반환합니다.
     */
    fun getCardCorporateRules(): List<PermanentCardCorporateRuleItem> {
        return cardCorporateRuleMap.entries
            .map { (k, v) -> PermanentCardCorporateRuleItem(cardKey = k, isCorporate = v) }
            .sortedWith(compareByDescending<PermanentCardCorporateRuleItem> { it.isCorporate }.thenBy { it.cardKey })
    }

    /**
     * 특정 카드 키의 법인/개인 영구 지정 규칙을 해제합니다.
     */
    fun removeCardCorporateRule(cardKey: String) {
        val clean = cardKey.trim()
        if (clean.isBlank()) return
        cardCorporateRuleMap.remove(clean)
        txCorporateMap.keys.removeIf { id -> id !in oneTimeEditedTxIds }
        saveToPrefs()
    }

    /**
     * 가맹점별 영구 규칙 및 카드 법인 규칙을 모두 일괄 초기화합니다. (카테고리 목록 및 사용자 분류 목록 자체는 유지)
     */
    fun clearAllPermanentRules() {
        merchantBaseCategoryMap.clear()
        merchantCustomCategoryMap.clear()
        merchantUserClassMap.clear()
        merchantCardDesignationMap.clear()
        merchantFixedExpenseMap.clear()
        merchantFixedPrefixRuleMap.clear()
        cardCorporateRuleMap.clear()

        txCustomCategoryMap.keys.removeIf { id -> id !in oneTimeEditedTxIds }
        txUserClassMap.keys.removeIf { id -> id !in oneTimeEditedTxIds }
        txCorporateMap.keys.removeIf { id -> id !in oneTimeEditedTxIds }
        txFixedExpenseMap.keys.removeIf { id -> id !in oneTimeEditedTxIds }
        saveToPrefs()
    }

    companion object {
        private const val PREFS_NAME = "expense_custom_meta_prefs"
        private const val KEY_META_JSON = "expense_custom_meta_json"
        const val BADGE_COLOR_COUNT = 10

        private val TRAILING_VAR_SUFFIX_REGEX = Regex("""[\s\-_()/]*\d[\d\s\-_()/]*$""")

        /**
         * 월별 일련번호가 붙는 가맹점/결제명(예: "예별손090154", "동양09-155")에서
         * 고정항목 판별 기준이 될 앞자리 텍스트(예: "예별손", "동양")를 추천 추출합니다.
         */
        fun suggestFixedExpensePrefix(merchantName: String?): String {
            val raw = MerchantAliasPreferences.sanitizeSingleLine(merchantName)
            if (raw.isBlank()) return ""
            val stripped = raw.replace(TRAILING_VAR_SUFFIX_REGEX, "").trim()
            if (stripped.length >= 2 && stripped.length < raw.length) {
                return stripped
            }
            return ""
        }

        /**
         * 주어진 결제명이 고정항목 판단 기준 텍스트(앞자리 일치)에 부합하는지 검사합니다.
         */
        fun matchesFixedExpensePrefix(merchantName: String?, prefixPattern: String?): Boolean {
            val rawMerchant = MerchantAliasPreferences.sanitizeSingleLine(merchantName)
            val cleanPrefix = MerchantAliasPreferences.sanitizeSingleLine(prefixPattern)
            if (rawMerchant.isBlank() || cleanPrefix.isBlank()) return false
            if (rawMerchant.startsWith(cleanPrefix, ignoreCase = true)) return true
            val normMerchant = MerchantAliasPreferences.normalizeMerchantKey(rawMerchant)
            val normPrefix = MerchantAliasPreferences.normalizeMerchantKey(cleanPrefix)
            if (normMerchant.isNotBlank() && normPrefix.isNotBlank() && normMerchant.startsWith(normPrefix)) {
                return true
            }
            return false
        }

        val DEFAULT_CARD_COMPANIES = listOf(
            "신한카드", "KB국민카드", "현대카드", "삼성카드",
            "롯데카드", "우리카드", "하나카드", "NH농협카드",
            "BC카드", "카카오페이", "토스페이"
        )

        val DEFAULT_BANK_NAMES = listOf(
            "NH농협은행", "KB국민은행", "신한은행", "우리은행",
            "하나은행", "기업은행", "카카오뱅크", "토스뱅크",
            "새마을금고", "우체국", "SC제일은행", "케이뱅크"
        )

        // 정규식 객체 사전 컴파일 (수천 건 트랜잭션 루프 내 반복 컴파일 오버헤드 완벽 제거)
        private val CORP_PAREN_REGEX = Regex("""\s*\(\s*법인(?:카드)?\s*\)""")
        private val CORP_BRACKET_REGEX = Regex("""\s*\[\s*법인(?:카드)?\s*\]""")
        private val PERSONAL_PAREN_REGEX = Regex("""\s*\(\s*개인(?:카드)?\s*\)""")
        private val HANA_MASK_REGEX = Regex("""하나\s*\(?[0-9*]{2,}""")
        private val HANA_COMMA_MASK_REGEX = Regex("""(?:신용|체크)\s*\(\s*,\s*[0-9*]{2,}\s*\)""")
        private val CREDIT_CHECK_MASK_REGEX = Regex("""(?:신용|체크)\s*\(\s*,?\s*[0-9*]{2,}\s*\)""")
        private val WOORI_MASK_REGEX = Regex("""우리(?:카드|체크|BC|비씨|법인|기업|WON|원|페이)?\s*\(?[0-9*]{2,}""")
        private val WOORI_APPROVAL_BRACKET_REGEX = Regex("""\[(?:일시불|체크|할부|해외|해외체크|법인|가족|\d+개월)\s*[.·]\s*(?:승인|취소|승인취소)\]""")
        private val SHINHAN_MASK_REGEX = Regex("""신한\s*\(?[0-9*]{2,}""")
        private val HYUNDAI_MASK_REGEX = Regex("""현대\s*\(?[0-9*]{2,}""")
        private val SAMSUNG_MASK_REGEX = Regex("""삼성\s*\(?[0-9*]{2,}""")
        private val LOTTE_MASK_REGEX = Regex("""롯데\s*\(?[0-9*]{2,}""")
        private val CARD_TOK_PREFIX_REGEX = Regex("""카드\s*[:：]?\s*(?<tok>[가-힣A-Za-z0-9*(),]{3,})""")
        private val CARD_TOK_ISSUER_REGEX = Regex("""(?<tok>(?:NH|KB|국민|신한|삼성|현대|롯데|우리|하나|BC|비씨|기업)(?:농협|국민|BC|비씨|WON)?(?:카드|체크|신용|법인|기업)?\s*\(?[,0-9*]{2,}\)?)""")
        private val CARD_TOK_TYPE_REGEX = Regex("""(?<tok>(?:신용|체크)\s*\(\s*,?\s*[0-9*]{2,}\s*\))""")
        private val DIGITS_AND_MASK_REGEX = Regex("""[0-9*]{2,}""")

        private fun stripCardTagSuffixes(raw: String): String {
            if (!raw.contains("법인") && !raw.contains("개인")) return raw.trim()
            return raw
                .replace(CORP_PAREN_REGEX, "")
                .replace(CORP_BRACKET_REGEX, "")
                .replace(PERSONAL_PAREN_REGEX, "")
                .trim()
        }

        private fun matchExplicitCardCompanyFromText(text: String?): String? {
            if (text.isNullOrBlank()) return null
            val upper = text.uppercase()
            return when {
                text.startsWith("우리") || text.contains("우리카드") || text.contains("우리체크") ||
                    text.contains("우리BC") || text.contains("우리비씨") || text.contains("우리법인") ||
                    text.contains("우리기업") || upper.contains("우리WON") || text.contains("우리원카드") ||
                    text.contains("우리페이") || upper.contains("WOORI") ||
                    text.contains("1588-9955") || text.contains("1599-9955") ||
                    text.contains("15889955") || text.contains("15999955") ||
                    WOORI_MASK_REGEX.containsMatchIn(text) ||
                    WOORI_APPROVAL_BRACKET_REGEX.containsMatchIn(text) -> "우리카드"
                text.startsWith("하나") || text.contains("하나카드") || text.contains("하나체크") ||
                    upper.contains("HANA") || text.contains("1800-1111") || text.contains("18001111") ||
                    HANA_MASK_REGEX.containsMatchIn(text) || HANA_COMMA_MASK_REGEX.containsMatchIn(text) -> "하나카드"
                upper.startsWith("NH") || text.startsWith("농협") || upper.contains("NH농협") ||
                    text.contains("농협카드") || text.contains("농협체크") || text.contains("채움") ||
                    text.contains("1588-1600") || text.contains("1644-4000") -> "NH농협카드"
                text.startsWith("신한") || upper.contains("SOL페이") || text.contains("신한카드") ||
                    text.contains("신한체크") || upper.contains("SHINHAN") || text.contains("1544-7200") ||
                    SHINHAN_MASK_REGEX.containsMatchIn(text) -> "신한카드"
                upper.startsWith("KB") || text.startsWith("국민") || upper.contains("KB국민") ||
                    text.contains("국민카드") || text.contains("국민체크") || upper.contains("KOOKMIN") ||
                    text.contains("1588-1688") -> "KB국민카드"
                text.startsWith("현대") || text.contains("현대카드") || upper.contains("HYUNDAI") ||
                    text.contains("1577-6000") || HYUNDAI_MASK_REGEX.containsMatchIn(text) -> "현대카드"
                text.startsWith("삼성") || text.contains("삼성카드") || upper.contains("SAMSUNG") ||
                    text.contains("1588-8700") || SAMSUNG_MASK_REGEX.containsMatchIn(text) -> "삼성카드"
                text.startsWith("롯데") || text.contains("롯데카드") || upper.contains("LOTTE") ||
                    text.contains("1588-8100") || LOTTE_MASK_REGEX.containsMatchIn(text) -> "롯데카드"
                upper.startsWith("BC") || text.startsWith("비씨") || upper.contains("BC카드") ||
                    text.contains("비씨카드") || text.contains("페이북") || upper.contains("PAYBOOC") ||
                    text.contains("1588-4000") -> "BC카드"
                text.contains("카카오페이") || text.startsWith("카카오") || upper.contains("KAKAOPAY") -> "카카오페이"
                text.contains("네이버페이") || text.startsWith("네이버") || upper.contains("NAVERPAY") -> "네이버페이"
                text.contains("토스페이") || text.startsWith("토스") || upper.contains("TOSS") -> "토스페이"
                text.contains("우리") -> "우리카드"
                text.contains("하나") -> "하나카드"
                upper.contains("NH") || text.contains("농협") -> "NH농협카드"
                text.contains("신한") -> "신한카드"
                upper.contains("KB") || text.contains("국민") -> "KB국민카드"
                text.contains("현대") -> "현대카드"
                text.contains("삼성") -> "삼성카드"
                text.contains("롯데") -> "롯데카드"
                upper.contains("BC") || text.contains("비씨") -> "BC카드"
                else -> null
            }
        }

        /**
         * 카드 결제 힌트 문자열(예: "하나0*2*", "NH0*2*", "우리(1234)", "KB국민체크(8*4*)" 등)에서
         * 표준 카드사명을 자동 추출하여 반환합니다.
         */
        fun inferCanonicalCardCompany(
            rawCardOrHint: String?,
            fallbackOriginalText: String? = null,
            fallbackSecondText: String? = null
        ): String {
            val cleaned = stripCardTagSuffixes(rawCardOrHint.orEmpty())

            // 이미 표준 카드사 목록과 정확히 일치하는 경우 즉시 반환
            if (cleaned in DEFAULT_CARD_COMPANIES) return cleaned

            // 1순위: 명시적인 카드사명/대표번호/고유 헤더 매칭 (신용/체크 일반 마스킹보다 항상 우선)
            matchExplicitCardCompanyFromText(cleaned)?.let { return it }
            if (!fallbackOriginalText.isNullOrBlank()) {
                matchExplicitCardCompanyFromText(fallbackOriginalText)?.let { return it }
            }
            if (!fallbackSecondText.isNullOrBlank()) {
                matchExplicitCardCompanyFromText(fallbackSecondText)?.let { return it }
            }

            // 2순위: 명시적 카드사명이 전혀 없을 때만 신용(1234)/체크(1234) 패턴을 하나카드로 추론
            if (CREDIT_CHECK_MASK_REGEX.containsMatchIn(cleaned) ||
                (!fallbackOriginalText.isNullOrBlank() && CREDIT_CHECK_MASK_REGEX.containsMatchIn(fallbackOriginalText)) ||
                (!fallbackSecondText.isNullOrBlank() && CREDIT_CHECK_MASK_REGEX.containsMatchIn(fallbackSecondText))
            ) {
                return "하나카드"
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
            val cleaned = stripCardTagSuffixes(rawBankOrHint.orEmpty())

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

        /**
         * 카테고리 또는 사용자 분류 이름이 '업무/법인' 계열인지 판별합니다.
         */
        fun isCorporateCategoryOrClassification(name: String?): Boolean {
            val clean = name?.trim().orEmpty()
            if (clean.isBlank()) return false
            return clean.equals("업무/법인", ignoreCase = true) ||
                clean.contains("법인") ||
                clean.equals("업무", ignoreCase = true) ||
                clean.startsWith("업무/")
        }

        /**
         * 문자 원문이나 원본 카드명에서 명시적인 카드사 힌트가 존재하는 경우 표준 카드사명을 반환합니다.
         */
        fun extractExplicitCardCompanyFromOriginal(
            rawCardOrBankName: String?,
            originalText: String?
        ): String? {
            if (!originalText.isNullOrBlank()) {
                matchExplicitCardCompanyFromText(originalText)?.let { return it }
            }
            if (!rawCardOrBankName.isNullOrBlank()) {
                matchExplicitCardCompanyFromText(rawCardOrBankName)?.let { return it }
            }
            val combined = "${rawCardOrBankName.orEmpty()} ${originalText.orEmpty()}".trim()
            if (combined.isBlank()) return null
            if (CREDIT_CHECK_MASK_REGEX.containsMatchIn(combined)) {
                return "하나카드"
            }
            return null
        }

        /**
         * 카드 결제 원문 또는 카드명에서 추출 가능한 고유 카드 토큰(예: "하나0*2*", "NH카드2*7*", "신용(,0*2*)", "삼성1844", "KB국민카드4024" 등)을 추출합니다.
         */
        fun extractRawCardHintToken(
            rawCardOrBankName: String?,
            originalText: String?
        ): String? {
            val orig = originalText.orEmpty()
            val rawCard = stripCardTagSuffixes(rawCardOrBankName.orEmpty())

            if (orig.isNotBlank()) {
                // 1. "카드 하나0*2*" 형태
                if (orig.contains("카드")) {
                    CARD_TOK_PREFIX_REGEX.find(orig)?.groups?.get("tok")?.value?.trim()?.let { tok ->
                        if (tok.any { it.isDigit() || it == '*' }) return tok
                    }
                }
                // 2. "하나0*2*", "NH카드2*7*", "삼성1844", "KB국민카드4024", "우리(1234)" 형태
                CARD_TOK_ISSUER_REGEX.find(orig)?.groups?.get("tok")?.value?.replace(" ", "")?.trim()?.let { tok ->
                    return tok
                }
                // 3. "신용(,0*2*)" 형태
                if (orig.contains("신용") || orig.contains("체크")) {
                    CARD_TOK_TYPE_REGEX.find(orig)?.groups?.get("tok")?.value?.replace(" ", "")?.trim()?.let { tok ->
                        return tok
                    }
                }
            }
            // 4. rawCard 자체에 마스킹 번호나 숫자가 포함된 경우 (예: "하나0*2*")
            if (rawCard.isNotBlank() && rawCard.any { it.isDigit() || it == '*' } && !rawCard.contains("원")) {
                return rawCard
            }
            return null
        }

        /**
         * 카드사 정보 및 추출된 카드 토큰을 기반으로 법인카드 여부 영구저장/조회용 키 목록(구체적인 카드번호 키 -> 표준 카드사명 키 순서)을 생성합니다.
         */
        fun extractCardCorporateKeys(
            cardCompany: String?,
            rawCardOrBankName: String? = null,
            originalText: String? = null
        ): List<String> {
            val keys = mutableListOf<String>()
            val canonicalCard = stripCardTagSuffixes(
                inferCanonicalCardCompany(
                    cardCompany ?: rawCardOrBankName,
                    rawCardOrBankName,
                    originalText
                )
            )

            val rawToken = extractRawCardHintToken(rawCardOrBankName ?: cardCompany, originalText)
            if (!rawToken.isNullOrBlank()) {
                val digitsAndMask = DIGITS_AND_MASK_REGEX.find(rawToken)?.value
                if (!digitsAndMask.isNullOrBlank() && canonicalCard.isNotBlank()) {
                    keys.add("${canonicalCard}#${digitsAndMask}")
                }
                keys.add(rawToken)
            }
            if (canonicalCard.isNotBlank()) {
                keys.add(canonicalCard)
            }
            return keys.distinct()
        }
    }
}

