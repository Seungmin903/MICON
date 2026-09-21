package com.micon.app.ui.onboarding

import android.graphics.Bitmap
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.onboarding.*
import com.micon.app.data.singanalysis.*
import com.micon.app.range.*
import com.micon.app.ui.theme.MiconTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.RandomAccessFile

@RunWith(AndroidJUnit4::class)
class VoiceOnboardingScreenTest {
    @get:Rule val compose = createComposeRule()
    private fun range() = RangeReport(RangeProtocol.VERSION,
        (36..84).map { midi -> RangeNote(midi, if (midi in listOf(48, 60, 72)) "passed" else "unmeasured",
            if (midi in listOf(48, 60, 72)) RangeMetrics(5.0, 8.0, 1.0, 1.0, 2.5, stabilityScore = 90.0) else null) },
        48, 72, emptyList(), "completed", "measured", "measured", "available", verification = "device_measured")

    private fun audio() = SavedRecording(SingRecordingMeta(
        id = "recording", songId = "1", title = "Song", artist = "Artist", scope = "FULL",
        planSource = "SONG_START", plannedStartSec = 0.0, plannedEndSec = 100.0,
        sungStartSec = 0.0, sungEndSec = 35.0, endedEarly = true, recordedAt = 0,
        sampleRate = 16000, durationSec = 35.0, lyricTiming = "SYNCED", wavFileName = "recording.wav"),
        File("recording.wav"), File("recording.json"))

