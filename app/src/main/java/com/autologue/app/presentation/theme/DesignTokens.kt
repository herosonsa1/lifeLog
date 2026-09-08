package com.autologue.app.presentation.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * AutoLogue Spacing Scale (4px / 8px Consistent Grid)
 */
object Spacing {
    val xxs: Dp = 2.dp   // 초미세 요소 간격
    val xs: Dp  = 4.dp   // 아이콘-텍스트 간격
    val sm: Dp  = 8.dp   // 섹션 헤더 상하 간격
    val md: Dp  = 12.dp  // 리스트 아이템 세로 패딩
    val lg: Dp  = 16.dp  // 화면 기본 상하 패딩
    val xl: Dp  = 20.dp  // 화면 좌우 표준 여백
    val xxl: Dp = 24.dp  // 탭 간격, 대형 섹션 간격
    val xxxl: Dp = 32.dp // 주요 블록 간 여백
}

/**
 * AutoLogue Shape Tokens (Clean B2B SaaS Geometric Corners)
 */
object AppShapes {
    val tag = RoundedCornerShape(3.dp)
    val button = RoundedCornerShape(4.dp)
    val card = RoundedCornerShape(4.dp)
    val pill = RoundedCornerShape(6.dp)
    val modal = RoundedCornerShape(6.dp)
}

/**
 * AutoLogue Semantic Color Tokens
 */
object AppColors {
    val background = PureWhite
    val surface = PureWhite
    val surfaceVariant = Slate50
    val primary = Color(0xFF2563EB) // 선명하고 신뢰감 있는 코발트 블루 (이전 검정 탈피)
    val primaryDark = Color(0xFF1D4ED8)
    val secondary = Slate700
    val textPrimary = Slate900
    val textSecondary = Slate500
    val textMuted = Slate400
    val border = Slate200
    val borderInput = Slate300
    
    // Semantic States
    val successText = Forest700
    val successBg = Forest50
    val warningText = Amber700
    val warningBg = Amber50
    val errorText = Rose700
    val errorBg = Rose50
    
    // Button Design System Tokens
    val actionPrimaryBg = Color(0xFF2563EB)
    val actionPrimaryText = PureWhite
    val actionSecondaryBg = Slate100
    val actionSecondaryBorder = Slate300
    val actionSecondaryText = Slate700
    val actionDangerBg = Rose50
    val actionDangerBorder = Rose100
    val actionDangerText = Rose700
}

/**
 * AutoLogue Menu Theme Colors
 * 각 메뉴별 고유 아이덴티티 및 즉각적인 시각적 구분을 위한 4원색 테마 팔레트
 */
object MenuColors {
    // 1. 다이어리 (Diary): 일상, 사진, 타임라인, 지도 - 스마트 로열 블루
    val diary = Color(0xFF2563EB)
    val diaryBg = Color(0xFFEFF6FF)
    val diaryBorder = Color(0xFFBFDBFE)

    // 2. 가계부 (Expense): 금융, 영수증, 지출 내역 - 프리미엄 코랄 로즈
    val expense = Color(0xFFE11D48)
    val expenseBg = Color(0xFFFFF1F2)
    val expenseBorder = Color(0xFFFECDD3)

    // 3. 차계부 (CarLedger): 주유, 운행 기록, 차량 관리 - 웜 앰버 오렌지
    val carLedger = Color(0xFFD97706)
    val carLedgerBg = Color(0xFFFFFBEB)
    val carLedgerBorder = Color(0xFFFDE68A)

    // 4. 골프 (Golf): 필드 잔디, 라운딩, 스코어카드 - 필드 포레스트 그린
    val golf = Color(0xFF15803D)
    val golfBg = Color(0xFFF0FDF4)
    val golfBorder = Color(0xFFBBF7D0)
}

/**
 * AutoLogue Typography Hierarchy (NanumSquare Neo 6-Scale)
 */
object AppTypography {
    val display = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
        color = AppColors.textPrimary
    )

    val h1 = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        color = AppColors.textPrimary
    )

    val h2 = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
        color = AppColors.textPrimary
    )

    val h3 = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = AppColors.textPrimary
    )

    val body = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = AppColors.textPrimary
    )

    val bodySecondary = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = AppColors.textSecondary
    )

    val caption = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        letterSpacing = 0.2.sp,
        color = AppColors.textSecondary
    )

    val captionMuted = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Normal,
        fontSize = 11.sp,
        lineHeight = 15.sp,
        color = AppColors.textMuted
    )
}
