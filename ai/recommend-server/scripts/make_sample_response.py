"""docs/sample-response.json 과 docs/response-schema.json 을 만든다.

녹음 파일 없이 카탈로그 곡 하나의 임베딩·음역 프로파일을 "사용자"로 삼아 실제 추천 파이프라인(build_result)을 돌린다.
FE 는 sample-response.json 으로 DTO 를 만들고, 스키마가 바뀌면 이 스크립트를 다시 돌린다.

    .venv\\Scripts\\python.exe scripts\\make_sample_response.py [--query-tj TJ-14260] [--top-k 8] [--genre 발라드] [--artist 이하이]
"""

from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

import numpy as np  # noqa: E402

from recommendation_api import build_result  # noqa: E402
from schemas import Job  # noqa: E402
from timbre_recommender.catalog import CatalogIndex  # noqa: E402
from timbre_recommender.pitch_range import RangeCatalog  # noqa: E402

INDEX_DIR = ROOT / "recommender-assets" / "catalog-index"
DOCS = ROOT / "docs"


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--query-tj", default="TJ-14260", help="사용자 대역으로 쓸 카탈로그 곡의 tj_id")
    parser.add_argument("--top-k", type=int, default=8)
    parser.add_argument("--mode", default="timbre", choices=["timbre", "range"])
    parser.add_argument("--genre", action="append", default=None, help="온보딩 선호 장르 (반복 가능)")
    parser.add_argument("--artist", action="append", default=None, help="온보딩 선호 가수 (반복 가능)")
    args = parser.parse_args()
    genres = tuple(args.genre) if args.genre is not None else ("R&B", "발라드")
    artists = tuple(args.artist) if args.artist is not None else ("이하이", "한로로")

    catalog = CatalogIndex.load(INDEX_DIR, verify_hashes=True)
    ranges = RangeCatalog.load(INDEX_DIR, expected_track_ids={t.track_id for t in catalog.tracks}, verify_hashes=True)
    query_track = next((t for t in catalog.tracks if (t.metadata or {}).get("tj_id") == args.query_tj), catalog.tracks[0])

    result = build_result(
        catalog, ranges,
        query={key: np.asarray(catalog.embeddings(query_track, key)) for key in ("ecapa", "singer_byol")},
        pitch_profile=ranges.profiles[query_track.track_id], pitch_error=None, mode=args.mode, top_k=args.top_k,
        genres=genres, preferred_artists=artists, excluded_track_ids=(query_track.track_id,), seed=20260908,
    )
    job = Job(
        id="00000000-0000-4000-8000-000000000000", status="complete", progress=100, message="음색과 음역 추천이 완료되었습니다.",
        mode=args.mode, genres=list(genres), preferred_artists=list(artists), result=result,
        sample_note=f"합성 샘플: 카탈로그 곡 {query_track.track_id}({query_track.title} / {query_track.artist}) 의 임베딩·음역 프로파일을 사용자 입력으로 사용",
    )
    DOCS.mkdir(exist_ok=True)
    (DOCS / "sample-response.json").write_text(json.dumps(job.model_dump(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    (DOCS / "response-schema.json").write_text(json.dumps(Job.model_json_schema(), ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"query={query_track.track_id} shelves={len(result['shelves'])} -> docs/sample-response.json, docs/response-schema.json")
    for shelf in result["shelves"]:
        print(f"\n[{shelf['key']}] {shelf['title']} — {shelf['metric']}")
        for r in shelf["items"]:
            print(f"  {r['position']} {r['song_id']:<9} key={r['recommended_key']!s:>4} final={r['final_score']:.3f} {r['title']} / {r['artist']}  {r['metrics']}")


if __name__ == "__main__":
    main()
