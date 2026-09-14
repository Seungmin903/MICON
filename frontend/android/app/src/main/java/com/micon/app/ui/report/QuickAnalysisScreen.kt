package com.micon.app.ui.report

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cancel
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.audio.PitchCapture
import com.micon.app.audio.PianoPlayer
import com.micon.app.data.local.UserStore
import com.micon.app.data.voice.ScaleAnalysisSubmitter
import com.micon.app.data.voice.ScaleSubmitResult
import com.micon.app.ui.rememberAppContainer
import com.micon.app.scoring.ScoringConstants
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.charts.PianoRangeChart
import com.micon.app.ui.charts.PianoTargetKeyboard
import com.micon.app.ui.charts.PitchGauge
import com.micon.app.ui.charts.WaveformBars
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PillChip
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.StepBar
import com.micon.app.ui.theme.GreenChipBg
import com.micon.app.ui.theme.GreenChipText
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import com.micon.app.ui.theme.RedChipBg
import com.micon.app.ui.theme.Teal
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * 음성 간편 분석.
 *
 * 1단계 마이크 확인: 편안하게 "아-" → 입력 상태 확인 + **주변 소음 바닥(noise floor) 보정**. 목소리 유형(보통/낮음/높음)을 고르면
 *   편한 음부터 시작한다(기본: C4 → A3 → E4 → F3 → G4). 못 내는 음은 언제든 건너뛸 수 있고, 그러면 확인된 음들의 중심에서 가장 가까운
 *   새 음이 대체 추천된다 — 확인 개수는 건너뛰기로 줄지 않으므로 반드시 5개를 채운다.
 * 2~6단계 기준음 맞추기(5개): 기준음마다 건반에 표시하고, 감지된 음을 건반 위 마커로 보여 준다. 건반을 누르면 그 음을 듣고 기준음으로
 *   바꿀 수 있다(내 음역에 맞게 조정). ±50¢ 이내를 0.6초(누적, 잠깐 끊겨도 서서히만 감소) 유지하면 다음 음으로.
 *   판정은 소음 바닥의 3배 이상·명료도 0.75 이상인 프레임만 쓰고, 최근 5프레임 중앙값으로 흔들림을 줄인다.
 * 완료: 확인된 음들로 편안한 음역(comfortLow~High)을 채운 임시 프로필을 UserStore 에 넣고, 로그인 사용자는 녹음 WAV 를
 *   백엔드에 올려 SCALE 분석을 요청한다([ScaleAnalysisSubmitter]). 서버 결과가 COMPLETED 면 리포트 탭이 임시 프로필을 덮는다.
 */
class QuickAnalysisViewModel(appContext: Context, private val submitter: ScaleAnalysisSubmitter) : ViewModel() {
    enum class Step { MIC_CHECK, PITCH, DONE }

    /** 서버 분석 제출 상태. NONE 은 아직 안 함(측정 중), UNAVAILABLE 은 비회원·AI 미준비(정상). */
    enum class SubmitPhase { NONE, SENDING, ACCEPTED, UNAVAILABLE, ERROR }

    data class Submit(val phase: SubmitPhase = SubmitPhase.NONE, val message: String = "")

    /** 시작 기준음 계획 5개. 편안한 중간 음에서 시작해 아래·위로 번갈아 넓혀 간다. 못 내는 음은 건너뛰면 중심 쪽 다른 음으로 대체된다. */
    enum class VoiceType(val label: String, val targets: List<Int>) {
        NORMAL("보통", listOf(60, 57, 64, 53, 67)),      // C4 → A3 → E4 → F3 → G4
        LOW("낮은 목소리", listOf(55, 52, 59, 48, 62)),   // G3 → E3 → B3 → C3 → D4
        HIGH("높은 목소리", listOf(64, 60, 67, 57, 72)),  // E4 → C4 → G4 → A3 → C5
    }

