package com.autologue.app.presentation.expense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.theme.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Scaffold(
        containerColor = AppColors.background,
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        Text(
                            text = "스마트 가계부",
                            style = AppTypography.h1
                        )
                    },
                    actions = {
                        AutoLogueOutlinedButton(
                            text = "지출 직접 추가",
                            icon = Icons.Default.Add,
                            onClick = { viewModel.openAddDialog() }
                        )
                        Spacer(modifier = Modifier.width(Spacing.lg))
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = AppColors.background)
                )
                HairlineDivider()
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(bottom = Spacing.xxxl)
        ) {
            // Level 0: Month / Period Navigator Bar
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.md),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Month Navigator (< 2026년 8월 >)
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50,
                        shadowElevation = 1.5.dp,
                        border = BorderStroke(1.dp, Slate200)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
                        ) {
                            IconButton(
                                onClick = { viewModel.previousMonth() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "이전달", tint = Slate700, modifier = Modifier.size(18.dp))
                            }
                            Text(
                                text = uiState.dateDisplayTitle,
                                style = AppTypography.body.copy(fontWeight = FontWeight.Bold, color = Slate900),
                                modifier = Modifier
                                    .clickable { viewModel.openDateRangeDialog() }
                                    .padding(horizontal = 8.dp)
                            )
                            IconButton(
                                onClick = { viewModel.nextMonth() },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "다음달", tint = Slate700, modifier = Modifier.size(18.dp))
                            }
                        }
                    }

                    // Quick Period Setting Button
                    AutoLogueActionChipButton(
                        text = "기간 설정",
                        icon = Icons.Default.CalendarMonth,
                        onClick = { viewModel.openDateRangeDialog() },
                        containerColor = Color(0xFFEFF6FF),
                        borderColor = Color(0xFFBFDBFE),
                        contentColor = Color(0xFF1D4ED8)
                    )
                }
                HairlineDivider()
            }

            // Level 1: Monthly Total Spending & Income KPI
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.Bottom
                ) {
                    Column {
                        Text(
                            text = "${uiState.dateDisplayTitle} 총 지출",
                            style = AppTypography.caption
                        )
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        Text(
                            text = "%,d원".format(uiState.totalExpense),
                            style = AppTypography.display
                        )
                    }
                    if (uiState.totalIncome > 0L) {
                        Column(horizontalAlignment = Alignment.End) {
                            Text(
                                text = "${uiState.dateDisplayTitle} 총 수입/입금",
                                style = AppTypography.caption
                            )
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            Text(
                                text = "+%,d원".format(uiState.totalIncome),
                                style = AppTypography.h2.copy(color = Slate600)
                            )
                        }
                    }
                }
                HairlineDivider()
            }

            // Level 1-B: Monthly Budget & Spending Gauge Card
            if (uiState.filterMode == ExpenseDateFilterMode.MONTH) {
                item {
                    val budget = uiState.monthlyBudget
                    val spent = uiState.totalExpense
                    val remaining = budget - spent
                    val ratio = (spent.toFloat() / budget.toFloat()).coerceIn(0f, 1.5f)
                    val progressRatio = ratio.coerceAtMost(1f)
                    val gaugeColor = when {
                        ratio >= 1.0f -> Color(0xFFEF4444)
                        ratio >= 0.8f -> Color(0xFFF59E0B)
                        else -> Color(0xFF10B981)
                    }

                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = Color(0xFFF8FAFC),
                        border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xs)
                    ) {
                        Column(modifier = Modifier.padding(16.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        text = "🎯 월간 목표 예산",
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF334155)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "%,d원".format(budget),
                                        fontSize = 13.sp,
                                        color = Color(0xFF64748B)
                                    )
                                }
                                TextButton(
                                    onClick = { viewModel.openBudgetDialog() },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
                                    modifier = Modifier.height(28.dp)
                                ) {
                                    Text("예산 변경", fontSize = 12.sp, color = Color(0xFF2563EB), fontWeight = FontWeight.SemiBold)
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            LinearProgressIndicator(
                                progress = { progressRatio },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp)),
                                color = gaugeColor,
                                trackColor = Color(0xFFE2E8F0)
                            )

                            Spacer(modifier = Modifier.height(8.dp))

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "%,d원 사용 (%.1f%%)".format(spent, (spent.toFloat() / budget * 100f)),
                                    fontSize = 12.sp,
                                    color = Color(0xFF64748B)
                                )
                                Text(
                                    text = if (remaining >= 0) "%,d원 남음".format(remaining) else "%,d원 초과".format(-remaining),
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (remaining >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                }
            }

            // Level 2: Category Breakdown with subtle proportion bar
            if (uiState.categoryTotals.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "카테고리별 지출",
                            style = AppTypography.caption
                        )
                        if (uiState.selectedCategory != null) {
                            Text(
                                text = "필터 해제 ✕",
                                style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.Bold),
                                modifier = Modifier.clickable { viewModel.setCategoryFilter(null) }
                            )
                        }
                    }
                }

                val totalExp = uiState.totalExpense.coerceAtLeast(1L)
                val categoryEntries = uiState.categoryTotals.entries.toList()
                val totalCount = categoryEntries.size

                itemsIndexed(categoryEntries) { index, (category, amount) ->
                    val isSelected = uiState.selectedCategory == category
                    val barColor = getRainbowUsageColor(index, totalCount)

                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { viewModel.toggleCategoryFilter(category) }
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xs)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(8.dp)
                                        .background(barColor, CircleShape)
                                )
                                Spacer(modifier = Modifier.width(Spacing.xs))
                                Text(
                                    text = category.displayName,
                                    style = AppTypography.body,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) AppColors.primary else AppColors.textPrimary
                                )
                            }
                            Text(
                                text = "%,d원".format(amount),
                                style = AppTypography.h3,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                color = if (isSelected) AppColors.primary else AppColors.textPrimary
                            )
                        }
                        Spacer(modifier = Modifier.height(Spacing.xxs))
                        // Visual proportion bar with dynamic rainbow rank color
                        val proportion = (amount.toFloat() / totalExp).coerceIn(0f, 1f)
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp))
                                .background(Slate100)
                        ) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(proportion)
                                    .fillMaxHeight()
                                    .background(barColor)
                            )
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    HairlineDivider()
                }
            }

            // Level 3: Transaction List Header with Quick Category Chips
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = if (uiState.selectedCategory != null) {
                                "${uiState.selectedCategory!!.displayName} (${uiState.filteredTransactions.size}건)"
                            } else {
                                "결제 내역 (전체)"
                            },
                            style = AppTypography.caption
                        )
                        Text(
                            text = "${uiState.filteredTransactions.size}건",
                            style = AppTypography.captionMuted
                        )
                    }

                    // Horizontal Quick Filter Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xxs),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
                    ) {
                        Surface(
                            onClick = { viewModel.setCategoryFilter(null) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (uiState.selectedCategory == null) AppColors.primary else Slate50,
                            shadowElevation = if (uiState.selectedCategory == null) 2.dp else 1.dp,
                            border = BorderStroke(1.dp, if (uiState.selectedCategory == null) AppColors.primaryDark else Slate200)
                        ) {
                            Text(
                                text = "전체 (${uiState.transactions.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (uiState.selectedCategory == null) PureWhite else Slate700,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }

                        uiState.categoryTotals.keys.forEach { cat ->
                            val isSelected = uiState.selectedCategory == cat
                            val count = uiState.transactions.count { it.category == cat }
                            Surface(
                                onClick = { viewModel.toggleCategoryFilter(cat) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AppColors.primary else Slate50,
                                shadowElevation = if (isSelected) 2.dp else 1.dp,
                                border = BorderStroke(1.dp, if (isSelected) AppColors.primaryDark else Slate200)
                            ) {
                                Text(
                                    text = "${cat.displayName} ($count)",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PureWhite else Slate700,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(Spacing.xs))
                    HairlineDivider()
                }
            }

            // Level 4: Transaction Items
            if (uiState.filteredTransactions.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(Spacing.xxxl),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "조회 조건에 해당하는 결제 내역이 없습니다.",
                            style = AppTypography.bodySecondary
                        )
                    }
                }
            } else {
                items(uiState.filteredTransactions) { tx ->
                    SaaSTransactionRow(
                        transaction = tx,
                        onClick = { viewModel.openEditDialog(tx) }
                    )
                    HairlineDivider()
                }
            }
        }
    }

    // Date Range & Period Filter Dialog
    if (uiState.isDateRangePickerOpen) {
        DateRangeFilterDialog(
            currentYearMonth = uiState.selectedYearMonth,
            currentMode = uiState.filterMode,
            startDate = uiState.customStartDate,
            endDate = uiState.customEndDate,
            onDismiss = { viewModel.closeDateRangeDialog() },
            onSelectPreset = { preset -> viewModel.selectQuickPreset(preset) },
            onSelectMonth = { ym -> viewModel.selectYearMonth(ym) },
            onSelectCustomRange = { s, e -> viewModel.setCustomDateRange(s, e) }
        )
    }

    // Manual Add Expense Dialog
    if (uiState.isAddDialogOpen) {
        AddManualExpenseDialog(
            onDismiss = { viewModel.closeAddDialog() },
            onAdd = { merchant, amount, category, card, memo ->
                viewModel.addManualTransaction(merchant, amount, category, card, memo)
            }
        )
    }


    // Budget Edit Dialog
    if (uiState.isBudgetDialogOpen) {
        var budgetText by remember { mutableStateOf(uiState.monthlyBudget.toString()) }
        AlertDialog(
            onDismissRequest = { viewModel.closeBudgetDialog() },
            title = { Text("월간 목표 예산 설정", fontWeight = FontWeight.Bold, fontSize = 17.sp) },
            text = {
                Column {
                    Text("해당 월의 총 지출 한도를 설정하세요.", fontSize = 13.sp, color = Color(0xFF64748B))
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = budgetText,
                        onValueChange = { budgetText = it.filter { c -> c.isDigit() } },
                        label = { Text("목표 예산 (원)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val amount = budgetText.toLongOrNull() ?: 1500000L
                        viewModel.updateMonthlyBudget(amount)
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB))
                ) {
                    Text("저장", color = Color.White)
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.closeBudgetDialog() }) {
                    Text("취소", color = Color(0xFF64748B))
                }
            }
        )
    }

    // Edit Transaction Dialog
    if (uiState.editingTransaction != null) {
        EditTransactionDialog(
            transaction = uiState.editingTransaction!!,
            onDismiss = { viewModel.closeEditDialog() },
            onSave = { cat, memo ->
                viewModel.updateTransactionDetails(
                    transactionId = uiState.editingTransaction!!.id,
                    category = cat,
                    memo = memo
                )
            },
            onDelete = {
                viewModel.deleteTransaction(uiState.editingTransaction!!.id)
            }
        )
    }
}

