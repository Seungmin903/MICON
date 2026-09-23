package com.micon.app.scoring

import com.micon.app.scoring.ScoringConstants.CALIBRATION_LIMIT_SECONDS
import com.micon.app.scoring.ScoringConstants.FRAME_HOP_SECONDS
import com.micon.app.scoring.ScoringConstants.LIVE_MIN_JUDGE_SECONDS
import com.micon.app.scoring.ScoringConstants.LIVE_MISSED_VOICED_RATIO
import com.micon.app.scoring.ScoringConstants.LIVE_ONSET_NEAR_SEMITONES
import com.micon.app.scoring.ScoringConstants.LIVE_ONSET_SEARCH_SECONDS
import com.micon.app.scoring.ScoringConstants.LIVE_PITCH_FULL_CENTS
import com.micon.app.scoring.ScoringConstants.LIVE_PITCH_GOOD_CENTS
import com.micon.app.scoring.ScoringConstants.LIVE_PITCH_WRONG_CENTS
import com.micon.app.scoring.ScoringConstants.LIVE_PITCH_ZERO_CENTS
import com.micon.app.scoring.ScoringConstants.LIVE_TIMING_FULL_SECONDS
import com.micon.app.scoring.ScoringConstants.LIVE_TIMING_GOOD_SECONDS
import com.micon.app.scoring.ScoringConstants.LIVE_TIMING_ZERO_SECONDS
import com.micon.app.scoring.ScoringConstants.LIVE_WINDOW_PAD_SECONDS
import com.micon.app.scoring.ScoringConstants.WEIGHT_PITCH
import com.micon.app.scoring.ScoringConstants.WEIGHT_TIMING
import com.micon.app.scoring.ScoringMath.clamp01
import com.micon.app.scoring.ScoringMath.fold
import com.micon.app.scoring.ScoringMath.median
import kotlin.math.abs
import kotlin.math.floor

/** 라이브 판정 상태. UPCOMING/ACTIVE 는 아직 판정 전. SKIPPED 는 너무 짧아 판정하지 않은 음표(총점 제외). */
enum class LiveStatus {
    UPCOMING, ACTIVE, GOOD, SHARP, FLAT, WRONG, MISSED, SKIPPED;

