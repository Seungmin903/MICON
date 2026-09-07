package com.micon.app.scoring

import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.roundToInt

/** live-scoring.tsx L35–45 의 순수 헬퍼. android.* 의존 없음. */
object ScoringMath {
    private val NOTE_NAMES = arrayOf("C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B")

    /** 반음 오차를 한 옥타브 안(−6…+6)으로 접는다. delta - 12·round(delta/12) */
    fun fold(delta: Double): Double = delta - 12.0 * Math.round(delta / 12.0)

    /** 정렬 후 floor(n/2) 번째 값. 짝수 길이도 평균하지 않는다(웹과 동일). 빈 목록은 0. */
    fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }

    fun gradeOf(score: Double): String =
        ScoringConstants.GRADES.firstOrNull { (threshold, _) -> score >= threshold }?.second ?: "F"

    /** 초 → "m:ss" */
    fun formatTime(seconds: Double): String {
        val total = maxOf(0, Math.round(seconds).toInt())
        return "${total / 60}:${(total % 60).toString().padStart(2, '0')}"
    }

    /** Hz → MIDI (A4 = 440 = 69). f0 ≤ 0 이면 null. */
    fun hzToMidi(f0: Double): Double? = if (f0 > 0) 69.0 + 12.0 * (ln(f0 / 440.0) / ln(2.0)) else null

    fun midiToHz(midi: Double): Double = 440.0 * Math.pow(2.0, (midi - 69.0) / 12.0)

    /** 60 → "C4" */
    fun midiName(midi: Int): String = NOTE_NAMES[((midi % 12) + 12) % 12] + (floor(midi / 12.0).toInt() - 1)

    fun midiName(midi: Double): String = midiName(midi.roundToInt())

    fun clamp01(value: Double): Double = value.coerceIn(0.0, 1.0)

    /** 마이크 프레임의 유성 판정 (live-scoring.tsx L310–313). rmsLow 가 있으면 그것으로 게이트. */
    fun isVoiced(f0: Double, clarity: Double, rms: Double, rmsLow: Double? = null): Boolean =
        (rmsLow ?: rms) >= ScoringConstants.RMS_MIN && clarity >= ScoringConstants.CLARITY_MIN && f0 > 0
}
