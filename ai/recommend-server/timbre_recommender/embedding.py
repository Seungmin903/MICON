"""4-second audio segmentation and reproducible ECAPA/BYOL extraction."""

from __future__ import annotations

from dataclasses import asdict, dataclass
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import threading
import time
from typing import Any

import librosa
import numpy as np
import soundfile as sf

from .contracts import MODEL_CONTRACTS


ANALYSIS_SAMPLE_RATE = 44_100
WINDOW_SECONDS = 4.0
HOP_SECONDS = 2.0
WINDOW_SAMPLES = int(ANALYSIS_SAMPLE_RATE * WINDOW_SECONDS)
OFFICIAL_SINGER_BYOL_REPOSITORY = "https://github.com/SonyCSLParis/ssl-singer-identity"
OFFICIAL_SINGER_BYOL_REVISION = "24751d11c6c169adc53be906fd36560df447f974"
ECAPA_CHECKPOINT_SHA256 = "0575cb64845e6b9a10db9bcb74d5ac32b326b8dc90352671d345e2ee3d0126a2"
SINGER_BYOL_CHECKPOINT_SHA256 = "509f96f23a1076325f4164fa4176fa282e709da203d30b109fa05b1b6cb3c978"
SINGER_BYOL_HPARAMS_SHA256 = "c0a4160b9546f3d8c8245b559a0a5386fb0925feb9179ba4bc4bcea41dbbde5a"


@dataclass(frozen=True)
class SegmentInfo:
    index: int
    start_seconds: float
    end_seconds: float
    rms: float
    peak: float
    active_frame_fraction: float
    clipped_fraction: float


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(block)
    return digest.hexdigest()


def _require_sha256(path: Path, expected: str, label: str) -> None:
    if not path.is_file():
        raise FileNotFoundError(f"{label} not found: {path}")
    actual = _sha256(path)
    if actual != expected:
        raise RuntimeError(
            f"{label} SHA-256 mismatch: expected {expected}, got {actual} ({path})"
        )


def _git_revision(path: Path) -> str | None:
    if (path / ".git").exists():
        completed = subprocess.run(
            ["git", "-C", str(path), "rev-parse", "HEAD"],
            check=True,
            capture_output=True,
            text=True,
        )
        return completed.stdout.strip()
    revision_file = path / "REVISION"
    if revision_file.is_file():
        revision = revision_file.read_text(encoding="utf-8").strip()
        if len(revision) != 40 or any(character not in "0123456789abcdef" for character in revision):
            raise RuntimeError(f"invalid vendored revision marker: {revision_file}")
        return revision
    return None


def _atomic_save_npy(path: Path, values: np.ndarray) -> None:
    temporary = path.with_name(f".{path.name}.tmp")
    with temporary.open("wb") as stream:
        np.save(stream, values, allow_pickle=False)
    os.replace(temporary, path)


def _uniform_indices(length: int, maximum: int) -> np.ndarray:
    if length <= maximum:
        return np.arange(length, dtype=np.int64)
    return np.linspace(0, length - 1, num=maximum, dtype=np.int64)


