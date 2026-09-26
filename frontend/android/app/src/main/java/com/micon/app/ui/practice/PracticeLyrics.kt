package com.micon.app.ui.practice

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.micon.app.scoring.LyricSheet
import com.micon.app.scoring.LyricTiming
import com.micon.app.ui.theme.Grey
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.Lavender
import com.micon.app.ui.theme.Purple

/** 가사 영역 높이에 맞춘 글자 크기. 좁은 기기(S10 360×760)에서도 두 줄이 잘리지 않게 한다 */
private data class LyricType(
    val current: TextUnit,
    val currentLine: TextUnit,
    val next: TextUnit,
    val nextLine: TextUnit,
)

private fun lyricTypeFor(heightDp: Float, compact: Boolean): LyricType = when {
    !compact -> LyricType(28.sp, 38.sp, 20.sp, 28.sp)
    heightDp < 150f -> LyricType(18.sp, 24.sp, 14.sp, 19.sp)
    heightDp < 210f -> LyricType(21.sp, 28.sp, 16.sp, 22.sp)
    else -> LyricType(24.sp, 32.sp, 18.sp, 25.sp)
}

/**
 * 재생 중 가사: **현재 줄 + 다음 줄, 딱 두 줄만**. 지난 줄은 보여 주지 않는다 —
 * 세 줄을 넣으면 큰 화면이 아닌 기기에서 어김없이 잘렸다.
 * 연습(노래 연습중)과 불러서 분석하기가 함께 쓴다.
 *
 * **줄 단위로만 맞춘다.** 글자별 채움 시각(`LyricChar.at`)은 음표에 매칭된 음절만
 * 실측이고 나머지는 선형 보간이라(`LyricLines.fillChars`) 글자 단위로 칠하면 싱크가 눈에 띄게 어긋났다.
 * 음절 판정 색은 점수판의 판정 라벨과 음표 레인(Runway)이 담당한다.
 */
@Composable
internal fun KaraokePanel(
    sheet: LyricSheet,
    nowSec: Double,
    modifier: Modifier = Modifier,
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
    val current = currentIndex?.let { lines.getOrNull(it) }
    val next = lines.getOrNull((currentIndex ?: -1) + 1)
    BoxWithConstraints(modifier.fillMaxWidth()) {
        val type = lyricTypeFor(maxHeight.value, compactPracticeStyle)
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = if (compactPracticeStyle) 4.dp else 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            if (current != null) {
                Column(
                    Modifier.fillMaxWidth()
                        .background(if (compactPracticeStyle) Purple.copy(alpha = 0.18f) else Lavender, RoundedCornerShape(14.dp))
                        .padding(horizontal = 12.dp, vertical = if (compactPracticeStyle) 9.dp else 12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        current.text,
                        fontSize = type.current,
                        lineHeight = type.currentLine,
                        fontWeight = FontWeight.Bold,
                        color = Ink,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            } else {
                Text("♪", fontSize = type.current, color = Grey)
            }
            Spacer(Modifier.height(if (compactPracticeStyle) 7.dp else 12.dp))
            if (next != null) {
                Text(
                    next.text,
                    fontSize = type.next,
                    lineHeight = type.nextLine,
                    color = Grey,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
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
}
