"""Karaoke reference melody: turn the faithful transcription into a singable, syllable-per-note score.

Rules (in order):
1. Split a note that contains several syllable onsets (repeated same-pitch syllables the tracker merged).
2. Attach each syllable to one onset note and mark following notes before the next syllable as melisma extensions.
3. Remove ornaments: notes shorter than a 16th (≤ 130 ms) with no syllable are absorbed by a neighbour
   within 3 semitones or dropped; a short note sliding into a longer neighbour ≤ 3 semitones away is a
   scoop and merges into it; with lyrics, short melisma tails ≤ 2 semitones away merge into the previous note.
4. Quantise to an 8th-note grid, falling back to 16ths only when the start is clearly off the 8th grid;
   simultaneous unlabelled starts merge; distinct lyric notes are kept in sequence.
"""

from __future__ import annotations

from typing import Any, Callable

import numpy as np

SYLLABLE_TOLERANCE = 0.15
SPLIT_MARGIN = 0.08
MIN_PIECE_SECONDS = 0.06
NEIGHBOUR_GAP = 0.06
ORNAMENT_MAX_SECONDS = 0.13
SCOOP_MAX_SECONDS = 0.20
MELISMA_MAX_SECONDS = 0.25
LEGATO_GAP_SECONDS = 0.10
EIGHTH_TOLERANCE = 0.17
MIN_BEATS = 0.25

NOTE_KEYS = ("sourceStart", "sourceEnd", "midi", "actualMidi", "confidence")
OPTIONAL_KEYS = ("register", "registerConfidence", "registerScore", "lightness", "breathy", "h1h2Db", "hrfDb", "cppDb")  # 성구 annotations ride along
LYRIC_KEYS = ("lyric", "lyricScore", "lyricExtend", "lyricStart", "lyricEnd", "lyricStatus", "explicitExtend")


def _clone(note: dict[str, Any]) -> dict[str, Any]:
    copy = {key: note[key] for key in NOTE_KEYS}
    for key in OPTIONAL_KEYS:
        if key in note:
            copy[key] = note[key]
    copy["lyric"] = None
    copy["lyricExtend"] = False
    return copy


def _duration(note: dict[str, Any]) -> float:
    return note["sourceEnd"] - note["sourceStart"]


# ----------------------------------------------------------------------------- syllables

def _split_on_syllables(notes: list[dict[str, Any]], onsets: np.ndarray) -> list[dict[str, Any]]:
    result: list[dict[str, Any]] = []
    for note in notes:
        inner = onsets[(onsets > note["sourceStart"] + SPLIT_MARGIN) & (onsets < note["sourceEnd"] - SPLIT_MARGIN)]
        if len(inner) == 0:
            result.append(note)
            continue
        cuts = [note["sourceStart"], *inner.tolist(), note["sourceEnd"]]
        for start, end in zip(cuts, cuts[1:]):
            if end - start < MIN_PIECE_SECONDS and result and result[-1] is not note:
                result[-1]["sourceEnd"] = end
                continue
            piece = dict(note)
            piece["sourceStart"], piece["sourceEnd"] = start, end
            result.append(piece)
    return result


def _assign_syllables(notes: list[dict[str, Any]], syllables: list[dict[str, Any]]) -> int:
    for note in notes:
        note["_syllables"] = []
        note["lyricExtend"] = False
    if not notes:
        return len(syllables)
    starts = np.array([note["sourceStart"] for note in notes])
    unassigned = 0
    ordered_syllables = sorted(syllables, key=lambda item: float(item["start"]))
    for syllable_index, syllable in enumerate(ordered_syllables):
        onset = float(syllable["start"])
        aligned_end = max(onset, float(syllable.get("end", onset)))
        next_onset = (
            float(ordered_syllables[syllable_index + 1]["start"])
            if syllable_index + 1 < len(ordered_syllables)
            else None
        )
        order = np.argsort(np.abs(starts - onset))[:3]
        primary: int | None = None
        for index in order:
            if abs(starts[index] - onset) <= SYLLABLE_TOLERANCE and not notes[index]["_syllables"]:
                primary = int(index)
                break
        if primary is None:
            for index, note in enumerate(notes):
                if note["sourceStart"] - 0.03 <= onset < note["sourceEnd"] and not note["_syllables"]:
                    primary = index
                    break
        if primary is None:
            overlaps = [
                max(0.0, min(note["sourceEnd"], aligned_end) - max(note["sourceStart"], onset))
                if not note["_syllables"] else 0.0
                for note in notes
            ]
            if overlaps and max(overlaps) > 0:
                primary = int(np.argmax(overlaps))
        if primary is None:
            unassigned += 1
            continue

        notes[primary]["_syllables"].append(syllable)
        notes[primary]["lyricExtend"] = False
        # CTC emits a short token span even while a vowel is held. Semantically that
        # syllable owns later sung notes until the next syllable onset.
        ownership_end = next_onset if next_onset is not None else aligned_end
        if bool(syllable.get("explicitExtend")) and next_onset is None:
            ownership_end = max(ownership_end, notes[-1]["sourceEnd"])
        for index in range(primary + 1, len(notes)):
            note = notes[index]
            if note["sourceStart"] >= ownership_end - 0.01:
                break
            if note["_syllables"]:
                continue
            if min(note["sourceEnd"], ownership_end) > max(note["sourceStart"], onset):
                note["lyricExtend"] = True

    for note in notes:
        items = note["_syllables"]
        item = items[0] if items else None
        note["lyric"] = item["text"] if item else None
        note["lyricScore"] = float(item["score"]) if item else None
        if item:
            note["lyricStart"] = float(item["start"])
            note["lyricEnd"] = float(item.get("end", item["start"]))
            note["explicitExtend"] = bool(item.get("explicitExtend"))
            note["lyricStatus"] = "unknown-language" if not item.get("known", True) else ("low-confidence" if float(item["score"]) < 0.2 else "aligned")
        del note["_syllables"]
    return unassigned


