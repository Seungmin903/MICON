package com.micon.app.scoring

import com.micon.app.data.RangeFit
import com.micon.app.data.StrainSection
import com.micon.app.data.StrainSections
import com.micon.app.data.backend.dto.KaraokeNote
import kotlin.math.abs
import kotlin.math.exp

/**
 * 지속시간 가중 반음 분포. 서버 `pitch_profile.summarize_pitch_profile` 과 같은 의미다.
 * MIDI 36~95 의 60칸, 합 1. core = 5–95 % 지점, median = 50 % 지점.
 */
class PitchProfile(
    val histogram: DoubleArray,
    val coreLow: Int,
    val median: Int,
    val coreHigh: Int,
    /** false 면 음역 점수를 0 으로 둔다 (서버 catalogProfileReliable 과 동일) */
    val reliable: Boolean = true,
) {
    init {
        require(histogram.size == BINS) { "histogram must have $BINS bins" }
        require(coreLow <= median && median <= coreHigh) { "core/median out of order" }
    }

    companion object {
        const val MIDI_MIN = 36
        const val MIDI_MAX = 95
        const val BINS = MIDI_MAX - MIDI_MIN + 1

        /** 곡 음표에서. 지속시간 = sourceEnd − sourceStart. 유효 음표가 없으면 null. */
        fun fromNotes(notes: List<KaraokeNote>, reliable: Boolean = true): PitchProfile? {
            val hist = DoubleArray(BINS)
            var total = 0.0
            for (n in notes) {
                val d = n.sourceEnd - n.sourceStart
                if (d <= 0 || d.isNaN()) continue
                hist[n.midi.coerceIn(MIDI_MIN, MIDI_MAX) - MIDI_MIN] += d
                total += d
            }
            if (total <= 0) return null
            for (i in hist.indices) hist[i] /= total
            return PitchProfile(hist, percentile(hist, 0.05), percentile(hist, 0.50), percentile(hist, 0.95), reliable)
        }

        /**
         * 사용자 안정 음역만 알 때 (UserStore.VoiceProfile.comfortLow/High). 분포는 균등, 중앙음은 중점.
         * 음성 분석이 P50 을 내려주면 그 값으로 교체한다.
         */
        fun ofStableRange(low: Int, high: Int): PitchProfile {
            val lo = low.coerceIn(MIDI_MIN, MIDI_MAX)
            val hi = high.coerceIn(lo, MIDI_MAX)
            val hist = DoubleArray(BINS)
            for (m in lo..hi) hist[m - MIDI_MIN] = 1.0 / (hi - lo + 1)
            return PitchProfile(hist, lo, (lo + hi) / 2, hi)
        }

        /** 서버 weighted_percentile: 누적 질량이 처음으로 p·total 이상이 되는 반음 */
        private fun percentile(hist: DoubleArray, p: Double): Int {
            var cursor = 0.0
            for (i in hist.indices) {
                cursor += hist[i]
                if (cursor >= p - 1e-12) return MIDI_MIN + i
            }
            return MIDI_MAX
        }
    }
}

/**
 * 음역 적합도·추천 키. recommend-algo `timbre_recommender/pitch_range.py` (`vocal-range-keyshift-first-98-v3`) 를 그대로 옮겼다.
 * 서버와 답이 달라지면 이 파일이 아니라 입력(프로필)이 다른 것이다.
 */
object RangeFitCalculator {
    const val SCORING_VERSION = "vocal-range-keyshift-first-98-v3"

    // TODO(§15 미확정): 종합 적합도 공식·추천 키 조정 범위는 요구사항 명세 15장 확정 대상. 아래는 recommend-algo v2 의 임시값이며
    //   확정되면 이 블록과 KEY_SHIFTS 만 바꾼다. 서버 파라미터 세트와 함께 바뀌어야 한다 (TIMBRE_RECOMMENDER.md "사용자 평가 후 조정할 값").
    const val COMFORT_PADDING_SEMITONES = 2
    private const val COVERAGE_CENTER = 0.80
    private const val COVERAGE_SIGMOID_SCALE = 0.05
    private const val CORE_EXCESS_DECAY = 4.0
    private const val CENTER_DECAY = 3.0
    private const val SPAN_DECAY = 4.0
    private const val W_COVERAGE = 0.85
    private const val W_SPAN = 0.15
    private const val W_BASE = 0.70
    private const val W_SOFT = 0.30
    private const val GUIDED_TARGET_COVERAGE = 0.98f

    /** 추천 키와 실시간 연습 키는 같은 범위만 사용한다. */
    val KEY_SHIFTS: IntRange = ScoringConstants.KEY_SHIFT_MIN..ScoringConstants.KEY_SHIFT_MAX

    data class Result(val keyShift: Int, val fit: RangeFit)

