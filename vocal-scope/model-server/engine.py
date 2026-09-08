from __future__ import annotations

import os
import shutil
import subprocess
import sys
from pathlib import Path
from typing import Any, Callable

import imageio_ffmpeg
import numpy as np

from karaoke import build_karaoke_notes, finalize_notes
from lyrics_engine import align_lyrics, transcribe_lyrics
from pitch_engine import extract_vocal_melody, make_time_to_beat
from range_summary import NOTE_NAMES, _analyze_range, _weighted_percentile, midi_to_name  # noqa: F401  (shared with offline importers)
from separation import normalize_backend, separate

KARAOKE_PRESERVED_KEYS = (
    "lyricScore", "lyricExtend", "lyricStart", "lyricEnd", "lyricStatus", "explicitExtend",
    "register", "registerConfidence", "registerScore", "lightness", "breathy",
    "h1h2Db", "hrfDb", "cppDb", "quantizationAdjusted",
)


def _convert_to_wav(source: Path, target: Path) -> None:
    ffmpeg = imageio_ffmpeg.get_ffmpeg_exe()
    command = [ffmpeg, "-y", "-i", str(source), "-vn", "-ac", "2", "-ar", "44100", str(target)]
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace")
    if completed.returncode != 0:
        raise RuntimeError(f"음원 변환 실패: {completed.stderr[-600:]}")


def _separate(input_wav: Path, output_dir: Path, device: str, backend: str | None = None) -> tuple[Path, Path, dict[str, Any]]:
    """Vocal/accompaniment separation (Mel-Band RoFormer by default, htdemucs fallback) — see separation.py."""
    return separate(input_wav, output_dir, device, backend)


def _pitch_label(pitch: Any) -> str:
    accidental = ""
    if pitch.accidental is not None:
        accidental = pitch.accidental.unicode or pitch.accidental.modifier
    return f"{pitch.step}{accidental}{pitch.octave}"


def _write_filtered_midi(
    notes: list[dict[str, Any]],
    target: Path,
    tempo_bpm: int,
    meter: int,
    key: dict[str, Any] | None = None,
) -> None:
    import pretty_midi

    midi = pretty_midi.PrettyMIDI(initial_tempo=tempo_bpm)
    midi.time_signature_changes.append(pretty_midi.TimeSignature(meter, 4, 0.0))
    if key:
        key_number = int(key["tonic"]) % 12 + (12 if key.get("mode") == "minor" else 0)
        midi.key_signature_changes.append(pretty_midi.KeySignature(key_number, 0.0))
    vocal = pretty_midi.Instrument(program=53, name="Vocal Melody")
    for item in notes:
        velocity = round(45 + item["confidence"] * 75)
        vocal.notes.append(pretty_midi.Note(
            velocity=max(1, min(127, velocity)),
            pitch=item["midi"],
            start=item["scoreStart"],
            end=item["scoreEnd"],
        ))
    midi.instruments.append(vocal)
    midi.write(str(target))
    lyric_events = [(str(item["lyric"]), midi.time_to_tick(item["scoreStart"])) for item in notes if item.get("lyric")]
    if lyric_events:
        _insert_midi_lyrics(target, lyric_events)


def _insert_midi_lyrics(target: Path, lyric_events: list[tuple[str, int]]) -> None:
    """pretty_midi/mido save meta text as latin-1; re-open as UTF-8 and add Hangul lyric events."""
    import mido

    midi_file = mido.MidiFile(str(target), charset="utf-8")
    track = midi_file.tracks[-1]
    absolute: list[tuple[int, int, Any]] = []
    tick = 0
    for message in track:
        tick += message.time
        absolute.append((tick, 1 if message.type == "end_of_track" else 0, message))
    for text, at in lyric_events:
        absolute.append((int(at), 0, mido.MetaMessage("lyrics", text=text, time=0)))
    absolute.sort(key=lambda entry: (entry[0], entry[1]))
    rebuilt = mido.MidiTrack()
    previous = 0
    for at, _, message in absolute:
        message.time = at - previous
        previous = at
        rebuilt.append(message)
    midi_file.tracks[-1] = rebuilt
    midi_file.save(str(target))


def _respell_for_key(pitches: list[Any], sharps: int) -> None:
    """Spell inferred accidentals in the direction of the key signature (flat keys → flats)."""
    if sharps == 0:
        return
    for pitch in pitches:
        accidental = pitch.accidental
        if accidental is None or not getattr(pitch, "spellingIsInferred", True):
            continue
        if (sharps < 0 and accidental.alter > 0) or (sharps > 0 and accidental.alter < 0):
            pitch.getEnharmonic(inPlace=True)


LOW_RANGE_MIDI = 65  # F4: a melody whose median sits below this (typical male range) is written an octave up


