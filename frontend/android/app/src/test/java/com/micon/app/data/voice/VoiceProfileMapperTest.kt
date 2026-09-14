package com.micon.app.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceProfileMapperTest {
    private fun analysis(completedAt: String? = null) =
        VoiceAnalysis(9, 57, 42, "SONG", VoiceAnalysisStatus.COMPLETED, "VALID", null, null, "2026-09-13T21:00:00", completedAt)

    private fun range(low: Double?, high: Double?, stableLow: Double? = null, stableHigh: Double? = null, measuredAt: String? = null) =
        VocalRange(low, high, stableLow, stableHigh, null, null, null, null, measuredAt)

    @Test fun `maps full and stable range and derives stability curve`() {
        val profile = checkNotNull(VoiceProfileMapper.toProfile(VoiceAnalysisReport(analysis(), range(48.0, 72.0, 55.0, 67.0, "2026-09-13T21:05:00"), null), now = 1L))
        assertEquals(48, profile.lowMidi); assertEquals(72, profile.highMidi)
        assertEquals(55, profile.comfortLow); assertEquals(67, profile.comfortHigh)
        assertEquals(55, profile.recommendLow); assertEquals(67, profile.recommendHigh)
        assertEquals(8, profile.stability.size); assertEquals(8, profile.stabilityLabels.size)
        assertEquals("C3", profile.stabilityLabels.first()); assertEquals("C5", profile.stabilityLabels.last())
        assertEquals(0f, profile.stability.first()); assertEquals(0f, profile.stability.last())
        assertTrue(profile.stability.any { it == 1f })
        assertTrue(profile.measuredAt > 1L)                 // 서버 시각을 썼다
    }

    @Test fun `stable range defaults to full range and is clamped inside it`() {
        val p1 = checkNotNull(VoiceProfileMapper.toProfile(VoiceAnalysisReport(analysis(), range(50.0, 70.0), null), now = 5L))
        assertEquals(50, p1.comfortLow); assertEquals(70, p1.comfortHigh)
        assertEquals(5L, p1.measuredAt)                     // 시각이 없으면 now
        val p2 = checkNotNull(VoiceProfileMapper.toProfile(VoiceAnalysisReport(analysis(), range(50.0, 70.0, 40.0, 90.0), null)))
        assertEquals(50, p2.comfortLow); assertEquals(70, p2.comfortHigh)
    }

    @Test fun `missing or inverted range yields null`() {
        assertNull(VoiceProfileMapper.toProfile(VoiceAnalysisReport(analysis(), null, null)))
        assertNull(VoiceProfileMapper.toProfile(VoiceAnalysisReport(analysis(), range(null, 70.0), null)))
        assertNull(VoiceProfileMapper.toProfile(VoiceAnalysisReport(analysis(), range(70.0, 70.0), null)))
    }

    @Test fun `stability is 1 inside comfort range and fades to 0 at the edges`() {
        assertEquals(1f, VoiceProfileMapper.stabilityAt(60, 48, 72, 55, 67))
        assertEquals(0f, VoiceProfileMapper.stabilityAt(48, 48, 72, 55, 67))
        assertEquals(3f / 7f, VoiceProfileMapper.stabilityAt(51, 48, 72, 55, 67), 0.001f)   // (51-48)/(55-48)
        assertEquals(0f, VoiceProfileMapper.stabilityAt(72, 48, 72, 55, 67))
        assertEquals(0f, VoiceProfileMapper.stabilityAt(40, 48, 72, 48, 67))            // comfortLow == low: 아래쪽은 바로 0
    }

    @Test fun `server time parses ISO local date time`() {
        assertNotNull(VoiceProfileMapper.parseServerTime("2026-09-13T21:05:00"))
        assertNull(VoiceProfileMapper.parseServerTime("not-a-time"))
        assertNull(VoiceProfileMapper.parseServerTime(null))
    }
}
