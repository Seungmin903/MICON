package com.micon.app.scoring

import kotlin.math.max
import kotlin.math.min

/** 보컬이 없는 구간(전주·간주). 연습 중 여기 들어오면 "간주 건너뛰기" 를 보여 준다. */
data class Interlude(val start: Double, val end: Double) {
    val length: Double get() = end - start
}

/** 보컬 구간 묶음(1절·2절…). 라벨은 순서 기반 — 카탈로그에 구간(sections) 데이터가 아직 없다. */
data class Section(val index: Int, val label: String, val start: Double, val end: Double)

/** 최고음이 몰린 구간. "하이라이트" 점프 목적지. */
data class Highlight(val start: Double, val end: Double, val midi: Int)

class SectionMap(val interludes: List<Interlude>, val sections: List<Section>, val highlight: Highlight?) {
    fun interludeAt(seconds: Double): Interlude? = interludes.firstOrNull { seconds >= it.start && seconds < it.end }

    /** [seconds] 가 속한(또는 직전) 구간 인덱스. 첫 구간 시작 전이면 0. */
    fun sectionIndexAt(seconds: Double): Int? {
        if (sections.isEmpty()) return null
        val i = sections.indexOfLast { seconds >= it.start - JUMP_LEAD_SECONDS }
        return if (i < 0) 0 else i
    }

    companion object {
        /** 구간으로 점프할 때 구간 시작보다 이만큼 앞에서 시작 */
        const val JUMP_LEAD_SECONDS = 1.5
        val EMPTY = SectionMap(emptyList(), emptyList(), null)
    }
}

object SongSections {
    /** 이 이상 음표가 비면 간주로 본다 */
    const val MIN_GAP_SECONDS = 5.0
    /** 이보다 짧은 보컬 묶음은 앞 구간에 붙인다 (간주 안의 애드리브 한두 음 등) */
    private const val MIN_SECTION_SECONDS = 12.0
    private const val HIGHLIGHT_WINDOW_SECONDS = 10.0
    /** 마디 정보가 없을 때 하이라이트 앞에 두는 준비 시간(≈ 한 마디) */
    const val HIGHLIGHT_LEAD_FALLBACK_SECONDS = 2.5

    /**
     * @param barStarts 마디 시작 시각(비트 그리드에서). 하이라이트 시작을 "그 구절이 시작하는 마디의 한 마디 앞" 으로 맞춘다 —
     *                  최고음에서 바로 시작하면 아무도 맞출 수 없다.
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

        // 2) 간주: 전주(첫 음표 앞) + 묶음 사이
        val interludes = ArrayList<Interlude>()
        if (blocks.first().start >= MIN_GAP_SECONDS) interludes.add(Interlude(0.0, blocks.first().start))
        for (k in 1 until blocks.size) interludes.add(Interlude(blocks[k - 1].end, blocks[k].start))

        // 3) 구간 라벨: 줄 시작에 맞춰 시작 시각을 살짝 당긴다
        val sections = blocks.mapIndexed { index, b ->
            val lineStart = lines.firstOrNull { it.start >= b.start - 0.5 && it.start < b.end }?.start
            val start = min(b.start, lineStart ?: b.start)
            Section(index, labelFor(index, blocks.size), start, b.end)
        }

        return SectionMap(interludes, sections, findHighlight(sorted, lines, blocks.map { it.start to it.end }, barStarts))
    }

    private fun labelFor(index: Int, count: Int): String = when {
        count == 1 -> "전체"
        count <= 6 -> "${index + 1}절"
        else -> "구간 ${index + 1}"
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
        val start = if (barStarts.isNotEmpty()) {
            val idx = barStarts.indexOfLast { it <= phraseStart + 0.05 }
            when {
                idx >= 1 -> barStarts[idx - 1]
                idx == 0 -> barStarts[0]
                else -> max(0.0, phraseStart - HIGHLIGHT_LEAD_FALLBACK_SECONDS)
            }
        } else max(0.0, phraseStart - HIGHLIGHT_LEAD_FALLBACK_SECONDS)
        val blockEnd = blocks.firstOrNull { (a, b) -> bestStart >= a && bestStart <= b }?.second ?: (bestStart + HIGHLIGHT_WINDOW_SECONDS)
        val end = min(blockEnd, bestStart + HIGHLIGHT_WINDOW_SECONDS + 4.0)
        return Highlight(start, end, highest)
    }
}
