package com.micon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.micon.app.appContainer
import com.micon.app.data.backend.NetworkAuthRepository
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.ui.common.GreyButton
import com.micon.app.ui.common.MiconLogo
import com.micon.app.ui.common.MiconTextField
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.GreenChipText
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red

/** Every launch checks server identity and onboarding state; unavailable servers offer retry. */
@Composable
fun SplashScreen(onLoggedIn: () -> Unit, onNeedLogin: () -> Unit, onNeedSurvey: () -> Unit) {
    val backend = LocalContext.current.appContainer.backend
    val model: SessionGateViewModel = viewModel {
        SessionGateViewModel(NetworkAuthRepository(backend.api),
            NetworkPreferenceRepository(backend.retrofit.create(PreferenceApi::class.java)))
    }
    val state by model.state.collectAsStateWithLifecycle()
    val main by rememberUpdatedState(onLoggedIn)
    val login by rememberUpdatedState(onNeedLogin)
    val survey by rememberUpdatedState(onNeedSurvey)
    LaunchedEffect(state.destination) {
        when (state.destination) {
            SessionDestination.LOGIN -> login()
            SessionDestination.SURVEY -> survey()
            SessionDestination.MAIN -> main()
            null -> Unit
        }
    }
    Column(Modifier.fillMaxSize().background(Color.White).padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
        MiconLogo(barHeight = 40.dp, fontSize = 40.sp)
        Spacer(Modifier.height(28.dp))
        if (state.loading) CircularProgressIndicator(Modifier.size(28.dp), color = Purple)
        state.error?.let {
            Text(it, color = Grey, textAlign = TextAlign.Center)
            Spacer(Modifier.height(20.dp))
            PrimaryButton("다시 시도", model::retry, Modifier.fillMaxWidth().testTag("session_retry"))
        }
    }
}

@Composable
private fun rememberAuthViewModel(): AuthViewModel {
    val backend = LocalContext.current.appContainer.backend
    return viewModel { AuthViewModel(NetworkAuthRepository(backend.api)) }
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onSignup: () -> Unit, onFindPassword: () -> Unit = {}) {
    val model = rememberAuthViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    // Passwords are deliberately never saved to SavedStateHandle, rememberSaveable, or disk.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    val loggedIn by rememberUpdatedState(onLoggedIn)
    BackHandler(enabled = state.submitting) { }
    LaunchedEffect(state.authenticated) {
        if (state.authenticated) { password = ""; loggedIn() }
    }
    Column(Modifier.fillMaxSize().background(Color.White).imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(96.dp))
        MiconLogo(barHeight = 34.dp, fontSize = 34.sp)
        Spacer(Modifier.height(56.dp))
        MiconTextField("이메일", email, { if (!state.submitting) email = it }, "이메일을 입력해주세요",
            modifier = Modifier.testTag("auth_email"), keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(18.dp))
        MiconTextField("비밀번호", password, { if (!state.submitting) password = it }, "비밀번호를 입력해주세요",
            modifier = Modifier.testTag("auth_password"), isPassword = true)
        AuthMessage(state.error ?: notice)
        Spacer(Modifier.height(20.dp))
        PrimaryButton(if (state.submitting) "로그인 중…" else "로그인하기",
            onClick = { notice = null; model.submit(email, password, signup = false) },
            modifier = Modifier.fillMaxWidth().testTag("auth_submit"),
            enabled = !state.submitting && email.isNotBlank() && password.isNotBlank())
        Spacer(Modifier.height(16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text("비밀번호 찾기", fontSize = 14.sp, color = InkSoft,
                modifier = Modifier.clickable(enabled = !state.submitting) {
                    notice = "비밀번호 찾기는 준비 중이에요."; onFindPassword()
                })
            Text("회원가입", fontSize = 14.sp, color = InkSoft,
                modifier = Modifier.clickable(enabled = !state.submitting, onClick = onSignup))
        }
        Spacer(Modifier.height(40.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = GreyLight)
            Text("또는", fontSize = 13.sp, color = Grey, modifier = Modifier.padding(horizontal = 14.dp))
            HorizontalDivider(Modifier.weight(1f), color = GreyLight)
        }
        Spacer(Modifier.height(36.dp))
        GreyButton("구글 로그인", onClick = {
            if (!state.submitting) notice = "구글 로그인은 준비 중이에요. 이메일로 로그인해주세요."
        }, modifier = Modifier.fillMaxWidth(), leading = { GoogleG() })
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun GoogleG() {
    Box(Modifier.size(24.dp), contentAlignment = Alignment.Center) {
        Text("G", fontSize = 20.sp, fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(brush = Brush.sweepGradient(listOf(
                Color(0xFF4285F4), Color(0xFF34A853), Color(0xFFFBBC05), Color(0xFFEA4335), Color(0xFF4285F4)))))
    }
}

@Composable
fun SignupScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val model = rememberAuthViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val pwOk = password.length in 8..64 && password.toByteArray(Charsets.UTF_8).size <= 72
    val confirmOk = confirm.isEmpty() || confirm == password
    val done by rememberUpdatedState(onDone)
    BackHandler(enabled = state.submitting) { }
    LaunchedEffect(state.authenticated) {
        if (state.authenticated) { password = ""; confirm = ""; done() }
    }
    Column(Modifier.fillMaxSize().background(Color.White).imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(64.dp))
        MiconLogo(barHeight = 34.dp, fontSize = 34.sp)
        Spacer(Modifier.height(28.dp))
        Text("회원가입", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkSoft)
        Spacer(Modifier.height(32.dp))
        MiconTextField("이메일", email, { if (!state.submitting) email = it }, "이메일을 입력해주세요",
            modifier = Modifier.testTag("auth_email"), keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(18.dp))
        MiconTextField("비밀번호", password, { if (!state.submitting) password = it }, "비밀번호를 입력해주세요",
            modifier = Modifier.testTag("auth_password"), isPassword = true,
            helper = if (password.toByteArray(Charsets.UTF_8).size > 72) "비밀번호가 너무 길어요. 조금 짧게 입력해주세요." else "8~64자로 입력해주세요.",
            helperColor = if (password.isEmpty() || pwOk) GreenChipText else Red)
        Spacer(Modifier.height(18.dp))
        MiconTextField("비밀번호 확인", confirm, { if (!state.submitting) confirm = it }, "비밀번호를 다시 입력해주세요",
            modifier = Modifier.testTag("auth_password_confirm"), isPassword = true,
            helper = if (!confirmOk) "비밀번호가 일치하지 않습니다." else null, helperColor = Red)
        AuthMessage(state.error)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(if (state.submitting) "가입 중…" else "회원가입하기",
            onClick = { model.submit(email, password, signup = true) },
            modifier = Modifier.fillMaxWidth().testTag("auth_submit"),
            enabled = !state.submitting && email.isNotBlank() && pwOk && confirm == password)
        Spacer(Modifier.height(16.dp))
        Text("로그인으로 돌아가기", fontSize = 14.sp, color = Grey,
            modifier = Modifier.clickable(enabled = !state.submitting, onClick = onBack))
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun AuthMessage(message: String?) {
    message?.let {
        Spacer(Modifier.height(14.dp))
        Text(it, color = Red, fontSize = 14.sp, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().testTag("auth_message"))
    }
}
