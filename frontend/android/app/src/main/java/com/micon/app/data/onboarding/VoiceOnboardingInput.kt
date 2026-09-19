package com.micon.app.data.onboarding

import com.micon.app.data.singanalysis.RecordingEligibility
import com.micon.app.data.singanalysis.SavedRecording
import com.micon.app.range.RangeReport
import kotlin.math.abs

/** Checks the app's PCM16 mono recordings before a session or model job is created. */
object VoiceOnboardingInput {
    const val MAX_SECONDS = 120.0
    private const val AUDIO_TOLERANCE_SECONDS = 0.05

    fun problem(draftId: String, range: RangeReport?, recording: SavedRecording?): String? {
        if (range?.availability != "available" || range.lowMidi == null || range.highMidi == null ||
            range.lowMidi !in 0..127 || range.highMidi !in range.lowMidi..127) {
            return "확인된 음역이 없어요. 음역부터 다시 측정해 주세요."
        }
        return recordingProblem(draftId, recording)
    }

    fun recordingProblem(draftId: String, recording: SavedRecording?): String? {
        if (recording == null || recording.meta.onboardingDraftId != draftId) {
            return "이 측정에 사용할 녹음이 없어요. 노래를 다시 불러 주세요."
        }
        val meta = recording.meta
        if (meta.sampleRate !in setOf(44100, 48000) || meta.channels != 1 || meta.bitsPerSample != 16) {
            return "분석에 사용할 녹음 형식이 아니에요. 노래를 다시 녹음해 주세요."
        }
        if (!meta.durationSec.isFinite() || meta.durationSec < RecordingEligibility.MIN_SECONDS ||
            meta.durationSec > MAX_SECONDS + AUDIO_TOLERANCE_SECONDS) {
            return "반주와 함께 30초 이상, 2분 이내로 다시 녹음해 주세요."
        }
        var previousEnd = 0.0
        var recordedSeconds = 0.0
        for (take in meta.takes) {
            if (listOf(take.wavStartSec, take.wavEndSec, take.songStartSec, take.songEndSec).any { !it.isFinite() } ||
                take.wavStartSec < previousEnd || take.wavEndSec <= take.wavStartSec ||
                take.wavEndSec > meta.durationSec || take.songStartSec < 0 || take.songEndSec <= take.songStartSec) {
                return "녹음된 구간을 확인할 수 없어요. 노래를 다시 불러 주세요."
            }
            previousEnd = take.wavEndSec
            recordedSeconds += take.wavEndSec - take.wavStartSec
        }
        if (recordedSeconds + 1e-6 < RecordingEligibility.MIN_SECONDS) {
            return "반주와 함께 녹음된 구간이 30초 미만이에요. 노래를 다시 불러 주세요."
        }
        // PitchCapture writes a 44-byte header followed by PCM16 mono samples.
        val actualSeconds = (recording.wav.length() - 44).toDouble() / (meta.sampleRate * 2)
        if (!recording.wav.isFile || !recording.wav.canRead() || actualSeconds < RecordingEligibility.MIN_SECONDS ||
            actualSeconds > MAX_SECONDS + AUDIO_TOLERANCE_SECONDS ||
            abs(actualSeconds - meta.durationSec) > AUDIO_TOLERANCE_SECONDS) {
            return "녹음 파일을 확인할 수 없어요. 노래를 다시 녹음해 주세요."
        }
        return null
    }
}
