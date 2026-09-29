"""Offline, private recording diagnostics. Labels are evaluation metadata, never inference inputs.

Reads audio using FFmpeg without altering originals; writes derived evidence to ignored workspace.
No server requests, model downloads, or private recording copies in Git.
"""
from __future__ import annotations

import argparse
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import wave

import imageio_ffmpeg
import numpy as np
from scipy.signal import correlate


def decode(path: Path, rate: int) -> tuple[np.ndarray, str]:
    result = subprocess.run([imageio_ffmpeg.get_ffmpeg_exe(), "-hide_banner", "-i", str(path),
                             "-vn", "-ac", "1", "-ar", str(rate), "-f", "f32le", "pipe:1"],
                            capture_output=True, check=True)
    return np.frombuffer(result.stdout, dtype="<f4").astype(float), result.stderr.decode("utf-8", errors="replace")


def yin(window: np.ndarray, rate: int) -> tuple[float, float]:
    """Same fixed-span CMNDF/2x/3x selection as Android YinPitch (not a neural F0 model)."""
    max_tau, min_tau = min(int(rate / 65), len(window) - 2), max(2, int(rate / 1050))
    span = len(window) - max_tau
    energy = np.r_[0, np.cumsum(window ** 2)]
    cross = correlate(window, window[:span], mode="valid", method="fft")
    difference = np.maximum(0, energy[span] + energy[span:span + max_tau + 1] - energy[:max_tau + 1] - 2 * cross)
    running = np.cumsum(difference[1:])
    cmndf = np.r_[1, np.divide(difference[1:] * np.arange(1, max_tau + 1), running,
                             out=np.ones(max_tau), where=running > 1e-15)]
    tau = -1
    for candidate in range(min_tau, max_tau + 1):
        if cmndf[candidate] < .12:
            tau = candidate
            while tau + 1 <= max_tau and cmndf[tau + 1] < cmndf[tau]:
                tau += 1
            break
    if tau < 0:
        tau = min_tau + int(np.argmin(cmndf[min_tau:]))
    for multiple in (2, 3):
        if tau * multiple <= max_tau:
            start, end = max(min_tau, tau * multiple - 3), min(max_tau, tau * multiple + 3)
            local = start + int(np.argmin(cmndf[start:end + 1]))
            if cmndf[local] < cmndf[tau] - .04:
                tau = local
    refined = float(tau)
    if 1 < tau < max_tau:
        left, center, right = cmndf[tau - 1:tau + 2]
        denominator = 2 * (2 * center - right - left)
        if abs(denominator) > 1e-12:
            shift = (right - left) / denominator
            if abs(shift) <= 1:
                refined += shift
    return rate / refined, float(np.clip(1 - cmndf[tau], 0, 1))


def evidence(window: np.ndarray, rate: int, f0: float) -> dict:
    samples = window - window.mean()
    period = round(rate / f0)
    def correlation(lag):
        left, right = samples[lag:], samples[:-lag]
        return float(left @ right / np.sqrt((left @ left) * (right @ right) + 1e-12))
    one, two = correlation(period), correlation(2 * period)
    tapered = samples * np.hanning(len(samples))
    time = np.arange(len(samples)) / rate
    frequencies = np.arange(1, 7)[:, None] * f0 + np.array([-4, 0, 4])[None, :]
    amplitudes = np.abs(np.exp(-2j * np.pi * frequencies[:, :, None] * time) @ tapered).max(axis=1)
    low, high = np.linalg.norm(amplitudes[:2]), np.linalg.norm(amplitudes[2:])
    richness = float(20 * np.log10((high + 1e-8) / (low + 1e-8)))
    h1h2 = float(20 * np.log10((amplitudes[0] + 1e-8) / (amplitudes[1] + 1e-8)))
    h2h4 = float(20 * np.log10((amplitudes[1] + 1e-8) / (amplitudes[3] + 1e-8)))
    return {"richnessDb": richness, "h1H2Db": h1h2, "h2H4Db": h2h4,
            "periodicity": float(np.clip(one, 0, 1)), "doublePeriodAdvantage": float(np.clip(two - one, 0, 1))}


