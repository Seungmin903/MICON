from __future__ import annotations

import json
from pathlib import Path
import unittest
from unittest import mock

from schemas import Job, Recommendation
import timbre_recommender.composition as composition
from timbre_recommender.composition import (
    allowed_languages,
    compose_two_axis,
    song_id_of,
    track_language,
)


def _item(track_id: str, ecapa: float, byol: float, *, tj: str | None = "TJ-00001", lang: str = "kor", range_score: float | None = 0.9):
    return {
        "track_id": track_id,
        "title": f"title-{track_id}",
        "artist": "artist",
        "metadata": {"tj_id": tj, "languages": lang, "genres": ["K-Pop"], "album_name": "a", "display_image_url": None, "release_date": "2020-01-01"},
        "models": {
            "ecapa": {"absolute_score": ecapa, "match_consistency": 0.7},
            "singer_byol": {"absolute_score": byol, "match_consistency": 0.6},
        },
        "timbre_score": (ecapa + byol) / 2,
        "final_score": (ecapa + byol) / 2,
        "range_fit": None if range_score is None else {
            "scoringVersion": "vocal-range-keyshift-continuous-v2", "score": range_score, "coverage": 0.9, "centerScore": 0.9,
            "spanFit": 1.0, "softFit": 0.8, "keyShift": -2, "songCoreLowMidi": 55, "songCoreHighMidi": 67, "catalogProfileReliable": True,
        },
    }


class SongIdAndLanguageTest(unittest.TestCase):
    def test_song_id_uses_last_number_group(self) -> None:
        self.assertEqual(song_id_of({"tj_id": "TJ-00140"}), "tj-00140")
        self.assertEqual(song_id_of({"tj_id": "TJ001-13588"}), "tj-13588")
        self.assertIsNone(song_id_of({}))
        self.assertIsNone(song_id_of(None))

    def test_language_mapping(self) -> None:
        self.assertEqual(track_language({"languages": "kor"}), "ko")
        self.assertEqual(track_language({"languages": "jpn"}), "jp")
        self.assertIsNone(track_language({"languages": "mul"}))
        self.assertIsNone(allowed_languages([]))
        self.assertEqual(allowed_languages(["팝"]), {"en"})
        self.assertEqual(allowed_languages(["발라드", "팝"]), {"ko", "en"})


