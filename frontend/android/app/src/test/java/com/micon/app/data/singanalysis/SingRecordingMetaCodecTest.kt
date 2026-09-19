package com.micon.app.data.singanalysis

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SingRecordingMetaCodecTest {
    private fun sample() = SingRecordingMeta(
        id = "sing-20260911-101500-42", songId = "42", title = "밤편지", artist = "아이유",
        scope = SingScope.HIGHLIGHT.name, planSource = PlanSource.HIGHLIGHT.name,
        plannedStartSec = 100.5, plannedEndSec = 240.0, sungStartSec = 100.5, sungEndSec = 172.3, endedEarly = true,
        recordedAt = 1_789_000_000_000L, sampleRate = 16_000, durationSec = 80.25,
        takes = listOf(SingTake(2.1, 40.0, 100.5, 138.4), SingTake(45.0, 78.9, 138.4, 172.3)),
        lyricTiming = "SYNCED",
        lyricLines = listOf(SingLyricLine("이 밤 그날의 반딧불을", 101.0, 105.5), SingLyricLine("당신의 창 가까이 보낼게요", 106.0, 110.0), SingLyricLine("음", 111.0, 112.0)),
        wavFileName = "sing-20260911-101500-42.wav", dataVersion = null,
    )

    @Test
    fun `round trip keeps every field`() {
        val meta = sample()
        val json = SingRecordingMetaCodec.toJson(meta)
        assertTrue(json.contains("\"takes\""))
        assertEquals(meta, SingRecordingMetaCodec.fromJson(json))
    }

    @Test
    fun `unknown fields are ignored and missing lists default to empty`() {
        val json = """
            {"id":"x","songId":1,"title":"t","artist":"a","scope":"FULL","planSource":"SONG_START",
             "plannedStartSec":0.0,"plannedEndSec":10.0,"sungStartSec":0.0,"sungEndSec":9.0,"endedEarly":false,
             "recordedAt":1,"sampleRate":48000,"durationSec":9.5,"lyricTiming":"NONE","wavFileName":"x.wav",
             "futureField":"ignored"}
        """.trimIndent()
        val meta = SingRecordingMetaCodec.fromJson(json)
        assertNotNull(meta)
        assertEquals(emptyList<SingTake>(), meta!!.takes)
        assertEquals(emptyList<SingLyricLine>(), meta.lyricLines)
        assertEquals(1, meta.schemaVersion)
        assertEquals(1, meta.channels)
        assertEquals("1", meta.songId) // Previously saved numeric song IDs remain readable.
        assertEquals(SingScope.FULL, meta.scopeEnum)
    }

    @Test
    fun `broken json yields null instead of throwing`() {
        assertNull(SingRecordingMetaCodec.fromJson("{not json"))
        assertNull(SingRecordingMetaCodec.fromJson("""{"id":"only"}"""))
    }

    @Test
    fun `unknown scope name falls back to FULL`() {
        val meta = sample().copy(scope = "SOMETHING_NEW")
        assertEquals(SingScope.FULL, meta.scopeEnum)
    }

    @Test fun `uuid and existing server ids survive the recording metadata round trip`() {
        val meta = sample().copy(songId = "d221afea-6dc1-5223-a6bc-68fa055b4820",
            serverRecordingId = 57L, serverAnalysisId = 71L)
        assertEquals(meta, SingRecordingMetaCodec.fromJson(SingRecordingMetaCodec.toJson(meta)))
    }
}
