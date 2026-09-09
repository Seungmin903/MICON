"""Reference (original singer) vs performance (user) comparison → pitch / timing judgement per note + score.

Both recordings go through the *same* analysis pipeline (separation → VAD → CREPE/pYIN, engine.run_pipeline),
so this module only consumes two finished job directories:

  reference   : job.json (result.notes / karaokeNotes / beatGrid / tempoBpm) + pitch_frames.npz
  performance : job.json + pitch_frames.npz

Steps
1. Both 10 ms F0 curves are binned to a coarser alignment grid (50 ms; 100 ms for very long pairs).
2. Transposition (karaoke key change, octave-down singing): every pitch-class shift (−6…+5) is tried with a
   coarse octave-folded DTW; the cheapest alignment wins, its median raw pitch gap fixes the octave.
3. Subsequence DTW (own vectorised implementation; performance = query, reference = full song) with an
   octave-folded pitch cost aligns the two; the user may sing only a part of the song, start at any offset,
   and the recording may be longer than the song.
4. Every reference note (karaoke melody if present, otherwise the transcription) is mapped through the warping
   path: sung pitch = median of the user's F0 inside the mapped range → cents error / sharp / flat / wrong /
   missed; onset deviation = local deviation of the warping path from its rolling-median trend (+ 10 ms
   refinement on the user's F0) → early / late.
5. Duration-weighted pitch and timing scores (0–100), grade, per-measure heatmap, worst segments, and the two
   curves on the reference time axis for the overlay chart.

CLI (model venv):
  python compare_engine.py --reference <job dir> --performance <job dir> [--no-transpose] [--no-octave] [--out file]
"""

from __future__ import annotations

import argparse
import json
import math
import warnings
from pathlib import Path
from typing import Any

import numpy as np

NOTE_NAMES = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]

SOURCE_FRAME = 0.01           # pitch_frames.npz grid
ALIGN_HOP = 0.05              # alignment grid
ALIGN_HOP_COARSE = 0.10       # used when perf_frames × ref_frames would exceed MAX_CELLS
MAX_CELLS = 14_000_000
SEARCH_CELLS = 3_000_000      # coarse grid budget for the transposition search (12 DTW passes)
COST_SATURATION = 4.0         # semitones: ≥ this is "a wrong note", saturate the cost
COST_VOICING_MISMATCH = 0.7
COST_BOTH_SILENT = 0.10
WARP_PENALTY = 0.25           # additive cost of a pure insertion / deletion step
TREND_WINDOW_SECONDS = 16.0   # robust local-linear fit window for the timing trend (tempo may drift a cappella)
TREND_STEP_SECONDS = 0.5
TREND_OUTLIER_SECONDS = 0.25
ONSET_SEARCH_SECONDS = 0.30
ONSET_NEAR_SEMITONES = 0.6
ONSET_MIN_FRAMES = 5
ONSET_MAX_SHIFT = 0.25        # ignore a refined onset that moves more than this from the DTW mapping

# --- judgement thresholds
PITCH_GOOD_CENTS = 45.0       # within a quarter tone (-ish) of the original singer → "good"
PITCH_OFF_CENTS = 75.0        # > good and ≤ off → "slightly" sharp/flat (still sharp/flat status)
PITCH_WRONG_CENTS = 150.0
MISSED_VOICED_RATIO = 0.35    # user voiced for less than this share of the note → missed
TIMING_GOOD_SECONDS = 0.12
TIMING_OFF_SECONDS = 0.45
SHORT_HOLD_RATIO = 0.55       # held less than this share of a long reference note → "short"

# --- scoring curves
PITCH_FULL_CENTS = 30.0       # ≤ → 100 %
PITCH_ZERO_CENTS = 150.0      # ≥ → 0 %
TIMING_FULL_SECONDS = 0.06
TIMING_ZERO_SECONDS = 0.40
WEIGHT_PITCH = 0.65
WEIGHT_TIMING = 0.35
GRADES = [(95, "S"), (88, "A"), (78, "B"), (65, "C"), (50, "D")]


def midi_to_name(midi: int) -> str:
    return f"{NOTE_NAMES[int(midi) % 12]}{int(midi) // 12 - 1}"


def _fold(delta: np.ndarray | float) -> np.ndarray | float:
    """Fold a semitone difference to the nearest octave: result in (-6, 6]."""
    return delta - 12.0 * np.round(np.asarray(delta, dtype=float) / 12.0)


# ----------------------------------------------------------------------------- loading

def load_job(job_dir: Path) -> dict[str, Any]:
    job = json.loads((job_dir / "job.json").read_text(encoding="utf-8"))
    if job.get("status") != "complete" or not job.get("result"):
        raise RuntimeError(f"완료된 분석이 아닙니다: {job_dir.name}")
    return job


