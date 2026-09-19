package com.micon.app.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class LoginReturnDestinationTest {
    @Test fun uuidSurvivesSongPracticeAndLoginReturnRoutes() {
        val id = "d221afea-6dc1-5223-a6bc-68fa055b4820"
        assertEquals("song/$id", Routes.song(id))
        assertEquals("match/$id", Routes.songMatch(id))
        assertEquals("practice/$id?selectedKey=-2", Routes.practice(id, -2))
        assertEquals("report/sing/$id", Routes.singAnalysis(id))
        assertEquals("song/$id?source=favorites", loginReturnDestination(Routes.SONG, id, source = Routes.FAVORITES))
        assertEquals("song/$id", loginReturnDestination(Routes.PRACTICE_RUN, id))
    }
    @Test fun detailOriginAndSelectedPracticeKeyAreOptionalAndBackwardsCompatible() {
        assertEquals("song/42", Routes.song("42"))
        assertEquals("song/42?source=favorites", Routes.song("42", Routes.FAVORITES))
        assertEquals("practice/42", Routes.practice("42"))
        assertEquals("practice/42?selectedKey=-2", Routes.practice("42", -2))
        assertEquals("practice/42?selectedKey=3", Routes.practice("42", 3))
        assertTrue(Routes.SONG_MATCH in Routes.withBottomBar)
    }
    @Test fun restoresRequestedTabAndSong() {
        assertEquals(Routes.FAVORITES, loginReturnDestination(Routes.FAVORITES))
        assertEquals("song/42", loginReturnDestination(Routes.SONG, "42"))
        assertEquals("song/42?source=favorites", loginReturnDestination(Routes.SONG, "42", source = Routes.FAVORITES))
        assertEquals("song/42", loginReturnDestination(Routes.SONG, "42", source = "untrusted"))
        assertEquals("match/42", loginReturnDestination(Routes.SONG_MATCH, "42"))
        assertEquals("setlist/42", loginReturnDestination(Routes.SETLIST, setlistId = 42))
    }

    @Test fun authenticationScreensDoNotReplacePendingDestination() {
        for (route in listOf(Routes.LOGIN, Routes.SIGNUP, Routes.SURVEY, Routes.SPLASH, Routes.PASSWORD_RESET)) {
            assertNull(loginReturnDestination(route))
        }
    }

    @Test fun doesNotRestoreGuestRecordingOrResult() {
        assertEquals(Routes.REPORT, loginReturnDestination(Routes.SING_ANALYSIS_RUN, "42"))
        assertEquals(Routes.EVALUATION, loginReturnDestination(Routes.RESULT))
        assertEquals("song/42", loginReturnDestination(Routes.PRACTICE_RUN, "42"))
    }

    @Test fun rejectsInvalidDestinations() {
        assertNull(loginReturnDestination("untrusted"))
        assertNull(loginReturnDestination(Routes.SONG, ""))
        assertNull(loginReturnDestination(Routes.SETLIST, setlistId = 0))
        assertNull(loginReturnDestination(Routes.SETLIST, setlistId = -1))
    }
}
