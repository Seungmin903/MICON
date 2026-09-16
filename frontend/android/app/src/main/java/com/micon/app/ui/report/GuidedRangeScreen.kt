package com.micon.app.ui.report

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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

@Composable
fun GuidedRangeScreen(onBack: () -> Unit, onDone: () -> Unit, extension: Boolean = false, onRestart: () -> Unit = onDone,
    repository: com.micon.app.range.RangeRepository? = null,
    onTimbreMeasurement: (() -> Unit)? = null,
) {
    val context = LocalContext.current
    val vm: GuidedRangeViewModel = viewModel(factory = viewModelFactory {
        initializer { GuidedRangeViewModel(context.applicationContext, extension, repository ?: com.micon.app.range.RangeRepository(context)) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
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
    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("음역 안정성 측정", onBack = { if (state.running) vm.finish() else onBack() })
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            val session = state.session
            if (!state.soundReady && !state.running) {
                if (state.soundLoading) Text("피아노 기준음을 준비하고 있어요", color = Grey)
                else TextButton(onClick = vm::prepareReference) { Text("피아노 기준음 다시 준비") }
            }
            if (state.running && session != null) {
                RangeMeasurementPanel(session, state.level, state.keyboardNotes, state.pausedForChoice, vm::replay)
            } else if (state.preparationFailed) {
                Text("시작음을 조금 더 맞춰볼까요?", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                if (state.stopReason == "input_quality") Text("주변 소리와 마이크 위치도 확인해 주세요.", color = Grey)
                if (state.stopReason == "model_limit") Text("이번 테스트는 C2~C6 범위의 음을 확인할 수 있어요.", color = Grey)
                if (state.hasAttempts) OutlinedButton(onClick = vm::previewReference, enabled = state.soundReady && !state.previewPlaying, modifier = Modifier.fillMaxWidth()) {
                    Text(if (state.previewPlaying) "기준음 재생 중…" else "시작음 ${ScoringMath.midiName(state.previewMidi)} 듣기")
                }
                PrimaryButton("편한 시작음 다시 찾기", { vm.discardPending(); onRestart() }, Modifier.fillMaxWidth(), enabled = !state.previewPlaying)
                OutlinePurpleButton("여기서 나가기", onDone, Modifier.fillMaxWidth())
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
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    val active = state.session
                    if (active?.phase == "profile_ready") {
                        RangeProfileChoiceActions(state.pausedForChoice, { vm.finish("completed") }, vm::continueMeasurement)
                    } else {
                        OutlinePurpleButton("여기까지 측정하기", { vm.finish() }, Modifier.weight(1f))
                        if (active != null) RangeAdvanceButton(active, vm::next, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}
