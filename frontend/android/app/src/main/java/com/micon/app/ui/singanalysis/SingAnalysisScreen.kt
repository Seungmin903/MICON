package com.micon.app.ui.singanalysis

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.singanalysis.PlanSource
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.data.singanalysis.ScopePlan
import com.micon.app.data.singanalysis.SingScope
import com.micon.app.scoring.LyricTiming
import com.micon.app.ui.charts.WaveformBars
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.RetryBlock
import com.micon.app.ui.common.SoftChip
import com.micon.app.ui.common.StepBar
import com.micon.app.ui.common.TimeProgress
import com.micon.app.ui.common.VinylArt
import com.micon.app.ui.common.formatSec
import com.micon.app.ui.practice.KaraokePanel
import com.micon.app.ui.rememberAppContainer
import com.micon.app.ui.singanalysis.SingAnalysisViewModel.Phase
import com.micon.app.ui.singanalysis.SingAnalysisViewModel.SubmitPhase
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyBg
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Paper
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.PinkBg
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import com.micon.app.ui.theme.Teal
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 불러서 분석하기 (Figma 프레임 없음 — "음성 간편 분석" 의 단계 구성 + "노래 연습중" 의 가사 화면을 따른다).
 *
 * 한 경로에서 단계별로 세 화면을 그린다:
 * - SETUP: 곡 정보 + 범위 카드 3개(전곡 · 2절부터 · 하이라이트만). 카드를 누르면 마이크 권한 → 카운트인 → 부르기.
 * - COUNTDOWN/RUNNING/PAUSED: REC 표시 · 진행 바 · 가사(현재 줄 채움 + 다음 줄) · 마이크 레벨 · 하단 시트(일시정지/이어서 + **여기까지 마치기**).
 *   점수판·음정 레인·판정색·진동은 없다(채점은 서버 분석 담당).
 * - DONE: 저장 완료 + 녹음 정보 + 다시 듣기 · 분석 요청 · 다시 부르기 · 리포트로 돌아가기.
 */
@Composable
fun SingAnalysisScreen(songId: Long, onBack: () -> Unit, onBackToReport: () -> Unit,
    recordings: com.micon.app.data.singanalysis.SingAnalysisRecordingStore? = null,
    onboardingDraftId: String? = null, onRecordingReady: ((SavedRecording) -> Unit)? = null,
) {
    val container = rememberAppContainer()
    val context = LocalContext.current
    val vm: SingAnalysisViewModel = viewModel(
        key = "sing-analysis-$songId",
        factory = viewModelFactory {
            initializer { SingAnalysisViewModel(context.applicationContext, songId, container.songs,
                container.mediaHttp, recordings ?: container.singRecordings, container.songAnalysis,
                container.voiceAnalyses, onboardingDraftId) }
        },
    )
    val state by vm.state.collectAsStateWithLifecycle()
    androidx.compose.runtime.LaunchedEffect(state.saved?.id) {
        state.saved?.let { onRecordingReady?.invoke(it) }
    }

    // 마이크 권한: 카드를 누른 뒤 요청하고, 허용되면 그 카드로 바로 시작한다.
    var pendingPlan by remember { mutableStateOf<ScopePlan?>(null) }
    val requestMicrophone = com.micon.app.ui.common.rememberMicrophoneAccess(
        onAllowed = { pendingPlan?.let(vm::choose); pendingPlan = null }, onDenied = vm::permissionDenied)
    fun chooseWithPermission(plan: ScopePlan) {
        pendingPlan = plan
        requestMicrophone()
    }

    // Screen locking is allowed; switching to another app invalidates this recording.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP &&
                (context.getSystemService(android.content.Context.POWER_SERVICE) as android.os.PowerManager).isInteractive) vm.interruptRecording()
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    val view = LocalView.current
    DisposableEffect(state.isSinging) {
        view.keepScreenOn = state.isSinging
        onDispose { view.keepScreenOn = false }
    }
    BackHandler(enabled = state.isSinging) { vm.requestExit() }
    BackHandler(enabled = state.phase == Phase.SAVING) { }

    Box(Modifier.fillMaxSize().background(Color.White)) {
        when (state.phase) {
            Phase.LOADING -> CircularProgressIndicator(Modifier.align(Alignment.Center), color = Purple)
            Phase.ERROR -> Column(Modifier.fillMaxSize()) {
                BackHeader("목소리 분석", onBack)
                RetryBlock(state.error ?: "오류", vm::load)
            }
            Phase.SETUP -> SetupBody(state, onChoose = ::chooseWithPermission, onBack = onBack,
                compact = onboardingDraftId != null)
            Phase.COUNTDOWN, Phase.RUNNING, Phase.PAUSED -> SingingBody(state, vm)
            Phase.SAVING -> Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                CircularProgressIndicator(color = Purple)
                Spacer(Modifier.height(14.dp))
                Text("녹음을 저장하고 있어요", fontSize = 15.sp, color = Grey)
            }
            Phase.DONE -> DoneBody(state, vm, onBack = onBack, onBackToReport = onBackToReport)
        }

        if (state.confirmDiscard) {
            AlertDialog(onDismissRequest = vm::cancelExit,
                title = { Text("녹음을 그만할까요?") },
                text = { Text("지금 녹음한 내용은 지워져요. 다시 시작할 수 있어요.") },
                confirmButton = { TextButton(onClick = { vm.discardAndExit(); onBack() }) { Text("녹음 지우고 나가기") } },
                dismissButton = { TextButton(onClick = vm::cancelExit) { Text("계속하기") } })
        }
        if (state.confirmEnd) {
            FinishDialog(
                sungSeconds = state.sungSeconds,
                onConfirm = vm::confirmFinish,
                onContinue = vm::cancelFinish,
                onDiscard = { vm.discardAndExit(); onBack() },
            )
        }
    }
}

