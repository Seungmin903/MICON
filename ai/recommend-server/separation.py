"""Vocal / accompaniment separation backends.

Default backend is **Mel-Band RoFormer** (Kimberley Jensen vocals checkpoint, MIT), which on the
public "multisong" benchmark scores ~10.98 dB vocals SDR versus ~8.38 dB for the HTDemucs model we
used before (ZFTurbo/Music-Source-Separation-Training, docs/pretrained_models.md).  A cleaner
vocal stem matters twice for VocalScope: the F0 paths agree more often, and the harmonic (register)
features are no longer biased by separation artefacts in the upper harmonics.

Backends
  melband   Mel-Band RoFormer via the `melband-roformer-infer` package (weights auto-downloaded to
            ~/.cache/melband-roformer-infer on first use).  fp16 autocast, 8 s chunks, overlap 2.
            On CUDA OOM the chunk is halved and retried; if it still fails we fall back to htdemucs.
  htdemucs  Previous behaviour (`python -m demucs.separate -n htdemucs`).

The model is kept on the CPU between jobs and only moved to the GPU while separating, because the
6 GB card is shared with CREPE, Whisper and wav2vec2 further down the pipeline.
"""

from __future__ import annotations

import contextlib
import io
import os
import subprocess
import sys
import threading
import time
from pathlib import Path
from typing import Any

import imageio_ffmpeg
import numpy as np
import soundfile as sf

DEFAULT_BACKEND = os.environ.get("VOCALSCOPE_SEPARATOR", "melband")
BACKENDS = ("melband", "htdemucs")
MELBAND_MODEL_KEY = "melband-roformer-kim-vocals"
MELBAND_LABEL = "mel-band-roformer-kim"
MELBAND_MIN_CHUNK = 88200  # 2 s: below this the transformer context is too short to be useful

_MELBAND_LOAD_LOCK = threading.Lock()
_MELBAND_RUN_LOCK = threading.Lock()
_MELBAND_CACHE: dict[str, Any] = {}


def normalize_backend(value: str | None) -> str:
    default = DEFAULT_BACKEND.strip().lower()
    if default not in BACKENDS:
        default = "melband"
    value = (value or default).strip().lower()
    return value if value in BACKENDS else default


# ----------------------------------------------------------------------------- HTDemucs (legacy)

def _separate_htdemucs(input_wav: Path, output_dir: Path, device: str) -> tuple[Path, Path]:
    command = [
        sys.executable, "-m", "demucs.separate", "-n", "htdemucs",
        "--two-stems", "vocals", "--device", device,
        "--segment", "7", "--overlap", "0.25", "--shifts", "1",
        "-o", str(output_dir), str(input_wav),
    ]
    environment = os.environ.copy()
    environment["PATH"] = str(Path(imageio_ffmpeg.get_ffmpeg_exe()).parent) + os.pathsep + environment.get("PATH", "")
    completed = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", env=environment)
    if completed.returncode != 0:
        raise RuntimeError(f"보컬 분리 실패: {completed.stderr[-900:] or completed.stdout[-900:]}")
    track_dir = output_dir / "htdemucs" / input_wav.stem
    vocals = track_dir / "vocals.wav"
    instrumental = track_dir / "no_vocals.wav"
    if not vocals.exists() or not instrumental.exists():
        raise RuntimeError("분리 모델이 출력 파일을 만들지 못했습니다.")
    return vocals, instrumental


# ----------------------------------------------------------------------------- Mel-Band RoFormer

def _load_melband() -> tuple[Any, Any]:
    """Model (on CPU) + ConfigDict, built once per process."""
    with _MELBAND_LOAD_LOCK:
        if "model" in _MELBAND_CACHE:
            return _MELBAND_CACHE["model"], _MELBAND_CACHE["config"]
        import torch
        import yaml
        from ml_collections import ConfigDict
        from mel_band_roformer import ensure_model_assets, get_model_from_config
        from mel_band_roformer.inference import SafeLoaderWithTuple

        # The package prints emoji progress lines; on a cp949 Windows console that raises
        # UnicodeEncodeError, so swallow its stdout/stderr entirely.
        with contextlib.redirect_stdout(io.StringIO()), contextlib.redirect_stderr(io.StringIO()):
            model_path, config_path = ensure_model_assets(MELBAND_MODEL_KEY)
        with open(config_path, encoding="utf-8") as handle:
            config = ConfigDict(yaml.load(handle, Loader=SafeLoaderWithTuple))
        model = get_model_from_config("mel_band_roformer", config)
        state = torch.load(model_path, map_location="cpu")
        model.load_state_dict(state)
        model.eval()
        _MELBAND_CACHE.update(model=model, config=config, model_path=str(model_path))
        return model, config


def _write_pcm16(path: Path, audio: np.ndarray, sample_rate: int) -> None:
    peak = float(np.max(np.abs(audio))) if audio.size else 0.0
    if peak > 0.999:  # Demucs "rescale" behaviour: avoid clipping the stem
        audio = audio / peak * 0.999
    sf.write(path, audio.T.astype(np.float32), sample_rate, subtype="PCM_16")


