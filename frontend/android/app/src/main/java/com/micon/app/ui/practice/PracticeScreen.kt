package com.micon.app.ui.practice

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import com.micon.app.scoring.LyricLine
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.TimeProgress
import com.micon.app.ui.practice.PracticeViewModel.Phase
import com.micon.app.ui.practice.PracticeViewModel.ReportPhase
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red

/**
 * Figma "노래 연습 - 노래 연습전 / 연습중".
 * 위: 곡명(보라)·가수·진행 바 카드. 가운데: 가사 줄 목록(현재 줄 연보라 하이라이트, 부른 음절은 보라 굵게).
 * 아래: 흰 시트 — 되감기 · 마이크(시작)/일시정지(회색) · 닫기.
 * 라이브 채점 로직은 PracticeViewModel 그대로. 종료 후 정밀 리포트 버튼이 시트에 나온다.
 * (프로토타입의 러너웨이 시각화 Runway.kt 는 남겨 두었고 이 화면에서는 쓰지 않는다.)
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
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.start() }
    fun startWithPermission() {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.start()
        else permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
    }
    val running = state.phase == Phase.RUNNING || state.phase == Phase.COUNTDOWN
    val durationSec = vm.notes.lastOrNull()?.end ?: 0.0

    Box(Modifier.fillMaxSize().background(Color.White)) {
        when (state.phase) {
            Phase.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Purple)
            Phase.ERROR -> RetryBlock(state.error ?: "오류", onBack)
            else -> Column(Modifier.fillMaxSize()) {
                Surface(color = Color.White, shadowElevation = 4.dp) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 22.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Spacer(Modifier.height(24.dp))
                        Text(state.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Purple)
                        Spacer(Modifier.height(8.dp))
                        Text(state.artist, fontSize = 16.sp, color = Grey)
                        Spacer(Modifier.height(36.dp))
                        TimeProgress(state.nowSec, durationSec)
                        if (state.phase == Phase.READY) KeyRow(state.keyShift, state.keyBusy, onChange = vm::changeKey)
                    }
                }
                LyricsList(vm.lyricLines, state.nowSec, running, Modifier.weight(1f))
            }
        }

        if (state.phase == Phase.COUNTDOWN) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                Text("${state.countdown}", fontSize = 96.sp, fontWeight = FontWeight.Black, color = Purple)
            }
        }

        if (state.phase != Phase.LOADING && state.phase != Phase.ERROR) {
            Surface(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                color = Color.White, shadowElevation = 16.dp,
            ) {
                Column(Modifier.padding(top = 26.dp, bottom = 40.dp)) {
                    Row(Modifier.fillMaxWidth().padding(horizontal = 40.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                        IconButton(onClick = { vm.stop(false); vm.player.seekTo(0.0) }) {
                            Icon(Icons.Filled.Replay, "처음부터", Modifier.size(34.dp), tint = Ink)
                        }
                        Surface(
                            onClick = { if (running) vm.stop(false) else startWithPermission() },
                            shape = CircleShape,
                            color = if (running) GreyLight else Purple,
                            shadowElevation = 8.dp,
                            modifier = Modifier.size(118.dp),
                        ) {
                            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                                Icon(
                                    if (running) Icons.Filled.Pause else Icons.Filled.Mic,
                                    if (running) "일시정지" else "시작",
                                    Modifier.size(48.dp),
                                    tint = if (running) Grey else Color.White,
                                )
                            }
                        }
                        IconButton(onClick = { vm.stop(false); onBack() }) { Icon(Icons.Filled.Close, "닫기", Modifier.size(34.dp), tint = Ink) }
                    }
                    if (state.phase == Phase.FINISHED) {
                        Spacer(Modifier.height(18.dp))
                        Column(Modifier.padding(horizontal = 28.dp)) {
                            state.totals?.let { t ->
                                Text("라이브 점수 ${t.overall.toInt()}점 (${t.grade}) · ${t.judged}음 판정", fontSize = 13.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                            }
                            Spacer(Modifier.height(10.dp))
                            when (state.report.phase) {
                                ReportPhase.NONE -> PrimaryButton("정밀 리포트 만들기", vm::makePreciseReport, Modifier.fillMaxWidth(), height = 52.dp)
                                ReportPhase.UPLOADING, ReportPhase.ANALYZING -> Column {
                                    Text("${state.report.message}  ${state.report.progress}%", fontSize = 13.sp, color = Grey)
                                    LinearProgressIndicator(progress = { state.report.progress / 100f }, modifier = Modifier.fillMaxWidth(), color = Purple, trackColor = Lavender)
                                }
                                ReportPhase.DONE -> PrimaryButton("정밀 리포트 보기", { state.report.jobId?.let(onOpenResult) }, Modifier.fillMaxWidth(), height = 52.dp)
                                ReportPhase.ERROR -> Column {
                                    Text(state.report.message, color = Red, fontSize = 13.sp)
                                    OutlinePurpleButton("다시 시도", vm::makePreciseReport, Modifier.fillMaxWidth(), height = 48.dp)
                                }
                            }
                        }
                    }
                    state.error?.let { Text(it, color = Red, fontSize = 12.sp, modifier = Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) }
                }
            }
        }
    }
}

/** 반주 키 조절 (READY 상태에서만). Figma 에는 없어 작게 둔다. */
@Composable
private fun KeyRow(keyShift: Int, busy: Boolean, onChange: (Int) -> Unit) {
    Row(Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("−", fontSize = 20.sp, color = Purple, modifier = Modifier.clickable(enabled = !busy) { onChange(keyShift - 1) })
        Text(if (keyShift == 0) "원키" else (if (keyShift > 0) "키 +$keyShift" else "키 $keyShift"), fontSize = 13.sp, color = Grey)
        Text("+", fontSize = 20.sp, color = Purple, modifier = Modifier.clickable(enabled = !busy) { onChange(keyShift + 1) })
    }
}

@Composable
private fun LyricsList(lines: List<LyricLine>, nowSec: Double, running: Boolean, modifier: Modifier) {
    val listState = rememberLazyListState()
    val currentIndex = lines.indexOfLast { it.start <= nowSec }
        .let { i -> if (i < 0) 0 else if (i + 1 < lines.size && nowSec >= lines[i].end) i + 1 else i }
    LaunchedEffect(currentIndex, running) {
        if (running && lines.isNotEmpty()) listState.animateScrollToItem(currentIndex, scrollOffset = -220)
    }
    LazyColumn(modifier.fillMaxWidth(), state = listState, contentPadding = PaddingValues(top = 120.dp, bottom = 260.dp)) {
        itemsIndexed(lines) { i, line ->
            val isCurrent = i == currentIndex && running
            Box(
                Modifier.fillMaxWidth().background(if (isCurrent) Lavender else Color.Transparent).padding(vertical = if (isCurrent) 14.dp else 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (isCurrent) {
                    Text(
                        buildAnnotatedString {
                            line.tokens.forEach { tok ->
                                val sung = nowSec >= tok.note.start
                                if (tok.space) append(" ")
                                withStyle(SpanStyle(color = if (sung) Purple else Ink, fontWeight = if (sung) FontWeight.Bold else FontWeight.Medium)) { append(tok.note.lyric ?: "") }
                            }
                        },
                        fontSize = 24.sp, textAlign = TextAlign.Center,
                    )
                } else {
                    Text(line.text, fontSize = 19.sp, color = Grey, textAlign = TextAlign.Center)
                }
            }
        }
    }
}
