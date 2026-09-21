package com.micon.app.ui.onboarding

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
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

internal fun onboardingInitialDestination(state: VoiceOnboardingState): String = when {
    state.expired -> "expired"
    state.recording != null -> "result"
    state.canSelectSong -> if (state.songGuideCompleted) "pick" else "song-guide"
    !state.analysisGuideCompleted -> "analysis-guide"
    !state.rangeGuideCompleted -> "range-guide"
    else -> "measure"
}

internal fun onboardingHonorificName(nickname: String?): String? = nickname?.trim()?.takeIf(String::isNotEmpty)
    ?.let { if (it.endsWith("님")) it else "${it}님" }

internal fun analysisGuideCopy(nickname: String?): String = onboardingHonorificName(nickname)?.let {
    "두 단계에 따라 ${it}의 목소리를 분석할거에요"
} ?: "두 단계에 따라 목소리를 분석할거에요"

internal fun rangeGuideCopy(nickname: String?): String = onboardingHonorificName(nickname)?.let {
    "지금부터는 ${it}이 낼 수 있는 소리의 범위를 측정해요"
} ?: "지금부터는 낼 수 있는 소리의 범위를 측정해요"

internal fun timbreGuideCopy(nickname: String?): String = onboardingHonorificName(nickname)?.let {
    "지금부터는 ${it}이 노래할때 어떤 목소리를 가졌는지 분석할거에요"
} ?: "지금부터는 노래할때 어떤 목소리를 가졌는지 분석할거에요"

@Composable
internal fun OnboardingStageGuideScreen(
    text: String,
    buttonLabel: String,
    testTag: String,
    error: String? = null,
    onBack: () -> Unit,
    onNext: () -> Boolean,
    onFinished: () -> Unit,
) {
    var leaving by remember { mutableStateOf(false) }
    val transition = remember { MutableTransitionState(true) }
    val finished by rememberUpdatedState(onFinished)
    transition.targetState = !leaving
    OnboardingDarkBackground(transition.currentState || transition.targetState)
    LaunchedEffect(transition.isIdle, transition.currentState) {
        if (transition.isIdle && !transition.currentState) finished()
    }
    AnimatedVisibility(transition, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
        Column(Modifier.fillMaxSize().background(Ink).testTag(testTag)) {
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp), enabled = !leaving) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White)
            }
            Column(
                Modifier.weight(1f).fillMaxWidth().padding(24.dp).verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
            ) {
                Text(text, color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = Color.White, textAlign = TextAlign.Center)
                }
            }
            PrimaryButton(buttonLabel, {
                if (!leaving && onNext()) leaving = true
            }, Modifier.fillMaxWidth().padding(24.dp), enabled = !leaving)
        }
    }
}

@Composable
internal fun OnboardingSongGuideScreen(
    nickname: String? = null,
    error: String? = null,
    onBack: () -> Unit,
    onNext: () -> Boolean,
    onFinished: () -> Unit,
) = OnboardingStageGuideScreen(
    text = timbreGuideCopy(nickname),
    buttonLabel = "노래 선택하기",
    testTag = "onboarding-song-guide",
    error = error,
    onBack = onBack,
    onNext = onNext,
    onFinished = onFinished,
)
