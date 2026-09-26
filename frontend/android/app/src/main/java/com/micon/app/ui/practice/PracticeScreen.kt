package com.micon.app.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.micon.app.core.auth.TokenManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.KaraokeNames
import com.micon.app.scoring.LyricSheet
import com.micon.app.scoring.LyricTiming
import com.micon.app.scoring.ScoringConstants
import com.micon.app.scoring.SectionMap
import com.micon.app.scoring.ScoringUi
import com.micon.app.scoring.Totals
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.formatSec
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.practice.PracticeViewModel.Phase
import com.micon.app.ui.practice.PracticeViewModel.SavePhase
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
 *     구간 칩(1절·2절·하이라이트) — 누르면 그 구간이 시작하는 마디의 한 마디 앞(첫 박)으로 이동(재생 중에도, 카운트인 후).
 * 가운데: 연습 중 실시간 점수판 + 방금 음표 판정 칩, 접을 수 있는 러너웨이(피아노롤), 가사 줄 목록(LRC 줄 텍스트, 글자는 음절 시각으로 채움).
 *        가사 줄을 누르면(정지 상태) 그 줄이 구간 반복 범위가 된다. 간주 건너뛰기 버튼은 항상 보이며 간주 밖에서는 안내한다.
 * 아래: 흰 시트 — 처음부터 · 시작/일시정지/이어서 · 닫기(또는 마치기). 종료 후 요약·녹음 다시 듣기·연습 리포트(백엔드 /practices 에 저장된 기록).
 */
