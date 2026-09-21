package com.micon.app.ui.practice

import com.micon.app.data.InstrumentalMedia
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class KeyedInstrumentalCacheTest {
    private val now = Instant.parse("2026-09-21T00:00:00Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)

    @Test fun `returns url while more than sixty seconds remain`() {
        val cache = KeyedInstrumentalCache(clock)
        cache.put(-2, media("https://s3/key-2", "2026-09-21T00:01:01Z"))
        assertEquals("https://s3/key-2", cache.valid(-2)?.playbackUrl)
    }

    @Test fun `expires url at refresh boundary and tolerates legacy response`() {
        val cache = KeyedInstrumentalCache(clock)
        cache.put(2, media("https://s3/key2", "2026-09-21T00:01:00Z"))
        assertNull(cache.valid(2))
        cache.put(3, media("https://s3/key3", null))
        assertEquals("https://s3/key3", cache.valid(3)?.playbackUrl)
    }

    private fun media(url: String, expiresAt: String?) = InstrumentalMedia(
        mediaId = 1, filePath = "instrumental.opus", mimeType = "audio/ogg", fileBytes = 1,
        sha256 = "abc", playbackUrl = url, playbackUrlExpiresAt = expiresAt,
    )
}
