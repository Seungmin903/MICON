"""Optional compatibility test against the actual separately maintained recommender."""
import os
from pathlib import Path

import numpy as np
import pytest

from ai.embedding_catalog.loader import CatalogCache, build_recommender_index
from ai.embedding_catalog.prepare import upload
from conftest import synthetic_snapshot


def test_existing_recommender_index_preserves_mysql_ids(package_factory, store, models, tmp_path, monkeypatch):
    source = os.environ.get("MICON_RECOMMENDER_SOURCE")
    if not source:
        pytest.skip("Set MICON_RECOMMENDER_SOURCE to the deployed model-server directory")
    assert (Path(source) / "timbre_recommender/catalog.py").is_file()
    monkeypatch.syspath_prepend(source)
    from timbre_recommender.catalog import CatalogIndex

    directory, manifest, checksum = package_factory()
    upload(directory, store)
    catalog = CatalogCache(store, tmp_path / "cache", models).refresh(synthetic_snapshot(store, manifest, checksum))
    output = tmp_path / "index"
    build_recommender_index(catalog, output)
    loaded = CatalogIndex.load(output, verify_hashes=True)
    assert [track.track_id for track in loaded.tracks] == ["1", "2"]
    assert loaded.tracks[0].metadata["catalog_track_id"] == "catalog-0"
    from timbre_recommender.composition import song_id_of
    assert [song_id_of(track.metadata) for track in loaded.tracks] == [1, 2]
    for song_id in (1, 2):
        for model in models:
            np.testing.assert_allclose(loaded.embeddings(str(song_id), model),
                                       catalog.embeddings(song_id, model), atol=1e-6)