@Composable
fun PracticeScreen(
    songId: String,
    onBack: () -> Unit,
    onOpenResult: (Long) -> Unit,
    onReturnToPractice: () -> Unit = onBack,
    initialKey: Int = 0,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: PracticeViewModel = viewModel(
        key = "practice-$songId-$initialKey",
        factory = viewModelFactory { initializer { PracticeViewModel(context.applicationContext, songId, container.songs, container.mediaHttp, container.practices, container.practiceRecordings, sessionSnapshot = TokenManager::snapshot, isLoggedIn = TokenManager::isLoggedIn, initialKey = initialKey) } },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    com.micon.app.ui.common.PlaybackLifecycleEffect(vm, onExit = vm::stopAudioForExit)
    fun hasMic() = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.start() }
    fun startWithPermission() { if (hasMic()) vm.start() else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) }
    // 시작 전 마이크 미리보기(권한이 이미 있을 때만; 없으면 시작 버튼에서 요청)
    LaunchedEffect(state.phase) { if (state.phase == Phase.READY && hasMic()) vm.startMicPreview() }

    val running = state.isRunning
    var finishedExpanded by remember { mutableStateOf(true) }
    LaunchedEffect(state.phase) { if (state.phase == Phase.FINISHED) finishedExpanded = true }

    Box(Modifier.fillMaxSize().background(Color(0xFFF7F5FC))) {
        when (state.phase) {
            Phase.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Purple)
            Phase.ERROR -> RetryBlock(state.error ?: "오류", onBack)
            else -> Column(Modifier.fillMaxSize()) {
                // 재생 중에는 노래방처럼 필요한 것만: 작은 제목·진행 바 → 점수판 → 음표 레인 → 현재 가사(+다음 줄).
                // 키·구간 칩·설정은 멈췄을 때(READY/PAUSED/FINISHED)만 보인다.
                Surface(color = Color(0xFFF7F5FC)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = if (running) 8.dp else 14.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        if (running) {
                            CompactSongTitle(state.title, state.artist)
                            SeekBar(state.nowSec, state.durationSec, enabled = false, onSeek = vm::seekTo)
                            KeyRow(
                                state.keyShift,
                                state.keyName,
                                state.shiftedKeyName,
                                enabled = !state.keySwitching,
                                onChange = vm::changeKey,
                            )
                        } else if (state.phase == Phase.PAUSED) {
                            // 멈췄을 때도 재생 중과 같은 구성으로 둔다 — 접이식 "키·시작 위치" 패널과
                            // 구간 칩은 없앴다(시작 위치는 연습 시작 전 화면에서만 고른다).
                            // 탐색 바는 멈췄을 때만 열어 위치를 옮길 수 있게 한다.
                            CompactSongTitle(state.title, state.artist)
                            SeekBar(state.nowSec, state.durationSec, enabled = true, onSeek = vm::seekTo)
                            KeyRow(
                                state.keyShift,
                                state.keyName,
                                state.shiftedKeyName,
                                enabled = !state.keySwitching,
                                onChange = vm::changeKey,
                            )
                        } else {
                        Spacer(Modifier.height(14.dp))
                        Text(state.title, fontSize = 21.sp, fontWeight = FontWeight.Bold, color = Purple, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(4.dp))
                        Text(state.artist, fontSize = 15.sp, color = Grey)
                        Spacer(Modifier.height(14.dp))
                        SeekBar(state.nowSec, state.durationSec, enabled = state.phase == Phase.READY || state.phase == Phase.FINISHED, onSeek = vm::seekTo)
                        Spacer(Modifier.height(20.dp))
                        KeyRow(
                            state.keyShift,
                            state.keyName,
                            state.shiftedKeyName,
                            enabled = state.phase == Phase.READY && !state.keySwitching,
                            onChange = vm::changeKey,
                        )
                        Spacer(Modifier.height(16.dp))
                        SectionChips(
                            vm.sections,
                            positionSec = state.nowSec,
                            enabled = state.phase != Phase.COUNTDOWN,
                            onBeginning = vm::jumpToBeginning,
                            onSection = vm::jumpToSection,
                        )
                        Spacer(Modifier.height(28.dp))
                        }
                    }
                }
                if (running || state.phase == Phase.PAUSED) {
                    ScoreBoard(
                        state.totals, state.lastJudged, state.lastJudgedAtMs, state.calibrationMs, state.autoCalibrate, state.showRunway, onToggleRunway = vm::toggleRunway,
                        onSkipInterlude = {
                            val message = when (vm.skipInterlude()) {
                                SkipInterludeResult.SKIPPED -> null
                                SkipInterludeResult.NOT_INTERLUDE -> "간주 구간이 아닙니다."
                                SkipInterludeResult.TOO_LATE -> "곧 노래가 시작돼 건너뛸 구간이 없어요."
                                SkipInterludeResult.NOT_READY -> "카운트다운이 끝나면 다시 눌러 주세요."
                            }
                            message?.let { Toast.makeText(context, it, Toast.LENGTH_SHORT).show() }
                        },
                    )
                    // 레인(퍼펙트스코어식 음표 레인)은 고정 180dp 였다. 작은 기기(S10 360×760)에서는
                    // 그 높이 때문에 아래 가사가 0에 가깝게 눌려 잘렸다. 이제 레인과 가사가 남는 높이를
                    // 1 : 1.4 로 나눠 갖는다 — 화면이 좁을수록 레인이 먼저 양보한다.
                    AnimatedVisibility(
                        visible = state.showRunway,
                        modifier = if (state.showRunway) Modifier.weight(1f) else Modifier,
                    ) {
                        Runway(
                            vm.notes, vm.displayBars, vm.frames, vm.beatGrid, vm.meter, state.nowSec, state.keyShift,
                            Modifier.fillMaxWidth().heightIn(max = 180.dp).padding(vertical = 8.dp),
                        )
                    }
                }
                if (running) {
                    KaraokePanel(vm.sheet, state.nowSec, Modifier.weight(1.4f), compactPracticeStyle = true)
                } else {
                    LyricsList(
                        vm.sheet, state.nowSec, running,
                        lineSelectionEnabled = !running,
                        compactPausedStyle = state.phase == Phase.PAUSED,
                        onLineTap = { index -> vm.sheet.lines.getOrNull(index)?.let { vm.seekTo(it.start) } },
                        Modifier.weight(1f),
                    )
                }
                // 하단 시트(오버레이)에 가리지 않도록 비워 두는 높이
                Spacer(Modifier.height(if (state.phase == Phase.FINISHED && finishedExpanded) 350.dp else if (running) 168.dp else 190.dp))
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

        if (state.keySwitching) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.28f)), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.background(Color.White, RoundedCornerShape(16.dp)).padding(horizontal = 28.dp, vertical = 22.dp)) {
                    CircularProgressIndicator(color = Purple, modifier = Modifier.size(32.dp), strokeWidth = 3.dp)
                    Spacer(Modifier.height(10.dp))
                    Text("키 변경 중", color = Ink, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                    Text("현재 위치에서 곧 이어집니다", color = Grey, fontSize = 12.sp)
                }
            }
        }

        if (state.phase != Phase.LOADING && state.phase != Phase.ERROR) {
            BottomSheet(state, vm, finishedExpanded, onFinishedExpandedChange = { finishedExpanded = it }, onStart = ::startWithPermission,
                onBack = { vm.stopAudioForExit(); onBack() },
                onReturnToPractice = { vm.stopAudioForExit(); onReturnToPractice() },
                onOpenResult = { id -> vm.stopAudioForExit(); onOpenResult(id) })
        }
    }
}

