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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.EditNote
import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.compose.ui.platform.LocalContext
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material3.*
import com.autologue.app.data.preferences.UserAccount
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
import com.autologue.app.domain.model.isBankAccountTransaction
import com.autologue.app.domain.model.isDepositTransaction
import com.autologue.app.domain.model.isSelfTransfer
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
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val smsPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { perms ->
        val granted = perms[Manifest.permission.READ_SMS] == true
        if (granted) {
            viewModel.syncHistoricalSms(context)
        }
    }

    LaunchedEffect(uiState.syncResultMessage) {
        val msg = uiState.syncResultMessage
        if (!msg.isNullOrBlank()) {
            snackbarHostState.showSnackbar(msg)
            viewModel.dismissResultMessage()
        }
    }

    if (uiState.isPasteSmsDialogOpen) {
        PasteSmsDialog(
            onDismiss = { viewModel.closePasteSmsDialog() },
            onConfirm = { transactions ->
                viewModel.importPastedTransactions(transactions)
            }
        )
    }

    Scaffold(
        containerColor = AppColors.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth().windowInsetsPadding(TopAppBarDefaults.windowInsets)) {
                TopMenuAccentBar(color = MenuColors.expense)
                TopAppBar(
                    windowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
                    title = {
                        Column {
                            Text(
                                text = "스마트 가계부",
                                style = AppTypography.h2
                            )
                            Text(
                                text = "소비·지출 관리",
                                style = AppTypography.caption.copy(
                                    color = MenuColors.expense,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    },
                    actions = {
                        AutoLogueOutlinedButton(
                            text = "문자 동기화",
                            icon = Icons.Default.Sync,
                            onClick = {
                                val hasPerm = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.READ_SMS
                                ) == PackageManager.PERMISSION_GRANTED
                                if (hasPerm) {
                                    viewModel.syncHistoricalSms(context)
                                } else {
                                    smsPermissionLauncher.launch(
                                        arrayOf(Manifest.permission.READ_SMS, Manifest.permission.RECEIVE_SMS)
                                    )
                                }
                            },
                            contentColor = MenuColors.expense,
                            containerColor = MenuColors.expenseBg,
                            borderColor = MenuColors.expenseBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "지출 추가",
                            icon = Icons.Default.Add,
                            onClick = { viewModel.openAddDialog() },
                            contentColor = MenuColors.expense,
                            containerColor = MenuColors.expenseBg,
                            borderColor = MenuColors.expenseBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
                        AutoLogueOutlinedButton(
                            text = "문자 붙여넣기",
                            icon = Icons.Default.ContentPaste,
                            onClick = { viewModel.openPasteSmsDialog() },
                            contentColor = MenuColors.expense,
                            containerColor = MenuColors.expenseBg,
                            borderColor = MenuColors.expenseBorder
                        )
                        Spacer(modifier = Modifier.width(Spacing.xs))
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
            // Level 0: Month / Period Navigator Bar & Management Action Chips
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.xs)) {
                            AutoLogueActionChipButton(
                                text = if (uiState.hasActiveSubFilter) "조회조건 필터 (적용중)" else "조회조건 필터",
                                icon = Icons.Default.CalendarMonth,
                                onClick = { viewModel.openDateRangeDialog() },
                                containerColor = if (uiState.hasActiveSubFilter) Color(0xFFDBEAFE) else Color(0xFFEFF6FF),
                                borderColor = if (uiState.hasActiveSubFilter) Color(0xFF3B82F6) else Color(0xFFBFDBFE),
                                contentColor = Color(0xFF1D4ED8)
                            )
                        }
                    }

                    // 카테고리 관리 / 사용자 분류 관리 / 계좌 관리 / 엑셀 다운로드 바
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AutoLogueActionChipButton(
                            text = "카테고리 관리",
                            icon = Icons.Default.EditNote,
                            onClick = { viewModel.openCategoryManageDialog() },
                            containerColor = Color(0xFFF0FDF4),
                            borderColor = Color(0xFFBBF7D0),
                            contentColor = Color(0xFF15803D)
                        )
                        AutoLogueActionChipButton(
                            text = "사용자 분류 관리",
                            icon = Icons.Default.EditNote,
                            onClick = { viewModel.openUserClassificationManageDialog() },
                            containerColor = Color(0xFFF5F3FF),
                            borderColor = Color(0xFFDDD6FE),
                            contentColor = Color(0xFF6D28D9)
                        )
                        AutoLogueActionChipButton(
                            text = "계좌 관리",
                            icon = Icons.Default.AccountBalance,
                            onClick = { viewModel.openAccountManageDialog() },
                            containerColor = Color(0xFFF1F5F9),
                            borderColor = Color(0xFFCBD5E1),
                            contentColor = Color(0xFF334155)
                        )
                        AutoLogueActionChipButton(
                            text = "엑셀 다운로드",
                            icon = Icons.Default.Sync,
                            onClick = { viewModel.exportToExcel(context) },
                            containerColor = Color(0xFFFFFBEB),
                            borderColor = Color(0xFFFDE68A),
                            contentColor = Color(0xFFB45309)
                        )
                    }
                }
                HairlineDivider()
            }

            // Level 1: Monthly Total Spending & Income KPI (+ Active Filter Summary)
            item {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.xl, vertical = Spacing.lg)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
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

                    // 활성화된 조회조건(사용자별 / 카테고리별 / 법인카드 등) 요약 배너
                    if (uiState.hasActiveSubFilter) {
                        val activeLabel = uiState.buildActiveFilterLabel { cat ->
                            viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFEFF6FF),
                            border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "조회조건: $activeLabel",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1E40AF)
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = "조건 해당 지출 합계: %,d원 (${uiState.filteredTransactions.size}건)".format(uiState.filteredExpenseTotal),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF1D4ED8)
                                    )
                                }
                                Surface(
                                    onClick = { viewModel.clearAllSubFilters() },
                                    shape = RoundedCornerShape(6.dp),
                                    color = PureWhite,
                                    border = BorderStroke(1.dp, Color(0xFF93C5FD))
                                ) {
                                    Text(
                                        text = "조건 해제 ✕",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF1D4ED8),
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
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
                                        text = "월간 목표 예산",
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

            // Level 2: Category Breakdown vs User Classification Breakdown (Segmented Switch)
            if (uiState.transactions.isNotEmpty() || uiState.userClassifications.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // [카테고리별 지출] / [사용자별 지출] 전환 탭
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Slate100,
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Row(modifier = Modifier.padding(2.dp)) {
                                val isByCat = uiState.breakdownMode == ExpenseBreakdownMode.BY_CATEGORY
                                Surface(
                                    onClick = { viewModel.setBreakdownMode(ExpenseBreakdownMode.BY_CATEGORY) },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isByCat) PureWhite else Color.Transparent,
                                    shadowElevation = if (isByCat) 1.dp else 0.dp
                                ) {
                                    Text(
                                        text = "카테고리별 지출",
                                        fontSize = 12.sp,
                                        fontWeight = if (isByCat) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isByCat) Slate900 else Slate600,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                                Surface(
                                    onClick = { viewModel.setBreakdownMode(ExpenseBreakdownMode.BY_USER) },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (!isByCat) PureWhite else Color.Transparent,
                                    shadowElevation = if (!isByCat) 1.dp else 0.dp
                                ) {
                                    Text(
                                        text = "사용자별 지출",
                                        fontSize = 12.sp,
                                        fontWeight = if (!isByCat) FontWeight.Bold else FontWeight.Medium,
                                        color = if (!isByCat) Slate900 else Slate600,
                                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        if (uiState.hasActiveSubFilter) {
                            Text(
                                text = "필터 해제 ✕",
                                style = AppTypography.caption.copy(color = AppColors.primary, fontWeight = FontWeight.Bold),
                                modifier = Modifier.clickable { viewModel.clearAllSubFilters() }
                            )
                        }
                    }
                }

                val totalExp = uiState.totalExpense.coerceAtLeast(1L)

                if (uiState.breakdownMode == ExpenseBreakdownMode.BY_CATEGORY) {
                    val effectiveEntries = uiState.effectiveCategoryTotals.entries.toList()
                    val totalCount = effectiveEntries.size.coerceAtLeast(1)

                    itemsIndexed(effectiveEntries) { index, (catDisplayName, amount) ->
                        val selectedBuiltInName = uiState.selectedCategory?.let {
                            viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(it)
                        }
                        val isSelected = when {
                            !uiState.selectedCustomCategoryName.isNullOrBlank() ->
                                uiState.selectedCustomCategoryName == catDisplayName
                            uiState.selectedCategory != null ->
                                selectedBuiltInName == catDisplayName
                            else -> false
                        }
                        val barColor = getRainbowUsageColor(index, totalCount)
                        val pct = (amount.toFloat() / totalExp * 100f)

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) Color(0xFFEFF6FF) else Color.Transparent)
                                .clickable { viewModel.toggleEffectiveCategoryByName(catDisplayName) }
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
                                        text = catDisplayName,
                                        style = AppTypography.body,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) AppColors.primary else AppColors.textPrimary
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "%.1f%%".format(pct),
                                        fontSize = 11.sp,
                                        color = Slate500
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
                } else {
                    val userEntries = uiState.userClassificationTotals.entries.toList()
                    itemsIndexed(userEntries) { index, (userKey, amount) ->
                        val isUnassigned = userKey == UNASSIGNED_USER_FILTER
                        val displayUserName = if (isUnassigned) "미지정 (분류 전)" else userKey
                        val matchedUc = uiState.userClassifications.firstOrNull { it.name == userKey }
                        val (fgColor, bgColor) = if (matchedUc != null) {
                            getUserClassificationBadgeColors(matchedUc.colorIndex)
                        } else if (isUnassigned) {
                            Slate600 to Slate100
                        } else {
                            getUserClassificationBadgeColors(index)
                        }
                        val isSelected = uiState.selectedUserClassification == userKey
                        val txCount = if (isUnassigned) {
                            uiState.transactions.count { uiState.userClassByTxId[it.id] == null }
                        } else {
                            uiState.transactions.count { uiState.userClassByTxId[it.id]?.name == userKey }
                        }
                        val pct = if (uiState.totalExpense > 0L) (amount.toFloat() / totalExp * 100f) else 0f

                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(if (isSelected) bgColor.copy(alpha = 0.5f) else Color.Transparent)
                                .clickable { viewModel.toggleUserClassificationFilter(userKey) }
                                .padding(horizontal = Spacing.xl, vertical = Spacing.xs)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    MetricBadge(
                                        text = displayUserName,
                                        textColor = fgColor,
                                        backgroundColor = bgColor
                                    )
                                    Text(
                                        text = "${txCount}건 · %.1f%%".format(pct),
                                        fontSize = 11.sp,
                                        color = Slate500
                                    )
                                }
                                Text(
                                    text = "%,d원".format(amount),
                                    style = AppTypography.h3,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.SemiBold,
                                    color = if (isSelected) fgColor else AppColors.textPrimary
                                )
                            }
                            Spacer(modifier = Modifier.height(Spacing.xxs))
                            val proportion = if (uiState.totalExpense > 0L) (amount.toFloat() / totalExp).coerceIn(0f, 1f) else 0f
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
                                        .background(fgColor)
                                )
                            }
                        }
                    }
                }

                item {
                    Spacer(modifier = Modifier.height(Spacing.sm))
                    HairlineDivider()
                }
            }

            // Level 3: Transaction List Header with Selective Delete & Quick Filter Chips (Category & User)
            item {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.xl, vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val activeLabel = uiState.buildActiveFilterLabel { cat ->
                            viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (activeLabel.isNotBlank()) {
                                    "$activeLabel (${uiState.filteredTransactions.size}건)"
                                } else {
                                    "결제 내역 (${uiState.filteredTransactions.size}건)"
                                },
                                style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800)
                            )
                            if (uiState.hasActiveSubFilter) {
                                Text(
                                    text = "선택 조건 지출 합계: %,d원".format(uiState.filteredExpenseTotal),
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = AppColors.primary
                                )
                            }
                        }

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 조회조건 필터 모달 열기 버튼
                            Surface(
                                onClick = { viewModel.openDateRangeDialog() },
                                shape = RoundedCornerShape(6.dp),
                                color = if (uiState.hasActiveSubFilter) Color(0xFFDBEAFE) else Slate100,
                                border = BorderStroke(1.dp, if (uiState.hasActiveSubFilter) Color(0xFF93C5FD) else Slate300)
                            ) {
                                Text(
                                    text = "상세 필터",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.hasActiveSubFilter) Color(0xFF1D4ED8) else Slate700,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }

                            // 선택 삭제 토글 버튼
                            Surface(
                                onClick = { viewModel.toggleSelectionMode() },
                                shape = RoundedCornerShape(6.dp),
                                color = if (uiState.isSelectionMode) Color(0xFFFEE2E2) else Slate100,
                                border = BorderStroke(1.dp, if (uiState.isSelectionMode) Color(0xFFFCA5A5) else Slate300)
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = if (uiState.isSelectionMode) Icons.Default.Close else Icons.Default.Delete,
                                        contentDescription = "선택 삭제",
                                        tint = if (uiState.isSelectionMode) Color(0xFFDC2626) else Slate700,
                                        modifier = Modifier.size(13.dp)
                                    )
                                    Text(
                                        text = if (uiState.isSelectionMode) "선택 취소" else "선택 삭제",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (uiState.isSelectionMode) Color(0xFFDC2626) else Slate700
                                    )
                                }
                            }
                        }
                    }

                    // 다중 선택 삭제 컨트롤 바
                    if (uiState.isSelectionMode) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Color(0xFFFEF2F2),
                            border = BorderStroke(1.dp, Color(0xFFFECACA)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.xl, vertical = 4.dp)
                        ) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                val allSelected = uiState.filteredTransactions.isNotEmpty() &&
                                    uiState.selectedTransactionIds.size == uiState.filteredTransactions.size
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.clickable { viewModel.selectAllFilteredTransactions() }
                                ) {
                                    Checkbox(
                                        checked = allSelected,
                                        onCheckedChange = { viewModel.selectAllFilteredTransactions() }
                                    )
                                    Text(
                                        text = "전체 선택 (${uiState.selectedTransactionIds.size}/${uiState.filteredTransactions.size})",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate800
                                    )
                                }
                                Button(
                                    onClick = { viewModel.deleteSelectedTransactions() },
                                    enabled = uiState.selectedTransactionIds.isNotEmpty(),
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                    shape = RoundedCornerShape(8.dp),
                                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Icon(Icons.Default.Delete, contentDescription = null, tint = PureWhite, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(
                                        text = "${uiState.selectedTransactionIds.size}건 영구 삭제",
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = PureWhite
                                    )
                                }
                            }
                        }
                    }

                    // [1단] 카테고리별 조회 필터 칩 바
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.xl, vertical = Spacing.xxs),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "카테고리별:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate500
                        )
                        val isAllCatSelected = uiState.selectedCategory == null &&
                            uiState.selectedCustomCategoryName == null &&
                            !uiState.isRecurringFilterOnly
                        Surface(
                            onClick = { viewModel.setCategoryFilter(null) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isAllCatSelected) AppColors.primary else Slate50,
                            shadowElevation = if (isAllCatSelected) 2.dp else 1.dp,
                            border = BorderStroke(1.dp, if (isAllCatSelected) AppColors.primaryDark else Slate200)
                        ) {
                            Text(
                                text = "전체 (${uiState.transactions.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAllCatSelected) PureWhite else Slate700,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        // 정기지출 모아보기 필터 칩
                        val currentMonthRecurringCount = uiState.transactions.count { it.id in uiState.recurringTransactionIds }
                        Surface(
                            onClick = { viewModel.toggleRecurringFilter() },
                            shape = RoundedCornerShape(8.dp),
                            color = if (uiState.isRecurringFilterOnly) Color(0xFF4338CA) else Color(0xFFEEF2FF),
                            shadowElevation = if (uiState.isRecurringFilterOnly) 2.dp else 1.dp,
                            border = BorderStroke(1.dp, if (uiState.isRecurringFilterOnly) Color(0xFF3730A3) else Color(0xFFC7D2FE))
                        ) {
                            Text(
                                text = "정기지출 ($currentMonthRecurringCount)",
                                fontSize = 11.sp,
                                fontWeight = if (uiState.isRecurringFilterOnly) FontWeight.Bold else FontWeight.Medium,
                                color = if (uiState.isRecurringFilterOnly) PureWhite else Color(0xFF4338CA),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }

                        // 기본 카테고리 필터 칩 (지출 내역이 있거나 등록된 모든 기본 카테고리 표시)
                        val builtInCatsToShow = (uiState.categoryTotals.keys +
                            uiState.managedCategories.filter { it.isBuiltIn }.map { it.baseCategory }).distinct()
                        builtInCatsToShow.forEach { cat ->
                            val isSelected = uiState.selectedCategory == cat &&
                                uiState.selectedCustomCategoryName == null &&
                                !uiState.isRecurringFilterOnly
                            val count = uiState.transactions.count { tx ->
                                if (cat == ExpenseCategory.ETC) {
                                    tx.category == ExpenseCategory.ETC && uiState.customCategoryByTxId[tx.id].isNullOrBlank()
                                } else {
                                    tx.category == cat
                                }
                            }
                            val displayCat = viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
                            Surface(
                                onClick = { viewModel.toggleCategoryFilter(cat) },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) AppColors.primary else Slate50,
                                shadowElevation = if (isSelected) 2.dp else 1.dp,
                                border = BorderStroke(1.dp, if (isSelected) AppColors.primaryDark else Slate200)
                            ) {
                                Text(
                                    text = "$displayCat ($count)",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PureWhite else Slate700,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }

                        // 커스텀 추가된 카테고리 필터 칩
                        uiState.managedCategories.filter { !it.isBuiltIn }.forEach { customItem ->
                            val count = uiState.transactions.count { uiState.customCategoryByTxId[it.id] == customItem.displayName }
                            val isSelected = uiState.selectedCustomCategoryName == customItem.displayName
                            Surface(
                                onClick = {
                                    viewModel.setCustomCategoryFilter(
                                        if (isSelected) null else customItem.displayName
                                    )
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF0D9488) else Color(0xFFF0FDFA),
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF0F766E) else Color(0xFF99F6E4))
                            ) {
                                Text(
                                    text = "${customItem.displayName} ($count)",
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PureWhite else Color(0xFF0F766E),
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    // [2단] 사용자별 조회 필터 칩 바 (항상 표시)
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState())
                            .padding(horizontal = Spacing.xl, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "사용자별:",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Slate500
                        )
                        val isAllUsers = uiState.selectedUserClassification == null
                        Surface(
                            onClick = { viewModel.toggleUserClassificationFilter(null) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isAllUsers) Slate800 else Slate50,
                            border = BorderStroke(1.dp, if (isAllUsers) Slate900 else Slate200)
                        ) {
                            Text(
                                text = "전체 (${uiState.transactions.size})",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isAllUsers) PureWhite else Slate600,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        uiState.userClassifications.forEach { uc ->
                            val isSel = uiState.selectedUserClassification == uc.name
                            val (fg, bg) = getUserClassificationBadgeColors(uc.colorIndex)
                            val count = uiState.transactions.count { uiState.userClassByTxId[it.id]?.name == uc.name }
                            Surface(
                                onClick = { viewModel.toggleUserClassificationFilter(uc.name) },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSel) fg else bg,
                                border = BorderStroke(1.dp, fg)
                            ) {
                                Text(
                                    text = "${uc.name} ($count)",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) PureWhite else fg,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                        // 미지정(사용자 미분류) 필터 칩
                        val unassignedCount = uiState.transactions.count { uiState.userClassByTxId[it.id] == null }
                        val isUnassignedSel = uiState.selectedUserClassification == UNASSIGNED_USER_FILTER
                        Surface(
                            onClick = { viewModel.toggleUserClassificationFilter(UNASSIGNED_USER_FILTER) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isUnassignedSel) Slate700 else Slate100,
                            border = BorderStroke(1.dp, if (isUnassignedSel) Slate800 else Slate300)
                        ) {
                            Text(
                                text = "미지정 ($unassignedCount)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isUnassignedSel) PureWhite else Slate600,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                        // 법인카드 빠른 필터 칩
                        val corpCount = uiState.transactions.count { uiState.corporateByTxId[it.id] == true }
                        val isCorpSelected = uiState.selectedCorporateFilter == true
                        Surface(
                            onClick = { viewModel.setCorporateFilter(true) },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isCorpSelected) Color(0xFFB45309) else Color(0xFFFEF3C7),
                            border = BorderStroke(1.dp, Color(0xFFF59E0B))
                        ) {
                            Text(
                                text = "법인카드 ($corpCount)",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isCorpSelected) PureWhite else Color(0xFFB45309),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
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
                    val matchingAccount = viewModel.getMatchingAccount(tx)
                    val isRecurring = tx.id in uiState.recurringTransactionIds
                    val userClass = uiState.userClassByTxId[tx.id]
                    val customCatName = uiState.customCategoryByTxId[tx.id]
                    val effectiveCatName = customCatName ?: viewModel.getEffectiveCategoryDisplayName(tx)
                    val isCorporate = uiState.corporateByTxId[tx.id] == true
                    val isOneTimeEdited = tx.id in uiState.oneTimeEditedIds
                    val isSelectedForDelete = tx.id in uiState.selectedTransactionIds

                    SaaSTransactionRow(
                        transaction = tx,
                        effectiveCategoryName = effectiveCatName,
                        userClassification = userClass,
                        isCorporate = isCorporate,
                        isOneTimeEdited = isOneTimeEdited,
                        matchingAccount = matchingAccount,
                        isRecurring = isRecurring,
                        isSelectionMode = uiState.isSelectionMode,
                        isSelectedForDelete = isSelectedForDelete,
                        onClick = {
                            if (uiState.isSelectionMode) {
                                viewModel.toggleSelectTransaction(tx.id)
                            } else {
                                viewModel.openEditDialog(tx)
                            }
                        }
                    )
                    HairlineDivider()
                }
            }
        }
    }

    // Date Range & Comprehensive Filter Dialog (기간 + 사용자별 + 카테고리별 + 카드구분)
    if (uiState.isDateRangePickerOpen) {
        DateRangeFilterDialog(
            currentYearMonth = uiState.selectedYearMonth,
            currentMode = uiState.filterMode,
            startDate = uiState.customStartDate,
            endDate = uiState.customEndDate,
            managedCategories = uiState.managedCategories,
            userClassifications = uiState.userClassifications,
            selectedCategory = uiState.selectedCategory,
            selectedCustomCategoryName = uiState.selectedCustomCategoryName,
            selectedUserClassification = uiState.selectedUserClassification,
            selectedCorporateFilter = uiState.selectedCorporateFilter,
            isRecurringFilterOnly = uiState.isRecurringFilterOnly,
            onDismiss = { viewModel.closeDateRangeDialog() },
            onSelectPreset = { preset -> viewModel.selectQuickPreset(preset) },
            onSelectMonth = { ym -> viewModel.selectYearMonth(ym) },
            onSelectCustomRange = { s, e -> viewModel.setCustomDateRange(s, e) },
            onSelectCategory = { cat -> viewModel.setCategoryFilter(cat) },
            onSelectCustomCategory = { customName -> viewModel.setCustomCategoryFilter(customName) },
            onToggleRecurring = { viewModel.toggleRecurringFilter() },
            onSelectUserClassification = { uName -> viewModel.setUserClassificationFilter(uName) },
            onSelectCorporateFilter = { isCorp -> viewModel.setCorporateFilter(isCorp) },
            onClearAllFilters = { viewModel.clearAllSubFilters() },
            onOpenCategoryManage = {
                viewModel.closeDateRangeDialog()
                viewModel.openCategoryManageDialog()
            },
            onOpenUserManage = {
                viewModel.closeDateRangeDialog()
                viewModel.openUserClassificationManageDialog()
            }
        )
    }

    // Manual Add Expense Dialog
    if (uiState.isAddDialogOpen) {
        AddManualExpenseDialog(
            managedCategories = uiState.managedCategories,
            userClassifications = uiState.userClassifications,
            isSaving = uiState.isSaving,
            onResolveCardCorporate = { cardName ->
                viewModel.expenseCustomMetaPreferences.resolveCardCorporateByCardName(cardName)
            },
            onAddPermanentCategory = { newCat -> viewModel.addCustomCategoryPermanent(newCat) },
            onOpenCategoryManage = { viewModel.openCategoryManageDialog() },
            onOpenUserClassificationManage = { viewModel.openUserClassificationManageDialog() },
            onUpdateUserClassification = { oldName, newName, colorIdx ->
                viewModel.updateUserClassification(oldName, newName, colorIdx)
            },
            onDeleteUserClassification = { name -> viewModel.deleteUserClassification(name) },
            onDismiss = { viewModel.closeAddDialog() },
            onAdd = { merchant, amount, category, card, isCorp, customCat, userClass, isFixed, memo ->
                viewModel.addManualTransaction(
                    merchantName = merchant,
                    amount = amount,
                    category = category,
                    cardOrBankName = card,
                    memo = memo,
                    isCorporate = isCorp,
                    customCategoryName = customCat,
                    userClassificationName = userClass,
                    isFixedExpense = isFixed
                )
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
        val editingTx = uiState.editingTransaction!!
        val resolvedBaseCardOrBank = viewModel.expenseCustomMetaPreferences.extractBaseCardName(
            viewModel.expenseCustomMetaPreferences.resolveDisplayCardOrBankName(editingTx)
        )
        val sameDirectionExistingTxs = remember(editingTx.id, uiState.allTransactions) {
            val isDep = editingTx.isDepositTransaction()
            uiState.allTransactions
                .filter { it.id != editingTx.id && it.isDepositTransaction() == isDep }
                .sortedByDescending { it.timestamp }
        }
        val autoMatchedExistingIds = remember(editingTx.id, uiState.allTransactions) {
            viewModel.findMatchingExistingTransactions(editingTx).map { it.id }.toSet()
        }
        EditTransactionDialog(
            transaction = editingTx,
            managedCategories = uiState.managedCategories,
            userClassifications = uiState.userClassifications,
            initialCustomCategoryName = uiState.customCategoryByTxId[editingTx.id],
            initialUserClassificationName = uiState.userClassByTxId[editingTx.id]?.name,
            initialIsCorporate = uiState.corporateByTxId[editingTx.id] == true || editingTx.cardOrBankName.contains("법인"),
            initialBaseCardName = resolvedBaseCardOrBank,
            initialIsOneTimeSave = editingTx.id in uiState.oneTimeEditedIds,
            initialIsFixedExpense = editingTx.id in uiState.recurringTransactionIds,
            isSaving = uiState.isSaving,
            candidateExistingTransactions = sameDirectionExistingTxs,
            autoMatchedExistingTxIds = autoMatchedExistingIds,
            userClassByTxId = uiState.userClassByTxId,
            customCategoryByTxId = uiState.customCategoryByTxId,
            onResolveCardCorporate = { cardName ->
                viewModel.expenseCustomMetaPreferences.resolveCardCorporateByCardName(
                    cardCompany = cardName,
                    rawCardOrBankName = editingTx.cardOrBankName,
                    originalText = editingTx.originalText
                )
            },
            onAddPermanentCategory = { newCat -> viewModel.addCustomCategoryPermanent(newCat) },
            onUpdateCategoryName = { item, newName -> viewModel.updateManagedCategoryName(item, newName) },
            onDeleteCategory = { item -> viewModel.deleteManagedCategory(item) },
            onOpenCategoryManage = { viewModel.openCategoryManageDialog() },
            onOpenUserClassificationManage = { viewModel.openUserClassificationManageDialog() },
            onUpdateUserClassification = { oldName, newName, colorIdx ->
                viewModel.updateUserClassification(oldName, newName, colorIdx)
            },
            onDeleteUserClassification = { name -> viewModel.deleteUserClassification(name) },
            onDismiss = { viewModel.closeEditDialog() },
            onSave = { newMerchantName, cat, cardCompany, isCorp, customCat, userClass, isOneTime, isFixed, memo, permanentSaveOptions ->
                viewModel.updateTransactionDetails(
                    transactionId = editingTx.id,
                    newMerchantName = newMerchantName,
                    category = cat,
                    memo = memo,
                    cardCompany = cardCompany,
                    isCorporate = isCorp,
                    customCategoryName = customCat,
                    userClassificationName = userClass,
                    isOneTimeSave = isOneTime,
                    isFixedExpense = isFixed,
                    permanentSaveOptions = permanentSaveOptions
                )
            },
            onDelete = {
                viewModel.deleteTransaction(editingTx.id)
            }
        )
    }

    // Category Manage Dialog (5. 등록된 카테고리들 관리기능: 추가, 삭제, 수정)
    if (uiState.isCategoryManageDialogOpen) {
        CategoryManageDialog(
            managedCategories = uiState.managedCategories,
            onDismiss = { viewModel.closeCategoryManageDialog() },
            onAddCategory = { name -> viewModel.addCustomCategoryPermanent(name) },
            onUpdateCategoryName = { item, newName -> viewModel.updateManagedCategoryName(item, newName) },
            onDeleteCategory = { item -> viewModel.deleteManagedCategory(item) },
            onRestoreDefaults = { viewModel.restoreDefaultCategories() }
        )
    }

    // User Classification Manage Dialog (6. 사용자 분류 관리기능: 직접입력, 추가, 삭제, 수정)
    if (uiState.isUserClassificationManageDialogOpen) {
        UserClassificationManageDialog(
            userClassifications = uiState.userClassifications,
            onDismiss = { viewModel.closeUserClassificationManageDialog() },
            onAddUserClassification = { name, colorIdx -> viewModel.addUserClassification(name, colorIdx) },
            onUpdateUserClassification = { oldName, newName, colorIdx ->
                viewModel.updateUserClassification(oldName, newName, colorIdx)
            },
            onDeleteUserClassification = { name -> viewModel.deleteUserClassification(name) }
        )
    }

    // Account Manage Dialog
    if (uiState.isAccountManageDialogOpen) {
        AccountManageDialog(
            accounts = uiState.userAccounts,
            onDismiss = { viewModel.closeAccountManageDialog() },
            onAddAccount = { bankName, pattern, alias ->
                viewModel.addAccount(bankName, pattern, alias)
            },
            onDeleteAccount = { id ->
                viewModel.deleteAccount(id)
            }
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SaaSTransactionRow(
    transaction: Transaction,
    effectiveCategoryName: String = transaction.category.displayName,
    userClassification: com.autologue.app.data.preferences.UserClassificationItem? = null,
    isCorporate: Boolean = false,
    isOneTimeEdited: Boolean = false,
    matchingAccount: UserAccount? = null,
    isRecurring: Boolean = false,
    isSelectionMode: Boolean = false,
    isSelectedForDelete: Boolean = false,
    onClick: () -> Unit
) {
    val isSelf = transaction.isSelfTransfer()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelectedForDelete) Color(0xFFFEF2F2) else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = Spacing.xl, vertical = Spacing.md),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isSelectionMode) {
            Checkbox(
                checked = isSelectedForDelete,
                onCheckedChange = { onClick() },
                modifier = Modifier.padding(end = 8.dp)
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            FlowRow(
                verticalArrangement = Arrangement.spacedBy(4.dp),
                horizontalArrangement = Arrangement.spacedBy(Spacing.xs)
            ) {
                Text(
                    text = transaction.merchantName,
                    style = AppTypography.h2
                )
                // 사용자 분류 지정 시 다른 색상의 배지로 표기
                if (userClassification != null) {
                    val (uFg, uBg) = getUserClassificationBadgeColors(userClassification.colorIndex)
                    MetricBadge(
                        text = userClassification.name,
                        textColor = uFg,
                        backgroundColor = uBg
                    )
                }
                // 법인카드 지정 시 배지 표기
                if (isCorporate) {
                    MetricBadge(
                        text = "법인카드",
                        textColor = Color(0xFFB45309),
                        backgroundColor = Color(0xFFFEF3C7)
                    )
                }
                if (isOneTimeEdited) {
                    MetricBadge(
                        text = "1회성",
                        textColor = Color(0xFF475569),
                        backgroundColor = Color(0xFFF1F5F9)
                    )
                }
                if (matchingAccount != null) {
                    MetricBadge(
                        text = matchingAccount.badgeLabel,
                        textColor = Color(0xFF1E40AF),
                        backgroundColor = Color(0xFFDBEAFE)
                    )
                }
                if (isRecurring) {
                    MetricBadge(
                        text = "고정지출",
                        textColor = Color(0xFF5B21B6),
                        backgroundColor = Color(0xFFEDE9FE)
                    )
                }
                if (isSelf) {
                    MetricBadge(
                        text = "내 계좌간 이동",
                        textColor = Indigo700,
                        backgroundColor = Indigo50
                    )
                }
            }
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
                    text = if (isSelf) "계좌이동 (미집계)" else effectiveCategoryName,
                    style = AppTypography.caption.copy(
                        color = if (isSelf) Indigo600 else AppColors.primary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
                if (transaction.cardOrBankName.isNotBlank()) {
                    Text("·", style = AppTypography.captionMuted)
                    Text(
                        text = if (matchingAccount != null) matchingAccount.alias else transaction.cardOrBankName,
                        style = AppTypography.captionMuted.copy(
                            color = if (isCorporate) Color(0xFFB45309) else Slate500,
                            fontWeight = if (isCorporate) FontWeight.SemiBold else FontWeight.Normal
                        )
                    )
                }
            }
            if (!transaction.transferMemo.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                val memoToDisplay = if (matchingAccount != null && transaction.transferMemo.contains(matchingAccount.accountNumberPattern)) {
                    transaction.transferMemo.replace(matchingAccount.accountNumberPattern, matchingAccount.alias)
                } else {
                    transaction.transferMemo
                }
                Text(
                    text = "메모: $memoToDisplay",
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
                    color = when {
                        isIncome -> Emerald600
                        isSelf -> Slate500
                        else -> Slate900
                    }
                )
            )
            Spacer(modifier = Modifier.height(Spacing.xxs))
            if (isSelf) {
                Text(
                    text = "지출 미집계",
                    fontSize = 11.sp,
                    color = Indigo600,
                    fontWeight = FontWeight.Medium
                )
            } else {
                Text(
                    text = if (isSelectionMode) (if (isSelectedForDelete) "선택됨" else "터치하여 선택") else "수정/분류 →",
                    fontSize = 11.sp,
                    color = if (isSelectedForDelete) Color(0xFFDC2626) else Slate400
                )
            }
        }
    }
}

/**
 * 조회조건 필터 설정 팝업 다이얼로그 (조회 기간 + 사용자별 + 카테고리별 + 카드/법인 구분)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DateRangeFilterDialog(
    currentYearMonth: YearMonth,
    currentMode: ExpenseDateFilterMode,
    startDate: LocalDate,
    endDate: LocalDate,
    managedCategories: List<com.autologue.app.data.preferences.ManagedCategoryItem>,
    userClassifications: List<com.autologue.app.data.preferences.UserClassificationItem>,
    selectedCategory: ExpenseCategory?,
    selectedCustomCategoryName: String?,
    selectedUserClassification: String?,
    selectedCorporateFilter: Boolean?,
    isRecurringFilterOnly: Boolean,
    onDismiss: () -> Unit,
    onSelectPreset: (String) -> Unit,
    onSelectMonth: (YearMonth) -> Unit,
    onSelectCustomRange: (LocalDate, LocalDate) -> Unit,
    onSelectCategory: (ExpenseCategory?) -> Unit,
    onSelectCustomCategory: (String?) -> Unit,
    onToggleRecurring: () -> Unit,
    onSelectUserClassification: (String?) -> Unit,
    onSelectCorporateFilter: (Boolean?) -> Unit,
    onClearAllFilters: () -> Unit,
    onOpenCategoryManage: () -> Unit,
    onOpenUserManage: () -> Unit
) {
    var tempYearMonth by remember { mutableStateOf(currentYearMonth) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("조회조건 필터 설정", style = AppTypography.h2)
                TextButton(
                    onClick = onClearAllFilters,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("조건 초기화", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color(0xFFDC2626))
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 1. 사용자별 조회 필터 섹션
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "1. 사용자별 조회",
                        style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800)
                    )
                    Text(
                        text = "사용자 분류 관리 →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF6D28D9),
                        modifier = Modifier.clickable { onOpenUserManage() }
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isAllUsers = selectedUserClassification == null
                    Surface(
                        onClick = { onSelectUserClassification(null) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAllUsers) Slate800 else Slate50,
                        border = BorderStroke(1.dp, if (isAllUsers) Slate900 else Slate200)
                    ) {
                        Text(
                            text = "전체 사용자",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAllUsers) PureWhite else Slate700,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    userClassifications.forEach { uc ->
                        val isSel = selectedUserClassification == uc.name
                        val (fg, bg) = getUserClassificationBadgeColors(uc.colorIndex)
                        Surface(
                            onClick = {
                                onSelectUserClassification(if (isSel) null else uc.name)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) fg else bg,
                            border = BorderStroke(1.dp, fg)
                        ) {
                            Text(
                                text = uc.name,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) PureWhite else fg,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    val isUnassignedSel = selectedUserClassification == UNASSIGNED_USER_FILTER
                    Surface(
                        onClick = {
                            onSelectUserClassification(if (isUnassignedSel) null else UNASSIGNED_USER_FILTER)
                        },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isUnassignedSel) Slate700 else Slate100,
                        border = BorderStroke(1.dp, if (isUnassignedSel) Slate800 else Slate300)
                    ) {
                        Text(
                            text = "미지정",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isUnassignedSel) PureWhite else Slate600,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }
                }

                HairlineDivider()

                // 2. 카테고리별 조회 필터 섹션
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "2. 카테고리별 조회",
                        style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800)
                    )
                    Text(
                        text = "카테고리 관리 →",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.SemiBold,
                        color = Color(0xFF15803D),
                        modifier = Modifier.clickable { onOpenCategoryManage() }
                    )
                }
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val isAllCat = selectedCategory == null &&
                        selectedCustomCategoryName == null &&
                        !isRecurringFilterOnly
                    Surface(
                        onClick = { onSelectCategory(null) },
                        shape = RoundedCornerShape(8.dp),
                        color = if (isAllCat) AppColors.primary else Slate50,
                        border = BorderStroke(1.dp, if (isAllCat) AppColors.primaryDark else Slate200)
                    ) {
                        Text(
                            text = "전체 카테고리",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isAllCat) PureWhite else Slate700,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    Surface(
                        onClick = onToggleRecurring,
                        shape = RoundedCornerShape(8.dp),
                        color = if (isRecurringFilterOnly) Color(0xFF4338CA) else Color(0xFFEEF2FF),
                        border = BorderStroke(1.dp, if (isRecurringFilterOnly) Color(0xFF3730A3) else Color(0xFFC7D2FE))
                    ) {
                        Text(
                            text = "정기지출만",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isRecurringFilterOnly) PureWhite else Color(0xFF4338CA),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    managedCategories.filter { it.isBuiltIn }.forEach { item ->
                        val isSel = selectedCategory == item.baseCategory &&
                            selectedCustomCategoryName == null &&
                            !isRecurringFilterOnly
                        Surface(
                            onClick = {
                                onSelectCategory(if (isSel) null else item.baseCategory)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) AppColors.primary else Slate50,
                            border = BorderStroke(1.dp, if (isSel) AppColors.primaryDark else Slate200)
                        ) {
                            Text(
                                text = item.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) PureWhite else Slate700,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }

                    managedCategories.filter { !it.isBuiltIn }.forEach { customItem ->
                        val isSel = selectedCustomCategoryName == customItem.displayName
                        Surface(
                            onClick = {
                                onSelectCustomCategory(if (isSel) null else customItem.displayName)
                            },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) Color(0xFF0D9488) else Color(0xFFF0FDFA),
                            border = BorderStroke(1.dp, if (isSel) Color(0xFF0F766E) else Color(0xFF99F6E4))
                        ) {
                            Text(
                                text = customItem.displayName,
                                fontSize = 12.sp,
                                fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                color = if (isSel) PureWhite else Color(0xFF0F766E),
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                HairlineDivider()

                // 3. 카드/결제 구분 조회
                Text(
                    text = "3. 카드/결제 구분 조회",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800)
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    val corpOptions = listOf(
                        null to "전체",
                        false to "개인결제만",
                        true to "법인카드만"
                    )
                    corpOptions.forEach { (value, label) ->
                        val isSel = selectedCorporateFilter == value
                        Surface(
                            onClick = { onSelectCorporateFilter(value) },
                            shape = RoundedCornerShape(8.dp),
                            color = if (isSel) Color(0xFFB45309) else Slate50,
                            border = BorderStroke(1.dp, if (isSel) Color(0xFF92400E) else Slate200)
                        ) {
                            Text(
                                text = label,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isSel) PureWhite else Slate700,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

                HairlineDivider()

                // 4. 조회 기간 설정 (빠른 기간 & 월별 선택)
                Text(
                    text = "4. 조회 기간 선택",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800)
                )
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
                            shadowElevation = 1.dp,
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Text(
                                text = preset,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = AppColors.primary,
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                            )
                        }
                    }
                }

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

                if (tempYearMonth != currentYearMonth || currentMode != ExpenseDateFilterMode.MONTH) {
                    Button(
                        onClick = { onSelectMonth(tempYearMonth) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary),
                        shape = RoundedCornerShape(8.dp)
                    ) {
                        Text("${tempYearMonth.year}년 ${tempYearMonth.monthValue}월로 기간 변경", color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("적용 완료", color = PureWhite, fontWeight = FontWeight.Bold)
            }
        },
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
    managedCategories: List<com.autologue.app.data.preferences.ManagedCategoryItem>,
    userClassifications: List<com.autologue.app.data.preferences.UserClassificationItem>,
    isSaving: Boolean = false,
    onResolveCardCorporate: (String) -> Boolean? = { null },
    onAddPermanentCategory: (String) -> Boolean,
    onOpenCategoryManage: () -> Unit = {},
    onOpenUserClassificationManage: () -> Unit = {},
    onUpdateUserClassification: (oldName: String, newName: String, colorIdx: Int) -> Unit = { _, _, _ -> },
    onDeleteUserClassification: (name: String) -> Unit = {},
    onDismiss: () -> Unit,
    onAdd: (
        merchant: String,
        amount: Long,
        category: ExpenseCategory,
        card: String,
        isCorporate: Boolean,
        customCategoryName: String?,
        userClassificationName: String?,
        isFixedExpense: Boolean,
        memo: String?
    ) -> Unit
) {
    var merchantName by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf(ExpenseCategory.FOOD) }
    var selectedCustomCategoryName by remember { mutableStateOf<String?>(null) }
    var newPermanentCategoryInput by remember { mutableStateOf("") }
    var cardOrBankName by remember { mutableStateOf("신한카드") }
    var isCorporate by remember { mutableStateOf(onResolveCardCorporate("신한카드") ?: false) }
    var userClassificationInput by remember { mutableStateOf("") }
    var isFixedExpense by remember { mutableStateOf(false) }
    var memo by remember { mutableStateOf("") }

    val isBankMode = selectedCategory == ExpenseCategory.TRANSFER || selectedCategory == ExpenseCategory.INCOME

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
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
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                // 1. 가맹점 / 상호명
                AutoLogueTextField(
                    value = merchantName,
                    onValueChange = { merchantName = it },
                    label = "가맹점 / 상호명",
                    placeholder = "예: 스타벅스 강남점, 서울아산병원",
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

                // 3. 카드 지정 또는 출금/입금 은행 자동 선택 (이체/출금·입금 시 카드 선택 숨김)
                if (isBankMode) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "출금/이체 은행 선택 (계좌 거래로 카드 선택 숨김)",
                            style = AppTypography.caption.copy(color = Color(0xFF047857), fontWeight = FontWeight.SemiBold)
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_BANK_NAMES.forEach { bankPreset ->
                                val isBankSelected = cardOrBankName.trim() == bankPreset
                                Surface(
                                    onClick = { cardOrBankName = bankPreset },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isBankSelected) Color(0xFFECFDF5) else Slate50,
                                    border = BorderStroke(1.dp, if (isBankSelected) Color(0xFF059669) else Slate200)
                                ) {
                                    Text(
                                        text = bankPreset,
                                        fontSize = 10.sp,
                                        fontWeight = if (isBankSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isBankSelected) Color(0xFF047857) else Slate700,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }
                        AutoLogueTextField(
                            value = cardOrBankName,
                            onValueChange = { cardOrBankName = it },
                            label = "은행명 직접 입력",
                            placeholder = "예: NH농협은행, KB국민은행, 우리은행, 하나은행",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "카드 지정 (카드사 및 법인 여부)",
                                style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                Surface(
                                    onClick = { isCorporate = false },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (!isCorporate) Color(0xFF2563EB) else Slate50,
                                    border = BorderStroke(1.dp, if (!isCorporate) Color(0xFF1D4ED8) else Slate200)
                                ) {
                                    Text(
                                        text = "개인카드",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (!isCorporate) PureWhite else Slate600,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                                Surface(
                                    onClick = { isCorporate = true },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isCorporate) Color(0xFF0F172A) else Slate50,
                                    border = BorderStroke(1.dp, if (isCorporate) Color(0xFF0F172A) else Slate200)
                                ) {
                                    Text(
                                        text = "법인카드",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isCorporate) PureWhite else Slate600,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_CARD_COMPANIES.forEach { cardPreset ->
                                val isCardSelected = cardOrBankName.trim() == cardPreset
                                Surface(
                                    onClick = {
                                        cardOrBankName = cardPreset
                                        if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(userClassificationInput) ||
                                            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(selectedCustomCategoryName)
                                        ) {
                                            isCorporate = true
                                        } else {
                                            onResolveCardCorporate(cardPreset)?.let { isCorporate = it }
                                        }
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isCardSelected) Color(0xFFEFF6FF) else Slate50,
                                    border = BorderStroke(1.dp, if (isCardSelected) Color(0xFF2563EB) else Slate200)
                                ) {
                                    Text(
                                        text = cardPreset,
                                        fontSize = 10.sp,
                                        fontWeight = if (isCardSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isCardSelected) Color(0xFF1D4ED8) else Slate700,
                                        modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        AutoLogueTextField(
                            value = cardOrBankName,
                            onValueChange = {
                                cardOrBankName = it
                                onResolveCardCorporate(it)?.let { savedCorp -> isCorporate = savedCorp }
                            },
                            label = "카드회사 / 결제수단 직접 입력",
                            placeholder = "예: 하나카드, NH농협카드, 우리카드, 신한카드",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }

                // 4. 고정지출(정기지출) 지정
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = "고정지출 지정",
                        style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Surface(
                            onClick = { isFixedExpense = false },
                            shape = RoundedCornerShape(6.dp),
                            color = if (!isFixedExpense) Slate800 else Slate50,
                            border = BorderStroke(1.dp, if (!isFixedExpense) Slate900 else Slate200),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "일반 지출",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (!isFixedExpense) PureWhite else Slate600,
                                modifier = Modifier.padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                        Surface(
                            onClick = { isFixedExpense = true },
                            shape = RoundedCornerShape(6.dp),
                            color = if (isFixedExpense) Color(0xFF5B21B6) else Color(0xFFF5F3FF),
                            border = BorderStroke(1.dp, if (isFixedExpense) Color(0xFF4C1D95) else Color(0xFFDDD6FE)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text(
                                text = "고정지출로 지정",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (isFixedExpense) PureWhite else Color(0xFF5B21B6),
                                modifier = Modifier.padding(vertical = 7.dp),
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    }
                }

                // 5. 카테고리 선택 + 관리
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "카테고리 선택",
                            style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                        )
                        Surface(
                            onClick = onOpenCategoryManage,
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100,
                            border = BorderStroke(1.dp, Slate300)
                        ) {
                            Text(
                                text = "카테고리 수정/삭제",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate700,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        managedCategories.forEach { item ->
                            val isSelected = if (item.isBuiltIn) {
                                selectedCustomCategoryName == null && selectedCategory == item.baseCategory
                            } else {
                                selectedCustomCategoryName == item.displayName
                            }
                            Surface(
                                onClick = {
                                    val nextCat = if (item.isBuiltIn) item.baseCategory else ExpenseCategory.ETC
                                    val wasBank = selectedCategory == ExpenseCategory.TRANSFER || selectedCategory == ExpenseCategory.INCOME
                                    val willBeBank = nextCat == ExpenseCategory.TRANSFER || nextCat == ExpenseCategory.INCOME
                                    selectedCategory = nextCat
                                    selectedCustomCategoryName = if (item.isBuiltIn) null else item.displayName
                                    if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(item.displayName)) {
                                        isCorporate = true
                                    }
                                    if (!wasBank && willBeBank) {
                                        cardOrBankName = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName(cardOrBankName)
                                            ?: "NH농협은행"
                                    } else if (wasBank && !willBeBank) {
                                        cardOrBankName = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalCardCompany(cardOrBankName)
                                            ?: "신한카드"
                                    }
                                },
                                shape = RoundedCornerShape(8.dp),
                                color = if (isSelected) Color(0xFF2563EB) else Slate50,
                                shadowElevation = if (isSelected) 2.dp else 1.dp,
                                border = BorderStroke(1.dp, if (isSelected) Color(0xFF1D4ED8) else Slate200)
                            ) {
                                Text(
                                    text = item.displayName,
                                    fontSize = 11.sp,
                                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSelected) PureWhite else Slate700,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }
                    }

                    if (selectedCategory == ExpenseCategory.FUEL || selectedCategory == ExpenseCategory.CAR_MAINTENANCE) {
                        Surface(
                            shape = RoundedCornerShape(6.dp),
                            color = Color(0xFFECFDF5),
                            border = BorderStroke(1.dp, Color(0xFF6EE7B7)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "차량 관련 카테고리(${selectedCategory.displayName})로 저장 시 차계부에도 자동 표시됩니다.",
                                fontSize = 10.5.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF047857),
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                            )
                        }
                    }

                    if (selectedCategory == ExpenseCategory.ETC) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFF8FAFC),
                            border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = "기타 선택: 새 카테고리를 영구 추가하여 지정할 수 있습니다",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF1E40AF)
                                )
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Box(modifier = Modifier.weight(1f)) {
                                        AutoLogueTextField(
                                            value = newPermanentCategoryInput,
                                            onValueChange = { newPermanentCategoryInput = it },
                                            label = "추가할 카테고리명",
                                            placeholder = "예: 반려동물, 학원/교육, 경조사",
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )
                                    }
                                    Button(
                                        onClick = {
                                            val trimmed = newPermanentCategoryInput.trim()
                                            if (trimmed.isNotEmpty()) {
                                                onAddPermanentCategory(trimmed)
                                                selectedCustomCategoryName = trimmed
                                                if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(trimmed)) {
                                                    isCorporate = true
                                                }
                                                newPermanentCategoryInput = ""
                                            }
                                        },
                                        enabled = newPermanentCategoryInput.trim().isNotEmpty(),
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                                    ) {
                                        Text("영구 추가", fontSize = 11.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }

                // 6. 카테고리와 별개인 '사용자 분류' 지정 (직접 입력 + 배지 선택 + 수정/삭제/색상 변경)
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "사용자 분류 (컬러 배지 · 엑셀 포함)",
                            style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                        )
                        Surface(
                            onClick = onOpenUserClassificationManage,
                            shape = RoundedCornerShape(6.dp),
                            color = Slate100,
                            border = BorderStroke(1.dp, Slate300)
                        ) {
                            Text(
                                text = "분류 수정/삭제/색상",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate700,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                            )
                        }
                    }
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Surface(
                            onClick = { userClassificationInput = "" },
                            shape = RoundedCornerShape(6.dp),
                            color = if (userClassificationInput.isBlank()) Slate800 else Slate50,
                            border = BorderStroke(1.dp, if (userClassificationInput.isBlank()) Slate800 else Slate200)
                        ) {
                            Text(
                                text = "미지정",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = if (userClassificationInput.isBlank()) PureWhite else Slate600,
                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                            )
                        }
                        userClassifications.forEach { uc ->
                            val (fg, bg) = getUserClassificationBadgeColors(uc.colorIndex)
                            val isSelected = userClassificationInput.trim() == uc.name
                            Surface(
                                onClick = {
                                    userClassificationInput = uc.name
                                    if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(uc.name)) {
                                        isCorporate = true
                                    }
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isSelected) fg else bg,
                                border = BorderStroke(1.dp, fg)
                            ) {
                                Text(
                                    text = uc.name,
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSelected) PureWhite else fg,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }
                    AutoLogueTextField(
                        value = userClassificationInput,
                        onValueChange = {
                            userClassificationInput = it
                            if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(it)) {
                                isCorporate = true
                            }
                        },
                        label = "사용자 분류명 직접 입력 (입력 시 자동 등록)",
                        placeholder = "예: 홍길동, 가족공용, 프로젝트A, 배우자",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                }

                // 7. 메모
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
            Button(
                onClick = {
                    if (amount > 0 && !isSaving) {
                        val finalCustomCat = newPermanentCategoryInput.trim().takeIf { it.isNotEmpty() }?.also {
                            onAddPermanentCategory(it)
                        } ?: selectedCustomCategoryName
                        onAdd(
                            merchantName.ifBlank { "직접 추가 지출" },
                            amount,
                            selectedCategory,
                            cardOrBankName,
                            isCorporate,
                            finalCustomCat,
                            userClassificationInput.trim().ifEmpty { null },
                            isFixedExpense,
                            memo
                        )
                    }
                },
                enabled = amount > 0 && !isSaving,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                shape = RoundedCornerShape(10.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(15.dp),
                        strokeWidth = 2.dp,
                        color = PureWhite
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text("저장 중...", color = PureWhite, fontWeight = FontWeight.Bold)
                } else {
                    Text("추가하기", color = PureWhite, fontWeight = FontWeight.Bold)
                }
            }
        },
        dismissButton = {
            AutoLogueSecondaryButton(
                text = "취소",
                onClick = { if (!isSaving) onDismiss() }
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
    managedCategories: List<com.autologue.app.data.preferences.ManagedCategoryItem>,
    userClassifications: List<com.autologue.app.data.preferences.UserClassificationItem>,
    initialCustomCategoryName: String?,
    initialUserClassificationName: String?,
    initialIsCorporate: Boolean,
    initialBaseCardName: String,
    initialIsOneTimeSave: Boolean,
    initialIsFixedExpense: Boolean = false,
    isSaving: Boolean = false,
    candidateExistingTransactions: List<Transaction> = emptyList(),
    autoMatchedExistingTxIds: Set<Long> = emptySet(),
    userClassByTxId: Map<Long, com.autologue.app.data.preferences.UserClassificationItem> = emptyMap(),
    customCategoryByTxId: Map<Long, String> = emptyMap(),
    onResolveCardCorporate: (String) -> Boolean? = { null },
    onAddPermanentCategory: (String) -> Boolean,
    onUpdateCategoryName: (com.autologue.app.data.preferences.ManagedCategoryItem, String) -> Unit = { _, _ -> },
    onDeleteCategory: (com.autologue.app.data.preferences.ManagedCategoryItem) -> Unit = {},
    onOpenCategoryManage: () -> Unit = {},
    onOpenUserClassificationManage: () -> Unit = {},
    onUpdateUserClassification: (oldName: String, newName: String, colorIdx: Int) -> Unit = { _, _, _ -> },
    onDeleteUserClassification: (name: String) -> Unit = {},
    onDismiss: () -> Unit,
    onSave: (
        merchantName: String,
        category: ExpenseCategory,
        cardCompany: String,
        isCorporate: Boolean,
        customCategoryName: String?,
        userClassificationName: String?,
        isOneTimeSave: Boolean,
        isFixedExpense: Boolean,
        memo: String?,
        permanentSaveOptions: com.autologue.app.data.preferences.PermanentSaveOptions
    ) -> Unit,
    onDelete: () -> Unit
) {
    val isDeposit = remember(transaction.id, transaction.category, transaction.originalText, transaction.transferMemo) {
        transaction.isDepositTransaction()
    }
    val inferredCard = remember(transaction.id, initialBaseCardName, transaction.cardOrBankName, transaction.originalText) {
        com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalCardCompany(
            initialBaseCardName,
            transaction.cardOrBankName,
            transaction.originalText
        ) ?: initialBaseCardName.takeIf { it.isNotBlank() && !it.endsWith("은행") && it != "계좌이체" } ?: "신한카드"
    }
    val inferredBank = remember(transaction.id, initialBaseCardName, transaction.cardOrBankName, transaction.originalText) {
        com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName(
            initialBaseCardName,
            transaction.cardOrBankName,
            transaction.originalText
        ) ?: initialBaseCardName.takeIf { it.isNotBlank() && !it.endsWith("카드") } ?: "NH농협은행"
    }
    val extractedRawCardHint = remember(transaction.id, transaction.cardOrBankName, transaction.originalText) {
        com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.extractRawCardHintToken(
            transaction.cardOrBankName,
            transaction.originalText
        )
    }

    var merchantName by remember(transaction.id) { mutableStateOf(transaction.merchantName) }
    var selectedCategory by remember(transaction.id) { mutableStateOf(transaction.category) }
    var selectedCustomCategoryName by remember(transaction.id) { mutableStateOf(initialCustomCategoryName) }
    var newPermanentCategoryInput by remember(transaction.id) { mutableStateOf("") }
    var cardCompany by remember(transaction.id) {
        mutableStateOf(if (transaction.isBankAccountTransaction()) inferredBank else inferredCard)
    }
    var isCorporate by remember(transaction.id) {
        mutableStateOf(
            initialIsCorporate ||
                com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(initialUserClassificationName) ||
                com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(initialCustomCategoryName)
        )
    }
    var userClassificationInput by remember(transaction.id) { mutableStateOf(initialUserClassificationName ?: "") }
    var isOneTimeSave by remember(transaction.id) { mutableStateOf(initialIsOneTimeSave) }
    var isFixedExpense by remember(transaction.id) { mutableStateOf(initialIsFixedExpense) }
    var memo by remember(transaction.id) { mutableStateOf(transaction.transferMemo ?: "") }

    // 영구저장 시 선택적으로 지정할 항목 상태 (결제명, 카테고리, 사용자 분류, 카드/은행·법인, 고정지출, 메모)
    var applyMerchantName by remember(transaction.id) { mutableStateOf(true) }
    var applyCategory by remember(transaction.id) { mutableStateOf(true) }
    var applyUserClassification by remember(transaction.id) { mutableStateOf(true) }
    var applyCardAndCorporate by remember(transaction.id) { mutableStateOf(true) }
    var applyFixedExpense by remember(transaction.id) { mutableStateOf(true) }
    var applyMemo by remember(transaction.id) { mutableStateOf(false) }

    // 기존 사용내역 중 함께 적용할 대상 선택 상태 (기본값: 금액 무관 동일 항목 자동 매칭 전체 선택)
    var selectedExistingTxIds by remember(transaction.id, autoMatchedExistingTxIds) {
        mutableStateOf(autoMatchedExistingTxIds)
    }
    var showExistingTxSelector by remember(transaction.id) { mutableStateOf(false) }
    var showOtherExistingTxs by remember(transaction.id) { mutableStateOf(false) }

    // 인라인 수정 상태 (사용자 분류명 수정 / 카테고리명 수정)
    var editingUcNameTarget by remember { mutableStateOf<String?>(null) }
    var editingUcNewName by remember { mutableStateOf("") }
    var editingCatItemTarget by remember { mutableStateOf<com.autologue.app.data.preferences.ManagedCategoryItem?>(null) }
    var editingCatNewName by remember { mutableStateOf("") }

    val isBankTx = transaction.isBankAccountTransaction(selectedCategory)
    val txDirLabel = if (isDeposit) "입금" else "출금"
    val txTypeBadgeLabel = when {
        isDeposit -> "입금 내역"
        isBankTx -> "출금/이체"
        else -> "카드 결제"
    }

    AlertDialog(
        onDismissRequest = { if (!isSaving) onDismiss() },
        title = {
            Text(
                text = "결제 내역 상세 설정 및 수정",
                style = AppTypography.h2
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                ) {
                    // 상단 결제 요약
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = Slate50,
                        shadowElevation = 1.5.dp,
                        border = BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.padding(Spacing.md)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "%,d원".format(transaction.amount),
                                    style = AppTypography.h1.copy(color = AppColors.primary, fontWeight = FontWeight.Bold)
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        shape = RoundedCornerShape(4.dp),
                                        color = if (isDeposit) Color(0xFFECFDF5) else Color(0xFFEFF6FF),
                                        border = BorderStroke(1.dp, if (isDeposit) Color(0xFF6EE7B7) else Color(0xFFBFDBFE))
                                    ) {
                                        Text(
                                            text = txTypeBadgeLabel,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isDeposit) Color(0xFF047857) else Color(0xFF1D4ED8),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    if (isFixedExpense) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFEDE9FE),
                                            border = BorderStroke(1.dp, Color(0xFFC4B5FD))
                                        ) {
                                            Text(
                                                text = "고정지출",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF5B21B6),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                    if (!isBankTx && isCorporate) {
                                        Surface(
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFF1E293B)
                                        ) {
                                            Text(
                                                text = "법인카드",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PureWhite,
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }
                                }
                            }
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = "${transaction.timestamp.format(DateTimeFormatter.ofPattern("yyyy.MM.dd(E) HH:mm", Locale.KOREA))} · $cardCompany",
                                style = AppTypography.captionMuted
                            )
                        }
                    }

                    // 1. 결제명 수정 입력 필드
                    AutoLogueTextField(
                        value = merchantName,
                        onValueChange = { merchantName = it },
                        label = "결제명 (가맹점 / 결제처 이름)",
                        placeholder = "결제명을 입력하세요 (예: 스타벅스, 쿠팡, 서울약국)",
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )

                    // 2. 출금/이체·입금일 때는 카드 선택을 숨기고 은행 자동 선택 표시, 카드 결제일 때는 카드사 자동 추출 선택 표시
                    if (isBankTx) {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (isDeposit) "입금 은행 지정 (자동 선택됨)" else "출금/이체 은행 지정 (자동 선택됨)",
                                    style = AppTypography.caption.copy(color = Color(0xFF047857), fontWeight = FontWeight.SemiBold)
                                )
                                Text(
                                    text = "계좌 거래 (카드 선택 숨김)",
                                    fontSize = 10.sp,
                                    color = Slate500,
                                    fontWeight = FontWeight.Medium
                                )
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_BANK_NAMES.forEach { bankPreset ->
                                    val isBankSelected = cardCompany.trim() == bankPreset
                                    Surface(
                                        onClick = { cardCompany = bankPreset },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isBankSelected) Color(0xFFECFDF5) else Slate50,
                                        border = BorderStroke(1.dp, if (isBankSelected) Color(0xFF059669) else Slate200)
                                    ) {
                                        Text(
                                            text = bankPreset,
                                            fontSize = 10.sp,
                                            fontWeight = if (isBankSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isBankSelected) Color(0xFF047857) else Slate700,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            AutoLogueTextField(
                                value = cardCompany,
                                onValueChange = { cardCompany = it },
                                label = "은행명 직접 입력",
                                placeholder = "예: NH농협은행, KB국민은행, 우리은행, 하나은행",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "카드 지정 (힌트 기반 카드사 자동 선택)",
                                        style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                                    )
                                    Text(
                                        text = if (!extractedRawCardHint.isNullOrBlank()) {
                                            "추출 카드정보 [$extractedRawCardHint] 기준으로 법인카드 여부가 영구저장됩니다"
                                        } else {
                                            "선택된 카드사 [$cardCompany] 기준으로 법인카드 여부가 영구저장됩니다"
                                        },
                                        fontSize = 9.5.sp,
                                        color = Slate500
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                    Surface(
                                        onClick = { isCorporate = false },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (!isCorporate) Color(0xFF2563EB) else Slate50,
                                        border = BorderStroke(1.dp, if (!isCorporate) Color(0xFF1D4ED8) else Slate200)
                                    ) {
                                        Text(
                                            text = "개인카드",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (!isCorporate) PureWhite else Slate600,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                    Surface(
                                        onClick = { isCorporate = true },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isCorporate) Color(0xFF0F172A) else Slate50,
                                        border = BorderStroke(1.dp, if (isCorporate) Color(0xFF0F172A) else Slate200)
                                    ) {
                                        Text(
                                            text = "법인카드",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = if (isCorporate) PureWhite else Slate600,
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            FlowRow(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(4.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_CARD_COMPANIES.forEach { cardPreset ->
                                    val isCardSelected = cardCompany.trim() == cardPreset
                                    Surface(
                                        onClick = {
                                            cardCompany = cardPreset
                                            val isCorpSelected = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(userClassificationInput) ||
                                                com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(selectedCustomCategoryName)
                                            if (isCorpSelected) {
                                                isCorporate = true
                                            } else {
                                                onResolveCardCorporate(cardPreset)?.let { isCorporate = it }
                                            }
                                        },
                                        shape = RoundedCornerShape(6.dp),
                                        color = if (isCardSelected) Color(0xFFEFF6FF) else Slate50,
                                        border = BorderStroke(1.dp, if (isCardSelected) Color(0xFF2563EB) else Slate200)
                                    ) {
                                        Text(
                                            text = cardPreset,
                                            fontSize = 10.sp,
                                            fontWeight = if (isCardSelected) FontWeight.Bold else FontWeight.Medium,
                                            color = if (isCardSelected) Color(0xFF1D4ED8) else Slate700,
                                            modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                        )
                                    }
                                }
                            }

                            AutoLogueTextField(
                                value = cardCompany,
                                onValueChange = {
                                    cardCompany = it
                                    val isCorpSelected = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(userClassificationInput) ||
                                        com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(selectedCustomCategoryName)
                                    if (!isCorpSelected) {
                                        onResolveCardCorporate(it)?.let { savedCorp -> isCorporate = savedCorp }
                                    }
                                },
                                label = "카드회사 / 결제수단 직접 입력",
                                placeholder = "예: 하나카드, NH농협카드, 우리카드, 신한카드",
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }

                    // 3. 고정지출 지정 및 저장
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "고정지출(정기지출) 지정",
                                style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                            )
                            if (isFixedExpense) {
                                Text(
                                    text = "고정지출 모아보기 및 통계에 반영됨",
                                    fontSize = 10.sp,
                                    color = Color(0xFF5B21B6),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                onClick = { isFixedExpense = false },
                                shape = RoundedCornerShape(6.dp),
                                color = if (!isFixedExpense) Slate800 else Slate50,
                                border = BorderStroke(1.dp, if (!isFixedExpense) Slate900 else Slate200),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "일반 지출",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!isFixedExpense) PureWhite else Slate600,
                                    modifier = Modifier.padding(vertical = 7.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                            Surface(
                                onClick = { isFixedExpense = true },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isFixedExpense) Color(0xFF5B21B6) else Color(0xFFF5F3FF),
                                border = BorderStroke(1.dp, if (isFixedExpense) Color(0xFF4C1D95) else Color(0xFFDDD6FE)),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "고정지출로 지정",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isFixedExpense) PureWhite else Color(0xFF5B21B6),
                                    modifier = Modifier.padding(vertical = 7.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }
                    }

                    // 4. 카테고리 변경 + 수정/삭제 + '기타' 선택 시 추가 카테고리 영구 등록
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "카테고리 변경",
                                style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                            )
                            Surface(
                                onClick = onOpenCategoryManage,
                                shape = RoundedCornerShape(6.dp),
                                color = Slate100,
                                border = BorderStroke(1.dp, Slate300)
                            ) {
                                Text(
                                    text = "카테고리 수정/삭제",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate700,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            managedCategories.forEach { item ->
                                val isSelected = if (item.isBuiltIn) {
                                    selectedCustomCategoryName == null && selectedCategory == item.baseCategory
                                } else {
                                    selectedCustomCategoryName == item.displayName
                                }
                                Surface(
                                    onClick = {
                                        val nextCat = if (item.isBuiltIn) item.baseCategory else ExpenseCategory.ETC
                                        val wasBank = transaction.isBankAccountTransaction(selectedCategory)
                                        val willBeBank = transaction.isBankAccountTransaction(nextCat)
                                        selectedCategory = nextCat
                                        selectedCustomCategoryName = if (item.isBuiltIn) null else item.displayName
                                        if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(item.displayName)) {
                                            isCorporate = true
                                        }
                                        if (!wasBank && willBeBank) {
                                            cardCompany = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalBankName(
                                                cardCompany,
                                                transaction.cardOrBankName,
                                                transaction.originalText
                                            ) ?: inferredBank
                                        } else if (wasBank && !willBeBank) {
                                            cardCompany = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.inferCanonicalCardCompany(
                                                cardCompany,
                                                transaction.cardOrBankName,
                                                transaction.originalText
                                            ) ?: inferredCard
                                        }
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                    color = if (isSelected) Color(0xFF2563EB) else Slate50,
                                    shadowElevation = if (isSelected) 2.dp else 1.dp,
                                    border = BorderStroke(1.dp, if (isSelected) Color(0xFF1D4ED8) else Slate200)
                                ) {
                                    Text(
                                        text = item.displayName,
                                        fontSize = 11.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                                        color = if (isSelected) PureWhite else Slate700,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                    )
                                }
                            }
                        }

                        // 현재 선택된 카테고리에 대한 빠른 이름 수정 / 삭제 바
                        val currentSelectedManagedCat = managedCategories.firstOrNull { item ->
                            if (item.isBuiltIn) {
                                selectedCustomCategoryName == null && selectedCategory == item.baseCategory
                            } else {
                                selectedCustomCategoryName == item.displayName
                            }
                        }
                        if (currentSelectedManagedCat != null && currentSelectedManagedCat.baseCategory != ExpenseCategory.ETC || !currentSelectedManagedCat?.displayName.isNullOrBlank()) {
                            if (editingCatItemTarget != null && editingCatItemTarget?.id == currentSelectedManagedCat?.id) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    OutlinedTextField(
                                        value = editingCatNewName,
                                        onValueChange = { editingCatNewName = it },
                                        label = { Text("카테고리 이름 수정", fontSize = 11.sp) },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val target = editingCatItemTarget
                                            val trimmed = editingCatNewName.trim()
                                            if (target != null && trimmed.isNotEmpty()) {
                                                onUpdateCategoryName(target, trimmed)
                                                if (!target.isBuiltIn) {
                                                    selectedCustomCategoryName = trimmed
                                                }
                                                if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(trimmed)) {
                                                    isCorporate = true
                                                }
                                            }
                                            editingCatItemTarget = null
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                    ) {
                                        Text("변경", fontSize = 11.sp, color = PureWhite)
                                    }
                                    TextButton(onClick = { editingCatItemTarget = null }) {
                                        Text("취소", fontSize = 11.sp, color = Slate600)
                                    }
                                }
                            } else if (currentSelectedManagedCat != null && (!currentSelectedManagedCat.isBuiltIn || currentSelectedManagedCat.baseCategory != ExpenseCategory.ETC)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.End,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "선택됨: ${currentSelectedManagedCat.displayName}",
                                        fontSize = 10.sp,
                                        color = Slate500,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Surface(
                                        onClick = {
                                            editingCatItemTarget = currentSelectedManagedCat
                                            editingCatNewName = currentSelectedManagedCat.displayName
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFEFF6FF),
                                        border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                                    ) {
                                        Text(
                                            text = "이름 수정",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2563EB),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Surface(
                                        onClick = {
                                            onDeleteCategory(currentSelectedManagedCat)
                                            selectedCategory = ExpenseCategory.ETC
                                            selectedCustomCategoryName = null
                                        },
                                        shape = RoundedCornerShape(4.dp),
                                        color = Color(0xFFFEF2F2),
                                        border = BorderStroke(1.dp, Color(0xFFFECACA))
                                    ) {
                                        Text(
                                            text = "항목 삭제",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFDC2626),
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }
                        }

                        if (selectedCategory == ExpenseCategory.FUEL || selectedCategory == ExpenseCategory.CAR_MAINTENANCE) {
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = Color(0xFFECFDF5),
                                border = BorderStroke(1.dp, Color(0xFF6EE7B7)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "차량 주행 관련 카테고리(${selectedCategory.displayName})로 저장 시 차계부에도 함께 표시되며, 영구저장된 결제명이 차계부에도 자동 매칭됩니다.",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Color(0xFF047857),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp)
                                )
                            }
                        }

                        if (selectedCategory == ExpenseCategory.ETC) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Color(0xFFF8FAFC),
                                border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text(
                                        text = "기타 선택: 추가 카테고리를 영구 등록할 수 있습니다",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color(0xFF1E40AF)
                                    )
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            AutoLogueTextField(
                                                value = newPermanentCategoryInput,
                                                onValueChange = { newPermanentCategoryInput = it },
                                                label = "새 카테고리명",
                                                placeholder = "예: 반려동물, 학원/교육, 경조사",
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val trimmed = newPermanentCategoryInput.trim()
                                                if (trimmed.isNotEmpty()) {
                                                    onAddPermanentCategory(trimmed)
                                                    selectedCustomCategoryName = trimmed
                                                    if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(trimmed)) {
                                                        isCorporate = true
                                                    }
                                                    newPermanentCategoryInput = ""
                                                }
                                            },
                                            enabled = newPermanentCategoryInput.trim().isNotEmpty(),
                                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 10.dp, vertical = 10.dp)
                                        ) {
                                            Text("영구 추가", fontSize = 11.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 5. 카테고리와 별개인 '사용자 분류' 지정 (삭제/수정 및 배지 색상 변경 지원)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "사용자 분류 (컬러 배지 · 수정/삭제/색상 변경)",
                                style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                            )
                            Surface(
                                onClick = onOpenUserClassificationManage,
                                shape = RoundedCornerShape(6.dp),
                                color = Slate100,
                                border = BorderStroke(1.dp, Slate300)
                            ) {
                                Text(
                                    text = "전체 분류 관리",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate700,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            Surface(
                                onClick = {
                                    userClassificationInput = ""
                                    editingUcNameTarget = null
                                },
                                shape = RoundedCornerShape(6.dp),
                                color = if (userClassificationInput.isBlank()) Slate800 else Slate50,
                                border = BorderStroke(1.dp, if (userClassificationInput.isBlank()) Slate800 else Slate200)
                            ) {
                                Text(
                                    text = "미지정",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (userClassificationInput.isBlank()) PureWhite else Slate600,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 4.dp)
                                )
                            }
                            userClassifications.forEach { uc ->
                                val (fg, bg) = getUserClassificationBadgeColors(uc.colorIndex)
                                val isSelected = userClassificationInput.trim() == uc.name
                                Surface(
                                    onClick = {
                                        userClassificationInput = uc.name
                                        if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(uc.name)) {
                                            isCorporate = true
                                        }
                                        editingUcNameTarget = null
                                    },
                                    shape = RoundedCornerShape(6.dp),
                                    color = if (isSelected) fg else bg,
                                    border = BorderStroke(1.dp, fg)
                                ) {
                                    Text(
                                        text = uc.name,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = if (isSelected) PureWhite else fg,
                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                    )
                                }
                            }
                        }

                        // 선택된 사용자 분류 항목이 있을 때 인라인 수정/삭제 및 배지 색상 즉시 변경 바 제공
                        val selectedUcItem = userClassifications.firstOrNull {
                            it.name.equals(userClassificationInput.trim(), ignoreCase = true)
                        }
                        if (selectedUcItem != null) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Slate50,
                                border = BorderStroke(1.dp, Slate200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    if (editingUcNameTarget == selectedUcItem.name) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            OutlinedTextField(
                                                value = editingUcNewName,
                                                onValueChange = { editingUcNewName = it },
                                                label = { Text("분류 이름 수정", fontSize = 11.sp) },
                                                singleLine = true,
                                                modifier = Modifier.weight(1f)
                                            )
                                            Button(
                                                onClick = {
                                                    val trimmed = editingUcNewName.trim()
                                                    if (trimmed.isNotEmpty()) {
                                                        onUpdateUserClassification(selectedUcItem.name, trimmed, selectedUcItem.colorIndex)
                                                        userClassificationInput = trimmed
                                                        if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(trimmed)) {
                                                            isCorporate = true
                                                        }
                                                    }
                                                    editingUcNameTarget = null
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 8.dp)
                                            ) {
                                                Text("저장", fontSize = 11.sp, color = PureWhite)
                                            }
                                            TextButton(onClick = { editingUcNameTarget = null }) {
                                                Text("취소", fontSize = 11.sp, color = Slate600)
                                            }
                                        }
                                    } else {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "'${selectedUcItem.name}' 배지 색상 및 항목 관리",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Slate700
                                            )
                                            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                                Surface(
                                                    onClick = {
                                                        editingUcNameTarget = selectedUcItem.name
                                                        editingUcNewName = selectedUcItem.name
                                                    },
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFEFF6FF),
                                                    border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                                                ) {
                                                    Text(
                                                        text = "이름 수정",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFF2563EB),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                                Surface(
                                                    onClick = {
                                                        onDeleteUserClassification(selectedUcItem.name)
                                                        userClassificationInput = ""
                                                    },
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFFEF2F2),
                                                    border = BorderStroke(1.dp, Color(0xFFFECACA))
                                                ) {
                                                    Text(
                                                        text = "삭제",
                                                        fontSize = 10.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color(0xFFDC2626),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    // 배지 색상 10종 원클릭 변경 팔레트
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "배지 색상:",
                                            fontSize = 10.sp,
                                            color = Slate500,
                                            fontWeight = FontWeight.Medium
                                        )
                                        (0 until com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT).forEach { cIdx ->
                                            val (cFg, cBg) = getUserClassificationBadgeColors(cIdx)
                                            val isCurrentColor = (selectedUcItem.colorIndex % com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT) == cIdx
                                            Box(
                                                modifier = Modifier
                                                    .size(if (isCurrentColor) 22.dp else 18.dp)
                                                    .clip(CircleShape)
                                                    .background(if (isCurrentColor) cFg else cBg)
                                                    .border(
                                                        width = if (isCurrentColor) 2.dp else 1.dp,
                                                        color = cFg,
                                                        shape = CircleShape
                                                    )
                                                    .clickable {
                                                        onUpdateUserClassification(selectedUcItem.name, selectedUcItem.name, cIdx)
                                                    },
                                                contentAlignment = Alignment.Center
                                            ) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(6.dp)
                                                        .clip(CircleShape)
                                                        .background(if (isCurrentColor) PureWhite else cFg)
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        AutoLogueTextField(
                            value = userClassificationInput,
                            onValueChange = {
                                userClassificationInput = it
                                if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(it)) {
                                    isCorporate = true
                                }
                            },
                            label = "사용자명 직접 입력 (입력 시 자동 추가)",
                            placeholder = "예: 본인, 가족, 업무/법인, 배우자",
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    // 6. 메모 작성
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

                Spacer(modifier = Modifier.height(10.dp))
                HairlineDivider()
                Spacer(modifier = Modifier.height(10.dp))

                // 저장 버튼 바로 위에 배치된 [1회성 저장 / 영구저장] 선택 및 영구저장 항목 지정 카드
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = if (isOneTimeSave) Color(0xFFFFFBEB) else Color(0xFFEFF6FF),
                    border = BorderStroke(1.dp, if (isOneTimeSave) Color(0xFFFDE68A) else Color(0xFFBFDBFE)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Surface(
                                onClick = { if (!isSaving) isOneTimeSave = true },
                                shape = RoundedCornerShape(6.dp),
                                color = if (isOneTimeSave) Color(0xFFD97706) else PureWhite,
                                border = BorderStroke(1.dp, if (isOneTimeSave) Color(0xFFB45309) else Slate200),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "1회성 저장",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isOneTimeSave) PureWhite else Slate700,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                            Surface(
                                onClick = { if (!isSaving) isOneTimeSave = false },
                                shape = RoundedCornerShape(6.dp),
                                color = if (!isOneTimeSave) Color(0xFF2563EB) else PureWhite,
                                border = BorderStroke(1.dp, if (!isOneTimeSave) Color(0xFF1D4ED8) else Slate200),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "영구저장",
                                    fontSize = 12.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (!isOneTimeSave) PureWhite else Slate700,
                                    modifier = Modifier.padding(vertical = 8.dp),
                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                )
                            }
                        }

                        if (isOneTimeSave) {
                            Text(
                                text = "이번 $txDirLabel 내역만 단독 수정됩니다 (동일 결제처 및 기존 사용내역에 영향 없음)",
                                fontSize = 10.sp,
                                color = Color(0xFFB45309)
                            )
                        } else {
                            // 1) 영구저장할 항목(속성) 선택 섹션
                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, Color(0xFFDBEAFE)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "영구저장 적용 항목 선택 (금액 제외)",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1E3A8A)
                                        )
                                        val allChecked = applyMerchantName && applyCategory &&
                                            applyUserClassification && applyCardAndCorporate &&
                                            applyFixedExpense && applyMemo
                                        Surface(
                                            onClick = {
                                                if (!isSaving) {
                                                    if (allChecked) {
                                                        applyMerchantName = true
                                                        applyCategory = true
                                                        applyUserClassification = true
                                                        applyCardAndCorporate = true
                                                        applyFixedExpense = true
                                                        applyMemo = false
                                                    } else {
                                                        applyMerchantName = true
                                                        applyCategory = true
                                                        applyUserClassification = true
                                                        applyCardAndCorporate = true
                                                        applyFixedExpense = true
                                                        applyMemo = true
                                                    }
                                                }
                                            },
                                            shape = RoundedCornerShape(4.dp),
                                            color = Color(0xFFEFF6FF),
                                            border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                                        ) {
                                            Text(
                                                text = if (allChecked) "기본선택" else "전체선택",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1D4ED8),
                                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                            )
                                        }
                                    }

                                    val optionItems = listOf(
                                        Triple("결제명", applyMerchantName) { v: Boolean -> applyMerchantName = v },
                                        Triple("카테고리", applyCategory) { v: Boolean -> applyCategory = v },
                                        Triple("사용자 분류", applyUserClassification) { v: Boolean -> applyUserClassification = v },
                                        Triple(if (isBankTx) "은행 정보" else "카드·법인여부", applyCardAndCorporate) { v: Boolean -> applyCardAndCorporate = v },
                                        Triple("고정지출", applyFixedExpense) { v: Boolean -> applyFixedExpense = v },
                                        Triple("메모", applyMemo) { v: Boolean -> applyMemo = v }
                                    )

                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalArrangement = Arrangement.spacedBy(5.dp),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        optionItems.forEach { (label, checked, onToggle) ->
                                            Surface(
                                                onClick = { if (!isSaving) onToggle(!checked) },
                                                shape = RoundedCornerShape(6.dp),
                                                color = if (checked) Color(0xFF2563EB) else Slate50,
                                                border = BorderStroke(1.dp, if (checked) Color(0xFF1D4ED8) else Slate200)
                                            ) {
                                                Text(
                                                    text = if (checked) "✓ $label" else label,
                                                    fontSize = 10.sp,
                                                    fontWeight = if (checked) FontWeight.Bold else FontWeight.Medium,
                                                    color = if (checked) PureWhite else Slate600,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }
                            }

                            // 2) 기존 사용내역 포함 일괄 적용 대상 선택 섹션 (금액 무관)
                            val autoMatchedTxs = remember(candidateExistingTransactions, autoMatchedExistingTxIds) {
                                candidateExistingTransactions.filter { it.id in autoMatchedExistingTxIds }
                            }
                            val otherCandidateTxs = remember(candidateExistingTransactions, autoMatchedExistingTxIds) {
                                candidateExistingTransactions.filter { it.id !in autoMatchedExistingTxIds }.take(30)
                            }
                            val txDateFmt = remember { DateTimeFormatter.ofPattern("MM.dd HH:mm", Locale.KOREA) }

                            Surface(
                                shape = RoundedCornerShape(6.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, Color(0xFFDBEAFE)),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Column(
                                    modifier = Modifier.padding(8.dp),
                                    verticalArrangement = Arrangement.spacedBy(5.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "기존 사용내역 일괄 적용 (${selectedExistingTxIds.size}건 선택)",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1E3A8A)
                                            )
                                            Text(
                                                text = "동일 항목 기존 내역의 금액은 그대로 유지하고 선택 속성만 적용",
                                                fontSize = 9.sp,
                                                color = Slate500
                                            )
                                        }
                                        Row(
                                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            if (autoMatchedExistingTxIds.isNotEmpty()) {
                                                Surface(
                                                    onClick = {
                                                        if (!isSaving) {
                                                            selectedExistingTxIds = if (selectedExistingTxIds.containsAll(autoMatchedExistingTxIds)) {
                                                                selectedExistingTxIds - autoMatchedExistingTxIds
                                                            } else {
                                                                selectedExistingTxIds + autoMatchedExistingTxIds
                                                            }
                                                        }
                                                    },
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = Color(0xFFEFF6FF),
                                                    border = BorderStroke(1.dp, Color(0xFFBFDBFE))
                                                ) {
                                                    val allAutoSelected = selectedExistingTxIds.containsAll(autoMatchedExistingTxIds)
                                                    Text(
                                                        text = if (allAutoSelected) "동일항목 해제" else "동일항목(${autoMatchedExistingTxIds.size}건)",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = Color(0xFF1D4ED8),
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                            if (candidateExistingTransactions.isNotEmpty()) {
                                                Surface(
                                                    onClick = { showExistingTxSelector = !showExistingTxSelector },
                                                    shape = RoundedCornerShape(4.dp),
                                                    color = if (showExistingTxSelector) Color(0xFF1E40AF) else Slate100,
                                                    border = BorderStroke(1.dp, if (showExistingTxSelector) Color(0xFF1E3A8A) else Slate300)
                                                ) {
                                                    Text(
                                                        text = if (showExistingTxSelector) "내역 접기 ▴" else "내역 선택 ▾",
                                                        fontSize = 9.sp,
                                                        fontWeight = FontWeight.SemiBold,
                                                        color = if (showExistingTxSelector) PureWhite else Slate700,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }
                                    }

                                    if (showExistingTxSelector) {
                                        if (autoMatchedTxs.isEmpty()) {
                                            Text(
                                                text = "동일 결제처로 자동 매칭된 기존 내역이 없습니다.",
                                                fontSize = 10.sp,
                                                color = Slate500
                                            )
                                        } else {
                                            Text(
                                                text = "동일 결제처 기존 내역 (${autoMatchedTxs.size}건 · 금액 무관 매칭)",
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1D4ED8)
                                            )
                                            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                autoMatchedTxs.forEach { candTx ->
                                                    val isChecked = candTx.id in selectedExistingTxIds
                                                    val candCat = customCategoryByTxId[candTx.id] ?: candTx.category.displayName
                                                    val candUcName = userClassByTxId[candTx.id]?.name
                                                    Surface(
                                                        onClick = {
                                                            if (!isSaving) {
                                                                selectedExistingTxIds = if (isChecked) {
                                                                    selectedExistingTxIds - candTx.id
                                                                } else {
                                                                    selectedExistingTxIds + candTx.id
                                                                }
                                                            }
                                                        },
                                                        shape = RoundedCornerShape(5.dp),
                                                        color = if (isChecked) Color(0xFFEFF6FF) else Slate50,
                                                        border = BorderStroke(1.dp, if (isChecked) Color(0xFF93C5FD) else Slate200),
                                                        modifier = Modifier.fillMaxWidth()
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Column(modifier = Modifier.weight(1f)) {
                                                                Text(
                                                                    text = "${if (isChecked) "✓ " else ""}${candTx.merchantName} · $candCat${if (!candUcName.isNullOrBlank()) " ($candUcName)" else ""}",
                                                                    fontSize = 10.sp,
                                                                    fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Medium,
                                                                    color = if (isChecked) Color(0xFF1E3A8A) else Slate700,
                                                                    maxLines = 1,
                                                                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                                )
                                                                Text(
                                                                    text = "${candTx.timestamp.format(txDateFmt)} · ${"%,d".format(candTx.amount)}원 (금액 유지)",
                                                                    fontSize = 9.sp,
                                                                    color = Slate500
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }

                                        if (otherCandidateTxs.isNotEmpty()) {
                                            Surface(
                                                onClick = { showOtherExistingTxs = !showOtherExistingTxs },
                                                shape = RoundedCornerShape(4.dp),
                                                color = Slate50,
                                                border = BorderStroke(1.dp, Slate200),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text(
                                                    text = if (showOtherExistingTxs) {
                                                        "다른 기존 $txDirLabel 내역 숨기기 ▴"
                                                    } else {
                                                        "+ 다른 기존 $txDirLabel 내역에서도 선택하기 (${otherCandidateTxs.size}건) ▾"
                                                    },
                                                    fontSize = 9.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Slate600,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                                    textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                                )
                                            }
                                            if (showOtherExistingTxs) {
                                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                                    otherCandidateTxs.forEach { candTx ->
                                                        val isChecked = candTx.id in selectedExistingTxIds
                                                        val candCat = customCategoryByTxId[candTx.id] ?: candTx.category.displayName
                                                        val candUcName = userClassByTxId[candTx.id]?.name
                                                        Surface(
                                                            onClick = {
                                                                if (!isSaving) {
                                                                    selectedExistingTxIds = if (isChecked) {
                                                                        selectedExistingTxIds - candTx.id
                                                                    } else {
                                                                        selectedExistingTxIds + candTx.id
                                                                    }
                                                                }
                                                            },
                                                            shape = RoundedCornerShape(5.dp),
                                                            color = if (isChecked) Color(0xFFEFF6FF) else PureWhite,
                                                            border = BorderStroke(1.dp, if (isChecked) Color(0xFF93C5FD) else Slate200),
                                                            modifier = Modifier.fillMaxWidth()
                                                        ) {
                                                            Row(
                                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 5.dp),
                                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                                verticalAlignment = Alignment.CenterVertically
                                                            ) {
                                                                Column(modifier = Modifier.weight(1f)) {
                                                                    Text(
                                                                        text = "${if (isChecked) "✓ " else ""}${candTx.merchantName} · $candCat${if (!candUcName.isNullOrBlank()) " ($candUcName)" else ""}",
                                                                        fontSize = 10.sp,
                                                                        fontWeight = if (isChecked) FontWeight.Bold else FontWeight.Medium,
                                                                        color = if (isChecked) Color(0xFF1E3A8A) else Slate700,
                                                                        maxLines = 1,
                                                                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                                                                    )
                                                                    Text(
                                                                        text = "${candTx.timestamp.format(txDateFmt)} · ${"%,d".format(candTx.amount)}원 (금액 유지)",
                                                                        fontSize = 9.sp,
                                                                        color = Slate500
                                                                    )
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Text(
                                text = "선택한 항목들이 영구 규칙 및 선택된 기존 내역(${selectedExistingTxIds.size}건)에 동일 적용됩니다.",
                                fontSize = 10.sp,
                                color = Color(0xFF1E40AF)
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                AutoLogueDangerButton(
                    text = "삭제",
                    enabled = !isSaving,
                    onClick = onDelete
                )

                Button(
                    onClick = {
                        if (!isSaving) {
                            val finalCustomCat = newPermanentCategoryInput.trim().takeIf { it.isNotEmpty() }?.also {
                                onAddPermanentCategory(it)
                            } ?: selectedCustomCategoryName
                            val defaultFallback = if (isBankTx) inferredBank else inferredCard
                            onSave(
                                merchantName.trim().ifBlank { transaction.merchantName },
                                selectedCategory,
                                cardCompany.trim().ifBlank { defaultFallback },
                                if (isBankTx) false else isCorporate,
                                finalCustomCat,
                                userClassificationInput.trim().ifEmpty { null },
                                isOneTimeSave,
                                isFixedExpense,
                                memo,
                                com.autologue.app.data.preferences.PermanentSaveOptions(
                                    applyMerchantName = applyMerchantName,
                                    applyCategory = applyCategory,
                                    applyUserClassification = applyUserClassification,
                                    applyCardAndCorporate = applyCardAndCorporate,
                                    applyFixedExpense = applyFixedExpense,
                                    applyMemo = applyMemo,
                                    selectedExistingTxIds = selectedExistingTxIds
                                )
                            )
                        }
                    },
                    enabled = !isSaving,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2563EB)),
                    shape = RoundedCornerShape(10.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    if (isSaving) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(15.dp),
                            strokeWidth = 2.dp,
                            color = PureWhite
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text("저장 중...", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    } else {
                        Text("저장", color = PureWhite, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                    }
                }
            }
        },
        dismissButton = {
            AutoLogueSecondaryButton(
                text = "취소",
                onClick = { if (!isSaving) onDismiss() }
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
