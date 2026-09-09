"""Re-time the catalog lyrics with real forced alignment and rebuild karaokeNotes for every workspace job.

Why: `import_stem_pitch.py --assign-lyrics` only spreads LRC *line* times evenly over the syllables of each line
(pseudo timing), and songs without an LRC got no timing at all (88 of 211).  This script aligns the lyric text to the
separated vocal stem (``보컬분리/<folder>/vocals.wav``):

  * Korean-dominant text → `lyrics_engine.align_lyrics` (wav2vec2-ko CTC, Hangul-syllable vocabulary)
  * everything else (Japanese, English, mixed) → `lyrics_align_mms.align_lyrics_mms` (MMS_FA + uroman)

Then, per song:
  1. if the catalog has an LRC, the alignment is validated against the LRC line times (median |Δ| ≤ tolerance);
     on failure the old LRC pseudo-syllables are kept so nothing gets worse;
  2. if the catalog has no LRC, one is generated from the aligned line onsets (``result.lyrics.syncedGenerated``) so the
     app's karaoke strip works for those songs too (build_catalog_db.py copies it into ``songs.lyrics_synced``);
  3. `karaoke.build_karaoke_notes` is re-run on the source notes (now keeping every syllable) and job.json is rewritten
     (the first run keeps a ``job.json.orig`` backup).

Usage (from model-server, venv python):
  python align_catalog_lyrics.py --catalog <v6.jsonl> --stems "<보컬분리>" [--workspace workspace] [--device cuda]
        [--only tj-14352,tj-52994] [--limit N] [--force] [--tolerance 1.2] [--rebuild-only]

  --rebuild-only reuses the syllables stored by a previous run (``result.lyricsSyllables``) and only re-runs
  karaoke.build_karaoke_notes — use it after changing karaoke.py without paying for alignment again.
"""
from __future__ import annotations

import argparse
import json
import re
import shutil
import statistics
import sys
import time
import unicodedata
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from import_stem_pitch import LRC_LINE, lrc_syllables  # noqa: E402
from karaoke import build_karaoke_notes  # noqa: E402
from range_summary import make_time_to_beat, midi_to_name  # noqa: E402

HANGUL = re.compile(r"[가-힣]")
KANA_KANJI = re.compile(r"[\u3040-\u30ff\u31f0-\u31ff\u4e00-\u9fff]")
LRC_LEAD_SECONDS = 0.25          # generated LRC line time = first syllable onset − lead (matches the app's tolerance)
LRC_BREAK_GAP_SECONDS = 6.0      # insert an empty LRC marker (interlude) when lines are this far apart


def log(message: str) -> None:
    print(message, flush=True)


def load_catalog(path: Path) -> dict[str, dict[str, Any]]:
    rows: dict[str, dict[str, Any]] = {}
    with path.open(encoding="utf-8") as handle:
        for line in handle:
            if line.strip():
                row = json.loads(line)
                rows[row["track_id"]] = row
    return rows


def hangul_ratio(text: str) -> float:
    letters = [ch for ch in text if ch.isalnum() or HANGUL.match(ch)]
    if not letters:
        return 0.0
    return sum(1 for ch in letters if HANGUL.match(ch)) / len(letters)


def strip_transliteration(plain: str, language: str | None) -> tuple[str, int]:
    """Japanese catalog lyrics interleave a Korean reading (독음) line after each Japanese line. Those lines are not sung,
    so aligning them steals audio from the real lines. Drop a Hangul-only line that follows a kana/kanji line."""
    if language != "jpn":
        return plain, 0
    kept: list[str] = []
    dropped = 0
    previous_japanese = False
    for line in plain.splitlines():
        text = line.strip()
        if text and previous_japanese and HANGUL.search(text) and not KANA_KANJI.search(text):
            dropped += 1
            continue
        kept.append(line)
        if text:
            previous_japanese = bool(KANA_KANJI.search(text))
    return "\n".join(kept), dropped


