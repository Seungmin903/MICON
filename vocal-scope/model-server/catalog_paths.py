"""Locate catalog audio files on whichever PC the batch runs on.

The enrichment JSONL (`metadata_enrichment/results/*_v6_*.jsonl`) records each song's audio as
`source_asset.local_path`, an absolute path from the PC that downloaded it.  On another machine only the
`audio/` folder moves, so paths have to be re-derived.  Three strategies, tried in order:

1. `local_path` with a prefix rewrite (`--audio-old` -> `--audio-new`), when both are given.
2. `<audio_root>/<artist>/<track_id>.mp3` — the folder layout of `Desktop\\노래 모음\\audio`.
3. A one-time scan of `audio_root` indexed by TJ number.  Needed because ~100 files carry a chart-rank
   prefix in the file name (`TJ001-13588`) while the JSONL `track_id` has none (`TJ-13588`).

`tj_number()` is the join key used everywhere: batch job id `tj-<number>`, `songs.song_id`, `song_analysis`.
"""
from __future__ import annotations

import os
import re
from pathlib import Path
from typing import Any

AUDIO_EXTENSIONS = (".mp3", ".m4a", ".wav", ".flac", ".ogg", ".opus", ".webm")
_TJ_RE = re.compile(r"TJ\d*-(\d+)", re.IGNORECASE)


def tj_number(text: str | None) -> str | None:
    """'MC THE MAX__TJ-14260_official__...' -> '14260'; 'x__TJ001-13588_official' -> '13588'."""
    if not text:
        return None
    match = _TJ_RE.search(text)
    return match.group(1) if match else None


def job_id_for_track(track_id: str) -> str:
    """Workspace job id for a catalog track: always `tj-<number>` regardless of any rank prefix."""
    number = tj_number(track_id)
    if not number:
        raise ValueError(f"TJ code not found in track_id: {track_id}")
    return f"tj-{number}"


def build_audio_index(audio_root: Path) -> dict[str, Path]:
    """Scan `audio_root` once and map TJ number -> audio file (first match wins)."""
    index: dict[str, Path] = {}
    for dirpath, _dirs, files in os.walk(audio_root):
        for name in files:
            if not name.lower().endswith(AUDIO_EXTENSIONS):
                continue
            number = tj_number(name)
            if number and number not in index:
                index[number] = Path(dirpath) / name
    return index


def resolve_audio_path(
    record: dict[str, Any],
    audio_root: Path | None,
    *,
    audio_old: str | None = None,
    audio_new: str | None = None,
    index: dict[str, Path] | None = None,
) -> Path | None:
    """Return an existing audio file for a catalog record / song-list entry, or None.

    `record` may be an enrichment row (`track_id`, `source_asset.local_path`) or a song-list entry
    (`track_id`, `path`).
    """
    track_id = record.get("track_id") or ""
    candidates: list[Path] = []

    explicit = record.get("path") or (record.get("source_asset") or {}).get("local_path")
    if explicit:
        if audio_old and audio_new and str(explicit).startswith(audio_old):
            candidates.append(Path(str(explicit).replace(audio_old, audio_new, 1)))
        candidates.append(Path(explicit))

    if audio_root and track_id:
        artist = track_id.split("__", 1)[0]
        for ext in AUDIO_EXTENSIONS:
            candidates.append(audio_root / artist / f"{track_id}{ext}")

    for candidate in candidates:
        if candidate.is_file():
            return candidate

    if audio_root and index is not None:
        number = tj_number(track_id)
        if number and number in index:
            return index[number]
    return None
