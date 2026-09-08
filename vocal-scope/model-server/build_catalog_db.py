"""Build the MICON catalog database (SQLite) from the enrichment catalog + VocalScope batch results.

Tables follow docs/micon_schema.sql (song side): songs, song_analysis, song_notes, song_artifacts, song_sections.
SQLite is used so the app team can query immediately; the same column names load into PostgreSQL later
(JSONB columns are stored as JSON text here).

Usage:
  python build_catalog_db.py --catalog <enriched_..._v6.jsonl> --out <micon_catalog.sqlite> [--workspace <dir>] --audio-root <audio 폴더>
Re-running is idempotent (INSERT OR REPLACE by song_id).
"""
from __future__ import annotations

import argparse
import json
import os
import re
import sys
import sqlite3
import time
from pathlib import Path
from typing import Any

ROOT = Path(__file__).resolve().parent
sys.path.insert(0, str(ROOT))
from catalog_paths import build_audio_index, job_id_for_track, resolve_audio_path  # noqa: E402
NOTE_INDEX = {"C": 0, "C♯": 1, "D": 2, "D♯": 3, "E": 4, "F": 5, "F♯": 6, "G": 7, "G♯": 8, "A": 9, "A♯": 10, "B": 11}

DDL = """
CREATE TABLE IF NOT EXISTS songs (
  song_id TEXT PRIMARY KEY, tj_number TEXT UNIQUE, track_id TEXT UNIQUE NOT NULL,
  title TEXT NOT NULL, title_normalized TEXT, artist_name TEXT NOT NULL, artists TEXT, credit_type TEXT,
  album_name TEXT, release_date TEXT, release_date_raw TEXT, duration_ms INTEGER, display_image_url TEXT, image_type TEXT,
  primary_language TEXT, languages TEXT, primary_genre TEXT, genres TEXT, market_context_tags TEXT, chart_entries TEXT,
  external_ids TEXT, lyrics_plain TEXT, lyrics_synced TEXT, lyrics_source TEXT, lyrics_license_status TEXT,
  lyrics_distribution_allowed INTEGER DEFAULT 0, audio_path TEXT, audio_sha256 TEXT, audio_bytes INTEGER, source_url TEXT,
  metadata_review_status TEXT NOT NULL DEFAULT 'unverified', schema_version INTEGER NOT NULL DEFAULT 1,
  created_at TEXT NOT NULL, updated_at TEXT NOT NULL
);
CREATE INDEX IF NOT EXISTS songs_artist_idx ON songs (artist_name);
CREATE INDEX IF NOT EXISTS songs_genre_idx ON songs (primary_genre);
CREATE INDEX IF NOT EXISTS songs_language_idx ON songs (primary_language);
CREATE TABLE IF NOT EXISTS song_analysis (
  song_id TEXT PRIMARY KEY REFERENCES songs(song_id) ON DELETE CASCADE,
  analysis_version TEXT NOT NULL, model TEXT, device TEXT, analyzed_at TEXT NOT NULL, status TEXT NOT NULL, error TEXT,
  key_tonic INTEGER, key_mode TEXT, key_name TEXT, key_confidence REAL, tuning_cents INTEGER,
  tempo_bpm INTEGER, tempo_raw_bpm REAL, tempo_candidates TEXT, time_signature TEXT, meter_confidence REAL,
  lowest_midi INTEGER, highest_midi INTEGER, core_low_midi INTEGER, core_high_midi INTEGER, range_ratios TEXT,
  vocal_start_sec REAL, vocal_end_sec REAL, voiced_seconds REAL, voiced_ratio REAL, note_count INTEGER,
  range_confidence INTEGER, f0_agreement REAL, octave_mismatch REAL, task_support TEXT,
  register TEXT, lyrics_alignment TEXT, karaoke_stats TEXT, separation TEXT, beat_grid TEXT, score_octave_shift INTEGER, timings TEXT
);
CREATE INDEX IF NOT EXISTS song_analysis_key_idx ON song_analysis (key_name);
CREATE INDEX IF NOT EXISTS song_analysis_range_idx ON song_analysis (lowest_midi, highest_midi);
CREATE INDEX IF NOT EXISTS song_analysis_tempo_idx ON song_analysis (tempo_bpm);
CREATE TABLE IF NOT EXISTS song_notes (
  song_id TEXT PRIMARY KEY REFERENCES songs(song_id) ON DELETE CASCADE,
  karaoke_version INTEGER NOT NULL DEFAULT 1, karaoke_notes TEXT NOT NULL, source_notes TEXT NOT NULL
);
CREATE TABLE IF NOT EXISTS song_artifacts (
  song_id TEXT NOT NULL REFERENCES songs(song_id) ON DELETE CASCADE, kind TEXT NOT NULL, path TEXT NOT NULL,
  media_type TEXT, bytes INTEGER, semitones INTEGER DEFAULT 0, created_at TEXT NOT NULL,
  PRIMARY KEY (song_id, kind, semitones)
);
CREATE TABLE IF NOT EXISTS song_sections (
  song_id TEXT NOT NULL REFERENCES songs(song_id) ON DELETE CASCADE, idx INTEGER NOT NULL, label TEXT NOT NULL,
  start_sec REAL NOT NULL, end_sec REAL NOT NULL, lowest_midi INTEGER, highest_midi INTEGER,
  review_status TEXT NOT NULL DEFAULT 'unverified', PRIMARY KEY (song_id, idx)
);
"""

