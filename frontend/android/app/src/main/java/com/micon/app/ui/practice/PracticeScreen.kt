package com.micon.app.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.core.auth.TokenManager
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.MeasureRow
import com.micon.app.scoring.ScoringConstants
import com.micon.app.ui.common.PlaybackLifecycleEffect
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.organisms.MiconAppBar
import com.micon.app.ui.design.organisms.MiconScreen
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.practice.PracticeViewModel.Phase
import com.micon.app.ui.practice.PracticeViewModel.SavePhase
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.songdetail.KeyStep
import kotlin.math.roundToInt

private val Good = Color(0xFF1F6B3A)
private val Pink = Color(0xFFC92A5E)
private val Waiting = Color(0xFFC3BED6)

/**
 * 연습 — 프로토타입 `practiceReady`(시작 전) → `practiceSing`(부르는 중) → `practiceResult`(결과) 세 화면을
 * 한 라우트 안에서 단계로 보여 준다. 채점·저장 로직은 [PracticeViewModel] 그대로.
 */
@Composable
fun PracticeScreen(songId: String, onBack: () -> Unit, onOpenResult: (Long) -> Unit, initialKey: Int = 0) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: PracticeViewModel = viewModel(
        key = "practice-$songId-$initialKey",
        factory = viewModelFactory { initializer { PracticeViewModel(context.applicationContext, songId, container.songs, container.mediaHttp, container.practices, isLoggedIn = { TokenManager.isLoggedIn() }, initialKey = initialKey) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    PlaybackLifecycleEffect(vm, onExit = vm::stopAudioForExit)
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.start() }
    fun startWithPermission() { if (hasMic()) vm.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    fun exit() { vm.stopAudioForExit(); onBack() }

    when (state.phase) {
        Phase.LOADING -> MiconScreen(appBar = { MiconAppBar(onBack = ::exit) }, scrollable = false) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        }
        Phase.ERROR -> MiconScreen(appBar = { MiconAppBar(onBack = ::exit) }, bottom = { MiconButton("돌아가기", ::exit, Modifier.fillMaxWidth()) }) {
            Text(state.error ?: "연습 데이터를 불러오지 못했어요.", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Phase.READY -> ReadyScreen(state, vm, onStart = ::startWithPermission, onBack = ::exit)
        Phase.COUNTDOWN, Phase.RUNNING, Phase.PAUSED -> SingScreen(state, vm)
        Phase.FINISHED -> ResultScreen(state, vm, songId,
            onAgain = { vm.restart(); startWithPermission() }, onOther = ::exit, onDetail = onOpenResult)
    }
}

// ---------------------------------------------------------------- 시작 전

@Composable
private fun ReadyScreen(state: PracticeViewModel.UiState, vm: PracticeViewModel, onStart: () -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    MiconScreen(
        appBar = { MiconAppBar(onBack = onBack) },
        verticalArrangement = Arrangement.spacedBy(MiconSpace.md),
        bottom = {
            if (!state.headphones) Text("이어폰을 쓰면 판정이 정확해요.", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
            MiconButton("시작하기", onStart, Modifier.fillMaxWidth())
        },
    ) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            CompactDisc(title = state.title, artist = state.artist, size = 120.dp)
            Spacer(Modifier.height(4.dp))
            Text(state.title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(state.artist, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MiconCard(kind = MiconCardKind.Outlined) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text("키", style = MaterialTheme.typography.labelLarge, modifier = Modifier.weight(1f))
                KeyStep("−", size = 40.dp, enabled = state.keyShift > ScoringConstants.KEY_SHIFT_MIN) { vm.changeKey(state.keyShift - 1) }
                Text(if (state.keyShift > 0) "+${state.keyShift}" else "${state.keyShift}", fontFamily = MiconMonoFamily, fontSize = 19.sp, fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center, modifier = Modifier.width(40.dp))
                KeyStep("+", size = 40.dp, enabled = state.keyShift < ScoringConstants.KEY_SHIFT_MAX) { vm.changeKey(state.keyShift + 1) }
            }
            Box(Modifier.fillMaxWidth().padding(vertical = 8.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(Modifier.fillMaxWidth().clickable { vm.setSkipIntro(!state.skipIntro) }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("전주 건너뛰기", style = MaterialTheme.typography.labelLarge)
                    Text("첫 가사 4초 전부터 시작해요", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(checked = state.skipIntro, onCheckedChange = vm::setSkipIntro,
                    colors = SwitchDefaults.colors(checkedTrackColor = MaterialTheme.colorScheme.primary, uncheckedTrackColor = Color(0xFFD9D5EA), uncheckedBorderColor = Color.Transparent))
            }
        }
        val firstLines = vm.lyricLines.map { it.text }.filter { it.isNotBlank() }.take(2)
        if (firstLines.isNotEmpty()) MiconCard(kind = MiconCardKind.Tinted) {
            Text("첫 소절 미리보기", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onPrimaryContainer, letterSpacing = 1.sp)
            Spacer(Modifier.height(6.dp))
            firstLines.forEach { Text(it, fontSize = 15.sp, lineHeight = 25.sp, color = MaterialTheme.colorScheme.onPrimaryContainer) }
        }
    }
}

// ---------------------------------------------------------------- 부르는 중

@Composable
private fun SingScreen(state: PracticeViewModel.UiState, vm: PracticeViewModel) {
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    fun stop() { if (state.phase != Phase.PAUSED) vm.pause(); vm.finishEarly() }
    BackHandler { if (state.phase == Phase.PAUSED) vm.restart() else vm.pause() }

    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = MiconSpace.screenHorizontal).padding(top = 10.dp)) {
            // 제목 · 진행 · 시간
            Row(Modifier.fillMaxWidth().height(44.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("${state.title} · ${state.artist}", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = accent, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                val progress = if (state.durationSec > 0) (state.nowSec / state.durationSec).toFloat().coerceIn(0f, 1f) else 0f
                Box(Modifier.weight(1f).height(4.dp).clip(CircleShape).background(Color(0xFFDCD6FF))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxHeight().background(MaterialTheme.colorScheme.primary))
                }
                Text(mmss(state.nowSec), fontFamily = MiconMonoFamily, fontSize = 12.sp, color = muted)
            }
            // 점수
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                Text("${state.totals?.overall?.roundToInt() ?: 0}", fontFamily = MiconMonoFamily, fontSize = 60.sp, fontWeight = FontWeight.Black, letterSpacing = (-2).sp, lineHeight = 64.sp)
                Text(" 점", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(bottom = 10.dp))
            }
            // 음정 곡선(목표선 위에 내 음정)
            Runway(vm.notes, vm.displayBars, vm.frames, vm.beatGrid, vm.meter, state.nowSec, state.keyShift, Modifier.fillMaxWidth().height(110.dp))
            // 가사
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                LyricNow(vm, state.nowSec)
            }
            Spacer(Modifier.height(150.dp))
        }

        // 결과 바로 보기
        Box(
            Modifier.align(Alignment.BottomEnd).navigationBarsPadding().padding(end = 12.dp, bottom = 140.dp)
                .height(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.92f))
                .clickable(onClick = ::stop).padding(horizontal = 12.dp),
            contentAlignment = Alignment.Center,
        ) { Text("결과 바로 보기 ›", fontFamily = MiconMonoFamily, fontSize = 12.sp, color = accent) }

        // 하단 시트
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White, shadowElevation = 12.dp,
        ) {
            Row(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(start = 32.dp, end = 32.dp, top = 18.dp, bottom = 24.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                IconButton(vm::restart, Modifier.size(48.dp)) { Icon(Icons.Filled.Replay, contentDescription = "처음부터", tint = MaterialTheme.colorScheme.onSurface) }
                val paused = state.phase == Phase.PAUSED
                Box(
                    Modifier.size(80.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primaryContainer)
                        .clickable { if (paused) vm.resume() else vm.pause() },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, contentDescription = if (paused) "이어서" else "일시정지", tint = accent, modifier = Modifier.size(30.dp)) }
                IconButton(::stop, Modifier.size(48.dp)) { Icon(Icons.Filled.Stop, contentDescription = "그만하기", tint = MaterialTheme.colorScheme.onSurface) }
            }
        }

        if (state.phase == Phase.COUNTDOWN) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.2f)), contentAlignment = Alignment.Center) {
                if (state.buffering) CircularProgressIndicator(color = Color.White)
                else if (state.countdown > 0) Text("${state.countdown}", fontFamily = MiconMonoFamily, fontSize = 72.sp, fontWeight = FontWeight.Black, color = Color.White)
            }
        }
        state.error?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 12.sp, modifier = Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 48.dp)) }
    }
}

