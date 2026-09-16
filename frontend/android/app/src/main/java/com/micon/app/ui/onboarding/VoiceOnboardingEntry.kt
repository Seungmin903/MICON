package com.micon.app.ui.onboarding

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.micon.app.ui.common.PrimaryButton

@Composable
fun VoiceOnboardingEntry(
    state: VoiceOnboardingEntryState, onOpen: () -> Unit, onResume: () -> Unit,
    onStartNew: () -> Unit, onDismiss: () -> Unit,
) {
    PrimaryButton(if (state.busy) "측정 기록 확인 중…" else "목소리 분석하러 가기", onOpen,
        Modifier.fillMaxWidth(), height = 66.dp, enabled = !state.busy && !state.navigate)
    if (state.choosing) AlertDialog(
        onDismissRequest = { if (!state.busy) onDismiss() },
        title = { Text("이전 분석을 이어할까요?") },
        text = {
            Column {
                Text("진행하던 분석이 있어요. 새로 시작하면 이번 측정 기록과 녹음은 지워지고 음역부터 다시 측정해요. 저장한 목소리 프로필은 유지돼요.")
                state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = {
            TextButton(onClick = onResume, enabled = !state.busy) { Text("이전 분석 이어하기") }
        },
        dismissButton = {
            TextButton(onClick = onStartNew, enabled = !state.busy) { Text("새로 측정 시작하기") }
        },
    )
    else state.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
}