@Composable
fun SaaSTransactionRow(
    transaction: Transaction,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = transaction.merchantName,
                style = AppTypography.h2
            )
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = transaction.timestamp.format(DateTimeFormatter.ofPattern("M.d(E) HH:mm", Locale.KOREA)),
                    style = AppTypography.captionMuted
                )
                Text("·", style = AppTypography.captionMuted)
                Text(
                    text = transaction.category.displayName,
                    style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.SemiBold)
                )
                if (transaction.cardOrBankName != null) {
                    Text("·", style = AppTypography.captionMuted)
                    Text(
                        text = transaction.cardOrBankName!!,
                        style = AppTypography.captionMuted
                    )
                }
            }
            if (!transaction.transferMemo.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "📝 ${transaction.transferMemo}",
                    style = AppTypography.caption.copy(color = Slate600)
                )
            }
        }

        Column(horizontalAlignment = Alignment.End) {
            val isIncome = transaction.category == ExpenseCategory.INCOME
            Text(
                text = (if (isIncome) "+" else "") + "%,d원".format(transaction.amount),
                style = AppTypography.h2.copy(
                    fontWeight = FontWeight.Bold,
                    color = if (isIncome) Emerald600 else Slate900
                )
            )
            Spacer(modifier = Modifier.height(Spacing.xxs))
            Text(
                text = "수정/메모 →",
                fontSize = 11.sp,
                color = Slate400
            )
        }
    }
}

