"""Offline evaluation CLI for the VocalScope melody engine.

Examples (always run with the model venv interpreter):
  .venv-model/Scripts/python.exe model-server/evaluate.py --job model-server/workspace/<job-id> --expect-vocal-start-min 21
  .venv-model/Scripts/python.exe model-server/evaluate.py --audio song.mp3 --out <dir>
  .venv-model/Scripts/python.exe model-server/evaluate.py --folder "C:/songs" --out <dir>
"""

from __future__ import annotations

import argparse
import csv
import json
import shutil
import sys
import tempfile
import time
from pathlib import Path
from typing import Any

sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
sys.path.insert(0, str(Path(__file__).resolve().parent))

import numpy as np  # noqa: E402

from engine import _convert_to_wav, _create_musicxml, _separate, _write_filtered_midi, midi_to_name, render_karaoke, written_octave_shift  # noqa: E402
from karaoke import build_karaoke_notes  # noqa: E402
from lyrics_engine import align_lyrics, transcribe_lyrics  # noqa: E402
from pitch_engine import FRAME_SECONDS, extract_vocal_melody, make_time_to_beat  # noqa: E402

AUDIO_EXTENSIONS = {".mp3", ".wav", ".flac", ".m4a", ".ogg"}


def _energy_vocal_start(debug: dict[str, Any]) -> float | None:
    """Reference vocal start from energy alone: first frame where vocal/mix ratio ≥ 0.15 for ≥ 60 ms and dBFS ≥ -50."""
    ratio = debug.get("ratio")
    dbfs = debug["dbfs"]
    if ratio is None:
        active = dbfs >= -50.0
    else:
        active = (ratio >= 0.15) & (dbfs >= -50.0)
    run = 0
    for index, flag in enumerate(active):
        run = run + 1 if flag else 0
        if run >= 6:
            return (index - 5) * FRAME_SECONDS
    return None


def _describe(result: dict[str, Any], elapsed: float, label: str) -> dict[str, Any]:
    notes = result["notes"]
    debug = result.get("_debug", {})
    energy_start = _energy_vocal_start(debug) if debug else None
    early = [note for note in notes if energy_start is not None and note["sourceStart"] < energy_start - 0.3]
    summary = {
        "label": label,
        "vocalStart": result["vocalStart"],
        "energyVocalStart": None if energy_start is None else round(energy_start, 3),
        "notesBeforeEnergyStart": len(early),
        "noteCount": len(notes),
        "voicedRatio": result["voicedRatio"],
        "voicedSeconds": result["voicedSeconds"],
        "f0AgreementRatio": result["f0AgreementRatio"],
        "octaveMismatchRatio": result["octaveMismatchRatio"],
        "comparedFrames": result["comparedFrames"],
        "tuningCents": result["tuningCents"],
        "key": result["key"]["name"],
        "tempoBpm": result["tempoBpm"],
        "timeSignature": result["timeSignature"],
        "engineSeconds": round(elapsed, 1),
        "timings": result["timings"],
    }
    print(f"\n=== {label}")
    for name, value in summary.items():
        if name not in {"label", "timings"}:
            print(f"  {name:22s} {value}")
    print(f"  {'timings':22s} {result['timings']}")
    register = result.get("register") or {}
    if register.get("error"):
        print(f"  register: FAILED {register['error']}")
        summary["registerError"] = register["error"]
    elif register:
        counts = {label: sum(1 for note in notes if note.get("register") == label) for label in ("modal", "falsetto", "mixed", "unclear")}
        summary.update({
            "registerBimodal": register.get("bimodal"),
            "registerAshmanD": register.get("ashmanD"),
            "registerRatios": register.get("ratios"),
            "registerNoteCounts": counts,
            "registerMedianFeatures": register.get("medianFeatures"),
        })
        print(
            f"  register: bimodal={register.get('bimodal')} D={register.get('ashmanD')} weights={register.get('componentWeights')} "
            f"single={register.get('singleRegisterLabel')} ratios={register.get('ratios')} notes={counts}"
        )
        print(f"    median features {register.get('medianFeatures')}  modalRange={register.get('modalRangeMidi')} falsettoRange={register.get('falsettoRangeMidi')} medianMidi={register.get('medianMidi')} atypicalFrames={register.get('atypicalFrames')}")
        for caveat in register.get("caveats") or []:
            print(f"    caveat: {caveat}")
        summary["registerCaveats"] = register.get("caveats")
        falsetto = [note for note in notes if note.get("register") == "falsetto"]
        if falsetto:
            print("    falsetto notes (first 12):")
            for note in falsetto[:12]:
                print(f"      {note['sourceStart']:8.3f}–{note['sourceEnd']:8.3f} {midi_to_name(note['midi']):4s} p={note.get('falsettoProbability')} conf={note.get('registerConfidence')} h1h2={note.get('h1h2Db')} cpp={note.get('cppDb')}")
    print("  first notes:")
    for note in notes[:15]:
        print(
            f"    {note['sourceStart']:8.3f}–{note['sourceEnd']:8.3f}  {midi_to_name(note['midi']):4s} "
            f"midi={note['midi']:3d} cents={note['cents']:+4d} conf={note['confidence']:.2f} beat={note['scoreStartBeat']:.2f} "
            f"reg={note.get('register', '-')}/{note.get('registerConfidence', 0):.2f}"
        )
    return summary


