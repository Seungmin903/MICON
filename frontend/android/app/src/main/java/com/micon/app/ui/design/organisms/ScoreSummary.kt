package com.micon.app.ui.design.organisms

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import com.micon.app.ui.design.atoms.MiconBadge
import com.micon.app.ui.design.atoms.MiconCard
import com.micon.app.ui.design.atoms.MiconChipTone
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 연습 결과 · 연습 중 점수. `ScoreBoard` + `FinishedPanel` 을 모은다.
 *
 * 화면에 남기는 숫자는 **0~100 점수 하나**뿐이다. 등급 · 음정 65% + 박자 35% 같은
 * 채점 방식 · 정확 · 놓침 · 보정 ms · `+12¢` 는 전부 내린다 — 부르는 사람이
 * 그 숫자를 보고 할 수 있는 일이 없다.
 */
@Composable
fun ScoreSummary(
    score: Int,
    modifier: Modifier = Modifier,
    /** 한 줄 평. "후렴은 안정적이었어요" 처럼 사람 말로 */
    comment: String? = null,
    /** 잘한 구간 · 아쉬운 구간 각각 한 개까지 */
    bestSection: String? = null,
    weakSection: String? = null,
) {
    MiconCard(modifier = modifier.fillMaxWidth()) {
        Column(
            Modifier.fillMaxWidth().semantics { contentDescription = "점수 ${score}점" },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                "$score",
                style = MaterialTheme.typography.displayLarge,
                color = MaterialTheme.colorScheme.primary,
            )
            if (comment != null) {
                Spacer(Modifier.height(MiconSpace.xs))
                Text(
                    comment,
                    style = MaterialTheme.typography.bodyLarge,
                    textAlign = TextAlign.Center,
                )
            }
        }
        if (bestSection != null || weakSection != null) {
            Spacer(Modifier.height(MiconSpace.md))
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(MiconSpace.xs),
            ) {
                if (bestSection != null) {
                    MiconBadge("잘한 곳 · $bestSection", tone = MiconChipTone.Success)
                }
                if (weakSection != null) {
                    MiconBadge("아쉬운 곳 · $weakSection", tone = MiconChipTone.Challenge)
                }
            }
        }
    }
}
