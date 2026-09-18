package com.micon.app.ui.songdetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.data.SongDetail
import com.micon.app.data.backend.dto.KaraokeNote
import com.micon.app.ui.common.*
import com.micon.app.scoring.ScoringConstants
import com.micon.app.ui.songdetail.SongDetailColors as Detail
import kotlin.math.abs
import kotlin.math.roundToInt

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun RangeMatchingCard(detail: SongDetail, range: MeasuredPitchRange?, key: Int,
    profileLoadFailed: Boolean, notes: List<KaraokeNote>) {
    val analyzedRange = detail.analysis?.let { analysis ->
        val low = analysis.lowestMidi
        val high = analysis.highestMidi
        if (low != null && high != null && low in 0..127 && high in low..127) low to high else null
    }
    val low = (analyzedRange?.first ?: notes.minOfOrNull { it.midi })?.plus(key)
    val high = (analyzedRange?.second ?: notes.maxOfOrNull { it.midi })?.plus(key)
    val hasSongRange = low != null && high != null && low <= high
    val above = if (hasSongRange && range != null) (high!! - range.high).coerceAtLeast(0) else null
    val below = if (hasSongRange && range != null) (range.low - low!!).coerceAtLeast(0) else null
    MiconCard(Modifier.testTag("range-matching"), color = Detail.card) {
        CardTitle("음역대 매칭 분석", color = Detail.title)
        Spacer(Modifier.height(6.dp))
        Text("${songKeyLabel(key)} 기준으로 내 측정 음역과 비교해요", color = Detail.muted, fontSize = 12.sp)
        Spacer(Modifier.height(18.dp))
        if (range == null) {
            Text(if (profileLoadFailed) "내 음역을 불러오지 못했어요. 리포트에서 다시 확인해 주세요."
                else "목소리를 분석하면 내 음역과 이 곡을 비교할 수 있어요.", fontSize = 14.sp, color = Detail.body, lineHeight = 21.sp)
        } else {
            Text(when { !hasSongRange -> "이 곡의 음역 정보를 기다리고 있어요"
                above == 0 && below == 0 -> "내 측정 음역 안에 들어오는 곡이에요"
                else -> "일부 음역을 먼저 확인해 보세요" },
                fontSize = 17.sp, color = if (above == 0 && below == 0) Detail.accent else Detail.title, fontWeight = FontWeight.SemiBold)
        }
        if (hasSongRange || range != null) {
            val axisLow = minOf(if (hasSongRange) low!! else range!!.low, range?.low ?: low!!) - 2
            val axisHigh = maxOf(if (hasSongRange) high!! else range!!.high, range?.high ?: high!!) + 2
            Spacer(Modifier.height(18.dp))
            range?.let { RangeTrack("내 측정 음역", it.low, it.high, axisLow, axisHigh, Detail.measured) }
            if (range != null && hasSongRange) Spacer(Modifier.height(14.dp))
            if (hasSongRange) RangeTrack("곡 음역 · ${songKeyLabel(key)}", low!!, high!!, axisLow, axisHigh, Detail.accent)
        }
        Spacer(Modifier.height(16.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (hasSongRange) {
                SoftChip("최저 ${RangeNoteNames.short(low!!)}", bg = Detail.inset, fg = Detail.body)
                SoftChip("최고 ${RangeNoteNames.short(high!!)}", bg = Detail.inset, fg = Detail.body)
            }
        }
        if (above != null && below != null && (above > 0 || below > 0)) {
            Spacer(Modifier.height(12.dp))
            Text(listOfNotNull(if (above > 0) "높은 음은 ${above}반음 위" else null,
                if (below > 0) "낮은 음은 ${below}반음 아래" else null).joinToString(", ") + "에 있어요. 아래 그래프에서 위치를 확인해 보세요.",
                color = Detail.body, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}

@Composable
private fun RangeTrack(label: String, low: Int, high: Int, axisLow: Int, axisHigh: Int, color: Color) {
    Column {
        Text(label, color = Detail.body, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(6.dp))
        Text("${RangeNoteNames.short(low)} — ${RangeNoteNames.short(high)}", color = Detail.muted, fontSize = 11.sp)
        Spacer(Modifier.height(8.dp))
        Canvas(Modifier.fillMaxWidth().height(10.dp)) {
            drawRoundRect(Detail.track, cornerRadius = CornerRadius(size.height / 2))
            val span = (axisHigh - axisLow).coerceAtLeast(1)
            val start = (low - axisLow).toFloat() / span * size.width
            val width = ((high - low).toFloat() / span * size.width).coerceAtLeast(5.dp.toPx())
            drawRoundRect(color, Offset(start, 0f), Size(width.coerceAtMost(size.width - start), size.height), CornerRadius(size.height / 2))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SongSettingsCard(state: SongDetailState, onKey: (Int) -> Unit, onRetry: () -> Unit) {
    // 실시간 시프트 범위(±3) 밖의 추천 키는 고를 수 없으므로 표시하지 않는다
    val recommended = state.fit?.recommendedKey?.takeIf { it in ScoringConstants.KEY_SHIFT_MIN..ScoringConstants.KEY_SHIFT_MAX }
    val timbre = state.fit?.timbre
    val timbreScore = state.fit?.timbreFit?.takeIf { it.isFinite() && it in 0f..1f }
    MiconCard(Modifier.testTag("song-settings"), color = Detail.card) {
        CardTitle("곡 맞춤 설정 추천", color = Detail.title)
        Spacer(Modifier.height(16.dp))
        Column(Modifier.fillMaxWidth().background(Detail.accentSurface, RoundedCornerShape(16.dp)).padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.Tune, null, Modifier.size(20.dp), tint = Detail.accent)
                Spacer(Modifier.width(8.dp)); Text("권장 키 조절", color = Detail.accent, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Text(if (state.fitLoading) "···" else recommended?.let(::songKeyLabel) ?: "—",
                    fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Detail.accent)
                Text(when { state.fitLoading -> "맞춤 키를 확인하고 있어요"
                    recommended == null -> "추천 키 정보가 아직 없어요"
                    recommended == 0 -> "원곡 키를 추천해요"
                    else -> "원키보다 ${abs(recommended)}반음 ${if (recommended < 0) "낮게" else "높게"}" },
                    color = Detail.body, fontSize = 13.sp, lineHeight = 20.sp, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(selected = state.selectedKey == 0, onClick = { onKey(0) }, label = { Text("원키") }, modifier = Modifier.testTag("key-original"))
                if (recommended != null && recommended != 0) FilterChip(selected = state.selectedKey == recommended,
                    onClick = { onKey(recommended) }, label = { Text("추천 ${songKeyLabel(recommended)}") }, modifier = Modifier.testTag("key-recommended"))
            }
            Text("선택한 키로 그래프와 연습이 함께 바뀌어요", color = Detail.accent, fontSize = 11.sp, lineHeight = 17.sp)
        }
        Spacer(Modifier.height(12.dp))
        Row(Modifier.fillMaxWidth().background(Detail.inset, RoundedCornerShape(16.dp)).padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(40.dp).background(Detail.accentSurface, RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Icon(Icons.Outlined.GraphicEq, null, tint = Detail.accent, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text("음색 적합도", color = Detail.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(4.dp))
                Text(if (state.fitLoading) "분석을 확인하고 있어요" else timbre?.label
                    ?: if (timbreScore != null) "목소리 유사도를 기준으로 한 점수예요" else "음색 분석 정보가 아직 없어요",
                    color = Detail.muted, fontSize = 12.sp, lineHeight = 18.sp)
            }
            if (timbreScore != null) {
                Spacer(Modifier.width(8.dp))
                Column(horizontalAlignment = Alignment.End) {
                    Text("${(timbreScore * 100).roundToInt()}", color = Detail.accent, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("/ 100", color = Detail.muted, fontSize = 10.sp)
                }
            }
        }
        state.fitError?.let {
            Spacer(Modifier.height(8.dp)); Text(it, color = Detail.muted, fontSize = 12.sp)
            TextButton(onClick = onRetry) { Text("맞춤 분석 다시 불러오기") }
        }
    }
}
