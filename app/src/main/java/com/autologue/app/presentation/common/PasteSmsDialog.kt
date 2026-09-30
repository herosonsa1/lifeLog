package com.autologue.app.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ContentPaste
import androidx.compose.material.icons.filled.LocalGasStation
import androidx.compose.material.icons.filled.Payment
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.autologue.app.data.parser.SmsParser
import com.autologue.app.domain.model.ExpenseCategory
import com.autologue.app.domain.model.Transaction
import com.autologue.app.presentation.theme.*
import java.time.format.DateTimeFormatter

@Composable
fun PasteSmsDialog(
    initialText: String = "",
    onDismiss: () -> Unit,
    onConfirm: (List<Transaction>) -> Unit
) {
    var rawText by remember { mutableStateOf(initialText) }
    val clipboardManager = LocalClipboardManager.current

    val parsedTransactions = remember(rawText) {
        if (rawText.isBlank()) emptyList()
        else SmsParser.parseMultiple(rawText)
    }

    val fuelCount = parsedTransactions.count { it.category == ExpenseCategory.FUEL }
    val normalCount = parsedTransactions.size - fuelCount
    val totalAmount = parsedTransactions.sumOf { it.amount }

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
                .imePadding()
                .systemBarsPadding(),
            contentAlignment = Alignment.Center
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = PureWhite,
                tonalElevation = 6.dp,
                shadowElevation = 10.dp,
                modifier = Modifier
                    .fillMaxWidth(0.94f)
                    .heightIn(max = 680.dp)
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp)
                ) {
                    // 1. 헤더
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(Indigo50),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.ContentPaste,
                                    contentDescription = null,
                                    tint = Indigo600,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(10.dp))
                            Column {
                                Text(
                                    text = "결제 문자 간편 등록",
                                    style = AppTypography.h3.copy(fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Slate900)
                                )
                                Text(
                                    text = "RCS(채팅+) 및 카드 결제 문자 일괄 파싱",
                                    style = AppTypography.caption.copy(color = Slate500, fontSize = 11.sp)
                                )
                            }
                        }
                        IconButton(
                            onClick = onDismiss,
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "닫기",
                                tint = Slate400
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // 2. 클립보드 붙여넣기 퀵 액션 바
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "문자 내용 입력",
                            style = AppTypography.caption.copy(fontWeight = FontWeight.SemiBold, color = Slate700)
                        )
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(Blue50)
                                .clickable {
                                    val clip = clipboardManager.getText()?.text
                                    if (!clip.isNullOrBlank()) {
                                        rawText = clip
                                    }
                                }
                                .padding(horizontal = 10.dp, vertical = 5.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.ContentPaste,
                                contentDescription = null,
                                tint = Blue600,
                                modifier = Modifier.size(13.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "클립보드에서 가져오기",
                                style = AppTypography.caption.copy(
                                    color = Blue700,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 11.sp
                                )
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))

                    // 3. 문자 텍스트 입력창
                    OutlinedTextField(
                        value = rawText,
                        onValueChange = { rawText = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(120.dp),
                        placeholder = {
                            Text(
                                text = "문자 앱에서 복사한 카드 결제 문자를 여기에 붙여넣으세요.\n(여러 건을 한꺼번에 붙여넣어도 자동으로 분리·인식됩니다)",
                                style = AppTypography.caption.copy(color = Slate400, fontSize = 12.sp)
                            )
                        },
                        textStyle = AppTypography.body.copy(fontSize = 12.sp, color = Slate800),
                        shape = RoundedCornerShape(10.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Blue500,
                            unfocusedBorderColor = Slate200,
                            focusedContainerColor = Slate50,
                            unfocusedContainerColor = Slate50
                        )
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // 4. 실시간 파싱 분석 결과
                    if (rawText.isNotBlank()) {
                        if (parsedTransactions.isEmpty()) {
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Amber50,
                                border = BorderStroke(1.dp, Amber200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = "⚠️ 인식된 카드 결제 문자가 없습니다.\n카드사 승인 문자(금액, 상호명 등)가 올바른지 확인해주세요.",
                                    style = AppTypography.caption.copy(color = Amber800, fontSize = 11.5.sp),
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        } else {
                            // 분석 요약 배너
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = Emerald50,
                                border = BorderStroke(1.dp, Emerald200),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Icon(
                                            imageVector = Icons.Default.CheckCircle,
                                            contentDescription = null,
                                            tint = Emerald600,
                                            modifier = Modifier.size(16.dp)
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "총 ${parsedTransactions.size}건 감지 (주유 ${fuelCount}건, 일반 ${normalCount}건)",
                                            style = AppTypography.caption.copy(
                                                color = Emerald900,
                                                fontWeight = FontWeight.Bold,
                                                fontSize = 12.sp
                                            )
                                        )
                                    }
                                    Text(
                                        text = "합계 %,d원".format(totalAmount),
                                        style = AppTypography.caption.copy(
                                            color = Emerald800,
                                            fontWeight = FontWeight.ExtraBold,
                                            fontSize = 12.sp
                                        )
                                    )
                                }
                            }

                            Spacer(modifier = Modifier.height(8.dp))

                            // 파싱된 거래 목록 스크롤 뷰
                            LazyColumn(
                                modifier = Modifier
                                    .weight(1f)
                                    .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                items(parsedTransactions) { tx ->
                                    ParsedTransactionItemRow(tx)
                                }
                            }
                        }
                    } else {
                        // 입력 전 힌트 배너
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = Slate100,
                            modifier = Modifier
                                .weight(1f)
                                .fillMaxWidth()
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .padding(16.dp),
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Payment,
                                    contentDescription = null,
                                    tint = Slate400,
                                    modifier = Modifier.size(32.dp)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "갤럭시 '채팅+(RCS)' 메시지도 완벽 지원합니다.",
                                    style = AppTypography.caption.copy(
                                        color = Slate700,
                                        fontWeight = FontWeight.SemiBold,
                                        fontSize = 12.sp
                                    )
                                )
                                Text(
                                    text = "문자 앱에서 결제 문자를 복사한 뒤 상단의\n[클립보드에서 가져오기]를 눌러보세요.",
                                    style = AppTypography.caption.copy(
                                        color = Slate500,
                                        fontSize = 11.sp,
                                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                                    )
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // 5. 하단 버튼 액션
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.End,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextButton(
                            onClick = onDismiss,
                            colors = ButtonDefaults.textButtonColors(contentColor = Slate600)
                        ) {
                            Text("취소", style = AppTypography.h3.copy(fontSize = 13.sp))
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                if (parsedTransactions.isNotEmpty()) {
                                    onConfirm(parsedTransactions)
                                }
                            },
                            enabled = parsedTransactions.isNotEmpty(),
                            shape = RoundedCornerShape(8.dp),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Blue600,
                                contentColor = PureWhite,
                                disabledContainerColor = Slate200,
                                disabledContentColor = Slate400
                            )
                        ) {
                            Text(
                                text = if (parsedTransactions.isEmpty()) "등록할 내역 없음"
                                else "가계부 및 차계부에 등록 (${parsedTransactions.size}건)",
                                style = AppTypography.h3.copy(fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ParsedTransactionItemRow(tx: Transaction) {
    val isFuel = tx.category == ExpenseCategory.FUEL
    val isCancel = tx.amount < 0 || tx.transferMemo?.contains("승인취소") == true
    val dtStr = tx.timestamp.format(DateTimeFormatter.ofPattern("M/d HH:mm"))

    Surface(
        shape = RoundedCornerShape(8.dp),
        color = if (isFuel) Amber50.copy(alpha = 0.5f) else Slate50,
        border = BorderStroke(1.dp, if (isFuel) Amber200 else Slate200),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (isFuel) {
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(4.dp))
                                .background(Amber100)
                                .padding(horizontal = 5.dp, vertical = 2.dp)
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.LocalGasStation,
                                    contentDescription = null,
                                    tint = Amber800,
                                    modifier = Modifier.size(11.dp)
                                )
                                Spacer(modifier = Modifier.width(2.dp))
                                Text(
                                    text = "주유",
                                    style = AppTypography.caption.copy(
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = Amber900
                                    )
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                    Text(
                        text = tx.merchantName,
                        style = AppTypography.body.copy(
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = Slate900
                        ),
                        maxLines = 1
                    )
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = "${tx.cardOrBankName ?: "카드"} · $dtStr" + (if (isCancel) " · [승인취소]" else ""),
                    style = AppTypography.caption.copy(fontSize = 11.sp, color = if (isCancel) Red600 else Slate500)
                )
            }

            Text(
                text = (if (tx.amount > 0) "%,d원" else "-%,d원").format(Math.abs(tx.amount)),
                style = AppTypography.body.copy(
                    fontSize = 14.sp,
                    fontWeight = FontWeight.ExtraBold,
                    color = when {
                        isCancel -> Red600
                        isFuel -> Amber800
                        else -> Slate900
                    }
                )
            )
        }
    }
}
