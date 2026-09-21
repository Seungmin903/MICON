package com.micon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.micon.app.appContainer
import com.micon.app.data.backend.NetworkAuthRepository
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.ui.common.MiconLogo
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.GreenChipText
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import com.micon.app.R
import kotlinx.coroutines.launch

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
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onSignup: () -> Unit,
    onFindPassword: () -> Unit = {},
    onGuest: () -> Unit,
) {
    val model = rememberAuthViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    // Passwords are deliberately never saved to SavedStateHandle, rememberSaveable, or disk.
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var googleSignInInProgress by remember { mutableStateOf(false) }
    val coroutineScope = rememberCoroutineScope()
    val context = LocalContext.current
    val loggedIn by rememberUpdatedState(onLoggedIn)
    BackHandler(enabled = state.submitting) { }
    LaunchedEffect(state.authenticated) {
        if (state.authenticated) { password = ""; loggedIn() }
    }
    Column(Modifier.fillMaxSize().background(AuthBackground).imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start) {
        Spacer(Modifier.height(44.dp))
        MiconLogo(barHeight = 26.dp, fontSize = 26.sp)
        Spacer(Modifier.height(16.dp))
        Text("만나서 반가워요", fontSize = 24.sp, lineHeight = 32.sp,
            fontWeight = FontWeight.Black, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("로그인하면 지난 분석 결과와 연습 기록, 분석 결과에 따른 추천까지 받을 수 있어요.",
            fontSize = 13.5.sp, lineHeight = 22.sp, color = Grey)
        Spacer(Modifier.height(20.dp))
        AuthTextField(email, { if (!state.submitting) email = it }, "이메일",
            modifier = Modifier.testTag("auth_email"), keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(10.dp))
        AuthTextField(password, { if (!state.submitting) password = it }, "비밀번호",
            modifier = Modifier.testTag("auth_password"), isPassword = true)
        AuthMessage(state.error ?: notice)
        Spacer(Modifier.height(20.dp))
        AuthPrimaryButton(if (state.submitting) "로그인 중…" else "로그인",
            onClick = { notice = null; model.submit(email, password, signup = false) },
            modifier = Modifier.fillMaxWidth().testTag("auth_submit"),
            enabled = !state.submitting && email.isNotBlank() && password.isNotBlank())
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically) {
            Text("회원가입", fontSize = 13.5.sp, color = Grey,
                modifier = Modifier.clickable(enabled = !state.submitting, onClick = onSignup).padding(12.dp))
            Text("|", fontSize = 13.sp, color = GreyLight)
            Text("비밀번호 찾기", fontSize = 13.5.sp, color = Grey,
                modifier = Modifier.clickable(enabled = !state.submitting) {
                    notice = null; onFindPassword()
                }.padding(12.dp))
        }
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            HorizontalDivider(Modifier.weight(1f), color = GreyLight)
            Text("또는", fontSize = 12.sp, color = Grey, modifier = Modifier.padding(horizontal = 10.dp))
            HorizontalDivider(Modifier.weight(1f), color = GreyLight)
        }
        Spacer(Modifier.height(20.dp))
        AuthOutlineButton(if (googleSignInInProgress) "구글 로그인 중…" else "구글로 시작하기", onClick = {
            if (state.submitting || googleSignInInProgress) return@AuthOutlineButton
            notice = null
            googleSignInInProgress = true
            coroutineScope.launch {
                try {
                    val idToken = GoogleIdTokenProvider.request(
                        context, context.getString(R.string.google_web_client_id)
                    )
                    model.submitGoogle(idToken)
                } catch (error: GoogleSignInException) {
                    notice = error.message
                } finally {
                    googleSignInInProgress = false
                }
            }
        }, modifier = Modifier.fillMaxWidth(), leading = { GoogleG() },
            enabled = !state.submitting && !googleSignInInProgress)
        Spacer(Modifier.height(10.dp))
        AuthOutlineButton("로그인 없이 둘러보기", onClick = onGuest,
            modifier = Modifier.fillMaxWidth().testTag("auth_guest"))
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
internal fun AuthPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Purple,
            contentColor = Color.White,
            disabledContainerColor = Color(0xFFD9D5EA),
            disabledContentColor = Color(0xFF7C7796),
        ),
        elevation = ButtonDefaults.buttonElevation(defaultElevation = 3.dp),
    ) {
        Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun AuthOutlineButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    OutlinedButton(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(56.dp),
        shape = RoundedCornerShape(28.dp),
        border = BorderStroke(1.dp, Color(0xFFE4E2EE)),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = Color.White, contentColor = Ink),
        contentPadding = PaddingValues(horizontal = 16.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            leading?.let { Box(Modifier.align(Alignment.CenterStart)) { it() } }
            Text(text, fontSize = 16.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun AuthTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text,
    helper: String? = null,
    helperColor: Color = Grey,
) {
    Column(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            modifier = Modifier.fillMaxWidth().height(56.dp),
            placeholder = { Text(placeholder, color = Grey, fontSize = 15.sp) },
            singleLine = true,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 15.sp, color = Ink),
            shape = RoundedCornerShape(16.dp),
            visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
            keyboardOptions = KeyboardOptions(keyboardType = if (isPassword) KeyboardType.Password else keyboardType),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Purple,
                unfocusedBorderColor = Color(0xFFE4E2EE),
                focusedContainerColor = Color.White,
                unfocusedContainerColor = Color.White,
                cursorColor = Purple,
            ),
        )
        helper?.let {
            Text(it, fontSize = 12.sp, color = helperColor, modifier = Modifier.padding(start = 16.dp, top = 6.dp))
        }
    }
}

