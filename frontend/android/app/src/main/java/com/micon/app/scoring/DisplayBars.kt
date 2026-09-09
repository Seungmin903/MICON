package com.micon.app.scoring

import kotlin.math.abs
import kotlin.math.max

/**
 * 러너웨이에 그리는 음표 막대. 원곡 음표(karaokeNotes)는 비브라토·꺾기·장식음 때문에 아주 짧은 조각으로 잘려 있어
 * 그대로 그리면 점이 줄줄이 찍힌 것처럼 보인다. 퍼펙트스코어처럼 **음절 하나 = 막대 하나** 에 가깝게 이어 붙인다.
 *
 * 규칙 (시간순):
 *  1. 가사 줄 구간 밖의 음표(전주·간주·아웃트로에서 잡힌 코러스·허밍)는 그리지 않는다 — 부를 가사가 없는 곳에 막대가 남는 문제.
 *  2. 앞 막대와 0.12 s 이내로 이어지고 같은 음이면 합친다.
 *  3. 0.15 s 미만의 짧은 조각은 가사가 없으면(멜리스마·장식음) 음이 2반음 안에서 다르더라도 앞 막대에 붙인다.
 *  4. 남은 0.12 s 미만 조각 중 가사 없는 것은 버린다.
 * 막대의 음높이는 안에서 가장 긴 음표의 것. 색은 [LiveNote.status] 를 모아 [statusOf] 로 정한다.
 */
class DisplayBar(val start: Double, val end: Double, val midi: Int, val notes: List<LiveNote>) {
    /** 막대 색을 정하는 대표 상태: 하나라도 부르는 중이면 ACTIVE, 모두 미판정이면 UPCOMING, 아니면 판정된 것들의 다수결(정확 우선). */
    fun statusOf(): LiveStatus {
        if (notes.any { it.status == LiveStatus.ACTIVE }) return LiveStatus.ACTIVE
        val judged = notes.filter { it.status.judged }
        if (judged.isEmpty()) return LiveStatus.UPCOMING
        val good = judged.count { it.status == LiveStatus.GOOD }
        if (good * 2 >= judged.size) return LiveStatus.GOOD
        return judged.filter { it.status != LiveStatus.GOOD }.groupingBy { it.status }.eachCount().maxByOrNull { it.value }!!.key
    }
}

object DisplayBars {
    private const val JOIN_GAP_SECONDS = 0.12
    private const val SHORT_SECONDS = 0.15
    private const val MIN_BAR_SECONDS = 0.12
    /** 가사 줄 앞뒤로 이만큼은 그 줄의 음표로 본다 */
    const val LINE_PAD_SECONDS = 0.6

    /** [lines] 가 비어 있으면 구간 필터를 하지 않는다(가사 없는 곡). */
    fun inLyricSpan(note: LiveNote, lines: List<LyricLine>): Boolean {
        if (lines.isEmpty()) return true
        for (line in lines) {
            if (note.start >= line.start - LINE_PAD_SECONDS && note.start <= line.end + LINE_PAD_SECONDS) return true
        }
        return false
    }

    fun build(notes: List<LiveNote>, lines: List<LyricLine>): List<DisplayBar> {
        class Mutable(var start: Double, var end: Double, val notes: MutableList<LiveNote>)
        val bars = ArrayList<Mutable>()
        for (note in notes.filter { inLyricSpan(it, lines) }.sortedBy { it.start }) {
            val last = bars.lastOrNull()
            val gap = if (last != null) note.start - last.end else Double.MAX_VALUE
            val short = note.end - note.start < SHORT_SECONDS
            if (last != null && gap <= JOIN_GAP_SECONDS) {
                val lastMidi = last.notes.maxByOrNull { it.end - it.start }!!.midi
                val samePitch = note.midi == lastMidi
                val ornament = short && note.lyric.isNullOrEmpty() && abs(note.midi - lastMidi) <= 2
                if (samePitch || ornament) {
                    last.end = max(last.end, note.end)
                    last.notes.add(note)
                    continue
                }
            }
            bars.add(Mutable(note.start, note.end, mutableListOf(note)))
        }
        return bars
            .filter { it.end - it.start >= MIN_BAR_SECONDS || it.notes.any { n -> !n.lyric.isNullOrEmpty() } }
            .map { bar -> DisplayBar(bar.start, bar.end, bar.notes.maxByOrNull { it.end - it.start }!!.midi, bar.notes.toList()) }
    }
}
