package com.micon.app.audio

import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Reads the app's original PCM without changing uploaded samples. A live file has a placeholder header. */
object Pcm16MonoWav {
    fun sampleRate(file: File): Int = RandomAccessFile(file, "r").use {
        it.seek(24)
        Integer.reverseBytes(it.readInt())
    }
    fun duration(file: File, rate: Int, finalized: Boolean): Double? {
        if (rate !in setOf(16000, 44100, 48000) || !file.isFile || !file.canRead() || file.length() <= 44) return null
        val bytes = file.length() - 44
        if (bytes % 2 != 0L) return null
        if (finalized) {
            val header = ByteArray(44)
            RandomAccessFile(file, "r").use { it.readFully(header) }
            val h = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
            fun textAt(start: Int, count: Int) = String(header, start, count, Charsets.US_ASCII)
            if (textAt(0, 4) != "RIFF" || textAt(8, 8) != "WAVEfmt " || textAt(36, 4) != "data" ||
                h.getInt(16) != 16 || h.getShort(20).toInt() != 1 || h.getShort(22).toInt() != 1 ||
                h.getInt(24) != rate || h.getInt(28) != rate * 2 || h.getShort(32).toInt() != 2 ||
                h.getShort(34).toInt() != 16 || h.getInt(40).toLong() != bytes ||
                h.getInt(4).toLong() != bytes + 36) return null
        }
        return bytes.toDouble() / (rate * 2)
    }

    fun samples(file: File, start: Long, count: Int): FloatArray {
        require(start >= 0 && count > 0)
        val bytes = ByteArray(count * 2)
        RandomAccessFile(file, "r").use { it.seek(44 + start * 2); it.readFully(bytes) }
        val pcm = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        return FloatArray(count) { pcm.short / 32768f }
    }
}
