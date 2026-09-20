package com.micon.app.ui.design.tokens

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 라운드 토큰. 카드·시트는 크게, 버튼·필드는 중간, 칩·배지는 full */
object MiconShape {
    val card: CornerBasedShape = RoundedCornerShape(20.dp)
    /** 히어로 카드 · CD 플레이어 본체 */
    val cardLarge: CornerBasedShape = RoundedCornerShape(28.dp)
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
    /** 알약. 높이 52dp 의 절반 */
    val button: Shape = RoundedCornerShape(26.dp)
    /** CD 플레이어 데크 */
    val deck: CornerBasedShape = RoundedCornerShape(26.dp)
    val field: CornerBasedShape = RoundedCornerShape(16.dp)
    val chip: Shape = RoundedCornerShape(percent = 50)
}

internal val MiconShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = MiconShape.field,
    large = MiconShape.card,
    extraLarge = MiconShape.cardLarge,
)

/**
 * 보라 틴트 그림자. 회색 그림자를 쓰지 않는다 — 라벤더 배경 위에서 때가 탄 것처럼 보인다.
 *
 * 시안의 `0 10 28 rgba(76,64,160,0.10)` 에 해당한다. Compose 그림자는 offset·blur 를
 * 따로 받지 않으므로 elevation 으로 근사하고, 색만 [MiconShadowTint] 로 물들인다.
 */
fun Modifier.miconShadow(
    elevation: Dp = 10.dp,
    shape: Shape = MiconShape.card,
    alpha: Float = 0.10f,
): Modifier = shadow(
    elevation = elevation,
    shape = shape,
    clip = false,
    ambientColor = MiconShadowTint.copy(alpha = alpha),
    spotColor = MiconShadowTint.copy(alpha = alpha),
)