class ComposeTest(unittest.TestCase):
    def setUp(self) -> None:
        # ecapa 순위: A C D B E F / byol 순위: B C E A D F. C 는 두 축 모두 2위 → CONSENSUS.
        self.ranked = [
            _item("A", 0.95, 0.80),
            _item("B", 0.60, 0.99),
            _item("C", 0.90, 0.90),
            _item("D", 0.85, 0.50),
            _item("E", 0.40, 0.85),
            _item("F", 0.30, 0.30),
        ]

    def test_quota_picks_and_final_score_order(self) -> None:
        out = compose_two_axis(self.ranked, top_k=5, range_enabled=True, scoring_version="s")
        recs = out["recommendations"]
        by_id = {r["track_id"]: r for r in recs}
        self.assertEqual(set(by_id), {"A", "B", "C", "D", "E"})            # F 는 양쪽 다 꼴찌
        self.assertEqual(by_id["C"]["axis"], "CONSENSUS")
        self.assertEqual(by_id["A"]["axis"], "ECAPA")
        self.assertEqual(by_id["D"]["axis"], "ECAPA")
        self.assertEqual(by_id["B"]["axis"], "SINGER_BYOL")
        self.assertEqual(by_id["E"]["axis"], "SINGER_BYOL")
        self.assertEqual(out["composition"]["filled"], {"ecapa": 2, "singer_byol": 2, "consensus": 1})
        # 순서 = final_score 내림차순: C .90, A .875, B .795, D .675, E .625
        self.assertEqual([r["track_id"] for r in recs], ["C", "A", "B", "D", "E"])
        self.assertEqual([r["position"] for r in recs], [1, 2, 3, 4, 5])
        self.assertEqual(recs[0]["recommended_key"], -2)
        self.assertEqual(out["composition"]["order"], "final_score desc")
        for r in recs:
            Recommendation.model_validate(r)

    def test_consensus_requires_top_n_in_both_axes(self) -> None:
        with mock.patch.object(composition, "CONSENSUS_TOP_N", 1):
            out = compose_two_axis(self.ranked, top_k=5, range_enabled=True, scoring_version="s")
        self.assertEqual(out["composition"]["filled"]["consensus"], 0)          # ecapa 1위 A ≠ byol 1위 B
        self.assertEqual(len(out["recommendations"]), 5)                           # 빈 자리는 다른 축이 채움
        self.assertTrue(all(r["axis"] != "CONSENSUS" for r in out["recommendations"]))

    def test_percentiles_confidence_and_ranks(self) -> None:
        recs = compose_two_axis(self.ranked, top_k=6, range_enabled=True, scoring_version="s")["recommendations"]
        by_id = {r["track_id"]: r for r in recs}
        self.assertEqual(by_id["A"]["axis_scores"]["ecapa"], 1.0)
        self.assertEqual(by_id["B"]["axis_scores"]["singer_byol"], 1.0)
        self.assertEqual(by_id["C"]["axis_scores"]["consensus"], 0.8)             # min(0.8, 0.8)
        self.assertEqual(by_id["A"]["axis_rank"], {"ecapa": 1, "singer_byol": 4})
        self.assertEqual(by_id["A"]["confidence"], 0.7)                            # ECAPA 축 일치율
        self.assertEqual(by_id["C"]["confidence"], 0.6)                            # CONSENSUS 는 약한 축
        for r in recs:
            for v in r["axis_scores"].values():
                self.assertTrue(0.0 <= v <= 1.0)

    def test_language_gate_and_song_id_filter(self) -> None:
        ranked = self.ranked + [_item("J", 0.99, 0.99, lang="jpn"), _item("N", 0.99, 0.99, tj=None)]
        out = compose_two_axis(ranked, top_k=5, genres=["발라드"], range_enabled=True, scoring_version="s")
        ids = {r["track_id"] for r in out["recommendations"]}
        self.assertNotIn("J", ids)
        self.assertNotIn("N", ids)
        self.assertEqual(out["language_gate"]["allowed"], ["ko"])
        self.assertEqual(out["language_gate"]["excluded_count"], 1)
        self.assertEqual(out["catalog_filter"]["excluded_without_song_id"], 1)

    def test_range_filter_drops_low_scores_only_when_enabled(self) -> None:
        ranked = self.ranked + [_item("L", 0.99, 0.99, range_score=0.1)]
        with_range = compose_two_axis(ranked, top_k=7, range_enabled=True, scoring_version="s")
        self.assertNotIn("L", {r["track_id"] for r in with_range["recommendations"]})
        self.assertEqual(with_range["range_filter"]["excluded_count"], 1)
        without = compose_two_axis(ranked, top_k=7, range_enabled=False, scoring_version="s")
        self.assertIn("L", {r["track_id"] for r in without["recommendations"]})
        self.assertTrue(all(r["range_fit"] is None and r["recommended_key"] is None for r in without["recommendations"]))

    def test_sample_response_matches_schema_when_present(self) -> None:
        sample = Path(__file__).resolve().parents[1] / "docs" / "sample-response.json"
        if not sample.is_file():
            self.skipTest("docs/sample-response.json 미생성")
        job = Job.model_validate(json.loads(sample.read_text(encoding="utf-8")))
        self.assertEqual(job.status, "complete")
        recs = job.result.recommendations
        self.assertTrue(recs and all(r.song_id for r in recs))
        self.assertEqual([r.final_score for r in recs], sorted((r.final_score for r in recs), reverse=True))


if __name__ == "__main__":
    unittest.main()
