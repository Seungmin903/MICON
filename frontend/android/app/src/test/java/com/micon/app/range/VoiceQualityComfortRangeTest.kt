package com.micon.app.range

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VoiceQualityComfortRangeTest {
    private fun quality(vararg scores: Pair<Int, Double>) = VoiceQualityResult(
        LocalVoiceQualityAnalyzer.VERSION, 60,
        scores.map { (midi, score) -> VoiceQualityPoint(midi, score, 0.0, 0.0, 100.0) })

    @Test fun `lowest and highest measured notes at least 80 bound one range`() {
        val result = quality(56 to 90.0, 57 to 80.0, 58 to 0.0, 59 to 95.0,
            60 to 100.0, 62 to 91.0, 63 to 89.0, 64 to 85.0)
        assertEquals(56..64, VoiceQualityComfortRange.find(result, 56, 64))
        assertEquals(56..60, VoiceQualityComfortRange.find(result, 56, 61))
    }

    @Test fun `missing middle note stays unscored but lies within the displayed range`() {
        assertEquals(58..60, VoiceQualityComfortRange.find(quality(58 to 82.0, 60 to 98.0), 58, 60))
        assertNull(VoiceQualityComfortRange.find(quality(58 to 0.0, 60 to 79.9), 58, 60))
    }
}
