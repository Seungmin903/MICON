from __future__ import annotations

import json
import os
from pathlib import Path
import tempfile
import unittest
from unittest import mock

import numpy as np

from pitch_profile import summarize_pitch_profile
import timbre_recommender.bulk_catalog as bulk_catalog
from timbre_recommender.bulk_catalog import (
    SelectionTrack,
    _output_paths,
    _state_path,
    finalize_catalog,
    load_selection,
    pending_tracks,
    pipeline_fingerprint,
    publish_catalog,
    validate_checkpoint,
)
from timbre_recommender.catalog import CatalogIndex, sha256_file
from timbre_recommender.pitch_range import RangeCatalog


class BulkCatalogTestCase(unittest.TestCase):
    def setUp(self) -> None:
        self.temporary = tempfile.TemporaryDirectory()
        self.root = Path(self.temporary.name)
        self.workspace = self.root / "workspace"
        self.selection = self.root / "selection.jsonl"

    def tearDown(self) -> None:
        self.temporary.cleanup()

    def _selection_row(
        self, track_id: str, checksum: str, *, metadata: dict[str, object] | None = None
    ) -> dict[str, object]:
        return {
            "track_id": track_id,
            "audio_path": str(self.root / f"{track_id}.mp3"),
            "audio_sha256": checksum,
            "title": f"title-{track_id}",
            "artist": f"artist-{track_id}",
            "decision": "include",
            "metadata": metadata or {},
        }

    def _write_selection(self, *rows: dict[str, object]) -> None:
        self.selection.write_text(
            "".join(json.dumps(row, ensure_ascii=False) + "\n" for row in rows),
            encoding="utf-8",
        )

    def _complete(self, track: SelectionTrack, fingerprint: str = "fingerprint") -> None:
        outputs = _output_paths(self.workspace, track.track_id)
        outputs["ecapa"].parent.mkdir(parents=True, exist_ok=True)
        np.save(outputs["ecapa"], np.ones((3, 192), dtype=np.float32), allow_pickle=False)
        np.save(
            outputs["singer_byol"],
            np.ones((3, 1000), dtype=np.float32),
            allow_pickle=False,
        )
        outputs["segments"].write_text('{"segments": []}\n', encoding="utf-8")
        profile = summarize_pitch_profile(
            [
                {"midi": 60, "duration": 1.0, "confidence": 0.9},
                {"midi": 64, "duration": 1.0, "confidence": 0.9},
                {"midi": 67, "duration": 1.0, "confidence": 0.9},
            ],
            profile_kind="catalog_song",
        )
        outputs["pitch"].write_text(
            json.dumps(profile, ensure_ascii=False) + "\n", encoding="utf-8"
        )
        records: dict[str, dict[str, object]] = {}
        for key, path in outputs.items():
            records[key] = {"sha256": sha256_file(path)}
            if key in {"ecapa", "singer_byol"}:
                records[key]["shape"] = list(
                    np.load(path, mmap_mode="r", allow_pickle=False).shape
                )
        state = {
            "schema_version": 1,
            "track_id": track.track_id,
            "status": "complete",
            "audio_sha256": track.audio_sha256,
            "pipeline_fingerprint": fingerprint,
            "outputs": records,
        }
        state_path = _state_path(self.workspace, track.track_id)
        state_path.parent.mkdir(parents=True, exist_ok=True)
        state_path.write_text(json.dumps(state) + "\n", encoding="utf-8")

    def test_selection_rejects_duplicate_content_and_strips_private_metadata(self) -> None:
        checksum = "a" * 64
        self._write_selection(
            self._selection_row(
                "tj-1",
                checksum,
                metadata={
                    "genres": ["Pop"],
                    "lyrics": "never expose",
                    "audio_path": "private",
                },
            )
        )
        track = load_selection(self.selection)[0]
        self.assertEqual(track.metadata, {"genres": ["Pop"]})

        self._write_selection(
            self._selection_row("tj-1", checksum),
            self._selection_row("tj-2", checksum),
        )
        with self.assertRaisesRegex(ValueError, "duplicate selected audio content"):
            load_selection(self.selection)

    def test_checkpoint_reuse_depends_on_source_fingerprint_and_every_output(self) -> None:
        self._write_selection(self._selection_row("tj-1", "b" * 64))
        track = load_selection(self.selection)[0]
        self._complete(track)
        self.assertEqual(validate_checkpoint(track, self.workspace, "fingerprint"), (True, "reusable"))
        self.assertEqual(
            validate_checkpoint(track, self.workspace, "new-fingerprint"),
            (False, "pipeline_changed"),
        )
        _output_paths(self.workspace, track.track_id)["pitch"].unlink()
        self.assertEqual(
            validate_checkpoint(track, self.workspace, "fingerprint"),
            (False, "missing_pitch"),
        )

    def test_pending_tracks_only_invalidates_the_changed_track(self) -> None:
        rows = [self._selection_row("tj-1", "c" * 64), self._selection_row("tj-2", "d" * 64)]
        self._write_selection(*rows)
        tracks = load_selection(self.selection)
        for track in tracks:
            self._complete(track)
        changed = SelectionTrack(
            track_id=tracks[1].track_id,
            audio_path=tracks[1].audio_path,
            audio_sha256="e" * 64,
            title=tracks[1].title,
            artist=tracks[1].artist,
            metadata=tracks[1].metadata,
        )
        pending, reasons = pending_tracks([tracks[0], changed], self.workspace, "fingerprint")
        self.assertEqual([item.track_id for item in pending], ["tj-2"])
        self.assertEqual(reasons, {"tj-1": "reusable", "tj-2": "source_hash_changed"})

    def test_finalize_and_publish_make_a_hash_verified_catalog(self) -> None:
        self._write_selection(
            self._selection_row(
                "tj-1",
                "f" * 64,
                metadata={
                    "genres": ["Pop"],
                    "album_name": "Album",
                    "lyrics": "not allowed",
                },
            )
        )
        track = load_selection(self.selection)[0]
        self._complete(track)
        release = self.root / "release" / "catalog-index"
        with mock.patch(
            "timbre_recommender.bulk_catalog.pipeline_fingerprint",
            return_value=("fingerprint", {}),
        ):
            result = finalize_catalog(
                selection_path=self.selection,
                workspace=self.workspace,
                config={},
                model_server_root=self.root,
                output_dir=release,
            )
        self.assertEqual(result["tracks"], 1)
        catalog = CatalogIndex.load(release, verify_hashes=True)
        self.assertEqual(catalog.tracks[0].metadata["genres"], ["Pop"])
        self.assertNotIn("lyrics", catalog.tracks[0].metadata)
        RangeCatalog.load(release, expected_track_ids={"tj-1"}, verify_hashes=True)

        published_dir = self.root / "published"
        published = publish_catalog(release_dir=release, destination_dir=published_dir)
        self.assertEqual(published["tracks"], 1)
        CatalogIndex.load(published_dir, verify_hashes=True)

        marker = published_dir / "old-generation.marker"
        marker.write_text("must survive rollback", encoding="utf-8")
        with self.assertRaisesRegex(RuntimeError, "unmanaged files"):
            publish_catalog(release_dir=release, destination_dir=published_dir)
        self.assertEqual(marker.read_text(encoding="utf-8"), "must survive rollback")
        marker.unlink()
        before_hashes = {
            name: sha256_file(published_dir / name)
            for name in bulk_catalog.MANAGED_RELEASE_FILES
        }
        original_replace = os.replace

        def fail_staged_directory_swap(source: object, destination: object) -> None:
            source_path = Path(source)
            destination_path = Path(destination)
            if (
                destination_path.resolve() == published_dir.resolve()
                and source_path.name.startswith(f".{published_dir.name}.publish-")
                and "backup" not in source_path.name
            ):
                raise OSError("simulated directory swap failure")
            original_replace(source, destination)

        with mock.patch(
            "timbre_recommender.bulk_catalog.os.replace",
            side_effect=fail_staged_directory_swap,
        ):
            with self.assertRaisesRegex(OSError, "simulated directory swap failure"):
                publish_catalog(release_dir=release, destination_dir=published_dir)
        self.assertEqual(
            {
                name: sha256_file(published_dir / name)
                for name in bulk_catalog.MANAGED_RELEASE_FILES
            },
            before_hashes,
        )
        CatalogIndex.load(published_dir, verify_hashes=True)
        self.assertEqual(list(self.root.glob(".published.publish*")), [])

    def test_pipeline_fingerprint_covers_builder_code_and_processing_parameters(self) -> None:
        code_files = (
            "separation.py",
            "pitch_engine.py",
            "pitch_profile.py",
            "build_range_catalog.py",
            "timbre_recommender/bulk_catalog.py",
            "timbre_recommender/embedding.py",
            "timbre_recommender/contracts.py",
        )
        for relative in code_files:
            path = self.root / relative
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(f"source:{relative}\n", encoding="utf-8")
        model_paths = {}
        for name in ("ecapa", "singer_model", "singer_hparams"):
            path = self.root / f"{name}.bin"
            path.write_bytes(name.encode("ascii"))
            model_paths[name] = path
        model_paths["singer_repo"] = self.root

        with mock.patch(
            "timbre_recommender.bulk_catalog._require_model_files",
            return_value=model_paths,
        ), mock.patch(
            "timbre_recommender.bulk_catalog._runtime_provenance",
            return_value={"runtime": "test"},
        ):
            first, details = pipeline_fingerprint(
                config={}, separator="htdemucs", model_server_root=self.root
            )
            self.assertEqual(
                details["parameters"]["embedding_max_segments"],
                bulk_catalog.EMBED_MAX_SEGMENTS,
            )
            self.assertIn(
                "timbre_recommender/bulk_catalog.py", details["source_sha256"]
            )

            builder = self.root / "timbre_recommender" / "bulk_catalog.py"
            builder.write_text("changed builder source\n", encoding="utf-8")
            changed_code, _ = pipeline_fingerprint(
                config={}, separator="htdemucs", model_server_root=self.root
            )
            with mock.patch.object(bulk_catalog, "EMBED_MAX_SEGMENTS", 13):
                changed_parameter, _ = pipeline_fingerprint(
                    config={}, separator="htdemucs", model_server_root=self.root
                )
        self.assertNotEqual(first, changed_code)
        self.assertNotEqual(changed_code, changed_parameter)

    def test_finalize_requires_all_selected_tracks_by_default(self) -> None:
        self._write_selection(
            self._selection_row("tj-1", "1" * 64),
            self._selection_row("tj-2", "2" * 64),
        )
        self._complete(load_selection(self.selection)[0])
        with mock.patch(
            "timbre_recommender.bulk_catalog.pipeline_fingerprint",
            return_value=("fingerprint", {}),
        ):
            with self.assertRaisesRegex(RuntimeError, "incomplete tracks"):
                finalize_catalog(
                    selection_path=self.selection,
                    workspace=self.workspace,
                    config={},
                    model_server_root=self.root,
                    output_dir=self.root / "release" / "catalog-index",
                )


if __name__ == "__main__":
    unittest.main()
