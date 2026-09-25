package com.micon.app.ui.songdetail

import android.widget.Toast
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.SongDetail
import com.micon.app.data.SongFit
import com.micon.app.data.practice.PracticeScoreTrend
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.ScoringConstants
import com.micon.app.ui.analysis.RangeBar
import com.micon.app.ui.common.describeBackendError
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconFontFamily
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.rememberAppContainer
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * 곡 상세 — 프로토타입 `songDetail`. "이 곡을 내가 부를 수 있나"에 한 화면으로 답한다.
 * 곡 정보 → 음역 매치(내 음역·곡 음역을 같은 자에) → 키 조절(막대·문장이 같이 바뀜) → 어려운 구간 → 연습하기.
 * 예전 곡 매치 상세·곡 상세 두 화면을 하나로 합쳤고, 어둡던 톤을 라이트로 통일했다.
 */
@Composable
fun SongDetailScreen(songId: String, onBack: () -> Unit, onPractice: (Int) -> Unit,
    onSettings: () -> Unit = {}, onLogin: (() -> Unit)? = null,
    carouselIds: List<String> = emptyList(), artwork: Map<String, String?> = emptyMap(), onSongSelected: (String) -> Unit = {}) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: SongDetailViewModel = viewModel(key = "song-$songId", factory = viewModelFactory {
        initializer { SongDetailViewModel(context.applicationContext, container.songs, container.mediaHttp, container.recommend, container.practices, songId) }
    })
    val state by vm.state.collectAsStateWithLifecycle()
    val profile by UserStore.profile.collectAsStateWithLifecycle()
    val liked by container.favorites.likedIds.collectAsStateWithLifecycle()
    val loggedIn = TokenManager.isLoggedIn()
    val scope = rememberCoroutineScope()
    LaunchedEffect(loggedIn) { if (loggedIn) runCatching { container.favorites.refresh() } }
    fun toggleLike() {
        if (!loggedIn) { onLogin?.invoke() ?: Toast.makeText(context, "로그인하면 찜할 수 있어요.", Toast.LENGTH_SHORT).show(); return }
        scope.launch { runCatching { container.favorites.toggle(songId) }.onFailure { Toast.makeText(context, describeBackendError(it), Toast.LENGTH_SHORT).show() } }
    }
    SongDetailContent(state, profile, liked = songId in liked, showFavorite = loggedIn, onLike = ::toggleLike,
        onKey = vm::selectKey, onPractice = { vm.leaveScreen(); onPractice(state.selectedKey) },
        onBack = { vm.leaveScreen(); onBack() }, onRetryDetail = vm::load)
}

/** 순수 콘텐츠 — 프리뷰·UI 테스트용 */
@Composable
fun SongDetailContent(
    state: SongDetailState, profile: UserStore.VoiceProfile?, liked: Boolean = false, showFavorite: Boolean = true, onLike: () -> Unit = {},
    onKey: (Int) -> Unit = {}, onPractice: () -> Unit = {}, onBack: () -> Unit = {}, onRetryDetail: () -> Unit = {},
) {
    val detail = state.detail
    val key = state.selectedKey
    MiconScreen(
        appBar = {
            MiconAppBar(onBack = onBack, actions = {
                if (showFavorite) {
                    IconButton(onLike, Modifier.size(MiconSpace.touchTarget)) {
                        Icon(if (liked) Icons.Filled.Favorite else Icons.Outlined.FavoriteBorder, contentDescription = if (liked) "찜 해제" else "찜",
                            tint = if (liked) Color(0xFFC92A5E) else MaterialTheme.colorScheme.onSurface, modifier = Modifier.size(23.dp))
                    }
                }
            })
        },
        verticalArrangement = Arrangement.spacedBy(MiconSpace.md),
        bottom = {
            MiconButton(
                text = when {
                    state.assetsLoading || state.keyLoading -> "반주 준비 중…"
                    !state.practiceEnabled -> "연습 데이터 준비 중"
                    key == 0 -> "원키로 연습하기"
                    else -> "${NoteNames.keyShiftText(key).removeSuffix("기")}고 연습하기"
                },
                onClick = onPractice, modifier = Modifier.fillMaxWidth().testTag("song-practice"), enabled = state.practiceEnabled,
            )
        },
    ) {
        if (detail == null) {
            Box(Modifier.fillMaxWidth().padding(top = 80.dp), contentAlignment = Alignment.Center) {
                if (state.loading) CircularProgressIndicator()
                else Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(state.error ?: "곡을 불러오지 못했어요.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    MiconButton("다시 불러오기", onRetryDetail, kind = com.micon.app.ui.design.atoms.MiconButtonKind.Text)
                }
            }
            return@MiconScreen
        }
        val fit = state.fit
        val song = songRange(detail, fit)
        val mine = profile?.let { it.comfortLow to it.comfortHigh }
        val matchPct = rangeMatchPercent(mine, song, key)
        val verdict = rangeVerdict(mine, song, key, fit?.recommendedKey)

        Header(detail, verdict)
        MatchCard(matchPct, mine, song, key, verdict, state.fitLoading, profile == null)
        KeyCard(key, onKey)
        DifficultCard(state, fit)
        val scoreTrend = state.scoreTrend
        if (scoreTrend != null) PracticeScoreTrendCard(scoreTrend)
    }
}