// ---------------------------------------------------------------------------
// SETUP: 범위 선택
// ---------------------------------------------------------------------------

@Composable
internal fun SetupBody(state: SingAnalysisViewModel.UiState, onChoose: (ScopePlan) -> Unit, onBack: () -> Unit,
    compact: Boolean = false,
) {
    Column(Modifier.fillMaxSize()) {
        BackHeader("목소리 분석", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(if (compact) 8.dp else 16.dp))
            StepBar(2, 3)
            Spacer(Modifier.height(if (compact) 12.dp else 24.dp))
            if (compact) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                    VinylArt(state.imageUrl, 64.dp, fallbackText = state.title)
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(state.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = InkSoft,
                            maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(4.dp))
                        Text(state.artist, fontSize = 14.sp, color = Grey, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            } else {
                Box(Modifier.size(132.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) {
                    VinylArt(state.imageUrl, 108.dp, fallbackText = state.title)
                }
                Spacer(Modifier.height(18.dp))
                Text(state.title, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = InkSoft, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                Spacer(Modifier.height(6.dp))
                Text("${state.artist} · ${formatSec(state.durationSec)}", fontSize = 14.sp, color = Grey)
                Spacer(Modifier.height(8.dp))
                LyricTimingChip(state.lyricTiming)
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = if (compact) 24.dp else 32.dp)) {
                Spacer(Modifier.height(if (compact) 12.dp else 22.dp))
                Text("어디부터 부를까요?", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = InkSoft)
                Spacer(Modifier.height(10.dp))
                Column(verticalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp)) {
                    state.plans.forEach { plan -> ScopeCard(plan, onClick = { onChoose(plan) }, compact = compact) }
                }
                Spacer(Modifier.height(8.dp))
                Text("카드를 누르면 박자 카운트 후 반주가 시작돼요", fontSize = 12.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = Red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(18.dp))
                MiconCard {
                    CardTitle("부르기 전에")
                    Spacer(Modifier.height(8.dp))
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("· 이어폰을 쓰면 반주가 녹음에 섞이지 않아 더 정확해요", fontSize = 13.sp, color = Grey)
                        Text("· 반주와 함께 30초 이상 녹음한 뒤 일시정지하고 마쳐 주세요", fontSize = 13.sp, color = Grey)
                        Text("· 마이크는 목소리 분석을 위해 사용해요", fontSize = 13.sp, color = Grey)
                    }
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "나중에 할게요", fontSize = 17.sp, color = Purple, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onBack).padding(vertical = 6.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun LyricTimingChip(timing: LyricTiming) {
    when (timing) {
        LyricTiming.SYNCED -> SoftChip("가사 싱크")
        LyricTiming.NOTES -> SoftChip("가사(음절)")
        LyricTiming.APPROX -> SoftChip("가사 근사")
        LyricTiming.NONE -> SoftChip("가사 없음", bg = GreyBg, fg = Grey)
    }
}

/** 범위 카드. 비활성이면 흐리게 + 사유. 하이라이트는 핑크(연습 구간 칩과 같은 색 의미). */
@Composable
private fun ScopeCard(plan: ScopePlan, onClick: () -> Unit, compact: Boolean = false) {
    val (icon, iconBg, iconTint) = when (plan.scope) {
        SingScope.FULL -> Triple(Icons.Outlined.MusicNote, Lavender, Purple)
        SingScope.SECOND_HALF -> Triple(Icons.Outlined.SkipNext, Lavender, Purple)
        SingScope.HIGHLIGHT -> Triple(Icons.Outlined.Star, PinkBg, Pink)
    }
    MiconCard(Modifier.fillMaxWidth().testTag("scope-${plan.scope.name}")
        .alpha(if (plan.enabled) 1f else 0.45f).clickable(enabled = plan.enabled, onClick = onClick),
        padding = PaddingValues(if (compact) 12.dp else 16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(if (compact) 32.dp else 44.dp).background(iconBg, CircleShape), contentAlignment = Alignment.Center) {
                Icon(icon, null, Modifier.size(24.dp), tint = iconTint)
            }
            Spacer(Modifier.width(if (compact) 10.dp else 14.dp))
            Column(Modifier.weight(1f)) {
                Text(plan.displayLabel, fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink)
                if (!compact) {
                    Spacer(Modifier.height(2.dp))
                    if (plan.enabled) {
                        Text("${formatSec(plan.startSec)}부터 끝까지 · 약 ${formatSec(plan.lengthSec)}", fontSize = 13.sp, color = Grey)
                    }
                    plan.note?.let { Text(it, fontSize = 12.sp, color = if (plan.enabled) Grey else Red) }
                }
            }
            if (plan.enabled) Icon(Icons.Filled.ChevronRight, null, tint = Purple)
        }
    }
}

// ---------------------------------------------------------------------------
// 부르기: REC 헤더 · 가사 · 마이크 레벨 · 하단 시트 · 카운트다운 오버레이
// ---------------------------------------------------------------------------

@Composable
private fun SingingBody(state: SingAnalysisViewModel.UiState, vm: SingAnalysisViewModel) {
    val paused = state.phase == Phase.PAUSED
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize()) {
            Surface(color = Color.White, shadowElevation = 4.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RecIndicator(active = state.phase == Phase.RUNNING && !state.buffering, elapsedSec = state.elapsedRecSec)
                        Spacer(Modifier.width(12.dp))
                        Text(
                            "${state.title}  ·  ${state.artist}", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = Purple,
                            maxLines = 1, textAlign = TextAlign.Center, modifier = Modifier.weight(1f),
                        )
                        Spacer(Modifier.width(8.dp))
                        state.plan?.let { SoftChip(it.displayLabel) }
                    }
                    Spacer(Modifier.height(6.dp))
                    TimeProgress(state.nowSec, state.durationSec)
                }
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                KaraokePanel(
                    vm.sheet, state.nowSec, Modifier.fillMaxSize(),
                    judgementColors = false,
                    emptyMessage = "가사가 준비되지 않았어요. 다른 곡을 골라 주세요.",
                )
                if (paused) {
                    Column(
                        Modifier.fillMaxSize().background(Color.White.copy(alpha = 0.85f)),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center,
                    ) {
                        Text("일시정지", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Purple)
                        Spacer(Modifier.height(6.dp))
                        Text("이어서 부르면 박자 카운트 뒤 반주가 다시 시작돼요", fontSize = 13.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
                    }
                }
            }

            MicLevelStrip(state.micLevel, Modifier.padding(horizontal = 24.dp))
            // 하단 시트(오버레이)에 가리지 않도록 비워 두는 높이
            Spacer(Modifier.height(232.dp))
        }

        // 카운트다운 오버레이(연습과 동일)
        if (state.phase == Phase.COUNTDOWN) {
            Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.25f)), contentAlignment = Alignment.Center) {
                if (state.buffering) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = Purple)
                        Spacer(Modifier.height(12.dp))
                        Text("반주를 준비하고 있어요", fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                } else if (state.countdown > 0) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("${state.countdown}", fontSize = 96.sp, fontWeight = FontWeight.Black, color = Purple)
                        Text("박자에 맞춰 준비!", fontSize = 14.sp, color = Color.White, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        ControlSheet(state, vm, Modifier.align(Alignment.BottomCenter))
    }
}

