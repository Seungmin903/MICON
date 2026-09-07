package com.micon.app.scoring

import com.micon.app.scoring.ScoringConstants.CALIBRATION_LIMIT_SECONDS
import com.micon.app.scoring.ScoringConstants.FRAME_HOP_SECONDS
import com.micon.app.scoring.ScoringConstants.MISSED_VOICED_RATIO
import com.micon.app.scoring.ScoringConstants.ONSET_NEAR_SEMITONES
import com.micon.app.scoring.ScoringConstants.ONSET_SEARCH_SECONDS
import com.micon.app.scoring.ScoringConstants.PITCH_FULL_CENTS
import com.micon.app.scoring.ScoringConstants.PITCH_GOOD_CENTS
import com.micon.app.scoring.ScoringConstants.PITCH_WRONG_CENTS
import com.micon.app.scoring.ScoringConstants.PITCH_ZERO_CENTS
import com.micon.app.scoring.ScoringConstants.TIMING_FULL_SECONDS
import com.micon.app.scoring.ScoringConstants.TIMING_GOOD_SECONDS
import com.micon.app.scoring.ScoringConstants.TIMING_ZERO_SECONDS
import com.micon.app.scoring.ScoringConstants.WEIGHT_PITCH
import com.micon.app.scoring.ScoringConstants.WEIGHT_TIMING
import com.micon.app.scoring.ScoringMath.clamp01
import com.micon.app.scoring.ScoringMath.fold
import com.micon.app.scoring.ScoringMath.median
import kotlin.math.abs
import kotlin.math.floor

/** 라이브 판정 상태. UPCOMING/ACTIVE 는 아직 판정 전. */
enum class LiveStatus {
    UPCOMING, ACTIVE, GOOD, SHARP, FLAT, WRONG, MISSED;

    val judged: Boolean get() = this != UPCOMING && this != ACTIVE

    fun toPitchStatus(): PitchStatus? = when (this) {
        GOOD -> PitchStatus.GOOD
        SHARP -> PitchStatus.SHARP
        FLAT -> PitchStatus.FLAT
        WRONG -> PitchStatus.WRONG
        MISSED -> PitchStatus.MISSED
        else -> null
    }
}

/** 원곡 음표 하나 + 라이브 판정 결과. midi 는 원곡 기준(키 이동 전). 시간은 MR(원곡) 초. */
class LiveNote(
    val index: Int,
    val start: Double,
    val end: Double,
    val midi: Int,
    val lyric: String?,
    val measure: Int,
    val weight: Double,
) {
    var status: LiveStatus = LiveStatus.UPCOMING
    var cents: Int? = null
    /** 초, +늦음. 온셋을 못 찾으면 null(레가토). */
    var deviation: Double? = null
    var pitchScore: Double? = null
    var timingScore: Double? = null

    fun reset() {
        status = LiveStatus.UPCOMING; cents = null; deviation = null; pitchScore = null; timingScore = null
    }
}

/** 마이크 프레임. t = MR(원곡) 초, midi = null 이면 무성. */
data class Frame(val t: Double, val midi: Double?)

data class Totals(
    val pitch: Double, val timing: Double, val overall: Double,
    val judged: Int, val good: Int, val sharp: Int, val flat: Int, val wrong: Int, val missed: Int,
    val early: Int, val late: Int, val centsSum: Int, val devSum: Double, val sung: Int,
    val calibration: Double,
) {
    val grade: String get() = ScoringMath.gradeOf(overall)
}

data class MeasureRow(val measure: Int, val score: Int, val pitch: Int, val timing: Int, val notes: Int)

/**
 * 음표별 라이브 판정 (live-scoring.tsx `class LiveScorer` L88–227 포팅). UI 와 분리된 순수 로직.
 * 재생 위치(now)를 알기 때문에 DTW 없이 음표가 끝나는 즉시 판정한다.
 * 스레드 안전하지 않음 — 한 스레드(오디오 콜백 → 채널 → 단일 코루틴)에서만 호출할 것.
 */
class LiveScorer(val notes: List<LiveNote>) {
    val frames: ArrayList<Frame> = ArrayList(4096)
    private val rawDeviations = ArrayList<Double>(48)
    /** 자동 지연 보정(초). 양수 = 사용자가 늦게 들리는 장비. */
    var calibration = 0.0
        private set
    var autoCalibrate = true
    var keyShift = 0
    private var cursor = 0

    fun addFrame(frame: Frame) {
        frames.add(frame)
        if (frames.size > 3000) frames.subList(0, 1000).clear() // 최근 ~60 s 만 유지
    }

    /** now(MR 초)까지 끝난 음표를 확정하고 그 목록을 돌려준다. */
    fun tick(now: Double): List<LiveNote> {
        val done = ArrayList<LiveNote>(2)
        while (cursor < notes.size) {
            val note = notes[cursor]
            if (note.start - 0.05 <= now && note.end + 0.2 > now && note.status == LiveStatus.UPCOMING) {
                note.status = LiveStatus.ACTIVE
            }
            if (note.end + calibration + 0.25 > now) break
            judge(note)
            done.add(note)
            cursor++
        }
        return done
    }

    /** 뒤에서부터 훑어 [from, to] 프레임만 (O(window)). */
    private fun framesBetween(from: Double, to: Double): List<Frame> {
        val result = ArrayList<Frame>()
        for (i in frames.indices.reversed()) {
            val frame = frames[i]
            if (frame.t < from) break
            if (frame.t <= to) result.add(frame)
        }
        result.reverse()
        return result
    }

