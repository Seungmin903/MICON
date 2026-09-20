package com.micon.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.BuildConfig
import com.micon.app.appContainer
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.backend.NetworkAuthRepository
import com.micon.app.data.backend.dto.WithdrawRequest
import com.micon.app.data.local.UserStore
import com.micon.app.data.onboarding.VoiceOnboardingStore
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconField
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconSpace
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val PREFS = "micon-settings"
private const val KEY_NOTATION = "notation"

/** 앱 시작 시 저장된 음 이름 표기를 복원한다 */
fun restoreNotation(context: android.content.Context) {
    val raw = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(KEY_NOTATION, null) ?: return
    runCatching { NoteNames.setNotation(NoteNames.Notation.valueOf(raw)) }
}

/**
 * 설정 — 프로토타입 `settings`. 프로필 카드 · 행 3개(음 이름 표기 · 원본 녹음 저장 · 선호 수정) · 로그아웃.
 * 음 이름 표기(도레미/CDE)는 여기서 바꾸고 앱 전체가 따라 바뀐다(기기 저장).
 */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit = {}, onEditPreferences: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: SettingsViewModel = viewModel(factory = viewModelFactory { initializer {
        val owner = TokenManager.getUserId()
        SettingsViewModel(context.appContainer.settings) {
            if (TokenManager.getUserId() == owner) UserStore.clearVoiceData()
            withContext(Dispatchers.IO) { if (owner > 0) VoiceOnboardingStore(context.applicationContext, owner).clearVoiceData() }
        }
    } })
    val state by vm.state.collectAsStateWithLifecycle()
    val notation by NoteNames.notation.collectAsStateWithLifecycle()
    LaunchedEffect(state.toast) { state.toast?.let { Toast.makeText(context, it, Toast.LENGTH_LONG).show(); vm.consumeToast() } }
    val backend = context.appContainer.backend
    val auth = remember(backend) { NetworkAuthRepository(backend.api) }
    val scope = rememberCoroutineScope()
    var email by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) { email = runCatching { backend.api.currentUser().body()?.data?.email }.getOrNull() }
    var loggingOut by remember { mutableStateOf(false) }
    var showDeleteVoice by remember { mutableStateOf(false) }
    var showWithdraw by remember { mutableStateOf(false) }
    var withdrawing by remember { mutableStateOf(false) }
    var withdrawPassword by remember { mutableStateOf("") }
    var withdrawError by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = loggingOut) { }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    MiconScreen(appBar = { MiconAppBar(title = "설정", onBack = onBack) }, verticalArrangement = Arrangement.spacedBy(MiconSpace.sm)) {
        MiconCard(kind = MiconCardKind.Outlined) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CompactDisc(title = (UserStore.nickname ?: "나").take(2), size = 48.dp)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(UserStore.nickname ?: "내 계정", fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text(email ?: "", style = MaterialTheme.typography.labelMedium, color = muted)
                }
            }
        }
        MiconCard(kind = MiconCardKind.Outlined, contentPadding = 0.dp) {
            SettingRow("음 이름 표기", notation.label) {
                NoteNames.setNotation(if (notation == NoteNames.Notation.SOLFEGE) NoteNames.Notation.LETTERS else NoteNames.Notation.SOLFEGE)
                context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(KEY_NOTATION, NoteNames.notation.value.name).apply()
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = MiconSpace.md))
            Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).padding(horizontal = MiconSpace.md), verticalAlignment = Alignment.CenterVertically) {
                Text("원본 녹음 저장", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                val on = state.saveOriginalRecording
                if (on == null) Text(state.settingsError ?: "불러오는 중…", style = MaterialTheme.typography.labelMedium, color = muted)
                else Switch(checked = on, onCheckedChange = vm::setSaveOriginalRecording, enabled = !state.saving,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedTrackColor = Color(0xFFD9D5EA), uncheckedBorderColor = Color.Transparent))
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = MiconSpace.md))
            SettingRow("좋아하는 장르·가수", "수정", onEditPreferences)
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, modifier = Modifier.padding(horizontal = MiconSpace.md))
            SettingRow(if (state.deleting) "음성데이터 삭제 중…" else "음성데이터 삭제", "") { if (!state.deleting) showDeleteVoice = true }
        }
        MiconButton(if (loggingOut) "로그아웃 중…" else "로그아웃", {
            if (!loggingOut) { loggingOut = true; scope.launch(Dispatchers.Main.immediate) { auth.logout(); onLogout() } }
        }, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            TextButton({ if (!loggingOut) showWithdraw = true }) { Text("회원 탈퇴", color = Color(0xFFC92A5E), style = MaterialTheme.typography.labelLarge) }
        }
        Text("버전 ${BuildConfig.VERSION_NAME}", style = MaterialTheme.typography.labelMedium, color = muted, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    }

    if (showDeleteVoice) AlertDialog(
        onDismissRequest = { showDeleteVoice = false },
        title = { Text("음성 데이터를 삭제할까요?") },
        text = { Text("서버에 저장된 내 녹음과 분석 결과가 모두 지워져요. 되돌릴 수 없어요.") },
        confirmButton = { TextButton({ showDeleteVoice = false; vm.deleteVoiceData() }) { Text("삭제") } },
        dismissButton = { TextButton({ showDeleteVoice = false }) { Text("취소") } },
    )
    if (showWithdraw) AlertDialog(
        onDismissRequest = { if (!withdrawing) showWithdraw = false },
        title = { Text("회원 탈퇴") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("계정과 개인 데이터가 모두 삭제돼요. 확인을 위해 비밀번호를 입력해 주세요.", style = MaterialTheme.typography.bodyMedium)
                MiconField(withdrawPassword, { withdrawPassword = it }, "현재 비밀번호", password = true)
                withdrawError?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.labelMedium) }
            }
        },
        confirmButton = {
            TextButton(enabled = !withdrawing && withdrawPassword.isNotBlank(), onClick = {
                withdrawing = true; withdrawError = null
                scope.launch {
                    try {
                        val response = backend.api.withdraw(WithdrawRequest(withdrawPassword))
                        if (!response.isSuccessful) withdrawError = if (response.code() == 401) "비밀번호가 올바르지 않습니다." else "탈퇴를 처리하지 못했습니다."
                        else { TokenManager.clear(); showWithdraw = false; Toast.makeText(context, "탈퇴와 개인 데이터 삭제가 완료되었습니다.", Toast.LENGTH_LONG).show(); onLogout() }
                    } catch (_: Exception) { withdrawError = "연결을 확인한 뒤 다시 시도해주세요." }
                    finally { withdrawing = false }
                }
            }) { Text(if (withdrawing) "처리 중…" else "탈퇴", color = Color(0xFFC92A5E)) }
        },
        dismissButton = { TextButton({ showWithdraw = false }, enabled = !withdrawing) { Text("취소") } },
    )
}

@Composable
private fun SettingRow(label: String, value: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).heightIn(min = 48.dp).padding(horizontal = MiconSpace.md), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
