package com.micon.app.data.singanalysis

import com.micon.app.data.PracticeSection
import com.micon.app.scoring.SectionMap

enum class SingScope(val label: String) {
    FULL("처음부터 부르기"), SECOND_HALF("2절부터 시작"), HIGHLIGHT("하이라이트부터 시작"),
}

// Keep old values readable in recordings made by earlier app versions.
enum class PlanSource { SONG_START, INTRO_LEAD, BACKEND_SECTION, DERIVED_SECTION, HALF_DURATION, HIGHLIGHT, UNAVAILABLE }

data class ScopePlan(
    val scope: SingScope, val displayLabel: String, val startSec: Double, val endSec: Double,
    val source: PlanSource, val enabled: Boolean, val note: String? = null,
) {
    val lengthSec: Double get() = (endSec - startSec).coerceAtLeast(0.0)
}

/** Use registered sections only. Notes, generic vocal blocks and duration ratios do not identify verses. */
object SingScopeResolver {
    @Suppress("UNUSED_PARAMETER")
    fun resolve(durationSec: Double, firstNoteStart: Double?, lastNoteEnd: Double?,
                backendSections: List<PracticeSection>, derived: SectionMap): List<ScopePlan> {
        val duration = durationSec.takeIf { it.isFinite() && it > 0 } ?: 0.0
        val sections = backendSections.filter { it.startMs >= 0 && it.endMs > it.startMs }
        val verses = sections.filter { it.sectionType.equals("VERSE", true) }.sortedBy { it.sequenceNo }
        val second = sections.firstOrNull {
            it.sectionType.uppercase() in setOf("SECOND_VERSE", "VERSE_2") || it.sectionName.trim() == "2절"
        } ?: verses.getOrNull(1)
        val highlight = sections.firstOrNull {
            it.sectionType.equals("HIGHLIGHT", true) || it.sectionName.trim() == "하이라이트"
        }
        fun plan(scope: SingScope, start: Double?, source: PlanSource): ScopePlan {
            val validStart = start?.takeIf { it.isFinite() && it >= 0 && it < duration }
            val enough = validStart != null && duration - validStart >= RecordingEligibility.MIN_SECONDS
            return ScopePlan(scope, scope.label, validStart ?: 0.0, duration,
                if (validStart == null) PlanSource.UNAVAILABLE else source, enough,
                when {
                    validStart == null -> "등록된 시작 구간이 없어요. 다른 시작 위치를 골라 주세요."
                    !enough -> "남은 반주가 30초 미만이에요. 다른 시작 위치나 곡을 골라 주세요."
                    scope == SingScope.FULL -> "전주를 포함해 곡 처음부터 시작해요"
                    else -> "등록된 구간부터 곡 끝까지 불러요"
                })
        }
        return listOf(plan(SingScope.FULL, 0.0, PlanSource.SONG_START),
            plan(SingScope.SECOND_HALF, second?.startMs?.div(1000.0), PlanSource.BACKEND_SECTION),
            plan(SingScope.HIGHLIGHT, highlight?.startMs?.div(1000.0), PlanSource.BACKEND_SECTION))
    }
}
