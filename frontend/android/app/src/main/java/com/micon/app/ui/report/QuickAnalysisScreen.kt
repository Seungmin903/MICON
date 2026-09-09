package com.micon.app.ui.report

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
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
import com.micon.app.data.local.UserStore
import com.micon.app.scoring.ScoringConstants
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.charts.PitchGauge
import com.micon.app.ui.charts.WaveformBars
import com.micon.app.ui.common.BackHeader
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.OutlinePurpleButton
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.GreenChipBg
import com.micon.app.ui.theme.GreenChipText
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.Red
import com.micon.app.ui.theme.RedChipBg
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import kotlin.math.abs

/**
 * 음성 간편 분석 (Figma 4 프레임).
 * 1/2 마이크 확인: 편안하게 "아-" → 입력 상태 좋음/불안정 (RMS 기준)
 * 2/2 기준음 G3 맞추기: YIN 피치 → cents 오차, ±45¢ 이내 3초 유지하면 완료
 * 완료 시 UserStore.profile 에 임시 프로필을 넣는다. (실제로는 녹음 WAV 를 엔진 POST /jobs 로 정밀 분석)
 */
class QuickAnalysisViewModel(appContext: Context) : ViewModel() {
    enum class Step { MIC_CHECK, PITCH, DONE }

    data class State(
        val step: Step = Step.MIC_CHECK,
        val micRunning: Boolean = false,
        val level: Float = 0f,            // 0~1 표시용
        val micGood: Boolean? = null,     // 입력 상태 좋음/불안정
        val cents: Float? = null,         // 기준음 대비 오차 (무성 null)
        val matched: Boolean = false,
        val holdProgress: Float = 0f,     // 0~1 (3초)
        val error: String? = null,
    )

    val targetMidi = 55 // G3
    private val _state = MutableStateFlow(State())
    val state = _state.asStateFlow()
    private val capture = PitchCapture()
    private val cacheDir = appContext.cacheDir
    private var holdStart: Long? = null
    private var levelHistory = ArrayDeque<Double>()

    fun startMic() {
        if (capture.isRunning) return
        try {
            capture.start(File(cacheDir, "quick-analysis.wav")) { f -> viewModelScope.launch { onFrame(f) } }
            _state.update { it.copy(micRunning = true, error = null) }
        } catch (e: Exception) {
            _state.update { it.copy(error = "마이크를 사용할 수 없습니다: ${e.message}") }
        }
    }

    private fun onFrame(f: PitchCapture.PitchFrame) {
        val level = (f.rms / 0.12).toFloat().coerceIn(0f, 1f)
        levelHistory.addLast(f.rms); if (levelHistory.size > 40) levelHistory.removeFirst()
        val mean = levelHistory.average()
        val jitter = if (mean > 0) levelHistory.map { abs(it - mean) }.average() / mean else 1.0
        val good = mean > ScoringConstants.RMS_MIN * 2 && jitter < 0.6
        val voiced = ScoringMath.isVoiced(f.f0, f.clarity, f.rms, f.rmsLow)
        val cents = if (voiced) ScoringMath.hzToMidi(f.f0)?.let { m -> ((ScoringMath.fold(m - targetMidi)) * 100).toFloat() } else null
        val matched = cents != null && abs(cents) <= ScoringConstants.PITCH_GOOD_CENTS
        val now = System.currentTimeMillis()
        if (_state.value.step == Step.PITCH) {
            if (matched) { if (holdStart == null) holdStart = now } else holdStart = null
        }
        val hold = holdStart?.let { ((now - it) / 3000f).coerceIn(0f, 1f) } ?: 0f
        _state.update { it.copy(level = level, micGood = if (levelHistory.size > 10) good else null, cents = cents, matched = matched, holdProgress = hold) }
        if (_state.value.step == Step.PITCH && hold >= 1f) finish()
    }

    fun goPitchStep() { holdStart = null; _state.update { it.copy(step = Step.PITCH, holdProgress = 0f) } }

    private fun finish() {
        if (_state.value.step == Step.DONE) return
        UserStore.lastRecording = capture.stop()          // 추천 서버 업로드용 (RemoteRecommendRepository)
        // TODO: 저장된 WAV 를 엔진에 올려(POST /jobs, sourceType=vocal) 실제 음역·안정도를 계산한다.
        UserStore.setProfile(UserStore.VoiceProfile())
        _state.update { it.copy(step = Step.DONE, micRunning = false) }
    }

    fun stop() { if (capture.isRunning) capture.stop(); _state.update { it.copy(micRunning = false) } }

    override fun onCleared() { if (capture.isRunning) capture.stop() }

    @Suppress("unused")
    private suspend fun demoDelay() = delay(1)
}

@Composable
fun QuickAnalysisScreen(onBack: () -> Unit, onDone: () -> Unit) {
    val context = LocalContext.current
    val vm: QuickAnalysisViewModel = viewModel(factory = viewModelFactory { initializer { QuickAnalysisViewModel(context.applicationContext) } })
    val state by vm.state.collectAsStateWithLifecycle()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted -> if (granted) vm.startMic() }

    LaunchedEffect(Unit) {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) vm.startMic()
        else permission.launch(Manifest.permission.RECORD_AUDIO)
    }
    LaunchedEffect(state.step) { if (state.step == QuickAnalysisViewModel.Step.DONE) { delay(600); onDone() } }

    Column(Modifier.fillMaxSize().background(Color.White)) {
        BackHeader("목소리 분석", onBack = { vm.stop(); onBack() })
        Spacer(Modifier.height(20.dp))
        StepBar(if (state.step == QuickAnalysisViewModel.Step.MIC_CHECK) 1 else 2, 2)
        when (state.step) {
            QuickAnalysisViewModel.Step.MIC_CHECK -> MicCheckBody(state, onStart = vm::goPitchStep, onLater = { vm.stop(); onBack() })
            else -> PitchBody(state, vm.targetMidi, onStop = { vm.stop(); onBack() })
        }
        state.error?.let { Text(it, color = Red, fontSize = 13.sp, modifier = Modifier.padding(24.dp)) }
    }
}

