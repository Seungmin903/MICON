package com.micon.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SongSectionsTest {
    private fun note(start: Double, end: Double, midi: Int = 60, index: Int = 0) =
        LiveNote(index = index, start = start, end = end, midi = midi, lyric = null, measure = 0, weight = (end - start).coerceIn(0.1, 1.5))

    /** 전주 12 s · 1절 12–40 s · 간주 40–52 s · 2절 52–90 s(최고음 70–75 s) */
    private fun song(): List<LiveNote> {
        val notes = ArrayList<LiveNote>()
        var t = 12.0
        while (t < 40.0) { notes.add(note(t, t + 0.4, 62, notes.size)); t += 0.6 }
        t = 52.0
        while (t < 90.0) { notes.add(note(t, t + 0.4, if (t in 70.0..75.0) 72 else 63, notes.size)); t += 0.6 }
        return notes
    }

    @Test
    fun `interludes, sections and highlight are derived from note gaps`() {
        val map = SongSections.build(song(), emptyList())
        assertEquals(2, map.interludes.size)
        assertEquals(0.0, map.interludes[0].start, 1e-9)               // 전주
        assertEquals(12.0, map.interludes[0].end, 1e-9)
        assertEquals(52.0, map.interludes[1].end, 1e-9)                // 간주 끝 = 2절 첫 음표
        assertEquals(listOf("1절", "2절"), map.sections.map { it.label })
        assertEquals(12.0, map.sections[0].start, 1e-9)
        assertEquals(52.0, map.sections[1].start, 1e-9)
        assertNotNull(map.interludeAt(45.0))
        assertNull(map.interludeAt(30.0))
        assertEquals(0, map.sectionIndexAt(0.0))
        assertEquals(0, map.sectionIndexAt(30.0))
        assertEquals(1, map.sectionIndexAt(51.0))                      // 시작 1.5 s 앞부터 다음 구간
        val h = checkNotNull(map.highlight)
        assertEquals(72, h.midi)
        // 구절 정보 없음 → 최고음 1 s 앞이 구절 시작, 그보다 한 마디(2.5 s) 앞에서 출발
        assertTrue("highlight start ${h.start}", h.start >= 65.5 && h.start <= 67.5)
        assertTrue(h.end <= 90.4)
    }

    @Test
    fun `short vocal blobs inside an interlude merge into the previous section`() {
        val notes = song() + listOf(note(45.0, 45.4, 60, 999))         // 간주 중 한 음(애드리브)
        val map = SongSections.build(notes.sortedBy { it.start }, emptyList())
        assertEquals(2, map.sections.size)
        assertEquals(2, map.interludes.size)
        assertTrue(map.interludes[1].start >= 45.4 - 1e-9)             // 애드리브까지 1절에 포함
    }

    @Test
    fun `highlight snaps to the lyric line that contains it`() {
        val notes = song()
        val line = LyricLine("최고음 줄", 69.5, 75.0, 76.0, emptyList(), emptyList())
        val map = SongSections.build(notes, listOf(line))
        assertEquals(69.5 - SongSections.HIGHLIGHT_LEAD_FALLBACK_SECONDS, checkNotNull(map.highlight).start, 1e-9)
    }

    @Test
    fun `highlight starts one bar before the phrase when bar starts are known`() {
        val notes = song()
        val line = LyricLine("최고음 줄", 69.5, 75.0, 76.0, emptyList(), emptyList())
        val bars = (0 until 50).map { it * 2.0 }                        // 2 s 마디
        val map = SongSections.build(notes, listOf(line), bars)
        assertEquals(66.0, checkNotNull(map.highlight).start, 1e-9)   // 69.5 가 속한 마디 68.0 의 한 마디 앞
    }

    @Test
    fun `single block song has one section and no interlude when the intro is short`() {
        val notes = (0 until 50).map { i -> note(2.0 + i * 0.5, 2.4 + i * 0.5, 60, i) }
        val map = SongSections.build(notes, emptyList())
        assertEquals(1, map.sections.size)
        assertEquals("전체", map.sections[0].label)
        assertTrue(map.interludes.isEmpty())
    }
}