def normalize(text: str) -> str:
    return re.sub(r"\s+", " ", unicodedata.normalize("NFKC", text)).strip().casefold()


def parse_lrc(synced: str) -> list[tuple[float, str]]:
    lines: list[tuple[float, str]] = []
    for raw in synced.splitlines():
        match = LRC_LINE.match(raw.strip())
        if not match:
            continue
        minutes, seconds, fraction, text = match.groups()
        frac = float(f"0.{fraction}") if fraction else 0.0
        lines.append((int(minutes) * 60 + int(seconds) + frac, text.strip()))
    return sorted(lines)


def line_onsets(syllables: list[dict[str, Any]]) -> dict[int, tuple[float, float]]:
    """line index → (first onset, last end)"""
    onsets: dict[int, tuple[float, float]] = {}
    for item in syllables:
        line = int(item.get("line", 0))
        start, end = float(item["start"]), float(item.get("end", item["start"]))
        if line not in onsets:
            onsets[line] = (start, end)
        else:
            onsets[line] = (min(onsets[line][0], start), max(onsets[line][1], end))
    return onsets


def validate_against_lrc(aligned_lines: list[str], syllables: list[dict[str, Any]], synced: str) -> dict[str, Any]:
    """Match LRC lines to aligned lines by text (in order) and compare onsets."""
    onsets = line_onsets(syllables)
    normalized_lines = [normalize(l) for l in aligned_lines]
    cursor = 0
    deltas: list[float] = []
    for time_sec, text in parse_lrc(synced):
        if not text:
            continue
        key = normalize(text)
        found = None
        for index in range(cursor, len(normalized_lines)):
            if normalized_lines[index] == key:
                found = index
                break
        if found is None:
            continue
        cursor = found + 1
        if found in onsets:
            deltas.append(onsets[found][0] - time_sec)
    if not deltas:
        return {"matched": 0, "medianAbs": None, "medianDelta": None, "spread": None}
    median_delta = statistics.median(deltas)
    return {
        "matched": len(deltas),
        "medianAbs": round(statistics.median(abs(d) for d in deltas), 3),
        "medianDelta": round(median_delta, 3),
        # median |Δ − medianΔ|: small → alignment tracks the LRC up to a constant shift (LRC offset, trust alignment)
        "spread": round(statistics.median(abs(d - median_delta) for d in deltas), 3),
        "p90Abs": round(sorted(abs(d) for d in deltas)[int(len(deltas) * 0.9) - 1 if len(deltas) >= 10 else -1], 3),
    }


