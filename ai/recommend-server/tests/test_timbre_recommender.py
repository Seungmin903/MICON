from __future__ import annotations

import csv
import json
from pathlib import Path
import tempfile
import unittest

import numpy as np
import soundfile as sf

from timbre_recommender.catalog import CatalogIndex, build_catalog_index
from timbre_recommender.embedding import load_vocal_windows
from timbre_recommender.scoring import RankerConfig, recommend


def _segments(dimension: int, axis: int, count: int = 6, jitter: float = 0.01) -> np.ndarray:
    rows = []
    for index in range(count):
        row = np.zeros(dimension, dtype=np.float32)
        row[axis] = 1.0
        row[(axis + index + 1) % dimension] = jitter
        row /= np.linalg.norm(row)
        rows.append(row)
    return np.stack(rows)


class RecommenderTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _build(self, tracks: list[dict[str, object]], *, max_segments: int = 9) -> CatalogIndex:
        manifest = self.root / "catalog.csv"
        with manifest.open("w", encoding="utf-8", newline="") as stream:
            writer = csv.DictWriter(
                stream,
                fieldnames=(
                    "track_id",
                    "title",
                    "artist",
                    "ecapa_path",
                    "singer_byol_path",
                    "metadata_json",
                ),
            )
            writer.writeheader()
            for item in tracks:
                track_id = str(item["track_id"])
                ecapa_path = self.root / f"{track_id}.ecapa.npy"
                byol_path = self.root / f"{track_id}.singer_byol.npy"
                np.save(ecapa_path, item["ecapa"], allow_pickle=False)
                np.save(byol_path, item["singer_byol"], allow_pickle=False)
                writer.writerow(
                    {
                        "track_id": track_id,
                        "title": item.get("title", track_id),
                        "artist": item.get("artist", f"artist-{track_id}"),
                        "ecapa_path": ecapa_path.name,
                        "singer_byol_path": byol_path.name,
                        "metadata_json": json.dumps(item.get("metadata", {})),
                    }
                )
        output = self.root / "index"
        build_catalog_index(manifest, output, max_segments=max_segments)
        return CatalogIndex.load(output, verify_hashes=True)

    def test_exact_timbre_match_ranks_first_and_is_deterministic(self) -> None:
        query = {
            "ecapa": _segments(192, 0),
            "singer_byol": _segments(1000, 0),
        }
        catalog = self._build(
            [
                {"track_id": "match", **query},
                {
                    "track_id": "other-a",
                    "ecapa": _segments(192, 1),
                    "singer_byol": _segments(1000, 1),
                },
                {
                    "track_id": "other-b",
                    "ecapa": _segments(192, 2),
                    "singer_byol": _segments(1000, 2),
                },
            ]
        )
        first = recommend(catalog, query)
        second = recommend(catalog, query)
        self.assertEqual(first, second)
        self.assertEqual(first["recommendations"][0]["track_id"], "match")
        self.assertEqual(first["algorithm"]["effective_weights"], {"ecapa": 0.5, "singer_byol": 0.5})
        self.assertEqual(first["algorithm"]["scope"], "vocal_timbre_only")
        self.assertEqual(first["algorithm"]["normalization"], "absolute cosine-derived similarity clipped to [0, 1]")
        self.assertAlmostEqual(first["recommendations"][0]["models"]["ecapa"]["absolute_score"], 1.0, places=4)
        self.assertNotIn("percentile", first["recommendations"][0]["models"]["ecapa"])

    def test_confidence_diagnostics_do_not_change_absolute_fusion_weights(self) -> None:
        ecapa_query = _segments(192, 0, count=4)
        byol_query = _segments(1000, 0, count=4, jitter=0.0)
        byol_near = byol_query.copy()
        byol_near[:, 1] = 0.01
        byol_near /= np.linalg.norm(byol_near, axis=1, keepdims=True)
        catalog = self._build(
            [
                {
                    "track_id": "a",
                    "ecapa": _segments(192, 0),
                    "singer_byol": _segments(1000, 0, jitter=0.0),
                },
                {
                    "track_id": "b",
                    "ecapa": _segments(192, 1),
                    "singer_byol": byol_near,
                },
            ]
        )
        result = recommend(catalog, {"ecapa": ecapa_query, "singer_byol": byol_query})
        self.assertTrue(result["model_diagnostics"]["ecapa"]["high_confidence"])
        self.assertFalse(result["model_diagnostics"]["singer_byol"]["high_confidence"])
        self.assertEqual(
            result["algorithm"]["effective_weights"],
            {"ecapa": 0.5, "singer_byol": 0.5},
        )
        self.assertEqual(
            result["algorithm"]["weight_reason"],
            "fixed_candidate_independent_weights",
        )

    def test_catalog_caps_segments_and_exclusion_is_applied(self) -> None:
        query = {
            "ecapa": _segments(192, 0, count=15),
            "singer_byol": _segments(1000, 0, count=15),
        }
        catalog = self._build(
            [
                {"track_id": "source", **query},
                {
                    "track_id": "candidate",
                    "ecapa": _segments(192, 1, count=15),
                    "singer_byol": _segments(1000, 1, count=15),
                },
            ],
            max_segments=9,
        )
        self.assertEqual(catalog.embeddings("source", "ecapa").shape, (9, 192))
        result = recommend(catalog, query, excluded_track_ids=["source"])
        self.assertEqual(result["query"]["segments"], {"ecapa": 12, "singer_byol": 12})
        self.assertEqual([item["track_id"] for item in result["recommendations"]], ["candidate"])

    def test_absolute_similarity_does_not_depend_on_candidate_rank(self) -> None:
        query = {
            "ecapa": _segments(192, 0),
            "singer_byol": _segments(1000, 0),
        }
        catalog = self._build(
            [
                {"track_id": "exact", **query},
                {
                    "track_id": "orthogonal-a",
                    "ecapa": _segments(192, 1),
                    "singer_byol": _segments(1000, 1),
                },
                {
                    "track_id": "orthogonal-b",
                    "ecapa": _segments(192, 2),
                    "singer_byol": _segments(1000, 2),
                },
            ]
        )
        full_result = recommend(catalog, query)
        reduced_result = recommend(catalog, query, excluded_track_ids=["orthogonal-b"])
        full_scores = {
            item["track_id"]: item["models"]["ecapa"]["absolute_score"]
            for item in full_result["recommendations"]
        }
        reduced_scores = {
            item["track_id"]: item["models"]["ecapa"]["absolute_score"]
            for item in reduced_result["recommendations"]
        }
        self.assertAlmostEqual(full_scores["exact"], 1.0, places=4)
        self.assertAlmostEqual(
            full_scores["orthogonal-a"], reduced_scores["orthogonal-a"], places=12
        )

    def test_catalog_rejects_wrong_model_dimension(self) -> None:
        with self.assertRaisesRegex(ValueError, "shape must be"):
            self._build(
                [
                    {
                        "track_id": "bad",
                        "ecapa": np.ones((3, 191), dtype=np.float32),
                        "singer_byol": _segments(1000, 0),
                    }
                ]
            )

    def test_clean_audio_is_split_into_four_second_two_second_hop_windows(self) -> None:
        sample_rate = 44_100
        time = np.arange(sample_rate * 12, dtype=np.float32) / sample_rate
        signal = 0.2 * np.sin(2 * np.pi * 220 * time)
        audio = self.root / "vocal.wav"
        sf.write(audio, signal, sample_rate, subtype="FLOAT")
        windows, metadata = load_vocal_windows(audio, max_segments=12, min_segments=5)
        self.assertEqual(windows.shape, (5, sample_rate * 4))
        self.assertEqual([item.start_seconds for item in metadata], [0.0, 2.0, 4.0, 6.0, 8.0])

    def test_ten_seconds_can_supply_four_user_windows_without_changing_catalog_default(self) -> None:
        sample_rate = 44_100
        time = np.arange(sample_rate * 10, dtype=np.float32) / sample_rate
        audio = self.root / "ten-second-vocal.wav"
        sf.write(audio, 0.2 * np.sin(2 * np.pi * 220 * time), sample_rate, subtype="FLOAT")
        windows, metadata = load_vocal_windows(audio, max_segments=12, min_segments=4)
        self.assertEqual(windows.shape, (4, sample_rate * 4))
        self.assertEqual([item.start_seconds for item in metadata], [0.0, 2.0, 4.0, 6.0])
        with self.assertRaisesRegex(ValueError, "need at least 5"):
            load_vocal_windows(audio, max_segments=12, min_segments=5)

    def test_index_provenance_is_recorded(self) -> None:
        catalog = self._build(
            [
                {
                    "track_id": "one",
                    "ecapa": _segments(192, 0),
                    "singer_byol": _segments(1000, 0),
                }
            ]
        )
        metadata = json.loads((catalog.root / "index.json").read_text(encoding="utf-8"))
        self.assertEqual(metadata["schema_version"], 1)
        self.assertIn("manifest_sha256", metadata)
        self.assertEqual(metadata["models"]["ecapa"]["dimension"], 192)
        self.assertEqual(metadata["models"]["singer_byol"]["dimension"], 1000)

    def test_optional_track_metadata_is_preserved_in_index_and_results(self) -> None:
        vectors = {
            "ecapa": _segments(192, 0),
            "singer_byol": _segments(1000, 0),
        }
        catalog = self._build(
            [
                {
                    "track_id": "one",
                    **vectors,
                    "metadata": {
                        "genres": ["K-Pop"],
                        "album_name": "Album",
                        "release_date": "2024-01-01",
                    },
                }
            ]
        )
        self.assertEqual(catalog.tracks[0].metadata["genres"], ["K-Pop"])
        result = recommend(catalog, vectors)
        self.assertEqual(result["recommendations"][0]["metadata"]["album_name"], "Album")


if __name__ == "__main__":
    unittest.main()
