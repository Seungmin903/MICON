package com.micon.app.ui.design.organisms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
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
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.CompactDiscSize
import com.micon.app.ui.design.tokens.MiconShadowTint
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.design.tokens.miconShadow

/**
 * 포터블 CD 플레이어 — 추천 탭의 주인공.
 *
 * 추천 탭에는 지금 검색·성별·시대·장르·인기 칩과 LP 버블 8개가 같은 무게로 놓여 있어
 * 무엇부터 보라는 것인지 알 수 없다. 이 화면의 주 행동은 하나다: **가운데 CD 를 누르는 것.**
 *
 * 그래서 **재생 버튼을 두지 않는다.** 가운데 CD 를 누르면 곡 상세로 넘어가면서 거기서 재생이 시작된다.
 * ⏮ ⏭ 는 "갈아끼우기" 이고, 아래 랙을 가로로 넘기는 것, 랙의 CD 를 직접 누르는 것과 같은 동작이다.
 *
 * 상단 일자 힌지 선은 그리지 않는다 — 바텀시트 손잡이로 오해한다.
 */
@Composable
fun CdPlayer(
    title: String,
    artist: String,
    trackIndex: Int,
    trackTotal: Int,
    onDiscClick: () -> Unit,
    onPrev: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    discSize: Dp = CompactDiscSize.playerDefault,
    spinning: Boolean = false,
) {
    val bodyPadding = MiconSpace.lg
    Surface(
        modifier = modifier
            .fillMaxWidth()
            .miconShadow(elevation = 14.dp, shape = RoundedCornerShape(40.dp), alpha = 0.14f),
        shape = RoundedCornerShape(40.dp),
        color = Color.Transparent,
    ) {
        Box {
            // 본체 — 투톤 플레이트. 위는 밝은 은색, 아래는 라벤더 그림자 쪽
            Canvas(Modifier.matchParentSize()) { drawPlayerBody() }

            Column(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = bodyPadding, vertical = MiconSpace.xl),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // --- 뚜껑 창: 금속 링 + 스모크 유리 + 사선 반사 ---
                Box(contentAlignment = Alignment.Center) {
                    CompactDisc(
                        title = title,
                        artist = artist,
                        imageUrl = imageUrl,
                        size = discSize,
                        spinning = spinning,
                        contentDescription = "$title, $artist. 눌러서 곡 자세히 보기",
                        modifier = Modifier.clickable(onClick = onDiscClick),
                    )
                    Canvas(Modifier.size(discSize + 22.dp)) { drawLidGlass() }
                }

                Spacer(Modifier.height(MiconSpace.lg))

                // --- 앞면: OPEN 슬라이더 · LCD · 물리 버튼 ---
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OpenSlider()
                    LcdPanel(trackIndex = trackIndex, trackTotal = trackTotal)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        PhysicalButton(onClick = onPrev) {
                            Icon(Icons.Outlined.SkipPrevious, contentDescription = "이전 곡", modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.width(MiconSpace.xxs))
                        PhysicalButton(onClick = onNext) {
                            Icon(Icons.Outlined.SkipNext, contentDescription = "다음 곡", modifier = Modifier.size(22.dp))
                        }
                    }
                }
            }
        }
    }
}

