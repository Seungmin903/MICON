package com.micon.app.ui.onboarding

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.backend.dto.PasswordResetChangeRequest
import com.micon.app.data.backend.dto.PasswordResetIssueRequest
import com.micon.app.data.backend.dto.PasswordResetVerifyRequest
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconTextField
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private data class PasswordResetState(
    val step: Int = 0,
    val loading: Boolean = false,
    val requestId: String? = null,
    val resetToken: String? = null,
    val error: String? = null,
    val completed: Boolean = false,
)

private class PasswordResetViewModel(private val api: BackendApi) : ViewModel() {
    private val mutableState = MutableStateFlow(PasswordResetState())
    val state = mutableState.asStateFlow()

    fun issue(email: String) = submit {
        val response = api.requestPasswordReset(PasswordResetIssueRequest(email.trim()))
        val issued = response.body()?.data
        if (!response.isSuccessful || issued == null) error("인증번호를 요청하지 못했어요.")
        it.copy(step = 1, requestId = issued.requestId)
    }

    fun verify(code: String) = submit { current ->
        val requestId = current.requestId ?: error("재설정 요청 정보가 없어요.")
        val response = api.verifyPasswordReset(PasswordResetVerifyRequest(requestId, code.trim()))
        val verified = response.body()?.data
        if (!response.isSuccessful || verified == null) error("인증번호가 올바르지 않거나 만료됐어요.")
        current.copy(step = 2, resetToken = verified.resetToken)
    }

    fun reset(password: String) = submit { current ->
        val token = current.resetToken ?: error("재설정 인증 정보가 없어요.")
        val response = api.resetPassword(token, PasswordResetChangeRequest(password))
        if (!response.isSuccessful) error("비밀번호를 변경하지 못했어요.")
        current.copy(completed = true)
    }

    private fun submit(block: suspend (PasswordResetState) -> PasswordResetState) {
        if (mutableState.value.loading || mutableState.value.completed) return
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        viewModelScope.launch {
            mutableState.value = try {
                block(mutableState.value).copy(loading = false, error = null)
            } catch (error: Exception) {
                mutableState.value.copy(loading = false, error = error.message ?: "요청을 처리하지 못했어요.")
            }
        }
    }
}

@Composable
fun PasswordResetScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val container = rememberAppContainer()
    val model: PasswordResetViewModel = viewModel { PasswordResetViewModel(container.backend.api) }
    val state by model.state.collectAsStateWithLifecycle()
    var email by androidx.compose.runtime.remember { mutableStateOf("") }
    var code by androidx.compose.runtime.remember { mutableStateOf("") }
    var password by androidx.compose.runtime.remember { mutableStateOf("") }
    var confirm by androidx.compose.runtime.remember { mutableStateOf("") }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("비밀번호 재설정", onBack)
        Column(
            Modifier.fillMaxSize().imePadding().verticalScroll(rememberScrollState()).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            val title = when { state.completed -> "비밀번호 변경 완료"; state.step == 0 -> "가입한 이메일을 입력해주세요"; state.step == 1 -> "이메일로 받은 인증번호를 입력해주세요"; else -> "새 비밀번호를 입력해주세요" }
            Text(title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Ink, textAlign = TextAlign.Center)
            Spacer(Modifier.height(28.dp))
            when {
                state.completed -> PrimaryButton("로그인하기", onDone, Modifier.fillMaxWidth())
                state.step == 0 -> {
                    MiconTextField("이메일", email, { email = it }, "user@example.com", keyboardType = KeyboardType.Email)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("인증번호 받기", { model.issue(email) }, Modifier.fillMaxWidth(), enabled = !state.loading && email.isNotBlank())
                }
                state.step == 1 -> {
                    MiconTextField("인증번호", code, { code = it.filter(Char::isDigit).take(6) }, "6자리 숫자", keyboardType = KeyboardType.Number)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("인증하기", { model.verify(code) }, Modifier.fillMaxWidth(), enabled = !state.loading && code.length == 6)
                }
                else -> {
                    MiconTextField("새 비밀번호", password, { password = it }, "8~64자", isPassword = true)
                    Spacer(Modifier.height(14.dp))
                    MiconTextField("비밀번호 확인", confirm, { confirm = it }, "한 번 더 입력해주세요", isPassword = true)
                    Spacer(Modifier.height(20.dp))
                    PrimaryButton("비밀번호 변경", { model.reset(password) }, Modifier.fillMaxWidth(), enabled = !state.loading && password.length in 8..64 && password == confirm)
                }
            }
            state.error?.let { Spacer(Modifier.height(14.dp)); Text(it, color = Red, fontSize = 14.sp, textAlign = TextAlign.Center) }
            if (state.loading) { Spacer(Modifier.height(12.dp)); Text("처리 중…", color = Grey, fontSize = 13.sp) }
        }
    }
}
