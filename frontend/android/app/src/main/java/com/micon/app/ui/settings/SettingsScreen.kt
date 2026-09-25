package com.micon.app.ui.settings

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.outlined.AccountCircle
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.appContainer
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.backend.NetworkAuthRepository
import com.micon.app.data.backend.dto.PasswordChangeRequest
import com.micon.app.data.backend.dto.WithdrawRequest
import com.micon.app.data.backend.errorEnvelope
import com.micon.app.data.backend.unwrapData
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconTextField
import com.micon.app.ui.onboarding.AuthPrimaryButton
import com.micon.app.ui.onboarding.AuthTextField
import com.micon.app.ui.onboarding.PASSWORD_GUIDE
import com.micon.app.ui.onboarding.isValidPassword
import com.micon.app.ui.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** HTML 프로토타입의 카드 구성을 적용한 계정 설정 화면. */
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onLogout: () -> Unit = {},
    onEditPreferences: () -> Unit = {},
    onChangePassword: () -> Unit = {},
) {
    val context = LocalContext.current
    val backend = context.appContainer.backend
    val auth = remember(backend) { NetworkAuthRepository(backend.api) }
    val scope = rememberCoroutineScope()
    var accountEmail by remember { mutableStateOf<String?>(null) }
    var accountLoadFailed by remember { mutableStateOf(false) }
    var loggingOut by remember { mutableStateOf(false) }
    var showWithdraw by remember { mutableStateOf(false) }
    var withdrawing by remember { mutableStateOf(false) }
    var withdrawPassword by remember { mutableStateOf("") }
    var withdrawError by remember { mutableStateOf<String?>(null) }

    BackHandler(enabled = loggingOut) { }
    LaunchedEffect(Unit) {
        runCatching { backend.api.currentUser().unwrapData().email }
            .onSuccess { accountEmail = it }
            .onFailure { accountLoadFailed = true }
    }

    if (showWithdraw) {
        AlertDialog(
            onDismissRequest = { if (!withdrawing) showWithdraw = false },
            containerColor = Color.White,
            shape = RoundedCornerShape(22.dp),
            title = { Text("회원 탈퇴", color = Ink, fontWeight = FontWeight.Bold) },
            text = {
                Column {
                    Text("탈퇴하면 기존 계정과 개인 기록에 접근할 수 없습니다. 현재 비밀번호를 입력해주세요.", color = Grey)
                    Spacer(Modifier.height(16.dp))
                    MiconTextField(
                        "현재 비밀번호",
                        withdrawPassword,
                        { withdrawPassword = it; withdrawError = null },
                        "비밀번호",
                        isPassword = true,
                    )
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
                                val ownerId = TokenManager.snapshot().userId
                                val response = backend.api.withdraw(WithdrawRequest(withdrawPassword))
                                if (!response.isSuccessful) {
                                    val backendError = response.errorEnvelope()
                                    withdrawError = withdrawalErrorMessage(backendError?.code, backendError?.message)
                                } else {
                                    val localDeleted = runCatching {
                                        if (ownerId > 0) withContext(Dispatchers.IO) { context.appContainer.practiceRecordings.deleteAll(ownerId) }
                                    }.isSuccess
                                    TokenManager.clear()
                                    showWithdraw = false
                                    Toast.makeText(context, if (localDeleted) "탈퇴와 개인 데이터 삭제가 완료되었습니다."
                                        else "탈퇴는 완료됐지만 기기 녹음 삭제에 실패했습니다.", Toast.LENGTH_LONG).show()
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
            dismissButton = { TextButton(enabled = !withdrawing, onClick = { showWithdraw = false }) { Text("취소", color = Grey) } },
        )
    }

    Column(Modifier.fillMaxSize().background(Color(0xFFF7F5FC)).statusBarsPadding()) {
        BackHeader("설정", { if (!loggingOut) onBack() })
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(20.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = Color.White, shadowElevation = 2.dp,
            ) {
                Row(Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(52.dp).background(Purple.copy(alpha = 0.12f), CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.AccountCircle, null, Modifier.size(30.dp), tint = Purple)
                    }
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text("현재 계정", fontSize = 12.sp, color = Grey)
                        Spacer(Modifier.height(4.dp))
                        Text(
                            accountEmail ?: if (accountLoadFailed) "계정 정보를 불러오지 못했어요" else "불러오는 중…",
                            fontSize = 15.sp, fontWeight = FontWeight.SemiBold,
                            color = if (accountLoadFailed) Pink else Ink,
                        )
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            Surface(
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(20.dp),
                color = Color.White, shadowElevation = 2.dp,
            ) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 4.dp)) {
                    SettingsMenuRow(Icons.Outlined.FavoriteBorder, "선호 장르/가수 수정", onClick = onEditPreferences)
                    SettingsDivider()
                    // 음 이름 표기(도레미/CDE) — 바꾸면 앱 전체 표기가 따라 바뀐다
                    NotationSettingRow()
                    SettingsDivider()
                    SettingsMenuRow(Icons.Outlined.Lock, "비밀번호 변경", onClick = onChangePassword)
                    SettingsDivider()
                    SettingsMenuRow(Icons.AutoMirrored.Outlined.Logout, if (loggingOut) "로그아웃 중…" else "로그아웃") {
                        if (!loggingOut) {
                            loggingOut = true
                            scope.launch(Dispatchers.Main.immediate) { auth.logout(); onLogout() }
                        }
                    }
                    SettingsDivider()
                    SettingsMenuRow(Icons.Filled.Cancel, "탈퇴", color = Pink) { if (!loggingOut) showWithdraw = true }
                }
            }
            Spacer(Modifier.height(32.dp))
        }
    }
}

