package com.autologue.app.presentation.expense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.data.preferences.ManagedCategoryItem
import com.autologue.app.data.preferences.PermanentCardCorporateRuleItem
import com.autologue.app.data.preferences.PermanentMerchantRuleSummary
import com.autologue.app.data.preferences.PermanentRuleAttribute
import com.autologue.app.data.preferences.UserClassificationItem
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.TransactionRule
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.theme.*

/**
 * 카테고리 목록에서 '기타(ExpenseCategory.ETC)' 항목이 항상 제일 하단(마지막)에 오도록 정렬하는 헬퍼
 */
fun List<ManagedCategoryItem>.withEtcAlwaysAtBottom(): List<ManagedCategoryItem> {
    return this.sortedBy { it.isBuiltIn && it.baseCategory == ExpenseCategory.ETC }
}

/**
 * 사용자 분류 배지용 10가지 고대비 색상 팔레트 (글자색 to 배경색)
 */
fun getUserClassificationBadgeColors(colorIndex: Int): Pair<Color, Color> {
    val palettes = listOf(
        Color(0xFF047857) to Color(0xFFD1FAE5), // 0: 에메랄드 그린
        Color(0xFF6D28D9) to Color(0xFFEDE9FE), // 1: 바이올렛 퍼플
        Color(0xFFBE123C) to Color(0xFFFFE4E6), // 2: 로즈 레드
        Color(0xFFB45309) to Color(0xFFFEF3C7), // 3: 앰버 골드
        Color(0xFF0E7490) to Color(0xFFCFFAFE), // 4: 시안 블루
        Color(0xFF1D4ED8) to Color(0xFFDBEAFE), // 5: 로얄 블루
        Color(0xFF0F766E) to Color(0xFFCCFBF1), // 6: 틸 그린
        Color(0xFFA21CAF) to Color(0xFFFAE8FF), // 7: 푸시아 핑크
        Color(0xFF4338CA) to Color(0xFFE0E7FF), // 8: 인디고 블루
        Color(0xFF374151) to Color(0xFFE5E7EB)  // 9: 슬레이트 그레이
    )
    val idx = (colorIndex % palettes.size + palettes.size) % palettes.size
    return palettes[idx]
}

