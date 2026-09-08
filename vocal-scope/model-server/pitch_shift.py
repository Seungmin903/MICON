"""Key (pitch) shift for the separated accompaniment — karaoke-style 키 조절.

Uses pedalboard.PitchShift (Rubber Band engine): tempo is preserved, only the pitch moves.

Rendering strategy: one PitchShift instance slows down super-linearly with input length
(30 s ≈ 1.4 s, 60 s ≈ 9.7 s, 4 min ≈ 43 s on this machine), so the track is cut into
~20 s chunks that each get a fresh instance, processed in a small thread pool, and joined
with a short crossfade. Chunk output is sample-aligned to its input (~6 ms constant latency,
identical for every instance), so seams line up in time. Each chunk is rendered with extra
context on both sides that is discarded afterwards: a fresh instance wobbles (±6 %) for the
first ~150 ms and its flushed tail bulges (up to +45 %) for the last ~200 ms. The crossfade
exponent adapts to the measured correlation of the two renders in the overlap, so the seam
keeps the original loudness whether the phases happen to agree or not.

Rendering runs in a *separate* Python process without torch: once torch is imported into a
process, pedalboard/Rubber Band slows down 5-15x there (measured 40 s vs 11 s single-threaded on
the same file), and the engine process always has torch loaded. The subprocess path falls back to
in-process rendering if it fails for any reason.

Results are cached next to the original as ``instrumental_key{+N|-N}.wav``.
"""
from __future__ import annotations

import os
import subprocess
import sys
import threading
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path

import numpy as np
import soundfile as sf

