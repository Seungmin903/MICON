package com.micon.app.range

/** An outcome belongs to one tested key, never every key between two extrema. */
object RangeKeyboardState {
    fun notes(previous: List<RangeNote>, attempts: List<RangeAttempt>): List<RangeNote> {
        val prior = previous.associateBy { it.midi }
        val grouped = attempts.filter { it.selectedForVisit && it.clientStatus !in setOf("interrupted", "unmeasured") }
            .groupBy { it.metrics?.creditedMidi ?: it.targetMidi }
        return (RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI).map { midi ->
            val old = prior[midi] ?: RangeNote(midi, "unmeasured")
            val recent = grouped[midi].orEmpty()
            if (recent.isEmpty()) old else {
                val passed = recent.count { it.clientStatus == "passed" } + old.successfulAttempts
                val failed = recent.any { it.clientStatus == "not_passed" } || old.status in setOf("not_passed", "inconsistent")
                val status = when {
                    old.status == "needs_review" -> "needs_review"
                    passed > 0 && failed -> "inconsistent"
                    passed >= 2 -> "reconfirmed"
                    passed > 0 -> "passed"
                    failed -> "not_passed"
                    else -> "insufficient_quality"
                }
                old.copy(status = status, successfulAttempts = passed, attempts = old.attempts + recent.size)
            }
        }
    }
}
