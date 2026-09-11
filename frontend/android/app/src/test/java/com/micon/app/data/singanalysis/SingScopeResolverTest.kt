package com.micon.app.data.singanalysis

import com.micon.app.data.PracticeSection
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.SectionMap
import com.micon.app.scoring.SongSections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SingScopeResolverTest {
    private fun note(start: Double, end: Double, midi: Int = 60, index: Int = 0) =
        LiveNote(index = index, start = start, end = end, midi = midi, lyric = null, measure = 0, weight = (end - start).coerceIn(0.1, 1.5))

    /** 전주 12 s · 1절 12–40 s · 간주 40–52 s · 2절 52–90 s(최고음 70–75 s) — SongSectionsTest 와 같은 곡 */
    private fun song(): List<LiveNote> {
        val notes = ArrayList<LiveNote>()
        var t = 12.0
        while (t < 40.0) { notes.add(note(t, t + 0.4, 62, notes.size)); t += 0.6 }
        t = 52.0
        while (t < 90.0) { notes.add(note(t, t + 0.4, if (t in 70.0..75.0) 72 else 63, notes.size)); t += 0.6 }
        return notes
    }

    private fun vocal(seq: Int, startMs: Int, endMs: Int) =
        PracticeSection(sectionId = seq.toLong(), sectionType = "VOCAL", sectionName = "보컬 구간", startMs = startMs, endMs = endMs, sequenceNo = seq, difficultyType = "NONE", difficultyScore = null, description = null)

    private fun interlude(seq: Int, startMs: Int, endMs: Int) =
        PracticeSection(sectionId = seq.toLong(), sectionType = "INTERLUDE", sectionName = "간주", startMs = startMs, endMs = endMs, sequenceNo = seq, difficultyType = "NONE", difficultyScore = null, description = null)

    private fun plans(notes: List<LiveNote>, backend: List<PracticeSection> = emptyList(), duration: Double = 100.0): Map<SingScope, ScopePlan> {
        val derived = if (notes.isEmpty()) SectionMap.EMPTY else SongSections.build(notes, emptyList())
        return SingScopeResolver.resolve(duration, notes.firstOrNull()?.start, notes.lastOrNull()?.end, backend, derived).associateBy { it.scope }
    }

    @Test
    fun `full starts four seconds before the first note and ends two seconds after the last`() {
        val p = plans(song()).getValue(SingScope.FULL)
        assertEquals(8.0, p.startSec, 1e-9)
        assertEquals(PlanSource.INTRO_LEAD, p.source)
        assertTrue(p.enabled)
        // 마지막 음표 끝 ≈ 89.8 + 2 → 91.8 (곡 길이 100 보다 짧으니 그대로)
        assertEquals(song().last().end + 2.0, p.endSec, 1e-9)
    }

    @Test
    fun `full starts at zero when the intro is short or there are no notes`() {
        val short = listOf(note(2.0, 2.5), note(3.0, 30.0))
        assertEquals(0.0, plans(short).getValue(SingScope.FULL).startSec, 1e-9)
        assertEquals(PlanSource.SONG_START, plans(short).getValue(SingScope.FULL).source)
        val none = plans(emptyList(), duration = 200.0).getValue(SingScope.FULL)
        assertEquals(0.0, none.startSec, 1e-9)
        assertEquals(200.0, none.endSec, 1e-9)
    }

    @Test
    fun `second half prefers the backend VOCAL sections`() {
        val backend = listOf(vocal(1, 12_000, 40_000), interlude(2, 40_000, 52_000), vocal(3, 52_000, 90_000))
        val p = plans(song(), backend).getValue(SingScope.SECOND_HALF)
        assertTrue(p.enabled)
        assertEquals(PlanSource.BACKEND_SECTION, p.source)
        assertEquals(52.0 - SingScopeResolver.LEAD_SECONDS, p.startSec, 1e-9)
        assertEquals("2절부터 시작", p.displayLabel)
    }

    @Test
    fun `second half falls back to the derived sections when the backend has none`() {
        val p = plans(song()).getValue(SingScope.SECOND_HALF)
        assertTrue(p.enabled)
        assertEquals(PlanSource.DERIVED_SECTION, p.source)
        assertEquals(52.0 - SingScopeResolver.LEAD_SECONDS, p.startSec, 1e-9)
    }

    @Test
    fun `second half is disabled when the song is a single vocal block`() {
        val backend = listOf(vocal(1, 12_000, 90_000))
        val p = plans(song(), backend).getValue(SingScope.SECOND_HALF)
        assertFalse(p.enabled)
        assertEquals(PlanSource.UNAVAILABLE, p.source)

        val oneBlock = ArrayList<LiveNote>().apply { var t = 5.0; while (t < 60.0) { add(note(t, t + 0.4, 60, size)); t += 0.6 } }
        val derivedOnly = plans(oneBlock).getValue(SingScope.SECOND_HALF)
        assertFalse(derivedOnly.enabled)
    }

    @Test
    fun `second half uses half the duration when there is no section information at all`() {
        val p = plans(emptyList(), duration = 200.0).getValue(SingScope.SECOND_HALF)
        assertTrue(p.enabled)
        assertEquals(PlanSource.HALF_DURATION, p.source)
        assertEquals(100.0, p.startSec, 1e-9)
        assertEquals("절반부터 시작", p.displayLabel)
    }

    @Test
    fun `second half rejects a backend candidate that starts too early and falls through`() {
        val backend = listOf(vocal(1, 0, 4_000), vocal(2, 5_000, 90_000))   // 2번째 VOCAL 이 5 s → 후보 기각
        val p = plans(song(), backend).getValue(SingScope.SECOND_HALF)
        assertEquals(PlanSource.DERIVED_SECTION, p.source)
        assertEquals(52.0 - SingScopeResolver.LEAD_SECONDS, p.startSec, 1e-9)
    }

    @Test
    fun `highlight follows the derived highlight and is disabled without one`() {
        val derived = SongSections.build(song(), emptyList())
        val p = plans(song()).getValue(SingScope.HIGHLIGHT)
        assertTrue(p.enabled)
        assertEquals(PlanSource.HIGHLIGHT, p.source)
        assertEquals(checkNotNull(derived.highlight).start, p.startSec, 1e-9)

        val none = plans(emptyList(), duration = 120.0).getValue(SingScope.HIGHLIGHT)
        assertFalse(none.enabled)
        assertEquals(PlanSource.UNAVAILABLE, none.source)
    }

    @Test
    fun `end is clamped to the song duration and starts stay at least five seconds before it`() {
        val p = plans(song(), duration = 60.0)   // 곡 길이가 음표보다 짧다고 주장하는 경우
        assertEquals(60.0, p.getValue(SingScope.FULL).endSec, 1e-9)
        p.values.filter { it.enabled }.forEach { assertTrue(it.startSec <= it.endSec - 5.0 + 1e-9) }
    }
}
