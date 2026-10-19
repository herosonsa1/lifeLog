package com.autologue.app.presentation.expense

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
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
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.PieChart
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
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

private val ROW_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("M.d(E) HH:mm", Locale.KOREA)
private val MODAL_DATE_FORMATTER: DateTimeFormatter =
    DateTimeFormatter.ofPattern("yyyy.MM.dd(E) HH:mm", Locale.KOREA)

/**
 * 가계부 상단 서브 페이지 구분 (결제 내역 전용 뷰 vs 소비 통계 리포트 전용 페이지)
 */
private enum class ExpensePageTab {
    TRANSACTIONS, // 결제 내역 조회 및 빠른 관리 (기본)
    STATISTICS    // 원형 그래프 + 카테고리별/사용자별 심층 소비 통계 리포트
}

/**
 * 통계 페이지 원형(도넛) 그래프 분석 기준
 */
private enum class ExpenseDonutDimension {
    BY_CATEGORY,  // 카테고리별 소비 비중
    BY_USER       // 사용자별 소비 비중
}

/**
 * 원형 그래프 조각 데이터 모델
 */
private data class DonutSliceData(
    val key: String,
    val label: String,
    val amount: Long,
    val txCount: Int,
    val percentage: Float,
    val color: Color,
    val isOtherBucket: Boolean = false
)

/**
 * 단일 패스 O(N)으로 사전 계산되는 빠른 필터 칩 카운트 및 핵심 요약 지표
 */
private data class ExpenseQuickFilterSnapshot(
    val recurringCount: Int,
    val recurringAmount: Long,
    val builtInCatCounts: Map<ExpenseCategory, Int>,
    val customCatCounts: Map<String, Int>,
    val userCounts: Map<String, Int>,
    val unassignedCount: Int,
    val corporateCount: Int,
    val corporateAmount: Long
)

/**
 * 스크롤 중 연산을 0으로 만들기 위한 거래 행 사전 계산 메타데이터
 */
