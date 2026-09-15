package com.micon.app.ui.report

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.audio.PitchCapture
import com.micon.app.audio.ReferenceTone
import com.micon.app.range.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GuidedRangeViewModel(context: Context, extension: Boolean = false,
    private val repository: RangeRepository = RangeRepository(context),
) : ViewModel() {
    data class State(
        val running: Boolean = false, val finished: Boolean = false, val uploading: Boolean = false,
        val level: Float = 0f, val session: RangeSession.View? = null, val report: RangeReport? = null,
        val error: String? = null, val analysisMessage: String = "", val hasPending: Boolean = false,
        val hasAttempts: Boolean = false, val stopReason: String? = null,
        val preparationFailed: Boolean = false, val previewPlaying: Boolean = false, val previewMidi: Int = 60,
        val soundReady: Boolean = false, val soundLoading: Boolean = true,
        val keyboardNotes: List<RangeNote> = emptyList(),
        val pausedForChoice: Boolean = false,
        val diagnosticMessage: String? = null,
    )
    private val appContext = context.applicationContext
    private val keepOriginal = false
    private val parentReport = if (extension) repository.localReport()
        ?.takeIf { it.protocolVersion == RangeProtocol.VERSION && it.analysisId != null && it.availability == "available" } else null
    private val missingParent = extension && parentReport == null
    private val session = RangeSession(UUID.randomUUID().toString(), parentReport)
    private val capture = PitchCapture(fmin = 60.0, fmax = 1150.0, collectCandidates = true)
    private val pitchSelector = GuidedPitchSelector()
    private val tone = ReferenceTone(context.applicationContext)
    private val displayFilter = PitchDisplayFilter()
    private data class CapturedFrame(val frame: PitchCapture.PitchFrame, val sequence: Long, val capturedNanos: Long)
    private val frameChannel = Channel<CapturedFrame>(64, kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
    private val frameSequence = AtomicLong(0)
    private var lastConsumedSequence = 0L
    @Volatile private var diagnostic: RangeDiagnostics? = null
    private var lastRenderedKey: Triple<Double, Int, Double?>? = null
    private val finishing = AtomicBoolean(false)
    private val changingCapture = AtomicBoolean(false)
    private val persistenceLock = Any()
    private var cueJob: Job? = null
    private var playedCue = 0
    private var lastCheckpoint = 0.0
    private val restored = repository.pending()
    private val _state = MutableStateFlow(State(hasPending = restored != null,
        diagnosticMessage = if (RangeDiagnostics.armed(context)) "진단 준비됨 · 다음 측정 1회의 원음과 판정 로그를 기기에 보관해요" else null,
        hasAttempts = restored?.submission?.attempts?.isNotEmpty() == true, stopReason = restored?.submission?.stopReason,
        preparationFailed = restored?.submission?.needsPreparationRetry() == true, previewMidi = restored?.submission?.startMidi ?: 60,
        error = if (missingParent) "추가 측정할 결과가 없습니다. 리포트에서 기본 측정을 먼저 완료해 주세요." else null))
    val state = _state.asStateFlow()

    init {
        prepareReference()
        viewModelScope.launch(Dispatchers.Default) {
            for (captured in frameChannel) {
                if (finishing.get()) continue
                val f = captured.frame
                val previousView = if (diagnostic != null) _state.value.session else null
                val before = session.view()
                val selection = pitchSelector.select(RangeFrame(f.t, f.duration, f.f0, f.clarity, f.rms, f.rmsLow, f.clippedRatio),
                    f.initialF0, f.initialClarity, before.rmsFloor,
                    "${before.cueId}:${before.phase}:${before.referenceGeneration}", before.inputSuppressed)
                val raw = session.onFrame(selection.frame)
                val shown = displayFilter.update(raw.currentMidi, raw.elapsed, raw.cueId, raw.targetMidi, raw.inputSuppressed)
                val view = raw.copy(currentMidi = shown, cents = raw.targetMidi?.let { target -> shown?.let { 100 * (it - target) } })
                diagnostic?.trace?.let { trace ->
                    trace.record("evaluated", mapOf("sequence" to captured.sequence, "audioTime" to f.t,
                        "queueDelayMs" to (System.nanoTime() - captured.capturedNanos) / 1_000_000.0,
                        "missingSequences" to (captured.sequence - lastConsumedSequence - 1).coerceAtLeast(0),
                        "decision" to session.lastDiagnostic, "pitchSelection" to selection.reason,
                        "selectedF0" to selection.frame.f0, "recentMidi" to selection.recentMidi,
                        "previousDisplayMidi" to previousView?.currentMidi,
                        "displayMidi" to shown, "displayCents" to view.cents, "displayHold" to view.holdProgress * 2.5,
                        "targetAfter" to view.targetMidi, "cueAfter" to view.cueId, "phaseAfter" to view.phase))
                    if (trace.failure != null || trace.dropped.get() > 0) _state.update {
                        it.copy(diagnosticMessage = "진단 로그 일부를 저장하지 못했어요 · 원음은 보관해요")
                    }
                }
                lastConsumedSequence = captured.sequence
                _state.update { it.copy(session = view, level = (f.rms / .12).toFloat().coerceIn(0f, 1f),
                    keyboardNotes = RangeKeyboardState.notes(parentReport?.notes.orEmpty(), raw.attempts)) }
                if (view.done) { finish("completed"); continue }
                if (view.phase == "profile_ready") { pauseForChoice(); continue }
                if (view.waitingForCue && view.cueId != playedCue) {
                    playedCue = view.cueId
                    cueJob?.cancel()
                    cueJob = viewModelScope.launch {
                        try {
                            diagnosticEvent("cue_started", mapOf("cueId" to view.cueId, "targetMidi" to view.cueMidi))
                            tone.play(view.cueMidi!!, view.cueDurationSeconds)
                            diagnosticEvent("cue_finished", mapOf("cueId" to view.cueId))
                            session.cueFinished(view.cueId, capture.capturedSeconds)
                        } catch (e: CancellationException) { diagnosticEvent("cue_cancelled", mapOf("cueId" to view.cueId)); throw e }
                        catch (e: Exception) { finish("input_quality", "기준음을 재생하지 못했습니다. 소리 출력을 확인해 주세요.") }
                    }
                }
                if (view.elapsed - lastCheckpoint >= 2.0) {
                    lastCheckpoint = view.elapsed
                    try { withContext(Dispatchers.IO) { if (!finishing.get()) checkpoint() } }
                    catch (e: CancellationException) { throw e }
                    catch (e: Exception) { finish("input_quality", "측정 기록을 저장하지 못했어요. 저장 공간을 확인해 주세요.") }
                }
            }
        }
    }

    private fun diagnosticEvent(type: String, fields: Map<String, Any?> = emptyMap()) {
        diagnostic?.trace?.record(type, fields + ("audioTime" to capture.capturedSeconds))
    }

    private fun onCaptured(f: PitchCapture.PitchFrame) {
        val sequence = frameSequence.incrementAndGet()
        val stamp = System.nanoTime()
        diagnostic?.trace?.record("captured", mapOf("sequence" to sequence, "audioTime" to f.t,
            "duration" to f.duration, "f0" to f.f0.takeIf { it.isFinite() },
            "clarity" to f.clarity.takeIf { it.isFinite() }, "rms" to f.rms, "rmsLow" to f.rmsLow,
            "clippedRatio" to f.clippedRatio, "initialF0" to f.initialF0, "initialClarity" to f.initialClarity,
            "sampleRate" to capture.recordingRate,
            "analysisRate" to capture.analysisRate, "capturedNanos" to stamp))
        frameChannel.trySend(CapturedFrame(f, sequence, stamp))
    }

    /** Compose commit observation, distinct from the faster producer-side display value. */
    fun displayed(view: RangeSession.View?) {
        if (diagnostic == null || view == null) return
        val key = Triple(view.elapsed, view.cueId, view.currentMidi)
        if (key == lastRenderedKey) return
        lastRenderedKey = key
        diagnostic?.trace?.record("rendered", mapOf("audioTime" to view.elapsed, "cueId" to view.cueId,
            "targetMidi" to view.targetMidi, "displayMidi" to view.currentMidi, "cents" to view.cents,
            "holdSeconds" to view.holdProgress * 2.5, "suppressed" to view.inputSuppressed))
    }

    fun start() {
        if (!_state.value.soundReady) return
        if (missingParent || _state.value.running || _state.value.finished || _state.value.hasPending || _state.value.previewPlaying) return
        _state.update { it.copy(running = true, session = session.view(), error = null) }
        try {
            diagnostic = diagnostic ?: RangeDiagnostics.claim(appContext, session.id, repository.audio(session.id))
            session.diagnosticsEnabled = diagnostic != null
            if (diagnostic != null) _state.update { it.copy(diagnosticMessage = "진단 기록 중 · 이번 측정의 원음과 판정 로그를 기기에 보관해요") }
            capture.start(repository.audio(session.id), onError = {
                diagnosticEvent("capture_error", mapOf("errorType" to it.javaClass.simpleName))
                finish("input_quality", "마이크 입력이 중단되었습니다.")
            }, onFrame = ::onCaptured)
            viewModelScope.launch {
                var previous = -1.0
                while (_state.value.running && !finishing.get()) {
                    delay(2000)
                    if (_state.value.pausedForChoice || changingCapture.get()) { previous = -1.0; continue }
                    val current = capture.capturedSeconds
                    if (current == previous) { finish("input_quality", "마이크 입력이 멈췄습니다."); break }
                    previous = current
                }
            }
        } catch (e: Exception) {
            capture.stop()
            diagnosticEvent("start_error", mapOf("errorType" to e.javaClass.simpleName))
            _state.update { it.copy(running = false, error = "측정을 시작하지 못했습니다. 마이크 권한과 저장 공간을 확인해 주세요.") }
        }
    }

    fun permissionDenied() { _state.update { it.copy(error = "측정하려면 마이크 권한이 필요합니다. 기기 설정에서 허용할 수 있어요.") } }
    fun replay() { diagnosticEvent("replay_requested"); session.replay(capture.capturedSeconds) }
    fun next(visitId: String?) {
        if (!_state.value.running || finishing.get()) return
        diagnosticEvent("manual_next_requested", mapOf("visitId" to visitId))
        if (session.next(visitId, capture.capturedSeconds)) {
            cueJob?.cancel()
            displayFilter.reset()
            val view = session.view()
            _state.update { it.copy(session = view, keyboardNotes = RangeKeyboardState.notes(parentReport?.notes.orEmpty(), view.attempts)) }
            if (view.done) finish("completed")
            else if (view.phase == "profile_ready") viewModelScope.launch { pauseForChoice() }
        }
    }
    private suspend fun pauseForChoice() {
        if (_state.value.pausedForChoice || !changingCapture.compareAndSet(false, true)) return
        try {
            cueJob?.cancel()
            withContext(Dispatchers.IO) {
                synchronized(persistenceLock) { capture.stop(); if (!finishing.get()) checkpoint() }
            }
            while (frameChannel.tryReceive().isSuccess) { /* discard queued pre-pause frames */ }
            diagnosticEvent("recording_paused")
            displayFilter.reset()
            _state.update { if (finishing.get()) it else it.copy(pausedForChoice = true, level = 0f) }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { finish("input_quality", "측정 기록을 저장하지 못했어요.") }
        finally { changingCapture.set(false) }
    }

    fun continueMeasurement() {
        if (!_state.value.pausedForChoice || finishing.get() || !changingCapture.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (session.continueMeasurement(capture.capturedSeconds)) {
                    _state.update { it.copy(pausedForChoice = false, session = session.view()) }
                    if (session.view().done) finish("completed")
                    else {
                        diagnosticEvent("recording_resumed")
                        capture.resume(repository.audio(session.id), onError = {
                            diagnosticEvent("capture_error", mapOf("errorType" to it.javaClass.simpleName))
                            finish("input_quality", "마이크 입력이 중단되었습니다.")
                        }, onFrame = ::onCaptured)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { finish("input_quality", "마이크를 다시 시작하지 못했어요. 여기까지의 기록을 보존합니다.") }
            finally { changingCapture.set(false) }
        }
    }

    fun previewReference() {
        if (!_state.value.soundReady) return
        if (_state.value.running || _state.value.previewPlaying || _state.value.uploading) return
        _state.update { it.copy(previewPlaying = true, error = null) }
        cueJob = viewModelScope.launch {
            try {
                tone.play(_state.value.previewMidi, 1.0)
                delay(250) // Let the cue decay before microphone/noise calibration can start.
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(error = "기준음을 재생하지 못했어요. 미디어 음량과 소리 출력을 확인해 주세요.") } }
            finally { _state.update { it.copy(previewPlaying = false) } }
        }
    }

    fun prepareReference() {
        if (_state.value.soundReady) return
        _state.update { it.copy(soundLoading = true,
            error = if (missingParent) "추가 측정할 결과가 없습니다. 리포트에서 기본 측정을 먼저 완료해 주세요." else null,
            keyboardNotes = parentReport?.notes.orEmpty()) }
        viewModelScope.launch {
            try { tone.prepare(); _state.update { it.copy(soundReady = true, soundLoading = false) } }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { _state.update { it.copy(soundLoading = false, error = "피아노 기준음을 준비하지 못했어요. 다시 준비해 주세요.") } }
        }
    }

    private fun checkpoint() {
        synchronized(persistenceLock) {
            if (finishing.get()) return
            val current = session.submission()
            if (current.durationSeconds > 0) repository.savePending(PendingRange(
                current.copy(stopReason = "app_interrupted", keepOriginal = keepOriginal), sampleRate = capture.recordingRate), checkpoint = true)
            diagnostic?.snapshot(current, capture.recordingRate)
        }
    }

    fun finish(reason: String = "user_stopped", error: String? = null) {
        if (!_state.value.running || !finishing.compareAndSet(false, true)) return
        diagnosticEvent("finish_requested", mapOf("reason" to reason))
        cueJob?.cancel()
        viewModelScope.launch {
            val submission = session.stop(reason, capture.capturedSeconds).copy(keepOriginal = keepOriginal)
            val preparationFailed = submission.needsPreparationRetry()
            val saved = try {
                withContext(Dispatchers.IO) {
                    synchronized(persistenceLock) {
                        capture.stop()
                        repository.savePending(PendingRange(submission, sampleRate = capture.recordingRate))
                        runCatching { diagnostic?.finish(submission, capture.recordingRate) }.onFailure {
                            _state.update { s -> s.copy(diagnosticMessage = "진단 로그 마무리에 실패했어요 · 원음과 저장된 기록은 보관해요") }
                        }.onSuccess {
                            if (diagnostic != null) _state.update { s -> s.copy(diagnosticMessage = "진단 기록 저장됨 · 이번 측정의 원음과 판정 로그를 보관했어요") }
                        }
                    }
                }
                true
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { false }
            _state.update { it.copy(running = false, finished = true, pausedForChoice = false, session = session.view(), hasPending = saved,
                hasAttempts = submission.attempts.isNotEmpty(), stopReason = submission.stopReason,
                error = if (saved) error else "측정 기록을 저장하지 못했어요. 저장 공간을 확인해 주세요.",
                preparationFailed = preparationFailed, previewMidi = submission.startMidi ?: 60) }
            if (saved && !preparationFailed && submission.attempts.isNotEmpty()) retryUpload()
        }
    }

    fun retryUpload() {
        if (_state.value.uploading || _state.value.preparationFailed) return
        _state.update { it.copy(uploading = true, error = null) }
        viewModelScope.launch {
            try {
                val report = repository.submit { message -> _state.update { it.copy(analysisMessage = message) } }
                _state.update { it.copy(report = report, finished = true, hasPending = false) }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(error = if (repository.deviceOnly)
                    "측정 결과를 정리하지 못했어요. 다시 시도하거나 새로 측정해 주세요."
                    else "모델 분석에 연결하지 못했습니다. PC와 USB 연결을 확인한 뒤 다시 분석해 주세요. 녹음은 보존되어 있어요.") }
            } finally { _state.update { it.copy(uploading = false) } }
        }
    }

    fun discardPending() {
        repository.discardPending()
        _state.update { it.copy(hasPending = false, error = null) }
    }

    override fun onCleared() {
        cueJob?.cancel()
        if (_state.value.running && (capture.isRunning || _state.value.pausedForChoice)) {
            val submission = session.stop("app_interrupted", capture.capturedSeconds).copy(keepOriginal = keepOriginal)
            synchronized(persistenceLock) {
                capture.stop()
                repository.savePending(PendingRange(submission, sampleRate = capture.recordingRate))
                runCatching { diagnostic?.finish(submission, capture.recordingRate) }
            }
        }
        runCatching { diagnostic?.trace?.close() }
        frameChannel.close()
    }
}
