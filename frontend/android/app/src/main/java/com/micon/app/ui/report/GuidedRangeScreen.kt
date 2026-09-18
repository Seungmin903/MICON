package com.micon.app.ui.report

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Mic
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first

@Composable
fun GuidedRangeScreen(onBack: () -> Unit, onDone: () -> Unit, extension: Boolean = false, onRestart: () -> Unit = onDone,
    repository: com.micon.app.range.RangeRepository? = null,
    onTimbreMeasurement: (() -> Unit)? = null,
    onMeasurementSaved: ((String) -> Unit)? = null,
    onboardingIntro: Boolean = false,
) {
    val context = LocalContext.current
    val vm: GuidedRangeViewModel = viewModel(factory = viewModelFactory {
        initializer { GuidedRangeViewModel(context.applicationContext, extension, repository ?: com.micon.app.range.RangeRepository(context),
            submitOnFinish = onMeasurementSaved == null, awaitAnchorConfirmation = onboardingIntro) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    OnboardingRangeTransition(state.savedMeasurementId, onMeasurementSaved)
    SideEffect { vm.displayed(state.session) }
    val start = com.micon.app.ui.common.rememberMicrophoneAccess(vm::start, vm::permissionDenied)
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) vm.finish("app_interrupted")
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    BackHandler(state.running) { vm.finish() }
    val introVisible = onboardingIntro && onboardingRangeIntroVisible(state)
    val guideActive = onboardingIntro && state.running && state.session?.phase == "anchor_ready"
    val guideVisible = guideActive && (state.pausedForGuide || state.resumingGuide)
    Box(Modifier.fillMaxSize()) {
        if (!introVisible && !guideVisible) {
            Column(Modifier.fillMaxSize().background(Color.White)) {
                BackHeader("음역 안정성 측정", onBack = { if (state.running) vm.finish() else onBack() })
                val scrollState = rememberScrollState()
                val contentModifier = Modifier.weight(1f).fillMaxWidth()
                    .then(if (state.running) Modifier else Modifier.verticalScroll(scrollState))
                    .padding(horizontal = if (state.running) 16.dp else 24.dp, vertical = if (state.running) 4.dp else 24.dp)
                Column(contentModifier, verticalArrangement = Arrangement.spacedBy(if (state.running) 4.dp else 18.dp)) {
                    val session = state.session
                    if (!state.soundReady && !state.running) {
                        if (state.soundLoading) Text("피아노 기준음을 준비하고 있어요", color = Grey)
                        else TextButton(onClick = vm::prepareReference) { Text("피아노 기준음 다시 준비") }
                    }
                    if (state.running && session != null) {
                        RangeMeasurementPanel(session, state.level, state.keyboardNotes, state.pausedForChoice, vm::replay,
                            modifier = Modifier.weight(1f))
                    } else if (state.preparationFailed) {
                        Text("시작음을 조금 더 맞춰볼까요?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                        if (state.stopReason == "input_quality") Text("주변 소리와 마이크 위치도 확인해 주세요.", color = Grey)
                        if (state.stopReason == "model_limit") Text("이번 테스트는 C2~C6 범위의 음을 확인할 수 있어요.", color = Grey)
                        if (state.hasAttempts) OutlinedButton(onClick = vm::previewReference, enabled = state.soundReady && !state.previewPlaying, modifier = Modifier.fillMaxWidth()) {
                            Text(if (state.previewPlaying) "기준음 재생 중…" else "시작음 ${ScoringMath.midiName(state.previewMidi)} 듣기")
                        }
                        PrimaryButton("편한 시작음 다시 찾기", { vm.discardPending(); onRestart() }, Modifier.fillMaxWidth(), enabled = !state.previewPlaying)
                        OutlinePurpleButton("여기서 나가기", onDone, Modifier.fillMaxWidth())
                    } else if (onMeasurementSaved != null && state.savedMeasurementId != null) {
                        // Navigation follows local persistence, without rendering the range result page.
                    } else if (onMeasurementSaved != null && (state.finished || state.hasPending)) {
                        Text("음역을 다시 측정해 주세요", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        Text("프로필을 만들 수 있을 때까지 측정하면 음색 테스트로 넘어갈 수 있어요.", color = Grey)
                        PrimaryButton("음역 다시 측정하기", onRestart, Modifier.fillMaxWidth())
                        TextButton(onClick = onBack) { Text("여기서 나가기") }
                    } else if (state.finished || state.hasPending) {
                        Text(if (state.report == null) "이번에 측정한 결과" else "이번에 확인한 음역", fontSize = 22.sp, fontWeight = FontWeight.Bold)
                        when (state.stopReason) {
                            "anchor_not_matched" -> "시작음 재측정 필요"
                            "input_quality" -> "마이크 입력 확인 필요"
                            "app_interrupted" -> "앱 중단 · 부분 결과 저장됨"
                            "model_limit" -> "측정 범위 도달"
                            "user_stopped" -> "측정 종료"
                            "time_limit" -> "측정 시간 종료"
                            else -> null
                        }?.let { Text(it, color = Grey) }
                        val report = state.report
                        if (report != null) {
                            if (onTimbreMeasurement != null) {
                                RangeMeasurementReport(report, onTimbreMeasurement,
                                    enabled = !state.uploading && !state.hasPending)
                            }
                            else if (repository?.deviceOnly == true) com.micon.app.ui.onboarding.VoiceRangeSummary(report)
                            else RangeReportCards(report)
                        }
                        else if (!state.uploading) Text("분석 대기 중", color = Grey)
                        if (state.uploading) {
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                            Text(state.analysisMessage)
                        } else if (state.hasPending) {
                            if (state.hasAttempts) PrimaryButton(if (repository?.deviceOnly == true) "결과 다시 확인하기" else "모델로 다시 분석하기", vm::retryUpload, Modifier.fillMaxWidth())
                            TextButton(onClick = { vm.discardPending(); onRestart() }) { Text("이 녹음을 지우고 새로 측정") }
                        } else {
                            TextButton(onClick = onRestart) { Text("새로 측정하기") }
                        }
                        if (onTimbreMeasurement == null) {
                            if (repository?.deviceOnly == true) {
                                PrimaryButton("다음 · 부를 곡 고르기", onDone, Modifier.fillMaxWidth(),
                                    enabled = state.report?.availability == "available" && !state.uploading)
                            } else OutlinePurpleButton("저장한 결과 보기", onDone, Modifier.fillMaxWidth())
                        }
                    } else {
                        Text("마이크로 목소리를 듣고 편하게 낼 수 있는 음역을 측정해요. 기준음을 따라 불러 주세요.", color = Grey)
                        PrimaryButton("측정 시작", { start() }, Modifier.fillMaxWidth(), enabled = state.soundReady && !state.previewPlaying)
                    }
                    state.error?.let { Text(it, color = Red) }
                }
                if (state.running) {
                    Surface(shadowElevation = 6.dp) {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            val active = state.session
                            RangePassGuide(active)
                            if (active?.phase == "profile_ready") {
                                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                    RangeProfileChoiceActions(state.pausedForChoice, { vm.finish("completed") }, vm::continueMeasurement)
                                }
                            } else RangeMeasurementActions(active, { vm.finish() }, vm::next)
                        }
                    }
                }
            }
        }
        OnboardingRangeIntroOverlay(visible = introVisible, calibrating = state.running,
            startEnabled = state.soundReady && !state.previewPlaying && !state.running,
            soundLoading = state.soundLoading, soundReady = state.soundReady, error = state.error,
            onStart = start, onRetrySound = vm::prepareReference,
            onBack = { if (state.running) vm.finish() else onBack() })
        OnboardingRangeGuideOverlay(guideActive = guideActive, visible = guideVisible,
            ready = state.pausedForGuide && !state.resumingGuide, error = state.error,
            onStart = vm::startAnchorMeasurement, onBack = { vm.finish() })
    }
}

@Composable
internal fun OnboardingRangeGuideOverlay(guideActive: Boolean, visible: Boolean, ready: Boolean,
    error: String? = null, onStart: () -> Unit, onBack: () -> Unit,
) {
    var step by remember { mutableIntStateOf(0) }
    val currentVisible by rememberUpdatedState(visible)
    LaunchedEffect(guideActive) {
        if (guideActive) {
            step = 0
            snapshotFlow { currentVisible }.first { it }
            delay(1500); step = 1
            delay(2000); step = 2
        }
    }
    AnimatedVisibility(visible, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
        Column(Modifier.fillMaxSize().background(Ink).testTag("onboarding-range-guide")) {
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(if (step == 0) "잘하셨어요!" else "이제 안내음을 듣고, 따라서 '아'를 내주세요",
                    color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = Color.White, textAlign = TextAlign.Center)
                }
                if (step == 2) {
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton(if (error == null) "측정 시작" else "다시 시도", onStart,
                        Modifier.fillMaxWidth(), enabled = ready)
                }
            }
        }
    }
}