private data class PrecomputedRowMeta(
    val matchingAccount: UserAccount?,
    val effectiveCatName: String,
    val formattedDate: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExpenseScreen(
    viewModel: ExpenseViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // 2026.10 UX: [결제 내역] 페이지와 [소비 통계 리포트] 페이지 분리 + 접었다 폈다 하는 아코디언 상태
    var activePageTab by remember { mutableStateOf(ExpensePageTab.TRANSACTIONS) }
    var isToolsMenuExpanded by remember { mutableStateOf(false) }
    var isQuickFilterExpanded by remember { mutableStateOf(false) }
    var donutDimension by remember { mutableStateOf(ExpenseDonutDimension.BY_CATEGORY) }
    var selectedDonutSliceKey by remember(uiState.selectedYearMonth, uiState.filterMode, donutDimension) {
        mutableStateOf<String?>(null)
    }
    var isCatRankSectionExpanded by remember { mutableStateOf(true) }
    var showAllCategoryRanks by remember { mutableStateOf(false) }
    var isUserRankSectionExpanded by remember { mutableStateOf(true) }
    var showAllUserRanks by remember { mutableStateOf(false) }

    val filteredTransactions = remember(
        uiState.transactions,
        uiState.selectedCategory,
        uiState.selectedCustomCategoryName,
        uiState.isRecurringFilterOnly,
        uiState.selectedUserClassification,
        uiState.selectedCorporateFilter,
        uiState.recurringTransactionIds,
        uiState.customCategoryByTxId,
        uiState.userClassByTxId,
        uiState.corporateByTxId
    ) {
        uiState.filteredTransactions
    }
    val filteredExpenseTotal = remember(filteredTransactions) {
        filteredTransactions.filter { !it.isDepositTransaction() && !it.isSelfTransfer() }.sumOf { it.amount }
    }

    // 단일 패스 O(N) 필터 카운트 및 고정/법인 합계 사전 계산 (리컴포지션 시 다중 루프 완전 제거)
    val quickFilterSnapshot = remember(
        uiState.transactions,
        uiState.recurringTransactionIds,
        uiState.customCategoryByTxId,
        uiState.userClassByTxId,
        uiState.corporateByTxId
    ) {
        var recCount = 0
        var recAmt = 0L
        val builtInMap = HashMap<ExpenseCategory, Int>(16)
        val customMap = HashMap<String, Int>(16)
        val userMap = HashMap<String, Int>(16)
        var unassignCnt = 0
        var corpCnt = 0
        var corpAmt = 0L

        for (tx in uiState.transactions) {
            val isRealExpense = !tx.isDepositTransaction() && !tx.isSelfTransfer()
            if (tx.id in uiState.recurringTransactionIds) {
                recCount++
                if (isRealExpense) recAmt += tx.amount
            }
            val customCat = uiState.customCategoryByTxId[tx.id]
            if (!customCat.isNullOrBlank()) {
                customMap[customCat] = (customMap[customCat] ?: 0) + 1
            } else {
                builtInMap[tx.category] = (builtInMap[tx.category] ?: 0) + 1
            }
            val uClass = uiState.userClassByTxId[tx.id]
            if (uClass == null) {
                unassignCnt++
            } else {
                userMap[uClass.name] = (userMap[uClass.name] ?: 0) + 1
            }
            if (uiState.corporateByTxId[tx.id] == true) {
                corpCnt++
                if (isRealExpense) corpAmt += tx.amount
            }
        }
        ExpenseQuickFilterSnapshot(
            recurringCount = recCount,
            recurringAmount = recAmt,
            builtInCatCounts = builtInMap,
            customCatCounts = customMap,
            userCounts = userMap,
            unassignedCount = unassignCnt,
            corporateCount = corpCnt,
            corporateAmount = corpAmt
        )
    }

    // LazyColumn 스크롤 시 메인 스레드 연산을 0으로 만들기 위한 행별 표시 메타데이터 사전 인덱싱
    val rowMetaByTxId = remember(
        filteredTransactions,
        uiState.userAccounts,
        uiState.customCategoryByTxId,
        uiState.managedCategories
    ) {
        val map = HashMap<Long, PrecomputedRowMeta>(filteredTransactions.size.coerceAtLeast(16))
        for (tx in filteredTransactions) {
            val customCatName = uiState.customCategoryByTxId[tx.id]
            val effectiveCatName = customCatName ?: viewModel.getEffectiveCategoryDisplayName(tx)
            map[tx.id] = PrecomputedRowMeta(
                matchingAccount = viewModel.getMatchingAccount(tx),
                effectiveCatName = effectiveCatName,
                formattedDate = tx.timestamp.format(ROW_DATE_FORMATTER)
            )
        }
        map
    }

    val categoryStatItems = remember(
        uiState.transactions,
        uiState.categoryTotals,
        uiState.selectedUserClassification,
        uiState.selectedCorporateFilter,
        uiState.isRecurringFilterOnly,
        uiState.customCategoryByTxId,
        uiState.userClassByTxId,
        uiState.corporateByTxId,
        uiState.recurringTransactionIds,
        uiState.managedCategories
    ) {
        uiState.getCategoryStatItems { cat ->
            viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
        }
    }
    val userStatItems = remember(
        uiState.transactions,
        uiState.userClassificationTotals,
        uiState.userClassifications,
        uiState.selectedCategory,
        uiState.selectedCustomCategoryName,
        uiState.selectedCorporateFilter,
        uiState.isRecurringFilterOnly,
        uiState.customCategoryByTxId,
        uiState.userClassByTxId,
        uiState.corporateByTxId,
        uiState.recurringTransactionIds,
        uiState.managedCategories
    ) {
        uiState.getUserClassificationStatItems { cat ->
            viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
        }
    }

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
        contentWindowInsets = WindowInsets(0.dp, 0.dp, 0.dp, 0.dp),
        containerColor = AppColors.background,
        snackbarHost = { AppSnackbarHost(snackbarHostState) },
        topBar = {
            Column(modifier = Modifier.fillMaxWidth()) {
                TopMenuAccentBar(color = MenuColors.expense)
                com.autologue.app.presentation.common.AutoLogueCompactTopBar(
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
                    },
                    containerColor = AppColors.background
                )
                HairlineDivider()
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                // =========================================================================
                // 고정 상단 컨트롤 바: [연월 선택기] + [접었다 폈다 하는 관리·도구 메뉴] + [결제 내역 | 소비 통계 리포트] 탭
                // =========================================================================
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(PureWhite)
                        .padding(horizontal = Spacing.xl, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // 연월/기간 네비게이터 (< 2026년 10월 >)
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                IconButton(
                                    onClick = { viewModel.previousMonth() },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.ChevronLeft, contentDescription = "이전달", tint = Slate700, modifier = Modifier.size(18.dp))
                                }
                                Text(
                                    text = uiState.dateDisplayTitle,
                                    style = AppTypography.body.copy(fontWeight = FontWeight.Bold, color = Slate900, fontSize = 13.5.sp),
                                    modifier = Modifier
                                        .clickable { viewModel.openDateRangeDialog() }
                                        .padding(horizontal = 6.dp)
                                )
                                IconButton(
                                    onClick = { viewModel.nextMonth() },
                                    modifier = Modifier.size(30.dp)
                                ) {
                                    Icon(Icons.Default.ChevronRight, contentDescription = "다음달", tint = Slate700, modifier = Modifier.size(18.dp))
                                }
                            }
                        }

                        // 우측 버튼 그룹: [조회조건 필터] + [관리·도구 메뉴 접기/펼치기]
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // 조회조건 필터 모달 버튼
                            Row(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (uiState.hasActiveSubFilter) Color(0xFFDBEAFE) else Slate50)
                                    .border(
                                        1.dp,
                                        if (uiState.hasActiveSubFilter) Color(0xFF3B82F6) else Slate200,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { viewModel.openDateRangeDialog() }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.CalendarMonth,
                                    contentDescription = "상세 필터",
                                    tint = if (uiState.hasActiveSubFilter) Color(0xFF1D4ED8) else Slate700,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = if (uiState.hasActiveSubFilter) "필터 적용중" else "기간·필터",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (uiState.hasActiveSubFilter) Color(0xFF1D4ED8) else Slate700
                                )
                            }

                            // 접었다 폈다 하는 [관리·도구] 아코디언 토글 버튼
                            Row(
                                modifier = Modifier
                                    .height(32.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isToolsMenuExpanded) Slate800 else Slate100)
                                    .border(
                                        1.dp,
                                        if (isToolsMenuExpanded) Slate900 else Slate200,
                                        RoundedCornerShape(8.dp)
                                    )
                                    .clickable { isToolsMenuExpanded = !isToolsMenuExpanded }
                                    .padding(horizontal = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                            ) {
                                Text(
                                    text = "관리·도구",
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isToolsMenuExpanded) PureWhite else Slate700
                                )
                                Icon(
                                    imageVector = if (isToolsMenuExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                    contentDescription = "관리 메뉴 열기/닫기",
                                    tint = if (isToolsMenuExpanded) PureWhite else Slate600,
                                    modifier = Modifier.size(15.dp)
                                )
                            }
                        }
                    }

                    // 접었다 폈다 하는 [관리·도구 서랍 패널] (평소에는 숨겨져 화면 가시성 극대화)
                    AnimatedVisibility(
                        visible = isToolsMenuExpanded,
                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180)),
                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(140))
                    ) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "가계부 설정 및 부가 도구",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate600
                                    )
                                    Text(
                                        text = "접기 ▴",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Slate500,
                                        modifier = Modifier.clickable { isToolsMenuExpanded = false }
                                    )
                                }
                                val totalPermanentRulesCount = uiState.permanentMerchantRules.size +
                                    uiState.cardCorporateRules.size +
                                    uiState.rules.size
                                Surface(
                                    shape = RoundedCornerShape(8.dp),
                                    color = Color(0xFFEFF6FF),
                                    border = BorderStroke(1.dp, Color(0xFF93C5FD)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .clickable {
                                            isToolsMenuExpanded = false
                                            viewModel.openPermanentRulesDialog()
                                        }
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 12.dp, vertical = 9.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = "영구저장 데이터 관리 (자동 치환·분류 규칙 ${totalPermanentRulesCount}건)",
                                                fontSize = 12.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF1E40AF)
                                            )
                                            Text(
                                                text = "잘못 영구저장된 결제명 자동 치환 및 카테고리 규칙 해제·원본 복원",
                                                fontSize = 10.sp,
                                                color = Color(0xFF1D4ED8)
                                            )
                                        }
                                        Surface(
                                            shape = RoundedCornerShape(6.dp),
                                            color = Color(0xFF2563EB)
                                        ) {
                                            Text(
                                                text = "관리 열기",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = PureWhite,
                                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                            )
                                        }
                                    }
                                }
                                ExpenseUniformFilterGrid(
                                    options = listOf(
                                        ExpenseGridOptionItem(
                                            label = "카테고리 관리",
                                            isSelected = false,
                                            dotColor = Color(0xFF15803D),
                                            unselectedContainerColor = PureWhite,
                                            unselectedBorderColor = Slate200,
                                            unselectedContentColor = Slate800,
                                            onClick = {
                                                isToolsMenuExpanded = false
                                                viewModel.openCategoryManageDialog()
                                            }
                                        ),
                                        ExpenseGridOptionItem(
                                            label = "사용자 분류 관리",
                                            isSelected = false,
                                            dotColor = Color(0xFF6D28D9),
                                            unselectedContainerColor = PureWhite,
                                            unselectedBorderColor = Slate200,
                                            unselectedContentColor = Slate800,
                                            onClick = {
                                                isToolsMenuExpanded = false
                                                viewModel.openUserClassificationManageDialog()
                                            }
                                        ),
                                        ExpenseGridOptionItem(
                                            label = "내 계좌 관리",
                                            isSelected = false,
                                            dotColor = Color(0xFF334155),
                                            unselectedContainerColor = PureWhite,
                                            unselectedBorderColor = Slate200,
                                            unselectedContentColor = Slate800,
                                            onClick = {
                                                isToolsMenuExpanded = false
                                                viewModel.openAccountManageDialog()
                                            }
                                        ),
                                        ExpenseGridOptionItem(
                                            label = "목표 예산 설정",
                                            isSelected = false,
                                            dotColor = Color(0xFF2563EB),
                                            unselectedContainerColor = PureWhite,
                                            unselectedBorderColor = Slate200,
                                            unselectedContentColor = Slate800,
                                            onClick = {
                                                isToolsMenuExpanded = false
                                                viewModel.openBudgetDialog()
                                            }
                                        ),
                                        ExpenseGridOptionItem(
                                            label = "문자 붙여넣기",
                                            isSelected = false,
                                            dotColor = Color(0xFF0D9488),
                                            unselectedContainerColor = PureWhite,
                                            unselectedBorderColor = Slate200,
                                            unselectedContentColor = Slate800,
                                            onClick = {
                                                isToolsMenuExpanded = false
                                                viewModel.openPasteSmsDialog()
                                            }
                                        ),
                                        ExpenseGridOptionItem(
                                            label = "엑셀 다운로드",
                                            isSelected = false,
                                            dotColor = Color(0xFFB45309),
                                            unselectedContainerColor = PureWhite,
                                            unselectedBorderColor = Slate200,
                                            unselectedContentColor = Slate800,
                                            onClick = {
                                                isToolsMenuExpanded = false
                                                viewModel.exportToExcel(context)
                                            }
                                        )
                                    ),
                                    columns = 3,
                                    itemHeight = 34.dp,
                                    fontSize = 11.5.sp
                                )
                            }
                        }
                    }

                    // 2026.10 트렌드 세그먼트 탭: [결제 내역] vs [소비 통계 리포트 (원형 그래프)]
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(38.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Slate100)
                            .border(1.dp, Slate200, RoundedCornerShape(10.dp))
                            .padding(3.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        val isTxTab = activePageTab == ExpensePageTab.TRANSACTIONS
                        val isStatTab = activePageTab == ExpensePageTab.STATISTICS

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isTxTab) PureWhite else Color.Transparent)
                                .then(
                                    if (isTxTab) Modifier.border(1.dp, Slate200, RoundedCornerShape(8.dp))
                                    else Modifier
                                )
                                .clickable { activePageTab = ExpensePageTab.TRANSACTIONS },
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "결제 내역 (${filteredTransactions.size}건)",
                                fontSize = 12.5.sp,
                                fontWeight = if (isTxTab) FontWeight.Bold else FontWeight.Medium,
                                color = if (isTxTab) Slate900 else Slate600
                            )
                        }

                        Box(
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxHeight()
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (isStatTab) PureWhite else Color.Transparent)
                                .then(
                                    if (isStatTab) Modifier.border(1.dp, Slate200, RoundedCornerShape(8.dp))
                                    else Modifier
                                )
                                .clickable { activePageTab = ExpensePageTab.STATISTICS },
                            contentAlignment = Alignment.Center
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.PieChart,
                                    contentDescription = null,
                                    tint = if (isStatTab) AppColors.primary else Slate500,
                                    modifier = Modifier.size(14.dp)
                                )
                                Text(
                                    text = "소비 통계 리포트",
                                    fontSize = 12.5.sp,
                                    fontWeight = if (isStatTab) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isStatTab) AppColors.primary else Slate600
                                )
                            }
                        }
                    }
                }
                HairlineDivider()

                // =========================================================================
                // 본문 영역: 선택된 탭([결제 내역] 또는 [소비 통계 리포트])에 따라 독립 렌더링
                // =========================================================================
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    contentPadding = PaddingValues(bottom = Spacing.xxxl)
                ) {
                    if (activePageTab == ExpensePageTab.TRANSACTIONS) {
                        // -----------------------------------------------------------------
                        // [탭 1: 결제 내역 페이지] - 컴팩트 요약 + 접이식 빠른 필터 + 결제 리스트 집중
                        // -----------------------------------------------------------------
                        item(key = "tx_summary_header") {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.xl, vertical = Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(10.dp)
                            ) {
                                // 상단 총 지출 / 총 수입 & 통계 바로가기 버튼
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column {
                                        Text(
                                            text = "${uiState.dateDisplayTitle} 총 지출",
                                            style = AppTypography.caption
                                        )
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Row(
                                            verticalAlignment = Alignment.Bottom,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "%,d원".format(uiState.totalExpense),
                                                style = AppTypography.display.copy(fontSize = 24.sp)
                                            )
                                            if (uiState.totalIncome > 0L) {
                                                Text(
                                                    text = "수입 +%,d원".format(uiState.totalIncome),
                                                    fontSize = 12.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Emerald600,
                                                    modifier = Modifier.padding(bottom = 3.dp)
                                                )
                                            }
                                        }
                                    }

                                    // 원형 그래프 및 비율 통계 페이지로 이동하는 원클릭 버튼
                                    Row(
                                        modifier = Modifier
                                            .height(34.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(Color(0xFFEFF6FF))
                                            .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(8.dp))
                                            .clickable { activePageTab = ExpensePageTab.STATISTICS }
                                            .padding(horizontal = 10.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(5.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PieChart,
                                            contentDescription = "소비 비율 통계",
                                            tint = Color(0xFF1D4ED8),
                                            modifier = Modifier.size(14.dp)
                                        )
                                        Text(
                                            text = "비율·통계 보기 >",
                                            fontSize = 11.5.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF1D4ED8)
                                        )
                                    }
                                }

                                // 월간 목표 예산 슬림 바 (1줄 요약으로 공간 절약)
                                if (uiState.filterMode == ExpenseDateFilterMode.MONTH) {
                                    val budget = uiState.monthlyBudget.coerceAtLeast(1L)
                                    val spent = uiState.totalExpense
                                    val remaining = uiState.monthlyBudget - spent
                                    val ratio = (spent.toFloat() / budget.toFloat()).coerceIn(0f, 1.5f)
                                    val progressRatio = ratio.coerceAtMost(1f)
                                    val gaugeColor = when {
                                        ratio >= 1.0f -> Color(0xFFEF4444)
                                        ratio >= 0.8f -> Color(0xFFF59E0B)
                                        else -> Color(0xFF10B981)
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(10.dp),
                                        color = Slate50,
                                        border = BorderStroke(1.dp, Slate200),
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { viewModel.openBudgetDialog() }
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 9.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "월 예산 %,d원 중 %.1f%% 사용".format(uiState.monthlyBudget, spent.toFloat() / budget * 100f),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Slate700
                                                )
                                                Text(
                                                    text = if (remaining >= 0) "%,d원 남음 (변경 >)".format(remaining) else "%,d원 초과 (변경 >)".format(-remaining),
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = if (remaining >= 0) Color(0xFF059669) else Color(0xFFDC2626)
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(6.dp))
                                            LinearProgressIndicator(
                                                progress = { progressRatio },
                                                modifier = Modifier
                                                    .fillMaxWidth()
                                                    .height(5.dp)
                                                    .clip(RoundedCornerShape(3.dp)),
                                                color = gaugeColor,
                                                trackColor = Slate200
                                            )
                                        }
                                    }
                                }

                                // 활성화된 조회조건 요약 배너
                                if (uiState.hasActiveSubFilter) {
                                    val activeLabel = uiState.buildActiveFilterLabel { cat ->
                                        viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
                                    }
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
                                                    text = "적용 필터: $activeLabel",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF1E40AF)
                                                )
                                                Text(
                                                    text = "조건 해당 지출 합계: %,d원 (${filteredTransactions.size}건)".format(filteredExpenseTotal),
                                                    fontSize = 11.5.sp,
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
                                                    text = "해제 ✕",
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

                        // 결제 내역 리스트 헤더 + 접었다 폈다 하는 [빠른 분류 필터] 아코디언 + [선택 삭제]
                        item(key = "tx_list_controls") {
                            Column(modifier = Modifier.fillMaxWidth()) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = Spacing.xl, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "결제 내역 목록 (${filteredTransactions.size}건)",
                                        style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800, fontSize = 13.sp)
                                    )

                                    Row(
                                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        val permRuleTotal = uiState.permanentMerchantRules.size +
                                            uiState.cardCorporateRules.size +
                                            uiState.rules.size
                                        // 영구저장 데이터 관리 바로가기 버튼
                                        Box(
                                            modifier = Modifier
                                                .height(30.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (permRuleTotal > 0) Color(0xFFEFF6FF) else Slate100
                                                )
                                                .border(
                                                    1.dp,
                                                    if (permRuleTotal > 0) Color(0xFF93C5FD) else Slate300,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable { viewModel.openPermanentRulesDialog() }
                                                .padding(horizontal = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = "영구저장($permRuleTotal)",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (permRuleTotal > 0) Color(0xFF1D4ED8) else Slate700
                                            )
                                        }

                                        // 접었다 폈다 하는 빠른 분류 필터 토글 버튼
                                        Row(
                                            modifier = Modifier
                                                .height(30.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(
                                                    if (isQuickFilterExpanded || uiState.hasActiveSubFilter) Color(0xFFDBEAFE)
                                                    else Slate100
                                                )
                                                .border(
                                                    1.dp,
                                                    if (isQuickFilterExpanded || uiState.hasActiveSubFilter) Color(0xFF60A5FA)
                                                    else Slate300,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable { isQuickFilterExpanded = !isQuickFilterExpanded }
                                                .padding(horizontal = 8.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(2.dp)
                                        ) {
                                            Text(
                                                text = if (uiState.hasActiveSubFilter) "필터(적용)" else "빠른 필터",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isQuickFilterExpanded || uiState.hasActiveSubFilter) Color(0xFF1D4ED8) else Slate700
                                            )
                                            Icon(
                                                imageVector = if (isQuickFilterExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = "빠른 필터 펼치기/접기",
                                                tint = if (isQuickFilterExpanded || uiState.hasActiveSubFilter) Color(0xFF1D4ED8) else Slate600,
                                                modifier = Modifier.size(14.dp)
                                            )
                                        }

                                        // 선택 삭제 토글 버튼
                                        Box(
                                            modifier = Modifier
                                                .height(30.dp)
                                                .clip(RoundedCornerShape(8.dp))
                                                .background(if (uiState.isSelectionMode) Color(0xFFFEE2E2) else Slate100)
                                                .border(
                                                    1.dp,
                                                    if (uiState.isSelectionMode) Color(0xFFFCA5A5) else Slate300,
                                                    RoundedCornerShape(8.dp)
                                                )
                                                .clickable { viewModel.toggleSelectionMode() }
                                                .padding(horizontal = 8.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(3.dp)
                                            ) {
                                                Icon(
                                                    imageVector = if (uiState.isSelectionMode) Icons.Default.Close else Icons.Default.Delete,
                                                    contentDescription = "선택 삭제",
                                                    tint = if (uiState.isSelectionMode) Color(0xFFDC2626) else Slate700,
                                                    modifier = Modifier.size(13.dp)
                                                )
                                                Text(
                                                    text = if (uiState.isSelectionMode) "취소" else "선택삭제",
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
                                            val allSelected = filteredTransactions.isNotEmpty() &&
                                                uiState.selectedTransactionIds.size == filteredTransactions.size
                                            Row(
                                                verticalAlignment = Alignment.CenterVertically,
                                                modifier = Modifier.clickable { viewModel.selectAllFilteredTransactions() }
                                            ) {
                                                Checkbox(
                                                    checked = allSelected,
                                                    onCheckedChange = { viewModel.selectAllFilteredTransactions() }
                                                )
                                                Text(
                                                    text = "전체 선택 (${uiState.selectedTransactionIds.size}/${filteredTransactions.size})",
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

                                // 접었다 폈다 하는 2단 인라인 빠른 필터 패널 (사전 계산된 quickFilterSnapshot으로 O(1) 렌더링)
                                AnimatedVisibility(
                                    visible = isQuickFilterExpanded,
                                    enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180)),
                                    exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(140))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = Spacing.xl, vertical = 4.dp)
                                            .clip(RoundedCornerShape(10.dp))
                                            .background(Slate50)
                                            .border(1.dp, Slate200, RoundedCornerShape(10.dp))
                                            .padding(horizontal = 10.dp, vertical = 8.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        // [1단] 카테고리별 조회 필터 칩 바 (기타 항목은 항상 제일 마지막에 배치)
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier.width(52.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Text(
                                                    text = "카테고리",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Slate600
                                                )
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val isAllCatSelected = uiState.selectedCategory == null &&
                                                    uiState.selectedCustomCategoryName == null &&
                                                    !uiState.isRecurringFilterOnly
                                                ExpenseInlineFilterChip(
                                                    text = "전체 (${uiState.transactions.size})",
                                                    isSelected = isAllCatSelected,
                                                    onClick = { viewModel.setCategoryFilter(null) }
                                                )

                                                ExpenseInlineFilterChip(
                                                    text = "정기지출 (${quickFilterSnapshot.recurringCount})",
                                                    isSelected = uiState.isRecurringFilterOnly,
                                                    onClick = { viewModel.toggleRecurringFilter() },
                                                    selectedContainerColor = Color(0xFF4338CA),
                                                    selectedBorderColor = Color(0xFF3730A3),
                                                    unselectedContainerColor = Color(0xFFEEF2FF),
                                                    unselectedBorderColor = Color(0xFFC7D2FE),
                                                    unselectedContentColor = Color(0xFF4338CA)
                                                )

                                                val builtInCatsToShow = remember(uiState.categoryTotals, uiState.managedCategories) {
                                                    (uiState.categoryTotals.keys +
                                                        uiState.managedCategories.filter { it.isBuiltIn }.map { it.baseCategory }).distinct()
                                                }
                                                // 1) '기타(ETC)'를 제외한 기본 카테고리 먼저 표시
                                                builtInCatsToShow.filter { it != ExpenseCategory.ETC }.forEach { cat ->
                                                    val isSelected = uiState.selectedCategory == cat &&
                                                        uiState.selectedCustomCategoryName == null &&
                                                        !uiState.isRecurringFilterOnly
                                                    val count = quickFilterSnapshot.builtInCatCounts[cat] ?: 0
                                                    val displayCat = viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
                                                    ExpenseInlineFilterChip(
                                                        text = "$displayCat ($count)",
                                                        isSelected = isSelected,
                                                        onClick = { viewModel.toggleCategoryFilter(cat) }
                                                    )
                                                }

                                                // 2) 사용자 추가 커스텀 카테고리 표시
                                                uiState.managedCategories.filter { !it.isBuiltIn }.forEach { customItem ->
                                                    val count = quickFilterSnapshot.customCatCounts[customItem.displayName] ?: 0
                                                    val isSelected = uiState.selectedCustomCategoryName == customItem.displayName
                                                    ExpenseInlineFilterChip(
                                                        text = "${customItem.displayName} ($count)",
                                                        isSelected = isSelected,
                                                        onClick = {
                                                            viewModel.setCustomCategoryFilter(
                                                                if (isSelected) null else customItem.displayName
                                                            )
                                                        },
                                                        selectedContainerColor = Color(0xFF0D9488),
                                                        selectedBorderColor = Color(0xFF0F766E)
                                                    )
                                                }

                                                // 3) '기타(ETC)' 항목은 항상 제일 마지막(하단/우측 끝)에 표시
                                                if (ExpenseCategory.ETC in builtInCatsToShow) {
                                                    val isEtcSelected = uiState.selectedCategory == ExpenseCategory.ETC &&
                                                        uiState.selectedCustomCategoryName == null &&
                                                        !uiState.isRecurringFilterOnly
                                                    val etcCount = quickFilterSnapshot.builtInCatCounts[ExpenseCategory.ETC] ?: 0
                                                    val displayEtc = viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(ExpenseCategory.ETC)
                                                    ExpenseInlineFilterChip(
                                                        text = "$displayEtc ($etcCount)",
                                                        isSelected = isEtcSelected,
                                                        onClick = { viewModel.toggleCategoryFilter(ExpenseCategory.ETC) }
                                                    )
                                                }
                                            }
                                        }

                                        // [2단] 사용자별 조회 필터 칩 바
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Box(
                                                modifier = Modifier.width(52.dp),
                                                contentAlignment = Alignment.CenterStart
                                            ) {
                                                Text(
                                                    text = "사용자별",
                                                    fontSize = 11.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Slate600
                                                )
                                            }
                                            Row(
                                                modifier = Modifier
                                                    .weight(1f)
                                                    .horizontalScroll(rememberScrollState()),
                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                val isAllUsers = uiState.selectedUserClassification == null
                                                ExpenseInlineFilterChip(
                                                    text = "전체 (${uiState.transactions.size})",
                                                    isSelected = isAllUsers,
                                                    onClick = { viewModel.toggleUserClassificationFilter(null) },
                                                    selectedContainerColor = Slate800,
                                                    selectedBorderColor = Slate900
                                                )

                                                uiState.userClassifications.forEach { uc ->
                                                    val isSel = uiState.selectedUserClassification == uc.name
                                                    val (fg, _) = getUserClassificationBadgeColors(uc.colorIndex)
                                                    val count = quickFilterSnapshot.userCounts[uc.name] ?: 0
                                                    ExpenseInlineFilterChip(
                                                        text = "${uc.name} ($count)",
                                                        isSelected = isSel,
                                                        onClick = { viewModel.toggleUserClassificationFilter(uc.name) },
                                                        dotColor = fg,
                                                        selectedContainerColor = fg,
                                                        selectedBorderColor = fg
                                                    )
                                                }

                                                val isUnassignedSel = uiState.selectedUserClassification == UNASSIGNED_USER_FILTER
                                                ExpenseInlineFilterChip(
                                                    text = "미지정 (${quickFilterSnapshot.unassignedCount})",
                                                    isSelected = isUnassignedSel,
                                                    onClick = { viewModel.toggleUserClassificationFilter(UNASSIGNED_USER_FILTER) },
                                                    selectedContainerColor = Slate700,
                                                    selectedBorderColor = Slate800
                                                )

                                                val isCorpSelected = uiState.selectedCorporateFilter == true
                                                ExpenseInlineFilterChip(
                                                    text = "법인카드 (${quickFilterSnapshot.corporateCount})",
                                                    isSelected = isCorpSelected,
                                                    onClick = { viewModel.setCorporateFilter(true) },
                                                    dotColor = Color(0xFFD97706),
                                                    selectedContainerColor = Color(0xFFB45309),
                                                    selectedBorderColor = Color(0xFF92400E)
                                                )
                                            }
                                        }
                                    }
                                }

                                HairlineDivider()
                            }
                        }

                        // 결제 내역 아이템 리스트
                        if (filteredTransactions.isEmpty()) {
                            item(key = "tx_empty_state") {
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
                            items(
                                items = filteredTransactions,
                                key = { it.id }
                            ) { tx ->
                                val precomputed = rowMetaByTxId[tx.id]
                                val isRecurring = tx.id in uiState.recurringTransactionIds
                                val userClass = uiState.userClassByTxId[tx.id]
                                val isCorporate = uiState.corporateByTxId[tx.id] == true
                                val isOneTimeEdited = tx.id in uiState.oneTimeEditedIds
                                val isSelectedForDelete = tx.id in uiState.selectedTransactionIds

                                SaaSTransactionRow(
                                    transaction = tx,
                                    effectiveCategoryName = precomputed?.effectiveCatName ?: tx.category.displayName,
                                    userClassification = userClass,
                                    isCorporate = isCorporate,
                                    isOneTimeEdited = isOneTimeEdited,
                                    matchingAccount = precomputed?.matchingAccount,
                                    isRecurring = isRecurring,
                                    isSelectionMode = uiState.isSelectionMode,
                                    isSelectedForDelete = isSelectedForDelete,
                                    formattedDate = precomputed?.formattedDate,
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
                    } else {
                        // -----------------------------------------------------------------
                        // [탭 2: 소비 통계 리포트 별도 페이지] - 원형(도넛) 그래프 + 접이식 심층 통계
                        // -----------------------------------------------------------------
                        item(key = "stat_donut_card") {
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
                                        val restAmt = rest.sumOf { it.amount }
                                        val restCnt = rest.sumOf { it.txCount }
                                        val restPct = rest.sumOf { it.percentage.toDouble() }.toFloat()
                                        top5 + DonutSliceData(
                                            key = "__OTHER_CAT__",
                                            label = "기타 (${rest.size}개)",
                                            amount = restAmt,
                                            txCount = restCnt,
                                            percentage = restPct,
                                            color = Slate400,
                                            isOtherBucket = true
                                        )
                                    }
                                } else {
                                    val nonZeroUsers = userStatItems.filter { it.amount > 0L }
                                    nonZeroUsers.mapIndexed { idx, uItem ->
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

                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.xl, vertical = Spacing.md),
                                verticalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                // 1. 원형(도넛) 그래프 카드
                                ExpenseDonutChartCard(
                                    dateTitle = uiState.dateDisplayTitle,
                                    totalExpense = uiState.totalExpense,
                                    totalIncome = uiState.totalIncome,
                                    dimension = donutDimension,
                                    onDimensionChange = {
                                        donutDimension = it
                                        selectedDonutSliceKey = null
                                    },
                                    slices = donutSlices,
                                    selectedSliceKey = selectedDonutSliceKey,
                                    onSelectSlice = { key ->
                                        selectedDonutSliceKey = if (selectedDonutSliceKey == key) null else key
                                    },
                                    onNavigateToSliceTransactions = { slice ->
                                        if (!slice.isOtherBucket) {
                                            if (donutDimension == ExpenseDonutDimension.BY_CATEGORY) {
                                                viewModel.toggleEffectiveCategoryByName(slice.key)
                                            } else {
                                                viewModel.toggleUserClassificationFilter(slice.key)
                                            }
                                        }
                                        activePageTab = ExpensePageTab.TRANSACTIONS
                                    }
                                )

                                // 2. 소비 구조 3대 핵심 요약 지표 (고정지출 비중 / 법인카드 비중 / 월 예산 달성률)
                                val totalExpSafe = uiState.totalExpense.coerceAtLeast(1L)
                                val recurringPct = (quickFilterSnapshot.recurringAmount.toFloat() / totalExpSafe * 100f).coerceIn(0f, 100f)
                                val corpPct = (quickFilterSnapshot.corporateAmount.toFloat() / totalExpSafe * 100f).coerceIn(0f, 100f)
                                val budgetPct = (uiState.totalExpense.toFloat() / uiState.monthlyBudget.coerceAtLeast(1L) * 100f)

                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                                ) {
                                    // 고정지출 비중 카드
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = PureWhite,
                                        border = BorderStroke(1.dp, Slate200),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                if (!uiState.isRecurringFilterOnly) viewModel.toggleRecurringFilter()
                                                activePageTab = ExpensePageTab.TRANSACTIONS
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("고정지출 비중", fontSize = 11.sp, color = Slate500, fontWeight = FontWeight.Medium)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = "%.1f%%".format(recurringPct),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFF4338CA)
                                            )
                                            Text(
                                                text = "%,d원 (${quickFilterSnapshot.recurringCount}건)".format(quickFilterSnapshot.recurringAmount),
                                                fontSize = 10.5.sp,
                                                color = Slate600,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // 법인카드 비중 카드
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = PureWhite,
                                        border = BorderStroke(1.dp, Slate200),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable {
                                                viewModel.setCorporateFilter(true)
                                                activePageTab = ExpensePageTab.TRANSACTIONS
                                            }
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("법인카드 비중", fontSize = 11.sp, color = Slate500, fontWeight = FontWeight.Medium)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = "%.1f%%".format(corpPct),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFB45309)
                                            )
                                            Text(
                                                text = "%,d원 (${quickFilterSnapshot.corporateCount}건)".format(quickFilterSnapshot.corporateAmount),
                                                fontSize = 10.5.sp,
                                                color = Slate600,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }

                                    // 월 예산 대비 카드
                                    Surface(
                                        shape = RoundedCornerShape(12.dp),
                                        color = PureWhite,
                                        border = BorderStroke(1.dp, Slate200),
                                        modifier = Modifier
                                            .weight(1f)
                                            .clickable { viewModel.openBudgetDialog() }
                                    ) {
                                        Column(modifier = Modifier.padding(10.dp)) {
                                            Text("월 예산 소진율", fontSize = 11.sp, color = Slate500, fontWeight = FontWeight.Medium)
                                            Spacer(modifier = Modifier.height(3.dp))
                                            Text(
                                                text = "%.1f%%".format(budgetPct),
                                                fontSize = 15.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (budgetPct >= 100f) Color(0xFFDC2626) else Color(0xFF059669)
                                            )
                                            val diff = uiState.monthlyBudget - uiState.totalExpense
                                            Text(
                                                text = if (diff >= 0) "잔여 %,d원".format(diff) else "초과 %,d원".format(-diff),
                                                fontSize = 10.5.sp,
                                                color = Slate600,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 3. 접었다 폈다 하는 [카테고리별 지출 상세 순위] 섹션 카드
                        item(key = "stat_cat_accordion") {
                            val totalCatCount = categoryStatItems.size.coerceAtLeast(1)
                            val displayedCats = if (showAllCategoryRanks) categoryStatItems else categoryStatItems.take(5)

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, Slate200),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.xl, vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // 아코디언 헤더
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { isCatRankSectionExpanded = !isCatRankSectionExpanded }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "카테고리별 소비 순위 및 비율",
                                                style = AppTypography.body.copy(fontWeight = FontWeight.Bold, color = Slate900, fontSize = 13.5.sp)
                                            )
                                            MetricBadge(
                                                text = "${categoryStatItems.size}개",
                                                textColor = AppColors.primary,
                                                backgroundColor = Color(0xFFEFF6FF)
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "카테고리 관리",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Slate500,
                                                modifier = Modifier.clickable { viewModel.openCategoryManageDialog() }
                                            )
                                            Icon(
                                                imageVector = if (isCatRankSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = "접기/펼치기",
                                                tint = Slate600,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    AnimatedVisibility(
                                        visible = isCatRankSectionExpanded,
                                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180)),
                                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(140))
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            HairlineDivider()
                                            if (categoryStatItems.isEmpty()) {
                                                Text(
                                                    text = "조건에 해당하는 카테고리 지출 내역이 없습니다.",
                                                    fontSize = 12.sp,
                                                    color = Slate500,
                                                    modifier = Modifier.padding(16.dp)
                                                )
                                            } else {
                                                displayedCats.forEachIndexed { index, item ->
                                                    val barColor = getRainbowUsageColor(index, totalCatCount)
                                                    val isHighlighted = selectedDonutSliceKey == item.displayName &&
                                                        donutDimension == ExpenseDonutDimension.BY_CATEGORY

                                                    Column(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .background(if (isHighlighted) Color(0xFFEFF6FF) else Color.Transparent)
                                                            .clickable {
                                                                donutDimension = ExpenseDonutDimension.BY_CATEGORY
                                                                selectedDonutSliceKey = item.displayName
                                                            }
                                                            .padding(horizontal = 14.dp, vertical = 10.dp)
                                                    ) {
                                                        Row(
                                                            modifier = Modifier.fillMaxWidth(),
                                                            horizontalArrangement = Arrangement.SpaceBetween,
                                                            verticalAlignment = Alignment.CenterVertically
                                                        ) {
                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                modifier = Modifier.weight(1f)
                                                            ) {
                                                                Box(
                                                                    modifier = Modifier
                                                                        .size(18.dp)
                                                                        .clip(CircleShape)
                                                                        .background(barColor.copy(alpha = 0.15f)),
                                                                    contentAlignment = Alignment.Center
                                                                ) {
                                                                    Text(
                                                                        text = "${index + 1}",
                                                                        fontSize = 10.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = barColor
                                                                    )
                                                                }
                                                                Spacer(modifier = Modifier.width(8.dp))
                                                                Text(
                                                                    text = item.displayName,
                                                                    style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold),
                                                                    color = Slate900
                                                                )
                                                                Spacer(modifier = Modifier.width(6.dp))
                                                                Text(
                                                                    text = "%.1f%% (%d건)".format(item.percentage, item.txCount),
                                                                    fontSize = 11.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = barColor
                                                                )
                                                            }

                                                            Row(
                                                                verticalAlignment = Alignment.CenterVertically,
                                                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                            ) {
                                                                Text(
                                                                    text = "%,d원".format(item.amount),
                                                                    style = AppTypography.h3.copy(fontWeight = FontWeight.Bold),
                                                                    color = Slate900
                                                                )
                                                                Box(
                                                                    modifier = Modifier
                                                                        .clip(RoundedCornerShape(6.dp))
                                                                        .background(Slate100)
                                                                        .clickable {
                                                                            viewModel.toggleEffectiveCategoryByName(item.displayName)
                                                                            activePageTab = ExpensePageTab.TRANSACTIONS
                                                                        }
                                                                        .padding(horizontal = 7.dp, vertical = 3.dp)
                                                                ) {
                                                                    Text(
                                                                        text = "내역 >",
                                                                        fontSize = 10.5.sp,
                                                                        fontWeight = FontWeight.Bold,
                                                                        color = Slate700
                                                                    )
                                                                }
                                                            }
                                                        }

                                                        Spacer(modifier = Modifier.height(5.dp))
                                                        val proportion = (item.percentage / 100f).coerceIn(0f, 1f)
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth()
                                                                .height(4.dp)
                                                                .clip(RoundedCornerShape(2.dp))
                                                                .background(Slate100)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .fillMaxWidth(proportion)
                                                                    .fillMaxHeight()
                                                                    .background(barColor)
                                                            )
                                                        }
                                                        if (item.topUsersSummary.isNotBlank()) {
                                                            Spacer(modifier = Modifier.height(3.dp))
                                                            Text(
                                                                text = "사용자 비중: ${item.topUsersSummary}",
                                                                fontSize = 10.5.sp,
                                                                color = Slate500
                                                            )
                                                        }
                                                    }
                                                    if (index < displayedCats.lastIndex) {
                                                        HairlineDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                                    }
                                                }

                                                // 5개 초과 시 접었다 폈다 하는 더보기 버튼
                                                if (categoryStatItems.size > 5) {
                                                    HairlineDivider()
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .clickable { showAllCategoryRanks = !showAllCategoryRanks }
                                                            .padding(vertical = 10.dp),
                                                        contentAlignment = Alignment.Center
                                                    ) {
                                                        Text(
                                                            text = if (showAllCategoryRanks) {
                                                                "상위 5개 카테고리만 간단히 보기 ▴"
                                                            } else {
                                                                "나머지 ${categoryStatItems.size - 5}개 카테고리 더 펼쳐보기 (총 ${categoryStatItems.size}개) ▾"
                                                            },
                                                            fontSize = 11.5.sp,
                                                            fontWeight = FontWeight.Bold,
                                                            color = AppColors.primary
                                                        )
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // 4. 접었다 폈다 하는 [사용자별 지출 상세 순위] 섹션 카드
                        item(key = "stat_user_accordion") {
                            val displayedUsers = if (showAllUserRanks) userStatItems else userStatItems.take(5)
                            val userTotalAmount = userStatItems.sumOf { it.amount }

                            Surface(
                                shape = RoundedCornerShape(14.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, Slate200),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = Spacing.xl, vertical = 6.dp)
                            ) {
                                Column(modifier = Modifier.fillMaxWidth()) {
                                    // 아코디언 헤더
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .clickable { isUserRankSectionExpanded = !isUserRankSectionExpanded }
                                            .padding(horizontal = 14.dp, vertical = 12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                                        ) {
                                            Text(
                                                text = "사용자별 소비 순위 및 비율",
                                                style = AppTypography.body.copy(fontWeight = FontWeight.Bold, color = Slate900, fontSize = 13.5.sp)
                                            )
                                            MetricBadge(
                                                text = "${userStatItems.count { it.amount > 0L || !it.isUnassigned }}명",
                                                textColor = Color(0xFF6D28D9),
                                                backgroundColor = Color(0xFFF5F3FF)
                                            )
                                        }
                                        Row(
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                        ) {
                                            Text(
                                                text = "사용자 관리",
                                                fontSize = 11.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Slate500,
                                                modifier = Modifier.clickable { viewModel.openUserClassificationManageDialog() }
                                            )
                                            Icon(
                                                imageVector = if (isUserRankSectionExpanded) Icons.Default.ExpandLess else Icons.Default.ExpandMore,
                                                contentDescription = "접기/펼치기",
                                                tint = Slate600,
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }

                                    AnimatedVisibility(
                                        visible = isUserRankSectionExpanded,
                                        enter = expandVertically(animationSpec = tween(200)) + fadeIn(animationSpec = tween(180)),
                                        exit = shrinkVertically(animationSpec = tween(180)) + fadeOut(animationSpec = tween(140))
                                    ) {
                                        Column(modifier = Modifier.fillMaxWidth()) {
                                            HairlineDivider()

                                            // 사용자별 누적 비중 스택 바
                                            val nonZeroUserStats = userStatItems.filter { it.amount > 0L }
                                            if (nonZeroUserStats.size > 1 && userTotalAmount > 0L) {
                                                Row(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .padding(horizontal = 14.dp, vertical = 8.dp)
                                                        .height(6.dp)
                                                        .clip(RoundedCornerShape(3.dp))
                                                        .background(Slate100)
                                                ) {
                                                    nonZeroUserStats.forEachIndexed { idx, uItem ->
                                                        val (uFg, _) = if (uItem.isUnassigned) {
                                                            Slate400 to Slate100
                                                        } else {
                                                            getUserClassificationBadgeColors(uItem.colorIndex ?: idx)
                                                        }
                                                        val weightVal = (uItem.amount.toFloat() / userTotalAmount.toFloat()).coerceAtLeast(0.02f)
                                                        Box(
                                                            modifier = Modifier
                                                                .weight(weightVal)
                                                                .fillMaxHeight()
                                                                .background(uFg)
                                                        )
                                                    }
                                                }
                                            }

                                            displayedUsers.forEachIndexed { index, uItem ->
                                                val (fgColor, bgColor) = if (uItem.isUnassigned) {
                                                    Slate600 to Slate100
                                                } else {
                                                    getUserClassificationBadgeColors(uItem.colorIndex ?: index)
                                                }
                                                val isHighlighted = selectedDonutSliceKey == uItem.userKey &&
                                                    donutDimension == ExpenseDonutDimension.BY_USER

                                                Column(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .background(if (isHighlighted) bgColor.copy(alpha = 0.45f) else Color.Transparent)
                                                        .clickable {
                                                            donutDimension = ExpenseDonutDimension.BY_USER
                                                            selectedDonutSliceKey = uItem.userKey
                                                        }
                                                        .padding(horizontal = 14.dp, vertical = 10.dp)
                                                ) {
                                                    Row(
                                                        modifier = Modifier.fillMaxWidth(),
                                                        horizontalArrangement = Arrangement.SpaceBetween,
                                                        verticalAlignment = Alignment.CenterVertically
                                                    ) {
                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                            modifier = Modifier.weight(1f)
                                                        ) {
                                                            Box(
                                                                modifier = Modifier
                                                                    .size(8.dp)
                                                                    .background(fgColor, CircleShape)
                                                            )
                                                            MetricBadge(
                                                                text = uItem.displayName,
                                                                textColor = fgColor,
                                                                backgroundColor = bgColor
                                                            )
                                                            Text(
                                                                text = "%.1f%% (%d건)".format(uItem.percentage, uItem.txCount),
                                                                fontSize = 11.5.sp,
                                                                fontWeight = FontWeight.Bold,
                                                                color = fgColor
                                                            )
                                                            if (uItem.corporateTxCount > 0) {
                                                                MetricBadge(
                                                                    text = "법인 ${uItem.corporateTxCount}건",
                                                                    textColor = Color(0xFF0369A1),
                                                                    backgroundColor = Color(0xFFE0F2FE)
                                                                )
                                                            }
                                                        }

                                                        Row(
                                                            verticalAlignment = Alignment.CenterVertically,
                                                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                                                        ) {
                                                            Text(
                                                                text = "%,d원".format(uItem.amount),
                                                                style = AppTypography.h3.copy(fontWeight = FontWeight.Bold),
                                                                color = Slate900
                                                            )
                                                            Box(
                                                                modifier = Modifier
                                                                    .clip(RoundedCornerShape(6.dp))
                                                                    .background(Slate100)
                                                                    .clickable {
                                                                        viewModel.toggleUserClassificationFilter(uItem.userKey)
                                                                        activePageTab = ExpensePageTab.TRANSACTIONS
                                                                    }
                                                                    .padding(horizontal = 7.dp, vertical = 3.dp)
                                                            ) {
                                                                Text(
                                                                    text = "내역 >",
                                                                    fontSize = 10.5.sp,
                                                                    fontWeight = FontWeight.Bold,
                                                                    color = Slate700
                                                                )
                                                            }
                                                        }
                                                    }

                                                    Spacer(modifier = Modifier.height(5.dp))
                                                    val proportion = (uItem.percentage / 100f).coerceIn(0f, 1f)
                                                    Box(
                                                        modifier = Modifier
                                                            .fillMaxWidth()
                                                            .height(4.dp)
                                                            .clip(RoundedCornerShape(2.dp))
                                                            .background(Slate100)
                                                    ) {
                                                        Box(
                                                            modifier = Modifier
                                                                .fillMaxWidth(proportion)
                                                                .fillMaxHeight()
                                                                .background(fgColor)
                                                        )
                                                    }
                                                    if (uItem.topCategoriesSummary.isNotBlank()) {
                                                        Spacer(modifier = Modifier.height(3.dp))
                                                        Text(
                                                            text = "주요 카테고리: ${uItem.topCategoriesSummary}",
                                                            fontSize = 10.5.sp,
                                                            color = Slate500
                                                        )
                                                    }
                                                }
                                                if (index < displayedUsers.lastIndex) {
                                                    HairlineDivider(modifier = Modifier.padding(horizontal = 14.dp))
                                                }
                                            }

                                            if (userStatItems.size > 5) {
                                                HairlineDivider()
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxWidth()
                                                        .clickable { showAllUserRanks = !showAllUserRanks }
                                                        .padding(vertical = 10.dp),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Text(
                                                        text = if (showAllUserRanks) {
                                                            "상위 5명만 간단히 보기 ▴"
                                                        } else {
                                                            "나머지 ${userStatItems.size - 5}명 더 펼쳐보기 (총 ${userStatItems.size}명) ▾"
                                                        },
                                                        fontSize = 11.5.sp,
                                                        fontWeight = FontWeight.Bold,
                                                        color = AppColors.primary
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

            com.autologue.app.presentation.common.AutoLogueLoadingOverlay(
                isLoading = uiState.isLoading || uiState.isSaving,
                title = if (uiState.isSaving) "변경 사항 저장 중" else uiState.loadingTitle,
                message = if (uiState.isSaving) "선택하신 영구저장 항목과 내역을 반영하고 있습니다..." else uiState.loadingMessage,
                accentColor = MenuColors.expense,
                delayMillis = 500L,
                modifier = Modifier.fillMaxSize()
            )
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
        val resolvedBaseCardOrBank = remember(editingTx.id) {
            viewModel.expenseCustomMetaPreferences.extractBaseCardName(
                viewModel.expenseCustomMetaPreferences.resolveDisplayCardOrBankName(editingTx)
            )
        }
        val activePermanentRule = remember(editingTx.id, uiState.permanentMerchantRules) {
            viewModel.findPermanentRuleForTransaction(editingTx)
        }
        val editCandidatePair by produceState(
            initialValue = Pair(emptyList<Transaction>(), emptySet<Long>()),
            key1 = editingTx.id
        ) {
            value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.Default) {
                val isDep = editingTx.isDepositTransaction()
                val candidates = uiState.allTransactions
                    .filter { it.id != editingTx.id && it.isDepositTransaction() == isDep }
                    .sortedByDescending { it.timestamp }
                val matchedIds = viewModel.findMatchingExistingTransactions(editingTx).map { it.id }.toSet()
                Pair(candidates, matchedIds)
            }
        }
        val sameDirectionExistingTxs = editCandidatePair.first
        val autoMatchedExistingIds = editCandidatePair.second
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
            activePermanentRule = activePermanentRule,
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
            onOpenPermanentRulesManage = { viewModel.openPermanentRulesDialog() },
            onRemovePermanentRule = { rule ->
                viewModel.removePermanentMerchantRule(rule)
                viewModel.closeEditDialog()
            },
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

    // Permanent Rules Manage Dialog (7. 영구저장 데이터 통합 조회 및 해제/원본 복원 관리)
    if (uiState.isPermanentRulesDialogOpen) {
        PermanentRulesManageDialog(
            merchantRules = uiState.permanentMerchantRules,
            cardCorporateRules = uiState.cardCorporateRules,
            dbRules = uiState.rules,
            getBuiltInCategoryName = { cat ->
                viewModel.expenseCustomMetaPreferences.getBuiltInCategoryDisplayName(cat)
            },
            onDismiss = { viewModel.closePermanentRulesDialog() },
            onRemoveMerchantRule = { rule -> viewModel.removePermanentMerchantRule(rule) },
            onRemoveMerchantAttribute = { rule, attr -> viewModel.removePermanentMerchantAttribute(rule, attr) },
            onRemoveCardCorporateRule = { cardKey -> viewModel.removeCardCorporateRule(cardKey) },
            onDeleteDbRule = { ruleId, keyword -> viewModel.deleteTransactionRule(ruleId, keyword) },
            onClearAllRules = { viewModel.clearAllPermanentRules() }
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

/**
 * 2026.10 트렌드 소비 비율 원형(도넛) 그래프 & 인터랙티브 범례 카드
 * - 전체 소비 중 카테고리별 / 사용자별 비율(%)을 시각적 도넛 차트와 수치로 동시에 표시
 * - 항목 터치 시 중앙 오버레이에 선택된 항목의 비율·금액·건수를 강조 표시하고 [해당 내역 바로 보기] 연동 지원
 */
@Composable
private fun ExpenseDonutChartCard(
    dateTitle: String,
    totalExpense: Long,
    totalIncome: Long,
    dimension: ExpenseDonutDimension,
    onDimensionChange: (ExpenseDonutDimension) -> Unit,
    slices: List<DonutSliceData>,
    selectedSliceKey: String?,
    onSelectSlice: (String) -> Unit,
    onNavigateToSliceTransactions: (DonutSliceData) -> Unit
) {
    val animProgress by animateFloatAsState(
        targetValue = 1f,
        animationSpec = tween(durationMillis = 550, easing = FastOutSlowInEasing),
        label = "donutSweep"
    )
    val selectedSlice = remember(slices, selectedSliceKey) {
        slices.firstOrNull { it.key == selectedSliceKey }
    }

    Surface(
        shape = RoundedCornerShape(16.dp),
        color = PureWhite,
        border = BorderStroke(1.dp, Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 1. 카드 상단 헤더: 기간 총 지출 요약 + [카테고리별 비중 | 사용자별 비중] 전환 스위치
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "$dateTitle 전체 소비 비율",
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = Slate600
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(
                        verticalAlignment = Alignment.Bottom,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "%,d원".format(totalExpense),
                            style = AppTypography.h1.copy(fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Slate900)
                        )
                        if (totalIncome > 0L) {
                            Text(
                                text = "(수입 +%,d원)".format(totalIncome),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = Emerald600,
                                modifier = Modifier.padding(bottom = 2.dp)
                            )
                        }
                    }
                }

                // 분석 기준 전환 (카테고리별 vs 사용자별)
                Row(
                    modifier = Modifier
                        .height(32.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Slate100)
                        .border(1.dp, Slate200, RoundedCornerShape(8.dp))
                        .padding(2.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val isCat = dimension == ExpenseDonutDimension.BY_CATEGORY
                    val isUser = dimension == ExpenseDonutDimension.BY_USER

                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isCat) PureWhite else Color.Transparent)
                            .clickable { onDimensionChange(ExpenseDonutDimension.BY_CATEGORY) }
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "카테고리별",
                            fontSize = 11.sp,
                            fontWeight = if (isCat) FontWeight.Bold else FontWeight.Medium,
                            color = if (isCat) Slate900 else Slate600
                        )
                    }
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .clip(RoundedCornerShape(6.dp))
                            .background(if (isUser) PureWhite else Color.Transparent)
                            .clickable { onDimensionChange(ExpenseDonutDimension.BY_USER) }
                            .padding(horizontal = 9.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "사용자별",
                            fontSize = 11.sp,
                            fontWeight = if (isUser) FontWeight.Bold else FontWeight.Medium,
                            color = if (isUser) Slate900 else Slate600
                        )
                    }
                }
            }

            if (slices.isEmpty() || totalExpense <= 0L) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 28.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "해당 기간에 집계된 소비 내역이 없습니다.",
                        fontSize = 12.5.sp,
                        color = Slate500
                    )
                }
            } else {
                // 2. 원형(도넛) 차트 + 우측 비율 범례 (2열 구조)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 좌측: 도넛 그래프 Canvas + 중앙 비율/금액 오버레이
                    Box(
                        modifier = Modifier.size(154.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val strokeBase = 24.dp.toPx()
                            val strokeSelected = 30.dp.toPx()
                            val pad = strokeSelected / 2f + 4.dp.toPx()
                            val arcDiameter = (size.minDimension - pad * 2f).coerceAtLeast(10f)
                            val topLeft = Offset((size.width - arcDiameter) / 2f, (size.height - arcDiameter) / 2f)
                            val arcSize = Size(arcDiameter, arcDiameter)

                            // 배경 트랙 원
                            drawArc(
                                color = Color(0xFFF1F5F9),
                                startAngle = 0f,
                                sweepAngle = 360f,
                                useCenter = false,
                                topLeft = topLeft,
                                size = arcSize,
                                style = Stroke(width = strokeBase)
                            )

                            val gapAngle = if (slices.size > 1) 2.2f else 0f
                            var currentStartAngle = -90f
                            val totalPct = slices.sumOf { it.percentage.toDouble() }.toFloat().coerceAtLeast(1f)

                            slices.forEach { slice ->
                                val rawSweep = (slice.percentage / totalPct) * 360f * animProgress
                                val sweep = (rawSweep - gapAngle).coerceAtLeast(1.2f)
                                val isSel = selectedSliceKey == slice.key
                                val alpha = if (selectedSliceKey == null || isSel) 1f else 0.35f

                                drawArc(
                                    color = slice.color.copy(alpha = alpha),
                                    startAngle = currentStartAngle + (gapAngle / 2f),
                                    sweepAngle = sweep,
                                    useCenter = false,
                                    topLeft = topLeft,
                                    size = arcSize,
                                    style = Stroke(
                                        width = if (isSel) strokeSelected else strokeBase,
                                        cap = StrokeCap.Butt
                                    )
                                )
                                currentStartAngle += rawSweep
                            }
                        }

                        // 도넛 중앙 텍스트 영역
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier
                                .widthIn(max = 96.dp)
                                .clickable {
                                    if (selectedSlice != null) {
                                        onSelectSlice(selectedSlice.key)
                                    }
                                }
                        ) {
                            if (selectedSlice != null) {
                                Text(
                                    text = selectedSlice.label,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate700,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "%.1f%%".format(selectedSlice.percentage),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = selectedSlice.color
                                )
                                Text(
                                    text = "%,d원".format(selectedSlice.amount),
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = Slate500,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            } else {
                                val topSlice = slices.first()
                                Text(
                                    text = "최다: ${topSlice.label}",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate600,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "%.1f%%".format(topSlice.percentage),
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = topSlice.color
                                )
                                Text(
                                    text = "항목 터치 시 상세",
                                    fontSize = 9.5.sp,
                                    color = Slate400
                                )
                            }
                        }
                    }

                    // 우측: 비율 범례 리스트 (터치하여 원형 조각 강조 가능)
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(5.dp)
                    ) {
                        slices.forEach { slice ->
                            val isSel = selectedSliceKey == slice.key
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) Slate100 else Color.Transparent)
                                    .clickable { onSelectSlice(slice.key) }
                                    .padding(horizontal = 6.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .background(slice.color, CircleShape)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = slice.label,
                                        fontSize = 11.5.sp,
                                        fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                        color = Slate800,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "%.1f%%".format(slice.percentage),
                                    fontSize = 11.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isSel) slice.color else Slate700
                                )
                            }
                        }
                    }
                }

                // 3. 선택된 원형 그래프 조각이 있을 때 하단 상세 정보 & 내역 바로가기 바
                AnimatedVisibility(
                    visible = selectedSlice != null,
                    enter = expandVertically(animationSpec = tween(180)) + fadeIn(animationSpec = tween(150)),
                    exit = shrinkVertically(animationSpec = tween(150)) + fadeOut(animationSpec = tween(120))
                ) {
                    if (selectedSlice != null) {
                        Surface(
                            shape = RoundedCornerShape(10.dp),
                            color = Slate50,
                            border = BorderStroke(1.dp, Slate200),
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
                                        text = "${selectedSlice.label} · 전체 소비의 %.1f%%".format(selectedSlice.percentage),
                                        fontSize = 11.5.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Slate900
                                    )
                                    Text(
                                        text = "합계 %,d원 (총 %d건)".format(selectedSlice.amount, selectedSlice.txCount),
                                        fontSize = 11.sp,
                                        color = Slate600
                                    )
                                }
                                if (!selectedSlice.isOtherBucket) {
                                    Surface(
                                        onClick = { onNavigateToSliceTransactions(selectedSlice) },
                                        shape = RoundedCornerShape(7.dp),
                                        color = AppColors.primary
                                    ) {
                                        Text(
                                            text = "결제 내역 보기 >",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = PureWhite,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
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
    formattedDate: String? = null,
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
                    text = formattedDate ?: transaction.timestamp.format(ROW_DATE_FORMATTER),
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
 * 조회조건 필터 설정 팝업 다이얼로그 (가로폭 100% 정렬 그리드 + 균일 높이 칩 + 깔끔한 엔터프라이즈 라이트 테마)
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

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp)
                .heightIn(max = 680.dp),
            shape = RoundedCornerShape(16.dp),
            color = PureWhite,
            shadowElevation = 8.dp,
            border = BorderStroke(1.dp, Slate200)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
            ) {
                // 상단 헤더: 타이틀 + 조건 초기화 버튼
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "조회조건 필터 설정",
                        style = AppTypography.h2.copy(fontWeight = FontWeight.Bold, color = Slate900)
                    )
                    Box(
                        modifier = Modifier
                            .height(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(0xFFFEF2F2))
                            .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(6.dp))
                            .clickable { onClearAllFilters() }
                            .padding(horizontal = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "조건 초기화",
                            fontSize = 11.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFFDC2626)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HairlineDivider()

                // 중앙 스크롤 영역 (3열/4열 균등 그리드 정렬)
                Column(
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(vertical = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp)
                ) {
                    // 1. 사용자별 조회 필터 섹션 (3열 균등 그리드)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "1. 사용자별 조회",
                                style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800, fontSize = 12.5.sp)
                            )
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFF5F3FF))
                                    .border(1.dp, Color(0xFFDDD6FE), RoundedCornerShape(6.dp))
                                    .clickable { onOpenUserManage() }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "사용자 분류 관리 →",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF6D28D9)
                                )
                            }
                        }

                        val userGridOptions = buildList {
                            val isAllUsers = selectedUserClassification == null
                            add(
                                ExpenseGridOptionItem(
                                    label = "전체 사용자",
                                    isSelected = isAllUsers,
                                    selectedContainerColor = Slate800,
                                    selectedBorderColor = Slate900,
                                    onClick = { onSelectUserClassification(null) }
                                )
                            )
                            userClassifications.forEach { uc ->
                                val isSel = selectedUserClassification == uc.name
                                val (fg, _) = getUserClassificationBadgeColors(uc.colorIndex)
                                add(
                                    ExpenseGridOptionItem(
                                        label = uc.name,
                                        isSelected = isSel,
                                        dotColor = fg,
                                        selectedContainerColor = fg,
                                        selectedBorderColor = fg,
                                        onClick = { onSelectUserClassification(if (isSel) null else uc.name) }
                                    )
                                )
                            }
                            val isUnassignedSel = selectedUserClassification == UNASSIGNED_USER_FILTER
                            add(
                                ExpenseGridOptionItem(
                                    label = "미지정",
                                    isSelected = isUnassignedSel,
                                    selectedContainerColor = Slate700,
                                    selectedBorderColor = Slate800,
                                    onClick = { onSelectUserClassification(if (isUnassignedSel) null else UNASSIGNED_USER_FILTER) }
                                )
                            )
                        }
                        ExpenseUniformFilterGrid(
                            options = userGridOptions,
                            columns = 3
                        )
                    }

                    HairlineDivider()

                    // 2. 카테고리별 조회 필터 섹션 (3열 균등 그리드)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "2. 카테고리별 조회",
                                style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800, fontSize = 12.5.sp)
                            )
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Color(0xFFF0FDF4))
                                    .border(1.dp, Color(0xFFBBF7D0), RoundedCornerShape(6.dp))
                                    .clickable { onOpenCategoryManage() }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "카테고리 관리 →",
                                    fontSize = 10.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF15803D)
                                )
                            }
                        }

                        val categoryGridOptions = buildList {
                            val isAllCat = selectedCategory == null &&
                                selectedCustomCategoryName == null &&
                                !isRecurringFilterOnly
                            add(
                                ExpenseGridOptionItem(
                                    label = "전체 카테고리",
                                    isSelected = isAllCat,
                                    onClick = { onSelectCategory(null) }
                                )
                            )
                            add(
                                ExpenseGridOptionItem(
                                    label = "정기지출만",
                                    isSelected = isRecurringFilterOnly,
                                    selectedContainerColor = Color(0xFF4338CA),
                                    selectedBorderColor = Color(0xFF3730A3),
                                    unselectedContainerColor = Color(0xFFEEF2FF),
                                    unselectedBorderColor = Color(0xFFC7D2FE),
                                    unselectedContentColor = Color(0xFF4338CA),
                                    onClick = onToggleRecurring
                                )
                            )
                            managedCategories.withEtcAlwaysAtBottom().forEach { item ->
                                if (item.isBuiltIn) {
                                    val isSel = selectedCategory == item.baseCategory &&
                                        selectedCustomCategoryName == null &&
                                        !isRecurringFilterOnly
                                    add(
                                        ExpenseGridOptionItem(
                                            label = item.displayName,
                                            isSelected = isSel,
                                            onClick = { onSelectCategory(if (isSel) null else item.baseCategory) }
                                        )
                                    )
                                } else {
                                    val isSel = selectedCustomCategoryName == item.displayName
                                    add(
                                        ExpenseGridOptionItem(
                                            label = item.displayName,
                                            isSelected = isSel,
                                            selectedContainerColor = Color(0xFF0D9488),
                                            selectedBorderColor = Color(0xFF0F766E),
                                            onClick = { onSelectCustomCategory(if (isSel) null else item.displayName) }
                                        )
                                    )
                                }
                            }
                        }
                        ExpenseUniformFilterGrid(
                            options = categoryGridOptions,
                            columns = 3
                        )
                    }

                    HairlineDivider()

                    // 3. 카드/결제 구분 조회 (3열 균등 그리드)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "3. 카드/결제 구분 조회",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800, fontSize = 12.5.sp)
                        )
                        val corpGridOptions = listOf(
                            null to "전체",
                            false to "개인결제만",
                            true to "법인카드만"
                        ).map { (value, label) ->
                            val isSel = selectedCorporateFilter == value
                            ExpenseGridOptionItem(
                                label = label,
                                isSelected = isSel,
                                selectedContainerColor = Color(0xFFB45309),
                                selectedBorderColor = Color(0xFF92400E),
                                onClick = { onSelectCorporateFilter(value) }
                            )
                        }
                        ExpenseUniformFilterGrid(
                            options = corpGridOptions,
                            columns = 3
                        )
                    }

                    HairlineDivider()

                    // 4. 조회 기간 설정 (4열 균등 프리셋 + 월별 네비게이터 바)
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "4. 조회 기간 선택",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800, fontSize = 12.5.sp)
                        )
                        val presetOptions = listOf("이번 달", "지난 달", "최근 3개월", "올해 전체").map { preset ->
                            ExpenseGridOptionItem(
                                label = preset,
                                isSelected = false,
                                unselectedContainerColor = Color(0xFFEFF6FF),
                                unselectedBorderColor = Color(0xFFBFDBFE),
                                unselectedContentColor = AppColors.primary,
                                onClick = { onSelectPreset(preset) }
                            )
                        }
                        ExpenseUniformFilterGrid(
                            options = presetOptions,
                            columns = 4,
                            itemHeight = 34.dp,
                            fontSize = 11.5.sp
                        )

                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(42.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(Slate50)
                                .border(1.dp, Slate200, RoundedCornerShape(10.dp))
                                .padding(horizontal = 6.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = { tempYearMonth = tempYearMonth.minusMonths(1) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ChevronLeft, contentDescription = "이전달", tint = Slate700)
                            }
                            Text(
                                text = "${tempYearMonth.year}년 ${tempYearMonth.monthValue}월",
                                style = AppTypography.h2.copy(fontWeight = FontWeight.Bold, color = Slate900)
                            )
                            IconButton(
                                onClick = { tempYearMonth = tempYearMonth.plusMonths(1) },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(Icons.Default.ChevronRight, contentDescription = "다음달", tint = Slate700)
                            }
                        }

                        if (tempYearMonth != currentYearMonth || currentMode != ExpenseDateFilterMode.MONTH) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(38.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(AppColors.primary)
                                    .clickable { onSelectMonth(tempYearMonth) },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "${tempYearMonth.year}년 ${tempYearMonth.monthValue}월로 기간 변경",
                                    color = PureWhite,
                                    fontSize = 12.5.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }

                HairlineDivider()
                Spacer(modifier = Modifier.height(12.dp))

                // 하단 버튼 영역 (가로폭 100% 2열 균등 배치)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Slate100)
                            .border(1.dp, Slate300, RoundedCornerShape(10.dp))
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "닫기",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate700
                        )
                    }
                    Box(
                        modifier = Modifier
                            .weight(1.3f)
                            .height(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(AppColors.primary)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "적용 완료",
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = PureWhite
                        )
                    }
                }
            }
        }
    }
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
                        val bankOptions = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_BANK_NAMES.map { bankPreset ->
                            val isBankSelected = cardOrBankName.trim() == bankPreset
                            ExpenseGridOptionItem(
                                label = bankPreset,
                                isSelected = isBankSelected,
                                selectedContainerColor = Color(0xFF059669),
                                selectedBorderColor = Color(0xFF047857),
                                onClick = { cardOrBankName = bankPreset }
                            )
                        }
                        ExpenseUniformFilterGrid(
                            options = bankOptions,
                            columns = 3,
                            itemHeight = 32.dp,
                            fontSize = 11.sp
                        )
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
                                ExpenseInlineFilterChip(
                                    text = "개인카드",
                                    isSelected = !isCorporate,
                                    onClick = { isCorporate = false },
                                    height = 26.dp,
                                    fontSize = 10.5.sp
                                )
                                ExpenseInlineFilterChip(
                                    text = "법인카드",
                                    isSelected = isCorporate,
                                    onClick = { isCorporate = true },
                                    selectedContainerColor = Color(0xFF0F172A),
                                    selectedBorderColor = Color(0xFF0F172A),
                                    height = 26.dp,
                                    fontSize = 10.5.sp
                                )
                            }
                        }

                        val cardOptions = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_CARD_COMPANIES.map { cardPreset ->
                            val isCardSelected = cardOrBankName.trim() == cardPreset
                            ExpenseGridOptionItem(
                                label = cardPreset,
                                isSelected = isCardSelected,
                                onClick = {
                                    cardOrBankName = cardPreset
                                    if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(userClassificationInput) ||
                                        com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(selectedCustomCategoryName)
                                    ) {
                                        isCorporate = true
                                    } else {
                                        onResolveCardCorporate(cardPreset)?.let { isCorporate = it }
                                    }
                                }
                            )
                        }
                        ExpenseUniformFilterGrid(
                            options = cardOptions,
                            columns = 3,
                            itemHeight = 32.dp,
                            fontSize = 11.sp
                        )

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
                    ExpenseUniformFilterGrid(
                        options = listOf(
                            ExpenseGridOptionItem(
                                label = "일반 지출",
                                isSelected = !isFixedExpense,
                                selectedContainerColor = Slate800,
                                selectedBorderColor = Slate900,
                                onClick = { isFixedExpense = false }
                            ),
                            ExpenseGridOptionItem(
                                label = "고정지출로 지정",
                                isSelected = isFixedExpense,
                                selectedContainerColor = Color(0xFF5B21B6),
                                selectedBorderColor = Color(0xFF4C1D95),
                                unselectedContainerColor = Color(0xFFF5F3FF),
                                unselectedBorderColor = Color(0xFFDDD6FE),
                                unselectedContentColor = Color(0xFF5B21B6),
                                onClick = { isFixedExpense = true }
                            )
                        ),
                        columns = 2,
                        itemHeight = 34.dp,
                        fontSize = 11.5.sp
                    )
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
                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Slate100)
                                .border(1.dp, Slate300, RoundedCornerShape(6.dp))
                                .clickable { onOpenCategoryManage() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "카테고리 수정/삭제",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate700
                            )
                        }
                    }
                    val catOptions = managedCategories.withEtcAlwaysAtBottom().map { item ->
                        val isSelected = if (item.isBuiltIn) {
                            selectedCustomCategoryName == null && selectedCategory == item.baseCategory
                        } else {
                            selectedCustomCategoryName == item.displayName
                        }
                        ExpenseGridOptionItem(
                            label = item.displayName,
                            isSelected = isSelected,
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
                            }
                        )
                    }
                    ExpenseUniformFilterGrid(
                        options = catOptions,
                        columns = 3,
                        itemHeight = 34.dp,
                        fontSize = 11.5.sp
                    )

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
                        Box(
                            modifier = Modifier
                                .height(24.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Slate100)
                                .border(1.dp, Slate300, RoundedCornerShape(6.dp))
                                .clickable { onOpenUserClassificationManage() }
                                .padding(horizontal = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "분류 수정/삭제/색상",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate700
                            )
                        }
                    }
                    val userAddOptions = buildList {
                        add(
                            ExpenseGridOptionItem(
                                label = "미지정",
                                isSelected = userClassificationInput.isBlank(),
                                selectedContainerColor = Slate800,
                                selectedBorderColor = Slate900,
                                onClick = { userClassificationInput = "" }
                            )
                        )
                        userClassifications.forEach { uc ->
                            val (fg, _) = getUserClassificationBadgeColors(uc.colorIndex)
                            val isSelected = userClassificationInput.trim() == uc.name
                            add(
                                ExpenseGridOptionItem(
                                    label = uc.name,
                                    isSelected = isSelected,
                                    dotColor = fg,
                                    selectedContainerColor = fg,
                                    selectedBorderColor = fg,
                                    onClick = {
                                        userClassificationInput = uc.name
                                        if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(uc.name)) {
                                            isCorporate = true
                                        }
                                    }
                                )
                            )
                        }
                    }
                    ExpenseUniformFilterGrid(
                        options = userAddOptions,
                        columns = 3,
                        itemHeight = 32.dp,
                        fontSize = 11.sp
                    )
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
    activePermanentRule: com.autologue.app.data.preferences.PermanentMerchantRuleSummary? = null,
    onResolveCardCorporate: (String) -> Boolean? = { null },
    onAddPermanentCategory: (String) -> Boolean,
    onUpdateCategoryName: (com.autologue.app.data.preferences.ManagedCategoryItem, String) -> Unit = { _, _ -> },
    onDeleteCategory: (com.autologue.app.data.preferences.ManagedCategoryItem) -> Unit = {},
    onOpenCategoryManage: () -> Unit = {},
    onOpenUserClassificationManage: () -> Unit = {},
    onOpenPermanentRulesManage: () -> Unit = {},
    onRemovePermanentRule: (com.autologue.app.data.preferences.PermanentMerchantRuleSummary) -> Unit = {},
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
                                text = "${transaction.timestamp.format(MODAL_DATE_FORMATTER)} · $cardCompany",
                                style = AppTypography.captionMuted
                            )
                        }
                    }

                    // 0. 현재 결제처에 적용 중인 영구저장 규칙 안내 및 즉시 해제 배너
                    if (activePermanentRule != null) {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Color(0xFFFFFBEB),
                            border = BorderStroke(1.dp, Color(0xFFFCD34D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(5.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = "현재 영구저장 규칙 적용 중",
                                        fontSize = 11.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Color(0xFF92400E)
                                    )
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        Surface(
                                            onClick = onOpenPermanentRulesManage,
                                            shape = RoundedCornerShape(5.dp),
                                            color = PureWhite,
                                            border = BorderStroke(1.dp, Color(0xFFFCD34D))
                                        ) {
                                            Text(
                                                text = "전체 목록 관리",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFB45309),
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                        Surface(
                                            onClick = { onRemovePermanentRule(activePermanentRule) },
                                            shape = RoundedCornerShape(5.dp),
                                            color = Color(0xFFFEF2F2),
                                            border = BorderStroke(1.dp, Color(0xFFFCA5A5))
                                        ) {
                                            Text(
                                                text = "영구저장 해제·복원",
                                                fontSize = 9.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFDC2626),
                                                modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                            )
                                        }
                                    }
                                }
                                val summaryText = buildList {
                                    if (activePermanentRule.sourceMerchantNames.isNotEmpty()) {
                                        add("치환: ${activePermanentRule.sourceMerchantNames.joinToString(", ")} ➔ ${activePermanentRule.merchantName}")
                                    }
                                    val catDisp = activePermanentRule.effectiveCategoryLabel
                                    if (!catDisp.isNullOrBlank()) add("카테고리: $catDisp")
                                    if (!activePermanentRule.userClassificationName.isNullOrBlank()) {
                                        add("분류: ${activePermanentRule.userClassificationName}")
                                    }
                                    if (activePermanentRule.isFixedExpense == true) add("고정지출")
                                }.joinToString(" · ")
                                if (summaryText.isNotEmpty()) {
                                    Text(
                                        text = summaryText,
                                        fontSize = 10.sp,
                                        color = Color(0xFF78350F),
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
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

                            val bankEditOptions = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_BANK_NAMES.map { bankPreset ->
                                val isBankSelected = cardCompany.trim() == bankPreset
                                ExpenseGridOptionItem(
                                    label = bankPreset,
                                    isSelected = isBankSelected,
                                    selectedContainerColor = Color(0xFF059669),
                                    selectedBorderColor = Color(0xFF047857),
                                    onClick = { cardCompany = bankPreset }
                                )
                            }
                            ExpenseUniformFilterGrid(
                                options = bankEditOptions,
                                columns = 3,
                                itemHeight = 32.dp,
                                fontSize = 11.sp
                            )

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
                                    ExpenseInlineFilterChip(
                                        text = "개인카드",
                                        isSelected = !isCorporate,
                                        onClick = { isCorporate = false },
                                        height = 26.dp,
                                        fontSize = 10.5.sp
                                    )
                                    ExpenseInlineFilterChip(
                                        text = "법인카드",
                                        isSelected = isCorporate,
                                        onClick = { isCorporate = true },
                                        selectedContainerColor = Color(0xFF0F172A),
                                        selectedBorderColor = Color(0xFF0F172A),
                                        height = 26.dp,
                                        fontSize = 10.5.sp
                                    )
                                }
                            }

                            val cardEditOptions = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.DEFAULT_CARD_COMPANIES.map { cardPreset ->
                                val isCardSelected = cardCompany.trim() == cardPreset
                                ExpenseGridOptionItem(
                                    label = cardPreset,
                                    isSelected = isCardSelected,
                                    onClick = {
                                        cardCompany = cardPreset
                                        val isCorpSelected = com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(userClassificationInput) ||
                                            com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(selectedCustomCategoryName)
                                        if (isCorpSelected) {
                                            isCorporate = true
                                        } else {
                                            onResolveCardCorporate(cardPreset)?.let { isCorporate = it }
                                        }
                                    }
                                )
                            }
                            ExpenseUniformFilterGrid(
                                options = cardEditOptions,
                                columns = 3,
                                itemHeight = 32.dp,
                                fontSize = 11.sp
                            )

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
                        ExpenseUniformFilterGrid(
                            options = listOf(
                                ExpenseGridOptionItem(
                                    label = "일반 지출",
                                    isSelected = !isFixedExpense,
                                    selectedContainerColor = Slate800,
                                    selectedBorderColor = Slate900,
                                    onClick = { isFixedExpense = false }
                                ),
                                ExpenseGridOptionItem(
                                    label = "고정지출로 지정",
                                    isSelected = isFixedExpense,
                                    selectedContainerColor = Color(0xFF5B21B6),
                                    selectedBorderColor = Color(0xFF4C1D95),
                                    unselectedContainerColor = Color(0xFFF5F3FF),
                                    unselectedBorderColor = Color(0xFFDDD6FE),
                                    unselectedContentColor = Color(0xFF5B21B6),
                                    onClick = { isFixedExpense = true }
                                )
                            ),
                            columns = 2,
                            itemHeight = 34.dp,
                            fontSize = 11.5.sp
                        )
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
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Slate100)
                                    .border(1.dp, Slate300, RoundedCornerShape(6.dp))
                                    .clickable { onOpenCategoryManage() }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "카테고리 수정/삭제",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate700
                                )
                            }
                        }
                        val editCatOptions = managedCategories.withEtcAlwaysAtBottom().map { item ->
                            val isSelected = if (item.isBuiltIn) {
                                selectedCustomCategoryName == null && selectedCategory == item.baseCategory
                            } else {
                                selectedCustomCategoryName == item.displayName
                            }
                            ExpenseGridOptionItem(
                                label = item.displayName,
                                isSelected = isSelected,
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
                                }
                            )
                        }
                        ExpenseUniformFilterGrid(
                            options = editCatOptions,
                            columns = 3,
                            itemHeight = 34.dp,
                            fontSize = 11.5.sp
                        )

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
                                    Box(
                                        modifier = Modifier
                                            .height(22.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFEFF6FF))
                                            .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(4.dp))
                                            .clickable {
                                                editingCatItemTarget = currentSelectedManagedCat
                                                editingCatNewName = currentSelectedManagedCat.displayName
                                            }
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "이름 수정",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFF2563EB)
                                        )
                                    }
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Box(
                                        modifier = Modifier
                                            .height(22.dp)
                                            .clip(RoundedCornerShape(4.dp))
                                            .background(Color(0xFFFEF2F2))
                                            .border(1.dp, Color(0xFFFECACA), RoundedCornerShape(4.dp))
                                            .clickable {
                                                onDeleteCategory(currentSelectedManagedCat)
                                                selectedCategory = ExpenseCategory.ETC
                                                selectedCustomCategoryName = null
                                            }
                                            .padding(horizontal = 6.dp),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Text(
                                            text = "항목 삭제",
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Color(0xFFDC2626)
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
                            Box(
                                modifier = Modifier
                                    .height(24.dp)
                                    .clip(RoundedCornerShape(6.dp))
                                    .background(Slate100)
                                    .border(1.dp, Slate300, RoundedCornerShape(6.dp))
                                    .clickable { onOpenUserClassificationManage() }
                                    .padding(horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "전체 분류 관리",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Slate700
                                )
                            }
                        }
                        val editUserOptions = buildList {
                            add(
                                ExpenseGridOptionItem(
                                    label = "미지정",
                                    isSelected = userClassificationInput.isBlank(),
                                    selectedContainerColor = Slate800,
                                    selectedBorderColor = Slate900,
                                    onClick = {
                                        userClassificationInput = ""
                                        editingUcNameTarget = null
                                    }
                                )
                            )
                            userClassifications.forEach { uc ->
                                val (fg, _) = getUserClassificationBadgeColors(uc.colorIndex)
                                val isSelected = userClassificationInput.trim() == uc.name
                                add(
                                    ExpenseGridOptionItem(
                                        label = uc.name,
                                        isSelected = isSelected,
                                        dotColor = fg,
                                        selectedContainerColor = fg,
                                        selectedBorderColor = fg,
                                        onClick = {
                                            userClassificationInput = uc.name
                                            if (com.autologue.app.data.preferences.ExpenseCustomMetaPreferences.isCorporateCategoryOrClassification(uc.name)) {
                                                isCorporate = true
                                            }
                                            editingUcNameTarget = null
                                        }
                                    )
                                )
                            }
                        }
                        ExpenseUniformFilterGrid(
                            options = editUserOptions,
                            columns = 3,
                            itemHeight = 32.dp,
                            fontSize = 11.sp
                        )

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
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "저장 방식 선택",
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Bold,
                                color = Slate800
                            )
                            Surface(
                                onClick = onOpenPermanentRulesManage,
                                shape = RoundedCornerShape(5.dp),
                                color = PureWhite,
                                border = BorderStroke(1.dp, Color(0xFF93C5FD))
                            ) {
                                Text(
                                    text = "영구저장 데이터 관리 →",
                                    fontSize = 10.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = Color(0xFF1D4ED8),
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                )
                            }
                        }
                        ExpenseUniformFilterGrid(
                            options = listOf(
                                ExpenseGridOptionItem(
                                    label = "1회성 저장",
                                    isSelected = isOneTimeSave,
                                    selectedContainerColor = Color(0xFFD97706),
                                    selectedBorderColor = Color(0xFFB45309),
                                    unselectedContainerColor = PureWhite,
                                    onClick = { if (!isSaving) isOneTimeSave = true }
                                ),
                                ExpenseGridOptionItem(
                                    label = "영구저장",
                                    isSelected = !isOneTimeSave,
                                    selectedContainerColor = Color(0xFF2563EB),
                                    selectedBorderColor = Color(0xFF1D4ED8),
                                    unselectedContainerColor = PureWhite,
                                    onClick = { if (!isSaving) isOneTimeSave = false }
                                )
                            ),
                            columns = 2,
                            itemHeight = 36.dp,
                            fontSize = 12.sp
                        )

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
                                        Box(
                                            modifier = Modifier
                                                .height(22.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Color(0xFFEFF6FF))
                                                .border(1.dp, Color(0xFFBFDBFE), RoundedCornerShape(4.dp))
                                                .clickable {
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
                                                }
                                                .padding(horizontal = 6.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text(
                                                text = if (allChecked) "기본선택" else "전체선택",
                                                fontSize = 9.sp,
                                                fontWeight = FontWeight.SemiBold,
                                                color = Color(0xFF1D4ED8)
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

                                    ExpenseUniformFilterGrid(
                                        options = optionItems.map { (label, checked, onToggle) ->
                                            ExpenseGridOptionItem(
                                                label = if (checked) "✓ $label" else label,
                                                isSelected = checked,
                                                selectedContainerColor = Color(0xFF2563EB),
                                                selectedBorderColor = Color(0xFF1D4ED8),
                                                onClick = { if (!isSaving) onToggle(!checked) }
                                            )
                                        },
                                        columns = 3,
                                        itemHeight = 30.dp,
                                        fontSize = 10.5.sp
                                    )
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

/**
 * 인라인 필터 바 전용 칩 컴포넌트
 * Material3 Surface(onClick = ...)의 48dp 강제 확장(minimumInteractiveComponentSize)을 사용하지 않고
 * 고정 높이(기본 30.dp)와 균일한 패딩/모서리를 적용하여 줄 간격 왜곡과 좌우 들여쓰기 틀어짐을 완벽히 방지합니다.
 */
@Composable
fun ExpenseInlineFilterChip(
    text: String,
    isSelected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    dotColor: Color? = null,
    selectedContainerColor: Color = AppColors.primary,
    selectedBorderColor: Color = AppColors.primaryDark,
    selectedContentColor: Color = PureWhite,
    unselectedContainerColor: Color = PureWhite,
    unselectedBorderColor: Color = Slate200,
    unselectedContentColor: Color = Slate700,
    height: Dp = 30.dp,
    fontSize: TextUnit = 11.5.sp
) {
    val bgColor = if (isSelected) selectedContainerColor else unselectedContainerColor
    val bdColor = if (isSelected) selectedBorderColor else unselectedBorderColor
    val fgColor = if (isSelected) selectedContentColor else unselectedContentColor
    val shape = RoundedCornerShape(8.dp)

    Row(
        modifier = modifier
            .height(height)
            .clip(shape)
            .background(bgColor)
            .border(1.dp, bdColor, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
    ) {
        if (dotColor != null) {
            Box(
                modifier = Modifier
                    .size(6.dp)
                    .background(if (isSelected) PureWhite else dotColor, CircleShape)
            )
            Spacer(modifier = Modifier.width(5.dp))
        }
        Text(
            text = text,
            fontSize = fontSize,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = fgColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * 균등 그리드 필터 버튼 데이터 모델
 */
data class ExpenseGridOptionItem(
    val label: String,
    val isSelected: Boolean,
    val dotColor: Color? = null,
    val selectedContainerColor: Color = AppColors.primary,
    val selectedBorderColor: Color = AppColors.primaryDark,
    val selectedContentColor: Color = PureWhite,
    val unselectedContainerColor: Color = Slate50,
    val unselectedBorderColor: Color = Slate200,
    val unselectedContentColor: Color = Slate700,
    val onClick: () -> Unit
)

/**
 * 가로폭 100%에 맞춰 N열 균등 분할(Modifier.weight(1f))로 정렬되는 필터 그리드 컴포넌트
 * 글자 수(2글자~6글자)에 관계없이 모든 버튼이 동일한 가로폭과 고정 높이(기본 36.dp)를 유지하며,
 * 마지막 행에 빈 칸이 남더라도 Spacer(Modifier.weight(1f))로 열 정렬을 완벽히 맞춥니다.
 */
@Composable
fun ExpenseUniformFilterGrid(
    options: List<ExpenseGridOptionItem>,
    columns: Int = 3,
    modifier: Modifier = Modifier,
    itemHeight: Dp = 36.dp,
    fontSize: TextUnit = 12.sp,
    horizontalSpacing: Dp = 6.dp,
    verticalSpacing: Dp = 6.dp
) {
    val rows = options.chunked(columns.coerceAtLeast(1))
    val shape = RoundedCornerShape(8.dp)

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(verticalSpacing)
    ) {
        rows.forEach { rowItems ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(horizontalSpacing),
                verticalAlignment = Alignment.CenterVertically
            ) {
                rowItems.forEach { item ->
                    val bgColor = if (item.isSelected) item.selectedContainerColor else item.unselectedContainerColor
                    val bdColor = if (item.isSelected) item.selectedBorderColor else item.unselectedBorderColor
                    val fgColor = if (item.isSelected) item.selectedContentColor else item.unselectedContentColor

                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(itemHeight)
                            .clip(shape)
                            .background(bgColor)
                            .border(1.dp, bdColor, shape)
                            .clickable(onClick = item.onClick)
                            .padding(horizontal = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        if (item.dotColor != null) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .background(if (item.isSelected) PureWhite else item.dotColor, CircleShape)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                        }
                        Text(
                            text = item.label,
                            fontSize = fontSize,
                            fontWeight = if (item.isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = fgColor,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            textAlign = TextAlign.Center
                        )
                    }
                }
                val emptySlots = columns - rowItems.size
                repeat(emptySlots) {
                    Spacer(
                        modifier = Modifier
                            .weight(1f)
                            .height(itemHeight)
                    )
                }
            }
        }
    }
}