    data class State(
        val step: Step = Step.MIC_CHECK,
        val micRunning: Boolean = false,
        val level: Float = 0f,                 // 0~1 표시용
        val micGood: Boolean? = null,          // 입력 상태 좋음/불안정
        val noiseLevel: Int = 0,               // 0 조용 · 1 보통 · 2 시끄러움
        val voiceType: VoiceType = VoiceType.NORMAL,
        val targets: List<Int> = VoiceType.NORMAL.targets,
        val targetMidi: Int = VoiceType.NORMAL.targets.first(),
        val confirmed: List<Int> = emptyList(),
        val failed: List<Int> = emptyList(),          // 건너뛴 음 (다시 추천하지 않음)
        val replacedFrom: Int? = null,                // 건너뛴 직후: 어떤 음 대신 추천됐는지
        val detectedMidi: Double? = null,      // 최근 5프레임 중앙값 (무성 null)
        val cents: Float? = null,              // 기준음 대비 오차(접지 않은 실제 값)
        val matched: Boolean = false,
        val holdProgress: Float = 0f,          // 0~1
        val justConfirmed: Boolean = false,    // 확인 직후 잠깐 true (성공 표시)
        val tonePlaying: Boolean = false,
        val submit: Submit = Submit(),
        val error: String? = null,
    ) {
        val totalSteps: Int get() = 1 + REQUIRED_NOTES
        val stepNumber: Int get() = when (step) { Step.MIC_CHECK -> 1; Step.PITCH -> 2 + confirmed.size; Step.DONE -> totalSteps }
    }

    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val capture = PitchCapture()
    private val piano = PianoPlayer(appContext)
    private val cacheDir = appContext.cacheDir
    private val frames = Channel<PitchCapture.PitchFrame>(64, BufferOverflow.DROP_OLDEST)

    private val levelHistory = ArrayDeque<Double>()     // 마이크 확인용 RMS
    private val noiseHistory = ArrayDeque<Double>()     // 소음 바닥 추정용 RMS (마이크 확인 단계)
    private var noiseFloor = 0.0
    private val midiHistory = ArrayDeque<Double>()      // 최근 유성 프레임 MIDI
    private var matchedFrames = 0
    private var muteUntilMs = 0L                        // 기준음 재생 중 자기 소리 무시
    private var advancing = false

    init {
        viewModelScope.launch { for (f in frames) onFrame(f) }
    }

    fun startMic() {
        if (capture.isRunning) return
        try {
            capture.start(File(cacheDir, "quick-analysis.wav")) { f -> frames.trySend(f) }
            _state.update { it.copy(micRunning = true, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "마이크를 사용할 수 없습니다: ${e.message}") }
        }
    }