MEDIA_TYPES = {
    ".wav": "audio/wav", ".opus": "audio/ogg", ".ogg": "audio/ogg", ".mid": "audio/midi",
    ".musicxml": "application/vnd.recordare.musicxml+xml", ".npz": "application/octet-stream",
}


def media_type_for(filename: str) -> str | None:
    return MEDIA_TYPES.get(Path(filename).suffix.lower())


def js(value: Any) -> str | None:
    return None if value is None else json.dumps(value, ensure_ascii=False)


def song_id_for(track_id: str) -> str | None:
    """`tj-<number>` — same rule as batch_analyze (catalog_paths.job_id_for_track); rank prefixes are dropped."""
    try:
        return job_id_for_track(track_id)
    except ValueError:
        return None


def note_to_midi(name: str) -> int | None:
    match = re.match(r"^([A-G]♯?)(-?\d+)$", name.strip())
    if not match:
        return None
    return NOTE_INDEX[match.group(1)] + (int(match.group(2)) + 1) * 12


def core_range_midi(text: str | None) -> tuple[int | None, int | None]:
    if not text or "–" not in text:
        return None, None
    low, high = text.split("–", 1)
    return note_to_midi(low), note_to_midi(high)


def task_support(result: dict[str, Any]) -> dict[str, str]:
    """Initial rule-based support status (장르·메타데이터 기준서 §11). Thresholds mirror the web quality warning."""
    def num(key: str, default: float) -> float:
        value = result.get(key)
        return default if value is None else float(value)  # 0.0 is a valid value — never use `or`

    agreement = num("f0AgreementRatio", 0.0)
    octave = num("octaveMismatchRatio", 1.0)
    voiced = num("voicedSeconds", 0.0)
    pitch = "pass" if agreement >= 0.6 and octave <= 0.1 and voiced >= 30 else "review"
    register = result.get("register") or {}
    timbre = "review" if register and not register.get("error") else "fail"
    rhythm = "pass" if num("meterConfidence", 0.0) >= 0.6 else "review"
    return {"pitch_range": pitch, "timbre": timbre, "rhythm": rhythm, "vocal_identity": "not_analyzed"}


CREDIT_TYPES = {"solo", "featuring", "collaboration", "group"}


