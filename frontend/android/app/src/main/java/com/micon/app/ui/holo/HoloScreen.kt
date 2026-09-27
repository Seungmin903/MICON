package com.micon.app.ui.holo

import android.app.Activity
import android.content.ContextWrapper
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.appContainer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.rememberModalBottomSheetState
import com.micon.app.data.SongSummary
import com.micon.app.range.FreeRangeSession
import com.micon.app.range.RangeRepository
import com.micon.app.range.onboardingPreview
import com.micon.app.ui.common.MiconLogo
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import com.micon.app.ui.songlist.SongCatalogContent
import com.micon.app.ui.songlist.SongListViewModel
import com.micon.app.ui.common.rememberMicrophoneAccess
import com.micon.app.ui.design.molecules.NoteNames
import com.micon.app.ui.design.molecules.OctaveNoteText
import com.micon.app.ui.design.molecules.OctaveRangeText
import com.micon.app.ui.report.FreeRangeViewModel
import kotlinx.coroutines.delay
import kotlin.math.ceil
import kotlin.math.roundToInt
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.tokens.MiconMonoFamily
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/** 프로토타입 `holo` 색 */
private val HoloBase = Color(0xFF5A4CE4)
private val HoloG1 = Color(0xFF6D4BE8)
private val HoloG2 = Color(0xFF5B6BEE)
private val HoloG3 = Color(0xFF7C5BF0)
private val HoloG4 = Color(0xFF8E63F2)
private val BlobColors = listOf(Color(0xFFC084FC), Color(0xFF5FA8FB), Color(0xFF7DD3FC), Color(0xFFA855F7), Color(0xFF6366F1))
private val HoloButtonText = Color(0xFF3B2BA8)
private val Ink = Color(0xFF1E1B33)
private val Muted = Color(0xFF6B6B78)

/**
 * 첫 화면 · 목소리 분석 (idle → rec → 결과). 프로토타입 `holo`.
 *
 * 세로 배치는 전부 비율(로고 11% · 구체 flex · 가사 19% · 하단 24%)이라 S10(760dp)~S23U(883dp) 모두
 * 같은 인상이다. 상태바 아이콘은 이 화면에서만 밝게 바꾸고 나갈 때 되돌린다.
 */
