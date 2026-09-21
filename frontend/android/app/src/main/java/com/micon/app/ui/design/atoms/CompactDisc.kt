package com.micon.app.ui.design.atoms

import android.graphics.Typeface
import android.provider.Settings
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalInspectionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.micon.app.ui.design.tokens.MiconLabelPastels
import com.micon.app.ui.design.tokens.MiconOnSurface
import androidx.compose.ui.graphics.Path as ComposePath
import android.graphics.Path as AndroidPath

/**
 * CD. `VinylArt`(lp_ring.png) · `SongDetailTurntable`(tonearm.png) · `BubbleCluster` 를 모두 대체한다.
 *
 * PNG 를 쓰지 않고 Canvas 로 그린다 — `lp_ring.png` 는 260×260px 한 장뿐이라
 * 150dp 이상에서 400~600px 로 늘어나 깨졌다. 벡터로 그리면 WQHD+ 에서도 선명하다.
 *
 * 지름 D 기준 비율
 *  - 아트 D (Crop, 원형)
 *  - 무지개 허브 링 0.28D
 *  - 홀 0.12D
 *  - 광택: 위쪽 넓은 하이라이트 1 + 좁은 반사 2줄 (디스크가 돌아도 **광택은 고정**)
 *  - 동심원 홈 링은 그리지 않는다 — LP 로 보인다
 */
object CompactDiscSize {
    /** 추천 플레이어 중앙. S10 220 · S23U 244 는 호출부에서 [forPlayer] 로 계산 */
    val playerDefault: Dp = 220.dp
    /** 랙 미니 */
    val rack: Dp = 52.dp
    /** 목록 행 (기존 76 → 56) */
    val row: Dp = 56.dp
    /** 연습 화면 헤더 */
    val header: Dp = 88.dp
    /** 기록 행 */
    val history: Dp = 48.dp

    /**
     * 플레이어 중앙 디스크 = `min(폭 − 140, 가용높이 × 0.42)`.
     * 360dp 폭 · 538dp 가용에서 220dp, 384 · 602 에서 244dp 가 나온다.
     */
    fun forPlayer(screenWidth: Dp, availableHeight: Dp): Dp {
        val byWidth = screenWidth - 140.dp
        val byHeight = availableHeight * 0.42f
        return if (byWidth < byHeight) byWidth else byHeight
    }
}

/** 파스텔 허브 링. 무지개지만 채도를 낮춰 앨범아트를 이기지 않는다 */
private val HubRingColors = listOf(
    Color(0xFFFFD6E0), Color(0xFFFFE9C6), Color(0xFFE6F5C9),
    Color(0xFFC9EFE6), Color(0xFFCFE0FA), Color(0xFFE2D4FB),
    Color(0xFFFFD6E0),
)

@Composable
fun CompactDisc(
    title: String,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    artist: String? = null,
    size: Dp = CompactDiscSize.row,
    spinning: Boolean = false,
    /** 한 바퀴 시간. 기본 12초(느린 대기 회전), 플레이어 재생 중은 5.5초 */
    spinPeriodMs: Int = 12_000,
    contentDescription: String? = null,
) {
    val angle = rememberDiscAngle(spinning, spinPeriodMs)
    val describedAs = contentDescription
    // 글자를 읽을 수 있는 크기부터만 "인쇄 라벨" 을 찍는다. 랙 미니는 색과 허브만.
    val printLabel = size >= 96.dp

    Box(
        modifier = modifier
            .size(size)
            .shadow(4.dp, CircleShape, clip = false, ambientColor = MiconOnSurface.copy(alpha = 0.22f), spotColor = MiconOnSurface.copy(alpha = 0.22f))
            .semantics { describedAs?.let { this.contentDescription = it } },
    ) {
        // --- 도는 층: 아트 + 인쇄 라벨 + 허브 링 + 홀 ---
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { rotationZ = angle }
                .clip(CircleShape),
        ) {
            if (imageUrl != null) {
                AsyncImage(
                    model = imageUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            } else {
                PrintedLabel(title = title, artist = artist, printText = printLabel)
            }
            Canvas(Modifier.fillMaxSize()) { drawHubAndHole() }
        }
        // --- 고정 층: 광택 + 테두리 (돌지 않는다) ---
        Canvas(Modifier.fillMaxSize()) { drawGlossAndRim() }
    }
}

/**
 * 앨범아트가 없을 때 — 제목을 위쪽 원호에, 가수를 홀 아래에 인쇄한다.
 * 배경은 제목 해시로 고른 파스텔 2색이라 **같은 곡은 항상 같은 색**이다.
 */
