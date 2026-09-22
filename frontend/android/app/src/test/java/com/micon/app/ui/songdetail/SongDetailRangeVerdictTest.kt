package com.micon.app.ui.songdetail

import com.micon.app.ui.design.atoms.MiconChipTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SongDetailRangeVerdictTest {
    @Test fun missingRangeIsNotReportedAsGoodFit() {
        val missingProfile = rangeVerdict(null, 60..70, 0)
        val missingSong = rangeVerdict(60 to 70, null, 0)

        assertEquals("평가 불가", missingProfile.label)
        assertEquals(MiconChipTone.Neutral, missingProfile.tone)
        assertEquals("평가 불가", missingSong.label)
        assertNull(rangeMatchPercent(null, 60..70, 0))
    }

    @Test fun verdictUsesShiftedRangesInsteadOfRecommendationScore() {
        assertEquals("잘 맞아요", rangeVerdict(60 to 70, 60..70, 0).label)
        assertEquals("한 키 조절", rangeVerdict(60 to 70, 60..70, 2).label)
        assertEquals("도전곡", rangeVerdict(60 to 70, 60..70, 3).label)
    }

    @Test fun rangeMatchChangesWhenKeyChanges() {
        assertEquals(100, rangeMatchPercent(60 to 70, 60..70, 0))
        assertEquals(80, rangeMatchPercent(60 to 70, 60..70, 2))
        assertEquals(0, rangeMatchPercent(60 to 70, 71..81, 0))
    }
}
