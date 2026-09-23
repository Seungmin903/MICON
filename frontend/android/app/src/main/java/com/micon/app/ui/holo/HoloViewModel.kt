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
import com.micon.app.data.backend.dto.toLiveNotes
import com.micon.app.range.RangeReport
import com.micon.app.scoring.LyricLines
import com.micon.app.scoring.LyricSheet
import com.micon.app.scoring.SectionMap
import com.micon.app.scoring.SongSections
import com.micon.app.ui.design.molecules.NoteNames
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
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

    /** 1단계 음역(15초 미션) → 2단계 음색(노래 부르기). 두 단계를 한 흐름으로 잇지 않는다. */
    enum class Stage { RANGE, TIMBRE }

    data class State(
        val stage: Stage = Stage.RANGE,
        /** 새 음역 측정을 시작할 때 기존 화면 범위의 측정 ViewModel에도 전달한다. */
        val rangeRestartVersion: Long = 0,
        /** 1단계에서 잰 음역(또는 지난 측정값). 2단계 결과 카드의 음역은 이 값을 쓴다 */
        val measuredLow: Int? = null,
        val measuredHigh: Int? = null,
        val phase: Phase = Phase.IDLE,
        val query: String = "",
        val suggestions: List<SongSummary> = emptyList(),
        /** 검색 전에도 고를 수 있게 미리 받아 두는 곡 목록 */
        val catalog: List<SongSummary> = emptyList(),
        val catalogLoading: Boolean = false,
        val catalogLoadingMore: Boolean = false,
        val catalogPage: Int = 0,
        val catalogHasMore: Boolean = true,
        val catalogMoreFailed: Boolean = false,
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
        /** 간주를 건너뛰지 않고 실제로 부른 시간(초). 2단계는 이 값으로 끝난다 */
        val sungSec: Double = 0.0,
        /** 지금 간주 구간을 지나가는 중 */
        val interlude: Boolean = false,
        /** 서버 저장 상태 문구(결과 화면 하단) */
        val saving: Boolean = false,
        val saved: Boolean = false,
        /** 서버가 프로필 저장을 허용했는가(음색 분석 완료) */
        val saveAllowed: Boolean = false,
        /** 서버 음색 분석이 끝났는가(성공·실패 무관) */
        val analysisFinished: Boolean = false,
        val saveError: String? = null,
    ) {
        val progress: Float get() = (sungSec / SING_SECONDS).toFloat().coerceIn(0f, 1f)
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
    private var sections: SectionMap = SectionMap.EMPTY
    private var sungAccum = 0.0
    private var lastTickPos = -1.0
    private var dataVersion: String? = null
    private var tick: Job? = null
    private var startJob: Job? = null
    private var searchJob: Job? = null
    private var submitJob: Job? = null
    private var saveJob: Job? = null
    private var session: HoloAnalysisClient.Session? = null
    private var pollVersion: Long = 0
    private var saveAllowed = false
    private var autoSaveAttemptedFor: Long? = null
    private val ownerId: Long? get() = TokenManager.getUserId().takeIf { it > 0 && TokenManager.isLoggedIn() }

    init {
        _state.update { it.copy(hasPrevious = store.read(ownerId) != null || UserStore.profile.value != null) }
        player.onError = { msg -> _state.update { it.copy(error = msg) } }
    }

    fun previousResult(): HoloResult? = store.read(ownerId)

    /** 곡 목록을 미리 받아 둔다. 검색어 없이도 고를 수 있어야 한다 */
    fun loadCatalog() {
        if (_state.value.catalog.isNotEmpty() || _state.value.catalogLoading) return
        _state.update { it.copy(catalogLoading = true, catalogMoreFailed = false) }
        viewModelScope.launch {
            try {
                val page = songs.search("", 0, CATALOG_PAGE_SIZE)
                _state.update { it.copy(catalog = page.items, catalogLoading = false, catalogPage = page.page,
                    catalogHasMore = hasNextCatalogPage(page.page, page.totalPages, page.items.size, page.size)) }
            } catch (_: Exception) {
                _state.update { it.copy(catalogLoading = false) }
            }
        }
    }

    /** 곡 선택 시트가 끝에 닿으면 다음 카탈로그 페이지를 이어 붙인다. */
    fun loadMoreCatalog() {
        val current = _state.value
        if (current.query.isNotBlank() || current.catalogLoading || current.catalogLoadingMore ||
            !current.catalogHasMore || current.catalogMoreFailed) return
        viewModelScope.launch {
            _state.update { it.copy(catalogLoadingMore = true) }
            try {
                val page = songs.search("", current.catalogPage + 1, CATALOG_PAGE_SIZE)
                _state.update { state ->
                    val known = state.catalog.mapTo(HashSet()) { it.id }
                    state.copy(catalog = state.catalog + page.items.filter { it.id !in known },
                        catalogLoadingMore = false, catalogPage = page.page,
                        catalogHasMore = hasNextCatalogPage(page.page, page.totalPages, page.items.size, page.size))
                }
            } catch (_: Exception) {
                _state.update { it.copy(catalogLoadingMore = false, catalogMoreFailed = true) }
            }
        }
    }

    /** 실패한 다음 페이지는 시트를 다시 열 때 한 번 더 시도할 수 있게 한다. */
    fun retryCatalogMore() {
        if (_state.value.catalogMoreFailed) {
            _state.update { it.copy(catalogMoreFailed = false) }
            loadMoreCatalog()
        } else {
            loadCatalog()
        }
    }

    /** 1단계 음역 측정이 끝났다. 잰 값을 안고 2단계(음색)로 넘어간다 */
    fun rangeMeasured(range: RangeReport?) {
        if (_state.value.stage == Stage.TIMBRE) return
        _state.update { it.copy(stage = Stage.TIMBRE, measuredLow = range?.lowMidi, measuredHigh = range?.highMidi,
            phase = Phase.IDLE, error = null) }
    }

    /**
     * 음역은 건너뛰고 음색(노래)만 다시 본다 — 분석 탭의 "노래로 분석하기".
     * 음역은 지난 측정값을 그대로 쓴다. 없으면 1단계부터 하게 둔다.
     */
    fun startTimbreOnly() {
        cancelPendingSubmission()
        startJob?.cancel()
        val last = store.read(ownerId)
        if (last == null) {
            _state.update { State(hasPrevious = it.hasPrevious, rangeRestartVersion = it.rangeRestartVersion + 1) }
            return
        }
        tick?.cancel(); if (capture.isRunning) capture.stop(); player.pause()
        _state.update {
            State(hasPrevious = true, stage = Stage.TIMBRE, measuredLow = last.lowMidi, measuredHigh = last.highMidi,
                rangeRestartVersion = it.rangeRestartVersion)
        }
    }

    fun backToRange() {
        cancelPendingSubmission()
        startJob?.cancel()
        tick?.cancel(); if (capture.isRunning) capture.stop(); player.pause()
        _state.update { State(hasPrevious = it.hasPrevious, rangeRestartVersion = it.rangeRestartVersion + 1) }
    }

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
        val startVersion = _state.value.rangeRestartVersion
        startJob?.cancel()
        frames.clear()
        _state.update { it.copy(phase = Phase.LOADING, error = null, elapsedSec = 0.0) }
        startJob = viewModelScope.launch {
            try {
                if (picked != null) prepareSong(picked)
                else { sheet = null; songStartSec = 0.0 }
                currentCoroutineContext().ensureActive()
                if (_state.value.rangeRestartVersion != startVersion || _state.value.stage != Stage.TIMBRE) return@launch
                val file = File(File(appContext.filesDir, "holo").apply { mkdirs() }, "rec-${UUID.randomUUID()}.wav")
                wav = file
                sungAccum = 0.0
                lastTickPos = -1.0
                capture.start(file, onError = { e ->
                    _state.update { current ->
                        if (current.rangeRestartVersion == startVersion && current.stage == Stage.TIMBRE &&
                            current.phase in setOf(Phase.LOADING, Phase.REC))
                            current.copy(phase = Phase.ERROR, error = e.message ?: "마이크를 열지 못했어요.") else current
                    }
                }) { f ->
                    if (_state.value.rangeRestartVersion == startVersion) synchronized(frames) { frames.add(f) }
                }
                if (picked != null) { player.seekTo(songStartSec); player.play() }
                val (l0, i0) = lyricsAt(if (picked != null) songStartSec else 0.0)
                _state.update { it.copy(phase = Phase.REC, lyrics = l0, lyricIndex = i0) }
                tick = launch {
                    while (true) {
                        val t = capture.capturedSeconds
                        val now = if (picked != null) player.positionSec else t
                        // 간주는 건너뛴다. 반주를 기다리느라 부르는 시간이 늘어나지 않게.
                        var inInterlude = false
                        if (picked != null) {
                            val gap = sections.interludeAt(now)
                            if (gap != null) {
                                val target = sections.leadBefore(gap.end)
                                if (target > now + 0.3) { player.seekTo(target); lastTickPos = target } else inInterlude = true
                            }
                        }
                        // 실제로 부른 시간만 센다(곡이 없으면 녹음 시간 그대로)
                        if (picked == null) sungAccum = t
                        else {
                            if (lastTickPos >= 0 && now > lastTickPos && !inInterlude) sungAccum += (now - lastTickPos).coerceAtMost(0.5)
                            lastTickPos = now
                        }
                        val (l, i) = lyricsAt(now)
                        _state.update { it.copy(elapsedSec = t, sungSec = sungAccum, interlude = inInterlude, lyrics = l, lyricIndex = i) }
                        if (sungAccum >= SING_SECONDS) { finish(); break }
                        delay(120)
                    }
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                _state.update { current ->
                    if (current.rangeRestartVersion == startVersion && current.stage == Stage.TIMBRE)
                        current.copy(phase = Phase.ERROR, error = e.message ?: "준비 중 문제가 생겼어요.") else current
                }
            }
        }
    }

    private suspend fun prepareSong(song: SongSummary) {
        val assets = songs.practiceAssets(song.id)
        val url = assets.instrumental.playbackUrl ?: throw IllegalStateException("이 곡은 반주가 아직 준비되지 않았어요.")
        dataVersion = assets.dataVersion
        val notes = assets.targetNotes.toLiveNotes(assets.timeSignature)
        val built = LyricLines.build(notes, assets.lyricsSynced, assets.lyricsPlain)
        sheet = built
        sections = SongSections.build(notes, built.lines)
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
        val low = _state.value.measuredLow
        val high = _state.value.measuredHigh
        val result = HoloRangeAnalyzer.analyze(snapshot, picked?.id, picked?.title, picked?.artist)
            ?.let { r -> if (low != null && high != null) r.withMeasuredRange(low, high) else r }
        if (result == null || !result.usable) {
            _state.update { it.copy(phase = Phase.ERROR, error = "목소리가 충분히 잡히지 않았어요. 조금 더 크게, 4초 이상 불러 주세요.") }
            file?.delete()
            return
        }
        cancelPendingSubmission()
        _state.update { it.copy(phase = Phase.DONE, result = result, saving = false, saved = false,
            saveAllowed = false, analysisFinished = false, saveError = null) }
        applyProfile(result)
        runCatching { store.write(ownerId, result) }
        val recordedSec = capture.capturedSeconds
        submitJob = viewModelScope.launch(Dispatchers.IO) { submit(result, file, picked, recordedSec) }
    }

    private suspend fun submit(result: HoloResult, file: File?, picked: SongSummary?, recordedSec: Double) {
        try {
            val s = client.createSession(ownerId)
            if (_state.value.result?.measuredAt != result.measuredAt) return
            session = s
            client.submitRange(s, result)
            _state.update { it.copy(result = it.result?.copy(sessionId = s.id)) }
            if (picked != null && file != null && file.isFile && recordedSec >= 4.0) {
                client.submitTimbre(s, file, picked.id, capture.recordingRate, (recordedSec * 1000).toInt(),
                    (songStartSec * 1000).toInt(), dataVersion, result.measuredAt)
                _state.update { it.copy(result = it.result?.copy(timbreSubmitted = true)) }
                client.poll(s) { p ->
                    if (session !== s || _state.value.result?.measuredAt != result.measuredAt) return@poll
                    pollVersion = p.version; saveAllowed = p.saveAllowed
                    _state.update { it.copy(saveAllowed = p.saveAllowed, analysisFinished = p.done,
                        result = if (p.artists.isNotEmpty()) it.result?.copy(artists = p.artists) else it.result) }
                    _state.value.result?.let { r -> runCatching { store.write(ownerId, r) } }
                    // 음역·음색 분석이 끝나면 결과 화면의 버튼을 기다리지 않고 저장한다.
                    // 가수 검색은 결과 버전을 바꾸지 않으므로 기다릴 필요가 없다.
                    if (p.saveAllowed && ownerId != null && autoSaveAttemptedFor != s.id) {
                        autoSaveAttemptedFor = s.id
                        viewModelScope.launch { save() }
                    }
                }
                _state.update { if (it.result?.measuredAt == result.measuredAt) it.copy(analysisFinished = true,
                    saveError = if (!saveAllowed && !it.saved) "음색 분석이 끝나지 않아 저장할 수 없어요. 다시 측정해 주세요." else it.saveError) else it }
            } else _state.update { it.copy(analysisFinished = true) }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) {
            _state.update { if (it.result?.measuredAt == result.measuredAt) it.copy(analysisFinished = true,
                saveError = "서버에 연결할 수 없어 저장하지 못했어요.") else it }
        }
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

    /** 분석 완료 시 자동 호출한다. 버튼에서는 완료 후 이동하거나 저장 실패를 재시도한다. */
    fun save(onDone: () -> Unit = {}) {
        if (_state.value.saved) { onDone(); return }
        if (_state.value.saving) return
        val s = session
        if (ownerId == null || s == null || !saveAllowed) return
        val version = pollVersion
        val measuredAt = _state.value.result?.measuredAt
        _state.update { it.copy(saving = true, saveError = null) }
        saveJob = viewModelScope.launch {
            try {
                client.saveProfile(s, version)
                if (session !== s || _state.value.result?.measuredAt != measuredAt) return@launch
                _state.update { it.copy(saving = false, saved = true, saveError = null,
                    result = it.result?.copy(profileSaved = true)) }
                _state.value.result?.let { r -> withContext(Dispatchers.IO) { runCatching { store.write(ownerId, r) } } }
                UserStore.profile.value?.let { UserStore.setProfile(it.copy(measuredAt = System.currentTimeMillis())) }
                onDone()
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (session === s) _state.update { it.copy(saving = false, saveError = e.message ?: "프로필을 저장하지 못했어요.") }
            }
        }
    }

    private fun cancelPendingSubmission() {
        submitJob?.cancel(); saveJob?.cancel()
        session = null; pollVersion = 0; saveAllowed = false; autoSaveAttemptedFor = null
    }

    fun reset() {
        cancelPendingSubmission()
        startJob?.cancel()
        searchJob?.cancel()
        tick?.cancel(); if (capture.isRunning) capture.stop(); player.pause()
        if (_state.value.phase != Phase.DONE) wav?.delete()
        wav = null
        synchronized(frames) { frames.clear() }
        _state.update { State(hasPrevious = it.hasPrevious, rangeRestartVersion = it.rangeRestartVersion + 1) }
    }

    override fun onCleared() {
        startJob?.cancel()
        tick?.cancel()
        if (capture.isRunning) capture.stop()
        player.release()
    }

    companion object {
        /** 2단계에서 실제로 불러야 하는 시간. 간주를 건너뛰므로 체감 시간은 이보다 짧다 */
        const val SING_SECONDS = 30.0
        const val DURATION_SEC = 30.0
        private const val CATALOG_PAGE_SIZE = 40
        val GUIDE_LINES = listOf("아— 하고 편하게 소리내 주세요", "낮은 음에서 높은 음까지", "천천히 올려 볼게요")
    }
}

internal fun hasNextCatalogPage(page: Int, totalPages: Int, itemCount: Int, pageSize: Int): Boolean =
    if (totalPages > 0) page + 1 < totalPages else itemCount >= pageSize
