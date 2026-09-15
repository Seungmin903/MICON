package com.micon.app.range

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.scoring.ScoringMath
import com.micon.app.ui.report.RangeAdvanceButton
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
            RangeAdvanceButton(view.value,{visit -> session.next(visit,t);view.value=session.view()},Modifier.fillMaxWidth())
        }}}
        compose.onNodeWithText("시작음 다시 찾기").assertIsDisplayed().performClick()
        compose.onNodeWithText("준비").assertIsDisplayed()
        compose.onNodeWithText("넘어가기").assertDoesNotExist()
        compose.runOnIdle {
            voice(114,60.0);session.cueFinished(session.view().cueId,t);voice(14);voice(70,60.0);voice(6)
            view.value=session.view()
        }
        compose.onNodeWithText("6반음씩 고음 탐색").assertIsDisplayed()
        compose.onNodeWithText("목표 F♯4").assertIsDisplayed()
        compose.runOnIdle { voice(30) }
        compose.onNodeWithText("넘어가기").performClick()
        compose.onNodeWithText("고음 사이 음 확인").assertIsDisplayed()
        compose.onNodeWithText("목표 D♯4").assertIsDisplayed()
        assertEquals("unmeasured",session.view().attempts.last().clientStatus)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),"guided-v6-midpoint.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
}