@Composable
fun HoloScreen(
    onResult: () -> Unit,
    /** 분석을 미루고 둘러보기 — 분석 탭으로 */
    onSkip: (() -> Unit)? = null,
    vm: HoloViewModel,
) {
    val state by vm.state.collectAsStateWithLifecycle()
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = generateSequence(LocalContext.current) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    DisposableEffect(lifecycle, vm, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true) {
                val current = vm.state.value
                // 2단계에서 앱을 벗어나면 녹음만 버리고 곡 고르기로 돌아간다. 1단계에서 잰 음역은 지우지 않는다.
                if (current.stage == HoloViewModel.Stage.TIMBRE &&
                    (current.phase != HoloViewModel.Phase.DONE || current.result == null)) vm.cancelTimbre()
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LightStatusBarIcons()
    if (state.stage == HoloViewModel.Stage.RANGE) RangeStage(vm, onSkip, state.rangeRestartVersion)
    else TimbreStage(vm, onResult, onSkip)
}

// ---------------------------------------------------------------- 1단계: 음역

/**
 * 음역 측정. 재는 일은 자유 발성 미션(FreeRangeViewModel)이 그대로 하고,
 * 이 화면은 홀로 디자인으로 감싸기만 한다. 편한 음 → 낮은 음 10초 → 높은 음 15초.
 */
@Composable
private fun RangeStage(vm: HoloViewModel, onSkip: (() -> Unit)?, rangeRestartVersion: Long) {
    val context = LocalContext.current
    val repository = remember { RangeRepository(context.applicationContext) }
    val rangeVm: FreeRangeViewModel = viewModel(key = "holo-range", factory = viewModelFactory {
        initializer { FreeRangeViewModel(context.applicationContext, repository, true) }
    })
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val activity = generateSequence(context) { (it as? ContextWrapper)?.baseContext }
        .filterIsInstance<Activity>().firstOrNull()
    DisposableEffect(lifecycle, rangeVm, vm, activity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP && activity?.isChangingConfigurations != true &&
                vm.state.value.stage == HoloViewModel.Stage.RANGE) {
                vm.reset()
                rangeVm.restartMeasurement(vm.state.value.rangeRestartVersion)
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(rangeRestartVersion, rangeVm) {
        if (rangeRestartVersion > 0L) rangeVm.restartMeasurement(rangeRestartVersion)
    }
    val rs by rangeVm.state.collectAsStateWithLifecycle()
    val view = rs.view
    val start = rememberMicrophoneAccess(onAllowed = rangeVm::start, onDenied = { })
    val preview = remember(rs.savedId) { rs.savedId?.let { repository.pending()?.submission?.onboardingPreview() } }

    val done = view.phase == "done"
    val running = rs.recording
    val progress = ((view.completed + stageProgressOf(view)) / 3.0).toFloat().coerceIn(0f, 1f)
    val introActive = !running && !rs.busy && rs.error == null && view.phase == "noise"
    var showStartPrompt by remember { mutableStateOf(false) }
    LaunchedEffect(introActive) {
        showStartPrompt = false
        if (introActive) {
            delay(1500)
            showStartPrompt = true
        }
    }
    val message = when {
        done -> "음역을 다 쟀어요"
        introActive && showStartPrompt -> "조용해졌다면, 아래 '음역 측정 시작' 버튼을 눌러주세요"
        else -> view.message
    }

    var confirmSkip by remember { mutableStateOf(false) }

    HoloChrome(
        logoVisible = !running,
        globeScale = if (running) 0.85f + progress * 1.1f else 1f,
        absorbing = running,
        topPill = if (!running && onSkip != null) "나중에 ›" to { confirmSkip = true } else null,
        error = rs.error,
        middle = {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Crossfade(targetState = message, animationSpec = tween(300), label = "range-message") { current ->
                    Text(current, color = Color.White, fontSize = 21.sp,
                        fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                }
                if (running && (view.phase == "down" || view.phase == "up")) {
                    Text(
                        if (view.remainingSeconds > 0) "남은 시간 " + ceil(view.remainingSeconds).toInt() + "초" else "조금만 더 불러 주세요",
                        color = Color.White.copy(alpha = 0.72f), fontSize = 15.sp, fontFamily = MiconMonoFamily,
                    )
                    view.extremeMidi?.let {
                        val label = if (view.phase == "down") "가장 낮은" else "가장 높은"
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("지금까지 " + label + " 음 ", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                            OctaveNoteText(it.roundToInt(), size = 20.sp, color = Color.White)
                        }
                    }
                }
                val low = preview?.lowMidi
                val high = preview?.highMidi
                if (done && low != null && high != null) {
                    OctaveRangeText(low, high, size = 22.sp, color = Color.White, separator = "  ~  ")
                }
            }
        },
        bottom = {
            Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    if (done) "이제 노래로 음색을 봐요" else "1단계 · 음역 측정  " + (view.completed.coerceAtMost(2) + 1) + "/3",
                    color = Color.White.copy(alpha = 0.88f), fontSize = 12.5.sp, textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )
                Box(Modifier.fillMaxWidth().height(4.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.25f))) {
                    Box(Modifier.fillMaxWidth(progress).fillMaxSize().background(Color.White))
                }
                when {
                    rs.busy || (done && rs.savedId == null && rs.error == null) ->
                        HoloButton("정리하는 중…", enabled = false) {}
                    done && (rs.error != null || preview == null) -> HoloButton("다시 측정하기") {
                        vm.reset()
                        rangeVm.restartMeasurement(vm.state.value.rangeRestartVersion)
                    }
                    done -> HoloButton("노래로 음색 측정하기") { vm.rangeMeasured(preview) }
                    running -> Text("듣고 있어요", color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp,
                        textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth())
                    rs.error != null || view.phase == "failed" -> HoloButton("이 단계 다시 하기", onClick = rangeVm::retryStage)
                    else -> AnimatedVisibility(visible = !introActive || showStartPrompt,
                        enter = fadeIn(tween(300)), exit = fadeOut(tween(300))) {
                        HoloButton("음역 측정 시작", onClick = start)
                    }
                }
            }
        },
    )
    if (confirmSkip && onSkip != null) SkipConfirmDialog(measured = view.completed > 0 || done,
        onDismiss = { confirmSkip = false }, onConfirm = { confirmSkip = false; onSkip() })
}

