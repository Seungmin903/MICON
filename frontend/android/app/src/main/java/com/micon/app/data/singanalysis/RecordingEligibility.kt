package com.micon.app.data.singanalysis

/** Counts only overlapping microphone and MR progress. Seeks and stalled clocks earn no time. */
class RecordingEligibility {
    private data class Sample(val audio: Double, val song: Double, val monotonic: Double, val active: Boolean)
    private var previous: Sample? = null
    private var anchor: Sample? = null
    private var creditedInSpan = 0.0
    val takes = mutableListOf<SingTake>()
    var seconds: Double = 0.0
        private set
    var valid: Boolean = true
        private set

    fun sample(audio: Double, song: Double, monotonic: Double, active: Boolean) {
        if (!valid || !audio.isFinite() || !song.isFinite() || !monotonic.isFinite()) return
        val current = Sample(audio, song, monotonic, active)
        val old = previous
        previous = current
        fun beginSpan() { anchor = if (active) current else null; creditedInSpan = 0.0 }
        if (old == null || !old.active || !active) { beginSpan(); return }
        val wallDelta = monotonic - old.monotonic
        val songDelta = song - old.song
        if (wallDelta !in 0.0..0.5 || audio < old.audio || songDelta < 0 || songDelta > wallDelta + 0.1) {
            beginSpan(); return
        }
        val start = anchor ?: current.also { anchor = it }
        // AudioRecord publishes blocks, not one sample per UI tick. Retain uncredited progress
        // within a continuous playing span instead of discarding ticks with no new block.
        val total = minOf(audio - start.audio, song - start.song, monotonic - start.monotonic)
        val length = total - creditedInSpan
        if (length <= 0) return
        seconds += length
        val take = SingTake(start.audio + creditedInSpan, start.audio + total,
            start.song + creditedInSpan, start.song + total)
        creditedInSpan = total
        val last = takes.lastOrNull()
        if (last != null && kotlin.math.abs(last.wavEndSec - take.wavStartSec) < 0.002 &&
            kotlin.math.abs(last.songEndSec - take.songStartSec) < 0.002) {
            takes[takes.lastIndex] = last.copy(wavEndSec = take.wavEndSec, songEndSec = take.songEndSec)
        } else takes.add(take)
    }

    fun pause() { previous = null; anchor = null; creditedInSpan = 0.0 }
    fun invalidate() { valid = false; pause() }
    fun canFinish(paused: Boolean): Boolean = valid && paused && seconds >= MIN_SECONDS

    companion object { const val MIN_SECONDS = 30.0 }
}
