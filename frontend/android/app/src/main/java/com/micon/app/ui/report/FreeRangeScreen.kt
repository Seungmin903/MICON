package com.micon.app.ui.report

import android.content.Context
import android.util.Log
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
import com.micon.app.BuildConfig
import com.micon.app.audio.PitchCapture
import com.micon.app.audio.Pcm16MonoWav
import com.micon.app.range.FreeRangeSession
import com.micon.app.range.LocalVoiceQualityAnalyzer
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
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.ceil

private class NeedsNewRangeMeasurement(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal class FreeRangeViewModel(context: Context, private val repository: RangeRepository,
    private val onboarding: Boolean) : ViewModel() {
    private val appContext = context.applicationContext
    data class State(
        val view: FreeRangeSession.View,
        val recording: Boolean = false,
        val busy: Boolean = false,
        val error: String? = null,
        val needsRerecord: Boolean = false,
        val savedId: String? = null,
        val report: RangeReport? = null,
    )
    private var session = if (onboarding) {
        // A new onboarding visit starts at the first measurement, even after process recreation.
        repository.discardUnfinishedFreeMeasurement()
        FreeRangeSession()
    } else repository.freeCheckpoint()?.let(FreeRangeSession::restore) ?: FreeRangeSession()
    private val capture = PitchCapture(collectCandidates = true)
    private val qualityFrames = mutableListOf<RangeFrame>()
    private var pausing = AtomicBoolean(false)
    private val stateFlow = MutableStateFlow(State(session.view()))
    val state = stateFlow.asStateFlow()
    private var appliedRestartVersion = 0L
    private val restartLock = Any()
    @Volatile private var attemptGeneration = 0L
    private var transitionJob: Job? = null
    private var saveJob: Job? = null

    private fun updateIfCurrent(generation: Long, change: (State) -> State) {
        stateFlow.update { current -> if (generation == attemptGeneration) change(current) else current }
    }

    /** A result-screen retry must reset the retained HOLO ViewModel as well as its stored audio. */
    fun restartMeasurement(version: Long) {
        synchronized(restartLock) {
            if (!onboarding || version <= appliedRestartVersion) return
            appliedRestartVersion = version
            attemptGeneration++
            transitionJob?.cancel()
            saveJob?.cancel()
            capture.stop()
            val oldId = session.id
            repository.discardUnfinishedFreeMeasurement()
            // A failed save may have left WAV audio without a checkpoint or pending submission.
            if (!com.micon.app.range.RangeDiagnostics.retainsAudio(appContext, oldId)) repository.audio(oldId).delete()
            session = FreeRangeSession()
            synchronized(qualityFrames) { qualityFrames.clear() }
            pausing = AtomicBoolean(false)
            stateFlow.value = State(session.view())
        }
    }

    fun permissionDenied() { stateFlow.update { it.copy(error = "마이크 권한을 허용해 주세요.") } }
    fun resume() { if (session.view().phase == "done") save() else start() }

    /** A completed recording with insufficient score evidence needs new audio, not another analysis of the same WAV. */
    fun retryMeasurement() {
        val nextVersion = synchronized(restartLock) { appliedRestartVersion + 1 }
        restartMeasurement(nextVersion)
    }

    fun start() {
        if (stateFlow.value.recording || stateFlow.value.busy || stateFlow.value.savedId != null) return
        val activeSession = session
        val generation = attemptGeneration
        val pauseFlag = pausing
        val phase = activeSession.view().phase
        val wav = repository.audio(activeSession.id)
        if (phase == "transition") {
            val durableSeconds = runCatching { Pcm16MonoWav.duration(wav, Pcm16MonoWav.sampleRate(wav), false) }
                .getOrNull() ?: capture.capturedSeconds
            activeSession.advance(maxOf(capture.capturedSeconds, durableSeconds))
        }
        if (phase == "done") return
        stateFlow.update { it.copy(recording = true, error = null, view = activeSession.view()) }
        try {
            val callback: (PitchCapture.PitchFrame) -> Unit = callback@{ f ->
                if (generation != attemptGeneration) return@callback
                val frame = RangeFrame(f.t, f.duration, f.f0, f.clarity, f.rms, f.rmsLow, f.clippedRatio)
                if (onboarding) synchronized(qualityFrames) { qualityFrames.add(frame) }
                val view = activeSession.onFrame(frame)
                updateIfCurrent(generation) { it.copy(view = view) }
                if (generation == attemptGeneration && view.phase in setOf("transition", "done", "failed") &&
                    pauseFlag.compareAndSet(false, true)) {
                    transitionJob = viewModelScope.launch {
                        try {
                            withContext(Dispatchers.IO) {
                                synchronized(restartLock) { if (generation == attemptGeneration) capture.stop() }
                            }
                            if (generation != attemptGeneration) return@launch
                            activeSession.checkpoint(capture.capturedSeconds)?.let { checkpoint ->
                                withContext(Dispatchers.IO) {
                                    synchronized(restartLock) {
                                        if (generation == attemptGeneration) repository.saveFreeCheckpoint(checkpoint)
                                    }
                                }
                            }
                            if (generation != attemptGeneration) return@launch
                            updateIfCurrent(generation) { it.copy(recording = false, view = activeSession.view()) }
                            if (activeSession.view().phase == "done") save()
                            else if (activeSession.view().phase == "transition") {
                                delay(1_200)
                                if (generation == attemptGeneration) start()
                            }
                        } catch (cancelled: CancellationException) { throw cancelled }
                        catch (_: Exception) {
                            updateIfCurrent(generation) { it.copy(recording = false, error = "녹음을 저장하지 못했어요. 다시 시도해 주세요.") }
                        } finally { pauseFlag.set(false) }
                    }
                }
            }
            if (wav.isFile && wav.length() >= 44) capture.resume(wav, { captureFailed(generation, pauseFlag, it) }, callback)
            else capture.start(wav, { captureFailed(generation, pauseFlag, it) }, callback)
        } catch (_: Exception) {
            updateIfCurrent(generation) { it.copy(recording = false, error = "마이크를 시작하지 못했어요. 다시 시도해 주세요.") }
        }
    }

    private fun captureFailed(generation: Long, pauseFlag: AtomicBoolean, error: Throwable) {
        if (generation != attemptGeneration) return
        if (!pauseFlag.compareAndSet(false, true)) return
        transitionJob = viewModelScope.launch {
            withContext(Dispatchers.IO) {
                synchronized(restartLock) { if (generation == attemptGeneration) capture.stop() }
            }
            updateIfCurrent(generation) { it.copy(recording = false, error = "녹음이 중단됐어요. 현재 단계를 다시 시도해 주세요.") }
            pauseFlag.set(false)
        }
    }

    fun retryStage() {
        if (stateFlow.value.recording || stateFlow.value.busy) return
        when (session.view().phase) {
            "done" -> { save(); return }
            "transition" -> {
                // The previous stage already completed; resume its paused handoff instead of retrying it.
                stateFlow.update { it.copy(error = null) }
                start()
                return
            }
        }
        if (!session.retryCurrent(capture.capturedSeconds)) return
        stateFlow.update { it.copy(view = session.view(), error = null) }
        start()
    }

    private fun save() {
        if (stateFlow.value.busy || stateFlow.value.savedId != null) return
        val activeSession = session
        val generation = attemptGeneration
        stateFlow.update { it.copy(busy = true, error = null, needsRerecord = false) }
        saveJob = viewModelScope.launch {
            try {
                val wav = repository.audio(activeSession.id)
                val rate = Pcm16MonoWav.sampleRate(wav)
                withContext(Dispatchers.IO) { repository.repairWav(wav, rate) }
                if (generation != attemptGeneration) return@launch
                val duration = Pcm16MonoWav.duration(wav, rate, true) ?: error("녹음 파일을 확인하지 못했어요.")
                val original = checkNotNull(activeSession.submission(duration))
                val ready = withContext(Dispatchers.IO) {
                    RangeInputQuality().inspect(original, wav, rate, true)
                }
                if (generation != attemptGeneration) return@launch
                if (!ready.ready) {
                    if (onboarding) throw NeedsNewRangeMeasurement(ready.message)
                    else error(ready.message)
                }
                val submission = if (onboarding) {
                    val frames = synchronized(qualityFrames) { qualityFrames.toList() }
                    val quality = try {
                        withContext(Dispatchers.IO) {
                            LocalVoiceQualityAnalyzer.analyze(wav, frames, original,
                                continuousScore = BuildConfig.EXPERIMENTAL_CONTINUOUS_VOICE_QUALITY)
                        }
                    } catch (cancelled: CancellationException) { throw cancelled }
                    catch (error: Exception) {
                        Log.w("FreeRangeViewModel", "Voice-quality analysis failed after range validation", error)
                        throw IllegalStateException("발성 유지 점수 분석에 실패했어요. 다시 시도해 주세요.", error)
                    }
                    val validQuality = quality?.takeIf { result ->
                        result.points.any { it.midi == result.referenceMidi && it.score.isFinite() }
                    }
                    if (validQuality == null) {
                        Log.w("FreeRangeViewModel", "Voice-quality evidence unavailable after range validation; frames=${frames.size}")
                        throw NeedsNewRangeMeasurement("발성 유지 점수를 계산하지 못했어요. 음역을 다시 측정해 주세요.")
                    }
                    original.copy(voiceQuality = validQuality)
                } else original
                if (generation != attemptGeneration) return@launch
                withContext(Dispatchers.IO) {
                    synchronized(restartLock) {
                        if (generation == attemptGeneration) repository.savePending(PendingRange(submission, sampleRate = rate))
                    }
                }
                if (generation != attemptGeneration) return@launch
                updateIfCurrent(generation) { it.copy(savedId = activeSession.id) }
                if (!onboarding) {
                    val report = repository.submit { }
                    updateIfCurrent(generation) { it.copy(report = report) }
                }
            } catch (cancelled: CancellationException) { throw cancelled }
            catch (e: Exception) {
                updateIfCurrent(generation) { it.copy(
                    error = e.message ?: "음역 분석을 시작하지 못했어요. 녹음은 보존돼 있어요.",
                    needsRerecord = e is NeedsNewRangeMeasurement) }
            } finally {
                updateIfCurrent(generation) { it.copy(busy = false) }
            }
        }
    }

    override fun onCleared() {
        capture.stop()
        super.onCleared()
    }
}

/** Leave visible progress until the sweep's voice and pitch conditions actually complete. */
private fun stageProgress(view: FreeRangeSession.View): Double = when (view.phase) {
    "comfort" -> (view.holdSeconds / FreeRangeSession.HOLD_SECONDS).coerceIn(0.0, 1.0)
    "down", "up" -> (view.elapsed / FreeRangeSession.missionSeconds(view.phase)).coerceIn(0.0, .95)
    else -> 0.0
}

@Composable
fun FreeRangeScreen(onBack: () -> Unit, repository: RangeRepository? = null,
    onMeasurementSaved: ((String) -> Unit)? = null,
    /**
     * 결과 페이지에서 "노래로 음색 측정하기" 를 눌렀을 때. null 이면 버튼을 그리지 않는다.
     * 음역 측정과 음색 측정을 한 흐름으로 잇지 않고, 사용자가 결과를 보고 직접 넘어가게 하려는 것이다.
     */
    onTimbreMeasurement: (() -> Unit)? = null) {
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
                Text("음 유지 ${"%.1f".format(view.holdSeconds.coerceAtMost(FreeRangeSession.HOLD_SECONDS))}/1.5초", color = Grey)
                LinearProgressIndicator(progress = { (view.holdSeconds / FreeRangeSession.HOLD_SECONDS).toFloat().coerceIn(0f, 1f) }, Modifier.fillMaxWidth())
            }
            if (view.phase in setOf("down", "up")) {
                // Past the mission time the stage says what it is still short of, not a countdown.
                Text(when {
                    view.elapsed < FreeRangeSession.missionSeconds(view.phase) ->
                        "남은 시간 ${ceil(view.remainingSeconds).toInt().coerceIn(0, FreeRangeSession.missionSeconds(view.phase).toInt())}초"
                    view.voicedSeconds < FreeRangeSession.MIN_VOICED_SECONDS ->
                        "인식한 목소리 ${view.voicedSeconds.toInt()} / ${FreeRangeSession.MIN_VOICED_SECONDS.toInt()}초"
                    else -> "낮은·높은 음의 안정적인 구간을 기다리는 중…"
                }, color = Grey, modifier = Modifier.testTag("free-range-remaining"))
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
                view.phase == "done" && state.needsRerecord && !state.busy ->
                    PrimaryButton("음역 다시 측정", { vm.retryMeasurement(); start() }, Modifier.fillMaxWidth())
                view.phase == "done" && state.error != null && !state.busy ->
                    PrimaryButton("분석 다시 시도", vm::retryStage, Modifier.fillMaxWidth())
                !state.recording && !state.busy && state.error == null && view.phase != "failed" ->
                    PrimaryButton("측정 시작", start, Modifier.fillMaxWidth())
            }
            if (view.phase == "done" && state.report != null && onTimbreMeasurement != null)
                PrimaryButton("노래로 음색 측정하기", onTimbreMeasurement, Modifier.fillMaxWidth())
            if (!state.recording && view.phase != "done" && (view.phase == "failed" || state.error != null))
                PrimaryButton("이 단계 다시 시도", vm::retryStage, Modifier.fillMaxWidth())
        }
    }
}
