from __future__ import annotations

import os
import unittest
from unittest import mock

import catalog_link


class CatalogLinkTest(unittest.TestCase):
    def setUp(self) -> None:
        self.previous_cache = dict(catalog_link._CACHE)
        self.previous_attempt = catalog_link._LAST_ATTEMPT_MONOTONIC
        catalog_link._CACHE.update(revision=None, batchId=None, songIds={}, error=None)
        catalog_link._LAST_ATTEMPT_MONOTONIC = None

    def tearDown(self) -> None:
        catalog_link._CACHE.update(self.previous_cache)
        catalog_link._LAST_ATTEMPT_MONOTONIC = self.previous_attempt

    def test_ensure_throttles_failures_and_recovers_after_interval(self) -> None:
        response = {"data": {"revision": "r2", "batch_id": "batch-2",
                              "tracks": [{"catalog_track_id": "tj-00281", "song_id": 412}]}}
        with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080", AI_SERVICE_TOKEN="token"),
              mock.patch.object(catalog_link.time, "monotonic", side_effect=[100.0, 110.0, 131.0]),
              mock.patch.object(catalog_link, "_get",
                                side_effect=[OSError("backend starting"), response]) as fetch):
            self.assertEqual(catalog_link.refresh()["mappedTracks"], 0)
            self.assertEqual(catalog_link.ensure()["mappedTracks"], 0)
            self.assertEqual(catalog_link.ensure()["mappedTracks"], 1)
        self.assertEqual(fetch.call_count, 2)

    def test_ensure_does_not_refetch_a_ready_mapping(self) -> None:
        catalog_link._CACHE.update(songIds={"track": 1}, revision="r1", error=None)
        with mock.patch.object(catalog_link, "_get") as fetch:
            self.assertEqual(catalog_link.ensure()["mappedTracks"], 1)
        fetch.assert_not_called()


if __name__ == "__main__":
    unittest.main()
