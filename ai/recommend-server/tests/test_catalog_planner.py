from __future__ import annotations

import csv
import json
from pathlib import Path
import tempfile
import unittest
from unittest import mock

from timbre_recommender.catalog_planner import (
    artist_similarity,
    build_catalog_plan,
    fingerprint_audio_files,
    load_metadata_jsonl,
    normalize_text,
    parse_audio_filename,
    strip_brackets,
    title_keys,
    write_catalog_plan,
)


def _metadata(
    title: str,
    artist: str,
    *,
    genres: list[str] | None = None,
    artists: list[dict[str, str]] | None = None,
) -> dict[str, object]:
    return {
        "title": title,
        "artists": artists or [{"name": artist, "role": "primary"}],
        "album_name": "album",
        "release_date": "2025-01-02",
        "display_image_url": "https://example.test/cover.jpg",
        "image_type": "album_cover",
        "credit_relations": [{"type": "lyricist", "artist": {"name": "writer"}}],
        "languages": "kor",
        "lyrics": {"plain": "secret lyrics", "synced": "[00:00] secret"},
        "normalized_genres": genres or ["K-Pop"],
    }


class CatalogPlannerTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.audio = self.root / "audio"
        self.audio.mkdir()
        self.metadata_path = self.root / "metadata.jsonl"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _audio(self, artist: str, tj: str, title: str, content: bytes) -> Path:
        folder = self.audio / artist
        folder.mkdir(parents=True, exist_ok=True)
        path = folder / f"{artist}__{tj}_official__{title}.mp3"
        path.write_bytes(content)
        return path

    def _write_metadata(self, *records: dict[str, object]) -> None:
        self.metadata_path.write_text(
            "".join(json.dumps(item, ensure_ascii=False) + "\n" for item in records),
            encoding="utf-8",
        )

    def test_filename_and_reported_normalizers(self) -> None:
        parsed = parse_audio_filename(
            Path("10CM__TJ073-54825_official__\ubd04\uc774 \uc88b\ub0d0__(Live).mp3")
        )
        self.assertEqual(parsed.artist, "10CM")
        self.assertEqual(parsed.tj_code, "TJ073-54825")
        self.assertEqual(parsed.title, "\ubd04\uc774 \uc88b\ub0d0__(Live)")
        self.assertEqual(normalize_text(" A-B_C! "), "abc")
        self.assertEqual(strip_brackets("A(B[C]D)E"), "AE")
        self.assertIn("partnerforlife", title_keys("\ub0b4 \uc0ac\ub78c (Partner For Life)"))
        score, left_alias, right_alias = artist_similarity("AKMU(\uc545\ubba4)", "\uc545\ubba4")
        self.assertEqual((score, left_alias, right_alias), (1.0, "\uc545\ubba4", "\uc545\ubba4"))
        self.assertEqual(artist_similarity("Tim", "\ud300"), (1.0, "tim", "tim"))
        with self.assertRaisesRegex(ValueError, "must match"):
            parse_audio_filename("bad.mp3")

    def test_metadata_loader_validates_ten_fields_and_drops_lyrics(self) -> None:
        self._write_metadata(_metadata("song", "singer"))
        record = load_metadata_jsonl(self.metadata_path)[0]
        public = record.to_public_dict()
        self.assertEqual(public["artist"], "singer")
        self.assertNotIn("lyrics", public)
        self.assertNotIn("credit_relations", public)

        malformed = _metadata("bad", "singer")
        del malformed["languages"]
        self._write_metadata(malformed)
        with self.assertRaisesRegex(ValueError, "missing"):
            load_metadata_jsonl(self.metadata_path)

    def test_policy_can_pin_the_metadata_file_hash(self) -> None:
        audio = self._audio("Singer", "TJ-1", "Song", b"audio")
        self._write_metadata(_metadata("Song", "Singer"))
        with self.assertRaisesRegex(RuntimeError, "metadata SHA-256"):
            build_catalog_plan(
                [audio],
                self.metadata_path,
                {
                    "source": {"metadata_sha256": "0" * 64},
                    "artists": {"Singer": "person"},
                },
            )

    def test_alias_matching_is_one_to_one_and_uses_artist_score(self) -> None:
        first = self._audio("BIGBANG", "TJ-1", "\ubc45\ubc45\ubc45", b"first")
        second = self._audio("Other", "TJ-2", "BANG BANG BANG", b"second")
        self._write_metadata(
            _metadata("BANG BANG BANG", "Other"),
            _metadata("BANG BANG BANG", "BIGBANG"),
        )
        plan = build_catalog_plan(
            [first, second],
            self.metadata_path,
            {"BIGBANG": "person", "Other": "person"},
        )
        by_artist = {
            item["aliases"]["filename_artist"]: item["match"]["metadata"]["artist"]
            for item in plan.records
        }
        self.assertEqual(by_artist, {"BIGBANG": "BIGBANG", "Other": "Other"})
        self.assertEqual(plan.summary["matched_audio"], 2)

    def test_conservative_exclusions_and_sha_deduplication(self) -> None:
        solo = self._audio("Solo", "TJ-10", "Solo Song", b"same-audio")
        duplicate = self._audio("Solo Alias", "TJ-11", "Solo Song", b"same-audio")
        group = self._audio("Band", "TJ-12", "Band Song", b"band")
        duet = self._audio("A Feat. B", "TJ-13", "Together", b"duet")
        trot = self._audio("Trot Singer", "TJ-14", "Trot Song", b"trot")
        unknown = self._audio("Unknown", "TJ-15", "Unknown Song", b"unknown")
        self._write_metadata(
            _metadata("Solo Song", "Solo"),
            _metadata("Band Song", "Band"),
            _metadata("Together", "A", artists=[
                {"name": "A", "role": "primary"},
                {"name": "B", "role": "featured"},
            ]),
            _metadata("Trot Song", "Trot Singer", genres=["Trot"]),
            _metadata("Unknown Song", "Unknown"),
        )
        plan = build_catalog_plan(
            [solo, duplicate, group, duet, trot, unknown],
            self.metadata_path,
            {
                "Solo": "person",
                "Solo Alias": "person",
                "Band": "group",
                "A": "person",
                "B": "person",
                "Trot Singer": "person",
                "Unknown": "ambiguous",
            },
        )
        records = {item["aliases"]["filename_artist"]: item for item in plan.records}
        duplicate_pair = [records["Solo"], records["Solo Alias"]]
        self.assertEqual(sum(item["decision"] == "include" for item in duplicate_pair), 1)
        duplicate_record = next(
            item for item in duplicate_pair if "duplicate_audio" in item["exclusion_reasons"]
        )
        self.assertIsNotNone(duplicate_record["duplicate_of"])
        self.assertIn("artist_policy:group", records["Band"]["exclusion_reasons"])
        self.assertTrue(
            any(reason.startswith("collaboration:") for reason in records["A Feat. B"]["exclusion_reasons"])
        )
        self.assertIn("genre:trot", records["Trot Singer"]["exclusion_reasons"])
        self.assertEqual(records["Unknown"]["decision"], "review")
        self.assertIn("artist_policy:ambiguous", records["Unknown"]["review_reasons"])
        self.assertEqual(plan.summary["duplicate_audio"], 1)
        self.assertEqual(plan.summary["selected"], 1)

    def test_hash_cache_resumes_without_rehashing_unchanged_audio(self) -> None:
        audio = self._audio("Singer", "TJ-1", "Song", b"audio bytes")
        cache = self.root / "hash-cache.json"
        first = fingerprint_audio_files([audio], cache_path=cache)
        with mock.patch(
            "timbre_recommender.catalog_planner.sha256_file",
            side_effect=AssertionError("unchanged source should use cache"),
        ):
            second = fingerprint_audio_files([audio], cache_path=cache)
        self.assertEqual(first, second)

    def test_low_confidence_unique_title_match_is_sent_to_review(self) -> None:
        audio = self._audio("Unrelated", "TJ-9", "Only Title", b"audio")
        self._write_metadata(_metadata("Only Title", "Different Artist"))
        plan = build_catalog_plan(
            [audio], self.metadata_path, {"Unrelated": "person"}
        )
        record = plan.records[0]
        self.assertEqual(record["decision"], "review")
        self.assertEqual(record["match"]["match_method"], "unique_title_fallback")
        self.assertIn(
            "metadata_match:low_artist_confidence", record["review_reasons"]
        )

    def test_track_include_override_clears_automatic_filters_only(self) -> None:
        audio = self._audio("Band Feat. Guest", "TJ-99", "Override", b"audio")
        self._write_metadata(
            _metadata(
                "Override",
                "Band",
                genres=["Trot"],
                artists=[
                    {"name": "Band", "role": "primary"},
                    {"name": "Guest", "role": "featured"},
                ],
            )
        )
        policy = {
            "artists": {
                "Band Feat. Guest": {
                    "entity_type": "group",
                    "decision": "exclude",
                    "reason_codes": ["group_entity"],
                }
            },
            "track_overrides": {
                "Band Feat. Guest||Override": {
                    "decision": "include",
                    "reason_codes": ["manually_approved"],
                }
            },
        }
        plan = build_catalog_plan([audio], self.metadata_path, policy)
        record = plan.records[0]
        self.assertEqual(record["decision"], "include")
        self.assertEqual(record["exclusion_reasons"], [])
        self.assertIn("genre:trot", record["automatic_exclusion_reasons"])
        self.assertIn("artist_policy:group_entity", record["automatic_exclusion_reasons"])
        self.assertTrue(
            any(
                reason.startswith("collaboration:")
                for reason in record["automatic_exclusion_reasons"]
            )
        )

    def test_atomic_outputs_are_builder_friendly_and_contain_no_lyrics(self) -> None:
        selected = self._audio("Singer", "TJ-7", "Song", b"audio")
        self._write_metadata(_metadata("Song", "Singer"))
        plan = build_catalog_plan(
            [selected], self.metadata_path, {"Singer": {"type": "person"}}
        )
        output = self.root / "plan"
        output.mkdir()
        (output / "catalog_plan.json").write_text("old", encoding="utf-8")
        paths = write_catalog_plan(plan, output)
        payload_text = paths["json"].read_text(encoding="utf-8")
        self.assertNotIn("secret lyrics", payload_text)
        self.assertNotIn('"lyrics"', payload_text)
        payload = json.loads(payload_text)
        self.assertEqual(payload["summary"]["selected"], 1)
        with paths["selected_csv"].open(encoding="utf-8", newline="") as stream:
            rows = list(csv.DictReader(stream))
        self.assertEqual(rows[0]["title"], "Song")
        self.assertEqual(rows[0]["artist"], "Singer")
        self.assertEqual(paths["excluded_csv"].read_text(encoding="utf-8").count("\n"), 1)
        selection = [
            json.loads(line)
            for line in paths["selection_jsonl"].read_text(encoding="utf-8").splitlines()
        ]
        self.assertEqual(selection[0]["audio_sha256"], plan.records[0]["source"]["sha256"])
        self.assertEqual(selection[0]["metadata"]["genres"], ["K-Pop"])
        self.assertNotIn("lyrics", selection[0]["metadata"])

    def test_full_policy_document_and_track_override_are_applied(self) -> None:
        selected = self._audio("Singer", "TJ-8", "Border Song", b"audio")
        self._write_metadata(_metadata("Border Song", "Singer"))
        plan = build_catalog_plan(
            [selected],
            self.metadata_path,
            {
                "artists": {
                    "Singer": {
                        "entity_type": "person",
                        "decision": "include",
                        "reason_codes": ["person_entity"],
                    }
                },
                "track_overrides": {
                    "Singer||Border Song": {
                        "decision": "review",
                        "trot_status": "enka_hold",
                        "reason_codes": ["enka_review"],
                    }
                },
            },
        )
        self.assertEqual(plan.summary["review"], 1)
        self.assertEqual(plan.records[0]["decision"], "review")
        self.assertIn("track_policy:enka_review", plan.records[0]["review_reasons"])

    def test_bundled_artist_policy_is_complete_and_contains_no_private_source_data(self) -> None:
        path = (
            Path(__file__).resolve().parents[1]
            / "recommender-assets"
            / "source-catalog"
            / "artist-policy.json"
        )
        text = path.read_text(encoding="utf-8")
        policy = json.loads(text)
        self.assertEqual(policy["schema_version"], 1)
        self.assertEqual(len(policy["artists"]), 556)
        self.assertEqual(policy["summary"]["track_count"], 872)
        self.assertNotIn("audio_path", text.casefold())
        self.assertNotIn('"lyrics"', text.casefold())
        for entry in policy["artists"].values():
            self.assertIn(entry["decision"], {"include", "exclude", "review"})
            self.assertIn(
                entry["entity_type"],
                {"person", "group", "duo", "virtual", "unknown"},
            )
            if entry["entity_type"] == "person":
                self.assertNotIn("entity_id", entry)
                self.assertNotIn("entity_label", entry)


if __name__ == "__main__":
    unittest.main()
