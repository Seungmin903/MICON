from __future__ import annotations

import json
from pathlib import Path
import tempfile
import unittest

from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.embedding import OFFICIAL_SINGER_BYOL_REVISION, _git_revision
from timbre_recommender.pitch_range import RangeCatalog

import setup_recommender


class RecommenderSetupTestCase(unittest.TestCase):
    def test_bundled_catalog_and_vendored_revision_are_valid(self) -> None:
        catalog = CatalogIndex.load(setup_recommender.ASSET_INDEX, verify_hashes=True)
        self.assertGreater(len(catalog.tracks), 0)
        ranges = RangeCatalog.load(
            setup_recommender.ASSET_INDEX,
            expected_track_ids={track.track_id for track in catalog.tracks},
            verify_hashes=True,
        )
        self.assertEqual(len(ranges.profiles), len(catalog.tracks))
        self.assertEqual(
            _git_revision(setup_recommender.VENDORED_SINGER_REPO),
            OFFICIAL_SINGER_BYOL_REVISION,
        )

    def test_runtime_config_uses_checkout_local_paths(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            config_path = setup_recommender.write_runtime_config(
                index_dir=root / "catalog",
                model_root=root / "models",
                singer_repo=root / "vendor",
                workspace=root / "workspace",
            )
            config = json.loads(config_path.read_text(encoding="utf-8"))
            self.assertEqual(config["index_dir"], str((root / "catalog").resolve()))
            self.assertEqual(
                config["ecapa_checkpoint"],
                str((root / "models" / "ecapa" / "embedding_model.ckpt").resolve()),
            )
            self.assertEqual(
                config["sources"]["singer_byol"]["code_revision"],
                OFFICIAL_SINGER_BYOL_REVISION,
            )


if __name__ == "__main__":
    unittest.main()
