package com.micon.app.ui.design.atoms

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.tokens.MiconColors
import com.micon.app.ui.design.tokens.MiconShape
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 칩 6벌(PillChip · SoftChip · ToggleChip · SectionChips · KeyBadge · 서브 배지)을 하나로.
 *
 * 시각 높이는 32dp 로 통일하고, 누를 수 있는 칩은 [minimumInteractiveComponentSize] 로
 * **보이는 크기는 그대로 둔 채 터치 영역만** 48dp 로 넓힌다. `defaultMinSize(48)` 를 쓰면
 * 칩 자체가 48dp 로 부풀어 시안의 32dp 가 나오지 않는다.
 * 29dp 칩에 6dp 간격이던 배치를 32dp · 간격 8dp 로 되돌린다.
 */
enum class MiconChipTone {
    /** 기본 — 보라 틴트 */
    Neutral,
    /** 편해요 · 안정 */
    Success,
    /** 도전 · 강조 */
    Challenge,
    /** 주의 — "한 키 내려" · "높은 레 2회" */
    Warning,
    /** 오류 */
    Error,
}

/** 선택할 수 있는 칩 (필터 · 구간 · 추천 종류) */
@Composable
fun MiconFilterChip(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize(),
        enabled = enabled,
        shape = MiconShape.chip,
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = MaterialTheme.colorScheme.surface,
            labelColor = MaterialTheme.colorScheme.onSurfaceVariant,
            selectedContainerColor = MaterialTheme.colorScheme.secondaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ),
        border = BorderStroke(
            width = 1.dp,
            color = if (selected) Color.Transparent else MaterialTheme.colorScheme.outlineVariant,
        ),
    )
}

/** 누르면 동작하는 칩 (자세히 · 다시 듣기) */
@Composable
fun MiconActionChip(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable () -> Unit)? = null,
) {
    AssistChip(
        onClick = onClick,
        modifier = modifier.minimumInteractiveComponentSize(),
        shape = MiconShape.chip,
        label = { Text(text, style = MaterialTheme.typography.labelLarge) },
        leadingIcon = leading,
        colors = AssistChipDefaults.assistChipColors(
            labelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    )
}

/**
 * 누를 수 없는 표시용 배지 (키 배지 · 적합 칩 · 상태 라벨).
 * 터치 대상이 아니므로 48dp 를 요구하지 않는다.
 */
@Composable
fun MiconBadge(
    text: String,
    modifier: Modifier = Modifier,
    tone: MiconChipTone = MiconChipTone.Neutral,
) {
    val extended = MiconColors.current
    val scheme = MaterialTheme.colorScheme
    val container = when (tone) {
        MiconChipTone.Neutral -> scheme.secondaryContainer
        MiconChipTone.Success -> extended.successContainer
        MiconChipTone.Challenge -> scheme.tertiaryContainer
        MiconChipTone.Warning -> extended.warningContainer
        MiconChipTone.Error -> scheme.errorContainer
    }
    val content = when (tone) {
        MiconChipTone.Neutral -> scheme.onSecondaryContainer
        MiconChipTone.Success -> extended.success
        MiconChipTone.Challenge -> scheme.tertiary
        MiconChipTone.Warning -> extended.warning
        MiconChipTone.Error -> scheme.error
    }
    Surface(color = container, contentColor = content, shape = MiconShape.chip, modifier = modifier) {
        Box(
            modifier = Modifier
                .heightIn(min = MiconSpace.chipHeight)
                .padding(PaddingValues(horizontal = MiconSpace.sm, vertical = MiconSpace.xxs)),
            contentAlignment = Alignment.Center,
        ) {
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** `+2 key` 대신 사람 말로. 키 조절은 "한 키 올려/내려" 로 읽힌다 */
fun keyShiftLabel(steps: Int): String = when {
    steps == 0 -> "원래 키"
    steps > 0 -> "${steps}키 올려"
    else -> "${-steps}키 내려"
}