    private fun capture(name: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.getExternalFilesDir(null), name).outputStream().use {
            compose.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun partialMemberResultShowsMeasuredRangeAndDisablesSave() {
        compose.setContent { MiconTheme {
            VoiceOnboardingResultScreen(VoiceOnboardingState("session", range = range(), recording = audio()), false,
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("4C(도) ~ 6C(도)").assertExists()
        compose.onNodeWithTag("onboarding_service_notice").assertTextContains("준비 중", substring = true)
        compose.onNodeWithText("분석 다시 확인하기").assertDoesNotExist()
        capture("onboarding-partial-top.png")
        compose.onNodeWithTag("onboarding_save").performScrollTo().assertIsNotEnabled()
        capture("onboarding-partial-save.png")
    }

    @Test fun completeMemberResultEnablesExplicitSave() {
        var saved = false
        val progress = VoiceAnalysisProgress("session", 1, ComponentStatus.COMPLETED,
            ComponentStatus.COMPLETED, ComponentStatus.FAILED, saveAllowed = true,
            rangeStatus = "AVAILABLE", sessionStatus = VoiceSessionStatus.READY_TO_SAVE)
        compose.setContent { MiconTheme {
            VoiceOnboardingResultScreen(VoiceOnboardingState("session", range = range(),
                recording = audio(), progress = progress, serviceAvailable = true), false, {}, {}, {}, {}, {}, { saved = true }, {}, {})
        } }
        compose.onNodeWithTag("onboarding_save").performScrollTo().assertIsEnabled().performClick()
        compose.runOnIdle { assertTrue(saved) }
    }

    @Test fun guestSeesLoginInsteadOfSaveAndDiscardNotice() {
        compose.setContent { MiconTheme {
            VoiceOnboardingResultScreen(VoiceOnboardingState("session", range = range(), recording = audio()), true,
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("onboarding_save").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_login").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("비회원 결과는 이 화면을 나가면 지워져요.", substring = true).assertIsDisplayed()
        capture("onboarding-guest-result.png")
    }

    @Test fun normalOnboardingStartsWithDeviceRangeAndExitConfirmation() {
        var exited = false
        val instrument = InstrumentationRegistry.getInstrumentation()
        instrument.uiAutomation.grantRuntimePermission(instrument.targetContext.packageName,
            android.Manifest.permission.RECORD_AUDIO)
        compose.setContent { MiconTheme {
            VoiceOnboardingFlow(true, { exited = true }, {})
        } }
        compose.mainClock.advanceTimeBy(2_000)
        compose.onNodeWithTag("onboarding-range-intro").assertIsDisplayed()
        compose.onNodeWithText("측정이 시작되면,").assertIsDisplayed()
        compose.onNodeWithText("편한 목소리로 '아'를 내주세요").assertIsDisplayed()
        compose.onNodeWithTag("guided-range-keyboard").assertDoesNotExist()
        compose.onNodeWithText("간편 분석하기").assertDoesNotExist()
        compose.onNodeWithContentDescription("뒤로").performClick()
        compose.onNodeWithText("비회원 분석 결과와 녹음은 나가면 지워져 다시 볼 수 없어요.").assertIsDisplayed()
        compose.onNodeWithText("나가기").performClick()
        compose.runOnIdle { assertTrue(exited) }
    }

    @Test fun queuedStatusAndRawProgressStayHiddenWhileInsufficientDataIsActionable() {
        val progress = VoiceAnalysisProgress("server", 12, ecapa = ComponentStatus.QUEUED,
            byol = ComponentStatus.INSUFFICIENT_DATA, sessionStatus = VoiceSessionStatus.PARTIAL_READY,
            message = "접수 준비 중 · 테스트 진행 메시지")
        compose.setContent { MiconTheme {
            VoiceOnboardingResultScreen(VoiceOnboardingState("draft", range = range(), recording = audio(),
                progress = progress, serviceAvailable = true), false, {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithText("순서 대기 중").assertDoesNotExist()
        compose.onNodeWithText("접수 준비 중", substring = true).assertDoesNotExist()
        compose.onNodeWithText("닮은 목소리 분석").assertDoesNotExist()
        compose.onNodeWithText("목소리 부족 · 다시 녹음").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("ECAPA", substring = true).assertDoesNotExist()
        compose.onNodeWithText("BYOL", substring = true).assertDoesNotExist()
        compose.onNodeWithTag("onboarding_save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun finalScreenWaitsForRangeWithoutShowingPreviewAsAResultOrInputError() {
        compose.setContent { MiconTheme {
            VoiceOnboardingResultScreen(VoiceOnboardingState("draft", rangePreview = range(), recording = audio(),
                rangeAnalysisStatus = OnboardingRangeStatus.RUNNING, serviceAvailable = true), false,
                {}, {}, {}, {}, {}, {}, {}, {})
        } }
        compose.onNodeWithTag("onboarding_analysis_wait").assertIsDisplayed()
        compose.onNodeWithText("음역 분석 결과를 확인하고 있어요.").assertIsDisplayed()
        compose.onNodeWithText("4C(도) ~ 6C(도)").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_input_error").assertDoesNotExist()
        compose.onNodeWithText("분석 다시 확인하기").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun rangeFailureOffersItsOwnRetryAndRemeasurement() {
        var retries = 0
        compose.setContent { MiconTheme {
            VoiceOnboardingResultScreen(VoiceOnboardingState("draft", rangePreview = range(), recording = audio(),
                rangeAnalysisStatus = OnboardingRangeStatus.FAILED, rangeAnalysisError = "음역 분석 연결 실패",
                serviceAvailable = true), false, {}, {}, {}, {}, {}, {}, {}, {}, onRetryRange = { retries++ })
        } }
        compose.onNodeWithTag("onboarding_range_error").assertIsDisplayed()
        compose.onNodeWithTag("onboarding_range_retry").performClick()
        compose.runOnIdle { assertEquals(1, retries) }
        compose.onNodeWithText("음역 다시 측정하기").assertIsDisplayed()
        compose.onNodeWithText("분석 다시 확인하기").assertDoesNotExist()
        compose.onNodeWithTag("onboarding_save").performScrollTo().assertIsNotEnabled()
    }

    @Test fun automaticAnalysisReactsWhenDelayedRangeBecomesReady() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val wav = File.createTempFile("ready-range-audio", ".wav", base.cacheDir)
        RandomAccessFile(wav, "rw").use { it.setLength(44L + 48000 * 2 * 35) }
        try {
            val recording = audio().copy(wav = wav, meta = audio().meta.copy(sampleRate = 48000,
                takes = listOf(SingTake(0.0, 35.0, 0.0, 35.0)), onboardingDraftId = "draft"))
            var current by mutableStateOf(VoiceOnboardingState("draft", recording = recording, serviceAvailable = true,
                rangePreview = range(), rangeAnalysisStatus = OnboardingRangeStatus.RUNNING))
            var starts = 0
            compose.setContent { OnboardingAutomaticAnalysis(current) { starts++ } }
            compose.runOnIdle { assertEquals(0, starts) }
            compose.runOnIdle { current = current.copy(range = range().copy(verification = "model_verified", backendSessionId = 7),
                rangePreview = null, rangeAnalysisStatus = OnboardingRangeStatus.READY) }
            compose.runOnIdle { assertEquals(1, starts) }
            compose.runOnIdle { current = current.copy(error = "unrelated UI update") }
            compose.runOnIdle { assertEquals(1, starts) }
        } finally { wav.delete() }
    }
}
