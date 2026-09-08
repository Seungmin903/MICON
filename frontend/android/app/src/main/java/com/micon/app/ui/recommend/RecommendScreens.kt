package com.micon.app.ui.recommend

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.GridView
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.StarOutline
import androidx.compose.material.icons.outlined.Whatshot
import androidx.compose.material.icons.outlined.ViewCarousel
import androidx.compose.material.icons.outlined.BubbleChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import coil.compose.AsyncImage
import com.micon.app.data.RecommendMode
import com.micon.app.data.RecommendRepository
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.SongRepository
import com.micon.app.data.SongSummary
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconSearchBar
import com.micon.app.ui.common.PillChip
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SoftChip
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.LavenderDeep
import com.micon.app.ui.theme.Orange
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.PinkBg
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

// ---------------------------------------------------------------------------
// 추천곡
// ---------------------------------------------------------------------------

class RecommendViewModel(private val songs: SongRepository, private val recommend: RecommendRepository) : ViewModel() {
    data class State(
        val query: String = "",
        val songs: List<SongSummary> = emptyList(),
        /** false = 기본 추천(프로필 없음). 개인화 문구를 쓰지 않는다(REC-004) */
        val personalized: Boolean = false,
        val loading: Boolean = true,
        val error: String? = null,
    )

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()

    init { load() }

    fun setQuery(q: String) { _state.value = _state.value.copy(query = q); load() }

    fun load() {
        viewModelScope.launch {
            _state.value = _state.value.copy(loading = true, error = null)
            val query = _state.value.query
            runCatching {
                if (query.isBlank()) {
                    val r = recommend.recommend(RecommendMode.RANGE, limit = 7)      // 순서는 저장소가 정한 그대로
                    r.items.map { it.song } to r.personalized
                } else {
                    songs.search(query, analyzedOnly = true, limit = 7) to false
                }
            }
                .onSuccess { (list, personalized) -> _state.value = _state.value.copy(songs = list, personalized = personalized, loading = false) }
                .onFailure { e -> _state.value = _state.value.copy(loading = false, error = e.message ?: e.toString()) }
        }
    }
}

/** Figma "추천곡"(버블) / "추천곡 - 캐러샐". 오른쪽 위 아이콘으로 두 배치를 전환한다. */
@Composable
fun RecommendScreen(onSongClick: (String) -> Unit, onSettings: () -> Unit) {
    val container = rememberAppContainer()
    val vm: RecommendViewModel = viewModel(factory = viewModelFactory { initializer { RecommendViewModel(container.songs, container.recommend) } })
    val state by vm.state.collectAsStateWithLifecycle()
    var carousel by remember { mutableStateOf(false) }
    var filter by remember { mutableStateOf<String?>(null) }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        MiconHeader(onSettings = onSettings)
        MiconSearchBar(state.query, vm::setQuery, Modifier.padding(horizontal = 20.dp))
        Spacer(Modifier.height(22.dp))
        Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(if (state.personalized) "이런 노래는 어때요?" else "기본 추천", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
            IconButton(onClick = { carousel = !carousel }) {
                Icon(if (carousel) Icons.Outlined.BubbleChart else Icons.Outlined.ViewCarousel, "배치 전환", tint = Grey)
            }
        }
        if (!state.personalized && !state.loading) {
            Text("목소리를 분석하면 내 음역에 맞춘 추천으로 바뀌어요", fontSize = 13.sp, color = Grey, modifier = Modifier.padding(horizontal = 24.dp))
        }
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 20.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf("음역대" to Icons.Outlined.GraphicEq, "음색" to Icons.Outlined.Palette, "장르" to Icons.Outlined.GridView, "인기" to Icons.Outlined.Whatshot).forEach { (label, icon) ->
                PillChip(
                    label, selected = filter == label, onClick = { filter = if (filter == label) null else label },
                    leading = { Icon(icon, null, Modifier.size(18.dp), tint = if (filter == label) Color.White else Purple) },
                    chevron = true, fontSize = 14.sp,
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        when {
            state.loading && state.songs.isEmpty() -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            state.error != null -> RetryBlock(state.error!!, vm::load)
            carousel -> VinylCarousel(state.songs, onSongClick)
            else -> BubbleCluster(state.songs, onSongClick)
        }
    }
}

/** Figma 버블 배치: 크기가 다른 LP 7장을 원형 군집으로. 프레임 412px 기준 비율로 배치한다. */
@Composable
private fun BubbleCluster(songs: List<SongSummary>, onSongClick: (String) -> Unit) {
    // (x 중심 비율, y 중심 비율, 지름 비율) — Figma 프레임 기준
    val slots = listOf(
        Triple(0.30f, 0.16f, 0.30f), Triple(0.70f, 0.20f, 0.36f), Triple(0.10f, 0.30f, 0.16f),
        Triple(0.48f, 0.50f, 0.46f), Triple(0.86f, 0.46f, 0.18f), Triple(0.18f, 0.80f, 0.28f), Triple(0.76f, 0.78f, 0.30f),
    )
    BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(420.dp)) {
        val w = maxWidth
        val h = maxHeight
        songs.take(slots.size).forEachIndexed { i, song ->
            val (cx, cy, d) = slots[i]
            val size = w * d
            Box(
                Modifier.offset(x = w * cx - size / 2, y = h * cy - size / 2).size(size).clickable { onSongClick(song.id) },
            ) { VinylArt(song.imageUrl, size, fallbackText = song.title) }
        }
    }
}

