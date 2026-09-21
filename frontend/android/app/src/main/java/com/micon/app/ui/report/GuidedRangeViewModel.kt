package com.micon.app.ui.report

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.audio.PitchCapture
import com.micon.app.audio.ReferenceTone
import com.micon.app.range.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class GuidedRangeViewModel(context: Context, extension: Boolean = false,
    private val repository: RangeRepository = RangeRepository(context),
    private val submitOnFinish: Boolean = true,
    awaitAnchorConfirmation: Boolean = false,
    private val capture: RangeRecorder = MicrophoneRangeRecorder(),
    private val awaitNoiseConfirmation: Boolean = false,
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
        val pausedForGuide: Boolean = false, val resumingGuide: Boolean = false,
        val pausedForIntro: Boolean = false, val resumingIntro: Boolean = false,
        val introLeaving: Boolean = false, val permissionMissing: Boolean = false,
        val diagnosticMessage: String? = null,
        val inputCheck: RangeInputCheck = RangeInputCheck(), val inputChecking: Boolean = false,
        val canSupplement: Boolean = true,
    )
    private val appContext = context.applicationContext
    private val keepOriginal = false
    private val parentReport = if (extension) repository.localReport()
        ?.takeIf { it.protocolVersion == RangeProtocol.VERSION && it.analysisId != null && it.availability == "available" } else null
    private val missingParent = extension && parentReport == null
    private val session = RangeSession(UUID.randomUUID().toString(), parentReport,
        awaitAnchorConfirmation = awaitAnchorConfirmation,
        previousCompletedTargets = parentReport?.let(repository::completedTargets),
        awaitComfortableConfirmation = awaitNoiseConfirmation)
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
    private val automaticPreparationRequested = AtomicBoolean(false)
    @Volatile private var guideCaptureStarted: CompletableDeferred<Unit>? = null
    @Volatile private var guideResumeError: Throwable? = null
    private val persistenceLock = Any()
    private var cueJob: Job? = null
    private var captureWatchdog: Job? = null
    private var inputJob: Job? = null
    private val inputQuality = RangeInputQuality()
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
        if (restored != null) viewModelScope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching { inputQuality.inspect(restored.submission, repository.audio(restored.submission.sessionId), restored.sampleRate, true) }
                    .getOrElse { RangeInputCheck(message = "녹음 파일을 확인할 수 없어요. 음역을 다시 측정해 주세요.") }
            }
            _state.update { it.copy(inputCheck = result) }
        }
        viewModelScope.launch(Dispatchers.Default) {
            for (captured in frameChannel) {
                if (finishing.get()) continue
                if (!_state.value.running) continue
                if (changingCapture.get()) continue
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
                if (view.phase == "comfortable_ready") { pauseRecording(Pause.INTRO); continue }
                if (view.phase == "anchor_ready") { pauseRecording(Pause.GUIDE); continue }
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
        guideCaptureStarted?.complete(Unit)
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

    fun start() = startRecording(requireSound = true)

    fun startAutomaticPreparation() {
        if (awaitNoiseConfirmation) startRecording(requireSound = false)
    }

    fun claimAutomaticPreparation(): Boolean = awaitNoiseConfirmation && !missingParent &&
        !_state.value.hasPending && !_state.value.finished && !_state.value.preparationFailed && !_state.value.running &&
        automaticPreparationRequested.compareAndSet(false, true)

    private fun startRecording(requireSound: Boolean) {
        if (requireSound && !_state.value.soundReady) return
        if (changingCapture.get() || finishing.get()) return
        if (missingParent || _state.value.running || _state.value.finished || _state.value.hasPending || _state.value.previewPlaying) return
        _state.update { it.copy(running = true, session = session.view(), error = null, permissionMissing = false) }
        try {
            diagnostic = diagnostic ?: RangeDiagnostics.claim(appContext, session.id, repository.audio(session.id))
            session.diagnosticsEnabled = diagnostic != null
            if (diagnostic != null) _state.update { it.copy(diagnosticMessage = "진단 기록 중 · 이번 측정의 원음과 판정 로그를 기기에 보관해요") }
            val audio = repository.audio(session.id)
            val onError: (Throwable) -> Unit = {
                diagnosticEvent("capture_error", mapOf("errorType" to it.javaClass.simpleName))
                captureFailed(if (awaitNoiseConfirmation) "마이크 입력이 중단되었습니다. 소음 측정을 다시 시도해 주세요."
                    else "마이크 입력이 중단되었습니다.")
            }
            if (awaitNoiseConfirmation && audio.isFile && audio.length() >= 44) capture.resume(audio, onError, ::onCaptured)
            else capture.start(audio, onError, ::onCaptured)
            captureWatchdog?.cancel()
            startInputChecks()
            captureWatchdog = viewModelScope.launch {
                var previous = -1.0
                while (_state.value.running && !finishing.get()) {
                    delay(2000)
                    if (_state.value.pausedForChoice || _state.value.pausedForGuide || _state.value.pausedForIntro || changingCapture.get()) { previous = -1.0; continue }
                    val current = capture.capturedSeconds
                    if (current == previous) {
                        captureFailed(if (awaitNoiseConfirmation) "마이크 입력이 멈췄습니다. 다시 시도해 주세요." else "마이크 입력이 멈췄습니다.")
                        break
                    }
                    previous = current
                }
            }
        } catch (e: Exception) {
            capture.stop()
            diagnosticEvent("start_error", mapOf("errorType" to e.javaClass.simpleName))
            _state.update { it.copy(running = false, error = "측정을 시작하지 못했습니다. 마이크 권한과 저장 공간을 확인해 주세요.") }
        }
    }

    private fun captureFailed(message: String) {
        if (!awaitNoiseConfirmation || session.view().phase != "noise") {
            finish("input_quality", message)
            return
        }
        viewModelScope.launch {
            if (finishing.get() || !changingCapture.compareAndSet(false, true)) return@launch
            try {
                withContext(Dispatchers.IO) { synchronized(persistenceLock) { capture.stop() } }
                while (frameChannel.tryReceive().isSuccess) { /* discard failed calibration frames */ }
                _state.update { it.copy(running = false, error = message) }
            } finally { changingCapture.set(false) }
        }
    }

    fun permissionDenied() { _state.update { it.copy(permissionMissing = true,
        error = "측정하려면 마이크 권한이 필요합니다. 기기 설정에서 허용할 수 있어요.") } }
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
    private enum class Pause(val phase: String, val log: String) {
        INTRO("comfortable_ready", "intro"), GUIDE("anchor_ready", "anchor_guide"), PROFILE("profile_ready", "profile_choice")
    }

    private suspend fun pauseForChoice() = pauseRecording(Pause.PROFILE)

    private suspend fun pauseRecording(reason: Pause) {
        if (_state.value.pausedForChoice || _state.value.pausedForGuide || _state.value.pausedForIntro || finishing.get() || !changingCapture.compareAndSet(false, true)) return
        try {
            cueJob?.cancel()
            withContext(Dispatchers.IO) {
                synchronized(persistenceLock) { capture.stop(); if (!finishing.get()) checkpoint() }
            }
            while (frameChannel.tryReceive().isSuccess) { /* discard queued pre-pause frames */ }
            diagnosticEvent("recording_paused", mapOf("reason" to reason.log))
            displayFilter.reset()
            _state.update { if (finishing.get()) it else it.copy(pausedForChoice = reason == Pause.PROFILE,
                pausedForGuide = reason == Pause.GUIDE, pausedForIntro = reason == Pause.INTRO, level = 0f) }
            if (reason == Pause.PROFILE) refreshInputCheck(finalized = true)
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { finish("input_quality", "측정 기록을 저장하지 못했어요.") }
        finally { changingCapture.set(false) }
    }

    fun startAnchorMeasurement() = resumePreparation(Pause.GUIDE)
    fun startComfortableMeasurement() = resumePreparation(Pause.INTRO)

    private fun resumePreparation(reason: Pause) {
        val paused = if (reason == Pause.INTRO) _state.value.pausedForIntro else _state.value.pausedForGuide
        if (!paused || finishing.get() || !changingCapture.compareAndSet(false, true)) return
        _state.update { it.copy(resumingGuide = reason == Pause.GUIDE, resumingIntro = reason == Pause.INTRO, error = null) }
        viewModelScope.launch {
            val started = CompletableDeferred<Unit>()
            guideResumeError = null
            guideCaptureStarted = started
            try {
                capture.resume(repository.audio(session.id), onError = { error ->
                    if (session.view().phase == reason.phase) {
                        guideResumeError = error
                        started.completeExceptionally(error)
                    } else if (!finishing.get()) {
                        finish("input_quality", "마이크 입력이 중단되었습니다.")
                    }
                }, onFrame = ::onCaptured)
                // Opening AudioRecord alone does not prove that recording actually started.
                withTimeout(3000) { started.await() }
                guideResumeError?.let { throw it }
                if (finishing.get()) return@launch
                _state.update { it.copy(pausedForGuide = false, resumingGuide = false,
                    pausedForIntro = false, resumingIntro = false, introLeaving = reason == Pause.INTRO) }
                delay(300) // Neither comfortable-pitch evaluation nor the first cue starts during the fade.
                guideResumeError?.let { throw it }
                if (finishing.get()) return@launch
                if (reason == Pause.INTRO) session.startComfortableMeasurement(capture.capturedSeconds)
                else session.startAnchorMeasurement(capture.capturedSeconds)
                diagnosticEvent("recording_resumed", mapOf("reason" to reason.log))
                _state.update { it.copy(session = session.view(), introLeaving = false) }
            } catch (e: Exception) {
                if (e is CancellationException && e !is kotlinx.coroutines.TimeoutCancellationException) throw e
                withContext(Dispatchers.IO) { synchronized(persistenceLock) { capture.stop() } }
                diagnosticEvent("guide_resume_error", mapOf("errorType" to e.javaClass.simpleName))
                _state.update { if (finishing.get()) it else it.copy(pausedForGuide = reason == Pause.GUIDE,
                    pausedForIntro = reason == Pause.INTRO, resumingGuide = false, resumingIntro = false, introLeaving = false,
                    error = "마이크를 다시 시작하지 못했어요. 기존 녹음은 보존되어 있어요. 다시 시도해 주세요.") }
            } finally {
                guideCaptureStarted = null
                while (frameChannel.tryReceive().isSuccess) { /* discard guide/fade frames */ }
                changingCapture.set(false)
            }
        }
    }

    fun continueMeasurement() {
        if (!_state.value.pausedForChoice || finishing.get() || !changingCapture.compareAndSet(false, true)) return
        viewModelScope.launch {
            try {
                if (session.continueMeasurement(capture.capturedSeconds)) {
                    _state.update { it.copy(pausedForChoice = false, session = session.view(), error = null) }
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

    private fun startInputChecks() {
        inputJob?.cancel()
        inputJob = viewModelScope.launch {
            while (_state.value.running && !finishing.get()) {
                refreshInputCheck(finalized = _state.value.pausedForChoice)
                delay(500)
            }
        }
    }

    private suspend fun refreshInputCheck(finalized: Boolean) {
        val submission = session.submission().let { if (finalized) it.copy(durationSeconds = capture.capturedSeconds) else it }
        val result = withContext(Dispatchers.IO) {
            runCatching { inputQuality.inspect(submission, repository.audio(session.id), capture.recordingRate, finalized) }
                .getOrElse { RangeInputCheck(message = "녹음 구간을 확인하지 못했어요. 다시 측정해 주세요.") }
        }
        if (!finishing.get()) _state.update { it.copy(inputCheck = result, canSupplement = result.canSupplement && session.canSupplement()) }
    }

    fun finish(reason: String = "user_stopped", error: String? = null) {
        if (error == null && reason in setOf("completed", "user_stopped") && !session.view().done && !_state.value.inputCheck.ready) return
        if (!_state.value.running || !finishing.compareAndSet(false, true)) return
        diagnosticEvent("finish_requested", mapOf("reason" to reason))
        cueJob?.cancel()
        captureWatchdog?.cancel()
        inputJob?.cancel()
        viewModelScope.launch {
            val requestedCompletion = error == null && reason in setOf("completed", "user_stopped")
            if (requestedCompletion) {
                _state.update { it.copy(inputChecking = true) }
                val result = withContext(Dispatchers.IO) {
                    capture.stop()
                    // A stopped capture is now immutable; this also seals the current attempt below.
                    val snapshot = if (session.view().targetMidi == null) session.submission().copy(durationSeconds = capture.capturedSeconds) else session.stop(reason, capture.capturedSeconds)
                    runCatching { inputQuality.inspect(snapshot, repository.audio(session.id), capture.recordingRate, true) }
                        .getOrElse { RangeInputCheck(message = "녹음 파일을 확인하지 못했어요. 다시 측정해 주세요.") }
                }
                if (!result.ready) {
                    session.waitForValidInput()
                    finishing.set(false)
                    _state.update { it.copy(session = session.view(), pausedForChoice = true, inputCheck = result,
                        inputChecking = false, canSupplement = result.canSupplement && session.canSupplement(), error = result.message) }
                    checkpoint()
                    startInputChecks()
                    return@launch
                }
                _state.update { it.copy(inputCheck = result, inputChecking = false) }
            }
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
            _state.update { it.copy(running = false, finished = true, pausedForChoice = false, pausedForGuide = false, resumingGuide = false,
                pausedForIntro = false, resumingIntro = false, introLeaving = false, session = session.view(), hasPending = saved,
                hasAttempts = submission.attempts.isNotEmpty(), stopReason = submission.stopReason,
                error = if (saved) error else "측정 기록을 저장하지 못했어요. 저장 공간을 확인해 주세요.",
                preparationFailed = preparationFailed, previewMidi = submission.startMidi ?: 60) }
            if (submitOnFinish && saved && !preparationFailed && _state.value.inputCheck.ready) retryUpload()
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
        finishing.set(true)
        inputJob?.cancel(); cueJob?.cancel(); captureWatchdog?.cancel()
        _state.update { it.copy(running = false, inputCheck = RangeInputCheck()) }
        capture.stop()
        repository.discardPending()
        _state.update { it.copy(hasPending = false, error = null) }
    }

    override fun onCleared() {
        inputJob?.cancel()
        cueJob?.cancel()
        captureWatchdog?.cancel()
        if (_state.value.running && (capture.isRunning || _state.value.pausedForChoice || _state.value.pausedForGuide || _state.value.pausedForIntro)) {
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
