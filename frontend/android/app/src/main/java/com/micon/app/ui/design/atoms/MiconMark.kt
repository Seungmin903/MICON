package com.micon.app.ui.design.atoms

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * 앱 마크 — 이퀄라이저 5줄, 가운데는 마이크. 프로토타입 `MARK_SVG`(viewBox 120×100) 를 옮겼다.
 * 탭 헤더의 `logo sm markonly` 자리에 쓴다. 폭:높이 = 6:5, 기본 23×19dp.
 *
 * [onDark] 이면 그라디언트 대신 흰 단색 — 프로토타입 `.logo.on-dark` 규칙.
 */
@Composable
fun MiconMark(
    modifier: Modifier = Modifier,
    height: Dp = 19.dp,
    onDark: Boolean = false,
) {
    Canvas(
        modifier
            .size(width = height * 1.2f, height = height)
            .semantics { contentDescription = "MICON" },
    ) {
        val u = size.width / 120f // 1 viewBox 단위
        fun bar(x: Float, y: Float, w: Float, h: Float, brush: Brush) {
            drawRoundRect(
                brush = brush,
                topLeft = Offset(x * u, y * u),
                size = Size(w * u, h * u),
                cornerRadius = CornerRadius(w * u / 2f, w * u / 2f),
            )
        }
        fun g(stops: List<Color>) = if (onDark) Brush.verticalGradient(listOf(Color.White, Color.White))
        else Brush.verticalGradient(stops, startY = 0f, endY = size.height)

        bar(6f, 30f, 15f, 40f, g(listOf(Color(0xFF8B5CF6), Color(0xFF5D8DF3))))
        bar(27f, 12f, 17f, 76f, g(listOf(Color(0xFFB15CF7), Color(0xFF6A7DF4), Color(0xFF7DD3FC))))
        drawMic(u, g(listOf(Color(0xFF5B5CF0), Color(0xFF7FA6F8))))
        bar(72f, 12f, 17f, 76f, g(listOf(Color(0xFF7DD3FC), Color(0xFF6E7CF5), Color(0xFFC084FC))))
        bar(99f, 30f, 15f, 40f, g(listOf(Color(0xFF6D8DF5), Color(0xFF8B5CF6))))
    }
}

/** 가운데 마이크 — 캡슐 헤드(50..66 × 22..62) 와 좁아지는 손잡이·베이스를 두 덩이로 근사 */
private fun DrawScope.drawMic(u: Float, brush: Brush) {
    drawRoundRect(brush, Offset(50f * u, 22f * u), Size(16f * u, 44f * u), CornerRadius(8f * u, 8f * u))
    drawRoundRect(brush, Offset(53.5f * u, 62f * u), Size(9f * u, 12f * u), CornerRadius(3f * u, 3f * u))
    drawRoundRect(brush, Offset(50.5f * u, 74f * u), Size(15f * u, 15f * u), CornerRadius(7.5f * u, 7.5f * u))
}
