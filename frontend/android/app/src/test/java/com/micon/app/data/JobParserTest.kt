package com.micon.app.data

import com.micon.app.data.engine.EngineClient
import com.micon.app.data.engine.dto.Job
import com.micon.app.data.engine.dto.toLiveNotes
import com.micon.app.scoring.LyricLines
import com.micon.app.scoring.PitchStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 엔진 workspace 의 실제 job.json 픽스처로 DTO 파싱을 고정한다. */
class JobParserTest {
    private val adapter = EngineClient.moshi.adapter(Job::class.java)

    private fun load(name: String): Job {
        val stream = checkNotNull(javaClass.classLoader!!.getResourceAsStream("fixtures/$name")) { "fixture $name" }
        return checkNotNull(adapter.fromJson(stream.bufferedReader().readText()))
    }

    @Test
    fun `reference job tj-14260 parses song info, register and karaoke notes`() {
        val job = load("tj-14260.job.json")
        assertEquals("tj-14260", job.id)
        assertEquals("reference", job.kind)
        assertTrue(job.isComplete)
        val result = checkNotNull(job.result)
        assertEquals("D major", result.key?.name)
        assertEquals(144, result.tempoBpm)
        assertEquals("4/4", result.timeSignature)
        assertEquals("F3", result.lowest)
        assertEquals("D5", result.highest)
        assertEquals(53, result.lowestMidi)
        assertEquals(74, result.highestMidi)
        assertEquals(13.76, result.vocalStart!!, 1e-6)
        assertEquals(8.0, result.ratios!!.low, 1e-9)
        assertTrue(result.hasInstrumental)

        val register = checkNotNull(result.register)
        assertEquals(0.7885, register.ratios!!["modal"]!!, 1e-6)
        assertEquals(47.1, register.lightnessMean!!, 1e-6)
        assertEquals(listOf(56, 72), register.modalRangeMidi)
        assertEquals(1, register.caveats!!.size)

        assertEquals(1152, result.karaokeNotes!!.size)
        assertEquals(1042, result.notes!!.size)
        val note = result.karaokeNotes!![10]
        assertEquals("은", note.lyric)
        assertEquals(57, note.midi)
        assertEquals("low-confidence", note.lyricStatus)
        assertEquals(16.35, note.sourceStart, 1e-9)
        assertEquals(9.5, note.scoreStartBeat!!, 1e-9)
        assertEquals("왜 이리도", result.lyrics!!.text!!.substring(0, 5))
        assertEquals(844, result.beatGrid!!.beatTimes.size)
    }

    @Test
    fun `live notes are built from karaoke notes sorted by start`() {
        val result = checkNotNull(load("tj-14260.job.json").result)
        val notes = result.toLiveNotes()
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
    fun `smaller reference jobs parse too`() {
        for (name in listOf("tj-01481.job.json", "tj-93799.job.json")) {
            val job = load(name)
            assertTrue(name, job.isComplete)
            val result = checkNotNull(job.result)
            assertNotNull(name, result.key?.name)
            assertTrue(name, result.karaokeNotes!!.isNotEmpty())
            assertTrue(name, result.toLiveNotes().isNotEmpty())
        }
    }

    @Test
    fun `performance job parses comparison scores, notes, measures and issues`() {
        val job = load("perf-sample.job.json")
        assertEquals("performance", job.kind)
        assertEquals("046809ad-5338-466d-b32c-2b66bf8195e2", job.referenceJobId)
        assertEquals(true, job.compareOptions?.allowTranspose)
        val comparison = checkNotNull(job.result?.comparison)
        assertEquals(88.0, comparison.scores.pitch, 1e-9)
        assertEquals(91.0, comparison.scores.timing, 1e-9)
        assertEquals(89.1, comparison.scores.overall, 1e-9)
        assertEquals("A", comparison.scores.grade)
        assertEquals(2, comparison.transposeSemitones)
        assertEquals(513, comparison.coverage!!.notesTotal)
        assertEquals(4, comparison.coverage!!.notesMissed)
        assertEquals(106, comparison.pitchStats!!.good)
        assertEquals(13, comparison.timingStats!!.late)
        assertEquals(513, comparison.notes.size)
        assertEquals(22, comparison.measures.size)
        assertEquals(12, comparison.issues.size)

        val first = comparison.notes.first()
        assertEquals(PitchStatus.GOOD, PitchStatus.fromKey(first.pitchStatus))
        assertEquals("이", first.lyric)
        assertEquals(5, first.centsError)
        assertTrue(first.covered)
        assertNull(PitchStatus.fromKey("weird"))
        assertEquals(100.0, comparison.measures.first().score, 1e-9)
        assertEquals("4음 안 부름", comparison.issues.first().summary)
        assertEquals(1205, comparison.curves!!.ref.size)
        assertEquals(45.0, comparison.thresholds!!.pitchGoodCents!!, 1e-9)
        assertEquals(0.65, comparison.thresholds!!.weights!!["pitch"]!!, 1e-9)
    }
}
