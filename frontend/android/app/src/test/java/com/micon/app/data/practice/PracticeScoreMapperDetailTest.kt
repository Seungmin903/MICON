package com.micon.app.data.practice

import com.micon.app.scoring.Frame
import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.LiveStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 절충안 확장 — 노트 판정 목록과 100ms 음정 곡선 생성 규칙을 고정한다. */
class PracticeScoreMapperDetailTest {
    private fun note(index: Int, status: LiveStatus, cents: Int? = null, deviation: Double? = null, judgeable: Boolean = true) =
        LiveNote(index = index, start = index.toDouble(), end = index + 0.5, midi = 60, lyric = "가", measure = 0, weight = 0.5).also {
            it.judgeable = judgeable; it.status = status; it.cents = cents; it.deviation = deviation
        }

    @Test
    fun `judged notes keep index and metrics, drop unjudged and non-judgeable`() {
        val notes = listOf(
            note(0, LiveStatus.GOOD, cents = 12, deviation = 0.04),
            note(1, LiveStatus.FLAT, cents = -45, deviation = 0.2),
            note(2, LiveStatus.MISSED, cents = null),
            note(3, LiveStatus.UPCOMING),
            note(4, LiveStatus.ACTIVE),
            note(5, LiveStatus.SKIPPED),
            note(6, LiveStatus.GOOD, cents = 3, judgeable = false),
        )
        val out = PracticeScoreMapper.judgedNotes(notes)
        assertEquals(listOf(0, 1, 2, 5), out.map { it.index })
        assertEquals("FLAT", out[1].status)
        assertEquals(-45, out[1].cents)
        assertEquals(0.2, out[1].deviation!!, 1e-9)
        assertNull(out[2].cents)          // MISSED 는 오차 없음
        assertEquals("SKIPPED", out[3].status)
    }

    @Test
    fun `over the limit, missed and skipped are dropped first`() {
        val notes = (0 until 10).map { i -> note(i, if (i % 2 == 0) LiveStatus.GOOD else LiveStatus.MISSED, cents = 0) }
        val out = PracticeScoreMapper.judgedNotes(notes, limit = 6)
        assertEquals(5, out.size)
        assertTrue(out.all { it.status == "GOOD" })
        assertEquals(listOf(0, 2, 4, 6, 8), out.map { it.index })
    }

    @Test
    fun `pitch curve bins 20ms frames into 100ms medians and keeps unvoiced gaps`() {
        val frames = buildList {
            // 1.00~1.10 s: 5프레임 유성(60, 60.2, 60.4, 70(튐), 60.1) → 중앙값 60.2
            listOf(60.0, 60.2, 60.4, 70.0, 60.1).forEachIndexed { i, m -> add(Frame(1.0 + i * 0.02, m)) }
            // 1.10~1.20 s: 무성
            repeat(5) { i -> add(Frame(1.1 + i * 0.02, null)) }
            // 1.20~1.30 s: 유성 62
            repeat(5) { i -> add(Frame(1.2 + i * 0.02, 62.0)) }
        }
        val curve = PracticeScoreMapper.pitchCurve(frames)!!
        assertEquals(100, curve.hopMs)
        assertEquals(1000, curve.startMs)
        assertEquals(3, curve.user.size)
        assertEquals(60.2, curve.user[0]!!, 1e-9)
        assertNull(curve.user[1])
        assertEquals(62.0, curve.user[2]!!, 1e-9)
    }

    @Test
    fun `pitch curve is null when there is no voiced frame`() {
        assertNull(PracticeScoreMapper.pitchCurve(emptyList()))
        assertNull(PracticeScoreMapper.pitchCurve(listOf(Frame(0.0, null), Frame(0.02, null))))
    }

    @Test
    fun `pitch curve respects the point cap`() {
        val frames = (0 until 20_000).map { i -> Frame(i * 0.02, 60.0) }   // 400 s → 4,000 칸 초과
        val curve = PracticeScoreMapper.pitchCurve(frames, maxPoints = 100)!!
        assertEquals(100, curve.user.size)
    }
}
