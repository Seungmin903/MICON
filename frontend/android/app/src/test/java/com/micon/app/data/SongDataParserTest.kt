package com.micon.app.data

import com.micon.app.data.backend.dto.toLiveNotes
import com.micon.app.scoring.LyricLines
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 곡 데이터(karaokeNotes·beatGrid) 파싱과 라이브 음표 변환을 실제 픽스처로 고정한다. 백엔드 practice-assets 가 같은 형식을 준다. */
class SongDataParserTest {
    @Test
    fun `karaoke notes and beat grid parse from the tj-14260 fixture`() {
        val result = checkNotNull(SongDataFixture.load("tj-14260.job.json").result)
        assertEquals("4/4", result.timeSignature)
        val notes = checkNotNull(result.karaokeNotes)
        assertEquals(1152, notes.size)
        val note = notes[10]
        assertEquals("은", note.lyric)
        assertEquals(57, note.midi)
        assertEquals("low-confidence", note.lyricStatus)
        assertEquals(16.35, note.sourceStart, 1e-9)
        assertEquals(9.5, note.scoreStartBeat!!, 1e-9)
        assertEquals(844, result.beatGrid!!.beatTimes.size)
        assertEquals("왜 이리도", result.lyrics!!.text!!.substring(0, 5))
    }

    @Test
    fun `live notes are built from karaoke notes sorted by start`() {
        val result = checkNotNull(SongDataFixture.load("tj-14260.job.json").result)
        val notes = checkNotNull(result.karaokeNotes).toLiveNotes(result.timeSignature)
        assertEquals(1152, notes.size)
        assertTrue(notes.zipWithNext().all { (a, b) -> a.start <= b.start })
        assertTrue(notes.all { it.weight in 0.1..1.5 })
        assertEquals(2, notes[10].measure)       // scoreStartBeat 9.5 / 4 → 2
        val lines = LyricLines.build(notes, result.lyrics?.text)
        assertTrue(lines.size > 20)
        assertTrue(lines.all { it.chars.isNotEmpty() && it.start <= it.end })
        assertTrue(lines.first().text.startsWith("왜"))
    }

    @Test
    fun `smaller fixtures parse too`() {
        for (name in listOf("tj-01481.job.json", "tj-93799.job.json")) {
            val result = checkNotNull(SongDataFixture.load(name).result)
            assertNotNull(name, result.timeSignature)
            val notes = checkNotNull(result.karaokeNotes)
            assertTrue(name, notes.isNotEmpty())
            assertTrue(name, notes.toLiveNotes(result.timeSignature).isNotEmpty())
        }
    }
}
