package com.micon.app.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.scoring.LyricSheet
import com.micon.app.scoring.LyricTiming
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple

/**
 * 재생 중 가사: 현재 줄(크게, 글자 채움) + 다음 줄(작게). 지난 줄은 보이지 않는다.
 * 연습(노래 연습중)과 불러서 분석하기가 함께 쓴다.
 *
 * @param judgementColors true 면 판정된 음절을 판정색(정확·높게·낮게…)으로, false 면 부른 글자를 보라로만 칠한다(채점 없는 화면).
 */
@Composable
internal fun KaraokePanel(
    sheet: LyricSheet,
    nowSec: Double,
    modifier: Modifier = Modifier,
    judgementColors: Boolean = true,
    compactPracticeStyle: Boolean = false,
    emptyMessage: String = "이 곡은 가사 데이터가 없어요. 음표 레인을 보며 불러 보세요.",
) {
    val lines = sheet.lines
    if (lines.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(emptyMessage, fontSize = 14.sp, color = Grey, textAlign = TextAlign.Center, modifier = Modifier.padding(32.dp))
        }
        return
    }
    val currentIndex = sheet.currentIndex(nowSec)
    val previous = currentIndex?.let { lines.getOrNull(it - 1) }
    val current = currentIndex?.let { lines.getOrNull(it) }
    val next = lines.getOrNull((currentIndex ?: -1) + 1)
    Column(
        modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compactPracticeStyle) 4.dp else 12.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        if (compactPracticeStyle && previous != null) {
            Text(previous.text, fontSize = 16.sp, lineHeight = 22.sp, color = Grey, textAlign = TextAlign.Center, maxLines = 2)
            Spacer(Modifier.height(7.dp))
        }
        if (current != null) {
            Text(
                buildAnnotatedString {
                    for (ch in current.chars) {
                        val sung = nowSec >= ch.at
                        val note = ch.note
                        val color = when {
                            judgementColors && note != null && note.status.judged -> Color(note.status.toPitchStatus()!!.argb)
                            sung -> Purple
                            else -> Ink
                        }
                        withStyle(SpanStyle(color = color, fontWeight = if (sung) FontWeight.Bold else FontWeight.Medium)) { append(ch.text) }
                    }
                },
                fontSize = if (compactPracticeStyle) 22.sp else 28.sp,
                lineHeight = if (compactPracticeStyle) 30.sp else 38.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
                    .background(if (compactPracticeStyle) Purple.copy(alpha = 0.18f) else Lavender, RoundedCornerShape(14.dp))
                    .padding(horizontal = 12.dp, vertical = if (compactPracticeStyle) 9.dp else 12.dp),
            )
        } else {
            Text("♪", fontSize = 28.sp, color = Grey)
        }
        Spacer(Modifier.height(if (compactPracticeStyle) 7.dp else 12.dp))
        if (next != null) {
            Text(
                next.text,
                fontSize = if (compactPracticeStyle) 17.sp else 20.sp,
                lineHeight = if (compactPracticeStyle) 24.sp else 28.sp,
                color = Grey,
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
        } else if (current != null) {
            Text("마지막 줄이에요", fontSize = 14.sp, color = Grey)
        }
        if (sheet.timing == LyricTiming.APPROX) {
            Spacer(Modifier.height(8.dp))
            Text("가사 위치는 원곡 음표로 추정한 값이에요", fontSize = 11.sp, color = Grey)
        }
    }
}
