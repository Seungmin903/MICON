package com.micon.app.range

import android.graphics.Bitmap
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.report.RangeStabilityCard
import com.micon.app.ui.theme.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/** UI fixtures only: never writes a measurement report or starts the microphone/model. */
@RunWith(AndroidJUnit4::class)
class RangeStabilityChartTest {
    @get:Rule val compose = createComposeRule()

    private fun report(notes: List<RangeNote>) = RangeReport("guided-range-v3", notes, 52, 67,
        emptyList(), "user_stopped", "user_stopped", "user_stopped", "available")

    @Test fun chartSelectionShowsRealValuesAndKeepsUncertainNotesEmpty() {
        val notes = (48..72).map { midi ->
            val ratio = (1.0 - kotlin.math.abs(midi - 60) * .065).coerceAtLeast(.12)
            val status = when (midi) {
                57 -> "unmeasured"
                66 -> "needs_review"
                in 59..61 -> "passed"
                else -> "not_passed"
            }
            RangeNote(midi, status, if (status == "unmeasured") null else RangeMetrics(12.0, 8.0, ratio, 1.0, ratio * 2))
        }
        compose.setContent {
            MiconTheme {
                Column(Modifier.fillMaxSize().background(GreyBg).verticalScroll(rememberScrollState()).padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("프로토타입 · 그래프 표시 예시")
                    RangeStabilityCard(report(notes))
                }
            }
        }
        compose.onNodeWithTag("range-stability-chart").assertIsDisplayed()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val density = context.resources.displayMetrics.density
        fun select(midi: Int) = compose.onNodeWithTag("range-stability-chart").performTouchInput {
            click(Offset(44 * density + (center.x * 2 - 60 * density) * (midi - 47) / 26, center.y))
        }
        select(60)
        compose.onNodeWithText("4옥타브 도 · 통과").assertIsDisplayed()
        compose.onNodeWithText("목표음 유지율 100%").assertIsDisplayed()
        val screenshot = File(context.getExternalFilesDir(null), "range-stability-sample.png")
        screenshot.outputStream().use { compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it) }
        select(57)
        compose.onNodeWithText("3옥타브 라 · 미측정").assertIsDisplayed()
        compose.onNodeWithText("미측정").assertIsDisplayed()
        compose.onAllNodesWithText("목표음 유지율 100%").assertCountEquals(0)
        select(66)
        compose.onNodeWithText("4옥타브 파# · 재측정 필요").assertIsDisplayed()
        compose.onNodeWithText("재측정 필요").assertIsDisplayed()
        compose.onAllNodesWithText("목표음 유지율 100%").assertCountEquals(0)
        listOf("음별 지도 펼치기", "음별 지도 접기", "전체 C2~C6 보기", "측정 주변만 보기",
            "✓ 통과  ·  ◎ 반복 확인  ·  △ 미통과/재측정  ·  – 미측정").forEach {
            compose.onNodeWithText(it).assertDoesNotExist()
        }
        compose.onNodeWithTag("range-stability-chart").performScrollTo().performTouchInput { click(Offset(44 * density, center.y)) }
        compose.onNodeWithText("2옥타브 시 · 미측정").assertIsDisplayed()
    }

    @Test fun noEvidenceShowsEmptyGraph() {
        compose.setContent { MiconTheme { RangeStabilityCard(report(emptyList()).copy(lowMidi = null, highMidi = null)) } }
        compose.onNodeWithTag("range-stability-chart").assertIsDisplayed()
        compose.onNodeWithText("미측정").assertIsDisplayed()
    }
}