def _dump_frames(result: dict[str, Any], path: Path) -> None:
    debug = result["_debug"]
    length = len(debug["dbfs"])
    ratio = debug["ratio"] if debug["ratio"] is not None else np.full(length, np.nan)
    register = debug.get("register") or {}
    register_columns = [name for name in ("composite", "h1h2", "hrf", "slope", "alpha", "cpp") if name in register]

    def register_value(name: str, index: int) -> str:
        value = float(register[name][index])
        return "" if not np.isfinite(value) else f"{value:.3f}"

    with path.open("w", newline="", encoding="utf-8") as handle:
        writer = csv.writer(handle)
        writer.writerow(["frame", "time", "dbfs", "ratio", "periodicity", "strict", "lenient", "voiced", "midi", "pyinMidi", *[f"reg_{name}" for name in register_columns]])
        for index in range(length):
            writer.writerow([
                index,
                round(index * FRAME_SECONDS, 3),
                round(float(debug["dbfs"][index]), 2),
                round(float(ratio[index]), 4),
                round(float(debug["periodicity"][index]), 4),
                int(debug["strict"][index]),
                int(debug["lenient"][index]),
                int(debug["voiced"][index]),
                round(float(debug["midi"][index]), 3),
                round(float(debug["pyinMidi"][index]), 3) if np.isfinite(debug["pyinMidi"][index]) else "",
                *[register_value(name, index) for name in register_columns],
            ])


KARAOKE: dict[str, Any] = {"enabled": False, "lyrics": None}
SEPARATOR: dict[str, Any] = {"backend": None}