private val AuthBackground = Color(0xFFF7F5FC)

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
    val pwOk = isValidPassword(password)
    val confirmOk = confirm.isEmpty() || confirm == password
    val done by rememberUpdatedState(onDone)
    BackHandler(enabled = state.submitting) { }
    LaunchedEffect(state.authenticated) {
        if (state.authenticated) { password = ""; confirm = ""; done() }
    }
    Column(Modifier.fillMaxSize().background(AuthBackground).imePadding()
        .verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
        horizontalAlignment = Alignment.Start) {
        Spacer(Modifier.height(44.dp))
        MiconLogo(barHeight = 26.dp, fontSize = 26.sp)
        Spacer(Modifier.height(16.dp))
        Text("처음 만나서 반가워요", fontSize = 24.sp, lineHeight = 32.sp,
            fontWeight = FontWeight.Black, color = Ink)
        Spacer(Modifier.height(8.dp))
        Text("계정을 만들면 분석 결과와 연습 기록을 안전하게 보관할 수 있어요.",
            fontSize = 13.5.sp, lineHeight = 22.sp, color = Grey)
        Spacer(Modifier.height(20.dp))
        AuthTextField(email, { if (!state.submitting) email = it }, "이메일",
            modifier = Modifier.testTag("auth_email"), keyboardType = KeyboardType.Email)
        Spacer(Modifier.height(10.dp))
        AuthTextField(password, { if (!state.submitting) password = it }, "비밀번호",
            modifier = Modifier.testTag("auth_password"), isPassword = true,
            helper = PASSWORD_GUIDE,
            helperColor = if (pwOk) GreenChipText else Red)
        Spacer(Modifier.height(10.dp))
        AuthTextField(confirm, { if (!state.submitting) confirm = it }, "비밀번호 확인",
            modifier = Modifier.testTag("auth_password_confirm"), isPassword = true,
            helper = if (!confirmOk) "비밀번호가 일치하지 않습니다." else null, helperColor = Red)
        AuthMessage(state.error)
        Spacer(Modifier.height(20.dp))
        AuthPrimaryButton(if (state.submitting) "가입 중…" else "회원가입",
            onClick = { model.submit(email, password, signup = true) },
            modifier = Modifier.fillMaxWidth().testTag("auth_submit"),
            enabled = !state.submitting && email.isNotBlank() && pwOk && confirm == password)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("로그인으로 돌아가기", fontSize = 13.5.sp, color = Grey,
                modifier = Modifier.clickable(enabled = !state.submitting, onClick = onBack).padding(14.dp))
        }
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
