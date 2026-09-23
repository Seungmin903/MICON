from __future__ import annotations

import os
import threading
import time
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
        fake_thread = mock.Mock()
        fake_thread.start.side_effect = lambda: None
        with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080", AI_SERVICE_TOKEN="token"),
              mock.patch.object(catalog_link.time, "monotonic", side_effect=[100.0, 110.0, 131.0]),
              mock.patch.object(catalog_link.threading, "Thread", return_value=fake_thread) as thread,
              mock.patch.object(catalog_link, "_get",
                                side_effect=[OSError("backend starting"), response]) as fetch):
            self.assertEqual(catalog_link.refresh()["mappedTracks"], 0)
            self.assertEqual(catalog_link.ensure()["mappedTracks"], 0)
            self.assertEqual(catalog_link.ensure()["mappedTracks"], 0)
            thread.call_args.kwargs["target"](*thread.call_args.kwargs["args"])
            self.assertEqual(catalog_link.status()["mappedTracks"], 1)
        self.assertEqual(fetch.call_count, 2)

    def test_ensure_refreshes_a_ready_mapping_after_interval(self) -> None:
        response = {"data": {"revision": "r2", "batch_id": "batch-2",
                              "tracks": [{"catalog_track_id": "new-track", "song_id": 2}]}}
        catalog_link._CACHE.update(songIds={"old-track": "1"}, revision="r1", error=None)
        catalog_link._LAST_ATTEMPT_MONOTONIC = 100.0
        fake_thread = mock.Mock()
        fake_thread.start.side_effect = lambda: None
        with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080"),
              mock.patch.object(catalog_link.time, "monotonic", return_value=131.0),
              mock.patch.object(catalog_link.threading, "Thread", return_value=fake_thread) as thread,
              mock.patch.object(catalog_link, "_get", return_value=response) as fetch):
            self.assertEqual(catalog_link.ensure()["revision"], "r1")
            self.assertEqual(catalog_link.ensure()["revision"], "r1")
            thread.call_args.kwargs["target"](*thread.call_args.kwargs["args"])
            self.assertEqual(catalog_link.status()["revision"], "r2")
        fetch.assert_called_once()
        self.assertEqual(catalog_link.song_ids()["new-track"], "2")

    def test_refresh_failure_keeps_last_good_mapping(self) -> None:
        catalog_link._CACHE.update(songIds={"track": "song-1"}, revision="r1", batchId="b1", error=None)
        with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080"),
              mock.patch.object(catalog_link, "_get", side_effect=OSError("offline"))):
            state = catalog_link.refresh()
        self.assertEqual(catalog_link.song_ids(), {"track": "song-1"})
        self.assertEqual(state["revision"], "r1")
        self.assertEqual(state["mappedTracks"], 1)
        self.assertEqual(state["error"], "offline")

    def test_ensure_returns_cached_mapping_while_backend_refresh_is_slow(self) -> None:
        catalog_link._CACHE.update(songIds={"old-track": "old-song"}, revision="r1", error=None)
        catalog_link._LAST_ATTEMPT_MONOTONIC = 0.0
        started = threading.Event()
        release = threading.Event()
        response = {"data": {"revision": "r2", "batch_id": "b2",
                             "tracks": [{"catalog_track_id": "new-track", "song_id": "new-song"}]}}

        def slow_get(*_):
            started.set()
            release.wait(timeout=2)
            return response

        try:
            with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080"),
                  mock.patch.object(catalog_link, "_get", side_effect=slow_get)):
                before = time.perf_counter()
                state = catalog_link.ensure()
                elapsed = time.perf_counter() - before
                self.assertLess(elapsed, 0.5)
                self.assertEqual(state["revision"], "r1")
                self.assertTrue(started.wait(timeout=1))
                self.assertEqual(catalog_link.song_ids(), {"old-track": "old-song"})
        finally:
            release.set()
            # Wait for the daemon refresh to publish and release its single-flight lock.
            acquired = catalog_link._REFRESH_LOCK.acquire(timeout=2)
            self.assertTrue(acquired)
            catalog_link._REFRESH_LOCK.release()
        self.assertEqual(catalog_link.status()["revision"], "r2")

    def test_uuid_is_not_coerced_and_is_returned_in_recommendations(self) -> None:
        song_id = "e8873464-9cc0-5dbe-b3ac-f561fd55eaa5"
        response = {"data": {"revision": 3, "batch_id": "uuid-batch",
                              "tracks": [{"catalog_track_id": "track-one", "song_id": song_id}]}}
        with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080"),
              mock.patch.object(catalog_link, "_get", return_value=response)):
            self.assertIsNone(catalog_link.refresh()["error"])
        self.assertEqual(catalog_link.song_ids()["track-one"], song_id)
        self.assertEqual(catalog_link.song_ids()[song_id], song_id)
        result = catalog_link.apply({"shelves": [{"items": [{"track_id": "track-one"}]}]})
        self.assertEqual(result["recommendations"][0]["song_id"], song_id)

    def test_legacy_numeric_id_is_emitted_as_string(self) -> None:
        self.assertEqual(catalog_link._song_id(42), "42")

    def test_invalid_ids_do_not_overwrite_previous_mapping(self) -> None:
        catalog_link._CACHE.update(songIds={"kept": "valid-id"}, revision=2, error=None)
        for bad in (True, 0, -1, "", " a", "a/b", "a" * 65, "곡"):
            with self.subTest(bad=bad):
                with self.assertRaises(ValueError):
                    catalog_link._song_id(bad)
                response = {"data": {"revision": 3, "batch_id": "bad",
                                      "tracks": [{"catalog_track_id": "track", "song_id": bad}]}}
                with (mock.patch.dict(os.environ, MICON_BE_BASE_URL="http://backend:8080"),
                      mock.patch.object(catalog_link, "_get", return_value=response)):
                    self.assertIsNotNone(catalog_link.refresh()["error"])
                self.assertEqual(catalog_link.song_ids(), {"kept": "valid-id"})
                self.assertEqual(catalog_link.status()["revision"], 2)
        with self.assertRaises(ValueError):
            catalog_link._song_id(None)


if __name__ == "__main__":
    unittest.main()