def load_vocal_windows(
    audio_path: Path | str,
    *,
    max_segments: int = 12,
    min_segments: int = 5,
    min_rms: float = 0.005,
    min_active_fraction: float = 0.50,
    max_clipped_fraction: float = 0.01,
) -> tuple[np.ndarray, list[SegmentInfo]]:
    """Load clean/separated vocals and retain deterministic usable windows."""

    if max_segments <= 0 or min_segments <= 0 or min_segments > max_segments:
        raise ValueError("require 0 < min_segments <= max_segments")
    source = Path(audio_path).resolve()
    if not source.is_file():
        raise FileNotFoundError(f"vocal audio not found: {source}")
    signal, sample_rate = sf.read(source, always_2d=True, dtype="float32")
    mono = np.mean(signal, axis=1, dtype=np.float32)
    if sample_rate != ANALYSIS_SAMPLE_RATE:
        mono = librosa.resample(
            mono,
            orig_sr=int(sample_rate),
            target_sr=ANALYSIS_SAMPLE_RATE,
            res_type="soxr_hq",
        ).astype(np.float32, copy=False)
    if len(mono) < WINDOW_SAMPLES:
        raise ValueError(
            f"vocal audio must contain at least {WINDOW_SECONDS:.1f} seconds; "
            f"got {len(mono) / ANALYSIS_SAMPLE_RATE:.2f} seconds"
        )

    hop_samples = int(HOP_SECONDS * ANALYSIS_SAMPLE_RATE)
    accepted: list[tuple[np.ndarray, SegmentInfo]] = []
    for index, start in enumerate(range(0, len(mono) - WINDOW_SAMPLES + 1, hop_samples)):
        window = np.ascontiguousarray(mono[start : start + WINDOW_SAMPLES], dtype=np.float32)
        rms = float(np.sqrt(np.mean(np.square(window), dtype=np.float64)))
        peak = float(np.max(np.abs(window)))
        clipped_fraction = float(np.mean(np.abs(window) >= 0.999))
        frame_rms = librosa.feature.rms(
            y=window, frame_length=2048, hop_length=512, center=False
        )[0]
        active_fraction = float(np.mean(frame_rms >= min_rms))
        if (
            rms < min_rms
            or active_fraction < min_active_fraction
            or clipped_fraction > max_clipped_fraction
        ):
            continue
        accepted.append(
            (
                window,
                SegmentInfo(
                    index=index,
                    start_seconds=start / ANALYSIS_SAMPLE_RATE,
                    end_seconds=(start + WINDOW_SAMPLES) / ANALYSIS_SAMPLE_RATE,
                    rms=rms,
                    peak=peak,
                    active_frame_fraction=active_fraction,
                    clipped_fraction=clipped_fraction,
                ),
            )
        )

    if len(accepted) < min_segments:
        raise ValueError(
            f"only {len(accepted)} usable 4-second vocal segments; "
            f"need at least {min_segments}. Supply cleaner/longer separated vocals "
            "or explicitly relax the quality thresholds."
        )
    selected = [accepted[int(index)] for index in _uniform_indices(len(accepted), max_segments)]
    return np.stack([item[0] for item in selected]), [item[1] for item in selected]


def _resolve_device(requested: str) -> str:
    import torch

    if requested == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    if requested == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA was requested but torch.cuda.is_available() is false")
    return requested


def _synchronize(device: str) -> None:
    """Make CUDA timings include queued kernels instead of only dispatch time."""
    if device == "cuda":
        import torch

        torch.cuda.synchronize()


def _ensure_speechbrain_torchaudio_compat() -> None:
    """Restore the discovery hook SpeechBrain 1.0.3 expects on torchaudio 2.11+."""

    import torchaudio

    if not hasattr(torchaudio, "list_audio_backends"):
        # The recommender reads audio through soundfile; SpeechBrain only calls
        # this removed API during import-time backend diagnostics.
        torchaudio.list_audio_backends = lambda: []


def _load_ecapa(checkpoint: Path, device: str):
    import torch
    _ensure_speechbrain_torchaudio_compat()
    from speechbrain.lobes.features import Fbank
    from speechbrain.lobes.models.ECAPA_TDNN import ECAPA_TDNN
    from speechbrain.processing.features import InputNormalization

    _require_sha256(checkpoint, ECAPA_CHECKPOINT_SHA256, "ECAPA checkpoint")
    features = Fbank(n_mels=80).to(device).eval()
    normalization = InputNormalization(norm_type="sentence", std_norm=False).to(device)
    model = ECAPA_TDNN(
        input_size=80,
        channels=[1024, 1024, 1024, 1024, 3072],
        kernel_sizes=[5, 3, 3, 3, 1],
        dilations=[1, 2, 3, 4, 1],
        attention_channels=128,
        lin_neurons=192,
    ).to(device)
    state = torch.load(checkpoint, map_location=device, weights_only=True)
    model.load_state_dict(state)
    model.eval()
    return features, normalization, model


def _embed_ecapa(
    windows_44k: np.ndarray,
    checkpoint: Path,
    device: str,
    batch_size: int,
) -> np.ndarray:
    components = _load_ecapa(checkpoint, device)
    return _embed_ecapa_with_components(windows_44k, components, device, batch_size)


