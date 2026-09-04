package com.autologue.app.presentation.common

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.theme.*

@Composable
fun HairlineDivider(
    modifier: Modifier = Modifier,
    color: Color = AppColors.border,
    thickness: Dp = 0.5.dp
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(thickness)
            .background(color)
    )
}

/**
 * 표준 Input 텍스트필드 (상단 라벨 + 기존 톤의 소프트 배경 + 그림자 입체 효과 + 슬레이트 테두리)
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
    trailingIcon: @Composable (() -> Unit)? = null
) {
    Column(modifier = modifier) {
        if (label != null) {
            Text(
                text = label,
                style = AppTypography.caption.copy(color = Slate700, fontWeight = FontWeight.SemiBold),
                modifier = Modifier.padding(bottom = 4.dp, start = 2.dp)
            )
        }
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Slate50,
            shadowElevation = 1.5.dp,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = value,
                onValueChange = onValueChange,
                placeholder = if (placeholder != null) { { Text(placeholder, fontSize = 13.sp, color = Slate400) } } else null,
                singleLine = singleLine,
                maxLines = maxLines,
                minLines = minLines,
                keyboardOptions = keyboardOptions,
                keyboardActions = keyboardActions,
                leadingIcon = leadingIcon,
                trailingIcon = trailingIcon,
                shape = RoundedCornerShape(8.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedContainerColor = Slate50,
                    unfocusedContainerColor = Slate50,
                    disabledContainerColor = Slate100,
                    focusedBorderColor = Color(0xFF2563EB),
                    unfocusedBorderColor = Color.Transparent,
                    focusedTextColor = Slate900,
                    unfocusedTextColor = Slate900,
                    cursorColor = Color(0xFF2563EB)
                ),
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

/**
 * 인라인 컴팩트 입력창 + 슬림 추가 버튼 (그림자 효과 적용)
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
    Row(
        modifier = modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Surface(
            shape = RoundedCornerShape(8.dp),
            color = Slate50,
            shadowElevation = 1.5.dp,
            border = BorderStroke(1.dp, Slate200),
            modifier = Modifier
                .weight(1f)
                .height(38.dp)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.CenterStart
            ) {
                if (value.isEmpty()) {
                    Text(placeholder, fontSize = 12.sp, color = Slate400)
                }
                BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = true,
                    textStyle = AppTypography.body.copy(color = Slate900, fontSize = 13.sp),
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            onClick = onAddClick,
            shape = RoundedCornerShape(8.dp),
            elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.dp, pressedElevation = 0.5.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = Color(0xFF2563EB),
                contentColor = PureWhite
            ),
            contentPadding = PaddingValues(horizontal = 14.dp, vertical = 0.dp),
            modifier = Modifier.height(38.dp)
        ) {
            Text(buttonText, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = PureWhite)
        }
    }
}

/**
 * 서브 액션 버튼 칩 (그림자 입체 효과 + 틴트 배경)
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
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = containerColor,
        shadowElevation = 2.dp,
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(13.dp), tint = contentColor)
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
        }
    }
}

/**
 * 표준 Primary 액션 버튼 (선명한 로열 블루 배경 + 순백 텍스트 + 입체 그림자)
 */
@Composable
fun AutoLoguePrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 2.5.dp, pressedElevation = 0.5.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.actionPrimaryBg,
            contentColor = AppColors.actionPrimaryText,
            disabledContainerColor = Slate100,
            disabledContentColor = Slate400
        ),
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = 10.dp),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(15.dp), tint = AppColors.actionPrimaryText)
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = text,
            style = AppTypography.h3.copy(color = AppColors.actionPrimaryText, fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * 표준 보조 / 취소 버튼 (부드러운 슬레이트 배경 + 테두리 + 그림자 효과)
 */
@Composable
fun AutoLogueSecondaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = null
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp, pressedElevation = 0.5.dp),
        border = BorderStroke(1.dp, AppColors.actionSecondaryBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.actionSecondaryBg,
            contentColor = AppColors.actionSecondaryText,
            disabledContainerColor = Slate50,
            disabledContentColor = Slate300
        ),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 10.dp),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.actionSecondaryText)
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = text,
            style = AppTypography.body.copy(color = AppColors.actionSecondaryText, fontWeight = FontWeight.SemiBold)
        )
    }
}

/**
 * 표준 삭제 / 위험 버튼 (산뜻한 소프트 레드 배경 + 테두리 + 그림자 효과)
 */
@Composable
fun AutoLogueDangerButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: ImageVector? = Icons.Default.Delete
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 1.5.dp, pressedElevation = 0.5.dp),
        border = BorderStroke(1.dp, AppColors.actionDangerBorder),
        colors = ButtonDefaults.buttonColors(
            containerColor = AppColors.actionDangerBg,
            contentColor = AppColors.actionDangerText,
            disabledContainerColor = Slate50,
            disabledContentColor = Slate300
        ),
        contentPadding = PaddingValues(horizontal = Spacing.md, vertical = 10.dp),
        modifier = modifier
    ) {
        if (icon != null) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = AppColors.actionDangerText)
            Spacer(modifier = Modifier.width(Spacing.xs))
        }
        Text(
            text = text,
            style = AppTypography.body.copy(color = AppColors.actionDangerText, fontWeight = FontWeight.Bold)
        )
    }
}

/**
 * 표준 Outlined 보조 버튼 (선명한 파란 틴트 배경 + 테두리 + 그림자)
 */
@Composable
fun AutoLogueOutlinedButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    enabled: Boolean = true
) {
    Surface(
        onClick = { if (enabled) onClick() },
        shape = RoundedCornerShape(8.dp),
        color = if (enabled) Color(0xFFEFF6FF) else Slate50,
        shadowElevation = if (enabled) 1.5.dp else 0.dp,
        border = BorderStroke(1.dp, if (enabled) Color(0xFF93C5FD) else Slate200),
        modifier = modifier.height(34.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (icon != null) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(14.dp), tint = if (enabled) Color(0xFF1D4ED8) else Slate400)
                Spacer(modifier = Modifier.width(4.dp))
            }
            Text(
                text = text,
                style = AppTypography.caption.copy(
                    color = if (enabled) Color(0xFF1D4ED8) else Slate400,
                    fontWeight = FontWeight.Bold
                )
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
            .padding(horizontal = Spacing.xl),
        horizontalArrangement = Arrangement.spacedBy(Spacing.xxl)
    ) {
        tabs.forEach { (tabKey, title) ->
            val isSelected = tabKey == selectedTab
            Column(
                modifier = Modifier
                    .clickable { onTabSelected(tabKey) }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = title,
                    style = AppTypography.body,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) AppColors.primary else AppColors.textMuted
                )
                Spacer(modifier = Modifier.height(6.dp))
                Box(
                    modifier = Modifier
                        .height(2.dp)
                        .width(if (isSelected) 24.dp else 0.dp)
                        .background(if (isSelected) AppColors.primary else Color.Transparent)
                )
            }
        }
    }
}

/**
 * 표준 정밀 텍스트 뱃지 / 태그
 */
@Composable
fun MetricBadge(
    text: String,
    modifier: Modifier = Modifier,
    textColor: Color = AppColors.textSecondary,
    backgroundColor: Color = AppColors.surfaceVariant
) {
    Box(
        modifier = modifier
            .clip(AppShapes.tag)
            .background(backgroundColor)
            .padding(horizontal = 6.dp, vertical = Spacing.xxs)
    ) {
        Text(
            text = text,
            style = AppTypography.caption.copy(
                color = textColor,
                fontWeight = FontWeight.SemiBold
            )
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
