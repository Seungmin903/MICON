package com.micon.app.range

/** Versioned measured values only; missing notes have no score, even when a dashed display link crosses them. */
data class RangeStabilityPoint(val note: RangeNote, val ratio: Float?) {
    val confirmed: Boolean get() = note.status in setOf("passed", "reconfirmed")
}

data class RangeStabilityPlot(val points: List<RangeStabilityPoint>, val scoreBased: Boolean = false) {
    // Include every semitone in points so missing evidence always breaks the line.
    val segments: List<Pair<RangeStabilityPoint, RangeStabilityPoint>> get() =
        points.zipWithNext().filter { (a, b) ->
            b.note.midi == a.note.midi + 1 && a.ratio != null && b.ratio != null
        }

    /** Display-only links. A gap is connected visually, never filled with fabricated note scores. */
    val connectedSegments: List<Pair<RangeStabilityPoint, RangeStabilityPoint>> get() =
        points.filter { it.ratio != null }.zipWithNext()

    companion object {
        fun from(notes: List<RangeNote>, showAll: Boolean = false, scoreBased: Boolean = false): RangeStabilityPlot {
            val supported = notes.filter { it.midi in RangeProtocol.MIN_MIDI..RangeProtocol.MAX_MIDI }
            val attempted = supported.filter { it.status != "unmeasured" }
            val low = if (showAll) RangeProtocol.MIN_MIDI else
                ((attempted.minOfOrNull { it.midi } ?: 59) - 1).coerceAtLeast(RangeProtocol.MIN_MIDI)
            val high = if (showAll) RangeProtocol.MAX_MIDI else
                ((attempted.maxOfOrNull { it.midi } ?: 61) + 1).coerceAtMost(RangeProtocol.MAX_MIDI)
            val byMidi = supported.associateBy { it.midi }
            return RangeStabilityPlot((low..high).map { midi ->
                val note = byMidi[midi] ?: RangeNote(midi, "unmeasured")
                val value = if (scoreBased) note.metrics?.stabilityScore?.div(100.0) else note.metrics?.inTuneRatio
                val ratio = value?.takeIf {
                    note.status in setOf("passed", "reconfirmed", "not_passed", "inconsistent") &&
                        it.isFinite() && it in 0.0..1.0
                }?.toFloat()
                RangeStabilityPoint(note, ratio)
            }, scoreBased)
        }
    }
}
