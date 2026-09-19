package com.micon.app.ui.singanalysis

import com.micon.app.ui.singanalysis.SingAnalysisViewModel.Phase
import org.junit.Assert.*
import org.junit.Test

class OnboardingRecordingControlsTest {
    private val recording = SingAnalysisViewModel.UiState(phase = Phase.RUNNING,
        onboarding = true, recordingValid = true)

    @Test fun countdownUsesCeilingAndDoesNotEnableFinishingEarly() {
        for ((elapsed, remaining) in listOf(0.0 to 30, 1.01 to 29, 29.0 to 1, 29.99 to 1)) {
            val state = recording.copy(elapsedRecSec = elapsed)
            assertEquals("${remaining}초 후에 분석을 끝낼 수 있어요", singFinishLabel(state))
            assertFalse(state.canFinish)
        }
        val ready = recording.copy(elapsedRecSec = 30.0)
        assertEquals("여기까지 부르기", singFinishLabel(ready))
        assertTrue(ready.canFinish)
    }

    @Test fun onlyValidRecordingAndPausedPhasesCanRequestFinish() {
        for (phase in Phase.entries) {
            assertEquals(phase == Phase.RUNNING || phase == Phase.PAUSED,
                recording.copy(phase = phase, elapsedRecSec = 30.0).canFinish)
            assertFalse(recording.copy(phase = phase, elapsedRecSec = 35.0, recordingValid = false).canFinish)
        }
    }

    @Test fun pausedAndBufferingUiKeepsTheCreditedCountdown() {
        for (state in listOf(recording, recording.copy(phase = Phase.PAUSED), recording.copy(buffering = true))) {
            assertEquals("18초 후에 분석을 끝낼 수 있어요", singFinishLabel(state.copy(elapsedRecSec = 12.4)))
        }
    }

    @Test fun standaloneStillRequiresPauseAndKeepsItsButtonLabel() {
        val standalone = recording.copy(onboarding = false, elapsedRecSec = 35.0)
        assertEquals("여기까지 마치기", singFinishLabel(standalone))
        assertFalse(standalone.canFinish)
        assertTrue(standalone.copy(phase = Phase.PAUSED).canFinish)
    }
}
