package com.micon.app.scoring

import kotlin.math.max
import kotlin.math.min

/**
 * 연습 화면 가사 시트.
 *
 * 줄 텍스트의 원본은 **LRC(`songs.lyrics_synced`)** 다. 음표에 붙은 음절(`karaokeNotes[].lyric`)은 글자 채움 시각과
 * 판정 색에만 쓰고, 음표에 붙지 못한 글자는 앞뒤 음절 사이를 보간해 채운다. 예전처럼 음절만 이어 붙여 줄을 만들면
 * 파이프라인이 음표에 배정하지 못한 글자가 화면에서 사라져 줄마다 1–2글자가 잘렸다.
 *
 * LRC 가 없으면 (1) 음절 음표만으로 줄을 묶고(엔진 정렬 결과), (2) 그것도 없으면 가사 텍스트를 원곡 음표의
 * 노래 구간에 글자 수 비례로 펼쳐 근사 타이밍을 만든다(가사 텍스트만 있는 곡). 어느 쪽인지는 [LyricSheet.timing] 이 말해 준다.
 */
enum class LyricTiming {
    /** LRC 줄 시각 + 음표 음절 → 글자 채움 */
    SYNCED,
    /** LRC 없음, 음표에 붙은 음절만으로 줄 구성 */
    NOTES,
    /** 가사 텍스트만 있음 — 음표 노래 구간에 비례 배치한 근사 시각 */
    APPROX,
    NONE,
}

/** 가사 한 글자의 채움 시각. [note] 는 그 글자가 붙은 음표(판정 색), 없으면 null. */
data class LyricChar(val text: String, val at: Double, val note: LiveNote?)

/**
 * @property start 줄이 불리기 시작하는 시각(초, MR 기준)
 * @property end 줄의 마지막 음절이 끝나는 시각(구간 반복 범위)
 * @property until 다음 줄이 시작되는 시각 — 이 시각까지 이 줄이 "현재 줄"
 */
data class LyricLine(val text: String, val start: Double, val end: Double, val until: Double, val chars: List<LyricChar>, val notes: List<LiveNote>) {
    /** 이 줄에서 [now] 까지 채워진 글자 비율 0..1 */
    fun progress(now: Double): Float {
        if (chars.isEmpty()) return if (now >= end) 1f else 0f
        val filled = chars.count { now >= it.at }
        return filled.toFloat() / chars.size
    }
}

data class LyricSheet(val lines: List<LyricLine>, val timing: LyricTiming, val breaks: List<Double> = emptyList()) {
    /** [now] 에 불리고 있는(또는 방금 지난) 줄. 줄 사이 공백(간주)이면 직전 줄. 시작 전이면 null. */
    fun currentIndex(now: Double): Int? {
        val i = lines.indexOfLast { it.start <= now }
        return if (i < 0) null else i
    }

    companion object {
        val EMPTY = LyricSheet(emptyList(), LyricTiming.NONE)
    }
}

object LyricLines {
    private val LRC_LINE = Regex("""^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?]\s*(.*)$""")
    /** 음절 음표가 LRC 줄 시각보다 이만큼 먼저 시작해도 그 줄로 본다 */
    private const val NOTE_LEAD_SECONDS = 0.3
    /** 음표 없는 줄의 길이 추정: 글자당 초 + 기본 */
    private const val SECONDS_PER_CHAR = 0.32
    private const val LINE_BASE_SECONDS = 0.8
    /** 근사 모드에서 노래 구간을 나누는 음표 사이 공백 */
    private const val SEGMENT_GAP_SECONDS = 4.0

    internal data class LrcEntry(val time: Double, val text: String)

    fun build(notes: List<LiveNote>, lyricsSynced: String?, lyricsPlain: String?): LyricSheet {
        val lrc = parseLrc(lyricsSynced)
        if (lrc.count { it.text.isNotEmpty() } >= 2) return fromLrc(lrc, notes)
        val syllables = notes.filter { !it.lyric.isNullOrBlank() }
        if (syllables.size >= 4) return LyricSheet(fromNotes(syllables, lyricsPlain ?: ""), LyricTiming.NOTES)
        val plainLines = (lyricsPlain ?: "").lines().map { it.trim() }
        if (plainLines.any { it.isNotEmpty() } && notes.isNotEmpty()) return fromPlain(plainLines, notes)
        return LyricSheet.EMPTY
    }

    /** 예전 시그니처(음절 음표 + 가사 텍스트만). 엔진 응답으로 만든 음표에 쓴다. */
    fun build(notes: List<LiveNote>, lyricsText: String?): List<LyricLine> = build(notes, null, lyricsText).lines

    // ------------------------------------------------------------------ LRC

    internal fun parseLrc(synced: String?): List<LrcEntry> {
        if (synced.isNullOrBlank()) return emptyList()
        val entries = ArrayList<LrcEntry>()
        for (raw in synced.lineSequence()) {
            val m = LRC_LINE.matchEntire(raw.trim()) ?: continue
            val (mm, ss, frac, text) = m.destructured
            val fraction = if (frac.isEmpty()) 0.0 else "0.$frac".toDouble()
            entries.add(LrcEntry(mm.toInt() * 60 + ss.toInt() + fraction, text.trim()))
        }
        return entries.sortedBy { it.time }
    }

