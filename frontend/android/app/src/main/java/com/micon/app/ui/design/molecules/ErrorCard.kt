package com.micon.app.ui.design.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.atoms.MiconButton
import com.micon.app.ui.design.atoms.MiconButtonKind
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconCardKind
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 오류 카드 — **무슨 일 · 왜 · 다음 행동 하나**. 이 세 줄을 벗어나지 않는다.
 *
 * 지금은 세션이 만료되면 이유 없이 로그인 화면으로 튕기고, 권한을 거부해도 안내가 없다.
 * 또 "서버가 분석 중입니다" 처럼 시스템 사정을 사용자에게 떠넘기는 문구가 남아 있다 —
 * [why] 에는 사용자가 무엇을 할 수 있는지와 관련된 이유만 적는다.
 */
@Composable
fun ErrorCard(
    what: String,
    modifier: Modifier = Modifier,
    why: String? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    MiconCard(modifier = modifier.fillMaxWidth(), kind = MiconCardKind.Outlined) {
        Row(verticalAlignment = Alignment.Top) {
            Icon(
                Icons.Outlined.ErrorOutline,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.error,
                modifier = Modifier.size(20.dp),
            )
            Spacer(Modifier.size(MiconSpace.sm))
            Column(Modifier.fillMaxWidth()) {
                Text(what, style = MaterialTheme.typography.titleMedium)
                if (why != null) {
                    Spacer(Modifier.height(MiconSpace.xxs))
                    Text(
                        why,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (actionText != null && onAction != null) {
                    Spacer(Modifier.height(MiconSpace.sm))
                    MiconButton(
                        text = actionText,
                        onClick = onAction,
                        kind = MiconButtonKind.Outline,
                    )
                }
            }
        }
    }
}

/**
 * 빈 상태. 고정 높이 상자(560dp 짜리도 있었다)를 쓰지 않는다 —
 * 호출부에서 `Modifier.weight(1f)` 로 남는 높이를 채운다.
 */
@Composable
fun EmptyState(
    title: String,
    modifier: Modifier = Modifier,
    description: String? = null,
    icon: ImageVector? = null,
    actionText: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(
                icon,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(48.dp),
            )
            Spacer(Modifier.height(MiconSpace.md))
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (description != null) {
            Spacer(Modifier.height(MiconSpace.xs))
            Text(
                description,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        if (actionText != null && onAction != null) {
            Spacer(Modifier.height(MiconSpace.lg))
            MiconButton(text = actionText, onClick = onAction)
        }
    }
}
