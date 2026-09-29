package com.micon.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RecommendationDisplayPolicyTest {
    @Test fun `fills fifteen slots with at most three songs per artist preserving order`() {
        val items = (0 until 30).map { index ->
            val artist = if (index < 10) "Same" else "Artist ${index / 3}"
            RecommendedSong(index + 1, SongSummary("$index", "Song $index", artist, null, 1000), null, emptyList())
        }
        val selected = RecommendationDisplayPolicy.select(items, 50)
        assertEquals(15, selected.size)
        assertEquals(listOf("0", "1", "2", "10"), selected.take(4).map { it.song.id })
        assertTrue(selected.groupingBy { it.song.artist }.eachCount().values.all { it <= 3 })
        assertEquals(5, RecommendationDisplayPolicy.select(items, 5).size)
    }
}
