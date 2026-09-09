package com.micon.app.core.auth

class MemoryTokenStore : AuthTokenStore {
    private var session = SessionSnapshot(null, null, -1, 0)
    @Synchronized override fun snapshot() = session
    @Synchronized override fun saveSession(userId: Long, accessToken: String, refreshToken: String) {
        session = SessionSnapshot(accessToken, refreshToken, userId, session.generation + 1)
    }
    @Synchronized override fun saveUserId(userId: Long) { session = session.copy(userId = userId) }
    @Synchronized override fun rotateTokens(expected: SessionSnapshot, accessToken: String, refreshToken: String): Boolean {
        if (session != expected) return false
        session = session.copy(accessToken = accessToken, refreshToken = refreshToken)
        return true
    }
    @Synchronized override fun clearIfCurrent(expected: SessionSnapshot): Boolean {
        if (session != expected) return false
        clear()
        return true
    }
    @Synchronized override fun clear() { session = SessionSnapshot(null, null, -1, session.generation + 1) }
}
