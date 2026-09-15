package com.micon.app.data.practice

import com.micon.app.data.PracticeSection
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.ScoringConstants
import com.micon.app.scoring.Totals
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
    fun result(totals: Totals, notes: List<LiveNote>, sections: List<PracticeSection>, keyShift: Int, calibrationMs: Int): PracticeResultInput =
        PracticeResultInput(
            status = if (totals.judged > 0) "COMPLETED" else "INSUFFICIENT_DATA",
            overallScore = totals.overall,
            pitchScore = totals.pitch,
            rhythmScore = totals.timing,
            sustainScore = null,
            summaryText = summaryText(totals, keyShift, calibrationMs),
            sections = sectionEvaluations(notes, sections),
        )

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
