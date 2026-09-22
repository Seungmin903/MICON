from __future__ import annotations

import csv
import gc
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np

from pitch_profile import (
    MIDI_MAX,
    MIDI_MIN,
    PITCH_EXTRACTION_VERSION,
    summarize_pitch_profile,
)
from timbre_recommender.catalog import CatalogIndex, build_catalog_index
from timbre_recommender.pitch_range import (
    KEY_SHIFT_BOUNDS,
    KEY_SHIFT_MAX,
    KEY_SHIFT_MIN,
    KEY_SHIFTS,
    RANGE_CATALOG_FILENAME,
    RANGE_SCORING_VERSION,
    RangeCatalog,
    _score_at_shift,
    canonical_json_sha256,
    score_range_fit,
)
from timbre_recommender.scoring import RankerConfig, recommend


def _notes(midis: list[int]) -> list[dict[str, float]]:
    return [
        {"midi": midi, "duration": 1.0, "confidence": 0.9}
        for midi in midis
    ]


def _profile(midis: list[int], kind: str = "test") -> dict[str, object]:
    return summarize_pitch_profile(
        _notes(midis),
        profile_kind=kind,
        f0_agreement_ratio=0.9,
        octave_mismatch_ratio=0.01,
        compared_frames=100,
    )


def _segments(dimension: int, axis: int) -> np.ndarray:
    values = np.zeros((6, dimension), dtype=np.float32)
    values[:, axis] = 1.0
    return values


def _manual_profile(
    masses: dict[int, float], *, core_low: int, median: int, core_high: int
) -> dict[str, object]:
    histogram = [0.0] * (MIDI_MAX - MIDI_MIN + 1)
    for midi, mass in masses.items():
        histogram[midi - MIDI_MIN] = mass
    return {
        "schemaVersion": 1,
        "histogramMidiMin": MIDI_MIN,
        "histogramMidiMax": MIDI_MAX,
        "histogram": histogram,
        "coreLowMidi": core_low,
        "medianMidi": median,
        "coreHighMidi": core_high,
        "reliable": True,
    }