def load_frames(job_dir: Path, result: dict[str, Any]) -> tuple[np.ndarray, np.ndarray, float, str]:
    """10 ms MIDI curve (NaN when unvoiced) and voiced mask. Falls back to a piecewise-constant curve built
    from the transcribed notes for jobs analysed before pitch_frames.npz existed."""
    path = job_dir / "pitch_frames.npz"
    if path.exists():
        data = np.load(path)
        midi = np.asarray(data["midi"], dtype=np.float64)
        voiced = np.asarray(data["voiced"], dtype=bool)
        frame = float(data["frame_seconds"]) if "frame_seconds" in data else SOURCE_FRAME
        midi = np.where(voiced & np.isfinite(midi), midi, np.nan)
        return midi, voiced, frame, "pitch_frames"
    notes = result.get("notes") or []
    if not notes:
        raise RuntimeError("비교에 사용할 음표가 없습니다.")
    end = max(float(note["sourceEnd"]) for note in notes) + 1.0
    length = int(math.ceil(end / SOURCE_FRAME))
    midi = np.full(length, np.nan)
    for note in notes:
        lo = int(round(float(note["sourceStart"]) / SOURCE_FRAME))
        hi = int(round(float(note["sourceEnd"]) / SOURCE_FRAME))
        midi[lo:hi] = float(note.get("actualMidi", note["midi"]))
    return midi, np.isfinite(midi), SOURCE_FRAME, "notes"


def _bin_curve(midi: np.ndarray, voiced: np.ndarray, frame: float, hop: float) -> tuple[np.ndarray, np.ndarray]:
    """Bin a fine curve to the alignment hop: a bin is voiced when ≥ 40 % of its frames are, MIDI = median."""
    per_bin = max(1, int(round(hop / frame)))
    length = int(math.ceil(len(midi) / per_bin))
    padded = np.full(length * per_bin, np.nan)
    padded[:len(midi)] = midi
    grid = padded.reshape(length, per_bin)
    voiced_grid = np.zeros(length * per_bin, dtype=bool)
    voiced_grid[:len(voiced)] = voiced
    voiced_count = voiced_grid.reshape(length, per_bin).sum(axis=1)
    binned_voiced = voiced_count >= max(1, math.ceil(per_bin * 0.4))
    with warnings.catch_warnings():
        warnings.simplefilter("ignore", RuntimeWarning)
        binned_midi = np.nanmedian(grid, axis=1)
    binned_midi = np.where(binned_voiced & np.isfinite(binned_midi), binned_midi, np.nan)
    return binned_midi, binned_voiced & np.isfinite(binned_midi)


# ----------------------------------------------------------------------------- transposition

def search_transposition(
    ref_midi10: np.ndarray, ref_voiced10: np.ndarray, ref_frame: float,
    perf_midi10: np.ndarray, perf_voiced10: np.ndarray, perf_frame: float,
    hop: float,
) -> dict[str, Any]:
    """Semitones to subtract from the performance so it sits in the reference key (karaoke key change,
    octave-down singing). Every pitch-class shift (−6…+5) is tried with a coarse octave-folded DTW; the
    cheapest alignment wins and its median raw pitch gap fixes the octave."""
    search_hop = hop
    while (len(ref_midi10) * ref_frame / search_hop) * (len(perf_midi10) * perf_frame / search_hop) > SEARCH_CELLS:
        search_hop *= 2
    ref_b, ref_v = _bin_curve(ref_midi10, ref_voiced10, ref_frame, search_hop)
    perf_b, perf_v = _bin_curve(perf_midi10, perf_voiced10, perf_frame, search_hop)
    costs: dict[int, float] = {}
    paths: dict[int, np.ndarray] = {}
    for shift in range(-6, 6):
        path, cost = align(ref_b, ref_v, perf_b, perf_v, shift, octave_tolerant=True)
        costs[shift] = cost
        paths[shift] = path
    ordered = sorted(costs.items(), key=lambda item: item[1])
    best_shift, best_cost = ordered[0]
    runner_cost = ordered[1][1] if len(ordered) > 1 else best_cost
    confidence = float(np.clip((runner_cost - best_cost) / max(best_cost, 1e-6), 0.0, 1.0))
    path = paths[best_shift]
    pair_ref = ref_b[path[:, 1]]
    pair_perf = perf_b[path[:, 0]]
    valid = np.isfinite(pair_ref) & np.isfinite(pair_perf)
    gap = float(np.median(pair_perf[valid] - pair_ref[valid])) if valid.sum() >= 10 else float(best_shift)
    semitones = best_shift + 12 * int(round((gap - best_shift) / 12.0))
    return {
        "semitones": int(semitones),
        "pitchClassShift": int(best_shift),
        "medianGap": round(gap, 2),
        "confidence": round(confidence, 3),
        "searchHop": search_hop,
        "candidateCosts": {str(shift): round(cost, 4) for shift, cost in costs.items()},
    }


# ----------------------------------------------------------------------------- alignment

