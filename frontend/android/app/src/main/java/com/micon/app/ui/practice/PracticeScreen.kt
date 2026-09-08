package com.micon.app.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Repeat
import androidx.compose.material.icons.automirrored.outlined.ShowChart
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.LyricLine
import com.micon.app.scoring.ScoringConstants
import com.micon.app.scoring.ScoringUi
import com.micon.app.scoring.Totals
import com.micon.app.ui.charts.WaveformBars
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.formatSec
import com.micon.app.ui.practice.PracticeViewModel.Phase
import com.micon.app.ui.practice.PracticeViewModel.ReportPhase
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.GreenChipBg
import com.micon.app.ui.theme.GreenChipText
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.LavenderDeep
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import com.micon.app.ui.theme.Teal
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Figma "노래 연습 - 노래 연습전 / 연습중" + 고도화.
 * 위: 곡명·가수, 탐색 가능한 진행 바(실제 반주 길이), 키 조절(원키 → 바뀐 키 이름, 렌더 대기 표시),
 *     시작 전 설정(이어폰·전주 건너뛰기·지연 보정·진동)과 마이크 미리보기.
 * 가운데: 연습 중 실시간 점수판 + 방금 음표 판정 칩, 접을 수 있는 러너웨이(피아노롤), 가사 줄 목록(음절 실제 시각으로 채움).
 *        가사 줄을 누르면(정지 상태) 그 줄이 구간 반복 범위가 된다.
 * 아래: 흰 시트 — 처음부터 · 시작/일시정지/이어서 · 닫기(또는 마치기). 종료 후 요약·녹음 다시 듣기·정밀 리포트.
 */
@Composable
fun PracticeScreen(songId: String, onBack: () -> Unit, onOpenResult: (String) -> Unit) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: PracticeViewModel = viewModel(
        key = "practice-$songId",
        factory = viewModelFactory { initializer { PracticeViewModel(context.applicationContext, songId, container.songs, container.engine) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.start() }
    fun startWithPermission() { if (hasMic()) vm.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    // 시작 전 마이크 미리보기(권한이 이미 있을 때만; 없으면 시작 버튼에서 요청)
    LaunchedEffect(state.phase) { if (state.phase == Phase.READY && hasMic()) vm.startMicPreview() }

    val running = state.isRunning

    Box(Modifier.fillMaxSize().background(Color.White)) {
        when (state.phase) {
            Phase.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Purple)
            Phase.ERROR -> RetryBlock(state.error ?: "오류", onBack)
            else -> Column(Modifier.fillMaxSize()) {
                Surface(color = Color.White, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(14.dp))
                        Text(state.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Purple, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(state.artist, fontSize = 15.sp, color = Grey)
                        Spacer(Modifier.height(14.dp))
                        SeekBar(state.nowSec, state.durationSec, enabled = state.phase == Phase.READY || state.phase == Phase.PAUSED || state.phase == Phase.FINISHED, onSeek = vm::seekTo)
                        KeyRow(state.keyShift, state.keyBusy, state.keyName, state.shiftedKeyName, enabled = !running, onChange = vm::changeKey)
                        if (state.phase == Phase.READY) {
                            Spacer(Modifier.height(8.dp))
                            SettingsRow(state, vm)
                            Spacer(Modifier.height(8.dp))
                            MicPreview(state.micRms, state.micNote)
                        }
                        state.loop?.let { loop ->
                            Spacer(Modifier.height(6.dp))
                            LoopChip(loop, state.loopCount, onClear = vm::clearLoop)
                        }
                    }
                }
                if (running || state.phase == Phase.PAUSED) {
                    ScoreBoard(state.totals, state.lastJudged, state.lastJudgedAtMs, state.calibrationMs, state.autoCalibrate, state.showRunway, onToggleRunway = vm::toggleRunway)
                    AnimatedVisibility(visible = state.showRunway) {
                        Runway(vm.notes, vm.frames, vm.lyricLines, vm.beatGrid, vm.meter, state.nowSec, state.keyShift, Modifier.fillMaxWidth().height(150.dp))
                    }
                }
                LyricsList(
                    vm.lyricLines, state.nowSec, running, loopLine = state.loop?.lineIndex,
                    onLineTap = { index -> if (!running) vm.toggleLoop(index) },
                    Modifier.weight(1f),
                )
            }
        }

        if (state.phase == Phase.COUNTDOWN) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                if (state.buffering) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Purple)
                        Spacer(Modifier.height(12.dp))
                        Text("반주를 준비하고 있어요", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                } else if (state.countdown > 0) {
                    Text("${state.countdown}", fontSize = 96.sp, fontWeight = FontWeight.Black, color = Purple)
                }
            }
        }

        if (state.phase != Phase.LOADING && state.phase != Phase.ERROR) {
            BottomSheet(state, vm, onStart = ::startWithPermission, onBack = onBack, onOpenResult = onOpenResult)
        }
    }
}

