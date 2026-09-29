package com.autologue.app.presentation.common

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarData
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.autologue.app.presentation.theme.NanumSquareNeo
import com.autologue.app.presentation.theme.PureWhite
import com.autologue.app.presentation.theme.Slate700
import com.autologue.app.presentation.theme.Slate900

/**
 * 앱 전역에서 고대비 가독성을 보장하는 프리미엄 스낵바/토스트 호스트 컴포넌트.
 *
 * 어두운 배경(Slate900)에 1dp Slate700 아웃라인 테두리를 두르고,
 * 내부 텍스트는 선명한 순백색(PureWhite, SemiBold 14sp)으로 고정하여
 * 다크 모드 및 라이트 모드 어디서든 탁월한 시인성을 보장합니다.
 */
@Composable
fun AppSnackbarHost(
    hostState: SnackbarHostState,
    modifier: Modifier = Modifier
) {
    SnackbarHost(
        hostState = hostState,
        modifier = modifier
    ) { snackbarData: SnackbarData ->
        Snackbar(
            modifier = Modifier
                .padding(horizontal = 16.dp, vertical = 12.dp)
                .border(
                    width = 1.dp,
                    color = Slate700,
                    shape = RoundedCornerShape(12.dp)
                ),
            shape = RoundedCornerShape(12.dp),
            containerColor = Slate900,
            contentColor = PureWhite,
            actionContentColor = Color(0xFFF59E0B) // Amber500
        ) {
            Text(
                text = snackbarData.visuals.message,
                color = PureWhite,
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold,
                fontFamily = NanumSquareNeo,
                lineHeight = 20.sp
            )
        }
    }
}