def analyze(path: Path, label: str, rate: int = 16000) -> dict:
    audio, source_info = decode(path, rate)
    hop, width = 320, 1024
    rows = []
    for start in range(0, len(audio) - width, hop):
        window = audio[start:start + width]
        rms = float(np.sqrt(np.mean(window ** 2)))
        low_rms = min(float(np.sqrt(np.mean(part ** 2))) for part in np.split(window, 2))
        f0, clarity = yin(window, rate) if rms >= .001 else (0., 0.)
        center = start + width // 2
        row = {"t": center / rate, "f0": f0, "clarity": clarity, "rms": rms, "rmsLow": low_rms}
        if f0 > 0 and 65 <= f0 <= 1050 and clarity >= .8 and low_rms >= .003:
            half = round(.09 * rate)
            if half <= center < len(audio) - half:
                row.update(evidence(audio[center-half:center+half], rate, f0))
                row["midi"] = float(69 + 12 * np.log2(f0 / 440))
        rows.append(row)
    active = [r for r in rows if r["rmsLow"] >= .003]
    voiced = [r for r in rows if "richnessDb" in r]
    metrics = {key: {"p10": float(np.percentile([r[key] for r in voiced], 10)),
                     "median": float(np.median([r[key] for r in voiced])),
                     "p90": float(np.percentile([r[key] for r in voiced], 90))}
               for key in ("f0", "midi", "richnessDb", "h1H2Db", "h2H4Db", "periodicity", "doublePeriodAdvantage")} if voiced else {}
    return {"file": path.name, "label": label, "sha256": hashlib.sha256(path.read_bytes()).hexdigest(),
            "sourceInfo": source_info, "analysisRate": rate, "durationSeconds": len(audio)/rate,
            "peak": float(np.max(np.abs(audio))), "clippedRatio": float(np.mean(np.abs(audio) >= .999)),
            "activeSeconds": len(active)*hop/rate, "usableSeconds": len(voiced)*hop/rate,
            "untrackedActiveRatio": 1 - len(voiced)/len(active) if active else 1,
            "metrics": metrics, "frames": rows}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--sample", action="append", required=True, help="label=absolute audio path; repeat")
    parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "workspace/voice-calibration/evidence.json")
    parser.add_argument("--emit-jvm-fixtures", action="store_true", help="write ignored PCM/frames for exact Android acoustic tests")
    args = parser.parse_args()
    reports = []
    for sample in args.sample:
        label, path = sample.split("=", 1)
        report = analyze(Path(path), label)
        reports.append(report)
        if args.emit_jvm_fixtures:
            args.output.parent.mkdir(parents=True, exist_ok=True)
            signal, _ = decode(Path(path), report["analysisRate"])
            stem = Path(path).stem
            with wave.open(str(args.output.parent / f"{stem}.wav"), "wb") as wav:
                wav.setnchannels(1)
                wav.setsampwidth(2)
                wav.setframerate(report["analysisRate"])
                wav.writeframes((np.clip(signal, -1, 32767/32768) * 32768).astype("<i2").tobytes())
            with (args.output.parent / f"{stem}.csv").open("w", newline="", encoding="utf-8") as stream:
                writer = csv.writer(stream)
                writer.writerow(["t", "f0", "clarity", "rms", "rmsLow"])
                writer.writerows([[r[key] for key in ("t", "f0", "clarity", "rms", "rmsLow")] for r in report["frames"]])
        print(json.dumps({k:v for k,v in report.items() if k not in ("frames", "sourceInfo")}, ensure_ascii=False), flush=True)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps({"scope":"one-speaker exploratory recordings, not independent validation", "recordings":reports},
                                     ensure_ascii=False, indent=2)+"\n", encoding="utf-8")
    if args.emit_jvm_fixtures:
        (args.output.parent / "manifest.tsv").write_text("\n".join(f"{Path(r['file']).stem}\t{r['label']}" for r in reports)+"\n", encoding="utf-8")


if __name__ == "__main__":
    main()