internal fun withdrawalErrorMessage(code: String?, message: String?): String = when (code) {
    "INVALID_CURRENT_PASSWORD" -> "비밀번호가 올바르지 않습니다."
    "INVALID_REQUEST" -> message ?: "입력한 내용을 확인해주세요."
    "ACCOUNT_NOT_ACTIVE" -> "이미 탈퇴했거나 이용할 수 없는 계정입니다."
    else -> message ?: "탈퇴를 처리하지 못했습니다."
}

@Composable
private fun SettingsMenuRow(icon: ImageVector, label: String, color: Color = Ink, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 17.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, Modifier.size(23.dp), tint = color)
        Spacer(Modifier.width(16.dp))
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.Medium, color = color, modifier = Modifier.weight(1f))
        Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null, Modifier.size(22.dp), tint = if (color == Pink) Pink else Grey)
    }
}

@Composable
private fun SettingsDivider() {
    HorizontalDivider(color = GreyLight, modifier = Modifier.padding(start = 39.dp))
}

/** 로그인 화면과 같은 입력창·버튼 규격을 사용하는 회원 비밀번호 변경 화면. */
@Composable
fun ChangePasswordScreen(onBack: () -> Unit, onLogout: () -> Unit) {
    val context = LocalContext.current
    val backend = context.appContainer.backend
    val scope = rememberCoroutineScope()
    var currentPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var confirmPassword by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var changing by remember { mutableStateOf(false) }
    val newPasswordValid = isValidPassword(newPassword)
    val confirmationMatches = confirmPassword.isNotEmpty() && confirmPassword == newPassword
    val canSubmit = currentPassword.isNotBlank() && newPasswordValid && confirmationMatches

    BackHandler(enabled = changing) { }
    Column(Modifier.fillMaxSize().background(Color(0xFFF7F5FC))) {
        BackHeader("비밀번호 변경", { if (!changing) onBack() })
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.Start,
        ) {
            Spacer(Modifier.height(28.dp))
            Text("비밀번호를 변경할게요", fontSize = 24.sp, lineHeight = 32.sp, fontWeight = FontWeight.Black, color = Ink)
            Spacer(Modifier.height(8.dp))
            Text("현재 비밀번호를 확인한 뒤 새로운 비밀번호로 변경합니다.", fontSize = 13.5.sp, lineHeight = 22.sp, color = Grey)
            Spacer(Modifier.height(24.dp))
            AuthTextField(currentPassword, { currentPassword = it; error = null }, "현재 비밀번호", isPassword = true)
            Spacer(Modifier.height(10.dp))
            AuthTextField(
                newPassword, { newPassword = it; error = null }, "새로운 비밀번호", isPassword = true,
                helper = PASSWORD_GUIDE,
                helperColor = if (newPasswordValid) GreenChipText else if (newPassword.isEmpty()) Grey else Pink,
            )
            Spacer(Modifier.height(10.dp))
            AuthTextField(
                confirmPassword, { confirmPassword = it; error = null }, "새로운 비밀번호 확인", isPassword = true,
                helper = when {
                    confirmPassword.isEmpty() -> null
                    confirmationMatches -> "비밀번호가 일치합니다."
                    else -> "비밀번호가 일치하지 않습니다."
                },
                helperColor = if (confirmationMatches) GreenChipText else Pink,
            )
            error?.let { Text(it, color = Pink, fontSize = 13.sp, modifier = Modifier.padding(start = 4.dp, top = 12.dp)) }
            Spacer(Modifier.height(24.dp))
            AuthPrimaryButton(
                text = if (changing) "변경 중…" else "변경",
                onClick = {
                    changing = true
                    error = null
                    scope.launch {
                        try {
                            val response = backend.api.changePassword(PasswordChangeRequest(currentPassword, newPassword))
                            if (!response.isSuccessful) {
                                val backendError = response.errorEnvelope()
                                error = when (backendError?.code) {
                                    "INVALID_CURRENT_PASSWORD" -> "현재 비밀번호가 올바르지 않습니다."
                                    "PASSWORD_UNCHANGED" -> "새 비밀번호는 현재 비밀번호와 달라야 합니다."
                                    "INVALID_REQUEST" -> backendError.message
                                    else -> backendError?.message ?: "비밀번호를 변경하지 못했습니다."
                                }
                            } else {
                                currentPassword = ""; newPassword = ""; confirmPassword = ""
                                TokenManager.clear()
                                Toast.makeText(context, "비밀번호가 변경되었습니다. 다시 로그인해주세요.", Toast.LENGTH_LONG).show()
                                onLogout()
                            }
                        } catch (_: Exception) {
                            error = "연결을 확인한 뒤 다시 시도해주세요."
                        } finally {
                            changing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = canSubmit && !changing,
            )
            Spacer(Modifier.height(40.dp))
        }
    }
}
