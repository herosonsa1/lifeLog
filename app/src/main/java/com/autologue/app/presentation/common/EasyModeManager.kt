package com.autologue.app.presentation.common

import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.HapticFeedbackConstants
import android.widget.Toast
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 전역 화면 모드 매니저
 * - 기본값(Default): 토스(Toss TDS) · 애플(Apple HIG) 스타일의 '쉬운 모드' (isEasyMode = true)
 * - '고급모드' 전환 시(isEasyMode = false): 기존의 상세 제어 UI/UX 100% 그대로 이용
 * - SharedPreferences("autologue_ui_mode_prefs")에 영구 저장되어 앱 재실행 시에도 유지됩니다.
 */
object EasyModeManager {
    private const val PREF_NAME = "autologue_ui_mode_prefs"
    private const val KEY_EASY_MODE = "is_easy_mode_default_v2"

    @Volatile
    private var initialized = false

    private val _isEasyMode = MutableStateFlow(true)
    val isEasyMode: StateFlow<Boolean> = _isEasyMode.asStateFlow()

    private val _isSettingsSheetOpen = MutableStateFlow(false)
    val isSettingsSheetOpen: StateFlow<Boolean> = _isSettingsSheetOpen.asStateFlow()

    fun ensureInitialized(context: Context) {
        if (!initialized) {
            synchronized(this) {
                if (!initialized) {
                    val prefs = context.applicationContext.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
                    _isEasyMode.value = prefs.getBoolean(KEY_EASY_MODE, true)
                    initialized = true
                }
            }
        }
    }

    fun setEasyMode(context: Context, enabled: Boolean) {
        ensureInitialized(context)
        _isEasyMode.value = enabled
        context.applicationContext
            .getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_EASY_MODE, enabled)
            .apply()
    }

    fun setAdvancedMode(context: Context, advancedEnabled: Boolean) {
        setEasyMode(context, !advancedEnabled)
    }

    fun toggleEasyMode(context: Context): Boolean {
        ensureInitialized(context)
        val next = !_isEasyMode.value
        setEasyMode(context, next)
        return next
    }

    fun openSettingsSheet() {
        _isSettingsSheetOpen.value = true
    }

    fun closeSettingsSheet() {
        _isSettingsSheetOpen.value = false
    }
}

val LocalEasyMode = compositionLocalOf { true }

/**
 * 토스(Toss TDS) · 애플(Apple HIG) 스타일의 고명도 미니멀 컬러 토큰
 */
object EasyTheme {
    val bg = Color(0xFFF2F4F6)             // 토스 시그니처 그레이 배경
    val surface = Color(0xFFFFFFFF)        // 순백색 섹션 카드
    val surfaceSubtle = Color(0xFFF9FAFB)  // 서브 그룹 배경
    val chipBg = Color(0xFFF2F4F6)         // 기본 칩/세그먼트 트랙 배경
    val borderSubtle = Color(0xFFE5E8EB)   // 미세 구분선

    val textPrimary = Color(0xFF191F28)    // 딥 잉크 본문/숫자 (Toss Gray 900)
    val textSecondary = Color(0xFF4E5968)  // 보조 설명 텍스트 (Toss Gray 700)
    val textMuted = Color(0xFF8B95A1)      // 캡션/시간 텍스트 (Toss Gray 500)
    val divider = Color(0xFFF2F4F6)        // 리스트 내부 헤어라인

    val tossBlue = Color(0xFF3182F6)       // 토스 프라이머리 블루
    val tossBlueSoft = Color(0xFFE8F3FF)   // 토스 블루 소프트 틴트
    val appleGreen = Color(0xFF0CA678)     // 성공 / 수입 / 양호 그린
    val appleGreenSoft = Color(0xFFE6FCF5)
    val appleRed = Color(0xFFF04452)       // 경고 / 초과 / 삭제 레드
    val appleRedSoft = Color(0xFFFFF0F1)
    val appleAmber = Color(0xFFD97706)     // 주유 / 법인 앰버
    val appleAmberSoft = Color(0xFFFEF3C7)
    val applePurple = Color(0xFF6741D9)    // 라이프 / 다이어리 퍼플
    val applePurpleSoft = Color(0xFFF3F0FF)