/**
 * 조회 기간 설정 팝업 다이얼로그 (프리셋 & 월간 & 직접 지정)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DateRangeFilterDialog(
    currentYearMonth: YearMonth,
    currentMode: ExpenseDateFilterMode,
    startDate: LocalDate,
    endDate: LocalDate,
    onDismiss: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectCustomRange: (LocalDate, LocalDate) -> Unit
) {
    var selectedTab by remember { mutableStateOf(if (currentMode == ExpenseDateFilterMode.MONTH) 0 else 1) }
    var tempYearMonth by remember { mutableStateOf(currentYearMonth) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("조회 기간 설정", style = AppTypography.h2)
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // Quick Presets
                Text("빠른 기간 선택", style = AppTypography.caption)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val presets = listOf("이번 달", "지난 달", "최근 3개월", "올해 전체")
                    presets.forEach { preset ->
                        Surface(
                            onClick = { onSelectPreset(preset) },
                            shape = RoundedCornerShape(8.dp),
                            color = Slate50,
                            shadowElevation = 1.5.dp,
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Text(
                                text = preset,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    }
                }

                HairlineDivider()

                // Monthly Selector
                Text("월별 선택", style = AppTypography.caption)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = { tempYearMonth = tempYearMonth.minusMonths(1) }) {
                        Icon(Icons.Default.ChevronLeft, contentDescription = "이전달")
                    }
                    Text(
                        text = "${tempYearMonth.year}년 ${tempYearMonth.monthValue}월",
                        style = AppTypography.h2
                    )
                    IconButton(onClick = { tempYearMonth = tempYearMonth.plusMonths(1) }) {
                        Icon(Icons.Default.ChevronRight, contentDescription = "다음달")
                    }
                }

                Button(
                    onClick = { onSelectMonth(tempYearMonth) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text("${tempYearMonth.year}년 ${tempYearMonth.monthValue}월 내역 조회", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            AutoLogueSecondaryButton(text = "닫기", onClick = onDismiss)
        },
        containerColor = AppColors.surface,
        shape = AppShapes.modal
    )
}

/**
 * 지출 직접 추가 팝업 다이얼로그 (입체 그림자 + 뚜렷한 입력필드 + 고대비 버튼)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AddManualExpenseDialog(
    onDismiss: () -> Unit,
    onAdd: (merchant: String, amount: Long, category: ExpenseCategory, card: String, memo: String?) -> Unit
) {
    var merchantName by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ExpenseCategory.FOOD) }
    var cardOrBankName by remember { mutableStateOf("신한카드") }
    var memo by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "지출 직접 추가",
                style = AppTypography.h2
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.md)
            ) {
                // 1. 가맹점 / 상호명
                AutoLogueTextField(
                    value = merchantName,
                    onValueChange = { merchantName = it },
                    label = "가맹점 / 상호명",
                    placeholder = "예: 스타벅스 강남점",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 2. 결제 금액
                AutoLogueTextField(
                    value = amountText,
                    onValueChange = { amountText = it.filter { ch -> ch.isDigit() } },
                    label = "결제 금액 (원)",
                    placeholder = "예: 15000",
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 3. 카테고리 선택
                Column {
                    Text(
                        text = "카테고리 선택",
                        style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold),
                        modifier = Modifier.padding(bottom = 6.dp, start = 2.dp)
                    )
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        ExpenseCategory.values().forEach { cat ->
                            val isSelected = cat == selectedCategory
                            Surface(
                                onClick = { selectedCategory = cat },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF2563EB) else Slate50,
                                shadowElevation = if (isSelected) 2.dp else 1.dp,
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF1D4ED8) else Slate200)
                            ) {
                                Text(
                                    text = cat.displayName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PureWhite else Slate700,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }
                }

                // 4. 결제 수단
                AutoLogueTextField(
                    value = cardOrBankName,
                    onValueChange = { cardOrBankName = it },
                    label = "결제 수단",
                    placeholder = "예: 신한카드, 카카오페이, 현금",
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )

                // 5. 메모
                AutoLogueTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = "메모 (선택)",
                    placeholder = "지출 내용 메모를 남겨보세요",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            val amount = amountText.toLongOrNull() ?: 0L
            AutoLoguePrimaryButton(
                text = "추가하기",
                onClick = {
                    if (amount > 0) {
                        onAdd(merchantName.ifBlank { "직접 추가 지출" }, amount, selectedCategory, cardOrBankName, memo)
                    }
                },
                enabled = amount > 0
            )
        },
        dismissButton = {
            AutoLogueSecondaryButton(
                text = "취소",
                onClick = onDismiss
            )
        },
        containerColor = AppColors.surface,
        shape = AppShapes.modal
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun EditTransactionDialog(
    transaction: Transaction,
    onDismiss: () -> Unit,
    onSave: (category: ExpenseCategory, memo: String?) -> Unit,
    onDelete: () -> Unit
) {
    var selectedCategory by remember { mutableStateOf(transaction.category) }
    var memo by remember { mutableStateOf(transaction.transferMemo ?: "") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = "결제 내역 수정 및 메모",
                style = AppTypography.h2
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // Read-only Transaction Summary with shadow
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = Slate50,
                    shadowElevation = 1.5.dp,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(Spacing.md)) {
                        Text(
                            text = transaction.merchantName,
                            style = AppTypography.h2
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "%,d원".format(transaction.amount),
                            style = AppTypography.h1.copy(color = AppColors.primary, fontWeight = FontWeight.Bold)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "${transaction.timestamp.format(DateTimeFormatter.ofPattern("yyyy.MM.dd(E) HH:mm", Locale.KOREA))} · ${transaction.cardOrBankName ?: "결제"}",
                            style = AppTypography.captionMuted
                        )
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xs))

                Text("카테고리 변경", style = AppTypography.caption)
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    ExpenseCategory.values().forEach { cat ->
                        val isSelected = cat == selectedCategory
                        Surface(
                            onClick = { selectedCategory = cat },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSelected) Color(0xFF2563EB) else Slate50,
                            shadowElevation = if (isSelected) 2.dp else 1.dp,
                            border = BorderStroke(1.dp, if (isSelected) Color(0xFF1D4ED8) else Slate200)
                        ) {
                            Text(
                                text = cat.displayName,
                                fontSize = 11.sp,
                                fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSelected) PureWhite else Slate700,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(Spacing.xs))

                AutoLogueTextField(
                    value = memo,
                    onValueChange = { memo = it },
                    label = "메모 작성",
                    placeholder = "이 지출에 대한 메모를 작성하세요 (예: 팀 점심 회식, 장비 구매)",
                    singleLine = false,
                    minLines = 2,
                    maxLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                AutoLogueDangerButton(
                    text = "삭제",
                    onClick = onDelete
                )

                AutoLoguePrimaryButton(
                    text = "저장",
                    onClick = { onSave(selectedCategory, memo) }
                )
            }
        },
        dismissButton = {
            AutoLogueSecondaryButton(
                text = "취소",
                onClick = onDismiss
            )
        },
        containerColor = AppColors.surface,
        shape = AppShapes.modal
    )
}

/**
 * 카테고리 지출 순위에 따라 무지개 색상(최다 사용량: 붉은색 -> 최소 사용량: 파란색)을 반환하는 헬퍼 함수
 */
fun getRainbowUsageColor(rankIndex: Int, totalCount: Int): Color {
    if (totalCount <= 1) return Color(0xFFE53935)

    val rainbowColors = listOf(
        Color(0xFFE53935), // 1. 짙은 레드 (가장 많음)
        Color(0xFFFF5722), // 2. 레드 오렌지
        Color(0xFFFB8C00), // 3. 비비드 오렌지
        Color(0xFFFBC02D), // 4. 골드/옐로우
        Color(0xFF7CB342), // 5. 라임 그린
        Color(0xFF43A047), // 6. 에메랄드 그린
        Color(0xFF00ACC1), // 7. 청록 / 시안
        Color(0xFF039BE5), // 8. 스카이 블루
        Color(0xFF1E88E5), // 9. 로얄 블루
        Color(0xFF3949AB)  // 10. 딥 블루 (가장 적음)
    )

    val ratio = rankIndex.toFloat() / (totalCount - 1).coerceAtLeast(1)
    val colorIndex = (ratio * (rainbowColors.size - 1)).toInt().coerceIn(0, rainbowColors.size - 1)
    return rainbowColors[colorIndex]
}
