package com.micon.app.ui.report

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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Air
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.MusicNote
import androidx.compose.material.icons.outlined.QueueMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micon.app.data.local.UserStore
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.micon.app.data.voice.VoiceAnalysisStatus
import com.micon.app.ui.rememberAppContainer
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.charts.BpmGaugeRow
import com.micon.app.ui.charts.PhraseSegments
import com.micon.app.ui.charts.PianoRangeChart
import com.micon.app.ui.charts.RadarHexagon
import com.micon.app.ui.charts.RangeMapChart
import com.micon.app.ui.charts.RangeRow
import com.micon.app.ui.charts.StabilityLineChart
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.DashedBox
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.MiconHeader
import com.micon.app.ui.common.MiconLogo
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.common.SoftChip
import com.micon.app.ui.theme.GreenBar
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.InkSoft
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Orange
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid

/** Unified onboarding entry; latest analysis is not an explicitly saved profile. */
@Composable
fun VoiceReportScreen(
    onQuickAnalysis: () -> Unit,
    onSettings: () -> Unit,
    onLogin: (() -> Unit)? = null,
) {
    val previousAnalysis by UserStore.profile.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val container = rememberAppContainer()
    val owner = if (onLogin == null) com.micon.app.core.auth.TokenManager.getUserId().takeIf { it > 0 } else null
    val saved by androidx.compose.runtime.produceState<com.micon.app.data.onboarding.SavedVoiceOnboardingProfile?>(null, owner) {
        value = if (owner == null) null else kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            val store = com.micon.app.data.onboarding.VoiceOnboardingStore(context.applicationContext, owner)
            store.savedProfile() ?: runCatching { container.voiceOnboarding.latestSaved(owner) }.getOrNull()
                ?.also(store::saveProfile)
        }
    }
    Column(Modifier.fillMaxSize().background(Color.White)) {
        if (onLogin == null) MiconHeader(onSettings = onSettings) else GuestReportHeader(onLogin)
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp)) {
            PrimaryButton("목소리 분석하러 가기", onQuickAnalysis, Modifier.fillMaxWidth(), height = 66.dp)
            Spacer(Modifier.height(16.dp))
            Text("음색 분석과 프로필 저장 기능을 준비 중이에요. 음역 측정과 노래 녹음은 체험할 수 있어요.",
                color = Grey, fontSize = 13.sp)
            Spacer(Modifier.height(16.dp))
            val current = saved
            if (current != null) {
                com.micon.app.ui.onboarding.VoiceRangeSummary(current.range)
                // Preserve develop's server-backed song fit card for a confirmed saved profile.
                ReportFitCard(owner != null)
            } else if (owner != null && previousAnalysis != null) {
                Text("이전에 확인한 분석", fontWeight = FontWeight.SemiBold, color = Ink)
                Spacer(Modifier.height(12.dp))
                ReportCards(checkNotNull(previousAnalysis), loggedIn = true)
            } else EmptyReport()
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun GuestReportHeader(onLogin: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(72.dp).padding(start = 20.dp, end = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MiconLogo(barHeight = 20.dp, fontSize = 20.sp)
        Spacer(Modifier.weight(1f))
        TextButton(onClick = onLogin) {
            Text("로그인/회원가입", color = InkSoft, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun BigCircleButton(icon: ImageVector, label: String, caption: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = CircleShape, color = Purple, shadowElevation = 10.dp, modifier = Modifier.size(196.dp)) {
        Column(Modifier.fillMaxSize(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(icon, null, Modifier.size(52.dp), tint = Color.White)
            Spacer(Modifier.height(12.dp))
            Text(label, fontSize = 21.sp, fontWeight = FontWeight.SemiBold, color = Color.White)
            Spacer(Modifier.height(4.dp))
            Text(caption, fontSize = 12.sp, color = Color.White.copy(alpha = 0.85f))
        }
    }
}

/** 서버 최근 분석이 아직 끝나지 않았거나 실패했을 때 한 줄 안내. 완료면 카드가 결과를 보여 주므로 표시하지 않는다. */
@Composable
private fun AnalysisStatusBanner(server: VoiceReportViewModel.UiState) {
    val latest = server.latest
    val text = when {
        server.error != null -> server.error
        latest == null || latest.status == VoiceAnalysisStatus.COMPLETED -> null
        latest.status == VoiceAnalysisStatus.FAILED || latest.status == VoiceAnalysisStatus.INSUFFICIENT_DATA || latest.status == VoiceAnalysisStatus.CANCELED ->
            "최근 분석이 완료되지 않았어요: " + (latest.failureReason?.takeIf { it.isNotBlank() } ?: latest.status.name)
        else -> "서버에서 목소리를 분석하고 있어요. 끝나면 이 화면에 결과가 채워져요."
    } ?: return
    Text(
        text, fontSize = 13.sp, color = Grey, lineHeight = 19.sp,
        modifier = Modifier.fillMaxWidth().background(Lavender, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
    )
    Spacer(Modifier.height(16.dp))
}

@Composable
private fun EmptyReport() {
    DashedBox(Modifier.fillMaxWidth().height(560.dp)) {
        Box(Modifier.fillMaxSize()) {
            Icon(Icons.Outlined.MusicNote, null, Modifier.align(Alignment.Center).offset(120.dp, (-80).dp).size(48.dp), tint = GreyLight)
            Icon(Icons.Outlined.QueueMusic, null, Modifier.align(Alignment.Center).offset((-120).dp, 100.dp).size(56.dp), tint = GreyLight)
            Text(
                "목소리 분석을 하고\n음성 분석 리포트를 확인해보세요",
                fontSize = 16.sp, color = Grey, textAlign = TextAlign.Center, lineHeight = 24.sp,
                modifier = Modifier.align(Alignment.Center),
            )
        }
    }
}

@Composable
private fun ReportCards(p: UserStore.VoiceProfile, loggedIn: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        MiconCard {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("나의 음역대", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.weight(1f))
                Text(
                    "${ScoringMath.midiName(p.lowMidi)} - ${ScoringMath.midiName(p.highMidi)}",
                    fontSize = 34.sp, fontWeight = FontWeight.SemiBold, color = Ink, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1.4f), textAlign = TextAlign.Center,
                )
            }
        }

        MiconCard {
            CardTitle("음역대 지도")
            Spacer(Modifier.height(10.dp))
            RangeMapChart(
                rows = listOf(
                    RangeRow("전체 음역", p.lowMidi, p.highMidi, PurpleMid, fadeTail = true),
                    RangeRow("편안한 음역", p.comfortLow, p.comfortHigh, GreenBar),
                    RangeRow("추천곡 음역", p.recommendLow, p.recommendHigh, Orange),
                ),
                axisLow = p.lowMidi, axisHigh = p.highMidi,
                ticks = listOf(p.lowMidi, p.lowMidi + 7, p.lowMidi + 12, p.lowMidi + 19, p.highMidi).distinct(),
            )
        }

        MiconCard {
            CardTitle("음역별 안정성")
            Spacer(Modifier.height(10.dp))
            StabilityLineChart(p.stability, p.stabilityLabels)
        }

        MiconCard {
            CardTitle("내 목소리에 맞는 음악")
            Spacer(Modifier.height(12.dp))
            Text("음역", fontSize = 13.sp, color = Grey)
            Spacer(Modifier.height(6.dp))
            PianoRangeChart(p.comfortLow, p.comfortHigh, caption = "편안한 음역 ${ScoringMath.midiName(p.comfortLow)}~${ScoringMath.midiName(p.comfortHigh)}")
            Spacer(Modifier.height(18.dp))
            Text("적정 템포 / 가사 밀도", fontSize = 13.sp, color = Grey)
            Spacer(Modifier.height(6.dp))
            BpmGaugeRow(p.bpmRange, Modifier.fillMaxWidth())
            Spacer(Modifier.height(18.dp))
            Text("프레이즈 길이", fontSize = 13.sp, color = Grey)
            Spacer(Modifier.height(8.dp))
            PhraseSegments(p.phraseLevel, modifier = Modifier.padding(horizontal = 12.dp))
            Spacer(Modifier.height(18.dp))
            Text("추천 장르 / 분위기", fontSize = 13.sp, color = Grey)
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { p.genres.forEach { SoftChip(it) } }
        }

        ReportFitCard(loggedIn)

        MiconCard {
            CardTitle("종합 보컬 특성")
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                RadarHexagon(p.traits, p.traitLabels, Modifier.weight(1f).size(190.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    p.strengths.forEachIndexed { i, s ->
                        Row(Modifier.background(Lavender, RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (i == 0) Icons.Filled.MusicNote else Icons.Filled.Air, null, Modifier.size(18.dp), tint = Purple)
                            Spacer(Modifier.width(8.dp))
                            Text(s, fontSize = 14.sp, color = Purple, fontWeight = FontWeight.SemiBold)
                        }
                    }
                }
            }
        }
    }
}