    val judged: Boolean get() = this != UPCOMING && this != ACTIVE && this != SKIPPED

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
    /** 가사 음절의 실제 발성 구간(초). 없으면 음표 start/end 를 쓴다. */
    val lyricStart: Double? = null,
    val lyricEnd: Double? = null,
) {
    /** 가사 채움 기준 시각: 음절 타이밍이 있으면 그것, 없으면 음표 시작. */
    val lyricAt: Double get() = lyricStart ?: start
    /** false 면 판정하지 않는다(가사 줄 밖에서 잡힌 코러스·허밍 등). ViewModel 이 가사 시트로 정한다. */
    var judgeable: Boolean = true
    var status: LiveStatus = LiveStatus.UPCOMING
    var cents: Int? = null
    /** 채점 시 키 이동을 반영한 목표 음(MIDI). 결과 화면에서 실제 음과 비교해 표시한다. */
    var judgedTargetMidi: Int? = null
    /** 유성 프레임 중앙값으로 구한 실제 음(MIDI). 옥타브를 보존한다. */
    var sungMidi: Double? = null
    /** 초, +늦음. 온셋을 못 찾으면 null(레가토). */
    var deviation: Double? = null
    var pitchScore: Double? = null
    var timingScore: Double? = null

    fun reset() {
        status = LiveStatus.UPCOMING; cents = null; judgedTargetMidi = null; sungMidi = null
        deviation = null; pitchScore = null; timingScore = null
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
 * 판정 기준은 ScoringConstants 의 LIVE_* (정밀 리포트보다 느슨함): 짧은 음표는 건너뛰고(SKIPPED), 실제 음높이
 * 프레임의 중앙값으로 음정 오차를 계산한다. ±70¢ 까지 정확, 2반음 초과는 다른 음.
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

    /**
     * [seconds] 이후 음표를 미판정으로 되돌리고 커서를 옮긴다(일시정지 중 탐색·구간 반복용).
     * 그 시각 이후의 프레임은 버린다. 이미 확정된 앞 음표의 판정은 유지한다.
     */
    fun rewindTo(seconds: Double) {
        var first = notes.size
        for (i in notes.indices) {
            if (notes[i].end + 0.25 > seconds) { first = i; break }
        }
        for (i in first until notes.size) notes[i].reset()
        cursor = minOf(cursor, first)
        val keep = frames.indexOfFirst { it.t >= seconds }
        if (keep >= 0) frames.subList(keep, frames.size).clear()
    }

    /**
     * [seconds] 로 앞으로 건너뛴다(간주·절 점프). 그 앞에서 끝나는 미판정 음표는 판정하지 않고 UPCOMING 으로 남겨
     * 총점에서 빠진다(놓침으로 세지 않는다). 뒤로 가는 경우는 [rewindTo] 를 쓴다.
     */
    fun skipTo(seconds: Double) {
        var first = notes.size
        for (i in notes.indices) {
            if (notes[i].end + 0.25 > seconds) { first = i; break }
        }
        for (i in cursor until minOf(first, notes.size)) if (notes[i].status == LiveStatus.ACTIVE) notes[i].reset()
        cursor = maxOf(cursor, first)
    }

    /** 재생 위치가 [seconds] 로 바뀌었을 때: 뒤로면 되감기, 앞으로면 건너뛰기. */
    fun jumpTo(seconds: Double, from: Double) {
        if (seconds < from) rewindTo(seconds) else skipTo(seconds)
    }

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
            if (!note.judgeable || note.end - note.start < LIVE_MIN_JUDGE_SECONDS) {
                note.status = LiveStatus.SKIPPED   // 가사 밖 음표·장식음·경과음: 판정하지 않는다
                cursor++
                continue
            }
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
        // 판정 창은 음표 앞뒤로 살짝 넓힌다 — 조금 이르거나 늦게 들어간 발성도 그 음표의 것으로 본다
        val window = framesBetween(start - LIVE_WINDOW_PAD_SECONDS, end + LIVE_WINDOW_PAD_SECONDS)
        val voiced = window.filter { it.midi != null }
        val expected = maxOf(1, Math.round((end - start) / FRAME_HOP_SECONDS).toInt())
        if (voiced.size < 3 || voiced.size.toDouble() / expected < LIVE_MISSED_VOICED_RATIO) {
            note.judgedTargetMidi = target
            note.status = LiveStatus.MISSED
            note.pitchScore = 0.0
            note.timingScore = 0.0
            return
        }
        val skip = floor(voiced.size * 0.15).toInt() // 어택 제외 (엔진과 동일)
        val body = if (voiced.size - skip >= 3) voiced.subList(skip, voiced.size) else voiced
        // 목표에 가까운 프레임을 고르면 옥타브 오류/배음 오검출을 목표음 쪽으로 끌어당긴다.
        // 프레임 전체의 중앙값은 목표와 독립적이므로 절대 옥타브를 보존하면서 스쿱·비브라토 이상치에 강하다.
        val sungMidi = median(body.map { it.midi!! })
        val cents = (sungMidi - target) * 100
        note.judgedTargetMidi = target
        note.sungMidi = sungMidi
        note.cents = Math.round(cents).toInt()
        note.status = when {
            abs(cents) <= LIVE_PITCH_GOOD_CENTS -> LiveStatus.GOOD
            abs(cents) > LIVE_PITCH_WRONG_CENTS -> LiveStatus.WRONG
            cents > 0 -> LiveStatus.SHARP
            else -> LiveStatus.FLAT
        }
        note.pitchScore = clamp01(1 - maxOf(0.0, abs(cents) - LIVE_PITCH_FULL_CENTS) / (LIVE_PITCH_ZERO_CENTS - LIVE_PITCH_FULL_CENTS))

        // 온셋: 시작 ±0.4 s 에서 목표 ±1 st 에 3프레임 연속 진입한 첫 시각
        val search = framesBetween(start - LIVE_ONSET_SEARCH_SECONDS, start + LIVE_ONSET_SEARCH_SECONDS + 0.06)
        var onset: Double? = null
        fun near(frame: Frame) = frame.midi != null && abs(fold(frame.midi - target)) <= LIVE_ONSET_NEAR_SEMITONES
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
            note.timingScore = clamp01(1 - maxOf(0.0, abs(raw) - LIVE_TIMING_FULL_SECONDS) / (LIVE_TIMING_ZERO_SECONDS - LIVE_TIMING_FULL_SECONDS))
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
                    if (deviation < -LIVE_TIMING_GOOD_SECONDS) early++ else if (deviation > LIVE_TIMING_GOOD_SECONDS) late++
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
