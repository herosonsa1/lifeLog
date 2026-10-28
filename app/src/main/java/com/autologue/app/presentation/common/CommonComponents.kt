package com.autologue.app.presentation.common

import android.os.SystemClock
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerInputChange
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.OffsetMapping
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.input.TransformedText
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.theme.*

private val DURATION_HIGHLIGHT_REGEX = Regex(
    "(?<!\\d)(?:(?:\\d+\\s*시간\\s*\\d+\\s*분|\\d+\\s*시간|\\d+\\s*분)(?!\\s*주기)(?:\\s*(?:운행|소요|이동))?|경유\\s*체류\\s*\\d+\\s*분)"
)

/**
 * 하단 회색 설명(힌트) 문자열 내에서 운행 소요시간('13분 운행', '1시간 15분 운행', '경유 체류 15분' 등)만
 * 선명한 검정색(#111827, SemiBold)으로 강조한 AnnotatedString을 생성합니다.
 */
fun buildHighlightedDurationText(
    text: String,
    highlightColor: Color = Color(0xFF111827)
): AnnotatedString {
    if (text.isEmpty()) return AnnotatedString("")
    val matches = DURATION_HIGHLIGHT_REGEX.findAll(text).toList()
    if (matches.isEmpty()) return AnnotatedString(text)

    return buildAnnotatedString {
        var cursor = 0
        for (match in matches) {
            val start = match.range.first
            val endExclusive = match.range.last + 1
            if (start > cursor) {
                append(text.substring(cursor, start))
            }
            pushStyle(SpanStyle(color = highlightColor, fontWeight = FontWeight.SemiBold))
            append(text.substring(start, endExclusive))
            pop()
            cursor = endExclusive
        }
        if (cursor < text.length) {
            append(text.substring(cursor))
        }
    }
}

@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = AppColors.border,
    thickness: Dp = 0.5.dp
) {
    val isEasyMode = LocalEasyMode.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(if (isEasyMode && color == AppColors.border) EasyTheme.divider else color)
    )
}

/**
 * 최상단 메뉴별 고유 테마 가로 색상 띠 (Accent Bar)
 * 현재 페이지가 어떤 메뉴인지 한눈에 인지할 수 있도록 상단 뷰포트에 2.5dp의 슬림 색상 바를 표시합니다.
 */
@Composable
fun TopMenuAccentBar(
    color: Color,
    modifier: Modifier = Modifier,
    height: Dp = 2.5.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .background(color)
    )
}

/**
 * 상단 컬러 라인(TopMenuAccentBar)과 하단 구분선(HairlineDivider) 사이의 불필요한 여백(기본 TopAppBar 64.dp 강제 높이)을 줄인 컴팩트 상단 바
 */
@Composable
fun AutoLogueCompactTopBar(
    title: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    containerColor: Color = AppColors.background,
    actions: @Composable RowScope.() -> Unit = {}
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .background(containerColor)
            .heightIn(min = 44.dp)
            .padding(start = 16.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp),
            contentAlignment = Alignment.CenterStart
        ) {
            title()
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.End
        ) {
            actions()
        }
    }
}

/**
 * 팝업 모달 상단 우측에서 언제든 '쉬운 모드 ↔ 고급모드'를 즉시 전환할 수 있는 토스·애플 감성 캡슐 칩
 */