    private fun onFrame(f: PitchCapture.PitchFrame) {
        val now = System.currentTimeMillis()
        val level = (f.rms / 0.12).toFloat().coerceIn(0f, 1f)
        val s = _state.value

        if (s.step == Step.MIC_CHECK) {
            levelHistory.addLast(f.rms); if (levelHistory.size > 40) levelHistory.removeFirst()
            noiseHistory.addLast(f.rms); if (noiseHistory.size > 150) noiseHistory.removeFirst()
            val mean = levelHistory.average()
            val jitter = if (mean > 0) levelHistory.map { abs(it - mean) }.average() / mean else 1.0
            noiseFloor = percentile(noiseHistory, 0.2)
            // 소음 바닥보다 확실히 큰 소리가 꾸준히 들어올 때만 "좋음" (조용히 있어도 좋음이 뜨던 문제)
            val speaking = mean > max(ScoringConstants.RMS_MIN * 3, noiseFloor * 2.5)
            val good: Boolean? = when { levelHistory.size <= 10 || !speaking -> null; jitter < 0.6 -> true; else -> false }   // 조용하면 "소리를 내주세요"
            _state.update { it.copy(level = level, micGood = good, noiseLevel = noiseLevelOf(noiseFloor)) }
            return
        }
        if (s.step != Step.PITCH) return
        if (now < muteUntilMs) {   // 기준음이 스피커에서 울리는 동안은 판정 보류
            _state.update { it.copy(level = level, tonePlaying = true) }
            return
        }
        val gate = max(ScoringConstants.RMS_MIN * QUICK_RMS_FACTOR, noiseFloor * NOISE_FACTOR)
        val voiced = f.f0 > 0 && f.clarity >= QUICK_CLARITY_MIN && f.rmsLow >= gate
        val midi = if (voiced) ScoringMath.hzToMidi(f.f0) else null
        if (midi != null) { midiHistory.addLast(midi); if (midiHistory.size > 5) midiHistory.removeFirst() } else if (midiHistory.isNotEmpty()) midiHistory.removeFirst()
        val detected = if (midiHistory.size >= 3) ScoringMath.median(midiHistory.toList()) else null
        val cents = detected?.let { ((it - s.targetMidi) * 100).toFloat() }
        val matched = cents != null && abs(cents) <= QUICK_TOLERANCE_CENTS
        matchedFrames = if (matched) matchedFrames + 1 else (matchedFrames - 2).coerceAtLeast(0)   // 잠깐 끊겨도 서서히만 감소
        val hold = (matchedFrames.toFloat() / HOLD_FRAMES).coerceIn(0f, 1f)
        _state.update {
            it.copy(level = level, detectedMidi = detected, cents = cents, matched = matched, holdProgress = hold, tonePlaying = false,
                replacedFrom = if (detected != null) null else it.replacedFrom)
        }
        if (hold >= 1f && !advancing) confirmCurrent()
    }

    private fun confirmCurrent() {
        advancing = true
        val s = _state.value
        _state.update { it.copy(confirmed = it.confirmed + s.targetMidi, justConfirmed = true, holdProgress = 1f) }
        viewModelScope.launch {
            delay(700)
            advance()
        }
    }

    /** 확인 뒤 다음 음: 계획 중 아직 안 쓴 음 → 없으면 중심에서 가장 가까운 새 음. 5개를 채우면 완료. */
    private fun advance() {
        val s = _state.value
        resetTargetState()
        if (s.confirmed.size >= REQUIRED_NOTES) { finish(); return }
        val used = (s.confirmed + s.failed).toSet()
        val next = s.targets.firstOrNull { it !in used } ?: nearestUnused(s, exclude = used)
        _state.update { it.copy(targetMidi = next, justConfirmed = false, holdProgress = 0f, cents = null, detectedMidi = null, matched = false, replacedFrom = null) }
        advancing = false
    }

    /** 건너뛴 음 대신 추천할 음: 확인된 음들의 중심(없으면 첫 계획 음)에서 가장 가까운, 아직 확인·실패하지 않은 음. */
    private fun nearestUnused(s: State, exclude: Set<Int>): Int {
        val center = if (s.confirmed.isNotEmpty()) ScoringMath.median(s.confirmed.map { it.toDouble() }).roundToInt() else s.targets.first()
        return (MIN_MIDI..MAX_MIDI).filter { it !in exclude }.minWithOrNull(compareBy({ abs(it - center) }, { abs(it - s.targets.first()) })) ?: center
    }

    private fun resetTargetState() { matchedFrames = 0; midiHistory.clear() }

    fun setVoiceType(type: VoiceType) {
        if (_state.value.step != Step.MIC_CHECK) return
        _state.update { it.copy(voiceType = type, targets = type.targets, targetMidi = type.targets.first()) }
    }

    fun goPitchStep() {
        resetTargetState()
        _state.update { it.copy(step = Step.PITCH, holdProgress = 0f, cents = null, detectedMidi = null, matched = false) }
    }

    /** 건반 탭: 이 음을 듣고 현재 단계의 기준음으로 바꾼다. */
    fun setTarget(midi: Int) {
        if (_state.value.step != Step.PITCH || advancing) return
        resetTargetState()
        _state.update { it.copy(targetMidi = midi, holdProgress = 0f, cents = null, detectedMidi = null, matched = false, replacedFrom = null) }
        playReference()
    }

