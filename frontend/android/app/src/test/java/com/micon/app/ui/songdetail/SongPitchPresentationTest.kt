package com.micon.app.ui.songdetail

import com.micon.app.data.PracticeSection
import com.micon.app.data.backend.dto.KaraokeNote
import org.junit.Assert.*
import org.junit.Test

class SongPitchPresentationTest {
    private val range = MeasuredPitchRange(55, 72)
    private fun pitch(notes: List<KaraokeNote>, key: Int = 0, measured: MeasuredPitchRange? = range) =
        SongPitchPresentation.build(notes, 120.0, measured, key, -2)

    @Test fun shortPeakIsNotAveragedAwayAndTimeIncludesIntro() {
        val notes = listOf(KaraokeNote(20.0, 21.0, 65), KaraokeNote(21.0, 21.08, 74), KaraokeNote(21.08, 22.0, 65))
        val p = pitch(notes)
        assertEquals(3, p.notes.size)
        assertEquals(21.0, p.personalRegions.single().start, .0001)
        assertEquals(21.08, p.personalRegions.single().end, .0001)
        assertEquals(120.0, p.duration, .0001)
        assertTrue(p.personalRegions.single().description.contains("2반음"))
        assertTrue(pitch(notes, -2).personalRegions.isEmpty())
        assertEquals(p.axisHigh, pitch(notes, -2).axisHigh)
    }

    @Test fun loweringKeyCanCreateLowBurdenAndDoesNotDeleteSongDifficulty() {
        val sections = listOf(PracticeSection(9, "VERSE", "1절", 30000, 40000, 1, "LONG_BREATH", null, null))
        val p = SongPitchPresentation.build(listOf(KaraokeNote(31.0, 32.0, 55)), 120.0, range, -2, -2, sections)
        assertEquals(PitchRegionKind.LOW, p.personalRegions.single().kind)
        assertTrue(p.regions.any { it.kind == PitchRegionKind.SONG && it.description.contains("호흡") })
    }

    @Test fun gapsAndHighLowGroupsAreKeptSeparate() {
        val p = pitch(listOf(KaraokeNote(3.0, 4.0, 74), KaraokeNote(4.5, 5.0, 75), KaraokeNote(9.0, 10.0, 76), KaraokeNote(4.0, 4.2, 52)))
        assertEquals(3, p.personalRegions.size)
        assertEquals(5.0, p.personalRegions.first().end, .001)
        assertEquals(PitchRegionKind.LOW, p.personalRegions[1].kind)
    }

    @Test fun missingProfileNeverMeansNoBurdenMeasured() {
        val p = pitch(listOf(KaraokeNote(2.0, 3.0, 90)), measured = null)
        assertNull(p.range)
        assertEquals(1, p.notes.size)
        assertTrue(p.regions.isEmpty())
    }

    @Test fun malformedNotesAndRangeAreRejectedWithoutBreakingEmptyGraphs() {
        val p = pitch(listOf(KaraokeNote(Double.NaN, 1.0, 60), KaraokeNote(-1.0, 2.0, 60),
            KaraokeNote(4.0, 3.0, 60), KaraokeNote(1.0, 2.0, 999)), measured = MeasuredPitchRange(72, 55))
        assertTrue(p.notes.isEmpty())
        assertNull(p.range)
        assertTrue(p.axisHigh > p.axisLow)
    }
}