/** 빨간 점(깜빡임) + REC + 경과 시간. 일시정지 중엔 점이 멈춘다. */
@Composable
private fun RecIndicator(active: Boolean, elapsedSec: Double) {
    val blink by rememberInfiniteTransition(label = "rec").animateFloat(
        initialValue = 1f, targetValue = 0.25f,
        animationSpec = infiniteRepeatable(tween(600, easing = LinearEasing), RepeatMode.Reverse), label = "recAlpha",
    )
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(10.dp).alpha(if (active) blink else 1f).background(if (active) Red else Grey, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(if (active) "REC" else "일시정지", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (active) Red else Grey)
        Spacer(Modifier.width(6.dp))
        Text(formatSec(elapsedSec), fontSize = 13.sp, color = Ink, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun MicLevelStrip(level: Float, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().background(Lavender.copy(alpha = 0.5f), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Filled.Mic, null, Modifier.size(16.dp), tint = Purple)
        Spacer(Modifier.width(8.dp))
        WaveformBars(level, Modifier.weight(1f).height(26.dp), bars = 40)
    }
}

/** 하단 시트: 일시정지/이어서 원 버튼 + 항상 보이는 "여기까지 마치기". */
@Composable
private fun ControlSheet(state: SingAnalysisViewModel.UiState, vm: SingAnalysisViewModel, modifier: Modifier) {
    val paused = state.phase == Phase.PAUSED
    Surface(modifier.fillMaxWidth(), shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp), color = Color.White, shadowElevation = 16.dp) {
        Column(Modifier.padding(top = 18.dp, bottom = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Surface(
                onClick = { if (paused) vm.resume() else vm.pause() },
                enabled = !paused || state.nowSec < (state.plan?.endSec ?: 0.0),
                shape = CircleShape,
                color = if (paused) Purple else GreyLight,
                shadowElevation = 8.dp,
                modifier = Modifier.size(88.dp),
            ) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Icon(if (paused) Icons.Filled.PlayArrow else Icons.Filled.Pause, if (paused) "이어서 부르기" else "일시정지", Modifier.size(42.dp), tint = if (paused) Color.White else Grey)
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(if (paused) "이어서 부르기" else "일시정지", fontSize = 12.sp, color = Grey)
            Spacer(Modifier.height(14.dp))
            OutlinePurpleButton(
                "여기까지 마치기", vm::requestFinish,
                Modifier.fillMaxWidth().padding(horizontal = 24.dp), height = 48.dp, enabled = state.canFinish,
            )
            Spacer(Modifier.height(6.dp))
            Text(when {
                state.elapsedRecSec < 30 -> "반주와 함께 녹음한 시간이 30초 이상이어야 해요"
                !paused -> "일시정지하면 녹음을 마칠 수 있어요"
                else -> "녹음을 마치고 분석 결과를 확인해 보세요"
            }, fontSize = 12.sp, color = Grey)
            TextButton(onClick = vm::requestExit) { Text("녹음 그만하기", color = Grey) }
            state.error?.let { Text(it, color = Red, fontSize = 12.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 28.dp, vertical = 6.dp)) }
        }
    }
}

@Composable
private fun FinishDialog(sungSeconds: Double, onConfirm: () -> Unit, onContinue: () -> Unit, onDiscard: () -> Unit) {
    AlertDialog(
        onDismissRequest = onContinue,
        containerColor = Paper,
        shape = RoundedCornerShape(20.dp),
        title = { Text("여기까지 마칠까요?", fontSize = 18.sp, color = Ink, fontWeight = FontWeight.Bold) },
        text = {
            Column {
                Text("지금까지 부른 ${formatSec(sungSeconds)} 이 저장돼요.", fontSize = 14.sp, color = InkSoft)
                Spacer(Modifier.height(14.dp))
                Text("저장하지 않고 나가기", fontSize = 13.sp, color = Red, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onDiscard).padding(vertical = 4.dp))
            }
        },
        confirmButton = { PrimaryButton("여기까지 마치기", onConfirm, height = 46.dp) },
        dismissButton = { TextButton(onClick = onContinue) { Text("계속 부르기", color = Purple, fontWeight = FontWeight.SemiBold) } },
    )
}