    private fun fromLrc(entries: List<LrcEntry>, notes: List<LiveNote>): LyricSheet {
        val syllables = notes.filter { !it.lyric.isNullOrBlank() }.sortedBy { it.lyricAt }
        val lastNoteEnd = notes.maxOfOrNull { it.end } ?: 0.0
        val breaks = entries.filter { it.text.isEmpty() }.map { it.time }
        val texts = entries.filter { it.text.isNotEmpty() }
        val lines = ArrayList<LyricLine>(texts.size)
        var cursor = 0
        for (i in texts.indices) {
            val entry = texts[i]
            val nextBoundary = entries.firstOrNull { it.time > entry.time + 0.01 }?.time
            val nextText = texts.getOrNull(i + 1)?.time
            val windowEnd = nextBoundary ?: Double.MAX_VALUE
            // 이 줄에 속한 음절: 줄 시각(−lead) 이상, 다음 경계(−lead) 미만
            val lineNotes = ArrayList<LiveNote>()
            while (cursor < syllables.size && syllables[cursor].lyricAt + NOTE_LEAD_SECONDS < entry.time) cursor++
            var k = cursor
            while (k < syllables.size && syllables[k].lyricAt + NOTE_LEAD_SECONDS < windowEnd) { lineNotes.add(syllables[k]); k++ }
            cursor = k
            val estimate = entry.time + LINE_BASE_SECONDS + SECONDS_PER_CHAR * entry.text.count { !it.isWhitespace() }
            val start = min(entry.time, lineNotes.firstOrNull()?.lyricAt ?: entry.time)
            val end = lineNotes.lastOrNull()?.end?.coerceAtLeast(start + 0.5)
                ?: min(estimate, nextBoundary ?: estimate).coerceAtLeast(start + 0.5)
            val until = nextText ?: (max(end, lastNoteEnd) + 1.0)
            lines.add(LyricLine(entry.text, start, end, until, fillChars(entry.text, start, end, lineNotes), lineNotes))
        }
        return LyricSheet(lines, LyricTiming.SYNCED, breaks)
    }

    /**
     * 줄 텍스트의 글자마다 채움 시각을 정한다. 음절 음표의 가사를 텍스트에서 순서대로 찾아 그 음표의 시각을 주고,
     * 못 찾은 글자는 앞뒤 시각 사이를 선형 보간한다. 공백은 다음 글자 시각을 따른다.
     */
    internal fun fillChars(text: String, start: Double, end: Double, lineNotes: List<LiveNote>): List<LyricChar> {
        val n = text.length
        if (n == 0) return emptyList()
        val at = DoubleArray(n) { Double.NaN }
        val noteOf = arrayOfNulls<LiveNote>(n)
        var pos = 0
        for (note in lineNotes) {
            val lyric = note.lyric?.trim().orEmpty()
            if (lyric.isEmpty()) continue
            val idx = indexOfNear(text, lyric, pos, lookahead = 8)
            if (idx < 0) continue
            for (c in idx until min(n, idx + lyric.length)) { at[c] = note.lyricAt; noteOf[c] = note }
            pos = idx + lyric.length
        }
        val isChar = BooleanArray(n) { !text[it].isWhitespace() }
        val timed = (0 until n).filter { isChar[it] && !at[it].isNaN() }
        val lastTimedAt = timed.lastOrNull()?.let { at[it] } ?: start
        val tailEnd = max(end, lastTimedAt + 0.2)
        var prevIdx = -1
        var prevAt = start
        var c = 0
        while (c < n) {
            if (!isChar[c]) { c++; continue }
            if (!at[c].isNaN()) { prevIdx = c; prevAt = at[c]; c++; continue }
            // c 부터 이어지는 미확정 글자 묶음 [c, stop)
            var stop = c
            var count = 0
            while (stop < n && (!isChar[stop] || at[stop].isNaN())) { if (isChar[stop]) count++; stop++ }
            val from = if (prevIdx < 0) start else prevAt
            val to = if (stop < n) at[stop] else tailEnd
            var step = 0
            for (k in c until stop) {
                if (!isChar[k]) continue
                step++
                at[k] = from + (to - from) * step / (count + 1)
            }
            c = stop
        }
        // 공백: 다음 글자 시각(없으면 앞 글자)
        for (k in 0 until n) {
            if (isChar[k]) continue
            val next = (k + 1 until n).firstOrNull { isChar[it] }
            val prev = (k - 1 downTo 0).firstOrNull { isChar[it] }
            at[k] = when { next != null -> at[next]; prev != null -> at[prev]; else -> start }
        }
        return (0 until n).map { LyricChar(text[it].toString(), at[it], noteOf[it]) }
    }

    /** [from] 부터 [lookahead] 글자 안에서 [needle] 을 찾는다(대소문자 무시). 없으면 −1. */
    private fun indexOfNear(text: String, needle: String, from: Int, lookahead: Int): Int {
        if (from >= text.length) return -1
        val idx = text.indexOf(needle, from, ignoreCase = true)
        return if (idx >= 0 && idx - from <= lookahead) idx else -1
    }