/** 같은 곡을 반복 연습한 완료 점수만 노래 상세의 마지막에 보여 준다. */
@Composable
private fun PracticeScoreTrendCard(trend: PracticeScoreTrend) {
    val points = trend.points.takeLast(5)
    val latest = points.lastOrNull()?.overallScore ?: return
    MiconCard(kind = MiconCardKind.Outlined) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("최근 ${points.size}회 연습 점수", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
                Text("같은 곡을 연습한 기록이에요", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${"%.1f".format(latest)}점", fontFamily = MiconMonoFamily, fontSize = 22.sp,
                fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        }
        Spacer(Modifier.height(12.dp))
        ScoreTrendChart(points)
    }
}

@Composable
private fun ScoreTrendChart(points: List<PracticeScoreTrend.Point>) {
    val colors = MaterialTheme.colorScheme
    BoxWithConstraints(Modifier.fillMaxWidth().height(164.dp).padding(horizontal = 4.dp, vertical = 4.dp)) {
        val chartLeft = 34.dp
        val chartRight = maxWidth - 10.dp
        val chartTop = 10.dp
        val chartBottom = maxHeight - 54.dp
        val pointInset = 26.dp
        fun pointX(index: Int) = if (points.size == 1) (chartLeft + chartRight) / 2
            else chartLeft + pointInset + (chartRight - chartLeft - pointInset * 2) * index / points.lastIndex
        fun pointY(score: Double) = chartBottom - (chartBottom - chartTop) * (score / 100.0).toFloat().coerceIn(0f, 1f)

        Canvas(Modifier.fillMaxSize()) {
            fun x(index: Int) = pointX(index).toPx()
            fun y(score: Double) = pointY(score).toPx()
            listOf(0, 25, 50, 75, 100).forEach { score ->
                val lineY = y(score.toDouble())
                drawLine(colors.outlineVariant, Offset(chartLeft.toPx(), lineY), Offset(chartRight.toPx(), lineY), 1.dp.toPx())
            }
            if (points.size > 1) {
                val line = Path()
                points.forEachIndexed { index, point ->
                    if (index == 0) line.moveTo(x(index), y(point.overallScore)) else line.lineTo(x(index), y(point.overallScore))
                }
                drawPath(line, colors.primary, style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
            points.forEachIndexed { index, point ->
                drawCircle(colors.primary, 4.dp.toPx(), Offset(x(index), y(point.overallScore)))
                drawCircle(colors.surface, 1.7.dp.toPx(), Offset(x(index), y(point.overallScore)))
            }
        }
        listOf(0, 25, 50, 75, 100).forEach { score ->
            Box(
                Modifier.width(chartLeft - 8.dp).height(16.dp).offset(y = pointY(score.toDouble()) - 8.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Text("$score", fontSize = 10.sp, color = colors.onSurfaceVariant, fontFamily = MiconFontFamily)
            }
        }
        points.forEachIndexed { index, point ->
            Box(
                Modifier.width(52.dp).height(42.dp).offset(x = pointX(index) - 26.dp, y = chartBottom + 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("${point.overallScore.roundToInt()}점", fontSize = 13.sp, color = colors.onSurface, fontWeight = FontWeight.SemiBold, fontFamily = MiconFontFamily)
                    Text(practiceTrendDate(point.playedAt), fontSize = 9.sp, color = colors.onSurfaceVariant, fontFamily = MiconFontFamily)
                }
            }
        }
    }
}

private fun practiceTrendDate(iso: String?): String = runCatching {
    val source = iso?.take(10) ?: return "-"
    val year = source.substring(0, 4).toInt()
    val month = source.substring(5, 7).toInt()
    val day = source.substring(8, 10).toInt()
    val dayNames = arrayOf("일", "월", "화", "수", "목", "금", "토")
    val calendar = java.util.Calendar.getInstance().apply {
        clear()
        set(year, month - 1, day)
    }
    "$month.$day ${dayNames[calendar.get(java.util.Calendar.DAY_OF_WEEK) - 1]}"
}.getOrDefault("-")

internal data class Verdict(val label: String, val tone: MiconChipTone, val message: String)

private fun songRange(detail: SongDetail, fit: SongFit?): IntRange? {
    val low = fit?.rangeFit?.songCoreLowMidi ?: detail.analysis?.coreLowMidi ?: detail.analysis?.lowestMidi
    val high = fit?.rangeFit?.songCoreHighMidi ?: detail.analysis?.coreHighMidi ?: detail.analysis?.highestMidi
    return if (low != null && high != null && high >= low) low..high else null
}

/** 추천 종합 점수와 분리해, 선택 키가 반영된 곡 음역과 내 편한 음역만으로 판정한다. */
internal fun rangeVerdict(
    mine: Pair<Int, Int>?, song: IntRange?, key: Int, recommendedKey: Int? = null,
): Verdict {
    if (mine == null) return Verdict("평가 불가", MiconChipTone.Neutral, "목소리를 분석하면 이 곡이 내 음역에 맞는지 알려 드려요.")
    if (song == null) return Verdict("평가 불가", MiconChipTone.Neutral, "이 곡의 음역 정보가 아직 없어요.")

    val observedLow = mine.first - com.micon.app.scoring.RangeFitCalculator.COMFORT_PADDING_SEMITONES
    val observedHigh = mine.second + com.micon.app.scoring.RangeFitCalculator.COMFORT_PADDING_SEMITONES
    val shifted = (song.first + key)..(song.last + key)
    val overTop = (shifted.last - observedHigh).coerceAtLeast(0)
    val underLow = (observedLow - shifted.first).coerceAtLeast(0)
    val excess = maxOf(overTop, underLow)
    val tone = when {
        excess == 0 -> MiconChipTone.Success
        excess <= 2 -> MiconChipTone.Warning
        else -> MiconChipTone.Challenge
    }
    val supportedRecommendation = recommendedKey?.coerceIn(ScoringConstants.KEY_SHIFT_MIN, ScoringConstants.KEY_SHIFT_MAX)
    val label = when {
        supportedRecommendation != null -> NoteNames.keyShiftText(supportedRecommendation)
        tone == MiconChipTone.Success -> "잘 맞아요"
        overTop > 0 -> NoteNames.keyShiftText((-overTop).coerceAtLeast(ScoringConstants.KEY_SHIFT_MIN))
        underLow > 0 -> NoteNames.keyShiftText(underLow.coerceAtMost(ScoringConstants.KEY_SHIFT_MAX))
        else -> "도전곡"
    }
    val message = when {
        supportedRecommendation != null && key == supportedRecommendation && key != 0 && overTop <= 0 && underLow <= 0 ->
            "추천 키를 적용했어요. 곡이 내 음역 안에 들어옵니다."
        key == 0 && overTop <= 0 && underLow <= 0 -> "곡이 내 음역 안에 들어와요. 원래 키로 불러도 편합니다."
        key == 0 && overTop > 0 -> "후렴 고음이 내 음역보다 ${NoteNames.distanceText(0, overTop).removeSuffix("요")}. 키를 내려 보세요."
        key == 0 && underLow > 0 -> "곡의 낮은 음이 내 음역보다 ${NoteNames.distanceText(underLow, 0).removeSuffix("요")}. 키를 올려 보세요."
        key < 0 && overTop <= 0 -> "${-key}키 내림 · 후렴 고음이 한결 편해집니다."
        key < 0 -> "${-key}키 내림 · 아직 고음이 ${NoteNames.distanceText(0, overTop).removeSuffix(" 높아요")} 남았어요."
        overTop > 0 -> "${key}키 올림 · 후렴에서 음이 내 음역을 벗어나요."
        else -> "${key}키 올림 · 아직 내 음역 안이에요."
    }
    return Verdict(label, tone, message)
}

/** 화면의 '음역 매치' 수치도 overallFit 대신 현재 선택 키의 실제 음역 겹침으로 계산한다. */
internal fun rangeMatchPercent(mine: Pair<Int, Int>?, song: IntRange?, key: Int): Int? {
    if (mine == null || song == null) return null
    val observedLow = mine.first - com.micon.app.scoring.RangeFitCalculator.COMFORT_PADDING_SEMITONES
    val observedHigh = mine.second + com.micon.app.scoring.RangeFitCalculator.COMFORT_PADDING_SEMITONES
    val shiftedLow = song.first + key
    val shiftedHigh = song.last + key
    val width = shiftedHigh - shiftedLow
    if (width == 0) return if (shiftedLow in observedLow..observedHigh) 100 else 0
    val overlap = (minOf(shiftedHigh, observedHigh) - maxOf(shiftedLow, observedLow)).coerceAtLeast(0)
    return ((overlap.toDouble() / width) * 100).roundToInt().coerceIn(0, 100)
}

@Composable
private fun Header(detail: SongDetail, verdict: Verdict) {
    val s = detail.summary
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        CompactDisc(title = s.title, artist = s.artist, imageUrl = s.imageUrl, size = 88.dp)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(s.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            val meta = listOfNotNull(s.artist, s.releaseDate?.take(4), detail.genres.firstOrNull() ?: s.genre).joinToString(" · ")
            Text(meta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            MiconBadge(verdict.label, tone = verdict.tone)
        }
    }
}

@Composable
private fun MatchCard(match: Int?, mine: Pair<Int, Int>?, song: IntRange?, key: Int, verdict: Verdict, loading: Boolean, noProfile: Boolean) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    MiconCard(kind = MiconCardKind.Elevated) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text("음역 매치", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp, modifier = Modifier.weight(1f).padding(bottom = 6.dp))
            if (match != null) {
                Text("$match", fontFamily = MiconMonoFamily, fontSize = 34.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = (-1).sp, lineHeight = 36.sp)
                Text("%", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(start = 2.dp, bottom = 6.dp))
            } else if (loading && !noProfile) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
        }
        Spacer(Modifier.height(8.dp))
        val shifted = song?.let { (it.first + key)..(it.last + key) }
        RangeBar(low = mine?.first ?: 0, high = mine?.second ?: 0, song = shifted)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp, 6.dp).background(Color(0xFF6C5CE7), RoundedCornerShape(3.dp)))
                Spacer(Modifier.width(6.dp)); Text("내 음역", fontSize = 12.sp, color = muted)
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp, 10.dp).border(1.5.dp, Color(0xFFC92A5E), RoundedCornerShape(5.dp)))
                Spacer(Modifier.width(6.dp)); Text("곡 음역", fontSize = 12.sp, color = muted)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(verdict.message, style = MaterialTheme.typography.bodyMedium, color = muted)
    }
}