def _cost_matrix(ref_midi: np.ndarray, ref_voiced: np.ndarray, perf_midi: np.ndarray, perf_voiced: np.ndarray, transpose: int, octave_tolerant: bool) -> np.ndarray:
    ref = np.nan_to_num(ref_midi, nan=0.0)[None, :]
    perf = (np.nan_to_num(perf_midi, nan=0.0) - transpose)[:, None]
    delta = perf - ref
    distance = np.abs(_fold(delta)) if octave_tolerant else np.abs(delta)
    cost = np.minimum(distance, COST_SATURATION) / COST_SATURATION
    both = perf_voiced[:, None] & ref_voiced[None, :]
    either = perf_voiced[:, None] ^ ref_voiced[None, :]
    return np.where(both, cost, np.where(either, COST_VOICING_MISMATCH, COST_BOTH_SILENT)).astype(np.float64)


def subsequence_dtw(cost: np.ndarray, penalty: float) -> tuple[np.ndarray, np.ndarray]:
    """Subsequence DTW with steps (1,1), (1,0), (0,1); the two non-diagonal steps cost `penalty` extra.
    Rows (the performance) must be consumed completely, the match may start and end anywhere in the columns
    (the reference). Own implementation: librosa's `dtw(subseq=True)` silently transposes the matrix when the
    query is longer than the reference and returns the path with swapped columns.

    Row recursion: D[i, j] = C[i, j] + min(D[i-1, j-1], D[i-1, j] + p, D[i, j-1] + p). Unrolling the horizontal
    step gives D[i, j] = T_j + cummin_k≤j(base_k − T_k) with T the prefix sum of (C + p) along the row and
    base_k the best non-horizontal entry, so every row is a handful of vectorised numpy ops."""
    rows, columns = cost.shape
    accumulated = np.empty((rows, columns), dtype=np.float64)
    step = cost + penalty
    for i in range(rows):
        if i == 0:
            base = cost[0].copy()
        else:
            previous = accumulated[i - 1]
            base = cost[i] + np.minimum(np.concatenate(([np.inf], previous[:-1])), previous + penalty)
        prefix = np.cumsum(step[i])
        accumulated[i] = prefix + np.minimum.accumulate(base - prefix)
    # backtrack from the cheapest end column
    i, j = rows - 1, int(np.argmin(accumulated[-1]))
    path = [(i, j)]
    while i > 0 or j > 0:
        target = accumulated[i, j] - cost[i, j]
        candidates: list[tuple[float, int, int]] = []
        if i > 0 and j > 0:
            candidates.append((abs(accumulated[i - 1, j - 1] - target), i - 1, j - 1))
        if i > 0:
            candidates.append((abs(accumulated[i - 1, j] + penalty - target), i - 1, j))
        if j > 0:
            candidates.append((abs(accumulated[i, j - 1] + penalty - target), i, j - 1))
        if i == 0:
            # first row: a horizontal move only continues if it really produced this cell; otherwise this is the start
            if not candidates or candidates[0][0] > 1e-6:
                break
        gap, i, j = min(candidates, key=lambda item: item[0])
        path.append((i, j))
    return accumulated, np.asarray(path[::-1], dtype=np.int64)


def align(
    ref_midi: np.ndarray, ref_voiced: np.ndarray, perf_midi: np.ndarray, perf_voiced: np.ndarray,
    transpose: int, octave_tolerant: bool,
) -> tuple[np.ndarray, float]:
    """Subsequence DTW (performance rows fully consumed, any reference subsequence). Returns the warping path
    as [[perf_index, ref_index], ...] (ascending) and the normalised path cost."""
    cost = _cost_matrix(ref_midi, ref_voiced, perf_midi, perf_voiced, transpose, octave_tolerant)
    accumulated, path = subsequence_dtw(cost, WARP_PENALTY)
    total = float(accumulated[path[-1, 0], path[-1, 1]])
    return path, total / max(1, len(path))


def _path_maps(path: np.ndarray, ref_length: int) -> tuple[np.ndarray, np.ndarray]:
    """Per reference bin: mean matched performance bin (NaN outside the covered range) and coverage mask."""
    mapped = np.full(ref_length, np.nan)
    sums = np.zeros(ref_length)
    counts = np.zeros(ref_length)
    np.add.at(sums, path[:, 1], path[:, 0])
    np.add.at(counts, path[:, 1], 1)
    covered = counts > 0
    mapped[covered] = sums[covered] / counts[covered]
    return mapped, covered


def robust_trend(times: np.ndarray, offsets: np.ndarray) -> np.ndarray:
    """Expected (performance − reference) offset at each `times`: local least-squares line over a
    ±TREND_WINDOW/2 window with one outlier-rejection pass, so short timing slips do not move the trend
    while slow a-cappella tempo drift does."""
    if len(times) < 4:
        return offsets.copy()
    half = TREND_WINDOW_SECONDS / 2.0
    anchors = np.arange(times[0], times[-1] + TREND_STEP_SECONDS, TREND_STEP_SECONDS)
    values = np.empty(len(anchors))
    for index, anchor in enumerate(anchors):
        lo = np.searchsorted(times, anchor - half)
        hi = np.searchsorted(times, anchor + half, side="right")
        x = times[lo:hi]
        y = offsets[lo:hi]
        if len(x) < 4:
            values[index] = float(np.median(y)) if len(y) else 0.0
            continue
        slope, intercept = np.polyfit(x, y, 1)
        residual = y - (slope * x + intercept)
        keep = np.abs(residual - np.median(residual)) <= TREND_OUTLIER_SECONDS
        if keep.sum() >= 4 and keep.sum() < len(x):
            slope, intercept = np.polyfit(x[keep], y[keep], 1)
        values[index] = slope * anchor + intercept
    return np.interp(times, anchors, values)


