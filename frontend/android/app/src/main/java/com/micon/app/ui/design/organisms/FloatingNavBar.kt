package com.micon.app.ui.design.organisms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.GraphicEq
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Album
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.tokens.MiconShadowTint
import com.micon.app.ui.design.tokens.MiconSpace
import com.micon.app.ui.design.tokens.miconShadow

/**
 * 하단 내비 — 전체 폭 80dp 바에서 좌우 16dp 띄운 **64dp 알약**으로 바꾼다.
 *
 * 콘텐츠는 알약 아래로 흘러가므로(`contentPadding` [MiconSpace.navBarContentPadding])
 * 바가 차지하던 높이를 화면이 되돌려 받는다 — S10 에서 실질 +16dp.
 *
 * 활성 탭은 색만으로 구분하지 않는다. 아이콘 뒤에 라벤더 알약이 깔리고 라벨이 굵어져서,
 * 색을 구분하지 못해도 어디에 있는지 알 수 있다.
 */
enum class MiconTab(val label: String, val icon: ImageVector) {
    /** 기존 "리포트" — 연습 결과와 헷갈려서 바꿨다 */
    Analysis("분석", Icons.Outlined.GraphicEq),
    Recommend("추천", Icons.Outlined.Album),
    Practice("연습", Icons.Outlined.Mic),
    /** 기존 "평가" — 누가 무엇을 평가하는지 불명확했다 */
    History("기록", Icons.Outlined.History),
    Favorites("찜", Icons.Outlined.Bookmark),
}

@Composable
fun FloatingNavBar(
    selected: MiconTab,
    onSelect: (MiconTab) -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .fillMaxWidth()
            .navigationBarsPadding()
            .padding(
                start = MiconSpace.navBarInset,
                end = MiconSpace.navBarInset,
                bottom = MiconSpace.navBarBottomGap,
            ),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = MiconSpace.navBarHeight)
                .miconShadow(elevation = 10.dp, shape = CircleShape, alpha = 0.16f),
            shape = CircleShape,
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MiconShadowTint.copy(alpha = 0.06f)),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = MiconSpace.navBarHeight),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                MiconTab.entries.forEach { tab ->
                    NavItem(
                        tab = tab,
                        selected = tab == selected,
                        onClick = { onSelect(tab) },
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

@Composable
private fun NavItem(
    tab: MiconTab,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val scheme = MaterialTheme.colorScheme
    val tint = if (selected) scheme.primary else scheme.onSurfaceVariant
    Column(
        modifier = modifier
            .heightIn(min = MiconSpace.touchTarget)
            .selectable(
                selected = selected,
                onClick = onClick,
                role = Role.Tab,
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box(
            Modifier
                .height(26.dp)
                .then(if (selected) Modifier.width(44.dp) else Modifier.width(26.dp))
                .background(
                    color = if (selected) scheme.primaryContainer else androidx.compose.ui.graphics.Color.Transparent,
                    shape = RoundedCornerShape(50),
                ),
            contentAlignment = Alignment.Center,
        ) {
            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.height(2.dp))
        Text(
            tab.label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
            color = tint,
        )
    }
}
