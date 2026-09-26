package com.micon.app.ui.practice

import com.micon.app.scoring.SectionMap

internal enum class SkipInterludeResult { SKIPPED, NOT_INTERLUDE, TOO_LATE, NOT_READY }

internal data class InterludeSkipPlan(val result: SkipInterludeResult, val targetSec: Double? = null)

/** Keep the button visible; decide what a tap can do from the current playback position. */
internal fun planInterludeSkip(sections: SectionMap, nowSec: Double, canSeek: Boolean): InterludeSkipPlan {
    val interlude = sections.interludeAt(nowSec)
        ?: return InterludeSkipPlan(SkipInterludeResult.NOT_INTERLUDE)
    if (!canSeek) return InterludeSkipPlan(SkipInterludeResult.NOT_READY)
    val target = sections.leadBefore(interlude.end)
    if (target <= nowSec) return InterludeSkipPlan(SkipInterludeResult.TOO_LATE)
    return InterludeSkipPlan(SkipInterludeResult.SKIPPED, target)
}
