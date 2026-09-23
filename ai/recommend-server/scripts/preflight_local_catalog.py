"""Validate the configured local recommendation catalog before starting Uvicorn."""

from __future__ import annotations

import json
from pathlib import Path
import sys


APP_ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(APP_ROOT))


def main() -> int:
    from artist_catalog import ArtistCatalog
    from recommendation_api import _configuration_status
    from timbre_recommender.catalog import CatalogIndex
    from timbre_recommender.pitch_range import RangeCatalog

    config, missing = _configuration_status()
    if missing:
        raise SystemExit(
            "Recommendation configuration is incomplete: " + ", ".join(missing)
        )

    catalog = CatalogIndex.load(config["index_dir"], verify_hashes=True)
    track_ids = {track.track_id for track in catalog.tracks}
    ranges = RangeCatalog.load(
        config["index_dir"], expected_track_ids=track_ids, verify_hashes=True
    )
    artists = ArtistCatalog.load(catalog)

    summary = {
        "ready": True,
        "catalogTracks": len(catalog.tracks),
        "rangeProfiles": len(ranges.profiles),
        "artistMappings": len(artists.by_track),
        "canonicalArtists": len(set(artists.by_track.values())),
        "catalogVersion": artists.catalog_version,
        "scoringVersion": catalog.metadata["scoring_version"],
        "rangeScoringVersion": ranges.metadata["scoringVersion"],
        "keyShiftRange": ranges.metadata["keyShiftRange"],
    }
    print(json.dumps(summary, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as error:
        raise SystemExit(
            f"Recommendation catalog preflight failed: {type(error).__name__}: {error}"
        ) from None
