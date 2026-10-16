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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.data.preferences.ExpenseCustomMetaPreferences
import com.autologue.app.data.preferences.ManagedCategoryItem
import com.autologue.app.data.preferences.UserClassificationItem
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.presentation.common.*
import com.autologue.app.presentation.theme.*

/**
 * 사용자 분류 배지용 8가지 고대비 색상 팔레트 (글자색 to 배경색)
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
        Color(0xFFA21CAF) to Color(0xFFFAE8FF)  // 7: 푸시아 핑크
    )
    val idx = (colorIndex % palettes.size + palettes.size) % palettes.size
    return palettes[idx]
}

/**
 * 5) 등록된 카테고리들 관리 다이얼로그 (추가, 삭제, 수정)
 */
@Composable
fun CategoryManageDialog(
    managedCategories: List<ManagedCategoryItem>,
    onDismiss: () -> Unit,
    onAddCategory: (String) -> Unit,
    onUpdateCategoryName: (ManagedCategoryItem, String) -> Unit,
    onDeleteCategory: (ManagedCategoryItem) -> Unit,
    onRestoreDefaults: () -> Unit
) {
    var newCategoryName by remember { mutableStateOf("") }
    var editingItemId by remember { mutableStateOf<String?>(null) }
    var editingNameText by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("카테고리 관리 (추가·수정·삭제)", style = AppTypography.h2)
                TextButton(
                    onClick = onRestoreDefaults,
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Icon(Icons.Default.Refresh, contentDescription = null, modifier = Modifier.size(14.dp), tint = Slate600)
                    Spacer(modifier = Modifier.width(4.dp))
                    Text("기본값 복원", fontSize = 11.sp, color = Slate600)
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
                    text = "새 카테고리를 영구 추가하거나 기존 카테고리 이름을 수정·삭제할 수 있습니다.",
                    style = AppTypography.captionMuted
                )

                // 새 카테고리 추가 영역
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    OutlinedTextField(
                        value = newCategoryName,
                        onValueChange = { newCategoryName = it },
                        placeholder = { Text("새 카테고리 이름 입력 (예: 교육/육아, 반려동물)", fontSize = 12.sp) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    Button(
                        onClick = {
                            val trimmed = newCategoryName.trim()
                            if (trimmed.isNotBlank()) {
                                onAddCategory(trimmed)
                                newCategoryName = ""
                            }
                        },
                        enabled = newCategoryName.isNotBlank(),
                        colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary),
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp)
                    ) {
                        Icon(Icons.Default.Add, contentDescription = "추가", modifier = Modifier.size(16.dp), tint = PureWhite)
                        Spacer(modifier = Modifier.width(2.dp))
                        Text("추가", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                    }
                }

                HairlineDivider()

                Text(
                    text = "등록된 카테고리 목록 (${managedCategories.size}개)",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate700)
                )

                managedCategories.forEach { item ->
                    val isEditing = editingItemId == item.id
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = if (item.isBuiltIn) Slate50 else Color(0xFFEFF6FF),
                        border = BorderStroke(1.dp, if (item.isBuiltIn) Slate200 else Color(0xFFBFDBFE)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                                    modifier = Modifier.weight(1f)
                                ) {
                                    Text(
                                        text = item.displayName,
                                        style = AppTypography.body.copy(fontWeight = FontWeight.SemiBold, color = Slate900)
                                    )
                                    MetricBadge(
                                        text = if (item.isBuiltIn) "기본" else "추가됨",
                                        textColor = if (item.isBuiltIn) Slate600 else Color(0xFF1D4ED8),
                                        backgroundColor = if (item.isBuiltIn) Slate200 else Color(0xFFDBEAFE)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(
                                        onClick = {
                                            editingItemId = item.id
                                            editingNameText = item.displayName
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "수정", tint = Slate600, modifier = Modifier.size(16.dp))
                                    }
                                    if (!(item.isBuiltIn && item.baseCategory == ExpenseCategory.ETC)) {
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
                    }
                }
            }
        },
        confirmButton = {
            AutoLoguePrimaryButton(text = "완료", onClick = onDismiss)
        },
        containerColor = AppColors.surface,
        shape = AppShapes.modal
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
    var newUserName by remember { mutableStateOf("") }
    var selectedColorIndex by remember { mutableStateOf(userClassifications.size % ExpenseCustomMetaPreferences.BADGE_COLOR_COUNT) }
    var editingOriginalName by remember { mutableStateOf<String?>(null) }
    var editingNameText by remember { mutableStateOf("") }
    var editingColorIndex by remember { mutableStateOf(0) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("사용자 분류 관리 (추가·수정·삭제)", style = AppTypography.h2)
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
                    shape = RoundedCornerShape(10.dp),
                    color = Slate50,
                    border = BorderStroke(1.dp, Slate200),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text("새 사용자 분류 추가", style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate800))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            OutlinedTextField(
                                value = newUserName,
                                onValueChange = { newUserName = it },
                                placeholder = { Text("사용자명 직접 입력 (예: 정선우, 배우자, 법인)", fontSize = 12.sp) },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
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
                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("추가", fontSize = 12.sp, color = PureWhite, fontWeight = FontWeight.Bold)
                            }
                        }

                        // 배지 색상 선택 팔레트
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("배지 색상:", fontSize = 11.sp, color = Slate600)
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

                HairlineDivider()

                Text(
                    text = "등록된 사용자 분류 (${userClassifications.size}개)",
                    style = AppTypography.caption.copy(fontWeight = FontWeight.Bold, color = Slate700)
                )

                userClassifications.forEach { item ->
                    val isEditing = editingOriginalName == item.name
                    val (badgeFg, badgeBg) = getUserClassificationBadgeColors(item.colorIndex)

                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = PureWhite,
                        border = BorderStroke(1.dp, Slate200),
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
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                                ) {
                                    Text("색상 변경:", fontSize = 11.sp, color = Slate600)
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
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 12.dp, vertical = 8.dp),
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
                                        text = "배지 미리보기",
                                        style = AppTypography.captionMuted.copy(fontSize = 11.sp)
                                    )
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                                    IconButton(
                                        onClick = {
                                            editingOriginalName = item.name
                                            editingNameText = item.name
                                            editingColorIndex = item.colorIndex
                                        },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Edit, contentDescription = "수정", tint = Slate600, modifier = Modifier.size(16.dp))
                                    }
                                    IconButton(
                                        onClick = { onDeleteUserClassification(item.name) },
                                        modifier = Modifier.size(30.dp)
                                    ) {
                                        Icon(Icons.Default.Delete, contentDescription = "삭제", tint = Color(0xFFDC2626), modifier = Modifier.size(16.dp))
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
        containerColor = AppColors.surface,
        shape = AppShapes.modal
    )
}