    /** 키 [shift] 로 곡을 옮겼을 때의 점수 항목. */
    fun scoreAtShift(user: PitchProfile, song: PitchProfile, shift: Int): RangeFit {
        val obsLow = user.coreLow - COMFORT_PADDING_SEMITONES
        val obsHigh = user.coreHigh + COMFORT_PADDING_SEMITONES
        var coverage = 0.0
        for (i in song.histogram.indices) {
            val midi = PitchProfile.MIDI_MIN + i + shift
            if (midi in obsLow..obsHigh) coverage += song.histogram[i]
        }
        val centerDistance = abs(song.median + shift - user.median).toDouble()
        val centerScore = exp(-centerDistance / CENTER_DECAY)
        val spanExcess = maxOf(0, (song.coreHigh - song.coreLow) - (obsHigh - obsLow))
        val spanFit = exp(-spanExcess / SPAN_DECAY)
        // 앱의 사용자 프로필은 안정 음역 측정값이다. 중앙값은 자연스러운 노래에서 관찰한 값이
        // 아니므로 서버의 guided-first-98 정책처럼 중심음 점수를 추천 키에 섞지 않는다.
        val base = W_COVERAGE * coverage + W_SPAN * spanFit
        val lowExcess = maxOf(0, obsLow - (song.coreLow + shift))
        val highExcess = maxOf(0, (song.coreHigh + shift) - obsHigh)
        val coverageFit = 1.0 / (1.0 + exp(-((coverage - COVERAGE_CENTER) / COVERAGE_SIGMOID_SCALE).coerceIn(-60.0, 60.0)))
        val boundaryFit = exp(-(lowExcess + highExcess) / CORE_EXCESS_DECAY)
        val softFit = coverageFit * boundaryFit
        val score = (W_BASE * base + W_SOFT * softFit).coerceIn(0.0, 1.0)
        return RangeFit(score.toFloat(), coverage.toFloat(), centerScore.toFloat(), spanFit.toFloat(), softFit.toFloat(), song.coreLow, song.coreHigh)
    }

    /**
     * 안정 음역 측정용 최적 키. 원키에서 가장 적게 움직여 커버리지 98%를 처음 만족하는 키를 고른다.
     * ±3 안에서 98%가 불가능하면 core 경계 초과가 가장 작은 키를 고른다.
     * 곡 프로필이 unreliable 이면 키는 고르되 score·softFit 을 0 으로 둔다(서버와 동일).
     */
    fun best(user: PitchProfile, song: PitchProfile): Result {
        val options = KEY_SHIFTS.map { k -> k to scoreAtShift(user, song, k) }
        val covered = options.filter { it.second.coverage >= GUIDED_TARGET_COVERAGE }
        val chosen = if (covered.isNotEmpty()) {
            covered.sortedWith(compareBy<Pair<Int, RangeFit>> { abs(it.first) }
                .thenByDescending { it.second.coverage }
                .thenByDescending { it.second.score }
                .thenBy { it.first }).first()
        } else {
            options.sortedWith(compareBy<Pair<Int, RangeFit>> { (key, _) ->
                val observedLow = user.coreLow - COMFORT_PADDING_SEMITONES
                val observedHigh = user.coreHigh + COMFORT_PADDING_SEMITONES
                maxOf(0, observedLow - (song.coreLow + key)) +
                    maxOf(0, (song.coreHigh + key) - observedHigh)
            }.thenByDescending { it.second.coverage }
                .thenByDescending { it.second.score }
                .thenBy { abs(it.first) }
                .thenBy { it.first }).first()
        }
        val fit = if (song.reliable) chosen.second else chosen.second.copy(score = 0f, softFit = 0f)
        return Result(chosen.first, fit)
    }

    /**
     * 부담 구간 (FIT-006). 키 [shift] 적용 후 음표가 사용자 안정 음역 상한 + 여유를 넘는 곳을 연속 구간으로 묶는다.
     * 서버에는 없는 앱 쪽 규칙. [gapSec] 이내로 이어지면 한 구간.
     */
    fun strainSections(notes: List<KaraokeNote>, shift: Int, userCoreHigh: Int, gapSec: Double = 1.0): List<StrainSection> {
        val limit = userCoreHigh + COMFORT_PADDING_SEMITONES
        val over = notes.asSequence()
            .filter { it.midi + shift > limit && it.sourceEnd > it.sourceStart }
            .sortedBy { it.sourceStart }
            .toList()
        val out = ArrayList<StrainSection>()
        var start = 0.0
        var end = 0.0
        var maxExcess = 0
        for (n in over) {
            val excess = n.midi + shift - limit
            if (maxExcess == 0 || n.sourceStart - end > gapSec) {   // 첫 음표 또는 간격 초과 → 새 구간
                if (maxExcess > 0) out += StrainSection(start, end, maxExcess)
                start = n.sourceStart; end = n.sourceEnd; maxExcess = excess
            } else {
                end = maxOf(end, n.sourceEnd); maxExcess = maxOf(maxExcess, excess)
            }
        }
        if (maxExcess > 0) out += StrainSection(start, end, maxExcess)
        return out
    }

    /** 원키와 추천 키 둘 다 (FIT-005 "적용 시 변화") */
    fun strainSectionsBoth(notes: List<KaraokeNote>, shift: Int, userCoreHigh: Int): StrainSections =
        StrainSections(original = strainSections(notes, 0, userCoreHigh), atKey = strainSections(notes, shift, userCoreHigh))
}
