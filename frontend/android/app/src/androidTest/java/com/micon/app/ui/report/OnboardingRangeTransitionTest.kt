package com.micon.app.ui.report

import android.content.Context
import android.content.ContextWrapper
import androidx.compose.material3.Text
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.navigation.compose.*
import androidx.test.platform.app.InstrumentationRegistry
import com.micon.app.data.onboarding.*
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.*
import com.micon.app.ui.onboarding.OnboardingRangeStatus
import com.micon.app.ui.onboarding.VoiceOnboardingViewModel
import com.micon.app.ui.theme.MiconTheme
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

class OnboardingRangeTransitionTest {
    @get:Rule val compose = createComposeRule()

    @Test fun savedMeasurementNavigatesAndAnalysisSurvivesMeasurementScreenDisposal() {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(base.cacheDir, "onboarding-transition-${UUID.randomUUID()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) {
            override fun getApplicationContext(): Context = this
            override fun getFilesDir() = directory
        }
        val response = CompletableDeferred<RangeReport>()
        val requests = AtomicInteger()
        val repository = RangeRepository(context, storageDirectory = File(directory, "range"),
            serverGateway = RangeAnalysisGateway { _, _ -> requests.incrementAndGet(); response.await() })
        val submission = RangeSubmission("saved-measurement", startMidi = 60, noiseRms = .001, durationSeconds = 10.0,
            stopReason = "completed", lowerBoundaryStatus = "FOUND", upperBoundaryStatus = "FOUND",
            registerEvents = emptyList(), sampling = RangeSampling(basicCompleted = true, measuredNoteCount = 1),
            attempts = listOf(RangeAttempt("attempt", 60, "anchor", 0.0, .5, 1.0, 4.0, 1.5, 3.5, "passed",
                RangeMetrics(0.0, 0.0, 1.0, 1.0, 2.0, creditedMidi = 60, stabilityScore = 80.0, creditedHoldSeconds = 2.0))))
        repository.audio(submission.sessionId).writeBytes(ByteArray(44 + 16000 * 2 * 10))
        repository.savePending(PendingRange(submission))
        val persistence = object : VoiceOnboardingPersistence {
            override fun draft() = VoiceOnboardingDraft("isolated-draft")
            override fun write(draft: VoiceOnboardingDraft) { }
            override fun range() = repository.lastSessionReport()
            override fun recording(id: String?): SavedRecording? = null
            override fun deleteRecording(id: String?) { }
            override fun saveProfile(profile: SavedVoiceOnboardingProfile) = error("unused")
            override fun clearDraft() = repository.clear()
        }
        val models = ViewModelStore()
        lateinit var model: VoiceOnboardingViewModel
        var transitions = 0
        try {
            compose.runOnUiThread {
                model = VoiceOnboardingViewModel(1, persistence, PendingVoiceOnboardingGateway(),
                    rangeAnalysis = RepositoryOnboardingRangeAnalysis(repository))
                models.put("flow", model)
            }
            compose.setContent { MiconTheme {
                val nav = rememberNavController()
                NavHost(nav, startDestination = "measure") {
                    composable("measure") {
                        GuidedRangeScreen({}, {}, repository = repository, onMeasurementSaved = {
                            if (model.rangeMeasurementSaved(it)) {
                                transitions++
                                nav.navigate("pick") { popUpTo("measure") { inclusive = true } }
                            }
                        })
                    }
                    composable("pick") { Text("부를 곡 고르기") }
                }
            } }
            compose.onNodeWithText("부를 곡 고르기").assertIsDisplayed()
            compose.onNodeWithText("이번에 측정한 결과").assertDoesNotExist()
            assertNull(model.state.value.range)
            assertTrue(model.state.value.waitingForRange)
            compose.waitUntil(10_000) { requests.get() == 1 }
            response.complete(submission.deviceReport().copy(verification = "model_verified", backendSessionId = 7))
            compose.waitUntil(10_000) { model.state.value.rangeAnalysisStatus == OnboardingRangeStatus.READY }
            compose.onNodeWithText("부를 곡 고르기").assertIsDisplayed()
            assertEquals(1, transitions)
            assertEquals(1, requests.get())
            assertEquals("model_verified", repository.localReport()!!.verification)
            assertEquals(7L, model.state.value.range!!.backendSessionId)
        } finally {
            compose.runOnUiThread { models.clear() }
            directory.deleteRecursively()
        }
    }
}
