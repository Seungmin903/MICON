package com.micon.app.range

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.report.RangeAdvanceButton
import com.micon.app.ui.report.RangePassGuide
import com.micon.app.ui.report.RangeMeasurementActions
import com.micon.app.ui.report.rangeStageLabel
import com.micon.app.ui.charts.GuidedRangeKeyboard
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class AdaptiveRangePresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Test fun actualControlsResetAnchorThenShowCoarseAndMidpointSearch() {
        compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
        val session=RangeSession("ui-v6");var t=0.0
        fun voice(n:Int,midi:Double?=null) { repeat(n) {
            session.onFrame(RangeFrame(t,.02,midi?.let(ScoringMath::midiToHz) ?: 0.0,
                if(midi==null)0.0 else 1.0,if(midi==null).0001 else .1));t+=.02
        }}
        voice(55);voice(101,60.0)
        val view=mutableStateOf(session.view())
        compose.setContent { MiconTheme { Column(Modifier.fillMaxWidth().padding(24.dp)) {
            Text(rangeStageLabel(view.value))
            GuidedRangeKeyboard(RangeKeyboardState.notes(emptyList(),view.value.attempts),view.value.targetMidi,null,provisional=true)
            Text("목표 ${view.value.targetMidi?.let(ScoringMath::midiName) ?: "찾는 중"}")
            RangePassGuide(view.value)
            RangeAdvanceButton(view.value,{visit -> session.next(visit,t);view.value=session.view()},Modifier.fillMaxWidth())
        }}}
        compose.onNodeWithText("시작음 다시 찾기").assertIsDisplayed().performClick()
        compose.onNodeWithText("준비").assertIsDisplayed()
        compose.onNodeWithText("이 음 패스하기").assertDoesNotExist()
        compose.onNodeWithTag("range-pass-guide").assertDoesNotExist()
        compose.runOnIdle {
            voice(114,60.0);session.cueFinished(session.view().cueId,t);voice(14);voice(70,60.0);voice(6)
            view.value=session.view()
        }
        compose.onNodeWithText("6반음씩 고음 탐색").assertIsDisplayed()
        compose.onNodeWithText("목표 F♯4").assertIsDisplayed()
        compose.onNodeWithTag("range-pass-guide").assertIsDisplayed()
            .assertTextEquals("소리 내기 어려운 음은 패스할 수 있어요.")
        compose.runOnIdle { voice(30) }
        compose.onNodeWithText("이 음 패스하기").performClick()
        compose.onNodeWithText("고음 사이 음 확인").assertIsDisplayed()
        compose.onNodeWithText("목표 D♯4").assertIsDisplayed()
        assertEquals("unmeasured",session.view().attempts.last().clientStatus)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),"guided-v6-midpoint.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }

    @Test fun passActionsFitSmallScreenWithLargeFontAndForwardTheCurrentVisit() {
        val session = mutableStateOf(RangeSession("pass-ui").view().copy(phase = "up", targetMidi = 66, visitId = "current-visit"))
        var passedVisit: String? = null
        compose.setContent { MiconTheme {
            val density = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density, 1.6f)) {
                Column(Modifier.width(320.dp).padding(24.dp)) {
                    RangePassGuide(session.value)
                    RangeMeasurementActions(session.value, {}, { passedVisit = it })
                }
            }
        } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "range-pass-large-font.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        listOf("이 음 패스하기").forEach { label ->
            compose.onNodeWithText(label).assertIsDisplayed()
                .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { action ->
                    val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                    assertTrue(action(layouts))
                    val layout = layouts.single()
                    assertFalse("$label is clipped vertically", layout.didOverflowHeight)
                    for (line in 0 until layout.lineCount) {
                        val inkWidth = layout.getLineRight(line) - layout.getLineLeft(line)
                        assertTrue("$label line $line is wider than its display area: $inkWidth > ${layout.size.width}",
                            inkWidth <= layout.size.width + 1f)
                        assertTrue("$label line $line extends below its display area",
                            layout.getLineBottom(line) <= layout.size.height + 1f)
                    }
                }
        }
        compose.onNodeWithTag("range-pass-guide").assertIsDisplayed()
        compose.onNodeWithText("여기까지 측정하기").assertDoesNotExist()
        compose.onNodeWithTag("range-action-divider").assertDoesNotExist()
        compose.onNodeWithText("이 음 패스하기").performClick()
        compose.runOnIdle { assertEquals("current-visit", passedVisit) }
        compose.runOnIdle {
            session.value = session.value.copy(
                sampling = session.value.sampling.copy(basicCompleted = true, measuredNoteCount = 3))
        }
        compose.onNodeWithText("여기까지 측정하기").assertIsEnabled()
        compose.onNodeWithTag("range-action-divider").assertIsDisplayed()
    }
}
