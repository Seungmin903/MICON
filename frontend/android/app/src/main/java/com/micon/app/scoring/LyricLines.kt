package com.micon.app.scoring

/**
 * 노래방식 가사 스트립용 줄 묶기 (live-scoring.tsx L410–446 의 순수 로직 추출).
 * 원 가사 텍스트의 줄바꿈·띄어쓰기를 음절에 복원해 줄 단위로 묶고, 텍스트가 없으면 시간 간격으로 근사한다.
 */
data class LyricToken(val note: LiveNote, val space: Boolean)

data class LyricLine(val tokens: List<LyricToken>, val start: Double, val end: Double) {
    val text: String get() = tokens.joinToString("") { (if (it.space) " " else "") + it.note.lyric }
}

object LyricLines {
    fun build(notes: List<LiveNote>, lyricsText: String?): List<LyricLine> {
        val syllables = notes.filter { !it.lyric.isNullOrEmpty() }
        val text = lyricsText ?: ""
        val lines = ArrayList<LyricLine>()
        var line = ArrayList<LyricToken>()
        var pos = 0
        fun pushLine() {
            if (line.isNotEmpty()) lines.add(LyricLine(line, line.first().note.start, line.last().note.end))
            line = ArrayList()
        }
        for (i in syllables.indices) {
            val note = syllables[i]
            val lyric = note.lyric!!
            val gap = if (i > 0) note.start - syllables[i - 1].end else 0.0
            var space = false
            var newline = false
            val idx = if (text.isNotEmpty()) text.indexOf(lyric, pos) else -1
            if (idx >= 0 && idx - pos <= 24) {
                val between = text.substring(pos, idx)
                if (between.contains('\n')) newline = true
                else if (between.any { it.isWhitespace() }) space = true
                pos = idx + lyric.length
            } else {
                // 텍스트 매칭 실패(멜리스마 연장·편집된 가사 등) — 시간 간격으로 근사
                newline = gap > 1.2
                space = !newline && gap > 0.35
            }
            val lineChars = line.sumOf { it.note.lyric!!.length + (if (it.space) 1 else 0) }
            if (i > 0 && (newline || gap > 2.5 || (lineChars >= 22 && space))) pushLine()
            if (line.isEmpty()) space = false
            line.add(LyricToken(note, space))
        }
        pushLine()
        return lines
    }
}
