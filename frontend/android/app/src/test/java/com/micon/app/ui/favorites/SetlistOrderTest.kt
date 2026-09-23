package com.micon.app.ui.favorites

import com.micon.app.data.favorites.SetlistSong
import org.junit.Assert.assertEquals
import org.junit.Test

class SetlistOrderTest {
    private fun song(id: String, position: Int) = SetlistSong(id, id, "artist", null, null, position)

    @Test fun `moving a song updates order and positions`() {
        val moved = moveSetlistSong(listOf(song("1", 0), song("2", 1), song("3", 2)), "1", "3")

        assertEquals(listOf("2", "3", "1"), moved.map { it.songId })
        assertEquals(listOf(0, 1, 2), moved.map { it.position })
    }

    @Test fun `unknown or same target leaves order unchanged`() {
        val songs = listOf(song("1", 0), song("2", 1))
        assertEquals(songs, moveSetlistSong(songs, "missing", "2"))
        assertEquals(songs, moveSetlistSong(songs, "1", "1"))
    }
}