MIN_SEMITONES = -6
MAX_SEMITONES = 6
CHUNK_SECONDS = 20.0
CROSSFADE_SECONDS = 0.25
CONTEXT_SECONDS = 0.5  # rendered on both sides of every chunk, then thrown away
MAX_WORKERS = max(1, min(4, (os.cpu_count() or 2) // 2))

_LOCKS: dict[str, threading.Lock] = {}
_LOCKS_GUARD = threading.Lock()


def clamp_semitones(value: int) -> int:
    return max(MIN_SEMITONES, min(MAX_SEMITONES, int(value)))


def shifted_name(stem: str, semitones: int) -> str:
    if semitones == 0:
        return f"{stem}.wav"
    return f"{stem}_key{semitones:+d}.wav"


def _lock_for(path: Path) -> threading.Lock:
    key = str(path)
    with _LOCKS_GUARD:
        if key not in _LOCKS:
            _LOCKS[key] = threading.Lock()
        return _LOCKS[key]


def _shift_chunk(chunk: np.ndarray, sample_rate: int, semitones: float) -> np.ndarray:
    from pedalboard import Pedalboard, PitchShift  # lazy import keeps engine start-up light

    board = Pedalboard([PitchShift(semitones=semitones)])
    out = np.asarray(board(np.ascontiguousarray(chunk), sample_rate), dtype=np.float32)
    if out.shape[1] != chunk.shape[1]:  # defensive: pedalboard returns equal length, but never trust it blindly
        fixed = np.zeros_like(chunk)
        n = min(out.shape[1], chunk.shape[1])
        fixed[:, :n] = out[:, :n]
        out = fixed
    return out


def _crossfade_exponent(tail: np.ndarray, head: np.ndarray) -> float:
    """Pick p in [1, 2] for sin^p / cos^p gains so that the summed overlap keeps unit power.

    p = 2 is equal-power (right when the two renders are uncorrelated), p = 1 is linear
    (right when they are identical). In between we solve g^2 (2 + 2 rho) = 1 at the midpoint.
    """
    a = tail.mean(axis=0)
    b = head.mean(axis=0)
    if a.std() < 1e-6 or b.std() < 1e-6:
        return 2.0
    rho = float(np.corrcoef(a, b)[0, 1])
    if not np.isfinite(rho):
        return 2.0
    rho = min(1.0, max(0.0, rho))
    exponent = np.log(1.0 / np.sqrt(2.0 + 2.0 * rho)) / np.log(np.sqrt(0.5))
    return float(min(2.0, max(1.0, exponent)))


def pitch_shift_array(audio: np.ndarray, sample_rate: int, semitones: int, workers: int = MAX_WORKERS) -> np.ndarray:
    """``audio`` is (channels, samples) float32. Returns the same shape, shifted by ``semitones``."""
    channels, total = audio.shape
    chunk = int(CHUNK_SECONDS * sample_rate)
    fade = int(CROSSFADE_SECONDS * sample_rate)
    context = int(CONTEXT_SECONDS * sample_rate)
    if total <= chunk + 2 * (fade + context):
        return _shift_chunk(audio, sample_rate, float(semitones))

    # spans = the part of each chunk that survives. Consecutive spans overlap by exactly `fade`
    # samples — the region where piece i fades out while piece i+1 fades in.
    spans = []
    for start in range(0, total, chunk):
        spans.append((start, min(total, start + chunk + fade)))
    if len(spans) > 1 and spans[-1][1] - spans[-1][0] <= fade:  # tiny leftover: fold into the previous span
        spans.pop()
        spans[-1] = (spans[-1][0], total)

    def render(span: tuple[int, int]) -> np.ndarray:
        begin, end = span
        pad_before = min(context, begin)
        pad_after = min(context, total - end)
        out = _shift_chunk(audio[:, begin - pad_before:end + pad_after], sample_rate, float(semitones))
        return out[:, pad_before:pad_before + (end - begin)]

    with ThreadPoolExecutor(max_workers=max(1, workers)) as pool:
        rendered = list(pool.map(render, spans))

    theta = np.linspace(0.0, np.pi / 2, fade, dtype=np.float32)
    sin_t = np.clip(np.sin(theta), 0.0, 1.0)
    cos_t = np.clip(np.cos(theta), 0.0, 1.0)
    exponents = [
        _crossfade_exponent(rendered[index - 1][:, -fade:], rendered[index][:, :fade])
        for index in range(1, len(spans))
    ]

    output = np.zeros((channels, total), dtype=np.float32)
    for index, ((begin, end), piece) in enumerate(zip(spans, rendered)):
        length = end - begin
        gain = np.ones(length, dtype=np.float32)
        if index > 0:
            gain[:fade] = sin_t ** exponents[index - 1]
        if index < len(spans) - 1:
            gain[length - fade:] = cos_t ** exponents[index]
        output[:, begin:end] += piece[:, :length] * gain
    return output


def _render_to(source: Path, target: Path, semitones: int) -> None:
    audio, sample_rate = sf.read(source, dtype="float32", always_2d=True)  # (samples, channels)
    shifted = pitch_shift_array(np.ascontiguousarray(audio.T), int(sample_rate), semitones)
    peak = float(np.max(np.abs(shifted))) if shifted.size else 0.0
    if peak > 0.999:
        shifted = shifted / peak * 0.999
    tmp = target.with_name(target.stem + ".tmp.wav")
    sf.write(tmp, shifted.T, sample_rate, subtype="PCM_16")
    tmp.replace(target)


def _render_isolated(source: Path, target: Path, semitones: int) -> bool:
    """Render in a fresh interpreter (no torch loaded). Returns False if that did not work."""
    env = dict(os.environ, PYTHONIOENCODING="utf-8", PYTHONUTF8="1")
    command = [sys.executable, str(Path(__file__).resolve()), str(source), str(target), str(semitones)]
    try:
        completed = subprocess.run(command, cwd=str(Path(__file__).resolve().parent), env=env, capture_output=True, timeout=600)
    except (OSError, subprocess.TimeoutExpired):
        return False
    return completed.returncode == 0 and target.exists()


def pitch_shift_file(source: Path, semitones: int, isolated: bool = True) -> Path:
    """Return a WAV of ``source`` shifted by ``semitones`` (tempo preserved). Cached on disk."""
    semitones = clamp_semitones(semitones)
    if semitones == 0:
        return source
    target = source.with_name(shifted_name(source.stem, semitones))
    with _lock_for(target):  # two browsers asking for the same key at once → render once
        if target.exists() and target.stat().st_mtime >= source.stat().st_mtime:
            return target
        if not (isolated and _render_isolated(source, target, semitones)):
            _render_to(source, target, semitones)
    return target


if __name__ == "__main__":  # subprocess entry: python pitch_shift.py <source.wav> <target.wav> <semitones>
    _source, _target, _semitones = Path(sys.argv[1]), Path(sys.argv[2]), int(sys.argv[3])
    _render_to(_source, _target, clamp_semitones(_semitones))
