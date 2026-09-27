package com.micon.app.ui.holo

import com.micon.app.range.VoiceQualityPoint
import com.micon.app.range.VoiceQualityResult
import org.junit.Assert.assertEquals
import org.junit.Test

class VoiceResultGraphPointsTest {
    @Test fun shortUnheldInteriorNoteDoesNotCreateDip() {
        val quality = VoiceQualityResult("personal-acoustic-v1", 61, listOf(
            VoiceQualityPoint(60, 0.0, 0.0, 0.0, 0.0),
            VoiceQualityPoint(61, 85.0, 0.0, 0.0, 70.0),
            VoiceQualityPoint(62, 0.0, 0.0, 0.0, 0.0),
            VoiceQualityPoint(63, 0.0, 1.0, 1.0, 50.0),
            VoiceQualityPoint(64, 0.0, 0.0, 0.0, 0.0),
        ))

        assertEquals(
            listOf(60 to 0f, 61 to .85f, 63 to 0f, 64 to 0f),
            visibleStabilityPoints(listOf(60, 61, 62, 63, 64), listOf(0f, .85f, 0f, 0f, 0f), quality),
        )
        assertEquals(5, quality.points.size) // 그래프에서만 숨기고 원본 측정값은 유지한다.
    }

    @Test fun legacyGraphWithoutVoiceQualityKeepsItsPoints() {
        val midis = listOf(60, 61, 62)
        val scores = listOf(.7f, 0f, .8f)
        assertEquals(midis.zip(scores), visibleStabilityPoints(midis, scores, null))
    }
}
