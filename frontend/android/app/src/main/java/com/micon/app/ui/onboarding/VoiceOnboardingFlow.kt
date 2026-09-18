package com.micon.app.ui.onboarding

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import androidx.navigation.NavType
import androidx.navigation.compose.*
import androidx.navigation.navArgument
import com.micon.app.appContainer
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.onboarding.*
import com.micon.app.range.RangeReport
import com.micon.app.ui.common.*
import com.micon.app.ui.report.GuidedRangeScreen
import com.micon.app.ui.report.RangeResultCards
import com.micon.app.ui.report.VoiceReportContent
import com.micon.app.ui.report.ReportFitCard
import com.micon.app.ui.singanalysis.SingAnalysisScreen
import com.micon.app.ui.singanalysis.SingAnalysisSongPickerScreen
import com.micon.app.ui.theme.*

@Composable
fun VoiceOnboardingFlow(isGuest: Boolean, onExit: () -> Unit, onLogin: () -> Unit) {
    val context = LocalContext.current
    val ownerId = if (isGuest) null else TokenManager.getUserId().takeIf { it > 0 }
    val store = remember(ownerId) { VoiceOnboardingStore(context.applicationContext, ownerId, context.appContainer.backend.api) }
    val gateway = context.appContainer.voiceOnboarding
    val vm: VoiceOnboardingViewModel = viewModel(key = "voice-onboarding-$ownerId", factory = viewModelFactory {
        initializer { VoiceOnboardingViewModel(ownerId, store, gateway,
            isCurrentOwner = { if (ownerId == null) !TokenManager.isLoggedIn() else TokenManager.getUserId() == ownerId },
            onDispose = store::disposeGuest, rangeAnalysis = store.rangeAnalysis) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    var confirmExit by remember { mutableStateOf(false) }
    fun leave() { if (isGuest) confirmExit = true else onExit() }

    key(state.draftId) {
        val nav = rememberNavController()
        val initial = remember { when {
            state.expired -> "expired"
            state.recording != null -> "result"
            state.canSelectSong -> "pick"
            else -> "measure"
        } }
        NavHost(navController = nav, startDestination = initial) {
            composable("expired") {
                BackHandler { leave() }
                Column(Modifier.fillMaxSize().padding(24.dp), verticalArrangement = Arrangement.Center) {
                    Text("이전 분석이 만료됐어요", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("음역부터 새로 측정해 주세요.", color = Grey)
                    Spacer(Modifier.height(24.dp))
                    PrimaryButton("새로 측정하기", vm::restart, Modifier.fillMaxWidth())
                    TextButton(onClick = ::leave) { Text("나가기") }
                }
            }
            composable("measure") {
                BackHandler { leave() }
                GuidedRangeScreen(onBack = ::leave, onDone = ::leave, onRestart = vm::restart,
                    repository = store.rangeRepository, onboardingIntro = true, onMeasurementSaved = { measurementId ->
                        if (vm.rangeMeasurementSaved(measurementId)) {
                            nav.navigate("pick") { popUpTo("measure") { inclusive = true } }
                        }
                    })
            }
            composable("pick") {
                BackHandler { leave() }
                SingAnalysisSongPickerScreen(onBack = ::leave, onSongClick = { nav.navigate("sing/$it") })
            }
            composable("sing/{songId}", arguments = listOf(navArgument("songId") { type = NavType.StringType })) { entry ->
                val songId = entry.arguments?.getString("songId") ?: return@composable
                SingAnalysisScreen(songId, onBack = { nav.popBackStack() }, onBackToReport = ::leave,
                    recordings = store.recordings, onboardingDraftId = state.draftId, onRecordingReady = {
                        vm.recordingReady(it)
                        nav.navigate("result") { popUpTo("pick") { inclusive = true } }
                    })
            }
            composable("result") {
                BackHandler(enabled = !state.saving) { leave() }
                BackHandler(enabled = state.saving) { }
                OnboardingAutomaticAnalysis(state, vm::analyzeWhenReady)
                VoiceOnboardingResultScreen(state, isGuest,
                    onExit = ::leave,
                    onLogin = { vm.discard(); onLogin() },
                    onAnalyze = { vm.analyze() },
                    onCancel = vm::cancelAnalysis,
                    onRetry = vm::analyze,
                    onSave = vm::save,
                    onRecordAgain = {
                        vm.newRecording()
                        nav.navigate("pick") { popUpTo("result") { inclusive = true } }
                    },
                    onRestart = vm::restart,
                    onRetryRange = vm::retryRangeAnalysis)
            }
        }
    }
    if (confirmExit) AlertDialog(onDismissRequest = { confirmExit = false },
        title = { Text("분석을 나갈까요?") },
        text = { Text("비회원 분석 결과와 녹음은 나가면 지워져 다시 볼 수 없어요.") },
        confirmButton = { TextButton(onClick = { vm.discard(); onExit() }) { Text("나가기") } },
        dismissButton = { TextButton(onClick = { confirmExit = false }) { Text("계속하기") } })
}

@Composable
fun VoiceOnboardingResultScreen(
    state: VoiceOnboardingState, isGuest: Boolean,
    onExit: () -> Unit, onLogin: () -> Unit, onAnalyze: () -> Unit, onCancel: () -> Unit,
    onRetry: (AnalysisComponent) -> Unit, onSave: () -> Unit, onRecordAgain: () -> Unit, onRestart: () -> Unit,
    onRetryRange: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize()) {
        BackHeader("목소리 분석 결과", onBack = { if (!state.saving) onExit() })
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)) {
            Text(if (state.saved) "내 목소리 분석 결과" else if (state.serviceAvailable) "내 목소리를 알아가는 중" else "내 목소리 측정 결과",
                fontSize = 25.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(if (state.serviceAvailable) "준비된 결과부터 확인할 수 있어요."
                else "음색 분석과 프로필 저장 기능을 준비 중이에요. 지금은 측정한 음역을 확인할 수 있어요.",
                color = Grey, modifier = Modifier.testTag("onboarding_service_notice"))
            val progress = state.progress
            state.range?.let { range ->
                VoiceReportContent(range, progress,
                    retryEnabled = !isGuest && state.canRetryAnalysis,
                    onRetry = onRetry,
                    // Fit uses the explicitly saved server profile, so do not show the previous profile's fit.
                    songFit = if (state.saved && !isGuest && state.savedReceipt != null) {
                        { ReportFitCard(true, state.savedReceipt) }
                    } else null)
            }
            state.error?.let { Text(it, color = Red, modifier = Modifier.testTag("onboarding_error")) }
            if (state.rangeAnalysisStatus == OnboardingRangeStatus.FAILED) {
                Text(state.rangeAnalysisError ?: "음역 분석 결과를 확인하지 못했어요.", color = Red,
                    modifier = Modifier.testTag("onboarding_range_error"))
                OutlinePurpleButton("음역 분석 다시 확인하기", onRetryRange,
                    Modifier.fillMaxWidth().testTag("onboarding_range_retry"))
                OutlinePurpleButton("음역 다시 측정하기", onRestart, Modifier.fillMaxWidth())
            }
            if (!isGuest && state.serviceAvailable && !state.busy && !state.recovering && !state.waitingForRange &&
                state.rangeAnalysisStatus != OnboardingRangeStatus.FAILED && !state.saved && progress?.canSave != true) {
                state.analysisInputError?.takeUnless { it == state.error }?.let {
                    Text(it, color = Red, modifier = Modifier.testTag("onboarding_input_error"))
                }
            }
            if (state.busy || state.recovering || state.waitingForRange) {
                LinearProgressIndicator(Modifier.fillMaxWidth().testTag("onboarding_analysis_wait"))
                if (state.waitingForRange) Text("음역 분석 결과를 확인하고 있어요.", color = Grey)
                TextButton(onClick = onCancel) { Text("분석 확인 중단") }
            } else if (state.serviceAvailable && !state.saved && !state.expired &&
                state.rangeAnalysisStatus != OnboardingRangeStatus.FAILED && progress?.canSave != true) {
                OutlinePurpleButton("분석 다시 확인하기", onAnalyze, Modifier.fillMaxWidth(), enabled = !isGuest && state.canAnalyze)
            }
            if (state.expired) PrimaryButton("새로 측정하기", onRestart, Modifier.fillMaxWidth())
            else if (isGuest) {
                Text("비회원 결과는 이 화면을 나가면 지워져요. 로그인해도 자동으로 저장되지 않아요.", color = Grey)
                PrimaryButton("로그인하고 내 프로필 만들기", onLogin,
                    Modifier.fillMaxWidth().testTag("onboarding_login"))
            } else {
                PrimaryButton(if (state.saved) "프로필 저장됨" else if (state.saving) "저장 중…"
                    else if (!state.serviceAvailable) "프로필 저장 준비 중" else "내 프로필로 저장",
                    onSave, Modifier.fillMaxWidth().testTag("onboarding_save"), enabled = state.canSave)
                if (!state.saved && state.serviceAvailable) Text("음역과 음색 분석이 끝나면 저장할 수 있어요. 저장 전까지 기존 프로필이 유지돼요.", color = Grey)
            }
            if (!state.saved) OutlinePurpleButton("노래 다시 부르기", onRecordAgain, Modifier.fillMaxWidth(),
                enabled = !state.saving && !state.expired)
            if (!state.saving) TextButton(onClick = onExit) { Text("리포트로 돌아가기") }
        }
    }
}

@Composable
internal fun OnboardingAutomaticAnalysis(state: VoiceOnboardingState, onReady: () -> Unit) {
    val currentCallback by rememberUpdatedState(onReady)
    LaunchedEffect(state.draftId, state.recording?.id, state.canAnalyze, state.needsInitialTimbreAnalysis) {
        if (state.canAnalyze && state.needsInitialTimbreAnalysis) currentCallback()
    }
}

@Composable
fun VoiceRangeSummary(range: RangeReport) {
    RangeResultCards(range)
}
