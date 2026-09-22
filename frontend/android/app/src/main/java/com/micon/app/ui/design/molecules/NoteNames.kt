package com.micon.app.ui.design.molecules

import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 음 이름을 사람 말로 옮긴다.
 *
 * "G2 – E4" · "C Major" · "1옥타브 (Low)" · MIDI 번호는 페르소나(노래방은 가지만 악보는 모르는 사람)가
 * 읽지 못한다. 화면에는 **높낮이 말 + 도레미**만 내보내고 MIDI 번호는 절대 노출하지 않는다.
 *
 * 기존 `ReportNoteNames` · `RangeNoteNames` · `karaokeOctaveName` 세 벌을 이걸로 모은다.
 */
object NoteNames {

    /** 설정 "음 이름 표기". 앱 전체 표기가 따라 바뀐다. 값은 [MiconApplication] 이 SharedPreferences 에서 복원한다 */
    enum class Notation(val label: String) { SOLFEGE("도레미"), LETTERS("CDE") }

    private val _notation = kotlinx.coroutines.flow.MutableStateFlow(Notation.SOLFEGE)
    val notation: kotlinx.coroutines.flow.StateFlow<Notation> = _notation
    fun setNotation(value: Notation) { _notation.value = value }

    /** 설정을 따르는 표기: 도레미면 "중간 도", CDE 면 "C4" */
    fun display(midi: Int): String = if (_notation.value == Notation.LETTERS) scientific(midi) else friendly(midi)
    fun displayRange(lowMidi: Int, highMidi: Int): String =
        if (_notation.value == Notation.LETTERS) scientificRange(lowMidi, highMidi) else rangeText(lowMidi, highMidi)

    private val syllables = arrayOf("도", "도♯", "레", "레♯", "미", "파", "파♯", "솔", "솔♯", "라", "라♯", "시")

    /** 1~10 은 고유어로 읽는 게 자연스럽다. "다섯 음 높아요" */
    private val counts = arrayOf("", "한", "두", "세", "네", "다섯", "여섯", "일곱", "여덟", "아홉", "열")

    /** MIDI 60 = C4 = 가온 도 */
    fun octaveOf(midi: Int): Int = floor(midi / 12.0).toInt() - 1

    fun syllableOf(midi: Int): String = syllables[((midi % 12) + 12) % 12]

    private val letters = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    /** 과학 표기 "C4" · "G♯3". 설정의 음 이름 표기가 CDE 일 때, 그리고 차트 축처럼 짧아야 하는 곳에 쓴다 */
    fun scientific(midi: Int): String = letters[((midi % 12) + 12) % 12] + octaveOf(midi)

    /** "C3 – C5" */
    fun scientificRange(lowMidi: Int, highMidi: Int): String = "${scientific(lowMidi)} – ${scientific(highMidi)}"

    /**
     * 옥타브 → 높낮이 말. 2 아래와 6 위는 양 끝으로 모은다 —
     * 더 잘게 나눠 봐야 사람이 구분하지 못한다.
     */
    fun heightWord(midi: Int): String = when (octaveOf(midi)) {
        in Int.MIN_VALUE..2 -> "아주 낮은"
        3 -> "낮은"
        4 -> "중간"
        5 -> "높은"
        else -> "아주 높은"
    }

    /** "중간 도" · "낮은 라" · "아주 높은 솔" */
    fun friendly(midi: Int): String = "${heightWord(midi)} ${syllableOf(midi)}"

    fun friendly(midi: Double): String = friendly(midi.roundToInt())

    /** "낮은 라 ~ 중간 솔" */
    fun rangeText(lowMidi: Int, highMidi: Int): String = "${friendly(lowMidi)} ~ ${friendly(highMidi)}"

    /**
     * TalkBack 용. `♯` 은 화면에서는 허용하지만 읽어 주기로는 "도보다 반 높은 음" 이 낫다.
     */
    fun spoken(midi: Int): String {
        val raw = syllableOf(midi)
        return if (raw.endsWith("♯")) {
            "${heightWord(midi)} ${raw.dropLast(1)}보다 반 높은 음"
        } else {
            friendly(midi)
        }
    }

    /**
     * 두 음의 거리. "다섯 음 높아요" / "두 음 낮아요" / "같아요"
     * (반음 개수를 그대로 "음" 으로 읽는다 — 화면에서 "반음" 이라는 말을 쓰지 않기로 했다)
     */
    fun distanceText(fromMidi: Int, toMidi: Int): String {
        val diff = toMidi - fromMidi
        if (diff == 0) return "같아요"
        val n = abs(diff)
        val word = if (n <= 10) counts[n] else "$n"
        return if (diff > 0) "$word 음 높아요" else "$word 음 낮아요"
    }

    /** 키 조절. "원래 키" / "한 키 내려" / "두 키 올려" */
    fun keyShiftText(steps: Int): String = when {
        steps == 0 -> "원래 키"
        steps > 0 -> "${counts.getOrElse(steps) { "$steps" }} 키 올려"
        else -> "${counts.getOrElse(-steps) { "${-steps}" }} 키 내려"
    }

    /**
     * 노래방 표기(2옥타브 솔 …). 기준은 [com.micon.app.scoring.KaraokeNames] 하나로 모은다 —
     * 예전에는 여기서 한 옥타브 어긋나게 세고 있었다(C4 를 "3옥타브 도" 로).
     */
    fun karaoke(midi: Int): String = com.micon.app.scoring.KaraokeNames.korean(midi)
}
