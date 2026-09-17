package com.micon.app.ui.report

import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.micon.app.BuildConfig
import com.micon.app.data.onboarding.*
import com.micon.app.range.RangeReport
import com.micon.app.ui.common.DashedBox
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple

@Composable
fun RangeMeasurementReport(range: RangeReport, onMeasureTimbre: () -> Unit, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RangeResultCards(range)
        TimbreMeasurementInvitation(onMeasureTimbre, enabled && range.availability == "available")
    }
}

@Composable
fun TimbreMeasurementInvitation(onMeasure: () -> Unit, enabled: Boolean = true) {
    DashedBox(Modifier.fillMaxWidth().testTag("timbre_measurement_invitation")) {
        Column(Modifier.fillMaxWidth().padding(24.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
            Text("음색 측정을 통해 더 많은 결과 확인하기", fontSize = 18.sp,
                fontWeight = FontWeight.SemiBold, lineHeight = 27.sp)
            PrimaryButton("음색 측정하기", onMeasure, Modifier.fillMaxWidth(), enabled = enabled)
        }
    }
}

/** Onboarding retains component failures and their retry actions. */
@Composable
fun VoiceReportContent(
    range: RangeReport,
    progress: VoiceAnalysisProgress?,
    retryEnabled: Boolean = false,
    onRetry: ((AnalysisComponent) -> Unit)? = null,
    songFit: (@Composable () -> Unit)? = null,
) {
    val current = progress?.takeIf { it.matchesRange(range) }
    LogVoiceReportProgress(current)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RangeResultCards(range)
        val complete = current?.hasTimbreReport(range) == true
        if (listOf(current?.ecapa, current?.byol).any { it != null && !it.processing }) {
            MiconCard {
                Text(if (complete) "음색 분석 결과" else "음색 분석", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                VoiceComponentRow("닮은 목소리 분석", current?.ecapa, retryEnabled,
                    onRetry?.let { retry -> { retry(AnalysisComponent.ECAPA) } })
                VoiceComponentRow("노래 음색 분석", current?.byol, retryEnabled,
                    onRetry?.let { retry -> { retry(AnalysisComponent.SINGER_BYOL) } })
            }
        }
        if (complete) {
            SimilarArtistsCard(checkNotNull(current), retryEnabled,
                onRetry?.let { retry -> { retry(AnalysisComponent.ARTIST_SEARCH) } })
            AdditionalVoiceReportCards(range, songFit)
        }
    }
}

/** The report tab keeps measured range cards and actual artist results. */
@Composable
fun SavedVoiceReportContent(range: RangeReport, progress: VoiceAnalysisProgress?) {
    val current = progress?.takeIf { it.matchesRange(range) }
    LogVoiceReportProgress(current)
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RangeResultCards(range, reportTab = true)
        SimilarArtistsCard(current?.takeIf { it.hasTimbreReport(range) }, showEmpty = true)
    }
}

@Composable
private fun LogVoiceReportProgress(current: VoiceAnalysisProgress?) {
    LaunchedEffect(current?.ecapa, current?.byol, current?.artistSearch, current?.message) {
        if (BuildConfig.DEBUG && current != null) {
            Log.d("VoiceReport", "ecapa=${current.ecapa}, byol=${current.byol}, artistSearch=${current.artistSearch}")
            current.message?.let { Log.d("VoiceReport", it) }
        }
    }
}

@Composable
internal fun SimilarArtistsCard(progress: VoiceAnalysisProgress? = null, retryEnabled: Boolean = false,
                               onRetry: (() -> Unit)? = null, showEmpty: Boolean = false) {
    if ((progress == null || progress.artistSearch.processing) && !showEmpty) return
    MiconCard(Modifier.testTag("similar-artists-card")) {
        Text("나와 닮은 가수", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        if (progress == null || progress.artistSearch.processing) {
            Spacer(Modifier.height(12.dp))
            Text("목소리와 음색이 비슷한 가수를 모아 보여드려요.", color = Grey, fontSize = 13.sp)
            return@MiconCard
        }
        val artists = progress.visibleArtists
        var expanded by remember(progress.sessionId, progress.version) { mutableStateOf(false) }
        if (progress.artistSearch == ComponentStatus.COMPLETED) {
            if (artists.isEmpty()) Text("지금은 닮은 가수를 찾지 못했어요.", color = Grey)
            else {
                artists.take(if (expanded) 5 else 3).forEachIndexed { index, artist ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text("${index + 1}", color = Purple, fontWeight = FontWeight.Bold)
                        Box(Modifier.size(48.dp).clip(CircleShape).background(Lavender), contentAlignment = Alignment.Center) {
                            Text(artist.name.take(1), color = Purple, fontWeight = FontWeight.Bold)
                            if (!artist.imageUrl.isNullOrBlank()) AsyncImage(artist.imageUrl, contentDescription = null,
                                contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                        }
                        Text(artist.name, modifier = Modifier.weight(1f), fontWeight = FontWeight.Medium)
                    }
                }
                if (artists.size > 3) TextButton(onClick = { expanded = !expanded }) {
                    Text(if (expanded) "접기" else "더 보기")
                }
            }
        } else VoiceComponentRow("가수 찾기", progress.artistSearch, retryEnabled, onRetry)
    }
}

@Composable
private fun VoiceComponentRow(label: String, status: ComponentStatus?, enabled: Boolean, onRetry: (() -> Unit)?) {
    if (status == null || status.processing) return
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Text(when (status) {
            ComponentStatus.PENDING, ComponentStatus.QUEUED, ComponentStatus.RUNNING -> ""
            ComponentStatus.COMPLETED -> "완료"
            ComponentStatus.FAILED -> "다시 시도 필요"
            ComponentStatus.INSUFFICIENT_DATA -> "목소리 부족 · 다시 녹음"
            ComponentStatus.CANCELED -> "취소됨"
        }, Modifier.weight(1f), color = if (status == ComponentStatus.COMPLETED) Purple else Grey)
    }
    if (status == ComponentStatus.FAILED && onRetry != null) {
        TextButton(onClick = onRetry, enabled = enabled) { Text("다시 시도") }
    }
}
