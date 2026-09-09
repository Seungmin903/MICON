"""Import the offline `stem-pitch` batch into engine-native job folders.

The stem-pitch run analysed pre-separated vocal stems with `pitch_engine.extract_vocal_melody` only, so each record
holds the catalog's app fields + `pitchAnalysis` (a strict subset of `engine.run_pipeline`'s result) + a
`pitchExtraction` provenance block.  This script fills in what the pipeline would have added (coreRange / ratios /
confidence, karaokeNotes, lyrics, artifacts ...) and writes `workspace/tj-<n>/{job.json, pitch_frames.npz,
instrumental.opus}`.  Downstream tools then work unchanged:

  * `app.py` serves `GET /jobs`, `/jobs/<id>/files/instrumental[?semitones=N]`, `/jobs/<id>/compare`
  * `build_catalog_db.py --workspace` loads `songs / song_analysis / song_notes / song_artifacts` for the Android app

Join key: `pitchExtraction.catalogRow` is a **1-based** row index into the v6 enrichment catalog
(`enriched_all_final_languages_v6_lyrics_normalized.jsonl`); that row's `track_id` gives `tj-<number>`
(`catalog_paths.job_id_for_track`), the same id the app, the DB and the engine use.  Title/artist are checked as a guard.

Usage:
  python import_stem_pitch.py --stem-pitch ../outputs/stem-pitch --catalog <v6.jsonl> --stems "<보컬분리 folder>"
        [--workspace workspace] [--run-name stem-pitch-YYYY-MM-DD] [--limit N] [--force] [--skip-opus]
        [--assign-lyrics] [--strict] [--jobs 4]
Re-running is idempotent: existing instrumental.opus files are kept, job.json is rewritten.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import sys
import time
import unicodedata
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from catalog_paths import job_id_for_track  # noqa: E402
from compact import encode_opus  # noqa: E402
from karaoke import build_karaoke_notes  # noqa: E402
from range_summary import _analyze_range, make_time_to_beat, midi_to_name  # noqa: E402

MIN_KARAOKE_NOTES = 10        # Android NoteNormalizer falls back to `notes` below this - which the SQLite path lacks
MIN_VOICED_SECONDS = 5.0
LRC_LINE = re.compile(r"^\[(\d{1,2}):(\d{2})(?:[.:](\d{1,3}))?\]\s*(.*)$")
MODEL_LABEL = "pre-separated stem + crepe-full + pyin"


# ----------------------------------------------------------------------------- helpers

def log(message: str) -> None:
    print(message, flush=True)


def normalize(text: str | None) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text or "")).strip().casefold()


def load_jsonl(path: Path) -> list[dict[str, Any]]:
    with path.open(encoding="utf-8") as handle:
        return [json.loads(line) for line in handle if line.strip()]


def track_dir_for(record: dict[str, Any], stem_pitch: Path) -> Path | None:
    """`pitchExtraction.frames` is an absolute path from the PC that ran the batch; only its folder name is trusted."""
    frames = str((record.get("pitchExtraction") or {}).get("frames") or "")
    parts = re.split(r"[\\/]+", frames)
    folder = parts[-2] if len(parts) >= 2 else ""
    candidate = stem_pitch / "tracks" / folder if folder else None
    return candidate if candidate and (candidate / "pitch_frames.npz").exists() else None


def is_cjk(char: str) -> bool:
    code = ord(char)
    return (
        0xAC00 <= code <= 0xD7A3 or 0x1100 <= code <= 0x11FF or 0x3130 <= code <= 0x318F   # Hangul
        or 0x3040 <= code <= 0x30FF or 0x4E00 <= code <= 0x9FFF or 0xFF66 <= code <= 0xFF9F  # kana / kanji
    )


def split_syllables(text: str) -> list[str]:
    """Korean/Japanese: one syllable per character; Latin: one per word. Punctuation is dropped."""
    tokens: list[str] = []
    for word in text.split():
        if any(is_cjk(ch) for ch in word):
            tokens.extend(ch for ch in word if not unicodedata.category(ch).startswith("P"))
        else:
            cleaned = "".join(ch for ch in word if not unicodedata.category(ch).startswith("P"))
            if cleaned:
                tokens.append(cleaned)
    return tokens


def lrc_syllables(synced: str | None, plain: str | None, vocal_end: float) -> list[dict[str, Any]] | None:
    """Line-timed LRC -> pseudo syllables (`start, end, text, score`) evenly spread inside each line's window.
    Returns None when the LRC is unusable or does not match the plain lyrics the app displays."""
    if not synced:
        return None
    lines: list[tuple[float, str]] = []
    for raw in synced.splitlines():
        match = LRC_LINE.match(raw.strip())
        if not match:
            continue
        minutes, seconds, fraction, text = match.groups()
        frac = float(f"0.{fraction}") if fraction else 0.0
        lines.append((int(minutes) * 60 + int(seconds) + frac, text.strip()))
    lines = [(t, text) for t, text in sorted(lines) if text]
    if len(lines) < 4:
        return None
    if plain:
        found = sum(1 for _, text in lines if text in plain)
        if found / len(lines) < 0.7:
            return None
    syllables: list[dict[str, Any]] = []
    for index, (start, text) in enumerate(lines):
        next_start = lines[index + 1][0] if index + 1 < len(lines) else vocal_end + 1.0
        tokens = split_syllables(text)
        if not tokens:
            continue
        window = max(0.8, min(next_start - start, 0.45 * len(tokens) + 1.0))
        step = window / len(tokens)
        for position, token in enumerate(tokens):
            onset = start + position * step
            syllables.append({"text": token, "start": round(onset, 3), "end": round(onset + step * 0.9, 3), "score": 0.5, "known": True})
    return syllables or None


# ----------------------------------------------------------------------------- conversion

def build_job(
    record: dict[str, Any], catalog_row: dict[str, Any], job_id: str, run_name: str, assign_lyrics: bool,
) -> dict[str, Any]:
    analysis = dict(record["pitchAnalysis"])
    extraction = record.get("pitchExtraction") or {}
    notes = analysis["notes"]
    for note in notes:
        note.setdefault("name", midi_to_name(int(note["midi"])))
    lyrics = record.get("lyrics") or {}
    plain = lyrics.get("plain") or ""

    result: dict[str, Any] = _analyze_range(notes)          # lowest/highest/coreRange/ratios/confidence/voicedDuration
    result.update(analysis)                                 # tempo, meter, key, beatGrid, register, timings, vocalStart ...

    syllables = lrc_syllables(lyrics.get("synced"), plain, float(analysis.get("vocalEnd") or 0.0)) if assign_lyrics else None
    time_to_beat = make_time_to_beat(analysis["beatGrid"])
    karaoke_notes, karaoke_stats = build_karaoke_notes(notes, syllables, time_to_beat, int(analysis["tempoBpm"]), midi_to_name)

    register = result.get("register") or {}
    for source_key, target in (("modalRangeMidi", "modalRange"), ("falsettoRangeMidi", "falsettoRange")):
        span = register.get(source_key)
        register[target] = f"{midi_to_name(span[0])}–{midi_to_name(span[1])}" if span else None

    result["scoreOctave"] = {"mode": "auto", "shift": 0}
    result["device"] = extraction.get("device")
    result["separation"] = {"backend": "pre-separated-stem", "model": None, "seconds": 0.0, "source": extraction.get("folder")}
    result["model"] = MODEL_LABEL
    result["lyrics"] = {
        "source": "catalog-lrc" if syllables else ("catalog" if plain else "none"),
        "text": plain,
        "syllables": len(syllables or []),
        "alignmentScore": None,
        "unknownTokens": 0,
        "explicitExtensions": 0,
        "error": None,
    }
    result["karaoke"] = karaoke_stats
    result["karaokeNotes"] = karaoke_notes
    result["karaokeVersion"] = 1
    result["artifacts"] = {"pitch-frames": "pitch_frames.npz"}
    result["stemPitch"] = {**extraction, "catalogTitle": record.get("title"), "catalogArtists": record.get("artists")}

    genres = record.get("normalized_genres") or catalog_row.get("normalized_genres") or []
    return {
        "id": job_id,
        "kind": "reference",
        "filename": f"{catalog_row['track_id']}.mp3",
        "createdAt": float(extraction.get("analyzedAt") or time.time()),
        "sourceType": "vocal",
        "separator": None,
        "hasLyrics": bool(plain),
        "status": "complete",
        "progress": 100,
        "message": "stem-pitch 배치 결과 가져옴",
        "result": result,
        "error": None,
        "catalog": {
            "trackId": catalog_row["track_id"],
            "tjNumber": (catalog_row.get("seed") or {}).get("tj_number"),
            "title": record.get("title"),
            "artist": (record.get("artists") or [{}])[0].get("name"),
            "genre": genres[0] if genres else None,
            "primaryLanguage": catalog_row.get("primary_language") or record.get("languages"),
            "lyricsSource": "catalog" if plain else None,
            "batchRun": run_name,
        },
    }


def review_reasons(job: dict[str, Any], has_instrumental: bool) -> list[str]:
    result = job["result"]
    reasons = []
    if len(result.get("karaokeNotes") or []) < MIN_KARAOKE_NOTES:
        reasons.append(f"karaokeNotes<{MIN_KARAOKE_NOTES}")
    if float(result.get("voicedSeconds") or 0.0) < MIN_VOICED_SECONDS:
        reasons.append(f"voicedSeconds<{MIN_VOICED_SECONDS:g}")
    if not has_instrumental:
        reasons.append("no-instrumental")
    return reasons


def save_job(job_dir: Path, job: dict[str, Any]) -> None:
    job_dir.mkdir(parents=True, exist_ok=True)
    (job_dir / "job.json").write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")


# ----------------------------------------------------------------------------- main

def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--stem-pitch", required=True, type=Path, help="folder with pitch_metadata.jsonl + tracks/")
    parser.add_argument("--catalog", required=True, type=Path, help="v6 enrichment JSONL (890 rows, has track_id)")
    parser.add_argument("--stems", type=Path, default=None, help="보컬분리 root: <folder>/no_vocals.wav becomes instrumental.opus")
    parser.add_argument("--workspace", type=Path, default=ROOT / "workspace")
    parser.add_argument("--run-name", default=time.strftime("stem-pitch-%Y-%m-%d"))
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--force", action="store_true", help="re-encode instrumental.opus even if present")
    parser.add_argument("--skip-opus", action="store_true", help="do not touch audio (jobs get status=review unless opus exists)")
    parser.add_argument("--assign-lyrics", action="store_true", help="attach LRC-timed syllables to karaokeNotes")
    parser.add_argument("--strict", action="store_true", help="skip records whose title/artist differ from the catalog row")
    parser.add_argument("--jobs", type=int, default=4, help="parallel Opus encoders")
    args = parser.parse_args()

    records = load_jsonl(args.stem_pitch / "pitch_metadata.jsonl")
    catalog = load_jsonl(args.catalog)
    if args.limit:
        records = records[: args.limit]
    log(f"records={len(records)} catalog_rows={len(catalog)} workspace={args.workspace}")

    prepared: list[tuple[dict[str, Any], Path, Path | None]] = []   # job, track_dir, stem wav
    skipped = 0
    for index, record in enumerate(records, 1):
        extraction = record.get("pitchExtraction") or {}
        row_number = int(extraction.get("catalogRow") or 0)
        if not 1 <= row_number <= len(catalog):
            log(f"[{index}] SKIP catalogRow out of range: {row_number} {record.get('title')}")
            skipped += 1
            continue
        row = catalog[row_number - 1]
        same = normalize(row.get("title")) == normalize(record.get("title")) and normalize(
            (row.get("artists") or [{}])[0].get("name")) == normalize((record.get("artists") or [{}])[0].get("name"))
        if not same:
            log(f"[{index}] WARN catalog row {row_number} mismatch: stem='{record.get('title')}' catalog='{row.get('title')}'")
            if args.strict:
                skipped += 1
                continue
        try:
            job_id = job_id_for_track(row["track_id"])
        except ValueError as error:
            log(f"[{index}] SKIP {error}")
            skipped += 1
            continue
        track_dir = track_dir_for(record, args.stem_pitch)
        if track_dir is None:
            log(f"[{index}] SKIP pitch_frames.npz not found for {record.get('title')}")
            skipped += 1
            continue
        stem_wav = (args.stems / str(extraction.get("folder")) / "no_vocals.wav") if args.stems else None
        job = build_job(record, row, job_id, args.run_name, args.assign_lyrics)
        prepared.append((job, track_dir, stem_wav))

    # --- audio: encode accompaniments in parallel (slowest step, ~50 MB WAV each)
    encode_failures: dict[str, str] = {}

    def encode(job: dict[str, Any], stem_wav: Path | None) -> None:
        target = args.workspace / job["id"] / "instrumental.opus"
        if args.skip_opus or stem_wav is None:
            return
        if target.exists() and not args.force:
            return
        if not stem_wav.exists():
            encode_failures[job["id"]] = f"missing {stem_wav}"
            return
        target.parent.mkdir(parents=True, exist_ok=True)
        try:
            encode_opus(stem_wav, target)
        except Exception as error:  # one bad file must not stop the import
            encode_failures[job["id"]] = str(error)[:300]

    started = time.perf_counter()
    if not args.skip_opus and args.stems:
        with ThreadPoolExecutor(max_workers=max(1, args.jobs)) as pool:
            list(pool.map(lambda item: encode(item[0], item[2]), prepared))
    log(f"opus stage done in {time.perf_counter() - started:.0f}s failures={len(encode_failures)}")

    # --- write jobs
    summary_dir = args.workspace / "_batch"
    summary_dir.mkdir(parents=True, exist_ok=True)
    counts = {"imported": 0, "complete": 0, "review": 0, "skipped": skipped}
    with (summary_dir / f"{args.run_name}.jsonl").open("w", encoding="utf-8") as summary:
        for index, (job, track_dir, _stem) in enumerate(prepared, 1):
            job_dir = args.workspace / job["id"]
            job_dir.mkdir(parents=True, exist_ok=True)
            shutil.copy2(track_dir / "pitch_frames.npz", job_dir / "pitch_frames.npz")
            has_instrumental = (job_dir / "instrumental.opus").exists()
            if has_instrumental:
                job["result"]["artifacts"]["instrumental"] = "instrumental.opus"
            reasons = review_reasons(job, has_instrumental)
            if job["id"] in encode_failures:
                reasons.append(f"opus: {encode_failures[job['id']]}")
            if reasons:
                job["status"] = "review"
                job["message"] = "검토 필요: " + ", ".join(reasons)
            save_job(job_dir, job)
            counts["imported"] += 1
            counts[job["status"]] += 1
            result = job["result"]
            line = {
                "id": job["id"], "status": job["status"], "title": job["catalog"]["title"], "artist": job["catalog"]["artist"],
                "range": f"{result['lowest']}~{result['highest']}", "coreRange": result["coreRange"], "notes": result["noteCount"],
                "karaokeNotes": len(result["karaokeNotes"]), "lyricNotes": result["karaoke"]["notesWithLyric"],
                "instrumental": has_instrumental, "reasons": reasons,
            }
            summary.write(json.dumps(line, ensure_ascii=False) + "\n")
            log(f"[{index}/{len(prepared)}] {job['status']:8s} {job['id']} {line['range']} notes={line['notes']} karaoke={line['karaokeNotes']} lyric={line['lyricNotes']} {job['catalog']['artist']} - {job['catalog']['title']}")
    log(f"FINISHED {counts}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
