package com.micon.app.range

import android.graphics.Bitmap
import android.content.Context
import android.content.ContextWrapper
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.report.*
import com.micon.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID

class RangeCopyCleanupTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun awake() { compose.activityRule.scenario.onActivity { it.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) } }
    private fun save(name:String) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null),name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG,100,it)
        }
    }
    @Test fun entryContainsStartWithoutExampleOrDeveloperGuide() {
        // A phone may already contain the user's unfinished recording. Do not discard it for an entry test.
        val base = compose.activity
        val isolated = object : ContextWrapper(base) {
            private val folder = File(base.cacheDir, "range-entry-test-${UUID.randomUUID()}").apply { mkdirs() }
            override fun getFilesDir(): File = folder
            override fun getApplicationContext(): Context = this
        }
        compose.setContent { CompositionLocalProvider(LocalContext provides isolated) {
            MiconTheme { GuidedRangeScreen(onBack={},onDone={}) }
        } }
        compose.onNodeWithText("음역 안정성 측정").assertIsDisplayed()
        compose.onNodeWithText("측정 시작").assertIsDisplayed()
        listOf("프로토타입","로그인 없이","예시 기준음","소리 확인용","1. 조용한","가성도 통과").forEach {
            compose.onNodeWithText(it,substring=true).assertDoesNotExist()
        }
        save("guided-clean-entry.png")
    }
    @Test fun activePanelKeepsReplayAndDisplaysNoTrailingInstructionsAtBothFontSizes() {
        val state=mutableStateOf(RangeSession("clean-ui").view().copy(phase="anchor",targetMidi=61,currentMidi=61.4,
            referenceMidi=61.0,pitchZone="buffer",holdProgress=.48f,waitingForCue=false,canResetAnchor=true))
        val scale=mutableStateOf(1f)
        var replays=0
        compose.setContent { MiconTheme {
            val density=LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(density.density,scale.value)) {
                Column(Modifier.fillMaxSize().background(Paper).verticalScroll(rememberScrollState()).padding(24.dp)) {
                    RangeMeasurementPanel(state.value,.4f,emptyList(),false,{replays++})
                }
            }
        } }
        for(fontScale in listOf(1f,1.6f)) {
            compose.runOnIdle { scale.value=fontScale }
            compose.onNodeWithTag("range-hold-feedback").assertTextEquals("현재 음을 유지해주세요")
            compose.onNodeWithText("C♯4 듣기").performScrollTo().assertIsDisplayed().performClick()
            listOf("현재 음:","지금 인정 유지","현재 합산","최장 인정","이탈 감점","고정 중심", "정상 ±45",
                "가성으로 전환했어요","측정 중 판정은","빈 건반은","지금 높이를","고음 탐색","경과",
                "지금 부를 음", "초록 건반은").forEach {
                compose.onNodeWithText(it,substring=true).assertDoesNotExist()
            }
            compose.onNodeWithTag("guided-range-keyboard")
                .assert(hasContentDescription("3옥타브 도부터 6옥타브 도까지 세 옥타브 건반", substring = true))
            val gauge=compose.onNodeWithTag("range-pitch-and-hold").fetchSemanticsNode().boundsInRoot
            val keyboard=compose.onNodeWithTag("guided-range-keyboard").fetchSemanticsNode().boundsInRoot
            val input=compose.onNodeWithTag("range-sound-input").fetchSemanticsNode().boundsInRoot
            assertTrue(gauge.bottom<keyboard.top)
            val feedback=compose.onNodeWithTag("range-hold-feedback").fetchSemanticsNode().boundsInRoot
            assertTrue(input.bottom <= feedback.top)
            assertTrue(feedback.bottom < keyboard.top)
            save("guided-clean-active-${fontScale}.png")
        }
        assertEquals(2,replays)
        compose.runOnIdle { state.value=state.value.copy(metrics=RangeMetrics(0.0,0.0,1.0,1.0,1.0,
            creditedMidi=61,stabilityScore=40.0,creditedHoldSeconds=1.0)) }
        compose.onNodeWithTag("range-hold-feedback").assertTextEquals("통과")
        compose.runOnIdle { state.value=state.value.copy(waitingForCue=true) }
        compose.onNodeWithTag("range-hold-feedback").assertTextEquals("")
        compose.onNodeWithText("C♯4 재생 중…").performScrollTo().assertIsNotEnabled()
    }
    @Test fun reportsKeepMetricsAndCardsWithoutExplanatoryCopy() {
        val m=RangeMetrics(10.0,13.4,.8,1.0,.58,continuousHoldSeconds=.58,actualMidi=51.9,creditedMidi=52,
            stabilityScore=22.5,creditedHoldSeconds=1.42,bufferSeconds=.32,outsideSeconds=0.0,
            excursionPenalty=12.8,referenceRmsCents=34.0)
        val report=RangeReport("guided-range-v7",listOf(RangeNote(52,"passed",m),RangeNote(64,"passed",m.copy(creditedMidi=64))),
            52,64,listOf(listOf(52,52),listOf(64,64)),"completed","boundary_refined","boundary_refined","available",verification="model_verified")
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().background(GreyBg).verticalScroll(rememberScrollState()).padding(24.dp)) { RangeReportCards(report) }
        } }
        listOf("전체 음역","안정 음역","추천 음역").forEach { compose.onNodeWithText(it).assertIsDisplayed() }
        listOf("음역 추출 모델 분석 결과","점을 눌러","이번 음역 테스트에서는","곡 분석과 추천 결과가","최장 연속 유지 구간을 모델로").forEach {
            compose.onNodeWithText(it,substring=true).assertDoesNotExist()
        }
        save("guided-clean-report.png")
        compose.onNodeWithText("안정성 22.5점 / 100점").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("인정 유지 1.42초 · 엄격한 연속 유지 0.58초").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("시작 높이 대비 흔들림 34.0 cents").performScrollTo().assertIsDisplayed()
        listOf("내 목소리에 맞는 음악","프레이즈 길이 · 미측정","곡 적합도","종합 보컬 특성").forEach {
            compose.onNodeWithText(it).performScrollTo().assertIsDisplayed()
        }
    }
}