@Composable
fun EasyModalModeSwitchChip(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isEasy by EasyModeManager.isEasyMode.collectAsState()

    Surface(
        onClick = {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            val next = EasyModeManager.toggleEasyMode(context)
            Toast.makeText(
                context,
                if (next) "쉬운 모드 UI/UX가 적용되었습니다." else "고급모드(기존 UI/UX)로 전환되었습니다.",
                Toast.LENGTH_SHORT
            ).show()
        },
        shape = RoundedCornerShape(14.dp),
        color = if (isEasy) EasyTheme.tossBlueSoft else Slate100,
        border = if (isEasy) null else BorderStroke(1.dp, Slate200),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Icon(
                imageVector = if (isEasy) Icons.Default.AutoAwesome else Icons.Default.Tune,
                contentDescription = "쉬운 모드 전환",
                tint = if (isEasy) EasyTheme.tossBlue else Slate600,
                modifier = Modifier.size(12.dp)
            )
            Text(
                text = if (isEasy) "쉬운모드" else "고급모드",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = if (isEasy) EasyTheme.tossBlue else Slate700,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

private val SINGLE_LINE_BREAK_REGEX = Regex("""[\r\n\t]+""")
private val MULTI_SPACE_INPUT_REGEX = Regex(""" {2,}""")
private val LEADING_EMOJI_STRIP_REGEX = Regex("""^[\p{So}\p{Cn}\uFE0F\u200D\s]+""")

fun cleanEasyLabelText(raw: String, isEasyMode: Boolean): String {
    if (!isEasyMode) return raw
    val stripped = raw.replace(LEADING_EMOJI_STRIP_REGEX, "").trimStart()
    return if (stripped.isNotEmpty()) stripped else raw
}

private fun normalizeSingleLineFieldText(raw: String): String {
    if (!raw.contains('\n') && !raw.contains('\r') && !raw.contains('\t')) return raw
    return raw.replace(SINGLE_LINE_BREAK_REGEX, " ").replace(MULTI_SPACE_INPUT_REGEX, " ")
}

/**
 * 주어진 문자열 전체(0..length)를 선택 상태로 만든 TextFieldValue를 생성합니다.
 */
fun createSelectAllTextFieldValue(text: String): TextFieldValue {
    return TextFieldValue(
        text = text,
        selection = TextRange(0, text.length),
        composition = null
    )
}

/**
 * selectAllOnTap 활성화 시 탭 직후 발생하는 CoreTextField의 커서 위치 초기화(단일 오프셋 이동)를 방어하고,
 * 실제 키보드 문자 입력(또는 한글 자모 조합 시작)이 감지되면 즉시 가드를 해제하여 정상 입력을 보장합니다.
 *
 * @return Pair(반영할 TextFieldValue, 가드 유지 여부)
 */
fun resolveSelectAllOnTapValueChange(
    previousValue: TextFieldValue,
    incomingValue: TextFieldValue,
    isSelectAllGuardActive: Boolean
): Pair<TextFieldValue, Boolean> {
    val textChanged = incomingValue.text != previousValue.text
    val startedImeComposition = incomingValue.composition != null && previousValue.composition == null
    if (textChanged || startedImeComposition) {
        return incomingValue to false
    }
    if (isSelectAllGuardActive && incomingValue.text.isNotEmpty()) {
        return createSelectAllTextFieldValue(incomingValue.text) to true
    }
    return incomingValue to false
}

/**
 * 표준 Input 텍스트필드 (쉬운 모드: 토스·애플 스타일 무테두리 소프트 그레이 입력 필드 / 고급모드: 기존 슬레이트 테두리 + 그림자)
 * isCompact = true 설정 시 입력필드 높이를 컴팩트하게 줄여 다이얼로그나 그리드 내에서 깔끔하게 표출합니다.
 * selectAllOnTap = true 설정 시 입력란이나 라벨을 클릭할 때마다 기존 문구 전체를 자동 선택하여 바로 새 내용을 덮어쓸 수 있습니다.
 */
@Composable
fun AutoLogueTextField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    label: String? = null,
    placeholder: String? = null,
    singleLine: Boolean = true,
    maxLines: Int = 1,
    minLines: Int = 1,
    keyboardOptions: KeyboardOptions = KeyboardOptions.Default,
    keyboardActions: KeyboardActions = KeyboardActions.Default,
    leadingIcon: @Composable (() -> Unit)? = null,
    trailingIcon: @Composable (() -> Unit)? = null,
    isCompact: Boolean = false,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    selectAllOnTap: Boolean = false
) {
    val isEasyMode = LocalEasyMode.current
    val displayValue = if (singleLine) normalizeSingleLineFieldText(value) else value
    val effectiveKeyboardOptions = if (singleLine && keyboardOptions.imeAction == androidx.compose.ui.text.input.ImeAction.Default) {
        keyboardOptions.copy(imeAction = androidx.compose.ui.text.input.ImeAction.Done)
    } else {
        keyboardOptions
    }
    val effectiveOnValueChange: (String) -> Unit = if (singleLine) {
        { next -> onValueChange(normalizeSingleLineFieldText(next)) }
    } else {
        onValueChange
    }

    val focusRequester = remember { FocusRequester() }
    var textFieldValueState by remember {
        mutableStateOf(
            TextFieldValue(
                text = displayValue,
                selection = TextRange(displayValue.length)
            )
        )
    }
    var lastDispatchedText by remember(displayValue) { mutableStateOf(displayValue) }
    var wasFocused by remember { mutableStateOf(false) }
    var selectAllGuardUntilMs by remember { mutableLongStateOf(0L) }
    var selectAllTriggerToken by remember { mutableIntStateOf(0) }

    if (selectAllOnTap && textFieldValueState.text != displayValue) {
        textFieldValueState = textFieldValueState.copy(
            text = displayValue,
            selection = TextRange(
                textFieldValueState.selection.start.coerceIn(0, displayValue.length),
                textFieldValueState.selection.end.coerceIn(0, displayValue.length)
            )
        )
    }

    val triggerSelectAll: () -> Unit = {
        val currentText = if (selectAllOnTap) textFieldValueState.text else displayValue
        if (selectAllOnTap && currentText.isNotEmpty()) {
            selectAllGuardUntilMs = SystemClock.uptimeMillis() + 240L
            val fullRange = TextRange(0, currentText.length)
            textFieldValueState = if (textFieldValueState.selection == fullRange && textFieldValueState.composition == null) {
                TextFieldValue(
                    text = currentText,
                    selection = TextRange(currentText.length),
                    composition = null
                )
            } else {
                createSelectAllTextFieldValue(currentText)
            }
            selectAllTriggerToken++
        }
    }

    if (selectAllOnTap) {
        LaunchedEffect(selectAllTriggerToken) {
            if (selectAllTriggerToken > 0) {
                withFrameNanos { }
                if (SystemClock.uptimeMillis() <= selectAllGuardUntilMs && textFieldValueState.text.isNotEmpty()) {
                    val full = createSelectAllTextFieldValue(textFieldValueState.text)
                    if (textFieldValueState.selection != full.selection || textFieldValueState.composition != null) {
                        textFieldValueState = full
                    }
                }
                kotlinx.coroutines.delay(45)
                if (SystemClock.uptimeMillis() <= selectAllGuardUntilMs && textFieldValueState.text.isNotEmpty()) {
                    val full = createSelectAllTextFieldValue(textFieldValueState.text)
                    if (textFieldValueState.selection != full.selection || textFieldValueState.composition != null) {
                        textFieldValueState = full
                    }
                }
            }
        }
    }

    val handleTextFieldValueChange: (TextFieldValue) -> Unit = { incoming ->
        val normalizedIncoming = if (singleLine) {
            val cleanText = normalizeSingleLineFieldText(incoming.text)
            if (cleanText != incoming.text) {
                incoming.copy(
                    text = cleanText,
                    selection = TextRange(
                        incoming.selection.start.coerceIn(0, cleanText.length),
                        incoming.selection.end.coerceIn(0, cleanText.length)
                    )
                )
            } else {
                incoming
            }
        } else {
            incoming
        }
        val guardActive = SystemClock.uptimeMillis() <= selectAllGuardUntilMs
        val (resolvedValue, keepGuard) = resolveSelectAllOnTapValueChange(
            previousValue = textFieldValueState,
            incomingValue = normalizedIncoming,
            isSelectAllGuardActive = guardActive
        )
        if (!keepGuard) {
            selectAllGuardUntilMs = 0L
        }
        textFieldValueState = resolvedValue
        if (lastDispatchedText != resolvedValue.text) {
            lastDispatchedText = resolvedValue.text
            effectiveOnValueChange(resolvedValue.text)
        }
    }

    val focusAndSelectModifier = if (selectAllOnTap) {
        Modifier
            .focusRequester(focusRequester)
            .onFocusChanged { focusState ->
                if (!wasFocused && focusState.isFocused) {
                    wasFocused = true
                    triggerSelectAll()
                } else if (!focusState.isFocused) {
                    wasFocused = false
                }
            }
    } else {
        Modifier
    }

    val surfaceTapSelectModifier = if (selectAllOnTap) {
        Modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                val downTime = down.uptimeMillis
                val downPos = down.position
                val touchSlop = viewConfiguration.touchSlop
                val longPressTimeout = viewConfiguration.longPressTimeoutMillis
                var exceededSlop = false
                var upEvent: PointerInputChange? = null

                while (true) {
                    val event = awaitPointerEvent(PointerEventPass.Initial)
                    val change = event.changes.firstOrNull { it.id == down.id } ?: break
                    if ((change.position - downPos).getDistance() > touchSlop) {
                        exceededSlop = true
                        break
                    }
                    if (!change.pressed) {
                        upEvent = change
                        break
                    }
                }

                if (!exceededSlop && upEvent != null && (upEvent.uptimeMillis - downTime) < longPressTimeout) {
                    triggerSelectAll()
                }
            }
        }
    } else {
        Modifier
    }

    Column(modifier = modifier) {
        if (label != null) {
            val cleanedLabel = cleanEasyLabelText(label, isEasyMode)
            val labelInteractionSource = remember { MutableInteractionSource() }
            Text(
                text = cleanedLabel,
                style = if (isEasyMode) {
                    AppTypography.caption.copy(
                        fontSize = if (isCompact) 12.sp else 12.5.sp,
                        color = EasyTheme.textSecondary,
                        fontWeight = FontWeight.SemiBold
                    )
                } else if (isCompact) {
                    AppTypography.caption.copy(fontSize = 11.sp, color = Slate600, fontWeight = FontWeight.SemiBold)
                } else {
                    AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold)
                },
                maxLines = 1,
                modifier = Modifier
                    .padding(bottom = if (isEasyMode) 5.dp else if (isCompact) 2.dp else 4.dp, start = 2.dp)
                    .then(
                        if (selectAllOnTap) {
                            Modifier.clickable(
                                interactionSource = labelInteractionSource,
                                indication = null
                            ) {
                                focusRequester.requestFocus()
                                triggerSelectAll()
                            }
                        } else {
                            Modifier
                        }
                    )
            )
        }
        val fieldShape = RoundedCornerShape(
            if (isEasyMode) (if (isCompact) 11.dp else 14.dp)
            else (if (isCompact) 6.dp else 8.dp)
        )
        Surface(
            shape = fieldShape,
            color = if (isEasyMode) EasyTheme.chipBg else Slate50,
            shadowElevation = if (isEasyMode) 0.dp else if (isCompact) 0.5.dp else 1.5.dp,
            border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
            modifier = Modifier
                .fillMaxWidth()
                .then(surfaceTapSelectModifier)
        ) {
            if (isCompact) {
                if (selectAllOnTap) {
                    BasicTextField(
                        value = textFieldValueState,
                        onValueChange = handleTextFieldValueChange,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        minLines = minLines,
                        keyboardOptions = effectiveKeyboardOptions,
                        keyboardActions = keyboardActions,
                        visualTransformation = visualTransformation,
                        textStyle = AppTypography.body.copy(
                            fontSize = if (isEasyMode) 14.sp else 13.sp,
                            fontWeight = if (isEasyMode) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isEasyMode) EasyTheme.textPrimary else Slate900
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(if (isEasyMode) EasyTheme.tossBlue else Color(0xFF2563EB)),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = if (isEasyMode) 12.dp else 8.dp,
                                        vertical = if (isEasyMode) 9.dp else 6.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (leadingIcon != null) {
                                    leadingIcon()
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (textFieldValueState.text.isEmpty() && placeholder != null) {
                                        Text(
                                            text = placeholder,
                                            fontSize = if (isEasyMode) 13.sp else 12.sp,
                                            color = if (isEasyMode) EasyTheme.textMuted else Slate400,
                                            maxLines = 1
                                        )
                                    }
                                    innerTextField()
                                }
                                if (trailingIcon != null) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    trailingIcon()
                                }
                            }
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(focusAndSelectModifier)
                    )
                } else {
                    BasicTextField(
                        value = displayValue,
                        onValueChange = effectiveOnValueChange,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        minLines = minLines,
                        keyboardOptions = effectiveKeyboardOptions,
                        keyboardActions = keyboardActions,
                        visualTransformation = visualTransformation,
                        textStyle = AppTypography.body.copy(
                            fontSize = if (isEasyMode) 14.sp else 13.sp,
                            fontWeight = if (isEasyMode) FontWeight.SemiBold else FontWeight.Medium,
                            color = if (isEasyMode) EasyTheme.textPrimary else Slate900
                        ),
                        cursorBrush = androidx.compose.ui.graphics.SolidColor(if (isEasyMode) EasyTheme.tossBlue else Color(0xFF2563EB)),
                        decorationBox = { innerTextField ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(
                                        horizontal = if (isEasyMode) 12.dp else 8.dp,
                                        vertical = if (isEasyMode) 9.dp else 6.dp
                                    ),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                if (leadingIcon != null) {
                                    leadingIcon()
                                    Spacer(modifier = Modifier.width(4.dp))
                                }
                                Box(modifier = Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                                    if (displayValue.isEmpty() && placeholder != null) {
                                        Text(
                                            text = placeholder,
                                            fontSize = if (isEasyMode) 13.sp else 12.sp,
                                            color = if (isEasyMode) EasyTheme.textMuted else Slate400,
                                            maxLines = 1
                                        )
                                    }
                                    innerTextField()
                                }
                                if (trailingIcon != null) {
                                    Spacer(modifier = Modifier.width(4.dp))
                                    trailingIcon()
                                }
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                if (selectAllOnTap) {
                    OutlinedTextField(
                        value = textFieldValueState,
                        onValueChange = handleTextFieldValueChange,
                        placeholder = if (placeholder != null) {
                            {
                                Text(
                                    placeholder,
                                    fontSize = if (isEasyMode) 13.5.sp else 13.sp,
                                    color = if (isEasyMode) EasyTheme.textMuted else Slate400
                                )
                            }
                        } else null,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        minLines = minLines,
                        keyboardOptions = effectiveKeyboardOptions,
                        keyboardActions = keyboardActions,
                        leadingIcon = leadingIcon,
                        trailingIcon = trailingIcon,
                        visualTransformation = visualTransformation,
                        shape = fieldShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = if (isEasyMode) EasyTheme.chipBg else Slate50,
                            unfocusedContainerColor = if (isEasyMode) EasyTheme.chipBg else Slate50,
                            disabledContainerColor = if (isEasyMode) EasyTheme.surfaceSubtle else Slate100,
                            focusedBorderColor = if (isEasyMode) EasyTheme.tossBlue else Color(0xFF2563EB),
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                            unfocusedTextColor = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                            cursorColor = if (isEasyMode) EasyTheme.tossBlue else Color(0xFF2563EB)
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .then(focusAndSelectModifier)
                    )
                } else {
                    OutlinedTextField(
                        value = displayValue,
                        onValueChange = effectiveOnValueChange,
                        placeholder = if (placeholder != null) {
                            {
                                Text(
                                    placeholder,
                                    fontSize = if (isEasyMode) 13.5.sp else 13.sp,
                                    color = if (isEasyMode) EasyTheme.textMuted else Slate400
                                )
                            }
                        } else null,
                        singleLine = singleLine,
                        maxLines = maxLines,
                        minLines = minLines,
                        keyboardOptions = effectiveKeyboardOptions,
                        keyboardActions = keyboardActions,
                        leadingIcon = leadingIcon,
                        trailingIcon = trailingIcon,
                        visualTransformation = visualTransformation,
                        shape = fieldShape,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = if (isEasyMode) EasyTheme.chipBg else Slate50,
                            unfocusedContainerColor = if (isEasyMode) EasyTheme.chipBg else Slate50,
                            disabledContainerColor = if (isEasyMode) EasyTheme.surfaceSubtle else Slate100,
                            focusedBorderColor = if (isEasyMode) EasyTheme.tossBlue else Color(0xFF2563EB),
                            unfocusedBorderColor = Color.Transparent,
                            focusedTextColor = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                            unfocusedTextColor = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                            cursorColor = if (isEasyMode) EasyTheme.tossBlue else Color(0xFF2563EB)
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

/**
 * 정수 숫자 문자열에 천단위 콤마(,)를 시각적으로 추가해주는 VisualTransformation
 * (예: "6384" -> "6,384", "1234567" -> "1,234,567")
 */
class NumberCommaVisualTransformation : VisualTransformation {
    override fun filter(text: AnnotatedString): TransformedText {
        val originalText = text.text
        if (originalText.isEmpty()) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val isNegative = originalText.startsWith("-")
        val cleanDigits = if (isNegative) originalText.substring(1) else originalText
        if (!cleanDigits.all { it.isDigit() }) {
            return TransformedText(text, OffsetMapping.Identity)
        }

        val formattedBuilder = StringBuilder()
        val originalToTransformed = IntArray(originalText.length + 1)
        val transformedToOriginal = mutableListOf<Int>()

        val n = cleanDigits.length
        if (isNegative) {
            formattedBuilder.append("-")
            transformedToOriginal.add(0)
        }

        var digitsProcessed = 0
        for (i in cleanDigits.indices) {
            val originalIndex = (if (isNegative) 1 else 0) + i
            originalToTransformed[originalIndex] = formattedBuilder.length
            formattedBuilder.append(cleanDigits[i])
            transformedToOriginal.add(originalIndex)
            digitsProcessed++

            val remaining = n - digitsProcessed
            if (remaining > 0 && remaining % 3 == 0) {
                formattedBuilder.append(',')
                transformedToOriginal.add(originalIndex + 1)
            }
        }
        val transformedLength = formattedBuilder.length
        originalToTransformed[originalText.length] = transformedLength
        transformedToOriginal.add(originalText.length)

        val offsetMapping = object : OffsetMapping {
            override fun originalToTransformed(offset: Int): Int {
                val clamped = offset.coerceIn(0, originalText.length)
                return originalToTransformed[clamped]
            }

            override fun transformedToOriginal(offset: Int): Int {
                val clamped = offset.coerceIn(0, transformedToOriginal.size - 1)
                return transformedToOriginal[clamped]
            }
        }

        return TransformedText(AnnotatedString(formattedBuilder.toString()), offsetMapping)
    }
}

/**
 * 인라인 컴팩트 입력창 + 슬림 추가 버튼 (쉬운 모드: 토스·애플 무테두리 칩 스타일 / 고급모드: 기존 그림자 효과)
 */
@Composable
fun AutoLogueCompactInputRow(
    value: String,
    onValueChange: (String) -> Unit,
    onAddClick: () -> Unit,
    placeholder: String = "이름 입력",
    buttonText: String = "추가",
    modifier: Modifier = Modifier
) {
    val isEasyMode = LocalEasyMode.current
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
            color = if (isEasyMode) EasyTheme.chipBg else Slate50,
            shadowElevation = if (isEasyMode) 0.dp else 1.5.dp,
            border = if (isEasyMode) null else BorderStroke(1.dp, Slate200),
            modifier = Modifier
                .weight(1f)
                .height(if (isEasyMode) 42.dp else 38.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 14.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(
                        placeholder,
                        fontSize = if (isEasyMode) 13.sp else 12.sp,
                        color = if (isEasyMode) EasyTheme.textMuted else Slate400,
                        maxLines = 1
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = AppTypography.body.copy(
                        color = if (isEasyMode) EasyTheme.textPrimary else Slate900,
                        fontSize = if (isEasyMode) 14.sp else 13.sp,
                        fontWeight = if (isEasyMode) FontWeight.SemiBold else FontWeight.Normal
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onAddClick,
            shape = RoundedCornerShape(if (isEasyMode) 12.dp else 8.dp),
            elevation = if (isEasyMode) ButtonDefaults.buttonElevation(0.dp, 0.dp)
                        else ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.5.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (isEasyMode) EasyTheme.tossBlue else Color(0xFF2563EB),
                contentColor = PureWhite
            ),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 0.dp),
            modifier = Modifier.height(if (isEasyMode) 42.dp else 38.dp)
        ) {
            Text(
                cleanEasyLabelText(buttonText, isEasyMode),
                fontSize = if (isEasyMode) 13.sp else 12.sp,
                fontWeight = FontWeight.Bold,
                color = PureWhite,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 서브 액션 버튼 칩 (M3 48dp 강제 여백 없이 정확한 높이 및 가로폭 정렬 보장)
 */
@Composable
fun AutoLogueActionChipButton(
    text: String,
    onClick: () -> Unit,
    icon: ImageVector? = null,
    containerColor: Color = Color(0xFFEFF6FF),
    borderColor: Color = Color(0xFFBFDBFE),
    contentColor: Color = Color(0xFF1D4ED8),
    modifier: Modifier = Modifier
) {
    val isEasyMode = LocalEasyMode.current
    val shape = RoundedCornerShape(if (isEasyMode) 10.dp else 8.dp)
    Box(
        modifier = modifier
            .height(if (isEasyMode) 34.dp else 32.dp)
            .clip(shape)
            .background(containerColor, shape)
            .then(
                if (isEasyMode) Modifier
                else Modifier.border(BorderStroke(1.dp, borderColor), shape)
            )
            .clickable(onClick = onClick)
            .padding(horizontal = if (isEasyMode) 12.dp else 10.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = contentColor)
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = cleanEasyLabelText(text, isEasyMode),
                fontSize = if (isEasyMode) 12.sp else 11.5.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor,
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 표준 Primary 액션 버튼 (쉬운 모드: 토스 블루 플랫 라운드 버튼 / 고급모드: 로열 블루 + 입체 그림자)
 */
@Composable
fun AutoLoguePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val isEasyMode = LocalEasyMode.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
        elevation = if (isEasyMode) ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
                    else ButtonDefaults.buttonElevation(defaultElevation = 2.5.dp, pressedElevation = 0.5.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isEasyMode) EasyTheme.tossBlue else AppColors.actionPrimaryBg,
            contentColor = AppColors.actionPrimaryText,
            disabledContainerColor = if (isEasyMode) EasyTheme.chipBg else Slate100,
            disabledContentColor = if (isEasyMode) EasyTheme.textMuted else Slate400
        ),
        contentPadding = PaddingValues(
            horizontal = if (isEasyMode) 18.dp else Spacing.lg,
            vertical = if (isEasyMode) 12.dp else 10.dp
        ),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = AppColors.actionPrimaryText)
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = cleanEasyLabelText(text, isEasyMode),
            style = AppTypography.h3.copy(
                fontSize = if (isEasyMode) 14.5.sp else 14.sp,
                color = AppColors.actionPrimaryText,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 표준 보조 / 취소 버튼 (쉬운 모드: 토스 소프트 그레이 무테두리 버튼 / 고급모드: 슬레이트 배경 + 테두리 + 그림자)
 */
@Composable
fun AutoLogueSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    val isEasyMode = LocalEasyMode.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
        elevation = if (isEasyMode) ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
                    else ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp, pressedElevation = 0.5.dp),
        border = if (isEasyMode) null else BorderStroke(1.dp, AppColors.actionSecondaryBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isEasyMode) EasyTheme.chipBg else AppColors.actionSecondaryBg,
            contentColor = if (isEasyMode) EasyTheme.textSecondary else AppColors.actionSecondaryText,
            disabledContainerColor = Slate50,
            disabledContentColor = Slate300
        ),
        contentPadding = PaddingValues(
            horizontal = if (isEasyMode) 16.dp else Spacing.md,
            vertical = if (isEasyMode) 12.dp else 10.dp
        ),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isEasyMode) EasyTheme.textSecondary else AppColors.actionSecondaryText
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = cleanEasyLabelText(text, isEasyMode),
            style = AppTypography.body.copy(
                fontSize = if (isEasyMode) 14.sp else 13.sp,
                color = if (isEasyMode) EasyTheme.textSecondary else AppColors.actionSecondaryText,
                fontWeight = FontWeight.SemiBold
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 표준 삭제 / 위험 버튼 (쉬운 모드: 토스·애플 소프트 레드 무테두리 버튼 / 고급모드: 테두리 + 그림자 효과)
 */
@Composable
fun AutoLogueDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = Icons.Default.Delete
) {
    val isEasyMode = LocalEasyMode.current
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(if (isEasyMode) 14.dp else 8.dp),
        elevation = if (isEasyMode) ButtonDefaults.buttonElevation(0.dp, 0.dp, 0.dp)
                    else ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp, pressedElevation = 0.5.dp),
        border = if (isEasyMode) null else BorderStroke(1.dp, AppColors.actionDangerBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = if (isEasyMode) EasyTheme.appleRedSoft else AppColors.actionDangerBg,
            contentColor = if (isEasyMode) EasyTheme.appleRed else AppColors.actionDangerText,
            disabledContainerColor = Slate50,
            disabledContentColor = Slate300
        ),
        contentPadding = PaddingValues(
            horizontal = if (isEasyMode) 16.dp else Spacing.md,
            vertical = if (isEasyMode) 12.dp else 10.dp
        ),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                modifier = Modifier.size(14.dp),
                tint = if (isEasyMode) EasyTheme.appleRed else AppColors.actionDangerText
            )
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = cleanEasyLabelText(text, isEasyMode),
            style = AppTypography.body.copy(
                fontSize = if (isEasyMode) 14.sp else 13.sp,
                color = if (isEasyMode) EasyTheme.appleRed else AppColors.actionDangerText,
                fontWeight = FontWeight.Bold
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

/**
 * 표준 Outlined 보조 버튼 (메뉴 테마 컬러 지원: 32dp 고정 슬림 높이)
 */
@Composable
fun AutoLogueOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true,
    contentColor: Color = Color(0xFF1D4ED8),
    containerColor: Color = Color(0xFFEFF6FF),
    borderColor: Color = Color(0xFF93C5FD)
) {
    val isEasyMode = LocalEasyMode.current
    val shape = RoundedCornerShape(if (isEasyMode) 10.dp else 8.dp)
    Box(
        modifier = modifier
            .height(if (isEasyMode) 34.dp else 32.dp)
            .clip(shape)
            .background(if (enabled) containerColor else Slate50, shape)
            .then(
                if (isEasyMode) Modifier
                else Modifier.border(BorderStroke(1.dp, if (enabled) borderColor else Slate200), shape)
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = if (isEasyMode) 11.dp else 9.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = if (enabled) contentColor else Slate400)
                Spacer(modifier = Modifier.width(3.dp))
            }
            Text(
                text = cleanEasyLabelText(text, isEasyMode),
                style = AppTypography.caption.copy(
                    fontSize = if (isEasyMode) 12.sp else 11.5.sp,
                    color = if (enabled) contentColor else Slate400,
                    fontWeight = FontWeight.Bold
                ),
                maxLines = 1,
                softWrap = false
            )
        }
    }
}

/**
 * 표준 모던 언더라인 탭 바
 */
@Composable
fun <T> UnderlineTabBar(
    tabs: List<Pair<T, String>>,
    selectedTab: T,
    onTabSelected: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { (tabKey, title) ->
            val isSelected = tabKey == selectedTab
            Column(
                modifier = Modifier
                    .weight(1f)
                    .clickable { onTabSelected(tabKey) }
                    .padding(top = 9.dp, start = 4.dp, end = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = AppTypography.body.copy(fontSize = 13.5.sp),
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) AppColors.primary else AppColors.textMuted,
                    maxLines = 1,
                    softWrap = false,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(7.dp))
                Box(
                    modifier = Modifier
                        .height(2.5.dp)
                        .width(if (isSelected) 32.dp else 0.dp)
                        .clip(RoundedCornerShape(topStart = 2.dp, topEnd = 2.dp))
                        .background(if (isSelected) AppColors.primary else Color.Transparent)
                )
            }
        }
    }
}

/**
 * 표준 정밀 텍스트 뱃지 / 태그 (줄바꿈 방지 maxLines = 1, softWrap = false 기본 적용)
 */
@Composable
fun MetricBadge(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = AppColors.textSecondary,
    backgroundColor: Color = AppColors.surfaceVariant
) {
    val isEasyMode = LocalEasyMode.current
    Box(
        modifier = modifier
            .clip(if (isEasyMode) RoundedCornerShape(8.dp) else AppShapes.tag)
            .background(backgroundColor)
            .padding(
                horizontal = if (isEasyMode) 8.dp else 6.dp,
                vertical = if (isEasyMode) 3.dp else Spacing.xxs
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = cleanEasyLabelText(text, isEasyMode),
            style = AppTypography.caption.copy(
                color = textColor,
                fontWeight = FontWeight.SemiBold,
                fontSize = if (isEasyMode) 11.5.sp else 11.sp
            ),
            maxLines = 1,
            softWrap = false
        )
    }
}

@Composable
fun StatusBadge(
    text: String,
    backgroundColor: Color = Slate100,
    textColor: Color = Slate700,
    modifier: Modifier = Modifier
) {
    MetricBadge(
        text = text,
        modifier = modifier,
        textColor = textColor,
        backgroundColor = backgroundColor
    )
}
