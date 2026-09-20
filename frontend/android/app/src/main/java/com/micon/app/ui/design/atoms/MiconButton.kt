package com.micon.app.ui.design.atoms

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.BorderStroke
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 앱의 버튼은 이 하나뿐이다. PrimaryButton · OutlinePurpleButton · GreyButton ·
 * `Text(...).clickable` 을 모두 대체한다.
 *
 * 높이 44·46·56 혼용을 [MiconSpace.buttonHeight] 52dp 로 통일하되,
 * 고정이 아니라 `heightIn(min=)` 이라 글자 크게 설정에서도 잘리지 않는다.
 * 비활성은 M3 기본 토큰(컨테이너 onSurface 12% · 글자 38%)을 그대로 쓴다 —
 * 흰 글자 on 보라 45%(대비 1.88:1) 조합을 되살리지 않는다.
 */
enum class MiconButtonKind {
    /** 화면의 주 행동. 한 화면에 하나 */
    Primary,
    /** 주 행동 옆의 대안 */
    Outline,
    /** 중립 행동 — 회색 버튼 자리 */
    Tonal,
    /** 링크성 행동. 시각은 글자뿐이지만 터치 48dp 를 갖는다 */
    Text,
}

@Composable
fun MiconButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    kind: MiconButtonKind = MiconButtonKind.Primary,
    enabled: Boolean = true,
    leading: (@Composable () -> Unit)? = null,
) {
    val label: @Composable () -> Unit = {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (leading != null) {
                leading()
                Spacer(Modifier.width(MiconSpace.xs))
            }
            Text(
                text = text,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
    val sized = modifier.heightIn(min = MiconSpace.buttonHeight)
    val contentPadding = ButtonDefaults.ContentPadding

    when (kind) {
        MiconButtonKind.Primary -> Button(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = MiconShape.button,
            contentPadding = contentPadding,
        ) { label() }

        MiconButtonKind.Outline -> OutlinedButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = MiconShape.button,
            border = BorderStroke(1.dp, SolidColor(MaterialTheme.colorScheme.primary)),
            colors = ButtonDefaults.outlinedButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
            contentPadding = contentPadding,
        ) { label() }

        MiconButtonKind.Tonal -> FilledTonalButton(
            onClick = onClick,
            modifier = sized,
            enabled = enabled,
            shape = MiconShape.button,
            colors = ButtonDefaults.filledTonalButtonColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
                contentColor = MaterialTheme.colorScheme.onSurface,
            ),
            contentPadding = contentPadding,
        ) { label() }

        // 링크처럼 보이지만 터치 영역은 48dp — Text.clickable 20dp 문제를 없앤다
        MiconButtonKind.Text -> TextButton(
            onClick = onClick,
            modifier = modifier.defaultMinSize(minHeight = MiconSpace.touchTarget),
            enabled = enabled,
            shape = MiconShape.button,
            colors = ButtonDefaults.textButtonColors(
                contentColor = MaterialTheme.colorScheme.primary,
            ),
        ) { label() }
    }
}
