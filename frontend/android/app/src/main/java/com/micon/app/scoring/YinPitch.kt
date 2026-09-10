package com.micon.app.scoring

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * YIN(CMNDF) 피치 추정. `docs/reference-src/pitch-worklet.js` L6–77 의 순수 함수 포팅.
 * 16 kHz 모노 프레임(WINDOW 샘플)을 받아 f0(Hz)와 clarity(0–1)를 돌려준다.
 * android.* 의존 없음 — JVM 단위 테스트에서 그대로 실행된다.
 */
object YinPitch {
    /** 원본 캡처 48 kHz → 16 kHz (평균 데시메이션). 44.1 kHz 도 rate 는 실제 값을 쓴다. */
    const val DECIMATE = 3
    /** 분석 창 (≈64 ms @16 kHz) */
    const val WINDOW = 1024
    /** ≈20 ms @16 kHz */
    const val HOP = 320
    const val FMIN = 70.0     // ≈C♯2
    const val FMAX = 1050.0   // ≈C6
    const val YIN_THRESHOLD = 0.12
    /** 워크릿 내부의 절대 무음 바닥. 유성 판정 게이트(RMS_MIN 0.004)와는 별개. */
    const val RMS_FLOOR = 1e-4

    data class Result(val f0: Double, val clarity: Double)
    data class Candidates(val initial: Result, val corrected: Result)

    /** 프레임 하나의 피치. 배음(2·3배음) 오검출 보정 + 포물선 보간 포함. */
    fun yinPitch(buffer: FloatArray, rate: Double, fmin: Double = FMIN, fmax: Double = FMAX): Result =
        candidates(buffer, rate, fmin, fmax).corrected

    fun candidates(buffer: FloatArray, rate: Double, fmin: Double = FMIN, fmax: Double = FMAX): Candidates {
        require(fmin > 0 && fmax > fmin && fmax < rate / 2)
        val maxTau = min((rate / fmin).toInt(), buffer.size - 2)
        val minTau = max(2, (rate / fmax).toInt())
        val span = buffer.size - maxTau
        val difference = DoubleArray(maxTau + 1)
        for (tau in 1..maxTau) {
            var sum = 0.0
            for (i in 0 until span) {
                val delta = (buffer[i] - buffer[i + tau]).toDouble()
                sum += delta * delta
            }
            difference[tau] = sum
        }
        val cmndf = DoubleArray(maxTau + 1) { 1.0 }
        var running = 0.0
        for (tau in 1..maxTau) {
            running += difference[tau]
            cmndf[tau] = if (running > 0) (difference[tau] * tau) / running else 1.0
        }
        var tau = -1
        var t = minTau
        while (t <= maxTau) {
            if (cmndf[t] < YIN_THRESHOLD) {
                while (t + 1 <= maxTau && cmndf[t + 1] < cmndf[t]) t++
                tau = t
                break
            }
            t++
        }
        if (tau < 0) {
            var best = minTau
            for (k in minTau + 1..maxTau) if (cmndf[k] < cmndf[best]) best = k
            tau = best
        }
        // 배음 오검출 보정: 2·3배음이 강한 목소리(특히 저음)는 반주기·1/3주기 딥이 먼저 임계값을 뚫어
        // 옥타브/5도 위로 잡힌다. k·tau 근처에 "확실히 더 깊은"(−0.04) 국소 최솟값이 있으면 그것이 진짜 주기.
        val initialTau = tau
        for (k in intArrayOf(2, 3)) {
            val center = tau * k
            if (center > maxTau) continue
            var local = center
            for (c in max(minTau, center - 3)..min(maxTau, center + 3)) {
                if (cmndf[c] < cmndf[local]) local = c
            }
            if (cmndf[local] < cmndf[tau] - 0.04) tau = local
        }
        fun result(tau: Int): Result {
        var refined = tau.toDouble()
        if (tau > 1 && tau < maxTau) {
            val s0 = cmndf[tau - 1]
            val s1 = cmndf[tau]
            val s2 = cmndf[tau + 1]
            val denominator = 2 * (2 * s1 - s2 - s0)
            if (abs(denominator) > 1e-12) {
                val shift = (s2 - s0) / denominator
                if (abs(shift) <= 1) refined = tau + shift
            }
        }
        return Result(f0 = rate / refined, clarity = (1 - cmndf[tau]).coerceIn(0.0, 1.0))
        }
        return Candidates(result(initialTau), result(tau))
    }

    /** 부분 구간 RMS. from/to 기본값은 전체 창. */
    fun frameRms(buffer: FloatArray, from: Int = 0, to: Int = buffer.size): Double {
        var sum = 0.0
        for (i in from until to) sum += buffer[i].toDouble() * buffer[i]
        return sqrt(sum / max(1, to - from))
    }
}