def _run_karaoke(vocals: Path, result: dict[str, Any], notes: list[dict[str, Any]], device: str, out_dir: Path, summary: dict[str, Any]) -> None:
    text = KARAOKE["lyrics"]
    source = "user"
    started = time.perf_counter()
    if not text:
        text = transcribe_lyrics(vocals, device)["text"]
        source = "whisper"
    whisper_seconds = time.perf_counter() - started
    started = time.perf_counter()
    aligned = align_lyrics(vocals, text, device) if text else {"syllables": [], "score": 0.0}
    align_seconds = time.perf_counter() - started
    karaoke_notes, stats = build_karaoke_notes(notes, aligned["syllables"] or None, make_time_to_beat(result["beatGrid"]), result["tempoBpm"], midi_to_name)
    meter = int(result["timeSignature"].split("/")[0])
    stats.update(render_karaoke(out_dir, karaoke_notes, result["tempoBpm"], meter, result["key"], written_octave_shift(karaoke_notes)))
    summary.update({
        "lyricsSource": source,
        "syllables": len(aligned["syllables"]),
        "alignmentScore": aligned["score"],
        "karaokeNotes": stats["karaokeNotes"],
        "notesWithLyric": stats["notesWithLyric"],
        "unassignedSyllables": stats["unassignedSyllables"],
        "lyricsEmbedded": stats["lyricsEmbedded"],
        "whisperSeconds": round(whisper_seconds, 1),
        "alignSeconds": round(align_seconds, 1),
    })
    (out_dir / "karaoke.json").write_text(json.dumps({"lyrics": text, "syllables": aligned["syllables"], "notes": karaoke_notes}, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"  karaoke: source={source} syllables={len(aligned['syllables'])} score={aligned['score']} notes={stats['karaokeNotes']} withLyric={stats['notesWithLyric']} unassigned={stats['unassignedSyllables']} embedded={stats['lyricsEmbedded']} whisper={whisper_seconds:.1f}s align={align_seconds:.1f}s")
    for note in karaoke_notes[:12]:
        print(f"    {note['scoreStartBeat']:6.2f}-{note['scoreEndBeat']:6.2f} {note['name']:4s} {note['lyric'] or '-'}")


def _run_engine(vocals: Path, mix: Path | None, device: str, label: str, out_dir: Path, dump_frames: bool) -> dict[str, Any]:
    started = time.perf_counter()
    result = extract_vocal_melody(vocals, mix, device, debug=True)
    elapsed = time.perf_counter() - started
    summary = _describe(result, elapsed, label)
    out_dir.mkdir(parents=True, exist_ok=True)
    notes = result["notes"]
    for note in notes:
        note["name"] = midi_to_name(note["midi"])
    meter = int(result["timeSignature"].split("/")[0])
    midi_path = out_dir / "vocal.mid"
    _write_filtered_midi(notes, midi_path, result["tempoBpm"], meter, result["key"])
    _create_musicxml(midi_path, out_dir / "vocal.musicxml")
    if dump_frames:
        _dump_frames(result, out_dir / "frames.csv")
    serialisable = {key: value for key, value in result.items() if key != "_debug"}
    (out_dir / "result.json").write_text(json.dumps(serialisable, ensure_ascii=False, indent=2), encoding="utf-8")
    if KARAOKE["enabled"]:
        _run_karaoke(vocals, result, notes, device, out_dir, summary)
    return summary


def _evaluate_job(job_dir: Path, device: str, out_dir: Path, dump_frames: bool) -> dict[str, Any]:
    vocals = job_dir / "vocals.wav"
    mix = job_dir / "input.wav"
    metadata = job_dir / "job.json"
    source_type = "mix"
    if metadata.exists():
        source_type = json.loads(metadata.read_text(encoding="utf-8")).get("sourceType", "mix")
    return _run_engine(vocals, mix if source_type == "mix" and mix.exists() else None, device, job_dir.name, out_dir, dump_frames)


def _evaluate_audio(audio: Path, source_type: str, device: str, out_dir: Path, dump_frames: bool) -> dict[str, Any]:
    out_dir.mkdir(parents=True, exist_ok=True)
    normalized = out_dir / "input.wav"
    vocals = out_dir / "vocals.wav"
    started = time.perf_counter()
    separation_info: dict[str, Any] = {"backend": "none", "model": None, "seconds": 0.0}
    _convert_to_wav(audio, normalized)
    if source_type == "vocal":
        shutil.copy2(normalized, vocals)
        separation_seconds = 0.0
    else:
        separated, _, separation_info = _separate(normalized, out_dir / "separated", device, SEPARATOR["backend"])
        shutil.copy2(separated, vocals)
        separation_seconds = time.perf_counter() - started
    summary = _run_engine(vocals, None if source_type == "vocal" else normalized, device, audio.name, out_dir, dump_frames)
    summary["separationSeconds"] = round(separation_seconds, 1)
    summary["separator"] = separation_info.get("backend")
    if separation_info.get("fallbackReason"):
        summary["separatorFallback"] = separation_info["fallbackReason"]
    print(f"  {'separationSeconds':22s} {summary['separationSeconds']}")
    print(f"  {'separation':22s} {separation_info}")
    return summary


def main() -> int:
    parser = argparse.ArgumentParser(description="VocalScope melody engine evaluation")
    parser.add_argument("--job", type=Path, help="existing workspace job dir (vocals.wav + input.wav)")
    parser.add_argument("--audio", type=Path, help="audio file to run through ffmpeg → Demucs → engine")
    parser.add_argument("--folder", type=Path, help="evaluate every audio file under this folder (recursive)")
    parser.add_argument("--source-type", choices=("mix", "vocal"), default="mix")
    parser.add_argument("--out", type=Path, default=Path(tempfile.gettempdir()) / "vocalscope-eval")
    parser.add_argument("--dump-frames", action="store_true", help="write frames.csv with VAD features")
    parser.add_argument("--expect-vocal-start-min", type=float, default=None)
    parser.add_argument("--device", default=None)
    parser.add_argument("--karaoke", action="store_true", help="also run lyrics (Whisper or --lyrics-file) alignment and the karaoke melody")
    parser.add_argument("--lyrics-file", type=Path, default=None, help="UTF-8 lyrics text used instead of Whisper (implies --karaoke)")
    parser.add_argument("--separator", choices=("melband", "htdemucs"), default=None, help="separation backend for --audio/--folder (default: engine default, melband)")
    args = parser.parse_args()
    SEPARATOR["backend"] = args.separator
    KARAOKE["enabled"] = bool(args.karaoke or args.lyrics_file)
    if args.lyrics_file:
        KARAOKE["lyrics"] = args.lyrics_file.read_text(encoding="utf-8")

    import torch

    device = args.device or ("cuda" if torch.cuda.is_available() else "cpu")
    summaries: list[dict[str, Any]] = []
    if args.job:
        summaries.append(_evaluate_job(args.job, device, args.out / args.job.name, args.dump_frames))
    if args.audio:
        summaries.append(_evaluate_audio(args.audio, args.source_type, device, args.out / args.audio.stem, args.dump_frames))
    if args.folder:
        files = sorted(path for path in args.folder.rglob("*") if path.suffix.lower() in AUDIO_EXTENSIONS)
        for path in files:
            try:
                summaries.append(_evaluate_audio(path, args.source_type, device, args.out / path.stem, args.dump_frames))
            except Exception as error:  # keep the batch going
                print(f"\n=== {path.name}\n  FAILED: {error}")
                summaries.append({"label": path.name, "error": str(error)})
    if not summaries:
        parser.error("one of --job, --audio or --folder is required")

    print("\n=== summary")
    header = ["label", "separator", "separationSeconds", "vocalStart", "energyVocalStart", "notesBeforeEnergyStart", "noteCount", "f0AgreementRatio", "octaveMismatchRatio", "tuningCents", "key", "tempoBpm", "engineSeconds"]
    if KARAOKE["enabled"]:
        header += ["lyricsSource", "syllables", "alignmentScore", "karaokeNotes", "notesWithLyric", "unassignedSyllables"]
    print("  " + " | ".join(header))
    for item in summaries:
        print("  " + " | ".join(str(item.get(column, "")) for column in header))
    (args.out / "summary.json").write_text(json.dumps(summaries, ensure_ascii=False, indent=2), encoding="utf-8")

    failed = False
    if args.expect_vocal_start_min is not None:
        for item in summaries:
            if "vocalStart" in item and item["vocalStart"] < args.expect_vocal_start_min:
                print(f"  ASSERTION FAILED: {item['label']} vocalStart {item['vocalStart']} < {args.expect_vocal_start_min}")
                failed = True
    return 1 if failed else 0


if __name__ == "__main__":
    sys.exit(main())
