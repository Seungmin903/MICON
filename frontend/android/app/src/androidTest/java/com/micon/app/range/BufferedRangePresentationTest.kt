package com.micon.app.range

import android.graphics.Bitmap
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.charts.BufferedPitchGauge
import com.micon.app.ui.report.RangeReportCards
import com.micon.app.ui.theme.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File

class BufferedRangePresentationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun awake() {compose.activityRule.scenario.onActivity {it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)}}
    private fun save(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun bufferedNeedleShowsFrozenReferenceAndRawZone() {
        compose.setContent {MiconTheme {
            Column(Modifier.fillMaxWidth().background(Paper).padding(24.dp)) {
                BufferedPitchGauge(61,61.65,61.0,"buffer",1.2f)
                Text("조금 흔들리고 있어요 · 유지 시간 일시 정지")
                Text("인정 유지 1.20초 · 이탈 감점 −8.0점")
            }
        }}
        compose.onNodeWithContentDescription("음정 영역 buffer · 정상 45 cents 이내 · 완충 100 cents 이내").assertIsDisplayed()
        compose.onNodeWithTag("pitch-hold-ring").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,
            "인정 유지 1.20초 · 완료한 원 1개 · 다음 원 20퍼센트"))
        compose.onNodeWithText("고정 중심 C#4 · 목표 C#4").assertIsDisplayed()
        save("guided-v5-buffer.png")
    }
    @Test fun reportShowsCreditedAndStrictTimesAndKeepsMissingNotesEmpty() {
        val m=RangeMetrics(8.0,20.0,.8,1.0,.8,continuousHoldSeconds=.8,actualMidi=60.1,creditedMidi=60,
            stabilityScore=45.0,timeScore=24.0,steadinessScore=29.0,creditedHoldSeconds=1.2,bufferSeconds=.2,
            outsideSeconds=0.0,excursionPenalty=8.0,referenceMidi=60.0)
        val report=RangeReport("guided-range-v5",(36..84).map {RangeNote(it,if(it==60)"passed" else "unmeasured",if(it==60)m else null)},
            60,60,listOf(listOf(60,60)),"completed","two_probe_failures","two_probe_failures","available")
        compose.setContent {MiconTheme {Column(Modifier.fillMaxSize().background(GreyBg).verticalScroll(rememberScrollState()).padding(24.dp)) {
            RangeReportCards(report)
        }}}
        compose.onNodeWithText("추천 음역").assertDoesNotExist()
        compose.onNodeWithText("최장 연속 유지 구간을 모델로 확인했어요.",substring=true).assertDoesNotExist()
        compose.onNodeWithTag("range-stability-chart").performScrollTo().assert(hasContentDescription("안정성 점수",substring=true))
        save("guided-v5-stability.png")
        compose.onNodeWithText("인정 유지 1.20초 · 엄격한 연속 유지 0.80초").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("완충 0.20초 · 범위 밖 0.00초 · 감점 8.0점").performScrollTo().assertIsDisplayed()
        save("guided-v5-details.png")
    }
}
