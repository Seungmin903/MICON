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
import com.micon.app.data.engine.dto.BeatGrid
import com.micon.app.data.engine.dto.toLiveNotes
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.data.singanalysis.ScopePlan
import com.micon.app.data.singanalysis.SingAnalysisRecordingStore
import com.micon.app.data.singanalysis.SingLyricLine
import com.micon.app.data.singanalysis.SingRecordingMeta
import com.micon.app.data.singanalysis.SingScopeResolver
import com.micon.app.data.singanalysis.SingTake
import com.micon.app.data.singanalysis.SongAnalysisSubmitter
import com.micon.app.data.singanalysis.SubmitResult
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
 * 흐름: load(백엔드 practice-assets) → SETUP(범위 카드 3개) → choose(마이크 켜기 → 반주 준비 → 카운트인 "탁탁탁") → RUNNING(가사만)
 *      ⇄ PAUSED(이어서 부르면 다시 카운트인) → finish(곡 끝 또는 "여기까지 마치기") → SAVING(WAV + 메타 JSON) → DONE.
 * - 마이크는 choose 에서 켜고 finish 에서 끈다. 일시정지·카운트인 중에도 계속 녹음해 **한 WAV** 로 남긴다.
 *   대신 "반주가 흐르며 부른 구간" 을 [SingTake] 로 기록해(WAV 시각 ↔ 곡 시각) 분석 쪽이 잘라낼 수 있게 한다.
 * - 저장된 녹음(JSON 있음)은 화면을 떠나도 지우지 않는다. 미저장 WAV 만 지운다.
 */