    internal fun judge(note: LiveNote) {
        val target = note.midi + keyShift
        val start = note.start + calibration
        val end = note.end + calibration
        val window = framesBetween(start, end)
        val voiced = window.filter { it.midi != null }
        val expected = maxOf(1, Math.round((end - start) / FRAME_HOP_SECONDS).toInt())
        if (voiced.size < 3 || voiced.size.toDouble() / expected < MISSED_VOICED_RATIO) {
            note.status = LiveStatus.MISSED
            note.pitchScore = 0.0
            note.timingScore = 0.0
            return
        }
        val skip = floor(voiced.size * 0.15).toInt() // 어택 제외 (엔진과 동일)
        val body = if (voiced.size - skip >= 3) voiced.subList(skip, voiced.size) else voiced
        val errors = body.map { fold(it.midi!! - target) }
        val cents = median(errors) * 100
        note.cents = Math.round(cents).toInt()
        note.status = when {
            abs(cents) <= PITCH_GOOD_CENTS -> LiveStatus.GOOD
            abs(cents) > PITCH_WRONG_CENTS -> LiveStatus.WRONG
            cents > 0 -> LiveStatus.SHARP
            else -> LiveStatus.FLAT
        }
        note.pitchScore = clamp01(1 - maxOf(0.0, abs(cents) - PITCH_FULL_CENTS) / (PITCH_ZERO_CENTS - PITCH_FULL_CENTS))

        // 온셋: 시작 ±0.3 s 에서 목표 ±0.6 st 에 3프레임 연속 진입한 첫 시각
        val search = framesBetween(start - ONSET_SEARCH_SECONDS, start + ONSET_SEARCH_SECONDS + 0.06)
        var onset: Double? = null
        fun near(frame: Frame) = frame.midi != null && abs(fold(frame.midi - target)) <= ONSET_NEAR_SEMITONES
        var i = 0
        while (i + 2 < search.size) {
            if (near(search[i]) && near(search[i + 1]) && near(search[i + 2])) {
                onset = search[i].t
                break
            }
            i++
        }
        if (onset != null) {
            val raw = onset - (note.start + calibration)
            rawDeviations.add(raw + calibration) // 보정 전 기준으로 축적
            if (rawDeviations.size > 40) rawDeviations.removeAt(0)
            if (autoCalibrate && rawDeviations.size >= 6) {
                calibration = median(rawDeviations).coerceIn(-CALIBRATION_LIMIT_SECONDS, CALIBRATION_LIMIT_SECONDS)
            }
            note.deviation = raw
            note.timingScore = clamp01(1 - maxOf(0.0, abs(raw) - TIMING_FULL_SECONDS) / (TIMING_ZERO_SECONDS - TIMING_FULL_SECONDS))
        } else {
            note.deviation = null
            note.timingScore = 0.5 // 온셋 불명(레가토 연속) — 중립
        }
    }

    fun totals(): Totals {
        var weight = 0.0
        var pitch = 0.0
        var timing = 0.0
        var judged = 0; var good = 0; var sharp = 0; var flat = 0; var wrong = 0; var missed = 0
        var early = 0; var late = 0; var centsSum = 0; var devSum = 0.0; var sung = 0
        for (note in notes) {
            val pitchScore = note.pitchScore ?: continue
            judged++
            weight += note.weight
            pitch += pitchScore * note.weight
            timing += (note.timingScore ?: 0.0) * note.weight
            if (note.status == LiveStatus.MISSED) {
                missed++
            } else {
                sung++
                when (note.status) {
                    LiveStatus.GOOD -> good++
                    LiveStatus.SHARP -> sharp++
                    LiveStatus.FLAT -> flat++
                    LiveStatus.WRONG -> wrong++
                    else -> {}
                }
                centsSum += note.cents ?: 0
                val deviation = note.deviation
                if (deviation != null) {
                    devSum += deviation
                    if (deviation < -TIMING_GOOD_SECONDS) early++ else if (deviation > TIMING_GOOD_SECONDS) late++
                }
            }
        }
        var pitchPct = 0.0; var timingPct = 0.0; var overall = 0.0
        if (weight > 0) {
            pitchPct = (pitch / weight) * 100
            timingPct = (timing / weight) * 100
            overall = WEIGHT_PITCH * pitchPct + WEIGHT_TIMING * timingPct
        }
        return Totals(pitchPct, timingPct, overall, judged, good, sharp, flat, wrong, missed, early, late, centsSum, devSum, sung, calibration)
    }

    fun measures(): List<MeasureRow> {
        class Bucket { var pitch = 0.0; var timing = 0.0; var weight = 0.0; var notes = 0 }
        val buckets = sortedMapOf<Int, Bucket>()
        for (note in notes) {
            val pitchScore = note.pitchScore ?: continue
            val bucket = buckets.getOrPut(note.measure) { Bucket() }
            bucket.pitch += pitchScore * note.weight
            bucket.timing += (note.timingScore ?: 0.0) * note.weight
            bucket.weight += note.weight
            bucket.notes++
        }
        return buckets.map { (measure, bucket) ->
            val pitch = Math.round((bucket.pitch / bucket.weight) * 100).toInt()
            val timing = Math.round((bucket.timing / bucket.weight) * 100).toInt()
            MeasureRow(
                measure = measure + 1, pitch = pitch, timing = timing, notes = bucket.notes,
                score = Math.round(WEIGHT_PITCH * pitch + WEIGHT_TIMING * timing).toInt(),
            )
        }
    }
}