def normalize_credit_type(value: Any) -> str | None:
    """The enrichment file stores a *source label* (e.g. 'musicbrainz_work_relations') here; only the team enum is kept."""
    return value if value in CREDIT_TYPES else None


def normalize_release_date(value: Any) -> str | None:
    """'2008' → '2008-01-01', '2002-01' → '2002-01-01'; anything else that is not YYYY-MM-DD → None."""
    if not value:
        return None
    text = str(value)
    if re.fullmatch(r"\d{4}-\d{2}-\d{2}", text):
        return text
    if re.fullmatch(r"\d{4}-\d{2}", text):
        return text + "-01"
    if re.fullmatch(r"\d{4}", text):
        return text + "-01-01"
    return None


def load_catalog(conn: sqlite3.Connection, catalog: Path, audio_old: str | None, audio_new: str | None, audio_root: Path | None = None) -> int:
    now = time.strftime("%Y-%m-%dT%H:%M:%S")
    count = 0
    audio_index = build_audio_index(audio_root) if audio_root else None
    unresolved = 0
    with catalog.open(encoding="utf-8") as handle:
        for line in handle:
            r = json.loads(line)
            track_id = r["track_id"]
            song_id = song_id_for(track_id)
            if not song_id:
                continue
            lyrics = r.get("lyrics") or {}
            asset = r.get("source_asset") or {}
            resolved = resolve_audio_path(r, audio_root, audio_old=audio_old, audio_new=audio_new, index=audio_index)
            if resolved is not None:
                path = str(resolved)
            else:  # keep the recorded path (rewritten if asked) so the row is not empty; count it
                path = asset.get("local_path")
                if path and audio_old and audio_new:
                    path = path.replace(audio_old, audio_new, 1)
                unresolved += 1
            artists = r.get("artists") or []
            genres = r.get("normalized_genres") or []
            conn.execute(
                """INSERT OR REPLACE INTO songs (song_id, tj_number, track_id, title, title_normalized, artist_name, artists, credit_type,
                   album_name, release_date, release_date_raw, duration_ms, display_image_url, image_type, primary_language, languages, primary_genre, genres,
                   market_context_tags, chart_entries, external_ids, lyrics_plain, lyrics_synced, lyrics_source, lyrics_license_status,
                   lyrics_distribution_allowed, audio_path, audio_sha256, audio_bytes, source_url, metadata_review_status, schema_version,
                   created_at, updated_at)
                   VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
                (
                    song_id, (r.get("seed") or {}).get("tj_number"), track_id,
                    r.get("title") or (r.get("seed") or {}).get("target_title"),
                    (r.get("search_identity") or {}).get("normalized_title"),
                    (artists[0]["name"] if artists else (r.get("seed") or {}).get("artist")),
                    js(artists), normalize_credit_type(r.get("credit_type")),
                    r.get("album_name"), normalize_release_date(r.get("release_date")), r.get("release_date"), r.get("duration_ms"),
                    r.get("display_image_url") or r.get("artwork_url"), r.get("image_type"),
                    r.get("primary_language"), js(r.get("languages")), (genres[0] if genres else None), js(genres),
                    js(r.get("market_context_tags")), js((r.get("seed") or {}).get("chart_entries")), js(r.get("external_ids")),
                    lyrics.get("plain"), lyrics.get("synced"), lyrics.get("source"), lyrics.get("license_status"),
                    1 if lyrics.get("distribution_allowed") else 0,
                    path, asset.get("sha256"), asset.get("bytes"), asset.get("source_url"),
                    "unverified" if (r.get("resolution") or {}).get("status") != "resolved" else "auto_resolved", 1,
                    now, now,
                ),
            )
            count += 1
    if unresolved:
        print(f"warning: {unresolved} songs have no audio file on this PC (songs.audio_path kept as recorded)", flush=True)
    return count


def load_jobs(conn: sqlite3.Connection, workspace: Path) -> tuple[int, int]:
    loaded = skipped = 0
    now = time.strftime("%Y-%m-%dT%H:%M:%S")
    for job_path in sorted(workspace.glob("tj*/job.json")):
        job = json.loads(job_path.read_text(encoding="utf-8"))
        song_id = job["id"]
        if conn.execute("SELECT 1 FROM songs WHERE song_id=?", (song_id,)).fetchone() is None:
            skipped += 1
            continue
        result = job.get("result") or {}
        catalog = job.get("catalog") or {}
        key = result.get("key") or {}
        core_low, core_high = core_range_midi(result.get("coreRange"))
        analyzed_at = time.strftime("%Y-%m-%dT%H:%M:%S", time.localtime(job.get("createdAt") or time.time()))
        conn.execute(
            """INSERT OR REPLACE INTO song_analysis (song_id, analysis_version, model, device, analyzed_at, status, error,
               key_tonic, key_mode, key_name, key_confidence, tuning_cents, tempo_bpm, tempo_raw_bpm, tempo_candidates, time_signature, meter_confidence,
               lowest_midi, highest_midi, core_low_midi, core_high_midi, range_ratios, vocal_start_sec, vocal_end_sec, voiced_seconds, voiced_ratio, note_count,
               range_confidence, f0_agreement, octave_mismatch, task_support, register, lyrics_alignment, karaoke_stats, separation, beat_grid, score_octave_shift, timings)
               VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)""",
            (
                song_id, catalog.get("batchRun") or "manual", result.get("model"), result.get("device"), analyzed_at, job.get("status"), job.get("error"),
                key.get("tonic"), key.get("mode"), key.get("name"), key.get("confidence"), result.get("tuningCents"),
                result.get("tempoBpm"), result.get("tempoRawBpm"), js(result.get("tempoCandidates")), result.get("timeSignature"), result.get("meterConfidence"),
                result.get("lowestMidi"), result.get("highestMidi"), core_low, core_high, js(result.get("ratios")),
                result.get("vocalStart"), result.get("vocalEnd"), result.get("voicedSeconds"), result.get("voicedRatio"), result.get("noteCount"),
                result.get("confidence"), result.get("f0AgreementRatio"), result.get("octaveMismatchRatio"),
                js(task_support(result)) if result else js({"pitch_range": "fail", "timbre": "fail", "rhythm": "fail", "vocal_identity": "not_analyzed"}),
                js(result.get("register")), js({k: v for k, v in (result.get("lyrics") or {}).items() if k != "text"}),
                js(result.get("karaoke")), js(result.get("separation")), js(result.get("beatGrid")),
                (result.get("scoreOctave") or {}).get("shift"), js(result.get("timings")),
            ),
        )
        if result.get("karaokeNotes") is not None:
            conn.execute(
                "INSERT OR REPLACE INTO song_notes (song_id, karaoke_version, karaoke_notes, source_notes) VALUES (?,?,?,?)",
                (song_id, result.get("karaokeVersion") or 1, js(result.get("karaokeNotes")), js(result.get("notes"))),
            )
        conn.execute("DELETE FROM song_artifacts WHERE song_id=?", (song_id,))
        for kind, filename in (result.get("artifacts") or {}).items():
            file_path = job_path.parent / filename
            conn.execute(
                "INSERT OR REPLACE INTO song_artifacts (song_id, kind, path, media_type, bytes, semitones, created_at) VALUES (?,?,?,?,?,?,?)",
                (song_id, kind, str(file_path), media_type_for(filename), file_path.stat().st_size if file_path.exists() else None, 0, now),
            )
        for shifted in list(job_path.parent.glob("instrumental_key*.wav")) + list(job_path.parent.glob("instrumental_key*.opus")):
            match = re.search(r"instrumental_key([+-]\d+)\.(wav|opus)", shifted.name)
            if match:
                conn.execute(
                    "INSERT OR REPLACE INTO song_artifacts (song_id, kind, path, media_type, bytes, semitones, created_at) VALUES (?,?,?,?,?,?,?)",
                    (song_id, "instrumental-key", str(shifted), media_type_for(shifted.name), shifted.stat().st_size, int(match.group(1)), now),
                )
        loaded += 1
    return loaded, skipped


MYSQL_TABLES = ("songs", "song_analysis", "song_notes", "song_artifacts", "song_sections")


def mysql_literal(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, (int, float)):
        return repr(value)
    text = str(value).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n").replace("\r", "\\r").replace("\0", "")
    return f"'{text}'"


def write_mysql_dump(conn: sqlite3.Connection, path: Path, batch_rows: int = 50) -> int:
    """Emit INSERT statements for the song-side tables (schema: docs/micon_schema.sql).
    Load with:  mysql -u <user> -p micon < micon_catalog.mysql.sql"""
    total = 0
    with path.open("w", encoding="utf-8", newline="\n") as out:
        out.write("-- generated by build_catalog_db.py; schema = docs/micon_schema.sql (MySQL 8)\n")
        out.write("SET NAMES utf8mb4;\nSET FOREIGN_KEY_CHECKS = 0;\nSTART TRANSACTION;\n")
        for table in MYSQL_TABLES:
            columns = [row[1] for row in conn.execute(f"PRAGMA table_info({table})")]
            rows = conn.execute(f"SELECT {', '.join(columns)} FROM {table}").fetchall()
            if not rows:
                continue
            col_list = ", ".join(f"`{c}`" for c in columns)
            updates = ", ".join(f"`{c}`=VALUES(`{c}`)" for c in columns[1:])
            for start in range(0, len(rows), batch_rows):
                chunk = rows[start:start + batch_rows]
                values = ",\n".join("(" + ", ".join(mysql_literal(v) for v in row) + ")" for row in chunk)
                out.write(f"INSERT INTO `{table}` ({col_list}) VALUES\n{values}\nON DUPLICATE KEY UPDATE {updates};\n")
            total += len(rows)
        out.write("COMMIT;\nSET FOREIGN_KEY_CHECKS = 1;\n")
    return total


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path, help="SQLite file (개발용)")
    parser.add_argument("--mysql-dump", type=Path, default=None, help="MySQL INSERT 덤프(.sql)도 함께 생성")
    parser.add_argument("--workspace", type=Path, default=ROOT / "workspace")
    parser.add_argument("--audio-root", type=Path, default=os.environ.get("VOCALSCOPE_AUDIO_ROOT"),
                        help="folder with <artist>/<track_id>.mp3 on THIS PC (or env VOCALSCOPE_AUDIO_ROOT); songs.audio_path is resolved here")
    parser.add_argument("--audio-old", default=None, help="(optional) path prefix recorded in the catalog's source_asset.local_path")
    parser.add_argument("--audio-new", default=None, help="(optional) what --audio-old should become on this PC")
    args = parser.parse_args()

    args.out.parent.mkdir(parents=True, exist_ok=True)
    conn = sqlite3.connect(args.out)
    conn.executescript(DDL)
    if args.audio_root and not Path(args.audio_root).is_dir():
        parser.error(f"--audio-root not found: {args.audio_root}")
    songs = load_catalog(conn, args.catalog, args.audio_old, args.audio_new, Path(args.audio_root) if args.audio_root else None)
    loaded, skipped = load_jobs(conn, args.workspace)
    conn.commit()
    analysed = conn.execute("SELECT COUNT(*) FROM song_analysis WHERE status='complete'").fetchone()[0]
    print(f"songs={songs} analysis_rows={loaded} (complete={analysed}) jobs_without_catalog={skipped} -> {args.out}")
    if args.mysql_dump:
        rows = write_mysql_dump(conn, args.mysql_dump)
        print(f"mysql dump: {rows} rows -> {args.mysql_dump}")
    conn.close()
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