// ---------------------------------------------------------------------------
// 상단: 탐색 바 · 키 · 설정 · 마이크 미리보기 · 반복 칩
// ---------------------------------------------------------------------------

@Composable
private fun CompactSongTitle(title: String, artist: String) {
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = Purple, fontWeight = FontWeight.SemiBold)) { append(title) }
            withStyle(SpanStyle(color = Grey, fontWeight = FontWeight.Normal)) { append("  ·  $artist") }
        },
        fontSize = 14.sp,
        textAlign = TextAlign.Center,
        maxLines = 1,
    )
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
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
            modifier = Modifier.weight(1f).height(24.dp),
            colors = SliderDefaults.colors(
                thumbColor = Purple.copy(alpha = 0.72f),
                activeTrackColor = Purple.copy(alpha = 0.48f),
                inactiveTrackColor = LavenderDeep.copy(alpha = 0.55f),
                disabledThumbColor = Purple.copy(alpha = 0.6f),
                disabledActiveTrackColor = Purple.copy(alpha = 0.4f),
                disabledInactiveTrackColor = LavenderDeep.copy(alpha = 0.45f),
            ),
            thumb = {
                Box(
                    Modifier.width(3.dp).height(14.dp)
                        .background(Purple.copy(alpha = 0.72f), RoundedCornerShape(50)),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(2.dp),
                    colors = SliderDefaults.colors(
                        activeTrackColor = Purple.copy(alpha = 0.48f),
                        inactiveTrackColor = LavenderDeep.copy(alpha = 0.55f),
                        disabledActiveTrackColor = Purple.copy(alpha = 0.4f),
                        disabledInactiveTrackColor = LavenderDeep.copy(alpha = 0.45f),
                    ),
                )
            },
        )
        Text(formatSec(durationSec), fontSize = 13.sp, color = Ink, modifier = Modifier.width(40.dp), textAlign = TextAlign.End)
    }
}

/** 키별로 렌더된 S3 반주를 선택한다. */
@Composable
private fun KeyRow(keyShift: Int, keyName: String?, shiftedKeyName: String?, enabled: Boolean, onChange: (Int) -> Unit) {
    val canUse = enabled
    Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("−", fontSize = 22.sp, color = if (canUse && keyShift > ScoringConstants.KEY_SHIFT_MIN) Purple else GreyLight, modifier = Modifier.clickable(enabled = canUse) { onChange(keyShift - 1) }.padding(horizontal = 8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(NoteNames.keyShiftText(keyShift), fontSize = 14.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            // 키별 S3 반주로 전환하는 동안에는 상위에서 입력을 막는다.
            val label = when {
                keyName == null -> null
                keyShift == 0 -> keyName
                else -> "$keyName → $shiftedKeyName"
            }
            if (label != null) Text(label, fontSize = 11.sp, color = Grey)
        }
        Text("+", fontSize = 22.sp, color = if (canUse && keyShift < ScoringConstants.KEY_SHIFT_MAX) Purple else GreyLight, modifier = Modifier.clickable(enabled = canUse) { onChange(keyShift + 1) }.padding(horizontal = 8.dp))
    }
}

/** 시작 위치 칩: 0초·1절·2절. 선택한 위치는 실제 연습 시작점으로 사용한다. */
@Composable
private fun SectionChips(
    sections: SectionMap,
    positionSec: Double,
    enabled: Boolean,
    onBeginning: () -> Unit,
    onSection: (Int) -> Unit,
) {
    fun selected(target: Double) = abs(positionSec - target) < 0.5
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        StartPositionChip("처음부터", selected(0.0), enabled, onBeginning)
        sections.sections.forEach { sec ->
            StartPositionChip("${sec.label}부터", selected(sec.jumpStart), enabled) { onSection(sec.index) }
        }
    }
}

