package com.micon.app.ui.practice

import com.micon.app.data.InstrumentalMedia
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 만료되는 키별 MR URL 캐시. 만료 60초 전부터 해당 키만 다시 조회한다. */
internal class KeyedInstrumentalCache(
    private val clock: Clock = Clock.systemUTC(),
    private val refreshBefore: Duration = Duration.ofSeconds(60),
) {
    private val entries = mutableMapOf<Int, InstrumentalMedia>()

    @Synchronized
    fun put(key: Int, media: InstrumentalMedia) {
        if (!media.playbackUrl.isNullOrBlank()) entries[key] = media
    }

    @Synchronized
    fun valid(key: Int): InstrumentalMedia? {
        val media = entries[key] ?: return null
        val expiresAt = media.playbackUrlExpiresAt?.let { runCatching { Instant.parse(it) }.getOrNull() }
        return if (expiresAt == null || clock.instant().plus(refreshBefore).isBefore(expiresAt)) media else null
    }
}
