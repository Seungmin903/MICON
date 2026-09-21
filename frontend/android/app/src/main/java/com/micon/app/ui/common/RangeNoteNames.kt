package com.micon.app.ui.common

import kotlin.math.floor
import kotlin.math.roundToInt

/** Display names for measured ranges, preserving the octave in F3 → 3옥타브 파. */
object RangeNoteNames {
    private val syllables = arrayOf("도", "도#", "레", "레#", "미", "파", "파#", "솔", "솔#", "라", "라#", "시")

    private fun octave(midi: Int) = floor(midi / 12.0).toInt() - 1
    private fun syllable(midi: Int) = syllables[((midi % 12) + 12) % 12]

    fun full(midi: Int): String = "${octave(midi)}옥타브 ${syllable(midi)}"
    fun full(midi: Double): String = full(midi.roundToInt())
    fun short(midi: Int): String = "${octave(midi)}옥 ${syllable(midi)}"
    fun range(lowMidi: Int, highMidi: Int): String = "${full(lowMidi)} ~ ${full(highMidi)}"
}
