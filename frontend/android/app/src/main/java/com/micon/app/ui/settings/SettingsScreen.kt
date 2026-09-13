package com.micon.app.ui.settings

import androidx.activity.compose.BackHandler
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PersonOff
import androidx.compose.material.icons.outlined.Save
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.BuildConfig
import com.micon.app.appContainer
import com.micon.app.data.backend.NetworkAuthRepository
import com.micon.app.data.backend.dto.WithdrawRequest
import com.micon.app.core.auth.TokenManager
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconTextField
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.Purple
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

/** Figma "설정": 섹션 라벨 + 아이콘 행 + 토글, 회원 탈퇴는 핑크. */
@Composable
fun SettingsScreen(onBack: () -> Unit, onLogout: () -> Unit = {}, onEditPreferences: () -> Unit = {}) {
    var keepOriginal by remember { mutableStateOf(true) } // TODO: 기본값 상충(명세 ON vs SET-002 OFF) 결정 후 저장소 연동
    val context = LocalContext.current
    val backend = context.appContainer.backend
    val auth = remember(backend) { NetworkAuthRepository(backend.api) }
    val scope = rememberCoroutineScope()
    var loggingOut by remember { mutableStateOf(false) }
    var showWithdraw by remember { mutableStateOf(false) }
    var withdrawing by remember { mutableStateOf(false) }
    var withdrawPassword by remember { mutableStateOf("") }
    var withdrawError by remember { mutableStateOf<String?>(null) }
    BackHandler(enabled = loggingOut) { }

    if (showWithdraw) {
        AlertDialog(
            onDismissRequest = { if (!withdrawing) showWithdraw = false },
            title = { Text("회원 탈퇴") },
            text = {
                Column {
                    Text("탈퇴하면 기존 계정과 개인 기록에 접근할 수 없습니다. 현재 비밀번호를 입력해주세요.")
                    Spacer(Modifier.height(16.dp))
                    MiconTextField("현재 비밀번호", withdrawPassword, { withdrawPassword = it }, "비밀번호", isPassword = true)
                    withdrawError?.let { Text(it, color = Pink, fontSize = 13.sp, modifier = Modifier.padding(top = 10.dp)) }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !withdrawing && withdrawPassword.isNotBlank(),
                    onClick = {
                        withdrawing = true
                        withdrawError = null
                        scope.launch {
                            try {
                                val response = backend.api.withdraw(WithdrawRequest(withdrawPassword))
                                if (!response.isSuccessful) {
                                    withdrawError = if (response.code() == 401) "비밀번호가 올바르지 않습니다." else "탈퇴를 처리하지 못했습니다."
                                } else {
                                    TokenManager.clear()
                                    showWithdraw = false
                                    Toast.makeText(context, "탈퇴와 개인 데이터 삭제가 완료되었습니다.", Toast.LENGTH_LONG).show()
                                    onLogout()
                                }
                            } catch (_: Exception) {
                                withdrawError = "연결을 확인한 뒤 다시 시도해주세요."
                            } finally {
                                withdrawing = false
                            }
                        }
                    },
                ) { Text(if (withdrawing) "처리 중…" else "탈퇴하기", color = Pink) }
            },
            dismissButton = { TextButton(enabled = !withdrawing, onClick = { showWithdraw = false }) { Text("취소") } },
        )
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("설정", { if (!loggingOut) onBack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
            Spacer(Modifier.height(28.dp))
            SectionTitle("나의 음악 취향")
            SettingRow(Icons.Outlined.FavoriteBorder, "관심 장르·아티스트 수정") { if (!loggingOut) onEditPreferences() }
            Spacer(Modifier.height(20.dp))
            SectionTitle("녹음 및 음성 데이터")
            SettingRow(Icons.Outlined.Mic, "마이크 권한 설정") { /* TODO: 앱 설정 화면 인텐트 */ }
            SettingRow(Icons.Outlined.Notifications, "알림 설정") { }
            Row(Modifier.fillMaxWidth().padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Save, null, Modifier.size(26.dp), tint = Ink)
                Spacer(Modifier.width(22.dp))
                Column(Modifier.weight(1f)) {
                    Text("원본 녹음 저장", fontSize = 17.sp, color = Ink)
                    Text("비활성화시 분석 후 삭제", fontSize = 14.sp, color = Grey)
                }
                Switch(
                    checked = keepOriginal, onCheckedChange = { keepOriginal = it },
                    colors = SwitchDefaults.colors(checkedThumbColor = Color.White, checkedTrackColor = Purple, uncheckedTrackColor = GreyLight, uncheckedThumbColor = Color.White, uncheckedBorderColor = GreyLight),
                )
            }
            Spacer(Modifier.height(20.dp))
            SectionTitle("개인정보 및 보안")
            SettingRow(Icons.Outlined.Shield, "개인정보 설정") { }
            SettingRow(Icons.Outlined.Delete, "음성데이터 삭제") { /* TODO: BackendApi 음성 데이터 삭제 */ }
            SettingRow(Icons.Outlined.PersonOff, if (loggingOut) "로그아웃 중…" else "로그아웃") {
                if (!loggingOut) {
                    loggingOut = true
                    scope.launch(Dispatchers.Main.immediate) { auth.logout(); onLogout() }
                }
            }
            SettingRow(Icons.Filled.Cancel, "회원 탈퇴", color = Pink) { if (!loggingOut) showWithdraw = true }
            Spacer(Modifier.height(32.dp))
            SectionTitle("개발자")
            DevRow("엔진 주소", BuildConfig.ENGINE_BASE_URL)
            DevRow("백엔드 주소", BuildConfig.API_BASE_URL)
            DevRow("앱 버전", BuildConfig.VERSION_NAME)
            Spacer(Modifier.height(40.dp))
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(text, fontSize = 15.sp, color = Grey, modifier = Modifier.padding(bottom = 6.dp))
}

@Composable
private fun SettingRow(icon: ImageVector, label: String, color: Color = Ink, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(26.dp), tint = color)
        Spacer(Modifier.width(22.dp))
        Text(label, fontSize = 17.sp, color = color, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(24.dp), tint = color)
    }
}

@Composable
private fun DevRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 14.sp, color = Ink, fontWeight = FontWeight.Medium, modifier = Modifier.width(96.dp))
        Text(value, fontSize = 13.sp, color = Grey)
    }
}
