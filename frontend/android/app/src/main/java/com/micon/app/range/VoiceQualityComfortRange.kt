package com.micon.app.range

/** The first and last measured notes at or above 80 define one display interval. */
object VoiceQualityComfortRange {
    const val MIN_SCORE = 80.0

    fun find(quality: VoiceQualityResult, low: Int, high: Int): IntRange? {
        if (low > high) return null
        val eligible = quality.points.asSequence()
            .filter { it.midi in low..high && it.score.isFinite() && it.score in MIN_SCORE..100.0 }
            .map { it.midi }.toList()
        if (eligible.isEmpty()) return null
        return eligible.min()..eligible.max()
    }
}