// ---------------------------------------------------------------------------
// 상단: 탐색 바 · 키 · 설정 · 마이크 미리보기 · 반복 칩
// ---------------------------------------------------------------------------

@Composable
private fun SeekBar(positionSec: Double, durationSec: Double, enabled: Boolean, onSeek: (Double) -> Unit) {
    var dragging by remember { mutableStateOf<Float?>(null) }
    val duration = durationSec.coerceAtLeast(1.0)
    val shown = dragging ?: (positionSec / duration).toFloat().coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(formatSec(shown * duration), fontSize = 13.sp, color = Ink, modifier = Modifier.width(40.dp))
        Slider(
            value = shown,
            onValueChange = { if (enabled) dragging = it },
            onValueChangeFinished = { dragging?.let { onSeek(it * duration) }; dragging = null },
            enabled = enabled,
            modifier = Modifier.weight(1f).height(28.dp),
            colors = SliderDefaults.colors(thumbColor = Purple, activeTrackColor = Purple, inactiveTrackColor = GreyLight, disabledThumbColor = Purple, disabledActiveTrackColor = Purple, disabledInactiveTrackColor = GreyLight),
        )
        Text(formatSec(durationSec), fontSize = 13.sp, color = Ink, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

/** 반주 키 조절. 렌더 중이면 스피너, 바뀐 키 이름을 함께 보여 준다. */
@Composable
private fun KeyRow(keyShift: Int, busy: Boolean, keyName: String?, shiftedKeyName: String?, enabled: Boolean, onChange: (Int) -> Unit) {
    val canUse = enabled && !busy
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("−", fontSize = 22.sp, color = if (canUse && keyShift > ScoringConstants.KEY_SHIFT_MIN) Purple else GreyLight, modifier = Modifier.clickable(enabled = canUse) { onChange(keyShift - 1) }.padding(horizontal = 8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (keyShift == 0) "원키" else (if (keyShift > 0) "키 +$keyShift" else "키 $keyShift"), fontSize = 14.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            val label = when {
                busy -> "반주 렌더 중… (첫 변경은 10초 정도)"
                keyName == null -> null
                keyShift == 0 -> keyName
                else -> "$keyName → $shiftedKeyName"
            }
            if (label != null) Row(verticalAlignment = Alignment.CenterVertically) {
                if (busy) { CircularProgressIndicator(Modifier.size(11.dp), color = Purple, strokeWidth = 2.dp); Spacer(Modifier.width(6.dp)) }
                Text(label, fontSize = 11.sp, color = if (busy) Purple else Grey)
            }
        }
        Text("+", fontSize = 22.sp, color = if (canUse && keyShift < ScoringConstants.KEY_SHIFT_MAX) Purple else GreyLight, modifier = Modifier.clickable(enabled = canUse) { onChange(keyShift + 1) }.padding(horizontal = 8.dp))
    }
}

@Composable
private fun SettingsRow(state: PracticeViewModel.UiState, vm: PracticeViewModel) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally)) {
        ToggleChip("이어폰", state.headphones, vm::setHeadphones)
        ToggleChip("전주 건너뛰기", state.skipIntro, vm::setSkipIntro)
        ToggleChip("지연 보정", state.autoCalibrate, vm::setAutoCalibrate)
        ToggleChip("진동", state.haptics, vm::setHaptics)
    }
}

@Composable
private fun ToggleChip(label: String, on: Boolean, onChange: (Boolean) -> Unit) {
    Surface(onClick = { onChange(!on) }, shape = RoundedCornerShape(50), color = if (on) LavenderDeep else GreyBg) {
        Text(label, fontSize = 12.sp, color = if (on) Purple else Grey, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 11.dp, vertical = 6.dp))
    }
}

