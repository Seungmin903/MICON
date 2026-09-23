package com.micon.app.ui.design.organisms

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.OpenInFull
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.tokens.MiconMonoFamily
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.design.tokens.miconShadow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 포터블 CD 플레이어 데크 — 추천 탭의 주인공. 프로토타입 `.deck2` 그대로.
 *
 * 정사각형(폭 − 40dp). 위에 `MICON` 칩 · 스피커 그릴 · `TRK 01 / 06` LCD 가 한 줄,
 * 그 아래 무대에 CD 가 올라간다. CD 가 없으면 점선 스핀들과 안내 문구.
 *
 * 연출 (프로토타입 CSS 그대로)
 *  - CD 장착: 작게·위에서·기울어진 채 떨어져 살짝 튄다 (`cdDrop` 0.62s)
 *  - 스핀들: 장착되면 흐려지며 0.9 로 줄어 사라진다
 *  - ⏮ ⏭: 0.78 → 1 로 튀어나온다
 *  - 재생 중 CD 회전 5.5s/회 · 데크 테두리를 따라 진행 링과 점
 *  - CD 가운데를 누르면 재생/일시정지 아이콘이 잠깐 떴다 사라진다 (`.cdOv`)
 *  - CD 를 아래로 끌어내리면 빼기
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
    /** 처음엔 무대가 비어 있다. false 면 점선 스핀들 + 안내가 보인다 */
    loaded: Boolean = title != null,
    /** 미리듣기 재생 중 — CD 가 돌고 진행 링이 채워진다 */
    playing: Boolean = false,
    /** 재생 진행 0..1 */
    progress: Float = 0f,
    spinning: Boolean = playing,
    hasPrev: Boolean = trackIndex > 1,
    hasNext: Boolean = trackIndex < trackTotal,
    /** CD 를 아래로 끌어내리면 — 프로토타입 "CD를 아래로 끌어내리면 빼기". null 이면 드래그 없음 */
    onDiscDragDown: (() -> Unit)? = null,
    /** 오른쪽 아래 확장 버튼. 선택한 곡의 상세 화면으로 이동한다. */
    onDetails: (() -> Unit)? = null,
) {
    val density = LocalDensity.current
    val pullThreshold = with(density) { 72.dp.toPx() }
    val pull = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()

    // cdDrop — 곡이 바뀔 때마다 0 → 1. 스프링 감쇠 0.55 라 1.04 쯤 튀고 돌아온다
    val drop = remember { Animatable(1f) }
    LaunchedEffect(title, loaded) {
        if (loaded && title != null) {
            drop.snapTo(0f)
            drop.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow))
        }
    }

    // .cdOv — 재생 상태가 바뀔 때만 0.7초 뜬다
    var overlayVisible by remember { mutableStateOf(false) }
    var lastPlaying by remember { mutableStateOf(playing) }
    LaunchedEffect(playing, loaded) {
        if (!loaded) { overlayVisible = false; lastPlaying = playing; return@LaunchedEffect }
        if (playing != lastPlaying) {
            lastPlaying = playing
            overlayVisible = true
            delay(700)
            overlayVisible = false
        }
    }

    val ringAlpha by animateFloatAsState(if (loaded) 1f else 0f, tween(400), label = "ring")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
            .miconShadow(elevation = 14.dp, shape = MiconShape.deck, alpha = 0.14f),
        shape = MiconShape.deck,
        color = Color.Transparent,
        border = BorderStroke(1.dp, Color(0x0B1E1B33)),
    ) {
        Box(Modifier.background(Brush.linearGradient(listOf(Color(0xFFFFFFFF), Color(0xFFF4F1FC))))) {
            Column(
                Modifier.matchParentSize().padding(MiconSpace.md),
                verticalArrangement = Arrangement.spacedBy(MiconSpace.sm),
            ) {
                DeckHead(trackIndex = trackIndex, trackTotal = trackTotal, loaded = loaded)

                BoxWithConstraints(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                    val stage = if (maxWidth < maxHeight) maxWidth else maxHeight

                    androidx.compose.animation.AnimatedVisibility(
                        visible = !loaded,
                        enter = fadeIn(tween(300)) + scaleIn(initialScale = 0.9f, animationSpec = tween(300)),
                        exit = fadeOut(tween(350)) + scaleOut(targetScale = 0.9f, animationSpec = tween(400)),
                    ) { Spindle(stage * 0.78f) }

                    if (loaded && title != null) {
                        CompactDisc(
                            title = title,
                            artist = artist,
                            imageUrl = imageUrl,
                            size = stage * 0.9f,
                            spinning = spinning,
                            spinPeriodMs = 5_500,
                            contentDescription = "$title, $artist. 눌러서 재생 또는 일시정지",
                            modifier = Modifier
                                .graphicsLayer {
                                    val p = drop.value
                                    val pullP = (pull.value / pullThreshold).coerceIn(0f, 1f)
                                    translationY = -52.dp.toPx() * (1f - p) + pull.value
                                    val s = 0.5f + 0.5f * p
                                    scaleX = s; scaleY = s
                                    rotationZ = -24f * (1f - p)
                                    alpha = ((0.4f + 0.6f * p).coerceIn(0f, 1f)) * (1f - 0.35f * pullP)
                                }
                                .then(
                                    if (onDiscDragDown == null) Modifier else Modifier.pointerInput(title) {
                                        detectVerticalDragGestures(
                                            onVerticalDrag = { _, dy -> scope.launch { pull.snapTo((pull.value + dy).coerceAtLeast(0f)) } },
                                            onDragEnd = {
                                                if (pull.value > pullThreshold) { onDiscDragDown(); scope.launch { pull.snapTo(0f) } }
                                                else scope.launch { pull.animateTo(0f, spring(stiffness = Spring.StiffnessMediumLow)) }
                                            },
                                            onDragCancel = { scope.launch { pull.animateTo(0f) } },
                                        )
                                    },
                                )
                                .clickable(onClick = onDiscClick),
                        )
                    }

                    // .cdOv — 가운데 74dp 흰 원에 ▶ / ⏸
                    androidx.compose.animation.AnimatedVisibility(
                        visible = overlayVisible,
                        enter = fadeIn(tween(220)) + scaleIn(initialScale = 0.7f, animationSpec = spring(dampingRatio = 0.5f)),
                        exit = fadeOut(tween(220)) + scaleOut(targetScale = 0.7f, animationSpec = tween(220)),
                    ) {
                        Box(
                            Modifier
                                .size(74.dp)
                                .miconShadow(elevation = 8.dp, shape = CircleShape, alpha = 0.22f)
                                .clip(CircleShape)
                                .background(Color(0xEBFFFFFF)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(
                                if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(34.dp),
                            )
                        }
                    }

                    StageNav(
                        icon = { Icon(Icons.Filled.SkipPrevious, contentDescription = "이전 곡", modifier = Modifier.size(19.dp)) },
                        onClick = onPrev,
                        visible = loaded && hasPrev,
                        modifier = Modifier.align(Alignment.CenterStart),
                    )
                    StageNav(
                        icon = { Icon(Icons.Filled.SkipNext, contentDescription = "다음 곡", modifier = Modifier.size(19.dp)) },
                        onClick = onNext,
                        visible = loaded && hasNext,
                        modifier = Modifier.align(Alignment.CenterEnd),
                    )
                    if (loaded && onDetails != null) {
                        IconButton(
                            onClick = onDetails,
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .size(44.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)),
                        ) {
                            Icon(
                                Icons.Outlined.OpenInFull,
                                contentDescription = "곡 상세 보기",
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                }
            }

            // .deckRing — 데크 테두리 6dp 안쪽을 따라 도는 진행 링 + 점
            ProgressRing(progress = progress, alpha = ringAlpha, modifier = Modifier.matchParentSize())
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
                style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Medium),
                color = hint,
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** 무대 양 끝 ⏮ ⏭ — 시각 38dp 흰 원, 터치 48dp. 0.78 → 1 로 튀어나온다 (`.stageNav i`) */
@Composable
private fun StageNav(
    icon: @Composable () -> Unit,
    onClick: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        modifier = modifier,
        enter = fadeIn(tween(240)) + scaleIn(initialScale = 0.78f, animationSpec = spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium)),
        exit = fadeOut(tween(200)) + scaleOut(targetScale = 0.78f, animationSpec = tween(200)),
    ) {
        IconButton(onClick = onClick, modifier = Modifier.size(MiconSpace.touchTarget)) {
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
}

/**
 * 프로토타입 `.deckRing` — 데크 가장자리 6dp 안쪽, 모서리 21dp 의 둥근 사각 경로.
 * 바탕 2.6dp `#E0DAF0`, 진행 3.4dp 보라→파랑, 끝에 5dp 점(흰 테두리). 왼쪽 위 모서리 뒤에서 시작해 시계 방향.
 */
@Composable
private fun ProgressRing(progress: Float, alpha: Float, modifier: Modifier = Modifier) {
    val measure = remember { PathMeasure() }
    val segment = remember { Path() }
    val ring = remember { Path() }
    Canvas(modifier) {
        if (alpha <= 0f) return@Canvas
        val inset = 6.dp.toPx()
        val r = 21.dp.toPx()
        val l = inset; val t = inset; val rt = size.width - inset; val b = size.height - inset
        ring.reset()
        ring.moveTo(l + r, t)
        ring.lineTo(rt - r, t)
        ring.arcTo(Rect(rt - 2 * r, t, rt, t + 2 * r), -90f, 90f, false)
        ring.lineTo(rt, b - r)
        ring.arcTo(Rect(rt - 2 * r, b - 2 * r, rt, b), 0f, 90f, false)
        ring.lineTo(l + r, b)
        ring.arcTo(Rect(l, b - 2 * r, l + 2 * r, b), 90f, 90f, false)
        ring.lineTo(l, t + r)
        ring.arcTo(Rect(l, t, l + 2 * r, t + 2 * r), 180f, 90f, false)
        ring.close()

        drawPath(ring, Color(0xFFE0DAF0).copy(alpha = alpha), style = Stroke(2.6.dp.toPx()))

        measure.setPath(ring, false)
        val len = measure.length
        val at = len * progress.coerceIn(0f, 1f)
        val brush = Brush.linearGradient(
            listOf(Color(0xFF8358F2).copy(alpha = alpha), Color(0xFF5B8DF3).copy(alpha = alpha)),
            start = Offset.Zero, end = Offset(size.width, size.height),
        )
        if (at > 0f) {
            segment.reset()
            measure.getSegment(0f, at, segment, true)
            drawPath(segment, brush, style = Stroke(3.4.dp.toPx(), cap = StrokeCap.Round))
        }
        val dot = measure.getPosition(at)
        drawCircle(Color.White.copy(alpha = alpha), radius = 6.3.dp.toPx(), center = dot)
        drawCircle(Color(0xFF5B8DF3).copy(alpha = alpha), radius = 5.dp.toPx(), center = dot)
    }
}
