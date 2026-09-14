package com.micon.app.ui.singanalysis

import android.content.Context
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.audio.InstrumentalPlayer
import com.micon.app.audio.PitchCapture
import com.micon.app.data.SongRepository
import com.micon.app.data.backend.dto.BeatGrid
import com.micon.app.data.backend.dto.toLiveNotes
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.data.singanalysis.ScopePlan
import com.micon.app.data.singanalysis.SingAnalysisRecordingStore
import com.micon.app.data.singanalysis.SingLyricLine
import com.micon.app.data.singanalysis.SingRecordingMeta
import com.micon.app.data.singanalysis.SingScopeResolver
import com.micon.app.data.singanalysis.SingTake
import com.micon.app.data.singanalysis.SongAnalysisSubmitter
import com.micon.app.data.singanalysis.SubmitResult
import com.micon.app.data.voice.VoiceAnalysisRepository
import com.micon.app.data.voice.VoiceAnalysisStatus
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LyricLines
import com.micon.app.scoring.LyricSheet
import com.micon.app.scoring.LyricTiming
import com.micon.app.scoring.NoteNormalizer
import com.micon.app.scoring.SectionMap
import com.micon.app.scoring.SongSections
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import kotlin.math.abs

/**
 * 불러서 분석하기 화면 로직. `PracticeViewModel` 에서 채점(YIN 판정·LiveScorer·러너웨이·진동·키 변경·구간 반복)을 뺀 것.
 *
 * 흐름: 곡 준비 → 등록 구간 선택 → 카운트인 → 녹음 ⇄ 일시정지 → 사용자 종료 → 임시 WAV 저장.
 * 카운트인·일시정지 때는 마이크를 끈다. MR과 녹음이 실제 진행된 구간만 [SingTake]에 남긴다.
 * 최소 30초를 확보하고 일시정지한 상태에서만 종료한다. 곡 끝은 자동 완료하지 않는다.
 * 저장한 임시 녹음의 수명은 상위 온보딩 세션이 관리한다.
 */
