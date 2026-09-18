package com.micon.app.onboarding

import android.graphics.Bitmap
import android.os.Environment
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.BuildConfig
import com.micon.app.MainActivity
import com.micon.app.appContainer
import com.micon.app.core.auth.TokenManager
import com.micon.app.data.preferences.NetworkPreferenceRepository
import com.micon.app.data.preferences.PreferenceApi
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import java.io.File

/** Opt-in real HTTP journey. Run only against backend's isolated preferenceSmoke H2 server. */
class OnboardingJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun signupDraftRestoreCompleteReloginAndEdit() {
        assumeTrue("Requires -e onboardingLiveSmoke true and preferenceSmoke server",
            InstrumentationRegistry.getArguments().getString("onboardingLiveSmoke") == "true" &&
                BuildConfig.API_BASE_URL == "http://10.0.2.2:18080/")
        compose.runOnUiThread { TokenManager.clear() }
        compose.activityRule.scenario.recreate()
        awaitTag("auth_submit")
        compose.onNodeWithText("회원가입").performScrollTo().performClick()
        awaitTag("auth_password_confirm")
        val email = "onboarding-${System.currentTimeMillis()}@example.com"
        val password = "MiconTest9!"
        input("auth_email", email)
        input("auth_password", password)
        input("auth_password_confirm", password)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("auth_submit").performScrollTo().performClick()

        awaitTag("genre-20")
        compose.onNodeWithTag("preferences-next").assertIsNotEnabled()
        compose.onNodeWithTag("genre-20").performClick()
        compose.onNodeWithTag("genre-25").performClick()
        compose.onNodeWithTag("genre-grid").performScrollToNode(hasTestTag("genre-20"))
        screenshot("onboarding-genres.png")
        compose.onNodeWithTag("preferences-next").performClick()
        awaitTag("preferences-search")
        compose.onNodeWithTag("preferences-complete").assertIsNotEnabled()
        compose.onNodeWithTag("preferences-search").performClick().performTextReplacement("아이유")
        awaitTag("artist-10")
        compose.onNodeWithTag("artist-10").assertIsDisplayed().performClick()
        screenshot("onboarding-artist-search.png")
        compose.onNodeWithTag("preferences-search").performTextReplacement("DAY6")
        awaitTag("artist-13")
        compose.onNodeWithTag("artist-13").assertIsDisplayed().performClick()
        compose.onNodeWithTag("preferences-close-search").performClick()
        compose.onNodeWithTag("preferences-search").performTextReplacement("")
        Espresso.closeSoftKeyboard()
        awaitTag("artist-16")
        screenshot("onboarding-artists.png")

        // Both stages survive backward navigation and Activity recreation before completion.
        compose.onNodeWithTag("preferences-back").performClick()
        compose.onNodeWithTag("genre-20").assertIsOn()
        compose.onNodeWithTag("genre-25").assertIsOn()
        compose.onNodeWithTag("preferences-next").performClick()
        compose.activityRule.scenario.recreate()
        awaitTag("selected-artist-10")
        awaitTag("selected-artist-13")
        compose.onNodeWithTag("preferences-complete").performClick()
        awaitTag("onboarding-range-intro")
        compose.onNodeWithText("편한 목소리로 '아'를 내주세요").assertIsDisplayed()
        compose.onNodeWithContentDescription("뒤로").performClick()
        awaitHome()

        val repo = NetworkPreferenceRepository(compose.activity.appContainer.backend.retrofit.create(PreferenceApi::class.java))
        runBlocking {
            val stored = repo.getPreferences()
            assertTrue(stored.onboardingCompleted)
            assertEquals(setOf(20L, 25L), stored.genreIds.toSet())
            assertEquals(setOf(10L, 13L), stored.artistIds.toSet())
        }

        // Server completion gates a fresh login, and settings opens persisted editable selections.
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithText("로그아웃").performScrollTo().performClick()
        awaitTag("auth_submit")
        input("auth_email", email)
        input("auth_password", password)
        Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("auth_submit").performScrollTo().performClick()
        awaitHome()
        compose.onNodeWithContentDescription("설정").performClick()
        compose.onNodeWithText("관심 장르·아티스트 수정").performScrollTo().performClick()
        awaitTag("genre-20")
        compose.onNodeWithTag("genre-20").assertIsOn()
        compose.onNodeWithTag("genre-25").assertIsOn()
        compose.onNodeWithTag("genre-25").performClick()
        compose.onNodeWithTag("preferences-next").performClick()
        awaitTag("selected-artist-13")
        compose.onNodeWithTag("selected-artist-13").performClick()
        compose.onNodeWithTag("preferences-complete").performClick()
        compose.waitUntil(30_000) {
            compose.onAllNodesWithTag("preference-survey").fetchSemanticsNodes().isEmpty()
        }
        runBlocking {
            val updated = repo.getPreferences()
            assertTrue(updated.onboardingCompleted)
            assertEquals(listOf(20L), updated.genreIds)
            assertEquals(listOf(10L), updated.artistIds)
        }
    }

    private fun input(tag: String, value: String) = compose.onNode(
        hasSetTextAction() and hasAnyAncestor(hasTestTag(tag)), useUnmergedTree = true,
    ).performScrollTo().performTextReplacement(value)

    private fun awaitTag(tag: String) = compose.waitUntil(30_000) {
        compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
    }

    private fun awaitHome() = compose.waitUntil(30_000) {
        compose.onAllNodesWithContentDescription("설정").fetchSemanticsNodes().isNotEmpty() &&
            compose.onAllNodesWithTag("preference-survey").fetchSemanticsNodes().isEmpty()
    }

    private fun screenshot(name: String) {
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        val directory = compose.activity.getExternalFilesDir(Environment.DIRECTORY_PICTURES)!!
        directory.mkdirs()
        File(directory, name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