def _interp_map(mapped: np.ndarray, covered: np.ndarray, hop: float):
    """Monotone reference-seconds → performance-seconds function over the covered range."""
    ref_index = np.flatnonzero(covered)
    ref_seconds = ref_index * hop
    perf_seconds = np.maximum.accumulate(mapped[covered]) * hop
    lo, hi = float(ref_seconds[0]), float(ref_seconds[-1])

    def convert(seconds: float) -> float:
        clamped = min(max(seconds, lo), hi)
        return float(np.interp(clamped, ref_seconds, perf_seconds))

    return convert, lo, hi


# ----------------------------------------------------------------------------- note judgement

def _reference_notes(result: dict[str, Any]) -> tuple[list[dict[str, Any]], str]:
    karaoke = result.get("karaokeNotes") or []
    if len(karaoke) >= 10:
        return sorted(karaoke, key=lambda note: float(note["sourceStart"])), "karaoke"
    return sorted(result.get("notes") or [], key=lambda note: float(note["sourceStart"])), "source"


def _pitch_status(cents: float | None, missed: bool) -> str:
    if missed or cents is None:
        return "missed"
    if abs(cents) <= PITCH_GOOD_CENTS:
        return "good"
    if abs(cents) > PITCH_WRONG_CENTS:
        return "wrong"
    return "sharp" if cents > 0 else "flat"


def _timing_status(deviation: float | None) -> str:
    if deviation is None:
        return "missed"
    if abs(deviation) <= TIMING_GOOD_SECONDS:
        return "good"
    if abs(deviation) > TIMING_OFF_SECONDS:
        return "off"
    return "late" if deviation > 0 else "early"


def _pitch_score(cents: float | None) -> float:
    if cents is None:
        return 0.0
    return float(np.clip(1.0 - max(0.0, abs(cents) - PITCH_FULL_CENTS) / (PITCH_ZERO_CENTS - PITCH_FULL_CENTS), 0.0, 1.0))


def _timing_score(deviation: float | None) -> float:
    if deviation is None:
        return 0.0
    return float(np.clip(1.0 - max(0.0, abs(deviation) - TIMING_FULL_SECONDS) / (TIMING_ZERO_SECONDS - TIMING_FULL_SECONDS), 0.0, 1.0))


def _refine_onset(perf_midi: np.ndarray, perf_voiced: np.ndarray, frame: float, around: float, target: float, octave_tolerant: bool) -> float | None:
    """Onset of the user's note nearest to the DTW-mapped time `around`: a transition from silence / another
    pitch (≥ 3 frames away from `target`) into ≥ ONSET_MIN_FRAMES frames within ±ONSET_NEAR_SEMITONES of it.
    None when no such transition exists nearby (the note is a continuation or the mapping stands)."""
    radius = int(round(ONSET_SEARCH_SECONDS / frame))
    center = int(round(around / frame))
    lo, hi = max(3, center - radius), min(len(perf_midi) - ONSET_MIN_FRAMES, center + radius)
    if hi <= lo:
        return None
    delta = perf_midi - target
    distance = np.abs(_fold(delta)) if octave_tolerant else np.abs(delta)
    near = perf_voiced & np.isfinite(distance) & (distance <= ONSET_NEAR_SEMITONES)
    best: float | None = None
    for index in range(lo, hi):
        if not near[index] or near[index - 1]:
            continue
        if near[index:index + ONSET_MIN_FRAMES].all() and not near[index - 3:index].any():
            candidate = index * frame
            if best is None or abs(candidate - around) < abs(best - around):
                best = candidate
    if best is not None and abs(best - around) > ONSET_MAX_SHIFT:
        return None
    return best


