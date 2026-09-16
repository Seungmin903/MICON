package com.micon.app.ui.report

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.micon.app.data.onboarding.*
import com.micon.app.range.RangeReport
import com.micon.app.ui.common.DashedBox
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.PrimaryButton
import com.micon.app.ui.theme.Grey
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

/** Onboarding and saved profiles share exactly the same result sections. */
@Composable
fun VoiceReportContent(
    range: RangeReport,
    progress: VoiceAnalysisProgress?,
    retryEnabled: Boolean = false,
    onRetry: ((AnalysisComponent) -> Unit)? = null,
    songFit: (@Composable () -> Unit)? = null,
) {
    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
        RangeResultCards(range)
        val current = progress?.takeIf { it.matchesRange(range) }
        val complete = current?.hasTimbreReport(range) == true
        MiconCard {
            Text(if (complete) "음색 분석 결과" else "음색 분석", fontSize = 18.sp, fontWeight = FontWeight.Bold)
            VoiceComponentRow("닮은 목소리 분석", current?.ecapa, retryEnabled,
                onRetry?.let { retry -> { retry(AnalysisComponent.ECAPA) } })
            VoiceComponentRow("노래 음색 분석", current?.byol, retryEnabled,
                onRetry?.let { retry -> { retry(AnalysisComponent.SINGER_BYOL) } })
        }
        if (complete) {
            SimilarArtistsCard(checkNotNull(current), retryEnabled,
                onRetry?.let { retry -> { retry(AnalysisComponent.ARTIST_SEARCH) } })
            AdditionalVoiceReportCards(range, songFit)
        }
    }
}

@Composable
private fun SimilarArtistsCard(progress: VoiceAnalysisProgress, retryEnabled: Boolean, onRetry: (() -> Unit)?) {
    MiconCard {
        Text("나와 닮은 가수", fontSize = 18.sp, fontWeight = FontWeight.Bold)
        val artists = progress.visibleArtists
        var expanded by remember(progress.sessionId, progress.version) { mutableStateOf(false) }
        if (progress.artistSearch == ComponentStatus.COMPLETED) {
            if (artists.isEmpty()) Text("지금은 닮은 가수를 찾지 못했어요.", color = Grey)
            else {
                artists.take(if (expanded) 5 else 3).forEachIndexed { index, artist ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("${index + 1}", color = Purple, fontWeight = FontWeight.Bold)
                        AsyncImage(artist.imageUrl, contentDescription = null, modifier = Modifier.size(40.dp))
                        Text(artist.name, fontWeight = FontWeight.Medium)
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
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f))
        Text(when (status) {
            null -> "준비 중"
            ComponentStatus.PENDING -> "접수 준비 중"
            ComponentStatus.QUEUED -> "순서 대기 중"
            ComponentStatus.RUNNING -> "분석 중"
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
