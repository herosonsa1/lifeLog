package com.autologue.app.presentation.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.autologue.app.R

val NanumSquareNeo = FontFamily(
    Font(R.font.nanumsquareneo_alt, FontWeight.Light),
    Font(R.font.nanumsquareneo_brg, FontWeight.Normal),
    Font(R.font.nanumsquareneo_brg, FontWeight.Medium),
    Font(R.font.nanumsquareneo_cbd, FontWeight.SemiBold),
    Font(R.font.nanumsquareneo_cbd, FontWeight.Bold),
    Font(R.font.nanumsquareneo_deb, FontWeight.ExtraBold),
    Font(R.font.nanumsquareneo_ehv, FontWeight.Black)
)

val Typography = Typography(
    displayLarge = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 32.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.5).sp,
        color = Slate900
    ),
    displayMedium = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 26.sp,
        lineHeight = 34.sp,
        letterSpacing = (-0.4).sp,
        color = Slate900
    ),
    displaySmall = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 30.sp,
        letterSpacing = (-0.3).sp,
        color = Slate900
    ),
    headlineLarge = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 22.sp,
        lineHeight = 28.sp,
        letterSpacing = (-0.2).sp,
        color = Slate900
    ),
    headlineMedium = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        lineHeight = 26.sp,
        letterSpacing = (-0.2).sp,
        color = Slate900
    ),
    headlineSmall = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
        color = Slate900
    ),
    titleLarge = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Bold,
        fontSize = 18.sp,
        lineHeight = 24.sp,
        letterSpacing = (-0.2).sp,
        color = Slate900
    ),
    titleMedium = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        letterSpacing = (-0.1).sp,
        color = Slate900
    ),
    titleSmall = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Medium,
        fontSize = 14.sp,
        lineHeight = 20.sp,
        color = Slate800
    ),
    bodyLarge = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Normal,
        fontSize = 15.sp,
        lineHeight = 22.sp,
        color = Slate800
    ),
    bodyMedium = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Normal,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = Slate600
    ),
    bodySmall = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Normal,
        fontSize = 12.sp,
        lineHeight = 16.sp,
        color = Slate500
    ),
    labelLarge = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        color = Slate800
    ),
    labelMedium = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.Medium,
        fontSize = 11.sp,
        lineHeight = 16.sp,
        letterSpacing = 0.2.sp,
        color = Slate600
    ),
    labelSmall = TextStyle(
        fontFamily = NanumSquareNeo,
        fontWeight = FontWeight.SemiBold,
        fontSize = 10.sp,
        lineHeight = 14.sp,
        letterSpacing = 0.5.sp,
        color = Slate500
    )
)