private fun stageProgressOf(view: FreeRangeSession.View): Double = when (view.phase) {
    "comfort" -> (view.holdSeconds / FreeRangeSession.HOLD_SECONDS).coerceIn(0.0, 1.0)
    "down", "up" -> (view.elapsed / FreeRangeSession.missionSeconds(view.phase)).coerceIn(0.0, 1.0)
    "done" -> 1.0
    else -> 0.0
}

// ---------------------------------------------------------------- 2단계: 음색

/** 노래로 음색을 본다. 간주는 건너뛰므로 실제로 부르는 시간만 쌓인다. */
@Composable
private fun TimbreStage(vm: HoloViewModel, onResult: () -> Unit, onSkip: (() -> Unit)?) {
    val state by vm.state.collectAsStateWithLifecycle()
    val rec = state.phase == HoloViewModel.Phase.REC || state.phase == HoloViewModel.Phase.LOADING
    val counting = state.phase == HoloViewModel.Phase.LOADING && state.countdown > 0
    var flood by remember { mutableStateOf(false) }
    LaunchedEffect(state.phase) { if (state.phase == HoloViewModel.Phase.DONE) flood = true }
    // 녹음 준비·카운트다운·녹음 중 뒤로가기는 곡 고르기로. 음역부터 다시 재지 않는다.
    BackHandler(enabled = rec) { vm.cancelTimbre() }
    val requestMic = rememberMicrophoneAccess(onAllowed = vm::start, onDenied = { })
    var pickerOpen by remember { mutableStateOf(false) }
    var confirmSkip by remember { mutableStateOf(false) }
    // 연습 탭과 같은 곡 카탈로그. 2단계에 들어오자마자 만들어 첫 목록·장르를 미리 받아 둔다.
    val context = LocalContext.current
    val catalog: SongListViewModel = viewModel(key = "holo-picker", factory = viewModelFactory {
        initializer {
            val container = context.appContainer
            SongListViewModel(container.songs, container.recommend,
                NetworkPreferenceRepository(container.backend.retrofit.create(PreferenceApi::class.java)))
        }
    })

    HoloChrome(
        logoVisible = !rec,
        globeScale = if (rec) 0.8f + state.progress * 1.2f else 1f,
        // 방울 흡수는 마이크가 켜진 뒤부터(반주 준비·카운트다운 동안은 아직 듣지 않는다)
        absorbing = state.phase == HoloViewModel.Phase.REC,
        startPill = if (rec) "‹ 곡 다시 고르기" to vm::cancelTimbre else null,
        topPill = when {
            counting -> null
            rec -> "결과 바로 보기 ›" to vm::finishEarly
            onSkip != null -> "나중에 ›" to { confirmSkip = true }
            else -> null
        },
        error = state.error,
        globeOverlay = { if (counting) CountdownNumber(state.countdown) },
        middle = {
            if (counting) CountdownGuide(state.lyrics.getOrNull(state.lyricIndex).orEmpty())
            else if (rec && state.lyrics.isNotEmpty()) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(11.dp)) {
                    state.lyrics.forEachIndexed { i, line ->
                        val cur = i == state.lyricIndex
                        Text(
                            line, textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            color = if (cur) Color.White else Color.White.copy(alpha = 0.44f),
                            fontSize = if (cur) 21.sp else 16.sp, fontWeight = if (cur) FontWeight.Bold else FontWeight.Normal,
                        )
                    }
                    // 시작 지점(첫 가사 1초 전)도 전주로 잡히므로, 부르기 시작한 뒤의 간주에만 알린다
                    if (state.interlude && state.sungSec > 0.0) Text("간주는 건너뛸게요", color = Color.White.copy(alpha = 0.6f), fontSize = 13.sp)
                }
            } else if (!rec) {
                Text("2단계 · 노래로 음색을 봐요", color = Color.White, fontSize = 19.sp,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            }
        },
        bottom = {
            if (rec) PillPlayer(state, Modifier.align(Alignment.BottomCenter))
            else Column(Modifier.fillMaxWidth().align(Alignment.BottomCenter), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                val picked = state.picked
                Text(
                    picked?.let { it.title + " · " + it.artist } ?: "부를 노래를 골라 주세요",
                    color = Color.White, fontSize = 14.sp, fontWeight = if (picked != null) FontWeight.Bold else FontWeight.Normal,
                    textAlign = TextAlign.Center, maxLines = 2, overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth(),
                )
                HoloButton(if (picked == null) "노래 고르기" else "곡 바꾸기", enabled = true) {
                    vm.openSongPicker()
                    pickerOpen = true
                }
                if (picked != null) HoloButton("이 곡으로 시작하기", onClick = requestMic)
            }
        },
        flood = flood, onFloodCovered = onResult, onFloodDone = { flood = false },
    )
    if (pickerOpen) SongPickerSheet(catalog, onPick = { vm.pick(it); pickerOpen = false }, onClose = { pickerOpen = false })
    // 2단계에 왔다면 음역은 이미 잰 상태다
    if (confirmSkip && onSkip != null) SkipConfirmDialog(measured = true,
        onDismiss = { confirmSkip = false }, onConfirm = { confirmSkip = false; onSkip() })
}

