package com.micon.app.data.practice

import com.micon.app.data.PracticeSection
import com.micon.app.scoring.Frame
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.ScoringConstants
import com.micon.app.scoring.Totals
import kotlin.math.floor
import kotlin.math.roundToInt

/**
 * 앱 라이브 채점(LiveScorer) 결과 → 백엔드 `PUT /practices/{id}/performance` 입력.
 *
 * - 점수: 종합·음정·박자 = [Totals] 그대로(0~100). 유지(sustain)는 라이브 채점에 없어 null.
 * - summaryText: 첫 줄은 사람이 읽는 한줄 평가, 둘째 줄은 판정 개수·보정·키(리포트 화면이 그대로 보여 준다). 500자 이내.
 * - sections: 백엔드 `song_section`(practice-assets 의 sections) 단위로 음표 판정을 모아 구간 점수를 낸다. 곡 시각이 구간 안인
 *   판정 음표만 센다. 구간이 20개를 넘으면 순서 앞 20개만(서버 한도).
 */
object PracticeScoreMapper {
    /** 음정 곡선 샘플 간격(ms). 캡처 20ms 프레임을 이 간격으로 묶어 중앙값을 낸다. */
    const val CURVE_HOP_MS = 100

    /**
     * @param frames 라이브 채점기가 모은 마이크 프레임(곡 시각 초, MIDI 또는 무성 null). 비어 있으면 곡선을 보내지 않는다.
     * @param dataVersion practice-assets 의 dataVersion — 서버가 notes 인덱스의 기준 음표 배열을 알 수 있게 함께 보낸다.
     */
    fun result(
        totals: Totals, notes: List<LiveNote>, sections: List<PracticeSection>, keyShift: Int, calibrationMs: Int,
        frames: List<Frame> = emptyList(), dataVersion: String? = null,
    ): PracticeResultInput =
        PracticeResultInput(
            status = if (totals.judged > 0) "COMPLETED" else "INSUFFICIENT_DATA",
            overallScore = totals.overall,
            pitchScore = totals.pitch,
            rhythmScore = totals.timing,
            sustainScore = null,
            summaryText = summaryText(totals, keyShift, calibrationMs),
            sections = sectionEvaluations(notes, sections),
            dataVersion = dataVersion,
            notes = judgedNotes(notes),
            curve = pitchCurve(frames),
        )

    /**
     * 절충안: 판정된 음표(GOOD·SHARP·FLAT·WRONG·MISSED)만 인덱스·상태·오차로 보낸다. UPCOMING/ACTIVE(미판정)와 판정 제외 음표는 뺀다.
     * SKIPPED(너무 짧아 판정 안 함)도 화면에서 "판정 없음" 으로 구분할 수 있게 포함한다. 서버 한도(600)를 넘으면 앞에서부터 자른다 —
     * 곡 뒷부분이 잘리는 것을 피하려면 한도 안에서는 그대로 두고, 넘을 때만 MISSED 부터 덜어낸다.
     */
    fun judgedNotes(notes: List<LiveNote>, limit: Int = RemotePracticeRepository.MAX_NOTES): List<JudgedNote> {
        val judged = notes.asSequence()
            .filter { it.judgeable && (it.status.judged || it.status == LiveStatus.SKIPPED) }
            .map { n ->
                val withMetrics = n.status != LiveStatus.MISSED && n.status != LiveStatus.SKIPPED
                JudgedNote(n.index, n.status.name, cents = if (withMetrics) n.cents else null, deviation = if (withMetrics) n.deviation else null)
            }
            .toList()
        if (judged.size <= limit) return judged
        val trimmed = judged.filter { it.status != LiveStatus.MISSED.name && it.status != LiveStatus.SKIPPED.name }
        return (if (trimmed.size <= limit) trimmed else trimmed.take(limit)).sortedBy { it.index }
    }

    /**
     * 20ms 프레임 → [CURVE_HOP_MS] 간격 곡선. 각 칸의 값은 유성 프레임 MIDI 의 중앙값, 유성 프레임이 없으면 null.
     * 프레임이 없거나 유성 프레임이 하나도 없으면 null(곡선 없음). 되감기·구간 반복으로 시각이 뒤섞일 수 있어 t 로 칸을 정한다.
     */
    fun pitchCurve(frames: List<Frame>, hopMs: Int = CURVE_HOP_MS, maxPoints: Int = RemotePracticeRepository.MAX_CURVE_POINTS): PitchCurve? {
        if (frames.isEmpty()) return null
        val valid = frames.filter { it.t.isFinite() && it.t >= 0 }
        if (valid.isEmpty()) return null
        val startMs = floor(valid.minOf { it.t } * 1000 / hopMs).toInt() * hopMs
        val lastMs = (valid.maxOf { it.t } * 1000).toInt()
        val bins = ((lastMs - startMs) / hopMs + 1).coerceAtMost(maxPoints)
        if (bins <= 0) return null
        val buckets = Array(bins) { ArrayList<Double>(6) }
        for (f in valid) {
            val midi = f.midi ?: continue
            val bin = ((f.t * 1000 - startMs) / hopMs).toInt()
            if (bin in 0 until bins) buckets[bin].add(midi)
        }
        val user = buckets.map { b -> if (b.isEmpty()) null else median(b) }
        if (user.all { it == null }) return null
        return PitchCurve(hopMs, startMs, user)
    }

