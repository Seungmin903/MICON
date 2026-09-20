package com.micon.app.ui.design.organisms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.design.tokens.miconShadow

/**
 * 포터블 CD 플레이어 데크 — 추천 탭의 주인공. 프로토타입 `.deck2` 그대로.
 *
 * 정사각형(폭 − 40dp). 위에 `MICON` 칩 · 스피커 그릴 · `TRK 01 / 06` LCD 가 한 줄,
 * 그 아래 무대에 CD 가 올라간다. CD 가 없으면 점선 스핀들과 안내 문구.
 *
 * 재생 버튼은 없다. CD 가운데를 누르면 곡 상세로 간다. ⏮ ⏭ 는 무대 양 끝에 떠 있다.
 * 폭이 곧 높이라서 세로가 짧은 기기에서는 화면이 스크롤된다 — 데크는 줄어들지 않는다.
 */
@Composable
fun CdPlayer(
    title: String?,
    artist: String?,
    trackIndex: Int,
    trackTotal: Int,
    onDiscClick: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    spinning: Boolean = false,
    /** 처음엔 무대가 비어 있다. false 면 점선 스핀들 + 안내가 보인다 */
    loaded: Boolean = title != null,
    hasPrev: Boolean = trackIndex > 1,
    hasNext: Boolean = trackIndex < trackTotal,
) {
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .miconShadow(elevation = 14.dp, shape = MiconShape.deck, alpha = 0.14f),
        shape = MiconShape.deck,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0x0B1E1B33)),
    ) {
        Column(
            Modifier
                .background(Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF4F1FC))))
                .padding(MiconSpace.md),
            verticalArrangement = Arrangement.spacedBy(MiconSpace.sm),
        ) {
            DeckHead(trackIndex = trackIndex, trackTotal = trackTotal, loaded = loaded)

            BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                val stage = if (maxWidth < maxHeight) maxWidth else maxHeight
                if (loaded && title != null) {
                    CompactDisc(
                        title = title,
                        artist = artist,
                        imageUrl = imageUrl,
                        size = stage * 0.9f,
                        spinning = spinning,
                        contentDescription = "$title, $artist. 눌러서 곡 자세히 보기",
                        modifier = Modifier.clickable(onClick = onDiscClick),
                    )
                } else {
                    Spindle(stage * 0.78f)
                }
                StageNav(
                    icon = { Icon(Icons.Filled.SkipPrevious, contentDescription = "이전 곡", modifier = Modifier.size(19.dp)) },
                    onClick = onPrev,
                    enabled = loaded && hasPrev,
                    modifier = Modifier.align(Alignment.CenterStart),
                )
                StageNav(
                    icon = { Icon(Icons.Filled.SkipNext, contentDescription = "다음 곡", modifier = Modifier.size(19.dp)) },
                    onClick = onNext,
                    enabled = loaded && hasNext,
                    modifier = Modifier.align(Alignment.CenterEnd),
                )
            }
        }
    }
}

/** `MICON` 칩 · 그릴 4×2 · `TRK 01 / 06` */
@Composable
private fun DeckHead(trackIndex: Int, trackTotal: Int, loaded: Boolean) {
    val scheme = MaterialTheme.colorScheme
    Row(
        Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Box(
            Modifier
                .height(24.dp)
                .clip(MiconShape.chip)
                .background(scheme.surface)
                .padding(horizontal = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                "MICON",
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MiconMonoFamily, letterSpacing = 1.6.sp),
                color = scheme.onSurfaceVariant,
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(3.dp)) {
            repeat(4) {
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Box(Modifier.size(3.dp).clip(CircleShape).background(Color(0xFFD9D5EA)))
                    Box(Modifier.size(3.dp).clip(CircleShape).background(Color(0xFFD9D5EA)))
                }
            }
        }
        Text(
            if (loaded) "TRK %02d / %02d".format(trackIndex, trackTotal) else "TRK —— / %02d".format(trackTotal),
            style = MaterialTheme.typography.labelMedium.copy(fontFamily = MiconMonoFamily, letterSpacing = 1.2.sp),
            color = scheme.onSurfaceVariant,
        )
    }
}

/** CD 가 없을 때 — 점선 원 + 안내. 프로토타입 `.spindle` */
@Composable
private fun Spindle(size: Dp) {
    val hint = MaterialTheme.colorScheme.onSurfaceVariant
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            drawCircle(
                color = Color(0xFFD9D5EA),
                radius = this.size.minDimension / 2f - 1.dp.toPx(),
                style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 6.dp.toPx()))),
            )
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Canvas(Modifier.size(26.dp)) {
                val c = Offset(this.size.width / 2f, this.size.height / 2f)
                drawCircle(Color(0xFFB9B4CC), radius = 9.dp.toPx(), center = c, style = Stroke(1.6.dp.toPx()))
                drawCircle(Color(0xFFB9B4CC), radius = 2.4.dp.toPx(), center = c, style = Stroke(1.6.dp.toPx()))
            }
            Text(
                "아래에서 곡을 골라\n플레이어에 올려보세요",
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = androidx.compose.ui.text.font.FontWeight.Medium),
                color = hint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 무대 양 끝 ⏮ ⏭ — 시각 38dp 흰 원, 터치 48dp. 프로토타입 `.stageNav` */
@Composable
private fun StageNav(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    enabled: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!enabled) { Spacer(modifier.size(MiconSpace.touchTarget)); return }
    IconButton(onClick = onClick, modifier = modifier.size(MiconSpace.touchTarget)) {
        Box(
            Modifier
                .size(38.dp)
                .miconShadow(elevation = 6.dp, shape = CircleShape, alpha = 0.16f)
                .clip(CircleShape)
                .background(Color(0xEBFFFFFF)),
            contentAlignment = Alignment.Center,
        ) { icon() }
    }
}

