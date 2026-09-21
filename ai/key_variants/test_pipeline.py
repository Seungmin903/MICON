import json
import tempfile
import unittest
from pathlib import Path

import pipeline


class PipelineTest(unittest.TestCase):
    def test_balanced_shards_cover_every_song_once(self):
        songs = [{"songId": str(index), "durationSec": float(index + 1)} for index in range(23)]
        shards = pipeline.balanced_shards(songs, 7)
        ids = [song["songId"] for shard in shards for song in shard]
        self.assertEqual(23, len(ids))
        self.assertEqual(23, len(set(ids)))
        loads = [sum(song["durationSec"] for song in shard) for shard in shards]
        self.assertLessEqual(max(loads) - min(loads), 23)

    def test_key_suffix_matches_s3_name_contract(self):
        self.assertEqual("-3", pipeline.key_suffix(-3))
        self.assertEqual("+1", pipeline.key_suffix(1))

    def test_load_completed_keeps_only_successes(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "manifest.jsonl"
            path.write_text("\n".join((
                json.dumps({"status": "ok", "songId": "a", "semitones": -1}),
                json.dumps({"status": "error", "songId": "a", "semitones": 1}),
            )), encoding="utf-8")
            self.assertEqual({("a", -1)}, set(pipeline.load_completed(path)))


if __name__ == "__main__":
    unittest.main()
