package com.micon.app.data.voice

import com.micon.app.data.local.UserStore
import com.micon.app.range.RangeReport
import com.micon.app.scoring.ScoringMath
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 서버 음성 분석 리포트 → 리포트 탭이 그리는 [UserStore.VoiceProfile].
 *
 * 서버가 주는 것만 채운다: 전체 음역(fullLow/High), 편안한 음역(stableLow/High, 없으면 전체), 추천곡 음역(= 편안한 음역),
 * 음역별 안정성 곡선은 두 음역에서 유도한다(편안한 음역 안 1.0 → 전체 음역 끝 0). 템포·프레이즈·장르·보컬 특성 6축은
 * 서버 계약에 아직 없어 [UserStore.VoiceProfile] 기본값을 그대로 둔다(간편 분석과 같은 처리).
 */
object VoiceProfileMapper {
    /** 음역 결과가 없거나 값이 말이 안 되면(high ≤ low) null — 리포트 탭은 기존 프로필을 유지한다. */
    fun toProfile(report: VoiceAnalysisReport, now: Long = System.currentTimeMillis()): UserStore.VoiceProfile? {
        val range = report.range ?: return null
        val low = range.fullLowMidi?.roundToInt() ?: return null
        val high = range.fullHighMidi?.roundToInt() ?: return null
        if (high <= low) return null
        val comfortLow = range.stableLowMidi?.roundToInt()?.coerceIn(low, high) ?: low
        val comfortHigh = range.stableHighMidi?.roundToInt()?.coerceIn(comfortLow, high) ?: high
        val labelsMidi = (0 until STABILITY_POINTS).map { i -> low + ((high - low) * i.toDouble() / (STABILITY_POINTS - 1)).roundToInt() }
        return UserStore.VoiceProfile(
            lowMidi = low, highMidi = high,
            comfortLow = comfortLow, comfortHigh = comfortHigh,
            recommendLow = comfortLow, recommendHigh = comfortHigh,
            stability = labelsMidi.map { stabilityAt(it, low, high, comfortLow, comfortHigh) },
            stabilityLabels = labelsMidi.map(ScoringMath::midiName),
            measuredAt = parseServerTime(range.measuredAt ?: report.analysis.completedAt) ?: now,
        )
    }

    /** 편안한 음역 안은 1, 바깥은 전체 음역 끝까지 선형으로 0 에 닿는다. */
    internal fun stabilityAt(midi: Int, low: Int, high: Int, comfortLow: Int, comfortHigh: Int): Float = when {
        midi in comfortLow..comfortHigh -> 1f
        midi < comfortLow -> if (comfortLow == low) 0f else ((midi - low).toFloat() / (comfortLow - low)).coerceIn(0f, 1f)
        else -> if (comfortHigh == high) 0f else ((high - midi).toFloat() / (high - comfortHigh)).coerceIn(0f, 1f)
    }

    /** 서버 LocalDateTime("2026-09-13T21:00:00") → epoch ms(기기 시간대). 형식이 다르면 null. */
    internal fun parseServerTime(raw: String?): Long? =
        raw?.let { runCatching { LocalDateTime.parse(it).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli() }.getOrNull() }

    /**
     * 온보딩(30초 계단 측정) 음역 리포트 → 프로필. 전체 음역은 lowMidi/highMidi, 편안한 음역은 안정 구간 중 가장 넓은 것,
     * 안정 구간이 없으면 통과한 음들 중 가장 긴 연속 구간, 그것도 없으면 전체 음역. 예시 기본값은 쓰지 않는다 — 값이 없으면 null.
     */
    fun fromOnboarding(range: RangeReport, measuredAt: Long = System.currentTimeMillis()): UserStore.VoiceProfile? {
        val low = range.lowMidi ?: return null
        val high = range.highMidi ?: return null
        if (high <= low) return null
        val stable = range.stableIntervals.filter { it.size == 2 && it[0] <= it[1] }.map { it[0]..it[1] }
            .ifEmpty { runs(range.notes.filter { it.status in CONFIRMED }.map { it.midi }) }
            .maxByOrNull { it.last - it.first }
        val comfortLow = (stable?.first ?: low).coerceIn(low, high)
        val comfortHigh = (stable?.last ?: high).coerceIn(comfortLow, high)
        val labelsMidi = (0 until STABILITY_POINTS).map { i -> low + ((high - low) * i.toDouble() / (STABILITY_POINTS - 1)).roundToInt() }
        return UserStore.VoiceProfile(
            lowMidi = low, highMidi = high,
            comfortLow = comfortLow, comfortHigh = comfortHigh,
            recommendLow = comfortLow, recommendHigh = comfortHigh,
            stability = labelsMidi.map { stabilityAt(it, low, high, comfortLow, comfortHigh) },
            stabilityLabels = labelsMidi.map(ScoringMath::midiName),
            measuredAt = measuredAt,
        )
    }

    /** 반음 단위로 이어진 음들을 구간으로 묶는다. */
    private fun runs(notes: List<Int>): List<IntRange> {
        val result = mutableListOf<IntRange>()
        notes.distinct().sorted().forEach { midi ->
            if (result.lastOrNull()?.last == midi - 1) result[result.lastIndex] = result.last().first..midi else result.add(midi..midi)
        }
        return result
    }

    private val CONFIRMED = setOf("passed", "reconfirmed")
    private const val STABILITY_POINTS = 8
}
