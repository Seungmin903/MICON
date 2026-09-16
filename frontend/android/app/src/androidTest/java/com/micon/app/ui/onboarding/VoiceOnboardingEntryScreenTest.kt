package com.micon.app.ui.onboarding

import androidx.activity.ComponentActivity
import android.graphics.Bitmap
import android.accessibilityservice.AccessibilityService
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class VoiceOnboardingEntryScreenTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun entryButtonDoesNotShowAChoiceWithoutProgress() {
        var opened = 0
        compose.setContent { MiconTheme {
            VoiceOnboardingEntry(VoiceOnboardingEntryState(), { opened++ }, {}, {}, {})
        } }
        compose.onNodeWithText("이전 분석 이어하기").assertDoesNotExist()
        compose.onNodeWithText("목소리 분석하러 가기").performClick()
        compose.runOnIdle { assertEquals(1, opened) }
    }

    @Test fun dialogOffersResumeAndStartNewWithClearConsequences() {
        var resumed = 0
        var restarted = 0
        compose.setContent { MiconTheme {
            VoiceOnboardingEntry(VoiceOnboardingEntryState(choosing = true), {}, { resumed++ }, { restarted++ }, {})
        } }
        compose.onNodeWithText("저장한 목소리 프로필은 유지돼요.", substring = true).assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "onboarding-resume-choice.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("이전 분석 이어하기").performClick()
        compose.runOnIdle { assertEquals(1, resumed); assertEquals(0, restarted) }
        compose.onNodeWithText("새로 측정 시작하기").performClick()
        compose.runOnIdle { assertEquals(1, restarted) }
    }

    @Test fun backDismissesWithoutChoosingOrDeleting() {
        var dismissed = 0
        var chosen = 0
        compose.setContent { MiconTheme {
            VoiceOnboardingEntry(VoiceOnboardingEntryState(choosing = true), {}, { chosen++ }, { chosen++ }, { dismissed++ })
        } }
        compose.onNode(isDialog()).assertIsDisplayed()
        assertTrue(InstrumentationRegistry.getInstrumentation().uiAutomation
            .performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK))
        compose.waitUntil(timeoutMillis = 5_000) { dismissed == 1 }
        compose.runOnIdle { assertEquals(1, dismissed); assertEquals(0, chosen) }
    }

    @Test fun choicesAreDisabledWhilePreparingNavigation() {
        compose.setContent { MiconTheme {
            VoiceOnboardingEntry(VoiceOnboardingEntryState(choosing = true, busy = true), {}, {}, {}, {})
        } }
        compose.onNodeWithText("이전 분석 이어하기").assertIsNotEnabled()
        compose.onNodeWithText("새로 측정 시작하기").assertIsNotEnabled()
    }
}