@Composable
private fun KeyCard(key: Int, onKey: (Int) -> Unit) {
    MiconCard(kind = MiconCardKind.Outlined) {
        Text("키 조절", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KeyStep("−", enabled = key > ScoringConstants.KEY_SHIFT_MIN) { onKey(key - 1) }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                // 타이머처럼: − 는 지금 숫자가 위로 빠지고 새 숫자가 아래에서, + 는 아래로 빠지고 위에서 들어온다.
                // 카드 칸 밖으로 넘친 부분은 잘라서 숫자판 한 칸이 돌아가는 것처럼 보이게 한다.
                AnimatedContent(
                    targetState = key,
                    transitionSpec = {
                        val down = targetState < initialState
                        val motion = tween<androidx.compose.ui.unit.IntOffset>(KEY_SLIDE_MS, easing = FastOutSlowInEasing)
                        val fade = tween<Float>(KEY_SLIDE_MS)
                        (slideInVertically(motion) { if (down) it else -it } + fadeIn(fade))
                            .togetherWith(slideOutVertically(motion) { if (down) -it else it } + fadeOut(fade))
                            .using(SizeTransform(clip = true))
                    },
                    label = "key-shift",
                ) { value ->
                    Text(if (value > 0) "+$value" else "$value", fontFamily = MiconMonoFamily, fontSize = 26.sp,
                        fontWeight = FontWeight.Bold, lineHeight = 30.sp, modifier = Modifier.testTag("song-key-value"))
                }
                // 보조 문구는 같이 미끄러지면 산만해서 짧게 겹쳐 바뀌기만 한다
                AnimatedContent(targetState = key, transitionSpec = { fadeIn(tween(KEY_SLIDE_MS)) togetherWith fadeOut(tween(KEY_SLIDE_MS)) },
                    label = "key-shift-label") { value ->
                    Text(NoteNames.keyShiftText(value), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            KeyStep("+", enabled = key < ScoringConstants.KEY_SHIFT_MAX) { onKey(key + 1) }
        }
    }
}

/** 키 숫자가 한 칸 미끄러지는 시간(ms) */
private const val KEY_SLIDE_MS = 220

@Composable
internal fun KeyStep(symbol: String, size: androidx.compose.ui.unit.Dp = 48.dp, enabled: Boolean = true, onClick: () -> Unit) {
    IconButton(onClick, Modifier.size(size), enabled = enabled) {
        Text(symbol, fontSize = 22.sp, fontWeight = FontWeight.Medium, textAlign = TextAlign.Center,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f))
    }
}

/** 곡 구간 중 난이도가 표시된 것 2개. 없으면 카드 생략 */
@Composable
private fun DifficultCard(state: SongDetailState, fit: SongFit?) {
    val hard = state.sections.filter { it.difficultyType != "NONE" && it.difficultyType.isNotBlank() }
        .sortedByDescending { it.difficultyScore ?: 0.0 }.take(2)
    val strain = fit?.strainSections?.original.orEmpty()
    // 어려운 구간은 서버의 practice-assets.sections 또는 /fit burdenSections에서 온다.
    // 실제로 표시할 구간이 없으면 카드도 표시하지 않는다.
    if (hard.isEmpty() && strain.isEmpty()) return
    MiconCard(kind = MiconCardKind.Outlined) {
        Text("어려운 구간", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
        Spacer(Modifier.height(4.dp))
        if (hard.isNotEmpty()) hard.forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.sectionName.ifBlank { s.sectionType }, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                val (label, tone) = when (s.difficultyType) {
                    "HIGH_NOTE" -> "높은 음" to MiconChipTone.Warning
                    "LONG_BREATH" -> "긴 호흡" to MiconChipTone.Neutral
                    "FAST_LYRICS" -> "빠른 가사" to MiconChipTone.Neutral
                    "PITCH_JUMP" -> "음 도약" to MiconChipTone.Warning
                    else -> "복합" to MiconChipTone.Challenge
                }
                MiconBadge(s.description?.takeIf { it.length <= 10 } ?: label, tone = tone)
            }
        } else strain.take(2).forEach { s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${(s.startSec / 60).toInt()}:${"%02d".format((s.startSec % 60).toInt())} 부근", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                MiconBadge("${s.maxExcessSemitones}음 높음", tone = MiconChipTone.Warning)
            }
        }
    }
}
