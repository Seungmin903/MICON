from __future__ import annotations

import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock

import catalog_cache


class CatalogCacheTest(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory()
        self.addCleanup(self.tmp.cleanup)
        self.addCleanup(catalog_cache.clear_cache)
        self.root = Path(self.tmp.name)
        (self.root / "index.json").write_text("{}", encoding="utf-8")
        (self.root / "range_profiles.json").write_text("{}", encoding="utf-8")

    def test_verified_objects_are_reused_until_any_catalog_file_changes(self):
        catalog = SimpleNamespace(tracks=(SimpleNamespace(track_id="one"),))
        ranges = object()
        with (mock.patch.object(catalog_cache.CatalogIndex, "load", return_value=catalog) as index_load,
              mock.patch.object(catalog_cache.RangeCatalog, "load", return_value=ranges) as range_load):
            first = catalog_cache.load_catalogs(self.root)
            cached = catalog_cache.load_catalogs(self.root)
            self.assertFalse(first[2]["catalogCacheHit"])
            self.assertTrue(cached[2]["catalogCacheHit"])
            index_load.assert_called_once_with(self.root, verify_hashes=True, timings=mock.ANY)
            range_load.assert_called_once_with(
                self.root, expected_track_ids={"one"}, verify_hashes=True, timings=mock.ANY,
            )

            (self.root / "range_profiles.json").write_text("{\"revision\": 2}", encoding="utf-8")
            refreshed = catalog_cache.load_catalogs(self.root)

        self.assertFalse(refreshed[2]["catalogCacheHit"])
        self.assertEqual(index_load.call_count, 2)
        self.assertEqual(range_load.call_count, 2)


if __name__ == "__main__":
    unittest.main()