def judge_notes(
    ref_notes: list[dict[str, Any]],
    convert,
    covered_range: tuple[float, float],
    trend_offset,
    perf_midi: np.ndarray,
    perf_voiced: np.ndarray,
    frame: float,
    transpose: int,
    octave_tolerant: bool,
) -> list[dict[str, Any]]:
    judged: list[dict[str, Any]] = []
    lo, hi = covered_range
    for index, note in enumerate(ref_notes):
        start = float(note["sourceStart"])
        end = float(note["sourceEnd"])
        # Judge against what the original singer actually sang (actualMidi), not the rounded score note.
        written = int(round(float(note["midi"])))
        actual = float(note.get("actualMidi", written))
        target = actual if abs(actual - written) <= 0.5 else float(written)
        entry: dict[str, Any] = {
            "index": index,
            "refStart": round(start, 3),
            "refEnd": round(end, 3),
            "midi": written,
            "targetMidi": round(target, 2),
            "name": note.get("name") or midi_to_name(written),
            "lyric": note.get("lyric"),
            "beat": note.get("scoreStartBeat"),
        }
        if end < lo or start > hi:
            entry.update(covered=False, pitchStatus="unsung", timingStatus="unsung")
            judged.append(entry)
            continue
        perf_start = convert(start)
        perf_end = max(convert(end), perf_start + 2 * frame)
        entry.update(covered=True, perfStart=round(perf_start, 3), perfEnd=round(perf_end, 3))

        frame_lo = int(round(perf_start / frame))
        frame_hi = max(frame_lo + 1, int(round(perf_end / frame)))
        window_voiced = perf_voiced[frame_lo:frame_hi]
        window_midi = perf_midi[frame_lo:frame_hi]
        voiced_ratio = float(window_voiced.mean()) if len(window_voiced) else 0.0
        voiced_values = window_midi[window_voiced & np.isfinite(window_midi)]
        missed = voiced_ratio < MISSED_VOICED_RATIO or len(voiced_values) < 3

        cents: float | None = None
        deviation: float | None = None
        if not missed:
            skip = int(len(voiced_values) * 0.15)  # drop the attack, like the transcription does
            body = voiced_values[skip:] if len(voiced_values) - skip >= 3 else voiced_values
            sung = float(np.median(body)) - transpose
            delta = sung - target
            folded = float(_fold(delta)) if octave_tolerant else delta
            cents = folded * 100.0
            entry["sungMidi"] = round(sung + transpose, 2)
            entry["sungInKey"] = round(target + folded, 2)
            entry["sungName"] = midi_to_name(int(round(target + folded)))
            entry["centsFromWritten"] = round((target + folded - written) * 100)
            entry["octaveShift"] = int(round((delta - folded) / 12.0)) if octave_tolerant else 0
            entry["stabilityCents"] = round(float(np.std(body)) * 100.0, 1) if len(body) >= 5 else None
            entry["heldRatio"] = round(voiced_ratio, 2)

            refined = _refine_onset(perf_midi, perf_voiced, frame, perf_start, target + transpose, octave_tolerant)
            actual_onset = refined if refined is not None else perf_start
            expected_onset = start + trend_offset(start)
            deviation = actual_onset - expected_onset
            entry["onsetRefined"] = refined is not None
            entry["onsetDeviation"] = round(deviation, 3)
            entry["short"] = bool(end - start >= 0.4 and voiced_ratio < SHORT_HOLD_RATIO)

        entry["centsError"] = None if cents is None else round(cents)
        entry["pitchStatus"] = _pitch_status(cents, missed)
        entry["timingStatus"] = _timing_status(deviation)
        entry["pitchScore"] = round(_pitch_score(cents), 3)
        entry["timingScore"] = round(_timing_score(deviation), 3)
        entry["weight"] = round(float(np.clip(end - start, 0.1, 1.5)), 3)
        judged.append(entry)
    return judged


# ----------------------------------------------------------------------------- aggregation

def _grade(score: float) -> str:
    for threshold, letter in GRADES:
        if score >= threshold:
            return letter
    return "F"


