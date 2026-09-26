package com.micon.app.core.auth

/** Changes on login/logout; late requests cannot restore a different account's session. */
data class SessionSnapshot(val accessToken: String?, val refreshToken: String?, val userId: Long, val generation: Long) {
    val isLoggedIn: Boolean get() = !refreshToken.isNullOrBlank()
}

/** Token rotation keeps the generation; a logout or new login changes it. */
fun SessionSnapshot.matchesRecordingOwner(owner: SessionSnapshot): Boolean =
    isLoggedIn && owner.isLoggedIn && userId > 0 && userId == owner.userId && generation == owner.generation

interface AuthTokenStore {
    fun snapshot(): SessionSnapshot
    fun saveSession(userId: Long, accessToken: String, refreshToken: String)
    fun saveUserId(userId: Long)
    fun rotateTokens(expected: SessionSnapshot, accessToken: String, refreshToken: String): Boolean
    fun clearIfCurrent(expected: SessionSnapshot): Boolean
    fun clear()
}
