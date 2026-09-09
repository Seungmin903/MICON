package com.micon.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DisplayBarsTest {
    private fun note(start: Double, end: Double, midi: Int, lyric: String? = null, index: Int = 0) =
        LiveNote(index = index, start = start, end = end, midi = midi, lyric = lyric, measure = 0, weight = 0.5, lyricStart = start, lyricEnd = end)

    @Test
    fun `vibrato fragments of one syllable become one bar`() {
        // "사" 를 0.9 s 끌면서 비브라토로 3조각, 마지막에 1반음 꺾는 짧은 장식음
        val notes = listOf(
            note(1.0, 1.3, 64, "사", 0), note(1.32, 1.6, 64, null, 1), note(1.62, 1.9, 64, null, 2), note(1.9, 2.0, 63, null, 3),
            note(2.4, 2.9, 67, "랑", 4),
        )
        val line = LyricLine("사랑", 1.0, 2.9, 5.0, emptyList(), notes)
        val bars = DisplayBars.build(notes, listOf(line))
        assertEquals(2, bars.size)
        assertEquals(1.0, bars[0].start, 1e-9)
        assertEquals(2.0, bars[0].end, 1e-9)
        assertEquals(64, bars[0].midi)
        assertEquals(4, bars[0].notes.size)
        assertEquals(67, bars[1].midi)
    }

    @Test
    fun `notes outside lyric lines are not drawn and lone short ornaments are dropped`() {
        val notes = listOf(
            note(0.5, 0.8, 60, null, 0),            // 전주에서 잡힌 허밍 → 가사 구간 밖
            note(10.0, 10.5, 62, "가", 1), note(11.0, 11.08, 70, null, 2), note(11.5, 12.0, 64, "나", 3),
            note(40.0, 41.0, 60, null, 4),          // 아웃트로 코러스
        )
        val line = LyricLine("가나", 10.0, 12.0, 20.0, emptyList(), notes)
        val bars = DisplayBars.build(notes, listOf(line))
        assertEquals(listOf(62, 64), bars.map { it.midi })
    }

    @Test
    fun `bar colour follows the majority of judged notes`() {
        val a = note(1.0, 1.3, 64, "사", 0).apply { status = LiveStatus.GOOD }
        val b = note(1.32, 1.6, 64, null, 1).apply { status = LiveStatus.FLAT }
        val c = note(1.62, 1.9, 64, null, 2).apply { status = LiveStatus.UPCOMING }
        val bar = DisplayBar(1.0, 1.9, 64, listOf(a, b, c))
        assertEquals(LiveStatus.GOOD, bar.statusOf())           // good 1 / judged 2 → 절반 이상
        c.status = LiveStatus.ACTIVE
        assertEquals(LiveStatus.ACTIVE, bar.statusOf())
        assertTrue(DisplayBar(0.0, 1.0, 60, listOf(note(0.0, 1.0, 60))).statusOf() == LiveStatus.UPCOMING)
    }
}