def _embed_ecapa_with_components(
    windows_44k: np.ndarray,
    components: tuple[Any, Any, Any],
    device: str,
    batch_size: int,
) -> np.ndarray:
    import torch

    normalized: list[np.ndarray] = []
    for window in windows_44k:
        rms = float(np.sqrt(np.mean(np.square(window), dtype=np.float64)))
        if rms <= 1e-12:
            raise ValueError("ECAPA cannot embed a silent window")
        at_16k = librosa.resample(
            window * (0.1 / rms),
            orig_sr=ANALYSIS_SAMPLE_RATE,
            target_sr=16_000,
            res_type="soxr_hq",
        ).astype(np.float32, copy=False)
        if len(at_16k) != 64_000:
            raise RuntimeError(f"unexpected ECAPA input length: {len(at_16k)}")
        normalized.append(at_16k)

    features, normalization, model = components
    outputs: list[np.ndarray] = []
    for start in range(0, len(normalized), batch_size):
        waveforms = torch.from_numpy(np.stack(normalized[start : start + batch_size])).to(
            device
        )
        lengths = torch.ones(len(waveforms), device=device)
        with torch.inference_mode():
            feats = features(waveforms)
            feats = normalization(feats, lengths)
            embeddings = model(feats, lengths).squeeze(1)
            embeddings = torch.nn.functional.normalize(embeddings, dim=-1)
        outputs.append(embeddings.cpu().numpy())
    result = np.asarray(np.concatenate(outputs, axis=0), dtype=np.float32)
    if result.shape != (len(windows_44k), MODEL_CONTRACTS["ecapa"].dimension):
        raise RuntimeError(f"unexpected ECAPA output shape: {result.shape}")
    return result


def _load_singer_byol(repo_dir: Path, checkpoint_root: Path, device: str):
    package = repo_dir / "singer_identity" / "model.py"
    if not package.is_file():
        raise FileNotFoundError(
            f"official Singer-BYOL checkout not found: {repo_dir}. Expected {package}"
        )
    checked_out_revision = _git_revision(repo_dir)
    if checked_out_revision is not None and checked_out_revision != OFFICIAL_SINGER_BYOL_REVISION:
        raise RuntimeError(
            "Singer-BYOL checkout revision mismatch: expected "
            f"{OFFICIAL_SINGER_BYOL_REVISION}, got {checked_out_revision}"
        )
    _require_sha256(
        checkpoint_root / "byol" / "hyperparams.yaml",
        SINGER_BYOL_HPARAMS_SHA256,
        "Singer-BYOL hyperparameters",
    )
    _require_sha256(
        checkpoint_root / "byol" / "model.pt",
        SINGER_BYOL_CHECKPOINT_SHA256,
        "Singer-BYOL checkpoint",
    )
    repo_text = str(repo_dir)
    if repo_text not in sys.path:
        sys.path.insert(0, repo_text)
    from singer_identity import load_model

    model = load_model("byol", source=str(checkpoint_root))
    model.eval()
    model.to(device)
    return model


def _embed_singer_byol(
    windows_44k: np.ndarray,
    repo_dir: Path,
    checkpoint_root: Path,
    device: str,
) -> np.ndarray:
    model = _load_singer_byol(repo_dir, checkpoint_root, device)
    return _embed_singer_byol_with_model(windows_44k, model, device)


def _embed_singer_byol_with_model(
    windows_44k: np.ndarray, model: Any, device: str
) -> np.ndarray:
    import torch

    outputs: list[np.ndarray] = []
    with torch.inference_mode():
        for index, window in enumerate(windows_44k):
            waveform = torch.from_numpy(np.asarray(window, dtype=np.float32))
            peak = waveform.abs().max()
            if float(peak) <= 0:
                raise ValueError(f"Singer-BYOL cannot embed silent window {index}")
            # One item per call is deliberate: upstream Grey2Rgb uses a batch-global max.
            embedding = model((waveform / peak).unsqueeze(0).to(device))
            if embedding.shape != (1, MODEL_CONTRACTS["singer_byol"].dimension):
                raise RuntimeError(f"unexpected Singer-BYOL output shape: {embedding.shape}")
            embedding = torch.nn.functional.normalize(embedding, dim=1)
            outputs.append(embedding.cpu().numpy())
    result = np.asarray(np.concatenate(outputs, axis=0), dtype=np.float32)
    if not np.all(np.isfinite(result)):
        raise RuntimeError("Singer-BYOL produced non-finite embeddings")
    return result


