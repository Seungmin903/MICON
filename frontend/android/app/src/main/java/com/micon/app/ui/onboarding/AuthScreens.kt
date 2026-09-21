package com.micon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.micon.app.R
import com.micon.app.appContainer
import com.micon.app.data.backend.NetworkAuthRepository
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconField
import com.micon.app.ui.design.atoms.MiconMark
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconLogoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import kotlinx.coroutines.launch

/**
 * 로그인 / 회원가입 — 프로토타입 `login`. 결과를 저장할 때 만나는 화면이라 설문·가입과 같은 규격을 쓴다.
 * 회원가입·비밀번호 찾기 탭 영역은 48dp, 버튼은 항상 활성(비활성 대비 문제).
 */
@Composable
private fun authViewModel(): AuthViewModel {
    val backend = LocalContext.current.appContainer.backend
    return viewModel { AuthViewModel(NetworkAuthRepository(backend.api)) }
}

@Composable
private fun AuthHeader(title: String, subtitle: String) {
    Spacer(Modifier.height(24.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        MiconMark(height = 26.dp)
        Spacer(Modifier.width(8.dp))
        Text("MICON", fontFamily = MiconLogoFamily, fontWeight = FontWeight.Black, fontSize = 26.sp, letterSpacing = 1.5.sp, color = MaterialTheme.colorScheme.onSurface)
    }
    Spacer(Modifier.height(8.dp))
    Text(title, style = MaterialTheme.typography.titleLarge)
    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun LoginScreen(onLoggedIn: () -> Unit, onSignup: () -> Unit, onFindPassword: () -> Unit = {}, onGuest: () -> Unit) {
    val model = authViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var notice by remember { mutableStateOf<String?>(null) }
    var googleInProgress by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val loggedIn by rememberUpdatedState(onLoggedIn)
    BackHandler(enabled = state.submitting) { }
    LaunchedEffect(state.authenticated) { if (state.authenticated) { password = ""; loggedIn() } }
    fun submit() { if (!state.submitting) { notice = null; model.submit(email, password, signup = false) } }
    fun signInWithGoogle() {
        if (state.submitting || googleInProgress) return
        notice = null
        googleInProgress = true
        scope.launch {
            try {
                model.submitGoogle(GoogleIdTokenProvider.request(context, context.getString(R.string.google_web_client_id)))
            } catch (error: GoogleSignInException) {
                notice = error.message
            } finally {
                googleInProgress = false
            }
        }
    }

    MiconScreen(modifier = Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(MiconSpace.lg)) {
        AuthHeader("다시 만나서 반가워요", "로그인하면 지난 분석 결과와 연습 기록을 그대로 볼 수 있어요.")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MiconField(email, { email = it }, "이메일", Modifier.testTag("auth_email"), keyboardType = KeyboardType.Email)
            MiconField(password, { password = it }, "비밀번호", Modifier.testTag("auth_password"), password = true, imeAction = ImeAction.Done, onImeAction = ::submit)
        }
        Column(verticalArrangement = Arrangement.spacedBy(MiconSpace.sm)) {
            (state.error ?: notice)?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("auth_message")) }
            MiconButton(if (state.submitting) "로그인 중…" else "로그인", ::submit, Modifier.fillMaxWidth().testTag("auth_submit"))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                MiconButton("회원가입", onSignup, kind = MiconButtonKind.Text, enabled = !state.submitting)
                Text("|", color = MaterialTheme.colorScheme.outlineVariant)
                MiconButton("비밀번호 찾기", onFindPassword, kind = MiconButtonKind.Text, enabled = !state.submitting)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.weight(1f).height(1.dp).padding(0.dp)) { androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
            Text("  또는  ", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Box(Modifier.weight(1f).height(1.dp)) { androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant) }
        }
        MiconButton(if (googleInProgress) "구글 로그인 중…" else "구글로 시작하기", ::signInWithGoogle, Modifier.fillMaxWidth(),
            kind = MiconButtonKind.Outline, enabled = !state.submitting && !googleInProgress, leading = { GoogleG() })
        MiconButton("둘러보기 · 로그인 없이", onGuest, Modifier.fillMaxWidth().testTag("auth_guest"), kind = MiconButtonKind.Outline, enabled = !state.submitting)
    }
}

@Composable
fun SignupScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val model = authViewModel()
    val state by model.state.collectAsStateWithLifecycle()
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    val pwOk = isValidPassword(password)
    val confirmOk = confirm.isEmpty() || confirm == password
    val done by rememberUpdatedState(onDone)
    BackHandler(enabled = state.submitting) { }
    LaunchedEffect(state.authenticated) { if (state.authenticated) { password = ""; confirm = ""; done() } }
    val muted = MaterialTheme.colorScheme.onSurfaceVariant

    MiconScreen(modifier = Modifier.imePadding(), verticalArrangement = Arrangement.spacedBy(MiconSpace.lg)) {
        AuthHeader("처음 만나서 반가워요", "계정을 만들면 분석 결과와 연습 기록을 안전하게 보관할 수 있어요.")
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            MiconField(email, { if (!state.submitting) email = it }, "이메일", Modifier.testTag("auth_email"), keyboardType = KeyboardType.Email)
            MiconField(password, { if (!state.submitting) password = it }, "비밀번호", Modifier.testTag("auth_password"), password = true)
            Text(PASSWORD_GUIDE, style = MaterialTheme.typography.labelMedium, color = if (password.isEmpty()) muted else if (pwOk) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
            MiconField(confirm, { if (!state.submitting) confirm = it }, "비밀번호 확인", Modifier.testTag("auth_password_confirm"), password = true, imeAction = ImeAction.Done)
            if (!confirmOk) Text("비밀번호가 일치하지 않습니다.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.error)
        }
        Column(verticalArrangement = Arrangement.spacedBy(MiconSpace.xs)) {
            state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().testTag("auth_message")) }
            MiconButton(if (state.submitting) "가입 중…" else "회원가입", { model.submit(email, password, signup = true) },
                Modifier.fillMaxWidth().testTag("auth_submit"), enabled = !state.submitting && email.isNotBlank() && pwOk && confirm == password)
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { MiconButton("로그인으로 돌아가기", onBack, kind = MiconButtonKind.Text, enabled = !state.submitting) }
        }
    }
}

/** 구글 버튼 앞의 G 마크. 브랜드 4색을 쓸어 담은 그라디언트라 이미지 자산이 필요 없다 */
@Composable
private fun GoogleG() {
    Box(Modifier.size(20.dp), contentAlignment = Alignment.Center) {
        Text(
            "G", fontSize = 18.sp, fontWeight = FontWeight.Bold,
            style = androidx.compose.ui.text.TextStyle(
                brush = Brush.sweepGradient(
                    listOf(Color(0xFF4285F4), Color(0xFF34A853), Color(0xFFFBBC05), Color(0xFFEA4335), Color(0xFF4285F4)),
                ),
            ),
        )
    }
}