class SingAnalysisViewModel(
    appContext: Context,
    val songId: Long,
    private val repo: SongRepository,
    mediaHttp: OkHttpClient,
    private val store: SingAnalysisRecordingStore,
    private val submitter: SongAnalysisSubmitter,
) : ViewModel() {
    enum class Phase { LOADING, SETUP, COUNTDOWN, RUNNING, PAUSED, SAVING, DONE, ERROR }
    enum class SubmitPhase { NONE, SENDING, DONE, UNAVAILABLE, ERROR }

    data class Submit(val phase: SubmitPhase = SubmitPhase.NONE, val message: String = "")

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val title: String = "",
        val artist: String = "",
        val imageUrl: String? = null,
        val durationSec: Double = 0.0,
        val nowSec: Double = 0.0,
        /** 녹음 파일 길이(초) — REC 경과 표시 */
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
        val saved: SavedRecording? = null,
        val endedEarly: Boolean = false,
        val takePlaying: Boolean = false,
        val submit: Submit = Submit(),
        val error: String? = null,
    ) {
        val isSinging: Boolean get() = phase == Phase.COUNTDOWN || phase == Phase.RUNNING || phase == Phase.PAUSED
        /** 지금까지 부른 곡 구간 길이(초) — 다이얼로그·완료 화면 문구 */
        val sungSeconds: Double get() = if (plan == null) 0.0 else (nowSec - plan.startSec).coerceAtLeast(0.0)
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
    private var recordingId: String? = null
    private var wavFile: File? = null
    private var takePlayer: MediaPlayer? = null

    /** 반주가 흐르며 부른 구간들. 열린 take 는 [openTake]. */
    private val takes = ArrayList<SingTake>()
    private var openTake: SingTake? = null

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
                beatGrid = assets.beatGrid
                meter = NoteNormalizer.meterOf(assets.timeSignature)
                tempoBpm = (assets.tempoBpm ?: detail.analysis?.tempoBpm)?.takeIf { it in 40..240 } ?: 100
                dataVersion = assets.dataVersion
                backendSections = assets.sections
                sections = if (notes.isEmpty()) SectionMap.EMPTY else SongSections.build(notes, sheet.lines, barStarts())
                val url = assets.instrumental.playbackUrl
                    ?: error("반주 재생 주소가 없습니다. 백엔드 practice-assets 응답에 반주 URL(playbackUrl)이 추가되어야 합니다.")
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
        notes.lastOrNull()?.end?.plus(6.0) ?: durationMs?.let { it / 1000.0 }?.takeIf { it > 0 } ?: 240.0

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
        takes.clear(); openTake = null
        val id = store.newId(songId)
        val file = store.wavFile(id)
        try {
            capture.start(file, onError = { e -> _state.update { it.copy(error = "녹음 오류: ${e.message}") } }) { frame -> frameChannel.trySend(frame) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "마이크를 사용할 수 없습니다: ${e.message}") }
            return
        }
        recordingId = id
        wavFile = file
        _state.update { it.copy(plan = plan, saved = null, endedEarly = false, submit = Submit(), error = null, nowSec = plan.startSec, elapsedRecSec = 0.0) }
        beginPlayback(plan.startSec, countFrom = meter)
    }

    fun permissionDenied() {
        _state.update { it.copy(error = "마이크 권한이 없어 녹음할 수 없어요. 설정에서 마이크를 허용해 주세요.") }
    }

    /** PAUSED → 카운트인 후 현재 위치에서 이어서. */
    fun resume() {
        if (_state.value.phase != Phase.PAUSED) return
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
            player.seekTo(startAt)
            _state.update { it.copy(buffering = false, nowSec = startAt, durationSec = player.durationSec ?: it.durationSec) }
            // 노래방식 카운트인: 곡 템포로 "탁 탁 탁 탁", 마지막 박은 높은 소리. 그 다음 박에 반주가 들어간다(연습과 동일).
            val beat = beatMillis()
            for (step in countFrom downTo 1) {
                _state.update { it.copy(countdown = step) }
                runCatching { clicks?.startTone(if (step == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 60) }
                delay(beat)
            }
            _state.update { it.copy(countdown = 0) }
            player.play()
            openTake = SingTake(wavStartSec = capture.capturedSeconds, wavEndSec = capture.capturedSeconds, songStartSec = startAt, songEndSec = startAt)
            _state.update { it.copy(phase = Phase.RUNNING) }
            tickJob?.cancel()
            tickJob = launch { tickLoop() }
        }
    }

    private suspend fun tickLoop() {
        val plan = _state.value.plan ?: return
        while (viewModelScope.isActive && _state.value.phase == Phase.RUNNING) {
            val now = player.positionSec
            _state.update { it.copy(nowSec = now, elapsedRecSec = capture.capturedSeconds) }
            if (player.isEnded || now >= plan.endSec) {
                finish(early = false)
                return
            }
            delay(33)
        }
    }

    /** 일시정지: 반주만 멈추고 마이크는 유지(한 파일). 카운트다운 중이면 카운트다운 취소. */
    fun pause() {
        val s = _state.value
        if (s.phase != Phase.RUNNING && s.phase != Phase.COUNTDOWN) return
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        closeTake()
        _state.update { it.copy(phase = Phase.PAUSED, countdown = 0, buffering = false, nowSec = player.positionSec) }
    }

    /** "여기까지 마치기" 버튼·뒤로가기: 반주를 멈추고 확인 다이얼로그를 띄운다. */
    fun requestFinish() {
        val s = _state.value
        if (!s.isSinging) return
        if (s.phase != Phase.PAUSED) pause()
        _state.update { it.copy(confirmEnd = true) }
    }

    fun cancelFinish() = _state.update { it.copy(confirmEnd = false) }

    fun confirmFinish() {
        if (_state.value.phase != Phase.PAUSED && !_state.value.isSinging) return
        _state.update { it.copy(confirmEnd = false) }
        finish(early = true)
    }

    private fun closeTake() {
        val open = openTake ?: return
        takes.add(open.copy(wavEndSec = capture.capturedSeconds, songEndSec = player.positionSec))
        openTake = null
    }

    private fun finish(early: Boolean) {
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        closeTake()
        val file = capture.stop() ?: wavFile
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
                _state.update { it.copy(phase = Phase.DONE, saved = saved, elapsedRecSec = meta.durationSec) }
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
        )
    }

    /** 다시 부르기(DONE → SETUP). 저장된 녹음은 그대로 둔다. */
    fun restart() {
        stopTake()
        takes.clear(); openTake = null
        recordingId = null; wavFile = null
        player.seekTo(0.0)
        _state.update { it.copy(phase = Phase.SETUP, plan = null, nowSec = 0.0, elapsedRecSec = 0.0, saved = null, endedEarly = false, submit = Submit(), error = null, confirmEnd = false, plans = resolvePlans(it.durationSec)) }
    }

    /** 저장하지 않고 나가기(확인 다이얼로그). 호출 뒤 화면이 onBack 한다. */
    fun discardAndExit() {
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        discardUnsaved()
        _state.update { it.copy(confirmEnd = false, phase = Phase.SETUP, plan = null, nowSec = 0.0) }
    }

    private fun discardUnsaved() {
        if (capture.isRunning) capture.stop()
        if (_state.value.saved == null) wavFile?.delete()
        recordingId = null; wavFile = null
        takes.clear(); openTake = null
    }

    private fun onPlayerError(message: String) {
        _state.update { it.copy(error = "반주 재생 오류: $message", buffering = false) }
        if (_state.value.phase == Phase.RUNNING || _state.value.phase == Phase.COUNTDOWN) pause()
    }

    private fun onFrame(frame: PitchCapture.PitchFrame) {
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMicUpdate < 150) return
        lastMicUpdate = nowMs
        _state.update { it.copy(micLevel = (frame.rms / 0.12).toFloat().coerceIn(0f, 1f)) }
    }

    // ------------------------------------------------------------------ 저장 후

    /** 분석 요청 — [SongAnalysisSubmitter] 에 넘긴다. 기본 구현은 "연동 준비 중". */
    fun submit() {
        val saved = _state.value.saved ?: return
        if (_state.value.submit.phase == SubmitPhase.SENDING) return
        _state.update { it.copy(submit = Submit(SubmitPhase.SENDING, "분석을 요청하고 있어요")) }
        viewModelScope.launch {
            val result = runCatching { submitter.submit(saved) { msg -> _state.update { it.copy(submit = it.submit.copy(message = msg)) } } }
                .getOrElse { e -> SubmitResult.Failed("분석 요청 실패: ${e.message ?: e.toString()}") }
            _state.update {
                it.copy(
                    submit = when (result) {
                        is SubmitResult.Accepted -> Submit(SubmitPhase.DONE, result.message)
                        is SubmitResult.Unavailable -> Submit(SubmitPhase.UNAVAILABLE, result.message)
                        is SubmitResult.Failed -> Submit(SubmitPhase.ERROR, result.message)
                    },
                )
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
        const val MIN_SECONDS = 2
    }
}
