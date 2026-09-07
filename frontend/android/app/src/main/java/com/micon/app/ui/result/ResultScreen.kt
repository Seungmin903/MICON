package com.micon.app.ui.result

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.engine.EngineClient
import com.micon.app.data.engine.dto.Comparison
import com.micon.app.data.engine.dto.ComparisonNote
import com.micon.app.scoring.PitchStatus
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.charts.RangeMapChart
import com.micon.app.ui.charts.RangeRow
import com.micon.app.ui.charts.StabilityLineChart
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.GreenBar
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid
import com.micon.app.ui.theme.Red
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class ResultViewModel(private val engine: EngineClient, private val repo: SongRepository, private val jobId: String) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Error(val message: String) : State
        data class Ready(val comparison: Comparison, val song: SongSummary?) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = State.Loading
            _state.value = try {
                val job = engine.awaitJob(jobId)
                val comparison = job.result?.comparison ?: throw IllegalStateException("이 작업에는 채점 결과가 없습니다.")
                val song = job.referenceJobId?.let { runCatching { repo.detail(it) }.getOrNull() }?.summary
                State.Ready(comparison, song)
            } catch (e: Exception) {
                State.Error(engine.describe(e))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// comparison → 화면 데이터 (음역대 지도 · 음역별 안정성 · 구간 평가)
// ---------------------------------------------------------------------------

private fun ComparisonNote.isGood() = pitchStatus == PitchStatus.GOOD.key
private fun ComparisonNote.isOff() = pitchStatus == PitchStatus.SHARP.key || pitchStatus == PitchStatus.FLAT.key || pitchStatus == PitchStatus.WRONG.key

data class RangeSummary(val songLow: Int, val songHigh: Int, val stable: IntRange?, val unstable: List<IntRange>)

fun rangeSummary(c: Comparison): RangeSummary {
    val all = c.notes
    val songLow = all.minOfOrNull { it.midi } ?: 48
    val songHigh = all.maxOfOrNull { it.midi } ?: 72
    val good = all.filter { it.covered && it.isGood() }.map { it.midi }
    val stable = if (good.isNotEmpty()) good.min()..good.max() else null
    val off = all.filter { it.covered && it.isOff() }.map { it.midi }.sorted()
    val unstable = ArrayList<IntRange>()
    if (off.isNotEmpty()) {
        val mid = (songLow + songHigh) / 2
        off.filter { it <= mid }.takeIf { it.isNotEmpty() }?.let { unstable += it.min()..it.max() }
        off.filter { it > mid }.takeIf { it.isNotEmpty() }?.let { unstable += it.min()..it.max() }
    }
    return RangeSummary(songLow, songHigh, stable, unstable)
}

/** 음역을 bins 개 구간으로 나눠 구간별 정확 비율(0~1)을 만든다. */
fun stabilityCurve(c: Comparison, bins: Int = 8): Pair<List<Float>, List<String>> {
    val covered = c.notes.filter { it.covered }
    if (covered.isEmpty()) return List(bins) { 0f } to List(bins) { "" }
    val lo = covered.minOf { it.midi }; val hi = covered.maxOf { it.midi }
    val span = (hi - lo).coerceAtLeast(1)
    val values = (0 until bins).map { b ->
        val inBin = covered.filter { ((it.midi - lo) * bins / (span + 1)).coerceIn(0, bins - 1) == b }
        if (inBin.isEmpty()) 0.5f else inBin.count { it.isGood() }.toFloat() / inBin.size
    }
    val labels = (0 until bins).map { b -> ScoringMath.midiName(lo + span * b / (bins - 1).coerceAtLeast(1)) }
    return values to labels
}

// ---------------------------------------------------------------------------
// 화면
// ---------------------------------------------------------------------------

/** 전체 화면 버전 (딥링크·연습 종료 후). Figma "노래 연습 분석 상세" 카드 3개 + 곡 행. */
@Composable
fun ResultScreen(jobId: String, onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("연습 분석 상세", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp)) {
            ResultDetailCards(jobId, onOpenFull = null, showSong = true)
            Spacer(Modifier.height(24.dp))
        }
    }
}