/**
 * 5) 등록된 카테고리들 관리 다이얼로그 (추가, 삭제, 수정 및 카테고리별 기본 사용자 지정 - 기타 항목은 항상 최하단 고정)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CategoryManageDialog(
    managedCategories: List<ManagedCategoryItem>,
    userClassifications: List<UserClassificationItem> = emptyList(),
    onDismiss: () -> Unit,
    onAddCategory: (String) -> Unit,
    onAddCategoryWithDefaultUser: ((String, String?) -> Unit)? = null,
    onSetCategoryDefaultUser: (ManagedCategoryItem, String?) -> Unit = { _, _ -> },
    onUpdateCategoryName: (ManagedCategoryItem, String) -> Unit,
    onDeleteCategory: (ManagedCategoryItem) -> Unit,
    onRestoreDefaults: () -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    var newCategoryName by remember { mutableStateOf("") }
    var newCategoryDefaultUser by remember { mutableStateOf<String?>(null) }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var editingNameText by remember { mutableStateOf("") }
    var defaultUserTargetId by remember { mutableStateOf<String?>(null) }
    var customDefaultUserInput by remember { mutableStateOf("") }
    val orderedCategories = remember(managedCategories) {
        managedCategories.withEtcAlwaysAtBottom()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        "카테고리 관리",
                        style = if (isEasyMode) {
                            AppTypography.h2.copy(
                                fontSize = 18.sp,
                                fontWeight = FontWeight.ExtraBold,
                                color = EasyTheme.textPrimary
                            )
                        } else {
                            AppTypography.h2
                        },
                        modifier = Modifier.weight(1f)
                    )
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        EasyModalModeSwitchChip()
                        TextButton(
                            onClick = onRestoreDefaults,
                            contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                        ) {
                            Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = Slate600)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("기본값 복원", fontSize = 11.sp, color = Slate600)
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "카테고리 추가·수정·삭제 및 카테고리별 기본 사용자를 지정할 수 있습니다. 기본 사용자가 지정된 카테고리라도 개별 내역에서 언제든 다른 사용자로 변경할 수 있습니다.",
                    style = AppTypography.captionMuted
                )

                // 새 카테고리 추가 영역 (기본 사용자 동시 지정 지원)
                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                    color = if (isEasyMode) EasyTheme.surface else Slate50,
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                AutoLogueTextField(
                                    value = newCategoryName,
                                    onValueChange = { newCategoryName = it },
                                    label = "새 카테고리 이름",
                                    placeholder = "예: 교육/육아, 반려동물",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Button(
                                onClick = {
                                    val trimmed = newCategoryName.trim()
                                    if (trimmed.isNotBlank()) {
                                        if (onAddCategoryWithDefaultUser != null) {
                                            onAddCategoryWithDefaultUser(trimmed, newCategoryDefaultUser)
                                        } else {
                                            onAddCategory(trimmed)
                                        }
                                        newCategoryName = ""
                                        newCategoryDefaultUser = null
                                    }
                                },
                                enabled = newCategoryName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isEasyMode) EasyTheme.blue else AppColors.primary
                                ),
                                shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = "추가", modifier = Modifier.size(16.dp), tint = PureWhite)
                                Spacer(modifier = Modifier.width(2.dp))
                                Text("추가", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                            }
                        }

                        if (userClassifications.isNotEmpty()) {
                            Text(
                                text = "새 카테고리 기본 사용자 (선택)",
                                style = AppTypography.caption.copy(fontSize = 10.5.sp, fontWeight = FontWeight.SemiBold, color = Slate600)
                            )
                            val newCatUserOptions = buildList {
                                add(
                                    ExpenseGridOptionItem(
                                        label = "미지정",
                                        isSelected = newCategoryDefaultUser.isNullOrBlank(),
                                        selectedContainerColor = Slate800,
                                        selectedBorderColor = Slate900,
                                        unselectedContainerColor = PureWhite,
                                        onClick = { newCategoryDefaultUser = null }
                                    )
                                )
                                userClassifications.forEach { uc ->
                                    val (fg, _) = getUserClassificationBadgeColors(uc.colorIndex)
                                    val isSel = newCategoryDefaultUser?.equals(uc.name, ignoreCase = true) == true
                                    add(
                                        ExpenseGridOptionItem(
                                            label = uc.name,
                                            isSelected = isSel,
                                            dotColor = fg,
                                            selectedContainerColor = fg,
                                            selectedBorderColor = fg,
                                            unselectedContainerColor = PureWhite,
                                            onClick = { newCategoryDefaultUser = uc.name }
                                        )
                                    )
                                }
                            }
                            ExpenseUniformFilterGrid(
                                options = newCatUserOptions,
                                columns = 3,
                                itemHeight = 28.dp,
                                fontSize = 10.5.sp
                            )
                        }
                    }
                }

                if (!isEasyMode) {
                    HairlineDivider()
                }

                Text(
                    text = "등록된 카테고리 목록 (${orderedCategories.size}개 · 배지를 눌러 기본 사용자 지정)",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate700)
                )

                orderedCategories.forEach { item ->
                    val isEditing = editingItemId == item.id
                    val isDefaultUserOpen = defaultUserTargetId == item.id
                    val isEtcBuiltIn = item.isBuiltIn && item.baseCategory == ExpenseCategory.ETC
                    val currentDefaultUser = item.defaultUserName?.takeIf { it.isNotBlank() }
                    val matchedDefaultUc = remember(currentDefaultUser, userClassifications) {
                        if (currentDefaultUser == null) null
                        else userClassifications.firstOrNull { it.name.equals(currentDefaultUser, ignoreCase = true) }
                    }

                    Surface(
                        shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                        color = if (isEasyMode) {
                            EasyTheme.surface
                        } else if (item.isBuiltIn) {
                            Slate50
                        } else {
                            Color(0xFFEFF6FF)
                        },
                        border = if (isEasyMode) {
                            null
                        } else {
                            BorderStroke(1.dp, if (item.isBuiltIn) Slate200 else Color(0xFFBFDBFE))
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.fillMaxWidth()) {
                            if (isEditing) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 10.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = editingNameText,
                                        onValueChange = { editingNameText = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val clean = editingNameText.trim()
                                            if (clean.isNotBlank()) {
                                                onUpdateCategoryName(item, clean)
                                                editingItemId = null
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text("저장", fontSize = 11.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(
                                        onClick = { editingItemId = null },
                                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 6.dp)
                                    ) {
                                        Text("취소", fontSize = 11.sp, color = Slate600)
                                    }
                                }
                            } else {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(4.dp),
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = item.displayName,
                                            style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold, color = Slate900),
                                            modifier = Modifier.align(Alignment.CenterVertically)
                                        )
                                        Box(modifier = Modifier.align(Alignment.CenterVertically)) {
                                            MetricBadge(
                                                text = when {
                                                    isEtcBuiltIn -> "기본 · 최하단 고정"
                                                    item.isBuiltIn -> "기본"
                                                    else -> "추가됨"
                                                },
                                                textColor = if (item.isBuiltIn) Slate600 else Color(0xFF1D4ED8),
                                                backgroundColor = if (item.isBuiltIn) Slate200 else Color(0xFFDBEAFE)
                                            )
                                        }
                                        val (uFg, uBg) = if (currentDefaultUser != null) {
                                            getUserClassificationBadgeColors(matchedDefaultUc?.colorIndex ?: 0)
                                        } else {
                                            Slate600 to PureWhite
                                        }
                                        Row(
                                            modifier = Modifier
                                                .align(Alignment.CenterVertically)
                                                .height(22.dp)
                                                .clip(RoundedCornerShape(6.dp))
                                                .background(if (isDefaultUserOpen) Slate800 else uBg)
                                                .border(
                                                    width = 1.dp,
                                                    color = if (isDefaultUserOpen) {
                                                        Slate900
                                                    } else if (currentDefaultUser != null) {
                                                        uFg.copy(alpha = 0.4f)
                                                    } else {
                                                        Slate300
                                                    },
                                                    shape = RoundedCornerShape(6.dp)
                                                )
                                                .clickable {
                                                    if (isDefaultUserOpen) {
                                                        defaultUserTargetId = null
                                                    } else {
                                                        defaultUserTargetId = item.id
                                                        customDefaultUserInput = currentDefaultUser.orEmpty()
                                                    }
                                                }
                                                .padding(horizontal = 7.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            if (currentDefaultUser != null && !isDefaultUserOpen) {
                                                Box(
                                                    modifier = Modifier
                                                        .size(5.dp)
                                                        .background(uFg, CircleShape)
                                                )
                                            }
                                            Text(
                                                text = if (currentDefaultUser != null) {
                                                    "기본: $currentDefaultUser"
                                                } else {
                                                    "기본 사용자 지정"
                                                },
                                                fontSize = 10.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = if (isDefaultUserOpen) PureWhite else uFg
                                            )
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                editingItemId = item.id
                                                editingNameText = item.displayName
                                                defaultUserTargetId = null
                                            },
                                            modifier = Modifier.size(30.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "수정", tint = Slate600, modifier = Modifier.size(16.dp))
                                        }
                                        if (!isEtcBuiltIn) {
                                            IconButton(
                                                onClick = { onDeleteCategory(item) },
                                                modifier = Modifier.size(30.dp)
                                            ) {
                                                Icon(Icons.Default.Delete, contentDescription = "삭제", tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
                                            }
                                        }
                                    }
                                }
                            }

                            if (isDefaultUserOpen && !isEditing) {
                                HairlineDivider()
                                Column(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(PureWhite)
                                        .padding(horizontal = 10.dp, vertical = 8.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            text = "'${item.displayName}' 기본 사용자 선택",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = Slate800
                                        )
                                        Box(
                                            modifier = Modifier
                                                .height(22.dp)
                                                .clip(RoundedCornerShape(4.dp))
                                                .background(Slate100)
                                                .clickable { defaultUserTargetId = null }
                                                .padding(horizontal = 7.dp),
                                            contentAlignment = Alignment.Center
                                        ) {
                                            Text("닫기", fontSize = 10.sp, fontWeight = FontWeight.SemiBold, color = Slate600)
                                        }
                                    }

                                    val itemUserOptions = buildList {
                                        add(
                                            ExpenseGridOptionItem(
                                                label = "미지정 (해제)",
                                                isSelected = currentDefaultUser == null,
                                                selectedContainerColor = Slate800,
                                                selectedBorderColor = Slate900,
                                                onClick = {
                                                    onSetCategoryDefaultUser(item, null)
                                                    customDefaultUserInput = ""
                                                }
                                            )
                                        )
                                        userClassifications.forEach { uc ->
                                            val (fg, _) = getUserClassificationBadgeColors(uc.colorIndex)
                                            val isSel = currentDefaultUser?.equals(uc.name, ignoreCase = true) == true
                                            add(
                                                ExpenseGridOptionItem(
                                                    label = uc.name,
                                                    isSelected = isSel,
                                                    dotColor = fg,
                                                    selectedContainerColor = fg,
                                                    selectedBorderColor = fg,
                                                    onClick = {
                                                        onSetCategoryDefaultUser(item, uc.name)
                                                        customDefaultUserInput = uc.name
                                                    }
                                                )
                                            )
                                        }
                                    }
                                    ExpenseUniformFilterGrid(
                                        options = itemUserOptions,
                                        columns = 3,
                                        itemHeight = 30.dp,
                                        fontSize = 11.sp
                                    )

                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Box(modifier = Modifier.weight(1f)) {
                                            AutoLogueTextField(
                                                value = customDefaultUserInput,
                                                onValueChange = { customDefaultUserInput = it },
                                                label = "기본 사용자명 직접 입력",
                                                placeholder = "예: 정선우, 본인, 가족",
                                                singleLine = true,
                                                modifier = Modifier.fillMaxWidth()
                                            )
                                        }
                                        Button(
                                            onClick = {
                                                val clean = customDefaultUserInput.trim()
                                                onSetCategoryDefaultUser(item, clean.ifBlank { null })
                                                defaultUserTargetId = null
                                            },
                                            colors = ButtonDefaults.buttonColors(
                                                containerColor = if (isEasyMode) EasyTheme.blue else AppColors.primary
                                            ),
                                            shape = RoundedCornerShape(8.dp),
                                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 10.dp)
                                        ) {
                                            Text(
                                                text = if (customDefaultUserInput.isBlank()) "해제" else "지정",
                                                fontSize = 11.sp,
                                                color = PureWhite,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            AutoLoguePrimaryButton(text = "완료", onClick = onDismiss)
        },
        containerColor = if (isEasyMode) EasyTheme.bg else AppColors.surface,
        shape = if (isEasyMode) RoundedCornerShape(28.dp) else AppShapes.modal
    )
}

/**
 * 6) 사용자 분류 관리 다이얼로그 (사용자명 직접입력, 추가/삭제/수정 및 배지 색상 지정)
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun UserClassificationManageDialog(
    userClassifications: List<UserClassificationItem>,
    onDismiss: () -> Unit,
    onAddUserClassification: (String, Int) -> Unit,
    onUpdateUserClassification: (String, String, Int) -> Unit,
    onDeleteUserClassification: (String) -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    var newUserName by remember { mutableStateOf("") }
    var selectedColorIndex by remember { mutableStateOf(userClassifications.size % ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT) }
    var editingOriginalName by remember { mutableStateOf<String?>(null) }
    var editingNameText by remember { mutableStateOf("") }
    var editingColorIndex by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "사용자 분류 관리",
                    style = if (isEasyMode) {
                        AppTypography.h2.copy(
                            fontSize = 18.sp,
                            fontWeight = FontWeight.ExtraBold,
                            color = EasyTheme.textPrimary
                        )
                    } else {
                        AppTypography.h2
                    },
                    modifier = Modifier.weight(1f)
                )
                EasyModalModeSwitchChip()
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(Spacing.sm)
            ) {
                Text(
                    text = "카테고리와 별개로 결제 내역에 사용자 분류(본인, 가족, 배우자, 회사 등)를 지정하면 리스트에 구분된 색상의 배지로 표기되고 엑셀 다운로드에도 포함됩니다.",
                    style = AppTypography.captionMuted
                )

                // 새 사용자 분류 직접 입력 및 배지 색상 선택
                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 16.dp else 10.dp),
                    color = if (isEasyMode) EasyTheme.surface else Slate50,
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(if (isEasyMode) 14.dp else 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("새 사용자 분류 추가", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Box(modifier = Modifier.weight(1f)) {
                                AutoLogueTextField(
                                    value = newUserName,
                                    onValueChange = { newUserName = it },
                                    label = "사용자명",
                                    placeholder = "예: 정선우, 배우자, 법인",
                                    singleLine = true,
                                    modifier = Modifier.fillMaxWidth()
                                )
                            }
                            Button(
                                onClick = {
                                    val clean = newUserName.trim()
                                    if (clean.isNotBlank()) {
                                        onAddUserClassification(clean, selectedColorIndex)
                                        newUserName = ""
                                        selectedColorIndex = (selectedColorIndex + 1) % ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT
                                    }
                                },
                                enabled = newUserName.isNotBlank(),
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isEasyMode) EasyTheme.blue else AppColors.primary
                                ),
                                shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp)
                            ) {
                                Text("추가", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                            }
                        }

                        // 배지 색상 선택 팔레트
                        FlowRow(
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("배지 색상:", fontSize = 11.sp, color = Slate600, modifier = Modifier.align(Alignment.CenterVertically))
                            for (idx in 0 until ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT) {
                                val (fg, bg) = getUserClassificationBadgeColors(idx)
                                val isSel = selectedColorIndex == idx
                                Box(
                                    modifier = Modifier
                                        .size(22.dp)
                                        .clip(CircleShape)
                                        .background(bg)
                                        .border(
                                            width = if (isSel) 2.dp else 1.dp,
                                            color = if (isSel) fg else Slate300,
                                            shape = CircleShape
                                        )
                                        .clickable { selectedColorIndex = idx },
                                    contentAlignment = Alignment.Center
                                ) {
                                    if (isSel) {
                                        Icon(Icons.Default.Check, contentDescription = null, tint = fg, modifier = Modifier.size(13.dp))
                                    }
                                }
                            }
                        }
                    }
                }

                if (!isEasyMode) {
                    HairlineDivider()
                }

                Text(
                    text = "등록된 사용자 분류 (${userClassifications.size}개) · 색상 원을 누르면 즉시 변경됩니다",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate700)
                )

                userClassifications.forEach { item ->
                    val isEditing = editingOriginalName == item.name
                    val (badgeFg, badgeBg) = getUserClassificationBadgeColors(item.colorIndex)

                    Surface(
                        shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                        color = if (isEasyMode) EasyTheme.surface else PureWhite,
                        border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isEditing) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    OutlinedTextField(
                                        value = editingNameText,
                                        onValueChange = { editingNameText = it },
                                        singleLine = true,
                                        modifier = Modifier.weight(1f)
                                    )
                                    Button(
                                        onClick = {
                                            val clean = editingNameText.trim()
                                            if (clean.isNotBlank()) {
                                                onUpdateUserClassification(item.name, clean, editingColorIndex)
                                                editingOriginalName = null
                                            }
                                        },
                                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF059669)),
                                        shape = RoundedCornerShape(6.dp),
                                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)
                                    ) {
                                        Text("저장", fontSize = 11.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                                    }
                                    TextButton(onClick = { editingOriginalName = null }) {
                                        Text("취소", fontSize = 11.sp, color = Slate600)
                                    }
                                }
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    verticalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("색상 변경:", fontSize = 11.sp, color = Slate600, modifier = Modifier.align(Alignment.CenterVertically))
                                    for (idx in 0 until ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT) {
                                        val (fg, bg) = getUserClassificationBadgeColors(idx)
                                        val isSel = editingColorIndex == idx
                                        Box(
                                            modifier = Modifier
                                                .size(20.dp)
                                                .clip(CircleShape)
                                                .background(bg)
                                                .border(
                                                    width = if (isSel) 2.dp else 1.dp,
                                                    color = if (isSel) fg else Slate300,
                                                    shape = CircleShape
                                                )
                                                .clickable { editingColorIndex = idx },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSel) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = fg, modifier = Modifier.size(12.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        MetricBadge(
                                            text = item.name,
                                            textColor = badgeFg,
                                            backgroundColor = badgeBg
                                        )
                                        Text(
                                            text = "이름 수정·삭제 및 배지 색상 선택",
                                            style = AppTypography.captionMuted.copy(fontSize = 10.5.sp)
                                        )
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                        IconButton(
                                            onClick = {
                                                editingOriginalName = item.name
                                                editingNameText = item.name
                                                editingColorIndex = item.colorIndex
                                            },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Edit, contentDescription = "수정", tint = Slate600, modifier = Modifier.size(15.dp))
                                        }
                                        IconButton(
                                            onClick = { onDeleteUserClassification(item.name) },
                                            modifier = Modifier.size(28.dp)
                                        ) {
                                            Icon(Icons.Default.Delete, contentDescription = "삭제", tint = Color(0xFFDC2626), modifier = Modifier.size(15.dp))
                                        }
                                    }
                                }
                                // 인라인 원클릭 배지 색상 변경 바
                                FlowRow(
                                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                                    verticalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    for (idx in 0 until ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT) {
                                        val (fg, bg) = getUserClassificationBadgeColors(idx)
                                        val isSel = item.colorIndex == idx
                                        Box(
                                            modifier = Modifier
                                                .size(18.dp)
                                                .clip(CircleShape)
                                                .background(bg)
                                                .border(
                                                    width = if (isSel) 2.dp else 1.dp,
                                                    color = if (isSel) fg else Slate300,
                                                    shape = CircleShape
                                                )
                                                .clickable {
                                                    onUpdateUserClassification(item.name, item.name, idx)
                                                },
                                            contentAlignment = Alignment.Center
                                        ) {
                                            if (isSel) {
                                                Icon(Icons.Default.Check, contentDescription = null, tint = fg, modifier = Modifier.size(11.dp))
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            AutoLoguePrimaryButton(text = "완료", onClick = onDismiss)
        },
        containerColor = if (isEasyMode) EasyTheme.bg else AppColors.surface,
        shape = if (isEasyMode) RoundedCornerShape(28.dp) else AppShapes.modal
    )
}

private enum class PermanentRulesFilterTab(val label: String) {
    ALL("전체"),
    ALIAS_ONLY("결제명 치환"),
    META_ONLY("분류·속성"),
    CARD_AND_KEYWORD("카드·키워드")
}

/**
 * 7) 영구저장된 데이터 통합 조회 및 해제/초기화 다이얼로그
 * - 결제명 자동 치환 규칙(원본명 -> 변경된 결제명), 카테고리, 사용자 분류, 카드/은행·법인 지정, 고정지출 규칙을 한눈에 확인
 * - 속성별 개별 [✕] 해제, 결제명 치환만 해제(원본명 복원), 결제처 전체 규칙 해제(원본 복원), 전체 초기화 지원
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PermanentRulesManageDialog(
    merchantRules: List<PermanentMerchantRuleSummary>,
    cardCorporateRules: List<PermanentCardCorporateRuleItem>,
    dbRules: List<TransactionRule>,
    getBuiltInCategoryName: (ExpenseCategory) -> String,
    onDismiss: () -> Unit,
    onRemoveMerchantRule: (PermanentMerchantRuleSummary) -> Unit,
    onRemoveMerchantAttribute: (PermanentMerchantRuleSummary, PermanentRuleAttribute) -> Unit,
    onRemoveCardCorporateRule: (String) -> Unit,
    onDeleteDbRule: (Long, String) -> Unit,
    onClearAllRules: () -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    var searchQuery by remember { mutableStateOf("") }
    var selectedTab by remember { mutableStateOf(PermanentRulesFilterTab.ALL) }
    var confirmClearAll by remember { mutableStateOf(false) }

    val cleanQuery = searchQuery.trim()
    val filteredMerchantRules = remember(merchantRules, cleanQuery, selectedTab) {
        merchantRules.filter { rule ->
            val tabMatches = when (selectedTab) {
                PermanentRulesFilterTab.ALL -> true
                PermanentRulesFilterTab.ALIAS_ONLY -> rule.hasAliasRule
                PermanentRulesFilterTab.META_ONLY ->
                    rule.effectiveCategoryLabel != null ||
                        !rule.userClassificationName.isNullOrBlank() ||
                        !rule.cardDesignation.isNullOrBlank() ||
                        rule.isFixedExpense != null
                PermanentRulesFilterTab.CARD_AND_KEYWORD -> false
            }
            if (!tabMatches) return@filter false
            if (cleanQuery.isEmpty()) return@filter true
            rule.merchantName.contains(cleanQuery, ignoreCase = true) ||
                rule.sourceMerchantNames.any { it.contains(cleanQuery, ignoreCase = true) } ||
                (rule.effectiveCategoryLabel?.contains(cleanQuery, ignoreCase = true) == true) ||
                (rule.userClassificationName?.contains(cleanQuery, ignoreCase = true) == true) ||
                (rule.cardDesignation?.contains(cleanQuery, ignoreCase = true) == true)
        }
    }

    val filteredCardRules = remember(cardCorporateRules, cleanQuery, selectedTab) {
        if (selectedTab != PermanentRulesFilterTab.ALL && selectedTab != PermanentRulesFilterTab.CARD_AND_KEYWORD) {
            emptyList()
        } else if (cleanQuery.isEmpty()) {
            cardCorporateRules
        } else {
            cardCorporateRules.filter { it.cardKey.contains(cleanQuery, ignoreCase = true) }
        }
    }

    val filteredDbRules = remember(dbRules, cleanQuery, selectedTab) {
        if (selectedTab != PermanentRulesFilterTab.ALL && selectedTab != PermanentRulesFilterTab.CARD_AND_KEYWORD) {
            emptyList()
        } else if (cleanQuery.isEmpty()) {
            dbRules
        } else {
            dbRules.filter {
                it.keywordPattern.contains(cleanQuery, ignoreCase = true) ||
                    getBuiltInCategoryName(it.targetCategory).contains(cleanQuery, ignoreCase = true)
            }
        }
    }

    val totalCount = merchantRules.size + cardCorporateRules.size + dbRules.size
    val aliasCount = merchantRules.count { it.hasAliasRule }
    val metaCount = merchantRules.count {
        it.effectiveCategoryLabel != null ||
            !it.userClassificationName.isNullOrBlank() ||
            !it.cardDesignation.isNullOrBlank() ||
            it.isFixedExpense != null
    }
    val cardAndDbCount = cardCorporateRules.size + dbRules.size

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                        Text(
                            "영구저장 데이터 관리",
                            style = if (isEasyMode) {
                                AppTypography.h2.copy(
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = EasyTheme.textPrimary
                                )
                            } else {
                                AppTypography.h2
                            }
                        )
                        MetricBadge(
                            text = "총 ${totalCount}건",
                            textColor = Color(0xFF1D4ED8),
                            backgroundColor = Color(0xFFDBEAFE)
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        EasyModalModeSwitchChip()
                        if (totalCount > 0) {
                            TextButton(
                                onClick = { confirmClearAll = !confirmClearAll },
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                            ) {
                                Icon(
                                    Icons.Default.Delete,
                                    contentDescription = null,
                                    modifier = Modifier.size(14.dp),
                                    tint = Color(0xFFDC2626)
                                )
                                Spacer(modifier = Modifier.width(3.dp))
                                Text(
                                    text = if (confirmClearAll) "초기화 취소" else "전체 초기화",
                                    fontSize = 11.sp,
                                    color = Color(0xFFDC2626),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
                if (confirmClearAll) {
                    Surface(
                        shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                        color = Color(0xFFFEF2F2),
                        border = if (isEasyMode) null else BorderStroke(1.dp, Color(0xFFFECACA)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "모든 영구저장 규칙을 해제하고 기존 자동 치환 내역을 원본으로 복원할까요?",
                                fontSize = 11.sp,
                                color = Color(0xFF991B1B),
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Button(
                                onClick = {
                                    confirmClearAll = false
                                    onClearAllRules()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFDC2626)),
                                shape = RoundedCornerShape(6.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp)
                            ) {
                                Text("전체 해제 실행", fontSize = 11.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(max = 520.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Surface(
                    shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                    color = if (isEasyMode) EasyTheme.surface else Color(0xFFF8FAFC),
                    border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "잘못 영구저장되어 자동으로 결제명이 치환되거나 분류되는 항목을 찾아 개별 해제할 수 있습니다. 해제 즉시 기존에 자동 치환되었던 내역도 원본 문자 기준으로 일괄 복원됩니다.",
                        style = AppTypography.captionMuted.copy(fontSize = 11.sp, lineHeight = 15.sp),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp)
                    )
                }

                // 검색 입력창
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = { Text("결제명, 원본 가맹점명, 카테고리, 사용자 검색...", fontSize = 12.sp) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Slate500, modifier = Modifier.size(17.dp))
                    },
                    trailingIcon = {
                        if (searchQuery.isNotEmpty()) {
                            IconButton(onClick = { searchQuery = "" }, modifier = Modifier.size(24.dp)) {
                                Icon(Icons.Default.Close, contentDescription = "지우기", tint = Slate500, modifier = Modifier.size(15.dp))
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                    colors = if (isEasyMode) {
                        OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = EasyTheme.surface,
                            unfocusedContainerColor = EasyTheme.surface,
                            focusedBorderColor = EasyTheme.blue.copy(alpha = 0.45f),
                            unfocusedBorderColor = Color.Transparent
                        )
                    } else {
                        OutlinedTextFieldDefaults.colors()
                    },
                    textStyle = LocalTextStyle.current.copy(fontSize = 12.5.sp),
                    modifier = Modifier.fillMaxWidth()
                )

                // 필터 탭 바
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(if (isEasyMode) 6.dp else 4.dp)
                ) {
                    val tabs = listOf(
                        PermanentRulesFilterTab.ALL to totalCount,
                        PermanentRulesFilterTab.ALIAS_ONLY to aliasCount,
                        PermanentRulesFilterTab.META_ONLY to metaCount,
                        PermanentRulesFilterTab.CARD_AND_KEYWORD to cardAndDbCount
                    )
                    tabs.forEach { (tab, count) ->
                        val isSel = selectedTab == tab
                        Surface(
                            shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
                            color = if (isSel) {
                                if (isEasyMode) EasyTheme.blue else AppColors.primary
                            } else {
                                if (isEasyMode) EasyTheme.surface else Slate100
                            },
                            border = if (isEasyMode) null else BorderStroke(1.dp, if (isSel) AppColors.primary else Slate200),
                            modifier = Modifier
                                .weight(1f)
                                .clickable { selectedTab = tab }
                        ) {
                            Column(
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = tab.label,
                                    fontSize = 10.5.sp,
                                    fontWeight = if (isSel) FontWeight.Bold else FontWeight.Medium,
                                    color = if (isSel) PureWhite else Slate700,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Text(
                                    text = "${count}건",
                                    fontSize = 9.5.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    color = if (isSel) Color(0xFFDBEAFE) else Slate500
                                )
                            }
                        }
                    }
                }

                if (!isEasyMode) {
                    HairlineDivider()
                }

                // 규칙 목록 스크롤 영역
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(if (isEasyMode) 10.dp else 8.dp)
                ) {
                    if (filteredMerchantRules.isEmpty() && filteredCardRules.isEmpty() && filteredDbRules.isEmpty()) {
                        Surface(
                            shape = RoundedCornerShape(if (isEasyMode) 16.dp else 10.dp),
                            color = if (isEasyMode) EasyTheme.surface else Slate50,
                            border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 28.dp, horizontal = 16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                Text(
                                    text = if (cleanQuery.isNotEmpty()) {
                                        "'$cleanQuery' 검색 조건에 해당하는 영구저장 데이터가 없습니다."
                                    } else {
                                        "현재 등록된 영구저장 규칙이 없습니다."
                                    },
                                    style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold, color = Slate600)
                                )
                                Text(
                                    text = "결제 내역 수정 화면에서 '영구저장'으로 저장한 항목들이 이곳에 표시됩니다.",
                                    style = AppTypography.captionMuted
                                )
                            }
                        }
                    }

                    // 1. 가맹점별 통합 영구저장 규칙 카드
                    filteredMerchantRules.forEach { rule ->
                        Surface(
                            shape = RoundedCornerShape(if (isEasyMode) 16.dp else 10.dp),
                            color = if (isEasyMode) EasyTheme.surface else PureWhite,
                            border = if (isEasyMode) {
                                null
                            } else {
                                BorderStroke(
                                    1.dp,
                                    if (rule.hasAliasRule) Color(0xFF93C5FD) else Slate200
                                )
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(10.dp),
                                verticalArrangement = Arrangement.spacedBy(7.dp)
                            ) {
                                // 상단 헤더: [출금/입금] 대표 결제명 + [규칙 전체 해제] 버튼
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
                                        MetricBadge(
                                            text = if (rule.isDeposit) "입금" else "출금",
                                            textColor = if (rule.isDeposit) Color(0xFF047857) else Color(0xFF1E40AF),
                                            backgroundColor = if (rule.isDeposit) Color(0xFFD1FAE5) else Color(0xFFDBEAFE)
                                        )
                                        Text(
                                            text = rule.merchantName,
                                            style = AppTypography.body.copy(
                                                fontWeight = FontWeight.Bold,
                                                color = Slate900,
                                                fontSize = 13.5.sp
                                            ),
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis
                                        )
                                    }

                                    Surface(
                                        shape = RoundedCornerShape(6.dp),
                                        color = Color(0xFFFEF2F2),
                                        border = BorderStroke(1.dp, Color(0xFFFECACA)),
                                        modifier = Modifier.clickable { onRemoveMerchantRule(rule) }
                                    ) {
                                        Row(
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            verticalAlignment = Alignment.CenterVertically,
                                            horizontalArrangement = Arrangement.spacedBy(3.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Delete,
                                                contentDescription = "전체 해제",
                                                tint = Color(0xFFDC2626),
                                                modifier = Modifier.size(13.dp)
                                            )
                                            Text(
                                                text = "전체 해제",
                                                fontSize = 10.5.sp,
                                                fontWeight = FontWeight.Bold,
                                                color = Color(0xFFDC2626)
                                            )
                                        }
                                    }
                                }

                                // 결제명 자동 치환 정보 박스 (원본명 -> 변경된 결제명)
                                if (rule.hasAliasRule) {
                                    Surface(
                                        shape = RoundedCornerShape(8.dp),
                                        color = Color(0xFFEFF6FF),
                                        border = BorderStroke(1.dp, Color(0xFFBFDBFE)),
                                        modifier = Modifier.fillMaxWidth()
                                    ) {
                                        Row(
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .padding(horizontal = 9.dp, vertical = 6.dp),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Column(
                                                modifier = Modifier.weight(1f),
                                                verticalArrangement = Arrangement.spacedBy(2.dp)
                                            ) {
                                                Text(
                                                    text = "결제명 자동 치환 적용 중",
                                                    fontSize = 10.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF1D4ED8)
                                                )
                                                Text(
                                                    text = "${rule.sourceMerchantNames.joinToString(", ")}  ➔  ${rule.merchantName}",
                                                    fontSize = 11.5.sp,
                                                    fontWeight = FontWeight.SemiBold,
                                                    color = Slate800
                                                )
                                            }
                                            Spacer(modifier = Modifier.width(6.dp))
                                            Surface(
                                                shape = RoundedCornerShape(6.dp),
                                                color = PureWhite,
                                                border = BorderStroke(1.dp, Color(0xFF93C5FD)),
                                                modifier = Modifier.clickable {
                                                    onRemoveMerchantAttribute(rule, PermanentRuleAttribute.ALIAS_NAME)
                                                }
                                            ) {
                                                Text(
                                                    text = "치환 해제·복원",
                                                    fontSize = 10.5.sp,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Color(0xFF1D4ED8),
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }
                                    }
                                }

                                // 개별 속성 배지 (탭하여 개별 속성만 해제 가능)
                                val hasAnyMeta = rule.effectiveCategoryLabel != null ||
                                    !rule.userClassificationName.isNullOrBlank() ||
                                    !rule.cardDesignation.isNullOrBlank() ||
                                    rule.isFixedExpense != null

                                if (hasAnyMeta) {
                                    FlowRow(
                                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                                        verticalArrangement = Arrangement.spacedBy(6.dp)
                                    ) {
                                        rule.effectiveCategoryLabel?.let { catLabel ->
                                            RemovableRuleAttributeChip(
                                                prefix = "카테고리",
                                                value = catLabel,
                                                textColor = Color(0xFF0F766E),
                                                bgColor = Color(0xFFCCFBF1),
                                                borderColor = Color(0xFF99F6E4),
                                                onRemove = {
                                                    onRemoveMerchantAttribute(rule, PermanentRuleAttribute.CATEGORY)
                                                }
                                            )
                                        }
                                        rule.userClassificationName?.takeIf { it.isNotBlank() }?.let { uName ->
                                            RemovableRuleAttributeChip(
                                                prefix = "사용자",
                                                value = uName,
                                                textColor = Color(0xFF6D28D9),
                                                bgColor = Color(0xFFEDE9FE),
                                                borderColor = Color(0xFFDDD6FE),
                                                onRemove = {
                                                    onRemoveMerchantAttribute(rule, PermanentRuleAttribute.USER_CLASSIFICATION)
                                                }
                                            )
                                        }
                                        rule.cardDesignation?.takeIf { it.isNotBlank() }?.let { cardDesig ->
                                            RemovableRuleAttributeChip(
                                                prefix = "카드/은행",
                                                value = cardDesig,
                                                textColor = Color(0xFF374151),
                                                bgColor = Slate100,
                                                borderColor = Slate300,
                                                onRemove = {
                                                    onRemoveMerchantAttribute(rule, PermanentRuleAttribute.CARD_DESIGNATION)
                                                }
                                            )
                                        }
                                        rule.isFixedExpense?.let { isFixed ->
                                            val isIncomeRule = rule.isDeposit || rule.baseCategory == ExpenseCategory.INCOME
                                            RemovableRuleAttributeChip(
                                                prefix = "고정항목",
                                                value = if (isFixed) {
                                                    if (isIncomeRule) "고정수입 지정" else "고정지출 지정"
                                                } else {
                                                    "고정항목 제외"
                                                },
                                                textColor = Color(0xFFB45309),
                                                bgColor = Color(0xFFFEF3C7),
                                                borderColor = Color(0xFFFDE68A),
                                                onRemove = {
                                                    onRemoveMerchantAttribute(rule, PermanentRuleAttribute.FIXED_EXPENSE)
                                                }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }

                    // 2. 카드별 법인/개인 영구 지정 규칙
                    if (filteredCardRules.isNotEmpty()) {
                        Text(
                            text = "카드별 법인/개인 영구 규칙 (${filteredCardRules.size}건)",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate700),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        filteredCardRules.forEach { cardRule ->
                            Surface(
                                shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                                color = if (isEasyMode) EasyTheme.surface else PureWhite,
                                border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = cardRule.cardKey,
                                            style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold, color = Slate900)
                                        )
                                        MetricBadge(
                                            text = if (cardRule.isCorporate) "법인카드 고정" else "개인결제 고정",
                                            textColor = if (cardRule.isCorporate) Color(0xFF1D4ED8) else Slate600,
                                            backgroundColor = if (cardRule.isCorporate) Color(0xFFDBEAFE) else Slate200
                                        )
                                    }
                                    TextButton(
                                        onClick = { onRemoveCardCorporateRule(cardRule.cardKey) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("규칙 해제", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }

                    // 3. 키워드 기반 자동 분류 규칙 (DB TransactionRule)
                    if (filteredDbRules.isNotEmpty()) {
                        Text(
                            text = "키워드 자동 분류 규칙 (${filteredDbRules.size}건)",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate700),
                            modifier = Modifier.padding(top = 4.dp)
                        )
                        filteredDbRules.forEach { dbRule ->
                            Surface(
                                shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
                                color = if (isEasyMode) EasyTheme.surface else PureWhite,
                                border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(
                                            text = "키워드: ${dbRule.keywordPattern}",
                                            style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold, color = Slate900)
                                        )
                                        MetricBadge(
                                            text = getBuiltInCategoryName(dbRule.targetCategory),
                                            textColor = Color(0xFF047857),
                                            backgroundColor = Color(0xFFD1FAE5)
                                        )
                                    }
                                    TextButton(
                                        onClick = { onDeleteDbRule(dbRule.id, dbRule.keywordPattern) },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Text("규칙 해제", fontSize = 11.sp, color = Color(0xFFDC2626), fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            AutoLoguePrimaryButton(text = "닫기", onClick = onDismiss)
        },
        containerColor = if (isEasyMode) EasyTheme.bg else AppColors.surface,
        shape = if (isEasyMode) RoundedCornerShape(28.dp) else AppShapes.modal
    )
}

@Composable
private fun RemovableRuleAttributeChip(
    prefix: String,
    value: String,
    textColor: Color,
    bgColor: Color,
    borderColor: Color,
    onRemove: () -> Unit
) {
    val isEasyMode = LocalEasyMode.current
    Surface(
        shape = RoundedCornerShape(if (isEasyMode) 8.dp else 6.dp),
        color = bgColor,
        border = if (isEasyMode) null else BorderStroke(1.dp, borderColor),
        modifier = Modifier.clickable { onRemove() }
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "$prefix: $value",
                fontSize = 10.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = textColor
            )
            Icon(
                Icons.Default.Close,
                contentDescription = "$prefix 규칙 해제",
                tint = textColor,
                modifier = Modifier.size(12.dp)
            )
        }
    }
}

