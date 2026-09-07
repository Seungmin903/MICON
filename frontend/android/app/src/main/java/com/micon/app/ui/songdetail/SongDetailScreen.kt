package com.micon.app.ui.songdetail

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import com.micon.app.data.SongDetail
import com.micon.app.data.SongRepository
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.engine.dto.KaraokeNote
import com.micon.app.data.local.UserStore
import com.micon.app.ui.charts.KeyChangeGraph
import com.micon.app.ui.charts.KeyChangeLegend
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.PlayerCard
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SoftChip
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
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
    private val engine: EngineClient,
    private val songId: String,
) : ViewModel() {
    data class State(
        val loading: Boolean = true,
        val error: String? = null,
        val detail: SongDetail? = null,
        val melody: List<Pair<Double, Double>> = emptyList(),   // (초, MIDI) 다운샘플
        val playing: Boolean = false,
        val positionSec: Double = 0.0,
        val durationSec: Double = 0.0,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val player = InstrumentalPlayer(appContext, engine.okHttp)

    init {
        player.onError = { msg -> _state.update { it.copy(error = "반주 재생 오류: $msg") } }
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
                val detail = repo.detail(songId) ?: error("곡을 찾을 수 없습니다: $songId")
                val notes = runCatching { repo.karaokeNotes(songId) }.getOrNull() ?: emptyList()
                _state.update {
                    it.copy(loading = false, detail = detail, melody = downsample(notes), durationSec = (detail.summary.durationMs ?: 0) / 1000.0)
                }
                if (detail.hasInstrumental) player.load(engine.instrumentalUrl(songId, 0))
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

    override fun onCleared() { player.release() }
}

/** Figma "노래 연습- 노래 상세정보": 헤더, 플레이어 카드, 키 변화 그래프. 하단 탭이 보이는 화면. */
@Composable
fun SongDetailScreen(songId: String, onBack: () -> Unit, onPractice: () -> Unit, onSettings: () -> Unit = {}) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: SongDetailViewModel = viewModel(
        key = "song-$songId",
        factory = viewModelFactory { initializer { SongDetailViewModel(context.applicationContext, container.songs, container.engine, songId) } },
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
                    )
                    MiconCard {
                        CardTitle("키 변화 그래프")
                        Spacer(Modifier.height(14.dp))
                        KeyChangeLegend(Modifier.padding(horizontal = 16.dp))
                        Spacer(Modifier.height(10.dp))
                        if (state.melody.isEmpty()) {
                            Text("분석된 음표가 없어 그래프를 그릴 수 없습니다.", fontSize = 13.sp, color = Grey, modifier = Modifier.padding(vertical = 40.dp))
                        } else {
                            val key = UserStore.recommendedKey(songId, a?.highestMidi) ?: 0
                            KeyChangeGraph(
                                melody = state.melody,
                                shifted = if (key != 0) state.melody.map { it.first to it.second + key } else null,
                                bandLow = profile?.comfortLow ?: 48,
                                bandHigh = profile?.comfortHigh ?: 72,
                            )
                            Text(
                                "보라: 곡 멜로디 · 분홍: 추천 키(${if (key > 0) "+$key" else key}) 적용 시 · 연보라 띠: 내 편안한 음역",
                                fontSize = 12.sp, color = Grey, modifier = Modifier.padding(top = 6.dp),
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
