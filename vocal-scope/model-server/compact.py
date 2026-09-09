"""Storage policy for finished jobs: keep one compressed accompaniment, drop every intermediate WAV.

Before (6-min song, 314 MB):  input.wav · separated/…/{vocals,no_vocals}.wav · vocals.wav · instrumental.wav (61 MB each)
After  (≈ 7 MB):              instrumental.opus (≈ 5 MB) · pitch_frames.npz · vocal.mid · karaoke.mid · job.json

What is removed can be re-created: MusicXML is generated on demand from the MIDI + notes (app.py), key-shifted
accompaniments are rendered from the Opus file on demand (`key_shifted_opus`), and the vocal stem can be
re-separated from the original mp3.  Lyrics re-alignment needs the vocal stem → after compaction it requires a
re-analysis (app.py returns 422 with that message).

CLI (apply to existing jobs):  python compact.py <job_dir> [<job_dir> ...]   (globs allowed)
"""
from __future__ import annotations

import glob
import json
import shutil
import subprocess
import sys
import time
from pathlib import Path
from typing import Any

import imageio_ffmpeg

OPUS_BITRATE = "128k"
INTERMEDIATE_FILES = ("input.wav", "vocals.wav", "instrumental.wav", "vocal.musicxml", "karaoke.musicxml")
INTERMEDIATE_DIRS = ("separated",)


def _ffmpeg(*args: str) -> None:
    exe = imageio_ffmpeg.get_ffmpeg_exe()
    completed = subprocess.run([exe, "-hide_banner", "-loglevel", "error", "-y", *args], capture_output=True, text=True, encoding="utf-8", errors="replace")
    if completed.returncode != 0:
        raise RuntimeError(f"ffmpeg failed ({completed.returncode}): {completed.stderr.strip()[:400]}")


def encode_opus(source_wav: Path, target: Path, bitrate: str = OPUS_BITRATE) -> Path:
    """WAV → Ogg/Opus (VBR, music profile). ~5 MB per 6 minutes at 128 kbps."""
    tmp = target.with_suffix(".tmp.opus")
    _ffmpeg("-i", str(source_wav), "-c:a", "libopus", "-b:a", bitrate, "-vbr", "on", "-application", "audio", str(tmp))
    if not tmp.exists() or tmp.stat().st_size < 1024:
        raise RuntimeError(f"opus output missing or empty: {tmp}")
    tmp.replace(target)
    return target


def decode_to_wav(source: Path, target: Path) -> Path:
    """Any audio → 44.1 kHz stereo PCM_16 WAV (input for the Rubber Band key shift)."""
    _ffmpeg("-i", str(source), "-ar", "44100", "-ac", "2", "-c:a", "pcm_s16le", str(target))
    return target


def _tree_size(path: Path) -> int:
    if path.is_file():
        return path.stat().st_size
    return sum(item.stat().st_size for item in path.rglob("*") if item.is_file())


def compact_job(job_dir: Path, result: dict[str, Any] | None = None, delete_original: bool = False) -> dict[str, Any]:
    """Apply the storage policy to one job directory and patch `result['artifacts']` in place.
    Idempotent: a second run only removes what is still there."""
    job_dir = Path(job_dir)
    freed = 0
    instrumental_wav = job_dir / "instrumental.wav"
    instrumental_opus = job_dir / "instrumental.opus"
    if instrumental_wav.exists() and not instrumental_opus.exists():
        encode_opus(instrumental_wav, instrumental_opus)
    for name in INTERMEDIATE_FILES:
        path = job_dir / name
        if path.exists():
            freed += path.stat().st_size
            path.unlink()
    for name in INTERMEDIATE_DIRS:
        path = job_dir / name
        if path.exists():
            freed += _tree_size(path)
            shutil.rmtree(path, ignore_errors=True)
    for path in job_dir.glob("instrumental_key*.wav"):  # cached key renders → re-rendered as opus on demand
        freed += path.stat().st_size
        path.unlink()
    if delete_original:
        for path in job_dir.glob("original.*"):
            freed += path.stat().st_size
            path.unlink()
    if result is not None:
        artifacts = result.setdefault("artifacts", {})
        artifacts.pop("vocals", None)
        if instrumental_opus.exists():
            artifacts["instrumental"] = instrumental_opus.name
        result["compact"] = {
            "appliedAt": time.time(),
            "policy": "instrumental-opus-128k; no stems; musicxml on demand",
            "instrumentalBytes": instrumental_opus.stat().st_size if instrumental_opus.exists() else None,
            "freedBytes": freed,
        }
    return {"freedBytes": freed, "instrumentalBytes": instrumental_opus.stat().st_size if instrumental_opus.exists() else None}


def key_shifted_opus(job_dir: Path, semitones: int) -> Path:
    """Key-shifted accompaniment from the Opus file, cached as instrumental_key±N.opus (≈ 5 MB instead of 63 MB)."""
    from pitch_shift import pitch_shift_file, shifted_name

    job_dir = Path(job_dir)
    source_opus = job_dir / "instrumental.opus"
    if semitones == 0:
        return source_opus
    target = job_dir / f"instrumental_key{semitones:+d}.opus"
    if target.exists():
        return target
    work = job_dir / "_keyshift"
    work.mkdir(exist_ok=True)
    try:
        wav = decode_to_wav(source_opus, work / "instrumental.wav")
        shifted_wav = pitch_shift_file(wav, semitones)  # work/instrumental_key±N.wav
        encode_opus(shifted_wav, target)
    finally:
        shutil.rmtree(work, ignore_errors=True)
    return target


def compact_paths(patterns: list[str]) -> None:
    dirs: list[Path] = []
    for pattern in patterns:
        dirs += [Path(p) for p in glob.glob(pattern)]
    total_freed = 0
    for job_dir in sorted(dirs):
        job_file = job_dir / "job.json"
        if not job_file.exists():
            continue
        job = json.loads(job_file.read_text(encoding="utf-8"))
        before = _tree_size(job_dir)
        summary = compact_job(job_dir, job.get("result"), delete_original=bool(job.get("catalog")))
        job_file.write_text(json.dumps(job, ensure_ascii=False, indent=2), encoding="utf-8")
        after = _tree_size(job_dir)
        total_freed += before - after
        print(f"{job_dir.name:14s} {before/1e6:7.1f} MB → {after/1e6:5.1f} MB  (opus {((summary['instrumentalBytes'] or 0)/1e6):.1f} MB)", flush=True)
    print(f"freed {total_freed/1e9:.2f} GB over {len(dirs)} jobs")


if __name__ == "__main__":
    compact_paths(sys.argv[1:])
