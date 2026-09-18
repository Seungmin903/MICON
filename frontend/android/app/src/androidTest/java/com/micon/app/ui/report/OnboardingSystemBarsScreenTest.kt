package com.micon.app.ui.report

import android.graphics.Bitmap
import android.os.Build
import android.view.Window
import androidx.activity.compose.setContent
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.core.view.WindowCompat
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.common.*
import com.micon.app.ui.theme.Ink
import com.micon.app.ui.theme.MiconTheme
import com.micon.app.MainActivity
import com.micon.app.ui.onboarding.OnboardingSongGuideScreen
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnboardingSystemBarsScreenTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()
    private lateinit var window: Window
    private val visible = mutableStateOf(false)
    private val mounted = mutableStateOf(true)

    @Suppress("DEPRECATION")
    private fun show(guide: Boolean, songGuide: Boolean = false) {
        compose.mainClock.autoAdvance = false
        compose.activityRule.scenario.onActivity { activity ->
                window = activity.window
                val controller = WindowCompat.getInsetsController(window, window.decorView)
                controller.isAppearanceLightStatusBars = true
                controller.isAppearanceLightNavigationBars = true
                if (Build.VERSION.SDK_INT >= 29) window.isNavigationBarContrastEnforced = true
        activity.setContent { MiconTheme {
            val chrome = remember { OnboardingChromeState() }
            CompositionLocalProvider(LocalOnboardingChrome provides chrome) {
                val transition = remember { MutableTransitionState(false) }.also { it.targetState = visible.value }
                if (mounted.value && !songGuide) OnboardingDarkBackground(transition.currentState || transition.targetState)
                if (chrome.dark) OnboardingSystemBars()
                Scaffold(containerColor = if (chrome.dark) Ink else Color.White) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding)) {
                        if (mounted.value) {
                            if (songGuide) OnboardingSongGuideScreen(onBack = {}, onNext = { true },
                                onFinished = { mounted.value = false })
                            else if (guide) OnboardingRangeGuideOverlay(visible.value, visible.value, true,
                                onStart = {}, onBack = {}, visibilityState = transition)
                            else OnboardingRangeIntroOverlay(visible.value, true, true,
                                onStart = {}, onBack = {}, visibilityState = transition)
                        }
                    }
                }
            }
        } }
        }
        compose.mainClock.advanceTimeByFrame()
        compose.runOnIdle { visible.value = true }
        compose.mainClock.advanceTimeBy(4000)
        compose.waitForIdle()
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun assertDarkAndCapture(label: String) {
        compose.runOnIdle {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
            if (Build.VERSION.SDK_INT >= 29) assertFalse(window.isNavigationBarContrastEnforced)
        }
        val instrument = InstrumentationRegistry.getInstrumentation()
        val navigationMode = instrument.uiAutomation.executeShellCommand("settings get secure navigation_mode").use { fd ->
            android.os.ParcelFileDescriptor.AutoCloseInputStream(fd).bufferedReader().readText().trim()
        }
        // Gesture-handle contrast samples rendered frames outside the manual Compose clock.
        compose.mainClock.autoAdvance = true
        try {
            compose.waitUntil(timeoutMillis = 3000) {
                val frame = checkNotNull(instrument.uiAutomation.takeScreenshot())
                val statusWhite = hasWhitePixel(frame, 0, frame.height / 25)
                // The system gesture handle uses translucent white (about 237 on this dark background).
                val navigationWhite = hasWhitePixel(frame, frame.height * 19 / 20, frame.height, minimum = 220)
                frame.recycle()
                statusWhite && navigationWhite
            }
        } catch (failure: Throwable) {
            val frame = checkNotNull(instrument.uiAutomation.takeScreenshot())
            File(instrument.targetContext.getExternalFilesDir(null), "$label-icons-failed-$navigationMode.png").outputStream().use {
                frame.compress(Bitmap.CompressFormat.PNG, 100, it)
            }
            frame.recycle()
            throw failure
        } finally {
            compose.mainClock.autoAdvance = false
        }
        val screenshot = checkNotNull(instrument.uiAutomation.takeScreenshot())
        val expected = Ink.toArgb()
        assertEquals("status inset background", expected, screenshot.getPixel(screenshot.width / 10, 5))
        assertEquals("navigation inset background", expected, screenshot.getPixel(screenshot.width / 10, screenshot.height - 5))
        File(instrument.targetContext.getExternalFilesDir(null), "$label-bars-$navigationMode.png").outputStream().use {
            screenshot.compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        screenshot.recycle()
    }

    private fun hasWhitePixel(bitmap: Bitmap, top: Int, bottom: Int, minimum: Int = 240): Boolean {
        for (y in top until bottom) for (x in 0 until bitmap.width) {
            val pixel = bitmap.getPixel(x, y)
            if (android.graphics.Color.red(pixel) >= minimum && android.graphics.Color.green(pixel) >= minimum &&
                android.graphics.Color.blue(pixel) >= minimum) return true
        }
        return false
    }

    private fun assertRestored() = compose.runOnIdle {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        assertTrue(controller.isAppearanceLightStatusBars)
        assertTrue(controller.isAppearanceLightNavigationBars)
        if (Build.VERSION.SDK_INT >= 29) assertTrue(window.isNavigationBarContrastEnforced)
    }

    @Test fun songGuidePaintsBothSystemInsetsAndRestoresStyleAfterItsFade() {
        show(guide = false, songGuide = true)
        assertDarkAndCapture("song-guide")
        compose.onNodeWithText("다음으로").performClick()
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle {
            val controller = WindowCompat.getInsetsController(window, window.decorView)
            assertFalse(controller.isAppearanceLightStatusBars)
            assertFalse(controller.isAppearanceLightNavigationBars)
        }
        compose.mainClock.advanceTimeBy(200)
        assertRestored()
    }

    @Test fun introPaintsBothSystemInsetsAndRestoresStyleAfterItsFade() {
        show(guide = false)
        assertDarkAndCapture("intro")
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(160)
        compose.runOnIdle { assertFalse(WindowCompat.getInsetsController(window, window.decorView).isAppearanceLightStatusBars) }
        compose.mainClock.advanceTimeBy(220)
        assertRestored()
    }

    @Test fun guidePaintsBothSystemInsetsAndRestoresStyleWhenRemoved() {
        show(guide = true)
        assertDarkAndCapture("guide")
        compose.runOnIdle { mounted.value = false }
        compose.mainClock.advanceTimeBy(100)
        assertRestored()
    }
}