    fun playReference() {
        val s = _state.value
        piano.play(s.targetMidi)
        muteUntilMs = System.currentTimeMillis() + PianoPlayer.DURATION_MS + 150
        midiHistory.clear()
        _state.update { it.copy(tonePlaying = true, detectedMidi = null, cents = null, matched = false) }
    }

    /** 이 음은 못 내겠다 → 실패로 기록하고, 중심 쪽 다른 음을 추천한다. 확인 개수는 늘지 않으므로 결국 5개를 채운다. */
    fun skipTarget() {
        val s = _state.value
        if (s.step != Step.PITCH || advancing) return
        resetTargetState()
        val failed = s.failed + s.targetMidi
        val replacement = nearestUnused(s.copy(failed = failed), exclude = (s.confirmed + failed).toSet())
        piano.play(replacement)
        muteUntilMs = System.currentTimeMillis() + PianoPlayer.DURATION_MS + 150
        _state.update {
            it.copy(failed = failed, targetMidi = replacement, replacedFrom = s.targetMidi, holdProgress = 0f, cents = null, detectedMidi = null, matched = false, tonePlaying = true)
        }
    }

    private fun finish() {
        if (_state.value.step == Step.DONE) return
        val wav = capture.stop()
        piano.stop()
        val confirmed = _state.value.confirmed
        // 화면에는 확인된 5음으로 만든 임시 프로필을 먼저 보여 주고, 서버 SCALE 분석 결과가 오면 리포트 탭이 덮는다.
        val profile = if (confirmed.isEmpty()) UserStore.VoiceProfile() else {
            val low = confirmed.min(); val high = confirmed.max()
            UserStore.VoiceProfile(
                lowMidi = low - 4, highMidi = high + 4,
                comfortLow = low, comfortHigh = high,
                recommendLow = low + 1, recommendHigh = high - 1,
            )
        }
        UserStore.setProfile(profile)
        _state.update { it.copy(step = Step.DONE, micRunning = false, justConfirmed = false) }
        if (wav != null && confirmed.isNotEmpty()) submitToServer(wav)
    }

    /** 녹음 WAV 를 백엔드에 올려 SCALE 분석을 요청한다(로그인 사용자만). 결과는 리포트 탭이 `latest` 로 읽는다. */
    private fun submitToServer(wav: File) {
        _state.update { it.copy(submit = Submit(SubmitPhase.SENDING, "서버에 녹음을 올리고 있어요")) }
        viewModelScope.launch {
            val result = runCatching { submitter.submit(wav) { msg -> _state.update { it.copy(submit = it.submit.copy(message = msg)) } } }
                .getOrElse { e -> ScaleSubmitResult.Failed("정밀 분석 요청 실패: ${e.message ?: e.toString()}") }
            _state.update {
                it.copy(
                    submit = when (result) {
                        is ScaleSubmitResult.Accepted -> Submit(SubmitPhase.ACCEPTED, "서버가 목소리를 정밀 분석하고 있어요. 결과는 리포트 탭에 채워져요.")
                        is ScaleSubmitResult.Unavailable -> Submit(SubmitPhase.UNAVAILABLE, result.message)
                        is ScaleSubmitResult.Failed -> Submit(SubmitPhase.ERROR, result.message)
                    },
                )
            }
        }
    }

    fun stop() { if (capture.isRunning) capture.stop(); piano.stop(); _state.update { it.copy(micRunning = false) } }

    override fun onCleared() { if (capture.isRunning) capture.stop(); piano.release() }

