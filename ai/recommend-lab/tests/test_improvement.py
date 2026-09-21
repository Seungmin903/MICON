import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from improvement import evaluate


def row(track_id, artist, ecapa, byol):
    return {"track_id": track_id, "title": track_id, "artist": artist, "is_rap": False,
            "final_score": (ecapa + byol) / 2,
            "models": {"ecapa": {"absolute_score": ecapa}, "singer_byol": {"absolute_score": byol}},
            "range_fit": {"keyShift": 0}}


class ImprovementTest(unittest.TestCase):
    def test_time_proxy_gate_and_artist_cap(self):
        rows = [row("a1", "A", .99, .99), row("a2", "A", .98, .98), row("a3", "A", .97, .97),
                row("b1", "B", .96, .96), row("b2", "B", .95, .95), row("c1", "C", .94, .94),
                row("d1", "D", .93, .93)]
        profiles = {item["track_id"]: {"reliable": True, "histogram": [1.0, 99.0], "histogramMidiMin": 59} for item in rows}
        profiles["d1"] = {"reliable": True, "histogram": [10.0, 90.0], "histogramMidiMin": 59}
        baseline = [{"track_id": item["track_id"], "title": item["title"], "artist": item["artist"], "recommended_key": 0} for item in rows[:5]]
        result = evaluate(rows, baseline=baseline, profile={"coreLowMidi": 60, "coreHighMidi": 60},
                          range_available=True, ranges=profiles, excluded_track_ids=set(), top_k=5)
        candidate = result["candidate"]
        self.assertEqual(candidate["metrics"]["maxSongsPerArtist"], 2)
        self.assertEqual(candidate["metrics"]["duplicateItems"], 2)
        self.assertNotIn("d1", {item["track_id"] for item in candidate["recommendations"]})
        self.assertTrue(result["verdict"]["artistCapSatisfied"])


if __name__ == "__main__":
    unittest.main()
