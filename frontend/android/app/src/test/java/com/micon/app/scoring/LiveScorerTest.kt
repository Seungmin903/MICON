package com.micon.app.scoring

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** live-scoring.tsx `LiveScorer.judge` 의 골든 케이스 — 합성 프레임으로 판정 규칙을 고정한다. */
class LiveScorerTest {
    private fun note(start: Double, end: Double, midi: Int, index: Int = 0, measure: Int = 0) =
        LiveNote(index = index, start = start, end = end, midi = midi, lyric = null, measure = measure, weight = (end - start).coerceIn(0.1, 1.5))

    /** [from, to) 구간을 20 ms 간격으로 채운 프레임 */
    private fun LiveScorer.feed(from: Double, to: Double, midi: Double?) {
        var t = from
        while (t < to - 1e-9) {
            addFrame(Frame(t, midi))
            t += 0.02
        }
    }

    @Test
    fun `note sung 20 cents sharp is good with pitch score 1`() {
        val scorer = LiveScorer(listOf(note(1.0, 1.5, 60)))
        scorer.feed(0.0, 1.0, null)
        scorer.feed(1.0, 1.5, 60.2)
        scorer.feed(1.5, 2.0, null)
        assertTrue(scorer.tick(1.6).isEmpty())          // end + 0.25 전에는 확정 안 함
        val done = scorer.tick(1.8)
        assertEquals(1, done.size)
        val judged = done.first()
        assertEquals(LiveStatus.GOOD, judged.status)
        assertEquals(20, judged.cents)
        assertEquals(1.0, judged.pitchScore!!, 1e-9)    // 30¢ 이하 → 만점
        assertNotNull(judged.deviation)
        assertTrue(abs(judged.deviation!!) < 0.03)      // 정박
        assertEquals(1.0, judged.timingScore!!, 1e-9)
    }

    @Test
    fun `60 cents high is sharp, 200 cents is wrong, octave error is folded`() {
        val scorer = LiveScorer(listOf(note(1.0, 1.5, 60, 0), note(2.0, 2.5, 60, 1), note(3.0, 3.5, 60, 2)))
        scorer.feed(1.0, 1.5, 60.6)      // +60¢
        scorer.feed(2.0, 2.5, 62.0)      // +200¢
        scorer.feed(3.0, 3.5, 72.1)      // 한 옥타브 위 +10¢ → fold → good
        val done = scorer.tick(4.0)
        assertEquals(listOf(LiveStatus.SHARP, LiveStatus.WRONG, LiveStatus.GOOD), done.map { it.status })
        assertEquals(60, done[0].cents)
        assertEquals(200, done[1].cents)
        assertEquals(10, done[2].cents)
        assertEquals(0.0, done[1].pitchScore!!, 1e-9)   // 150¢ 이상 → 0
    }

    @Test
    fun `silence during a note is missed`() {
        val scorer = LiveScorer(listOf(note(1.0, 1.5, 60)))
        scorer.feed(0.0, 2.0, null)
        val done = scorer.tick(2.0)
        assertEquals(LiveStatus.MISSED, done.single().status)
        assertEquals(0.0, done.single().pitchScore!!, 0.0)
        assertEquals(0.0, done.single().timingScore!!, 0.0)
    }

    @Test
    fun `late onset lowers timing score and legato without onset is neutral`() {
        val scorer = LiveScorer(listOf(note(1.0, 1.6, 60, 0), note(1.6, 2.2, 62, 1)))
        scorer.feed(1.2, 2.2, 60.0)      // 200 ms 늦게 시작, 두 번째 음표는 60 을 계속 부름(레가토, 온셋 없음)
        val done = scorer.tick(3.0)
        val first = done[0]
        assertEquals(LiveStatus.GOOD, first.status)
        assertTrue("deviation ${first.deviation}", abs(first.deviation!! - 0.2) < 0.03)
        // 1 - (0.2-0.06)/(0.4-0.06) ≈ 0.588
        assertEquals(0.588, first.timingScore!!, 0.05)
        val second = done[1]
        assertEquals(LiveStatus.WRONG, second.status)   // 62 목표에 60 → −200¢
        assertNull(second.deviation)
        assertEquals(0.5, second.timingScore!!, 1e-9)
    }

    @Test
    fun `totals and measures weight by duration and apply 65-35 blend`() {
        val scorer = LiveScorer(listOf(note(1.0, 1.5, 60, 0, measure = 0), note(2.0, 2.5, 60, 1, measure = 0), note(3.0, 3.5, 60, 2, measure = 1)))
        scorer.feed(1.0, 1.5, 60.0)
        scorer.feed(2.0, 2.5, 60.0)
        scorer.feed(3.0, 3.5, null)     // missed
        scorer.tick(4.0)
        val totals = scorer.totals()
        assertEquals(3, totals.judged)
        assertEquals(2, totals.good)
        assertEquals(1, totals.missed)
        // 음정 (1+1+0)/3 → 66.7, 박자 (1+1+0)/3 → 66.7, overall 동일
        assertEquals(66.67, totals.pitch, 0.1)
        assertEquals(66.67, totals.overall, 0.1)
        assertEquals("C", totals.grade)
        val measures = scorer.measures()
        assertEquals(listOf(1, 2), measures.map { it.measure })
        assertEquals(100, measures[0].score)
        assertEquals(0, measures[1].score)
    }

    @Test
    fun `auto calibration converges to the median device latency`() {
        val notes = (0 until 8).map { i -> note(1.0 + i * 1.0, 1.5 + i * 1.0, 60, i) }
        val scorer = LiveScorer(notes)
        for (i in 0 until 8) scorer.feed(1.15 + i, 1.65 + i, 60.0)   // 항상 150 ms 늦게 들림
        scorer.tick(10.0)
        val totals = scorer.totals()
        assertEquals(0.15, totals.calibration, 0.03)
        // 보정이 걸린 뒤의 음표는 정박으로 판정된다
        assertTrue(abs(notes.last().deviation!!) < 0.05)
    }
}
