package com.micon.app.ui.onboarding

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.common.LocalOnboardingChrome
import com.micon.app.ui.common.OnboardingChromeState
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnboardingSongGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    @Test fun timbreGuideUsesTheNewCopyAndFadeBlocksDuplicateNavigation() {
        var nexts = 0
        var finished = 0
        var backs = 0
        val chrome = OnboardingChromeState()
        compose.mainClock.autoAdvance = false
        compose.setContent { MiconTheme {
            CompositionLocalProvider(LocalOnboardingChrome provides chrome,
                LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    OnboardingSongGuideScreen(nickname = "유림", onBack = { backs++ }, onNext = { nexts++; true },
                        onFinished = { finished++ })
                }
            }
        } }
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("지금부터는 유림님이 노래할때 어떤 목소리를 가졌는지 분석할거에요").assertIsDisplayed()
        compose.onNodeWithText("노래 선택하기").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "onboarding-song-guide-large-font.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("노래 선택하기").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("노래 선택하기").assertIsNotEnabled().performTouchInput { click() }
        compose.mainClock.advanceTimeBy(150)
        compose.onNodeWithTag("onboarding-song-guide").assertExists()
        compose.runOnIdle { assertTrue(chrome.dark); assertEquals(0, finished) }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("onboarding-song-guide").assertDoesNotExist()
        compose.runOnIdle {
            assertFalse(chrome.dark)
            assertEquals(1, nexts); assertEquals(1, finished); assertEquals(1, backs)
        }
    }

    @Test fun persistenceRetryKeepsAllCopyAndDoesNotNavigateOrRestartTheSequence() {
        var attempts = 0
        var navigated = 0
        val error = mutableStateOf<String?>(null)
        compose.setContent { MiconTheme {
            OnboardingSongGuideScreen(error = error.value, onBack = {}, onNext = {
                attempts++
                error.value = if (attempts == 1) "저장 실패" else null
                attempts > 1
            }, onFinished = { navigated++ })
        } }
        compose.mainClock.advanceTimeBy(4000)
        compose.onNodeWithText("노래 선택하기").performClick()
        compose.onNodeWithText("저장 실패").assertIsDisplayed()
        compose.onNodeWithText("지금부터는 노래할때 어떤 목소리를 가졌는지 분석할거에요").assertIsDisplayed()
        compose.runOnIdle { assertEquals(0, navigated) }
        compose.onNodeWithText("노래 선택하기").performClick()
        compose.mainClock.advanceTimeBy(400)
        compose.runOnIdle { assertEquals(2, attempts); assertEquals(1, navigated) }
    }

    @Test fun stageGuideFitsLargeFontAndUsesGenericCopyWithoutNickname() {
        val chrome = OnboardingChromeState()
        compose.setContent { MiconTheme {
            CompositionLocalProvider(LocalOnboardingChrome provides chrome,
                LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    OnboardingStageGuideScreen(analysisGuideCopy(null), "다음", "onboarding-analysis-guide",
                        onBack = {}, onNext = { false }, onFinished = {})
                }
            }
        } }
        compose.onNodeWithText("두 단계에 따라 목소리를 분석할거에요").assertIsDisplayed()
        compose.onNodeWithText("다음").assertIsDisplayed()
        compose.runOnIdle { assertTrue(chrome.dark) }
    }
}
