package com.micon.app.data.voice

import com.micon.app.data.local.UserStore
import com.micon.app.data.holo.HoloResult
import com.micon.app.range.RangeReport
import com.micon.app.scoring.ScoringMath
import java.time.LocalDateTime
import java.time.ZoneId
import kotlin.math.roundToInt

/**
 * 서버 음성 분석 리포트 → 리포트 탭이 그리는 [UserStore.VoiceProfile].
 *
 * 서버가 주는 것만 채운다: 전체 음역(fullLow/High), 편안한 음역(stableLow/High, 없으면 전체), 추천곡 음역(= 편안한 음역),
 * 새 온보딩의 발성 점수는 측정된 음별 값만 사용한다. 구형 프로필의 안정성 곡선만 두 음역에서 유도한다.
 * 템포·프레이즈·장르·보컬 특성 6축은
 * 서버 계약에 아직 없어 [UserStore.VoiceProfile] 기본값을 그대로 둔다(간편 분석과 같은 처리).
 */
object VoiceProfileMapper {
    /**
     * 앱 재시작 뒤 서버에서 복원한 프로필을 기존 음성 결과 상세 화면에 표시하기 위한 변환이다.
     * 상세 화면이 실제로 사용하는 음역/안정성 값만 복원하고, 녹음 세션 전용 값은 비워 둔다.
     */
    fun toDisplayResult(profile: UserStore.VoiceProfile): HoloResult {
        val quality = profile.voiceQuality
        val pointCount = profile.stability.size
        val stabilityMidis = quality?.points?.map { it.midi } ?: when (pointCount) {
            0 -> emptyList()
            1 -> listOf((profile.lowMidi + profile.highMidi) / 2)
            else -> (0 until pointCount).map { index ->
                profile.lowMidi + ((profile.highMidi - profile.lowMidi) * index.toDouble() / (pointCount - 1)).roundToInt()
            }
        }
        val bpm = profile.bpmRange.split('-').mapNotNull { it.trim().toIntOrNull() }
        return HoloResult(
            measuredAt = profile.measuredAt,
            lowMidi = profile.lowMidi,
            highMidi = profile.highMidi,
            comfortLow = profile.comfortLow,
            comfortHigh = profile.comfortHigh,
            recommendLow = profile.recommendLow,
            recommendHigh = profile.recommendHigh,
            stability = quality?.points?.map { (it.score / 100.0).toFloat() } ?: profile.stability,
            stabilityMidis = stabilityMidis,
            voiceQuality = quality,
            phraseSec = profile.phraseLevel.toDouble(),
            bpmLow = bpm.getOrElse(0) { 0 },
            bpmHigh = bpm.getOrElse(1) { bpm.firstOrNull() ?: 0 },
            notes = emptyList(),
            voicedSec = 0.0,
            profileSaved = true,
        )
    }

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
        val held = if (range.protocolVersion == com.micon.app.range.RangeProtocol.FREE_VERSION &&
            range.stableLowMidi != null && range.stableHighMidi != null)
            listOf(range.stableLowMidi.roundToInt()..range.stableHighMidi.roundToInt()) else emptyList()
        val stable = (held + range.stableIntervals.filter { it.size == 2 && it[0] <= it[1] }.map { it[0]..it[1] })
            .ifEmpty { runs(range.notes.filter { it.status in CONFIRMED }.map { it.midi }) }
            .maxByOrNull { it.last - it.first }
        val comfortLow = (stable?.first ?: low).coerceIn(low, high)
        val comfortHigh = (stable?.last ?: high).coerceIn(comfortLow, high)
        val labelsMidi = (0 until STABILITY_POINTS).map { i -> low + ((high - low) * i.toDouble() / (STABILITY_POINTS - 1)).roundToInt() }
        val quality = range.voiceQuality?.takeIf { it.version == com.micon.app.range.LocalVoiceQualityAnalyzer.VERSION }
        return UserStore.VoiceProfile(
            lowMidi = low, highMidi = high,
            comfortLow = comfortLow, comfortHigh = comfortHigh,
            recommendLow = comfortLow, recommendHigh = comfortHigh,
            stability = quality?.points?.map { (it.score / 100.0).toFloat() }
                ?: labelsMidi.map { stabilityAt(it, low, high, comfortLow, comfortHigh) },
            stabilityLabels = quality?.points?.map { ScoringMath.midiName(it.midi) }
                ?: labelsMidi.map(ScoringMath::midiName),
            voiceQuality = quality,
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
