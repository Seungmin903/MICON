package com.micon.app.ui.report

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.runtime.remember
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import android.content.pm.PackageManager
import androidx.test.platform.app.InstrumentationRegistry
import android.content.Context
import android.content.ContextWrapper
import android.graphics.Bitmap
import com.micon.app.range.RangeRepository
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class OnboardingRangeIntroScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun capture(name: String) {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null), name)
        file.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun startWaitsForQuietAndBothLinesThenBlocksRepeatedClicks() {
        val ready = mutableStateOf(false)
        val busy = mutableStateOf(false)
        var starts = 0
        var backs = 0
        compose.mainClock.autoAdvance = false
        compose.setContent { MiconTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
            OnboardingRangeIntroOverlay(true, ready.value, !busy.value,
                onStart = { starts++; busy.value = true }, onBack = { backs++ })
            }
        } }
        compose.mainClock.advanceTimeBy(100)
        val duringFade = brightestTextPixel()
        compose.mainClock.advanceTimeBy(250)
        org.junit.Assert.assertTrue("first copy should fade in", brightestTextPixel() > duringFade + .08f)
        compose.onNodeWithText("측정이 시작되면,").assertIsDisplayed()
        compose.onNodeWithText("편한 목소리로 '아'를 내주세요").assertDoesNotExist()
        compose.onNodeWithText("측정 시작하기").assertDoesNotExist()
        compose.mainClock.advanceTimeBy(1550)
        compose.onNodeWithText("편한 목소리로 '아'를 내주세요").assertIsDisplayed()
        compose.onNodeWithText("측정 시작하기").assertDoesNotExist()
        compose.runOnIdle { ready.value = true }
        compose.mainClock.advanceTimeBy(350)
        compose.onNodeWithText("측정 시작하기").performClick()
        compose.mainClock.advanceTimeByFrame()
        compose.onNodeWithText("측정 시작하기").assertIsNotEnabled().performTouchInput { click() }
        compose.onNodeWithText("측정이 시작되면,").assertIsDisplayed()
        compose.onNodeWithText("편한 목소리로 '아'를 내주세요").assertIsDisplayed()
        compose.runOnIdle { assertEquals(1, starts) }
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.runOnIdle { assertEquals(1, backs) }
    }

    private fun brightestTextPixel(): Float {
        val bitmap = compose.onNodeWithText("측정이 시작되면,").captureToImage().asAndroidBitmap()
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return pixels.maxOf { android.graphics.Color.red(it) } / 255f
    }

    @Test fun decliningAutomaticPermissionKeepsTheIntroAndOffersAnExplicitRetry() {
        compose.setContent { MiconTheme {
            val base = LocalContext.current
            val denied = remember { object : ContextWrapper(base) {
                override fun checkPermission(permission: String, pid: Int, uid: Int) = PackageManager.PERMISSION_DENIED
                override fun checkSelfPermission(permission: String) = PackageManager.PERMISSION_DENIED
            } }
            CompositionLocalProvider(LocalContext provides denied) {
                val repository = remember { RangeRepository(base, deviceOnly = true,
                    storageDirectory = File(base.cacheDir, "denied-intro-${UUID.randomUUID()}")) }
                GuidedRangeScreen({}, {}, repository = repository, onboardingIntro = true)
            }
        } }
        compose.onNodeWithText("나중에").performClick()
        compose.onNodeWithTag("onboarding-range-intro").assertIsDisplayed()
        compose.onNodeWithText("측정 시작하기").assertDoesNotExist()
        compose.onNodeWithText("소음 측정 다시 시도").performClick()
        compose.onNodeWithText("나중에").assertIsDisplayed()
    }

    @Test fun deniedPermissionOrStartFailureKeepsIntroAvailableForRetry() {
        val error = mutableStateOf<String?>("마이크 권한과 저장 공간을 확인해 주세요")
        var retries = 0
        compose.setContent { MiconTheme {
            OnboardingRangeIntroOverlay(true, false, false, error = error.value,
                onStart = {}, onBack = {}, calibrationRetryAvailable = true,
                onRetryCalibration = { retries++; error.value = null })
        } }
        compose.onNodeWithText("마이크 권한과 저장 공간을 확인해 주세요").assertIsDisplayed()
        compose.onNodeWithText("측정 시작하기").assertDoesNotExist()
        compose.onNodeWithText("소음 측정 다시 시도").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals(1, retries) }
    }

    @Test fun comfortablePhaseRemovesIntroAfter300msFade() {
        val visible = mutableStateOf(true)
        compose.mainClock.autoAdvance = false
        compose.setContent { MiconTheme { Box {
            OnboardingRangeIntroOverlay(visible.value, true, false, onStart = {}, onBack = {})
        } } }
        compose.onNodeWithTag("onboarding-range-intro").assertExists()
        compose.runOnIdle { visible.value = false }
        compose.mainClock.advanceTimeBy(160)
        compose.onNodeWithTag("onboarding-range-intro").assertExists()
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithTag("onboarding-range-intro").assertDoesNotExist()
    }

    @Test fun actualMeasurementCalibratesThenRevealsTheFullKeyboard() {
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.grantRuntimePermission(instrument.targetContext.packageName,
            android.Manifest.permission.RECORD_AUDIO)
        compose.setContent { MiconTheme {
            val base = LocalContext.current
            val repository = remember {
                val label = "onboarding-intro-${UUID.randomUUID()}"
                val isolated = object : ContextWrapper(base) {
                    override fun getApplicationContext(): Context = this
                    override fun getFilesDir() = File(base.cacheDir, label).apply { mkdirs() }
                    override fun getSharedPreferences(name: String, mode: Int) =
                        base.getSharedPreferences("$label-$name", mode)
                }
                RangeRepository(isolated, deviceOnly = true)
            }
            GuidedRangeScreen({}, {}, repository = repository, onboardingIntro = true)
        } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasText("측정 시작하기") and isEnabled()).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNodeWithText("측정이 시작되면,").assertIsDisplayed()
        compose.onNodeWithText("편한 목소리로 '아'를 내주세요").assertIsDisplayed()
        compose.onNodeWithTag("guided-range-keyboard").assertDoesNotExist()
        capture("onboarding-range-intro.png")
        compose.onNodeWithText("측정 시작하기").performClick()
        compose.waitUntil(15_000) {
            compose.onAllNodesWithTag("onboarding-range-intro").fetchSemanticsNodes().isEmpty()
        }
        compose.onNodeWithTag("guided-range-keyboard").assertIsDisplayed()
        compose.onNodeWithText("여기까지 측정하기").assertDoesNotExist()
        capture("onboarding-comfortable-keyboard.png")
    }
}
