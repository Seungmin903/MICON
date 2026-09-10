package com.micon.app.ui.practice

import android.content.Context
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.media.MediaPlayer
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.audio.InstrumentalPlayer
import com.micon.app.audio.PitchCapture
import com.micon.app.data.SongRepository
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.engine.dto.BeatGrid
import com.micon.app.data.engine.dto.SemitonesRequest
import com.micon.app.data.engine.dto.toLiveNotes
import com.micon.app.scoring.DisplayBar
import com.micon.app.scoring.DisplayBars
import com.micon.app.scoring.Frame
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveScorer
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.Interlude
import com.micon.app.scoring.LyricLine
import com.micon.app.scoring.LyricLines
import com.micon.app.scoring.LyricSheet
import com.micon.app.scoring.LyricTiming
import com.micon.app.scoring.MeasureRow
import com.micon.app.scoring.SectionMap
import com.micon.app.scoring.SongSections
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
 *
 * 흐름: load → READY → start(마이크 켜기 → 반주 준비 대기 → 카운트다운 → 재생) → RUNNING(tick 30 Hz)
 *      ↔ PAUSED(이어서 부르기·탐색·구간 반복 설정) → FINISHED → 녹음 다시 듣기 / 정밀 리포트(/compare).
 * - 일시정지 중에도 마이크(WAV 녹음)는 유지해 한 파일로 /compare 에 올린다. 프레임은 RUNNING 일 때만 채점에 넣는다.
 * - LiveScorer 는 메인 스레드에서만 만진다 (캡처 스레드는 채널로 프레임만 넘김).
 * - 구간 반복(loop): 가사 줄 하나를 골라 두면 그 끝에서 시작으로 되감고 그 구간 음표를 다시 판정한다.
 * - 구간 점프(절·하이라이트·간주 건너뛰기): 재생 중에도 가능. 앞으로 건너뛴 음표는 판정하지 않고 총점에서 뺀다.
 * - 캡처 시계 ↔ MR 시계 동기: tick 이 "가장 최근 도착한 프레임의 캡처 시각 ↔ 지금 재생 위치" 를 2초마다 다시 맞춘다.
 *   동기가 잡히기 전(sync == null) 프레임은 채점에 넣지 않는다 — 되감기·점프 직후 엉뚱한 시각의 프레임이 섞이는 것을 막는다.
 */
