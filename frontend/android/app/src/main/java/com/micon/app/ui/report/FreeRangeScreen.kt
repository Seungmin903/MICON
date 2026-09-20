package com.micon.app.ui.report

import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.audio.PitchCapture
import com.micon.app.audio.Pcm16MonoWav
import com.micon.app.range.FreeRangeSession
import com.micon.app.range.PendingRange
import com.micon.app.range.RangeFrame
import com.micon.app.range.RangeInputQuality
import com.micon.app.range.RangeReport
import com.micon.app.range.RangeRepository
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.rememberMicrophoneAccess
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

internal class FreeRangeViewModel(context: Context, private val repository: RangeRepository,
    private val onboarding: Boolean) : ViewModel() {
    data class State(
        val view: FreeRangeSession.View,
        val recording: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val savedId: String? = null,
        val report: RangeReport? = null,
    )
    private val session = repository.freeCheckpoint()?.let(FreeRangeSession::restore) ?: FreeRangeSession()
    private val capture = PitchCapture(collectCandidates = true)
    private val pausing = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow(State(session.view()))
    val state = stateFlow.asStateFlow()

    fun permissionDenied() { stateFlow.update { it.copy(error = "마이크 권한을 허용해 주세요.") } }
    fun resume() { if (session.view().phase == "done") save() else start() }

    fun start() {
        if (stateFlow.value.recording || stateFlow.value.busy || stateFlow.value.savedId != null) return
        val phase = session.view().phase
        val wav = repository.audio(session.id)
        if (phase == "transition") {
            val durableSeconds = runCatching { Pcm16MonoWav.duration(wav, Pcm16MonoWav.sampleRate(wav), false) }
                .getOrNull() ?: capture.capturedSeconds
            session.advance(maxOf(capture.capturedSeconds, durableSeconds))
        }
        if (phase == "done") return
        stateFlow.update { it.copy(recording = true, error = null, view = session.view()) }
        try {
            val callback: (PitchCapture.PitchFrame) -> Unit = { f ->
                val view = session.onFrame(RangeFrame(f.t, f.duration, f.f0, f.clarity, f.rms, f.rmsLow, f.clippedRatio))
                stateFlow.update { it.copy(view = view) }
                if (view.phase in setOf("transition", "done", "failed") && pausing.compareAndSet(false, true)) {
                    viewModelScope.launch {
                        try {
                            withContext(Dispatchers.IO) { capture.stop() }
                            session.checkpoint(capture.capturedSeconds)?.let { checkpoint ->
                                withContext(Dispatchers.IO) { repository.saveFreeCheckpoint(checkpoint) }
                            }
                            stateFlow.update { it.copy(recording = false, view = session.view()) }
                            if (session.view().phase == "done") save()
                            else if (session.view().phase == "transition") {
                                delay(1_200)
                                start()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            stateFlow.update { it.copy(recording = false, error = "녹음을 저장하지 못했어요. 다시 시도해 주세요.") }
                        } finally { pausing.set(false) }
                    }
                }
            }
            if (wav.isFile && wav.length() >= 44) capture.resume(wav, ::captureFailed, callback)
            else capture.start(wav, ::captureFailed, callback)
        } catch (_: Exception) {
            stateFlow.update { it.copy(recording = false, error = "마이크를 시작하지 못했어요. 다시 시도해 주세요.") }
        }
    }

    private fun captureFailed(error: Throwable) {
        if (!pausing.compareAndSet(false, true)) return
        viewModelScope.launch {
            withContext(Dispatchers.IO) { capture.stop() }
            stateFlow.update { it.copy(recording = false, error = "녹음이 중단됐어요. 현재 단계를 다시 시도해 주세요.") }
            pausing.set(false)
        }
    }

    fun retryStage() {
        if (stateFlow.value.recording || stateFlow.value.busy) return
        if (session.view().phase == "done") { save(); return }
        if (!session.retryCurrent(capture.capturedSeconds)) return
        stateFlow.update { it.copy(view = session.view(), error = null) }
        start()
    }

    private fun save() {
        if (stateFlow.value.busy || stateFlow.value.savedId != null) return
        stateFlow.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val wav = repository.audio(session.id)
                val rate = Pcm16MonoWav.sampleRate(wav)
                withContext(Dispatchers.IO) { repository.repairWav(wav, rate) }
                val duration = Pcm16MonoWav.duration(wav, rate, true) ?: error("녹음 파일을 확인하지 못했어요.")
                val submission = checkNotNull(session.submission(duration))
                val ready = withContext(Dispatchers.IO) {
                    RangeInputQuality().inspect(submission, wav, rate, true)
                }
                check(ready.ready) { ready.message }
                withContext(Dispatchers.IO) { repository.savePending(PendingRange(submission, sampleRate = rate)) }
                stateFlow.update { it.copy(savedId = session.id) }
                if (!onboarding) {
                    val report = repository.submit { }
                    stateFlow.update { it.copy(report = report) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                stateFlow.update { it.copy(error = e.message ?: "음역 분석을 시작하지 못했어요. 녹음은 보존돼 있어요.") }
            } finally { stateFlow.update { it.copy(busy = false) } }
        }
    }

    override fun onCleared() {
        capture.stop()
        super.onCleared()
    }
}

/** Comfort fills as its note is held; a sweep fills as its fixed mission clock runs out. */
private fun stageProgress(view: FreeRangeSession.View): Double = when (view.phase) {
    "comfort" -> (view.holdSeconds / FreeRangeSession.HOLD_SECONDS).coerceIn(0.0, 1.0)
    "down", "up" -> (view.elapsed / FreeRangeSession.MISSION_SECONDS).coerceIn(0.0, 1.0)
    else -> 0.0
}

@Composable
fun FreeRangeScreen(onBack: () -> Unit, repository: RangeRepository? = null,
    onMeasurementSaved: ((String) -> Unit)? = null) {
    val context = LocalContext.current
    val vm: FreeRangeViewModel = viewModel(factory = viewModelFactory {
        initializer { FreeRangeViewModel(context.applicationContext, repository ?: RangeRepository(context), onMeasurementSaved != null) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val start = rememberMicrophoneAccess(vm::start, vm::permissionDenied)
    LaunchedEffect(vm) { if (state.view.phase == "done") vm.resume() else start() }
    LaunchedEffect(state.savedId) { state.savedId?.let { if (onMeasurementSaved != null) onMeasurementSaved(it) } }
    BackHandler(state.recording) { onBack() }
    val view = state.view
    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("음역 측정", onBack)
        Column(Modifier.fillMaxSize().then(if (view.phase == "done") Modifier.verticalScroll(rememberScrollState()) else Modifier)
            .padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            val number = (view.completed + 1).coerceAtMost(3)
            Text("$number/3", fontSize = 28.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.testTag("free-range-step"))
            LinearProgressIndicator(progress = {
                ((view.completed + stageProgress(view)) / 3.0).toFloat().coerceIn(0f, 1f)
            }, Modifier.fillMaxWidth())
            Text(view.message, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            if (view.phase == "comfort") {
                Text("음 유지 ${"%.1f".format(view.holdSeconds.coerceAtMost(FreeRangeSession.HOLD_SECONDS))}/0.5초", color = Grey)
                LinearProgressIndicator(progress = { (view.holdSeconds / FreeRangeSession.HOLD_SECONDS).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
            if (view.phase in setOf("down", "up")) {
                Text("남은 시간 ${ceil(view.remainingSeconds).toInt().coerceIn(0, FreeRangeSession.MISSION_SECONDS.toInt())}초",
                    color = Grey, modifier = Modifier.testTag("free-range-remaining"))
                LinearProgressIndicator(progress = { stageProgress(view).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
                Text(view.extremeMidi?.let {
                    "지금까지 ${if (view.phase == "down") "가장 낮은" else "가장 높은"} 음 ${ScoringMath.midiName(it)}"
                } ?: "아직 기록된 음이 없어요",
                    color = Grey, modifier = Modifier.testTag("free-range-extreme"))
            }
            state.error?.let { Text(it, color = Red) }
            if (view.phase != "done") Spacer(Modifier.weight(1f))
            when {
                view.phase == "transition" -> Text("다음 단계 준비 중…", color = Grey)
                view.phase == "done" && state.report != null -> RangeResultCards(state.report!!)
                view.phase == "done" && state.error != null -> PrimaryButton("분석 다시 시도", vm::retryStage, Modifier.fillMaxWidth())
                !state.recording && !state.busy && state.error == null && view.phase != "failed" ->
                    PrimaryButton("측정 시작", start, Modifier.fillMaxWidth())
            }
            if (!state.recording && view.phase != "done" && (view.phase == "failed" || state.error != null))
                PrimaryButton("이 단계 다시 시도", vm::retryStage, Modifier.fillMaxWidth())
        }
    }
}
