package com.micon.app.ui.design.molecules

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micon.app.scoring.KaraokeNames

/**
 * 노래방 표기 음이름 — "0옥타브 도". 옥타브 숫자와 도레미는 크게, "옥타브" 글자는 작게 써서
 * 음역 측정 화면에서 한눈에 읽히게 한다. 기준은 [KaraokeNames] (C4 = 2옥타브 도).
 *
 * 설정의 음 이름 표기가 CDE 면 과학 표기("C2") 한 덩이로 떨어뜨린다.
 */
@Composable
fun OctaveNoteText(
    midi: Int,
    modifier: Modifier = Modifier,
    size: TextUnit = 22.sp,
    color: Color = Color.Unspecified,
) {
    val notation by NoteNames.notation.collectAsStateWithLifecycle()
    val spoken = KaraokeNames.korean(midi)
    if (notation == NoteNames.Notation.LETTERS) {
        Text(
            NoteNames.scientific(midi),
            modifier = modifier,
            fontSize = size,
            lineHeight = size * 1.2f,
            fontWeight = FontWeight.Black,
            color = color,
        )
        return
    }
    // "0옥타브 도" 를 숫자 / "옥타브" / 음절 세 조각으로 나눈다
    val octave = spoken.substringBefore("옥타브")
    val syllable = spoken.substringAfter("옥타브 ", missingDelimiterValue = "")
    val small = size * 0.55f
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Black)) { append(octave) }
            withStyle(SpanStyle(fontSize = small, fontWeight = FontWeight.Medium)) { append("옥타브") }
            withStyle(SpanStyle(fontSize = size, fontWeight = FontWeight.Black)) { append(" $syllable") }
        },
        modifier = modifier.semantics { contentDescription = spoken },
        lineHeight = size * 1.25f,
        color = color,
    )
}
