package com.micon.app.ui.songdetail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.audio.InstrumentalPlayer
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongAnalysis
import com.micon.app.data.SongDetail
import com.micon.app.data.SongRepository
import okhttp3.OkHttpClient
import com.micon.app.data.engine.dto.KaraokeNote
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.KaraokeNames
import com.micon.app.ui.charts.KeyChangeGraph
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.PlayerCard
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SoftChip
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class SongDetailViewModel(
    appContext: Context,
    private val repo: SongRepository,
    mediaHttp: OkHttpClient,
    private val recommend: RecommendRepository,
    private val songId: Long,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val detail: SongDetail? = null,
        val recommendedKey: Int? = null,                        // RecommendRepository.fit. null = 평가 불가
        val melody: List<Pair<Double, Double>> = emptyList(),   // (초, MIDI) 다운샘플
        val playing: Boolean = false,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
        /** 미리 듣기 소리의 출처. 백엔드는 반주만 제공한다("instrumental"). 반주 URL 이 없으면 null(재생 불가). */
        val source: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val player = InstrumentalPlayer(appContext, mediaHttp)

    init {
        player.onError = { msg -> _state.update { it.copy(error = "재생 오류: $msg") } }
        player.onEnded = { _state.update { it.copy(playing = false) } }
        load()
        viewModelScope.launch {
            while (isActive) {
                if (player.isPlaying || player.isReady) {
                    _state.update { it.copy(positionSec = player.positionSec, durationSec = player.durationSec ?: it.durationSec, playing = player.isPlaying) }
                }
                delay(250)
            }
        }
    }

    fun load() {
        viewModelScope.launch {
            _state.update { it.copy(loading = true, error = null) }
            try {
                val base = repo.detail(songId) ?: error("곡을 찾을 수 없습니다: $songId")
                // 연습 데이터(음표·박자·반주 URL)는 practice-assets 에서. 준비 안 된 곡(409)은 없는 것으로 다룬다.
                val assets = runCatching { repo.practiceAssets(songId) }.getOrNull()
                val notes = assets?.targetNotes.orEmpty()
                val analysis = (base.analysis ?: assets?.let { SongAnalysis(it.keyName, it.tempoBpm, it.timeSignature, null, null) })
                    ?.let { a -> a.copy(timeSignature = a.timeSignature ?: assets?.timeSignature, noteCount = notes.size.takeIf { n -> n > 0 } ?: a.noteCount) }
                val detail = base.copy(analysis = analysis)
                val fit = runCatching { recommend.fit(songId) }.getOrNull()
                val url = assets?.instrumental?.playbackUrl
                _state.update {
                    it.copy(
                        loading = false, detail = detail, melody = downsample(notes), durationSec = (detail.summary.durationMs ?: 0) / 1000.0,
                        recommendedKey = fit?.recommendedKey, source = if (url != null) "instrumental" else null,
                    )
                }
                // 미리 듣기는 백엔드가 준 반주 URL. 없으면 플레이어를 비워 둔다.
                if (url != null) player.load(url)
            } catch (e: Exception) {
                _state.update { it.copy(loading = false, error = e.message ?: e.toString()) }
            }
        }
    }

    /**
     * 음표를 곡 길이의 1/36 구간 평균으로 묶고 3점 이동평균을 한 번 더 걸어
     * Figma 처럼 완만한 멜로디 곡선을 만든다(최고음 마커는 구간 평균 기준).
     */
    private fun downsample(notes: List<KaraokeNote>): List<Pair<Double, Double>> {
        if (notes.isEmpty()) return emptyList()
        val span = (notes.last().sourceEnd - notes.first().sourceStart).coerceAtLeast(10.0)
        val bucket = span / 36
        val raw = notes.groupBy { (it.sourceStart / bucket).toInt() }
            .toSortedMap()
            .map { (k, group) -> (k * bucket + bucket / 2) to group.map { it.midi.toDouble() }.average() }
        return raw.mapIndexed { i, (t, _) ->
            val window = raw.subList((i - 1).coerceAtLeast(0), (i + 2).coerceAtMost(raw.size))
            t to window.map { it.second }.average()
        }
    }

    fun toggle() {
        if (player.isPlaying) player.pause() else player.play()
        _state.update { it.copy(playing = player.isPlaying) }
    }

    /** 진행 바 탐색. 재생 중이 아니어도 위치만 옮긴다. */
    fun seekTo(seconds: Double) {
        val target = seconds.coerceIn(0.0, maxOf(0.0, _state.value.durationSec))
        player.seekTo(target)
        _state.update { it.copy(positionSec = target) }
    }

    override fun onCleared() { player.release() }
}