/**
 * "나중에" 확인. 나가면 홀로 흐름이 처음으로 돌아가(reset) 다음 분석은 1단계 음역부터 다시 한다.
 * 30초 넘게 잰 음역을 모르고 버리지 않도록, 이미 잰 게 있으면 그 점을 분명히 알린다.
 */
@Composable
private fun SkipConfirmDialog(measured: Boolean, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("분석을 나중에 할까요?") },
        text = {
            Text(if (measured) "지금 나가면 지금까지 잰 음역은 이어서 쓸 수 없어요. 다음에 분석할 때 음역부터 다시 측정해야 해요."
                else "지금 나가면 다음에 분석할 때 음역 측정부터 시작해요.")
        },
        confirmButton = { TextButton(onClick = onConfirm) { Text("나중에 하기") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("계속 분석하기") } },
    )
}

/** 구체 위의 큰 숫자 4·3·2·1. 숫자가 바뀔 때마다 살짝 커졌다 돌아온다 */
@Composable
internal fun CountdownNumber(value: Int) {
    val pop = remember { Animatable(1f) }
    LaunchedEffect(value) {
        pop.snapTo(1.25f)
        pop.animateTo(1f, tween(380, easing = FastOutSlowInEasing))
    }
    Text("$value", color = HoloButtonText, fontSize = 56.sp, fontWeight = FontWeight.Black,
        modifier = Modifier.graphicsLayer { scaleX = pop.value; scaleY = pop.value }.testTag("holo-countdown"))
}

/** 카운트다운 동안 가운데 안내: 간주 없이 곧바로 시작한다는 것 + 처음 부를 가사 */
@Composable
internal fun CountdownGuide(firstLine: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text("간주 없이 바로 노래가 시작돼요", color = Color.White, fontSize = 19.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center)
        Text("숫자가 끝나면 첫 가사부터 불러 주세요", color = Color.White.copy(alpha = 0.7f), fontSize = 14.sp,
            textAlign = TextAlign.Center)
        if (firstLine.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(firstLine, color = Color.White.copy(alpha = 0.9f), fontSize = 16.sp, textAlign = TextAlign.Center,
                maxLines = 2, overflow = TextOverflow.Ellipsis,
                modifier = Modifier.clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = 0.14f))
                    .padding(horizontal = 14.dp, vertical = 8.dp))
        }
    }
}