/** Figma 캐러샐 배치: 가로 스크롤 큰 LP */
@Composable
private fun VinylCarousel(songs: List<SongSummary>, onSongClick: (String) -> Unit) {
    LazyRow(
        Modifier.fillMaxWidth().padding(top = 20.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 24.dp),
        horizontalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        items(songs, key = { it.id }) { song ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.clickable { onSongClick(song.id) }) {
                VinylArt(song.imageUrl, 240.dp, fallbackText = song.title)
                Spacer(Modifier.height(14.dp))
                Text(song.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                Text(song.artist, fontSize = 14.sp, color = Grey)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// song-match-detail (곡 분석 및 추천)
// ---------------------------------------------------------------------------

class SongMatchViewModel(private val repo: SongRepository, private val recommend: RecommendRepository, private val songId: String) : ViewModel() {
    sealed interface State {
        data object Loading : State
        data class Error(val message: String) : State
        /** fit == null 이면 평가 불가(프로필 없음 또는 곡 음역 정보 없음, ERR-004) */
        data class Ready(val detail: SongDetail, val fit: SongFit?) : State
    }

    private val _state = MutableStateFlow<State>(State.Loading)
    val state = _state.asStateFlow()

    init { load() }

    fun load() {
        viewModelScope.launch {
            _state.value = State.Loading
            _state.value = runCatching { repo.detail(songId) to recommend.fit(songId) }.fold(
                onSuccess = { (d, fit) -> d?.let { State.Ready(it, fit) } ?: State.Error("곡을 찾을 수 없습니다: $songId") },
                onFailure = { State.Error(it.message ?: it.toString()) },
            )
        }
    }
}

/** MIDI → "2옥 도" 식 국내 노래방 옥타브 표기 (C4 = 2옥 도). */
fun karaokeOctaveName(midi: Int): String {
    val names = listOf("도", "도#", "레", "레#", "미", "파", "파#", "솔", "솔#", "라", "라#", "시")
    val octave = midi / 12 - 3 // C3(48) → 1옥
    return "${octave}옥 ${names[midi % 12]}"
}

@Composable
fun SongMatchDetailScreen(songId: String, onBack: () -> Unit, onPractice: () -> Unit) {
    val container = rememberAppContainer()
    val vm: SongMatchViewModel = viewModel(key = "match-$songId", factory = viewModelFactory { initializer { SongMatchViewModel(container.songs, container.recommend, songId) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by UserStore.profile.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize().background(GreyBg)) {
        BackHeader("곡 분석 및 추천", onBack)
        when (val s = state) {
            SongMatchViewModel.State.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator(color = Purple) }
            is SongMatchViewModel.State.Error -> RetryBlock(s.message, vm::load)
            is SongMatchViewModel.State.Ready -> MatchBody(s.detail, s.fit, profile, onPractice)
        }
    }
}

/**
 * 곡 분석 및 추천 본문. 적합도·추천 키·부담 구간은 [SongFit] 을 **그리기만** 한다(계산은 RecommendRepository).
 * fit == null → "평가 불가" (프로필 없음 또는 곡 음역 정보 없음, ERR-004). 음색·리듬은 데이터가 없으면 0점이 아닌 "평가 불가".
 */
@Composable
private fun MatchBody(detail: SongDetail, fit: SongFit?, profile: UserStore.VoiceProfile?, onPractice: () -> Unit) {
    val song = detail.summary
    val a = detail.analysis
    val songLow = a?.lowestMidi ?: 60
    val songHigh = a?.highestMidi ?: 72
    val userLow = profile?.lowMidi ?: songLow
    val userHigh = profile?.highMidi ?: songHigh
    val fitPct = fit?.let { (it.overallFit * 100).roundToInt() }
    val fitLabel = when { fitPct == null -> "평가 불가"; fitPct >= 90 -> "찰떡궁합!"; fitPct >= 75 -> "잘 맞아요"; else -> "도전해 보세요" }
    val key = fit?.recommendedKey
    val keyText = when { key == null -> "평가 불가"; key == 0 -> "원곡 키 추천 (0)"; key > 0 -> "키 +$key 추천"; else -> "키 $key 추천" }
    val axisLow = minOf(songLow, userLow) - 2
    val axisHigh = maxOf(songHigh, userHigh) + 2

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MiconCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(96.dp).clip(RoundedCornerShape(14.dp)).background(LavenderDeep)) {
                    song.imageUrl?.let { AsyncImage(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
                }
                Spacer(Modifier.width(16.dp))
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SoftChip(if (fitPct != null) "적합도 $fitPct%" else "적합도 평가 불가")
                        Spacer(Modifier.width(8.dp))
                        Text(fitLabel, fontSize = 14.sp, color = Purple, fontWeight = FontWeight.SemiBold)
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(song.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Ink)
                    Spacer(Modifier.height(4.dp))
                    Text(song.artist, fontSize = 15.sp, color = Grey)
                }
            }
        }

        MiconCard {
            CardTitle("음역대 매칭 분석") {
                Text(
                    when { fit == null -> "데이터 부족"; fitPct!! >= 75 -> "안정적인 소화 가능"; else -> "일부 구간 주의" },
                    fontSize = 14.sp, color = Purple, fontWeight = FontWeight.SemiBold,
                )
            }
            Spacer(Modifier.height(14.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("1옥타브 (Low)", fontSize = 12.sp, color = Grey); Text("2옥타브", fontSize = 12.sp, color = Grey); Text("3옥타브 (High)", fontSize = 12.sp, color = Grey)
            }
            Spacer(Modifier.height(12.dp))
            if (profile != null) {
                RangeBarRow("내 음역 범위 (${karaokeOctaveName(profile.lowMidi)} ~ ${karaokeOctaveName(profile.highMidi)})", profile.lowMidi, profile.highMidi, axisLow, axisHigh, LavenderDeep)
            } else {
                Text("내 음역 범위: 목소리 분석 후 표시됩니다", fontSize = 13.sp, color = Grey)
            }
            Spacer(Modifier.height(12.dp))
            RangeBarRow("곡 음역 범위 (${karaokeOctaveName(songLow)} ~ ${karaokeOctaveName(songHigh)})", songLow, songHigh, axisLow, axisHigh, Purple)
            Spacer(Modifier.height(14.dp))
            if (profile == null) {
                Text("리포트 탭에서 목소리를 분석하면 이 곡과 내 음역을 비교해 드려요.", fontSize = 15.sp, color = InkSoft, lineHeight = 22.sp)
            } else if (a == null) {
                Text("이 곡은 음역 분석 정보가 없어 적합도를 평가할 수 없어요.", fontSize = 15.sp, color = InkSoft, lineHeight = 22.sp)
            } else {
                Text(
                    buildAnnotatedString {
                        append("이 곡의 최고음은 ")
                        withStyle(SpanStyle(color = Purple, fontWeight = FontWeight.Bold)) { append(karaokeOctaveName(songHigh)) }
                        append("로, 회원님의 안정 음역대인 ")
                        withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(karaokeOctaveName(profile.comfortHigh)) }
                        append(
                            if (songHigh <= profile.comfortHigh) "보다 낮거나 같아 원키로도 무리가 적습니다."
                            else "보다 ${songHigh - profile.comfortHigh}키 높아 고음 구간에서 부담이 있을 수 있습니다.",
                        )
                    },
                    fontSize = 15.sp, color = InkSoft, lineHeight = 22.sp,
                )
            }
        }

        MiconCard {
            CardTitle("곡 맞춤 설정 추천")
            Spacer(Modifier.height(14.dp))
            SubCard("권장 키 조절", keyText) {
                Text(fit?.reasons?.firstOrNull() ?: "곡 음역 정보와 내 프로필이 있어야 계산돼요", fontSize = 13.sp, color = Purple)
            }
            Spacer(Modifier.height(12.dp))
            SubCard("음색 적합도", fit?.timbre?.label ?: "평가 불가") {
                Text(if (fit?.timbre != null) "두 축 중 ${fit.timbre.axis} 기준" else "음색 추천은 서버 연결 후 제공돼요", fontSize = 13.sp, color = Grey)
            }
            Spacer(Modifier.height(12.dp))
            SubCard("리듬 적합도", fit?.rhythmFit?.let { "${(it * 100).roundToInt()}%" } ?: "평가 불가") {
                Text("리듬 분석 데이터가 아직 없어요", fontSize = 13.sp, color = Grey)
            }
        }

        MiconCard {
            val strain = fit?.strainSections
            CardTitle("부담 구간") {
                Text(
                    if (strain == null) "평가 불가" else "원키 ${strain.original.size}곳 → 추천 키 ${strain.atKey.size}곳",
                    fontSize = 13.sp, color = Grey,
                )
            }
            Spacer(Modifier.height(14.dp))
            when {
                strain == null -> Text("프로필과 곡 음표가 있어야 계산돼요", fontSize = 13.sp, color = Grey)
                strain.atKey.isEmpty() -> Text("추천 키로 부르면 안정 음역을 넘는 구간이 없어요", fontSize = 14.sp, color = InkSoft)
                else -> strain.atKey.take(5).forEachIndexed { i, s ->
                    if (i > 0) Spacer(Modifier.height(8.dp))
                    HighlightRow("부담", "안정 음역 +${s.maxExcessSemitones}반음 초과", "${ScoringMath.formatTime(s.startSec)} ~ ${ScoringMath.formatTime(s.endSec)}", "고음 주의")
                }
            }
        }

        if (fit != null && fit.reasons.size > 1) {
            MiconCard {
                CardTitle("추천 사유")
                Spacer(Modifier.height(10.dp))
                fit.reasons.forEach { Text("• $it", fontSize = 14.sp, color = InkSoft, lineHeight = 22.sp) }
            }
        }

        PrimaryButton("이 곡 연습하기", onPractice, Modifier.fillMaxWidth())
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun RangeBarRow(label: String, low: Int, high: Int, axisLow: Int, axisHigh: Int, color: Color) {
    Column {
        Text(label, fontSize = 13.sp, color = InkSoft)
        Spacer(Modifier.height(6.dp))
        BoxWithConstraints(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(GreyLight)) {
            val span = (axisHigh - axisLow).coerceAtLeast(1)
            val x0 = maxWidth * (low - axisLow) / span
            val x1 = maxWidth * (high - axisLow) / span
            Box(Modifier.offset(x = x0).width(x1 - x0).height(10.dp).background(color, RoundedCornerShape(5.dp)))
        }
    }
}

@Composable
private fun SubCard(label: String, value: String, stars: Int = 0, hint: @Composable () -> Unit) {
    Column(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(16.dp)) {
        Text(label, fontSize = 13.sp, color = Grey)
        Spacer(Modifier.height(4.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Ink)
            if (stars > 0) {
                Spacer(Modifier.width(6.dp))
                repeat(3) { i -> Icon(if (i < stars) Icons.Filled.Star else Icons.Outlined.StarOutline, null, Modifier.size(18.dp), tint = Orange) }
            }
        }
        Spacer(Modifier.height(4.dp))
        hint()
    }
}

@Composable
private fun HighlightRow(badge: String, title: String, time: String, tag: String) {
    Row(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        SoftChip(badge, bg = PinkBg, fg = Pink)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 16.sp, fontWeight = FontWeight.Bold, color = Ink)
            Text(time, fontSize = 13.sp, color = Grey)
        }
        Text(tag, fontSize = 13.sp, color = Pink, fontWeight = FontWeight.SemiBold)
    }
}

@Suppress("unused")
private val unusedPurpleMid = PurpleMid
@Suppress("unused")
private val unusedLavender = Lavender
