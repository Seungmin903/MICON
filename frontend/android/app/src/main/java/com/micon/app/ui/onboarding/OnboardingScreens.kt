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