/** 시작 전 마이크 입력 레벨 + 감지 음이름. 마이크가 잡히는지 미리 확인하는 용도. */
@Composable
private fun MicPreview(rms: Double, note: String?) {
    Row(Modifier.fillMaxWidth().background(Lavender.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Mic, null, Modifier.size(16.dp), tint = Purple)
        Spacer(Modifier.width(8.dp))
        WaveformBars((rms / 0.12).toFloat().coerceIn(0f, 1f), Modifier.weight(1f).height(26.dp), bars = 40)
        Spacer(Modifier.width(10.dp))
        Text(note ?: "—", fontSize = 13.sp, color = if (note != null) Purple else Grey, fontWeight = FontWeight.SemiBold, modifier = Modifier.width(36.dp), textAlign = TextAlign.End)
    }
}

@Composable
private fun LoopChip(loop: PracticeViewModel.Loop, count: Int, onClear: () -> Unit) {
    Row(Modifier.background(LavenderDeep, RoundedCornerShape(50)).padding(start = 12.dp, end = 6.dp, top = 4.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Outlined.Repeat, null, Modifier.size(15.dp), tint = Purple)
        Spacer(Modifier.width(6.dp))
        Text("구간 반복 ${formatSec(loop.start)}~${formatSec(loop.end)}" + if (count > 0) " · ${count}회" else "", fontSize = 12.sp, color = Purple, fontWeight = FontWeight.SemiBold)
        IconButton(onClick = onClear, modifier = Modifier.size(24.dp)) { Icon(Icons.Filled.Close, "반복 해제", Modifier.size(14.dp), tint = Purple) }
    }
}

// ---------------------------------------------------------------------------
// 연습 중: 점수판 + 최근 판정 칩
// ---------------------------------------------------------------------------

@Composable
private fun ScoreBoard(totals: Totals?, lastJudged: LiveNote?, lastJudgedAtMs: Long, calibrationMs: Int, autoCalibrate: Boolean, showRunway: Boolean, onToggleRunway: () -> Unit) {
    Row(Modifier.fillMaxWidth().background(GreyBg).padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.width(74.dp)) {
            Text("${totals?.overall?.roundToInt() ?: 0}", fontSize = 26.sp, fontWeight = FontWeight.Black, color = Ink, lineHeight = 28.sp)
            Text("등급 ${totals?.grade ?: "-"}", fontSize = 11.sp, color = Grey)
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat("음정", "${totals?.pitch?.roundToInt() ?: 0}")
                Stat("박자", "${totals?.timing?.roundToInt() ?: 0}")
                Stat("정확", "${totals?.good ?: 0}", Teal)
                Stat("놓침", "${totals?.missed ?: 0}", Red)
                if (autoCalibrate && calibrationMs != 0) Stat("보정", "${calibrationMs}ms")
            }
            JudgementChip(lastJudged, lastJudgedAtMs)
        }
        IconButton(onClick = onToggleRunway, modifier = Modifier.size(32.dp)) {
            Icon(Icons.AutoMirrored.Outlined.ShowChart, if (showRunway) "음정 레인 숨기기" else "음정 레인 보기", tint = if (showRunway) Purple else Grey)
        }
    }
}

@Composable
private fun Stat(label: String, value: String, color: Color = InkSoft) {
    Row(verticalAlignment = Alignment.Bottom) {
        Text(label, fontSize = 10.sp, color = Grey)
        Spacer(Modifier.width(3.dp))
        Text(value, fontSize = 13.sp, color = color, fontWeight = FontWeight.SemiBold)
    }
}

