package com.micon.app.scoring

/**
 * 국내 노래방식 음이름. C4(MIDI 60) = "2옥타브 도". 초보 사용자에게 "C4" 보다 "2옥타브 도" 가 익숙하다.
 * 화면에는 [korean] 을 쓰고, 필요하면 [both] 로 서양식 표기를 괄호에 덧붙인다.
 */
object KaraokeNames {
    private val SYLLABLES = arrayOf("도", "도♯", "레", "레♯", "미", "파", "파♯", "솔", "솔♯", "라", "라♯", "시")

    private fun octave(midi: Int) = midi / 12 - 3
    private fun syllable(midi: Int) = SYLLABLES[((midi % 12) + 12) % 12]

    /** 60 → "2옥타브 도" */
    fun korean(midi: Int): String = "${octave(midi)}옥타브 ${syllable(midi)}"

    /** 60 → "2옥 도" (축 라벨용) */
    fun koreanShort(midi: Int): String = "${octave(midi)}옥 ${syllable(midi)}"

    /** 60 → "2옥타브 도(C4)" */
    fun both(midi: Int): String = "${korean(midi)}(${ScoringMath.midiName(midi)})"
}
