package com.micon.app.ui.holo

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.micon.app.audio.InstrumentalPlayer
import com.micon.app.audio.PitchCapture
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.backend.BackendApi
import com.micon.app.data.holo.HoloAnalysisClient
import com.micon.app.data.holo.HoloRangeAnalyzer
import com.micon.app.data.holo.HoloResult
import com.micon.app.data.holo.HoloResultStore
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.LyricLines
import com.micon.app.scoring.LyricSheet
import com.micon.app.ui.design.molecules.NoteNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

/**
 * 첫 화면(홀로) — 곡 검색(선택) → 30초 녹음 → 기기 분석 → 결과.
 *
 * 곡을 골랐으면 반주를 틀며 첫 가사부터 부르고, 그 녹음을 서버 음색 분석으로도 올린다(닮은 목소리).
 * 고르지 않으면 간편 분석: 낮은 음에서 높은 음까지 "아—" 로 올려 부르는 30초. 음역·안정성은 둘 다 기기에서 계산한다.
 */
class HoloViewModel(
    private val appContext: Context,
    private val songs: SongRepository,
    api: BackendApi,
    mediaHttp: OkHttpClient,
) : ViewModel() {
    enum class Phase { IDLE, LOADING, REC, DONE, ERROR }

    data class State(
        val phase: Phase = Phase.IDLE,
        val query: String = "",
        val suggestions: List<SongSummary> = emptyList(),
        val searching: Boolean = false,
        val picked: SongSummary? = null,
        /** 0..30 */
        val elapsedSec: Double = 0.0,
        /** 화면에 보이는 3줄(이전·현재·다음). 현재 줄 인덱스는 [lyricIndex] */
        val lyrics: List<String> = emptyList(),
        val lyricIndex: Int = 1,
        val result: HoloResult? = null,
        val error: String? = null,
        /** 이전 결과가 있어 "나중에" 로 건너뛸 수 있는가 */
        val hasPrevious: Boolean = false,
        /** 서버 저장 상태 문구(결과 화면 하단) */
        val saving: Boolean = false,
        val saved: Boolean = false,
    ) {
        val progress: Float get() = (elapsedSec / DURATION_SEC).toFloat().coerceIn(0f, 1f)
        val globeScale: Float get() = if (phase == Phase.REC) 0.8f + Math.pow(progress.toDouble(), 1.08).toFloat() * 1.2f else 1f
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    private val store = HoloResultStore(appContext)
    private val client = HoloAnalysisClient(api)
    private val player = InstrumentalPlayer(appContext, mediaHttp)
    private val capture = PitchCapture(analyzePitch = true, timbreRecording = true)
    private val frames = ArrayList<PitchCapture.PitchFrame>(3000)
    private var wav: File? = null
    private var sheet: LyricSheet? = null
    private var songStartSec = 0.0
    private var dataVersion: String? = null
    private var tick: Job? = null
    private var searchJob: Job? = null
    private var session: HoloAnalysisClient.Session? = null
    private var pollVersion: Long = 0
    private var saveAllowed = false
    private val ownerId: Long? get() = TokenManager.getUserId().takeIf { it > 0 && TokenManager.isLoggedIn() }

    init {
        _state.update { it.copy(hasPrevious = store.read(ownerId) != null || UserStore.profile.value != null) }
        player.onError = { msg -> _state.update { it.copy(error = msg) } }
    }

    fun previousResult(): HoloResult? = store.read(ownerId)

    // ---- 검색 ----
    fun onQuery(q: String) {
        _state.update { it.copy(query = q, picked = null) }
        searchJob?.cancel()
        if (q.isBlank()) { _state.update { it.copy(suggestions = emptyList(), searching = false) }; return }
        searchJob = viewModelScope.launch {
            delay(250)
            _state.update { it.copy(searching = true) }
            val items = runCatching { songs.search(q.trim(), 0, 6).items }.getOrDefault(emptyList())
            _state.update { it.copy(suggestions = items, searching = false) }
        }
    }

    fun pick(song: SongSummary) {
        searchJob?.cancel()
        _state.update { it.copy(picked = song, query = "${song.title} · ${song.artist}", suggestions = emptyList()) }
    }

    fun clearSuggestions() = _state.update { it.copy(suggestions = emptyList()) }

    // ---- 녹음 ----
    fun start() {
        if (_state.value.phase != Phase.IDLE && _state.value.phase != Phase.ERROR) return
        val picked = _state.value.picked
        frames.clear()
        _state.update { it.copy(phase = Phase.LOADING, error = null, elapsedSec = 0.0) }
        viewModelScope.launch {
            try {
                if (picked != null) prepareSong(picked)
                else { sheet = null; songStartSec = 0.0 }
                val file = File(File(appContext.filesDir, "holo").apply { mkdirs() }, "rec-${UUID.randomUUID()}.wav")
                wav = file
                capture.start(file, onError = { e -> _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: "마이크를 열지 못했어요.") } }) { f ->
                    synchronized(frames) { frames.add(f) }
                }
                if (picked != null) { player.seekTo(songStartSec); player.play() }
                val (l0, i0) = lyricsAt(if (picked != null) songStartSec else 0.0)
                _state.update { it.copy(phase = Phase.REC, lyrics = l0, lyricIndex = i0) }
                tick = launch {
                    while (true) {
                        val t = capture.capturedSeconds
                        val now = if (picked != null) player.positionSec else t
                        val (l, i) = lyricsAt(now)
                        _state.update { it.copy(elapsedSec = t, lyrics = l, lyricIndex = i) }
                        if (t >= DURATION_SEC) { finish(); break }
                        delay(120)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { it.copy(phase = Phase.ERROR, error = e.message ?: "준비 중 문제가 생겼어요.") }
            }
        }
    }

    private suspend fun prepareSong(song: SongSummary) {
        val assets = songs.practiceAssets(song.id)
        val url = assets.instrumental.playbackUrl ?: throw IllegalStateException("이 곡은 반주가 아직 준비되지 않았어요.")
        dataVersion = assets.dataVersion
        val built = LyricLines.build(emptyList(), assets.lyricsSynced, assets.lyricsPlain)
        sheet = built
        // 첫 가사 1초 전부터. 하이라이트 구간이 있으면 거기부터 — 30초 안에 가장 특징적인 목소리가 담긴다.
        val highlight = assets.sections.firstOrNull { it.sectionType.equals("CHORUS", true) || it.sectionName.trim() == "하이라이트" }
        songStartSec = (highlight?.startMs?.div(1000.0) ?: built.lines.firstOrNull()?.start?.minus(1.0) ?: 0.0).coerceAtLeast(0.0)
        player.load(url)
        if (!player.awaitReady(10_000)) throw IllegalStateException("반주를 불러오지 못했어요. 네트워크를 확인해 주세요.")
    }

    /** 곡: 현재 줄 기준 이전·현재·다음(가운데 강조). 간편: 안내 3줄, 10초마다 다음 줄 강조 */
    private fun lyricsAt(now: Double): Pair<List<String>, Int> {
        val s = sheet
        if (s == null || s.lines.isEmpty()) return GUIDE_LINES to (now / 10).toInt().coerceIn(0, 2)
        val i = s.currentIndex(now) ?: s.lines.indexOfFirst { it.start >= now }.coerceAtLeast(0)
        return listOf(s.lines.getOrNull(i - 1)?.text ?: "", s.lines.getOrNull(i)?.text ?: "", s.lines.getOrNull(i + 1)?.text ?: "") to 1
    }

    /** "결과 바로 보기" — 4초 넘게 불렀으면 그 분량으로 분석 */
    fun finishEarly() { if (_state.value.phase == Phase.REC) finish() }

    private fun finish() {
        tick?.cancel()
        player.pause()
        val file = capture.stop() ?: wav
        val snapshot = synchronized(frames) { frames.toList() }
        val picked = _state.value.picked
        val result = HoloRangeAnalyzer.analyze(snapshot, picked?.id, picked?.title, picked?.artist)
        if (result == null || !result.usable) {
            _state.update { it.copy(phase = Phase.ERROR, error = "목소리가 충분히 잡히지 않았어요. 조금 더 크게, 4초 이상 불러 주세요.") }
            file?.delete()
            return
        }
        _state.update { it.copy(phase = Phase.DONE, result = result) }
        applyProfile(result)
        runCatching { store.write(ownerId, result) }
        val recordedSec = capture.capturedSeconds
        viewModelScope.launch(Dispatchers.IO) { submit(result, file, picked, recordedSec) }
    }

    private suspend fun submit(result: HoloResult, file: File?, picked: SongSummary?, recordedSec: Double) {
        try {
            val s = client.createSession(ownerId)
            session = s
            client.submitRange(s, result)
            _state.update { it.copy(result = it.result?.copy(sessionId = s.id)) }
            if (picked != null && file != null && file.isFile && recordedSec >= 4.0) {
                client.submitTimbre(s, file, picked.id, capture.recordingRate, (recordedSec * 1000).toInt(),
                    (songStartSec * 1000).toInt(), dataVersion, result.measuredAt)
                _state.update { it.copy(result = it.result?.copy(timbreSubmitted = true)) }
                client.poll(s) { p ->
                    pollVersion = p.version; saveAllowed = p.saveAllowed
                    if (p.artists.isNotEmpty()) _state.update { it.copy(result = it.result?.copy(artists = p.artists)) }
                    _state.value.result?.let { r -> runCatching { store.write(ownerId, r) } }
                }
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { /* 서버 저장 실패는 결과 화면을 막지 않는다 */ }
    }

    /** 결과를 추천·곡 상세가 바로 쓰도록 사용자 프로필로 반영 */
    private fun applyProfile(r: HoloResult) {
        UserStore.setProfile(UserStore.VoiceProfile(
            lowMidi = r.lowMidi, highMidi = r.highMidi, comfortLow = r.comfortLow, comfortHigh = r.comfortHigh,
            recommendLow = r.recommendLow, recommendHigh = r.recommendHigh,
            stability = r.stability, stabilityLabels = r.stabilityMidis.map { NoteNames.scientific(it) },
            bpmRange = "${r.bpmLow}-${r.bpmHigh}", measuredAt = r.measuredAt,
        ))
    }

    /** 회원이면 서버 프로필 저장. 비회원은 호출부가 로그인으로 보낸다 */
    fun save(onDone: () -> Unit) {
        val s = session
        if (ownerId == null || s == null) { onDone(); return }
        _state.update { it.copy(saving = true) }
        viewModelScope.launch {
            val ok = runCatching { if (saveAllowed) client.saveProfile(s, pollVersion) }.isSuccess
            _state.update { it.copy(saving = false, saved = ok, result = it.result?.copy(profileSaved = ok)) }
            _state.value.result?.let { r -> withContext(Dispatchers.IO) { runCatching { store.write(ownerId, r) } } }
            onDone()
        }
    }

    fun reset() {
        tick?.cancel(); if (capture.isRunning) capture.stop(); player.pause()
        _state.update { State(hasPrevious = it.hasPrevious, picked = it.picked, query = it.query) }
    }

    override fun onCleared() {
        tick?.cancel()
        if (capture.isRunning) capture.stop()
        player.release()
    }

    companion object {
        const val DURATION_SEC = 30.0
        val GUIDE_LINES = listOf("아— 하고 편하게 소리내 주세요", "낮은 음에서 높은 음까지", "천천히 올려 볼게요")
    }
}
