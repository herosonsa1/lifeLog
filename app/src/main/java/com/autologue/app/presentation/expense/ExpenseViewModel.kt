package com.autologue.app.presentation.expense

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.PaymentMethod
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.TransactionRule
import com.autologue.app.domain.model.isSelfTransfer
import com.autologue.app.domain.repository.TransactionRepository
import com.autologue.app.domain.usecase.expense.ManageTransactionRulesUseCase
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
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
    val isBudgetDialogOpen: Boolean = false
) {
    val filteredTransactions: List<Transaction>
        get() = if (selectedCategory == null) transactions else transactions.filter { it.category == selectedCategory }

    val dateDisplayTitle: String
        get() = when (filterMode) {
            ExpenseDateFilterMode.MONTH -> "${selectedYearMonth.year}년 ${selectedYearMonth.monthValue}월"
            ExpenseDateFilterMode.CUSTOM_RANGE -> "${customStartDate.format(DateTimeFormatter.ofPattern("M.d"))} ~ ${customEndDate.format(DateTimeFormatter.ofPattern("M.d"))}"
        }
}

@HiltViewModel
class ExpenseViewModel @Inject constructor(
    private val transactionRepository: TransactionRepository,
    private val manageTransactionRulesUseCase: ManageTransactionRulesUseCase
) : ViewModel() {

    private val _uiState = MutableStateFlow(ExpenseUiState())
    val uiState: StateFlow<ExpenseUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            transactionRepository.cleanDuplicates()
        }
        loadData()
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
        val state = _uiState.value
        val filtered = when (state.filterMode) {
            ExpenseDateFilterMode.MONTH -> {
                state.allTransactions.filter { tx ->
                    YearMonth.from(tx.timestamp.toLocalDate()) == state.selectedYearMonth
                }
            }
            ExpenseDateFilterMode.CUSTOM_RANGE -> {
                state.allTransactions.filter { tx ->
                    val date = tx.timestamp.toLocalDate()
                    !date.isBefore(state.customStartDate) && !date.isAfter(state.customEndDate)
                }
            }
        }

        val expenseOnly = filtered.filter { it.category != ExpenseCategory.INCOME && !it.isSelfTransfer() }
        val totalExp = expenseOnly.sumOf { it.amount }
        val totalInc = filtered.filter { it.category == ExpenseCategory.INCOME }.sumOf { it.amount }
        val byCat = expenseOnly.groupBy { it.category }
            .mapValues { (_, txs) -> txs.sumOf { it.amount } }
            .toList()
            .sortedByDescending { it.second }
            .toMap()

        _uiState.value = state.copy(
            transactions = filtered,
            totalExpense = totalExp,
            totalIncome = totalInc,
            categoryTotals = byCat
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

    fun setCategoryFilter(category: ExpenseCategory?) {
        _uiState.value = _uiState.value.copy(selectedCategory = category)
    }

    fun toggleCategoryFilter(category: ExpenseCategory) {
        val current = _uiState.value
        _uiState.value = if (current.selectedCategory == category) {
            current.copy(selectedCategory = null)
        } else {
            current.copy(selectedCategory = category)
        }
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
        memo: String?
    ) {
        viewModelScope.launch {
            val tx = Transaction(
                amount = amount,
                merchantName = merchantName,
                originalText = "[직접 입력] $merchantName %,d원".format(amount),
                timestamp = LocalDateTime.now(),
                paymentMethod = if (cardOrBankName.contains("체크")) PaymentMethod.CHECK_CARD else PaymentMethod.CREDIT_CARD,
                category = category,
                cardOrBankName = cardOrBankName.ifBlank { "직접 입력" },
                transferMemo = memo?.ifBlank { null },
                isAutoCategorized = false
            )
            transactionRepository.insertTransaction(tx)
            closeAddDialog()
        }
    }

    fun updateTransactionDetails(
        transactionId: Long,
        category: ExpenseCategory,
        memo: String?,
        makeGlobalRule: Boolean = false,
        merchantKeyword: String? = null
    ) {
        viewModelScope.launch {
            val currentTx = _uiState.value.allTransactions.find { it.id == transactionId } ?: return@launch
            val updatedTx = currentTx.copy(
                category = category,
                transferMemo = memo?.ifBlank { null }
            )
            transactionRepository.updateTransaction(updatedTx)

            if (makeGlobalRule && !merchantKeyword.isNullOrBlank()) {
                manageTransactionRulesUseCase.addRule(
                    TransactionRule(
                        keywordPattern = merchantKeyword.trim(),
                        targetCategory = category
                    )
                )
            }
            closeEditDialog()
        }
    }

    fun deleteTransaction(transactionId: Long) {
        viewModelScope.launch {
            transactionRepository.deleteTransaction(transactionId)
            closeEditDialog()
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
}
