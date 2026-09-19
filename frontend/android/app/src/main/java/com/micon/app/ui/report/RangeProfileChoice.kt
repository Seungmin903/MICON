package com.micon.app.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Purple

@Composable
fun RangeProfileChoiceMessage(measuredCount: Int, paused: Boolean, inputReady: Boolean = false,
    message: String? = null) {
    Text(if (!paused) "녹음 구간 확인 중…" else if (inputReady) "음역 분석 준비 완료" else "측정 자료가 부족해요",
        fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Purple)
    if (paused && !inputReady) Text(message ?: "통과한 음의 녹음 구간이 필요해요. 추가로 측정해 주세요.", color = Grey)
}

@Composable
fun RowScope.RangeProfileChoiceActions(paused: Boolean, onFinish: () -> Unit, onContinue: () -> Unit,
    inputReady: Boolean = false) {
    PrimaryButton("프로필 생성하기", onFinish, Modifier.weight(1f), enabled = paused && inputReady)
    OutlinePurpleButton("추가로 분석하기", { if (paused) onContinue() }, Modifier.weight(1f))
}
