package com.micon.app.data.onboarding

import com.micon.app.data.singanalysis.*
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal fun onboardingRecording(wav: File, draftId: String = "local-draft", sampleRate: Int = 48000,
    seconds: Double = 35.0): SavedRecording {
    val pcmBytes = (sampleRate * 2 * seconds).toInt()
    val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + pcmBytes); put("WAVEfmt ".toByteArray())
        putInt(16); putShort(1); putShort(1); putInt(sampleRate); putInt(sampleRate * 2)
        putShort(2); putShort(16); put("data".toByteArray()); putInt(pcmBytes)
    }
    RandomAccessFile(wav, "rw").use { it.write(header.array()); it.setLength(44L + pcmBytes) }
    return SavedRecording(SingRecordingMeta(
        id = "recording", songId = "1", title = "Song", artist = "Artist", scope = "FULL",
        planSource = "SONG_START", plannedStartSec = 0.0, plannedEndSec = 200.0,
        sungStartSec = 0.0, sungEndSec = seconds, endedEarly = true, recordedAt = 0,
        sampleRate = sampleRate, durationSec = seconds, takes = listOf(SingTake(0.0, seconds, 0.0, seconds)),
        lyricTiming = "SYNCED", wavFileName = wav.name, onboardingDraftId = draftId),
        wav, File(wav.parentFile, wav.nameWithoutExtension + ".json"))
}
