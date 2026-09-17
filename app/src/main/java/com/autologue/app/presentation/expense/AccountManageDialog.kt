package com.autologue.app.presentation.expense

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autologue.app.data.preferences.UserAccount
import com.autologue.app.presentation.common.AutoLoguePrimaryButton
import com.autologue.app.presentation.common.AutoLogueSecondaryButton
import com.autologue.app.presentation.common.HairlineDivider
import com.autologue.app.presentation.common.MetricBadge
import com.autologue.app.presentation.theme.*

@Composable
fun AccountManageDialog(
    accounts: List<UserAccount>,
    onDismiss: () -> Unit,
    onAddAccount: (bankName: String, accountNumberPattern: String, alias: String) -> Unit,
    onDeleteAccount: (id: String) -> Unit
) {
    var showAddForm by remember { mutableStateOf(false) }
    var newBank by remember { mutableStateOf("") }
    var newAccountPattern by remember { mutableStateOf("") }
    var newAlias by remember { mutableStateOf("") }

    val quickBanks = listOf("NH농협", "KB국민", "신한", "우리", "하나", "카카오뱅크", "토스뱅크")

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            decorFitsSystemWindows = false,
            usePlatformDefaultWidth = false
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.5f))
                .padding(horizontal = Spacing.md, vertical = Spacing.xl),
            contentAlignment = Alignment.Center
        ) {
            Card(
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = PureWhite),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight(0.85f)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(Spacing.lg)
                ) {
                    // 헤더
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.AccountBalance,
                                contentDescription = null,
                                tint = AppColors.primary,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = "내 계좌 정보 관리",
                                    style = AppTypography.h2.copy(fontWeight = FontWeight.Bold)
                                )
                                Text(
                                    text = "등록된 계좌는 계좌번호 대신 별칭 뱃지로 안전하게 노출됩니다.",
                                    fontSize = 12.sp,
                                    color = Slate500
                                )
                            }
                        }
                        IconButton(onClick = onDismiss, modifier = Modifier.size(32.dp)) {
                            Icon(Icons.Default.Close, contentDescription = "닫기", tint = Slate500)
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.md))
                    HairlineDivider()
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // 계좌 목록
                    LazyColumn(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm)
                    ) {
                        if (accounts.isEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = Spacing.xxl),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("등록된 계좌가 없습니다. 아래 버튼을 눌러 계좌를 추가하세요.", fontSize = 13.sp, color = Slate400)
                                }
                            }
                        } else {
                            items(accounts) { acc ->
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Slate50,
                                    border = BorderStroke(1.dp, Slate200),
                                    modifier = Modifier.fillMaxWidth()
                                ) {
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(12.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Column {
                                            Row(verticalAlignment = Alignment.CenterVertically) {
                                                MetricBadge(
                                                    text = acc.badgeLabel,
                                                    textColor = Indigo700,
                                                    backgroundColor = Indigo50
                                                )
                                                Spacer(modifier = Modifier.width(6.dp))
                                                Text(
                                                    text = acc.alias.ifBlank { "기본 계좌" },
                                                    fontWeight = FontWeight.SemiBold,
                                                    fontSize = 14.sp,
                                                    color = Slate800
                                                )
                                            }
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = " · 계좌패턴: ${acc.accountNumberPattern}",
                                                fontSize = 12.sp,
                                                color = Slate500
                                            )
                                        }

                                        IconButton(
                                            onClick = { onDeleteAccount(acc.id) },
                                            modifier = Modifier.size(32.dp)
                                        ) {
                                            Icon(
                                                imageVector = Icons.Default.Delete,
                                                contentDescription = "삭제",
                                                tint = Color(0xFFEF4444),
                                                modifier = Modifier.size(18.dp)
                                            )
                                        }
                                    }
                                }
                            }
                        }

                        // 새 계좌 추가 입력 폼
                        if (showAddForm) {
                            item {
                                Surface(
                                    shape = RoundedCornerShape(12.dp),
                                    color = Color(0xFFF1F5F9),
                                    border = BorderStroke(1.dp, Color(0xFFCBD5E1)),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = Spacing.xs)
                                ) {
                                    Column(modifier = Modifier.padding(12.dp)) {
                                        Text("새 계좌 등록", fontWeight = FontWeight.Bold, fontSize = 14.sp, color = Slate800)
                                        Spacer(modifier = Modifier.height(8.dp))

                                        // 빠른 은행 선택 칩
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                                        ) {
                                            quickBanks.take(4).forEach { b ->
                                                val isSelected = newBank == b
                                                Surface(
                                                    onClick = { newBank = b },
                                                    shape = RoundedCornerShape(6.dp),
                                                    color = if (isSelected) AppColors.primary else PureWhite,
                                                    border = BorderStroke(1.dp, if (isSelected) AppColors.primary else Slate300)
                                                ) {
                                                    Text(
                                                        text = b,
                                                        fontSize = 11.sp,
                                                        color = if (isSelected) PureWhite else Slate700,
                                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))

                                        OutlinedTextField(
                                            value = newBank,
                                            onValueChange = { newBank = it },
                                            label = { Text("은행명 (예: NH농협, KB국민)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        OutlinedTextField(
                                            value = newAccountPattern,
                                            onValueChange = { newAccountPattern = it },
                                            label = { Text("계좌번호 또는 마스킹 (예: 312-****-9414-21, 0749)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(6.dp))

                                        OutlinedTextField(
                                            value = newAlias,
                                            onValueChange = { newAlias = it },
                                            label = { Text("계좌 별칭 (예: 생활비 통장, 급여 통장)") },
                                            singleLine = true,
                                            modifier = Modifier.fillMaxWidth()
                                        )

                                        Spacer(modifier = Modifier.height(10.dp))

                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.End,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            TextButton(onClick = { showAddForm = false }) {
                                                Text("취소", color = Slate600)
                                            }
                                            Spacer(modifier = Modifier.width(8.dp))
                                            Button(
                                                onClick = {
                                                    if (newBank.isNotBlank() && newAccountPattern.isNotBlank()) {
                                                        onAddAccount(newBank, newAccountPattern, newAlias)
                                                        newBank = ""
                                                        newAccountPattern = ""
                                                        newAlias = ""
                                                        showAddForm = false
                                                    }
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = AppColors.primary)
                                            ) {
                                                Text("추가 완료", color = PureWhite)
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(Spacing.sm))
                    HairlineDivider()
                    Spacer(modifier = Modifier.height(Spacing.sm))

                    // 하단 액션 버튼
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (!showAddForm) {
                            OutlinedButton(
                                onClick = { showAddForm = true },
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, AppColors.primary)
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null, tint = AppColors.primary, modifier = Modifier.size(16.dp))
                                Spacer(modifier = Modifier.width(4.dp))
                                Text("새 계좌 추가", color = AppColors.primary, fontSize = 13.sp)
                            }
                        } else {
                            Spacer(modifier = Modifier.width(1.dp))
                        }

                        Button(
                            onClick = onDismiss,
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Slate800)
                        ) {
                            Text("닫기", color = PureWhite, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}
