package com.micon.app.data

/** Catalog IDs are opaque path-safe strings, not numbers or locally recomputed hashes. */
private val SONG_ID = Regex("[A-Za-z0-9_-]{1,64}")

fun isValidSongId(value: String): Boolean = SONG_ID.matches(value)

fun requireSongId(value: String): String {
    require(isValidSongId(value)) { "Invalid song ID" }
    return value
}