/**
 * 부를 노래 고르기. 연습 탭 곡 목록과 같은 카탈로그(검색 · 장르 칩 · 무한 스크롤)를 시트 안에 그대로 쓴다.
 * 열자마자 곡 목록이 보이고, 장르나 검색어로 좁힌다. 시트라서 키보드가 올라와도 목록이 밀려 올라간다.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SongPickerSheet(catalog: SongListViewModel, onPick: (SongSummary) -> Unit, onClose: () -> Unit) {
    val state by catalog.state.collectAsStateWithLifecycle()
    ModalBottomSheet(onDismissRequest = onClose, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        Column(Modifier.fillMaxWidth().padding(bottom = 12.dp)) {
            Column(Modifier.padding(horizontal = 20.dp)) {
                Text("부를 노래 고르기", style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.height(4.dp))
                Text("아는 노래일수록 음색이 잘 잡혀요", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(12.dp))
            SongCatalogContent(
                state = state,
                onQueryChange = catalog::setQuery,
                onGenreChange = catalog::setGenre,
                onRefresh = catalog::refresh,
                onLoadMore = catalog::loadMore,
                onSongClick = { id -> state.songs.firstOrNull { it.id == id }?.let(onPick) },
                searchPlaceholder = "제목이나 가수로 찾기",
                modifier = Modifier.fillMaxWidth().heightIn(min = 320.dp, max = 560.dp),
            )
        }
    }
}

// ---------------------------------------------------------------- 공통 껍데기

/** 홀로 화면 뼈대 — 배경·로고·구체·가운데·하단. 세로는 전부 비율이라 기기 크기를 가리지 않는다 */
@Composable
private fun HoloChrome(
    logoVisible: Boolean,
    globeScale: Float,
    absorbing: Boolean,
    topPill: Pair<String, () -> Unit>?,
    error: String?,
    /** 왼쪽 위 알약(예: 녹음 중 "곡 다시 고르기"). [topPill] 과 같은 모양 */
    startPill: Pair<String, () -> Unit>? = null,
    middle: @Composable () -> Unit,
    bottom: @Composable BoxScope.() -> Unit,
    globeOverlay: @Composable () -> Unit = {},
    flood: Boolean = false,
    onFloodCovered: () -> Unit = {},
    onFloodDone: () -> Unit = {},
) {
    Box(Modifier.fillMaxSize().background(HoloBase)) {
        HoloBackground()
        // 로고·구체는 남는 공간을 나눠 갖고, 안내와 버튼은 필요한 만큼 차지한다.
        // 고정 비율로 두면 글자 크게 설정에서 버튼과 문구가 잘린다.
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(bottom = 14.dp)) {
            Box(Modifier.fillMaxWidth().heightIn(min = 62.dp, max = 96.dp).weight(0.11f, fill = false), contentAlignment = Alignment.Center) {
                androidx.compose.animation.AnimatedVisibility(visible = logoVisible, enter = fadeIn(tween(450)), exit = fadeOut(tween(450))) {
                    // 탭 헤더·로그인 화면과 같은 로고 한 장을 쓴다(어두운 배경이라 흰색으로)
                    MiconLogo(barHeight = 26.dp, onDark = true)
                }
            }
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Globe(scale = globeScale, absorbing = absorbing)
                globeOverlay()
            }
            Box(Modifier.fillMaxWidth().heightIn(min = 96.dp).padding(horizontal = 24.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center) { middle() }
            Box(Modifier.fillMaxWidth().padding(horizontal = 24.dp).padding(top = 8.dp),
                contentAlignment = Alignment.BottomCenter, content = bottom)
        }
        error?.let { msg ->
            Surface(
                Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 8.dp, start = 24.dp, end = 24.dp),
                color = Color.White.copy(alpha = 0.94f), shape = RoundedCornerShape(14.dp),
            ) { Text(msg, Modifier.padding(12.dp, 10.dp), color = Ink, fontSize = 13.sp) }
        }
        if (startPill != null) HoloPill(startPill, Modifier.align(Alignment.TopStart).statusBarsPadding().padding(top = 12.dp, start = 16.dp))
        if (topPill != null) HoloPill(topPill, Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top = 12.dp, end = 16.dp))
        Flood(active = flood, onCovered = onFloodCovered, onDone = onFloodDone)
    }
}

