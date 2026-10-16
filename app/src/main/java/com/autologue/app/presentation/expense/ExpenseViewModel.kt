package com.autologue.app.presentation.expense

import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.data.preferences.ManagedCategoryItem
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
    BY_CATEGORY,    // 카테고리별 지출 보기
    BY_USER         // 사용자별 지출 보기
}

const val UNASSIGNED_USER_FILTER = "__UNASSIGNED__"

data class ExpenseUiState(
    val allTransactions: List<Transaction> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val selectedCategory: ExpenseCategory? = null,
    val selectedCustomCategoryName: String? = null,
    val selectedUserClassification: String? = null,
    val selectedCorporateFilter: Boolean? = null,
    val breakdownMode: ExpenseBreakdownMode = ExpenseBreakdownMode.BY_CATEGORY,
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
    val userClassByTxId: Map<Long, UserClassificationItem> = emptyMap(),
    val customCategoryByTxId: Map<Long, String> = emptyMap(),
    val corporateByTxId: Map<Long, Boolean> = emptyMap(),
    val oneTimeEditedIds: Set<Long> = emptySet(),
    val metaVersion: Long = 0L,
    val isSaving: Boolean = false,
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

    private var hasAutoFocusedMonth = false

    init {
        loadData()
        observeUserAccounts()
        observeCustomMeta()
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { transactionRepository.cleanDuplicates() }
            cleanCorruptedTransferRecords()
            autoScanDeviceMessagesOnInit()
        }
    }

    private fun observeCustomMeta() {
        viewModelScope.launch {
            expenseCustomMetaPreferences.managedCategoriesFlow.collectLatest { cats ->
                _uiState.value = _uiState.value.copy(managedCategories = cats)
                recalculateFilteredTransactions()
            }
        }
        viewModelScope.launch {
            expenseCustomMetaPreferences.userClassificationsFlow.collectLatest { ucs ->
                _uiState.value = _uiState.value.copy(userClassifications = ucs)
                recalculateFilteredTransactions()
            }
        }
        viewModelScope.launch {
            expenseCustomMetaPreferences.metaVersionFlow.collectLatest { ver ->
                _uiState.value = _uiState.value.copy(metaVersion = ver)
                recalculateFilteredTransactions()
            }
        }
    }

    private suspend fun autoScanDeviceMessagesOnInit() {
        runCatching {
            val scanResult = importer.scanHistoricalSmsDetailed(appContext, daysBack = 365, limit = 5000)
            for (tx in scanResult.transactions) {
                if (expenseCustomMetaPreferences.isTransactionDeleted(tx)) continue
                runCatching { processTransactionUseCase(tx) }
            }
            transactionRepository.cleanDuplicates()
            val allTxs = transactionRepository.getAllTransactionsFlow().first()
            vehicleRepository.syncRefuelingFromTransactions(allTxs)
        }
    }

    private fun observeUserAccounts() {
        viewModelScope.launch {
            userAccountPreferences.accounts.collectLatest { accList ->
                _uiState.value = _uiState.value.copy(userAccounts = accList)
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
        viewModelScope.launch {
            transactionRepository.getAllTransactionsFlow().collectLatest { list ->
                _uiState.value = _uiState.value.copy(allTransactions = list)
                recalculateFilteredTransactions()
            }
        }
        viewModelScope.launch {
            manageTransactionRulesUseCase.getAllRules().collectLatest { rules ->
                _uiState.value = _uiState.value.copy(rules = rules)
            }
        }
    }

    private var recalcJob: kotlinx.coroutines.Job? = null

    private fun recalculateFilteredTransactions() {
        recalcJob?.cancel()
        recalcJob = viewModelScope.launch(Dispatchers.Default) {
            var state = _uiState.value
            // [중복 방어 및 비실사용 내역 차단 안전망 + 카드/은행 표준명 정규화]
            val dedupedAll = state.allTransactions
                .filterNot { tx ->
                    val isManual = tx.originalText.startsWith("[직접 입력]") || tx.originalText.startsWith("[차계부 직접 입력]")
                    !isManual && com.autologue.app.data.parser.SmsParser.isNonUsageOrCorruptedTransaction(
                        tx.merchantName,
                        tx.cardOrBankName,
                        tx.originalText
                    )
                }
                .distinctBy { tx ->
                    val minuteStamp = "${tx.timestamp.year}_${tx.timestamp.monthValue}_${tx.timestamp.dayOfMonth}_${tx.timestamp.hour}_${tx.timestamp.minute}"
                    val dirTag = if (tx.isDepositTransaction()) "IN" else "OUT"
                    "${dirTag}_${minuteStamp}_${tx.amount}_${tx.merchantName.replace(" ", "")}"
                }
                .map { tx ->
                    val displayCardOrBank = expenseCustomMetaPreferences.resolveDisplayCardOrBankName(tx)
                    if (!displayCardOrBank.isNullOrBlank() && displayCardOrBank != tx.cardOrBankName) {
                        tx.copy(cardOrBankName = displayCardOrBank)
                    } else {
                        tx
                    }
                }

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
            val recurringIds = mutableSetOf<Long>()

            val uClassMap = mutableMapOf<Long, UserClassificationItem>()
            val cCatMap = mutableMapOf<Long, String>()
            val corpMap = mutableMapOf<Long, Boolean>()
            val oneTimeSet = mutableSetOf<Long>()

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
                    dedupedAll.filter { tx ->
                        YearMonth.from(tx.timestamp.toLocalDate()) == state.selectedYearMonth
                    }
                }
                ExpenseDateFilterMode.CUSTOM_RANGE -> {
                    dedupedAll.filter { tx ->
                        val date = tx.timestamp.toLocalDate()
                        !date.isBefore(state.customStartDate) && !date.isAfter(state.customEndDate)
                    }
                }
            }

            val expenseOnly = filtered.filter { it.category != ExpenseCategory.INCOME && !it.isSelfTransfer() }
            val totalExp = expenseOnly.sumOf { it.amount }
            val totalInc = filtered.filter { it.category == ExpenseCategory.INCOME }.sumOf { it.amount }
            val recurringExp = expenseOnly.filter { it.id in recurringIds }.sumOf { it.amount }

            val byCat = expenseOnly.groupBy { it.category }
                .mapValues { (_, txs) -> txs.sumOf { it.amount } }
                .filter { it.value > 0L }
                .toList()
                .sortedByDescending { it.second }
                .toMap()

            val byEffectiveCat = expenseOnly
                .groupBy { tx -> cCatMap[tx.id] ?: expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(tx.category) }
                .mapValues { (_, txs) -> txs.sumOf { it.amount } }
                .filter { it.value > 0L }
                .toList()
                .sortedByDescending { it.second }
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

            _uiState.value = _uiState.value.copy(
                selectedYearMonth = state.selectedYearMonth,
                transactions = filtered,
                totalExpense = totalExp,
                totalIncome = totalInc,
                categoryTotals = byCat,
                effectiveCategoryTotals = byEffectiveCat,
                userClassificationTotals = byUserClass,
                recurringTransactionIds = recurringIds,
                recurringExpenseTotal = recurringExp,
                userClassByTxId = uClassMap,
                customCategoryByTxId = cCatMap,
                corporateByTxId = corpMap,
                oneTimeEditedIds = oneTimeSet
            )
        }
    }

    // 전월 이동
    fun previousMonth() {
        val current = _uiState.value
        _uiState.value = current.copy(
            filterMode = ExpenseDateFilterMode.MONTH,
            selectedYearMonth = current.selectedYearMonth.minusMonths(1)
        )
        recalculateFilteredTransactions()
    }

    // 다음월 이동
    fun nextMonth() {
        val current = _uiState.value
        _uiState.value = current.copy(
            filterMode = ExpenseDateFilterMode.MONTH,
            selectedYearMonth = current.selectedYearMonth.plusMonths(1)
        )
        recalculateFilteredTransactions()
    }

    // 특정 월 선택
    fun selectYearMonth(yearMonth: YearMonth) {
        _uiState.value = _uiState.value.copy(
            filterMode = ExpenseDateFilterMode.MONTH,
            selectedYearMonth = yearMonth,
            isDateRangePickerOpen = false
        )
        recalculateFilteredTransactions()
    }

    // 기간 직접 지정
    fun setCustomDateRange(startDate: LocalDate, endDate: LocalDate) {
        _uiState.value = _uiState.value.copy(
            filterMode = ExpenseDateFilterMode.CUSTOM_RANGE,
            customStartDate = startDate,
            customEndDate = endDate,
            isDateRangePickerOpen = false
        )
        recalculateFilteredTransactions()
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
        recalculateFilteredTransactions()
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
        _uiState.value = _uiState.value.copy(editingTransaction = null, isSaving = false)
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
        _uiState.value = _uiState.value.copy(isSaving = true)
        viewModelScope.launch {
            kotlinx.coroutines.delay(40)
            try {
                withContext(Dispatchers.IO) {
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
                    val insertedId = transactionRepository.insertTransaction(tx)
                    if (insertedId > 0L) {
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
            } finally {
                _uiState.value = _uiState.value.copy(isSaving = false, isAddDialogOpen = false)
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
        isFixedExpense: Boolean? = null
    ) {
        if (_uiState.value.isSaving) return
        val currentTx = _uiState.value.allTransactions.find { it.id == transactionId } ?: return
        _uiState.value = _uiState.value.copy(isSaving = true)

        viewModelScope.launch {
            // 저장 버튼의 스피너 애니메이션이 UI에 즉시 그려지도록 프레임 양보
            kotlinx.coroutines.delay(40)
            try {
                val resultMessage = withContext(Dispatchers.IO) {
                    val isDeposit = currentTx.isDepositTransaction()
                    val oldMerchantName = currentTx.merchantName.trim()
                    val finalMerchantName = newMerchantName.trim().ifBlank { oldMerchantName }
                    val applyToMerchantRule = !isOneTimeSave

                    val isManual = currentTx.originalText.startsWith("[직접 입력]") || currentTx.originalText.startsWith("[차계부 직접 입력]")
                    val rawParsedName = if (!isManual && currentTx.originalText.isNotBlank()) {
                        com.autologue.app.data.parser.SmsParser.parse(currentTx.cardOrBankName, currentTx.originalText, currentTx.timestamp)?.merchantName
                            ?: com.autologue.app.data.parser.SmsParser.parse(null, currentTx.originalText, currentTx.timestamp)?.merchantName
                    } else null

                    val genericPlaceholders = setOf("출금 내역", "계좌 이체", "입금 내역", "카드 가맹점", "가맹점", "직접 추가 지출")
                    val effectiveOldKey = if (oldMerchantName !in genericPlaceholders) {
                        oldMerchantName
                    } else {
                        rawParsedName?.takeIf { it.isNotBlank() && it !in genericPlaceholders } ?: ""
                    }
                    val effectiveRawKey = rawParsedName?.takeIf { it.isNotBlank() && it !in genericPlaceholders }

                    val wasFuel = currentTx.category == ExpenseCategory.FUEL ||
                        com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(oldMerchantName) ||
                        merchantAliasPreferences.getOriginalNamesForTarget(oldMerchantName, isDeposit).any {
                            com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(it)
                        }
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

                    val formattedCard = if (cardCompany != null) {
                        expenseCustomMetaPreferences.setTransactionCardDesignation(
                            txId = transactionId,
                            merchantName = finalMerchantName,
                            cardCompany = cardCompany,
                            isCorporate = effectiveCorporate,
                            applyToMerchantRule = applyToMerchantRule,
                            isDeposit = isDeposit,
                            extraMerchantNames = arrayOf(oldMerchantName, effectiveRawKey),
                            isBankAccountTx = isBankTx,
                            rawCardOrBankName = currentTx.cardOrBankName,
                            originalText = currentTx.originalText
                        )
                    } else {
                        expenseCustomMetaPreferences.resolveDisplayCardOrBankName(currentTx) ?: currentTx.cardOrBankName
                    }

                    expenseCustomMetaPreferences.assignBaseCategory(
                        merchantName = finalMerchantName,
                        category = resolvedCategory,
                        applyToMerchantRule = applyToMerchantRule,
                        isDeposit = isDeposit,
                        extraMerchantNames = arrayOf(oldMerchantName, effectiveRawKey)
                    )

                    expenseCustomMetaPreferences.assignCustomCategory(
                        txId = transactionId,
                        merchantName = finalMerchantName,
                        customCategoryName = customCategoryName,
                        applyToMerchantRule = applyToMerchantRule,
                        isDeposit = isDeposit,
                        extraMerchantNames = arrayOf(oldMerchantName, effectiveRawKey)
                    )
                    expenseCustomMetaPreferences.assignUserClassification(
                        txId = transactionId,
                        merchantName = finalMerchantName,
                        userClassificationName = userClassificationName,
                        applyToMerchantRule = applyToMerchantRule,
                        isDeposit = isDeposit,
                        extraMerchantNames = arrayOf(oldMerchantName, effectiveRawKey)
                    )

                    if (isFixedExpense != null) {
                        expenseCustomMetaPreferences.setFixedExpense(
                            txId = transactionId,
                            merchantName = finalMerchantName,
                            isFixed = isFixedExpense,
                            applyToMerchantRule = applyToMerchantRule,
                            isDeposit = isDeposit,
                            extraMerchantNames = arrayOf(oldMerchantName, effectiveRawKey)
                        )
                    }

                    val updatedTx = currentTx.copy(
                        merchantName = finalMerchantName,
                        category = resolvedCategory,
                        cardOrBankName = formattedCard,
                        transferMemo = memo?.ifBlank { null }
                    )

                    if (isOneTimeSave) {
                        expenseCustomMetaPreferences.markOneTimeEdited(updatedTx)
                    } else {
                        expenseCustomMetaPreferences.clearOneTimeEdited(updatedTx)
                    }

                    transactionRepository.updateTransaction(updatedTx)

                    var globalRenamedCount = 0
                    if (!isOneTimeSave) {
                        val targetKeyForGlobal = effectiveOldKey.ifBlank { finalMerchantName }
                        if (targetKeyForGlobal.isNotBlank()) {
                            // 1. 가계부 내 동일 결제처 + 동일 입출금 구분(입금/출금) 기존 내역 일괄 치환 및 영구 자동 치환 규칙 등록
                            val txRenamed = transactionRepository.renameMerchantGlobally(
                                oldMerchantName = targetKeyForGlobal,
                                newMerchantName = finalMerchantName,
                                rawOriginalMerchantName = effectiveRawKey,
                                isDeposit = isDeposit,
                                targetCategory = resolvedCategory,
                                targetCardOrBankName = formattedCard
                            )
                            // 2. 출금(결제) 내역인 경우 차계부 주유/정비 내역 중 동일 결제처 항목도 동시 일괄 치환
                            val fuelRenamed = if (!isDeposit && targetKeyForGlobal.isNotBlank()) {
                                vehicleRepository.renameGasStationGlobally(
                                    oldStationName = targetKeyForGlobal,
                                    newStationName = finalMerchantName,
                                    rawOriginalStationName = effectiveRawKey
                                )
                            } else 0
                            globalRenamedCount = txRenamed + fuelRenamed
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
                    if (isOneTimeSave) {
                        "'$finalMerchantName' [$dirLabel] 내역 1건을 1회성으로 저장했습니다. (동일 결제처의 다른 내역은 변경되지 않습니다)"
                    } else {
                        val extraMsg = if (globalRenamedCount > 0) " (동일 [$dirLabel] 내역 ${globalRenamedCount}건 추가 치환 완료)" else ""
                        "'$finalMerchantName' [$dirLabel] 영구저장 완료! 앞으로 동일 결제처의 [$dirLabel] 항목에만 구분되어 자동 치환됩니다.$extraMsg"
                    }
                }

                recalculateFilteredTransactions()
                _uiState.value = _uiState.value.copy(
                    syncResultMessage = resultMessage,
                    editingTransaction = null,
                    isSaving = false
                )
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    isSaving = false,
                    editingTransaction = null,
                    syncResultMessage = "저장 중 오류가 발생했습니다: ${e.localizedMessage ?: "알 수 없는 오류"}"
                )
            }
        }
    }

    fun deleteTransaction(transactionId: Long) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) {
                val currentTx = _uiState.value.allTransactions.find { it.id == transactionId }
                if (currentTx != null) {
                    expenseCustomMetaPreferences.markTransactionDeleted(currentTx)
                    val isVehicleRelated = currentTx.category == ExpenseCategory.FUEL ||
                        currentTx.category == ExpenseCategory.CAR_MAINTENANCE ||
                        com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(currentTx.merchantName) ||
                        com.autologue.app.data.repository.VehicleRepositoryImpl.isMaintenanceMerchant(currentTx.merchantName)
                    if (isVehicleRelated) {
                        vehicleRepository.deleteRefuelingLogByTransaction(
                            amount = currentTx.amount,
                            date = currentTx.timestamp.toLocalDate(),
                            merchantName = currentTx.merchantName
                        )
                    }
                }
                transactionRepository.deleteTransaction(transactionId)
            }
            closeEditDialog()
        }
    }

    // 선택적 일괄 삭제 모드 제어
    fun toggleSelectionMode() {
        val current = _uiState.value.isSelectionMode
        _uiState.value = _uiState.value.copy(
            isSelectionMode = !current,
            selectedTransactionIds = emptySet()
        )
    }

    fun exitSelectionMode() {
        _uiState.value = _uiState.value.copy(
            isSelectionMode = false,
            selectedTransactionIds = emptySet()
        )
    }

    fun toggleSelectTransaction(id: Long) {
        val currentSet = _uiState.value.selectedTransactionIds.toMutableSet()
        if (id in currentSet) {
            currentSet.remove(id)
        } else {
            currentSet.add(id)
        }
        _uiState.value = _uiState.value.copy(selectedTransactionIds = currentSet)
    }

    fun selectAllFilteredTransactions() {
        val allFilteredIds = _uiState.value.filteredTransactions.map { it.id }.toSet()
        val currentSet = _uiState.value.selectedTransactionIds
        val nextSet = if (currentSet.size == allFilteredIds.size && allFilteredIds.isNotEmpty()) {
            emptySet()
        } else {
            allFilteredIds
        }
        _uiState.value = _uiState.value.copy(selectedTransactionIds = nextSet)
    }

    fun deleteSelectedTransactions() {
        val selectedIds = _uiState.value.selectedTransactionIds.toSet()
        if (selectedIds.isEmpty()) return
        viewModelScope.launch {
            val targets = _uiState.value.allTransactions.filter { it.id in selectedIds }
            withContext(Dispatchers.IO) {
                expenseCustomMetaPreferences.markTransactionsDeleted(targets)
                for (tx in targets) {
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
                    transactionRepository.deleteTransaction(tx.id)
                }
            }
            _uiState.value = _uiState.value.copy(
                isSelectionMode = false,
                selectedTransactionIds = emptySet(),
                syncResultMessage = "선택한 결제 기록 ${targets.size}건을 영구 삭제했습니다. (자동 동기화 시 재등록되지 않습니다)"
            )
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
        expenseCustomMetaPreferences.updateCategoryName(item, newName)
    }

    fun deleteManagedCategory(item: ManagedCategoryItem) {
        expenseCustomMetaPreferences.deleteCategory(item)
    }

    fun restoreDefaultCategories() {
        expenseCustomMetaPreferences.restoreDefaultCategories()
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
        expenseCustomMetaPreferences.updateUserClassification(oldName, newName, colorIndex)
        if (_uiState.value.selectedUserClassification?.equals(oldName, ignoreCase = true) == true) {
            _uiState.value = _uiState.value.copy(selectedUserClassification = newName.trim())
        }
    }

    fun deleteUserClassification(name: String) {
        expenseCustomMetaPreferences.deleteUserClassification(name)
        if (_uiState.value.selectedUserClassification?.equals(name, ignoreCase = true) == true) {
            _uiState.value = _uiState.value.copy(selectedUserClassification = null)
        }
    }

    fun toggleUserClassificationFilter(name: String?) {
        val current = _uiState.value.selectedUserClassification
        _uiState.value = _uiState.value.copy(
            selectedUserClassification = if (name == null || current == name) null else name
        )
    }

    fun setUserClassificationFilter(name: String?) {
        _uiState.value = _uiState.value.copy(selectedUserClassification = name)
    }

    fun setCorporateFilter(isCorporate: Boolean?) {
        val current = _uiState.value.selectedCorporateFilter
        _uiState.value = _uiState.value.copy(
            selectedCorporateFilter = if (isCorporate == null || current == isCorporate) null else isCorporate
        )
    }

    fun setBreakdownMode(mode: ExpenseBreakdownMode) {
        _uiState.value = _uiState.value.copy(breakdownMode = mode)
    }

    fun clearAllSubFilters() {
        _uiState.value = _uiState.value.copy(
            selectedCategory = null,
            selectedCustomCategoryName = null,
            selectedUserClassification = null,
            selectedCorporateFilter = null,
            isRecurringFilterOnly = false
        )
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
            runCatching {
                val result = exportAllDataToExcelUseCase()
                _uiState.value = _uiState.value.copy(
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
        val current = _uiState.value.isRecurringFilterOnly
        _uiState.value = _uiState.value.copy(
            isRecurringFilterOnly = !current,
            selectedCategory = if (!current) null else _uiState.value.selectedCategory,
            selectedCustomCategoryName = null
        )
    }

    // 카테고리 필터 토글 시 정기지출 모아보기는 해제
    fun toggleCategoryFilter(category: ExpenseCategory) {
        val current = _uiState.value.selectedCategory
        val currentCustom = _uiState.value.selectedCustomCategoryName
        val isSameBuiltIn = current == category && currentCustom == null
        _uiState.value = _uiState.value.copy(
            selectedCategory = if (isSameBuiltIn) null else category,
            selectedCustomCategoryName = null,
            isRecurringFilterOnly = false
        )
    }

    fun setCategoryFilter(category: ExpenseCategory?) {
        _uiState.value = _uiState.value.copy(
            selectedCategory = category,
            selectedCustomCategoryName = null,
            isRecurringFilterOnly = false
        )
    }

    fun setCustomCategoryFilter(customCategoryName: String?) {
        _uiState.value = _uiState.value.copy(
            selectedCategory = if (customCategoryName != null) ExpenseCategory.ETC else null,
            selectedCustomCategoryName = customCategoryName,
            isRecurringFilterOnly = false
        )
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
        _uiState.value = _uiState.value.copy(isPasteSmsDialogOpen = true)
    }

    fun closePasteSmsDialog() {
        _uiState.value = _uiState.value.copy(isPasteSmsDialogOpen = false)
    }

    fun dismissResultMessage() {
        _uiState.value = _uiState.value.copy(syncResultMessage = null)
    }

    fun importPastedTransactions(transactions: List<Transaction>) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isPasteSmsDialogOpen = false)
            var addedCount = 0
            for (tx in transactions) {
                runCatching {
                    processTransactionUseCase(tx)
                    addedCount++
                }
            }
            // 주유 내역 동기화
            val allTxs = transactionRepository.getAllTransactionsFlow().first()
            val addedFuel = vehicleRepository.syncRefuelingFromTransactions(allTxs)

            loadData()
            val fuelMsg = if (addedFuel > 0) " (차계부 주유 ${addedFuel}건 반영)" else ""
            val msg = "결제 내역 ${transactions.size}건을 가계부에 성공적으로 등록했습니다.$fuelMsg"
            _uiState.value = _uiState.value.copy(syncResultMessage = msg)
        }
    }

    fun syncHistoricalSms(context: android.content.Context) {
        viewModelScope.launch {
            runCatching { transactionRepository.cleanDuplicates() }

            val hasPerm = androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_SMS
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED

            if (!hasPerm) {
                _uiState.value = _uiState.value.copy(
                    syncResultMessage = "문자(SMS) 읽기 권한이 필요합니다. 설정에서 권한을 허용해주세요."
                )
                return@launch
            }

            val scanResult = importer.scanHistoricalSmsDetailed(context, daysBack = 365, limit = 5000)
            var newCount = 0
            for (tx in scanResult.transactions) {
                if (expenseCustomMetaPreferences.isTransactionDeleted(tx)) continue
                runCatching {
                    processTransactionUseCase(tx)
                    newCount++
                }
            }

            runCatching { transactionRepository.cleanDuplicates() }
            val allTxs = transactionRepository.getAllTransactionsFlow().first()
            val addedFuel = vehicleRepository.syncRefuelingFromTransactions(allTxs)
            loadData()

            val fuelMsg = if (addedFuel > 0) " (차계부 주유 ${addedFuel}건 반영)" else ""
            val totalScanned = scanResult.totalMessagesScanned
            val msg = when {
                !scanResult.isPermissionGranted ->
                    "문자(SMS) 읽기 권한이 없습니다. 스마트폰 [설정 > 애플리케이션 > LifeLog > 권한]에서 'SMS'를 허용해주세요."
                allTxs.isNotEmpty() ->
                    "모든 결제 문자(총 ${allTxs.size}건)가 가계부 및 차계부에 최신 상태로 자동 동기화되었습니다.$fuelMsg"
                totalScanned > 0 ->
                    "기기 문자 총 ${totalScanned}건을 스캔했으나, 카드 결제 문자로 인식된 내역이 없습니다."
                else ->
                    "결제 문자 자동 동기화를 완료했습니다."
            }
            _uiState.value = _uiState.value.copy(syncResultMessage = msg)
        }
    }
}
