package com.micon.app.range

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.charts.GuidedRangeKeyboard
import com.micon.app.ui.report.RangeInputVisuals
import com.micon.app.ui.report.RangeReportCards
import com.micon.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class RangeLayoutRestorationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun save(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun pitchAndHoldAppearAboveSoundInputAndLegendIsRemoved() {
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val s=RangeSession("layout-test");var t=0.0
        repeat(55) { s.onFrame(RangeFrame(t,.02,0.0,0.0,.0001));t+=.02 }
        repeat(101) { s.onFrame(RangeFrame(t,.02,ScoringMath.midiToHz(60.0),1.0,.1));t+=.02 }
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().background(Paper).padding(24.dp),verticalArrangement=Arrangement.spacedBy(18.dp)) {
                RangeInputVisuals(s.view(),.4f)
                GuidedRangeKeyboard(emptyList(),60,60.0,provisional=true)
            }
        } }
        val pitch=compose.onNodeWithTag("range-pitch-and-hold").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        val input=compose.onNodeWithTag("range-sound-input").assertIsDisplayed().fetchSemanticsNode().boundsInRoot
        assertTrue(pitch.bottom < input.top)
        compose.onNodeWithText("보라 테두리 목표",substring=true).assertDoesNotExist()
        save("guided-six-input-layout.png")
    }
    @Test fun latestReportRestoresThreeContinuousOverviewRows() {
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val m=RangeMetrics(10.0,10.0,1.0,1.0,1.2,stabilityScore=40.0,creditedMidi=52,creditedHoldSeconds=1.2)
        val notes=listOf(RangeNote(48,"not_passed",m),RangeNote(52,"passed",m),
            RangeNote(64,"passed",m.copy(creditedMidi=64)),RangeNote(68,"not_passed",m))
        val report=RangeReport("guided-range-v7",notes,52,64,listOf(listOf(52,52),listOf(64,64)),
            "completed","boundary_refined","boundary_refined","available")
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().background(GreyBg).verticalScroll(rememberScrollState()).padding(24.dp)) {
                RangeReportCards(report)
            }
        } }
        listOf("전체 음역","안정 음역","추천 음역").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        compose.onNodeWithText("미통과·불안정").assertDoesNotExist()
        save("guided-six-continuous-map.png")
    }
}
