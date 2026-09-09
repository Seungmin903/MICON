"""Download pinned recommender weights and create a checkout-local config."""

from __future__ import annotations

import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
from typing import Any

from huggingface_hub import hf_hub_download

from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.embedding import (
    ECAPA_CHECKPOINT_SHA256,
    OFFICIAL_SINGER_BYOL_REVISION,
    SINGER_BYOL_CHECKPOINT_SHA256,
    SINGER_BYOL_HPARAMS_SHA256,
)
from timbre_recommender.pitch_range import RangeCatalog


ROOT = Path(__file__).resolve().parent
ASSET_INDEX = ROOT / "recommender-assets" / "catalog-index"
MODEL_ROOT = ROOT / "models" / "recommender"
VENDORED_SINGER_REPO = ROOT / "third_party" / "ssl-singer-identity"
WORKSPACE = ROOT / "workspace" / "recommender"

ECAPA_REPOSITORY = "speechbrain/spkrec-ecapa-voxceleb"
ECAPA_REVISION = "0f99f2d0ebe89ac095bcc5903c4dd8f72b367286"
SINGER_MODEL_REPOSITORY = "BernardoTorres/singer-identity"
SINGER_MODEL_REVISION = "55e09cdfcd733b1c01f2b94f316a0280995a909e"


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _require_hash(path: Path, expected: str, label: str) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"{label} not found: {path}")
    actual = _sha256(path)
    if actual != expected:
        raise RuntimeError(
            f"{label} SHA-256 mismatch: expected {expected}, got {actual} ({path})"
        )


def _ensure_huggingface_file(
    *,
    repository: str,
    revision: str,
    filename: str,
    destination: Path,
    expected_sha256: str,
    local_files_only: bool,
) -> str:
    if destination.is_file() and _sha256(destination) == expected_sha256:
        return "ready"
    cached = Path(
        hf_hub_download(
            repo_id=repository,
            revision=revision,
            filename=filename,
            local_files_only=local_files_only,
        )
    )
    _require_hash(cached, expected_sha256, f"downloaded {filename}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    temporary = destination.with_name(f".{destination.name}.tmp")
    shutil.copyfile(cached, temporary)
    _require_hash(temporary, expected_sha256, f"copied {filename}")
    os.replace(temporary, destination)
    return "downloaded"


def write_runtime_config(
    *,
    index_dir: Path,
    model_root: Path,
    singer_repo: Path,
    workspace: Path,
    device: str = "auto",
) -> Path:
    config = {
        "schema_version": 1,
        "index_dir": str(index_dir.resolve()),
        "ecapa_checkpoint": str(
            (model_root / "ecapa" / "embedding_model.ckpt").resolve()
        ),
        "singer_repo": str(singer_repo.resolve()),
        "singer_checkpoint_root": str((model_root / "singer-identity").resolve()),
        "device": device,
        "sources": {
            "ecapa": {
                "repository": ECAPA_REPOSITORY,
                "revision": ECAPA_REVISION,
            },
            "singer_byol": {
                "code_revision": OFFICIAL_SINGER_BYOL_REVISION,
                "model_repository": SINGER_MODEL_REPOSITORY,
                "model_revision": SINGER_MODEL_REVISION,
            },
        },
    }
    workspace.mkdir(parents=True, exist_ok=True)
    destination = workspace / "config.json"
    temporary = workspace / ".config.json.tmp"
    temporary.write_text(
        json.dumps(config, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(temporary, destination)
    return destination


def prepare(*, local_files_only: bool = False) -> dict[str, Any]:
    catalog = CatalogIndex.load(ASSET_INDEX, verify_hashes=True)
    RangeCatalog.load(
        ASSET_INDEX,
        expected_track_ids={track.track_id for track in catalog.tracks},
        verify_hashes=True,
    )
    revision_path = VENDORED_SINGER_REPO / "REVISION"
    revision = revision_path.read_text(encoding="utf-8").strip()
    if revision != OFFICIAL_SINGER_BYOL_REVISION:
        raise RuntimeError(
            "vendored Singer-BYOL revision mismatch: "
            f"expected {OFFICIAL_SINGER_BYOL_REVISION}, got {revision}"
        )
    if not (VENDORED_SINGER_REPO / "singer_identity" / "model.py").is_file():
        raise FileNotFoundError("vendored Singer-BYOL inference source is incomplete")

    ecapa_checkpoint = MODEL_ROOT / "ecapa" / "embedding_model.ckpt"
    singer_root = MODEL_ROOT / "singer-identity"
    states = {
        "ecapa": _ensure_huggingface_file(
            repository=ECAPA_REPOSITORY,
            revision=ECAPA_REVISION,
            filename="embedding_model.ckpt",
            destination=ecapa_checkpoint,
            expected_sha256=ECAPA_CHECKPOINT_SHA256,
            local_files_only=local_files_only,
        ),
        "singer_byol_model": _ensure_huggingface_file(
            repository=SINGER_MODEL_REPOSITORY,
            revision=SINGER_MODEL_REVISION,
            filename="byol/model.pt",
            destination=singer_root / "byol" / "model.pt",
            expected_sha256=SINGER_BYOL_CHECKPOINT_SHA256,
            local_files_only=local_files_only,
        ),
        "singer_byol_hparams": _ensure_huggingface_file(
            repository=SINGER_MODEL_REPOSITORY,
            revision=SINGER_MODEL_REVISION,
            filename="byol/hyperparams.yaml",
            destination=singer_root / "byol" / "hyperparams.yaml",
            expected_sha256=SINGER_BYOL_HPARAMS_SHA256,
            local_files_only=local_files_only,
        ),
    }
    config_path = write_runtime_config(
        index_dir=ASSET_INDEX,
        model_root=MODEL_ROOT,
        singer_repo=VENDORED_SINGER_REPO,
        workspace=WORKSPACE,
    )
    return {
        "ready": True,
        "catalog_tracks": len(catalog.tracks),
        "config": str(config_path),
        "models": states,
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--local-files-only",
        action="store_true",
        help="do not access the network; use an existing Hugging Face cache",
    )
    args = parser.parse_args()
    print(json.dumps(prepare(local_files_only=args.local_files_only), ensure_ascii=False, indent=2))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
