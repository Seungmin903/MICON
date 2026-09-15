package com.micon.app.ui.navigation

import org.junit.Assert.*
import org.junit.Test

class LoginReturnDestinationTest {
    @Test fun restoresRequestedTabAndSong() {
        assertEquals(Routes.FAVORITES, loginReturnDestination(Routes.FAVORITES))
        assertEquals("song/42", loginReturnDestination(Routes.SONG, 42))
        assertEquals("match/42", loginReturnDestination(Routes.SONG_MATCH, 42))
        assertEquals("setlist/42", loginReturnDestination(Routes.SETLIST, setlistId = 42))
    }

    @Test fun authenticationScreensDoNotReplacePendingDestination() {
        for (route in listOf(Routes.LOGIN, Routes.SIGNUP, Routes.SURVEY, Routes.SPLASH, Routes.PASSWORD_RESET)) {
            assertNull(loginReturnDestination(route))
        }
    }

    @Test fun doesNotRestoreGuestRecordingOrResult() {
        assertEquals(Routes.REPORT, loginReturnDestination(Routes.SING_ANALYSIS_RUN, 42))
        assertEquals(Routes.EVALUATION, loginReturnDestination(Routes.RESULT))
        assertEquals("song/42", loginReturnDestination(Routes.PRACTICE_RUN, 42))
    }

    @Test fun rejectsInvalidDestinations() {
        assertNull(loginReturnDestination("untrusted"))
        assertNull(loginReturnDestination(Routes.SONG, 0))
        assertNull(loginReturnDestination(Routes.SETLIST, setlistId = 0))
        assertNull(loginReturnDestination(Routes.SETLIST, setlistId = -1))
    }
}
