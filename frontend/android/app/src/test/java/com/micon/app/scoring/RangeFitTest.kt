package com.micon.app.scoring

import com.micon.app.data.SongDataFixture
import com.micon.app.data.backend.dto.KaraokeNote
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** recommend-algo pitch_range.py 규칙 포팅 검증. 곡은 실제 엔진 픽스처(tj-14260: F3~D5, 1152 음표). */
class RangeFitTest {
    private val songNotes: List<KaraokeNote> by lazy { SongDataFixture.karaokeNotes("tj-14260.job.json") }
    private val song by lazy { checkNotNull(PitchProfile.fromNotes(songNotes)) }

    @Test
    fun `song profile from notes has ordered core and unit mass`() {
        assertTrue(song.coreLow <= song.median && song.median <= song.coreHigh)
        assertTrue(song.coreLow >= 53 && song.coreHigh <= 74)              // 픽스처 lowest/highest 안
        assertEquals(1.0, song.histogram.sum(), 1e-9)
        assertNull(PitchProfile.fromNotes(emptyList()))
    }

    @Test
    fun `song inside stable range keeps original key with full coverage`() {
        // 안정 음역이 곡 core 를 감싸고 중앙음도 같은 "딱 맞는 목소리". 중앙음이 어긋나면 알고리즘은 커버리지가 유지되는 한 중앙을 맞추러 키를 옮긴다(서버 동일).
        val wide = PitchProfile.ofStableRange(song.coreLow - 1, song.coreHigh + 1)
        val user = PitchProfile(wide.histogram, wide.coreLow, song.median, wide.coreHigh)
        val r = RangeFitCalculator.best(user, song)
        assertEquals(0, r.keyShift)
        assertTrue("coverage=${r.fit.coverage}", r.fit.coverage >= 0.9f)
        assertTrue(r.fit.score > 0.9f)
    }

    @Test
    fun `high song for a low voice is shifted down and strain shrinks`() {
        val user = PitchProfile.ofStableRange(song.coreLow - 4, song.coreHigh - 4)
        val r = RangeFitCalculator.best(user, song)
        assertTrue("shift=${r.keyShift}", r.keyShift < 0)
        val strain = RangeFitCalculator.strainSectionsBoth(songNotes, r.keyShift, user.coreHigh)
        assertTrue(strain.original.isNotEmpty())
        assertTrue(strain.atKey.size <= strain.original.size)
        assertTrue(strain.atKey.sumOf { it.maxExcessSemitones } < strain.original.sumOf { it.maxExcessSemitones })
    }

    @Test
    fun `low song for a high voice is shifted up`() {
        val user = PitchProfile.ofStableRange(song.coreLow + 4, song.coreHigh + 4)
        assertTrue(RangeFitCalculator.best(user, song).keyShift > 0)
    }

    @Test
    fun `key shift contract matches the playable minus three through plus three range`() {
        assertEquals(-3, RangeFitCalculator.KEY_SHIFTS.first)
        assertEquals(3, RangeFitCalculator.KEY_SHIFTS.last)
        assertEquals(7, RangeFitCalculator.KEY_SHIFTS.count())
        assertFalse(4 in RangeFitCalculator.KEY_SHIFTS)
    }

    @Test
    fun `ideal plus six match is capped at plus three`() {
        val songHistogram = DoubleArray(PitchProfile.BINS).also { it[60 - PitchProfile.MIDI_MIN] = 1.0 }
        val songAtSixty = PitchProfile(songHistogram, 60, 60, 60)
        val userAtSixtySix = PitchProfile.ofStableRange(66, 66)

        assertEquals(3, RangeFitCalculator.best(userAtSixtySix, songAtSixty).keyShift)
    }

    @Test
    fun `guided recommendation uses the first key that reaches ninety eight percent coverage`() {
        val histogram = DoubleArray(PitchProfile.BINS).also { values ->
            (56..64).forEach { values[it - PitchProfile.MIDI_MIN] = 1.0 / 9.0 }
        }
        val candidate = PitchProfile(histogram, 56, 60, 64)
        val user = PitchProfile.ofStableRange(60, 64)

        val result = RangeFitCalculator.best(user, candidate)

        assertEquals(2, result.keyShift)
        assertTrue(result.fit.coverage >= 0.98f)
        assertTrue(RangeFitCalculator.scoreAtShift(user, candidate, 1).coverage < 0.98f)
    }

    @Test
    fun `ties prefer the original key`() {
        // 곡: 한 음만 (60). 사용자 중앙음도 60 → ±k 가 대칭이라 0 이 이겨야 한다.
        val hist = DoubleArray(PitchProfile.BINS).also { it[60 - PitchProfile.MIDI_MIN] = 1.0 }
        val one = PitchProfile(hist, 60, 60, 60)
        val user = PitchProfile.ofStableRange(56, 64)
        assertEquals(0, RangeFitCalculator.best(user, one).keyShift)
    }

    @Test
    fun `unreliable song profile scores zero but still picks a key`() {
        val shaky = PitchProfile(song.histogram, song.coreLow, song.median, song.coreHigh, reliable = false)
        val r = RangeFitCalculator.best(PitchProfile.ofStableRange(55, 67), shaky)
        assertEquals(0f, r.fit.score)
        assertEquals(0f, r.fit.softFit)
        assertTrue(r.keyShift in RangeFitCalculator.KEY_SHIFTS)
    }

    @Test
    fun `strain sections merge notes within the gap and split beyond it`() {
        fun n(s: Double, e: Double, midi: Int) = KaraokeNote(sourceStart = s, sourceEnd = e, midi = midi)
        val notes = listOf(n(10.0, 10.5, 70), n(10.6, 11.0, 72), n(11.2, 11.5, 66), n(30.0, 30.4, 71))
        val sections = RangeFitCalculator.strainSections(notes, 0, userCoreHigh = 65)  // limit 67
        assertEquals(2, sections.size)
        assertEquals(10.0, sections[0].startSec, 1e-9)
        assertEquals(11.0, sections[0].endSec, 1e-9)
        assertEquals(5, sections[0].maxExcessSemitones)
        assertEquals(4, sections[1].maxExcessSemitones)
        assertTrue(RangeFitCalculator.strainSections(notes, -6, userCoreHigh = 65).isEmpty())
    }
}
