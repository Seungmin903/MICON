package com.micon.app.ui.common

import org.junit.Assert.assertEquals
import org.junit.Test

class RangeNoteNamesTest {
    @Test fun measuredRangePreservesScientificOctaveNumbers() {
        assertEquals("3옥타브 파 ~ 4옥타브 라", RangeNoteNames.range(53, 69))
        assertEquals("3옥타브 시", RangeNoteNames.full(59))
        assertEquals("4옥타브 도", RangeNoteNames.full(60))
        assertEquals("2옥타브 도", RangeNoteNames.full(36))
        assertEquals("6옥타브 도", RangeNoteNames.full(84))
    }

    @Test fun accidentalsAndMeasuredPitchUseTheSameDisplayConvention() {
        assertEquals("4옥타브 도#", RangeNoteNames.full(61))
        assertEquals("4옥 파#", RangeNoteNames.short(66))
        assertEquals("4옥타브 도", RangeNoteNames.full(60.35))
        assertEquals("4옥타브 도#", RangeNoteNames.full(60.8))
    }
}
