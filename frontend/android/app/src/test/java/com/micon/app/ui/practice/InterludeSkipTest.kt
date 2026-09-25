package com.micon.app.ui.practice

import com.micon.app.scoring.Interlude
import com.micon.app.scoring.SectionMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class InterludeSkipTest {
    private val sections = SectionMap(listOf(Interlude(10.0, 15.0)), emptyList(), null)

    @Test fun outsideInterludeShowsNotInterludeMessageResult() {
        assertEquals(SkipInterludeResult.NOT_INTERLUDE, planInterludeSkip(sections, 9.9, true).result)
        assertEquals(SkipInterludeResult.NOT_INTERLUDE, planInterludeSkip(sections, 15.0, true).result)
    }

    @Test fun insideInterludeSkipsUntilDestinationIsReached() {
        val plan = planInterludeSkip(sections, 10.0, true)
        assertEquals(SkipInterludeResult.SKIPPED, plan.result)
        assertEquals(12.5, plan.targetSec!!, 0.0)
        val tooLate = planInterludeSkip(sections, 12.5, true)
        assertEquals(SkipInterludeResult.TOO_LATE, tooLate.result)
        assertNull(tooLate.targetSec)
    }

    @Test fun countdownDoesNotSeek() {
        val plan = planInterludeSkip(sections, 10.0, false)
        assertEquals(SkipInterludeResult.NOT_READY, plan.result)
        assertNull(plan.targetSec)
    }
}
