package com.micon.app.range

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.charts.GuidedPitchGauge
import com.micon.app.ui.charts.PitchHoldRing
import com.micon.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import kotlin.math.roundToInt

class PitchHoldRingTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Before fun keepTestWindowAwake() {
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun eachSecondStartsANewColorAndInterruptionsResetTheCircle() {
        val seconds = mutableStateOf(0f)
        compose.setContent { MiconTheme {
            Box(Modifier.background(Paper).padding(24.dp)) { PitchHoldRing("C♯4", seconds.value) }
        } }
        val ring = compose.onNodeWithTag("pitch-hold-ring")
        fun check(time: Float, rightColor: Color, leftColor: Color) {
            compose.runOnIdle { seconds.value = time }
            val bitmap = ring.captureToImage().asAndroidBitmap()
            val strokeCenter = (bitmap.width * 3f / 64).roundToInt()
            assertEquals("right arc at $time", rightColor.toArgb(), bitmap.getPixel(bitmap.width - strokeCenter, bitmap.height / 2))
            assertEquals("left arc at $time", leftColor.toArgb(), bitmap.getPixel(strokeCenter, bitmap.height / 2))
        }
        check(0f, Lavender, Lavender)
        check(.5f, Purple, Lavender)
        check(1f, Purple, Purple)
        check(1.5f, Teal, Purple)
        check(2f, Teal, Teal)
        check(2.5f, Orange, Teal)
        check(0f, Lavender, Lavender)
    }

    @Test fun everySupportedNoteStaysCenteredOnOneLineAtLargerFontSizes() {
        val target = mutableStateOf(60)
        val fontScale = mutableStateOf(1f)
        compose.setContent { MiconTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, fontScale.value)) {
                Box(Modifier.width(280.dp).background(Paper).padding(24.dp)) {
                    GuidedPitchGauge(ScoringMath.midiName(target.value), (target.value-2..target.value+2).map(ScoringMath::midiName),
                        0f, true, 1.5f, gaugeHeight=120.dp, ringSize=64.dp)
                }
            }
        } }
        val ring = compose.onNodeWithTag("pitch-hold-ring")
        val label = compose.onNodeWithTag("pitch-target-note", useUnmergedTree=true)
        val initial = ring.fetchSemanticsNode().boundsInRoot
        for (scale in listOf(1f, 1.6f)) {
            compose.runOnIdle { fontScale.value = scale }
            for (midi in 36..84) {
                compose.runOnIdle { target.value = midi }
                label.assertTextEquals(ScoringMath.midiName(midi))
                val layouts = mutableListOf<TextLayoutResult>()
                label.performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(layouts) }
                assertEquals(1, layouts.single().lineCount)
                val layout=layouts.single()
                if (layout.hasVisualOverflow) save("guided-note-overflow.png")
                assertFalse("${ScoringMath.midiName(midi)} scale=$scale size=${layout.size} widthOverflow=${layout.didOverflowWidth} heightOverflow=${layout.didOverflowHeight} style=${layout.layoutInput.style}", layout.hasVisualOverflow)
                val bounds = label.fetchSemanticsNode().boundsInRoot
                assertEquals(initial, ring.fetchSemanticsNode().boundsInRoot)
                assertEquals(initial.center.x, bounds.center.x, 1f)
                assertEquals(initial.center.y, bounds.center.y, 1f)
                assertTrue(bounds.width <= initial.width * .65f)
                assertTrue(bounds.height <= initial.height * .65f)
            }
        }
        compose.runOnIdle { target.value = 61 }
        save("guided-sharp-note-gauge.png")
    }

    @Test fun renderOneSecondCircleCycles() {
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxWidth().background(Paper).padding(24.dp), verticalArrangement=Arrangement.spacedBy(24.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement=Arrangement.SpaceEvenly) {
                    listOf(.5f,1.5f,2.5f).forEach { seconds ->
                        Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                            Text("${seconds}초")
                            PitchHoldRing("C♯4",seconds)
                        }
                    }
                }
                GuidedPitchGauge("C♯4", (59..63).map(ScoringMath::midiName), 0f, true, 1.5f, gaugeHeight=120.dp, ringSize=64.dp)
            }
        } }
        compose.onNodeWithText("1.5초").assertIsDisplayed()
        save("guided-one-second-circles.png")
    }
}
