package com.micon.app.ui.report

import com.micon.app.range.RangeSession
import org.junit.Assert.*
import org.junit.Test

class OnboardingRangeIntroTest {
    @Test fun freshMeasurementAndStartErrorsRemainOnTheIntro() {
        val idle = GuidedRangeViewModel.State(soundReady = true)
        assertTrue(onboardingRangeIntroVisible(idle))
        assertTrue(onboardingRangeIntroVisible(idle.copy(error = "마이크 권한 거절")))
        val noise = RangeSession("intro").view()
        assertTrue(onboardingRangeIntroVisible(idle.copy(session = noise, error = "시작 실패")))
    }

    @Test fun delayedNoiseCalibrationKeepsIntroUntilTheComfortablePhase() {
        val noise = RangeSession("noise").view()
        val measuring = GuidedRangeViewModel.State(running = true, session = noise)
        assertTrue(onboardingRangeIntroVisible(measuring))
        assertTrue(onboardingRangeIntroVisible(measuring.copy(session = noise.copy(phase = "comfortable_ready"), pausedForIntro = true)))
        assertFalse(onboardingRangeIntroVisible(measuring.copy(session = noise.copy(phase = "comfortable"))))
        assertFalse(onboardingRangeIntroVisible(measuring.copy(session = noise.copy(phase = "profile_ready"))))
    }

    @Test fun resumedOrFinishedMeasurementDoesNotShowTheFreshIntro() {
        val idle = GuidedRangeViewModel.State()
        assertFalse(onboardingRangeIntroVisible(idle.copy(hasPending = true)))
        assertFalse(onboardingRangeIntroVisible(idle.copy(finished = true)))
        assertFalse(onboardingRangeIntroVisible(idle.copy(preparationFailed = true)))
        val active = RangeSession("resumed").view().copy(phase = "up", targetMidi = 72)
        assertFalse(onboardingRangeIntroVisible(idle.copy(running = true, session = active)))
    }
}
