package com.micon.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** LRC 줄 텍스트가 원본이고, 음표에 붙지 못한 글자도 화면에서 사라지지 않아야 한다. */
class LyricLinesTest {
    private fun note(start: Double, end: Double, lyric: String?, index: Int = 0) =
        LiveNote(index = index, start = start, end = end, midi = 60, lyric = lyric, measure = 0, weight = 0.5, lyricStart = start, lyricEnd = end)

    private val lrc = """
        [00:10.00] 그리움이 여기서
        [00:14.00] 우리 노래를
        [00:20.00]
        [00:30.00] 다시 만나요
    """.trimIndent()

    @Test
    fun `lines come from the LRC text even when syllables are missing on notes`() {
        // 첫 줄 7글자 중 "그", "움", "여" 만 음표에 붙었다 (파이프라인이 나머지를 버린 상황)
        val notes = listOf(
            note(10.2, 10.6, "그", 0), note(10.9, 11.3, "움", 1), note(11.8, 12.2, "여", 2), note(12.4, 13.0, null, 3),
            note(14.1, 14.5, "우", 4), note(14.6, 15.0, "리", 5), note(15.3, 15.8, "노", 6),
            note(30.0, 30.5, "다", 7), note(30.6, 31.0, "시", 8), note(31.2, 31.8, "만", 9), note(31.9, 32.3, "요", 10),
        )
        val sheet = LyricLines.build(notes, lrc, null)
        assertEquals(LyricTiming.SYNCED, sheet.timing)
        assertEquals(3, sheet.lines.size)
        assertEquals("그리움이 여기서", sheet.lines[0].text)          // 잘리지 않음
        assertEquals("우리 노래를", sheet.lines[1].text)
        assertEquals(listOf(20.0), sheet.breaks)                       // 빈 줄 = 간주 표시
        val first = sheet.lines[0]
        assertEquals(8, first.chars.size)
        assertEquals(10.2, first.chars[0].at, 1e-9)                     // "그" ← 음표
        assertTrue(first.chars[1].at > 10.2 && first.chars[1].at < 10.9) // "리" 는 그/움 사이 보간
        assertEquals(10.9, first.chars[2].at, 1e-9)                     // "움"
        assertNull(first.chars[1].note)
        assertEquals(notes[0], first.chars[0].note)
        assertEquals(first.chars[5].at, first.chars[4].at, 1e-9)        // 공백은 다음 글자 시각
        assertTrue(first.chars.zipWithNext().all { (a, b) -> a.at <= b.at + 1e-9 })
        assertEquals(14.0, first.until, 1e-9)                           // 다음 줄 시작까지 현재 줄
        assertEquals(3, first.notes.size)
        // 두 번째 줄: 마지막 음표 끝이 줄 끝
        assertEquals(15.8, sheet.lines[1].end, 1e-9)
        // 현재 줄 판정: 간주(20~30) 동안은 직전 줄, 시작 전은 null
        assertEquals(1, sheet.currentIndex(25.0))
        assertEquals(2, sheet.currentIndex(30.1))
        assertNull(sheet.currentIndex(5.0))
    }

    @Test
    fun `a line with no matching notes is still timed by its length`() {
        val notes = listOf(note(10.2, 10.6, "그", 0), note(30.0, 30.5, "다", 1))
        val sheet = LyricLines.build(notes, lrc, null)
        val second = sheet.lines[1]                                      // "우리 노래를" — 음표 없음
        assertEquals(14.0, second.start, 1e-9)
        assertTrue(second.end > 14.0 && second.end <= 20.0)
        assertTrue(second.chars.all { !it.at.isNaN() })
        assertTrue(second.chars.first().at >= 14.0 && second.chars.last().at <= second.end + 0.3)
    }

    @Test
    fun `latin words attach whole words and fill the rest`() {
        val synced = "[00:01.00] Throw your elbows up\n[00:05.00] Louder"
        val notes = listOf(note(1.0, 1.3, "Throw", 0), note(1.9, 2.3, "elbows", 1), note(5.0, 5.4, "Louder", 2))
        val sheet = LyricLines.build(notes, synced, null)
        val line = sheet.lines[0]
        assertEquals("Throw your elbows up", line.text)
        assertEquals(1.0, line.chars[0].at, 1e-9)
        assertEquals(1.9, line.chars[11].at, 1e-9)                      // 'e' of elbows
        assertTrue(line.chars[6].at > 1.0 && line.chars[6].at < 1.9)    // "your" 보간
        assertTrue(line.chars.last().at >= 1.9)
    }

    @Test
    fun `plain lyrics without LRC are spread over the singing segments`() {
        val notes = (0 until 20).map { i -> note(10.0 + i * 0.5, 10.4 + i * 0.5, null, i) } +      // 10~20 s
            (0 until 20).map { i -> note(40.0 + i * 0.5, 40.4 + i * 0.5, null, 20 + i) }        // 40~50 s (간주 20 s)
        val plain = "첫째 줄 가사\n둘째 줄 가사\n\n셋째 줄 가사\n넷째 줄 가사"
        val sheet = LyricLines.build(notes, null, plain)
        assertEquals(LyricTiming.APPROX, sheet.timing)
        assertEquals(4, sheet.lines.size)
        // 문단 2개 = 노래 구간 2개 → 문단별 배치
        assertTrue(sheet.lines[0].start >= 10.0 && sheet.lines[1].end <= 20.4)
        assertTrue(sheet.lines[2].start >= 40.0 && sheet.lines[3].end <= 50.4)
        assertTrue(sheet.lines.zipWithNext().all { (a, b) -> a.start < b.start && a.until <= b.start + 1e-9 })
        assertTrue(sheet.lines.all { l -> l.chars.all { !it.at.isNaN() } })
    }

    @Test
    fun `no lyrics at all gives an empty sheet`() {
        val sheet = LyricLines.build(listOf(note(1.0, 1.5, null)), null, "  \n ")
        assertEquals(LyricTiming.NONE, sheet.timing)
        assertTrue(sheet.lines.isEmpty())
    }
}
