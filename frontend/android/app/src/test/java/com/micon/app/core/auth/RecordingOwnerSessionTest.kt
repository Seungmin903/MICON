package com.micon.app.core.auth

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecordingOwnerSessionTest {
    private val owner = SessionSnapshot("access", "refresh", userId = 11, generation = 3)

    @Test fun onlyTheSameActiveLoginMayPlayTheTake() {
        assertTrue(owner.matchesRecordingOwner(owner))
        assertTrue(owner.copy(accessToken = "rotated").matchesRecordingOwner(owner))
        assertFalse(owner.copy(userId = 12).matchesRecordingOwner(owner))
        assertFalse(owner.copy(generation = 4).matchesRecordingOwner(owner))
        assertFalse(owner.copy(refreshToken = null).matchesRecordingOwner(owner))
    }
}