/** 본체 플레이트 · 왼쪽 힌지 너클 2개 · 오른쪽 볼륨 휠과 이어폰 잭 · MICON 음각 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPlayerBody() {
    val r = 40.dp.toPx()
    // 투톤: 위 밝은 면 / 아래 그늘진 면
    drawRoundRect(
        brush = Brush.verticalGradient(
            0.0f to Color(0xFFFCFBFF),
            0.55f to Color(0xFFF1EEFA),
            1.0f to Color(0xFFE6E2F4),
        ),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
    )
    drawRoundRect(
        color = Color.White.copy(alpha = 0.9f),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(r, r),
        style = Stroke(width = 1.dp.toPx()),
    )

    // 왼쪽 힌지 너클 2개 (상단 가로선 대신 — 가로선은 시트 손잡이로 읽힌다)
    val knuckleW = 5.dp.toPx()
    val knuckleH = 26.dp.toPx()
    listOf(0.30f, 0.52f).forEach { at ->
        drawRoundRect(
            color = MiconShadowTint.copy(alpha = 0.12f),
            topLeft = Offset(0f, size.height * at),
            size = Size(knuckleW, knuckleH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(knuckleW / 2f, knuckleW / 2f),
        )
    }

    // 오른쪽 볼륨 휠 — 홈 5줄
    val wheelW = 6.dp.toPx()
    val wheelH = 44.dp.toPx()
    val wheelX = size.width - wheelW
    val wheelY = size.height * 0.34f
    drawRoundRect(
        color = MiconShadowTint.copy(alpha = 0.10f),
        topLeft = Offset(wheelX, wheelY),
        size = Size(wheelW, wheelH),
        cornerRadius = androidx.compose.ui.geometry.CornerRadius(wheelW / 2f, wheelW / 2f),
    )
    repeat(5) { i ->
        val y = wheelY + wheelH * (i + 1) / 6f
        drawLine(
            color = MiconShadowTint.copy(alpha = 0.18f),
            start = Offset(wheelX + 1f, y),
            end = Offset(wheelX + wheelW - 1f, y),
            strokeWidth = 1.dp.toPx(),
        )
    }

    // 이어폰 잭
    drawCircle(
        color = MiconShadowTint.copy(alpha = 0.16f),
        radius = 5.dp.toPx(),
        center = Offset(size.width - 12.dp.toPx(), size.height * 0.78f),
    )
}

/** 뚜껑 — 금속 링 + 스모크 유리 창 + 사선 반사 */
private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawLidGlass() {
    val radius = size.minDimension / 2f
    val center = Offset(size.width / 2f, size.height / 2f)

    // 스모크 유리 (아주 옅게 — 앨범아트를 죽이지 않는다)
    drawCircle(
        brush = Brush.linearGradient(
            listOf(Color.White.copy(alpha = 0.16f), Color(0x0F2A2740)),
            start = Offset(0f, 0f),
            end = Offset(size.width, size.height),
        ),
        radius = radius,
        center = center,
    )
    // 금속 링 2겹
    drawCircle(
        brush = Brush.sweepGradient(
            listOf(
                Color(0xFFE9E6F4), Color(0xFFFFFFFF), Color(0xFFD9D5EA),
                Color(0xFFFFFFFF), Color(0xFFE9E6F4),
            ),
            center,
        ),
        radius = radius - 2.dp.toPx(),
        center = center,
        style = Stroke(width = 5.dp.toPx()),
    )
    drawCircle(
        color = MiconShadowTint.copy(alpha = 0.10f),
        radius = radius - 5.dp.toPx(),
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    // 사선 반사 한 줄
    drawLine(
        brush = Brush.linearGradient(
            listOf(Color.Transparent, Color.White.copy(alpha = 0.5f), Color.Transparent),
        ),
        start = Offset(center.x - radius * 0.7f, center.y + radius * 0.5f),
        end = Offset(center.x + radius * 0.4f, center.y - radius * 0.75f),
        strokeWidth = 3.dp.toPx(),
    )
}

/** 뚜껑을 여는 슬라이더. 장식이지만 "이건 기계다" 를 알려 준다 */
@Composable
private fun OpenSlider() {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .width(34.dp)
                .height(14.dp)
                .clip(RoundedCornerShape(50))
                .background(MiconShadowTint.copy(alpha = 0.10f)),
            contentAlignment = Alignment.CenterStart,
        ) {
            Box(
                Modifier
                    .padding(start = 2.dp)
                    .size(10.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            )
        }
        Spacer(Modifier.height(2.dp))
        Text(
            "OPEN",
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** LCD — 트랙 번호만. 재생 시간·비트레이트 같은 건 넣지 않는다 */
@Composable
private fun LcdPanel(trackIndex: Int, trackTotal: Int) {
    Box(
        Modifier
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2A2740))
            .padding(horizontal = MiconSpace.sm, vertical = 6.dp),
    ) {
        Text(
            "TRK %02d / %02d".format(trackIndex, trackTotal),
            style = MaterialTheme.typography.labelMedium,
            fontFamily = FontFamily.Monospace,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFBFF3DE),
        )
    }
}

/** 눌리는 물리 버튼. 시각은 36dp 원이지만 터치는 48dp */
@Composable
private fun PhysicalButton(onClick: () -> Unit, content: @Composable () -> Unit) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(MiconSpace.touchTarget),
    ) {
        Box(
            Modifier
                .size(36.dp)
                .clip(CircleShape)
                .background(
                    Brush.verticalGradient(
                        listOf(Color.White, Color(0xFFEDEAF7)),
                    )
                ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}
