package com.micon.app.data.holo

import com.micon.app.core.network.Json
import com.micon.app.range.VoiceQualityPoint
import com.micon.app.range.VoiceQualityResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HoloResultQualityTest {
    private fun result() = HoloResult(measuredAt = 1L, lowMidi = 50, highMidi = 70,
        comfortLow = 55, comfortHigh = 65, recommendLow = 56, recommendHigh = 64,
        stability = listOf(.9f, .8f), stabilityMidis = listOf(55, 60),
        phraseSec = 4.0, bpmLow = 100, bpmHigh = 120, notes = emptyList(), voicedSec = 10.0)

    @Test fun measuredRangeUsesOnlyMeasuredAcousticPoints() {
        val quality = VoiceQualityResult("personal-acoustic-v1", 57, listOf(
            VoiceQualityPoint(55, 55.0, 0.0, .4, 90.0),
            VoiceQualityPoint(57, 100.0, 0.0, 0.0, 100.0),
            VoiceQualityPoint(60, 65.0, .3, 0.0, 80.0)))
        val updated = result().withMeasuredRange(48, 72, quality)
        assertEquals(listOf(55, 57, 60), updated.stabilityMidis)
        assertEquals(listOf(.55f, 1f, .65f), updated.stability)
        assertEquals(quality, updated.voiceQuality)
        assertEquals(48, updated.lowMidi)
        assertEquals(72, updated.highMidi)
    }

    @Test fun olderRangeKeepsLegacyGraph() {
        val updated = result().withMeasuredRange(48, 72)
        assertEquals(listOf(55, 60), updated.stabilityMidis)
        assertNull(updated.voiceQuality)
    }

    @Test fun localResultJsonRestoresQualityAndAcceptsMissingOptionalField() {
        val quality = VoiceQualityResult("personal-acoustic-v1", 57,
            listOf(VoiceQualityPoint(57, 100.0, 0.0, 0.0, 100.0)))
        val adapter = Json.moshi.adapter(HoloResult::class.java)
        val current = requireNotNull(adapter.fromJson(adapter.toJson(result().copy(voiceQuality = quality))))
        assertEquals(quality, current.voiceQuality)
        val legacy = requireNotNull(adapter.fromJson(adapter.toJson(result())))
        assertNull(legacy.voiceQuality)
    }
}