def summarise(notes: list[dict[str, Any]], meter: int) -> dict[str, Any]:
    covered = [note for note in notes if note.get("covered")]
    weights = np.array([note["weight"] for note in covered]) if covered else np.zeros(0)
    total_weight = float(weights.sum()) if len(weights) else 0.0

    def weighted(key: str) -> float:
        if total_weight <= 0:
            return 0.0
        return float(np.dot([note[key] for note in covered], weights) / total_weight) * 100.0

    pitch = weighted("pitchScore")
    timing = weighted("timingScore")
    overall = WEIGHT_PITCH * pitch + WEIGHT_TIMING * timing
    sung = [note for note in covered if note["pitchStatus"] != "missed"]
    cents_values = np.array([note["centsError"] for note in sung], dtype=float) if sung else np.zeros(0)
    deviations = np.array([note["onsetDeviation"] for note in sung], dtype=float) if sung else np.zeros(0)
    stability = [note["stabilityCents"] for note in sung if note.get("stabilityCents") is not None]

    pitch_counts = {key: 0 for key in ("good", "sharp", "flat", "wrong", "missed")}
    timing_counts = {key: 0 for key in ("good", "early", "late", "off", "missed")}
    for note in covered:
        pitch_counts[note["pitchStatus"]] += 1
        timing_counts[note["timingStatus"]] += 1

    measures: dict[int, dict[str, Any]] = {}
    for note in covered:
        beat = note.get("beat")
        measure = int(math.floor(float(beat) / meter)) if beat is not None else int(note["refStart"] // 2)
        bucket = measures.setdefault(measure, {"measure": measure + 1, "start": note["refStart"], "end": note["refEnd"], "notes": 0, "pitch": 0.0, "timing": 0.0, "weight": 0.0, "missed": 0, "sharp": 0, "flat": 0, "wrong": 0, "early": 0, "late": 0, "lyric": []})
        bucket["start"] = min(bucket["start"], note["refStart"])
        bucket["end"] = max(bucket["end"], note["refEnd"])
        bucket["notes"] += 1
        bucket["pitch"] += note["pitchScore"] * note["weight"]
        bucket["timing"] += note["timingScore"] * note["weight"]
        bucket["weight"] += note["weight"]
        for key in ("missed", "sharp", "flat", "wrong"):
            if note["pitchStatus"] == key:
                bucket[key] += 1
        for key in ("early", "late"):
            if note["timingStatus"] == key or (key == "late" and note["timingStatus"] == "off" and (note.get("onsetDeviation") or 0) > 0) or (key == "early" and note["timingStatus"] == "off" and (note.get("onsetDeviation") or 0) < 0):
                bucket[key] += 1
        if note.get("lyric"):
            bucket["lyric"].append(str(note["lyric"]))
    measure_rows = []
    for bucket in sorted(measures.values(), key=lambda item: item["measure"]):
        weight = bucket.pop("weight") or 1.0
        bucket["pitch"] = round(bucket["pitch"] / weight * 100)
        bucket["timing"] = round(bucket["timing"] / weight * 100)
        bucket["score"] = round(WEIGHT_PITCH * bucket["pitch"] + WEIGHT_TIMING * bucket["timing"])
        bucket["lyric"] = "".join(bucket["lyric"])[:24]
        measure_rows.append(bucket)

    return {
        "scores": {
            "pitch": round(pitch, 1),
            "timing": round(timing, 1),
            "overall": round(overall, 1),
            "grade": _grade(overall),
            "stabilityCents": round(float(np.mean(stability)), 1) if stability else None,
        },
        "pitchStats": {
            **pitch_counts,
            "meanCents": round(float(np.mean(cents_values))) if len(cents_values) else None,
            "medianCents": round(float(np.median(cents_values))) if len(cents_values) else None,
            "octaveShiftedNotes": sum(1 for note in sung if note.get("octaveShift")),
            "shortNotes": sum(1 for note in sung if note.get("short")),
        },
        "timingStats": {
            **timing_counts,
            "meanDeviationMs": round(float(np.mean(deviations)) * 1000) if len(deviations) else None,
            "absDeviationMs": round(float(np.mean(np.abs(deviations))) * 1000) if len(deviations) else None,
        },
        "measures": measure_rows,
    }


def find_issues(notes: list[dict[str, Any]], limit: int = 12) -> list[dict[str, Any]]:
    """Group consecutive problematic notes (≤ 1 s apart) into segments, worst first."""
    problems = [
        note for note in notes
        if note.get("covered") and (note["pitchStatus"] != "good" or note["timingStatus"] not in ("good",))
    ]
    segments: list[list[dict[str, Any]]] = []
    for note in problems:
        if segments and note["refStart"] - segments[-1][-1]["refEnd"] <= 1.0:
            segments[-1].append(note)
        else:
            segments.append([note])
    issues = []
    for group in segments:
        severity = sum((1.0 - note["pitchScore"]) * WEIGHT_PITCH + (1.0 - note["timingScore"]) * WEIGHT_TIMING for note in group)
        cents = [note["centsError"] for note in group if note.get("centsError") is not None and note["pitchStatus"] in ("sharp", "flat", "wrong")]
        deviations = [note["onsetDeviation"] for note in group if note.get("onsetDeviation") is not None and note["timingStatus"] in ("early", "late", "off")]
        missed = sum(1 for note in group if note["pitchStatus"] == "missed")
        parts = []
        if cents:
            mean_cents = float(np.mean(cents))
            direction = "높게" if mean_cents > 0 else "낮게"
            parts.append(f"{len(cents)}음 {direction} ({mean_cents:+.0f}¢)")
        if deviations:
            mean_dev = float(np.mean(deviations))
            parts.append(f"{len(deviations)}음 {'늦게' if mean_dev > 0 else '빨리'} ({mean_dev * 1000:+.0f} ms)")
        if missed:
            parts.append(f"{missed}음 안 부름")
        issues.append({
            "start": group[0]["refStart"],
            "end": group[-1]["refEnd"],
            "perfStart": group[0].get("perfStart"),
            "noteIndices": [note["index"] for note in group],
            "lyric": "".join(str(note["lyric"]) for note in group if note.get("lyric"))[:30] or None,
            "notes": len(group),
            "summary": " · ".join(parts) if parts else "불안정",
            "severity": round(severity, 2),
        })
    issues.sort(key=lambda item: item["severity"], reverse=True)
    return issues[:limit]


# ----------------------------------------------------------------------------- curves for the chart

def build_curves(
    ref_binned: np.ndarray, mapped: np.ndarray, covered: np.ndarray, perf_binned: np.ndarray,
    hop: float, transpose: int, octave_tolerant: bool,
) -> dict[str, Any]:
    index = np.flatnonzero(covered)
    if len(index) == 0:
        return {"hop": hop, "start": 0.0, "ref": [], "user": [], "perfTime": []}
    lo, hi = int(index[0]), int(index[-1]) + 1
    ref_values = ref_binned[lo:hi]
    user_values = np.full(hi - lo, np.nan)
    perf_time = np.full(hi - lo, np.nan)
    previous = np.nan
    for offset, j in enumerate(range(lo, hi)):
        if not covered[j]:
            continue
        perf_index = int(round(mapped[j]))
        perf_time[offset] = mapped[j] * hop
        if 0 <= perf_index < len(perf_binned) and np.isfinite(perf_binned[perf_index]):
            value = float(perf_binned[perf_index]) - transpose
            if octave_tolerant:
                anchor = ref_values[offset] if np.isfinite(ref_values[offset]) else previous
                if np.isfinite(anchor):
                    value = anchor + float(_fold(value - anchor))
            user_values[offset] = value
            previous = value

    def serialise(values: np.ndarray) -> list[float | None]:
        return [round(float(value), 2) if np.isfinite(value) else None for value in values]

    return {"hop": hop, "start": round(lo * hop, 3), "ref": serialise(ref_values), "user": serialise(user_values), "perfTime": serialise(perf_time)}


# ----------------------------------------------------------------------------- orchestration

def compare_jobs(
    reference_dir: Path,
    performance_dir: Path,
    allow_transpose: bool = True,
    octave_tolerant: bool = True,
    performance_job: dict[str, Any] | None = None,
) -> dict[str, Any]:
    """`performance_job` lets the server pass the freshly computed job (its job.json is still 'processing')."""
    reference = load_job(reference_dir)
    performance = performance_job if performance_job is not None else load_job(performance_dir)
    if not performance.get("result"):
        raise RuntimeError("부른 노래 분석 결과가 없습니다.")
    ref_result = reference["result"]
    perf_result = performance["result"]

    ref_midi10, ref_voiced10, ref_frame, ref_source = load_frames(reference_dir, ref_result)
    perf_midi10, perf_voiced10, perf_frame, _ = load_frames(performance_dir, perf_result)
    if not np.any(perf_voiced10):
        raise RuntimeError("부른 노래에서 유성 구간을 찾지 못했습니다.")

    # Trim the reference to the sung region (± 2 s) so silence before the first vocal is not aligned.
    ref_lo = max(0, int((float(ref_result.get("vocalStart", 0.0)) - 2.0) / ref_frame))
    ref_hi = min(len(ref_midi10), int((float(ref_result.get("vocalEnd", len(ref_midi10) * ref_frame)) + 2.0) / ref_frame))
    ref_midi10 = ref_midi10[:ref_hi]
    ref_voiced10 = ref_voiced10[:ref_hi]
    ref_midi10[:ref_lo] = np.nan
    ref_voiced10[:ref_lo] = False

    hop = ALIGN_HOP
    ref_binned, ref_bvoiced = _bin_curve(ref_midi10, ref_voiced10, ref_frame, hop)
    perf_binned, perf_bvoiced = _bin_curve(perf_midi10, perf_voiced10, perf_frame, hop)
    if len(ref_binned) * len(perf_binned) > MAX_CELLS:
        hop = ALIGN_HOP_COARSE
        ref_binned, ref_bvoiced = _bin_curve(ref_midi10, ref_voiced10, ref_frame, hop)
        perf_binned, perf_bvoiced = _bin_curve(perf_midi10, perf_voiced10, perf_frame, hop)

    if allow_transpose:
        transposition = search_transposition(ref_midi10, ref_voiced10, ref_frame, perf_midi10, perf_voiced10, perf_frame, hop)
    else:
        transposition = {"semitones": 0, "pitchClassShift": 0, "confidence": 1.0}
    transpose = int(transposition["semitones"])

    path, path_cost = align(ref_binned, ref_bvoiced, perf_binned, perf_bvoiced, transpose, octave_tolerant)
    mapped, covered = _path_maps(path, len(ref_binned))
    convert, covered_lo, covered_hi = _interp_map(mapped, covered, hop)

    # Refine the transposition from the aligned voiced pairs (integer semitones only).
    pairs_ref = ref_binned[path[:, 1]]
    pairs_perf = perf_binned[path[:, 0]]
    valid = np.isfinite(pairs_ref) & np.isfinite(pairs_perf)
    if allow_transpose and valid.sum() >= 20:
        gap = float(np.median(pairs_perf[valid] - pairs_ref[valid]))
        if octave_tolerant:
            gap = transpose + float(_fold(gap - transpose))
        refined = int(round(gap))
        if refined != transpose and abs(gap - refined) < 0.35:
            transpose = refined
            path, path_cost = align(ref_binned, ref_bvoiced, perf_binned, perf_bvoiced, transpose, octave_tolerant)
            mapped, covered = _path_maps(path, len(ref_binned))
            convert, covered_lo, covered_hi = _interp_map(mapped, covered, hop)

    pairs_ref = ref_binned[path[:, 1]]
    pairs_perf = perf_binned[path[:, 0]] - transpose
    valid = np.isfinite(pairs_ref) & np.isfinite(pairs_perf)
    pair_distance = np.abs(_fold(pairs_perf[valid] - pairs_ref[valid])) if octave_tolerant else np.abs(pairs_perf[valid] - pairs_ref[valid])
    matched_ratio = float((pair_distance <= 1.0).mean()) if valid.sum() else 0.0
    alignment_reliable = bool(valid.sum() >= 40 and matched_ratio >= 0.45)

    # Timing trend: rolling median of (performance − reference) seconds along the covered reference bins.
    covered_index = np.flatnonzero(covered)
    offsets = (mapped[covered] - covered_index) * hop
    trend_seconds = covered_index * hop
    trend = robust_trend(trend_seconds, offsets)

    def trend_offset(seconds: float) -> float:
        return float(np.interp(seconds, trend_seconds, trend))

    ref_notes, note_source = _reference_notes(ref_result)
    meter = int(str(ref_result.get("timeSignature", "4/4")).split("/")[0]) or 4
    judged = judge_notes(ref_notes, convert, (covered_lo, covered_hi), trend_offset, perf_midi10, perf_voiced10, perf_frame, transpose, octave_tolerant)
    summary = summarise(judged, meter)
    issues = find_issues(judged)
    curves = build_curves(ref_binned, mapped, covered, perf_binned, hop, transpose, octave_tolerant)

    covered_notes = [note for note in judged if note.get("covered")]
    sung_notes = [note for note in covered_notes if note["pitchStatus"] != "missed"]
    perf_lo, perf_hi = convert(covered_lo), convert(covered_hi)
    tempo_ratio = (perf_hi - perf_lo) / max(1e-3, covered_hi - covered_lo)
    ref_key = ref_result.get("key") or {}
    return {
        "referenceJobId": reference["id"],
        "performanceJobId": performance["id"],
        "options": {"allowTranspose": allow_transpose, "octaveTolerant": octave_tolerant},
        "transposeSemitones": transpose,
        "transposition": transposition,
        "referenceKey": ref_key.get("name"),
        "performanceKey": (perf_result.get("key") or {}).get("name"),
        "startOffsetSeconds": round(perf_lo - covered_lo, 2),
        "tempoRatio": round(tempo_ratio, 3),
        "coverage": {
            "refStart": round(covered_lo, 2),
            "refEnd": round(covered_hi, 2),
            "perfStart": round(perf_lo, 2),
            "perfEnd": round(perf_hi, 2),
            "notesTotal": len(judged),
            "notesCovered": len(covered_notes),
            "notesSung": len(sung_notes),
            "notesMissed": len(covered_notes) - len(sung_notes),
            "sungRatio": round(len(sung_notes) / len(judged), 3) if judged else 0.0,
            "coveredRatio": round(len(covered_notes) / len(judged), 3) if judged else 0.0,
        },
        **summary,
        "issues": issues,
        "notes": judged,
        "curves": curves,
        "alignment": {
            "method": "subsequence DTW · octave-folded pitch cost" if octave_tolerant else "subsequence DTW · absolute pitch cost",
            "hop": hop,
            "pathCost": round(path_cost, 4),
            "matchedRatio": round(matched_ratio, 3),
            "voicedPairs": int(valid.sum()),
            "reliable": alignment_reliable,
            "referenceFrames": ref_source,
            "noteSource": note_source,
            "referenceNoteCount": len(ref_notes),
        },
        "thresholds": {
            "pitchGoodCents": PITCH_GOOD_CENTS,
            "pitchWrongCents": PITCH_WRONG_CENTS,
            "timingGoodMs": TIMING_GOOD_SECONDS * 1000,
            "timingOffMs": TIMING_OFF_SECONDS * 1000,
            "weights": {"pitch": WEIGHT_PITCH, "timing": WEIGHT_TIMING},
        },
    }


def main() -> None:
    parser = argparse.ArgumentParser(description="Compare a sung performance with the analysed original.")
    parser.add_argument("--reference", required=True, type=Path)
    parser.add_argument("--performance", required=True, type=Path)
    parser.add_argument("--no-transpose", action="store_true")
    parser.add_argument("--no-octave", action="store_true", help="do not fold octave differences")
    parser.add_argument("--out", type=Path)
    args = parser.parse_args()
    result = compare_jobs(args.reference, args.performance, not args.no_transpose, not args.no_octave)
    if args.out:
        args.out.write_text(json.dumps(result, ensure_ascii=False, indent=1), encoding="utf-8")
    brief = {key: value for key, value in result.items() if key not in ("notes", "curves", "measures")}
    print(json.dumps(brief, ensure_ascii=False, indent=1))


if __name__ == "__main__":
    import sys

    sys.stdout.reconfigure(encoding="utf-8")  # type: ignore[union-attr]
    main()