/** 홀로 화면 위쪽의 반투명 알약 버튼("나중에 ›" · "결과 바로 보기 ›" · "‹ 곡 다시 고르기") */
@Composable
private fun HoloPill(pill: Pair<String, () -> Unit>, modifier: Modifier) {
    Box(
        modifier.height(32.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.18f))
            .border(1.dp, Color.White.copy(alpha = 0.55f), CircleShape)
            .clickable(onClick = pill.second).padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(pill.first, color = Color.White, fontSize = 12.sp, fontFamily = MiconMonoFamily) }
}

/** 홀로 화면의 흰 알약 버튼 */
@Composable
private fun HoloButton(text: String, enabled: Boolean = true, onClick: () -> Unit) {
    Box(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).clip(CircleShape)
            .background(Color.White.copy(alpha = if (enabled) 1f else 0.6f))
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text, color = HoloButtonText, fontSize = 16.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, maxLines = 2)
    }
}

/** 홀로·결과 화면이 같은 인스턴스를 본다 — 루트(Activity 범위)에서 만들어 넘긴다 */
@Composable
fun holoViewModel(): HoloViewModel {
    val context = LocalContext.current
    val app = context.appContainer
    return viewModel(factory = viewModelFactory {
        initializer { HoloViewModel(context.applicationContext, app.songs, app.backend.api, app.mediaHttp) }
    })
}

/** 이 화면에서만 상태바·내비 바 아이콘을 밝게. 벗어나면 테마 기본(어두움)으로 되돌린다 */
@Composable
private fun LightStatusBarIcons() {
    val view = LocalView.current
    DisposableEffect(view) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        controller?.isAppearanceLightStatusBars = false
        controller?.isAppearanceLightNavigationBars = false
        onDispose {
            controller?.isAppearanceLightStatusBars = true
            controller?.isAppearanceLightNavigationBars = true
        }
    }
}

// ---------------------------------------------------------------- background

@Composable
private fun HoloBackground() {
    val t = rememberInfiniteTransition(label = "holo-bg")
    val phases = listOf(15000, 18000, 21000, 17000, 24000).mapIndexed { i, ms ->
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "blob$i")
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.linearGradient(listOf(HoloG1, HoloG2, HoloG3, HoloG4), start = Offset(0f, 0f), end = Offset(600f, 1400f)),
        ),
    )
    Canvas(Modifier.fillMaxSize().blur(40.dp).alpha(0.85f)) {
        val w = size.width; val h = size.height
        val anchors = listOf(Offset(0.12f, 0.18f), Offset(0.9f, 0.16f), Offset(0.1f, 0.78f), Offset(0.92f, 0.82f), Offset(0.5f, 0.5f))
        val sizes = listOf(300f, 340f, 290f, 320f, 230f)
        anchors.forEachIndexed { i, a ->
            val p = phases[i].value * 2f * PI.toFloat()
            val cx = w * a.x + cos(p + i) * w * 0.10f
            val cy = h * a.y + sin(p * 1.3f + i) * h * 0.07f
            val r = sizes[i] * density * 0.55f * (0.9f + 0.1f * sin(p * 2f))
            drawCircle(Brush.radialGradient(listOf(BlobColors[i].copy(alpha = 0.85f), BlobColors[i].copy(alpha = 0f)), Offset(cx, cy), r), r, Offset(cx, cy))
        }
    }
}

