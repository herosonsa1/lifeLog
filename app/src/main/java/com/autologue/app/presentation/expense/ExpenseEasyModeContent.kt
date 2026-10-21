package com.autologue.app.presentation.expense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.domain.model.isDepositTransaction
import com.autologue.app.domain.model.isSelfTransfer
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.theme.*
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

private val EASY_DAY_HEADER_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M월 d일 (E)", Locale.KOREA)
private val EASY_TIME_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("HH:mm", Locale.KOREA)

private data class EasyDateTransactionGroup(
    val date: LocalDate,
    val headerLabel: String,
    val dayExpenseTotal: Long,
    val dayIncomeTotal: Long,
    val transactions: List<Transaction>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ExpenseEasyModeContent(
    uiState: ExpenseUiState,
    activePageTab: ExpensePageTab,
    onSelectPageTab: (ExpensePageTab) -> Unit,
    filteredTransactions: List<Transaction>,
    filteredExpenseTotal: Long,
    filteredIncomeTotal: Long,
    quickFilterSnapshot: ExpenseQuickFilterSnapshot,
    rowMetaByTxId: Map<Long, PrecomputedRowMeta>,
    categoryStatItems: List<CategoryStatItem>,
    userStatItems: List<UserClassificationStatItem>,
    donutDimension: ExpenseDonutDimension,
    onDonutDimensionChange: (ExpenseDonutDimension) -> Unit,
    selectedDonutSliceKey: String?,
    onSelectDonutSlice: (String) -> Unit,
    onNavigateToSliceTransactions: (DonutSliceData) -> Unit,
    comparisonReport: ExpensePeriodComparisonReport,
    analyticsSubTab: ExpenseAnalyticsSubTab,
    onSelectAnalyticsSubTab: (ExpenseAnalyticsSubTab) -> Unit,
    analyticsSortMode: ExpenseDeltaSortMode,
    onSelectAnalyticsSortMode: (ExpenseDeltaSortMode) -> Unit,
    onSelectAnalyticsPeriodUnit: (AnalyticsPeriodUnit) -> Unit,
    onPreviousAnalyticsPeriod: () -> Unit,
    onNextAnalyticsPeriod: () -> Unit,
    onSelectAnalyticsComparisonBasis: (AnalyticsComparisonBasis) -> Unit,
    onNavigateToCategoryTransactions: (String, PeriodSpec) -> Unit,
    getBuiltInCategoryDisplayName: (ExpenseCategory) -> String,
    resolveDisplayCardName: (Transaction) -> String,
    onPreviousMonth: () -> Unit,
    onNextMonth: () -> Unit,
    onOpenDateRangeDialog: () -> Unit,
    onOpenAddDialog: () -> Unit,
    onTriggerSmsSync: () -> Unit,
    onOpenBudgetDialog: () -> Unit,
    onOpenCategoryManage: () -> Unit,
    onOpenUserManage: () -> Unit,
    onOpenAccountManage: () -> Unit,
    onOpenPermanentRules: () -> Unit,
    onOpenPasteSms: () -> Unit,
    onExportExcel: () -> Unit,
    onToggleSelectionMode: () -> Unit,
    onSelectAllFiltered: () -> Unit,
    onDeleteSelected: () -> Unit,
    onClearAllSubFilters: () -> Unit,
    onSetCategoryFilter: (ExpenseCategory?) -> Unit,
    onToggleCategoryFilter: (ExpenseCategory) -> Unit,
    onSetCustomCategoryFilter: (String?) -> Unit,
    onToggleRecurringFilter: () -> Unit,
    onToggleUserFilter: (String?) -> Unit,
    onSetCorporateFilter: (Boolean?) -> Unit,
    onTransactionClick: (Transaction) -> Unit
) {
    var showToolsBottomSheet by remember { mutableStateOf(false) }

    // 일자별 거래 그룹핑 (토스 '내 소비' 스타일)
    val dateGroups = remember(filteredTransactions) {
        filteredTransactions
            .groupBy { it.timestamp.toLocalDate() }
            .entries
            .sortedByDescending { it.key }
            .map { (date, list) ->
                var expSum = 0L
                var incSum = 0L
                for (tx in list) {
                    val isInc = tx.category == ExpenseCategory.INCOME || tx.isDepositTransaction()
                    if (isInc) {
                        incSum += tx.amount
                    } else if (!tx.isSelfTransfer()) {
                        expSum += tx.amount
                    }
                }
                EasyDateTransactionGroup(
                    date = date,
                    headerLabel = date.format(EASY_DAY_HEADER_FORMATTER),
                    dayExpenseTotal = expSum,
                    dayIncomeTotal = incSum,
                    transactions = list
                )
            }
    }

    val permRuleTotal = uiState.permanentMerchantRules.size +
        uiState.cardCorporateRules.size +
        uiState.rules.size

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(EasyTheme.bg)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            EasyModeTopBar(
                title = "내 소비",
                subtitle = "카드·계좌 지출과 예산을 한눈에",
                rightExtraAction = {
                    Surface(
                        onClick = { showToolsBottomSheet = true },
                        shape = RoundedCornerShape(18.dp),
                        color = EasyTheme.surface,
                        border = BorderStroke(1.dp, EasyTheme.borderSubtle)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.MoreHoriz,
                                contentDescription = "가계부 관리 도구",
                                tint = EasyTheme.textSecondary,
                                modifier = Modifier.size(15.dp)
                            )
                            Text(
                                text = "관리",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = EasyTheme.textSecondary,
                                maxLines = 1,
                                softWrap = false
                            )
                        }
                    }
                }
            )

            LazyColumn(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = 4.dp,
                    bottom = 96.dp
                ),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 토스 스타일 상단 히어로 소비 요약 & 3탭 전환 카드
                item(key = "easy_expense_hero") {
                    EasySectionCard(
                        contentPadding = PaddingValues(20.dp)
                    ) {
                        EasyDateNavigatorRow(
                            title = uiState.dateDisplayTitle,
                            onPrevious = onPreviousMonth,
                            onNext = onNextMonth,
                            onTitleClick = onOpenDateRangeDialog,
                            trailingAction = {
                                Surface(
                                    onClick = onOpenDateRangeDialog,
                                    shape = RoundedCornerShape(12.dp),
                                    color = if (uiState.hasActiveSubFilter) EasyTheme.tossBlueSoft else EasyTheme.chipBg
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.FilterList,
                                            contentDescription = "상세 필터",
                                            tint = if (uiState.hasActiveSubFilter) EasyTheme.tossBlue else EasyTheme.textSecondary,
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = if (uiState.hasActiveSubFilter) "필터 켜짐" else "상세조건",
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (uiState.hasActiveSubFilter) EasyTheme.tossBlue else EasyTheme.textSecondary,
                                            maxLines = 1,
                                            softWrap = false
                                        )
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(14.dp))

                        // 대형 히어로 지출 금액 표시
                        Text(
                            text = "${uiState.dateDisplayTitle} 쓴 돈",
                            fontSize = 13.5.sp,
                            fontWeight = FontWeight.Medium,
                            color = EasyTheme.textSecondary
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.Bottom
                        ) {
                            Text(
                                text = "%,d원".format(uiState.totalExpense),
                                fontSize = 30.sp,
                                fontWeight = FontWeight.ExtraBold,
                                letterSpacing = (-0.8).sp,
                                color = EasyTheme.textPrimary
                            )
                            if (uiState.totalIncome > 0L) {
                                Surface(
                                    shape = RoundedCornerShape(10.dp),
                                    color = EasyTheme.appleGreenSoft
                                ) {
                                    Text(
                                        text = "수입 +%,d원".format(uiState.totalIncome),
                                        fontSize = 12.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.appleGreen,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // 월간 예산 바 (월간 조회 시)
                        if (uiState.filterMode == ExpenseDateFilterMode.MONTH) {
                            Spacer(modifier = Modifier.height(14.dp))
                            val budget = uiState.monthlyBudget.coerceAtLeast(1L)
                            val spent = uiState.totalExpense
                            val remaining = uiState.monthlyBudget - spent
                            val progressRatio = (spent.toFloat() / budget.toFloat()).coerceIn(0f, 1f)
                            val barColor = when {
                                spent > budget -> EasyTheme.appleRed
                                progressRatio >= 0.85f -> EasyTheme.appleAmber
                                else -> EasyTheme.tossBlue
                            }

                            Surface(
                                onClick = onOpenBudgetDialog,
                                shape = RoundedCornerShape(14.dp),
                                color = EasyTheme.surfaceSubtle,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "한 달 예산 %,d원".format(uiState.monthlyBudget),
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = EasyTheme.textSecondary
                                        )
                                        Text(
                                            text = if (remaining >= 0) {
                                                "%,d원 남음".format(remaining)
                                            } else {
                                                "%,d원 초과".format(-remaining)
                                            },
                                            fontSize = 12.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (remaining >= 0) EasyTheme.tossBlue else EasyTheme.appleRed
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(8.dp))
                                    LinearProgressIndicator(
                                        progress = { progressRatio },
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .height(6.dp)
                                            .clip(RoundedCornerShape(3.dp)),
                                        color = barColor,
                                        trackColor = Color(0xFFE5E8EB)
                                    )
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 3대 핵심 원탭 액션 버튼: [+ 내역 추가] [문자 불러오기] [관리 도구]
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            EasyActionButton(
                                text = "내역 추가",
                                icon = Icons.Default.Add,
                                onClick = onOpenAddDialog,
                                isPrimary = true,
                                modifier = Modifier.weight(1f)
                            )
                            EasyActionButton(
                                text = "문자 동기화",
                                icon = Icons.Default.Sync,
                                onClick = onTriggerSmsSync,
                                isPrimary = false,
                                modifier = Modifier.weight(1f)
                            )
                            EasyActionButton(
                                text = "도구·규칙",
                                icon = Icons.Default.Tune,
                                onClick = { showToolsBottomSheet = true },
                                isPrimary = false,
                                modifier = Modifier.weight(0.9f)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 3분할 세그먼트: [결제 내역] | [소비 비중] | [전월 비교]
                        EasySegmentedBar(
                            options = listOf(
                                ExpensePageTab.TRANSACTIONS to "내역 (${filteredTransactions.size})",
                                ExpensePageTab.STATISTICS to "소비 비중",
                                ExpensePageTab.PERIOD_COMPARISON to "기간 비교"
                            ),
                            selected = activePageTab,
                            onSelect = onSelectPageTab
                        )
                    }
                }

                if (activePageTab == ExpensePageTab.TRANSACTIONS) {
                    // 2. 단일 가로 스크롤 빠른 필터 칩 바
                    item(key = "easy_filter_row") {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val isAllSelected = !uiState.hasActiveSubFilter
                                EasyFilterPill(
                                    label = "전체 ${uiState.transactions.size}",
                                    isSelected = isAllSelected,
                                    onClick = onClearAllSubFilters
                                )

                                EasyFilterPill(
                                    label = "고정지출 ${quickFilterSnapshot.recurringCount}",
                                    isSelected = uiState.isRecurringFilterOnly,
                                    onClick = onToggleRecurringFilter
                                )

                                if (quickFilterSnapshot.corporateCount > 0 || uiState.selectedCorporateFilter == true) {
                                    EasyFilterPill(
                                        label = "법인카드 ${quickFilterSnapshot.corporateCount}",
                                        isSelected = uiState.selectedCorporateFilter == true,
                                        onClick = {
                                            onSetCorporateFilter(
                                                if (uiState.selectedCorporateFilter == true) null else true
                                            )
                                        }
                                    )
                                }

                                // 사용자 분류 칩
                                uiState.userClassifications.forEach { uc ->
                                    val count = quickFilterSnapshot.userCounts[uc.name] ?: 0
                                    if (count > 0 || uiState.selectedUserClassification == uc.name) {
                                        EasyFilterPill(
                                            label = "${uc.name} $count",
                                            isSelected = uiState.selectedUserClassification == uc.name,
                                            onClick = { onToggleUserFilter(uc.name) }
                                        )
                                    }
                                }

                                // 주요 카테고리 칩
                                val builtInCats = remember(uiState.categoryTotals, uiState.managedCategories) {
                                    (uiState.categoryTotals.keys +
                                        uiState.managedCategories.filter { it.isBuiltIn }.map { it.baseCategory }).distinct()
                                }
                                builtInCats.filter { it != ExpenseCategory.ETC }.forEach { cat ->
                                    val count = quickFilterSnapshot.builtInCatCounts[cat] ?: 0
                                    val isSel = uiState.selectedCategory == cat &&
                                        uiState.selectedCustomCategoryName == null &&
                                        !uiState.isRecurringFilterOnly
                                    if (count > 0 || isSel) {
                                        val name = getBuiltInCategoryDisplayName(cat)
                                        EasyFilterPill(
                                            label = "$name $count",
                                            isSelected = isSel,
                                            onClick = { onToggleCategoryFilter(cat) }
                                        )
                                    }
                                }

                                uiState.managedCategories.filter { !it.isBuiltIn }.forEach { customItem ->
                                    val count = quickFilterSnapshot.customCatCounts[customItem.displayName] ?: 0
                                    val isSel = uiState.selectedCustomCategoryName == customItem.displayName
                                    if (count > 0 || isSel) {
                                        EasyFilterPill(
                                            label = "${customItem.displayName} $count",
                                            isSelected = isSel,
                                            onClick = {
                                                onSetCustomCategoryFilter(if (isSel) null else customItem.displayName)
                                            }
                                        )
                                    }
                                }

                                if (ExpenseCategory.ETC in builtInCats) {
                                    val count = quickFilterSnapshot.builtInCatCounts[ExpenseCategory.ETC] ?: 0
                                    val isSel = uiState.selectedCategory == ExpenseCategory.ETC &&
                                        uiState.selectedCustomCategoryName == null &&
                                        !uiState.isRecurringFilterOnly
                                    if (count > 0 || isSel) {
                                        val name = getBuiltInCategoryDisplayName(ExpenseCategory.ETC)
                                        EasyFilterPill(
                                            label = "$name $count",
                                            isSelected = isSel,
                                            onClick = { onToggleCategoryFilter(ExpenseCategory.ETC) }
                                        )
                                    }
                                }
                            }

                            // 필터가 켜져 있을 때 선택 조건 합계 배너
                            if (uiState.hasActiveSubFilter) {
                                val activeLabel = uiState.buildActiveFilterLabel(getBuiltInCategoryDisplayName)
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = EasyTheme.tossBlueSoft,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 16.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = activeLabel,
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.tossBlue
                                            )
                                            Text(
                                                text = if (filteredIncomeTotal > 0L && filteredExpenseTotal == 0L) {
                                                    "수입 합계 +%,d원 (${filteredTransactions.size}건)".format(filteredIncomeTotal)
                                                } else {
                                                    "지출 합계 %,d원 (${filteredTransactions.size}건)".format(filteredExpenseTotal)
                                                },
                                                fontSize = 14.5.sp,
                                                fontWeight = FontWeight.ExtraBold,
                                                color = EasyTheme.textPrimary
                                            )
                                        }
                                        Surface(
                                            onClick = onClearAllSubFilters,
                                            shape = RoundedCornerShape(10.dp),
                                            color = EasyTheme.surface
                                        ) {
                                            Text(
                                                text = "필터 해제",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.tossBlue,
                                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                                            )
                                        }
                                    }
                                }
                            }

                            // 다중 선택 삭제 모드일 때 상단 액션 바
                            if (uiState.isSelectionMode) {
                                Surface(
                                    shape = RoundedCornerShape(16.dp),
                                    color = EasyTheme.appleRedSoft,
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 14.dp, vertical = 10.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            modifier = Modifier.clickable { onSelectAllFiltered() }
                                        ) {
                                            Checkbox(
                                                checked = filteredTransactions.isNotEmpty() &&
                                                    uiState.selectedTransactionIds.size == filteredTransactions.size,
                                                onCheckedChange = { onSelectAllFiltered() }
                                            )
                                            Text(
                                                text = "전체 선택 (${uiState.selectedTransactionIds.size}건)",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.textPrimary
                                            )
                                        }
                                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                            Surface(
                                                onClick = onToggleSelectionMode,
                                                shape = RoundedCornerShape(10.dp),
                                                color = EasyTheme.surface
                                            ) {
                                                Text(
                                                    text = "취소",
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = EasyTheme.textSecondary,
                                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp)
                                                )
                                            }
                                            Surface(
                                                onClick = onDeleteSelected,
                                                enabled = uiState.selectedTransactionIds.isNotEmpty(),
                                                shape = RoundedCornerShape(10.dp),
                                                color = EasyTheme.appleRed
                                            ) {
                                                Text(
                                                    text = "${uiState.selectedTransactionIds.size}건 삭제",
                                                    fontSize = 12.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 3. 일자별 그룹핑된 결제 내역 리스트 (토스 내 소비 스타일)
                    if (dateGroups.isEmpty()) {
                        item(key = "easy_empty_tx") {
                            EasySectionCard(
                                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 36.dp)
                            ) {
                                Column(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalAlignment = Alignment.CenterHorizontally,
                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    Text(
                                        text = "표시할 결제 내역이 없습니다",
                                        fontSize = 16.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.textPrimary
                                    )
                                    Text(
                                        text = "상단의 [문자 동기화] 또는 [내역 추가]를 눌러\n소비 기록을 불러오거나 추가해보세요.",
                                        fontSize = 13.sp,
                                        color = EasyTheme.textMuted
                                    )
                                }
                            }
                        }
                    } else {
                        items(
                            items = dateGroups,
                            key = { it.date.toEpochDay() }
                        ) { group ->
                            EasySectionCard(
                                contentPadding = PaddingValues(horizontal = 18.dp, vertical = 16.dp)
                            ) {
                                // 일자 헤더 행 (예: 10월 20일 화요일 | -48,500원)
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(bottom = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = group.headerLabel,
                                        fontSize = 13.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = EasyTheme.textSecondary
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                        if (group.dayIncomeTotal > 0L) {
                                            Text(
                                                text = "+%,d원".format(group.dayIncomeTotal),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.appleGreen
                                            )
                                        }
                                        if (group.dayExpenseTotal > 0L) {
                                            Text(
                                                text = "-%,d원".format(group.dayExpenseTotal),
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.textMuted
                                            )
                                        }
                                    }
                                }

                                HorizontalDivider(color = EasyTheme.divider)

                                group.transactions.forEachIndexed { idx, tx ->
                                    val meta = rowMetaByTxId[tx.id]
                                    val catName = meta?.effectiveCatName ?: tx.category.displayName
                                    val userClass = uiState.userClassByTxId[tx.id]
                                    val isCorp = uiState.corporateByTxId[tx.id] == true
                                    val isRecurring = tx.id in uiState.recurringTransactionIds
                                    val isSelectedForDelete = tx.id in uiState.selectedTransactionIds
                                    val cardLabel = resolveDisplayCardName(tx)

                                    EasyTossTransactionRow(
                                        transaction = tx,
                                        categoryName = catName,
                                        cardLabel = cardLabel,
                                        userLabel = userClass?.name,
                                        isCorporate = isCorp,
                                        isRecurring = isRecurring,
                                        isSelectionMode = uiState.isSelectionMode,
                                        isSelected = isSelectedForDelete,
                                        onClick = { onTransactionClick(tx) }
                                    )

                                    if (idx < group.transactions.lastIndex) {
                                        HorizontalDivider(
                                            color = EasyTheme.divider,
                                            modifier = Modifier.padding(start = 52.dp)
                                        )
                                    }
                                }
                            }
                        }
                    }
                } else if (activePageTab == ExpensePageTab.STATISTICS) {
                    // 소비 비중 탭: 도넛 차트 + 카테고리/사용자 순위
                    item(key = "easy_stat_donut") {
                        val donutSlices = remember(donutDimension, categoryStatItems, userStatItems) {
                            if (donutDimension == ExpenseDonutDimension.BY_CATEGORY) {
                                val totalCnt = categoryStatItems.size.coerceAtLeast(1)
                                if (categoryStatItems.size <= 6) {
                                    categoryStatItems.mapIndexed { idx, item ->
                                        DonutSliceData(
                                            key = item.displayName,
                                            label = item.displayName,
                                            amount = item.amount,
                                            txCount = item.txCount,
                                            percentage = item.percentage,
                                            color = getRainbowUsageColor(idx, totalCnt)
                                        )
                                    }
                                } else {
                                    val top5 = categoryStatItems.take(5).mapIndexed { idx, item ->
                                        DonutSliceData(
                                            key = item.displayName,
                                            label = item.displayName,
                                            amount = item.amount,
                                            txCount = item.txCount,
                                            percentage = item.percentage,
                                            color = getRainbowUsageColor(idx, 6)
                                        )
                                    }
                                    val rest = categoryStatItems.drop(5)
                                    top5 + DonutSliceData(
                                        key = "__OTHER_CAT__",
                                        label = "기타 (${rest.size}개)",
                                        amount = rest.sumOf { it.amount },
                                        txCount = rest.sumOf { it.txCount },
                                        percentage = rest.sumOf { it.percentage.toDouble() }.toFloat(),
                                        color = Slate400,
                                        isOtherBucket = true
                                    )
                                }
                            } else {
                                userStatItems.filter { it.amount > 0L }.mapIndexed { idx, uItem ->
                                    val (fg, _) = if (uItem.isUnassigned) {
                                        Slate500 to Slate100
                                    } else {
                                        getUserClassificationBadgeColors(uItem.colorIndex ?: idx)
                                    }
                                    DonutSliceData(
                                        key = uItem.userKey,
                                        label = uItem.displayName,
                                        amount = uItem.amount,
                                        txCount = uItem.txCount,
                                        percentage = uItem.percentage,
                                        color = fg
                                    )
                                }
                            }
                        }

                        ExpenseDonutChartCard(
                            dateTitle = uiState.dateDisplayTitle,
                            totalExpense = uiState.totalExpense,
                            totalIncome = uiState.totalIncome,
                            dimension = donutDimension,
                            onDimensionChange = onDonutDimensionChange,
                            slices = donutSlices,
                            selectedSliceKey = selectedDonutSliceKey,
                            onSelectSlice = onSelectDonutSlice,
                            onNavigateToSliceTransactions = onNavigateToSliceTransactions
                        )
                    }

                    // 카테고리별 순위 클린 리스트
                    item(key = "easy_stat_cat_list") {
                        EasySectionCard(
                            contentPadding = PaddingValues(20.dp)
                        ) {
                            Text(
                                text = "어디에 가장 많이 썼을까요?",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = EasyTheme.textPrimary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "항목을 누르면 해당 결제 내역만 모아서 보여줍니다",
                                fontSize = 12.5.sp,
                                color = EasyTheme.textMuted
                            )
                            Spacer(modifier = Modifier.height(14.dp))

                            categoryStatItems.forEachIndexed { idx, item ->
                                val barColor = getRainbowUsageColor(idx, categoryStatItems.size.coerceAtLeast(1))
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clip(RoundedCornerShape(12.dp))
                                        .clickable {
                                            onNavigateToSliceTransactions(
                                                DonutSliceData(
                                                    key = item.displayName,
                                                    label = item.displayName,
                                                    amount = item.amount,
                                                    txCount = item.txCount,
                                                    percentage = item.percentage,
                                                    color = barColor
                                                )
                                            )
                                        }
                                        .padding(vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Box(
                                            modifier = Modifier
                                                .size(34.dp)
                                                .clip(CircleShape)
                                                .background(barColor.copy(alpha = 0.14f)),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "${idx + 1}",
                                                fontSize = 13.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = barColor
                                            )
                                        }
                                        Column {
                                            Text(
                                                text = item.displayName,
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = EasyTheme.textPrimary
                                            )
                                            Text(
                                                text = "%.1f%% · %d건".format(item.percentage, item.txCount),
                                                fontSize = 12.sp,
                                                color = EasyTheme.textMuted
                                            )
                                        }
                                    }
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                                    ) {
                                        Text(
                                            text = "%,d원".format(item.amount),
                                            fontSize = 15.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = EasyTheme.textPrimary
                                        )
                                        Icon(
                                            imageVector = Icons.Default.ChevronRight,
                                            contentDescription = null,
                                            tint = EasyTheme.textMuted,
                                            modifier = Modifier.size(16.dp)
                                        )
                                    }
                                }
                                if (idx < categoryStatItems.lastIndex) {
                                    HorizontalDivider(color = EasyTheme.divider)
                                }
                            }
                        }
                    }
                } else {
                    // 기간 비교 탭
                    item(key = "easy_period_comp") {
                        ExpensePeriodComparisonSection(
                            report = comparisonReport,
                            selectedSubTab = analyticsSubTab,
                            onSelectSubTab = onSelectAnalyticsSubTab,
                            selectedSortMode = analyticsSortMode,
                            onSelectSortMode = onSelectAnalyticsSortMode,
                            onSelectPeriodUnit = onSelectAnalyticsPeriodUnit,
                            onPreviousPeriod = onPreviousAnalyticsPeriod,
                            onNextPeriod = onNextAnalyticsPeriod,
                            onSelectComparisonBasis = onSelectAnalyticsComparisonBasis,
                            onNavigateToCategoryTransactions = onNavigateToCategoryTransactions
                        )
                    }
                }
            }
        }

        // 가계부 관리 · 도구 모달 바텀시트 (모든 고급 관리 기능 100% 제공)
        if (showToolsBottomSheet) {
            val sheetContext = androidx.compose.ui.platform.LocalContext.current
            ModalBottomSheet(
                onDismissRequest = { showToolsBottomSheet = false },
                containerColor = EasyTheme.bg,
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFFD1D6DB)) }
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp)
                        .padding(bottom = 36.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = "가계부 관리 및 도구",
                        fontSize = 19.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = EasyTheme.textPrimary
                    )

                    EasySectionCard(contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)) {
                        EasyToolSheetRow(
                            title = "영구저장 자동 분류 규칙 관리",
                            subtitle = "등록된 자동 치환·카테고리 규칙 ${permRuleTotal}건 조회 및 해제",
                            icon = Icons.Default.AutoFixHigh,
                            accent = EasyTheme.tossBlue,
                            onClick = {
                                showToolsBottomSheet = false
                                onOpenPermanentRules()
                            }
                        )
                        HorizontalDivider(color = EasyTheme.divider)
                        EasyToolSheetRow(
                            title = "카테고리 추가 · 이름 변경",
                            subtitle = "식비, 쇼핑 등 기본 카테고리와 나만의 분류 관리",
                            icon = Icons.Default.Category,
                            accent = EasyTheme.appleGreen,
                            onClick = {
                                showToolsBottomSheet = false
                                onOpenCategoryManage()
                            }
                        )
                        HorizontalDivider(color = EasyTheme.divider)
                        EasyToolSheetRow(
                            title = "사용자별 분류 관리",
                            subtitle = "본인, 가족, 회식 등 사용자 태그 추가 및 편집",
                            icon = Icons.Default.Group,
                            accent = EasyTheme.applePurple,
                            onClick = {
                                showToolsBottomSheet = false
                                onOpenUserManage()
                            }
                        )
                        HorizontalDivider(color = EasyTheme.divider)
                        EasyToolSheetRow(
                            title = "내 계좌 · 카드 별칭 관리",
                            subtitle = "입출금 계좌 패턴 및 별칭 설정",
                            icon = Icons.Default.AccountBalance,
                            accent = EasyTheme.textPrimary,
                            onClick = {
                                showToolsBottomSheet = false
                                onOpenAccountManage()
                            }
                        )
                        HorizontalDivider(color = EasyTheme.divider)
                        EasyToolSheetRow(
                            title = "월간 목표 예산 설정",
                            subtitle = "현재 한 달 목표 예산: %,d원".format(uiState.monthlyBudget),
                            icon = Icons.Default.TrackChanges,
                            accent = EasyTheme.tossBlue,
                            onClick = {
                                showToolsBottomSheet = false
                                onOpenBudgetDialog()
                            }
                        )
                    }

                    EasySectionCard(contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp)) {
                        EasyToolSheetRow(
                            title = "결제 문자 직접 붙여넣기",
                            subtitle = "복사한 카드·은행 문자를 붙여넣어 즉시 등록",
                            icon = Icons.Default.ContentPaste,
                            accent = EasyTheme.appleAmber,
                            onClick = {
                                showToolsBottomSheet = false
                                onOpenPasteSms()
                            }
                        )
                        HorizontalDivider(color = EasyTheme.divider)
                        EasyToolSheetRow(
                            title = "가계부 엑셀 파일 내보내기",
                            subtitle = "전체 거래 내역을 엑셀(.xlsx)로 저장 및 공유",
                            icon = Icons.Default.FileDownload,
                            accent = EasyTheme.appleGreen,
                            onClick = {
                                showToolsBottomSheet = false
                                onExportExcel()
                            }
                        )
                        HorizontalDivider(color = EasyTheme.divider)
                        EasyToolSheetRow(
                            title = if (uiState.isSelectionMode) "선택 삭제 모드 끄기" else "여러 내역 선택 삭제",
                            subtitle = "원하는 내역들을 체크하여 한 번에 삭제합니다",
                            icon = Icons.Default.DeleteSweep,
                            accent = EasyTheme.appleRed,
                            onClick = {
                                showToolsBottomSheet = false
                                onToggleSelectionMode()
                            }
                        )
                        HorizontalDivider(color = EasyTheme.divider)
                        EasyToolSheetRow(
                            title = "고급모드로 전환 (기존 상세 UI/UX)",
                            subtitle = "다중 필터 바와 전문가용 상세 제어 화면으로 전환",
                            icon = Icons.Default.Tune,
                            accent = EasyTheme.tossBlue,
                            onClick = {
                                showToolsBottomSheet = false
                                EasyModeManager.setEasyMode(sheetContext, false)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EasyFilterPill(
    label: String,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(20.dp),
        color = if (isSelected) EasyTheme.textPrimary else EasyTheme.surface,
        border = if (isSelected) null else BorderStroke(1.dp, EasyTheme.borderSubtle)
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Color.White else EasyTheme.textSecondary,
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun EasyTossTransactionRow(
    transaction: Transaction,
    categoryName: String,
    cardLabel: String,
    userLabel: String?,
    isCorporate: Boolean,
    isRecurring: Boolean,
    isSelectionMode: Boolean,
    isSelected: Boolean,
    onClick: () -> Unit
) {
    val isIncome = transaction.category == ExpenseCategory.INCOME || transaction.isDepositTransaction()
    val isSelfTransfer = transaction.isSelfTransfer()

    val circleBg = when {
        isIncome -> EasyTheme.appleGreenSoft
        isCorporate -> EasyTheme.appleAmberSoft
        isRecurring -> EasyTheme.applePurpleSoft
        else -> EasyTheme.tossBlueSoft
    }
    val circleText = when {
        isIncome -> EasyTheme.appleGreen
        isCorporate -> EasyTheme.appleAmber
        isRecurring -> EasyTheme.applePurple
        else -> EasyTheme.tossBlue
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelected,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 6.dp)
            )
        }

        // 좌측 카테고리 원형 아바타
        Box(
            modifier = Modifier
                .size(40.dp)
                .clip(CircleShape)
                .background(circleBg),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = categoryName.take(2),
                fontSize = 12.sp,
                fontWeight = FontWeight.ExtraBold,
                color = circleText
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        // 중앙 가맹점명 & 서브 정보
        Column(modifier = Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Text(
                    text = transaction.merchantName,
                    fontSize = 15.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = EasyTheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false)
                )
                if (isCorporate) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = EasyTheme.appleAmberSoft
                    ) {
                        Text(
                            text = "법인",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = EasyTheme.appleAmber,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                if (isRecurring) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = EasyTheme.applePurpleSoft
                    ) {
                        Text(
                            text = "고정",
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = EasyTheme.applePurple,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
                if (!userLabel.isNullOrBlank()) {
                    Surface(
                        shape = RoundedCornerShape(6.dp),
                        color = EasyTheme.chipBg
                    ) {
                        Text(
                            text = userLabel,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = EasyTheme.textSecondary,
                            modifier = Modifier.padding(horizontal = 5.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(3.dp))

            val timeStr = transaction.timestamp.format(EASY_TIME_FORMATTER)
            val metaLine = buildString {
                append(categoryName)
                if (cardLabel.isNotBlank()) {
                    append(" · ")
                    append(cardLabel)
                }
                append(" · ")
                append(timeStr)
            }
            Text(
                text = metaLine,
                fontSize = 12.5.sp,
                color = EasyTheme.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Spacer(modifier = Modifier.width(10.dp))

        // 우측 금액
        val amountPrefix = when {
            isIncome -> "+"
            isSelfTransfer -> ""
            else -> "-"
        }
        val amountColor = when {
            isIncome -> EasyTheme.appleGreen
            isSelfTransfer -> EasyTheme.textMuted
            else -> EasyTheme.textPrimary
        }

        Text(
            text = "%s%,d원".format(amountPrefix, transaction.amount),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = amountColor
        )
    }
}

@Composable
internal fun EasyToolSheetRow(
    title: String,
    subtitle: String,
    icon: ImageVector,
    accent: Color,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(CircleShape)
                .background(accent.copy(alpha = 0.12f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = accent,
                modifier = Modifier.size(19.dp)
            )
        }
        Spacer(modifier = Modifier.width(14.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = EasyTheme.textPrimary
            )
            Spacer(modifier = Modifier.height(2.dp))
            Text(
                text = subtitle,
                fontSize = 12.sp,
                color = EasyTheme.textMuted
            )
        }
        Icon(
            imageVector = Icons.Default.ChevronRight,
            contentDescription = null,
            tint = EasyTheme.textMuted,
            modifier = Modifier.size(18.dp)
        )
    }
}
