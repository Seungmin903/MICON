package com.micon.app.ui.design.molecules

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.micon.app.ui.design.tokens.MiconSpace

/**
 * 여러 단계를 거치는 과정의 머리. `StepBar("n / m")` · 설문 단계 표시 ·
 * 측정 "확인된 음 3 / 8" 을 하나로 모은다.
 *
 * 온보딩 5단계(로그인 → 설문 → 측정 → 결과 → 추천)가 지금은 배경·헤더·버튼이 모두 달라
 * 서로 다른 앱처럼 보인다. 단계 화면은 전부 이 머리를 쓴다.
 */
@Composable
fun StepHeader(
    title: String,
    step: Int,
    total: Int,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
) {
    Column(modifier.fillMaxWidth().semantics { contentDescription = "$title, 전체 ${total}단계 중 ${step}단계" }) {
        Text(title, style = MaterialTheme.typography.titleLarge)
        if (subtitle != null) {
            Spacer(Modifier.height(MiconSpace.xxs))
            Text(
                subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(MiconSpace.sm))
        // 마디 막대 — "3 / 8" 이라는 숫자보다 남은 길이가 바로 읽힌다
        Row(
            Modifier.fillMaxWidth().heightIn(min = 6.dp),
            horizontalArrangement = Arrangement.spacedBy(MiconSpace.xxs),
        ) {
            repeat(total) { index ->
                val done = index < step
                Spacer(
                    Modifier
                        .weight(1f)
                        .height(6.dp)
                        .background(
                            color = if (done) {
                                MaterialTheme.colorScheme.primary
                            } else {
                                MaterialTheme.colorScheme.primaryContainer
                            },
                            shape = RoundedCornerShape(50),
                        ),
                )
            }
        }
    }
}
