package com.micon.app.audio

import android.content.Context
import android.media.*
import com.micon.app.R
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.min

/** Piano samples from develop decoded once; actual PCM completion gates the recording clock. */
class ReferenceTone(private val context: Context) {
    private data class Sample(val rate: Int, val pcm: FloatArray)
    private val decoded = mutableMapOf<Int, Sample>()

    suspend fun prepare() = withContext(Dispatchers.IO) {
        for ((midi, res) in resources) {
            ensureActive()
            if (midi !in decoded) decoded[midi] = decode(res)
        }
    }

    internal fun pcm(midi: Int, seconds: Double): ShortArray {
        require(midi in 36..84 && seconds in .1..2.0)
        val nearest = resources.keys.minBy { abs(it - midi) }
        val sample = checkNotNull(decoded[nearest]) { "피아노 기준음 준비가 끝나지 않았습니다." }
        val step = sample.rate / RATE.toDouble() * 2.0.pow((midi - nearest) / 12.0)
        val size = (RATE * seconds).toInt()
        return ShortArray(size) { i ->
            val position = i * step
            val index = position.toInt()
            val fraction = (position - index).toFloat()
            val a = sample.pcm.getOrElse(index) { 0f }
            val b = sample.pcm.getOrElse(index + 1) { 0f }
            val fade = min(1.0, min(i / (RATE * .005), (size - i - 1) / (RATE * .03))).coerceAtLeast(0.0)
            ((a + (b - a) * fraction) * fade * .8 * 32767).toInt().coerceIn(-32768, 32767).toShort()
        }
    }

    suspend fun play(midi: Int, seconds: Double = .5) = withContext(Dispatchers.IO) {
        val samples = pcm(midi, seconds)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder().setSampleRate(RATE).setEncoding(AudioFormat.ENCODING_PCM_16BIT).setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(samples.size * 2).setTransferMode(AudioTrack.MODE_STATIC).build()
        try {
            check(track.write(samples, 0, samples.size) == samples.size) { "기준음을 준비하지 못했습니다." }
            track.play()
            var waits = 0
            while (track.playbackHeadPosition < samples.size) {
                ensureActive()
                check(++waits < seconds * 100 + 100) { "기준음 재생이 중단되었습니다." }
                delay(10)
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    private fun decode(resource: Int): Sample {
        val extractor = MediaExtractor()
        var codec: MediaCodec? = null
        try {
            context.resources.openRawResourceFd(resource).use { extractor.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
            val track = (0 until extractor.trackCount).first { extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("audio/") == true }
            val format = extractor.getTrackFormat(track)
            extractor.selectTrack(track)
            val decoder = MediaCodec.createDecoderByType(format.getString(MediaFormat.KEY_MIME)!!)
            codec = decoder
            decoder.configure(format, null, null, 0); decoder.start()
            var rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            var channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            var encoding = AudioFormat.ENCODING_PCM_16BIT
            val bytes = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false
            var polls = 0
            while (!outputDone) {
                check(++polls < 2000) { "피아노 샘플을 읽지 못했습니다." }
                if (!inputDone) {
                    val input = decoder.dequeueInputBuffer(5000)
                    if (input >= 0) {
                        val buffer = decoder.getInputBuffer(input)!!
                        buffer.clear()
                        val count = extractor.readSampleData(buffer, 0)
                        if (count < 0) { decoder.queueInputBuffer(input, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM); inputDone = true }
                        else { decoder.queueInputBuffer(input, 0, count, extractor.sampleTime, 0); extractor.advance() }
                    }
                }
                val output = decoder.dequeueOutputBuffer(info, 5000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val out = decoder.outputFormat
                    rate = out.getInteger(MediaFormat.KEY_SAMPLE_RATE); channels = out.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                    encoding = if (out.containsKey(MediaFormat.KEY_PCM_ENCODING)) out.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                } else if (output >= 0) {
                    if (info.size > 0) {
                        val buffer = decoder.getOutputBuffer(output)!!
                        buffer.position(info.offset); buffer.limit(info.offset + info.size)
                        val chunk = ByteArray(info.size); buffer.get(chunk); bytes.write(chunk)
                    }
                    outputDone = info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
                    decoder.releaseOutputBuffer(output, false)
                }
            }
            check(encoding == AudioFormat.ENCODING_PCM_16BIT || encoding == AudioFormat.ENCODING_PCM_FLOAT)
            val buffer = ByteBuffer.wrap(bytes.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
            val width = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
            val mono = FloatArray(buffer.remaining() / width / channels) {
                var sum = 0f
                repeat(channels) { sum += if (width == 4) buffer.float else buffer.short / 32768f }
                sum / channels
            }
            check(mono.isNotEmpty() && mono.all { it.isFinite() })
            return Sample(rate, mono)
        } finally { runCatching { codec?.stop() }; codec?.release(); extractor.release() }
    }

    companion object {
        const val RATE = 44100
        private val resources = mapOf(48 to R.raw.piano_48, 51 to R.raw.piano_51, 54 to R.raw.piano_54,
            57 to R.raw.piano_57, 60 to R.raw.piano_60, 63 to R.raw.piano_63, 66 to R.raw.piano_66,
            69 to R.raw.piano_69, 72 to R.raw.piano_72, 75 to R.raw.piano_75, 78 to R.raw.piano_78,
            81 to R.raw.piano_81, 84 to R.raw.piano_84)
    }
}
