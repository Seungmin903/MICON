package com.micon.app.ui.design.organisms

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 상단 바 하나. `MiconHeader`(64 고정) · `BackHeader`(60) · `GuestReportHeader`(72) 를 모은다.
 *
 * 높이는 고정하지 않고 `heightIn(min=)` 이다 — 글자 크게 설정에서 제목이 잘리지 않는다.
 */
@Composable
fun MiconTopBar(
    modifier: Modifier = Modifier,
    title: String? = null,
    onBack: (() -> Unit)? = null,
    actions: @Composable (RowScope.() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = MiconSpace.topBarMinHeight)
            .padding(horizontal = MiconSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.size(MiconSpace.touchTarget)) {
                Icon(
                    Icons.AutoMirrored.Outlined.ArrowBack,
                    contentDescription = "뒤로",
                    modifier = Modifier.size(24.dp),
                )
            }
        } else {
            Spacer(Modifier.width(MiconSpace.sm))
        }
        Box(Modifier.weight(1f)) {
            if (title != null) {
                Text(
                    title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            } else {
                MiconWordmark()
            }
        }
        if (actions != null) {
            actions()
        } else {
            Spacer(Modifier.width(MiconSpace.sm))
        }
    }
}

/** 로고 자리. 이미지가 아니라 글자라 어떤 밀도에서도 선명하다 */
@Composable
private fun MiconWordmark() {
    Text(
        "MICON",
        style = MaterialTheme.typography.titleLarge,
        color = MaterialTheme.colorScheme.primary,
    )
}