/** 방금 판정된 음표: 판정 라벨 + 센트 + 빠름/늦음. 1.5초 지나면 흐려진다. */
@Composable
private fun JudgementChip(note: LiveNote?, atMs: Long) {
    if (note == null || !note.status.judged) { Text("첫 음표를 기다리고 있어요", fontSize = 12.sp, color = Grey); return }
    val fresh = System.currentTimeMillis() - atMs < 1500
    val status = note.status.toPitchStatus()!!
    val color = Color(status.argb)
    val parts = buildList {
        add(status.label)
        note.cents?.takeIf { note.status != LiveStatus.MISSED }?.let { add(if (it > 0) "+${it}¢" else "${it}¢") }
        note.deviation?.let { d ->
            when {
                d > ScoringConstants.TIMING_GOOD_SECONDS -> add("늦음 ${(d * 1000).roundToInt()}ms")
                d < -ScoringConstants.TIMING_GOOD_SECONDS -> add("빠름 ${(abs(d) * 1000).roundToInt()}ms")
                else -> add("박자 정확")
            }
        }
        note.lyric?.let { add("‘$it’") }
    }
    Text(parts.joinToString(" · "), fontSize = 12.sp, color = if (fresh) color else color.copy(alpha = 0.55f), fontWeight = FontWeight.SemiBold)
}

// ---------------------------------------------------------------------------
// 가사 목록 (음절 실제 시각으로 채움, 줄 탭 = 구간 반복)
// ---------------------------------------------------------------------------

@Composable
private fun LyricsList(lines: List<LyricLine>, nowSec: Double, running: Boolean, loopLine: Int?, onLineTap: (Int) -> Unit, modifier: Modifier) {
    val listState = rememberLazyListState()
    val currentIndex = lines.indexOfLast { it.start <= nowSec }
        .let { i -> if (i < 0) 0 else if (i + 1 < lines.size && nowSec >= lines[i].end) i + 1 else i }
    LaunchedEffect(currentIndex, running) {
        if (running && lines.isNotEmpty()) listState.animateScrollToItem(currentIndex, scrollOffset = -160)
    }
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) { Text("가사 타이밍 정보가 없는 곡이에요. 음정 레인을 보며 불러 보세요.", fontSize = 14.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp)) }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(top = 24.dp, bottom = 300.dp)) {
        itemsIndexed(lines) { i, line ->
            val isCurrent = i == currentIndex && running
            val isLoop = i == loopLine
            Box(
                Modifier.fillMaxWidth()
                    .background(if (isCurrent) Lavender else if (isLoop) LavenderDeep.copy(alpha = 0.35f) else Color.Transparent)
                    .clickable(enabled = !running) { onLineTap(i) }
                    .padding(vertical = if (isCurrent) 14.dp else 8.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent) {
                    Text(
                        buildAnnotatedString {
                            line.tokens.forEach { tok ->
                                val sung = nowSec >= tok.note.lyricAt
                                val judged = tok.note.status.judged
                                val color = when {
                                    judged -> Color(tok.note.status.toPitchStatus()!!.argb)
                                    sung -> Purple
                                    else -> Ink
                                }
                                if (tok.space) append(" ")
                                withStyle(SpanStyle(color = color, fontWeight = if (sung) FontWeight.Bold else FontWeight.Medium)) { append(tok.note.lyric ?: "") }
                            }
                        },
                        fontSize = 24.sp, textAlign = TextAlign.Center,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (isLoop) { Icon(Icons.Outlined.Repeat, null, Modifier.size(14.dp), tint = Purple); Spacer(Modifier.width(6.dp)) }
                        Text(line.text, fontSize = 19.sp, color = if (isLoop) Purple else Grey, textAlign = TextAlign.Center)
                    }
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// 하단 시트
// ---------------------------------------------------------------------------

@Composable
private fun BottomSheet(state: PracticeViewModel.UiState, vm: PracticeViewModel, onStart: () -> Unit, onBack: () -> Unit, onOpenResult: (String) -> Unit) {
    val running = state.isRunning
    Box(Modifier.fillMaxSize()) {
        Surface(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = Color.White, shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(top = 18.dp, bottom = 28.dp)) {
                if (state.phase == Phase.PAUSED) {
                    Text("일시정지 · 진행 바를 옮기거나 가사 줄을 눌러 구간 반복을 정할 수 있어요", fontSize = 12.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp))
                    Spacer(Modifier.height(6.dp))
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = vm::restart) { Icon(Icons.Filled.Replay, "처음부터", Modifier.size(32.dp), tint = Ink) }
                        Text("처음부터", fontSize = 11.sp, color = Grey)
                    }
                    Surface(
                        onClick = { when (state.phase) { Phase.PAUSED -> vm.resume(); Phase.RUNNING, Phase.COUNTDOWN -> vm.pause(); else -> onStart() } },
                        shape = CircleShape,
                        color = when (state.phase) { Phase.RUNNING, Phase.COUNTDOWN -> GreyLight; else -> Purple },
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(104.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val (icon, desc) = when (state.phase) {
                                Phase.RUNNING, Phase.COUNTDOWN -> Icons.Filled.Pause to "일시정지"
                                Phase.PAUSED -> Icons.Filled.PlayArrow to "이어서"
                                Phase.FINISHED -> Icons.Filled.Replay to "다시 부르기"
                                else -> Icons.Filled.Mic to "시작"
                            }
                            Icon(icon, desc, Modifier.size(46.dp), tint = if (running) Grey else Color.White)
                        }
                    }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        if (state.phase == Phase.PAUSED) {
                            IconButton(onClick = vm::finishEarly) { Icon(Icons.Filled.Stop, "여기까지 마치기", Modifier.size(32.dp), tint = Ink) }
                            Text("마치기", fontSize = 11.sp, color = Grey)
                        } else {
                            IconButton(onClick = { vm.pause(); onBack() }) { Icon(Icons.Filled.Close, "닫기", Modifier.size(32.dp), tint = Ink) }
                            Text("닫기", fontSize = 11.sp, color = Grey)
                        }
                    }
                }
                if (state.phase == Phase.FINISHED) FinishedPanel(state, vm, onOpenResult)
                state.error?.let { Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) }
            }
        }
    }
}

