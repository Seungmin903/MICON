"""Build the `--songs` JSON for batch_analyze.py from the enrichment catalog on *this* PC.

Reads the v6 enrichment JSONL, resolves each song's audio file under `--audio-root` (see catalog_paths.py),
applies optional filters, and writes a list of {track_id, title, artist, genre, path}.  Songs whose audio
cannot be found are listed on stderr and left out (or kept with `path: null` via --keep-missing).

Examples (from model-server/):
  # all 890 songs
  ..\\.venv-model\\Scripts\\python.exe make_song_list.py --catalog "D:\\노래 모음\\metadata_enrichment\\results\\enriched_all_final_languages_v6_lyrics_normalized.jsonl" --audio-root "D:\\노래 모음\\audio" --out batch\\all890.json

  # pilot-style subset: Korean, solo artist, lyrics present, genres interleaved, 50 songs
  ..\\.venv-model\\Scripts\\python.exe make_song_list.py --catalog <v6.jsonl> --audio-root <audio> --language kor --solo --with-lyrics --round-robin-genre --limit 50 --out batch\\pilot50.json
"""
from __future__ import annotations

import argparse
import json
import sys
from collections import defaultdict
from pathlib import Path
from typing import Any

sys.path.insert(0, str(Path(__file__).resolve().parent))
from catalog_paths import build_audio_index, resolve_audio_path, tj_number  # noqa: E402


def song_entry(record: dict[str, Any], path: Path | None) -> dict[str, Any]:
    artists = record.get("artists") or []
    seed = record.get("seed") or {}
    genres = record.get("normalized_genres") or []
    return {
        "track_id": record["track_id"],
        "title": record.get("title") or seed.get("target_title"),
        "artist": (artists[0].get("name") if artists else None) or seed.get("artist"),
        "genre": genres[0] if genres else None,
        "path": str(path) if path else None,
    }


def passes_filters(record: dict[str, Any], args: argparse.Namespace) -> bool:
    if args.language and record.get("primary_language") != args.language:
        return False
    if args.solo:
        artists = record.get("artists") or []
        if len(artists) > 1:
            return False
        credit = (record.get("credit_type") or "").lower()
        if credit in {"group", "duet", "featuring"}:
            return False
    if args.with_lyrics:
        lyrics = record.get("lyrics") or {}
        if not (lyrics.get("plain") or "").strip() or lyrics.get("instrumental"):
            return False
    if args.genre:
        genres = {g.lower() for g in (record.get("normalized_genres") or [])}
        if not genres & {g.lower() for g in args.genre}:
            return False
    return True


def round_robin(entries: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Interleave genres so a `--limit N` prefix stays genre-balanced."""
    buckets: dict[str, list[dict[str, Any]]] = defaultdict(list)
    for entry in entries:
        buckets[entry.get("genre") or "?"].append(entry)
    order = sorted(buckets, key=lambda g: -len(buckets[g]))
    ordered: list[dict[str, Any]] = []
    while any(buckets.values()):
        for genre in order:
            if buckets[genre]:
                ordered.append(buckets[genre].pop(0))
    return ordered


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("--catalog", required=True, type=Path, help="enrichment JSONL (v6)")
    parser.add_argument("--audio-root", required=True, type=Path, help="folder holding <artist>/<track>.mp3")
    parser.add_argument("--out", required=True, type=Path, help="song list JSON for batch_analyze.py --songs")
    parser.add_argument("--audio-old", default=None, help="path prefix recorded in the catalog (optional rewrite)")
    parser.add_argument("--audio-new", default=None, help="what --audio-old should become on this PC")
    parser.add_argument("--language", default=None, help="primary_language filter, e.g. kor")
    parser.add_argument("--solo", action="store_true", help="single primary artist only")
    parser.add_argument("--with-lyrics", action="store_true", help="require lyrics.plain (Whisper is then skipped)")
    parser.add_argument("--genre", action="append", default=None, help="normalized genre; repeatable")
    parser.add_argument("--round-robin-genre", action="store_true", help="interleave genres before --limit")
    parser.add_argument("--limit", type=int, default=0)
    parser.add_argument("--keep-missing", action="store_true", help="keep songs without audio (path=null)")
    args = parser.parse_args()

    if not args.audio_root.is_dir():
        print(f"audio root not found: {args.audio_root}", file=sys.stderr)
        return 2
    index = build_audio_index(args.audio_root)

    entries: list[dict[str, Any]] = []
    missing: list[str] = []
    seen: set[str] = set()
    total = 0
    with args.catalog.open(encoding="utf-8") as handle:
        for line in handle:
            if not line.strip():
                continue
            record = json.loads(line)
            total += 1
            if not tj_number(record.get("track_id")):
                continue
            if not passes_filters(record, args):
                continue
            key = tj_number(record["track_id"])
            if key in seen:  # same TJ number twice in the catalog -> keep the first
                continue
            seen.add(key)
            path = resolve_audio_path(record, args.audio_root, audio_old=args.audio_old, audio_new=args.audio_new, index=index)
            if path is None:
                missing.append(record["track_id"])
                if not args.keep_missing:
                    continue
            entries.append(song_entry(record, path))

    if args.round_robin_genre:
        entries = round_robin(entries)
    if args.limit:
        entries = entries[: args.limit]

    args.out.parent.mkdir(parents=True, exist_ok=True)
    args.out.write_text(json.dumps(entries, ensure_ascii=False, indent=1), encoding="utf-8")

    for track_id in missing:
        print(f"MISSING AUDIO {track_id}", file=sys.stderr)
    print(
        f"catalog={total} selected={len(entries)} missing_audio={len(missing)} "
        f"index={len(index)} files -> {args.out}",
        flush=True,
    )
    return 0 if not missing or args.keep_missing else 1


if __name__ == "__main__":
    raise SystemExit(main())
