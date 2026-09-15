package com.micon.app.data.backend.dto

import com.micon.app.scoring.LiveNote
import com.micon.app.scoring.NoteNormalizer
import com.micon.app.scoring.RefNoteInput

/*
 * 곡 데이터 DTO. 백엔드 `practice-assets`(`song_note.karaoke_notes`, `song_analysis.beat_grid`)가 VocalScope 곡 처리 결과와
 * 같은 JSON 형식을 준다. 예전에는 `data/engine/dto`(엔진 REST 응답)에 있었고, 엔진 직접 호출을 없앤 뒤 여기로 옮겼다.
 */

/** `beatGrid`: 비트 시각(초)과 첫 비트의 박 오프셋. 러너웨이 마디선·카운트인에 쓴다. */
data class BeatGrid(val beatTimes: List<Double> = emptyList(), val offsetBeats: Double = 0.0)

/** `karaokeNotes[]` — 가사 음절이 붙은 노래방 음표 (DATA_INVENTORY §2.6). 시간은 원곡 초. */
data class KaraokeNote(
    val sourceStart: Double,
    val sourceEnd: Double,
    val midi: Int,
    val actualMidi: Double? = null,
    val name: String? = null,
    val cents: Int? = null,
    val confidence: Double? = null,
    val duration: Double? = null,
    /** modal | falsetto | mixed | unclear */
    val register: String? = null,
    val registerConfidence: Double? = null,
    val lightness: Double? = null,
    val breathy: Boolean? = null,
    /** 음절. 멜리스마 연장은 null */
    val lyric: String? = null,
    val lyricExtend: Boolean? = null,
    val lyricStart: Double? = null,
    val lyricEnd: Double? = null,
    /** aligned | low-confidence | unknown-language */
    val lyricStatus: String? = null,
    val scoreStartBeat: Double? = null,
    val scoreEndBeat: Double? = null,
    val scoreStart: Double? = null,
    val scoreEnd: Double? = null,
)

fun KaraokeNote.toRefNoteInput() = RefNoteInput(
    midi = midi, lyric = lyric, scoreStartBeat = scoreStartBeat,
    sourceStart = sourceStart, sourceEnd = sourceEnd,
    lyricStart = lyricStart, lyricEnd = lyricEnd,
)

/** 백엔드 `targetNotes`(= karaoke_notes JSON 배열) → 라이브 채점·가사 싱크용 음표. 박자표는 마디 계산에 쓴다. */
fun List<KaraokeNote>.toLiveNotes(timeSignature: String?): List<LiveNote> =
    NoteNormalizer.toLiveNotes(map { it.toRefNoteInput() }, null, timeSignature)