@Composable
private fun StepBar(step: Int, total: Int) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.fillMaxWidth().height(6.dp)) {
            drawLine(Lavender, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), size.height, StrokeCap.Round)
            drawLine(Purple, Offset(0f, size.height / 2), Offset(size.width * step / total, size.height / 2), size.height, StrokeCap.Round)
        }
        Spacer(Modifier.height(10.dp))
        Text("$step / $total", fontSize = 14.sp, color = Grey)
    }
}

@Composable
private fun MicCheckBody(state: QuickAnalysisViewModel.State, onStart: () -> Unit, onLater: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(56.dp))
        Box(Modifier.size(150.dp).background(Lavender, CircleShape), contentAlignment = Alignment.Center) {
            Icon(Icons.Outlined.Mic, null, Modifier.size(64.dp), tint = Purple)
        }
        Spacer(Modifier.height(24.dp))
        Text("측정 전, 목소리를 확인할게요", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = InkSoft)
        Spacer(Modifier.height(8.dp))
        Text("편안하게 ‘아-’ 소리를 내주세요", fontSize = 14.sp, color = Grey)
        Spacer(Modifier.height(20.dp))
        MiconCard {
            Text("마이크 입력", fontSize = 14.sp, color = InkSoft)
            Spacer(Modifier.height(10.dp))
            Box(Modifier.fillMaxWidth().background(Lavender.copy(alpha = 0.6f), RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 6.dp)) {
                WaveformBars(state.level)
            }
            Spacer(Modifier.height(12.dp))
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                when (state.micGood) {
                    true -> StatusChip("입력 상태 좋음", GreenChipBg, GreenChipText, Icons.Filled.CheckCircle)
                    false -> StatusChip("입력 상태 불안정", RedChipBg, Red, Icons.Filled.Cancel)
                    null -> StatusChip("소리를 내주세요", Lavender, Purple, Icons.Outlined.Mic)
                }
            }
            Spacer(Modifier.height(10.dp))
            Text("주변의 소리가 적을수록 정확해요", fontSize = 12.sp, color = Grey, modifier = Modifier.fillMaxWidth(), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        Spacer(Modifier.weight(1f))
        PrimaryButton("측정 시작", onStart, Modifier.fillMaxWidth(), enabled = state.micRunning)
        Spacer(Modifier.height(18.dp))
        Text("나중에 할게요", fontSize = 17.sp, color = Purple, fontWeight = FontWeight.SemiBold, modifier = Modifier.clickable(onClick = onLater))
        Spacer(Modifier.height(40.dp))
    }
}

@Composable
private fun StatusChip(text: String, bg: Color, fg: Color, icon: androidx.compose.ui.graphics.vector.ImageVector) {
    Row(Modifier.background(bg, RoundedCornerShape(50)).padding(horizontal = 14.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, Modifier.size(16.dp), tint = fg)
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 14.sp, color = fg, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun PitchBody(state: QuickAnalysisViewModel.State, targetMidi: Int, onStop: () -> Unit) {
    val names = listOf(targetMidi - 2, targetMidi - 1, targetMidi, targetMidi + 1, targetMidi + 2).map { ScoringMath.midiName(it) }
    Column(Modifier.fillMaxSize().padding(horizontal = 32.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Spacer(Modifier.height(40.dp))
        Icon(Icons.Outlined.Mic, null, Modifier.size(36.dp), tint = Purple)
        Spacer(Modifier.height(28.dp))
        PitchGauge(ScoringMath.midiName(targetMidi), names, state.cents, state.matched, state.holdProgress)
        Spacer(Modifier.height(8.dp))
        if (state.matched) {
            Text("음정이 잘 맞아요", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = InkSoft)
            Text("3초간 유지하세요", fontSize = 16.sp, color = InkSoft)
        } else {
            Text(if (state.cents == null) "소리를 내주세요" else "음정이 맞지 않아요", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = InkSoft)
            Spacer(Modifier.height(16.dp))
            Surface(onClick = { /* TODO: 기준음 재생 (ToneGenerator 또는 짧은 사인파) */ }, shape = RoundedCornerShape(14.dp), color = Color.White, border = androidx.compose.foundation.BorderStroke(1.dp, Lavender), shadowElevation = 1.dp) {
                Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Outlined.Campaign, null, Modifier.size(20.dp), tint = Purple)
                    Spacer(Modifier.width(8.dp))
                    Text("기준음 들어보기", fontSize = 15.sp, color = Purple, fontWeight = FontWeight.SemiBold)
                }
            }
        }
        Spacer(Modifier.weight(1f))
        OutlinePurpleButton("측정 그만하기", onStop, Modifier.fillMaxWidth(), height = 62.dp)
        Spacer(Modifier.height(60.dp))
    }
    @Suppress("UNUSED_VARIABLE") val ink = Ink
}
