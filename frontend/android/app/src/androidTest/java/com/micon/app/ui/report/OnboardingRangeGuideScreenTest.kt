package com.micon.app.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import java.io.File
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class OnboardingRangeGuideScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun guideShowsSequentialCopyThenButtonAndSupportsRetryWithoutRestartingCopy() {
        val ready = mutableStateOf(true)
        val error = mutableStateOf<String?>(null)
        var starts = 0
        var backs = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MiconTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    OnboardingRangeGuideOverlay(true, true, ready.value, error.value,
                        onStart = { starts++; ready.value = false }, onBack = { backs++ })
                }
            }
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("잘하셨어요!").assertIsDisplayed()
        compose.onNodeWithText("측정 시작").assertDoesNotExist()
        capture("onboarding-range-guide-congratulations.png")
        compose.mainClock.advanceTimeBy(1450)
        compose.onNodeWithText("잘하셨어요!").assertIsDisplayed()
        compose.mainClock.advanceTimeBy(100)
        compose.onNodeWithText("이제 안내음을 듣고, 따라서 '아'를 내주세요").assertIsDisplayed()
        compose.onNodeWithText("잘하셨어요!").assertIsDisplayed()
        compose.onNodeWithText("측정 시작").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1900)
        compose.onNodeWithText("측정 시작").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(150)
        compose.mainClock.advanceTimeBy(300)
        capture("onboarding-range-guide-start.png")
        compose.onNodeWithText("측정 시작").assertIsEnabled().performClick()
        compose.onNodeWithText("잘하셨어요!").assertIsDisplayed()
        compose.onNodeWithText("이제 안내음을 듣고, 따라서 '아'를 내주세요").assertIsDisplayed()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("측정 시작").assertIsNotEnabled().performTouchInput { click() }
        assertEquals(1, starts)
        compose.runOnIdle { error.value = "녹음은 보존되어 있어요"; ready.value = true }
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("녹음은 보존되어 있어요").assertIsDisplayed()
        compose.onNodeWithText("다시 시도").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("뒤로").performClick()
        assertEquals(2, starts); assertEquals(1, backs)
    }

    @Test fun resumedGuideFadesOutOver300ms() {
        val visible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MiconTheme {
            OnboardingRangeGuideOverlay(true, visible.value, true, onStart = {}, onBack = {})
        } }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("onboarding-range-guide").assertExists()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("onboarding-range-guide").assertDoesNotExist()
    }
}