    private fun median(values: MutableList<Double>): Double {
        values.sort()
        val mid = values.size / 2
        return if (values.size % 2 == 1) values[mid] else (values[mid - 1] + values[mid]) / 2
    }

    fun summaryText(totals: Totals, keyShift: Int, calibrationMs: Int): String {
        val counts = buildList {
            add("정확 ${totals.good}"); add("높게 ${totals.sharp}"); add("낮게 ${totals.flat}"); add("다른 음 ${totals.wrong}"); add("안 부름 ${totals.missed}")
            add("빠름 ${totals.early}"); add("늦음 ${totals.late}"); add("판정 ${totals.judged}")
            if (keyShift != 0) add("키 ${signed(keyShift)}")
            if (calibrationMs != 0) add("보정 ${calibrationMs}ms")
        }
        return (headline(totals) + "\n" + counts.joinToString(" · ")).take(RemotePracticeRepository.SUMMARY_MAX)
    }

    /** 숫자 대신 말로 먼저 — 정밀 리포트의 한줄 평가와 같은 기준(종합 점수·놓친 비율). */
    fun headline(totals: Totals): String {
        val judged = totals.judged
        val missedRatio = if (judged > 0) totals.missed.toDouble() / judged else 0.0
        val overall = totals.overall.roundToInt()
        return when {
            judged == 0 -> "부른 부분이 너무 짧아 평가하기 어려워요. 한 절 이상 불러 보세요."
            missedRatio > 0.5 -> "음표의 절반 이상이 비어 있어요. 마이크가 목소리를 잡았는지, 이어폰을 썼는지 확인해 보세요."
            overall >= 90 -> "훌륭해요! 음정과 박자가 원곡처럼 안정적이에요."
            overall >= 80 -> "잘 불렀어요. 음정이 대체로 정확하고 박자도 잘 맞았어요."
            overall >= 65 -> "괜찮아요. 전체 흐름은 좋고, 몇 구간만 다듬으면 점수가 크게 올라요."
            overall >= 50 -> "아직 흔들리는 구간이 있어요. 구간 반복으로 연습해 보세요."
            else -> "이 곡은 지금 음역보다 어려울 수 있어요. 키를 낮추거나 쉬운 구간부터 연습해 보세요."
        }
    }

    fun sectionEvaluations(notes: List<LiveNote>, sections: List<PracticeSection>): List<SectionEvaluation> =
        sections.sortedBy { it.sequenceNo }.distinctBy { it.sectionId }.take(RemotePracticeRepository.MAX_SECTIONS).map { section ->
            val inSection = notes.filter { it.judgeable && it.pitchScore != null && (it.start * 1000).roundToInt() in section.startMs until section.endMs }
            if (inSection.isEmpty()) {
                SectionEvaluation(section.sectionId, score = null, measured = false, summary = "${section.sectionName} · 부르지 않음")
            } else {
                var weight = 0.0; var pitch = 0.0; var timing = 0.0
                var good = 0; var sharp = 0; var flat = 0; var wrong = 0; var missed = 0
                for (n in inSection) {
                    weight += n.weight
                    pitch += (n.pitchScore ?: 0.0) * n.weight
                    timing += (n.timingScore ?: 0.0) * n.weight
                    when (n.status) {
                        LiveStatus.GOOD -> good++
                        LiveStatus.SHARP -> sharp++
                        LiveStatus.FLAT -> flat++
                        LiveStatus.WRONG -> wrong++
                        LiveStatus.MISSED -> missed++
                        else -> {}
                    }
                }
                val score = if (weight > 0) (ScoringConstants.WEIGHT_PITCH * pitch / weight + ScoringConstants.WEIGHT_TIMING * timing / weight) * 100 else 0.0
                SectionEvaluation(
                    section.sectionId, score = score.coerceIn(0.0, 100.0), measured = true,
                    summary = "${section.sectionName} · 정확 $good · 높게 $sharp · 낮게 $flat · 다른 음 $wrong · 안 부름 $missed".take(RemotePracticeRepository.SECTION_SUMMARY_MAX),
                )
            }
        }

    private fun signed(value: Int) = if (value > 0) "+$value" else "$value"
}
