package com.micon.app.data.practice

import com.micon.app.data.PracticeSection
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import com.micon.app.scoring.Totals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PracticeScoreMapperTest {
    private fun totals(overall: Double, judged: Int = 20, missed: Int = 2) = Totals(
        pitch = 80.0, timing = 70.0, overall = overall, judged = judged, good = 12, sharp = 3, flat = 2, wrong = 1, missed = missed,
        early = 2, late = 5, centsSum = 40, devSum = 1.2, sung = judged - missed, calibration = -0.12,
    )

    private fun section(id: Long, name: String, startMs: Int, endMs: Int, seq: Int) =
        PracticeSection(sectionId = id, sectionType = "VOCAL", sectionName = name, startMs = startMs, endMs = endMs, sequenceNo = seq, difficultyType = "NORMAL", difficultyScore = null, description = null)

    private fun note(index: Int, start: Double, status: LiveStatus, pitch: Double?, timing: Double? = 1.0, judgeable: Boolean = true) =
        LiveNote(index, start, start + 0.5, 60, "라", 0, 1.0).also { it.status = status; it.pitchScore = pitch; it.timingScore = timing; it.judgeable = judgeable }

    @Test fun `summary text starts with a headline and lists counts within the limit`() {
        val text = PracticeScoreMapper.summaryText(totals(84.0), keyShift = -2, calibrationMs = -120)
        val lines = text.lines()
        assertEquals("잘 불렀어요. 음정이 대체로 정확하고 박자도 잘 맞았어요.", lines[0])
        assertTrue(lines[1].contains("정확 12") && lines[1].contains("안 부름 2") && lines[1].contains("키 -2") && lines[1].contains("보정 -120ms"))
        assertTrue(text.length <= RemotePracticeRepository.SUMMARY_MAX)
    }

    @Test fun `headline follows score and missed ratio`() {
        assertTrue(PracticeScoreMapper.headline(totals(95.0)).startsWith("훌륭해요"))
        assertTrue(PracticeScoreMapper.headline(totals(55.0)).contains("흔들리는"))
        assertTrue(PracticeScoreMapper.headline(totals(90.0, judged = 10, missed = 6)).contains("절반 이상"))
        assertTrue(PracticeScoreMapper.headline(totals(0.0, judged = 0, missed = 0)).contains("너무 짧아"))
    }

    @Test fun `section evaluations aggregate judged notes inside each backend section`() {
        val sections = listOf(section(502, "2절", 60_000, 120_000, 2), section(501, "1절", 0, 60_000, 1), section(503, "후렴", 120_000, 180_000, 3))
        val notes = listOf(
            note(0, 1.0, LiveStatus.GOOD, 1.0),
            note(1, 2.0, LiveStatus.SHARP, 0.5),
            note(2, 3.0, LiveStatus.MISSED, 0.0, timing = 0.0),
            note(3, 65.0, LiveStatus.GOOD, 1.0),
            note(4, 70.0, LiveStatus.UPCOMING, null),                 // 판정 전 → 제외
            note(5, 130.0, LiveStatus.GOOD, 1.0, judgeable = false), // 가사 밖 → 제외
        )
        val evaluations = PracticeScoreMapper.sectionEvaluations(notes, sections)
        assertEquals(listOf(501L, 502L, 503L), evaluations.map { it.sectionId })   // sequenceNo 순
        val first = evaluations[0]
        assertTrue(first.measured)
        assertEquals(50.0 * 0.65 + (2.0 / 3.0) * 100 * 0.35, first.score!!, 1e-6)  // 음정 (1+0.5+0)/3, 박자 (1+1+0)/3
        assertTrue(first.summary!!.startsWith("1절 · 정확 1 · 높게 1 · 낮게 0 · 다른 음 0 · 안 부름 1"))
        assertTrue(evaluations[1].measured); assertEquals(100.0, evaluations[1].score!!, 1e-9)
        assertFalse(evaluations[2].measured); assertNull(evaluations[2].score); assertEquals("후렴 · 부르지 않음", evaluations[2].summary)
    }

    @Test fun `section list is capped at the backend limit and deduplicated`() {
        val sections = (1..25).map { section(it.toLong(), "구간 $it", (it - 1) * 1000, it * 1000, it) } + section(1, "중복", 0, 1000, 1)
        val evaluations = PracticeScoreMapper.sectionEvaluations(emptyList(), sections)
        assertEquals(RemotePracticeRepository.MAX_SECTIONS, evaluations.size)
        assertEquals((1..20).map { it.toLong() }, evaluations.map { it.sectionId })
    }

    @Test fun `result marks insufficient data when nothing was judged`() {
        val result = PracticeScoreMapper.result(totals(0.0, judged = 0, missed = 0), emptyList(), emptyList(), keyShift = 0, calibrationMs = 0)
        assertEquals("INSUFFICIENT_DATA", result.status)
        assertNull(result.sustainScore)
        val completed = PracticeScoreMapper.result(totals(84.0), emptyList(), emptyList(), keyShift = 1, calibrationMs = 0)
        assertEquals("COMPLETED", completed.status)
        assertEquals(84.0, completed.overallScore!!, 1e-9); assertEquals(80.0, completed.pitchScore!!, 1e-9); assertEquals(70.0, completed.rhythmScore!!, 1e-9)
    }
}