    private fun percentile(values: Collection<Double>, p: Double): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).roundToInt().coerceIn(0, sorted.size - 1)]
    }

    private fun noiseLevelOf(floor: Double): Int = when {
        floor < ScoringConstants.RMS_MIN -> 0
        floor < ScoringConstants.RMS_MIN * 4 -> 1
        else -> 2
    }

    companion object {
        /** 간편 분석 전용 게이트. 공용 ScoringConstants(엔진·웹과 동기)는 건드리지 않는다. */
        const val QUICK_CLARITY_MIN = 0.75
        const val QUICK_RMS_FACTOR = 3.0          // RMS_MIN×3 ≈ −38 dBFS
        const val NOISE_FACTOR = 3.0              // 소음 바닥의 3배 이상만 목소리로
        const val QUICK_TOLERANCE_CENTS = 50f
        const val HOLD_FRAMES = 30                // 20 ms × 30 = 0.6 초
        const val REQUIRED_NOTES = 5              // 반드시 확인해야 하는 음 개수
        const val MIN_MIDI = 40                   // E2 — 대체 음 추천 범위
        const val MAX_MIDI = 84                   // C6
    }
}

@Composable
fun QuickAnalysisScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val container = rememberAppContainer()
    val vm: QuickAnalysisViewModel = viewModel(factory = viewModelFactory { initializer { QuickAnalysisViewModel(context.applicationContext, container.scaleAnalysis) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startMic() }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startMic()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    // 서버 제출이 진행 중이면 화면을 떠나지 않는다(ViewModel 이 사라지면 업로드가 취소된다). 끝나면 잠시 보여 주고 리포트로.
    LaunchedEffect(state.step, state.submit.phase) {
        if (state.step == QuickAnalysisViewModel.Step.DONE && state.submit.phase != QuickAnalysisViewModel.SubmitPhase.SENDING) { delay(2200); onDone() }
    }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("목소리 분석", onBack = { vm.stop(); onBack() })
        Spacer(Modifier.height(16.dp))
        StepBar(state.stepNumber, state.totalSteps)
        when (state.step) {
            QuickAnalysisViewModel.Step.MIC_CHECK -> MicCheckBody(state, onVoiceType = vm::setVoiceType, onStart = vm::goPitchStep, onLater = { vm.stop(); onBack() })
            QuickAnalysisViewModel.Step.PITCH -> PitchBody(state, onKeyTap = vm::setTarget, onPlayReference = vm::playReference, onSkip = vm::skipTarget, onStop = { vm.stop(); onBack() })
            QuickAnalysisViewModel.Step.DONE -> DoneBody(state)
        }
        state.error?.let { Text(it, color = Red, fontSize = 13.sp, modifier = Modifier.padding(24.dp)) }
    }
}