class PracticeViewModel(
    appContext: Context,
    val songId: String,
    private val repo: SongRepository,
    private val engine: EngineClient,
) : ViewModel() {
    enum class Phase { LOADING, READY, COUNTDOWN, RUNNING, PAUSED, FINISHED, ERROR }
    enum class ReportPhase { NONE, UPLOADING, ANALYZING, DONE, ERROR }

    data class Report(val phase: ReportPhase = ReportPhase.NONE, val message: String = "", val progress: Int = 0, val jobId: String? = null)

    /** 구간 반복 범위(MR 초). lineIndex 는 표시용. */
    data class Loop(val lineIndex: Int, val start: Double, val end: Double)

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val title: String = "",
        val artist: String = "",
        val keyName: String? = null,
        val keyShift: Int = 0,
        val keyBusy: Boolean = false,
        val skipIntro: Boolean = true,
        val autoCalibrate: Boolean = true,
        val headphones: Boolean = false,     // true → /compare sourceType=vocal (분리 생략). 스피커로 들으면 false 가 맞다. 헤드셋이 연결돼 있으면 자동으로 true.
        val headsetDetected: Boolean = false, // 유선/블루투스/USB 헤드셋 출력이 잡혔는가 (시작 시점 검사)
        val haptics: Boolean = true,
        val showRunway: Boolean = true,
        val countdown: Int = 0,
        val buffering: Boolean = false,      // 반주 준비 대기 중
        val nowSec: Double = 0.0,
        val durationSec: Double = 0.0,
        val totals: Totals? = null,
        val measures: List<MeasureRow> = emptyList(),
        val lastJudged: LiveNote? = null,
        val lastJudgedAtMs: Long = 0L,
        val micRms: Double = 0.0,
        val micNote: String? = null,
        val calibrationMs: Int = 0,
        val loop: Loop? = null,
        val loopCount: Int = 0,
        val lyricTiming: LyricTiming = LyricTiming.NONE,
        val interlude: Interlude? = null,      // 지금 간주 안이면 그 구간 (RUNNING 에서 "간주 건너뛰기")
        val sectionIndex: Int? = null,         // 지금 위치가 속한 구간(절)
        val takePlaying: Boolean = false,
        val report: Report = Report(),
        val error: String? = null,
    ) {
        val isRunning: Boolean get() = phase == Phase.RUNNING || phase == Phase.COUNTDOWN
        val shiftedKeyName: String? get() = keyName?.let { shiftKeyName(it, keyShift) }
    }

    private val _state = MutableStateFlow(UiState())
    val state = _state.asStateFlow()

    val player = InstrumentalPlayer(appContext, engine.okHttp)
    private val audioManager: AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val capture = PitchCapture()
    private val cacheDir = appContext.cacheDir
    private val vibrator: Vibrator? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) (appContext.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        else @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }.getOrNull()

    // 화면 그리기용 (메인 스레드에서만 읽고 쓴다)
    var notes: List<LiveNote> = emptyList(); private set
    var sheet: LyricSheet = LyricSheet.EMPTY; private set
    val lyricLines: List<LyricLine> get() = sheet.lines
    /** 러너웨이용 이어 붙인 음표 막대 (음절 단위) */
    var displayBars: List<DisplayBar> = emptyList(); private set
    var sections: SectionMap = SectionMap.EMPTY; private set
    var beatGrid: BeatGrid? = null; private set
    var meter: Int = 4; private set
    var tempoBpm: Int = 100; private set
    /** 카운트인 "탁 탁 탁" — 노래방처럼 박자에 맞춰 준비 시간을 준다. 기기가 지원하지 않으면 무음. */
    private val clicks: ToneGenerator? = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 90) }.getOrNull()
    var scorer: LiveScorer? = null; private set
    val frames: List<Frame> get() = scorer?.frames ?: emptyList()

    /** 캡처 시계 t ↔ MR 시계 mr 의 대응점. tick 에서만 만든다. */
    private data class Sync(val t: Double, val mr: Double)
    private var sync: Sync? = null
    private var latestCaptureT = Double.NaN
    private val frameChannel = Channel<PitchCapture.PitchFrame>(capacity = 512, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    private var lastMicUpdate = 0L
    private var tickJob: Job? = null
    private var countdownJob: Job? = null
    private var wavFile: File? = null
    private var takePlayer: MediaPlayer? = null
    private var pendingSeek: Double? = null     // READY 에서 사용자가 옮긴 시작 위치 (전주 건너뛰기보다 우선)

    init {
        player.onError = { message -> _state.update { it.copy(error = "반주 재생 오류: $message", buffering = false) } }
        viewModelScope.launch { for (frame in frameChannel) onFrame(frame) }
        cleanupOldTakes()
        refreshHeadset()
        load()
    }

    /** 헤드셋(유선·블루투스·USB) 출력이 연결돼 있으면 "이어폰" 을 자동으로 켠다. 사용자가 끈 경우는 존중한다. */
    fun refreshHeadset() {
        val devices = audioManager?.getDevices(AudioManager.GET_DEVICES_OUTPUTS) ?: return
        val headset = devices.any {
            it.type == AudioDeviceInfo.TYPE_WIRED_HEADSET || it.type == AudioDeviceInfo.TYPE_WIRED_HEADPHONES ||
                it.type == AudioDeviceInfo.TYPE_BLUETOOTH_A2DP || it.type == AudioDeviceInfo.TYPE_USB_HEADSET ||
                (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && it.type == AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
        _state.update { it.copy(headsetDetected = headset, headphones = if (headset && !it.headsetDetected) true else it.headphones) }
    }

    private fun load() {
        viewModelScope.launch {
            try {
                val detail = repo.detail(songId) ?: error("곡을 찾을 수 없습니다: $songId")
                val analysis = detail.analysis ?: error("분석 데이터가 없는 곡입니다.")
                if (!detail.hasInstrumental) error("이 곡은 반주가 준비되지 않아 연습할 수 없습니다.")
                val karaoke = repo.karaokeNotes(songId) ?: error("음표 데이터(song_notes)가 없습니다.")
                notes = karaoke.toLiveNotes(analysis.timeSignature)
                if (notes.isEmpty()) error("음표가 비어 있습니다.")
                sheet = LyricLines.build(notes, detail.lyricsSynced, detail.lyricsPlain)
                // 가사 줄 구간 밖 음표(전주·간주의 코러스·허밍)는 그리지도 판정하지도 않는다. 근사 가사(APPROX)는 구간이 부정확해 제외.
                val filterLines = if (sheet.timing == LyricTiming.SYNCED || sheet.timing == LyricTiming.NOTES) sheet.lines else emptyList()
                notes.forEach { it.judgeable = DisplayBars.inLyricSpan(it, filterLines) }
                displayBars = DisplayBars.build(notes, filterLines)
                beatGrid = analysis.beatGrid
                meter = NoteNormalizer.meterOf(analysis.timeSignature)
                tempoBpm = analysis.tempoBpm?.takeIf { it in 40..240 } ?: 100
                sections = SongSections.build(notes, sheet.lines, barStarts())
                player.load(engine.instrumentalUrl(songId, 0))
                _state.update {
                    it.copy(
                        phase = Phase.READY, title = detail.summary.title, artist = detail.summary.artist, keyName = analysis.keyName,
                        durationSec = fallbackDuration(), lyricTiming = sheet.timing, sectionIndex = sections.sectionIndexAt(0.0),
                    )
                }
                // 길이는 스트림 메타데이터가 오면 갱신
                launch {
                    if (player.awaitReady(10_000)) _state.update { it.copy(durationSec = player.durationSec ?: fallbackDuration()) }
                }
            } catch (e: Exception) {
                _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: e.toString()) }
            }
        }
    }

    private fun fallbackDuration(): Double = (notes.lastOrNull()?.end ?: 0.0) + 6.0

    /** 비트 그리드에서 마디 시작 시각만 골라낸다 (offsetBeats 기준으로 meter 마다). */
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

    /** 카운트인 한 박의 길이: 곡 템포. 너무 빠르거나 느리면 0.45–0.75 s 로 묶는다. */
    private fun beatMillis(): Long = ((60.0 / tempoBpm).coerceIn(0.45, 0.75) * 1000).toLong()

    // ------------------------------------------------------------------ 설정

    fun setSkipIntro(value: Boolean) = _state.update { it.copy(skipIntro = value) }
    fun setHeadphones(value: Boolean) = _state.update { it.copy(headphones = value) }
    fun setHaptics(value: Boolean) = _state.update { it.copy(haptics = value) }
    fun toggleRunway() = _state.update { it.copy(showRunway = !it.showRunway) }
    fun setAutoCalibrate(value: Boolean) {
        _state.update { it.copy(autoCalibrate = value) }
        scorer?.autoCalibrate = value
    }

    /** 반주 키 변경 (READY·PAUSED·FINISHED). 0 이 아니면 엔진에 미리 렌더를 요청한 뒤 스트림 URL 을 바꾼다. */
    fun changeKey(next: Int) {
        val target = next.coerceIn(ScoringConstants.KEY_SHIFT_MIN, ScoringConstants.KEY_SHIFT_MAX)
        val s = _state.value
        if (target == s.keyShift || s.keyBusy || s.isRunning) return
        viewModelScope.launch {
            _state.update { it.copy(keyBusy = true, error = null) }
            val position = player.positionSec
            try {
                if (target != 0) engine.api.renderInstrumentalKey(songId, SemitonesRequest(target))
                player.load(engine.instrumentalUrl(songId, target))
                if (player.awaitReady(15_000)) player.seekTo(position)
                scorer?.keyShift = target
                _state.update { it.copy(keyShift = target) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "키 변경 실패: ${engine.describe(e)}") }
            } finally {
                _state.update { it.copy(keyBusy = false) }
            }
        }
    }

    // ------------------------------------------------------------------ 재생 제어

    /** RECORD_AUDIO 권한이 있는 상태에서 호출. 마이크 → 반주 준비 → 카운트다운 → 반주 재생. */
    fun start() {
        refreshHeadset()
        val s = _state.value
        if (s.phase != Phase.READY && s.phase != Phase.FINISHED) return
        stopTake()
        notes.forEach { it.reset() }
        val newScorer = LiveScorer(notes).also { it.keyShift = s.keyShift; it.autoCalibrate = s.autoCalibrate }
        scorer = newScorer
        sync = null
        _state.update { it.copy(totals = null, measures = emptyList(), lastJudged = null, report = Report(), error = null, loopCount = 0, calibrationMs = 0) }
        if (!capture.isRunning) {
            try {
                wavFile = File(cacheDir, "live-take-${System.currentTimeMillis()}.wav")
                capture.start(wavFile) { frame -> frameChannel.trySend(frame) }
            } catch (e: Exception) {
                _state.update { it.copy(error = "마이크를 사용할 수 없습니다: ${e.message}") }
                return
            }
        }
        val startAt = pendingSeek ?: _state.value.loop?.let { maxOf(0.0, it.start - 1.0) }
            ?: if (_state.value.skipIntro) maxOf(0.0, notes.first().start - 4) else 0.0
        pendingSeek = null
        beginPlayback(startAt, countFrom = meter)
    }

    /** PAUSED → 박자 카운트인 후 현재 위치에서 이어서. */
    fun resume() {
        if (_state.value.phase != Phase.PAUSED) return
        sync = null
        beginPlayback(player.positionSec, countFrom = meter)
    }

    private fun beginPlayback(startAt: Double, countFrom: Int) {
        countdownJob?.cancel()
        countdownJob = viewModelScope.launch {
            _state.update { it.copy(phase = Phase.COUNTDOWN, countdown = 0, buffering = true) }
            if (!player.isReady) {
                if (player.isIdle) player.load(engine.instrumentalUrl(songId, _state.value.keyShift))
                if (!player.awaitReady(8000)) {
                    val judged = scorer?.totals()?.judged ?: 0
                    _state.update { it.copy(phase = if (judged > 0) Phase.PAUSED else Phase.READY, buffering = false, error = "반주를 불러오지 못했습니다. 엔진 연결을 확인하고 다시 시도하세요.") }
                    return@launch
                }
            }
            player.seekTo(startAt)
            _state.update { it.copy(buffering = false, nowSec = startAt, durationSec = player.durationSec ?: it.durationSec) }
            // 노래방식 카운트인: 곡 템포로 "탁 탁 탁 탁", 마지막 박은 높은 소리. 그 다음 박에 반주가 들어간다.
            val beat = beatMillis()
            for (step in countFrom downTo 1) {
                _state.update { it.copy(countdown = step) }
                runCatching { clicks?.startTone(if (step == 1) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 60) }
                delay(beat)
            }
            _state.update { it.copy(countdown = 0) }
            player.play()
            _state.update { it.copy(phase = Phase.RUNNING) }
            val scorer = scorer ?: return@launch
            tickJob?.cancel()
            tickJob = launch { tickLoop(scorer) }
        }
    }

    /** 일시정지: 반주만 멈추고 마이크·판정 상태는 유지한다. 카운트다운 중이면 카운트다운을 취소한다. */
    fun pause() {
        val s = _state.value
        if (!s.isRunning) return
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        val scorer = scorer
        _state.update { it.copy(phase = Phase.PAUSED, countdown = 0, buffering = false, totals = scorer?.totals(), measures = scorer?.measures() ?: emptyList(), interlude = sections.interludeAt(player.positionSec)) }
    }

    /** 끝까지 부르지 않고 마치기(PAUSED → FINISHED). 판정한 음표가 하나도 없으면 READY 로 돌아간다. */
    fun finishEarly() {
        if (_state.value.phase != Phase.PAUSED) return
        finish()
    }

    private fun finish() {
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        wavFile = capture.stop() ?: wavFile
        val scorer = scorer
        val judged = scorer?.totals()?.judged ?: 0
        _state.update {
            it.copy(
                phase = if (judged > 0) Phase.FINISHED else Phase.READY, countdown = 0, buffering = false, interlude = null,
                totals = scorer?.totals(), measures = scorer?.measures() ?: emptyList(),
                calibrationMs = ((scorer?.calibration ?: 0.0) * 1000).toInt(),
            )
        }
    }

    /** 처음부터: 판정·녹음을 버리고 READY 로. */
    fun restart() {
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        if (capture.isRunning) capture.stop()
        stopTake()
        notes.forEach { it.reset() }
        scorer = null
        sync = null
        pendingSeek = null
        player.seekTo(0.0)
        _state.update { it.copy(phase = Phase.READY, countdown = 0, buffering = false, nowSec = 0.0, totals = null, measures = emptyList(), lastJudged = null, report = Report(), loopCount = 0, calibrationMs = 0, error = null, interlude = null, sectionIndex = sections.sectionIndexAt(0.0)) }
    }

    /**
     * 위치 이동(진행 바 탐색·절 점프·간주 건너뛰기). READY/FINISHED: 시작 위치 지정.
     * PAUSED/RUNNING: 뒤로 가면 그 뒤 음표를 미판정으로 되돌리고, 앞으로 가면 건너뛴 음표는 판정하지 않는다(총점 제외).
     * 재생 중이면 반주만 옮기고 마이크·판정은 이어 간다. 구간 반복 범위를 벗어나면 반복을 해제한다.
     */
    fun seekTo(seconds: Double) {
        val s = _state.value
        val target = seconds.coerceIn(0.0, maxOf(0.0, s.durationSec))
        val loop = s.loop?.takeIf { target >= it.start - 1.5 && target <= it.end }
        when (s.phase) {
            Phase.READY, Phase.FINISHED -> {
                pendingSeek = target; player.seekTo(target)
                _state.update { it.copy(nowSec = target, loop = loop, sectionIndex = sections.sectionIndexAt(target), interlude = null) }
            }
            Phase.PAUSED, Phase.RUNNING -> {
                val wasRunning = s.phase == Phase.RUNNING
                if (wasRunning) { tickJob?.cancel(); tickJob = null; player.pause() }
                scorer?.jumpTo(target, from = player.positionSec)
                player.seekTo(target)
                sync = null
                _state.update {
                    it.copy(
                        nowSec = target, loop = loop, loopCount = if (loop == null) 0 else it.loopCount,
                        totals = scorer?.totals(), measures = scorer?.measures() ?: emptyList(),
                        sectionIndex = sections.sectionIndexAt(target), interlude = null,
                    )
                }
                // 재생 중 점프도 노래방처럼 카운트인을 주고 들어간다 — 어디서 시작하든 준비 시간이 있어야 맞출 수 있다
                if (wasRunning) beginPlayback(target, countFrom = meter)
            }
            else -> {}
        }
    }

    /** 절(구간) 시작 1.5초 앞으로 이동. 어느 단계에서든(카운트다운 제외) 가능. */
    fun jumpToSection(index: Int) {
        val section = sections.sections.getOrNull(index) ?: return
        seekTo(maxOf(0.0, section.start - SectionMap.JUMP_LEAD_SECONDS))
    }

    /** 최고음 구간(하이라이트)으로 이동. 시작 시각은 이미 "구절 한 마디 앞" 으로 계산돼 있다. */
    fun jumpToHighlight() {
        val h = sections.highlight ?: return
        seekTo(h.start)
    }

    /** 지금 간주 안이면 간주 끝 1.5초 앞으로. 연습 중·일시정지 모두. */
    fun skipInterlude() {
        val now = _state.value.nowSec
        val interlude = _state.value.interlude ?: sections.interludeAt(now) ?: return
        if (interlude.end - now <= SectionMap.JUMP_LEAD_SECONDS) return
        seekTo(interlude.end - SectionMap.JUMP_LEAD_SECONDS)
    }

    /** 가사 줄을 구간 반복 범위로 지정(다시 누르면 해제). READY·PAUSED 에서만. */
    fun toggleLoop(lineIndex: Int) {
        val s = _state.value
        if (s.isRunning) return
        val line = lyricLines.getOrNull(lineIndex) ?: return
        if (s.loop?.lineIndex == lineIndex) { clearLoop(); return }
        val loop = Loop(lineIndex, line.start, line.end + 0.4)
        _state.update { it.copy(loop = loop, loopCount = 0) }
        seekTo(maxOf(0.0, loop.start - 1.0))
    }

    fun clearLoop() = _state.update { it.copy(loop = null, loopCount = 0) }

    private suspend fun tickLoop(scorer: LiveScorer) {
        val lastEnd = notes.last().end
        while (viewModelScope.isActive && _state.value.phase == Phase.RUNNING) {
            val now = player.positionSec
            // 캡처 시계 ↔ MR 시계 재동기화: 첫 tick, 점프 직후(sync == null), 그리고 2 s 마다
            val s = sync
            if (!latestCaptureT.isNaN() && (s == null || abs(latestCaptureT - s.t) > 2)) sync = Sync(latestCaptureT, now)
            val done = scorer.tick(now)
            val loop = _state.value.loop
            val interlude = sections.interludeAt(now)
            val sectionIndex = sections.sectionIndexAt(now)
            if (done.isNotEmpty()) {
                val last = done.last()
                if (_state.value.haptics) vibrateFor(last.status)
                _state.update {
                    it.copy(totals = scorer.totals(), lastJudged = last, lastJudgedAtMs = System.currentTimeMillis(), measures = scorer.measures(), nowSec = now,
                        calibrationMs = (scorer.calibration * 1000).toInt(), interlude = interlude, sectionIndex = sectionIndex)
                }
            } else {
                _state.update { it.copy(nowSec = now, interlude = interlude, sectionIndex = sectionIndex) }
            }
            if (loop != null && now >= loop.end) {
                // 구간 끝 → 시작 1초 앞으로 되감고 그 구간 음표를 다시 판정
                val back = maxOf(0.0, loop.start - 1.0)
                scorer.rewindTo(back)
                player.seekTo(back)
                sync = null
                _state.update { it.copy(loopCount = it.loopCount + 1, nowSec = back, totals = scorer.totals(), measures = scorer.measures()) }
                delay(33)
                continue
            }
            if (player.isEnded || now >= lastEnd + 2) {
                finish()
                return
            }
            delay(33)
        }
    }

    private fun vibrateFor(status: LiveStatus) {
        val v = vibrator ?: return
        val ms = when (status) { LiveStatus.MISSED, LiveStatus.WRONG -> 45L; LiveStatus.SHARP, LiveStatus.FLAT -> 20L; else -> return }
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) v.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
            else @Suppress("DEPRECATION") v.vibrate(ms)
        }
    }

    private fun onFrame(frame: PitchCapture.PitchFrame) {
        val voiced = ScoringMath.isVoiced(frame.f0, frame.clarity, frame.rms, frame.rmsLow)
        val midi = if (voiced) ScoringMath.hzToMidi(frame.f0) else null
        latestCaptureT = frame.t
        val s = sync
        if (s != null && _state.value.phase == Phase.RUNNING) scorer?.addFrame(Frame(frame.t - s.t + s.mr, midi))
        val nowMs = System.currentTimeMillis()
        if (nowMs - lastMicUpdate > 150) {
            lastMicUpdate = nowMs
            _state.update { it.copy(micRms = frame.rms, micNote = midi?.let { m -> ScoringMath.midiName(m) }) }
        }
    }

    /** 시작 전 마이크 미리보기(레벨·음이름). 권한이 있을 때 READY 에서 호출. */
    fun startMicPreview() {
        if (capture.isRunning || _state.value.phase != Phase.READY) return
        runCatching {
            wavFile = File(cacheDir, "live-take-${System.currentTimeMillis()}.wav")
            capture.start(wavFile) { frame -> frameChannel.trySend(frame) }
        }.onFailure { e -> _state.update { it.copy(error = "마이크를 사용할 수 없습니다: ${e.message}") } }
    }

    // ------------------------------------------------------------------ 종료 후

    /** 방금 부른 녹음 다시 듣기 (FINISHED). */
    fun playTake() {
        val file = wavFile?.takeIf { it.exists() && it.length() > 44 } ?: return
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

    /** 이전 세션이 남긴 녹음 파일 정리 (이 화면이 만든 live-take-*.wav 만). */
    private fun cleanupOldTakes() {
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith("live-take-") && f.name.endsWith(".wav") }?.forEach { it.delete() }
        }
    }

    override fun onCleared() {
        countdownJob?.cancel()
        tickJob?.cancel()
        if (capture.isRunning) capture.stop()
        stopTake()
        player.release()
        runCatching { clicks?.release() }
        frameChannel.close()
        wavFile?.delete()
    }

    companion object {
        private val SHARP_NAMES = listOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")
        private val FLAT_NAMES = listOf("C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B")

        /** "E♭ major" 를 [shift] 반음 이동한 이름. 파싱 실패 시 원본 그대로. */
        fun shiftKeyName(keyName: String, shift: Int): String {
            if (shift == 0) return keyName
            val parts = keyName.trim().split(" ", limit = 2)
            val tonic = parts[0].replace("#", "♯").replace("b", "♭")
            val index = SHARP_NAMES.indexOf(tonic).takeIf { it >= 0 } ?: FLAT_NAMES.indexOf(tonic).takeIf { it >= 0 } ?: return keyName
            val shifted = ((index + shift) % 12 + 12) % 12
            val useFlat = tonic.contains("♭")
            val name = if (useFlat) FLAT_NAMES[shifted] else SHARP_NAMES[shifted]
            return if (parts.size > 1) "$name ${parts[1]}" else name
        }
    }
}