/** 범례 한 항목: 색 표시 + 말 라벨 */
@Composable
private fun LegendItem(color: Color, label: String, wide: Boolean = false) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(width = if (wide) 22.dp else 16.dp, height = if (wide) 12.dp else 4.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(label, fontSize = 12.sp, color = Grey, fontWeight = FontWeight.Medium)
    }
}

/** Figma "노래 연습- 노래 상세정보": 헤더, 플레이어 카드, 노래 적합 카드, 높낮이 흐름. 하단 탭이 보이는 화면. */
@Composable
fun SongDetailScreen(songId: Long, onBack: () -> Unit, onPractice: () -> Unit, onSettings: () -> Unit = {}) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: SongDetailViewModel = viewModel(
        key = "song-$songId",
        factory = viewModelFactory { initializer { SongDetailViewModel(context.applicationContext, container.songs, container.mediaHttp, container.recommend, songId) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by UserStore.profile.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onSettings = onSettings)
        val d = state.detail
        when {
            state.loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            d == null -> RetryBlock(state.error ?: "오류", vm::load)
            else -> {
                val a = d.analysis
                Column(
                    Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    PlayerCard(
                        title = d.summary.title, artist = d.summary.artist, imageUrl = d.summary.imageUrl,
                        positionSec = state.positionSec, durationSec = state.durationSec, playing = state.playing, onToggle = vm::toggle,
                        onSeek = vm::seekTo, caption = if (state.source == null) "미리 듣기 반주가 아직 준비되지 않았어요" else "미리 듣기는 반주로 재생됩니다",
                    )
                    // 1) 결론 먼저: 이 노래가 내 음역에 맞는지, 키를 얼마나 바꾸면 되는지 (노래방식 음이름)
                    val songHigh = a?.highestMidi ?: state.melody.maxOfOrNull { it.second.toInt() } ?: 72
                    val songLow = a?.lowestMidi ?: state.melody.minOfOrNull { it.second.toInt() } ?: 55
                    val key = if (profile != null) (state.recommendedKey ?: 0) else 0   // RecommendRepository.fit 결과 (임시 해시 계산 제거)
                    SongFitCard(
                        songLow = songLow, songHigh = songHigh,
                        comfortLow = profile?.comfortLow ?: 55, comfortHigh = profile?.comfortHigh ?: 67,
                        recommendedKey = key, noteCount = a?.noteCount, durationSec = (d.summary.durationMs ?: 0) / 1000.0,
                        hasProfile = profile != null,
                    )
                    // 2) 노래 높낮이 흐름: 어디가 높은지 한눈에. 라벨은 말로.
                    MiconCard {
                        CardTitle("노래 높낮이 흐름")
                        Spacer(Modifier.height(4.dp))
                        Text("시간에 따라 멜로디가 얼마나 높아지는지 보여 줘요. 연보라 띠 위로 올라간 곳이 나한테 높은 부분이에요.", fontSize = 12.sp, color = Grey, lineHeight = 17.sp)
                        Spacer(Modifier.height(10.dp))
                        if (state.melody.isEmpty()) {
                            Text("분석된 음표가 없어 그래프를 그릴 수 없습니다.", fontSize = 13.sp, color = Grey, modifier = Modifier.padding(vertical = 40.dp))
                        } else {
                            Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                                LegendItem(Purple, "원곡")
                                if (key != 0) LegendItem(Pink, "키 ${if (key > 0) "+$key" else key} 적용")
                                if (profile != null) LegendItem(Lavender, "내 편안한 음역", wide = true)
                            }
                            Spacer(Modifier.height(8.dp))
                            KeyChangeGraph(
                                melody = state.melody,
                                shifted = if (key != 0) state.melody.map { it.first to it.second + key } else null,
                                bandLow = profile?.comfortLow,
                                bandHigh = profile?.comfortHigh,
                                labelOf = { KaraokeNames.koreanShort(it) },
                                peakLabel = "가장 높은 곳 · ${KaraokeNames.korean(songHigh)}",
                                height = 240.dp,
                            )
                        }
                    }
                    if (a != null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            a.keyName?.let { SoftChip("조성 $it") }
                            a.tempoBpm?.let { SoftChip("$it BPM") }
                            a.timeSignature?.let { SoftChip(it) }
                        }
                    }
                    state.error?.let { Text(it, color = Red, fontSize = 13.sp) }
                    PrimaryButton(
                        text = when { a == null -> "분석 데이터 없음"; !d.hasInstrumental -> "반주 없음"; else -> "노래 연습 시작" },
                        onClick = onPractice, modifier = Modifier.fillMaxWidth(), enabled = a != null && d.hasInstrumental,
                    )
                    TextButton(onClick = onBack, modifier = Modifier.padding(bottom = 16.dp)) { Text("뒤로", color = Grey) }
                }
            }
        }
    }
}