class PitchProfileTestCase(unittest.TestCase):
    def test_key_shift_contract_matches_playable_accompaniment_range(self) -> None:
        self.assertEqual(KEY_SHIFT_MIN, -3)
        self.assertEqual(KEY_SHIFT_MAX, 3)
        self.assertEqual(KEY_SHIFT_BOUNDS, (-3, 3))
        self.assertEqual(KEY_SHIFTS, tuple(range(-3, 4)))
        self.assertEqual(len(KEY_SHIFTS), 7)
        self.assertNotIn(4, KEY_SHIFTS)

    def test_summary_uses_duration_weighted_core_range_and_normalized_histogram(self) -> None:
        notes = [
            {"midi": 48, "duration": 0.1, "confidence": 0.9},
            {"midi": 60, "duration": 5.0, "confidence": 0.8},
            {"midi": 64, "duration": 5.0, "confidence": 0.8},
            {"midi": 79, "duration": 0.1, "confidence": 0.9},
        ]
        profile = summarize_pitch_profile(notes, profile_kind="observed_user_sample")
        self.assertEqual(profile["coreLowMidi"], 60)
        self.assertEqual(profile["coreHighMidi"], 64)
        self.assertEqual(len(profile["histogram"]), MIDI_MAX - MIDI_MIN + 1)
        self.assertAlmostEqual(sum(profile["histogram"]), 1.0, places=6)

    def test_best_key_shift_prefers_matching_center(self) -> None:
        query = _profile([60, 61, 62, 63, 64])
        candidate = _profile([65, 66, 67, 68, 69])
        match = score_range_fit(query, candidate)
        self.assertEqual(match["keyShift"], -3)
        self.assertGreater(match["coverage"], 0.99)
        self.assertGreater(match["softFit"], 0.98)

    def test_ideal_positive_six_shift_is_limited_to_positive_three(self) -> None:
        query = _manual_profile({66: 1.0}, core_low=66, median=66, core_high=66)
        candidate = _manual_profile({60: 1.0}, core_low=60, median=60, core_high=60)

        match = score_range_fit(query, candidate)

        self.assertEqual(match["keyShift"], 3)
        self.assertNotEqual(match["keyShift"], 4)

    def test_guided_range_profile_ignores_center_and_prefers_first_covered_shift(self) -> None:
        # 앱 음역 측정 프로파일의 median 은 (하한+상한)//2 라 "주로 부르는 높이"가 아니다.
        # 곡 core 58–74(median 68), 사용자 창 54–71(중점 62): 중심음 맞추기는 범위 끝 -3을 고르지만
        # -1부터 이미 전부 창 안에 들어가므로 98%를 처음 만족하는 -1이 맞다.
        candidate = _manual_profile({m: 1.0 for m in range(58, 75)}, core_low=58, median=68, core_high=74)
        guided = _manual_profile({m: 1.0 for m in range(54, 72)}, core_low=54, median=62, core_high=71)
        guided["profileKind"] = "guided_range_measurement"
        observed = dict(guided, profileKind="observed_user_sample")

        self.assertEqual(score_range_fit(observed, candidate)["keyShift"], -3)   # 노래에서 뽑은 프로파일: 범위 안 중심 맞춤
        self.assertEqual(score_range_fit(observed, candidate)["keyShiftPolicy"], "center-match-v2")

        match = score_range_fit(guided, candidate)
        self.assertEqual(match["keyShift"], -1)
        self.assertEqual(match["totalCoreExcessSemitones"], 0)
        self.assertGreater(match["coverage"], 0.99)
        self.assertEqual(match["keyShiftPolicy"], "guided-first-98-v2")

    def test_guided_range_chooses_first_shift_reaching_ninety_eight_percent(self) -> None:
        guided = _manual_profile({m: 1.0 for m in range(60, 65)}, core_low=60, median=62, core_high=64)
        guided["profileKind"] = "guided_range_measurement"
        candidate = _manual_profile({m: 1.0 for m in range(56, 65)}, core_low=56, median=60, core_high=64)

        match = score_range_fit(guided, candidate)

        self.assertEqual(match["keyShift"], 2)
        self.assertGreaterEqual(match["coverage"], 0.98)
        self.assertLess(_score_at_shift(guided, candidate, 1)["coverage"], 0.98)

    def test_low_cross_model_agreement_marks_profile_unreliable(self) -> None:
        profile = summarize_pitch_profile(
            _notes([60, 61, 62, 63]),
            profile_kind="observed_user_sample",
            f0_agreement_ratio=0.4,
            octave_mismatch_ratio=0.01,
            compared_frames=100,
        )
        self.assertFalse(profile["reliable"])
        self.assertIn("low_crepe_pyin_agreement", profile["qualityReasons"])

    def test_unreliable_catalog_profile_cannot_contribute_range_score(self) -> None:
        query = _profile([60, 61, 62, 63, 64])
        candidate = _profile([60, 61, 62, 63, 64])
        candidate["reliable"] = False
        candidate["qualityReasons"] = ["low_crepe_pyin_agreement"]

        match = score_range_fit(query, candidate)

        self.assertFalse(match["catalogProfileReliable"])
        self.assertFalse(match["rangeContributionApplied"])
        self.assertEqual(match["score"], 0.0)
        self.assertEqual(match["softFit"], 0.0)
        self.assertGreater(match["scoreBeforeReliabilityGate"], 0.0)
        self.assertEqual(match["qualityReasons"], ["low_crepe_pyin_agreement"])

    def test_key_selection_uses_the_highest_continuous_score(self) -> None:
        query = _manual_profile(
            {70: 0.1, 77: 0.8, 81: 0.1}, core_low=70, median=77, core_high=81
        )
        candidate = _manual_profile(
            {64: 10 / 51, 73: 29 / 51, 80: 12 / 51},
            core_low=66,
            median=73,
            core_high=81,
        )
        match = score_range_fit(query, candidate)
        self.assertEqual(match["keyShift"], 2)
        self.assertGreaterEqual(match["coverage"], 0.80)
        self.assertGreater(match["coverageFit"], 0.50)

    def test_core_high_excess_is_penalized_continuously(self) -> None:
        query = _manual_profile(
            {70: 0.1, 77: 0.8, 81: 0.1}, core_low=70, median=77, core_high=81
        )
        candidate = _manual_profile(
            {74: 0.8, 90: 0.2}, core_low=74, median=74, core_high=90
        )
        match = score_range_fit(query, candidate)
        self.assertGreaterEqual(match["coverage"], 0.80)
        self.assertGreater(match["highCoreExcessSemitones"], 0.0)
        self.assertGreater(match["boundaryFit"], 0.0)
        self.assertLess(match["boundaryFit"], 1.0)

    def test_coverage_sigmoid_is_continuous_around_eighty_percent(self) -> None:
        query = _manual_profile(
            {70: 0.1, 77: 0.8, 81: 0.1}, core_low=70, median=77, core_high=81
        )
        matches = []
        for coverage in (0.79, 0.80, 0.81):
            candidate = _manual_profile(
                {77: coverage, 95: 1.0 - coverage},
                core_low=77,
                median=77,
                core_high=77,
            )
            matches.append(score_range_fit(query, candidate))
        coverage_fits = [item["coverageFit"] for item in matches]
        self.assertLess(coverage_fits[0], coverage_fits[1])
        self.assertLess(coverage_fits[1], coverage_fits[2])
        self.assertAlmostEqual(coverage_fits[1], 0.5, places=6)
        self.assertTrue(all(0.0 < item["softFit"] < 1.0 for item in matches))

    def test_range_catalog_requires_same_track_set(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = {
                "schemaVersion": 1,
                "scoringVersion": RANGE_SCORING_VERSION,
                "pitchExtractionVersion": PITCH_EXTRACTION_VERSION,
                "keyShiftRange": list(KEY_SHIFT_BOUNDS),
                "profiles": {"one": _profile([60, 62, 64])},
            }
            (root / RANGE_CATALOG_FILENAME).write_text(
                json.dumps(payload), encoding="utf-8"
            )
            with self.assertRaisesRegex(ValueError, "track mismatch"):
                RangeCatalog.load(root, expected_track_ids={"one", "two"})

    def test_index_provenance_hash_is_independent_of_json_line_endings(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            index = root / "index.json"
            lf_bytes = b'{\n  "schema_version": 1,\n  "tracks": []\n}\n'
            index.write_bytes(lf_bytes)
            source_hash = canonical_json_sha256(index)
            payload = {
                "schemaVersion": 1,
                "scoringVersion": RANGE_SCORING_VERSION,
                "pitchExtractionVersion": PITCH_EXTRACTION_VERSION,
                "keyShiftRange": list(KEY_SHIFT_BOUNDS),
                "source": {
                    "indexFile": index.name,
                    "indexCanonicalSha256": source_hash,
                },
                "profiles": {"one": _profile([60, 62, 64])},
            }
            (root / RANGE_CATALOG_FILENAME).write_text(
                json.dumps(payload), encoding="utf-8"
            )

            index.write_bytes(lf_bytes.replace(b"\n", b"\r\n"))

            loaded = RangeCatalog.load(
                root,
                expected_track_ids={"one"},
                verify_hashes=True,
            )
            self.assertEqual(set(loaded.profiles), {"one"})
            self.assertEqual(loaded.metadata["keyShiftRange"], list(KEY_SHIFT_BOUNDS))

    def test_range_catalog_rejects_stale_positive_six_metadata(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            payload = {
                "schemaVersion": 1,
                "scoringVersion": RANGE_SCORING_VERSION,
                "pitchExtractionVersion": PITCH_EXTRACTION_VERSION,
                "keyShiftRange": [-6, 6],
                "profiles": {"one": _profile([60, 62, 64])},
            }
            (root / RANGE_CATALOG_FILENAME).write_text(
                json.dumps(payload), encoding="utf-8"
            )

            with self.assertRaisesRegex(ValueError, "key shift range"):
                RangeCatalog.load(root)


class RangeAwareRankingTestCase(unittest.TestCase):
    def test_score_contracts_use_continuous_fusion_in_both_modes(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "catalog.csv"
            rows = []
            for track_id, axis in (("timbre", 0), ("range", 1)):
                ecapa = root / f"{track_id}.ecapa.npy"
                byol = root / f"{track_id}.singer_byol.npy"
                np.save(ecapa, _segments(192, axis), allow_pickle=False)
                np.save(byol, _segments(1000, axis), allow_pickle=False)
                rows.append(
                    {
                        "track_id": track_id,
                        "title": track_id,
                        "artist": "artist",
                        "ecapa_path": ecapa.name,
                        "singer_byol_path": byol.name,
                    }
                )
            with manifest.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)
            index_root = root / "index"
            build_catalog_index(manifest, index_root)
            catalog = CatalogIndex.load(index_root)
            query = {
                "ecapa": _segments(192, 0),
                "singer_byol": _segments(1000, 0),
            }
            query_pitch = _profile([60, 61, 62, 63, 64])
            catalog_pitch_profiles = {
                "timbre": _profile([78, 79, 80, 81, 82]),
                "range": _profile([60, 61, 62, 63, 64]),
            }
            result = recommend(
                catalog,
                query,
                query_pitch_profile=query_pitch,
                catalog_pitch_profiles=catalog_pitch_profiles,
                mode="range",
            )
            self.assertEqual(result["recommendations"][0]["track_id"], "range")
            self.assertEqual(
                result["algorithm"]["feature_weights"],
                {"timbre": 0.3, "range": 0.7},
            )
            self.assertEqual(
                result["algorithm"]["scope"],
                "vocal_timbre_and_observed_range",
            )

            timbre_result = recommend(
                catalog,
                query,
                query_pitch_profile=query_pitch,
                catalog_pitch_profiles=catalog_pitch_profiles,
                mode="timbre",
            )
            score_contracts = (
                (result, {"timbre": 0.3, "range": 0.7}),
                (timbre_result, {"timbre": 0.75, "range": 0.25}),
            )
            for mode_result, expected_weights in score_contracts:
                self.assertEqual(
                    mode_result["algorithm"]["feature_weights"], expected_weights
                )
                scores = [
                    item["final_score"] for item in mode_result["recommendations"]
                ]
                self.assertEqual(scores, sorted(scores, reverse=True))
                for item in mode_result["recommendations"]:
                    expected_fusion = (
                        expected_weights["timbre"] * item["timbre_score"]
                        + expected_weights["range"] * item["range_fit"]["score"]
                    )
                    self.assertAlmostEqual(
                        item["fusion_score"], expected_fusion, places=12
                    )
                    self.assertAlmostEqual(item["final_score"], expected_fusion, places=12)
                    self.assertNotIn("selection_tier", item)
            del catalog
            gc.collect()

    def test_range_candidate_union_adds_best_continuous_range_song(self) -> None:
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            manifest = root / "catalog.csv"
            rows = []
            for track_id, axis in (("ineligible", 0), ("eligible", 1)):
                ecapa = root / f"{track_id}.ecapa.npy"
                byol = root / f"{track_id}.singer_byol.npy"
                np.save(ecapa, _segments(192, axis), allow_pickle=False)
                np.save(byol, _segments(1000, axis), allow_pickle=False)
                rows.append(
                    {
                        "track_id": track_id,
                        "title": track_id,
                        "artist": "artist",
                        "ecapa_path": ecapa.name,
                        "singer_byol_path": byol.name,
                    }
                )
            with manifest.open("w", encoding="utf-8", newline="") as stream:
                writer = csv.DictWriter(stream, fieldnames=rows[0].keys())
                writer.writeheader()
                writer.writerows(rows)
            index_root = root / "index"
            build_catalog_index(manifest, index_root)
            catalog = CatalogIndex.load(index_root)
            query_pitch = _manual_profile(
                {70: 0.1, 77: 0.8, 81: 0.1}, core_low=70, median=77, core_high=81
            )
            ineligible = _manual_profile(
                {95: 1.0}, core_low=95, median=95, core_high=95
            )
            eligible = _manual_profile(
                {70: 0.1, 77: 0.8, 81: 0.1},
                core_low=70,
                median=77,
                core_high=81,
            )
            result = recommend(
                catalog,
                {"ecapa": _segments(192, 0), "singer_byol": _segments(1000, 0)},
                query_pitch_profile=query_pitch,
                catalog_pitch_profiles={"ineligible": ineligible, "eligible": eligible},
                mode="range",
                config=RankerConfig(candidate_k_per_model=1, top_k=1),
            )
            self.assertEqual(result["candidate_union_count"], 2)
            self.assertEqual(
                result["algorithm"]["range_scoring"]["key_shift_semitones"],
                list(KEY_SHIFT_BOUNDS),
            )
            self.assertEqual(result["algorithm"]["range_scoring"]["coverage_sigmoid_center"], 0.80)
            del catalog
            gc.collect()


if __name__ == "__main__":
    unittest.main()
