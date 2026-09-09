package com.micon.app.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import com.micon.app.R
import kotlin.math.abs
import kotlin.math.pow

/**
 * 실제 피아노 샘플로 기준음을 낸다 (간편 분석 "기준음 들어보기", 건반 탭).
 *
 * 샘플: University of Iowa Musical Instrument Samples(무료 공개) Piano.ff 를 3반음 간격 13개(C3~C6)만 2초로 잘라 OGG 로 담았다
 * (`res/raw/piano_<midi>.ogg`, 총 0.5 MB 안팎). 사이 음은 가장 가까운 샘플을 재생 속도 2^(반음차/12) 로 바꿔 낸다(최대 ±1반음이라
 * 음색 왜곡이 거의 없다). SoundPool 은 OGG 디코딩·속도 변경을 지원하고 지연이 짧아 탭 반응에 알맞다.
 */
class PianoPlayer(context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(3)
        .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
        .build()
    private val samples: Map<Int, Int> = SAMPLE_RES.mapValues { (_, res) -> pool.load(context, res, 1) }   // midi → soundId
    private var lastStream = 0

    /** [midi] 음을 낸다. 아직 로드 전이면 SoundPool 이 조용히 무시한다(첫 진입 직후 수십 ms). */
    fun play(midi: Int, volume: Float = 0.9f) {
        val nearest = samples.keys.minByOrNull { abs(it - midi) } ?: return
        val rate = 2.0.pow((midi - nearest) / 12.0).toFloat().coerceIn(0.5f, 2.0f)
        if (lastStream != 0) pool.stop(lastStream)
        lastStream = pool.play(samples.getValue(nearest), volume, volume, 1, 0, rate)
    }

    fun stop() { if (lastStream != 0) pool.stop(lastStream); lastStream = 0 }

    fun release() { stop(); pool.release() }

    companion object {
        /** 재생 길이(ms) — 샘플을 2.0 초로 잘랐다. 마이크 판정을 이 시간 동안 멈추는 데 쓴다. */
        const val DURATION_MS = 2000

        private val SAMPLE_RES = mapOf(
            48 to R.raw.piano_48, 51 to R.raw.piano_51, 54 to R.raw.piano_54, 57 to R.raw.piano_57,
            60 to R.raw.piano_60, 63 to R.raw.piano_63, 66 to R.raw.piano_66, 69 to R.raw.piano_69,
            72 to R.raw.piano_72, 75 to R.raw.piano_75, 78 to R.raw.piano_78, 81 to R.raw.piano_81,
            84 to R.raw.piano_84,
        )
    }
}