def written_octave_shift(notes: list[dict[str, Any]], mode: str = "auto") -> int:
    """Octaves to add to the *written* pitch. Published scores write low (male) melodies an octave up in
    treble clef so the notes sit on the staff; the sounding pitch (labels, MIDI) is unchanged."""
    if mode == "up":
        return 1
    if mode == "actual":
        return 0
    if not notes:
        return 0
    midis = sorted(int(item["midi"]) for item in notes)
    median = midis[len(midis) // 2]
    return 1 if median < LOW_RANGE_MIDI else 0


def _create_musicxml(
    midi_path: Path,
    target: Path,
    syllables: list[str | None] | None = None,
    title: str = "Vocal Melody",
    octave_shift: int = 0,
) -> bool:
    """MIDI → MusicXML with `C3`-style labels (always the sounding octave). With `syllables` (one per source
    note, None = melisma) the syllable becomes lyric line 1 and the label line 2. `octave_shift` raises the
    written pitch (8vb notation) without touching labels. Returns whether the syllables could be embedded."""
    from music21 import chord, converter, expressions, key as m21_key, note

    score = converter.parse(str(midi_path), format="midi")
    score.metadata.title = title
    score.metadata.composer = "VocalScope · CREPE + pYIN"
    if octave_shift:
        first_measure = score.parts[0].getElementsByClass("Measure").first()
        if first_measure is not None:
            marker = expressions.TextExpression("8vb · 실음은 표기보다 한 옥타브 아래")
            marker.placement = "above"
            first_measure.insert(0, marker)
    signature = score.recurse().getElementsByClass(m21_key.KeySignature).first()
    if signature is None:
        try:
            signature = score.analyze("key")
            first_measure = score.parts[0].getElementsByClass("Measure").first()
            if first_measure is not None:
                first_measure.insert(0, signature)
        except Exception:
            signature = None
    sharps = int(signature.sharps) if signature is not None else 0
    ordered = list(score.flatten().notes)
    heads = [item for item in ordered if item.tie is None or item.tie.type == "start"]
    embed = syllables is not None and len(heads) == len(syllables)
    head_index = 0
    for item in ordered:
        if isinstance(item, note.Note):
            _respell_for_key([item.pitch], sharps)
            label = _pitch_label(item.pitch)
            item.pitch.octave += octave_shift
        elif isinstance(item, chord.Chord):
            _respell_for_key(list(item.pitches), sharps)
            label = "/".join(_pitch_label(pitch) for pitch in item.pitches)
            for pitch in item.pitches:
                pitch.octave += octave_shift
        else:
            continue
        is_head = item.tie is None or item.tie.type == "start"
        if not embed:
            item.lyric = label
            continue
        item.lyrics = []
        if is_head:
            syllable = syllables[head_index]
            head_index += 1
            if syllable:
                item.addLyric(str(syllable), 1)
            item.addLyric(label, 2)
    score.write("musicxml", fp=str(target))
    return embed


def render_karaoke(
    job_dir: Path,
    notes: list[dict[str, Any]],
    tempo_bpm: int,
    meter: int,
    key: dict[str, Any] | None,
    octave_shift: int = 0,
) -> dict[str, Any]:
    """Write karaoke.mid / karaoke.musicxml for the simplified, lyric-carrying melody."""
    midi_target = job_dir / "karaoke.mid"
    xml_target = job_dir / "karaoke.musicxml"
    ordered = sorted(notes, key=lambda item: item["scoreStartBeat"])
    _write_filtered_midi(ordered, midi_target, tempo_bpm, meter, key)
    embedded = _create_musicxml(
        midi_target, xml_target, [item.get("lyric") for item in ordered], title="Karaoke Melody", octave_shift=octave_shift,
    )
    return {"lyricsEmbedded": embedded, "noteCount": len(ordered), "octaveShift": octave_shift}


def rerender_source_score(job_dir: Path, octave_shift: int) -> None:
    """Redraw vocal.musicxml (the faithful transcription) with a different written octave."""
    midi_target = job_dir / "vocal.mid"
    if midi_target.exists():
        _create_musicxml(midi_target, job_dir / "vocal.musicxml", octave_shift=octave_shift)


def rebuild_karaoke(job_dir: Path, edited: list[dict[str, Any]], result: dict[str, Any], octave_mode: str = "auto") -> list[dict[str, Any]]:
    """Apply editor output (midi / scoreStartBeat / scoreEndBeat / lyric) and regenerate the karaoke files."""
    cleaned: list[dict[str, Any]] = []
    for item in edited:
        start = float(item["scoreStartBeat"])
        end = float(item["scoreEndBeat"])
        midi = int(item["midi"])
        if end <= start or not 36 <= midi <= 95:
            continue
        cleaned_note = {
            "midi": midi,
            "actualMidi": float(item.get("actualMidi", midi)),
            "confidence": float(item.get("confidence", 0.9)),
            "scoreStartBeat": start,
            "scoreEndBeat": end,
            "sourceStart": float(item.get("sourceStart", start * 60.0 / result["tempoBpm"])),
            "sourceEnd": float(item.get("sourceEnd", end * 60.0 / result["tempoBpm"])),
            "lyric": (str(item.get("lyric") or "").strip() or None),
        }
        for key in KARAOKE_PRESERVED_KEYS:
            if key in item:
                cleaned_note[key] = item[key]
        cleaned.append(cleaned_note)
    cleaned.sort(key=lambda item: item["scoreStartBeat"])
    for current, following in zip(cleaned, cleaned[1:]):
        current["scoreEndBeat"] = min(current["scoreEndBeat"], following["scoreStartBeat"])
    cleaned = [item for item in cleaned if item["scoreEndBeat"] > item["scoreStartBeat"]]
    if not cleaned:
        raise RuntimeError("음표가 하나도 없어 악보를 만들 수 없습니다.")
    finalize_notes(cleaned, result["tempoBpm"], midi_to_name)
    meter = int(result["timeSignature"].split("/")[0])
    shift = written_octave_shift(cleaned, octave_mode)
    render_karaoke(job_dir, cleaned, result["tempoBpm"], meter, result.get("key"), shift)
    if shift != int(result.get("scoreOctave", {}).get("shift", 0)):
        rerender_source_score(job_dir, shift)
    result["scoreOctave"] = {"mode": octave_mode, "shift": shift}
    return cleaned


def realign_lyrics(job_dir: Path, text: str, result: dict[str, Any], device: str) -> tuple[list[dict[str, Any]], dict[str, Any], dict[str, Any]]:
    """Re-run forced alignment with corrected lyrics and rebuild the karaoke melody from the source notes."""
    vocals = job_dir / "vocals.wav"
    if not vocals.exists():
        raise RuntimeError("보컬 스템이 저장 정책으로 삭제된 작업입니다. 가사 재정렬은 원곡을 다시 분석한 뒤 가능합니다.")
    aligned = align_lyrics(vocals, text, device)
    syllables = aligned["syllables"] or None
    time_to_beat = make_time_to_beat(result["beatGrid"])
    karaoke_notes, stats = build_karaoke_notes(result["notes"], syllables, time_to_beat, result["tempoBpm"], midi_to_name)
    meter = int(result["timeSignature"].split("/")[0])
    octave_mode = result.get("scoreOctave", {}).get("mode", "auto")
    shift = written_octave_shift(karaoke_notes, octave_mode)
    result["scoreOctave"] = {"mode": octave_mode, "shift": shift}
    rendered = render_karaoke(job_dir, karaoke_notes, result["tempoBpm"], meter, result.get("key"), shift)
    lyrics_info = {
        "source": "user",
        "text": text,
        "syllables": len(aligned["syllables"]),
        "alignmentScore": aligned["score"],
        "unknownTokens": aligned.get("unknownTokenCount", 0),
        "explicitExtensions": aligned.get("explicitExtensions", 0),
        "error": None,
    }
    stats.update(rendered)
    return karaoke_notes, stats, lyrics_info


def run_pipeline(
    job_dir: Path,
    input_path: Path,
    update: Callable[[int, str], None],
    skip_separation: bool = False,
    lyrics_text: str | None = None,
    separator: str | None = None,
    skip_lyrics: bool = False,
    compact: bool = False,
) -> dict[str, Any]:
    """Full analysis of one audio file. `skip_lyrics` (performance recordings for comparison) leaves out
    Whisper / forced alignment — the melody, VAD and F0 stages are identical to the reference analysis.
    `compact` applies the storage policy afterwards (compact.py): accompaniment → Opus, every intermediate WAV
    and the vocal stem removed, MusicXML regenerated on demand."""
    import torch

    device = "cuda" if torch.cuda.is_available() else "cpu"
    separation_info: dict[str, Any] = {"backend": "none", "model": None, "seconds": 0.0}
    normalized = job_dir / "input.wav"
    separated_dir = job_dir / "separated"
    vocals_target = job_dir / "vocals.wav"
    instrumental_target = job_dir / "instrumental.wav"
    midi_target = job_dir / "vocal.mid"
    musicxml_target = job_dir / "vocal.musicxml"
    frames_target = job_dir / "pitch_frames.npz"

    update(5, "음원 형식을 분석용 WAV로 변환하고 있습니다.")
    _convert_to_wav(input_path, normalized)
    if skip_separation:
        shutil.copy2(normalized, vocals_target)
        update(58, "보컬 파일 확인 완료 · F0 추적 모델을 준비하고 있습니다.")
    else:
        backend = normalize_backend(separator)
        model_name = "Mel-Band RoFormer" if backend == "melband" else "HTDemucs"
        update(12, f"{model_name}가 {device.upper()}에서 보컬과 반주를 분리하고 있습니다.")
        vocals, instrumental, separation_info = _separate(normalized, separated_dir, device, backend)
        shutil.copy2(vocals, vocals_target)
        shutil.copy2(instrumental, instrumental_target)

    update(55, "보컬 활동을 감지하고 가창 기본주파수(F0)를 추적하고 있습니다.")
    melody = extract_vocal_melody(vocals_target, None if skip_separation else normalized, device, return_frames=True)
    frames = melody.pop("frames")
    np.savez_compressed(
        frames_target,
        midi=frames["midi"], voiced=frames["voiced"], confidence=frames["confidence"], dbfs=frames["dbfs"],
        frame_seconds=np.float32(frames["frameSeconds"]),
    )
    notes = melody.pop("notes")
    for item in notes:
        item["name"] = midi_to_name(item["midi"])
    meter = int(melody["timeSignature"].split("/")[0])
    _write_filtered_midi(notes, midi_target, melody["tempoBpm"], meter, melody.get("key"))

    update(70, "단선율을 박자 격자에 정렬하고 원곡 채보 악보를 만들고 있습니다.")
    analysis = _analyze_range(notes)
    octave_shift = written_octave_shift(notes)
    _create_musicxml(midi_target, musicxml_target, octave_shift=octave_shift)

    # --- lyrics: user text → forced alignment; otherwise Whisper on the vocal stem first
    lyrics_info: dict[str, Any] = {"source": "none", "text": "", "syllables": 0, "alignmentScore": None, "unknownTokens": 0, "explicitExtensions": 0, "error": None}
    syllables: list[dict[str, Any]] | None = None
    try:
        text = (lyrics_text or "").strip()
        source = "user"
        if skip_lyrics:
            text = ""
            lyrics_info["source"] = "skipped"
        elif not text:
            update(76, "Whisper가 보컬 트랙에서 가사를 받아쓰고 있습니다.")
            text = transcribe_lyrics(vocals_target, device)["text"]
            source = "whisper"
        if text:
            update(84, "가사 음절을 보컬 타이밍에 강제 정렬하고 있습니다 (wav2vec2 CTC).")
            aligned = align_lyrics(vocals_target, text, device)
            syllables = aligned["syllables"] or None
            lyrics_info.update(
                source=source,
                text=text,
                syllables=len(aligned["syllables"]),
                alignmentScore=aligned["score"],
                unknownTokens=aligned.get("unknownTokenCount", 0),
                explicitExtensions=aligned.get("explicitExtensions", 0),
            )
    except Exception as error:  # lyrics are optional: never fail the whole job for them
        lyrics_info["error"] = str(error)[:400]

    update(92, "노래방 기준 멜로디(장식음 정리 · 음절별 음표)를 만들고 있습니다.")
    time_to_beat = make_time_to_beat(melody["beatGrid"])
    karaoke_notes, karaoke_stats = build_karaoke_notes(notes, syllables, time_to_beat, melody["tempoBpm"], midi_to_name)
    karaoke_stats.update(render_karaoke(job_dir, karaoke_notes, melody["tempoBpm"], meter, melody.get("key"), octave_shift))

    register = melody.get("register") or {}
    for key_name, target in (("modalRangeMidi", "modalRange"), ("falsettoRangeMidi", "falsettoRange")):
        span = register.get(key_name)
        register[target] = f"{midi_to_name(span[0])}–{midi_to_name(span[1])}" if span else None
    analysis["scoreOctave"] = {"mode": "auto", "shift": octave_shift}
    analysis["device"] = device
    analysis["separation"] = separation_info
    separation_label = separation_info.get("model") or "no-separation"
    analysis["model"] = f"{separation_label} + crepe-full + pyin + wav2vec2-ko"
    analysis.update(melody)
    analysis["lyrics"] = lyrics_info
    analysis["karaoke"] = karaoke_stats
    analysis["karaokeNotes"] = karaoke_notes
    analysis["karaokeVersion"] = 1
    analysis["artifacts"] = {
        "vocals": "vocals.wav",
        "midi": "vocal.mid",
        "musicxml": "vocal.musicxml",
        "karaoke-midi": "karaoke.mid",
        "karaoke-musicxml": "karaoke.musicxml",
        "pitch-frames": "pitch_frames.npz",
    }
    if instrumental_target.exists():
        analysis["artifacts"]["instrumental"] = "instrumental.wav"
    if compact:
        update(98, "중간 파일을 정리하고 반주를 Opus로 인코딩하고 있습니다.")
        from compact import compact_job

        compact_job(job_dir, analysis, delete_original=False)
    update(100, "보컬 활동 감지, F0 추적, 가사 정렬, 노래방 악보 생성이 완료되었습니다.")
    return analysis
