package com.micon.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ScoringMathTest {
    @Test
    fun `fold wraps semitone error into one octave`() {
        assertEquals(0.2, ScoringMath.fold(12.2), 1e-9)
        assertEquals(-0.3, ScoringMath.fold(-12.3), 1e-9)
        assertEquals(5.0, ScoringMath.fold(5.0), 1e-9)
    }

    @Test
    fun `median takes the lower middle for even counts`() {
        assertEquals(0.0, ScoringMath.median(emptyList()), 0.0)
        assertEquals(3.0, ScoringMath.median(listOf(4.0, 1.0, 3.0, 2.0)), 0.0)
        assertEquals(2.0, ScoringMath.median(listOf(3.0, 1.0, 2.0)), 0.0)
    }

    @Test
    fun `grade boundaries match the engine`() {
        assertEquals("S", ScoringMath.gradeOf(95.0))
        assertEquals("A", ScoringMath.gradeOf(94.9))
        assertEquals("B", ScoringMath.gradeOf(78.0))
        assertEquals("C", ScoringMath.gradeOf(65.0))
        assertEquals("D", ScoringMath.gradeOf(50.0))
        assertEquals("F", ScoringMath.gradeOf(49.9))
    }

    @Test
    fun `hz to midi and note names`() {
        assertEquals(69.0, ScoringMath.hzToMidi(440.0)!!, 1e-9)
        assertEquals(57.0, ScoringMath.hzToMidi(220.0)!!, 1e-9)
        assertEquals(null, ScoringMath.hzToMidi(0.0))
        assertEquals("A4", ScoringMath.midiName(69))
        assertEquals("C4", ScoringMath.midiName(60))
        assertEquals("F3", ScoringMath.midiName(53))
        assertEquals("D5", ScoringMath.midiName(74))
    }

    @Test
    fun `formatTime and voiced gate`() {
        assertEquals("1:05", ScoringMath.formatTime(65.4))
        assertEquals("0:00", ScoringMath.formatTime(-3.0))
        assertTrue(ScoringMath.isVoiced(220.0, 0.9, 0.05))
        assertFalse(ScoringMath.isVoiced(220.0, 0.5, 0.05))          // clarity 게이트
        assertFalse(ScoringMath.isVoiced(220.0, 0.9, 0.05, 0.001))   // rmsLow 게이트
        assertFalse(ScoringMath.isVoiced(0.0, 0.9, 0.05))
    }

    @Test
    fun `score color ramp endpoints`() {
        assertEquals(0xFFC94A3D, ScoringUi.scoreColor(0.0))
        assertEquals(0xFFD98B2B, ScoringUi.scoreColor(60.0))
        assertEquals(0xFF3F9D5A, ScoringUi.scoreColor(100.0))
        assertEquals(0xFF3F9D5A, ScoringUi.scoreColor(120.0))
    }
}
