package com.micon.app.ui.singanalysis

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.ui.singanalysis.SingAnalysisViewModel.Phase
import com.micon.app.ui.theme.MiconTheme
import com.micon.app.ui.theme.Purple
import androidx.compose.ui.graphics.toArgb
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File

class OnboardingRecordingControlsScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun checkControls(fontScale: Float) {
        val state = mutableStateOf(SingAnalysisViewModel.UiState(phase = Phase.RUNNING,
            recordingValid = true, elapsedRecSec = 29.9, onboarding = true))
        var finishes = 0
        var pauses = 0
        compose.setContent { MiconTheme {
            CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale)) {
                Box(Modifier.requiredSize(360.dp, 640.dp)) {
                    ControlSheet(state.value, onTogglePause = { pauses++ }, onFinish = { finishes++ }, onExit = {})
                }
            }
        } }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "onboarding-countdown-$fontScale.png").outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        compose.onNodeWithText("1초 후에 분석을 끝낼 수 있어요").assertIsDisplayed().assertIsNotEnabled()
            .performSemanticsAction(SemanticsActions.GetTextLayoutResult) { get ->
                val layouts = mutableListOf<androidx.compose.ui.text.TextLayoutResult>()
                assertTrue(get(layouts))
                val layout = layouts.single()
                assertFalse("countdown clipped at fontScale=$fontScale: size=${layout.size}, lines=${layout.lineCount}, " +
                    "widthOverflow=${layout.didOverflowWidth}, heightOverflow=${layout.didOverflowHeight}, " +
                    "paragraphHeight=${layout.multiParagraph.height}, paragraphWidth=${layout.multiParagraph.width}, " +
                    "lineBottom=${layout.getLineBottom(layout.lineCount - 1)}",
                    layout.hasVisualOverflow)
            }
        compose.onNodeWithTag("sing-finish").performTouchInput { click() }
        compose.runOnIdle { assertEquals(0, finishes) }
        val pause = compose.onNodeWithTag("sing-pause-resume").captureToImage().asAndroidBitmap()
        assertEquals(Purple.toArgb(), pause.getPixel(pause.width / 2, pause.height / 4))
        compose.onNodeWithTag("sing-pause-resume").performClick()
        compose.runOnIdle { state.value = state.value.copy(elapsedRecSec = 30.0) }
        compose.onNodeWithText("여기까지 부르기").assertIsEnabled().performClick()
        compose.onNodeWithText("녹음을 마치고 분석 결과를 확인해 보세요").assertDoesNotExist()
        compose.onNodeWithText("일시정지하면 녹음을 마칠 수 있어요").assertDoesNotExist()
        compose.runOnIdle { assertEquals(1, finishes); assertEquals(1, pauses) }
    }
    @Test fun countdownAndPauseButtonOnSmallScreen() = checkControls(1f)
    @Test fun countdownFitsWithLargeFont() = checkControls(1.6f)

    @Test fun onboardingFinishDialogHasAlignedFullWidthActionsAndNoDiscardLink() {
        var confirms = 0
        var continues = 0
        compose.setContent { MiconTheme {
            FinishDialog(35.0, onConfirm = { confirms++ }, onContinue = { continues++ },
                onDiscard = { fail("No discard action in onboarding finish confirmation") }, onboarding = true)
        } }
        compose.onNodeWithText("정말 마치겠습니까?").assertIsDisplayed()
        compose.onNodeWithText("저장하지 않고 나가기").assertDoesNotExist()
        val finish = compose.onNodeWithText("여기까지 마치기").assertIsDisplayed()
        val keep = compose.onNodeWithText("계속 부르기").assertIsDisplayed()
        val finishButton = compose.onNode(hasText("여기까지 마치기") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        val keepButton = compose.onNode(hasText("계속 부르기") and hasClickAction()).fetchSemanticsNode().boundsInRoot
        assertEquals(finishButton.left, keepButton.left, 1f)
        assertEquals(finishButton.right, keepButton.right, 1f)
        assertEquals(finishButton.height, keepButton.height, 1f)
        assertTrue(keepButton.top > finishButton.bottom)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), "onboarding-finish-dialog.png").outputStream().use {
            compose.onNode(isDialog()).captureToImage().asAndroidBitmap().compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)
        }
        finish.performClick(); keep.performClick()
        compose.runOnIdle { assertEquals(1, confirms); assertEquals(1, continues) }
    }

    @Test fun standaloneKeepsItsPauseRequirementAndFinishDialog() {
        val state = SingAnalysisViewModel.UiState(phase = Phase.RUNNING,
            recordingValid = true, elapsedRecSec = 35.0)
        compose.setContent { MiconTheme {
            ControlSheet(state, {}, {}, {})
        } }
        compose.onNodeWithText("여기까지 마치기").assertIsNotEnabled()
        compose.onNodeWithText("일시정지하면 녹음을 마칠 수 있어요").assertIsDisplayed()
    }
}
