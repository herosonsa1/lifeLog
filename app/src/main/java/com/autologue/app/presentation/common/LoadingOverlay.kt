package com.autologue.app.presentation.common

import android.content.res.ColorStateList
import android.widget.ProgressBar
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.compose.ui.zIndex
import com.autologue.app.presentation.theme.AppColors
import com.autologue.app.presentation.theme.PureWhite
import com.autologue.app.presentation.theme.Spacing
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID

data class LoadingEntry(
    val id: String,
    val title: String,
    val message: String,
    val accentColor: Color,
    val immediateOverlay: Boolean = false,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * 앱 전역의 모든 작업(메뉴 이동, 조회, 필터, 저장, 영구저장, 삭제, 동기화, OCR 등)의
 * 데이터 처리 및 UI 화면 갱신 상태를 통합 관리하는 레지스트리.
 * 화면 간 이동 시에도 로딩 타이머가 끊기거나 초기화되지 않고 최전방(Popup + 최고 zIndex)에 유지됩니다.
 */
object GlobalLoadingRegistry {
    private val _entries = MutableStateFlow<Map<String, LoadingEntry>>(emptyMap())
    val entries: StateFlow<Map<String, LoadingEntry>> = _entries.asStateFlow()

    fun upsert(
        id: String,
        title: String,
        message: String,
        accentColor: Color = AppColors.primary,
        immediateOverlay: Boolean = false
    ) {
        _entries.update { current ->
            val existing = current[id]
            current + (id to LoadingEntry(
                id = id,
                title = title.ifBlank { "데이터 처리 및 화면 갱신 중" },
                message = message.ifBlank { "요청하신 작업을 처리하고 화면을 갱신하고 있습니다..." },
                accentColor = accentColor,
                immediateOverlay = immediateOverlay || (existing?.immediateOverlay == true),
                timestamp = existing?.timestamp ?: System.currentTimeMillis()
            ))
        }
    }

    fun remove(id: String) {
        _entries.update { current ->
            if (current.containsKey(id)) current - id else current
        }
    }

    /**
     * 메인 UI 스레드의 Choreographer VSYNC 프레임이 실제로 렌더링 완료될 때까지 대기합니다.
     * ViewModel 코루틴(MonotonicFrameClock 미포함)에서도 안전하게 실제 화면 갱신 완료 시점을 감지합니다.
     */
    suspend fun awaitNextUiFrames(frameCount: Int = 2) {
        withContext(NonCancellable + Dispatchers.Main.immediate) {
            repeat(frameCount.coerceAtLeast(1)) {
                val waited = runCatching {
                    kotlinx.coroutines.suspendCancellableCoroutine<Unit> { cont ->
                        android.view.Choreographer.getInstance().postFrameCallback {
                            if (cont.isActive) cont.resume(Unit) {}
                        }
                    }
                    true
                }.getOrDefault(false)
                if (!waited) {
                    delay(16L)
                }
            }
        }
    }

    /**
     * ViewModel의 모든 비동기 작업(저장, 수정, 삭제, 필터, 조회 등)을 감싸서
     * 데이터 처리 시작부터 UI 화면 갱신 프레임 완료 시점까지 전역 로딩 상태를 유지합니다.
     */
    suspend fun <T> runWithLoading(
        title: String = "데이터 처리 및 화면 갱신 중",
        message: String = "요청하신 데이터를 처리하고 화면을 갱신하고 있습니다...",
        accentColor: Color = AppColors.primary,
        immediateOverlay: Boolean = false,
        block: suspend () -> T
    ): T {
        val taskId = "task_${UUID.randomUUID()}"
        upsert(
            id = taskId,
            title = title,
            message = message,
            accentColor = accentColor,
            immediateOverlay = immediateOverlay
        )
        return try {
            block()
        } finally {
            awaitNextUiFrames(2)
            remove(taskId)
        }
    }

    /**
     * 필터 토글, 보기 모드 전환 등 상태 변경 후 무거운 UI 재구성(Recomposition)이 뒤따르는 작업을 추적합니다.
     */
    fun trackUiUpdate(
        scope: CoroutineScope,
        title: String = "데이터 처리 및 화면 갱신 중",
        message: String = "요청하신 조건으로 화면을 갱신하고 있습니다...",
        accentColor: Color = AppColors.primary,
        immediateOverlay: Boolean = false,
        action: () -> Unit
    ) {
        val taskId = "ui_update_${UUID.randomUUID()}"
        upsert(
            id = taskId,
            title = title,
            message = message,
            accentColor = accentColor,
            immediateOverlay = immediateOverlay
        )
        try {
            action()
        } finally {
            scope.launch(Dispatchers.Main.immediate) {
                awaitNextUiFrames(2)
                remove(taskId)
            }
        }
    }
}

/**
 * 각 화면(Screen) 또는 컴포넌트에서 로컬 로딩 상태를 전역 최전방 로딩 레지스트리에 연결하는 브릿지.
 * 실제 렌더링은 AppNavigation 최상단의 GlobalLoadingOverlayHost에서 단일 최전방 레이어로 수행하여
 * 화면 전환 시 타이머 리셋 현상과 모달/상단바에 가려지는 현상을 원천 방지합니다.
 */
@Composable
fun AutoLogueLoadingOverlay(
    isLoading: Boolean,
    title: String = "데이터 처리 및 화면 갱신 중",
    message: String = "요청하신 데이터를 처리하고 화면을 갱신하고 있습니다...",
    accentColor: Color = AppColors.primary,
    delayMillis: Long = 200L,
    showImmediateTopBar: Boolean = true,
    immediateOverlay: Boolean = false,
    modifier: Modifier = Modifier
) {
    val sourceId = remember { "ui_source_${UUID.randomUUID()}" }

    LaunchedEffect(isLoading, title, message, accentColor, immediateOverlay) {
        if (isLoading) {
            GlobalLoadingRegistry.upsert(
                id = sourceId,
                title = title,
                message = message,
                accentColor = accentColor,
                immediateOverlay = immediateOverlay
            )
        } else {
            // isLoading이 false로 바뀐 직후 새 데이터가 화면(LazyColumn, 차트 등)에 완전히 그려질 때까지 2프레임 대기 후 해제
            runCatching {
                withFrameNanos { }
                withFrameNanos { }
            }
            GlobalLoadingRegistry.remove(sourceId)
        }
    }

    DisposableEffect(sourceId) {
        onDispose {
            GlobalLoadingRegistry.remove(sourceId)
        }
    }
}

/**
 * 앱 전체 최상위(AppNavigation 루트 및 Popup 윈도우 레이어)에 배치되어
 * 모든 화면, 상단바, 하단바, 다이얼로그보다 항상 제일 앞에(Front-most) 로딩 애니메이션을 표시합니다.
 * 내부 스피너는 Android 하드웨어 RenderThread 기반 ProgressBar를 사용하여
 * 메인 스레드가 무거운 화면 갱신(Recomposition)을 수행하는 중에도 멈추지 않고 부드럽게 회전합니다.
 */
@Composable
fun GlobalLoadingOverlayHost(
    delayMillis: Long = 220L
) {
    val entriesMap by GlobalLoadingRegistry.entries.collectAsState()
    val activeEntry = remember(entriesMap) {
        entriesMap.values.maxByOrNull { it.timestamp }
    }
    val isAnyLoading = activeEntry != null

    var showFullOverlay by remember { mutableStateOf(false) }
    var displayedEntry by remember {
        mutableStateOf(
            LoadingEntry(
                id = "default",
                title = "데이터 처리 및 화면 갱신 중",
                message = "요청하신 데이터를 처리하고 화면을 갱신하고 있습니다...",
                accentColor = AppColors.primary
            )
        )
    }

    if (activeEntry != null) {
        displayedEntry = activeEntry
    }

    LaunchedEffect(isAnyLoading, activeEntry?.immediateOverlay, delayMillis) {
        if (isAnyLoading) {
            if (activeEntry?.immediateOverlay == true || delayMillis <= 0L) {
                showFullOverlay = true
            } else {
                // 데이터 처리 + 화면 갱신 합산 시간이 0.5초(500ms)에 도달하기 전에
                // 페이드인이 완료되어 사용자가 멈춤을 느끼지 않도록 220ms 시점에 오버레이 기동
                delay(delayMillis)
                if (GlobalLoadingRegistry.entries.value.isNotEmpty()) {
                    showFullOverlay = true
                }
            }
        } else {
            if (showFullOverlay) {
                // 화면 갱신 프레임이 완전히 안정화되고 깜빡임을 방지하도록 240ms 유지 후 종료
                delay(240L)
            }
            if (GlobalLoadingRegistry.entries.value.isEmpty()) {
                showFullOverlay = false
            }
        }
    }

    // 1. 최상단 즉시 반응 슬림 진행 바 (zIndex 최상위)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .zIndex(99999f)
    ) {
        AnimatedVisibility(
            visible = isAnyLoading,
            enter = fadeIn(animationSpec = tween(90)),
            exit = fadeOut(animationSpec = tween(180)),
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
        ) {
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(3.dp),
                color = displayedEntry.accentColor,
                trackColor = displayedEntry.accentColor.copy(alpha = 0.16f)
            )
        }
    }

    // 2. 화면 제일 앞(Popup 윈도우 레이어)에 표시되는 중앙 로딩 애니메이션 모달
    if (showFullOverlay) {
        Popup(
            alignment = Alignment.Center,
            properties = PopupProperties(
                focusable = false,
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
                clippingEnabled = false
            )
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color(0xFF0F172A).copy(alpha = 0.36f))
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null,
                        enabled = true
                    ) { /* 로딩 및 화면 갱신 중 중복 터치 방지 */ },
                contentAlignment = Alignment.Center
            ) {
                Surface(
                    shape = RoundedCornerShape(16.dp),
                    color = PureWhite,
                    shadowElevation = 10.dp,
                    border = BorderStroke(1.dp, Color(0xFFE2E8F0)),
                    modifier = Modifier
                        .padding(horizontal = Spacing.xxl)
                        .fillMaxWidth(0.84f)
                ) {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.lg, vertical = Spacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        val infiniteTransition = rememberInfiniteTransition(label = "GlobalLoadingPulse")
                        val pulseScale by infiniteTransition.animateFloat(
                            initialValue = 0.94f,
                            targetValue = 1.06f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(650, easing = FastOutSlowInEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "pulseScale"
                        )

                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .graphicsLayer(scaleX = pulseScale, scaleY = pulseScale)
                                .clip(CircleShape)
                                .background(displayedEntry.accentColor.copy(alpha = 0.12f)),
                            contentAlignment = Alignment.Center
                        ) {
                            // 네이티브 하드웨어 가속(RenderThread) ProgressBar:
                            // 메인 UI 스레드가 대용량 화면 레이아웃을 그리는 중에도 멈추지 않고 60fps로 회전합니다.
                            val tintArgb = displayedEntry.accentColor.toArgb()
                            AndroidView(
                                factory = { ctx ->
                                    ProgressBar(ctx).apply {
                                        isIndeterminate = true
                                        indeterminateTintList = ColorStateList.valueOf(tintArgb)
                                    }
                                },
                                update = { bar ->
                                    bar.indeterminateTintList = ColorStateList.valueOf(tintArgb)
                                },
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        Text(
                            text = displayedEntry.title.ifBlank { "데이터 처리 및 화면 갱신 중" },
                            fontSize = 14.5.sp,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF0F172A),
                            textAlign = TextAlign.Center
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Text(
                            text = displayedEntry.message.ifBlank { "요청하신 데이터를 처리하고 화면을 갱신하고 있습니다..." },
                            fontSize = 12.sp,
                            color = Color(0xFF64748B),
                            textAlign = TextAlign.Center,
                            lineHeight = 17.sp
                        )

                        Spacer(modifier = Modifier.height(12.dp))

                        LinearProgressIndicator(
                            modifier = Modifier
                                .fillMaxWidth(0.72f)
                                .height(3.dp)
                                .clip(RoundedCornerShape(1.5.dp)),
                            color = displayedEntry.accentColor,
                            trackColor = displayedEntry.accentColor.copy(alpha = 0.15f)
                        )
                    }
                }
            }
        }
    }
}
