package com.micon.app.data

import java.util.Locale

object RecommendationDisplayPolicy {
    const val SONGS_PER_SHELF = 15
    const val SONGS_PER_ARTIST = 3

    /** Apply after catalog mapping so missing summaries do not consume visible slots. */
    fun select(items: List<RecommendedSong>, limit: Int): List<RecommendedSong> {
        val count = limit.coerceIn(0, SONGS_PER_SHELF)
        val artists = mutableMapOf<String, Int>()
        return items.asSequence().filter { item ->
            val artist = item.song.artist.trim().lowercase(Locale.ROOT).ifBlank { "song:${item.song.id}" }
            val used = artists.getOrDefault(artist, 0)
            if (used >= SONGS_PER_ARTIST) false else {
                artists[artist] = used + 1
                true
            }
        }.take(count).toList()
    }
}
