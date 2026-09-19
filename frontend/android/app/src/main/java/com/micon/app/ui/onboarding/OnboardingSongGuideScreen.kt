package com.micon.app.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.ui.common.OnboardingDarkBackground
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.Ink
import kotlinx.coroutines.delay

internal fun onboardingInitialDestination(state: VoiceOnboardingState): String = when {
    state.expired -> "expired"
    state.recording != null -> "result"
    state.canSelectSong -> if (state.songGuideCompleted) "pick" else "song-guide"
    else -> "measure"
}

@Composable
internal fun OnboardingSongGuideScreen(error: String? = null, onBack: () -> Unit,
    onNext: () -> Boolean, onFinished: () -> Unit) {
    var step by remember { mutableIntStateOf(0) }
    var leaving by remember { mutableStateOf(false) }
    val transition = remember { MutableTransitionState(true) }
    val finished by rememberUpdatedState(onFinished)
    LaunchedEffect(Unit) {
        delay(1500); step = 1
        delay(1500); step = 2
        delay(300); step = 3
    }
    transition.targetState = !leaving
    OnboardingDarkBackground(transition.currentState || transition.targetState)
    LaunchedEffect(transition.isIdle, transition.currentState) {
        if (transition.isIdle && !transition.currentState) finished()
    }
    AnimatedVisibility(transition, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
        Column(Modifier.fillMaxSize().background(Ink).testTag("onboarding-song-guide")) {
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp), enabled = !leaving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center) {
                GuideLine(true, "잘했어요!")
                GuideLine(step >= 1, "이제 노래 하나를 선택해주세요,")
                GuideLine(step >= 2, "노래를 불러서 테스트를 진행할게요")
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = Color.White, textAlign = TextAlign.Center)
                }
            }
            GuideFadeIn(step >= 3) {
                PrimaryButton("다음으로", {
                    if (!leaving && onNext()) leaving = true
                }, Modifier.fillMaxWidth().padding(24.dp), enabled = !leaving)
            }
        }
    }
}

@Composable
private fun GuideLine(visible: Boolean, text: String) {
    GuideFadeIn(visible) {
        Text(text, Modifier.fillMaxWidth().padding(vertical = 8.dp), color = Color.White,
            fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
    }
}

@Composable
private fun GuideFadeIn(visible: Boolean, content: @Composable () -> Unit) {
    val transition = remember { MutableTransitionState(false) }
    transition.targetState = visible
    AnimatedVisibility(transition, enter = fadeIn(tween(300)), exit = ExitTransition.None) { content() }
}