/** 현재 줄은 단어(음절)마다 대기·지금·정확·틈린 색, 다음 줄은 작게 */
@Composable
private fun LyricNow(vm: PracticeViewModel, nowSec: Double) {
    val sheet = vm.sheet
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    if (sheet.lines.isEmpty()) { Text("가사 데이터가 없는 곡이에요. 음정 곡선을 보며 불러 보세요.", color = muted, textAlign = TextAlign.Center, style = MaterialTheme.typography.bodyMedium); return }
    val i = sheet.currentIndex(nowSec)
    val current = i?.let { sheet.lines.getOrNull(it) }
    val next = sheet.lines.getOrNull((i ?: -1) + 1)
    if (current != null) {
        Text(
            buildAnnotatedString {
                current.chars.forEach { ch ->
                    val st = ch.note?.status
                    val color = when {
                        st == LiveStatus.GOOD -> Good
                        st == LiveStatus.SHARP || st == LiveStatus.FLAT || st == LiveStatus.WRONG || st == LiveStatus.MISSED -> Pink
                        nowSec >= ch.at -> accent
                        else -> Waiting
                    }
                    withStyle(SpanStyle(color = color)) { append(ch.text) }
                }
            },
            fontSize = 24.sp, lineHeight = 36.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp, textAlign = TextAlign.Center,
        )
    } else Text("♪", fontSize = 24.sp, color = muted)
    Spacer(Modifier.height(10.dp))
    Text(next?.text ?: if (current != null) "— 마지막 소절 —" else "", fontSize = 16.sp, fontWeight = FontWeight.Medium, color = muted, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis)
}