class SingAnalysisViewModel(
    appContext: Context,
    val songId: Long,
    private val repo: SongRepository,
    mediaHttp: OkHttpClient,
    private val store: SingAnalysisRecordingStore,
    private val submitter: SongAnalysisSubmitter,
    private val voice: VoiceAnalysisRepository,
    private val onboardingDraftId: String? = null,
) : ViewModel() {
    enum class Phase { LOADING, SETUP, COUNTDOWN, RUNNING, PAUSED, SAVING, DONE, ERROR }

    /**
     * 분석 요청 상태. SENDING(업로드·요청 중) → DONE(접수, 서버 상태 폴링 중) → COMPLETED | FAILED.
     * UNAVAILABLE 은 비회원·AI 미준비처럼 지금은 못 하는 정상 상태, ERROR 는 요청 자체가 실패한 경우. 둘 다 다시 요청할 수 있다.
     */
    enum class SubmitPhase { NONE, SENDING, DONE, COMPLETED, FAILED, UNAVAILABLE, ERROR }

    data class Submit(val phase: SubmitPhase = SubmitPhase.NONE, val message: String = "")

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val title: String = "",
        val artist: String = "",
        val imageUrl: String? = null,
        val durationSec: Double = 0.0,
        val nowSec: Double = 0.0,
        /** MR 재생과 실제 마이크 녹음이 겹친 누적 시간(초). */
        val elapsedRecSec: Double = 0.0,
        val countdown: Int = 0,
        val buffering: Boolean = false,
        /** 마이크 레벨 0~1 */
        val micLevel: Float = 0f,
        val plans: List<ScopePlan> = emptyList(),
        val plan: ScopePlan? = null,
        val lyricTiming: LyricTiming = LyricTiming.NONE,
        /** "여기까지 마칠까요?" 다이얼로그 표시 */
        val confirmEnd: Boolean = false,
        val confirmDiscard: Boolean = false,
        val recordingValid: Boolean = false,
        val saved: SavedRecording? = null,
        val endedEarly: Boolean = false,
        val takePlaying: Boolean = false,
        val submit: Submit = Submit(),
        val error: String? = null,
    ) {
        val isSinging: Boolean get() = phase == Phase.COUNTDOWN || phase == Phase.RUNNING || phase == Phase.PAUSED
        /** 지금까지 부른 곡 구간 길이(초) — 다이얼로그·완료 화면 문구 */
        val sungSeconds: Double get() = elapsedRecSec
        val canFinish: Boolean get() = recordingValid && phase == Phase.PAUSED &&
            elapsedRecSec >= com.micon.app.data.singanalysis.RecordingEligibility.MIN_SECONDS
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    val player = InstrumentalPlayer(appContext, mediaHttp)
    private var instrumentalUrl: String? = null
    /** 녹음만: YIN 을 돌리지 않는다(rms 만) */
    private val capture = PitchCapture(analyzePitch = false)
    private val clicks: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()

    var sheet: LyricSheet = LyricSheet.EMPTY; private set
    private var notes: List<LiveNote> = emptyList()
    private var sections: SectionMap = SectionMap.EMPTY
    private var beatGrid: BeatGrid? = null
    private var meter: Int = 4
    private var tempoBpm: Int = 100
    private var dataVersion: String? = null
    private var backendSections = emptyList<com.micon.app.data.PracticeSection>()

    private val frameChannel = Channel<PitchCapture.PitchFrame>(capacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var lastMicUpdate = 0L
    private var tickJob: Job? = null
    private var countdownJob: Job? = null
    private var pollJob: Job? = null
    private var recordingId: String? = null
    private var wavFile: File? = null
    private var takePlayer: MediaPlayer? = null

    /** Valid MR/microphone overlaps copied into the final recording metadata. */
    private val takes = ArrayList<SingTake>()
    private var eligibility = com.micon.app.data.singanalysis.RecordingEligibility()
    private var lastAudioProgressAt = 0L
    private var lastAudioSeconds = 0.0

    init {
        player.onError = { message -> onPlayerError(message) }
        viewModelScope.launch { for (frame in frameChannel) onFrame(frame) }
        runCatching { store.deleteOrphans() }
        load()
    }

    // ------------------------------------------------------------------ 로드

    fun load() {
        _state.update { it.copy(phase = Phase.LOADING, error = null) }
        viewModelScope.launch {
            try {
                val detail = repo.detail(songId) ?: error("곡을 찾을 수 없습니다: $songId")
                // 409(준비 안 됨)는 PracticeAssetsNotReadyException 의 서버 메시지를 그대로 화면에 보인다.
                val assets = repo.practiceAssets(songId, 0)
                // 음표가 없어도 녹음은 가능하다(가사·구간 정보만 줄어든다) — 연습과 다른 점.
                notes = assets.targetNotes.toLiveNotes(assets.timeSignature)
                sheet = LyricLines.build(notes, assets.lyricsSynced, assets.lyricsPlain)
                check(sheet.lines.any { it.text.isNotBlank() }) { "가사가 준비되지 않은 곡이에요. 다른 곡을 골라 주세요." }
                beatGrid = assets.beatGrid
                meter = NoteNormalizer.meterOf(assets.timeSignature)
                tempoBpm = (assets.tempoBpm ?: detail.analysis?.tempoBpm)?.takeIf { it in 40..240 } ?: 100
                dataVersion = assets.dataVersion
                backendSections = assets.sections
                sections = if (notes.isEmpty()) SectionMap.EMPTY else SongSections.build(notes, sheet.lines, barStarts())
                val url = assets.instrumental.playbackUrl
                    ?: error("반주가 준비되지 않은 곡이에요. 다른 곡을 골라 주세요.")
                instrumentalUrl = url
                player.load(url)
                val duration = fallbackDuration(detail.summary.durationMs)
                _state.update {
                    it.copy(
                        phase = Phase.SETUP, title = detail.summary.title, artist = detail.summary.artist, imageUrl = detail.summary.imageUrl,
                        durationSec = duration, plans = resolvePlans(duration), lyricTiming = sheet.timing, nowSec = 0.0,
                    )
                }
                // 실제 반주 길이가 오면 범위 계산을 한 번 갱신한다(아직 SETUP 일 때만).
                launch {
                    if (player.awaitReady(10_000)) {
                        val real = player.durationSec ?: return@launch
                        _state.update { s -> if (s.phase == Phase.SETUP) s.copy(durationSec = real, plans = resolvePlans(real)) else s.copy(durationSec = real) }
                    }
                }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun resolvePlans(durationSec: Double): List<ScopePlan> =
        SingScopeResolver.resolve(durationSec, notes.firstOrNull()?.start, notes.lastOrNull()?.end, backendSections, sections)

    private fun fallbackDuration(durationMs: Long?): Double =
        durationMs?.let { it / 1000.0 }?.takeIf { it > 0 } ?: 0.0

    /** 비트 그리드에서 마디 시작 시각만(offsetBeats 기준으로 meter 마다). 연습과 동일. */
    private fun barStarts(): List<Double> {
        val grid = beatGrid ?: return emptyList()
        val out = ArrayList<Double>()
        for (index in grid.beatTimes.indices) {
            val beat = index - grid.offsetBeats
            if (beat < 0 || abs(beat % meter) > 1e-6) continue
            out.add(grid.beatTimes[index])
        }
        return out
    }

    /** 카운트인 한 박: 곡 템포, 0.45–0.75 s 로 묶는다(연습과 동일). */
    private fun beatMillis(): Long = ((60.0 / tempoBpm).coerceIn(0.45, 0.75) * 1000).toLong()

    // ------------------------------------------------------------------ 시작·재생 제어

    /** RECORD_AUDIO 권한이 있는 상태에서 호출. 범위 카드 선택 → 마이크 → 카운트인 → 반주. */
    fun choose(plan: ScopePlan) {
        val s = _state.value
        if (s.phase != Phase.SETUP || !plan.enabled) return
        stopTake()
        takes.clear()
        eligibility = com.micon.app.data.singanalysis.RecordingEligibility()
        val id = store.newId(songId)
        val file = store.wavFile(id)
        recordingId = id
        wavFile = file
        pollJob?.cancel()
        _state.update { it.copy(plan = plan, saved = null, endedEarly = false, submit = Submit(), error = null, nowSec = plan.startSec, elapsedRecSec = 0.0, recordingValid = false) }
        beginPlayback(plan.startSec, countFrom = meter)
    }

    fun permissionDenied() {
        _state.update { it.copy(error = "마이크 권한이 없어 녹음할 수 없어요. 설정에서 마이크를 허용해 주세요.") }
    }

    /** PAUSED → 카운트인 후 현재 위치에서 이어서. */
    fun resume() {
        if (_state.value.phase != Phase.PAUSED) return
        if (!eligibility.valid || player.isEnded || player.positionSec >= (_state.value.plan?.endSec ?: 0.0)) return
        _state.update { it.copy(confirmEnd = false) }
        beginPlayback(player.positionSec, countFrom = meter)
    }

    private fun beginPlayback(startAt: Double, countFrom: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            _state.update { it.copy(phase = Phase.COUNTDOWN, countdown = 0, buffering = true, error = null) }
            if (!player.isReady) {
                if (player.isIdle) instrumentalUrl?.let { player.load(it) }
                if (!player.awaitReady(8000)) {
                    _state.update { it.copy(phase = if (takes.isEmpty()) Phase.SETUP else Phase.PAUSED, buffering = false, error = "반주를 불러오지 못했습니다. 네트워크를 확인하고 다시 시도하세요.") }
                    if (takes.isEmpty()) discardUnsaved()
                    return@launch
                }
            }
            val duration = player.durationSec ?: _state.value.durationSec
            if (duration - startAt + eligibility.seconds < 30.0) {
                interruptRecording("남은 반주로는 30초를 녹음할 수 없어요. 다른 시작 위치나 곡을 골라 주세요.")
                return@launch
            }
            player.seekTo(startAt)
            _state.update { it.copy(buffering = false, nowSec = startAt, durationSec = duration,
                plan = it.plan?.copy(endSec = duration)) }
            // 노래방식 카운트인: 곡 템포로 "탁 탁 탁 탁", 마지막 박은 높은 소리. 그 다음 박에 반주가 들어간다(연습과 동일).
            val beat = beatMillis()
            for (step in countFrom downTo 1) {
                _state.update { it.copy(countdown = step) }
                runCatching { clicks?.startTone(if (step == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 60) }
                delay(beat)
            }
            _state.update { it.copy(countdown = 0) }
            val file = wavFile ?: return@launch
            try {
                val onError: (Throwable) -> Unit = {
                    viewModelScope.launch { interruptRecording("마이크 입력이 중단됐어요. 권한과 연결을 확인한 뒤 다시 녹음해 주세요.") }
                }
                if (file.isFile && file.length() > 44) capture.resume(file, onError) { frame -> frameChannel.trySend(frame) }
                else capture.start(file, onError) { frame -> frameChannel.trySend(frame) }
            } catch (e: Exception) {
                interruptRecording("마이크를 사용할 수 없어요. 권한을 확인한 뒤 다시 녹음해 주세요.")
                return@launch
            }
            lastAudioProgressAt = android.os.SystemClock.elapsedRealtime()
            lastAudioSeconds = capture.capturedSeconds
            player.play()
            _state.update { it.copy(phase = Phase.RUNNING, recordingValid = true) }
            tickJob?.cancel()
            tickJob = launch { tickLoop() }
        }
    }

    private suspend fun tickLoop() {
        val plan = _state.value.plan ?: return
        while (viewModelScope.isActive && _state.value.phase == Phase.RUNNING) {
            val now = player.positionSec
            val clock = android.os.SystemClock.elapsedRealtime()
            val audio = capture.capturedSeconds
            if (audio > lastAudioSeconds) { lastAudioSeconds = audio; lastAudioProgressAt = clock }
            if (!capture.isRunning || clock - lastAudioProgressAt > 2000) {
                interruptRecording("마이크 입력이 멈췄어요. 다시 녹음해 주세요.")
                return
            }
            eligibility.sample(audio, now, clock / 1000.0, player.isPlaying && capture.isRunning)
            _state.update { it.copy(nowSec = now, elapsedRecSec = eligibility.seconds, buffering = player.isBuffering) }
            if (player.isEnded || now >= plan.endSec) {
                pause()
                if (!eligibility.canFinish(true)) _state.update {
                    it.copy(error = "녹음된 구간이 30초 미만이에요. 뒤로 가서 다른 시작 위치나 곡을 골라 주세요.")
                }
                return
            }
            delay(33)
        }
    }

    /** Stop both devices. Resume appends PCM to the same file after the count-in. */
    fun pause() {
        val s = _state.value
        if (s.phase != Phase.RUNNING && s.phase != Phase.COUNTDOWN) return
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        capture.stop()
        eligibility.pause()
        _state.update { it.copy(phase = Phase.PAUSED, countdown = 0, buffering = false, nowSec = player.positionSec) }
    }

    /** Finishing never implicitly pauses playback or bypasses the 30-second gate. */
    fun requestFinish() {
        if (!_state.value.canFinish || !eligibility.canFinish(true)) return
        _state.update { it.copy(confirmEnd = true) }
    }

    fun cancelFinish() = _state.update { it.copy(confirmEnd = false) }

    fun confirmFinish() {
        if (!_state.value.canFinish || !eligibility.canFinish(true)) return
        _state.update { it.copy(confirmEnd = false) }
        finish(early = player.positionSec < (_state.value.plan?.endSec ?: 0.0))
    }

    private fun finish(early: Boolean) {
        if (!_state.value.canFinish || !eligibility.canFinish(true)) return
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        capture.stop()
        val file = wavFile
        takes.clear(); takes.addAll(eligibility.takes)
        val plan = _state.value.plan
        val rate = capture.recordingRate
        if (file == null || plan == null || file.length() < 44 + rate * 2 * MIN_SECONDS) {
            file?.delete()
            recordingId = null; wavFile = null
            _state.update { it.copy(phase = Phase.SETUP, countdown = 0, buffering = false, nowSec = 0.0, error = "녹음이 너무 짧아 저장하지 않았어요. 다시 골라 불러 주세요.") }
            return
        }
        _state.update { it.copy(phase = Phase.SAVING, endedEarly = early) }
        val s = _state.value
        val meta = buildMeta(file, plan, early, s, rate)
        viewModelScope.launch {
            try {
                val saved = withContext(Dispatchers.IO + NonCancellable) { store.save(meta) }
                _state.update { it.copy(phase = Phase.DONE, saved = saved, elapsedRecSec = eligibility.seconds) }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.ERROR, error = "녹음을 저장하지 못했어요: ${e.message ?: e.toString()}") }
            }
        }
    }

    private fun buildMeta(file: File, plan: ScopePlan, early: Boolean, s: UiState, rate: Int): SingRecordingMeta {
        val sungStart = plan.startSec
        val sungEnd = takes.lastOrNull()?.songEndSec ?: sungStart
        return SingRecordingMeta(
            id = recordingId ?: file.nameWithoutExtension,
            songId = songId, title = s.title, artist = s.artist,
            scope = plan.scope.name, planSource = plan.source.name, selectedKey = 0,
            plannedStartSec = plan.startSec, plannedEndSec = plan.endSec,
            sungStartSec = sungStart, sungEndSec = sungEnd, endedEarly = early,
            recordedAt = System.currentTimeMillis(), sampleRate = rate,
            durationSec = (file.length() - 44).coerceAtLeast(0) / (rate * 2.0),
            takes = takes.toList(),
            lyricTiming = sheet.timing.name,
            lyricLines = sheet.lines.filter { it.end >= sungStart && it.start <= sungEnd }.map { SingLyricLine(it.text, it.start, it.end) },
            wavFileName = file.name, dataVersion = dataVersion,
            onboardingDraftId = onboardingDraftId,
        )
    }

    /** 다시 부르기(DONE → SETUP). 저장된 녹음은 그대로 둔다. */
    fun restart() {
        stopTake()
        _state.value.saved?.let { store.delete(it.id) }
        discardUnsaved()
        eligibility = com.micon.app.data.singanalysis.RecordingEligibility()
        takes.clear()
        recordingId = null; wavFile = null
        player.seekTo(0.0)
        pollJob?.cancel()
        _state.update { it.copy(phase = Phase.SETUP, plan = null, nowSec = 0.0, elapsedRecSec = 0.0, saved = null, endedEarly = false, submit = Submit(), error = null, confirmEnd = false, confirmDiscard = false, recordingValid = false, plans = resolvePlans(it.durationSec)) }
    }

    /** 저장하지 않고 나가기(확인 다이얼로그). 호출 뒤 화면이 onBack 한다. */
    fun discardAndExit() {
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        discardUnsaved()
        _state.update { it.copy(confirmEnd = false, confirmDiscard = false, phase = Phase.SETUP, plan = null, nowSec = 0.0, recordingValid = false) }
    }

    private fun discardUnsaved() {
        if (capture.isRunning) capture.stop()
        if (_state.value.saved == null) wavFile?.delete()
        recordingId = null; wavFile = null
        takes.clear()
    }

    private fun onPlayerError(message: String) {
        if (_state.value.isSinging) interruptRecording("반주 재생이 중단됐어요. 연결을 확인한 뒤 다시 녹음해 주세요.")
        else _state.update { it.copy(error = "반주를 재생하지 못했어요. 다시 시도해 주세요.", buffering = false) }
    }

    fun requestExit() { pause(); _state.update { it.copy(confirmDiscard = true) } }
    fun cancelExit() { _state.update { it.copy(confirmDiscard = false) } }

    fun interruptRecording(message: String = "앱을 벗어나 녹음이 중단됐어요. 다시 녹음해 주세요.") {
        if (!_state.value.isSinging) return
        eligibility.invalidate()
        discardAndExit()
        _state.update { it.copy(error = message, elapsedRecSec = 0.0, micLevel = 0f) }
    }

    private fun onFrame(frame: PitchCapture.PitchFrame) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMicUpdate < 150) return
        lastMicUpdate = nowMs
        _state.update { it.copy(micLevel = (frame.rms / 0.12).toFloat().coerceIn(0f, 1f)) }
    }

    // ------------------------------------------------------------------ 저장 후

    /** 분석 요청 — [SongAnalysisSubmitter](백엔드 `/recordings` → `/voice-analyses`)에 넘기고, 접수되면 서버 상태를 폴링한다. */
    fun submit() {
        if (onboardingDraftId != null) {
            _state.update { it.copy(submit = Submit(SubmitPhase.UNAVAILABLE, "음색 분석 기능을 준비 중이에요.")) }
            return
        }
        val saved = _state.value.saved ?: return
        if (_state.value.submit.phase == SubmitPhase.SENDING) return
        pollJob?.cancel()
        _state.update { it.copy(submit = Submit(SubmitPhase.SENDING, "분석을 요청하고 있어요")) }
        viewModelScope.launch {
            val result = runCatching { submitter.submit(saved) { msg -> _state.update { it.copy(submit = it.submit.copy(message = msg)) } } }
                .getOrElse { e -> SubmitResult.Failed("분석 요청 실패: ${e.message ?: e.toString()}") }
            // 서버 id 가 메타에 적혔으면 화면의 saved 도 갱신한다(저장 위치 표시, 재요청 시 재업로드 생략)
            val refreshed = withContext(Dispatchers.IO) { runCatching { store.list().firstOrNull { it.id == saved.id } }.getOrNull() } ?: saved
            _state.update {
                it.copy(
                    saved = refreshed,
                    submit = when (result) {
                        is SubmitResult.Accepted -> Submit(SubmitPhase.DONE, result.message)
                        is SubmitResult.Unavailable -> Submit(SubmitPhase.UNAVAILABLE, result.message)
                        is SubmitResult.Failed -> Submit(SubmitPhase.ERROR, result.message)
                    },
                )
            }
            if (result is SubmitResult.Accepted) result.analysisId?.let(::startPolling)
        }
    }

    /**
     * 접수된 분석의 서버 상태를 [POLL_INTERVAL_MS] 마다 확인한다. AI 서버가 없으면 READY/ANALYZING 에 머무르므로
     * [POLL_MAX_MS] 뒤에는 멈추고 "리포트 탭에서 확인" 문구만 남긴다(리포트 탭이 `latest` 로 다시 본다).
     */
    private fun startPolling(analysisId: Long) {
        pollJob?.cancel()
        pollJob = viewModelScope.launch {
            val started = System.currentTimeMillis()
            while (isActive && System.currentTimeMillis() - started < POLL_MAX_MS) {
                delay(POLL_INTERVAL_MS)
                val analysis = runCatching { voice.get(analysisId) }.getOrNull() ?: continue
                when (analysis.status) {
                    VoiceAnalysisStatus.COMPLETED -> {
                        _state.update { it.copy(submit = Submit(SubmitPhase.COMPLETED, "분석이 끝났어요. 리포트에서 결과를 확인해 보세요.")) }
                        return@launch
                    }
                    VoiceAnalysisStatus.FAILED, VoiceAnalysisStatus.INSUFFICIENT_DATA, VoiceAnalysisStatus.CANCELED -> {
                        val reason = analysis.failureReason?.takeIf { it.isNotBlank() }
                            ?: if (analysis.status == VoiceAnalysisStatus.INSUFFICIENT_DATA) "분석에 쓸 수 있는 목소리 구간이 부족했어요. 더 길게 불러 보세요." else "서버에서 분석이 중단됐어요."
                        _state.update { it.copy(submit = Submit(SubmitPhase.FAILED, reason)) }
                        return@launch
                    }
                    VoiceAnalysisStatus.ANALYZING -> _state.update { it.copy(submit = it.submit.copy(message = "서버가 분석하고 있어요…")) }
                    else -> _state.update { it.copy(submit = it.submit.copy(message = "분석 순서를 기다리고 있어요")) }
                }
            }
            if (isActive && _state.value.submit.phase == SubmitPhase.DONE) {
                _state.update { it.copy(submit = it.submit.copy(message = "분석이 진행 중이에요. 결과는 리포트 탭에서 확인할 수 있어요.")) }
            }
        }
    }

    /** 저장된 녹음 다시 듣기 (DONE). */
    fun playTake() {
        val file = _state.value.saved?.wav?.takeIf { it.exists() && it.length() > 44 } ?: return
        stopTake()
        runCatching {
            takePlayer = MediaPlayer().apply {
                setDataSource(file.path)
                setOnCompletionListener { _state.update { it.copy(takePlaying = false) } }
                prepare()
                start()
            }
            _state.update { it.copy(takePlaying = true) }
        }.onFailure { e -> _state.update { it.copy(error = "녹음 재생 실패: ${e.message}") } }
    }

    fun stopTake() {
        takePlayer?.runCatching { stop(); release() }
        takePlayer = null
        if (_state.value.takePlaying) _state.update { it.copy(takePlaying = false) }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        tickJob?.cancel()
        pollJob?.cancel()
        // 저장된 녹음(JSON 있음)은 건드리지 않는다. 미저장 WAV 만 정리.
        if (capture.isRunning) {
            capture.stop()
            if (_state.value.saved == null) wavFile?.delete()
        }
        stopTake()
        player.release()
        runCatching { clicks?.release() }
        frameChannel.close()
    }

    private companion object {
        /** 이보다 짧은 녹음은 저장하지 않는다(초) */
        const val POLL_INTERVAL_MS = 3_000L
        const val POLL_MAX_MS = 120_000L
        const val MIN_SECONDS = 30
    }
}
