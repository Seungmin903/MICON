package com.micon.app.ui.singanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import android.graphics.Bitmap
import com.micon.app.data.singanalysis.PlanSource
import com.micon.app.data.singanalysis.ScopePlan
import com.micon.app.data.singanalysis.SingScope
import com.micon.app.scoring.LyricTiming
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnboardingSingSetupTest {
    @get:Rule val compose = createComposeRule()
    private val plans = SingScope.entries.map { scope ->
        ScopePlan(scope, scope.label, if (scope == SingScope.FULL) 0.0 else 55.0, 180.0,
            PlanSource.BACKEND_SECTION, scope != SingScope.HIGHLIGHT, "등록된 시작 구간이 없어요")
    }
    private val state = SingAnalysisViewModel.UiState(phase = SingAnalysisViewModel.Phase.SETUP,
        title = "긴 제목도 두 줄로 표시되는 노래 제목", artist = "가수", durationSec = 180.0,
        plans = plans, lyricTiming = LyricTiming.SYNCED)

    private fun checkCompact(fontScale: Float) {
        var chosen: ScopePlan? = null
        compose.setContent { MiconTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.requiredSize(360.dp, 640.dp).testTag("setup-page")) {
                    SetupBody(state, onChoose = { chosen = it }, onBack = {}, compact = true)
                }
            }
        } }
        val page = compose.onNodeWithTag("setup-page").fetchSemanticsNode().boundsInRoot
        plans.forEach { plan ->
            val card = compose.onNodeWithTag("scope-${plan.scope.name}").assertIsDisplayed()
            val bounds = card.fetchSemanticsNode().boundsInRoot
            assertTrue("Every option fits before scrolling", bounds.top >= page.top && bounds.bottom <= page.bottom)
            compose.onNodeWithText(plan.displayLabel).assertIsDisplayed()
        }
        val screenshot = File(InstrumentationRegistry.getInstrumentation().targetContext.getExternalFilesDir(null),
            "onboarding-setup-$fontScale.png")
        screenshot.outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("가사 싱크").assertDoesNotExist()
        compose.onNodeWithText("등록된 시작 구간이 없어요", substring = true).assertDoesNotExist()
        compose.onNodeWithText("부터 끝까지", substring = true).assertDoesNotExist()
        val disabled = compose.onNodeWithTag("scope-HIGHLIGHT").assertIsNotEnabled()
        disabled.performTouchInput { click() }
        compose.runOnIdle { assertNull(chosen) }
        compose.onNodeWithTag("scope-SECOND_HALF").performClick()
        compose.runOnIdle {
            assertEquals(plans[1], chosen)
            assertEquals(55.0, chosen!!.startSec, 0.0)
        }
        compose.onNodeWithText("부르기 전에").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("· 마이크는 목소리 분석을 위해 사용해요").performScrollTo().assertIsDisplayed()
    }

    @Test fun threeOptionsFitOnSmallScreen() = checkCompact(1f)
    @Test fun threeOptionsFitWithEnlargedFont() = checkCompact(1.6f)

    @Test fun standaloneSetupKeepsTimingAndScopeDetails() {
        compose.setContent { MiconTheme { SetupBody(state, {}, {}) } }
        compose.onNodeWithText("가사 싱크").assertExists()
        compose.onNodeWithText("0:00부터 끝까지 · 약 3:00").assertExists()
        compose.onAllNodesWithText("등록된 시작 구간이 없어요").assertCountEquals(3)
    }
}