@Composable
private fun MicCheckBody(state: QuickAnalysisViewModel.State, onVoiceType: (QuickAnalysisViewModel.VoiceType) -> Unit, onStart: () -> Unit, onLater: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(28.dp))
        Box(Modifier.size(120.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Mic, null, Modifier.size(54.dp), tint = Purple)
        }
        Spacer(Modifier.height(20.dp))
        Text("측정 전, 목소리를 확인할게요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = InkSoft)
        Spacer(Modifier.height(6.dp))
        Text("편안하게 ‘아-’ 소리를 내주세요", fontSize = 14.sp, color = Grey)
        Spacer(Modifier.height(18.dp))
        MiconCard {
            Text("마이크 입력", fontSize = 14.sp, color = InkSoft)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().background(Lavender.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                WaveformBars(state.level)
            }
            Spacer(Modifier.height(12.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
                when (state.micGood) {
                    true -> StatusChip("입력 상태 좋음", GreenChipBg, GreenChipText, Icons.Filled.CheckCircle)
                    false -> StatusChip("입력 상태 불안정", RedChipBg, Red, Icons.Filled.Cancel)
                    null -> StatusChip("소리를 내주세요", Lavender, Purple, Icons.Outlined.Mic)
                }
            }
            Spacer(Modifier.height(10.dp))
            val noiseText = when (state.noiseLevel) { 0 -> "주변 소음: 조용해요"; 1 -> "주변 소음: 보통이에요"; else -> "주변 소음이 커요 — 조용한 곳이 더 정확해요" }
            Text(noiseText, fontSize = 12.sp, color = if (state.noiseLevel == 2) Red else Grey, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        Text("목소리 유형을 고르면 편한 음부터 시작해요", fontSize = 14.sp, color = InkSoft, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
            QuickAnalysisViewModel.VoiceType.values().forEach { type ->
                PillChip(type.label, selected = state.voiceType == type, onClick = { onVoiceType(type) }, fontSize = 13.sp)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text("시작 음 " + state.targets.joinToString(" → ") { ScoringMath.midiName(it) } + " · 측정 중에 건반으로 바꿀 수 있어요", fontSize = 12.sp, color = Grey, textAlign = TextAlign.Center)
        Spacer(Modifier.height(28.dp))
        PrimaryButton("측정 시작", onStart, Modifier.fillMaxWidth(), enabled = state.micRunning)
        Spacer(Modifier.height(14.dp))
        Text("나중에 할게요", fontSize = 17.sp, color = Purple, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onLater))
        Spacer(Modifier.height(32.dp))
    }
}

@Composable
private fun StatusChip(text: String, bg: Color, fg: Color, icon: ImageVector) {
    Row(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PitchBody(
    state: QuickAnalysisViewModel.State,
    onKeyTap: (Int) -> Unit,
    onPlayReference: () -> Unit,
    onSkip: () -> Unit,
    onStop: () -> Unit,
) {
    val target = state.targetMidi
    val names = listOf(target - 2, target - 1, target, target + 1, target + 2).map { ScoringMath.midiName(it) }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(14.dp))
        Text("${state.confirmed.size + 1}번째 음 · 편하게 이 음을 내보세요", fontSize = 15.sp, color = Grey)
        Spacer(Modifier.height(8.dp))
        PitchGauge(ScoringMath.midiName(target), names, state.cents?.coerceIn(-200f, 200f), state.matched, state.holdProgress, gaugeHeight = 128.dp, ringSize = 76.dp)
        Spacer(Modifier.height(4.dp))
        val (title, subtitle, color) = hintFor(state)
        Text(title, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = color, textAlign = TextAlign.Center)
        Text(subtitle, fontSize = 14.sp, color = Grey, textAlign = TextAlign.Center)
        Spacer(Modifier.height(14.dp))
        MiconCard(padding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 12.dp)) {
            PianoTargetKeyboard(targetMidi = target, detectedMidi = state.detectedMidi, matched = state.matched, onKeyTap = onKeyTap)
            Spacer(Modifier.height(6.dp))
            Text("건반을 누르면 그 음을 듣고 기준음으로 바꿀 수 있어요", fontSize = 12.sp, color = Grey, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(onClick = onPlayReference, shape = RoundedCornerShape(14.dp), color = if (state.tonePlaying) Lavender else Color.White, border = BorderStroke(1.dp, Lavender), shadowElevation = 1.dp) {
                Row(Modifier.padding(horizontal = 18.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Campaign, null, Modifier.size(20.dp), tint = Purple)
                    Spacer(Modifier.width(8.dp))
                    Text("기준음 들어보기", fontSize = 15.sp, color = Purple, fontWeight = FontWeight.SemiBold)
                }
            }
            Surface(onClick = onSkip, enabled = !state.justConfirmed, shape = RoundedCornerShape(14.dp), color = Color.White, border = BorderStroke(1.dp, Lavender), shadowElevation = 1.dp) {
                Text("이 음 건너뛰기", fontSize = 15.sp, color = InkSoft, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            if (state.confirmed.isEmpty()) "확인된 음 0 / ${QuickAnalysisViewModel.REQUIRED_NOTES} · 못 내는 음은 건너뛰면 다른 음을 추천해요"
            else "확인된 음 ${state.confirmed.size} / ${QuickAnalysisViewModel.REQUIRED_NOTES}: " + state.confirmed.joinToString(" · ") { ScoringMath.midiName(it) },
            fontSize = 13.sp, color = if (state.confirmed.isEmpty()) Grey else Teal, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(24.dp))
        OutlinePurpleButton("측정 그만하기", onStop, Modifier.fillMaxWidth(), height = 56.dp)
        Spacer(Modifier.height(28.dp))
    }
}

/** 상태별 안내 문구: (제목, 부제, 색) */
private fun hintFor(state: QuickAnalysisViewModel.State): Triple<String, String, Color> {
    if (state.justConfirmed) return Triple("좋아요, ${ScoringMath.midiName(state.targetMidi)} 확인!", "다음 음으로 넘어갈게요", Teal)
    if (state.tonePlaying) {
        val from = state.replacedFrom
        return if (from != null) Triple("${ScoringMath.midiName(from)} 대신 ${ScoringMath.midiName(state.targetMidi)}", "이 음을 들어보고 따라 내보세요", Purple)
        else Triple("기준음을 들어보세요", "소리가 끝나면 따라 내보세요", Purple)
    }
    val cents = state.cents ?: run {
        val from = state.replacedFrom
        return if (from != null) Triple("${ScoringMath.midiName(state.targetMidi)} 을 추천해요", "${ScoringMath.midiName(from)} 은 건너뛰었어요 · ‘아-’ 하고 내보세요", InkSoft)
        else Triple("소리를 내주세요", "‘아-’ 하고 편하게 길게 내면 돼요", InkSoft)
    }
    if (state.matched) return Triple("음정이 잘 맞아요", "그대로 조금만 유지하세요 (${(state.holdProgress * 100).roundToInt()}%)", Teal)
    val semis = cents / 100f
    return when {
        semis <= -7f -> Triple("한 옥타브 정도 낮아요", "같은 음을 한 옥타브 높게 내보세요", Red)
        semis >= 7f -> Triple("한 옥타브 정도 높아요", "같은 음을 한 옥타브 낮게 내보세요", Red)
        semis < 0f -> Triple("조금 더 높게 ↑", "${abs(semis).roundToInt().coerceAtLeast(1)}반음 정도 낮아요", Red)
        else -> Triple("조금 더 낮게 ↓", "${semis.roundToInt().coerceAtLeast(1)}반음 정도 높아요", Red)
    }
}

@Composable
private fun DoneBody(state: QuickAnalysisViewModel.State) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(48.dp))
        Box(Modifier.size(120.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Filled.CheckCircle, null, Modifier.size(60.dp), tint = Purple)
        }
        Spacer(Modifier.height(22.dp))
        Text("측정 완료", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = InkSoft)
        Spacer(Modifier.height(8.dp))
        if (state.confirmed.isEmpty()) {
            Text("확인된 음이 없어 기본 프로필로 시작해요", fontSize = 14.sp, color = Grey, textAlign = TextAlign.Center)
        } else {
            val low = state.confirmed.min(); val high = state.confirmed.max()
            Text("편안한 음역 ${ScoringMath.midiName(low)} ~ ${ScoringMath.midiName(high)}", fontSize = 16.sp, color = Purple, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(18.dp))
            MiconCard { PianoRangeChart(hlLow = low, hlHigh = high, lowMidi = (low - 7).coerceAtLeast(36), highMidi = (high + 7).coerceAtMost(96)) }
        }
        Spacer(Modifier.height(12.dp))
        Text(
            when (state.submit.phase) {
                QuickAnalysisViewModel.SubmitPhase.NONE -> "리포트 탭에서 결과를 확인할 수 있어요"
                else -> state.submit.message
            },
            fontSize = 13.sp, color = if (state.submit.phase == QuickAnalysisViewModel.SubmitPhase.ERROR) Red else Grey, textAlign = TextAlign.Center,
        )
    }
}
