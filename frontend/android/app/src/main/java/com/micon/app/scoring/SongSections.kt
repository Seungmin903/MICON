package com.micon.app.scoring

import kotlin.math.max
import kotlin.math.min

/** 보컬이 없는 구간(전주·간주). 연습 중 여기 들어오면 "간주 건너뛰기" 를 보여 준다. */
data class Interlude(val start: Double, val end: Double) {
    val length: Double get() = end - start
}

/**
 * 절 구간. 모든 곡은 **1절·2절 둘로만** 나눈다 (구간이 4·5절까지 나오던 것을 통일).
 * [start] 는 보컬이 실제로 시작하는 시각(표시용), [jumpStart] 는 이 절로 점프할 때 반주를 시작하는 시각 —
 * 보컬이 시작하는 마디의 **한 마디 앞 첫 박**이다. 마디 정보가 없으면 고정 여유(2.5 s)를 뺀다.
 */
data class Section(val index: Int, val label: String, val start: Double, val end: Double, val jumpStart: Double)

/** 최고음이 몰린 구간. "하이라이트" 점프 목적지. [start] 는 이미 구절 마디의 한 마디 앞이다. */
data class Highlight(val start: Double, val end: Double, val midi: Int)

class SectionMap(
    val interludes: List<Interlude>,
    val sections: List<Section>,
    val highlight: Highlight?,
    private val barStarts: List<Double> = emptyList(),
) {
    fun interludeAt(seconds: Double): Interlude? = interludes.firstOrNull { seconds >= it.start && seconds < it.end }

    /** [seconds] 가 속한(또는 직전) 구간 인덱스. 첫 구간 점프 시각 전이면 0. */
    fun sectionIndexAt(seconds: Double): Int? {
        if (sections.isEmpty()) return null
        val i = sections.indexOfLast { seconds >= it.jumpStart }
        return if (i < 0) 0 else i
    }

    /**
     * [seconds] 에서 노래가 시작될 때 반주를 어디서 출발시킬지 — 그 시각이 속한 마디의 **한 마디 앞 첫 박**.
     * 절 점프·하이라이트·간주 건너뛰기가 모두 이 규칙을 쓴다. 노래 중간에서 툭 시작하지 않고 마디 첫 박에서
     * 들어가야 가수가 박자를 잡고 준비할 수 있다. 마디 정보가 없으면 [SongSections.LEAD_FALLBACK_SECONDS] 앞.
     */
    fun leadBefore(seconds: Double): Double = leadBefore(seconds, barStarts)

    companion object {
        val EMPTY = SectionMap(emptyList(), emptyList(), null)

        fun leadBefore(seconds: Double, barStarts: List<Double>): Double {
            if (barStarts.isEmpty()) return max(0.0, seconds - SongSections.LEAD_FALLBACK_SECONDS)
            val idx = barStarts.indexOfLast { it <= seconds + 0.05 }
            return when {
                idx >= 1 -> barStarts[idx - 1]
                idx == 0 -> barStarts[0]
                else -> max(0.0, seconds - SongSections.LEAD_FALLBACK_SECONDS)
            }
        }
    }
}

object SongSections {
    /** 이 이상 음표가 비면 간주로 본다 */
    const val MIN_GAP_SECONDS = 5.0
    /** 이보다 짧은 보컬 묶음은 앞 구간에 붙인다 (간주 안의 애드리브 한두 음 등) */
    private const val MIN_SECTION_SECONDS = 12.0
    /** 간주가 하나도 없는 곡: 이 이상 비는 음표 사이(가운데 25~75 % 구간)에서 1절·2절을 나눈다 */
    private const val FALLBACK_SPLIT_GAP_SECONDS = 2.0
    private const val HIGHLIGHT_WINDOW_SECONDS = 10.0
    /** 마디 정보가 없을 때 시작 앞에 두는 준비 시간(≈ 한 마디) */
    const val LEAD_FALLBACK_SECONDS = 2.5

