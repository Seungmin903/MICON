package com.micon.app.range

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.charts.GuidedRangeKeyboard
import com.micon.app.ui.report.RangeReportCards
import com.micon.app.ui.theme.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class GuidedRangeReportTest {
    @get:Rule val compose = createComposeRule()
    private val notes = (36..84).map { midi ->
        val status = when (midi) { 48, 50, 60, 61 -> "passed"; 62 -> "reconfirmed"; 64 -> "not_passed"; 66 -> "needs_review"; else -> "unmeasured" }
        RangeNote(midi, status, if (status == "unmeasured") null else RangeMetrics(12.0, 8.0, if (midi == 64) .4 else .96, 1.0, 1.8))
    }
    private fun save(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun keyboardNeverMovesOrChangesTargetOnTap() {
        val target = mutableStateOf(60)
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxWidth().background(Paper).padding(24.dp)) {
                GuidedRangeKeyboard(notes, target.value, 60.04, provisional = true)
            }
        } }
        val keyboard = compose.onNodeWithTag("guided-range-keyboard")
        keyboard.assertContentDescriptionEquals("C2부터 C6까지 고정 건반, 7 개 음 확인, 5 개 음 통과, 목표 C4")
        val bounds = keyboard.fetchSemanticsNode().boundsInRoot
        keyboard.performTouchInput { click(topLeft + androidx.compose.ui.geometry.Offset(10f, 60f)) }
        assertEquals(60, target.value)
        save("guided-keyboard-sample.png")
        compose.runOnIdle { target.value = 84 }
        keyboard.assertContentDescriptionEquals("C2부터 C6까지 고정 건반, 7 개 음 확인, 5 개 음 통과, 목표 C6")
        assertEquals(bounds, keyboard.fetchSemanticsNode().boundsInRoot)
    }

    @Test fun sharedReportKeepsDevelopCardsAndUnmeasuredPhraseGraphic() {
        val report = RangeReport("guided-range-v3", notes, 48, 62, listOf(listOf(48,48), listOf(50,50), listOf(60,62)),
            "user_stopped", "user_stopped", "user_stopped", "available", verification = "model_verified")
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().background(GreyBg).verticalScroll(rememberScrollState()).padding(24.dp)) {
                RangeReportCards(report)
            }
        } }
        compose.onNodeWithText("이번에 확인한 음역").assertIsDisplayed()
        compose.onNodeWithText("저음:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("고음:", substring = true).assertDoesNotExist()
        compose.onNodeWithText("음역대 지도").assertIsDisplayed()
        save("guided-report-top.png")
        compose.onNodeWithTag("guided-range-keyboard").performScrollTo().assertIsDisplayed()
        save("guided-report-keyboard.png")
        compose.onNodeWithContentDescription("프레이즈 길이 미측정, 7칸 비어 있음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("프레이즈 길이 · 미측정").assertIsDisplayed()
        save("guided-report-phrase.png")
        compose.onNodeWithText("종합 보컬 특성").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("미측정 · 종합 특성 점수는 아직 계산하지 않았어요.").assertDoesNotExist()
    }

    @Test fun v4ReportShowsContinuousMapsAndCompositeScores() {
        val m=RangeMetrics(5.0,8.0,1.0,1.0,2.5,0.0,2.5,60.35,60,91.1,50.0,41.1)
        val measured=listOf(RangeNote(60,"passed",m),RangeNote(62,"not_passed",m.copy(continuousHoldSeconds=.8,stabilityScore=50.0)),
            RangeNote(68,"passed",m.copy(actualMidi=68.2,creditedMidi=68)))
        val report=RangeReport("guided-range-v4",measured,60,68,listOf(listOf(60,68)),"completed",
            "two_probe_failures","two_probe_failures","available",rangeMap=RangeMap(listOf(58,69),listOf(60,68),listOf(62,66)))
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().background(GreyBg).verticalScroll(rememberScrollState()).padding(24.dp)) { RangeReportCards(report) }
        } }
        compose.onNodeWithText("전체 음역").assertIsDisplayed()
        compose.onNodeWithText("이 사이의 모든 음", substring = true).assertDoesNotExist()
        compose.onNodeWithText("최장 연속 유지 구간을 모델로 확인했어요.", substring = true).assertDoesNotExist()
        listOf("저음:", "고음:", "미도달", "전체는 잠깐이라도", "영역 안의 모든 음", "시간 + 흔들림 합산 점수", "시간 50.0점", "흔들림 점수").forEach {
            compose.onNodeWithText(it, substring = true).assertDoesNotExist()
        }
        compose.onNodeWithText("안정 음역").assertIsDisplayed()
        compose.onNodeWithText("추천 음역").assertIsDisplayed()
        save("guided-v4-range-map.png")
        compose.onNodeWithTag("range-stability-chart").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("range-stability-chart").assert(hasContentDescription("안정성 점수", substring = true))
        save("guided-v4-stability-score.png")
        compose.onNodeWithText("안정성 91.1점 / 100점").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("최장 연속 유지 2.50초").performScrollTo().assertIsDisplayed()
        save("guided-v4-score-details.png")
    }
}