// ---------------------------------------------------------------- 결과

@Composable
private fun ResultScreen(state: PracticeViewModel.UiState, vm: PracticeViewModel, songId: String,
    onAgain: () -> Unit, onOther: () -> Unit, onDetail: (Long) -> Unit) {
    val container = rememberAppContainer()
    val score = state.totals?.overall?.roundToInt() ?: 0
    val accent = MaterialTheme.colorScheme.onPrimaryContainer
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    BackHandler(onBack = onOther)

    // 지난 기록과 비교(회원만)
    var previous by remember { mutableStateOf<Int?>(null) }
    LaunchedEffect(songId) {
        if (TokenManager.isLoggedIn()) previous = runCatching {
            container.practices.history(0, 30).items.filter { it.songId == songId && it.overallScore != null && it.id != state.save.practiceId }
                .firstOrNull()?.overallScore?.roundToInt()
        }.getOrNull()
    }
    val countUp = remember { Animatable(0f) }
    LaunchedEffect(score) { countUp.snapTo(0f); countUp.animateTo(score.toFloat(), tween(900, easing = FastOutSlowInEasing)) }

    val sections = remember(state.measures) { sectionScores(vm, state.measures) }
    val best = sections.maxByOrNull { it.second }
    val worst = sections.filter { it != best }.minByOrNull { it.second }

    MiconScreen(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        bottom = {
            MiconButton("다시 연습하기", onAgain, Modifier.fillMaxWidth())
            MiconButton("다른 노래 고르기", onOther, Modifier.fillMaxWidth(), kind = MiconButtonKind.Outline)
        },
    ) {
        Spacer(Modifier.height(12.dp))
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(4.dp)) {
            MiconBadge("연습 끝!", tone = MiconChipTone.Success)
            Row(verticalAlignment = Alignment.Bottom) {
                Text("${countUp.value.roundToInt()}", fontFamily = MiconMonoFamily, fontSize = 72.sp, fontWeight = FontWeight.Black, color = accent, letterSpacing = (-2).sp, lineHeight = 76.sp)
                Text(" 점", fontSize = 16.sp, color = muted, modifier = Modifier.padding(bottom = 12.dp))
            }
            val delta = previous?.let { score - it }
            Text(
                when {
                    delta == null -> state.save.message.ifBlank { " " }
                    delta > 0 -> "지난번보다 ${delta}점 좋아졌어요"
                    delta < 0 -> "지난번보다 ${-delta}점 낮아요. 한 번 더!"
                    else -> "지난번과 같은 점수예요"
                },
                style = MaterialTheme.typography.bodyMedium, color = if (delta != null && delta > 0) Good else muted, textAlign = TextAlign.Center,
            )
        }
        if (sections.isNotEmpty()) MiconCard(kind = MiconCardKind.Elevated) {
            Text("구간별 점수", style = MaterialTheme.typography.labelMedium, color = accent, letterSpacing = 1.sp)
            Spacer(Modifier.height(10.dp))
            Spark(sections.map { it.second }, highlight = sections.indices.filter { sections[it].second >= 85 }.toSet())
            Row(Modifier.fillMaxWidth()) {
                sections.forEach { (label, _) -> Text(label, Modifier.weight(1f), fontSize = 12.sp, color = muted, textAlign = TextAlign.Center, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
        }
        if (best != null) MiconCard(kind = MiconCardKind.Outlined) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                MiconBadge("잘한 구간", tone = MiconChipTone.Success); Spacer(Modifier.weight(1f))
                Text("${best.second}점", fontFamily = MiconMonoFamily, fontWeight = FontWeight.Bold, color = Good)
            }
            Text("${best.first} · 음정이 흔들리지 않았어요", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 4.dp))
            if (worst != null) {
                Box(Modifier.fillMaxWidth().padding(vertical = 10.dp).height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    MiconBadge("아쉬운 구간", tone = MiconChipTone.Challenge); Spacer(Modifier.weight(1f))
                    Text("${worst.second}점", fontFamily = MiconMonoFamily, fontWeight = FontWeight.Bold, color = Pink)
                }
                Text("${worst.first} · ${weakReason(state)}", style = MaterialTheme.typography.bodyMedium, color = muted, modifier = Modifier.padding(top = 4.dp))
            }
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MiconButton(if (state.takePlaying) "정지" else "녹음 다시 듣기", { if (state.takePlaying) vm.stopTake() else vm.playTake() }, Modifier.weight(1f), kind = MiconButtonKind.Tonal)
                when (state.save.phase) {
                    SavePhase.SAVED -> MiconButton("자세히 보기", { state.save.practiceId?.let(onDetail) }, Modifier.weight(1f), kind = MiconButtonKind.Tonal)
                    SavePhase.ERROR -> MiconButton("기록 다시 저장", vm::retrySave, Modifier.weight(1f), kind = MiconButtonKind.Tonal)
                    SavePhase.SAVING -> Box(Modifier.weight(1f).height(MiconSpace.buttonHeight), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    else -> {}
                }
            }
        }
        if (state.save.phase == SavePhase.GUEST || state.save.phase == SavePhase.ERROR) {
            Text(state.save.message, style = MaterialTheme.typography.labelMedium, color = if (state.save.phase == SavePhase.ERROR) MaterialTheme.colorScheme.error else muted, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
        }
    }
}