# ----------------------------------------------------------------------------- ornaments

def _merge_ornaments(notes: list[dict[str, Any]], has_lyrics: bool, ornament_seconds: float) -> list[dict[str, Any]]:
    notes = [dict(note) for note in notes]
    changed = True
    while changed:
        changed = False
        for index, note in enumerate(notes):
            duration = _duration(note)
            carries = bool(note.get("lyric") or note.get("lyricExtend"))
            previous = notes[index - 1] if index > 0 else None
            following = notes[index + 1] if index + 1 < len(notes) else None
            gap_before = note["sourceStart"] - previous["sourceEnd"] if previous else 9.0
            gap_after = following["sourceStart"] - note["sourceEnd"] if following else 9.0

            if duration < ornament_seconds and not carries:
                if previous and gap_before < NEIGHBOUR_GAP and abs(note["midi"] - previous["midi"]) <= 3:
                    previous["sourceEnd"] = note["sourceEnd"]
                elif following and gap_after < NEIGHBOUR_GAP and abs(note["midi"] - following["midi"]) <= 3:
                    following["sourceStart"] = note["sourceStart"]
                del notes[index]
                changed = True
                break

            if (
                following
                and duration < SCOOP_MAX_SECONDS
                and gap_after < NEIGHBOUR_GAP
                and 1 <= abs(following["midi"] - note["midi"]) <= 3
                and _duration(following) > duration * 1.5
                and not carries
            ):
                following["sourceStart"] = note["sourceStart"]
                following["lyric"] = following.get("lyric") or note.get("lyric")
                following["lyricScore"] = following.get("lyricScore") or note.get("lyricScore")
                del notes[index]
                changed = True
                break

            if (
                has_lyrics
                and previous
                and not carries
                and duration < MELISMA_MAX_SECONDS
                and gap_before < NEIGHBOUR_GAP
                and abs(note["midi"] - previous["midi"]) <= 2
            ):
                previous["sourceEnd"] = note["sourceEnd"]
                del notes[index]
                changed = True
                break
    return notes


# ----------------------------------------------------------------------------- grid

def _snap(beat: float) -> float:
    eighth = round(beat * 2) / 2
    if abs(beat - eighth) <= EIGHTH_TOLERANCE:
        return eighth
    return round(beat * 4) / 4


