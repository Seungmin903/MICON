package com.micon.app.ui.design.atoms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.design.tokens.miconShadow

/**
 * 카드 하나. MiconCard · SubCard · DashedBox · Detail.card 의 라운드 12/14/16 혼용과
 * 회색 그림자를 정리한다. 그림자는 항상 보라 틴트다.
 */
enum class MiconCardKind {
    /** 흰 카드 + 보라 그림자. 기본 */
    Elevated,
    /** 테두리만. 목록 안에서 카드가 여러 장일 때 */
    Outlined,
    /** 배경 위 살짝 눌린 웰. 보조 정보 */
    Well,
    /** 라벤더 틴트. 강조 배너 */
    Tinted,
}

@Composable
fun MiconCard(
    modifier: Modifier = Modifier,
    kind: MiconCardKind = MiconCardKind.Elevated,
    shape: Shape = MiconShape.card,
    contentPadding: Dp = MiconSpace.md,
    content: @Composable ColumnScope.() -> Unit,
) {
    val scheme = MaterialTheme.colorScheme
    val container = when (kind) {
        MiconCardKind.Elevated, MiconCardKind.Outlined -> scheme.surface
        MiconCardKind.Well -> scheme.surfaceVariant
        MiconCardKind.Tinted -> scheme.primaryContainer
    }
    val border = when (kind) {
        MiconCardKind.Outlined -> BorderStroke(1.dp, scheme.outlineVariant)
        else -> null
    }
    val shadowed = if (kind == MiconCardKind.Elevated) {
        modifier.miconShadow(elevation = 10.dp, shape = shape)
    } else {
        modifier
    }
    Surface(
        modifier = shadowed,
        shape = shape,
        color = container,
        contentColor = scheme.onSurface,
        border = border,
    ) {
        Column(Modifier.padding(contentPadding), content = content)
    }
}
