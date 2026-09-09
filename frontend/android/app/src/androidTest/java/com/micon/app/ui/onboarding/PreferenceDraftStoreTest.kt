package com.micon.app.ui.onboarding

import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.preferences.PreferenceArtist
import com.micon.app.data.preferences.PreferenceDraft
import com.micon.app.data.preferences.PreferenceGenre
import com.micon.app.data.preferences.SharedPreferenceDraftStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PreferenceDraftStoreTest {
    @Test fun persistedDraftRestoresFromNewStoreWithoutCrossingAccountOrFlow() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val first = SharedPreferenceDraftStore(context)
        val user = 987001L
        val otherUser = 987002L
        first.clear(user, false)
        first.clear(user, true)
        first.clear(otherUser, false)
        try {
            val draft = PreferenceDraft(listOf(PreferenceGenre(3, "록")), listOf(PreferenceArtist(5, "아이유")), step = 1, dirty = true)
            first.write(user, false, draft)
            val reopened = SharedPreferenceDraftStore(context)
            assertEquals(draft, reopened.read(user, false))
            assertNull(reopened.read(otherUser, false))
            assertNull(reopened.read(user, true))
            reopened.clear(user, false)
            assertNull(first.read(user, false))
        } finally {
            first.clear(user, false)
            first.clear(user, true)
            first.clear(otherUser, false)
        }
    }
}
