"""Offline batch analysis of catalog songs (no web server needed).

Runs the same `engine.run_pipeline` the API uses, but feeds the catalog lyrics (metadata_enrichment v6)
so the Whisper transcription stage is skipped — only the wav2vec2 forced alignment runs.  Each song
becomes a normal workspace job (`workspace/tj-<number>/job.json`) that the API server picks up on
restart, plus a `catalog` block linking it to the TJ track.

Usage (from model-server/, engine must NOT be running — GPU is shared):
  $env:PYTHONIOENCODING='utf-8'
  ..\.venv-model\Scripts\python.exe batch_analyze.py --songs <list.json> --catalog <v6.jsonl> [--audio-root <audio>] [--limit N] [--force]

`--songs` is a JSON list of {track_id, title, artist, genre, path} (build it with make_song_list.py).  When
`path` is missing or does not exist on this PC, the file is looked up under `--audio-root` (see
catalog_paths.py), so a list made on another machine still works.  Progress goes to stdout (one line per
song) and to `workspace/_batch/<run-name>.jsonl`; redirect stdout to a file on cp949 consoles because the
Mel-Band package prints emoji.
"""
from __future__ import annotations

import argparse
import json
import shutil
import sys
import time
import traceback
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
WORKSPACE = ROOT / "workspace"

from catalog_paths import build_audio_index, job_id_for_track, resolve_audio_path  # noqa: E402


def job_id_for(track_id: str) -> str:
    """`tj-<number>` — rank prefixes in file names (TJ073-54825) are dropped so ids match songs.song_id."""
    return job_id_for_track(track_id)


def load_lyrics(catalog_path: Path, track_ids: set[str]) -> dict[str, dict[str, Any]]:
    found: dict[str, dict[str, Any]] = {}
    with catalog_path.open(encoding="utf-8") as handle:
        for line in handle:
            record = json.loads(line)
            if record.get("track_id") in track_ids:
                lyrics = record.get("lyrics") or {}
                found[record["track_id"]] = {
                    "plain": (lyrics.get("plain") or "").strip(),
                    "source": lyrics.get("source"),
                    "tjNumber": (record.get("seed") or {}).get("tj_number"),
                    "primaryLanguage": record.get("primary_language"),
                }
    return found


def save(job_dir: Path, job: dict[str, Any]) -> None:
    (job_dir / "job.json").write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")