// ---------------------------------------------------------------- globe

/**
 * 흰 비눗방울 구체. 메타볼(goo) 필터 위에 가운데 덩어리 + 표면을 미는 덩어리 5개, 녹음 중엔 바깥에서
 * 크기가 제각각인 방울 12개가 중심으로 모여 흡수된다. 표면 반점이 천천히 헤엄친다.
 */
@Composable
private fun Globe(scale: Float, absorbing: Boolean) {
    val animatedScale by animateFloatAsState(scale, tween(1100, easing = FastOutSlowInEasing), label = "globe-scale")
    val t = rememberInfiniteTransition(label = "globe")
    val floatY by t.animateFloat(-1f, 1f, infiniteRepeatable(tween(4500, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    val lobePhases = listOf(6200, 7400, 8100, 9300, 10500).mapIndexed { i, ms ->
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart), label = "lobe$i")
    }
    val breathe by t.animateFloat(0f, 1f, infiniteRepeatable(tween(6500, easing = LinearEasing), RepeatMode.Restart), label = "breathe")
    val bubblePhases = (0 until 12).map { i ->
        val ms = listOf(6800, 5900, 5100, 4400, 3900)[i % 5]
        t.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = LinearEasing), RepeatMode.Restart, initialStartOffset = androidx.compose.animation.core.StartOffset(i * 380)), label = "ab$i")
    }
    val swim by t.animateFloat(0f, 1f, infiniteRepeatable(tween(11000, easing = LinearEasing), RepeatMode.Restart), label = "swim")
    val effect = remember { gooEffect() }
    val absorbAlpha by animateFloatAsState(if (absorbing) 1f else 0f, tween(600), label = "absorb")

    BoxWithConstraints(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        val base = 108.dp
        Canvas(
            Modifier.fillMaxSize().graphicsLayer {
                renderEffect = effect
                translationY = floatY * 6.dp.toPx()
            },
        ) {
            val c = center
            val r = base.toPx() / 2f * animatedScale
            // 중심 덩어리 — 살짝 숨쉬듯 타원으로
            val bx = 1f + 0.03f * sin(breathe * 2f * PI.toFloat())
            val by = 1f - 0.03f * sin(breathe * 2f * PI.toFloat())
            drawOval(Color.White, topLeft = Offset(c.x - r * bx, c.y - r * by), size = androidx.compose.ui.geometry.Size(2 * r * bx, 2 * r * by))
            // 표면을 미는 덩어리
            lobePhases.forEachIndexed { i, ph ->
                val a = ph.value * 2f * PI.toFloat() + i * 1.26f
                val d = r * (0.62f + 0.14f * sin(a * 1.7f))
                drawCircle(Color.White, radius = r * 0.34f, center = Offset(c.x + cos(a) * d, c.y + sin(a) * d))
            }
            // 흡수 방울 (녹음 중)
            if (absorbAlpha > 0f) bubblePhases.forEachIndexed { i, ph ->
                val p = ph.value
                val angle = i * (2f * PI.toFloat() / 12f) + 0.4f
                val startR = (150f + (i * 37 % 80)) * density
                val dist = startR * (1f - p)
                val sz = listOf(13f, 20f, 27f, 35f, 41f)[i % 5] * density / 2f * (0.4f + 1.4f * p)
                drawCircle(Color.White.copy(alpha = absorbAlpha * (0.3f + 0.7f * p)), radius = sz, center = Offset(c.x + cos(angle) * dist, c.y + sin(angle) * dist))
            }
        }
        // 표면 광택: 헤엄치는 밝은 반점 + 좌상단 하이라이트 + 파란 빛 (구체 안으로 클립)
        Box(
            Modifier.size(base * animatedScale).clip(CircleShape),
        ) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.width
                val a = swim * 2f * PI.toFloat()
                fun spot(fx: Float, fy: Float, rr: Float, col: Color) {
                    val cx = w * fx + cos(a + fx * 7) * w * 0.10f
                    val cy = w * fy + sin(a * 0.8f + fy * 5) * w * 0.10f
                    drawCircle(Brush.radialGradient(listOf(col, col.copy(alpha = 0f)), Offset(cx, cy), w * rr), w * rr, Offset(cx, cy))
                }
                spot(0.30f, 0.28f, 0.30f, Color.White.copy(alpha = 0.55f))
                spot(0.70f, 0.66f, 0.26f, Color(0xFF9FD8FF).copy(alpha = 0.35f))
                spot(0.62f, 0.30f, 0.22f, Color(0xFFD9B8FF).copy(alpha = 0.28f))
                spot(0.36f, 0.72f, 0.24f, Color.White.copy(alpha = 0.30f))
                // 가장자리로 갈수록 살짝 비치는 배경색
                drawCircle(Brush.radialGradient(listOf(Color.Transparent, Color.Transparent, HoloG2.copy(alpha = 0.22f)), center, w / 2f), w / 2f, center)
            }
        }
    }
}