@Composable
private fun FinishedPanel(state: PracticeViewModel.UiState, vm: PracticeViewModel, onOpenResult: (String) -> Unit) {
    Spacer(Modifier.height(14.dp))
    Column(Modifier.padding(horizontal = 24.dp)) {
        state.totals?.let { t ->
            Row(Modifier.fillMaxWidth().background(GreyBg, RoundedCornerShape(12.dp)).padding(12.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                Summary("라이브 점수", "${t.overall.roundToInt()}", "등급 ${t.grade}")
                Summary("음정", "${t.pitch.roundToInt()}", "정확 ${t.good} · 높게 ${t.sharp} · 낮게 ${t.flat}")
                Summary("박자", "${t.timing.roundToInt()}", "빠름 ${t.early} · 늦음 ${t.late}")
                Summary("판정", "${t.judged}", "놓침 ${t.missed}" + if (state.calibrationMs != 0) " · 보정 ${state.calibrationMs}ms" else "")
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinePurpleButton(if (state.takePlaying) "정지" else "녹음 다시 듣기", { if (state.takePlaying) vm.stopTake() else vm.playTake() }, Modifier.weight(1f), height = 46.dp)
            when (state.report.phase) {
                ReportPhase.NONE -> PrimaryButton("정밀 리포트 만들기", vm::makePreciseReport, Modifier.weight(1.3f), height = 46.dp)
                ReportPhase.DONE -> PrimaryButton("정밀 리포트 보기", { state.report.jobId?.let(onOpenResult) }, Modifier.weight(1.3f), height = 46.dp)
                ReportPhase.ERROR -> OutlinePurpleButton("리포트 다시 시도", vm::makePreciseReport, Modifier.weight(1.3f), height = 46.dp)
                else -> Box(Modifier.weight(1.3f).height(46.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = Purple, strokeWidth = 2.dp) }
            }
        }
        when (state.report.phase) {
            ReportPhase.UPLOADING, ReportPhase.ANALYZING -> Column(Modifier.padding(top = 8.dp)) {
                Text("${state.report.message}  ${state.report.progress}%", fontSize = 12.sp, color = Grey)
                LinearProgressIndicator(progress = { state.report.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Purple, trackColor = Lavender)
            }
            ReportPhase.ERROR -> Text(state.report.message, color = Red, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
            else -> {}
        }
        if (!state.headphones) Text("스피커로 들으며 불렀다면 반주 분리 때문에 정밀 분석이 조금 더 걸려요.", fontSize = 11.sp, color = Grey, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
private fun Summary(label: String, value: String, caption: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 10.sp, color = Grey)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink)
        Text(caption, fontSize = 9.sp, color = Grey, textAlign = TextAlign.Center)
    }
}

@Suppress("unused")
private val unusedPalette = listOf(GreenChipBg, GreenChipText, ScoringUi.COLOR_INK)