    // ------------------------------------------------------------------ 음절 음표만 (LRC 없음)

    private fun fromNotes(syllables: List<LiveNote>, text: String): List<LyricLine> {
        data class Token(val note: LiveNote, val space: Boolean)
        val groups = ArrayList<List<Token>>()
        var line = ArrayList<Token>()
        var pos = 0
        fun pushLine() { if (line.isNotEmpty()) groups.add(line); line = ArrayList() }
        for (i in syllables.indices) {
            val note = syllables[i]
            val lyric = note.lyric!!
            val gap = if (i > 0) note.start - syllables[i - 1].end else 0.0
            var space = false
            var newline = false
            val idx = if (text.isNotEmpty()) text.indexOf(lyric, pos) else -1
            if (idx >= 0 && idx - pos <= 24) {
                val between = text.substring(pos, idx)
                if (between.contains('\n')) newline = true else if (between.any { it.isWhitespace() }) space = true
                pos = idx + lyric.length
            } else {
                newline = gap > 1.2
                space = !newline && gap > 0.35
            }
            val lineChars = line.sumOf { it.note.lyric!!.length + (if (it.space) 1 else 0) }
            if (i > 0 && (newline || gap > 2.5 || (lineChars >= 22 && space))) pushLine()
            if (line.isEmpty()) space = false
            line.add(Token(note, space))
        }
        pushLine()
        return groups.mapIndexed { gi, tokens ->
            val sb = StringBuilder()
            val chars = ArrayList<LyricChar>()
            for (tok in tokens) {
                if (tok.space) { sb.append(' '); chars.add(LyricChar(" ", tok.note.lyricAt, null)) }
                for (ch in tok.note.lyric!!) { sb.append(ch); chars.add(LyricChar(ch.toString(), tok.note.lyricAt, tok.note)) }
            }
            val start = tokens.first().note.start
            val end = tokens.last().note.end
            val until = groups.getOrNull(gi + 1)?.first()?.note?.start ?: (end + 1.0)
            LyricLine(sb.toString(), start, end, until, chars, tokens.map { it.note })
        }
    }

    // ------------------------------------------------------------------ 가사 텍스트만 (근사)

    internal data class Segment(val start: Double, val end: Double) { val length: Double get() = end - start }

    /** 음표를 [gap] 초 이상 비는 곳에서 끊은 노래 구간 */
    internal fun singingSegments(notes: List<LiveNote>, gap: Double = SEGMENT_GAP_SECONDS): List<Segment> {
        val sorted = notes.sortedBy { it.start }
        val segments = ArrayList<Segment>()
        var segStart = sorted.first().start
        var segEnd = sorted.first().end
        for (note in sorted.drop(1)) {
            if (note.start - segEnd >= gap) { segments.add(Segment(segStart, segEnd)); segStart = note.start }
            segEnd = max(segEnd, note.end)
        }
        segments.add(Segment(segStart, segEnd))
        return segments
    }

    private fun fromPlain(plainLines: List<String>, notes: List<LiveNote>): LyricSheet {
        val paragraphs = ArrayList<MutableList<String>>()
        var current = ArrayList<String>()
        for (l in plainLines) {
            if (l.isEmpty()) { if (current.isNotEmpty()) { paragraphs.add(current); current = ArrayList() } } else current.add(l)
        }
        if (current.isNotEmpty()) paragraphs.add(current)
        if (paragraphs.isEmpty()) return LyricSheet.EMPTY
        val segments = singingSegments(notes)
        fun weight(s: String) = s.count { !it.isWhitespace() } + 1.0

        val lines = ArrayList<LyricLine>()
        fun layout(texts: List<String>, segs: List<Segment>) {
            // 누적 노래 시간 → 실제 시각 (구간 사이 공백은 건너뜀)
            val total = segs.sumOf { it.length }
            fun timeAt(cum: Double): Double {
                var rest = cum
                for (s in segs) { if (rest <= s.length) return s.start + rest; rest -= s.length }
                return segs.last().end
            }
            val weights = texts.map(::weight)
            val sum = weights.sum()
            var cum = 0.0
            for ((i, text) in texts.withIndex()) {
                val share = total * weights[i] / sum
                val start = timeAt(cum)
                val end = timeAt(cum + share * 0.92)
                cum += share
                lines.add(LyricLine(text, start, end, Double.NaN, fillChars(text, start, end, emptyList()), emptyList()))
            }
        }
        if (paragraphs.size == segments.size && paragraphs.size > 1) {
            for (i in paragraphs.indices) layout(paragraphs[i], listOf(segments[i]))
        } else {
            layout(paragraphs.flatten(), segments)
        }
        val lastEnd = notes.maxOf { it.end }
        val finished = lines.mapIndexed { i, l -> l.copy(until = lines.getOrNull(i + 1)?.start ?: (max(l.end, lastEnd) + 1.0)) }
        return LyricSheet(finished, LyricTiming.APPROX)
    }
}