// ---------------------------------------------------------------------------
// DONE: 저장 완료
// ---------------------------------------------------------------------------

@Composable
private fun DoneBody(state: SingAnalysisViewModel.UiState, vm: SingAnalysisViewModel, onBack: () -> Unit, onBackToReport: () -> Unit) {
    val saved = state.saved
    Column(Modifier.fillMaxSize()) {
        BackHeader("목소리 분석", onBack)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()), horizontalAlignment = Alignment.CenterHorizontally) {
            Spacer(Modifier.height(16.dp))
            StepBar(3, 3)
            Spacer(Modifier.height(40.dp))
            Box(Modifier.size(120.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.CheckCircle, null, Modifier.size(60.dp), tint = Purple)
            }
            Spacer(Modifier.height(22.dp))
            Text("녹음 완료", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkSoft)
            Spacer(Modifier.height(8.dp))
            val scopeLabel = state.plan?.displayLabel ?: ""
            Text("${state.title} · $scopeLabel · ${formatSec(saved?.meta?.durationSec ?: state.elapsedRecSec)} 녹음", fontSize = 14.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(horizontal = 32.dp))
            if (state.endedEarly && saved != null) {
                Spacer(Modifier.height(4.dp))
                Text("중간에 마쳤어요 (${formatSec(saved.meta.sungStartSec)} ~ ${formatSec(saved.meta.sungEndSec)})", fontSize = 12.sp, color = Grey)
            }

            Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp)) {
                Spacer(Modifier.height(18.dp))
                if (saved != null) RecordingInfoCard(saved, state.takePlaying, onToggleTake = { if (state.takePlaying) vm.stopTake() else vm.playTake() })
                Spacer(Modifier.height(18.dp))
                when (state.submit.phase) {
                    SubmitPhase.SENDING -> Box(Modifier.fillMaxWidth().height(56.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(22.dp), color = Purple, strokeWidth = 2.dp) }
                    SubmitPhase.DONE -> PrimaryButton("분석 요청됨 · 결과 기다리는 중", {}, Modifier.fillMaxWidth(), enabled = false)
                    SubmitPhase.COMPLETED -> PrimaryButton("리포트에서 결과 보기", onBackToReport, Modifier.fillMaxWidth())
                    SubmitPhase.NONE -> PrimaryButton("분석 요청", vm::submit, Modifier.fillMaxWidth())
                    SubmitPhase.UNAVAILABLE, SubmitPhase.ERROR, SubmitPhase.FAILED -> PrimaryButton("분석 다시 요청", vm::submit, Modifier.fillMaxWidth())
                }
                if (state.submit.message.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        state.submit.message, fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth(),
                        color = when (state.submit.phase) { SubmitPhase.ERROR, SubmitPhase.FAILED -> Red; SubmitPhase.DONE, SubmitPhase.COMPLETED -> Teal; else -> Grey },
                    )
                }
                state.error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, fontSize = 13.sp, color = Red, textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                }
                Spacer(Modifier.height(14.dp))
                OutlinePurpleButton("다시 부르기", vm::restart, Modifier.fillMaxWidth(), height = 48.dp)
                Spacer(Modifier.height(16.dp))
                Text(
                    "리포트로 돌아가기", fontSize = 17.sp, color = Purple, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onBackToReport).padding(vertical = 6.dp),
                )
                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