    // PascalCase & camelCase 별칭 (화면·팝업별 쉬운 모드 컴포넌트 호환성 보장)
    val CanvasBg = bg
    val CardBg = surface
    val SubtleBoxBg = surfaceSubtle
    val DividerColor = borderSubtle
    val PositiveGreen = appleGreen
    val PositiveGreenBg = appleGreenSoft
    val NegativeRed = appleRed
    val NegativeRedBg = appleRedSoft
    val WarningAmber = appleAmber
    val WarningAmberBg = appleAmberSoft
    val AppleIndigo = applePurple

    val blue = tossBlue
    val blueSoft = tossBlueSoft
    val blueBg = tossBlueSoft
    val green = appleGreen
    val greenSoft = appleGreenSoft
    val red = appleRed
    val redSoft = appleRedSoft
    val coral = appleRed
    val accent = tossBlue
    val accentSoft = tossBlueSoft
}

/**
 * 표준 모드 상단 바 우측에 부착되는 '쉬운 모드 전환 / 설정' 트리거 버튼
 */
@Composable
fun StandardTopBarSettingsTrigger(
    accentColor: Color = EasyTheme.tossBlue,
    containerColor: Color = EasyTheme.tossBlueSoft,
    borderColor: Color = EasyTheme.tossBlue.copy(alpha = 0.35f),
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isEasy by EasyModeManager.isEasyMode.collectAsState()

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        Surface(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                val enabled = EasyModeManager.toggleEasyMode(context)
                Toast.makeText(
                    context,
                    if (enabled) "기본(쉬운) 모드로 전환되었습니다." else "고급모드(기존 UI/UX)로 전환되었습니다.",
                    Toast.LENGTH_SHORT
                ).show()
            },
            shape = RoundedCornerShape(16.dp),
            color = if (isEasy) EasyTheme.tossBlue else EasyTheme.tossBlueSoft,
            border = BorderStroke(1.dp, EasyTheme.tossBlue.copy(alpha = 0.35f))
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(3.dp)
            ) {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = "기본 쉬운 모드로 전환",
                    tint = if (isEasy) Color.White else EasyTheme.tossBlue,
                    modifier = Modifier.size(13.dp)
                )
                Text(
                    text = if (isEasy) "쉬운모드" else "쉬운모드로",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (isEasy) Color.White else EasyTheme.tossBlue,
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        IconButton(
            onClick = {
                view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                EasyModeManager.openSettingsSheet()
            },
            modifier = Modifier.size(30.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "앱 설정 및 모드 전환",
                tint = Color(0xFF4E5968),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

/**
 * 쉬운 모드 전용 클린 상단 헤더 (Toss · Apple Large Title Header)
 */
@Composable
fun EasyModeTopBar(
    title: String,
    subtitle: String? = null,
    accentColor: Color = EasyTheme.tossBlue,
    onOpenSettings: (() -> Unit)? = null,
    onQuickSwitchToStandard: (() -> Unit)? = null,
    rightExtraAction: (@Composable RowScope.() -> Unit)? = null
) {
    val context = LocalContext.current
    val view = LocalView.current

    Surface(
        color = EasyTheme.bg,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(start = 20.dp, end = 16.dp, top = 12.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = title,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = (-0.5).sp,
                    color = EasyTheme.textPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (!subtitle.isNullOrBlank()) {
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = subtitle,
                        fontSize = 12.5.sp,
                        fontWeight = FontWeight.Medium,
                        color = EasyTheme.textMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                if (rightExtraAction != null) {
                    rightExtraAction()
                }

                // 고급모드(기존 상세 UI/UX)로 즉시 전환하는 원터치 캡슐
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        EasyModeManager.setEasyMode(context, false)
                        Toast.makeText(context, "고급모드(기존 UI/UX)로 전환되었습니다.", Toast.LENGTH_SHORT).show()
                    },
                    shape = RoundedCornerShape(18.dp),
                    color = EasyTheme.surface,
                    border = BorderStroke(1.dp, EasyTheme.borderSubtle)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Tune,
                            contentDescription = "고급모드로 전환",
                            tint = EasyTheme.textSecondary,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "고급모드",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = EasyTheme.textSecondary,
                            maxLines = 1,
                            softWrap = false
                        )
                    }
                }

                // 설정 시트 열기 버튼
                Surface(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
                        EasyModeManager.openSettingsSheet()
                    },
                    shape = CircleShape,
                    color = EasyTheme.surface,
                    border = BorderStroke(1.dp, EasyTheme.borderSubtle),
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "설정 열기",
                            tint = EasyTheme.textSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }
        }
    }
}

