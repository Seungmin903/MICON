package com.micon.app.ui.common

import kotlin.math.floor

/** Scientific MIDI octave notation: MIDI 55 → 3G(솔), MIDI 60 → 4C(도). */
object ReportNoteNames {
    private val letters = arrayOf("C", "C#", "D", "D#", "E", "F", "F#", "G", "G#", "A", "A#", "B")
    private val syllables = arrayOf("도", "도#", "레", "레#", "미", "파", "파#", "솔", "솔#", "라", "라#", "시")

    fun note(midi: Int): String {
        val pitchClass = ((midi % 12) + 12) % 12
        return "${floor(midi / 12.0).toInt() - 1}${letters[pitchClass]}(${syllables[pitchClass]})"
    }

    fun range(lowMidi: Int, highMidi: Int): String = "${note(lowMidi)} ~ ${note(highMidi)}"

    fun fromScientific(label: String): String {
        val match = Regex("^([A-G])([#♯b♭]?)(-?\\d+)$").matchEntire(label.trim()) ?: return label
        val pitchClass = when (match.groupValues[1]) {
            "C" -> 0; "D" -> 2; "E" -> 4; "F" -> 5; "G" -> 7; "A" -> 9; else -> 11
        }
        val accidental = when (match.groupValues[2]) { "#", "♯" -> 1; "b", "♭" -> -1; else -> 0 }
        val octave = match.groupValues[3].toIntOrNull() ?: return label
        return note((octave + 1) * 12 + pitchClass + accidental)
    }
}