/**
 * 평가 탭 아코디언 안에서도 쓰는 카드 묶음: 음역대 지도 · 음역별 안정성 · 구간 평가.
 * onOpenFull 이 있으면 마지막에 "전체 리포트 보기" 링크를 둔다.
 */
@Composable
fun ResultDetailCards(jobId: String, onOpenFull: (() -> Unit)?, showSong: Boolean = false) {
    val container = rememberAppContainer()
    val vm: ResultViewModel = viewModel(key = "result-$jobId", factory = viewModelFactory { initializer { ResultViewModel(container.engine, container.songs, jobId) } })
    val state by vm.state.collectAsStateWithLifecycle()

    when (val s = state) {
        ResultViewModel.State.Loading -> Box(Modifier.fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
        is ResultViewModel.State.Error -> RetryBlock(s.message, vm::load)
        is ResultViewModel.State.Ready -> Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            val c = s.comparison
            if (showSong && s.song != null) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 4.dp)) {
                    VinylArt(s.song.imageUrl, 72.dp, fallbackText = s.song.title)
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(s.song.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        Text(s.song.artist, fontSize = 14.sp, color = Grey)
                    }
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${c.scores.overall.toInt()}점", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Purple)
                        Text("등급 ${c.scores.grade}", fontSize = 12.sp, color = Grey)
                    }
                }
            }

            val r = rangeSummary(c)
            val axisLow = (r.songLow - 2).coerceAtMost(48)
            val axisHigh = (r.songHigh + 2).coerceAtLeast(axisLow + 12)
            MiconCard {
                CardTitle("음역대 지도")
                Spacer(Modifier.height(10.dp))
                RangeMapChart(
                    rows = buildList {
                        add(RangeRow("노래 음역", r.songLow, r.songHigh, PurpleMid, fadeTail = true))
                        r.stable?.let { add(RangeRow("안정적 음역", it.first, it.last, GreenBar)) }
                        r.unstable.forEach { add(RangeRow("불안정 음역", it.first, it.last, Red)) }
                    },
                    axisLow = axisLow, axisHigh = axisHigh,
                    ticks = listOf(axisLow, axisLow + (axisHigh - axisLow) / 4, axisLow + (axisHigh - axisLow) / 2, axisLow + (axisHigh - axisLow) * 3 / 4, axisHigh).distinct(),
                )
            }

            val (values, labels) = stabilityCurve(c)
            MiconCard {
                CardTitle("음역별 안정성")
                Spacer(Modifier.height(10.dp))
                StabilityLineChart(values, labels)
            }

            MiconCard {
                CardTitle("구간 평가")
                Spacer(Modifier.height(12.dp))
                val issues = c.issues.sortedBy { it.start }.take(6)
                if (issues.isEmpty()) {
                    Text("음이탈 구간이 없어요. 잘 불렀어요!", fontSize = 14.sp, color = Grey)
                } else {
                    Column(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        issues.forEach { issue ->
                            val notes = c.notes.filter { it.refStart >= issue.start - 0.05 && it.refStart <= issue.end + 0.05 && !it.lyric.isNullOrEmpty() }
                            Column {
                                Text(ScoringMath.formatTime(issue.start), fontSize = 13.sp, color = InkSoft)
                                Spacer(Modifier.height(4.dp))
                                Text(
                                    buildAnnotatedString {
                                        if (notes.isEmpty()) append(issue.lyric ?: issue.summary)
                                        notes.forEach { n ->
                                            val bad = n.isOff() || n.pitchStatus == PitchStatus.MISSED.key
                                            withStyle(SpanStyle(color = if (bad) Red else Grey, fontWeight = if (bad) FontWeight.Bold else FontWeight.Normal)) { append(n.lyric!!) }
                                        }
                                    },
                                    fontSize = 18.sp, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                                )
                            }
                        }
                    }
                }
            }

            onOpenFull?.let {
                Text(
                    "전체 리포트 보기", fontSize = 14.sp, color = Purple, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = it).padding(8.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
            Spacer(Modifier.size(4.dp))
        }
    }
}
