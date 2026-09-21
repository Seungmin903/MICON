package com.micon.app.ui.design.organisms

import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.RenderEffect
import android.graphics.Shader
import android.os.Build
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asComposeRenderEffect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.design.tokens.miconShadow

/**
 * 하단 내비 "물방울" — 프로토타입 `.tabbar` + `.tabgoo`.
 *
 * 흰 알약(h68, 좌우 14, 바닥 16 + 시스템 내비 바) 위를 선택 탭 자리의 보라 방울이 따라다닌다.
 * 방울은 큰 원 1 + 꼬리 원 4 로 이뤄지고, 각 원이 서로 다른 시간(500~860ms, 오버슈트)으로
 * 움직여 목이 늘어났다가 붙는다. 뭉침은 SVG goo 필터와 같은 체인
 * (blur 6 → alpha ×24 −11 → blur 0.9) 을 [RenderEffect] 로 건다 — API 31 미만은 큰 원만 그린다.
 *
 * 3버튼 내비 바·제스처 바 모두 [navigationBarsPadding] 으로 비켜난다. 콘텐츠는 이 바 아래로
 * 흘러가므로 스크롤 화면은 [MiconSpace.navBarContentPadding] 을 하단에 확보한다.
 */
enum class MiconTab(val label: String, val icon: ImageVector) {
    Analysis("분석", Icons.Outlined.GraphicEq),
    Recommend("추천", Icons.Outlined.Album),
    Practice("연습", Icons.Outlined.Mic),
    History("기록", Icons.Outlined.Schedule),
    Favorites("찜", Icons.Outlined.FavoriteBorder),
}

private val GooA = Color(0xFF8358F2)
private val GooB = Color(0xFF6B6BF0)
private val GooC = Color(0xFF5B8DF3)
private val Overshoot = CubicBezierEasing(0.52f, -0.18f, 0.3f, 1.26f)

@Composable
fun FloatingNavBar(
    selected: MiconTab,
    onSelect: (MiconTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    val tabs = MiconTab.entries
    val index = tabs.indexOf(selected)
    // 각 원의 가로 위치(탭 인덱스 단위). 큰 원이 먼저, 꼬리가 시차를 두고 따라온다.
    val main by animateFloatAsState(index.toFloat(), tween(500, easing = Overshoot), label = "goo-main")
    val t1 by animateFloatAsState(index.toFloat(), tween(580, easing = Overshoot), label = "goo-t1")
    val t2 by animateFloatAsState(index.toFloat(), tween(660, easing = Overshoot), label = "goo-t2")
    val t3 by animateFloatAsState(index.toFloat(), tween(750, easing = Overshoot), label = "goo-t3")
    val t4 by animateFloatAsState(index.toFloat(), tween(860, easing = Overshoot), label = "goo-t4")

    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(start = MiconSpace.navBarInset, end = MiconSpace.navBarInset, bottom = MiconSpace.navBarBottomGap),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .height(MiconSpace.navBarHeight)
                .miconShadow(elevation = 14.dp, shape = CircleShape, alpha = 0.22f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.96f),
        ) {
            Box(Modifier.fillMaxSize()) {
                GooLayer(listOf(main to 52.dp, t1 to 24.dp, t2 to 16.dp, t3 to 10.dp, t4 to 6.dp), tabs.size)
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    tabs.forEach { tab ->
                        NavItem(tab, tab == selected, { onSelect(tab) }, Modifier.weight(1f))
                    }
                }
            }
        }
    }
}

@Composable
private fun GooLayer(circles: List<Pair<Float, androidx.compose.ui.unit.Dp>>, count: Int) {
    val effect = remember { gooRenderEffect() }
    Canvas(
        Modifier
            .fillMaxSize()
            .graphicsLayer { renderEffect = effect },
    ) {
        val slot = size.width / count
        val cy = size.height / 2f
        val brush = Brush.linearGradient(listOf(GooA, GooB, GooC), Offset(0f, 0f), Offset(size.height, size.height))
        val drawable = if (effect == null) circles.take(1) else circles
        drawable.forEach { (pos, d) ->
            drawCircle(brush, radius = d.toPx() / 2f, center = Offset((pos + 0.5f) * slot, cy))
        }
    }
}

/** SVG `gooF2`: feGaussianBlur 6 → feColorMatrix alpha `24 -11` → feGaussianBlur 0.9 */
private fun gooRenderEffect(): androidx.compose.ui.graphics.RenderEffect? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    val density = android.content.res.Resources.getSystem().displayMetrics.density
    val blur = RenderEffect.createBlurEffect(6f * density, 6f * density, Shader.TileMode.DECAL)
    val matrix = ColorMatrix(
        floatArrayOf(
            1f, 0f, 0f, 0f, 0f,
            0f, 1f, 0f, 0f, 0f,
            0f, 0f, 1f, 0f, 0f,
            0f, 0f, 0f, 24f, -11f * 255f,
        ),
    )
    val threshold = RenderEffect.createColorFilterEffect(ColorMatrixColorFilter(matrix), blur)
    return RenderEffect.createBlurEffect(0.9f * density, 0.9f * density, threshold, Shader.TileMode.DECAL).asComposeRenderEffect()
}

@Composable
private fun NavItem(tab: MiconTab, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    Column(
        modifier = modifier
            .fillMaxSize()
            .selectable(selected, onClick = onClick, role = Role.Tab, interactionSource = remember { MutableInteractionSource() }, indication = null),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            tab.icon,
            contentDescription = tab.label,
            tint = if (selected) Color.White else muted,
            modifier = Modifier.size(if (selected) 25.dp else 23.dp),
        )
        if (!selected) {
            Spacer(Modifier.height(2.dp))
            Text(tab.label, style = MaterialTheme.typography.labelMedium, color = muted, maxLines = 1)
        }
    }
}
