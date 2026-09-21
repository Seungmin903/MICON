"""DB export(장르 + MAIN 가수 ID)를 카탈로그 번들에 반영한다.

이름으로 가수를 추정하지 않는다. export 에 없는 곡이 있으면 중단한다.
임베딩·음역 값은 건드리지 않고, 장르 메타데이터와 아티스트 스냅샷만 다시 쓴다.

사용:
  python apply_db_metadata.py --export song-metadata-export.jsonl \
      --bundle <현재 번들 디렉터리> --out <새 번들 디렉터리> --revision 5
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import shutil
import sys
from datetime import datetime, timezone
from pathlib import Path

SERVER_DIR = Path(os.environ.get("MICON_RECOMMEND_SERVER") or Path(__file__).resolve().parents[1])
sys.path.insert(0, str(SERVER_DIR))

from timbre_recommender.catalog import CatalogIndex  # noqa: E402
from timbre_recommender.pitch_range import RangeCatalog, canonical_json_sha256  # noqa: E402
from artist_catalog import ArtistCatalog  # noqa: E402


def read_export(path: Path) -> dict[str, dict]:
    rows: dict[str, dict] = {}
    for line_number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), start=1):
        line = line.strip()
        if not line:
            continue
        try:
            row = json.loads(line)
        except json.JSONDecodeError as error:
            raise SystemExit(f"{path}:{line_number} JSON 파싱 실패: {error}")
        track_id = row.get("trackId")
        if not isinstance(track_id, str) or not track_id:
            raise SystemExit(f"{path}:{line_number} trackId 가 없습니다")
        if track_id in rows:
            raise SystemExit(f"{path}:{line_number} trackId 중복: {track_id}")
        rows[track_id] = row
    if not rows:
        raise SystemExit(f"{path}: 행이 없습니다")
    return rows


def genre_names(row: dict) -> list[str]:
    raw = row.get("genres") or []
    if isinstance(raw, str):
        raw = json.loads(raw)
    names, seen = [], set()
    for item in sorted(raw, key=lambda g: (not g.get("isPrimary"), str(g.get("name") or ""))):
        name = item.get("name") if isinstance(item, dict) else item
        if isinstance(name, str) and name.strip() and name not in seen:
            seen.add(name)
            names.append(name.strip())
    return names


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--export", required=True, type=Path)
    parser.add_argument("--bundle", required=True, type=Path)
    parser.add_argument("--out", required=True, type=Path)
    parser.add_argument("--revision", required=True, type=int, help="이 export 를 뜬 시점의 DB 활성 카탈로그 revision")
    args = parser.parse_args()

    rows = read_export(args.export)
    bundle, out = args.bundle.resolve(), args.out.resolve()
    index = json.loads((bundle / "index.json").read_text(encoding="utf-8"))
    tracks = index["tracks"]
    print(f"번들 {len(tracks)}곡 / export {len(rows)}행")

    missing = [t["track_id"] for t in tracks if t["track_id"] not in rows]
    if missing:
        for track_id in missing[:10]:
            print("  export 에 없는 곡:", track_id)
        raise SystemExit(f"중단: 번들 곡 {len(missing)}개가 export 에 없습니다. export 범위를 확인하세요.")

    bad_singer = [t["track_id"] for t in tracks if int(rows[t["track_id"]].get("mainSingerCount") or 0) != 1]
    if bad_singer:
        for track_id in bad_singer[:10]:
            print(f"  MAIN 가수가 1명이 아님: {track_id} ({rows[track_id].get('mainSingerCount')}명)")
        raise SystemExit(f"중단: {len(bad_singer)}곡의 MAIN 가수가 정확히 1명이 아닙니다. 정책 결정이 필요합니다.")

    out.mkdir(parents=True, exist_ok=False)
    for name in ("ecapa.npy", "singer_byol.npy"):
        shutil.copy2(bundle / name, out / name)

    # 1) 장르를 곡 메타데이터에 넣는다
    with_genre = 0
    for track in tracks:
        names = genre_names(rows[track["track_id"]])
        track["metadata"] = {**track["metadata"], "genres": names}
        with_genre += bool(names)
    index["tracks"] = tracks
    index["metadata_source"] = {
        "genres_and_artist_ids_from": "operational DB export (song_genre / song_singer role=MAIN)",
        "db_catalog_revision": args.revision,
        "exported_rows": len(rows),
        "applied_at": datetime.now(timezone.utc).isoformat(),
    }
    index_path = out / "index.json"
    index_path.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"장르 채워진 곡: {with_genre} / {len(tracks)}")

    # 2) 음역 프로필은 값 그대로, 인덱스 해시만 새로 묶는다
    ranges = json.loads((bundle / "range_profiles.json").read_text(encoding="utf-8"))
    ranges["source"] = {**ranges["source"], "indexFile": "index.json",
                        "indexCanonicalSha256": canonical_json_sha256(index_path)}
    (out / "range_profiles.json").write_text(
        json.dumps(ranges, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 3) 아티스트 스냅샷을 DB 값으로 다시 만든다
    records = []
    for track in tracks:
        row = rows[track["track_id"]]
        artist_id = row.get("artistId")
        if type(artist_id) is not int or artist_id <= 0:
            raise SystemExit(f"중단: {track['track_id']} 의 artistId 가 유효하지 않습니다: {artist_id!r}")
        records.append({"trackId": track["track_id"],
                        "songId": str(track["metadata"].get("song_id") or track["track_id"]),
                        "artistId": artist_id, "role": "MAIN"})
    snapshot = {
        "schemaVersion": 1,
        "catalogVersion": {t["metadata"]["batch_id"] for t in tracks}.pop(),
        "sourceRevision": args.revision,
        "indexSha256": hashlib.sha256(index_path.read_bytes()).hexdigest(),
        "tracks": records,
    }
    (out / "artist_ids.json").write_text(
        json.dumps(snapshot, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    provenance = json.loads((bundle / "handoff-provenance.json").read_text(encoding="utf-8")) \
        if (bundle / "handoff-provenance.json").is_file() else {}
    provenance.update({"genres_and_artist_ids_from_db_revision": args.revision,
                       "previous_bundle": str(bundle),
                       "metadata_applied_at": datetime.now(timezone.utc).isoformat()})
    (out / "handoff-provenance.json").write_text(
        json.dumps(provenance, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 4) 서버와 같은 로더로 검증
    catalog = CatalogIndex.load(out, verify_hashes=True)
    RangeCatalog.load(out, expected_track_ids={t.track_id for t in catalog.tracks}, verify_hashes=True)
    mapping = ArtistCatalog.load(catalog)
    changed = sum(1 for t in catalog.tracks
                  if mapping.by_track[t.track_id] != rows[t.track_id].get("artistId"))
    print(f"검증 통과: 곡 {len(catalog.tracks)} / 가수 {len(set(mapping.by_track.values()))}명 / "
          f"sourceRevision {mapping.source_revision} / 불일치 {changed}")
    print("출력:", out)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