def summarize(job: dict[str, Any], seconds: float) -> dict[str, Any]:
    result = job.get("result") or {}
    return {
        "id": job["id"],
        "trackId": job["catalog"]["trackId"],
        "status": job["status"],
        "seconds": round(seconds, 1),
        "key": (result.get("key") or {}).get("name"),
        "tempoBpm": result.get("tempoBpm"),
        "timeSignature": result.get("timeSignature"),
        "range": f"{result.get('lowest')}–{result.get('highest')}" if result else None,
        "coreRange": result.get("coreRange"),
        "noteCount": result.get("noteCount"),
        "karaokeNotes": (result.get("karaoke") or {}).get("karaokeNotes"),
        "alignmentScore": (result.get("lyrics") or {}).get("alignmentScore"),
        "unassignedSyllables": (result.get("karaoke") or {}).get("unassignedSyllables"),
        "f0Agreement": result.get("f0AgreementRatio"),
        "octaveMismatch": result.get("octaveMismatchRatio"),
        "separation": (result.get("separation") or {}).get("backend"),
        "separationSeconds": (result.get("separation") or {}).get("seconds"),
        "timings": result.get("timings"),
        "error": job.get("error"),
    }


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--songs", required=True, type=Path)
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--start", type=int, default=0)
    parser.add_argument("--force", action="store_true", help="re-analyze songs that already have a complete job")
    parser.add_argument("--run-name", default=None)
    parser.add_argument("--audio-root", type=Path, default=None, help="folder with <artist>/<track_id>.mp3; used when a song's 'path' is absent or missing on this PC")
    args = parser.parse_args()

    songs: list[dict[str, Any]] = json.loads(args.songs.read_text(encoding="utf-8"))
    songs = songs[args.start:]
    if args.limit:
        songs = songs[: args.limit]
    lyrics_by_track = load_lyrics(args.catalog, {song["track_id"] for song in songs})
    audio_index = build_audio_index(args.audio_root) if args.audio_root else None

    run_name = args.run_name or f"{args.songs.stem}-{time.strftime('%Y%m%d-%H%M%S')}"
    log_dir = WORKSPACE / "_batch"
    log_dir.mkdir(parents=True, exist_ok=True)
    log_path = log_dir / f"{run_name}.jsonl"

    from engine import run_pipeline  # heavy imports (torch) after argument parsing

    done = failed = skipped = 0
    batch_started = time.perf_counter()
    for index, song in enumerate(songs, 1):
        track_id = song["track_id"]
        job_id = job_id_for(track_id)
        job_dir = WORKSPACE / job_id
        existing = job_dir / "job.json"
        if existing.exists() and not args.force:
            try:
                if json.loads(existing.read_text(encoding="utf-8")).get("status") == "complete":
                    skipped += 1
                    print(f"[{index}/{len(songs)}] skip (complete) {job_id} {song['title']}", flush=True)
                    continue
            except Exception:
                pass
        source = resolve_audio_path(song, args.audio_root, index=audio_index)
        if source is None:
            print(f"[{index}/{len(songs)}] MISSING FILE {song.get('path') or track_id} (audio-root={args.audio_root})", flush=True)
            failed += 1
            continue
        if job_dir.exists():
            shutil.rmtree(job_dir, ignore_errors=True)
        job_dir.mkdir(parents=True, exist_ok=True)
        original = source  # 카탈로그 원본을 그대로 읽음 — 작업 폴더에 복사하지 않음 (저장 정책)

        catalog = lyrics_by_track.get(track_id, {})
        lyrics_text = catalog.get("plain") or None
        job: dict[str, Any] = {
            "id": job_id,
            "kind": "reference",
            "filename": source.name,
            "createdAt": time.time(),
            "sourceType": "mix",
            "separator": "melband",
            "hasLyrics": bool(lyrics_text),
            "status": "processing",
            "progress": 0,
            "message": "일괄 분석 시작",
            "result": None,
            "error": None,
            "catalog": {
                "trackId": track_id,
                "tjNumber": catalog.get("tjNumber"),
                "title": song.get("title"),
                "artist": song.get("artist"),
                "genre": song.get("genre"),
                "primaryLanguage": catalog.get("primaryLanguage"),
                "lyricsSource": catalog.get("source") if lyrics_text else None,
                "batchRun": run_name,
            },
        }
        save(job_dir, job)

        def update(progress: int, message: str) -> None:
            job["progress"] = progress
            job["message"] = message
            save(job_dir, job)

        started = time.perf_counter()
        try:
            result = run_pipeline(job_dir, original, update, skip_separation=False, lyrics_text=lyrics_text, separator="melband", compact=True)
            job.update(status="complete", progress=100, message="완료", result=result)
            done += 1
        except Exception as error:  # keep going — one bad song must not stop the batch
            job.update(status="failed", message="분석에 실패했습니다.", error=f"{type(error).__name__}: {error}")
            (job_dir / "traceback.txt").write_text(traceback.format_exc(), encoding="utf-8")
            failed += 1
        seconds = time.perf_counter() - started
        save(job_dir, job)
        row = summarize(job, seconds)
        with log_path.open("a", encoding="utf-8") as handle:
            handle.write(json.dumps(row, ensure_ascii=False) + "\n")
        status = "OK  " if job["status"] == "complete" else "FAIL"
        print(
            f"[{index}/{len(songs)}] {status} {seconds:6.1f}s {job_id:14s} {song.get('artist','')} - {song.get('title','')}"
            f" | key={row['key']} bpm={row['tempoBpm']} range={row['range']} notes={row['noteCount']} align={row['alignmentScore']}"
            + (f" | {row['error']}" if row["error"] else ""),
            flush=True,
        )

    total = time.perf_counter() - batch_started
    print(f"done={done} failed={failed} skipped={skipped} total={total/60:.1f} min log={log_path}", flush=True)
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    raise SystemExit(main())
