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
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import javax.inject.Inject

enum class ExpenseDateFilterMode {
    MONTH,          // 월간 단위 조회 (기본)
    CUSTOM_RANGE    // 특정 기간 직접 지정 조회
}

data class ExpenseUiState(
    val allTransactions: List<Transaction> = emptyList(),
    val transactions: List<Transaction> = emptyList(),
    val selectedCategory: ExpenseCategory? = null,
    val selectedCustomCategoryName: String? = null,
    val selectedUserClassification: String? = null,
    val rules: List<TransactionRule> = emptyList(),
    val totalExpense: Long = 0L,
    val totalIncome: Long = 0L,
    val categoryTotals: Map<ExpenseCategory, Long> = emptyMap(),
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
    // 선택적 일괄 삭제 모드 상태
    val isSelectionMode: Boolean = false,
    val selectedTransactionIds: Set<Long> = emptySet(),
    // 정기지출 상태
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
                list = list.filter { it.category == selectedCategory }
            }
            if (!selectedUserClassification.isNullOrBlank()) {
                list = list.filter { userClassByTxId[it.id]?.name == selectedUserClassification }
            }
            return list
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
            }
        }
        viewModelScope.launch {
            expenseCustomMetaPreferences.userClassificationsFlow.collectLatest { ucs ->
                _uiState.value = _uiState.value.copy(userClassifications = ucs)
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
            if (merchantAliasPreferences.isKnownAliasTarget(tx.merchantName)) continue

            var updated = tx
            var isModified = false
            val isCardMsg = com.autologue.app.data.parser.SmsParser.isCardApprovalMessage(tx.originalText)

            // 1. "09/14 00:23 312-****-9414-21 이민희 잔액383,252원 22,000원" 패턴 정제
            if (tx.merchantName.contains("312-") || (tx.merchantName.contains("잔액") && tx.merchantName.contains("이민희")) ||
                (tx.originalText.contains("312-") && tx.originalText.contains("이민희"))) {
                updated = updated.copy(
                    amount = 22000L,
                    merchantName = merchantAliasPreferences.resolveAlias("이민희"),
                    category = ExpenseCategory.TRANSFER,
                    cardOrBankName = "NH농협",
                    transferMemo = "출금 내역 (계좌: 312-****-9414-21, 잔액 383,252원)"
                )
                isModified = true
            } else if (tx.merchantName.startsWith("07491612193855") || (tx.originalText.contains("07491612193855") && (tx.merchantName == "0" || tx.merchantName.isBlank()))) {
                updated = updated.copy(
                    amount = 50000L,
                    merchantName = merchantAliasPreferences.resolveAlias("출금 내역"),
                    category = ExpenseCategory.TRANSFER,
                    cardOrBankName = "KB국민",
                    transferMemo = "출금 내역 (계좌: 07491612193855)"
                )
                isModified = true
            } else if (tx.originalText.contains("천재교과서") && tx.merchantName != merchantAliasPreferences.resolveAlias("(주)천재교과서")) {
                updated = updated.copy(
                    merchantName = merchantAliasPreferences.resolveAlias("(주)천재교과서"),
                    category = ExpenseCategory.LIVING,
                    cardOrBankName = "신한카드",
                    paymentMethod = PaymentMethod.CREDIT_CARD
                )
                isModified = true
            } else if (!isCardMsg && tx.merchantName.matches(Regex("""^\d{6,}.*"""))) {
                val cleanName = if (tx.merchantName.contains("출금")) "출금 내역" else "계좌 이체"
                updated = updated.copy(
                    merchantName = merchantAliasPreferences.resolveAlias(cleanName),
                    category = ExpenseCategory.TRANSFER,
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

    private fun recalculateFilteredTransactions() {
        var state = _uiState.value
        // [중복 방어 안전망] 동일한 분, 동일 금액, 동일 가맹점(공백 제외)의 중복 항목은 화면 및 합계에서 1건만 유지
        val dedupedAll = state.allTransactions.distinctBy { tx ->
            val minuteStamp = "${tx.timestamp.year}_${tx.timestamp.monthValue}_${tx.timestamp.dayOfMonth}_${tx.timestamp.hour}_${tx.timestamp.minute}"
            "${minuteStamp}_${tx.amount}_${tx.merchantName.replace(" ", "")}"
        }

        // [월 변경 자동 포커싱] 최초 진입 시 현재 월(예: 10월)에 내역이 0건이고 이전 월(예: 9월)에 거래 내역이 존재하면
        // 가장 최근 거래가 있는 연월(YearMonth)로 자동 포커싱하여 0원 빈 화면 노출 방지
        if (!hasAutoFocusedMonth && dedupedAll.isNotEmpty() && state.filterMode == ExpenseDateFilterMode.MONTH) {
            hasAutoFocusedMonth = true
            val hasCurrentMonthTx = dedupedAll.any { YearMonth.from(it.timestamp.toLocalDate()) == state.selectedYearMonth }
            if (!hasCurrentMonthTx) {
                val latestYearMonth = dedupedAll.maxOf { YearMonth.from(it.timestamp.toLocalDate()) }
                state = state.copy(selectedYearMonth = latestYearMonth)
            }
        }

        // 지난달 동일 금액/날짜 비교를 통한 정기지출 ID 집합 산출
        val recurringIds = RecurringExpenseDetector.detectRecurringTransactionIds(dedupedAll)

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
        }

        _uiState.value = state.copy(
            transactions = filtered,
            totalExpense = totalExp,
            totalIncome = totalInc,
            categoryTotals = byCat,
            recurringTransactionIds = recurringIds,
            recurringExpenseTotal = recurringExp,
            userClassByTxId = uClassMap,
            customCategoryByTxId = cCatMap,
            corporateByTxId = corpMap,
            oneTimeEditedIds = oneTimeSet
        )
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
        _uiState.value = _uiState.value.copy(editingTransaction = null)
    }

    fun addManualTransaction(
        merchantName: String,
        amount: Long,
        category: ExpenseCategory,
        cardOrBankName: String,
        memo: String?,
        isCorporate: Boolean = false,
        customCategoryName: String? = null,
        userClassificationName: String? = null
    ) {
        viewModelScope.launch {
            val isFuel = category == ExpenseCategory.FUEL ||
                com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(merchantName)
            val finalCategory = if (isFuel) ExpenseCategory.FUEL else category
            val formattedCard = expenseCustomMetaPreferences.formatCardDesignation(
                cardOrBankName.ifBlank { "직접 입력" },
                isCorporate
            )

            val tx = Transaction(
                amount = amount,
                merchantName = merchantName,
                originalText = "[직접 입력] $merchantName %,d원".format(amount),
                timestamp = LocalDateTime.now(),
                paymentMethod = if (formattedCard.contains("체크")) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
                category = finalCategory,
                cardOrBankName = formattedCard,
                transferMemo = memo?.ifBlank { null },
                isAutoCategorized = false
            )
            val insertedId = transactionRepository.insertTransaction(tx)
            if (insertedId > 0L) {
                expenseCustomMetaPreferences.setTransactionCardDesignation(
                    txId = insertedId,
                    merchantName = merchantName,
                    cardCompany = cardOrBankName.ifBlank { "직접 입력" },
                    isCorporate = isCorporate,
                    applyToMerchantRule = false
                )
                if (!customCategoryName.isNullOrBlank()) {
                    expenseCustomMetaPreferences.assignCustomCategory(
                        txId = insertedId,
                        merchantName = merchantName,
                        customCategoryName = customCategoryName,
                        applyToMerchantRule = false
                    )
                }
                if (!userClassificationName.isNullOrBlank()) {
                    expenseCustomMetaPreferences.assignUserClassification(
                        txId = insertedId,
                        merchantName = merchantName,
                        userClassificationName = userClassificationName,
                        applyToMerchantRule = false
                    )
                }
            }

            if (isFuel && amount > 0) {
                val lastFuel = vehicleRepository.getLatestRefuelingLog()
                val daysSince = if (lastFuel != null) {
                    java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), tx.timestamp.toLocalDate()).toInt()
                } else null

                val vehicleLog = com.autologue.app.domain.model.VehicleLog(
                    timestamp = tx.timestamp,
                    logType = com.autologue.app.domain.model.VehicleLogType.REFUELING,
                    fuelCost = tx.amount,
                    fuelAmountLiters = String.format(java.util.Locale.US, "%.1f", tx.amount / 1650.0).toDouble(),
                    daysSinceLastFuel = daysSince,
                    gasStationName = tx.merchantName,
                    note = "가계부 직접 입력 연동"
                )
                vehicleRepository.insertVehicleLog(vehicleLog)
            }
            closeAddDialog()
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
        isOneTimeSave: Boolean = false
    ) {
        viewModelScope.launch {
            val currentTx = _uiState.value.allTransactions.find { it.id == transactionId } ?: return@launch
            val oldMerchantName = currentTx.merchantName.trim()
            val finalMerchantName = newMerchantName.trim().ifBlank { oldMerchantName }
            val isMerchantRenamed = finalMerchantName.isNotBlank() && finalMerchantName != oldMerchantName
            val applyToMerchantRule = !isOneTimeSave

            val isManual = currentTx.originalText.startsWith("[직접 입력]") || currentTx.originalText.startsWith("[차계부 직접 입력]")
            val rawParsedName = if (!isManual && currentTx.originalText.isNotBlank()) {
                com.autologue.app.data.parser.SmsParser.parse(currentTx.cardOrBankName, currentTx.originalText, currentTx.timestamp)?.merchantName
                    ?: com.autologue.app.data.parser.SmsParser.parse(null, currentTx.originalText, currentTx.timestamp)?.merchantName
            } else null

            val wasFuel = currentTx.category == ExpenseCategory.FUEL ||
                com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(oldMerchantName) ||
                merchantAliasPreferences.getOriginalNamesForTarget(oldMerchantName).any {
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(it)
                }
            val isNowFuel = category == ExpenseCategory.FUEL ||
                (category == currentTx.category && wasFuel) ||
                com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(finalMerchantName)

            val formattedCard = if (cardCompany != null) {
                expenseCustomMetaPreferences.setTransactionCardDesignation(
                    txId = transactionId,
                    merchantName = finalMerchantName,
                    cardCompany = cardCompany,
                    isCorporate = isCorporate,
                    applyToMerchantRule = applyToMerchantRule
                )
            } else {
                currentTx.cardOrBankName
            }

            expenseCustomMetaPreferences.assignCustomCategory(
                txId = transactionId,
                merchantName = finalMerchantName,
                customCategoryName = customCategoryName,
                applyToMerchantRule = applyToMerchantRule
            )
            expenseCustomMetaPreferences.assignUserClassification(
                txId = transactionId,
                merchantName = finalMerchantName,
                userClassificationName = userClassificationName,
                applyToMerchantRule = applyToMerchantRule
            )

            val updatedTx = currentTx.copy(
                merchantName = finalMerchantName,
                category = if (isNowFuel && category == currentTx.category) ExpenseCategory.FUEL else category,
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
            if (!isOneTimeSave && isMerchantRenamed) {
                val genericPlaceholders = setOf("출금 내역", "계좌 이체", "카드 가맹점", "가맹점", "직접 추가 지출")
                val effectiveOldKey = if (oldMerchantName !in genericPlaceholders) {
                    oldMerchantName
                } else {
                    rawParsedName?.takeIf { it.isNotBlank() && it !in genericPlaceholders } ?: ""
                }
                val effectiveRawKey = rawParsedName?.takeIf { it.isNotBlank() && it !in genericPlaceholders }

                if (effectiveOldKey.isNotBlank()) {
                    // 1. 가계부 내 동일 결제처 기존 내역 일괄 치환 및 영구 자동 치환 규칙 등록
                    val txRenamed = transactionRepository.renameMerchantGlobally(
                        oldMerchantName = effectiveOldKey,
                        newMerchantName = finalMerchantName,
                        rawOriginalMerchantName = effectiveRawKey
                    )
                    // 2. 차계부 주유 내역 중 동일 결제처 항목도 동시 일괄 치환
                    val fuelRenamed = vehicleRepository.renameGasStationGlobally(
                        oldStationName = effectiveOldKey,
                        newStationName = finalMerchantName,
                        rawOriginalStationName = effectiveRawKey
                    )
                    globalRenamedCount = txRenamed + fuelRenamed
                }
            }

            if (!wasFuel && updatedTx.category == ExpenseCategory.FUEL && updatedTx.amount > 0) {
                val lastFuel = vehicleRepository.getLatestRefuelingLog()
                val daysSince = if (lastFuel != null) {
                    java.time.temporal.ChronoUnit.DAYS.between(lastFuel.timestamp.toLocalDate(), updatedTx.timestamp.toLocalDate()).toInt()
                } else null

                val noteTag = if (isOneTimeSave) {
                    "가계부 지출 카테고리 변경 연동 [custom_name:true] [one_time_edit:true]"
                } else {
                    "가계부 지출 카테고리 변경 연동 [custom_name:true]"
                }
                val vehicleLog = com.autologue.app.domain.model.VehicleLog(
                    timestamp = updatedTx.timestamp,
                    logType = com.autologue.app.domain.model.VehicleLogType.REFUELING,
                    fuelCost = updatedTx.amount,
                    fuelAmountLiters = String.format(java.util.Locale.US, "%.1f", updatedTx.amount / 1650.0).toDouble(),
                    daysSinceLastFuel = daysSince,
                    gasStationName = updatedTx.merchantName,
                    note = noteTag
                )
                vehicleRepository.insertVehicleLog(vehicleLog)
            } else if (wasFuel && updatedTx.category != ExpenseCategory.FUEL) {
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

            recalculateFilteredTransactions()

            if (isOneTimeSave) {
                _uiState.value = _uiState.value.copy(
                    syncResultMessage = "'$finalMerchantName' 내역 1건을 1회성으로 저장했습니다. (동일 결제처의 다른 내역은 변경되지 않습니다)"
                )
            } else if (isMerchantRenamed) {
                val extraMsg = if (globalRenamedCount > 0) " (기존 동일 결제처 ${globalRenamedCount}건 추가 치환 완료)" else ""
                _uiState.value = _uiState.value.copy(
                    syncResultMessage = "결제명을 '$finalMerchantName'(으)로 변경했습니다. 앞으로 같은 결제처 항목은 자동으로 치환됩니다.$extraMsg"
                )
            }
            closeEditDialog()
        }
    }

    fun deleteTransaction(transactionId: Long) {
        viewModelScope.launch {
            val currentTx = _uiState.value.allTransactions.find { it.id == transactionId }
            if (currentTx != null) {
                expenseCustomMetaPreferences.markTransactionDeleted(currentTx)
                val isFuel = currentTx.category == ExpenseCategory.FUEL ||
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(currentTx.merchantName)
                if (isFuel) {
                    vehicleRepository.deleteRefuelingLogByTransaction(
                        amount = currentTx.amount,
                        date = currentTx.timestamp.toLocalDate(),
                        merchantName = currentTx.merchantName
                    )
                }
            }
            transactionRepository.deleteTransaction(transactionId)
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
            expenseCustomMetaPreferences.markTransactionsDeleted(targets)
            for (tx in targets) {
                val isFuel = tx.category == ExpenseCategory.FUEL ||
                    com.autologue.app.data.repository.VehicleRepositoryImpl.isFuelMerchant(tx.merchantName)
                if (isFuel) {
                    vehicleRepository.deleteRefuelingLogByTransaction(
                        amount = tx.amount,
                        date = tx.timestamp.toLocalDate(),
                        merchantName = tx.merchantName
                    )
                }
                transactionRepository.deleteTransaction(tx.id)
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
        _uiState.value = _uiState.value.copy(
            selectedCategory = if (current == category) null else category,
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
