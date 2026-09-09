package com.micon.app.ui.result

import com.micon.app.data.engine.dto.Comparison
import com.micon.app.scoring.KaraokeNames
import com.micon.app.scoring.PitchStatus
import com.micon.app.scoring.ScoringMath

/** 정밀 리포트 맨 위의 한줄 평가 + 짧은 조언 (최대 3개). 숫자 그래프를 읽지 못하는 사용자를 위한 말 버전. */
data class ReportSummary(val headline: String, val tips: List<String>)

object ReportSummaries {
    fun build(c: Comparison): ReportSummary {
        val overall = c.scores.overall.toInt()
        val ps = c.pitchStats
        val ts = c.timingStats
        val good = ps?.good ?: 0
        val sharp = ps?.sharp ?: 0
        val flat = ps?.flat ?: 0
        val wrong = ps?.wrong ?: 0
        val missed = ps?.missed ?: 0
        val judged = good + sharp + flat + wrong + missed
        val sung = judged - missed
        val missedRatio = if (judged > 0) missed.toDouble() / judged else 0.0
        val goodRatio = if (sung > 0) good.toDouble() / sung else 0.0

        val headline = when {
            judged == 0 -> "부른 부분이 너무 짧아 평가하기 어려워요. 한 절 이상 불러 보세요."
            missedRatio > 0.5 -> "음표의 절반 이상이 비어 있어요. 마이크가 목소리를 잡았는지, 이어폰을 썼는지 확인해 보세요."
            overall >= 90 -> "훌륭해요! 음정과 박자가 원곡처럼 안정적이에요."
            overall >= 80 -> "잘 불렀어요. 음정이 대체로 정확하고 박자도 잘 맞았어요."
            overall >= 65 -> "괜찮아요. 전체 흐름은 좋고, 몇 구간만 다듬으면 점수가 크게 올라요."
            overall >= 50 -> "아직 흔들리는 구간이 있어요. 아래 구간을 구간 반복으로 연습해 보세요."
            else -> "이 곡은 지금 음역보다 어려울 수 있어요. 키를 낮추거나 쉬운 구간부터 연습해 보세요."
        }

        val tips = ArrayList<String>()
        if (sung > 0) {
            val mean = ps?.meanCents?.toInt()
            when {
                sharp > flat * 2 && sharp >= sung * 0.2 ->
                    tips.add("음이 전체적으로 높게 나는 편이에요" + (mean?.let { " (평균 ${if (it > 0) "+$it" else "$it"}¢)" } ?: "") + ". 힘을 조금 빼고 목표 음보다 살짝 낮게 잡아 보세요.")
                flat > sharp * 2 && flat >= sung * 0.2 ->
                    tips.add("음이 전체적으로 낮게 처지는 편이에요" + (mean?.let { " (평균 ${it}¢)" } ?: "") + ". 소리를 앞으로 보내며 호흡으로 받쳐 주세요.")
                wrong >= sung * 0.25 ->
                    tips.add("다른 음으로 부른 음표가 많아요. 원곡을 한 번 더 들으며 멜로디를 익혀 보세요.")
                goodRatio >= 0.8 ->
                    tips.add("음정 정확도가 높아요(${(goodRatio * 100).toInt()}%). 이 감각을 그대로 유지하세요.")
            }
        }
        if (ts != null) {
            val judgedTiming = ts.good + ts.early + ts.late + ts.off
            if (judgedTiming > 0) {
                when {
                    ts.late >= judgedTiming * 0.3 ->
                        tips.add("박자가 늦게 들어가는 편이에요" + (ts.meanDeviationMs?.let { " (평균 ${it.toInt()}ms)" } ?: "") + ". 가사가 채워지기 살짝 전에 소리를 내 보세요.")
                    ts.early >= judgedTiming * 0.3 ->
                        tips.add("박자가 앞서는 편이에요. 반주를 한 박 더 듣고 들어가 보세요.")
                    ts.good >= judgedTiming * 0.7 ->
                        tips.add("박자 감각이 좋아요.")
                }
            }
        }
        if (judged > 0 && missedRatio in 0.15..0.5) {
            tips.add("부르지 않은 음표가 ${(missedRatio * 100).toInt()}% 있어요. 긴 음은 끝까지 유지해 보세요.")
        }
        c.scores.stabilityCents?.let { if (it > 60) tips.add("한 음을 유지할 때 흔들림이 커요(±${it.toInt()}¢). 한 음을 길게 곧게 내는 연습이 도움이 돼요.") }

        val covered = c.notes.filter { it.covered }
        if (covered.isNotEmpty()) {
            val highest = covered.sortedByDescending { it.midi }.take(maxOf(3, covered.size / 10))
            val shaky = highest.count { it.pitchStatus != PitchStatus.GOOD.key }
            if (shaky >= highest.size * 0.6) {
                tips.add("고음 구간(${KaraokeNames.korean(highest.first().midi)} 근처)에서 흔들려요. 키를 1~2 낮춰 연습하고 점차 올려 보세요.")
            }
        }
        c.issues.maxByOrNull { it.severity }?.let { issue ->
            val lyric = issue.lyric?.takeIf { it.isNotBlank() }?.let { " \"$it\"" } ?: ""
            tips.add("가장 아쉬운 구간은 ${ScoringMath.formatTime(issue.start)}$lyric 이에요. 연습 화면에서 그 가사 줄을 눌러 구간 반복해 보세요.")
        }
        return ReportSummary(headline, tips.take(3))
    }
}