    /**
     * @param barStarts 마디 시작 시각(비트 그리드에서). 절·하이라이트 점프 시각을 "그 구절이 시작하는 마디의 한 마디 앞" 으로
     *                  맞춘다 — 최고음이나 첫 음절에서 바로 시작하면 아무도 맞출 수 없다.
     */
    fun build(notes: List<LiveNote>, lines: List<LyricLine>, barStarts: List<Double> = emptyList()): SectionMap {
        if (notes.isEmpty()) return SectionMap.EMPTY
        val sorted = notes.sortedBy { it.start }

        // 1) 보컬 묶음: MIN_GAP 이상 빈 곳에서 끊는다
        data class Block(var start: Double, var end: Double)
        val blocks = ArrayList<Block>()
        var block = Block(sorted.first().start, sorted.first().end)
        for (note in sorted.drop(1)) {
            if (note.start - block.end >= MIN_GAP_SECONDS) { blocks.add(block); block = Block(note.start, note.end) }
            block.end = max(block.end, note.end)
        }
        blocks.add(block)
        // 짧은 묶음은 앞 묶음에 병합 (첫 묶음이 짧으면 뒤에 병합)
        var i = 0
        while (blocks.size > 1 && i < blocks.size) {
            val b = blocks[i]
            if (b.end - b.start < MIN_SECTION_SECONDS) {
                if (i > 0) { blocks[i - 1].end = b.end; blocks.removeAt(i); continue }
                else { blocks[1].start = b.start; blocks.removeAt(0); continue }
            }
            i++
        }

        // 2) 간주: 전주(첫 음표 앞) + 묶음 사이 — 건너뛰기 대상은 전부 남긴다
        val interludes = ArrayList<Interlude>()
        if (blocks.first().start >= MIN_GAP_SECONDS) interludes.add(Interlude(0.0, blocks.first().start))
        for (k in 1 until blocks.size) interludes.add(Interlude(blocks[k - 1].end, blocks[k].start))

        // 3) 절은 둘로만: 묶음 사이 간주 중 가장 긴 곳에서 1절/2절을 나눈다.
        //    묶음이 하나뿐이면(간주 없는 곡) 가운데 구간의 가장 긴 음표 공백에서 나누고, 그것도 없으면 1절만 둔다.
        val vocalStart = blocks.first().start
        val vocalEnd = blocks.last().end
        val splitAt: Double? = if (blocks.size >= 2) {
            val k = (1 until blocks.size).maxBy { blocks[it].start - blocks[it - 1].end }
            blocks[k].start
        } else {
            fallbackSplit(sorted, vocalStart, vocalEnd)
        }
        val sections = ArrayList<Section>()
        fun section(index: Int, label: String, from: Double, to: Double) {
            // 시작은 첫 음표와 그 줄의 시작 중 앞선 쪽 (가사 줄이 살짝 먼저 시작하는 경우)
            val lineStart = lines.firstOrNull { it.start >= from - 0.5 && it.start < to }?.start
            val start = min(from, lineStart ?: from)
            sections.add(Section(index, label, start, to, SectionMap.leadBefore(start, barStarts)))
        }
        if (splitAt == null) {
            section(0, "1절", vocalStart, vocalEnd)
        } else {
            val firstEnd = sorted.last { it.start < splitAt }.end
            section(0, "1절", vocalStart, firstEnd)
            section(1, "2절", splitAt, vocalEnd)
        }

        return SectionMap(interludes, sections, findHighlight(sorted, lines, blocks.map { it.start to it.end }, barStarts), barStarts)
    }

    /** 간주 없는 곡의 1절/2절 경계: 보컬 구간 가운데 25~75 % 안에서 가장 긴 음표 공백(≥ 2 s) 뒤 첫 음표. */
    private fun fallbackSplit(sorted: List<LiveNote>, vocalStart: Double, vocalEnd: Double): Double? {
        val span = vocalEnd - vocalStart
        if (span <= 0) return null
        val lo = vocalStart + span * 0.25
        val hi = vocalStart + span * 0.75
        var best: Double? = null
        var bestGap = FALLBACK_SPLIT_GAP_SECONDS
        for (k in 1 until sorted.size) {
            val gap = sorted[k].start - sorted[k - 1].end
            if (sorted[k].start in lo..hi && gap >= bestGap) { bestGap = gap; best = sorted[k].start }
        }
        return best
    }

    /** 최고음 근처(최고음 −5 반음 이상) 음표의 길이·높이가 가장 큰 10초 창. 시작은 그 창이 속한 가사 줄 시작으로 맞춘다. */
    private fun findHighlight(sorted: List<LiveNote>, lines: List<LyricLine>, blocks: List<Pair<Double, Double>>, barStarts: List<Double>): Highlight? {
        val highest = sorted.maxOf { it.midi }
        val floor = highest - 5
        val candidates = sorted.filter { it.midi >= floor }
        if (candidates.isEmpty()) return null
        var bestStart = candidates.first().start
        var bestScore = -1.0
        for (anchor in candidates) {
            val s = anchor.start
            var score = 0.0
            for (n in sorted) {
                if (n.start < s) continue
                if (n.start > s + HIGHLIGHT_WINDOW_SECONDS) break
                if (n.midi >= floor) score += n.weight * (n.midi - floor + 1)
            }
            if (score > bestScore) { bestScore = score; bestStart = s }
        }
        val line = lines.lastOrNull { it.start <= bestStart + 0.3 }
        val phraseStart = line?.start?.takeIf { bestStart - it <= 6.0 } ?: max(0.0, bestStart - 1.0)
        // 구절 시작이 속한 마디의 "한 마디 앞" 에서 출발 → 가수가 준비하고 들어갈 수 있다
        val start = SectionMap.leadBefore(phraseStart, barStarts)
        val blockEnd = blocks.firstOrNull { (a, b) -> bestStart >= a && bestStart <= b }?.second ?: (bestStart + HIGHLIGHT_WINDOW_SECONDS)
        val end = min(blockEnd, bestStart + HIGHLIGHT_WINDOW_SECONDS + 4.0)
        return Highlight(start, end, highest)
    }
}
