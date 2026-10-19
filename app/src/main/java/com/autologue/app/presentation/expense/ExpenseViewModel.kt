package com.autologue.app.presentation.expense

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.data.preferences.ManagedCategoryItem
import com.autologue.app.data.preferences.PermanentCardCorporateRuleItem
import com.autologue.app.data.preferences.PermanentMerchantRuleSummary
import com.autologue.app.data.preferences.PermanentRuleAttribute
import com.autologue.app.data.preferences.UserAccount
import com.autologue.app.data.preferences.UserAccountPreferences
import com.autologue.app.data.preferences.UserClassificationItem
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.TransactionRule
import com.autologue.app.domain.model.isBankAccountTransaction
import com.autologue.app.domain.model.isDepositTransaction
import com.autologue.app.domain.model.isSelfTransfer
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.usecase.expense.ManageTransactionRulesUseCase
import com.autologue.app.domain.usecase.expense.RecurringExpenseDetector
import com.autologue.app.domain.usecase.export.ExportAllDataToExcelUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class ExpenseDateFilterMode {
    MONTH,          // 월간 단위 조회 (기본)
    CUSTOM_RANGE    // 특정 기간 직접 지정 조회
}

enum class ExpenseBreakdownMode {
    BOTH,           // 카테고리별 + 사용자별 통계 함께 보기 (기본)
    BY_CATEGORY,    // 카테고리별 지출 통계만 보기
    BY_USER         // 사용자별 지출 통계만 보기
}

const val UNASSIGNED_USER_FILTER = "__UNASSIGNED__"

data class CategoryStatItem(
    val displayName: String,
    val amount: Long,
    val txCount: Int,
    val percentage: Float,
    val topUsersSummary: String
)

data class UserClassificationStatItem(
    val userKey: String,
    val displayName: String,
    val colorIndex: Int?,
    val isUnassigned: Boolean,
    val amount: Long,
    val txCount: Int,
    val corporateAmount: Long,
    val corporateTxCount: Int,
    val percentage: Float,
    val topCategoriesSummary: String
)

data class ExpenseUiState(
    val allTransactions: List<Transaction> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val selectedCategory: ExpenseCategory? = null,
    val selectedCustomCategoryName: String? = null,
    val selectedUserClassification: String? = null,
    val selectedCorporateFilter: Boolean? = null,
    val breakdownMode: ExpenseBreakdownMode = ExpenseBreakdownMode.BOTH,
    val rules: List<TransactionRule> = emptyList(),
    val totalExpense: Long = 0L,
    val totalIncome: Long = 0L,
    val categoryTotals: Map<ExpenseCategory, Long> = emptyMap(),
    val effectiveCategoryTotals: Map<String, Long> = emptyMap(),
    val userClassificationTotals: Map<String, Long> = emptyMap(),
    val isAddDialogOpen: Boolean = false,
    val editingTransaction: Transaction? = null,
    // 날짜 필터링 상태
    val filterMode: ExpenseDateFilterMode = ExpenseDateFilterMode.MONTH,
    val selectedYearMonth: YearMonth = YearMonth.now(),
    val customStartDate: LocalDate = LocalDate.now().withDayOfMonth(1),
    val customEndDate: LocalDate = LocalDate.now(),
    val isDateRangePickerOpen: Boolean = false,
    val monthlyBudget: Long = 1500000L,
    val isBudgetDialogOpen: Boolean = false,
    // 내 계좌 관리 상태
    val userAccounts: List<UserAccount> = emptyList(),
    val isAccountManageDialogOpen: Boolean = false,
    // 카테고리 관리 및 사용자 분류 관리 상태
    val managedCategories: List<ManagedCategoryItem> = emptyList(),
    val isCategoryManageDialogOpen: Boolean = false,
    val userClassifications: List<UserClassificationItem> = emptyList(),
    val isUserClassificationManageDialogOpen: Boolean = false,
    // 영구저장 데이터 통합 관리 상태
    val isPermanentRulesDialogOpen: Boolean = false,
    val permanentMerchantRules: List<PermanentMerchantRuleSummary> = emptyList(),
    val cardCorporateRules: List<PermanentCardCorporateRuleItem> = emptyList(),
    val userClassByTxId: Map<Long, UserClassificationItem> = emptyMap(),
    val customCategoryByTxId: Map<Long, String> = emptyMap(),
    val corporateByTxId: Map<Long, Boolean> = emptyMap(),
    val oneTimeEditedIds: Set<Long> = emptySet(),
    val metaVersion: Long = 0L,
    val isSaving: Boolean = false,
    val isLoading: Boolean = true,
    val loadingTitle: String = "가계부 내역 조회 중",
    val loadingMessage: String = "결제 내역과 통계를 불러오고 있습니다...",
    // 선택적 일괄 삭제 모드 상태
    val isSelectionMode: Boolean = false,
    val selectedTransactionIds: Set<Long> = emptySet(),
    // 정기지출(고정지출) 상태
    val recurringTransactionIds: Set<Long> = emptySet(),
    val isRecurringFilterOnly: Boolean = false,
    val recurringExpenseTotal: Long = 0L,
    // 문자 간편 붙여넣기 다이얼로그 상태
    val isPasteSmsDialogOpen: Boolean = false,
    val syncResultMessage: String? = null
) {
    val filteredTransactions: List<Transaction>
        get() {
            var list = transactions
            if (isRecurringFilterOnly) {
                list = list.filter { it.id in recurringTransactionIds }
            } else if (!selectedCustomCategoryName.isNullOrBlank()) {
                list = list.filter { customCategoryByTxId[it.id] == selectedCustomCategoryName }
            } else if (selectedCategory != null) {
                list = list.filter { tx ->
                    if (selectedCategory == ExpenseCategory.ETC) {
                        tx.category == ExpenseCategory.ETC && customCategoryByTxId[tx.id].isNullOrBlank()
                    } else {
                        tx.category == selectedCategory
                    }
                }
            }
            if (!selectedUserClassification.isNullOrBlank()) {
                list = if (selectedUserClassification == UNASSIGNED_USER_FILTER) {
                    list.filter { userClassByTxId[it.id] == null }
                } else {
                    list.filter { userClassByTxId[it.id]?.name == selectedUserClassification }
                }
            }
            if (selectedCorporateFilter != null) {
                list = list.filter { (corporateByTxId[it.id] == true) == selectedCorporateFilter }
            }
            return list
        }

    val hasActiveSubFilter: Boolean
        get() = isRecurringFilterOnly ||
            selectedCategory != null ||
            !selectedCustomCategoryName.isNullOrBlank() ||
            !selectedUserClassification.isNullOrBlank() ||
            selectedCorporateFilter != null

    val filteredExpenseTotal: Long
        get() = filteredTransactions
            .filter { it.category != ExpenseCategory.INCOME && !it.isSelfTransfer() }
            .sumOf { it.amount }

    fun buildActiveFilterLabel(getBuiltInName: (ExpenseCategory) -> String): String {
        val parts = mutableListOf<String>()
        if (isRecurringFilterOnly) parts.add("정기지출")
        if (!selectedCustomCategoryName.isNullOrBlank()) {
            parts.add("카테고리: $selectedCustomCategoryName")
        } else if (selectedCategory != null) {
            parts.add("카테고리: ${getBuiltInName(selectedCategory)}")
        }
        if (!selectedUserClassification.isNullOrBlank()) {
            val uLabel = if (selectedUserClassification == UNASSIGNED_USER_FILTER) "미지정" else selectedUserClassification
            parts.add("사용자: $uLabel")
        }
        if (selectedCorporateFilter != null) {
            parts.add(if (selectedCorporateFilter) "법인카드" else "개인결제")
        }
        return parts.joinToString(" · ")
    }

    /**
     * 카테고리별 지출 통계 항목 리스트 산출 (선택된 사용자/법인/정기지출 필터와 실시간 교차 연동, 기타 항목은 항상 최하단 배치)
     */
    fun getCategoryStatItems(getBuiltInName: (ExpenseCategory) -> String): List<CategoryStatItem> {
        var baseList = transactions.filter {
            it.category != ExpenseCategory.INCOME &&
                it.category != ExpenseCategory.TRANSFER ||
                (it.category == ExpenseCategory.TRANSFER && !(it.cardOrBankName.contains("내계좌") || (it.transferMemo?.contains("내계좌") == true)))
        }.filter { it.category != ExpenseCategory.INCOME }

        if (isRecurringFilterOnly) {
            baseList = baseList.filter { it.id in recurringTransactionIds }
        }
        if (!selectedUserClassification.isNullOrBlank()) {
            baseList = if (selectedUserClassification == UNASSIGNED_USER_FILTER) {
                baseList.filter { userClassByTxId[it.id] == null }
            } else {
                baseList.filter { userClassByTxId[it.id]?.name == selectedUserClassification }
            }
        }
        if (selectedCorporateFilter != null) {
            baseList = baseList.filter { (corporateByTxId[it.id] == true) == selectedCorporateFilter }
        }

        val etcName = getBuiltInName(ExpenseCategory.ETC)
        val baseTotal = baseList.sumOf { it.amount }.coerceAtLeast(1L)
        return baseList
            .groupBy { tx -> customCategoryByTxId[tx.id] ?: getBuiltInName(tx.category) }
            .mapNotNull { (catName, txs) ->
                val sum = txs.sumOf { it.amount }
                if (sum <= 0L) return@mapNotNull null
                val pct = (sum.toFloat() / baseTotal * 100f)
                val topUsers = txs
                    .groupBy { tx -> userClassByTxId[tx.id]?.name ?: "미지정" }
                    .mapValues { (_, uTxs) -> uTxs.sumOf { it.amount } }
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(" · ") { (uName, uAmt) ->
                        val uPct = (uAmt.toFloat() / sum * 100f).toInt()
                        "$uName %,d원(%d%%)".format(uAmt, uPct)
                    }
                CategoryStatItem(
                    displayName = catName,
                    amount = sum,
                    txCount = txs.size,
                    percentage = pct,
                    topUsersSummary = topUsers
                )
            }
            .sortedWith(
                compareBy<CategoryStatItem> {
                    it.displayName == etcName || it.displayName == ExpenseCategory.ETC.displayName
                }.thenByDescending { it.amount }
            )
    }

    /**
     * 사용자별 지출 통계 항목 리스트 산출 (선택된 카테고리/법인/정기지출 필터와 실시간 교차 연동 + 사용자별 주요 카테고리 요약 포함)
     */
    fun getUserClassificationStatItems(getBuiltInName: (ExpenseCategory) -> String): List<UserClassificationStatItem> {
        var baseList = transactions.filter {
            it.category != ExpenseCategory.INCOME &&
                (it.category != ExpenseCategory.TRANSFER || !(it.cardOrBankName.contains("내계좌") || (it.transferMemo?.contains("내계좌") == true)))
        }

        if (isRecurringFilterOnly) {
            baseList = baseList.filter { it.id in recurringTransactionIds }
        } else if (!selectedCustomCategoryName.isNullOrBlank()) {
            baseList = baseList.filter { customCategoryByTxId[it.id] == selectedCustomCategoryName }
        } else if (selectedCategory != null) {
            baseList = baseList.filter { tx ->
                if (selectedCategory == ExpenseCategory.ETC) {
                    tx.category == ExpenseCategory.ETC && customCategoryByTxId[tx.id].isNullOrBlank()
                } else {
                    tx.category == selectedCategory
                }
            }
        }
        if (selectedCorporateFilter != null) {
            baseList = baseList.filter { (corporateByTxId[it.id] == true) == selectedCorporateFilter }
        }

        val baseTotal = baseList.sumOf { it.amount }
        val denom = baseTotal.coerceAtLeast(1L)
        val groupedByUser = baseList.groupBy { tx -> userClassByTxId[tx.id]?.name ?: UNASSIGNED_USER_FILTER }

        val knownUserNames = (userClassifications.map { it.name } +
            groupedByUser.keys.filter { it != UNASSIGNED_USER_FILTER }).distinct()

        val assignedItems = knownUserNames.mapIndexed { idx, uName ->
            val uTxs = groupedByUser[uName].orEmpty()
            val sum = uTxs.sumOf { it.amount }
            val corpTxs = uTxs.filter { corporateByTxId[it.id] == true }
            val corpAmt = corpTxs.sumOf { it.amount }
            val matchedUc = userClassifications.firstOrNull { it.name == uName }
            val topCats = if (sum > 0L) {
                uTxs.groupBy { tx -> customCategoryByTxId[tx.id] ?: getBuiltInName(tx.category) }
                    .mapValues { (_, cTxs) -> cTxs.sumOf { it.amount } }
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(" · ") { (cName, cAmt) ->
                        val cPct = (cAmt.toFloat() / sum * 100f).toInt()
                        "$cName %,d원(%d%%)".format(cAmt, cPct)
                    }
            } else {
                ""
            }
            UserClassificationStatItem(
                userKey = uName,
                displayName = uName,
                colorIndex = matchedUc?.colorIndex ?: idx,
                isUnassigned = false,
                amount = sum,
                txCount = uTxs.size,
                corporateAmount = corpAmt,
                corporateTxCount = corpTxs.size,
                percentage = if (baseTotal > 0L) (sum.toFloat() / denom * 100f) else 0f,
                topCategoriesSummary = topCats
            )
        }.sortedWith(compareByDescending<UserClassificationStatItem> { it.amount }.thenBy { it.displayName })

        val unassignedTxs = groupedByUser[UNASSIGNED_USER_FILTER].orEmpty()
        val unassignedSum = unassignedTxs.sumOf { it.amount }
        val result = assignedItems.toMutableList()
        if (unassignedTxs.isNotEmpty() || result.isEmpty()) {
            val corpTxs = unassignedTxs.filter { corporateByTxId[it.id] == true }
            val topCats = if (unassignedSum > 0L) {
                unassignedTxs.groupBy { tx -> customCategoryByTxId[tx.id] ?: getBuiltInName(tx.category) }
                    .mapValues { (_, cTxs) -> cTxs.sumOf { it.amount } }
                    .entries
                    .sortedByDescending { it.value }
                    .take(3)
                    .joinToString(" · ") { (cName, cAmt) ->
                        val cPct = (cAmt.toFloat() / unassignedSum * 100f).toInt()
                        "$cName %,d원(%d%%)".format(cAmt, cPct)
                    }
            } else {
                ""
            }
            result.add(
                UserClassificationStatItem(
                    userKey = UNASSIGNED_USER_FILTER,
                    displayName = "미지정 (분류 전)",
                    colorIndex = null,
                    isUnassigned = true,
                    amount = unassignedSum,
                    txCount = unassignedTxs.size,
                    corporateAmount = corpTxs.sumOf { it.amount },
                    corporateTxCount = corpTxs.size,
                    percentage = if (baseTotal > 0L) (unassignedSum.toFloat() / denom * 100f) else 0f,
                    topCategoriesSummary = topCats
                )
            )
        }
        return result
    }

    val dateDisplayTitle: String
        get() = when (filterMode) {
            ExpenseDateFilterMode.MONTH -> "${selectedYearMonth.year}년 ${selectedYearMonth.monthValue}월"
            ExpenseDateFilterMode.CUSTOM_RANGE -> "${customStartDate.format(DateTimeFormatter.ofPattern("M.d"))} ~ ${customEndDate.format(DateTimeFormatter.ofPattern("M.d"))}"
        }
}

