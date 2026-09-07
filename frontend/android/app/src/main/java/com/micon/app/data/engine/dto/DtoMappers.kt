package com.micon.app.data.engine.dto

import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.NoteNormalizer
import com.micon.app.scoring.RefNoteInput

fun KaraokeNote.toRefNoteInput() = RefNoteInput(
    midi = midi, lyric = lyric, scoreStartBeat = scoreStartBeat,
    sourceStart = sourceStart, sourceEnd = sourceEnd,
)

fun SourceNote.toRefNoteInput() = RefNoteInput(
    midi = midi, lyric = null, scoreStartBeat = scoreStartBeat,
    sourceStart = sourceStart, sourceEnd = sourceEnd, start = start, end = end,
)

/** 라이브 채점용 음표 목록. karaokeNotes ≥ 10 이면 그것, 아니면 notes. */
fun JobResult.toLiveNotes(): List<LiveNote> = NoteNormalizer.toLiveNotes(
    karaokeNotes = karaokeNotes?.map { it.toRefNoteInput() },
    notes = notes?.map { it.toRefNoteInput() },
    timeSignature = timeSignature,
)

/** DB `song_notes.karaoke_notes` JSON 배열(= result.karaokeNotes) → 라이브 음표. */
fun List<KaraokeNote>.toLiveNotes(timeSignature: String?): List<LiveNote> =
    NoteNormalizer.toLiveNotes(map { it.toRefNoteInput() }, null, timeSignature)