/** 마디 점수를 구간(1절·후렴…)으로 묶는다. 구간 정보가 없으면 6등분 */
private fun sectionScores(vm: PracticeViewModel, measures: List<MeasureRow>): List<Pair<String, Int>> {
    if (measures.isEmpty()) return emptyList()
    val measureStart = vm.notes.groupBy { it.measure }.mapValues { (_, ns) -> ns.minOf { it.start } }
    val secs = vm.sections.sections
    if (secs.isNotEmpty()) {
        return secs.mapNotNull { s ->
            val rows = measures.filter { m -> (measureStart[m.measure] ?: -1.0).let { it >= s.start && it < s.end } }
            if (rows.isEmpty()) null else s.label.take(4) to weighted(rows)
        }.take(8)
    }
    val chunk = (measures.size / 6.0).let { if (it < 1) 1 else Math.ceil(it).toInt() }
    return measures.chunked(chunk).mapIndexed { i, rows -> "${i + 1}" to weighted(rows) }
}

private fun weighted(rows: List<MeasureRow>): Int {
    val w = rows.sumOf { it.notes }.coerceAtLeast(1)
    return (rows.sumOf { it.score * it.notes } / w.toDouble()).roundToInt()
}

private fun weakReason(state: PracticeViewModel.UiState): String {
    val t = state.totals ?: return "다시 한 번 들어 보세요"
    return when {
        t.flat > t.sharp && t.flat >= t.missed -> "음이 내려갔어요"
        t.sharp > t.flat && t.sharp >= t.missed -> "음이 올라갔어요"
        t.missed > 0 -> "놓친 음이 있어요"
        t.late > t.early -> "박자가 늦었어요"
        else -> "박자가 빨랐어요"
    }
}

@Composable
private fun Spark(values: List<Int>, highlight: Set<Int>) {
    val rise = remember { Animatable(0f) }
    LaunchedEffect(values) { rise.snapTo(0f); rise.animateTo(1f, tween(600, easing = FastOutSlowInEasing)) }
    Row(Modifier.fillMaxWidth().height(44.dp), horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.Bottom) {
        values.forEachIndexed { i, v ->
            Box(
                Modifier.weight(1f).fillMaxHeight((v / 100f).coerceIn(0.04f, 1f)).graphicsLayer { scaleY = rise.value; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 1f) }
                    .clip(RoundedCornerShape(topStart = 3.dp, topEnd = 3.dp))
                    .background(if (i in highlight) MaterialTheme.colorScheme.primary else Color(0xFFDCD6FF)),
            )
        }
    }
}

private fun mmss(sec: Double): String { val s = sec.toInt().coerceAtLeast(0); return "%d:%02d".format(s / 60, s % 60) }