@HiltViewModel
class ExpenseViewModel @Inject constructor(
    @dagger.hilt.android.qualifiers.ApplicationContext private val appContext: android.content.Context,
    private val transactionRepository: TransactionRepository,
    private val vehicleRepository: com.autologue.app.domain.repository.VehicleRepository,
    private val manageTransactionRulesUseCase: ManageTransactionRulesUseCase,
    private val userAccountPreferences: UserAccountPreferences,
    private val merchantAliasPreferences: com.autologue.app.data.preferences.MerchantAliasPreferences,
    val expenseCustomMetaPreferences: ExpenseCustomMetaPreferences,
    private val exportAllDataToExcelUseCase: ExportAllDataToExcelUseCase,
    private val processTransactionUseCase: com.autologue.app.domain.usecase.expense.ProcessTransactionUseCase,
    private val importer: com.autologue.app.data.sync.HistoricalDataImporter
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpenseUiState())
    val uiState: StateFlow<ExpenseUiState> = _uiState.asStateFlow()

    // 현재 세션에서 삭제 요청된 트랜잭션 ID 집합 (DB 비동기 반영 전에도 즉시 100% 차단)
    private val deletedTxIdsInSession = java.util.concurrent.ConcurrentHashMap.newKeySet<Long>()

    private var hasAutoFocusedMonth = false
    private var dataLoadJob: kotlinx.coroutines.Job? = null
    private var rulesLoadJob: kotlinx.coroutines.Job? = null

    init {
        loadData()
        observeUserAccounts()
        observeCustomMeta()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { transactionRepository.cleanDuplicates() }
            cleanCorruptedTransferRecords()
        }
    }

    private fun observeCustomMeta() {
        viewModelScope.launch {
            expenseCustomMetaPreferences.managedCategoriesFlow.collectLatest { cats ->
                _uiState.update { it.copy(managedCategories = cats) }
                recalculateFilteredTransactions()
            }
        }
        viewModelScope.launch {
            expenseCustomMetaPreferences.userClassificationsFlow.collectLatest { ucs ->
                _uiState.update { it.copy(userClassifications = ucs) }
                recalculateFilteredTransactions()
            }
        }
        viewModelScope.launch {
            expenseCustomMetaPreferences.metaVersionFlow.collectLatest { ver ->
                _uiState.update { it.copy(metaVersion = ver) }
                recalculateFilteredTransactions()
            }
        }
        viewModelScope.launch {
            merchantAliasPreferences.aliasesFlow.collectLatest {
                recalculateFilteredTransactions()
            }
        }
    }

    private fun observeUserAccounts() {
        viewModelScope.launch {
            userAccountPreferences.accounts.collectLatest { accList ->
                _uiState.update { it.copy(userAccounts = accList) }
            }
        }
    }

    /**
     * 기존 DB에 저장되어 있던 계좌번호 노출 레코드 및 오염된 텍스트 자동 1회 정제
     */
    private suspend fun cleanCorruptedTransferRecords() {
        val all = transactionRepository.getAllTransactionsFlow().first()
        for (tx in all) {
            if (expenseCustomMetaPreferences.isOneTimeEdited(tx)) continue
            val isDeposit = tx.isDepositTransaction()
            if (merchantAliasPreferences.isKnownAliasTarget(tx.merchantName, isDeposit)) continue

            var updated = tx
            var isModified = false
            val isCardMsg = com.autologue.app.data.parser.SmsParser.isCardApprovalMessage(tx.originalText)

            // 1. "09/14 00:23 312-****-9414-21 이민희 잔액383,252원 22,000원" 패턴 정제
            if (tx.merchantName.contains("312-") || (tx.merchantName.contains("잔액") && tx.merchantName.contains("이민희")) ||
                (tx.originalText.contains("312-") && tx.originalText.contains("이민희"))) {
                updated = updated.copy(
                    amount = 22000L,
                    merchantName = merchantAliasPreferences.resolveAlias("이민희", false),
                    category = ExpenseCategory.TRANSFER,
                    cardOrBankName = "NH농협은행",
                    transferMemo = "출금 내역 (계좌: 312-****-9414-21, 잔액 383,252원)"
                )
                isModified = true
            } else if (tx.merchantName.startsWith("07491612193855") || (tx.originalText.contains("07491612193855") && (tx.merchantName == "0" || tx.merchantName.isBlank()))) {
                updated = updated.copy(
                    amount = 50000L,
                    merchantName = merchantAliasPreferences.resolveAlias("출금 내역", false),
                    category = ExpenseCategory.TRANSFER,
                    cardOrBankName = "KB국민은행",
                    transferMemo = "출금 내역 (계좌: 07491612193855)"
                )
                isModified = true
            } else if (tx.originalText.contains("천재교과서") && tx.merchantName != merchantAliasPreferences.resolveAlias("(주)천재교과서", false)) {
                updated = updated.copy(
                    merchantName = merchantAliasPreferences.resolveAlias("(주)천재교과서", false),
                    category = ExpenseCategory.LIVING,
                    cardOrBankName = "신한카드",
                    paymentMethod = PaymentMethod.CREDIT_CARD
                )
                isModified = true
            } else if (!isCardMsg && tx.merchantName.matches(Regex("""^\d{6,}.*"""))) {
                val cleanName = if (tx.merchantName.contains("출금")) "출금 내역" else if (isDeposit) "입금 내역" else "계좌 이체"
                updated = updated.copy(
                    merchantName = merchantAliasPreferences.resolveAlias(cleanName, isDeposit),
                    category = if (isDeposit) ExpenseCategory.INCOME else ExpenseCategory.TRANSFER,
                    transferMemo = tx.merchantName
                )
                isModified = true
            }

            if (isModified) {
                transactionRepository.updateTransaction(updated)
            }
        }
    }

    private fun loadData() {
        dataLoadJob?.cancel()
        dataLoadJob = viewModelScope.launch {
            transactionRepository.getAllTransactionsFlow().collectLatest { list ->
                val cleanList = withContext(Dispatchers.Default) {
                    list.filterNot { tx ->
                        tx.id in deletedTxIdsInSession ||
                            expenseCustomMetaPreferences.isTransactionDeleted(tx)
                    }
                }
                _uiState.update { it.copy(allTransactions = cleanList) }
                recalculateFilteredTransactions(showLoading = _uiState.value.transactions.isEmpty() && _uiState.value.isLoading)
            }
        }
        rulesLoadJob?.cancel()
        rulesLoadJob = viewModelScope.launch {
            manageTransactionRulesUseCase.getAllRules().collectLatest { rules ->
                _uiState.update { it.copy(rules = rules) }
            }
        }
    }

    private var recalcJob: kotlinx.coroutines.Job? = null

    /**
     * 현재 화면에 표시 중인 월/기간 내역(state.transactions)만을 대상으로
     * 합계 및 카테고리·사용자 통계를 즉시(<0.5ms) 재계산합니다. (수정/삭제 낙관적 UI 즉시 반영용)
     */
    private fun recomputeCurrentMonthStats(state: ExpenseUiState): ExpenseUiState {
        val filtered = state.transactions
        val uClassMap = state.userClassByTxId
        val cCatMap = state.customCategoryByTxId
        val recurringIds = state.recurringTransactionIds

        val expenseOnly = filtered.filter { it.category != ExpenseCategory.INCOME && !it.isSelfTransfer() }
        val totalExp = expenseOnly.sumOf { it.amount }
        val totalInc = filtered.filter { it.category == ExpenseCategory.INCOME }.sumOf { it.amount }
        val recurringExp = expenseOnly.filter { it.id in recurringIds }.sumOf { it.amount }

        val etcBuiltInName = expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(ExpenseCategory.ETC)

        val byCat = expenseOnly.groupBy { it.category }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
            .filter { it.value > 0L }
            .toList()
            .sortedWith(
                compareBy<Pair<ExpenseCategory, Long>> { it.first == ExpenseCategory.ETC }
                    .thenByDescending { it.second }
            )
            .toMap()

        val byEffectiveCat = expenseOnly
            .groupBy { tx -> cCatMap[tx.id] ?: expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(tx.category) }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
            .filter { it.value > 0L }
            .toList()
            .sortedWith(
                compareBy<Pair<String, Long>> {
                    it.first == etcBuiltInName || it.first == ExpenseCategory.ETC.displayName
                }.thenByDescending { it.second }
            )
            .toMap()

        val rawByUser = expenseOnly
            .groupBy { tx -> uClassMap[tx.id]?.name ?: UNASSIGNED_USER_FILTER }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }

        val allKnownUsers = (state.userClassifications.map { it.name } + rawByUser.keys.filter { it != UNASSIGNED_USER_FILTER }).distinct()
        val sortedUserPairs = allKnownUsers
            .map { uName -> uName to (rawByUser[uName] ?: 0L) }
            .sortedByDescending { it.second }
            .toMutableList()

        val unassignedAmt = rawByUser[UNASSIGNED_USER_FILTER] ?: 0L
        if (unassignedAmt > 0L || sortedUserPairs.isEmpty()) {
            sortedUserPairs.add(UNASSIGNED_USER_FILTER to unassignedAmt)
        }
        val byUserClass = sortedUserPairs.toMap()

        return state.copy(
            totalExpense = totalExp,
            totalIncome = totalInc,
            categoryTotals = byCat,
            effectiveCategoryTotals = byEffectiveCat,
            userClassificationTotals = byUserClass,
            recurringExpenseTotal = recurringExp
        )
    }

    /**
     * 주어진 baseState를 기반으로 삭제 항목·네이버페이·중복 내역을 제거하고
     * 월간/기간 필터 및 카테고리·사용자 통계를 계산한 최신 ExpenseUiState를 반환합니다.
     */
    private fun computeFilteredUiState(baseState: ExpenseUiState): ExpenseUiState {
        var state = baseState
        val dedupedAll = state.allTransactions
            .asSequence()
            .filterNot { tx ->
                tx.id in deletedTxIdsInSession ||
                    expenseCustomMetaPreferences.isTransactionDeleted(tx) ||
                    tx.paymentMethod == PaymentMethod.NAVER_PAY
            }
            .map { tx ->
                if (expenseCustomMetaPreferences.isOneTimeEdited(tx)) {
                    val displayCardOrBank = expenseCustomMetaPreferences.resolveDisplayCardOrBankName(tx)
                    if (displayCardOrBank.isNotBlank() && displayCardOrBank != tx.cardOrBankName) {
                        tx.copy(cardOrBankName = displayCardOrBank)
                    } else {
                        tx
                    }
                } else {
                    val isDeposit = tx.isDepositTransaction()
                    val resolvedMerchant = merchantAliasPreferences.resolveAlias(tx.merchantName, isDeposit)
                        .ifBlank { tx.merchantName }
                    val resolvedCategory = expenseCustomMetaPreferences.resolveMerchantBaseCategory(resolvedMerchant, isDeposit)
                        ?: expenseCustomMetaPreferences.resolveMerchantBaseCategory(tx.merchantName, isDeposit)
                        ?: tx.category
                    val baseAdjusted = if (resolvedMerchant != tx.merchantName || resolvedCategory != tx.category) {
                        tx.copy(
                            merchantName = resolvedMerchant,
                            category = resolvedCategory
                        )
                    } else {
                        tx
                    }
                    val displayCardOrBank = expenseCustomMetaPreferences.resolveDisplayCardOrBankName(baseAdjusted)
                    if (displayCardOrBank.isNotBlank() && displayCardOrBank != baseAdjusted.cardOrBankName) {
                        baseAdjusted.copy(cardOrBankName = displayCardOrBank)
                    } else {
                        baseAdjusted
                    }
                }
            }
            .filterNot { adjustedTx ->
                expenseCustomMetaPreferences.isTransactionDeleted(adjustedTx) ||
                    com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                        merchantName = adjustedTx.merchantName,
                        cardOrBankName = adjustedTx.cardOrBankName
                    )
            }
            .distinctBy { tx ->
                val minuteStamp = "${tx.timestamp.year}_${tx.timestamp.monthValue}_${tx.timestamp.dayOfMonth}_${tx.timestamp.hour}_${tx.timestamp.minute}"
                val dirTag = if (tx.isDepositTransaction()) "IN" else "OUT"
                "${dirTag}_${minuteStamp}_${tx.amount}_${com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)}"
            }
            .toList()

        // [월 변경 자동 포커싱] 최초 진입 시 현재 월에 내역이 0건이고 이전 월에 거래 내역이 존재하면 가장 최근 거래 연월로 자동 포커싱
        if (!hasAutoFocusedMonth && dedupedAll.isNotEmpty() && state.filterMode == ExpenseDateFilterMode.MONTH) {
            hasAutoFocusedMonth = true
            val hasCurrentMonthTx = dedupedAll.any { YearMonth.from(it.timestamp.toLocalDate()) == state.selectedYearMonth }
            if (!hasCurrentMonthTx) {
                val latestYearMonth = dedupedAll.maxOf { YearMonth.from(it.timestamp.toLocalDate()) }
                state = state.copy(selectedYearMonth = latestYearMonth)
            }
        }

        // 지난달 동일 금액/날짜 자동 감지 + 사용자의 고정지출 수동/영구 지정 통합 산출
        val autoRecurringIds = RecurringExpenseDetector.detectRecurringTransactionIds(dedupedAll)
        val recurringIds = HashSet<Long>(autoRecurringIds.size + 16)

        val uClassMap = HashMap<Long, UserClassificationItem>(dedupedAll.size)
        val cCatMap = HashMap<Long, String>(dedupedAll.size / 2 + 1)
        val corpMap = HashMap<Long, Boolean>(dedupedAll.size / 4 + 1)
        val oneTimeSet = HashSet<Long>()

        for (tx in dedupedAll) {
            expenseCustomMetaPreferences.resolveUserClassification(tx)?.let { uClassMap[tx.id] = it }
            expenseCustomMetaPreferences.resolveCustomCategoryLabel(tx)?.let { cCatMap[tx.id] = it }
            if (expenseCustomMetaPreferences.isCorporateTransaction(tx)) {
                corpMap[tx.id] = true
            }
            if (expenseCustomMetaPreferences.isOneTimeEdited(tx)) {
                oneTimeSet.add(tx.id)
            }
            val isFixed = expenseCustomMetaPreferences.resolveIsFixedExpense(
                tx = tx,
                autoRecurringIds = autoRecurringIds
            )
            if (isFixed) {
                recurringIds.add(tx.id)
            }
        }

        val filtered = when (state.filterMode) {
            ExpenseDateFilterMode.MONTH -> {
                val ym = state.selectedYearMonth
                dedupedAll.filter { tx ->
                    tx.timestamp.year == ym.year && tx.timestamp.monthValue == ym.monthValue
                }
            }
            ExpenseDateFilterMode.CUSTOM_RANGE -> {
                val start = state.customStartDate
                val end = state.customEndDate
                dedupedAll.filter { tx ->
                    val date = tx.timestamp.toLocalDate()
                    !date.isBefore(start) && !date.isAfter(end)
                }
            }
        }

        val knownNames = (state.allTransactions.map { it.merchantName } + dedupedAll.map { it.merchantName }).distinct()
        val permMerchantRules = expenseCustomMetaPreferences.getPermanentMerchantRuleSummaries(
            aliasPreferences = merchantAliasPreferences,
            knownMerchantNames = knownNames
        )
        val cardCorpRules = expenseCustomMetaPreferences.getCardCorporateRules()

        return recomputeCurrentMonthStats(
            state.copy(
                selectedYearMonth = state.selectedYearMonth,
                transactions = filtered,
                recurringTransactionIds = recurringIds,
                userClassByTxId = uClassMap,
                customCategoryByTxId = cCatMap,
                corporateByTxId = corpMap,
                oneTimeEditedIds = oneTimeSet,
                permanentMerchantRules = permMerchantRules,
                cardCorporateRules = cardCorpRules
            )
        )
    }

    private fun recalculateFilteredTransactions(
        loadingTitle: String = "가계부 내역 조회 중",
        loadingMessage: String = "조건에 맞는 결제 내역과 통계를 불러오고 있습니다...",
        showLoading: Boolean = false
    ) {
        recalcJob?.cancel()
        if (showLoading) {
            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingTitle = loadingTitle,
                    loadingMessage = loadingMessage
                )
            }
        }
        recalcJob = viewModelScope.launch(Dispatchers.Default) {
            val snapshot = _uiState.value
            val computed = computeFilteredUiState(snapshot)
            _uiState.update { latest ->
                val resolved = if (
                    latest.allTransactions !== snapshot.allTransactions ||
                    latest.metaVersion != snapshot.metaVersion ||
                    latest.filterMode != snapshot.filterMode ||
                    latest.selectedYearMonth != snapshot.selectedYearMonth ||
                    latest.customStartDate != snapshot.customStartDate ||
                    latest.customEndDate != snapshot.customEndDate ||
                    latest.userClassifications != snapshot.userClassifications ||
                    latest.managedCategories != snapshot.managedCategories
                ) {
                    computeFilteredUiState(latest)
                } else {
                    latest.copy(
                        selectedYearMonth = computed.selectedYearMonth,
                        transactions = computed.transactions,
                        totalExpense = computed.totalExpense,
                        totalIncome = computed.totalIncome,
                        categoryTotals = computed.categoryTotals,
                        effectiveCategoryTotals = computed.effectiveCategoryTotals,
                        userClassificationTotals = computed.userClassificationTotals,
                        recurringTransactionIds = computed.recurringTransactionIds,
                        recurringExpenseTotal = computed.recurringExpenseTotal,
                        userClassByTxId = computed.userClassByTxId,
                        customCategoryByTxId = computed.customCategoryByTxId,
                        corporateByTxId = computed.corporateByTxId,
                        oneTimeEditedIds = computed.oneTimeEditedIds,
                        permanentMerchantRules = computed.permanentMerchantRules,
                        cardCorporateRules = computed.cardCorporateRules
                    )
                }
                resolved.copy(isLoading = false)
            }
        }
    }

    // 전월 이동
    fun previousMonth() {
        val current = _uiState.value
        val targetMonth = current.selectedYearMonth.minusMonths(1)
        _uiState.value = current.copy(
            filterMode = ExpenseDateFilterMode.MONTH,
            selectedYearMonth = targetMonth
        )
        recalculateFilteredTransactions(
            loadingTitle = "${targetMonth.year}년 ${targetMonth.monthValue}월 내역 조회 중",
            loadingMessage = "해당 월의 결제 내역과 카테고리 통계를 집계하고 있습니다..."
        )
    }

    // 다음월 이동
    fun nextMonth() {
        val current = _uiState.value
        val targetMonth = current.selectedYearMonth.plusMonths(1)
        _uiState.value = current.copy(
            filterMode = ExpenseDateFilterMode.MONTH,
            selectedYearMonth = targetMonth
        )
        recalculateFilteredTransactions(
            loadingTitle = "${targetMonth.year}년 ${targetMonth.monthValue}월 내역 조회 중",
            loadingMessage = "해당 월의 결제 내역과 카테고리 통계를 집계하고 있습니다..."
        )
    }

    // 특정 월 선택
    fun selectYearMonth(yearMonth: YearMonth) {
        _uiState.value = _uiState.value.copy(
            filterMode = ExpenseDateFilterMode.MONTH,
            selectedYearMonth = yearMonth,
            isDateRangePickerOpen = false
        )
        recalculateFilteredTransactions(
            loadingTitle = "${yearMonth.year}년 ${yearMonth.monthValue}월 내역 조회 중",
            loadingMessage = "선택하신 월의 결제 내역을 불러오고 있습니다..."
        )
    }

    // 기간 직접 지정
    fun setCustomDateRange(startDate: LocalDate, endDate: LocalDate) {
        _uiState.value = _uiState.value.copy(
            filterMode = ExpenseDateFilterMode.CUSTOM_RANGE,
            customStartDate = startDate,
            customEndDate = endDate,
            isDateRangePickerOpen = false
        )
        recalculateFilteredTransactions(
            loadingTitle = "지정 기간 내역 조회 중",
            loadingMessage = "$startDate ~ $endDate 기간의 결제 내역을 분석하고 있습니다..."
        )
    }

    // 빠른 프리셋 선택 (이번 달, 지난 달, 최근 3개월, 올해 전체)
    fun selectQuickPreset(preset: String) {
        val today = LocalDate.now()
        when (preset) {
            "이번 달" -> {
                _uiState.value = _uiState.value.copy(
                    filterMode = ExpenseDateFilterMode.MONTH,
                    selectedYearMonth = YearMonth.now(),
                    isDateRangePickerOpen = false
                )
            }
            "지난 달" -> {
                _uiState.value = _uiState.value.copy(
                    filterMode = ExpenseDateFilterMode.MONTH,
                    selectedYearMonth = YearMonth.now().minusMonths(1),
                    isDateRangePickerOpen = false
                )
            }
            "최근 3개월" -> {
                _uiState.value = _uiState.value.copy(
                    filterMode = ExpenseDateFilterMode.CUSTOM_RANGE,
                    customStartDate = today.minusMonths(3).withDayOfMonth(1),
                    customEndDate = today,
                    isDateRangePickerOpen = false
                )
            }
            "올해 전체" -> {
                _uiState.value = _uiState.value.copy(
                    filterMode = ExpenseDateFilterMode.CUSTOM_RANGE,
                    customStartDate = today.withDayOfYear(1),
                    customEndDate = today,
                    isDateRangePickerOpen = false
                )
            }
        }
        recalculateFilteredTransactions(
            loadingTitle = "$preset 내역 조회 중",
            loadingMessage = "선택하신 기간($preset)의 결제 내역과 통계를 불러오고 있습니다..."
        )
    }

    fun openDateRangeDialog() {
        _uiState.value = _uiState.value.copy(isDateRangePickerOpen = true)
    }

    fun closeDateRangeDialog() {
        _uiState.value = _uiState.value.copy(isDateRangePickerOpen = false)
    }


    fun openAddDialog() {
        _uiState.value = _uiState.value.copy(isAddDialogOpen = true)
    }

    fun closeAddDialog() {
        _uiState.value = _uiState.value.copy(isAddDialogOpen = false)
    }

    fun openEditDialog(transaction: Transaction) {
        _uiState.value = _uiState.value.copy(editingTransaction = transaction)
    }

    fun closeEditDialog() {
        _uiState.update { it.copy(editingTransaction = null, isSaving = false) }
    }

    fun addManualTransaction(
        merchantName: String,
        amount: Long,
        category: ExpenseCategory,
        cardOrBankName: String,
        memo: String?,
        isCorporate: Boolean = false,
        customCategoryName: String? = null,
        userClassificationName: String? = null,
        isFixedExpense: Boolean = false
    ) {
        if (_uiState.value.isSaving) return
        if (com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                merchantName = merchantName,
                cardOrBankName = cardOrBankName,
                originalText = memo ?: ""
            )
        ) {
            _uiState.update {
                it.copy(
                    isSaving = false,
                    isAddDialogOpen = false,
                    syncResultMessage = "'네이버페이' 항목은 이력 등록 제외 대상입니다."
                )
            }
            return
        }
        _uiState.update { it.copy(isSaving = false, isAddDialogOpen = false) }
        viewModelScope.launch(Dispatchers.IO) {
            val isCustomFuel = customCategoryName?.let { it.contains("주유") || it.contains("충전") } == true
            val isCustomMaint = customCategoryName?.let { it.contains("정비") || it.contains("세차") } == true
            val isFuel = category == ExpenseCategory.FUEL || isCustomFuel ||
                (category != ExpenseCategory.CAR_MAINTENANCE && !isCustomMaint &&
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(merchantName))
            val isMaintenance = category == ExpenseCategory.CAR_MAINTENANCE || isCustomMaint ||
                (!isFuel && com.autologue.app.data.repository.VehicleRepositoryImpl.isMaintenanceMerchant(merchantName))
            val finalCategory = when {
                isFuel -> ExpenseCategory.FUEL
                isMaintenance -> ExpenseCategory.CAR_MAINTENANCE
                else -> category
            }
            val isBankTx = finalCategory == ExpenseCategory.TRANSFER || finalCategory == ExpenseCategory.INCOME
            val effectiveCardOrBank = if (isBankTx) {
                com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName(cardOrBankName)
                    ?: cardOrBankName.ifBlank { "NH농협은행" }
            } else {
                expenseCustomMetaPreferences.formatCardDesignation(
                    cardOrBankName.ifBlank { "직접 입력" },
                    isCorporate
                )
            }

            val tx = Transaction(
                amount = amount,
                merchantName = merchantName,
                originalText = "[직접 입력] $merchantName %,d원".format(amount),
                timestamp = LocalDateTime.now(),
                paymentMethod = when {
                    isBankTx -> PaymentMethod.BANK_TRANSFER
                    effectiveCardOrBank.contains("체크") -> PaymentMethod.CHECK_CARD
                    else -> PaymentMethod.CREDIT_CARD
                },
                category = finalCategory,
                cardOrBankName = effectiveCardOrBank,
                transferMemo = memo?.ifBlank { null },
                isAutoCategorized = false
            )
            expenseCustomMetaPreferences.runBatchUpdate {
                expenseCustomMetaPreferences.unmarkManualTransactionDeleted(tx)
            }
            val insertedId = transactionRepository.insertTransaction(tx)
            if (insertedId > 0L) {
                expenseCustomMetaPreferences.runBatchUpdate {
                    expenseCustomMetaPreferences.setTransactionCardDesignation(
                        txId = insertedId,
                        merchantName = merchantName,
                        cardCompany = effectiveCardOrBank,
                        isCorporate = if (isBankTx) false else isCorporate,
                        applyToMerchantRule = false,
                        isDeposit = finalCategory == ExpenseCategory.INCOME,
                        isBankAccountTx = isBankTx
                    )
                    if (!customCategoryName.isNullOrBlank()) {
                        expenseCustomMetaPreferences.assignCustomCategory(
                            txId = insertedId,
                            merchantName = merchantName,
                            customCategoryName = customCategoryName,
                            applyToMerchantRule = false,
                            isDeposit = finalCategory == ExpenseCategory.INCOME
                        )
                    }
                    if (!userClassificationName.isNullOrBlank()) {
                        expenseCustomMetaPreferences.assignUserClassification(
                            txId = insertedId,
                            merchantName = merchantName,
                            userClassificationName = userClassificationName,
                            applyToMerchantRule = false,
                            isDeposit = finalCategory == ExpenseCategory.INCOME
                        )
                    }
                    if (isFixedExpense) {
                        expenseCustomMetaPreferences.setFixedExpense(
                            txId = insertedId,
                            merchantName = merchantName,
                            isFixed = true,
                            applyToMerchantRule = false,
                            isDeposit = finalCategory == ExpenseCategory.INCOME
                        )
                    }
                }
            }

            if ((isFuel || isMaintenance) && amount > 0) {
                val targetLogType = if (isMaintenance && !isFuel) {
                    com.autologue.app.domain.model.VehicleLogType.MAINTENANCE
                } else {
                    com.autologue.app.domain.model.VehicleLogType.REFUELING
                }
                val daysSince = if (targetLogType == com.autologue.app.domain.model.VehicleLogType.REFUELING) {
                    val lastFuel = vehicleRepository.getLatestRefuelingLog()
                    if (lastFuel != null) {
                        java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), tx.timestamp.toLocalDate()).toInt()
                    } else null
                } else null

                val liters = if (targetLogType == com.autologue.app.domain.model.VehicleLogType.REFUELING) {
                    String.format(java.util.Locale.US, "%.1f", tx.amount / 1650.0).toDouble()
                } else 0.0

                val vehicleLog = com.autologue.app.domain.model.VehicleLog(
                    timestamp = tx.timestamp,
                    logType = targetLogType,
                    fuelCost = tx.amount,
                    fuelAmountLiters = liters,
                    daysSinceLastFuel = daysSince,
                    gasStationName = tx.merchantName,
                    note = "가계부 직접 입력 연동"
                )
                vehicleRepository.insertVehicleLog(vehicleLog)
            }
        }
    }

    private val accountTokenRegex = Regex("""\d{3,6}-[0-9*-]{3,}|\d{10,14}""")
    private val genericMerchantPlaceholders = setOf("출금 내역", "계좌 이체", "입금 내역", "카드 가맹점", "가맹점", "직접 추가 지출")

    /**
     * 영구저장 대상 판별:
     * 금액(amount)과 일시(timestamp)는 동일 항목 판별 기준에서 완전히 제외하고,
     * 동일 입금/출금 방향 내에서 동일 결제처(원문 가맹점명·치환 별칭·계좌번호 토큰 포함)에 해당하는
     * 기존 사용내역 목록을 반환합니다.
     */
    fun findMatchingExistingTransactions(
        targetTx: Transaction,
        editedMerchantName: String? = null
    ): List<Transaction> {
        val isDeposit = targetTx.isDepositTransaction()
        val oldMerchantName = targetTx.merchantName.trim()
        val newMerchant = editedMerchantName?.trim().orEmpty()

        val isManual = targetTx.originalText.startsWith("[직접 입력]") || targetTx.originalText.startsWith("[차계부 직접 입력]")
        val rawParsedName = if (!isManual && targetTx.originalText.isNotBlank()) {
            com.autologue.app.data.parser.SmsParser.parse(targetTx.cardOrBankName, targetTx.originalText, targetTx.timestamp)?.merchantName
                ?: com.autologue.app.data.parser.SmsParser.parse(null, targetTx.originalText, targetTx.timestamp)?.merchantName
        } else null

        val candidateKeys = LinkedHashSet<String>()
        if (oldMerchantName.isNotBlank() && oldMerchantName !in genericMerchantPlaceholders) {
            candidateKeys.add(oldMerchantName)
        }
        if (newMerchant.isNotBlank() && newMerchant !in genericMerchantPlaceholders) {
            candidateKeys.add(newMerchant)
        }
        if (!rawParsedName.isNullOrBlank() && rawParsedName !in genericMerchantPlaceholders) {
            candidateKeys.add(rawParsedName)
        }
        val aliasedOld = merchantAliasPreferences.resolveAlias(oldMerchantName, isDeposit)
        if (aliasedOld.isNotBlank() && aliasedOld !in genericMerchantPlaceholders) {
            candidateKeys.add(aliasedOld)
        }
        merchantAliasPreferences.getOriginalNamesForTarget(oldMerchantName, isDeposit).forEach {
            if (it.isNotBlank() && it !in genericMerchantPlaceholders) candidateKeys.add(it)
        }
        if (newMerchant.isNotBlank()) {
            merchantAliasPreferences.getOriginalNamesForTarget(newMerchant, isDeposit).forEach {
                if (it.isNotBlank() && it !in genericMerchantPlaceholders) candidateKeys.add(it)
            }
        }

        val candidateNormKeys = candidateKeys
            .map { com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(it) }
            .filter { it.isNotEmpty() }
            .toHashSet()

        val targetAccountToken = accountTokenRegex.find(targetTx.originalText)?.value
            ?: targetTx.transferMemo?.let { accountTokenRegex.find(it)?.value }

        return _uiState.value.allTransactions.filter { other ->
            if (other.id == targetTx.id) return@filter false
            if (other.isDepositTransaction() != isDeposit) return@filter false
            if (expenseCustomMetaPreferences.isOneTimeEdited(other)) return@filter false

            if (candidateNormKeys.isNotEmpty()) {
                val otherMerchant = other.merchantName.trim()
                val otherNorm = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(otherMerchant)
                if (otherNorm in candidateNormKeys) return@filter true

                val otherAliased = merchantAliasPreferences.resolveAlias(otherMerchant, isDeposit)
                val otherAliasedNorm = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(otherAliased)
                if (otherAliasedNorm in candidateNormKeys) return@filter true

                if (otherMerchant in genericMerchantPlaceholders && other.originalText.isNotBlank()) {
                    val otherManual = other.originalText.startsWith("[직접 입력]") || other.originalText.startsWith("[차계부 직접 입력]")
                    if (!otherManual && candidateKeys.any { key -> other.originalText.contains(key, ignoreCase = true) }) {
                        return@filter true
                    }
                }
                false
            } else {
                if (!targetAccountToken.isNullOrBlank()) {
                    val otherToken = accountTokenRegex.find(other.originalText)?.value
                        ?: other.transferMemo?.let { accountTokenRegex.find(it)?.value }
                    if (otherToken == targetAccountToken) return@filter true
                }
                merchantAliasPreferences.isSameMerchant(other.merchantName, oldMerchantName) &&
                    other.cardOrBankName == targetTx.cardOrBankName
            }
        }
    }

    fun updateTransactionDetails(
        transactionId: Long,
        newMerchantName: String = "",
        category: ExpenseCategory,
        memo: String?,
        makeGlobalRule: Boolean = false,
        merchantKeyword: String? = null,
        cardCompany: String? = null,
        isCorporate: Boolean = false,
        customCategoryName: String? = null,
        userClassificationName: String? = null,
        isOneTimeSave: Boolean = false,
        isFixedExpense: Boolean? = null,
        permanentSaveOptions: com.autologue.app.data.preferences.PermanentSaveOptions = com.autologue.app.data.preferences.PermanentSaveOptions()
    ) {
        val snapshot = _uiState.value
        val currentTx = snapshot.allTransactions.find { it.id == transactionId }
            ?: snapshot.transactions.find { it.id == transactionId }
            ?: snapshot.editingTransaction?.takeIf { it.id == transactionId }
            ?: return

        val isDeposit = currentTx.isDepositTransaction()
        val oldMerchantName = currentTx.merchantName.trim()
        val finalMerchantName = newMerchantName.trim().ifBlank { oldMerchantName }

        val applyMerchantRule = !isOneTimeSave && permanentSaveOptions.applyMerchantName
        val applyCategoryRule = !isOneTimeSave && permanentSaveOptions.applyCategory
        val applyUserClassRule = !isOneTimeSave && permanentSaveOptions.applyUserClassification
        val applyCardAndCorpRule = !isOneTimeSave && permanentSaveOptions.applyCardAndCorporate
        val applyFixedRule = !isOneTimeSave && permanentSaveOptions.applyFixedExpense
        val applyMemoRule = !isOneTimeSave && permanentSaveOptions.applyMemo

        val wasFuel = currentTx.category == ExpenseCategory.FUEL ||
            com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(oldMerchantName)
        val wasMaint = currentTx.category == ExpenseCategory.CAR_MAINTENANCE ||
            com.autologue.app.data.repository.VehicleRepositoryImpl.isMaintenanceMerchant(oldMerchantName)
        val wasVehicleCategory = wasFuel || wasMaint

        val isCustomFuel = customCategoryName?.let { it.contains("주유") || it.contains("충전") } == true
        val isCustomMaint = customCategoryName?.let { it.contains("정비") || it.contains("세차") } == true

        val isNowFuel = category == ExpenseCategory.FUEL || isCustomFuel ||
            (category == currentTx.category && !isCustomMaint && wasFuel) ||
            (category == currentTx.category && !isCustomMaint &&
                com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(finalMerchantName))
        val isNowMaint = category == ExpenseCategory.CAR_MAINTENANCE || isCustomMaint ||
            (category == currentTx.category && !isNowFuel && wasMaint) ||
            (category == currentTx.category && !isNowFuel &&
                com.autologue.app.data.repository.VehicleRepositoryImpl.isMaintenanceMerchant(finalMerchantName))

        val resolvedCategory = when {
            category == ExpenseCategory.FUEL || isCustomFuel -> ExpenseCategory.FUEL
            category == ExpenseCategory.CAR_MAINTENANCE || isCustomMaint -> ExpenseCategory.CAR_MAINTENANCE
            isNowFuel && category == currentTx.category -> ExpenseCategory.FUEL
            isNowMaint && category == currentTx.category -> ExpenseCategory.CAR_MAINTENANCE
            else -> category
        }

        val isBankTx = currentTx.isBankAccountTransaction(resolvedCategory)
        val effectiveCorporate = if (isBankTx) false else isCorporate
        val optimisticCard = if (cardCompany != null) {
            if (isBankTx) {
                ExpenseCustomMetaPreferences.inferCanonicalBankName(cardCompany)
                    ?: cardCompany.trim().ifBlank { currentTx.cardOrBankName }
            } else {
                expenseCustomMetaPreferences.formatCardDesignation(cardCompany, effectiveCorporate)
            }
        } else {
            currentTx.cardOrBankName
        }

        val optimisticUpdatedTx = currentTx.copy(
            merchantName = finalMerchantName,
            category = resolvedCategory,
            cardOrBankName = optimisticCard,
            transferMemo = memo?.ifBlank { null }
        )

        val selectedExistingSet = if (!isOneTimeSave) {
            permanentSaveOptions.selectedExistingTxIds.orEmpty()
        } else {
            emptySet()
        }

        // [0ms 낙관적 UI 즉시 반영] 모달을 즉시 닫고 화면의 리스트 및 통계를 프레임 드랍 없이 바로 갱신
        _uiState.update { state ->
            val targetIdsToUpdate = selectedExistingSet + transactionId
            fun transformTx(tx: Transaction): Transaction {
                if (tx.id == transactionId) return optimisticUpdatedTx
                if (!isOneTimeSave && tx.id in selectedExistingSet) {
                    return tx.copy(
                        merchantName = if (applyMerchantRule) finalMerchantName else tx.merchantName,
                        category = if (applyCategoryRule) resolvedCategory else tx.category,
                        cardOrBankName = if (applyCardAndCorpRule) optimisticCard else tx.cardOrBankName,
                        transferMemo = if (applyMemoRule) memo?.ifBlank { null } else tx.transferMemo
                    )
                }
                return tx
            }

            val nextTransactions = state.transactions.map(::transformTx)
            val nextAllTransactions = state.allTransactions.map(::transformTx)

            val nextCustomCatMap = state.customCategoryByTxId.toMutableMap()
            val cleanCustomCat = customCategoryName?.trim().orEmpty()
            val catTargetIds = if (applyCategoryRule) targetIdsToUpdate else setOf(transactionId)
            for (id in catTargetIds) {
                if (cleanCustomCat.isNotBlank() && resolvedCategory == ExpenseCategory.ETC) {
                    nextCustomCatMap[id] = cleanCustomCat
                } else {
                    nextCustomCatMap.remove(id)
                }
            }

            val nextUserClassMap = state.userClassByTxId.toMutableMap()
            val cleanUserName = userClassificationName?.trim().orEmpty()
            val matchedUc = if (cleanUserName.isNotBlank()) {
                state.userClassifications.firstOrNull { it.name.equals(cleanUserName, ignoreCase = true) }
                    ?: UserClassificationItem(name = cleanUserName, colorIndex = state.userClassifications.size)
            } else null
            val userTargetIds = if (applyUserClassRule) targetIdsToUpdate else setOf(transactionId)
            for (id in userTargetIds) {
                if (matchedUc != null) {
                    nextUserClassMap[id] = matchedUc
                } else {
                    nextUserClassMap.remove(id)
                }
            }

            val nextCorpMap = state.corporateByTxId.toMutableMap()
            val corpTargetIds = if (applyCardAndCorpRule) targetIdsToUpdate else setOf(transactionId)
            for (id in corpTargetIds) {
                if (effectiveCorporate) {
                    nextCorpMap[id] = true
                } else {
                    nextCorpMap.remove(id)
                }
            }

            val nextRecurringIds = state.recurringTransactionIds.toMutableSet()
            if (isFixedExpense != null) {
                val fixedTargetIds = if (applyFixedRule) targetIdsToUpdate else setOf(transactionId)
                for (id in fixedTargetIds) {
                    if (isFixedExpense) nextRecurringIds.add(id) else nextRecurringIds.remove(id)
                }
            }

            val nextOneTimeIds = state.oneTimeEditedIds.toMutableSet()
            if (isOneTimeSave) {
                nextOneTimeIds.add(transactionId)
            } else {
                nextOneTimeIds.removeAll(targetIdsToUpdate)
            }

            recomputeCurrentMonthStats(
                state.copy(
                    editingTransaction = null,
                    isSaving = false,
                    transactions = nextTransactions,
                    allTransactions = nextAllTransactions,
                    customCategoryByTxId = nextCustomCatMap,
                    userClassByTxId = nextUserClassMap,
                    corporateByTxId = nextCorpMap,
                    recurringTransactionIds = nextRecurringIds,
                    oneTimeEditedIds = nextOneTimeIds
                )
            )
        }

        // 백그라운드에서 배치(Batch) 저장 및 DB 일괄 반영 수행
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val isManual = currentTx.originalText.startsWith("[직접 입력]") || currentTx.originalText.startsWith("[차계부 직접 입력]")
                val rawParsedName = if (!isManual && currentTx.originalText.isNotBlank()) {
                    com.autologue.app.data.parser.SmsParser.parse(currentTx.cardOrBankName, currentTx.originalText, currentTx.timestamp)?.merchantName
                        ?: com.autologue.app.data.parser.SmsParser.parse(null, currentTx.originalText, currentTx.timestamp)?.merchantName
                } else null

                val effectiveOldKey = if (oldMerchantName !in genericMerchantPlaceholders) {
                    oldMerchantName
                } else {
                    rawParsedName?.takeIf { it.isNotBlank() && it !in genericMerchantPlaceholders } ?: ""
                }
                val effectiveRawKey = rawParsedName?.takeIf { it.isNotBlank() && it !in genericMerchantPlaceholders }

                val targetExistingTxs: List<Transaction> = if (isOneTimeSave) {
                    emptyList()
                } else if (permanentSaveOptions.selectedExistingTxIds != null) {
                    val selectedIds = permanentSaveOptions.selectedExistingTxIds
                    snapshot.allTransactions.filter { it.id != currentTx.id && it.id in selectedIds }
                } else {
                    findMatchingExistingTransactions(currentTx, finalMerchantName)
                }
                val targetExistingIds: Set<Long> = targetExistingTxs.map { it.id }.toSet()

                val allRuleMerchantNames = LinkedHashSet<String>()
                if (finalMerchantName.isNotBlank()) allRuleMerchantNames.add(finalMerchantName)
                if (oldMerchantName.isNotBlank()) allRuleMerchantNames.add(oldMerchantName)
                if (!effectiveRawKey.isNullOrBlank()) allRuleMerchantNames.add(effectiveRawKey)
                merchantAliasPreferences.getOriginalNamesForTarget(oldMerchantName, isDeposit).forEach {
                    if (it.isNotBlank()) allRuleMerchantNames.add(it)
                }
                merchantAliasPreferences.getOriginalNamesForTarget(finalMerchantName, isDeposit).forEach {
                    if (it.isNotBlank()) allRuleMerchantNames.add(it)
                }
                for (exTx in targetExistingTxs) {
                    val exName = exTx.merchantName.trim()
                    if (exName.isNotBlank() && exName !in genericMerchantPlaceholders) {
                        allRuleMerchantNames.add(exName)
                    }
                }
                val extraRuleKeysArray = allRuleMerchantNames.toTypedArray()

                // 메타데이터 변경을 단일 배치(runBatchUpdate)로 묶어 JSON 직렬화 및 Flow 발행을 단 1회로 압축
                var formattedCard = optimisticCard
                val updatedTx = expenseCustomMetaPreferences.runBatchUpdate {
                    formattedCard = if (cardCompany != null) {
                        expenseCustomMetaPreferences.setTransactionCardDesignation(
                            txId = transactionId,
                            merchantName = finalMerchantName,
                            cardCompany = cardCompany,
                            isCorporate = effectiveCorporate,
                            applyToMerchantRule = applyCardAndCorpRule,
                            isDeposit = isDeposit,
                            extraMerchantNames = extraRuleKeysArray,
                            isBankAccountTx = isBankTx,
                            rawCardOrBankName = currentTx.cardOrBankName,
                            originalText = currentTx.originalText,
                            targetTxIds = if (applyCardAndCorpRule) targetExistingIds else emptySet()
                        )
                    } else {
                        expenseCustomMetaPreferences.resolveDisplayCardOrBankName(currentTx).ifBlank { currentTx.cardOrBankName }
                    }

                    expenseCustomMetaPreferences.assignBaseCategory(
                        merchantName = finalMerchantName,
                        category = resolvedCategory,
                        applyToMerchantRule = applyCategoryRule,
                        isDeposit = isDeposit,
                        extraMerchantNames = extraRuleKeysArray
                    )

                    expenseCustomMetaPreferences.assignCustomCategory(
                        txId = transactionId,
                        merchantName = finalMerchantName,
                        customCategoryName = customCategoryName,
                        applyToMerchantRule = applyCategoryRule,
                        isDeposit = isDeposit,
                        extraMerchantNames = extraRuleKeysArray,
                        targetTxIds = if (applyCategoryRule) targetExistingIds else emptySet()
                    )
                    expenseCustomMetaPreferences.assignUserClassification(
                        txId = transactionId,
                        merchantName = finalMerchantName,
                        userClassificationName = userClassificationName,
                        applyToMerchantRule = applyUserClassRule,
                        isDeposit = isDeposit,
                        extraMerchantNames = extraRuleKeysArray,
                        targetTxIds = if (applyUserClassRule) targetExistingIds else emptySet()
                    )

                    if (isFixedExpense != null) {
                        expenseCustomMetaPreferences.setFixedExpense(
                            txId = transactionId,
                            merchantName = finalMerchantName,
                            isFixed = isFixedExpense,
                            applyToMerchantRule = applyFixedRule,
                            isDeposit = isDeposit,
                            extraMerchantNames = extraRuleKeysArray,
                            targetTxIds = if (applyFixedRule) targetExistingIds else emptySet()
                        )
                    }

                    val txToSave = currentTx.copy(
                        merchantName = finalMerchantName,
                        category = resolvedCategory,
                        cardOrBankName = formattedCard,
                        transferMemo = memo?.ifBlank { null }
                    )

                    if (isOneTimeSave) {
                        expenseCustomMetaPreferences.markOneTimeEdited(txToSave)
                    } else {
                        expenseCustomMetaPreferences.clearOneTimeEdited(txToSave)
                        for (exTx in targetExistingTxs) {
                            expenseCustomMetaPreferences.clearOneTimeEdited(exTx)
                        }
                    }
                    txToSave
                }

                val txsToBatchUpdate = ArrayList<Transaction>(targetExistingTxs.size + 1)
                txsToBatchUpdate.add(updatedTx)

                var globalRenamedCount = 0
                if (!isOneTimeSave) {
                    for (exTx in targetExistingTxs) {
                        val exWasVehicle = exTx.category == ExpenseCategory.FUEL || exTx.category == ExpenseCategory.CAR_MAINTENANCE
                        val exNewMerchant = if (applyMerchantRule) finalMerchantName else exTx.merchantName
                        val exNewCategory = if (applyCategoryRule) resolvedCategory else exTx.category
                        val exNewCard = if (applyCardAndCorpRule) formattedCard else exTx.cardOrBankName
                        val exNewMemo = if (applyMemoRule) memo?.ifBlank { null } else exTx.transferMemo

                        if (applyMerchantRule && exTx.merchantName.isNotBlank() &&
                            !exTx.merchantName.equals(finalMerchantName, ignoreCase = true)
                        ) {
                            merchantAliasPreferences.registerAlias(
                                oldName = exTx.merchantName,
                                newName = finalMerchantName,
                                rawOriginalName = null,
                                isDeposit = isDeposit
                            )
                        }

                        val updatedEx = exTx.copy(
                            merchantName = exNewMerchant,
                            category = exNewCategory,
                            cardOrBankName = exNewCard,
                            transferMemo = exNewMemo
                        )
                        txsToBatchUpdate.add(updatedEx)
                        globalRenamedCount++

                        val exIsNowVehicle = exNewCategory == ExpenseCategory.FUEL || exNewCategory == ExpenseCategory.CAR_MAINTENANCE
                        if (exWasVehicle && !exIsNowVehicle) {
                            vehicleRepository.deleteRefuelingLogByTransaction(
                                amount = exTx.amount,
                                date = exTx.timestamp.toLocalDate(),
                                merchantName = exTx.merchantName
                            )
                        }
                    }
                }

                // 단일 Room 트랜잭션으로 일괄 업데이트
                transactionRepository.updateTransactions(txsToBatchUpdate)

                if (!isOneTimeSave) {
                    val targetKeyForGlobal = effectiveOldKey.ifBlank { finalMerchantName }
                    if (targetKeyForGlobal.isNotBlank() && (applyMerchantRule || applyCategoryRule || applyCardAndCorpRule)) {
                        val txRenamed = transactionRepository.renameMerchantGlobally(
                            oldMerchantName = targetKeyForGlobal,
                            newMerchantName = if (applyMerchantRule) finalMerchantName else targetKeyForGlobal,
                            rawOriginalMerchantName = effectiveRawKey,
                            isDeposit = isDeposit,
                            targetCategory = if (applyCategoryRule) resolvedCategory else null,
                            targetCardOrBankName = if (applyCardAndCorpRule) formattedCard else null
                        )
                        val fuelRenamed = if (!isDeposit && applyMerchantRule && targetKeyForGlobal.isNotBlank()) {
                            vehicleRepository.renameGasStationGlobally(
                                oldStationName = targetKeyForGlobal,
                                newStationName = finalMerchantName,
                                rawOriginalStationName = effectiveRawKey
                            )
                        } else 0
                        if (txRenamed + fuelRenamed > globalRenamedCount) {
                            globalRenamedCount = txRenamed + fuelRenamed
                        }
                    }
                }

                val isNowVehicleCategory = updatedTx.category == ExpenseCategory.FUEL ||
                    updatedTx.category == ExpenseCategory.CAR_MAINTENANCE
                if (isNowVehicleCategory && updatedTx.amount > 0) {
                    val targetLogType = if (updatedTx.category == ExpenseCategory.CAR_MAINTENANCE) {
                        com.autologue.app.domain.model.VehicleLogType.MAINTENANCE
                    } else {
                        com.autologue.app.domain.model.VehicleLogType.REFUELING
                    }
                    val daysSince = if (targetLogType == com.autologue.app.domain.model.VehicleLogType.REFUELING) {
                        val lastFuel = vehicleRepository.getLatestRefuelingLog()
                        if (lastFuel != null) {
                            java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), updatedTx.timestamp.toLocalDate()).toInt()
                        } else null
                    } else null

                    val liters = if (targetLogType == com.autologue.app.domain.model.VehicleLogType.REFUELING) {
                        String.format(java.util.Locale.US, "%.1f", updatedTx.amount / 1650.0).toDouble()
                    } else 0.0

                    val noteTag = if (isOneTimeSave) {
                        "가계부 지출 카테고리 변경 연동 [custom_name:true] [one_time_edit:true]"
                    } else {
                        "가계부 지출 카테고리 변경 연동 [custom_name:true]"
                    }
                    val vehicleLog = com.autologue.app.domain.model.VehicleLog(
                        timestamp = updatedTx.timestamp,
                        logType = targetLogType,
                        fuelCost = updatedTx.amount,
                        fuelAmountLiters = liters,
                        daysSinceLastFuel = daysSince,
                        gasStationName = updatedTx.merchantName,
                        note = noteTag
                    )
                    vehicleRepository.insertVehicleLog(vehicleLog)
                    val allTxs = transactionRepository.getAllTransactionsFlow().first()
                    vehicleRepository.syncRefuelingFromTransactions(allTxs)
                } else if (wasVehicleCategory && !isNowVehicleCategory) {
                    vehicleRepository.deleteRefuelingLogByTransaction(
                        amount = currentTx.amount,
                        date = currentTx.timestamp.toLocalDate(),
                        merchantName = oldMerchantName
                    )
                }

                if (!isOneTimeSave && makeGlobalRule && !merchantKeyword.isNullOrBlank()) {
                    manageTransactionRulesUseCase.addRule(
                        TransactionRule(
                            keywordPattern = merchantKeyword.trim(),
                            targetCategory = updatedTx.category
                        )
                    )
                }

                val dirLabel = if (isDeposit) "입금" else "출금"
                val resultMessage = if (isOneTimeSave) {
                    "'$finalMerchantName' [$dirLabel] 내역 1건을 1회성으로 저장했습니다. (동일 결제처의 다른 내역은 변경되지 않습니다)"
                } else {
                    val appliedFields = buildList {
                        if (applyMerchantRule) add("결제명")
                        if (applyCategoryRule) add("카테고리")
                        if (applyUserClassRule) add("사용자분류")
                        if (applyCardAndCorpRule) add("카드/은행·법인")
                        if (applyFixedRule) add("고정지출")
                        if (applyMemoRule) add("메모")
                    }.joinToString(", ")
                    val extraMsg = if (globalRenamedCount > 0) " (기존 사용내역 ${globalRenamedCount}건 함께 반영 완료)" else ""
                    "'$finalMerchantName' [$dirLabel] 영구저장 완료! [적용 항목: $appliedFields]$extraMsg"
                }

                _uiState.update { it.copy(syncResultMessage = resultMessage) }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        syncResultMessage = "저장 중 오류가 발생했습니다: ${e.localizedMessage ?: "알 수 없는 오류"}"
                    )
                }
            }
        }
    }

    /**
     * 삭제 대상 트랜잭션과 동일 일자·동일 금액·동일 입출금 방향·동일(또는 별칭 치환) 가맹점명을 가진
     * 쌍둥이/중복 레코드를 모두 찾아 반환합니다. (.distinctBy 뒤에 숨어 있던 중복 레코드가 부활하는 현상 방지)
     */
    private fun findTwinTransactionsForDeletion(
        targetTx: Transaction,
        sourceList: List<Transaction>
    ): List<Transaction> {
        val targetDate = targetTx.timestamp.toLocalDate()
        val targetIsDeposit = targetTx.isDepositTransaction()
        val targetAliased = merchantAliasPreferences.resolveAlias(targetTx.merchantName, targetIsDeposit)
            .ifBlank { targetTx.merchantName }
        val targetNormRaw = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(targetTx.merchantName)
        val targetNormAliased = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(targetAliased)
        val targetMinute = "${targetTx.timestamp.year}_${targetTx.timestamp.monthValue}_${targetTx.timestamp.dayOfMonth}_${targetTx.timestamp.hour}_${targetTx.timestamp.minute}"

        return sourceList.filter { other ->
            if (other.id == targetTx.id) return@filter true
            if (other.amount != targetTx.amount) return@filter false
            if (other.isDepositTransaction() != targetIsDeposit) return@filter false
            if (other.timestamp.toLocalDate() != targetDate) return@filter false

            val otherAliased = merchantAliasPreferences.resolveAlias(other.merchantName, targetIsDeposit)
                .ifBlank { other.merchantName }
            val otherNormRaw = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(other.merchantName)
            val otherNormAliased = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(otherAliased)
            val otherMinute = "${other.timestamp.year}_${other.timestamp.monthValue}_${other.timestamp.dayOfMonth}_${other.timestamp.hour}_${other.timestamp.minute}"

            otherMinute == targetMinute ||
                otherNormRaw == targetNormRaw ||
                otherNormAliased == targetNormAliased ||
                otherNormRaw == targetNormAliased ||
                otherNormAliased == targetNormRaw ||
                merchantAliasPreferences.isSameMerchant(other.merchantName, targetTx.merchantName) ||
                merchantAliasPreferences.isSameMerchant(otherAliased, targetAliased)
        }
    }

    private fun buildDeleteSignatureVariants(txs: Collection<Transaction>): List<Transaction> {
        val variants = ArrayList<Transaction>(txs.size * 2)
        for (tx in txs) {
            variants.add(tx)
            val isDeposit = tx.isDepositTransaction()
            val aliased = merchantAliasPreferences.resolveAlias(tx.merchantName, isDeposit).trim()
            if (aliased.isNotBlank() && aliased != tx.merchantName) {
                variants.add(tx.copy(merchantName = aliased))
            }
        }
        return variants
    }

    fun deleteTransaction(transactionId: Long) {
        val snapshot = _uiState.value
        val currentTx = snapshot.transactions.find { it.id == transactionId }
            ?: snapshot.editingTransaction?.takeIf { it.id == transactionId }
            ?: snapshot.allTransactions.find { it.id == transactionId }

        // 현재 월 화면 리스트(snapshot.transactions) 내의 쌍둥이만 즉시 찾고(<0.05ms), 전체 DB 탐색은 IO에서 수행
        val visibleTwins = if (currentTx != null) {
            findTwinTransactionsForDeletion(currentTx, snapshot.transactions)
        } else {
            emptyList()
        }
        val immediateIds = (visibleTwins.map { it.id } + transactionId).toSet()
        deletedTxIdsInSession.addAll(immediateIds)

        // [0ms 낙관적 UI 즉시 반영] 메인 스레드에서 전체 computeFilteredUiState를 돌리지 않고 현재 월 리스트에서만 즉시 제거
        recalcJob?.cancel()
        _uiState.update { current ->
            val nextTransactions = current.transactions.filterNot { it.id in immediateIds }
            val nextAll = current.allTransactions.filterNot { it.id in immediateIds }
            recomputeCurrentMonthStats(
                current.copy(
                    transactions = nextTransactions,
                    allTransactions = nextAll,
                    editingTransaction = null,
                    isSaving = false,
                    isLoading = false,
                    selectedTransactionIds = current.selectedTransactionIds - immediateIds
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val allTwins = if (currentTx != null) {
                (findTwinTransactionsForDeletion(currentTx, snapshot.allTransactions) +
                    visibleTwins +
                    listOf(currentTx)).distinctBy { it.id }
            } else {
                emptyList()
            }
            val allTargetIds = (allTwins.map { it.id } + immediateIds).toSet()
            deletedTxIdsInSession.addAll(allTargetIds)

            if (allTwins.isNotEmpty()) {
                expenseCustomMetaPreferences.markTransactionsDeleted(buildDeleteSignatureVariants(allTwins))
            }

            for (tx in allTwins) {
                val isVehicleRelated = tx.category == ExpenseCategory.FUEL ||
                    tx.category == ExpenseCategory.CAR_MAINTENANCE ||
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(tx.merchantName) ||
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isMaintenanceMerchant(tx.merchantName)
                if (isVehicleRelated) {
                    vehicleRepository.deleteRefuelingLogByTransaction(
                        amount = tx.amount,
                        date = tx.timestamp.toLocalDate(),
                        merchantName = tx.merchantName
                    )
                }
            }
            transactionRepository.deleteTransactionsByIds(allTargetIds)
        }
    }

    // 선택적 일괄 삭제 모드 제어
    fun toggleSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = !it.isSelectionMode,
                selectedTransactionIds = emptySet()
            )
        }
    }

    fun exitSelectionMode() {
        _uiState.update {
            it.copy(
                isSelectionMode = false,
                selectedTransactionIds = emptySet()
            )
        }
    }

    fun toggleSelectTransaction(id: Long) {
        _uiState.update { current ->
            val currentSet = current.selectedTransactionIds.toMutableSet()
            if (id in currentSet) {
                currentSet.remove(id)
            } else {
                currentSet.add(id)
            }
            current.copy(selectedTransactionIds = currentSet)
        }
    }

    fun selectAllFilteredTransactions() {
        _uiState.update { current ->
            val allFilteredIds = current.filteredTransactions.map { it.id }.toSet()
            val currentSet = current.selectedTransactionIds
            val nextSet = if (currentSet.size == allFilteredIds.size && allFilteredIds.isNotEmpty()) {
                emptySet()
            } else {
                allFilteredIds
            }
            current.copy(selectedTransactionIds = nextSet)
        }
    }

    fun deleteSelectedTransactions() {
        val snapshot = _uiState.value
        val selectedIds = snapshot.selectedTransactionIds.toSet()
        if (selectedIds.isEmpty()) return

        val visibleTargets = snapshot.transactions.filter { it.id in selectedIds }
        val visibleTwins = visibleTargets.flatMap { target ->
            findTwinTransactionsForDeletion(target, snapshot.transactions)
        }
        val immediateIds = (visibleTwins.map { it.id } + selectedIds).toSet()
        deletedTxIdsInSession.addAll(immediateIds)

        val deletedDisplayCount = visibleTargets.size.coerceAtLeast(selectedIds.size)
        val resultMsg = "선택한 결제 기록 ${deletedDisplayCount}건을 영구 삭제했습니다. (자동 동기화 시 재등록되지 않습니다)"

        // [0ms 낙관적 UI 즉시 반영] 메인 스레드 블로킹 없이 선택 항목을 화면에서 즉시 제거
        recalcJob?.cancel()
        _uiState.update { current ->
            val nextTransactions = current.transactions.filterNot { it.id in immediateIds }
            val nextAll = current.allTransactions.filterNot { it.id in immediateIds }
            recomputeCurrentMonthStats(
                current.copy(
                    transactions = nextTransactions,
                    allTransactions = nextAll,
                    isSelectionMode = false,
                    selectedTransactionIds = emptySet(),
                    isLoading = false,
                    syncResultMessage = resultMsg
                )
            )
        }

        viewModelScope.launch(Dispatchers.IO) {
            val baseTargets = (snapshot.allTransactions.filter { it.id in selectedIds } + visibleTargets)
                .distinctBy { it.id }
            val allTwinTargets = baseTargets.flatMap { target ->
                findTwinTransactionsForDeletion(target, snapshot.allTransactions) + listOf(target)
            }.distinctBy { it.id }

            val allTargetIds = (allTwinTargets.map { it.id } + immediateIds).toSet()
            deletedTxIdsInSession.addAll(allTargetIds)

            if (allTwinTargets.isNotEmpty()) {
                expenseCustomMetaPreferences.markTransactionsDeleted(buildDeleteSignatureVariants(allTwinTargets))
            }

            for (tx in allTwinTargets) {
                val isVehicleRelated = tx.category == ExpenseCategory.FUEL ||
                    tx.category == ExpenseCategory.CAR_MAINTENANCE ||
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(tx.merchantName) ||
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isMaintenanceMerchant(tx.merchantName)
                if (isVehicleRelated) {
                    vehicleRepository.deleteRefuelingLogByTransaction(
                        amount = tx.amount,
                        date = tx.timestamp.toLocalDate(),
                        merchantName = tx.merchantName
                    )
                }
            }
            transactionRepository.deleteTransactionsByIds(allTargetIds)
        }
    }

    // 카테고리 관리 제어
    fun openCategoryManageDialog() {
        _uiState.value = _uiState.value.copy(isCategoryManageDialogOpen = true)
    }

    fun closeCategoryManageDialog() {
        _uiState.value = _uiState.value.copy(isCategoryManageDialogOpen = false)
    }

    fun addCustomCategoryPermanent(name: String): Boolean {
        return expenseCustomMetaPreferences.addCustomCategory(name)
    }

    fun updateManagedCategoryName(item: ManagedCategoryItem, newName: String) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseCustomMetaPreferences.updateCategoryName(item, newName)
        }
    }

    fun deleteManagedCategory(item: ManagedCategoryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseCustomMetaPreferences.deleteCategory(item)
        }
    }

    fun restoreDefaultCategories() {
        viewModelScope.launch(Dispatchers.IO) {
            expenseCustomMetaPreferences.restoreDefaultCategories()
        }
    }

    // 사용자 분류 관리 제어
    fun openUserClassificationManageDialog() {
        _uiState.value = _uiState.value.copy(isUserClassificationManageDialogOpen = true)
    }

    fun closeUserClassificationManageDialog() {
        _uiState.value = _uiState.value.copy(isUserClassificationManageDialogOpen = false)
    }

    fun addUserClassification(name: String, colorIndex: Int) {
        expenseCustomMetaPreferences.addOrGetUserClassification(name, colorIndex)
    }

    fun updateUserClassification(oldName: String, newName: String, colorIndex: Int) {
        if (_uiState.value.selectedUserClassification?.equals(oldName, ignoreCase = true) == true) {
            _uiState.update { it.copy(selectedUserClassification = newName.trim()) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            expenseCustomMetaPreferences.updateUserClassification(oldName, newName, colorIndex)
        }
    }

    fun deleteUserClassification(name: String) {
        if (_uiState.value.selectedUserClassification?.equals(name, ignoreCase = true) == true) {
            _uiState.update { it.copy(selectedUserClassification = null) }
        }
        viewModelScope.launch(Dispatchers.IO) {
            expenseCustomMetaPreferences.deleteUserClassification(name)
        }
    }

    // =========================================================================
    // 영구저장 데이터 통합 관리 제어 (개별 해제 · 결제처 전체 해제 · 원본 복원 · 전체 초기화)
    // =========================================================================

    fun openPermanentRulesDialog() {
        _uiState.update { it.copy(isPermanentRulesDialogOpen = true) }
        recalculateFilteredTransactions()
    }

    fun closePermanentRulesDialog() {
        _uiState.update { it.copy(isPermanentRulesDialogOpen = false) }
    }

    /**
     * 특정 거래 내역(tx)에 현재 적용되고 있는 영구저장 규칙(PermanentMerchantRuleSummary)을 찾아 반환합니다.
     */
    fun findPermanentRuleForTransaction(tx: Transaction): PermanentMerchantRuleSummary? {
        if (expenseCustomMetaPreferences.isOneTimeEdited(tx)) return null
        val isDeposit = tx.isDepositTransaction()
        val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
        val rawParsedName = if (!isManual && tx.originalText.isNotBlank()) {
            com.autologue.app.data.parser.SmsParser.parse(tx.cardOrBankName, tx.originalText, tx.timestamp)?.merchantName
                ?: com.autologue.app.data.parser.SmsParser.parse(null, tx.originalText, tx.timestamp)?.merchantName
        } else null

        val candidateNorms = buildList {
            add(tx.merchantName)
            add(merchantAliasPreferences.resolveAlias(tx.merchantName, isDeposit))
            if (!rawParsedName.isNullOrBlank()) add(rawParsedName)
        }.map { com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(it) }
            .filter { it.isNotBlank() }
            .toSet()

        if (candidateNorms.isEmpty()) return null

        return _uiState.value.permanentMerchantRules.firstOrNull { rule ->
            if (rule.isDeposit != isDeposit) return@firstOrNull false
            val ruleTargetNorm = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(rule.merchantName)
            if (ruleTargetNorm in candidateNorms) return@firstOrNull true
            rule.sourceMerchantNames.any { src ->
                com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(src) in candidateNorms
            }
        }
    }

    /**
     * 영구저장 규칙 해제 시, 이미 자동 치환되어 DB에 덮어써졌던 거래 내역들을
     * 원본 문자(originalText) 파싱 결과로 자동 복원합니다. (1회성 저장 내역은 보존)
     */
    private suspend fun restoreOriginalTransactionsForNames(
        affectedNames: Collection<String>,
        isDeposit: Boolean?,
        restoreMerchantName: Boolean,
        restoreCategory: Boolean,
        restoreCardOrBank: Boolean
    ): Pair<Int, Set<Long>> {
        val targetNorms = affectedNames
            .map { com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(it) }
            .filter { it.isNotBlank() }
            .toHashSet()
        if (targetNorms.isEmpty() && isDeposit != null) return Pair(0, emptySet())

        val allTxs = transactionRepository.getAllTransactionsFlow().first()
        val toUpdate = ArrayList<Transaction>()
        val affectedIds = HashSet<Long>()

        for (tx in allTxs) {
            if (expenseCustomMetaPreferences.isOneTimeEdited(tx)) continue
            val txIsDep = tx.isDepositTransaction()
            if (isDeposit != null && txIsDep != isDeposit) continue

            val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
            val parsed = if (!isManual && tx.originalText.isNotBlank()) {
                com.autologue.app.data.parser.SmsParser.parse(tx.cardOrBankName, tx.originalText, tx.timestamp)
                    ?: com.autologue.app.data.parser.SmsParser.parse(null, tx.originalText, tx.timestamp)
            } else null

            val txNorm = com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(tx.merchantName)
            val parsedNorm = parsed?.merchantName?.let {
                com.autologue.app.data.preferences.MerchantAliasPreferences.normalizeMerchantKey(it)
            }.orEmpty()

            val isMatched = targetNorms.isEmpty() ||
                txNorm in targetNorms ||
                (parsedNorm.isNotBlank() && parsedNorm in targetNorms)

            if (!isMatched) continue
            affectedIds.add(tx.id)

            if (parsed != null) {
                val restoredMerchant = if (restoreMerchantName && parsed.merchantName.isNotBlank()) {
                    parsed.merchantName
                } else {
                    tx.merchantName
                }
                val restoredCategory = if (restoreCategory) {
                    parsed.category
                } else {
                    tx.category
                }
                val restoredCard = if (restoreCardOrBank && parsed.cardOrBankName.isNotBlank()) {
                    parsed.cardOrBankName
                } else {
                    tx.cardOrBankName
                }
                if (restoredMerchant != tx.merchantName ||
                    restoredCategory != tx.category ||
                    restoredCard != tx.cardOrBankName
                ) {
                    toUpdate.add(
                        tx.copy(
                            merchantName = restoredMerchant,
                            category = restoredCategory,
                            cardOrBankName = restoredCard
                        )
                    )
                }
            }
        }

        if (toUpdate.isNotEmpty()) {
            transactionRepository.updateTransactions(toUpdate)
        }
        return Pair(toUpdate.size, affectedIds)
    }

    /**
     * 특정 결제처(PermanentMerchantRuleSummary)의 모든 영구저장 규칙(결제명 자동 치환 + 분류/속성 규칙)을
     * 일괄 해제하고, 자동 치환되었던 기존 거래 내역을 원본 문자 기준으로 복원합니다.
     */
    fun removePermanentMerchantRule(rule: PermanentMerchantRuleSummary) {
        viewModelScope.launch(Dispatchers.IO) {
            val allCandidateNames = LinkedHashSet<String>().apply {
                add(rule.merchantName)
                addAll(rule.sourceMerchantNames)
            }
            val removedAliasNames = merchantAliasPreferences.removeAliasByMerchant(
                merchantOrSourceName = rule.merchantName,
                isDeposit = rule.isDeposit
            )
            allCandidateNames.addAll(removedAliasNames)
            for (src in rule.sourceMerchantNames) {
                allCandidateNames.addAll(
                    merchantAliasPreferences.removeAliasByMerchant(
                        merchantOrSourceName = src,
                        isDeposit = rule.isDeposit
                    )
                )
            }

            val (restoredCount, affectedIds) = restoreOriginalTransactionsForNames(
                affectedNames = allCandidateNames,
                isDeposit = rule.isDeposit,
                restoreMerchantName = true,
                restoreCategory = true,
                restoreCardOrBank = true
            )

            expenseCustomMetaPreferences.removePermanentMerchantRule(
                merchantName = rule.merchantName,
                isDeposit = rule.isDeposit,
                extraMerchantNames = allCandidateNames,
                affectedTxIds = affectedIds
            )

            val dirLabel = if (rule.isDeposit) "입금" else "출금"
            val restoreSuffix = if (restoredCount > 0) " (기존 자동 치환 내역 ${restoredCount}건 원본 복원 완료)" else ""
            _uiState.update {
                it.copy(
                    syncResultMessage = "'${rule.merchantName}' [$dirLabel] 영구저장 규칙이 해제되었습니다.$restoreSuffix"
                )
            }
            recalculateFilteredTransactions()
        }
    }

    /**
     * 특정 결제처(PermanentMerchantRuleSummary)의 영구저장 규칙 중 선택한 단일 속성만 개별 해제합니다.
     */
    fun removePermanentMerchantAttribute(
        rule: PermanentMerchantRuleSummary,
        attribute: PermanentRuleAttribute
    ) {
        viewModelScope.launch(Dispatchers.IO) {
            val allCandidateNames = LinkedHashSet<String>().apply {
                add(rule.merchantName)
                addAll(rule.sourceMerchantNames)
            }

            if (attribute == PermanentRuleAttribute.ALIAS_NAME) {
                // 별칭 해제 전 원본 가맹점명으로 나머지 메타 규칙(카테고리, 사용자 분류 등)을 먼저 이관 보존
                expenseCustomMetaPreferences.removePermanentMerchantAttribute(
                    attribute = PermanentRuleAttribute.ALIAS_NAME,
                    merchantName = rule.merchantName,
                    isDeposit = rule.isDeposit,
                    extraMerchantNames = allCandidateNames
                )
                val removedAliasNames = merchantAliasPreferences.removeAliasByMerchant(
                    merchantOrSourceName = rule.merchantName,
                    isDeposit = rule.isDeposit
                )
                allCandidateNames.addAll(removedAliasNames)
                for (src in rule.sourceMerchantNames) {
                    allCandidateNames.addAll(
                        merchantAliasPreferences.removeAliasByMerchant(
                            merchantOrSourceName = src,
                            isDeposit = rule.isDeposit
                        )
                    )
                }
            }

            val (restoredCount, affectedIds) = restoreOriginalTransactionsForNames(
                affectedNames = allCandidateNames,
                isDeposit = rule.isDeposit,
                restoreMerchantName = attribute == PermanentRuleAttribute.ALIAS_NAME,
                restoreCategory = attribute == PermanentRuleAttribute.CATEGORY,
                restoreCardOrBank = attribute == PermanentRuleAttribute.CARD_DESIGNATION
            )

            if (attribute != PermanentRuleAttribute.ALIAS_NAME) {
                expenseCustomMetaPreferences.removePermanentMerchantAttribute(
                    attribute = attribute,
                    merchantName = rule.merchantName,
                    isDeposit = rule.isDeposit,
                    extraMerchantNames = allCandidateNames,
                    affectedTxIds = affectedIds
                )
            }

            val attrLabel = when (attribute) {
                PermanentRuleAttribute.ALIAS_NAME -> "결제명 자동 치환"
                PermanentRuleAttribute.CATEGORY -> "카테고리 지정"
                PermanentRuleAttribute.USER_CLASSIFICATION -> "사용자 분류"
                PermanentRuleAttribute.CARD_DESIGNATION -> "카드/은행·법인 지정"
                PermanentRuleAttribute.FIXED_EXPENSE -> "고정지출 지정"
            }
            val restoreSuffix = if (restoredCount > 0) " (기존 내역 ${restoredCount}건 원본 복원)" else ""
            _uiState.update {
                it.copy(
                    syncResultMessage = "'${rule.merchantName}'의 [$attrLabel] 영구저장 설정을 해제했습니다.$restoreSuffix"
                )
            }
            recalculateFilteredTransactions()
        }
    }

    /**
     * 카드별 법인/개인 영구 지정 규칙을 개별 해제합니다.
     */
    fun removeCardCorporateRule(cardKey: String) {
        viewModelScope.launch(Dispatchers.IO) {
            expenseCustomMetaPreferences.removeCardCorporateRule(cardKey)
            _uiState.update {
                it.copy(syncResultMessage = "'$cardKey' 카드의 법인/개인 영구 지정 규칙을 해제했습니다.")
            }
            recalculateFilteredTransactions()
        }
    }

    /**
     * 키워드 기반 자동 분류 규칙(TransactionRule)을 개별 삭제합니다.
     */
    fun deleteTransactionRule(ruleId: Long, keywordPattern: String) {
        viewModelScope.launch(Dispatchers.IO) {
            manageTransactionRulesUseCase.deleteRule(ruleId)
            _uiState.update {
                it.copy(syncResultMessage = "'$keywordPattern' 자동 분류 규칙을 해제했습니다.")
            }
            recalculateFilteredTransactions()
        }
    }

    /**
     * 등록된 모든 영구저장 규칙(결제명 자동 치환, 가맹점별 카테고리/사용자분류/카드·법인/고정지출, 카드별 법인 규칙)을
     * 일괄 초기화하고 자동 치환되었던 기존 내역들을 원본 문자 기준으로 복원합니다.
     */
    fun clearAllPermanentRules() {
        viewModelScope.launch(Dispatchers.IO) {
            val allRules = _uiState.value.permanentMerchantRules
            val allNames = LinkedHashSet<String>()
            for (r in allRules) {
                allNames.add(r.merchantName)
                allNames.addAll(r.sourceMerchantNames)
            }

            merchantAliasPreferences.clearAllAliases()
            expenseCustomMetaPreferences.clearAllPermanentRules()
            for (dbRule in _uiState.value.rules) {
                runCatching { manageTransactionRulesUseCase.deleteRule(dbRule.id) }
            }

            val (restoredCount, _) = restoreOriginalTransactionsForNames(
                affectedNames = allNames,
                isDeposit = null,
                restoreMerchantName = true,
                restoreCategory = true,
                restoreCardOrBank = true
            )

            val restoreSuffix = if (restoredCount > 0) " (기존 내역 ${restoredCount}건 원본 결제명·분류로 복원 완료)" else ""
            _uiState.update {
                it.copy(
                    syncResultMessage = "모든 영구저장 규칙을 초기화했습니다.$restoreSuffix"
                )
            }
            recalculateFilteredTransactions()
        }
    }

    fun toggleUserClassificationFilter(name: String?) {
        _uiState.update { state ->
            state.copy(
                selectedUserClassification = if (name == null || state.selectedUserClassification == name) null else name
            )
        }
    }

    fun setUserClassificationFilter(name: String?) {
        _uiState.update { it.copy(selectedUserClassification = name) }
    }

    fun setCorporateFilter(isCorporate: Boolean?) {
        _uiState.update { state ->
            state.copy(
                selectedCorporateFilter = if (isCorporate == null || state.selectedCorporateFilter == isCorporate) null else isCorporate
            )
        }
    }

    fun setBreakdownMode(mode: ExpenseBreakdownMode) {
        _uiState.update { it.copy(breakdownMode = mode) }
    }

    fun clearAllSubFilters() {
        _uiState.update {
            it.copy(
                selectedCategory = null,
                selectedCustomCategoryName = null,
                selectedUserClassification = null,
                selectedCorporateFilter = null,
                isRecurringFilterOnly = false
            )
        }
    }

    fun toggleEffectiveCategoryByName(displayName: String) {
        val state = _uiState.value
        val customMatch = state.managedCategories.firstOrNull { !it.isBuiltIn && it.displayName == displayName }
            ?: state.customCategoryByTxId.values.firstOrNull { it == displayName }?.let {
                ManagedCategoryItem("CUSTOM_$it", it, ExpenseCategory.ETC, false)
            }
        if (customMatch != null) {
            val isCurrentlySelected = state.selectedCustomCategoryName == customMatch.displayName
            setCustomCategoryFilter(if (isCurrentlySelected) null else customMatch.displayName)
            return
        }
        val builtInMatch = ExpenseCategory.values().firstOrNull {
            expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(it) == displayName || it.displayName == displayName
        }
        if (builtInMatch != null) {
            toggleCategoryFilter(builtInMatch)
        }
    }

    fun getEffectiveCategoryDisplayName(tx: Transaction): String {
        return expenseCustomMetaPreferences.resolveEffectiveCategoryDisplayName(tx)
    }

    // 엑셀 내보내기 (사용자 분류, 커스텀 카테고리, 카드/법인 여부 포함)
    fun exportToExcel(context: android.content.Context) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(
                isLoading = true,
                loadingTitle = "가계부 엑셀 파일 생성 중",
                loadingMessage = "결제 내역과 분류 통계를 엑셀 시트로 변환하고 있습니다..."
            )
            runCatching {
                val result = withContext(Dispatchers.IO) {
                    exportAllDataToExcelUseCase()
                }
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    syncResultMessage = "엑셀 파일이 다운로드 폴더에 저장되었습니다: ${result.fileName} (사용자 분류 · 카드/법인 구분 포함)"
                )
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                    putExtra(Intent.EXTRA_STREAM, result.fileUri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                val chooser = Intent.createChooser(shareIntent, "가계부 엑셀 파일 내보내기").apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(chooser)
            }.onFailure { e ->
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    syncResultMessage = "엑셀 내보내기 중 오류가 발생했습니다: ${e.localizedMessage}"
                )
            }
        }
    }

    fun openBudgetDialog() {
        _uiState.value = _uiState.value.copy(isBudgetDialogOpen = true)
    }

    fun closeBudgetDialog() {
        _uiState.value = _uiState.value.copy(isBudgetDialogOpen = false)
    }

    fun updateMonthlyBudget(newBudget: Long) {
        if (newBudget > 0L) {
            _uiState.value = _uiState.value.copy(monthlyBudget = newBudget, isBudgetDialogOpen = false)
        }
    }

    // 정기지출 모아보기 필터 토글
    fun toggleRecurringFilter() {
        _uiState.update { state ->
            val current = state.isRecurringFilterOnly
            state.copy(
                isRecurringFilterOnly = !current,
                selectedCategory = if (!current) null else state.selectedCategory,
                selectedCustomCategoryName = null
            )
        }
    }

    // 카테고리 필터 토글 시 정기지출 모아보기는 해제
    fun toggleCategoryFilter(category: ExpenseCategory) {
        _uiState.update { state ->
            val isSameBuiltIn = state.selectedCategory == category && state.selectedCustomCategoryName == null
            state.copy(
                selectedCategory = if (isSameBuiltIn) null else category,
                selectedCustomCategoryName = null,
                isRecurringFilterOnly = false
            )
        }
    }

    fun setCategoryFilter(category: ExpenseCategory?) {
        _uiState.update { state ->
            state.copy(
                selectedCategory = category,
                selectedCustomCategoryName = null,
                isRecurringFilterOnly = false
            )
        }
    }

    fun setCustomCategoryFilter(customCategoryName: String?) {
        _uiState.update { state ->
            state.copy(
                selectedCategory = if (customCategoryName != null) ExpenseCategory.ETC else null,
                selectedCustomCategoryName = customCategoryName,
                isRecurringFilterOnly = false
            )
        }
    }

    // 내 계좌 관리 다이얼로그 제어
    fun openAccountManageDialog() {
        _uiState.value = _uiState.value.copy(isAccountManageDialogOpen = true)
    }

    fun closeAccountManageDialog() {
        _uiState.value = _uiState.value.copy(isAccountManageDialogOpen = false)
    }

    fun addAccount(bankName: String, pattern: String, alias: String) {
        userAccountPreferences.addAccount(bankName, pattern, alias)
    }

    fun deleteAccount(id: String) {
        userAccountPreferences.deleteAccount(id)
    }

    fun getMatchingAccount(tx: Transaction): UserAccount? {
        return userAccountPreferences.findMatchingAccount(
            originalText = tx.originalText,
            transferMemo = tx.transferMemo,
            merchantName = tx.merchantName,
            cardOrBankName = tx.cardOrBankName
        )
    }

    fun openPasteSmsDialog() {
        _uiState.update { it.copy(isPasteSmsDialogOpen = true) }
    }

    fun closePasteSmsDialog() {
        _uiState.update { it.copy(isPasteSmsDialogOpen = false) }
    }

    fun dismissResultMessage() {
        _uiState.update { it.copy(syncResultMessage = null) }
    }

    fun importPastedTransactions(transactions: List<Transaction>) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    isPasteSmsDialogOpen = false,
                    isLoading = true,
                    loadingTitle = "결제 문자 등록 중",
                    loadingMessage = "붙여넣은 결제 내역을 가계부와 차계부에 반영하고 있습니다..."
                )
            }
            val validTransactions = transactions.filterNot { tx ->
                tx.paymentMethod == PaymentMethod.NAVER_PAY ||
                    com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                        merchantName = tx.merchantName,
                        cardOrBankName = tx.cardOrBankName,
                        originalText = tx.originalText
                    )
            }
            val (addedCount, addedFuel) = withContext(Dispatchers.IO) {
                var insertedCount = 0
                for (tx in validTransactions) {
                    runCatching {
                        val id = processTransactionUseCase(tx)
                        if (id > 0L) insertedCount++
                    }
                }
                val allTxs = transactionRepository.getAllTransactionsFlow().first()
                val fuelCount = vehicleRepository.syncRefuelingFromTransactions(allTxs)
                Pair(insertedCount, fuelCount)
            }

            val fuelMsg = if (addedFuel > 0) " (차계부 주유 ${addedFuel}건 반영)" else ""
            val msg = if (validTransactions.isEmpty() && transactions.isNotEmpty()) {
                "'네이버페이' 항목은 이력 등록 제외 대상입니다."
            } else {
                "결제 내역 ${addedCount}건을 가계부에 성공적으로 등록했습니다.$fuelMsg"
            }
            _uiState.update { it.copy(isLoading = false, syncResultMessage = msg) }
        }
    }

    fun syncHistoricalSms(context: android.content.Context) {
        viewModelScope.launch {
            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasPerm) {
                _uiState.update {
                    it.copy(
                        syncResultMessage = "문자(SMS) 읽기 권한이 필요합니다. 설정에서 권한을 허용해주세요."
                    )
                }
                return@launch
            }

            _uiState.update {
                it.copy(
                    isLoading = true,
                    loadingTitle = "결제 문자 동기화 중",
                    loadingMessage = "단말기 결제 문자를 분석하여 가계부를 동기화하고 있습니다..."
                )
            }

            val msg = withContext(Dispatchers.IO) {
                runCatching { transactionRepository.cleanDuplicates() }
                val scanResult = importer.scanHistoricalSmsDetailed(context, daysBack = 365, limit = 5000)
                var newCount = 0
                for (tx in scanResult.transactions) {
                    if (expenseCustomMetaPreferences.isTransactionDeleted(tx)) continue
                    if (tx.paymentMethod == PaymentMethod.NAVER_PAY ||
                        com.autologue.app.data.parser.SmsParser.isNaverPayExcluded(
                            merchantName = tx.merchantName,
                            cardOrBankName = tx.cardOrBankName,
                            originalText = tx.originalText
                        )
                    ) continue
                    runCatching {
                        val id = processTransactionUseCase(tx)
                        if (id > 0L) newCount++
                    }
                }

                runCatching { transactionRepository.cleanDuplicates() }
                val allTxs = transactionRepository.getAllTransactionsFlow().first()
                val addedFuel = vehicleRepository.syncRefuelingFromTransactions(allTxs)

                val fuelMsg = if (addedFuel > 0) " (차계부 주유 ${addedFuel}건 반영)" else ""
                val totalScanned = scanResult.totalMessagesScanned
                when {
                    !scanResult.isPermissionGranted ->
                        "문자(SMS) 읽기 권한이 없습니다. 스마트폰 [설정 > 애플리케이션 > LifeLog > 권한]에서 'SMS'를 허용해주세요."
                    allTxs.isNotEmpty() ->
                        "모든 결제 문자(총 ${allTxs.size}건)가 가계부 및 차계부에 최신 상태로 자동 동기화되었습니다.$fuelMsg"
                    totalScanned > 0 ->
                        "기기 문자 총 ${totalScanned}건을 스캔했으나, 카드 결제 문자로 인식된 내역이 없습니다."
                    else ->
                        "결제 문자 자동 동기화를 완료했습니다."
                }
            }
            _uiState.update { it.copy(isLoading = false, syncResultMessage = msg) }
        }
    }
}