def _separate_melband_unlocked(input_wav: Path, output_dir: Path, device: str) -> tuple[Path, Path, dict[str, Any]]:
    import torch
    from mel_band_roformer import demix_track

    stage = time.perf_counter()
    model, config = _load_melband()
    model_load_ms = round((time.perf_counter() - stage) * 1000, 1)
    stage = time.perf_counter()
    mix, sample_rate = sf.read(input_wav, dtype="float32", always_2d=True)  # (samples, channels)
    if sample_rate != int(config.model.sample_rate):
        raise RuntimeError(f"분리 입력은 {config.model.sample_rate} Hz여야 합니다 (현재 {sample_rate} Hz).")
    if mix.shape[1] == 1:
        mix = np.repeat(mix, 2, axis=1)
    mixture = torch.from_numpy(mix.T.copy())  # (2, samples)
    audio_read_ms = round((time.perf_counter() - stage) * 1000, 1)

    target = torch.device("cuda" if device == "cuda" and torch.cuda.is_available() else "cpu")
    chunk_size = int(config.inference.chunk_size)
    original_chunk = chunk_size
    retries = 0
    demix_ms = 0.0
    model_to_device_ms = 0.0
    model_to_cpu_ms = 0.0
    try:
        stage = time.perf_counter()
        model.to(target)
        if target.type == "cuda":
            torch.cuda.synchronize()
        model_to_device_ms = round((time.perf_counter() - stage) * 1000, 1)
        while True:
            stage = time.perf_counter()
            try:
                config.inference.chunk_size = chunk_size
                with contextlib.redirect_stdout(io.StringIO()):  # progress spam → silence
                    sources, _ = demix_track(config, model, mixture.clone(), target)
                if target.type == "cuda":
                    torch.cuda.synchronize()
                demix_ms += (time.perf_counter() - stage) * 1000
                break
            except torch.cuda.OutOfMemoryError:
                demix_ms += (time.perf_counter() - stage) * 1000
                torch.cuda.empty_cache()
                if chunk_size // 2 < MELBAND_MIN_CHUNK:
                    raise
                chunk_size //= 2
                retries += 1
    finally:
        config.inference.chunk_size = original_chunk
        stage = time.perf_counter()
        model.to("cpu")
        if target.type == "cuda":
            torch.cuda.empty_cache()
        model_to_cpu_ms = round((time.perf_counter() - stage) * 1000, 1)

    stage = time.perf_counter()
    vocals = np.asarray(sources["vocals"], dtype=np.float32)  # (2, samples)
    vocals = vocals[:, : mix.shape[0]]
    if vocals.shape[1] < mix.shape[0]:
        vocals = np.pad(vocals, ((0, 0), (0, mix.shape[0] - vocals.shape[1])))
    instrumental = mix.T - vocals

    track_dir = output_dir / "melband" / input_wav.stem
    track_dir.mkdir(parents=True, exist_ok=True)
    vocals_path = track_dir / "vocals.wav"
    instrumental_path = track_dir / "no_vocals.wav"
    _write_pcm16(vocals_path, vocals, sample_rate)
    _write_pcm16(instrumental_path, instrumental, sample_rate)
    output_write_ms = round((time.perf_counter() - stage) * 1000, 1)
    info = {
        "inputSeconds": round(mix.shape[0] / sample_rate, 2),
        "chunkSeconds": round(chunk_size / sample_rate, 2),
        "oomRetries": retries,
        "device": target.type,
        "modelLoadMs": model_load_ms,
        "audioReadMs": audio_read_ms,
        "modelToDeviceMs": model_to_device_ms,
        "demixMs": round(demix_ms, 1),
        "modelToCpuMs": model_to_cpu_ms,
        "outputWriteMs": output_write_ms,
    }
    return vocals_path, instrumental_path, info


def _separate_melband(input_wav: Path, output_dir: Path, device: str) -> tuple[Path, Path, dict[str, Any]]:
    """Serialize use of the cached mutable model/config across concurrent API jobs."""
    with _MELBAND_RUN_LOCK:
        return _separate_melband_unlocked(input_wav, output_dir, device)


# ----------------------------------------------------------------------------- public API

def separate(input_wav: Path, output_dir: Path, device: str, backend: str | None = None,
             *, allow_fallback: bool = True) -> tuple[Path, Path, dict[str, Any]]:
    """Return (vocals.wav, instrumental.wav, info).  `info` records the backend actually used."""
    backend = normalize_backend(backend)
    started = time.perf_counter()
    if backend == "melband":
        try:
            vocals, instrumental, extra = _separate_melband(input_wav, output_dir, device)
            info = {"backend": "melband", "model": MELBAND_LABEL, "requested": "melband", **extra}
            info["seconds"] = round(time.perf_counter() - started, 1)
            return vocals, instrumental, info
        except Exception as error:  # noqa: BLE001 - any failure → keep the job alive with the old model
            if not allow_fallback:
                raise RuntimeError(f"Mel-Band 보컬 분리 실패: {type(error).__name__}: {str(error)[:200]}") from error
            fallback_reason = f"{type(error).__name__}: {str(error)[:200]}"
            started = time.perf_counter()
            vocals, instrumental = _separate_htdemucs(input_wav, output_dir, device)
            return vocals, instrumental, {
                "backend": "htdemucs", "model": "htdemucs", "requested": "melband",
                "fallbackReason": fallback_reason, "seconds": round(time.perf_counter() - started, 1),
            }
    vocals, instrumental = _separate_htdemucs(input_wav, output_dir, device)
    return vocals, instrumental, {"backend": "htdemucs", "model": "htdemucs", "requested": "htdemucs", "seconds": round(time.perf_counter() - started, 1)}