@Composable
private fun PrintedLabel(title: String, artist: String?, printText: Boolean) {
    val pastel = remember(title) {
        MiconLabelPastels[((title.hashCode() % MiconLabelPastels.size) + MiconLabelPastels.size) % MiconLabelPastels.size]
    }
    Canvas(Modifier.fillMaxSize()) {
        drawCircle(Brush.linearGradient(listOf(pastel.first, pastel.second)))
        if (!printText) return@Canvas

        val d = this.size.minDimension
        val cx = this.size.width / 2f
        val cy = this.size.height / 2f
        val ink = MiconOnSurface.copy(alpha = 0.72f).toArgb()

        drawIntoCanvas { canvas ->
            val titlePaint = android.graphics.Paint().apply {
                isAntiAlias = true
                color = ink
                textSize = d * 0.085f
                textAlign = android.graphics.Paint.Align.CENTER
                typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
            }
            val r = d * 0.37f
            val arc = AndroidPath().apply {
                addArc(cx - r, cy - r, cx + r, cy + r, 200f, 140f)
            }
            canvas.nativeCanvas.drawTextOnPath(title.take(18), arc, 0f, 0f, titlePaint)

            if (!artist.isNullOrBlank()) {
                val artistPaint = android.graphics.Paint().apply {
                    isAntiAlias = true
                    color = MiconOnSurface.copy(alpha = 0.55f).toArgb()
                    textSize = d * 0.062f
                    textAlign = android.graphics.Paint.Align.CENTER
                }
                canvas.nativeCanvas.drawText(artist.take(14), cx, cy + d * 0.26f, artistPaint)
            }
        }
    }
}

/** 허브 링 0.28D + 홀 0.12D */
private fun DrawScope.drawHubAndHole() {
    val d = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)
    val hubRadius = d * 0.14f
    val holeRadius = d * 0.06f

    // 허브: 아트를 가려 무지개 띠가 드러나는 부분
    drawCircle(
        brush = Brush.sweepGradient(HubRingColors, center),
        radius = hubRadius,
        center = center,
    )
    // 허브 안쪽 흰 테 — 실물 CD 의 클램핑 영역
    drawCircle(Color.White.copy(alpha = 0.92f), radius = holeRadius * 1.9f, center = center)
    drawCircle(
        color = Color.White.copy(alpha = 0.6f),
        radius = hubRadius,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
    // 홀
    drawCircle(Color(0xFFF2F1F8), radius = holeRadius, center = center)
    drawCircle(
        color = MiconOnSurface.copy(alpha = 0.18f),
        radius = holeRadius,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/** 위쪽 넓은 하이라이트 + 좁은 반사 2줄 + 1dp 흰 70% 테두리 */
private fun DrawScope.drawGlossAndRim() {
    val d = size.minDimension
    val center = Offset(size.width / 2f, size.height / 2f)
    val radius = d / 2f

    val disc = ComposePath().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                center = center,
                radius = radius,
            )
        )
    }
    clipPath(disc) {
        rotate(degrees = -22f, pivot = center) {
            // 넓은 하이라이트
            drawRect(
                brush = Brush.verticalGradient(
                    listOf(Color.White.copy(alpha = 0.30f), Color.White.copy(alpha = 0.02f)),
                    startY = -d * 0.1f,
                    endY = d * 0.42f,
                ),
                topLeft = Offset(center.x - d, center.y - d * 0.62f),
                size = Size(d * 2f, d * 0.52f),
            )
            // 좁은 반사 2줄
            drawRect(
                color = Color.White.copy(alpha = 0.22f),
                topLeft = Offset(center.x - d, center.y + d * 0.08f),
                size = Size(d * 2f, d * 0.035f),
            )
            drawRect(
                color = Color.White.copy(alpha = 0.12f),
                topLeft = Offset(center.x - d, center.y + d * 0.17f),
                size = Size(d * 2f, d * 0.02f),
            )
        }
    }
    drawCircle(
        brush = SolidColor(Color.White.copy(alpha = 0.7f)),
        radius = radius - 0.5f,
        center = center,
        style = Stroke(width = 1.dp.toPx()),
    )
}

/**
 * 회전각. 12초에 한 바퀴 linear.
 *
 * 멈추는 경우
 *  - [spinning] 이 false (재생 중이 아님)
 *  - 화면이 백그라운드로 감 (ON_PAUSE)
 *  - 시스템 "애니메이션 배율 0" (접근성 · 배터리 절약)
 *  - Preview / 테스트
 *
 * 화면을 벗어나면 컴포저블이 사라지므로 애니메이션도 함께 끝난다.
 */
@Composable
private fun rememberDiscAngle(spinning: Boolean, periodMs: Int): Float {
    val context = LocalContext.current
    val inspecting = LocalInspectionMode.current
    val animationsOn = remember(context) {
        Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) > 0f
    }

    var resumed by remember { mutableStateOf(true) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> resumed = true
                Lifecycle.Event.ON_PAUSE -> resumed = false
                else -> Unit
            }
        }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }

    val active = spinning && resumed && animationsOn && !inspecting

    // 멈추면 그 각도에서 선다 — 일시정지한 CD 가 0° 로 튀어 돌아가지 않게
    val angle = remember { androidx.compose.animation.core.Animatable(0f) }
    LaunchedEffect(active, periodMs) {
        if (!active) return@LaunchedEffect
        while (true) {
            val from = angle.value % 360f
            angle.snapTo(from)
            angle.animateTo(from + 360f, tween(durationMillis = periodMs, easing = LinearEasing))
        }
    }
    return angle.value
}
