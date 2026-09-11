package com.micon.app.data.singanalysis

import com.micon.app.data.PracticeSection
import com.micon.app.scoring.SectionMap
import kotlin.math.max
import kotlin.math.min

/** 불러서 분석하기에서 사용자가 고르는 범위. 세 가지 모두 곡 끝까지 진행한다(팀 결정 2026-09-11). */
enum class SingScope(val label: String) {
    FULL("전곡 부르기"),
    SECOND_HALF("2절부터 시작"),
    HIGHLIGHT("하이라이트만 부르기"),
}

/** 시작 시각을 어디서 얻었는지. 메타데이터에 남겨 분석 쪽이 신뢰도를 판단할 수 있게 한다. */
enum class PlanSource {
    /** 곡 처음(전주 정보 없음) */
    SONG_START,
    /** 첫 음표 4초 앞(연습의 "전주 건너뛰기" 와 같음) */
    INTRO_LEAD,
    /** 백엔드 `song_section` 의 두 번째 VOCAL 구간 */
    BACKEND_SECTION,
    /** 음표 간격으로 나눈 보컬 묶음(`SongSections`)의 두 번째 */
    DERIVED_SECTION,
    /** 절 정보가 전혀 없어 곡 길이의 절반 */
    HALF_DURATION,
    /** `SongSections` 의 최고음 구간 */
    HIGHLIGHT,
    /** 이 곡에서는 제공할 수 없음 */
    UNAVAILABLE,
}

/**
 * 범위 하나의 계산 결과. 시각은 곡(반주) 시계 기준 초.
 * @param displayLabel 카드 제목. 절 정보가 없으면 "절반부터 시작" 처럼 바뀐다.
 * @param note 카드 아래 한 줄 설명(비활성 사유 포함). 시각 포맷은 UI 가 한다.
 */
data class ScopePlan(
    val scope: SingScope,
    val displayLabel: String,
    val startSec: Double,
    val endSec: Double,
    val source: PlanSource,
    val enabled: Boolean,
    val note: String? = null,
) {
    val lengthSec: Double get() = max(0.0, endSec - startSec)
}

/**
 * 세 범위의 시작 시각을 정한다. 순수 함수 — 음표·구간 정보만 받는다(온디바이스 AI 없음).
 *
 * - 종료는 모두 곡 끝: `min(duration, lastNoteEnd + 2)`, 음표가 없으면 `duration`.
 * - FULL: 첫 음표 4초 앞(전주 건너뛰기). 음표가 없으면 0.
 * - SECOND_HALF: 백엔드 VOCAL 구간 2번째 → 규칙 구간 2번째 → (보컬 묶음이 1개로 확인되면 비활성) → 정보가 없으면 절반.
 *   후보는 `MIN_SECOND_START ≤ start ≤ end − MIN_TAIL` 안에 있어야 한다.
 * - HIGHLIGHT: `SectionMap.highlight.start`(이미 한 마디 앞으로 당겨져 있음). 없으면 비활성.
 */
object SingScopeResolver {
    /** 절 점프 시 구간 시작보다 이만큼 앞에서 시작(연습과 동일) */
    const val LEAD_SECONDS = SectionMap.JUMP_LEAD_SECONDS
    /** 전곡 시작 시 첫 음표보다 이만큼 앞(연습 "전주 건너뛰기" 와 동일) */
    const val INTRO_LEAD_SECONDS = 4.0
    /** 2절 후보로 인정하는 최소 시작 시각 */
    const val MIN_SECOND_START = 20.0
    /** 2절 후보 뒤에 최소 이만큼은 남아 있어야 한다 */
    const val MIN_TAIL = 15.0
    /** 시작은 끝에서 최소 이만큼 앞 */
    private const val MIN_LENGTH = 5.0

    fun resolve(
        durationSec: Double,
        firstNoteStart: Double?,
        lastNoteEnd: Double?,
        backendSections: List<PracticeSection>,
        derived: SectionMap,
    ): List<ScopePlan> {
        val duration = max(0.0, durationSec)
        val end = lastNoteEnd?.let { min(duration, it + 2.0) }?.takeIf { it > 0 } ?: duration
        return listOf(full(firstNoteStart, end), secondHalf(duration, end, backendSections, derived), highlight(end, derived))
    }

    private fun clampStart(start: Double, end: Double): Double = start.coerceIn(0.0, max(0.0, end - MIN_LENGTH))

    private fun full(firstNoteStart: Double?, end: Double): ScopePlan {
        val lead = firstNoteStart?.let { max(0.0, it - INTRO_LEAD_SECONDS) } ?: 0.0
        val start = clampStart(lead, end)
        return ScopePlan(
            scope = SingScope.FULL, displayLabel = SingScope.FULL.label, startSec = start, endSec = end,
            source = if (start > 0) PlanSource.INTRO_LEAD else PlanSource.SONG_START, enabled = true,
            note = if (start > 0) "전주는 건너뛰고 시작해요" else "처음부터 끝까지",
        )
    }

    private fun secondHalf(duration: Double, end: Double, backendSections: List<PracticeSection>, derived: SectionMap): ScopePlan {
        fun candidate(start: Double): Double? = start.takeIf { it >= MIN_SECOND_START && it <= end - MIN_TAIL }

        val vocal = backendSections.filter { it.sectionType.equals("VOCAL", ignoreCase = true) }.sortedBy { it.sequenceNo }
        if (vocal.size >= 2) {
            candidate(vocal[1].startMs / 1000.0 - LEAD_SECONDS)?.let {
                return ScopePlan(SingScope.SECOND_HALF, SingScope.SECOND_HALF.label, clampStart(it, end), end, PlanSource.BACKEND_SECTION, true, "2절 시작 1.5초 앞부터")
            }
        }
        // 백엔드가 구간을 알고 있고 보컬 묶음이 하나라고 하면 그 판단을 따른다(규칙 계산보다 우선).
        val unavailable = ScopePlan(SingScope.SECOND_HALF, SingScope.SECOND_HALF.label, 0.0, end, PlanSource.UNAVAILABLE, false, "이 곡은 절 구분이 없어요")
        if (vocal.size == 1) return unavailable
        if (derived.sections.size >= 2) {
            candidate(derived.sections[1].start - LEAD_SECONDS)?.let {
                return ScopePlan(SingScope.SECOND_HALF, SingScope.SECOND_HALF.label, clampStart(it, end), end, PlanSource.DERIVED_SECTION, true, "2절 시작 1.5초 앞부터")
            }
        }
        if (derived.sections.size == 1) return unavailable
        val half = clampStart(duration / 2, end)
        return ScopePlan(SingScope.SECOND_HALF, "절반부터 시작", half, end, PlanSource.HALF_DURATION, half > 0, "절 정보가 없어 곡 절반 지점부터 시작해요")
    }

    private fun highlight(end: Double, derived: SectionMap): ScopePlan {
        val h = derived.highlight
            ?: return ScopePlan(SingScope.HIGHLIGHT, SingScope.HIGHLIGHT.label, 0.0, end, PlanSource.UNAVAILABLE, false, "최고음 구간을 찾지 못했어요")
        return ScopePlan(SingScope.HIGHLIGHT, SingScope.HIGHLIGHT.label, clampStart(h.start, end), end, PlanSource.HIGHLIGHT, true, "최고음이 몰린 구간 한 마디 앞부터")
    }
}
