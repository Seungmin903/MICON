package com.micon.app.audio

import com.micon.app.data.InstrumentalMedia
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeInstrumentalCacheTest {
    private val hash = "a".repeat(64)

    @Test fun `prefetches only immediate supported neighbors`() {
        assertEquals(listOf(1, -1), PracticeInstrumentalCache.adjacentKeys(0, -3, 3))
        assertEquals(listOf(2), PracticeInstrumentalCache.adjacentKeys(3, -3, 3))
        assertEquals(listOf(-2), PracticeInstrumentalCache.adjacentKeys(-3, -3, 3))
    }

    @Test fun `cache key survives signed url refresh`() {
        assertEquals(
            PracticeInstrumentalCache.cacheKey(media("https://s3/first", hash, 1024)),
            PracticeInstrumentalCache.cacheKey(media("https://s3/renewed", hash, 1024)),
        )
        assertNull(PracticeInstrumentalCache.cacheKey(media("https://s3/bad", "not-a-hash", 1024)))
    }

    @Test fun `prefetch is bounded and needs usable metadata`() {
        assertTrue(PracticeInstrumentalCache.eligibleForPrefetch(media("https://s3/valid", hash, 1024)))
        assertFalse(PracticeInstrumentalCache.eligibleForPrefetch(media(null, hash, 1024)))
        assertFalse(PracticeInstrumentalCache.eligibleForPrefetch(media("https://s3/large", hash, 9L * 1024 * 1024)))
        assertFalse(PracticeInstrumentalCache.eligibleForPrefetch(media("https://s3/unknown", hash, null)))
    }

    private fun media(url: String?, sha: String?, bytes: Long?) = InstrumentalMedia(
        mediaId = 1, filePath = "instrumental.opus", mimeType = "audio/ogg", fileBytes = bytes,
        sha256 = sha, playbackUrl = url,
    )
}