/** SVG `gooF`: blur 6 → alpha ×22 −10 → blur 2.4 */
private fun gooEffect(): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val d = android.content.res.Resources.getSystem().displayMetrics.density
    val blur = RenderEffect.createBlurEffect(6f * d, 6f * d, Shader.TileMode.DECAL)
    val m = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 22f, -10f * 255f))
    val th = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(m), blur)
    return RenderEffect.createBlurEffect(2.4f * d, 2.4f * d, th, Shader.TileMode.DECAL).asComposeRenderEffect()
}

// ---------------------------------------------------------------- idle bottom

// ---------------------------------------------------------------- rec bottom

@Composable
private fun PillPlayer(state: HoloViewModel.State, modifier: Modifier = Modifier) {
    val picked = state.picked
    Row(
        modifier.fillMaxWidth().height(78.dp).clip(CircleShape).background(Color.White.copy(alpha = 0.94f))
            .padding(start = 8.dp, end = 22.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactDisc(title = picked?.title ?: "", artist = picked?.artist, imageUrl = picked?.imageUrl, size = 62.dp, spinning = true, spinPeriodMs = 6000)
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            val s = state.elapsedSec.toInt().coerceIn(0, 30)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(picked?.title ?: "노래", color = Ink, fontSize = 14.5.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                Text("0:${"%02d".format(s)} / 0:30", color = Muted, fontSize = 12.sp, fontFamily = MiconMonoFamily)
            }
            Text(picked?.artist.orEmpty(), color = Muted, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).clip(CircleShape).background(Color(0xFFE4E0F2))) {
                Box(
                    Modifier.fillMaxWidth(state.progress).fillMaxSize()
                        .background(Brush.horizontalGradient(listOf(Color(0xFF7C5BF0), Color(0xFF5FA8FB)))),
                )
            }
        }
    }
}

// ---------------------------------------------------------------- flood transition

/** 구체 중심에서 흰 원이 화면을 덮으면 결과 화면으로 넘어가고, 이어서 서서히 걷힌다 */
@Composable
private fun Flood(active: Boolean, onCovered: () -> Unit, onDone: () -> Unit) {
    val scale = remember { Animatable(0f) }
    val alpha = remember { Animatable(1f) }
    LaunchedEffect(active) {
        if (!active) return@LaunchedEffect
        scale.snapTo(0f); alpha.snapTo(1f)
        scale.animateTo(15f, tween(950, easing = FastOutSlowInEasing)) { if (value >= 9.5f) onCovered() }
        alpha.animateTo(0f, tween(600))
        onDone()
    }
    if (active) Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Box(Modifier.size(120.dp).graphicsLayer { scaleX = scale.value; scaleY = scale.value; this.alpha = alpha.value }.background(Color.White, CircleShape))
    }
}
