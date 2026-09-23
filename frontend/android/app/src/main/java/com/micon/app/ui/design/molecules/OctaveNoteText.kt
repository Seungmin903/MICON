package com.micon.app.ui.design.molecules

import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.micon.app.scoring.KaraokeNames

/**
 * 앱 전체 음이름 표기 — 노래방식 "2옥타브 도". 기준은 [KaraokeNames] (C4 = MIDI 60 = 2옥타브 도).
 *
 * "2옥타브" 부분은 음절("도")의 [OCTAVE_SCALE] 배 크기로 작게, 음절은 원 크기로 그린다.
 * 설정의 음 이름 표기가 CDE 면 과학 표기("C4") 한 덩이로 떨어뜨린다.
 *
 * - 일반 텍스트 자리(제목·라벨)에는 [OctaveNoteText] / [OctaveRangeText] 컴포저블을 쓴다.
 * - 이미 Text 가 있는 자리(축 라벨, KeyValue 등)에는 [karaokeNoteText] / [karaokeRangeText] 로
 *   AnnotatedString 을 받아 그 Text 에 넣는다.
 * - Canvas 에 직접 그릴 때는 [KaraokeNames.koreanShort] 를 [karaokeParts] 로 쪼개 두 크기로 그린다 (Runway 참고).
 */
const val OCTAVE_SCALE = 0.6f

/** "2옥타브 도" → ("2옥타브", "도"). CDE 설정이면 ("", "C4") */
fun karaokeParts(midi: Int, short: Boolean = false): Pair<String, String> {
    if (NoteNames.notation.value == NoteNames.Notation.LETTERS) return "" to NoteNames.scientific(midi)
    val full = if (short) KaraokeNames.koreanShort(midi) else KaraokeNames.korean(midi)
    val idx = full.lastIndexOf(' ')
    return if (idx < 0) "" to full else full.substring(0, idx) to full.substring(idx + 1)
}

/**
 * "2옥타브 도" AnnotatedString. [size] 는 음절 크기, 옥타브 부분은 [size] × [OCTAVE_SCALE].
 * [weight] 는 음절, [octaveWeight] 는 옥타브 글자 굵기. [fontFamily] 를 주면 두 조각 모두 그 폰트.
 */
fun karaokeNoteText(
    midi: Int,
    size: TextUnit,
    weight: FontWeight = FontWeight.Black,
    octaveWeight: FontWeight = FontWeight.Medium,
    fontFamily: FontFamily? = null,
    short: Boolean = false,
): AnnotatedString = buildAnnotatedString { appendKaraoke(midi, size, weight, octaveWeight, fontFamily, short) }

/** "1옥타브 라 ~ 2옥타브 솔" */
fun karaokeRangeText(
    lowMidi: Int,
    highMidi: Int,
    size: TextUnit,
    weight: FontWeight = FontWeight.Black,
    octaveWeight: FontWeight = FontWeight.Medium,
    fontFamily: FontFamily? = null,
    separator: String = " ~ ",
    short: Boolean = false,
): AnnotatedString = buildAnnotatedString {
    appendKaraoke(lowMidi, size, weight, octaveWeight, fontFamily, short)
    withStyle(SpanStyle(fontSize = size * OCTAVE_SCALE, fontWeight = octaveWeight, fontFamily = fontFamily)) { append(separator) }
    appendKaraoke(highMidi, size, weight, octaveWeight, fontFamily, short)
}

private fun androidx.compose.ui.text.AnnotatedString.Builder.appendKaraoke(
    midi: Int, size: TextUnit, weight: FontWeight, octaveWeight: FontWeight, fontFamily: FontFamily?, short: Boolean,
) {
    val (octave, syllable) = karaokeParts(midi, short)
    if (octave.isNotEmpty()) {
        withStyle(SpanStyle(fontSize = size * OCTAVE_SCALE, fontWeight = octaveWeight, fontFamily = fontFamily)) { append(octave) }
    }
    withStyle(SpanStyle(fontSize = size, fontWeight = weight, fontFamily = fontFamily)) { append(syllable) }
}

/** 한 음. "2옥타브 도" — 옥타브 부분 작게, 음절 크게. */
@Composable
fun OctaveNoteText(
    midi: Int,
    modifier: Modifier = Modifier,
    size: TextUnit = 22.sp,
    color: Color = Color.Unspecified,
    weight: FontWeight = FontWeight.Black,
    octaveWeight: FontWeight = FontWeight.Medium,
    fontFamily: FontFamily? = null,
    textAlign: TextAlign? = null,
) {
    // 설정이 바뀌면 다시 그리도록 구독한다
    val notation by NoteNames.notation.collectAsStateWithLifecycle()
    val spoken = if (notation == NoteNames.Notation.LETTERS) NoteNames.scientific(midi) else KaraokeNames.korean(midi)
    Text(
        karaokeNoteText(midi, size, weight, octaveWeight, fontFamily),
        modifier = modifier.semantics { contentDescription = spoken },
        lineHeight = size * 1.25f,
        color = color,
        textAlign = textAlign,
    )
}

/** 음역 범위. "1옥타브 라 ~ 2옥타브 솔" — 구분자는 옥타브 글자와 같은 작은 크기. */
@Composable
fun OctaveRangeText(
    lowMidi: Int,
    highMidi: Int,
    modifier: Modifier = Modifier,
    size: TextUnit = 22.sp,
    color: Color = Color.Unspecified,
    weight: FontWeight = FontWeight.Black,
    octaveWeight: FontWeight = FontWeight.Medium,
    fontFamily: FontFamily? = null,
    separator: String = " ~ ",
    textAlign: TextAlign? = null,
) {
    val notation by NoteNames.notation.collectAsStateWithLifecycle()
    val spoken = if (notation == NoteNames.Notation.LETTERS) NoteNames.scientificRange(lowMidi, highMidi)
    else "${KaraokeNames.korean(lowMidi)}부터 ${KaraokeNames.korean(highMidi)}까지"
    Text(
        karaokeRangeText(lowMidi, highMidi, size, weight, octaveWeight, fontFamily, separator),
        modifier = modifier.semantics { contentDescription = spoken },
        lineHeight = size * 1.25f,
        color = color,
        textAlign = textAlign,
    )
}
