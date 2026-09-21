#!/usr/bin/env python3
"""Transactionally register or roll back verified INSTRUMENTAL_KEY manifest rows."""

from __future__ import annotations

import argparse
import json
import os
import sys
from pathlib import Path


def connect():
    try:
        import pymysql
    except ImportError as error:
        raise SystemExit("install requirements.txt before database registration") from error
    required = ("DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASSWORD")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit("missing environment variables: " + ", ".join(missing))
    return pymysql.connect(host=os.environ["DB_HOST"], port=int(os.environ["DB_PORT"]), database=os.environ["DB_NAME"],
                           user=os.environ["DB_USER"], password=os.environ["DB_PASSWORD"], charset="utf8mb4", autocommit=False)


def variants(path: Path) -> list[dict]:
    rows = json.loads(path.read_text(encoding="utf-8"))["variants"]
    if len(rows) != 5388:
        raise SystemExit(f"expected 5,388 variants, got {len(rows)}")
    if len({(row["songId"], int(row["semitones"])) for row in rows}) != len(rows):
        raise SystemExit("manifest contains duplicate song/key pairs")
    return rows


def register(connection, rows: list[dict], apply: bool) -> dict:
    inserted = exact = 0
    with connection.cursor() as cursor:
        for row in rows:
            cursor.execute("""SELECT file_path,mime_type,file_bytes,sha256,generation_status FROM song_media
                              WHERE song_id=%s AND kind='INSTRUMENTAL_KEY' AND semitones=%s FOR UPDATE""",
                           (row["songId"], row["semitones"]))
            existing = cursor.fetchone()
            expected = (row["objectKey"], row["mimeType"], row["bytes"], row["sha256"], "READY")
            if existing:
                if tuple(existing) != expected:
                    raise RuntimeError(f"conflicting existing row for {row['songId']} key {row['semitones']:+d}")
                exact += 1
                continue
            cursor.execute("""INSERT INTO song_media(song_id,kind,semitones,file_path,mime_type,file_bytes,sha256,generation_status)
                              VALUES(%s,'INSTRUMENTAL_KEY',%s,%s,%s,%s,%s,'READY')""",
                           (row["songId"], row["semitones"], row["objectKey"], row["mimeType"], row["bytes"], row["sha256"]))
            inserted += 1
        song_ids = sorted({row["songId"] for row in rows})
        placeholders = ",".join(["%s"] * len(song_ids))
        cursor.execute(f"""SELECT song_id,COUNT(DISTINCT semitones),MIN(semitones),MAX(semitones)
                           FROM song_media WHERE song_id IN ({placeholders})
                             AND generation_status='READY' AND kind IN ('INSTRUMENTAL','INSTRUMENTAL_KEY')
                             AND semitones BETWEEN -3 AND 3 GROUP BY song_id""", song_ids)
        coverage = cursor.fetchall()
        invalid = [row for row in coverage if row[1:] != (7, -3, 3)]
        if len(coverage) != len(song_ids) or invalid:
            raise RuntimeError(f"7-key coverage failed: songs={len(coverage)}/{len(song_ids)} invalid={invalid[:10]}")
    if apply:
        connection.commit()
    else:
        connection.rollback()
    return {"applied": apply, "inserted": inserted, "alreadyExact": exact, "songsWithSevenKeys": len(coverage)}


def rollback(connection, rows: list[dict], apply: bool) -> dict:
    deleted = 0
    with connection.cursor() as cursor:
        for row in rows:
            cursor.execute("""DELETE FROM song_media WHERE song_id=%s AND kind='INSTRUMENTAL_KEY' AND semitones=%s
                              AND file_path=%s AND sha256=%s""", (row["songId"], row["semitones"], row["objectKey"], row["sha256"]))
            deleted += cursor.rowcount
    if deleted != len(rows):
        connection.rollback()
        raise RuntimeError(f"rollback guard failed: exact rows={deleted}/{len(rows)}")
    if apply:
        connection.commit()
    else:
        connection.rollback()
    return {"applied": apply, "deleted": deleted}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("register", "rollback")); parser.add_argument("--manifest", required=True)
    parser.add_argument("--apply", action="store_true", help="commit; omission is a rollback-only rehearsal")
    args = parser.parse_args()
    rows = variants(Path(args.manifest))
    connection = connect()
    try:
        result = register(connection, rows, args.apply) if args.command == "register" else rollback(connection, rows, args.apply)
        print(json.dumps(result, ensure_ascii=False))
    except Exception as error:
        connection.rollback()
        print(str(error), file=sys.stderr)
        raise
    finally:
        connection.close()


if __name__ == "__main__":
    main()
