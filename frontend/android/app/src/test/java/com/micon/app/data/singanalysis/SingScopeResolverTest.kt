package com.micon.app.data.singanalysis

import com.micon.app.data.PracticeSection
import com.micon.app.scoring.SectionMap
import org.junit.Assert.*
import org.junit.Test

class SingScopeResolverTest {
    private fun section(type: String, name: String, start: Int, seq: Int = 1) =
        PracticeSection(seq.toLong(), type, name, start, start + 10_000, seq, "NONE", null, null)

    private fun plans(duration: Double = 120.0, vararg sections: PracticeSection) =
        SingScopeResolver.resolve(duration, 12.0, 90.0, sections.toList(), SectionMap.EMPTY).associateBy { it.scope }

    @Test fun fullIncludesIntroAndOutro() {
        val plan = plans().getValue(SingScope.FULL)
        assertEquals(0.0, plan.startSec, 0.0)
        assertEquals(120.0, plan.endSec, 0.0)
        assertTrue(plan.enabled)
    }

    @Test fun missingSectionsStayDisabledWithoutRatioFallback() {
        val result = plans()
        assertFalse(result.getValue(SingScope.SECOND_HALF).enabled)
        assertFalse(result.getValue(SingScope.HIGHLIGHT).enabled)
        assertNotNull(result.getValue(SingScope.SECOND_HALF).note)
    }

    @Test fun genericVocalBlocksAreNotSecondVerse() {
        val result = plans(120.0, section("VOCAL", "보컬", 10_000), section("VOCAL", "보컬", 50_000, 2))
        assertFalse(result.getValue(SingScope.SECOND_HALF).enabled)
    }

    @Test fun registeredStartsAreUsedExactly() {
        val result = plans(120.0, section("VERSE", "1절", 10_000), section("VERSE", "2절", 55_000, 2),
            section("CUSTOM", "하이라이트", 80_000, 3))
        assertEquals(55.0, result.getValue(SingScope.SECOND_HALF).startSec, 0.0)
        assertEquals(80.0, result.getValue(SingScope.HIGHLIGHT).startSec, 0.0)
        assertTrue(result.values.all { it.enabled })
    }

    @Test fun thirtySecondsRemainingIsBoundary() {
        assertFalse(plans(29.999).getValue(SingScope.FULL).enabled)
        assertTrue(plans(30.0).getValue(SingScope.FULL).enabled)
        assertTrue(plans(120.0, section("HIGHLIGHT", "", 90_000)).getValue(SingScope.HIGHLIGHT).enabled)
        assertFalse(plans(120.0, section("HIGHLIGHT", "", 90_001)).getValue(SingScope.HIGHLIGHT).enabled)
    }

    @Test fun invalidOrUnknownDurationCannotStartRecording() {
        for (duration in listOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
            assertTrue(plans(duration).values.none { it.enabled })
        }
    }
}