def format_lrc_time(seconds: float) -> str:
    seconds = max(0.0, seconds)
    minutes = int(seconds // 60)
    rest = seconds - minutes * 60
    return f"[{minutes:02d}:{rest:05.2f}]"


LRC_OFFSET_REPLACE_SECONDS = 0.6   # LRC shifted by more than this vs. the aligned onsets → regenerate its times


def generate_lrc(aligned_lines: list[str], syllables: list[dict[str, Any]]) -> str:
    onsets = line_onsets(syllables)
    out: list[str] = []
    previous_end: float | None = None
    for index, text in enumerate(aligned_lines):
        if index not in onsets or not text.strip():
            continue
        start, end = onsets[index]
        if previous_end is not None and start - previous_end >= LRC_BREAK_GAP_SECONDS:
            out.append(format_lrc_time(previous_end + 0.5))
        out.append(f"{format_lrc_time(start - LRC_LEAD_SECONDS)} {text.strip()}")
        previous_end = end
    return "\n".join(out)


def rebuild_only(job_path: Path) -> dict[str, Any]:
    job = json.loads(job_path.read_text(encoding="utf-8"))
    result = job["result"]
    stored = result.get("lyricsSyllables")
    if not stored:
        return {"id": job["id"], "status": "no-stored-syllables"}
    syllables = [dict(zip(SYLLABLE_KEYS, item)) for item in stored]
    time_to_beat = make_time_to_beat(result["beatGrid"])
    karaoke_notes, stats = build_karaoke_notes(result["notes"], syllables, time_to_beat, int(result["tempoBpm"]), midi_to_name)
    result["karaokeNotes"] = karaoke_notes
    result["karaoke"] = stats
    result["karaokeVersion"] = 2
    job_path.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
    return {"id": job["id"], "status": "ok", "rebuilt": True, "notesWithLyric": stats["notesWithLyric"], "kept": stats.get("lyricSyllablesKept"), "unassigned": stats["unassignedSyllables"]}


SYLLABLE_KEYS = ("text", "line", "wordStart", "known", "explicitExtend", "start", "end", "score")


def compact_syllables(syllables: list[dict[str, Any]]) -> list[list[Any]]:
    """Store the syllables as positional rows (keys in SYLLABLE_KEYS) so job.json does not double in size."""
    return [[item.get(key) for key in SYLLABLE_KEYS] for item in syllables]


def process(job_path: Path, row: dict[str, Any], stems: Path, device: str, tolerance: float, force: bool) -> dict[str, Any]:
    job = json.loads(job_path.read_text(encoding="utf-8"))
    result = job["result"]
    lyrics_info = result.get("lyrics") or {}
    if lyrics_info.get("aligner") and not force:
        return {"id": job["id"], "status": "skip-done"}
    plain_full = (row.get("lyrics") or {}).get("plain") or lyrics_info.get("text") or ""
    synced = (row.get("lyrics") or {}).get("synced")
    plain, dropped_lines = strip_transliteration(plain_full, row.get("primary_language"))
    if not plain.strip():
        return {"id": job["id"], "status": "no-lyrics"}
    folder = (result.get("stemPitch") or {}).get("folder")
    vocals = stems / str(folder) / "vocals.wav"
    if not vocals.exists():
        return {"id": job["id"], "status": "no-vocals", "path": str(vocals)}

    started = time.perf_counter()
    ratio = hangul_ratio(plain)
    if ratio >= 0.5:
        from lyrics_engine import align_lyrics

        aligned = align_lyrics(vocals, plain, device)
        aligner = "wav2vec2-ko"
    else:
        from lyrics_align_mms import align_lyrics_mms

        aligned = align_lyrics_mms(vocals, plain, device)
        aligner = "mms-fa"
    syllables = aligned["syllables"]
    seconds = time.perf_counter() - started

    check: dict[str, Any] = {}
    used = "aligned"
    replace_lrc = False
    if synced:
        check = validate_against_lrc(aligned["lines"], syllables, synced)
        consistent = check.get("matched", 0) >= 3 and check.get("spread") is not None and check["spread"] <= tolerance
        if not consistent:
            fallback = lrc_syllables(synced, plain, float(result.get("vocalEnd") or 0.0))
            if fallback:
                syllables = fallback
                used = "lrc-pseudo"
        elif abs(check.get("medianDelta") or 0.0) > LRC_OFFSET_REPLACE_SECONDS:
            replace_lrc = True   # LRC times are shifted against this recording — the app would show lines at the wrong time
    if not syllables:
        return {"id": job["id"], "status": "no-syllables", "aligner": aligner}

    if not (job_path.parent / "job.json.orig").exists():
        shutil.copy2(job_path, job_path.parent / "job.json.orig")

    time_to_beat = make_time_to_beat(result["beatGrid"])
    karaoke_notes, stats = build_karaoke_notes(result["notes"], syllables, time_to_beat, int(result["tempoBpm"]), midi_to_name)
    result["karaokeNotes"] = karaoke_notes
    result["karaoke"] = stats
    result["karaokeVersion"] = 2
    result["lyricsSyllables"] = compact_syllables(syllables)
    generated = generate_lrc(aligned["lines"], syllables) if used == "aligned" and (not synced or replace_lrc) else None
    result["lyrics"] = {
        "source": ("catalog-lrc" if synced else "catalog") + ("+aligned" if used == "aligned" else ""),
        "text": plain_full,
        "alignedText": plain if dropped_lines else None,
        "transliterationLinesDropped": dropped_lines,
        "syllables": len(syllables),
        "alignmentScore": aligned["score"] if used == "aligned" else None,
        "unknownTokens": aligned.get("unknownTokenCount", 0),
        "explicitExtensions": aligned.get("explicitExtensions", 0),
        "error": None,
        "aligner": aligner,
        "alignedUsed": used == "aligned",
        "lrcCheck": check or None,
        "hangulRatio": round(ratio, 3),
        "syncedGenerated": generated,
        "syncedReplace": bool(generated and synced),
        # review: the LRC and the aligner disagree by a lot → the catalog LRC/lyrics probably belong to another version
        "quality": "review" if used != "aligned" and (check.get("medianAbs") or 0) > 5.0 else ("aligned" if used == "aligned" else "lrc-pseudo"),
        "alignedAt": time.strftime("%Y-%m-%dT%H:%M:%S"),
    }
    job_path.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
    return {
        "id": job["id"], "status": "ok", "title": job["catalog"].get("title"), "aligner": aligner, "used": used,
        "syllables": len(syllables), "score": aligned["score"], "notesWithLyric": stats["notesWithLyric"],
        "kept": stats.get("lyricSyllablesKept"), "unassigned": stats["unassignedSyllables"],
        "lrcCheck": check or None, "generatedLrc": bool(generated), "replacedLrc": bool(generated and synced), "droppedLines": dropped_lines, "seconds": round(seconds, 1),
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--stems", required=True, type=Path, help="보컬분리 root")
    parser.add_argument("--workspace", type=Path, default=ROOT / "workspace")
    parser.add_argument("--device", default="cuda")
    parser.add_argument("--only", default="", help="comma-separated job ids")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--force", action="store_true", help="re-align jobs that already carry an aligner tag")
    parser.add_argument("--tolerance", type=float, default=1.2, help="max median |aligned − LRC| seconds to accept alignment")
    parser.add_argument("--rebuild-only", action="store_true", help="reuse stored syllables; only rebuild karaokeNotes")
    args = parser.parse_args()

    import torch

    device = args.device if (args.device != "cuda" or torch.cuda.is_available()) else "cpu"
    rows = load_catalog(args.catalog)
    only = {item.strip() for item in args.only.split(",") if item.strip()}
    job_paths = sorted(args.workspace.glob("tj-*/job.json"))
    if only:
        job_paths = [p for p in job_paths if p.parent.name in only]
    if args.limit:
        job_paths = job_paths[: args.limit]
    log(f"jobs={len(job_paths)} device={device}")

    summary_dir = args.workspace / "_batch"
    summary_dir.mkdir(parents=True, exist_ok=True)
    summary_path = summary_dir / f"align-lyrics-{time.strftime('%Y-%m-%d')}.jsonl"
    counts: dict[str, int] = {}
    with summary_path.open("a", encoding="utf-8") as summary:
        for index, job_path in enumerate(job_paths, 1):
            job = json.loads(job_path.read_text(encoding="utf-8"))
            row = rows.get((job.get("catalog") or {}).get("trackId"))
            if row is None:
                line = {"id": job["id"], "status": "no-catalog-row"}
            else:
                try:
                    line = rebuild_only(job_path) if args.rebuild_only else process(job_path, row, args.stems, device, args.tolerance, args.force)
                except Exception as error:  # one bad song must not stop the batch
                    line = {"id": job["id"], "status": "error", "error": f"{type(error).__name__}: {str(error)[:300]}"}
            counts[line["status"]] = counts.get(line["status"], 0) + 1
            summary.write(json.dumps(line, ensure_ascii=False) + "\n")
            summary.flush()
            log(f"[{index}/{len(job_paths)}] {json.dumps(line, ensure_ascii=False)}")
    log(f"FINISHED {counts} → {summary_path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