internal fun onboardingRangeIntroVisible(state: GuidedRangeViewModel.State): Boolean =
    !state.finished && !state.hasPending && !state.preparationFailed &&
        (!state.running || state.session?.phase == "noise")

@Composable
internal fun OnboardingRangeIntroOverlay(visible: Boolean, calibrating: Boolean, startEnabled: Boolean,
    soundLoading: Boolean = false, soundReady: Boolean = true, error: String? = null,
    onStart: () -> Unit, onRetrySound: () -> Unit = {}, onBack: () -> Unit,
) {
    AnimatedVisibility(visible, enter = EnterTransition.None, exit = fadeOut(tween(300))) {
        Column(Modifier.fillMaxSize().background(Ink).testTag("onboarding-range-intro")) {
            IconButton(onClick = onBack, modifier = Modifier.padding(8.dp)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "뒤로", tint = Color.White)
            }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 24.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Filled.Mic, null, Modifier.size(128.dp), tint = Color.White)
                Spacer(Modifier.height(24.dp))
                Text(if (calibrating) "잠시 조용히 계세요" else "편한 목소리로 '아'를 내주세요",
                    color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center)
                error?.let {
                    Spacer(Modifier.height(16.dp))
                    Text(it, color = Color.White, textAlign = TextAlign.Center)
                }
            }
            Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                if (!soundReady && !calibrating) {
                    if (soundLoading) Text("피아노 기준음을 준비하고 있어요", color = Color.White)
                    else TextButton(onClick = onRetrySound) { Text("피아노 기준음 다시 준비", color = Color.White) }
                    Spacer(Modifier.height(12.dp))
                }
                PrimaryButton("측정 시작", onStart, Modifier.fillMaxWidth(), enabled = startEnabled)
            }
        }
    }
}

@Composable
internal fun OnboardingRangeTransition(measurementId: String?, onSaved: ((String) -> Unit)?) {
    val currentCallback by rememberUpdatedState(onSaved)
    LaunchedEffect(measurementId) {
        measurementId?.let { currentCallback?.invoke(it) }
    }
}
