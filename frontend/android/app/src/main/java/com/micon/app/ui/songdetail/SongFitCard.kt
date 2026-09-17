package com.micon.app.ui.songdetail

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.scoring.KaraokeNames
import com.micon.app.ui.common.CardTitle
import com.micon.app.ui.common.MiconCard
import com.micon.app.ui.common.SoftChip
import com.micon.app.ui.theme.GreenChipBg
import com.micon.app.ui.theme.GreenChipText
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.GreyLight
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.LavenderDeep
import com.micon.app.ui.theme.Pink
import com.micon.app.ui.theme.PinkBg
import com.micon.app.ui.theme.Purple
import com.micon.app.ui.theme.PurpleMid
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * "이 노래, 나한테 맞을까?" — 곡 상세 첫 카드. 초보가 바로 읽을 수 있게 말로 결론을 먼저 주고,
 * 노래 음역 막대와 내 편안한 음역 막대를 같은 축에 겹쳐 보인다. 음이름은 노래방식(2옥타브 라).
 */
@Composable
fun SongFitCard(
    songLow: Int,
    songHigh: Int,
    comfortLow: Int,
    comfortHigh: Int,
    /** null = 서버가 키를 계산하지 못했다. 0 은 실제 원키 추천이다. */
    recommendedKey: Int?,
    noteCount: Int?,
    durationSec: Double?,
    hasProfile: Boolean,
    profileLoadFailed: Boolean = false,
) {
    val diff = songHigh - comfortHigh          // 반음. 양수 = 노래 최고음이 내 편안한 음역보다 높다
    val lowDiff = comfortLow - songLow          // 양수 = 노래 최저음이 내 편안한 음역보다 낮다
    val density = if (noteCount != null && durationSec != null && durationSec > 0) noteCount / durationSec else 0.0
    val difficulty = (if (diff > 5) 2 else if (diff > 2) 1 else 0) + (if (songHigh - songLow > 19) 1 else 0) + (if (density > 3.2) 1 else 0)
    val stars = when { difficulty >= 3 -> 3; difficulty >= 1 -> 2; else -> 1 }
    val verdict = when {
        !hasProfile && profileLoadFailed -> "음역 정보를 불러오지 못했어요. 다시 시도해 주세요."
        !hasProfile -> "내 음역을 아직 측정하지 않았어요. 리포트 탭에서 30초 측정을 하면 이 노래가 맞는지 알려 드려요."
        diff <= 0 && lowDiff <= 0 -> "최고음 ${KaraokeNames.korean(songHigh)}이 내 편안한 음역 안에 있어요. 원키로 편하게 부를 수 있어요."
        diff <= 0 -> "높은 음은 편하지만, 낮은 음(${KaraokeNames.korean(songLow)})이 내 음역보다 ${lowDiff}반음 아래예요. 키를 조금 올리면 편해요."
        diff <= 2 -> "최고음 ${KaraokeNames.korean(songHigh)}이 내 편안한 음역보다 ${diff}반음 높아요. 살짝 도전적이지만 충분히 부를 수 있어요."
        diff <= 5 -> "최고음 ${KaraokeNames.korean(songHigh)}이 내 편안한 음역보다 ${diff}반음 높아요." + (recommendedKey?.let { " 키를 $it 낮추면 편하게 부를 수 있어요." } ?: " 권장 키는 아직 계산되지 않았어요.")
        else -> "최고음 ${KaraokeNames.korean(songHigh)}이 내 편안한 음역보다 ${diff}반음이나 높아요." + (recommendedKey?.let { " 키를 $it 낮추고, 고음 구간은 하이라이트 연습으로 따로 익혀 보세요." } ?: " 권장 키는 아직 계산되지 않았어요.")
    }

    MiconCard {
        CardTitle("이 노래, 나한테 맞을까?")
        Spacer(Modifier.height(10.dp))
        Text(verdict, fontSize = 15.sp, color = Ink, lineHeight = 22.sp)
        Spacer(Modifier.height(14.dp))
        RangeCompareBar(songLow, songHigh, if (hasProfile) comfortLow else null, if (hasProfile) comfortHigh else null)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftChip("최고음 ${KaraokeNames.korean(songHigh)}")
            SoftChip("최저음 ${KaraokeNames.korean(songLow)}")
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftChip(
                text = when { !hasProfile -> "키 추천은 측정 후"; recommendedKey == null -> "권장 키 계산 안 됨"; recommendedKey == 0 -> "원키 추천"; recommendedKey != null && recommendedKey > 0 -> "키 +$recommendedKey 추천"; else -> "키 $recommendedKey 추천" },
                bg = if (!hasProfile || recommendedKey == null) GreyLight else if (recommendedKey == 0) GreenChipBg else PinkBg,
                fg = if (!hasProfile || recommendedKey == null) Grey else if (recommendedKey == 0) GreenChipText else Pink,
            )
            SoftChip("난이도 " + "★".repeat(stars) + "☆".repeat(3 - stars), bg = GreyLight, fg = Ink)
        }
    }
}

/** 같은 축 위에 "노래" 와 "나" 두 막대. 양 끝에 노래방식 음이름. 내 음역이 없으면 노래 막대만. */
@Composable
private fun RangeCompareBar(songLow: Int, songHigh: Int, comfortLow: Int?, comfortHigh: Int?) {
    val measurer = rememberTextMeasurer()
    val axisLow = min(songLow, comfortLow ?: songLow) - 2
    val axisHigh = max(songHigh, comfortHigh ?: songHigh) + 2
    Canvas(Modifier.fillMaxWidth().height(if (comfortLow != null) 96.dp else 52.dp)) {
        val labelW = 34.dp.toPx()
        val span = (axisHigh - axisLow).toFloat().coerceAtLeast(1f)
        val trackW = size.width - labelW
        fun x(m: Int) = labelW + trackW * (m - axisLow) / span
        val barH = 14.dp.toPx()
        val rowH = 44.dp.toPx()
        fun row(index: Int, label: String, low: Int, high: Int, color: Color) {
            val y = index * rowH + 8.dp.toPx() + barH / 2
            val name = measurer.measure(label, TextStyle(fontSize = 13.sp, color = Ink, fontWeight = FontWeight.SemiBold))
            drawText(name, topLeft = Offset(0f, y - name.size.height / 2f))
            drawLine(GreyLight, Offset(labelW, y), Offset(size.width, y), strokeWidth = 2.dp.toPx())
            drawRoundRect(color, Offset(x(low), y - barH / 2), Size(max(x(high) - x(low), barH), barH), CornerRadius(barH / 2))
            val lo = measurer.measure(KaraokeNames.koreanShort(low), TextStyle(fontSize = 11.sp, color = Grey))
            val hi = measurer.measure(KaraokeNames.koreanShort(high), TextStyle(fontSize = 11.sp, color = Grey))
            val ty = y + barH / 2 + 2.dp.toPx()
            drawText(lo, topLeft = Offset((x(low) - lo.size.width / 2f).coerceIn(labelW, size.width - lo.size.width), ty))
            val hiX = (x(high) - hi.size.width / 2f).coerceIn(labelW, size.width - hi.size.width)
            if (abs(hiX - (x(low) - lo.size.width / 2f)) > lo.size.width + 6.dp.toPx()) drawText(hi, topLeft = Offset(hiX, ty))
        }
        row(0, "노래", songLow, songHigh, PurpleMid)
        if (comfortLow != null && comfortHigh != null) row(1, "나", comfortLow, comfortHigh, LavenderDeep.copy(alpha = 1f).let { Purple.copy(alpha = 0.45f) })
    }
}
