package com.micon.app.ui.practice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.audio.InstrumentalPlayer
import com.micon.app.audio.PitchCapture
import com.micon.app.data.SongRepository
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.engine.dto.BeatGrid
import com.micon.app.data.engine.dto.SemitonesRequest
import com.micon.app.data.engine.dto.toLiveNotes
import com.micon.app.scoring.Frame
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveScorer
import com.micon.app.scoring.LyricLine
import com.micon.app.scoring.LyricLines
import com.micon.app.scoring.MeasureRow
import com.micon.app.scoring.NoteNormalizer
import com.micon.app.scoring.ScoringConstants
import com.micon.app.scoring.ScoringMath
import com.micon.app.scoring.Totals
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * 노래 연습 화면 로직 (live-scoring.tsx 의 React 상태 부분을 ViewModel 로).
 * 흐름: load → READY → start(마이크 켜기 + 카운트다운 + 반주 재생) → RUNNING(tick 30 Hz) → FINISHED → 정밀 리포트(/compare).
 * LiveScorer 는 메인 스레드에서만 만진다 (캡처 스레드는 채널로 프레임만 넘김).
 */
class PracticeViewModel(
    appContext: Context,
    val songId: String,
    private val repo: SongRepository,
    private val engine: EngineClient,
) : ViewModel() {
    enum class Phase { LOADING, READY, COUNTDOWN, RUNNING, FINISHED, ERROR }
    enum class ReportPhase { NONE, UPLOADING, ANALYZING, DONE, ERROR }

    data class Report(val phase: ReportPhase = ReportPhase.NONE, val message: String = "", val progress: Int = 0, val jobId: String? = null)

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val title: String = "",
        val artist: String = "",
        val keyName: String? = null,
        val keyShift: Int = 0,
        val keyBusy: Boolean = false,
        val skipIntro: Boolean = true,
        val autoCalibrate: Boolean = true,
        val headphones: Boolean = true,      // true → /compare sourceType=vocal (분리 생략)
        val countdown: Int = 0,
        val nowSec: Double = 0.0,
        val totals: Totals? = null,
        val measures: List<MeasureRow> = emptyList(),
        val lastJudged: LiveNote? = null,
        val micRms: Double = 0.0,
        val micNote: String? = null,
        val report: Report = Report(),
        val error: String? = null,
        val playerReady: Boolean = false,
    )

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    val player = InstrumentalPlayer(appContext, engine.okHttp)
    private val capture = PitchCapture()
    private val cacheDir = appContext.cacheDir

    // 화면 그리기용 (메인 스레드에서만 읽고 쓴다)
    var notes: List<LiveNote> = emptyList(); private set
    var lyricLines: List<LyricLine> = emptyList(); private set
    var beatGrid: BeatGrid? = null; private set
    var meter: Int = 4; private set
    var scorer: LiveScorer? = null; private set
    val frames: List<Frame> get() = scorer?.frames ?: emptyList()

    private data class Sync(val t: Double, val mr: Double, var latestT: Double)
    private var sync: Sync? = null
    private val frameChannel = Channel<PitchCapture.PitchFrame>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var lastMicUpdate = 0L
    private var tickJob: Job? = null
    private var wavFile: File? = null

    init {
        player.onError = { message -> _state.update { it.copy(error = "반주 재생 오류: $message") } }
        viewModelScope.launch { for (frame in frameChannel) onFrame(frame) }
        load()
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val detail = repo.detail(songId) ?: error("곡을 찾을 수 없습니다: $songId")
                val analysis = detail.analysis ?: error("분석 데이터가 없는 곡입니다.")
                val karaoke = repo.karaokeNotes(songId) ?: error("음표 데이터(song_notes)가 없습니다.")
                notes = karaoke.toLiveNotes(analysis.timeSignature)
                if (notes.isEmpty()) error("음표가 비어 있습니다.")
                lyricLines = LyricLines.build(notes, detail.lyricsPlain)
                beatGrid = analysis.beatGrid
                meter = NoteNormalizer.meterOf(analysis.timeSignature)
                player.load(engine.instrumentalUrl(songId, 0))
                _state.update {
                    it.copy(phase = Phase.READY, title = detail.summary.title, artist = detail.summary.artist, keyName = analysis.keyName, playerReady = true)
                }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: e.toString()) }
            }
        }
    }

    fun setSkipIntro(value: Boolean) = _state.update { it.copy(skipIntro = value) }
    fun setHeadphones(value: Boolean) = _state.update { it.copy(headphones = value) }
    fun setAutoCalibrate(value: Boolean) {
        _state.update { it.copy(autoCalibrate = value) }
        scorer?.autoCalibrate = value
    }

    /** 반주 키 변경. 0 이 아니면 엔진에 미리 렌더를 요청한 뒤 스트림 URL 을 바꾼다. */
    fun changeKey(next: Int) {
        val target = next.coerceIn(ScoringConstants.KEY_SHIFT_MIN, ScoringConstants.KEY_SHIFT_MAX)
        val s = _state.value
        if (target == s.keyShift || s.keyBusy || s.phase == Phase.RUNNING || s.phase == Phase.COUNTDOWN) return
        viewModelScope.launch {
            _state.update { it.copy(keyBusy = true, error = null) }
            try {
                if (target != 0) engine.api.renderInstrumentalKey(songId, SemitonesRequest(target))
                player.load(engine.instrumentalUrl(songId, target))
                scorer?.keyShift = target
                _state.update { it.copy(keyShift = target) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "키 변경 실패: ${engine.describe(e)}") }
            } finally {
                _state.update { it.copy(keyBusy = false) }
            }
        }
    }

    /** RECORD_AUDIO 권한이 있는 상태에서 호출. 마이크 → 카운트다운 → 반주 재생. */
    fun start() {
        val s = _state.value
        if (s.phase != Phase.READY && s.phase != Phase.FINISHED) return
        notes.forEach { it.reset() }
        val newScorer = LiveScorer(notes).also { it.keyShift = s.keyShift; it.autoCalibrate = s.autoCalibrate }
        scorer = newScorer
        sync = null
        _state.update { it.copy(totals = null, measures = emptyList(), lastJudged = null, report = Report(), error = null) }
        try {
            wavFile = File(cacheDir, "live-take-${System.currentTimeMillis()}.wav")
            capture.start(wavFile) { frame -> frameChannel.trySend(frame) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "마이크를 사용할 수 없습니다: ${e.message}") }
            return
        }
        viewModelScope.launch {
            val firstNote = notes.first().start
            player.seekTo(if (_state.value.skipIntro) maxOf(0.0, firstNote - 4) else 0.0)
            _state.update { it.copy(phase = Phase.COUNTDOWN) }
            for (step in 3 downTo 1) {
                _state.update { it.copy(countdown = step) }
                delay(800)
            }
            _state.update { it.copy(countdown = 0) }
            player.play()
            _state.update { it.copy(phase = Phase.RUNNING) }
            tickJob = launch { tickLoop(newScorer) }
        }
    }

    private suspend fun tickLoop(scorer: LiveScorer) {
        val lastEnd = notes.last().end
        while (viewModelScope.isActive && _state.value.phase == Phase.RUNNING) {
            val now = player.positionSec
            // 캡처 시계 ↔ MR 시계 재동기화 (2 s 마다, 또는 첫 tick)
            val s = sync
            if (s != null && (abs(s.latestT - s.t) > 2 || s.mr == 0.0)) sync = Sync(s.latestT, now, s.latestT)
            val done = scorer.tick(now)
            if (done.isNotEmpty()) {
                _state.update { it.copy(totals = scorer.totals(), lastJudged = done.last(), measures = scorer.measures(), nowSec = now) }
            } else {
                _state.update { it.copy(nowSec = now) }
            }
            if (player.isEnded || now >= lastEnd + 2) {
                stop(finished = true)
                return
            }
            delay(33)
        }
    }

    private fun onFrame(frame: PitchCapture.PitchFrame) {
        val voiced = ScoringMath.isVoiced(frame.f0, frame.clarity, frame.rms, frame.rmsLow)
        val midi = if (voiced) ScoringMath.hzToMidi(frame.f0) else null
        val s = sync
        if (s == null) {
            sync = Sync(frame.t, 0.0, frame.t)
        } else {
            s.latestT = frame.t
            if (_state.value.phase == Phase.RUNNING) scorer?.addFrame(Frame(frame.t - s.t + s.mr, midi))
        }
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMicUpdate > 200) {
            lastMicUpdate = nowMs
            _state.update { it.copy(micRms = frame.rms, micNote = midi?.let { m -> ScoringMath.midiName(m) }) }
        }
    }

    fun stop(finished: Boolean) {
        val s = _state.value
        if (s.phase != Phase.RUNNING && s.phase != Phase.COUNTDOWN) return
        tickJob?.cancel(); tickJob = null
        player.pause()
        wavFile = capture.stop()
        val scorer = scorer
        val judged = scorer?.totals()?.judged ?: 0
        _state.update {
            it.copy(
                phase = if (finished || judged > 0) Phase.FINISHED else Phase.READY,
                totals = scorer?.totals(), measures = scorer?.measures() ?: emptyList(),
            )
        }
    }

    /** 종료 후 녹음 WAV 를 /compare 에 올려 정밀 채점. 완료되면 report.jobId 로 결과 화면을 연다. */
    fun makePreciseReport() {
        val file = wavFile
        if (file == null || !file.exists() || file.length() < 20_000) {
            _state.update { it.copy(report = Report(ReportPhase.ERROR, "녹음 데이터가 없습니다. 다시 한 번 불러주세요.")) }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(report = Report(ReportPhase.UPLOADING, "녹음을 AI 엔진으로 전송하고 있습니다.", 2)) }
            try {
                val sourceType = if (_state.value.headphones) "vocal" else "mix"
                val perf = engine.startCompare(songId, file, sourceType = sourceType)
                _state.update { it.copy(report = Report(ReportPhase.ANALYZING, "정밀 분석 중…", 5, perf.id)) }
                engine.awaitComparison(perf.id) { job ->
                    _state.update { it.copy(report = Report(ReportPhase.ANALYZING, job.message ?: "분석 중…", job.progress, perf.id)) }
                }
                _state.update { it.copy(report = Report(ReportPhase.DONE, "정밀 리포트 완성", 100, perf.id)) }
            } catch (e: Exception) {
                _state.update { it.copy(report = Report(ReportPhase.ERROR, engine.describe(e))) }
            }
        }
    }

    override fun onCleared() {
        tickJob?.cancel()
        if (capture.isRunning) capture.stop()
        player.release()
        frameChannel.close()
    }
}