@Composable
private fun StartPositionChip(label: String, selected: Boolean, enabled: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        enabled = enabled,
        shape = RoundedCornerShape(50),
        color = if (selected) Purple else Color.White,
        border = BorderStroke(1.dp, if (selected) Purple else Color(0xFFE4E2EE)),
    ) {
        Text(
            label,
            fontSize = 11.5.sp,
            color = if (selected) Color.White else Ink,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// 연습 중: 점수판 + 최근 판정 칩
// ---------------------------------------------------------------------------

@Composable
private fun ScoreBoard(
    totals: Totals?, lastJudged: LiveNote?, lastJudgedAtMs: Long, calibrationMs: Int, autoCalibrate: Boolean, showRunway: Boolean, onToggleRunway: () -> Unit,
    onSkipInterlude: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().background(Color(0xFFF4F2FC)).padding(horizontal = 32.dp, vertical = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(horizontalAlignment = Alignment.Start) {
            Text("score", fontSize = 12.sp, color = Grey, lineHeight = 13.sp, fontWeight = FontWeight.SemiBold)
            Text("${totals?.overall?.roundToInt() ?: 0}", fontSize = 44.sp, fontWeight = FontWeight.Black, color = Ink, lineHeight = 44.sp)
        }
        Spacer(Modifier.width(12.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                JudgementLabel(lastJudged, lastJudgedAtMs)
                Surface(
                    onClick = onSkipInterlude,
                    modifier = Modifier.semantics { contentDescription = "간주 건너뛰기" },
                    shape = RoundedCornerShape(50),
                    color = Purple,
                ) {
                    Row(Modifier.padding(horizontal = 9.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.SkipNext, null, Modifier.size(14.dp), tint = Color.White)
                        Spacer(Modifier.width(3.dp))
                        Text("간주 건너뛰기", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
        Spacer(Modifier.width(16.dp))
        Surface(
            onClick = onToggleRunway,
            modifier = Modifier.width(40.dp).height(24.dp).semantics {
                contentDescription = if (showRunway) "악보 접기" else "악보 펼치기"
            },
            shape = RoundedCornerShape(50),
            color = if (showRunway) LavenderDeep else Color.White,
            border = BorderStroke(1.dp, if (showRunway) Purple.copy(alpha = 0.45f) else GreyLight),
        ) {
            Box(
                Modifier.fillMaxSize().padding(3.dp),
                contentAlignment = if (showRunway) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(16.dp).background(if (showRunway) Purple else Grey, CircleShape))
            }
        }
    }
}

/** 방금 판정된 음표의 음정 상태를 표시하고, 옥타브 오류면 목표음과 실제음을 함께 보여 준다. */
@Composable
private fun JudgementLabel(note: LiveNote?, atMs: Long) {
    if (note == null || !note.status.judged) { Text("첫 음표를 기다리고 있어요", fontSize = 12.sp, color = Grey); return }
    val fresh = System.currentTimeMillis() - atMs < 1500
    val status = note.status.toPitchStatus()!!
    val color = Color(status.argb)
    val label = when (note.status) {
        LiveStatus.GOOD -> "Perfect"
        LiveStatus.SHARP -> "High"
        LiveStatus.FLAT -> "Low"
        LiveStatus.WRONG -> "Bad"
        LiveStatus.MISSED -> "Miss"
        else -> ""
    }
    val targetMidi = note.judgedTargetMidi
    val sungMidi = note.sungMidi
    val octaveFeedback = if (targetMidi != null && sungMidi != null) {
        val difference = sungMidi - targetMidi
        val octaves = (difference / 12.0).roundToInt()
        if (octaves != 0 && abs(difference - octaves * 12.0) <= 1.0) {
            "${KaraokeNames.koreanShort(targetMidi)} → ${KaraokeNames.koreanShort(sungMidi.roundToInt())} · ${abs(octaves)}옥타브 ${if (octaves < 0) "낮게" else "높게"}"
        } else null
    } else null
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, fontSize = 14.sp, color = if (fresh) color else color.copy(alpha = 0.55f), fontWeight = FontWeight.Bold)
        octaveFeedback?.let {
            Text(it, fontSize = 9.sp, color = if (fresh) color else color.copy(alpha = 0.55f), maxLines = 1)
        }
    }
}

// 재생 중 가사(KaraokePanel)는 불러서 분석하기와 공유 → PracticeLyrics.kt

// ---------------------------------------------------------------------------
// 멈춤 상태 가사 목록 (LRC 줄 텍스트 · 줄 탭 = 구간 반복)
// ---------------------------------------------------------------------------

@Composable
private fun LyricsList(
    sheet: LyricSheet,
    nowSec: Double,
    running: Boolean,
    lineSelectionEnabled: Boolean,
    compactPausedStyle: Boolean = false,
    onLineTap: (Int) -> Unit,
    modifier: Modifier,
) {
    val lines = sheet.lines
    val listState = rememberLazyListState()
    val currentIndex = sheet.currentIndex(nowSec) ?: 0
    // 재생 중과 일시정지 상태 모두 같은 현재 소절을 사용한다. 다음 소절의 시작 시각을
    // 다시 찾으면 정지 순간 강조 표시가 한 줄 앞으로 이동한다.
    val selectedStartIndex = currentIndex
    LaunchedEffect(selectedStartIndex) {
        if (lines.isNotEmpty()) listState.animateScrollToItem(selectedStartIndex, scrollOffset = -60)
    }
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("이 곡은 가사 데이터가 없어요. 음정 레인을 보며 불러 보세요.", fontSize = 14.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(top = 12.dp, bottom = 24.dp)) {
        if (sheet.timing == LyricTiming.APPROX) item {
            Text(
                "가사 싱크 정보가 없는 곡이라 원곡 음표 타이밍으로 가사 위치를 추정했어요. 줄 전환이 실제와 조금 다를 수 있어요.",
                fontSize = 11.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 6.dp),
            )
        }
        itemsIndexed(lines) { i, line ->
            val isCurrent = i == currentIndex && running
            val isSelectedStart = i == selectedStartIndex && !running
            val isHighlighted = isCurrent || isSelectedStart
            Box(
                Modifier.fillMaxWidth()
                    .padding(horizontal = if (isHighlighted && compactPausedStyle) 20.dp else if (isSelectedStart) 20.dp else 0.dp)
                    .background(
                        when {
                            isSelectedStart -> Purple.copy(alpha = 0.18f)
                            isCurrent -> Lavender
                            else -> Color.Transparent
                        },
                        if (isSelectedStart) RoundedCornerShape(14.dp) else RoundedCornerShape(0.dp),
                    )
                    .clickable(enabled = lineSelectionEnabled) { onLineTap(i) }
                    .padding(vertical = if (isHighlighted && compactPausedStyle) 9.dp else if (isHighlighted) 16.dp else 9.dp, horizontal = 16.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isHighlighted && compactPausedStyle || isCurrent) {
                    // 글자 단위 채움은 걷어냈다 — 줄 전체를 한 덩이로 강조한다(KaraokePanel 과 같은 규칙)
                    Text(
                        line.text,
                        fontSize = if (compactPausedStyle) 22.sp else 28.sp,
                        lineHeight = if (compactPausedStyle) 30.sp else 38.sp,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        textAlign = TextAlign.Center,
                    )
                } else {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        val isNext = i == currentIndex + 1
                        val isPrevious = i == currentIndex - 1
                        Text(
                            line.text,
                            fontSize = if (compactPausedStyle) {
                                when { isNext -> 17.sp; isPrevious -> 16.sp; else -> 16.sp }
                            } else if (isNext) 22.sp else 19.sp,
                            lineHeight = if (compactPausedStyle) 24.sp else if (isNext) 30.sp else 26.sp,
                            color = when {
                                isSelectedStart -> Purple
                                isNext -> Ink
                                i < currentIndex && running -> Grey.copy(alpha = 0.6f)
                                else -> Grey
                            },
                            fontWeight = if (isSelectedStart || isNext) FontWeight.SemiBold else FontWeight.Normal,
                            textAlign = TextAlign.Center,
                        )
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
private fun BottomSheet(
    state: PracticeViewModel.UiState,
    vm: PracticeViewModel,
    finishedExpanded: Boolean,
    onFinishedExpandedChange: (Boolean) -> Unit,
    onStart: () -> Unit,
    onBack: () -> Unit,
    onReturnToPractice: () -> Unit,
    onOpenResult: (Long) -> Unit,
) {
    val running = state.isRunning
    Box(Modifier.fillMaxSize()) {
        Surface(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .then(
                    if (state.phase == Phase.FINISHED) {
                        Modifier.pointerInput(Unit) {
                            detectVerticalDragGestures { change, dragAmount ->
                                change.consume()
                                when {
                                    dragAmount > 6f -> onFinishedExpandedChange(false)
                                    dragAmount < -6f -> onFinishedExpandedChange(true)
                                }
                            }
                        }
                    } else Modifier
                ),
            shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
            color = Color.White, shadowElevation = 16.dp,
        ) {
            Column(Modifier.padding(top = if (state.phase == Phase.FINISHED) 8.dp else 14.dp, bottom = if (state.phase == Phase.FINISHED) 38.dp else 28.dp)) {
                if (state.phase == Phase.FINISHED) {
                    IconButton(
                        onClick = { onFinishedExpandedChange(!finishedExpanded) },
                        modifier = Modifier.align(Alignment.CenterHorizontally).size(32.dp),
                    ) {
                        Icon(
                            if (finishedExpanded) Icons.Filled.KeyboardArrowDown else Icons.Filled.KeyboardArrowUp,
                            if (finishedExpanded) "완료 화면 접기" else "완료 화면 펼치기",
                            tint = Grey,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                if (state.phase == Phase.FINISHED && finishedExpanded) {
                    FinishedPanel(state, vm, onOpenResult, onReturnToPractice)
                    Spacer(Modifier.height(16.dp))
                }
                Box(Modifier.fillMaxWidth().padding(horizontal = 36.dp), contentAlignment = Alignment.Center) {
                    if (state.phase != Phase.FINISHED) {
                        Column(Modifier.align(Alignment.CenterStart), horizontalAlignment = Alignment.CenterHorizontally) {
                            val rewindEnabled = state.phase == Phase.PAUSED
                            IconButton(onClick = vm::restart, enabled = rewindEnabled) {
                                Icon(Icons.Filled.Replay, "되돌리기", Modifier.size(30.dp), tint = if (rewindEnabled) Ink else GreyLight)
                            }
                            Text("되돌리기", fontSize = 11.sp, color = if (rewindEnabled) Grey else GreyLight)
                        }
                    }
                    Surface(
                        onClick = { when (state.phase) { Phase.PAUSED -> vm.resume(); Phase.RUNNING, Phase.COUNTDOWN -> vm.pause(); else -> onStart() } },
                        enabled = !(state.phase == Phase.FINISHED && state.save.phase == SavePhase.SAVING),
                        shape = CircleShape,
                        // 재생 중에도 같은 보라를 쓴다 — 예전엔 연한 LavenderDeep 위에 흰 아이콘이라 꺼진 것처럼 보였다
                        color = Purple,
                        shadowElevation = 8.dp,
                        modifier = Modifier.size(if (state.phase == Phase.FINISHED) 60.dp else 72.dp),
                    ) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            val (icon, desc) = when (state.phase) {
                                Phase.RUNNING, Phase.COUNTDOWN -> Icons.Filled.Pause to "일시정지"
                                Phase.PAUSED -> Icons.Filled.PlayArrow to "이어서"
                                Phase.FINISHED -> Icons.Filled.Replay to "다시 부르기"
                                else -> Icons.Filled.PlayArrow to "시작"
                            }
                            Icon(icon, desc, Modifier.size(32.dp), tint = Color.White)
                        }
                    }
                    if (state.phase != Phase.FINISHED) {
                        val finishEnabled = state.phase == Phase.PAUSED
                        Column(Modifier.align(Alignment.CenterEnd), horizontalAlignment = Alignment.CenterHorizontally) {
                            IconButton(
                                onClick = vm::finishEarly,
                                enabled = finishEnabled,
                            ) {
                                Icon(Icons.Filled.Stop, "여기까지 마치기", Modifier.size(32.dp), tint = if (finishEnabled) Ink else GreyLight)
                            }
                            Text("마치기", fontSize = 11.sp, color = if (finishEnabled) Grey else GreyLight)
                        }
                    }
                }
                if (state.phase == Phase.FINISHED) {
                    Text(
                        "다시하기",
                        modifier = Modifier.align(Alignment.CenterHorizontally).padding(top = 5.dp),
                        fontSize = 11.sp,
                        color = Grey,
                    )
                }
                state.error?.let { Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) }
            }
        }
    }
}

@Composable
private fun FinishedPanel(
    state: PracticeViewModel.UiState,
    vm: PracticeViewModel,
    onOpenResult: (Long) -> Unit,
    onReturnToPractice: () -> Unit,
) {
    Column(Modifier.padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FinishedActionButton(if (state.takePlaying) "정지" else "녹음 듣기", { if (state.takePlaying) vm.stopTake() else vm.playTake() }, Modifier.weight(1f), filled = false)
            when (state.save.phase) {
                SavePhase.SAVED -> FinishedActionButton("리포트 상세 보기", { state.save.practiceId?.let(onOpenResult) }, Modifier.weight(1.3f), filled = true)
                SavePhase.ERROR -> OutlinePurpleButton("기록 다시 저장", vm::retrySave, Modifier.weight(1.3f), height = 46.dp)
                SavePhase.SAVING -> Box(Modifier.weight(1.3f).height(46.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = Purple, strokeWidth = 2.dp) }
                SavePhase.GUEST -> FinishedActionButton("목록으로", onReturnToPractice, Modifier.weight(1.3f), filled = true)
                SavePhase.NONE -> {}
            }
        }
        Spacer(Modifier.height(18.dp))
        state.totals?.let { t ->
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("score", fontSize = 16.sp, color = Grey, fontWeight = FontWeight.SemiBold)
                Text("${t.overall.roundToInt()}", fontSize = 52.sp, lineHeight = 56.sp, fontWeight = FontWeight.Black, color = Ink)
            }
        }
        if (state.save.message.isNotEmpty()) {
            Text(state.save.message, color = if (state.save.phase == SavePhase.ERROR) Red else Grey, fontSize = 12.sp, modifier = Modifier.padding(top = 6.dp))
        }
        if (state.save.recordingSaveFailed) {
            TextButton(onClick = vm::retryRecordingSave) { Text("녹음 다시 저장") }
        }
    }
}

@Composable
private fun FinishedActionButton(text: String, onClick: () -> Unit, modifier: Modifier, filled: Boolean) {
    if (filled) {
        Button(
            onClick = onClick,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(50),
            colors = ButtonDefaults.buttonColors(containerColor = Purple, contentColor = Color.White),
        ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    } else {
        OutlinedButton(
            onClick = onClick,
            modifier = modifier.height(46.dp),
            shape = RoundedCornerShape(50),
            border = BorderStroke(1.dp, Purple),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Purple),
        ) { Text(text, fontSize = 14.sp, fontWeight = FontWeight.SemiBold) }
    }
}

@Suppress("unused")
private val unusedPalette = listOf(GreenChipBg, GreenChipText, ScoringUi.COLOR_INK)
