package com.micon.app.ui.report

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.onboarding.*
import com.micon.app.range.*
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class StagedVoiceReportScreenTest {
    @get:Rule val compose = createComposeRule()
    private val range = RangeReport(RangeProtocol.VERSION, listOf(
        RangeNote(48, "passed", RangeMetrics(5.0, 8.0, 1.0, 1.0, 2.0, stabilityScore = 90.0)),
        RangeNote(72, "passed", RangeMetrics(8.0, 9.0, 1.0, 1.0, 1.8, stabilityScore = 80.0))),
        48, 72, emptyList(), "completed", "FOUND", "FOUND", "available", backendSessionId = 7)
    private val complete = VoiceAnalysisProgress("7", 3, ecapa = ComponentStatus.COMPLETED,
        byol = ComponentStatus.COMPLETED, artistSearch = ComponentStatus.COMPLETED,
        artists = listOf(SimilarVoiceArtist(1, "테스트 가수", null, .9)),
        rangeStatus = "AVAILABLE", sessionStatus = VoiceSessionStatus.READY_TO_SAVE)

    private fun show(content: @Composable () -> Unit) {
        compose.setContent { MiconTheme {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)) { content() }
        } }
    }

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun rangeOnlyShowsThreeCardsAndInvitationAndContinuesOnTap() {
        var continued = false
        show { RangeMeasurementReport(range, { continued = true }) }
        compose.onNodeWithText("이번에 확인한 음역").assertIsDisplayed()
        compose.onNodeWithText("음역대 지도").assertExists()
        compose.onNodeWithText("음역별 안정성").assertExists()
        compose.onNodeWithText("내 목소리에 맞는 음악").assertDoesNotExist()
        compose.onNodeWithText("곡 적합도").assertDoesNotExist()
        compose.onNodeWithText("종합 보컬 특성").assertDoesNotExist()
        compose.onNodeWithText("음색 측정하기").performScrollTo().assertIsEnabled()
        compose.onNodeWithText("음색 측정을 통해 더 많은 결과 확인하기").assertIsDisplayed()
        compose.onNodeWithTag("timbre_measurement_invitation").performScrollTo()
        capture("staged-range-invitation.png")
        compose.onNodeWithText("음색 측정하기").performClick()
        compose.runOnIdle { assertTrue(continued) }
    }

    @Test fun insufficientRangeCannotContinueToTimbre() {
        show { RangeMeasurementReport(range.copy(availability = "insufficient_data"), {}) }
        compose.onNodeWithText("음색 측정하기").performScrollTo().assertIsNotEnabled()
    }

    @Test fun pendingAndFailureKeepRangeWithoutRevealingAdditionalCards() {
        var progress by mutableStateOf(complete.copy(byol = ComponentStatus.RUNNING))
        var retried: AnalysisComponent? = null
        show { VoiceReportContent(range, progress, retryEnabled = true, onRetry = { retried = it }) }
        compose.onNodeWithText("분석 중").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("나와 닮은 가수").assertDoesNotExist()
        compose.onNodeWithText("내 목소리에 맞는 음악").assertDoesNotExist()
        compose.runOnIdle { progress = progress.copy(byol = ComponentStatus.FAILED) }
        compose.onNodeWithText("다시 시도").performScrollTo().performClick()
        compose.runOnIdle { assertEquals(AnalysisComponent.SINGER_BYOL, retried) }
        compose.runOnIdle { progress = progress.copy(byol = ComponentStatus.INSUFFICIENT_DATA) }
        compose.onNodeWithText("목소리 부족 · 다시 녹음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("종합 보컬 특성").assertDoesNotExist()
    }

    @Test fun completionRevealsCardsAndSavedStateKeepsThem() {
        var progress by mutableStateOf(complete.copy(byol = ComponentStatus.RUNNING))
        show { VoiceReportContent(range, progress,
            songFit = if (progress.sessionStatus == VoiceSessionStatus.SAVED) { { Text("저장 프로필 곡 적합도") } } else null) }
        compose.onNodeWithText("내 목소리에 맞는 음악").assertDoesNotExist()
        compose.runOnIdle { progress = complete }
        compose.onNodeWithText("테스트 가수").performScrollTo().assertIsDisplayed()
        capture("staged-timbre-result.png")
        compose.onNodeWithText("내 목소리에 맞는 음악").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("적정 템포 / 가사 밀도 · 미측정").assertExists()
        compose.onNodeWithText("종합 보컬 특성").performScrollTo().assertIsDisplayed()
        compose.runOnIdle { progress = complete.copy(sessionStatus = VoiceSessionStatus.SAVED, saveAllowed = false) }
        compose.onNodeWithText("저장 프로필 곡 적합도").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("테스트 가수").assertExists()
        compose.onNodeWithText("음색 측정하기").assertDoesNotExist()
    }

    @Test fun artistLookupIsIndependentAndEmptyResultIsExplicit() {
        var progress by mutableStateOf(complete.copy(artistSearch = ComponentStatus.RUNNING, artists = emptyList()))
        show { VoiceReportContent(range, progress) }
        compose.onNodeWithText("가수 찾기").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("내 목소리에 맞는 음악").assertExists()
        compose.runOnIdle { progress = progress.copy(artistSearch = ComponentStatus.COMPLETED) }
        compose.onNodeWithText("지금은 닮은 가수를 찾지 못했어요.").performScrollTo().assertIsDisplayed()
    }

    @Test fun aPreviousSessionsTimbreNeverAppearsUnderANewRange() {
        show { VoiceReportContent(range.copy(backendSessionId = 8), complete) }
        compose.onNodeWithText("C3 ~ C5").assertIsDisplayed()
        compose.onNodeWithText("테스트 가수").assertDoesNotExist()
        compose.onNodeWithText("음색 분석 결과").assertDoesNotExist()
        compose.onNodeWithText("내 목소리에 맞는 음악").assertDoesNotExist()
    }
}
