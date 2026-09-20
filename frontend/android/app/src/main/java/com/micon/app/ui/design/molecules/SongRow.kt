package com.micon.app.ui.design.molecules

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.atoms.CompactDisc
import com.micon.app.ui.design.atoms.CompactDiscSize
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 곡 한 줄. 기존 `SongRow` · 검색 버블 · `ReportFitCard` 행 · 기록 행을 하나로 모은다.
 *
 * CD 는 56dp (기존 LP 76dp 에서 줄였다 — 한 화면에 들어오는 곡이 늘고, 목록에서 아트는
 * 구분만 되면 충분하다). 행 전체가 48dp 이상 터치 영역이다.
 */
@Composable
fun SongRow(
    title: String,
    artist: String,
    modifier: Modifier = Modifier,
    imageUrl: String? = null,
    /** "한 키 내려" 같은 말. `+2 key` 를 그대로 넣지 않는다 */
    keyLabel: String? = null,
    /** 두 번째 줄 오른쪽에 붙는 보조 문구 — 시각·점수 등 */
    meta: String? = null,
    discSize: androidx.compose.ui.unit.Dp = CompactDiscSize.row,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (RowScope.() -> Unit)? = null,
) {
    val clickable = if (onClick != null) modifier.clickable(onClick = onClick) else modifier
    Row(
        modifier = clickable
            .fillMaxWidth()
            .heightIn(min = MiconSpace.touchTarget + MiconSpace.md)
            .padding(vertical = MiconSpace.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompactDisc(
            title = title,
            artist = artist,
            imageUrl = imageUrl,
            size = discSize,
            contentDescription = null,
        )
        Spacer(Modifier.width(MiconSpace.sm))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    artist,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (meta != null) {
                    Spacer(Modifier.width(MiconSpace.xs))
                    Text(
                        meta,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
        if (keyLabel != null) {
            Spacer(Modifier.width(MiconSpace.xs))
            MiconBadge(keyLabel)
        }
        if (trailing != null) {
            Spacer(Modifier.width(MiconSpace.xxs))
            trailing()
        }
    }
}
