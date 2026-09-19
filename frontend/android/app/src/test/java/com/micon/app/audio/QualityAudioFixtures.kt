package com.micon.app.audio

import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

fun writeQualityWav(file: File, rate: Int = 16000, seconds: Double = 4.0, amplitude: Double = .08) {
    val count = (rate * seconds).toInt()
    val wav = ByteBuffer.allocate(44 + count * 2).order(ByteOrder.LITTLE_ENDIAN)
    wav.put("RIFF".toByteArray()).putInt(36 + count * 2).put("WAVEfmt ".toByteArray()).putInt(16)
    wav.putShort(1).putShort(1).putInt(rate).putInt(rate * 2).putShort(2).putShort(16)
    wav.put("data".toByteArray()).putInt(count * 2)
    repeat(count) { wav.putShort((32767 * amplitude * sin(2 * PI * 261.625565 * it / rate)).toInt().toShort()) }
    file.writeBytes(wav.array())
}
