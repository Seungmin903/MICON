package com.micon.app.range

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.micon.app.ui.charts.GuidedKeyboardMode
import com.micon.app.ui.charts.GuidedRangeKeyboard
import com.micon.app.ui.charts.PianoRangeChart
import com.micon.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import kotlin.math.roundToInt

class GuidedKeyboardOctaveScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun checkColor(expected: Color, actual: Color) {
        assertEquals(expected.red, actual.red, .03f)
        assertEquals(expected.green, actual.green, .03f)
        assertEquals(expected.blue, actual.blue, .03f)
    }

    @Test fun boundariesStayBelowTargetBordersAndUserMarker() {
        val target = mutableStateOf<Int?>(null)
        var pixelsPerDp = 1f
        compose.setContent { MiconTheme {
            pixelsPerDp = LocalDensity.current.density
            Box(Modifier.requiredWidth(360.dp)) {
                GuidedRangeKeyboard(listOf(RangeNote(48, "passed", null)), target.value, 48.0,
                    showTargetLabel = false)
            }
        } }
        val keyboard = compose.onNodeWithTag("guided-range-keyboard")
        keyboard.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "2 옥타브, 3 옥타브, 4 옥타브, 5 옥타브"))
        var bitmap = keyboard.captureToImage().toPixelMap()
        val x = (bitmap.width * 7f / 29).roundToInt()
        val y = (bitmap.height * .75f).roundToInt()
        checkColor(InkSoft, bitmap[x, y])
        compose.runOnIdle { target.value = 48 }
        bitmap = keyboard.captureToImage().toPixelMap()
        checkColor(Purple, bitmap[x, y])
        checkColor(GreenBar, bitmap[(bitmap.width * 7.5f / 29).roundToInt(), y])
        checkColor(Teal, bitmap[(bitmap.width * 7.5f / 29).roundToInt(),
            (bitmap.height - 12 * pixelsPerDp).roundToInt()])
        compose.onNodeWithTag("range-current-target").assertDoesNotExist()
    }

    @Test fun lowAndHighWindowsKeepAllThreeOctavesWithLargeFont() {
        val target = mutableStateOf(36)
        compose.setContent { MiconTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                Box(Modifier.requiredWidth(328.dp)) {
                    GuidedRangeKeyboard(emptyList(), target.value, mode = GuidedKeyboardMode.THREE_OCTAVES,
                        showTargetLabel = false)
                }
            }
        } }
        val keyboard = compose.onNodeWithTag("guided-range-keyboard").assertIsDisplayed()
        keyboard.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "2 옥타브, 3 옥타브, 4 옥타브"))
        val bounds = keyboard.fetchSemanticsNode().boundsInRoot
        compose.runOnIdle { target.value = 84 }
        keyboard.assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "3 옥타브, 4 옥타브, 5 옥타브"))
        assertEquals(bounds, keyboard.fetchSemanticsNode().boundsInRoot)
        keyboard.captureToImage()
    }

    @Test fun reportPianoCentersFourOctaveLabelsAndKeepsThickBoundaries() {
        compose.setContent { MiconTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, 1.6f)) {
                Box(Modifier.requiredWidth(328.dp)) {
                    PianoRangeChart(48, 72, lowMidi = 36, highMidi = 84, showOctaves = true)
                }
            }
        } }
        val piano = compose.onNode(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "2 옥타브, 3 옥타브, 4 옥타브, 5 옥타브")).assertIsDisplayed()
        val bitmap = piano.captureToImage().toPixelMap()
        checkColor(Ink, bitmap[(bitmap.width * 7f / 29).roundToInt(), (bitmap.height * .75f).roundToInt()])
        checkColor(Lavender, bitmap[(bitmap.width * 7.5f / 29).roundToInt(), (bitmap.height * .75f).roundToInt()])
    }
}