/**
 * 토스·애플 스타일 순백색 라운드 섹션 카드 (중첩 보더 배제, 여백 중심)
 */
@Composable
fun EasySectionCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    containerColor: Color = EasyTheme.surface,
    cornerRadius: Dp = 22.dp,
    contentPadding: PaddingValues = PaddingValues(horizontal = 20.dp, vertical = 18.dp),
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        shape = RoundedCornerShape(cornerRadius),
        color = containerColor,
        modifier = modifier
            .fillMaxWidth()
            .then(
                if (onClick != null) {
                    Modifier.clip(RoundedCornerShape(cornerRadius)).clickable { onClick() }
                } else {
                    Modifier
                }
            )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(contentPadding),
            content = content
        )
    }
}

/**
 * iOS / Toss 스타일 세그먼트 컨트롤
 */
@Composable
fun <T> EasySegmentedBar(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier
) {
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = Color(0xFFE5E8EB),
        modifier = modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(3.dp),
            horizontalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            options.forEach { (value, label) ->
                val isSelected = value == selected
                val bgColor by animateColorAsState(
                    targetValue = if (isSelected) EasyTheme.surface else Color.Transparent,
                    animationSpec = tween(160),
                    label = "segBg"
                )
                Surface(
                    onClick = { onSelect(value) },
                    shape = RoundedCornerShape(11.dp),
                    color = bgColor,
                    modifier = Modifier.weight(1f)
                ) {
                    Box(
                        modifier = Modifier.padding(vertical = 9.dp, horizontal = 4.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            fontSize = 13.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                            color = if (isSelected) EasyTheme.textPrimary else EasyTheme.textSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EasySegmentedBar(
    options: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    EasySegmentedBar(
        options = options.mapIndexed { idx, label -> idx to label },
        selected = selectedIndex,
        onSelect = onSelect,
        modifier = modifier
    )
}

/**
 * 토스 스타일 원탭 소프트 액션 버튼 (최소 높이 46dp 보장)
 */
@Composable
fun EasyActionButton(
    text: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isPrimary: Boolean = false,
    accentColor: Color = EasyTheme.tossBlue,
    containerColor: Color = if (isPrimary) accentColor else EasyTheme.chipBg,
    contentColor: Color = if (isPrimary) Color.White else EasyTheme.textPrimary
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(14.dp),
        color = containerColor,
        modifier = modifier.heightIn(min = 46.dp)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (isPrimary) Color.White else accentColor,
                modifier = Modifier.size(17.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = text,
                fontSize = 13.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = contentColor,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

/**
 * 토스·애플 스타일 날짜/기간 선택 캡슐
 * - 좌측 날짜 네비게이터에 weight(1f, fill = false)를 부여하여 우측 액션 버튼(오늘, 불러오기 등)이
 *   항상 먼저 고유 너비를 확보하고 세로 줄넘김 없이 1줄로 깔끔하게 표시되도록 보장합니다.
 */
@Composable
fun EasyDateNavigatorRow(
    title: String,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onTitleClick: (() -> Unit)? = null,
    trailingAction: (@Composable RowScope.() -> Unit)? = null
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Row(
            modifier = Modifier.weight(1f, fill = false),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Surface(
                onClick = onPrevious,
                shape = CircleShape,
                color = EasyTheme.chipBg,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ChevronLeft,
                        contentDescription = "이전",
                        tint = EasyTheme.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }

            Surface(
                shape = RoundedCornerShape(12.dp),
                color = Color.Transparent,
                modifier = Modifier
                    .weight(1f, fill = false)
                    .then(
                        if (onTitleClick != null) {
                            Modifier.clip(RoundedCornerShape(12.dp)).clickable { onTitleClick() }
                        } else Modifier
                    )
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    Text(
                        text = title,
                        fontSize = 16.5.sp,
                        fontWeight = FontWeight.Bold,
                        color = EasyTheme.textPrimary,
                        maxLines = 1,
                        softWrap = false,
                        overflow = TextOverflow.Ellipsis
                    )
                    if (onTitleClick != null) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = "날짜 선택",
                            tint = EasyTheme.textSecondary,
                            modifier = Modifier.size(17.dp)
                        )
                    }
                }
            }

            Surface(
                onClick = onNext,
                shape = CircleShape,
                color = EasyTheme.chipBg,
                modifier = Modifier.size(32.dp)
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        imageVector = Icons.Default.ChevronRight,
                        contentDescription = "다음",
                        tint = EasyTheme.textSecondary,
                        modifier = Modifier.size(19.dp)
                    )
                }
            }
        }

        if (trailingAction != null) {
            Spacer(modifier = Modifier.width(6.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                content = trailingAction
            )
        }
    }
}

/**
 * 전역 설정 모달 바텀시트 ('쉬운 모드 전환' 및 앱 환경설정)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppSettingsBottomSheet(
    isEasyMode: Boolean,
    onSetEasyMode: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val view = LocalView.current
    val isNotifGranted = remember {
        val pkgName = context.packageName
        val flat = Settings.Secure.getString(context.contentResolver, "enabled_notification_listeners")
        flat != null && flat.contains(pkgName)
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = EasyTheme.bg,
        shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
        dragHandle = { BottomSheetDefaults.DragHandle(color = Color(0xFFD1D6DB)) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 36.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // 상단 시트 헤더
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        text = "화면 및 앱 설정",
                        fontSize = 20.sp,
                        fontWeight = FontWeight.ExtraBold,
                        color = EasyTheme.textPrimary
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "나에게 맞는 화면 스타일을 언제든 바꿀 수 있습니다",
                        fontSize = 13.sp,
                        color = EasyTheme.textMuted
                    )
                }
                Surface(
                    onClick = onDismiss,
                    shape = CircleShape,
                    color = EasyTheme.surface,
                    modifier = Modifier.size(34.dp)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "닫기",
                            tint = EasyTheme.textSecondary,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }

            // 1. 핵심 기능: 쉬운 모드 전환 스위치 & 고급모드 비교 선택 카드
            EasySectionCard(
                contentPadding = PaddingValues(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "쉬운 모드 전환",
                                fontSize = 17.sp,
                                fontWeight = FontWeight.Bold,
                                color = EasyTheme.textPrimary
                            )
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = if (isEasyMode) EasyTheme.tossBlueSoft else EasyTheme.chipBg
                            ) {
                                Text(
                                    text = if (isEasyMode) "쉬운 모드 사용 중" else "고급모드 사용 중",
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isEasyMode) EasyTheme.tossBlue else EasyTheme.textSecondary,
                                    modifier = Modifier.padding(horizontal = 7.dp, vertical = 3.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "켜면 메인 화면·통계·모든 상세 팝업까지 토스·애플 감성의 깔끔한 쉬운 모드 UI/UX가 적용되며, 끄면 기존 고급모드 상세 UI/UX로 전환됩니다.",
                            fontSize = 13.sp,
                            color = EasyTheme.textSecondary,
                            lineHeight = 18.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Switch(
                        checked = isEasyMode,
                        onCheckedChange = { easyEnabled ->
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onSetEasyMode(easyEnabled)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = EasyTheme.tossBlue,
                            uncheckedThumbColor = Color.White,
                            uncheckedTrackColor = Color(0xFFD1D6DB),
                            uncheckedBorderColor = Color.Transparent
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                // 모드 직접 선택 2분할 카드 (시각적 미리보기)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // 쉬운 모드 (기본) 선택 카드
                    val easySelected = isEasyMode
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onSetEasyMode(true)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (easySelected) EasyTheme.tossBlueSoft else EasyTheme.surfaceSubtle,
                        border = BorderStroke(
                            width = if (easySelected) 2.dp else 1.dp,
                            color = if (easySelected) EasyTheme.tossBlue else EasyTheme.borderSubtle
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "쉬운 모드 (기본)",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (easySelected) EasyTheme.tossBlue else EasyTheme.textPrimary
                                )
                                Icon(
                                    imageVector = if (easySelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (easySelected) EasyTheme.tossBlue else EasyTheme.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "Toss · Apple 스타일\n메인·통계·팝업 통합\n깔끔한 여백과 큰 글씨",
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = EasyTheme.textSecondary
                            )
                        }
                    }

                    // 고급모드 (기존 상세 UI/UX) 선택 카드
                    val stdSelected = !isEasyMode
                    Surface(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.CONFIRM)
                            onSetEasyMode(false)
                        },
                        shape = RoundedCornerShape(16.dp),
                        color = if (stdSelected) EasyTheme.tossBlueSoft else EasyTheme.surfaceSubtle,
                        border = BorderStroke(
                            width = if (stdSelected) 2.dp else 1.dp,
                            color = if (stdSelected) EasyTheme.tossBlue else EasyTheme.borderSubtle
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "고급모드",
                                    fontSize = 14.5.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = if (stdSelected) EasyTheme.tossBlue else EasyTheme.textPrimary
                                )
                                Icon(
                                    imageVector = if (stdSelected) Icons.Default.CheckCircle else Icons.Default.RadioButtonUnchecked,
                                    contentDescription = null,
                                    tint = if (stdSelected) EasyTheme.tossBlue else EasyTheme.textMuted,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Text(
                                text = "기존 UI/UX 100% 유지\n다중 필터·상세 배지·\n전문가용 관리 툴바",
                                fontSize = 11.5.sp,
                                lineHeight = 16.sp,
                                color = EasyTheme.textSecondary
                            )
                        }
                    }
                }
            }

            // 2. 기능 보존 안내 및 실시간 알림 설정
            EasySectionCard(
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable {
                            runCatching {
                                context.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                            }.onFailure {
                                Toast.makeText(context, "설정 화면을 열 수 없습니다.", Toast.LENGTH_SHORT).show()
                            }
                        },
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "실시간 결제·일상 알림 자동 수집",
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = EasyTheme.textPrimary
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = if (isNotifGranted) "현재 정상 작동 중입니다" else "터치하여 알림 접근 권한을 켜주세요",
                            fontSize = 12.5.sp,
                            color = if (isNotifGranted) EasyTheme.appleGreen else EasyTheme.appleRed
                        )
                    }
                    Surface(
                        shape = RoundedCornerShape(10.dp),
                        color = if (isNotifGranted) EasyTheme.appleGreenSoft else EasyTheme.appleRedSoft
                    ) {
                        Text(
                            text = if (isNotifGranted) "연동됨" else "설정하기",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = if (isNotifGranted) EasyTheme.appleGreen else EasyTheme.appleRed,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
                HorizontalDivider(color = EasyTheme.divider)
                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "쉬운 모드에서도 모든 내역 터치 시 상세 수정, 결제 일시 변경, 영구저장 규칙, 문자 동기화, 스코어카드 스캔, 엑셀 내보내기 기능을 100% 동일하게 사용할 수 있습니다.",
                    fontSize = 12.5.sp,
                    lineHeight = 18.sp,
                    color = EasyTheme.textMuted
                )
            }
        }
    }
}