class VocalEmbeddingRuntime:
    """Reusable ECAPA and Singer-BYOL models for large catalog builds.

    The regular one-shot API intentionally keeps setup self-contained. A bulk
    build must not reload both checkpoints for every song, so this runtime owns
    the validated model instances and can embed many vocal stems in one process.
    """

    def __init__(
        self,
        *,
        ecapa_checkpoint: Path | str,
        singer_repo: Path | str,
        singer_checkpoint_root: Path | str,
        device: str = "auto",
        batch_size: int = 8,
        offload_after_embed: bool = False,
    ) -> None:
        if batch_size <= 0:
            raise ValueError("batch_size must be positive")
        self.ecapa_checkpoint = Path(ecapa_checkpoint).resolve()
        self.singer_repo = Path(singer_repo).resolve()
        self.singer_checkpoint_root = Path(singer_checkpoint_root).resolve()
        self.device = _resolve_device(device)
        self.batch_size = batch_size
        self.offload_after_embed = offload_after_embed
        self.checked_out_singer_revision = _git_revision(self.singer_repo)
        self.ecapa_checkpoint_sha256 = ECAPA_CHECKPOINT_SHA256
        self.singer_checkpoint_sha256 = SINGER_BYOL_CHECKPOINT_SHA256
        self._inference_lock = threading.Lock()
        load_device = "cpu" if offload_after_embed else self.device
        self._ecapa_components = _load_ecapa(self.ecapa_checkpoint, load_device)
        self._singer_model = _load_singer_byol(
            self.singer_repo, self.singer_checkpoint_root, load_device
        )

    def _move_models(self, device: str) -> None:
        import torch

        for component in self._ecapa_components:
            component.to(device)
        self._singer_model.to(device)
        if device == "cpu" and torch.cuda.is_available():
            torch.cuda.empty_cache()

    def require_compatible(
        self,
        *,
        ecapa_checkpoint: Path,
        singer_repo: Path,
        singer_checkpoint_root: Path,
        requested_device: str,
        batch_size: int,
    ) -> None:
        expected = (
            self.ecapa_checkpoint,
            self.singer_repo,
            self.singer_checkpoint_root,
        )
        actual = (
            ecapa_checkpoint.resolve(),
            singer_repo.resolve(),
            singer_checkpoint_root.resolve(),
        )
        if actual != expected:
            raise ValueError("embedding runtime model paths do not match this request")
        if requested_device != "auto" and _resolve_device(requested_device) != self.device:
            raise ValueError("embedding runtime device does not match this request")
        if batch_size != self.batch_size:
            raise ValueError("embedding runtime batch size does not match this request")

    def embed_windows(
        self, windows_44k: np.ndarray, *, timings: dict[str, Any] | None = None
    ) -> tuple[np.ndarray, np.ndarray]:
        # The API can admit more than one job when RECOMMEND_CONCURRENCY is raised.
        # Upstream Singer-BYOL and device moves are not safe to mutate concurrently.
        with self._inference_lock:
            move_started = time.perf_counter()
            self._move_models(self.device)
            _synchronize(self.device)
            if timings is not None:
                timings["modelDeviceTransferMs"] = round(
                    (time.perf_counter() - move_started) * 1000, 1
                )
            try:
                started = time.perf_counter()
                ecapa = _embed_ecapa_with_components(
                    windows_44k,
                    self._ecapa_components,
                    self.device,
                    self.batch_size,
                )
                _synchronize(self.device)
                if timings is not None:
                    timings["ecapaMs"] = round((time.perf_counter() - started) * 1000, 1)

                started = time.perf_counter()
                singer_byol = _embed_singer_byol_with_model(
                    windows_44k, self._singer_model, self.device
                )
                _synchronize(self.device)
                if timings is not None:
                    timings["singerByolMs"] = round((time.perf_counter() - started) * 1000, 1)
                return ecapa, singer_byol
            finally:
                if self.offload_after_embed and self.device == "cuda":
                    self._move_models("cpu")