def _quantize(notes: list[dict[str, Any]], time_to_beat: Callable[[float], float]) -> list[dict[str, Any]]:
    for note in notes:
        note["scoreStartBeat"] = max(0.0, _snap(time_to_beat(note["sourceStart"])))
        note["_endBeat"] = time_to_beat(note["sourceEnd"])

    merged: list[dict[str, Any]] = []
    for note in notes:
        if merged and note["scoreStartBeat"] <= merged[-1]["scoreStartBeat"]:
            previous = merged[-1]
            previous_protected = bool(previous.get("lyric") or previous.get("lyricExtend"))
            note_protected = bool(note.get("lyric") or note.get("lyricExtend"))
            if previous_protected and note_protected:
                note["scoreStartBeat"] = previous["scoreStartBeat"] + MIN_BEATS
                note["quantizationAdjusted"] = True
                merged.append(note)
                continue
            keep, other = (merged[-1], note) if _duration(merged[-1]) >= _duration(note) else (note, merged[-1])
            labelled = previous if previous_protected else note if note_protected else None
            keep["scoreStartBeat"] = merged[-1]["scoreStartBeat"]
            keep["sourceStart"] = min(keep["sourceStart"], other["sourceStart"])
            keep["sourceEnd"] = max(keep["sourceEnd"], other["sourceEnd"])
            keep["_endBeat"] = max(keep["_endBeat"], other["_endBeat"])
            if labelled is not None:
                for key in LYRIC_KEYS:
                    if key in labelled:
                        keep[key] = labelled[key]
            merged[-1] = keep
            continue
        merged.append(note)

    for index, note in enumerate(merged):
        end = _snap(note["_endBeat"])
        start = note["scoreStartBeat"]
        end = max(start + MIN_BEATS, end)
        if index + 1 < len(merged):
            limit = merged[index + 1]["scoreStartBeat"]
            if merged[index + 1]["sourceStart"] - note["sourceEnd"] <= LEGATO_GAP_SECONDS:
                end = limit
            end = min(end, limit)
            end = max(end, min(start + MIN_BEATS, limit))
        note["scoreEndBeat"] = end
        del note["_endBeat"]
    return [note for note in merged if note["scoreEndBeat"] > note["scoreStartBeat"]]


# ----------------------------------------------------------------------------- public

def finalize_notes(
    notes: list[dict[str, Any]],
    tempo_bpm: int,
    midi_to_name: Callable[[int], str],
) -> list[dict[str, Any]]:
    """Fill derived fields (seconds on the score grid, names, cents) and round for JSON."""
    seconds_per_beat = 60.0 / tempo_bpm
    for note in notes:
        note["midi"] = int(note["midi"])
        note["name"] = midi_to_name(note["midi"])
        actual = float(note.get("actualMidi", note["midi"]))
        note["actualMidi"] = round(actual, 3)
        note["cents"] = int(round((actual - note["midi"]) * 100.0))
        note["confidence"] = round(float(note.get("confidence", 0.8)), 4)
        note["scoreStartBeat"] = round(float(note["scoreStartBeat"]), 4)
        note["scoreEndBeat"] = round(float(note["scoreEndBeat"]), 4)
        note["scoreStart"] = round(note["scoreStartBeat"] * seconds_per_beat, 4)
        note["scoreEnd"] = round(note["scoreEndBeat"] * seconds_per_beat, 4)
        note["sourceStart"] = round(float(note.get("sourceStart", note["scoreStart"])), 4)
        note["sourceEnd"] = round(float(note.get("sourceEnd", note["scoreEnd"])), 4)
        note["duration"] = round(note["sourceEnd"] - note["sourceStart"], 4)
        note["lyric"] = (note.get("lyric") or None)
        if note.get("lyricScore") is not None:
            note["lyricScore"] = round(float(note["lyricScore"]), 3)
    return notes


def build_karaoke_notes(
    notes: list[dict[str, Any]],
    syllables: list[dict[str, Any]] | None,
    time_to_beat: Callable[[float], float],
    tempo_bpm: int,
    midi_to_name: Callable[[int], str],
) -> tuple[list[dict[str, Any]], dict[str, Any]]:
    working = [_clone(note) for note in sorted(notes, key=lambda item: item["sourceStart"])]
    has_lyrics = bool(syllables)
    unassigned = 0
    if has_lyrics:
        onsets = np.array(sorted(item["start"] for item in syllables))
        working = _split_on_syllables(working, onsets)
        unassigned = _assign_syllables(working, syllables)
    sixteenth_seconds = 60.0 / tempo_bpm / 4.0
    ornament_seconds = min(ORNAMENT_MAX_SECONDS, 0.85 * sixteenth_seconds)
    working = _merge_ornaments(working, has_lyrics, ornament_seconds)
    working = _quantize(working, time_to_beat)
    finalize_notes(working, tempo_bpm, midi_to_name)
    stats = {
        "sourceNotes": len(notes),
        "karaokeNotes": len(working),
        "notesWithLyric": sum(1 for note in working if note.get("lyric")),
        "melismaNotes": sum(1 for note in working if note.get("lyricExtend")),
        "lowConfidenceLyrics": sum(1 for note in working if note.get("lyricStatus") != "aligned" and note.get("lyric")),
        "quantizationAdjustments": sum(1 for note in working if note.get("quantizationAdjusted")),
        "unassignedSyllables": unassigned,
    }
    return working, stats
