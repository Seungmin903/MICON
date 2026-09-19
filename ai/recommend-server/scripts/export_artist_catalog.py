"""Build a validated artist_ids.json from a read-only DB JSON export on stdin.

No database credentials or online lookup are needed by the running AI server.
"""
from __future__ import annotations

import argparse
import json
from pathlib import Path
import sys

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from artist_catalog import ARTIST_CATALOG_FILENAME, make_snapshot
from timbre_recommender.catalog import CatalogIndex


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--catalog", type=Path, required=True)
    parser.add_argument("--revision", type=int, required=True)
    args = parser.parse_args()
    catalog = CatalogIndex.load(args.catalog, verify_hashes=True)
    body = make_snapshot(catalog, json.load(sys.stdin), args.revision)
    target = catalog.root / ARTIST_CATALOG_FILENAME
    if target.exists():
        raise SystemExit("Snapshot already exists; use a new catalog directory or explicitly archive it first")
    with target.open("x", encoding="utf-8") as stream:
        stream.write(json.dumps(body, ensure_ascii=False, indent=2) + "\n")
    print(json.dumps({"tracks": len(body["tracks"]),
        "artists": len({x["artistId"] for x in body["tracks"]}),
        "catalogVersion": body["catalogVersion"], "sourceRevision": body["sourceRevision"]}))


if __name__ == "__main__":
    main()