def embed_vocal_audio(
    audio_path: Path | str,
    output_prefix: Path | str,
    *,
    ecapa_checkpoint: Path | str,
    singer_repo: Path | str,
    singer_checkpoint_root: Path | str,
    device: str = "auto",
    batch_size: int = 8,
    max_segments: int = 12,
    min_segments: int = 5,
    force: bool = False,
    runtime: VocalEmbeddingRuntime | None = None,
    timings: dict[str, Any] | None = None,
    cached_embeddings: dict[str, tuple[np.ndarray, np.ndarray]] | None = None,
) -> dict[str, Any]:
    """Extract both model matrices and a provenance sidecar from vocal audio."""

    if batch_size <= 0:
        raise ValueError("batch_size must be positive")
    source = Path(audio_path).resolve()
    prefix = Path(output_prefix).resolve()
    destinations = {
        "ecapa": Path(f"{prefix}.ecapa.npy"),
        "singer_byol": Path(f"{prefix}.singer_byol.npy"),
        "metadata": Path(f"{prefix}.segments.json"),
    }
    existing = [path for path in destinations.values() if path.exists()]
    if existing and not force:
        raise FileExistsError(
            f"embedding output already exists ({existing[0]}); pass force=True to replace it"
        )
    prefix.parent.mkdir(parents=True, exist_ok=True)
    started = time.perf_counter()
    windows, segments = load_vocal_windows(
        source, max_segments=max_segments, min_segments=min_segments
    )
    if timings is not None:
        timings["segmentationMs"] = round((time.perf_counter() - started) * 1000, 1)
        timings["segmentCount"] = len(segments)
    ecapa_path = Path(ecapa_checkpoint).resolve()
    singer_repo_path = Path(singer_repo).resolve()
    singer_root = Path(singer_checkpoint_root).resolve()
    if runtime is None:
        started = time.perf_counter()
        runtime = VocalEmbeddingRuntime(
            ecapa_checkpoint=ecapa_path,
            singer_repo=singer_repo_path,
            singer_checkpoint_root=singer_root,
            device=device,
            batch_size=batch_size,
        )
        if timings is not None:
            timings["runtimeLoadMs"] = round((time.perf_counter() - started) * 1000, 1)
    else:
        runtime.require_compatible(
            ecapa_checkpoint=ecapa_path,
            singer_repo=singer_repo_path,
            singer_checkpoint_root=singer_root,
            requested_device=device,
            batch_size=batch_size,
        )
    if cached_embeddings:
        ecapa = np.empty((len(windows), MODEL_CONTRACTS["ecapa"].dimension), dtype=np.float32)
        byol = np.empty((len(windows), MODEL_CONTRACTS["singer_byol"].dimension), dtype=np.float32)
        missing: list[int] = []
        for index, window in enumerate(windows):
            cached = cached_embeddings.get(hashlib.sha256(window.tobytes()).hexdigest())
            if cached is None:
                missing.append(index)
            else:
                ecapa[index], byol[index] = cached
        if missing:
            fresh_ecapa, fresh_byol = runtime.embed_windows(windows[missing], timings=timings)
            ecapa[missing], byol[missing] = fresh_ecapa, fresh_byol
        if timings is not None:
            timings["precomputedSegments"] = len(windows) - len(missing)
    else:
        ecapa, byol = runtime.embed_windows(windows, timings=timings)

    write_started = time.perf_counter()
    _atomic_save_npy(destinations["ecapa"], ecapa)
    _atomic_save_npy(destinations["singer_byol"], byol)
    metadata = {
        "schema_version": 1,
        "source_path": str(source),
        "source_sha256": _sha256(source),
        "analysis_sample_rate": ANALYSIS_SAMPLE_RATE,
        "window_seconds": WINDOW_SECONDS,
        "hop_seconds": HOP_SECONDS,
        "segment_count": len(segments),
        "segments": [asdict(segment) for segment in segments],
        "device": runtime.device,
        "models": {
            "ecapa": {
                "dimension": ecapa.shape[1],
                "checkpoint_sha256": runtime.ecapa_checkpoint_sha256,
                "preprocessing": MODEL_CONTRACTS["ecapa"].preprocessing,
            },
            "singer_byol": {
                "dimension": byol.shape[1],
                "checkpoint_sha256": runtime.singer_checkpoint_sha256,
                "official_repository": OFFICIAL_SINGER_BYOL_REPOSITORY,
                "expected_code_revision": OFFICIAL_SINGER_BYOL_REVISION,
                "checked_out_code_revision": runtime.checked_out_singer_revision,
                "preprocessing": MODEL_CONTRACTS["singer_byol"].preprocessing,
            },
        },
        "outputs": {
            "ecapa": destinations["ecapa"].name,
            "singer_byol": destinations["singer_byol"].name,
        },
    }
    temporary = destinations["metadata"].with_name(f".{destinations['metadata'].name}.tmp")
    temporary.write_text(
        json.dumps(metadata, ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    os.replace(temporary, destinations["metadata"])
    if timings is not None:
        timings["artifactWriteMs"] = round((time.perf_counter() - write_started) * 1000, 1)
    return metadata
