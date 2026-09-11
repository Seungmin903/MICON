package com.micon.app.audio

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.micon.app.scoring.YinPitch
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * 마이크 → 16 kHz 모노 PCM → 20 ms 마다 YIN 피치 프레임. `pitch-worklet.js` 의 AudioWorkletProcessor(L83–128)
 * 를 AudioRecord 로 옮긴 것. 같은 PCM 을 WAV 로도 저장해 종료 후 `/compare` 에 올린다
 * (MediaRecorder 와 AudioRecord 를 동시에 열 수 없으므로 한 스트림을 두 용도로 쓴다).
 *
 * 프레임 t 는 **캡처 시계 기준 초**(창 중심). MR 시계와의 동기는 호출자(ViewModel)가 맡는다.
 */
class PitchCapture(private val fmin: Double = YinPitch.FMIN, private val fmax: Double = YinPitch.FMAX,
                   private val collectCandidates: Boolean = false,
                   /** false 면 YIN 을 돌리지 않고 레벨(rms)만 담은 프레임을 낸다 — 녹음만 필요한 화면(불러서 분석하기)용 */
                   private val analyzePitch: Boolean = true) {
    data class PitchFrame(val t: Double, val f0: Double, val clarity: Double, val rms: Double, val rmsLow: Double,
        val duration: Double = 0.02, val clippedRatio: Double = 0.0,
        val initialF0: Double? = null, val initialClarity: Double? = null)

    @Volatile var capturedSeconds: Double = 0.0
        private set
    var recordingRate: Int = 16000
        private set

    @Volatile private var running = false
    private var thread: Thread? = null
    private var record: AudioRecord? = null

    /** 실제 분석 샘플레이트(데시메이션 후). 16 kHz 가 안 되면 48 k/3 = 16 k, 44.1 k/3 = 14.7 k. */
    var analysisRate: Double = 16000.0
        private set

    val isRunning: Boolean get() = running

    /**
     * @param wavFile null 이 아니면 PCM16 모노 WAV 로 저장 (stop() 에서 헤더 완성)
     * @param onFrame 캡처 스레드에서 호출된다 — 가볍게 처리하고 채널/Flow 로 넘길 것
     */
    @SuppressLint("MissingPermission") // RECORD_AUDIO 는 화면에서 먼저 요청한다
    fun start(wavFile: File?, onError: (Throwable) -> Unit = {}, onFrame: (PitchFrame) -> Unit) {
        startInternal(wavFile, false, onError, onFrame)
    }

    /** Restart capture after a choice pause, appending PCM on the same recording clock. */
    fun resume(wavFile: File, onError: (Throwable) -> Unit = {}, onFrame: (PitchFrame) -> Unit) {
        startInternal(wavFile, true, onError, onFrame)
    }

    @SuppressLint("MissingPermission")
    private fun startInternal(wavFile: File?, append: Boolean, onError: (Throwable) -> Unit, onFrame: (PitchFrame) -> Unit) {
        check(!running) { "already running" }
        val prefixBytes = if (append) {
            require(wavFile != null && wavFile.isFile && wavFile.length() >= 44 && (wavFile.length() - 44) % 2 == 0L)
            wavFile.length() - 44
        } else 0L
        val (record, captureRate) = openRecord(if (append) recordingRate else null)
        recordingRate = captureRate
        val decimate = if (captureRate >= 32000) YinPitch.DECIMATE else 1
        analysisRate = captureRate.toDouble() / decimate
        this.record = record
        capturedSeconds = prefixBytes.toDouble() / (captureRate * 2)
        running = true
        thread = Thread({
            try { loop(record, captureRate, decimate, wavFile, prefixBytes, append, onFrame) }
            catch (e: Exception) { if (running) onError(e) }
            finally { running = false }
        }, "pitch-capture").apply {
            priority = Thread.MAX_PRIORITY
            start()
        }
    }

    /** 캡처 종료. WAV 파일을 저장했으면 그 파일을 돌려준다. */
    @Synchronized fun stop(): File? {
        running = false
        record?.runCatching { stop() }
        thread?.join(1500)
        thread = null
        record?.runCatching { stop() }
        record?.release()
        record = null
        return finishedWav
    }

    @Volatile private var finishedWav: File? = null

    @SuppressLint("MissingPermission")
    private fun openRecord(preferredRate: Int? = null): Pair<AudioRecord, Int> {
        for (rate in preferredRate?.let { intArrayOf(it) } ?: intArrayOf(16000, 48000, 44100)) {
            val minBuffer = AudioRecord.getMinBufferSize(rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            if (minBuffer <= 0) continue
            val buffer = maxOf(minBuffer, rate / 5 * 2) // ≥200 ms
            // VOICE_RECOGNITION: AGC·노이즈 억제 없이 비교적 원음 (웹: noiseSuppression/autoGainControl false)
            val record = runCatching {
                AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, rate, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer)
            }.getOrNull() ?: continue
            if (record.state == AudioRecord.STATE_INITIALIZED) return record to rate
            record.release()
        }
        throw IllegalStateException("마이크를 열 수 없습니다 (AudioRecord 초기화 실패)")
    }

    private fun loop(record: AudioRecord, captureRate: Int, decimate: Int, wavFile: File?, prefixBytes: Long, append: Boolean, onFrame: (PitchFrame) -> Unit) {
        val offset = prefixBytes.toDouble() / (captureRate * 2)
        val rate = captureRate.toDouble() / decimate
        val ring = FloatArray(8192)
        val clipRing = FloatArray(8192)
        val scratch = FloatArray(YinPitch.WINDOW)
        var write = 0
        var decimated = 0L
        var accumulator = 0f
        var accumulated = 0
        var clippedSamples = 0
        var capturedSamples = 0L
        var sinceHop = 0
        val chunk = ShortArray(YinPitch.HOP * decimate)
        val pcmBytes = ByteBuffer.allocate(chunk.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        val wavOut = wavFile?.let { BufferedOutputStream(FileOutputStream(it, append), 1 shl 16) }
        var pcmWritten = prefixBytes
        if (wavOut != null && !append) wavOut.write(ByteArray(44)) // 헤더 자리, stop 에서 채움
        finishedWav = null
        try {
            record.startRecording()
            while (running) {
                val n = record.read(chunk, 0, chunk.size)
                if (n <= 0) { if (running && n < 0) error("AudioRecord read failed: $n"); continue }
                capturedSamples += n
                capturedSeconds = offset + capturedSamples.toDouble() / captureRate
                if (wavOut != null) {
                    pcmBytes.clear()
                    for (i in 0 until n) pcmBytes.putShort(chunk[i])
                    wavOut.write(pcmBytes.array(), 0, n * 2)
                    pcmWritten += n * 2
                }
                for (i in 0 until n) {
                    accumulator += chunk[i] / 32768f
                    if (kotlin.math.abs(chunk[i].toInt()) >= 32441) clippedSamples++
                    if (++accumulated < decimate) continue
                    ring[write] = accumulator / decimate
                    clipRing[write] = clippedSamples.toFloat() / decimate
                    write = (write + 1) % ring.size
                    accumulator = 0f
                    accumulated = 0
                    clippedSamples = 0
                    decimated++
                    if (++sinceHop < YinPitch.HOP || decimated < YinPitch.WINDOW) continue
                    sinceHop = 0
                    var clipSum = 0.0
                    for (k in 0 until YinPitch.WINDOW) {
                        val index = (((write - YinPitch.WINDOW + k) % ring.size) + ring.size) % ring.size
                        scratch[k] = ring[index]
                        clipSum += clipRing[index]
                    }
                    val clipRatio = clipSum / YinPitch.WINDOW
                    val duration = YinPitch.HOP / rate
                    val rms = YinPitch.frameRms(scratch)
                    // 창 앞/뒤 절반 중 작은 RMS: 소리가 켜지거나 꺼지는 경계 프레임을 걸러낸다
                    val half = YinPitch.WINDOW / 2
                    val rmsLow = minOf(YinPitch.frameRms(scratch, 0, half), YinPitch.frameRms(scratch, half, YinPitch.WINDOW))
                    val t = offset + (decimated - half) / rate
                    if (!analyzePitch || rms < YinPitch.RMS_FLOOR || rmsLow < YinPitch.RMS_FLOOR * 0.5) {
                        onFrame(PitchFrame(t, 0.0, 0.0, rms, rmsLow, duration, clipRatio))
                    } else {
                        val candidates = YinPitch.candidates(scratch, rate, fmin, fmax)
                        onFrame(PitchFrame(t, candidates.corrected.f0, candidates.corrected.clarity, rms, rmsLow, duration, clipRatio,
                            if (collectCandidates) candidates.initial.f0 else null,
                            if (collectCandidates) candidates.initial.clarity else null))
                    }
                }
            }
        } finally {
            if (wavOut != null) {
                wavOut.flush(); wavOut.close()
                writeWavHeader(wavFile, captureRate, pcmWritten)
                finishedWav = wavFile
            }
        }
    }

    private fun writeWavHeader(file: File, sampleRate: Int, pcmBytes: Long) {
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + pcmBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)                 // fmt chunk size
        header.putShort(1)                // PCM
        header.putShort(1)                // mono
        header.putInt(sampleRate)
        header.putInt(sampleRate * 2)     // byte rate
        header.putShort(2)                // block align
        header.putShort(16)               // bits per sample
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(pcmBytes.toInt())
        RandomAccessFile(file, "rw").use { it.seek(0); it.write(header.array()) }
    }
}