@Composable
private fun RecordingInfoCard(saved: SavedRecording, playing: Boolean, onToggleTake: () -> Unit) {
    val meta = saved.meta
    MiconCard {
        CardTitle("녹음 정보")
        Spacer(Modifier.height(10.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            InfoRow("파일 크기", "%.1f MB".format(Locale.US, saved.wav.length() / 1_048_576.0))
            InfoRow("샘플레이트", "${meta.sampleRate / 1000} kHz · 모노 16bit")
            InfoRow("녹음 시각", SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(meta.recordedAt)))
            InfoRow("부른 구간", "${formatSec(meta.sungStartSec)} ~ ${formatSec(meta.sungEndSec)}" + if (meta.takes.size > 1) " · ${meta.takes.size}번에 나눠 부름" else "")
            InfoRow("저장 위치", meta.serverRecordingId?.let { "이 기기 + 서버 (녹음 #$it)" } ?: "이 기기(앱 내부)")
            if (meta.planSource == PlanSource.HALF_DURATION.name) InfoRow("시작 기준", "절 정보 없음 → 곡 절반")
        }
        Spacer(Modifier.height(12.dp))
        OutlinePurpleButton(if (playing) "정지" else "다시 듣기", onToggleTake, Modifier.fillMaxWidth(), height = 46.dp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth()) {
        Text(label, fontSize = 13.sp, color = Grey, modifier = Modifier.width(84.dp))
        Text(value, fontSize = 13.sp, color = Ink, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
    }
}
