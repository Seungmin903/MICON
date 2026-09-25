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
import com.micon.app.data.PracticeSection
import com.micon.app.data.backend.AuthSessionExpiredException
import com.micon.app.data.backend.BackendRequestException
import com.micon.app.core.auth.SessionSnapshot
import com.micon.app.core.auth.matchesRecordingOwner
import com.micon.app.data.practice.PracticeRecordingStore
import com.micon.app.data.practice.PracticeRepository
import com.micon.app.data.practice.PracticeScoreMapper
import com.micon.app.data.backend.dto.BeatGrid
import com.micon.app.data.backend.dto.toLiveNotes
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
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
import java.io.IOException
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * 노래 연습 화면 로직 (live-scoring.tsx 의 React 상태 부분을 ViewModel 로).
 *
 * 흐름: load(백엔드 practice-assets) → READY → start(마이크 켜기 → 반주 준비 대기 → 카운트다운 → 재생) → RUNNING(tick 30 Hz)
 *      ↔ PAUSED(이어서 부르기·탐색·구간 반복 설정) → FINISHED → 녹음 다시 듣기 / 정밀 리포트(백엔드 /practices 연동 예정).
 * - 일시정지 중에도 마이크(WAV 녹음)는 유지해 한 파일로 올린다. 프레임은 RUNNING 일 때만 채점에 넣는다.
 * - 반주는 백엔드가 practice-assets 로 준 만료 URL 을 스트리밍한다. 곡 데이터·엔진 직접 호출은 없다.
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
    mediaHttp: OkHttpClient,
    private val practices: PracticeRepository,
    private val recordings: PracticeRecordingStore,
    private val sessionSnapshot: () -> SessionSnapshot,
    private val isLoggedIn: () -> Boolean,
    private val initialKey: Int = 0,
) : ViewModel() {
    enum class Phase { LOADING, READY, COUNTDOWN, RUNNING, PAUSED, FINISHED, ERROR }

    /** 연습 기록 저장(백엔드 /practices). GUEST 는 비회원이라 저장하지 않는 정상 상태, ERROR 는 [retrySave] 로 다시 시도한다. */
    enum class SavePhase { NONE, SAVING, SAVED, GUEST, ERROR }

    data class Save(val phase: SavePhase = SavePhase.NONE, val message: String = "", val practiceId: Long? = null,
                    val recordingSaveFailed: Boolean = false)

    /** 구간 반복 범위(MR 초). lineIndex 는 표시용. */
    data class Loop(val lineIndex: Int, val start: Double, val end: Double)

    data class UiState(
        val phase: Phase = Phase.LOADING,
        val title: String = "",
        val artist: String = "",
        val keyName: String? = null,
        val keyShift: Int = 0,
        val skipIntro: Boolean = true,
        val autoCalibrate: Boolean = true,
        val headphones: Boolean = false,     // true → /compare sourceType=vocal (분리 생략). 스피커로 들으면 false 가 맞다. 헤드셋이 연결돼 있으면 자동으로 true.
        val headsetDetected: Boolean = false, // 유선/블루투스/USB 헤드셋 출력이 잡혔는가 (시작 시점 검사)
        val haptics: Boolean = true,
        val showRunway: Boolean = true,
        val countdown: Int = 0,
        val buffering: Boolean = false,      // 반주 준비 대기 중
        val keySwitching: Boolean = false,   // 재생 위치를 유지하며 키별 S3 반주 교체 중
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
        val save: Save = Save(),
        val error: String? = null,
    ) {
        val isRunning: Boolean get() = phase == Phase.RUNNING || phase == Phase.COUNTDOWN
        val shiftedKeyName: String? get() = keyName?.let { shiftKeyName(it, keyShift) }
    }

    private val _state = MutableStateFlow(UiState(keyShift = initialKey))
    val state = _state.asStateFlow()

    val player = InstrumentalPlayer(appContext, mediaHttp)
    private var audioReleased = false
    /** 현재 키의 반주 스트리밍 URL (백엔드 발급, 만료 있음). */
    private var instrumentalUrl: String? = null
    private val instrumentalCache = KeyedInstrumentalCache()
    private var keySwitchJob: Job? = null
    private var pendingKey: Int? = null
    private var keyChangeCount = 0
    private var sessionInitialKey: Int? = null
    private val audioManager: AudioManager? = appContext.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
    private val capture = PitchCapture(fmin = ScoringConstants.PRACTICE_PITCH_MIN_HZ)
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
    private var takeOwner: SessionSnapshot? = null
    private var takeSerial = 0L
    private var takePlayer: MediaPlayer? = null
    private var pendingSeek: Double? = null     // READY 에서 사용자가 옮긴 시작 위치 (전주 건너뛰기보다 우선)

    /** 백엔드 song_section (practice-assets). 구간 평가 저장에 쓴다. */
    private var backendSections: List<PracticeSection> = emptyList()
    /** practice-assets 의 dataVersion — 기록 저장 시 노트 인덱스 기준으로 함께 보낸다. */
    private var assetsDataVersion: String? = null
    /** 열려 있는 백엔드 연습 세션. [practiceOpen] 이면 아직 complete/cancel 전. */
    private var practiceId: Long? = null
    private var practiceOpen = false
    private val practiceMutex = Mutex()
    private var saveJob: Job? = null
    /** 화면이 사라진 뒤에도 미완료 세션을 취소하기 위한 스코프(onCleared 는 suspend 불가). */
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                require(initialKey in ScoringConstants.KEY_SHIFT_MIN..ScoringConstants.KEY_SHIFT_MAX) { "지원하지 않는 연습 키예요." }
                val detail = repo.detail(songId) ?: error("곡을 찾을 수 없습니다: $songId")
                // 409(준비 안 됨)는 PracticeAssetsNotReadyException 의 서버 메시지를 그대로 화면에 보인다.
                val assets = repo.practiceAssets(songId, initialKey)
                check(assets.songId == songId && assets.selectedKey == initialKey) { "요청한 곡과 반주 정보가 일치하지 않습니다." }
                val karaoke = assets.targetNotes
                if (karaoke.isEmpty()) error("음표 데이터(song_note)가 없습니다.")
                notes = karaoke.toLiveNotes(assets.timeSignature)
                if (notes.isEmpty()) error("음표가 비어 있습니다.")
                sheet = LyricLines.build(notes, assets.lyricsSynced, assets.lyricsPlain)
                // 가사 줄 구간 밖 음표(전주·간주의 코러스·허밍)는 그리지도 판정하지도 않는다. 근사 가사(APPROX)는 구간이 부정확해 제외.
                val filterLines = if (sheet.timing == LyricTiming.SYNCED || sheet.timing == LyricTiming.NOTES) sheet.lines else emptyList()
                notes.forEach { it.judgeable = DisplayBars.inLyricSpan(it, filterLines) }
                displayBars = DisplayBars.build(notes, filterLines)
                beatGrid = assets.beatGrid
                backendSections = assets.sections
                assetsDataVersion = assets.dataVersion
                meter = NoteNormalizer.meterOf(assets.timeSignature)
                tempoBpm = (assets.tempoBpm ?: detail.analysis?.tempoBpm)?.takeIf { it in 40..240 } ?: 100
                sections = SongSections.build(notes, sheet.lines, barStarts())
                val url = assets.instrumental.playbackUrl
                    ?: error("반주 재생 주소가 없습니다. 백엔드 practice-assets 응답에 반주 URL(playbackUrl)이 추가되어야 합니다.")
                instrumentalCache.put(initialKey, assets.instrumental)
                instrumentalUrl = url
                player.load(url)
                _state.update {
                    it.copy(
                        phase = Phase.READY, title = detail.summary.title, artist = detail.summary.artist, keyName = assets.keyName ?: detail.analysis?.keyName,
                        durationSec = fallbackDuration(), lyricTiming = sheet.timing, sectionIndex = sections.sectionIndexAt(0.0),
                    )
                }
                // 길이는 스트림 메타데이터가 오면 갱신
                launch {
                    if (player.awaitReady(10_000)) _state.update { it.copy(durationSec = player.durationSec ?: fallbackDuration()) }
                }
                // 화면 표시를 막지 않고 나머지 키의 짧은 MR 응답만 미리 받아 둔다. 실패한 키는 선택할 때 다시 조회한다.
                for (key in ScoringConstants.KEY_SHIFT_MIN..ScoringConstants.KEY_SHIFT_MAX) {
                    if (key != initialKey) launch { runCatching { repo.instrumental(songId, key) }.onSuccess { instrumentalCache.put(key, it) } }
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

    /** 재생 위치를 유지한 채 키별 S3 반주를 교체한다. 연속 요청은 마지막 키만 한 번 더 적용한다. */
    fun changeKey(next: Int) {
        val target = next.coerceIn(ScoringConstants.KEY_SHIFT_MIN, ScoringConstants.KEY_SHIFT_MAX)
        val s = _state.value
        if (target == s.keyShift || s.phase !in setOf(Phase.READY, Phase.RUNNING, Phase.PAUSED)) return
        pendingKey = target
        if (keySwitchJob?.isActive == true) return
        keySwitchJob = viewModelScope.launch {
            while (true) {
                val requested = pendingKey ?: break
                pendingKey = null
                switchInstrumental(requested)
            }
        }
    }

    private suspend fun switchInstrumental(target: Int) {
        val before = _state.value
        if (target == before.keyShift) return
        val previousUrl = instrumentalUrl ?: return
        val previousPosition = player.positionSec
        val resumeRunning = before.phase == Phase.RUNNING
        tickJob?.cancel(); tickJob = null
        player.pause()
        sync = null
        _state.update { it.copy(keySwitching = true, buffering = true, error = null) }
        try {
            val media = instrumentalCache.valid(target) ?: repo.instrumental(songId, target).also { instrumentalCache.put(target, it) }
            val nextUrl = media.playbackUrl ?: error("선택한 키의 반주 재생 주소가 없습니다.")
            player.load(nextUrl)
            check(player.awaitReady(8_000)) { "선택한 키의 반주를 8초 안에 불러오지 못했습니다." }
            player.seekTo(previousPosition)
            instrumentalUrl = nextUrl
            scorer?.keyShift = target
            sync = null
            if (resumeRunning) player.play()
            if (sessionInitialKey != null) keyChangeCount += 1
            _state.update {
                it.copy(keyShift = target, nowSec = previousPosition, durationSec = player.durationSec ?: it.durationSec,
                    keySwitching = false, buffering = false, error = null)
            }
        } catch (error: Exception) {
            player.load(previousUrl)
            if (player.awaitReady(8_000)) {
                player.seekTo(previousPosition)
                if (resumeRunning) player.play()
            }
            scorer?.keyShift = before.keyShift
            sync = null
            _state.update {
                it.copy(keyShift = before.keyShift, nowSec = previousPosition, keySwitching = false, buffering = false,
                    error = "키를 바꾸지 못했습니다. 이전 키로 계속 재생합니다: ${error.message ?: "네트워크 오류"}")
            }
        } finally {
            if (resumeRunning) scorer?.let { active -> tickJob = viewModelScope.launch { tickLoop(active) } }
        }
    }

    // ------------------------------------------------------------------ 재생 제어

    /** RECORD_AUDIO 권한이 있는 상태에서 호출. 마이크 → 반주 준비 → 카운트다운 → 반주 재생. */
    fun start() {
        refreshHeadset()
        val s = _state.value
        if (s.phase != Phase.READY && s.phase != Phase.FINISHED) return
        if (s.phase == Phase.FINISHED && s.save.phase == SavePhase.SAVING) return
        takeSerial++
        stopTake()
        // READY's microphone preview must not be kept in the user's saved take.
        if (capture.isRunning) capture.stop()
        deleteTemporaryTake()
        takeOwner = sessionSnapshot().takeIf { it.isLoggedIn && it.userId > 0 }
        notes.forEach { it.reset() }
        val newScorer = LiveScorer(notes).also { it.keyShift = s.keyShift; it.autoCalibrate = s.autoCalibrate }
        scorer = newScorer
        sessionInitialKey = s.keyShift
        keyChangeCount = 0
        sync = null
        _state.update { it.copy(totals = null, measures = emptyList(), lastJudged = null, save = Save(), error = null, loopCount = 0, calibrationMs = 0) }
        try {
            wavFile = File(cacheDir, "live-take-${System.currentTimeMillis()}.wav")
            capture.start(wavFile) { frame -> frameChannel.trySend(frame) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "마이크를 사용할 수 없습니다: ${e.message}") }
            return
        }
        // 로그인 사용자: 백엔드 연습 세션(READY → IN_PROGRESS)을 미리 연다. 실패해도 연습은 진행하고 종료 때 다시 만든다.
        if (takeOwner != null) viewModelScope.launch { runCatching { openPractice() } }
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
                if (player.isIdle) instrumentalUrl?.let { player.load(it) }
                if (!player.awaitReady(8000)) {
                    val judged = scorer?.totals()?.judged ?: 0
                    _state.update { it.copy(phase = if (judged > 0) Phase.PAUSED else Phase.READY, buffering = false, error = "반주를 불러오지 못했습니다. 네트워크를 확인하고 다시 시도하세요.") }
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

    /** 끝까지 부르지 않고 마치기. 점수가 0점이어도 정상 완료·기록 저장 흐름으로 이어진다. */
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
        val totals = scorer?.totals()
        val hasAttempt = scorer != null
        _state.update {
            it.copy(
                phase = if (hasAttempt) Phase.FINISHED else Phase.READY, countdown = 0, buffering = false, interlude = null,
                totals = totals, measures = scorer?.measures() ?: emptyList(),
                calibrationMs = ((scorer?.calibration ?: 0.0) * 1000).toInt(),
            )
        }
        if (hasAttempt) savePractice() else cancelOpenPractice()
    }

    /** 처음부터: 판정·녹음을 버리고 READY 로. 열린 백엔드 세션은 취소한다. */
    fun restart() {
        takeSerial++
        countdownJob?.cancel(); countdownJob = null
        tickJob?.cancel(); tickJob = null
        player.pause()
        if (capture.isRunning) capture.stop()
        stopTake()
        deleteTemporaryTake()
        takeOwner = null
        notes.forEach { it.reset() }
        scorer = null
        sync = null
        sessionInitialKey = null
        keyChangeCount = 0
        pendingSeek = null
        saveJob?.cancel()
        cancelOpenPractice()
        player.seekTo(0.0)
        _state.update { it.copy(phase = Phase.READY, countdown = 0, buffering = false, nowSec = 0.0, totals = null, measures = emptyList(), lastJudged = null, save = Save(), loopCount = 0, calibrationMs = 0, error = null, interlude = null, sectionIndex = sections.sectionIndexAt(0.0)) }
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

    /** 전주를 건너뛰지 않고 반주의 0초부터 연습을 시작한다. */
    fun jumpToBeginning() = seekTo(0.0)

    /**
     * 절(1절·2절)로 이동. 반주는 그 절이 시작하는 마디의 **한 마디 앞 첫 박**([Section.jumpStart])에서 출발하고,
     * 재생 중이었다면 [seekTo] 가 한 마디 카운트인까지 준 뒤 들어간다. 어느 단계에서든(카운트다운 제외) 가능.
     */
    fun jumpToSection(index: Int) {
        val section = sections.sections.getOrNull(index) ?: return
        seekTo(section.jumpStart)
    }

    /** 최고음 구간(하이라이트)으로 이동. 시작 시각은 이미 "구절 마디의 한 마디 앞" 으로 계산돼 있다. */
    fun jumpToHighlight() {
        val h = sections.highlight ?: return
        seekTo(h.start)
    }

    /** 지금 간주 안이면 다음 보컬이 시작하는 마디의 한 마디 앞으로. 연습 중·일시정지 모두. */
    fun skipInterlude() {
        val now = _state.value.nowSec
        val interlude = _state.value.interlude ?: sections.interludeAt(now) ?: return
        val target = sections.leadBefore(interlude.end)
        if (target <= now) return                       // 이미 그 마디 안 → 건너뛸 게 없다
        seekTo(target)
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
        if (s != null && _state.value.phase == Phase.RUNNING && !_state.value.keySwitching) {
            scorer?.addFrame(Frame(frame.t - s.t + s.mr, midi))
        }
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

    /** Covers backgrounding, system back and report navigation, including buffered audio/count-in. */
    fun stopAudioForExit() {
        if (audioReleased) return
        pause()
        player.pause()
        clicks?.stopTone()
        stopTake()
    }

    fun stopTake() {
        takePlayer?.runCatching { stop(); release() }
        takePlayer = null
        if (_state.value.takePlaying) _state.update { it.copy(takePlaying = false) }
    }

    // ------------------------------------------------------------------ 연습 기록 저장 (백엔드 /practices)

    /** 백엔드 세션을 시작 시 선택한 키로 만든다. 열린 세션이 있으면 그대로 쓴다. */
    private suspend fun openPractice(): Long = practiceMutex.withLock {
        val owner = takeOwner ?: throw AuthSessionExpiredException()
        val serial = takeSerial
        requireCurrentOwner(owner)
        practiceId?.takeIf { practiceOpen }?.let { return@withLock it }
        val created = practices.create(songId, selectedKey = sessionInitialKey ?: _state.value.keyShift)
        if (serial != takeSerial || takeOwner?.userId != owner.userId || takeOwner?.generation != owner.generation) {
            if (isCurrentOwner(owner)) cleanupScope.launch { runCatching { practices.cancel(created.id) } }
            throw CancellationException("연습이 바뀌었습니다.")
        }
        requireCurrentOwner(owner)
        practiceId = created.id
        practiceOpen = true
        created.id
    }

    /** 열린 세션을 ABORTED 로 닫는다(처음부터·판정 0·화면 이탈). 실패해도 무시한다. */
    private fun cancelOpenPractice() {
        val id = practiceId?.takeIf { practiceOpen } ?: return
        practiceOpen = false
        practiceId = null
        cleanupScope.launch { runCatching { practices.cancel(id) } }
    }

    /**
     * FINISHED 뒤 기록 저장: 세션(없으면 생성) → complete → 라이브 채점 점수 PUT. 저장되면 [Save.practiceId] 로 리포트를 연다.
     * 비회원은 서버를 부르지 않는다. 실패는 [retrySave] 로 이어 간다(완료된 단계는 건너뛴다).
     */
    private fun savePractice() {
        val owner = takeOwner
        if (owner == null || !isLoggedIn() || !isCurrentOwner(owner)) {
            _state.update { it.copy(save = Save(SavePhase.GUEST, "로그인하면 연습 기록이 저장돼요.")) }
            return
        }
        val scorer = scorer ?: return
        saveJob?.cancel()
        _state.update { it.copy(save = Save(SavePhase.SAVING, "연습 기록을 저장하고 있어요")) }
        saveJob = viewModelScope.launch {
            try {
                requireCurrentOwner(owner)
                val id = openPractice()
                requireCurrentOwner(owner)
                val totals = scorer.totals()
                var keyRecorded = true
                if (practiceOpen) {
                    keyRecorded = completePractice(id, judgedSpanMs())
                    practiceOpen = false
                }
                requireCurrentOwner(owner)
                // The backend session is complete; preserve the take before the report request can fail or be interrupted.
                val recordingSaved = saveRecording(owner, id)
                val result = PracticeScoreMapper.result(
                    totals, notes, backendSections,
                    keyShift = _state.value.keyShift, calibrationMs = (totals.calibration * 1000).roundToInt(),
                    frames = scorer.frames.toList(), dataVersion = assetsDataVersion,
                )
                practices.savePerformance(id, result)
                requireCurrentOwner(owner)
                val message = if (!recordingSaved) "연습 기록은 저장했지만 녹음을 저장하지 못했어요."
                    else if (keyRecorded) "연습 기록을 저장했어요."
                    else "연습 기록을 저장했어요. 키 ${signedKey(_state.value.keyShift)} 정보는 서버가 아직 받지 않아 원키로 남았어요."
                _state.update { it.copy(save = Save(SavePhase.SAVED, message, practiceId = id, recordingSaveFailed = !recordingSaved)) }
            } catch (e: AuthSessionExpiredException) {
                _state.update { it.copy(save = Save(SavePhase.GUEST, e.message ?: "로그인이 만료됐어요.")) }
            } catch (e: BackendRequestException) {
                _state.update { it.copy(save = Save(SavePhase.ERROR, "기록을 저장하지 못했어요: ${e.message}")) }
            } catch (e: IOException) {
                _state.update { it.copy(save = Save(SavePhase.ERROR, "서버에 연결할 수 없어 기록을 저장하지 못했어요.")) }
            }
        }
    }

    /**
     * 연습 완료. 상태변경 API(PATCH status)가 BE 명세에서 제거되어 세션은 READY 그대로 complete 로 간다.
     * BE 가 READY→COMPLETED 를 허용해야 하며(2026-09-16 요청), 아니면 409 INVALID_PRACTICE_STATE 가 그대로 올라온다.
     */
    private suspend fun completePractice(id: Long, durationMs: Int): Boolean {
        val key = _state.value.keyShift
        try {
            practices.complete(id, durationMs, finalSelectedKey = key, keyChangeCount = keyChangeCount)
            return true
        } catch (e: BackendRequestException) {
            // 키별 반주 등록이 덜 된 운영 환경에서도 결과 저장 자체는 남긴다.
            if (key == 0 || e.status != 422 || e.code != "UNSUPPORTED_PRACTICE_KEY") throw e
            practices.complete(id, durationMs)
            return false
        }
    }

    private fun signedKey(key: Int): String = if (key > 0) "+$key" else key.toString()

    /** 판정된 첫 음표부터 마지막 음표까지(곡 시각, ms) — "부른 길이" 로 저장한다. */
    private fun judgedSpanMs(): Int {
        val judged = notes.filter { it.status.judged }
        if (judged.isEmpty()) return 0
        return ((judged.maxOf { it.end } - judged.minOf { it.start }) * 1000).roundToInt().coerceAtLeast(0)
    }

    fun retrySave() {
        if (_state.value.phase == Phase.FINISHED && _state.value.save.phase == SavePhase.ERROR) savePractice()
    }

    fun retryRecordingSave() {
        val saved = _state.value.save
        val owner = takeOwner ?: return
        val id = saved.practiceId ?: return
        if (_state.value.phase != Phase.FINISHED || !saved.recordingSaveFailed || !isCurrentOwner(owner)) return
        viewModelScope.launch {
            val success = try { saveRecording(owner, id) } catch (_: AuthSessionExpiredException) { return@launch }
            _state.update { it.copy(save = it.save.copy(
                message = if (success) "녹음도 이 기기에 저장했어요." else "녹음을 저장하지 못했어요. 저장 공간을 확인해 주세요.",
                recordingSaveFailed = !success,
            )) }
        }
    }

    private suspend fun saveRecording(owner: SessionSnapshot, practiceId: Long): Boolean {
        val source = wavFile ?: return false
        return try {
            requireCurrentOwner(owner)
            withContext(Dispatchers.IO) { recordings.save(owner.userId, practiceId, source) }
            requireCurrentOwner(owner)
            true
        } catch (_: IOException) { false }
          catch (_: IllegalArgumentException) { false }
    }

    private fun isCurrentOwner(owner: SessionSnapshot): Boolean = sessionSnapshot().matchesRecordingOwner(owner)

    private fun requireCurrentOwner(owner: SessionSnapshot) {
        if (!isCurrentOwner(owner)) throw AuthSessionExpiredException()
    }

    private fun deleteTemporaryTake() {
        wavFile?.takeIf { it.parentFile == cacheDir && it.name.startsWith("live-take-") }?.delete()
        wavFile = null
    }

    /** 이전 세션이 남긴 녹음 파일 정리 (이 화면이 만든 live-take-*.wav 만). */
    private fun cleanupOldTakes() {
        runCatching {
            cacheDir.listFiles { f -> f.name.startsWith("live-take-") && f.name.endsWith(".wav") }?.forEach { it.delete() }
        }
    }

    override fun onCleared() {
        stopAudioForExit()
        audioReleased = true
        countdownJob?.cancel()
        tickJob?.cancel()
        if (capture.isRunning) capture.stop()
        stopTake()
        player.release()
        runCatching { clicks?.release() }
        frameChannel.close()
        deleteTemporaryTake()
        saveJob?.cancel()
        cancelOpenPractice()
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
