package com.micon.app.scoring

import kotlin.math.floor

/** 엔진 `karaokeNotes[]` / `notes[]` 공통 입력 (live-scoring.tsx RefNoteInput). */
data class RefNoteInput(
    val midi: Int,
    val lyric: String? = null,
    val scoreStartBeat: Double? = null,
    val sourceStart: Double? = null,
    val sourceEnd: Double? = null,
    val start: Double? = null,
    val end: Double? = null,
)

/** 원곡 음표를 LiveNote 로 정규화 (live-scoring.tsx `refNotes` useMemo L257–275). */
object NoteNormalizer {
    /**
     * karaokeNotes 가 10개 이상이면 그것을, 아니면 notes 를 쓴다.
     * measure = floor(scoreStartBeat / meter), 없으면 floor(start / 2). weight = clamp(duration, 0.1, 1.5).
     */
    fun toLiveNotes(karaokeNotes: List<RefNoteInput>?, notes: List<RefNoteInput>?, timeSignature: String?): List<LiveNote> {
        val source = if ((karaokeNotes?.size ?: 0) >= 10) karaokeNotes!! else notes ?: emptyList()
        val meter = meterOf(timeSignature)
        return source
            .sortedBy { startOf(it) }
            .mapIndexed { index, note ->
                val start = startOf(note)
                val end = endOf(note)
                LiveNote(
                    index = index,
                    start = start,
                    end = end,
                    midi = note.midi,
                    lyric = note.lyric,
                    measure = note.scoreStartBeat?.let { floor(it / meter).toInt() } ?: floor(start / 2).toInt(),
                    weight = (end - start).coerceIn(0.1, 1.5),
                )
            }
    }

    /** "4/4" → 4. 파싱 실패는 4. */
    fun meterOf(timeSignature: String?): Int =
        timeSignature?.substringBefore('/')?.trim()?.toIntOrNull()?.takeIf { it > 0 } ?: 4

    private fun startOf(note: RefNoteInput) = note.sourceStart ?: note.start ?: 0.0
    private fun endOf(note: RefNoteInput) = note.sourceEnd ?: note.end ?: startOf(note)
}
