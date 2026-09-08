"""Vocal melody extraction: CREPE-full probabilities → VAD → per-segment Viterbi + pYIN → notes.

Pipeline (all curves live on a shared 10 ms frame grid, hop 160 @ 16 kHz):

1. CREPE full probabilities are computed once for the whole vocal stem (GPU) and kept on CPU.
2. Voice activity detection (VAD) combines stem energy, vocal/mix energy ratio and CREPE
   periodicity with hysteresis, so nothing before the first sung note becomes a note.
3. Only voiced segments are decoded with Viterbi (own implementation: torchcrepe's decoder
   soft-maxes sigmoid outputs and over-smooths) and cross-checked with a narrow-range pYIN
   ("두 경로 F0 합의", "옥타브 불일치율"). pYIN never decides voicing.
4. A global tuning offset is removed *before* rounding to semitones.
5. Notes are segmented with hysteresis (vibrato-safe) plus syllable onsets, merged, aligned to
   a per-beat straight/triplet grid, and the key is estimated for sensible accidentals.
"""

from __future__ import annotations

import math
import time
from pathlib import Path
from typing import Any, Callable

import librosa
import numpy as np
import torch
import torchcrepe
from scipy.ndimage import binary_dilation, label as label_regions, median_filter

from register_engine import analyze_register

SAMPLE_RATE = 16000
HOP_LENGTH = 160
FRAME_SECONDS = HOP_LENGTH / SAMPLE_RATE
FRAME_LENGTH = 2048  # pYIN analysis window
RMS_FRAME_LENGTH = 640  # 40 ms energy window: keeps VAD boundaries within ±20 ms
FMIN = float(librosa.note_to_hz("C2"))
FMAX = float(librosa.note_to_hz("B6"))
PITCH_BINS = 360
CENTS_PER_BIN = 20.0
CENTS_OFFSET = 1997.3794084376191  # torchcrepe.convert.bins_to_cents
MIDI_OFFSET = 69.0 + 12.0 * math.log2(10.0 / 440.0)  # cents (re 10 Hz) / 100 → MIDI
CREPE_BATCH = 512

# --- VAD thresholds (fixed; see plan) ---
PERIODICITY_ENTER = 0.62
PERIODICITY_STAY = 0.45
ABS_DB_ENTER = -50.0
ABS_DB_STAY = -56.0
REL_DB_ENTER = -35.0
REL_DB_STAY = -42.0
HARD_DB_FLOOR = -66.0
RATIO_VETO = 0.06
RATIO_ENTER = 0.15
SEED_FRAMES = 3
GAP_FRAMES_SHORT = 6
GAP_FRAMES_ENERGETIC = 10
MIN_REGION_FRAMES = 12
PREROLL_FRAMES = 8
ANALYSIS_PAD_FRAMES = 5

# --- F0 comparison / segmentation ---
AGREEMENT_SEMITONES = 0.7
NOTE_CHANGE_SEMITONES = 0.6
NOTE_CHANGE_FRAMES = 5
NOTE_SMOOTH_FRAMES = 15
MIN_PIECE_FRAMES = 8
MERGE_GAP_SECONDS = 0.10


# ----------------------------------------------------------------------------- helpers

def _match_length(values: np.ndarray, length: int, fill: float = 0.0) -> np.ndarray:
    values = np.asarray(values).reshape(-1)
    if len(values) >= length:
        return values[:length]
    return np.pad(values, (0, length - len(values)), constant_values=fill)


def _voiced_regions(mask: np.ndarray) -> list[tuple[int, int]]:
    regions: list[tuple[int, int]] = []
    index = 0
    length = len(mask)
    while index < length:
        if not mask[index]:
            index += 1
            continue
        end = index
        while end < length and mask[end]:
            end += 1
        regions.append((index, end))
        index = end
    return regions


def _remove_short_regions(mask: np.ndarray, minimum: int) -> np.ndarray:
    result = mask.copy()
    for start, end in _voiced_regions(mask):
        if end - start < minimum:
            result[start:end] = False
    return result


def _fill_short_gaps(mask: np.ndarray, maximum: int) -> np.ndarray:
    result = mask.copy()
    for start, end in _voiced_regions(~mask):
        if start > 0 and end < len(mask) and end - start <= maximum:
            result[start:end] = True
    return result


def _fill_gaps_energy_aware(mask: np.ndarray, energetic: np.ndarray, short: int, long: int) -> np.ndarray:
    """Fill gaps ≤ short frames always; gaps ≤ long frames only if the gap keeps energy (consonants)."""
    result = mask.copy()
    for start, end in _voiced_regions(~mask):
        if start == 0 or end == len(mask):
            continue
        width = end - start
        if width <= short or (width <= long and bool(np.all(energetic[start:end]))):
            result[start:end] = True
    return result


def _load_audio(path: Path, sr: int = SAMPLE_RATE) -> np.ndarray:
    audio, _ = librosa.load(str(path), sr=sr, mono=True)
    return audio.astype(np.float32)


def _frame_rms(audio: np.ndarray) -> np.ndarray:
    rms = librosa.feature.rms(y=audio, frame_length=RMS_FRAME_LENGTH, hop_length=HOP_LENGTH, center=True)[0]
    return median_filter(rms, size=3, mode="nearest")


# ----------------------------------------------------------------------------- CREPE

def _frequency_to_bin(frequency: float, ceil: bool = False) -> int:
    cents = 1200.0 * math.log2(frequency / 10.0)
    value = (cents - CENTS_OFFSET) / CENTS_PER_BIN
    return int(math.ceil(value)) if ceil else int(math.floor(value))


def _crepe_probabilities(audio: np.ndarray, device: str) -> np.ndarray:
    """Whole-track CREPE-full per-bin sigmoid probabilities, shape [frames, 360], float32 on CPU."""
    audio_tensor = torch.from_numpy(np.ascontiguousarray(audio, dtype=np.float32)).unsqueeze(0)
    chunks: list[np.ndarray] = []
    with torch.inference_mode():
        generator = torchcrepe.preprocess(
            audio_tensor, SAMPLE_RATE, HOP_LENGTH, batch_size=CREPE_BATCH, device=device, pad=True
        )
        for frames in generator:
            probabilities = torchcrepe.infer(frames, model="full", device=device)
            chunks.append(probabilities.float().cpu().numpy())
    probabilities = np.concatenate(chunks, axis=0)
    low = max(0, _frequency_to_bin(FMIN))
    high = min(PITCH_BINS, _frequency_to_bin(FMAX, ceil=True))
    probabilities[:, :low] = 0.0
    probabilities[:, high:] = 0.0
    return probabilities


def _local_average_midi(probabilities: np.ndarray, bins: np.ndarray) -> np.ndarray:
    """Dither-free sub-bin pitch: probability-weighted cents over ±4 bins around the decoded bin."""
    offsets = np.arange(-4, 5)
    index = np.clip(bins[:, None] + offsets[None, :], 0, PITCH_BINS - 1)
    weights = np.take_along_axis(probabilities, index, axis=1)
    cents = index * CENTS_PER_BIN + CENTS_OFFSET
    averaged = (weights * cents).sum(axis=1) / (weights.sum(axis=1) + 1e-9)
    return averaged / 100.0 + MIDI_OFFSET


_TRANSITION: np.ndarray | None = None


def _transition_matrix() -> np.ndarray:
    global _TRANSITION
    if _TRANSITION is None:
        xx, yy = np.meshgrid(np.arange(PITCH_BINS), np.arange(PITCH_BINS))
        transition = np.maximum(12 - np.abs(xx - yy), 0).astype(np.float64)
        _TRANSITION = transition / transition.sum(axis=1, keepdims=True)
    return _TRANSITION


def _viterbi_bins(probabilities: np.ndarray) -> np.ndarray:
    """Viterbi over CREPE bins with column-normalised emissions (original CREPE recipe)."""
    emission = probabilities.astype(np.float64).T  # [360, frames]
    emission = np.maximum(emission, 1e-8)
    emission = emission / emission.sum(axis=0, keepdims=True)
    return np.asarray(librosa.sequence.viterbi(emission, _transition_matrix()), dtype=np.int64)


# ----------------------------------------------------------------------------- VAD

def _detect_voice_activity(
    dbfs: np.ndarray,
    periodicity: np.ndarray,
    ratio: np.ndarray | None,
) -> tuple[np.ndarray, np.ndarray, dict[str, Any]]:
    length = len(dbfs)
    smooth_periodicity = median_filter(periodicity, size=5, mode="nearest")
    audible = dbfs > -90.0
    reference_db = float(np.percentile(dbfs[audible], 95)) if np.any(audible) else float(np.max(dbfs))
    energy_enter = ((dbfs >= ABS_DB_ENTER) | (dbfs >= reference_db + REL_DB_ENTER)) & (dbfs >= HARD_DB_FLOOR + 4)
    energy_stay = ((dbfs >= ABS_DB_STAY) | (dbfs >= reference_db + REL_DB_STAY)) & (dbfs >= HARD_DB_FLOOR)

    if ratio is not None:
        ratio_enter = ratio >= RATIO_ENTER
        ratio_veto = ratio < RATIO_VETO
    else:
        ratio_enter = np.ones(length, dtype=bool)
        ratio_veto = np.zeros(length, dtype=bool)

    strict = energy_enter & (smooth_periodicity >= PERIODICITY_ENTER) & ratio_enter
    seeds = _remove_short_regions(strict, SEED_FRAMES)
    lenient = energy_stay & (smooth_periodicity >= PERIODICITY_STAY) & ~ratio_veto
    labels, count = label_regions(lenient)
    keep = np.zeros(count + 1, dtype=bool)
    keep[np.unique(labels[seeds])] = True
    keep[0] = False
    voiced = keep[labels]

    voiced = _fill_gaps_energy_aware(voiced, energy_stay, GAP_FRAMES_SHORT, GAP_FRAMES_ENERGETIC)
    voiced = _remove_short_regions(voiced, MIN_REGION_FRAMES)
    # Energy-guided pre-roll: keep breathy/plosive attacks that carry energy but low periodicity.
    for start, _ in _voiced_regions(voiced):
        cursor = start - 1
        steps = 0
        while cursor >= 0 and steps < PREROLL_FRAMES and not voiced[cursor] and energy_stay[cursor] and not ratio_veto[cursor]:
            voiced[cursor] = True
            cursor -= 1
            steps += 1

    analysis = binary_dilation(voiced, iterations=ANALYSIS_PAD_FRAMES)
    stats = {
        "referenceDb": round(reference_db, 2),
        "voicedFrames": int(voiced.sum()),
        "strict": strict,
        "lenient": lenient,
        "energyEnter": energy_enter,
        "energyStay": energy_stay,
    }
    return voiced, analysis, stats


# ----------------------------------------------------------------------------- pYIN cross-check

def _pyin_segment(
    audio: np.ndarray,
    start: int,
    end: int,
    crepe_midi: np.ndarray,
) -> tuple[np.ndarray, np.ndarray, np.ndarray]:
    """pYIN on one segment with a range narrowed around the CREPE estimate. Returns arrays for [start, end)."""
    length = end - start
    pyin_midi = np.full(length, np.nan)
    pyin_voiced = np.zeros(length, dtype=bool)
    pyin_prob = np.zeros(length)
    finite = crepe_midi[np.isfinite(crepe_midi)]
    if len(finite) == 0:
        return pyin_midi, pyin_voiced, pyin_prob
    low = float(np.min(finite)) - 3.0
    high = float(np.max(finite)) + 3.0
    center = (low + high) / 2.0
    span = max(12.0, min(30.0, high - low))
    low, high = center - span / 2.0, center + span / 2.0
    fmin = max(50.0, float(librosa.midi_to_hz(low)))
    fmax = min(2000.0, float(librosa.midi_to_hz(high)))
    if fmax <= fmin * 1.2:
        fmax = fmin * 1.5

    pad = 7
    chunk = 1000
    for chunk_start in range(start, end, chunk):
        chunk_end = min(end, chunk_start + chunk)
        frame_lo = max(0, chunk_start - pad)
        sample_lo = frame_lo * HOP_LENGTH
        sample_hi = min(len(audio), (chunk_end + pad) * HOP_LENGTH)
        segment = audio[sample_lo:sample_hi]
        if len(segment) < FRAME_LENGTH:
            continue
        f0, flag, prob = librosa.pyin(
            segment,
            sr=SAMPLE_RATE,
            fmin=fmin,
            fmax=fmax,
            frame_length=FRAME_LENGTH,
            hop_length=HOP_LENGTH,
            center=True,
            fill_na=None,
        )
        for local_index in range(len(f0)):
            global_index = frame_lo + local_index
            if chunk_start <= global_index < chunk_end:
                target = global_index - start
                pyin_midi[target] = librosa.hz_to_midi(max(float(f0[local_index]), 1e-6))
                pyin_voiced[target] = bool(flag[local_index])
                pyin_prob[target] = float(prob[local_index])
    return pyin_midi, pyin_voiced, pyin_prob


def _compare_paths(crepe_midi: np.ndarray, pyin_midi: np.ndarray, pyin_voiced: np.ndarray) -> tuple[np.ndarray, np.ndarray]:
    delta = np.abs(crepe_midi - pyin_midi)
    valid = pyin_voiced & np.isfinite(delta)
    agree = valid & (delta <= AGREEMENT_SEMITONES)
    octave = valid & (np.abs(delta - 12.0) <= AGREEMENT_SEMITONES)
    return agree, octave


def _octave_correct(crepe_midi: np.ndarray, pyin_midi: np.ndarray, octave_mask: np.ndarray) -> np.ndarray:
    if not np.any(octave_mask):
        return crepe_midi
    corrected = crepe_midi.copy()
    running = median_filter(crepe_midi, size=31, mode="nearest")
    for index in np.flatnonzero(octave_mask):
        if abs(pyin_midi[index] - running[index]) < 1.0:
            corrected[index] += 12.0 * np.sign(pyin_midi[index] - crepe_midi[index])
    return corrected


# ----------------------------------------------------------------------------- tuning

def _estimate_tuning(midi: np.ndarray, periodicity: np.ndarray, voiced: np.ndarray) -> float:
    """Circular mean of fractional semitone on stable voiced frames, in semitones. 0 when unreliable."""
    candidates = voiced & np.isfinite(midi)
    if candidates.sum() < 20:
        return 0.0
    smoothed = median_filter(np.nan_to_num(midi, nan=0.0), size=5, mode="nearest")
    stable = candidates & (np.abs(midi - smoothed) < 0.2)
    if stable.sum() < 20:
        stable = candidates
    fraction = midi[stable] - np.round(midi[stable])
    weights = np.maximum(periodicity[stable], 1e-3)
    angles = 2.0 * np.pi * fraction
    vector = np.sum(weights * np.exp(1j * angles)) / np.sum(weights)
    if abs(vector) < 0.2:
        return 0.0
    offset = float(np.angle(vector) / (2.0 * np.pi))
    return offset


# ----------------------------------------------------------------------------- beats

def _estimate_beats(audio: np.ndarray, sample_rate: int = SAMPLE_RATE) -> dict[str, Any]:
    hop = 512
    onset = librosa.onset.onset_strength(y=audio, sr=sample_rate, hop_length=hop)
    tempo, beat_frames = librosa.beat.beat_track(onset_envelope=onset, sr=sample_rate, hop_length=hop, units="frames")
    tempo_value = float(np.asarray(tempo).reshape(-1)[0]) if np.asarray(tempo).size else 120.0
    beat_frames = np.asarray(beat_frames, dtype=int)
    beat_times = librosa.frames_to_time(beat_frames, sr=sample_rate, hop_length=hop)
    if len(beat_times) >= 4:
        median_interval = float(np.median(np.diff(beat_times)))
        if median_interval > 0:
            tempo_value = 60.0 / median_interval
    raw_tempo = tempo_value if math.isfinite(tempo_value) and tempo_value > 0 else None
    if not math.isfinite(tempo_value) or not 45 <= tempo_value <= 240:
        tempo_value = 120.0
    while tempo_value < 60:
        tempo_value *= 2
    while tempo_value > 190:
        tempo_value /= 2
    tempo_candidates = sorted({
        int(round(candidate))
        for candidate in ((raw_tempo or tempo_value) * 0.5, raw_tempo or tempo_value, (raw_tempo or tempo_value) * 2.0, tempo_value)
        if 30 <= candidate <= 300
    })

    meter = 4
    downbeat_index = 0
    meter_confidence = 0.0
    meter_candidates = [{"timeSignature": "3/4", "confidence": 0.0}, {"timeSignature": "4/4", "confidence": 0.0}]
    if len(beat_frames) >= 12:
        strengths = onset[np.clip(beat_frames, 0, max(0, len(onset) - 1))]
        candidate_scores: dict[int, tuple[float, int]] = {}
        for candidate in (3, 4):
            phase_means = [float(np.mean(strengths[phase::candidate])) for phase in range(candidate)]
            phase = int(np.argmax(phase_means))
            selected = phase_means[phase]
            others = [value for idx, value in enumerate(phase_means) if idx != phase]
            contrast = (selected - float(np.mean(others))) / (float(np.mean(strengths)) + 1e-6)
            candidate_scores[candidate] = (contrast, phase)
        score3, phase3 = candidate_scores[3]
        score4, phase4 = candidate_scores[4]
        positive_total = max(1e-6, max(0.0, score3) + max(0.0, score4))
        meter_candidates = [
            {"timeSignature": "3/4", "confidence": round(max(0.0, score3) / positive_total, 3)},
            {"timeSignature": "4/4", "confidence": round(max(0.0, score4) / positive_total, 3)},
        ]
        if score3 > max(0.20, score4 * 1.35):
            meter, phase, meter_confidence = 3, phase3, max(0.0, min(1.0, score3))
        else:
            meter, phase, meter_confidence = 4, phase4, max(0.0, min(1.0, score4))
        downbeat_index = phase

    if len(beat_times) < 2:
        duration = len(audio) / sample_rate
        interval = 60.0 / tempo_value
        beat_times = np.arange(0.0, duration + interval, interval)
        downbeat_index = 0

    return {
        "tempo": int(round(tempo_value)),
        "tempo_raw": round(float(raw_tempo), 2) if raw_tempo is not None else None,
        "tempo_candidates": tempo_candidates,
        "beat_times": np.asarray(beat_times, dtype=float),
        "meter": meter,
        "downbeat_index": downbeat_index,
        "meter_confidence": round(meter_confidence, 3),
        "meter_candidates": meter_candidates,
    }


def _time_to_beat(time_value: float, beat_times: np.ndarray) -> float:
    positions = np.arange(len(beat_times), dtype=float)
    if len(beat_times) < 2:
        return time_value * 2.0
    if time_value < beat_times[0]:
        interval = max(1e-3, beat_times[1] - beat_times[0])
        return (time_value - beat_times[0]) / interval
    if time_value > beat_times[-1]:
        interval = max(1e-3, beat_times[-1] - beat_times[-2])
        return positions[-1] + (time_value - beat_times[-1]) / interval
    return float(np.interp(time_value, beat_times, positions))


def _quantize(value: float, divisions: int) -> float:
    return round(value * divisions) / divisions


# ----------------------------------------------------------------------------- onsets

def _syllable_onsets(audio: np.ndarray, dbfs: np.ndarray, voiced: np.ndarray) -> set[int]:
    length = len(dbfs)
    envelope = librosa.onset.onset_strength(y=audio, sr=SAMPLE_RATE, hop_length=HOP_LENGTH)
    envelope = _match_length(envelope, length, 0.0)
    # p99 over voiced frames: p95 over-fires on sparse onsets, max under-fires on dense lyrics.
    scale = float(np.percentile(envelope[voiced], 99)) if np.any(voiced) else float(np.max(envelope))
    normalised = envelope / (scale + 1e-9)
    onset_frames = librosa.onset.onset_detect(
        onset_envelope=normalised,
        sr=SAMPLE_RATE,
        hop_length=HOP_LENGTH,
        backtrack=True,
        normalize=False,
        pre_max=3,
        post_max=3,
        pre_avg=12,
        post_avg=12,
        delta=0.30,
        wait=7,
        units="frames",
    )
    onsets = {int(frame) for frame in onset_frames if 0 <= int(frame) < length}

    # RMS dips of ≥ 6 dB within ±80 ms (repeated same-pitch syllables).
    if length > 17:
        padded = np.pad(dbfs, (8, 8), mode="edge")
        windows = np.lib.stride_tricks.sliding_window_view(padded, 17)
        is_min = dbfs <= windows.min(axis=1) + 1e-6
        left = windows[:, :9].max(axis=1) - dbfs
        right = windows[:, 8:].max(axis=1) - dbfs
        dips = np.flatnonzero(is_min & (left >= 6.0) & (right >= 6.0) & voiced)
        onsets.update(int(frame) for frame in dips)
    return onsets


# ----------------------------------------------------------------------------- note segmentation

def _anchor_center(raw: np.ndarray, start: int, end: int) -> float:
    segment = raw[start + 3:min(start + 12, end)]
    if len(segment) < 3:
        segment = raw[start:end]
    return float(np.median(segment))


def _refine_boundary(raw: np.ndarray, start: int, deviation_index: int, center: float, new_center: float) -> int:
    length = len(raw)
    midpoint = (center + new_center) / 2.0
    direction = 1.0 if new_center >= center else -1.0
    low = max(start + 1, deviation_index - 15)
    high = min(length - 1, deviation_index + 10)
    crossing = deviation_index
    for index in range(low, high + 1):
        window = raw[index:min(length, index + 3)]
        if len(window) and np.all((window - midpoint) * direction > 0):
            crossing = index
            break
    if deviation_index - crossing > 10:  # long glide → split at its middle
        crossing = (crossing + deviation_index) // 2
    return int(min(max(crossing, start + 1), length - 1))


def _hysteresis_bounds(raw: np.ndarray) -> list[tuple[int, int]]:
    length = len(raw)
    if length < 2:
        return [(0, length)]
    smooth = median_filter(raw, size=min(NOTE_SMOOTH_FRAMES, length), mode="nearest")
    bounds: list[tuple[int, int]] = []
    start = 0
    center = _anchor_center(raw, 0, length)
    index = 1
    while index < length:
        if abs(smooth[index] - center) <= NOTE_CHANGE_SEMITONES:
            index += 1
            continue
        excursion_end = index
        while excursion_end < length and abs(smooth[excursion_end] - center) > NOTE_CHANGE_SEMITONES:
            excursion_end += 1
        sustained = (excursion_end - index) >= NOTE_CHANGE_FRAMES or (excursion_end == length and excursion_end - index >= 3)
        if not sustained:
            index = excursion_end
            continue
        new_center = _anchor_center(raw, index, excursion_end)
        boundary = _refine_boundary(raw, start, index, center, new_center)
        bounds.append((start, boundary))
        start = boundary
        center = new_center
        index = max(boundary, index) + 1
    bounds.append((start, length))
    return [(a, b) for a, b in bounds if b > a]


def _segment_notes(
    midi_curve: np.ndarray,
    confidence: np.ndarray,
    voiced: np.ndarray,
    onset_frames: set[int],
    min_note_frames: int,
) -> list[dict[str, Any]]:
    onsets_sorted = np.asarray(sorted(onset_frames), dtype=int)
    notes: list[dict[str, Any]] = []
    for region_start, region_end in _voiced_regions(voiced):
        raw = midi_curve[region_start:region_end]
        if not np.all(np.isfinite(raw)):
            raw = np.where(np.isfinite(raw), raw, np.nanmedian(raw) if np.any(np.isfinite(raw)) else 60.0)
        pieces: list[tuple[int, int]] = []
        for left, right in _hysteresis_bounds(raw):
            cuts = [
                int(frame) - region_start
                for frame in onsets_sorted
                if left + MIN_PIECE_FRAMES <= int(frame) - region_start <= right - MIN_PIECE_FRAMES
            ]
            last = left
            for cut in cuts:
                if cut - last >= MIN_PIECE_FRAMES:
                    pieces.append((last, cut))
                    last = cut
            pieces.append((last, right))

        for left, right in pieces:
            if right - left < min_note_frames:
                continue
            body = raw[left + 4:right] if right - left >= 15 else raw[left:right]
            actual_midi = float(np.median(body))
            note_midi = int(round(actual_midi))
            if not 36 <= note_midi <= 95:
                continue
            absolute_left = region_start + left
            absolute_right = region_start + right
            average_confidence = float(np.mean(confidence[absolute_left:absolute_right]))
            if average_confidence < 0.2:
                continue
            start = absolute_left * FRAME_SECONDS
            end = absolute_right * FRAME_SECONDS
            notes.append({
                "sourceStart": start,
                "sourceEnd": end,
                "duration": end - start,
                "midi": note_midi,
                "actualMidi": actual_midi,
                "cents": (actual_midi - note_midi) * 100.0,
                "confidence": average_confidence,
            })

    merged: list[dict[str, Any]] = []
    for note in notes:
        if merged:
            previous = merged[-1]
            gap = note["sourceStart"] - previous["sourceEnd"]
            frame_a = int(previous["sourceEnd"] / FRAME_SECONDS)
            frame_b = int(note["sourceStart"] / FRAME_SECONDS)
            onset_between = bool(
                onsets_sorted.size
                and np.searchsorted(onsets_sorted, frame_a, side="left") < np.searchsorted(onsets_sorted, frame_b, side="right")
            )
            if note["midi"] == previous["midi"] and gap <= MERGE_GAP_SECONDS and not onset_between:
                total = previous["duration"] + note["duration"]
                previous["actualMidi"] = (previous["actualMidi"] * previous["duration"] + note["actualMidi"] * note["duration"]) / total
                previous["confidence"] = (previous["confidence"] * previous["duration"] + note["confidence"] * note["duration"]) / total
                previous["sourceEnd"] = note["sourceEnd"]
                previous["duration"] = previous["sourceEnd"] - previous["sourceStart"]
                previous["cents"] = (previous["actualMidi"] - previous["midi"]) * 100.0
                continue
        merged.append(note)
    return merged


# ----------------------------------------------------------------------------- beat alignment

def _align_notes_to_beats(notes: list[dict[str, Any]], beat_data: dict[str, Any]) -> list[dict[str, Any]]:
    if not notes:
        return notes
    beat_times = np.asarray(beat_data["beat_times"], dtype=float)
    meter = int(beat_data["meter"])
    downbeat_index = int(beat_data["downbeat_index"])
    raw_starts = [_time_to_beat(note["sourceStart"], beat_times) - downbeat_index for note in notes]
    raw_ends = [_time_to_beat(note["sourceEnd"], beat_times) - downbeat_index for note in notes]
    first_measure = math.floor(min(raw_starts) / meter) * meter
    raw_starts = [value - first_measure for value in raw_starts]
    raw_ends = [value - first_measure for value in raw_ends]

    # Per-beat grid: triplets only when ≥ 2 notes in the beat fit them clearly better.
    groups: dict[int, list[int]] = {}
    for index, start in enumerate(raw_starts):
        groups.setdefault(math.floor(start), []).append(index)
    grid: dict[int, int] = {}
    for beat, members in groups.items():
        straight_error = sum(abs(raw_starts[i] - _quantize(raw_starts[i], 4)) for i in members)
        triplet_error = sum(abs(raw_starts[i] - _quantize(raw_starts[i], 3)) for i in members)
        grid[beat] = 3 if len(members) >= 2 and triplet_error < 0.7 * straight_error else 4

    candidates: list[dict[str, Any]] = []
    for index, note in enumerate(notes):
        divisions = grid.get(math.floor(raw_starts[index]), 4)
        start = max(0.0, _quantize(raw_starts[index], divisions))
        end = max(start + 1.0 / divisions, _quantize(raw_ends[index], divisions))
        note["scoreStartBeat"] = start
        note["scoreEndBeat"] = end
        candidates.append(note)

    kept: list[dict[str, Any]] = []
    for note in candidates:
        if kept and note["scoreStartBeat"] - kept[-1]["scoreStartBeat"] < 0.2:
            if note["duration"] > kept[-1]["duration"]:
                kept[-1] = note
            continue
        kept.append(note)
    for current, following in zip(kept, kept[1:]):
        current["scoreEndBeat"] = min(current["scoreEndBeat"], following["scoreStartBeat"])
    # Re-anchor after quantisation so the score never opens with an empty bar.
    leading = math.floor(min(note["scoreStartBeat"] for note in kept) / meter) * meter
    if leading > 0:
        for note in kept:
            note["scoreStartBeat"] -= leading
            note["scoreEndBeat"] -= leading
    seconds_per_beat = 60.0 / beat_data["tempo"]
    for note in kept:
        note["scoreStart"] = note["scoreStartBeat"] * seconds_per_beat
        note["scoreEnd"] = note["scoreEndBeat"] * seconds_per_beat
    # Expose the exact seconds→score-beat mapping so later passes (karaoke grid) share the same bar lines.
    beat_data["offset_beats"] = float(downbeat_index + first_measure + leading)
    return kept


def make_time_to_beat(beat_grid: dict[str, Any]) -> Callable[[float], float]:
    beat_times = np.asarray(beat_grid["beatTimes"], dtype=float)
    offset = float(beat_grid["offsetBeats"])
    return lambda seconds: _time_to_beat(seconds, beat_times) - offset


# ----------------------------------------------------------------------------- key

_MAJOR_PROFILE = np.array([6.35, 2.23, 3.48, 2.33, 4.38, 4.09, 2.52, 5.19, 2.39, 3.66, 2.29, 2.88])
_MINOR_PROFILE = np.array([6.33, 2.68, 3.52, 5.38, 2.60, 3.53, 2.54, 4.75, 3.98, 2.69, 3.34, 3.17])
_MAJOR_SHARPS = {0: 0, 7: 1, 2: 2, 9: 3, 4: 4, 11: 5, 6: 6, 1: -5, 8: -4, 3: -3, 10: -2, 5: -1}
_SHARP_NAMES = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
_FLAT_NAMES = ["C", "D♭", "D", "E♭", "E", "F", "G♭", "G", "A♭", "A", "B♭", "B"]


def _estimate_key(notes: list[dict[str, Any]]) -> dict[str, Any]:
    histogram = np.zeros(12)
    for note in notes:
        histogram[note["midi"] % 12] += note["duration"]
    if histogram.sum() <= 0:
        return {"tonic": 0, "mode": "major", "sharps": 0, "name": "C major", "confidence": 0.0}
    best = (-2.0, 0, "major")
    scores = []
    for tonic in range(12):
        for mode, profile in (("major", _MAJOR_PROFILE), ("minor", _MINOR_PROFILE)):
            rotated = np.roll(profile, tonic)
            score = float(np.corrcoef(histogram, rotated)[0, 1])
            scores.append(score)
            if score > best[0]:
                best = (score, tonic, mode)
    score, tonic, mode = best
    relative_major = tonic if mode == "major" else (tonic + 3) % 12
    sharps = _MAJOR_SHARPS[relative_major]
    names = _FLAT_NAMES if sharps < 0 else _SHARP_NAMES
    ordered = sorted(scores, reverse=True)
    margin = ordered[0] - ordered[1] if len(ordered) > 1 else 0.0
    return {
        "tonic": tonic,
        "mode": mode,
        "sharps": sharps,
        "name": f"{names[tonic]} {mode}",
        "confidence": round(max(0.0, min(1.0, margin * 4.0)), 3),
    }


# ----------------------------------------------------------------------------- orchestration

def extract_vocal_melody(
    vocals_path: Path,
    mix_path: Path | None,
    device: str,
    debug: bool = False,
    return_frames: bool = False,
) -> dict[str, Any]:
    """`return_frames` adds `frames` (10 ms grid: tuned MIDI curve, voiced mask, confidence, dBFS) used by
    the performance comparison (compare_engine.py); the caller persists them to pitch_frames.npz."""
    timings: dict[str, float] = {}
    clock = time.perf_counter()

    vocal = _load_audio(vocals_path)
    if len(vocal) < SAMPLE_RATE // 2:
        raise RuntimeError("보컬 오디오가 너무 짧습니다.")
    if float(np.max(np.abs(vocal))) < 1e-6:
        raise RuntimeError("보컬 트랙이 무음입니다.")
    mix = _load_audio(mix_path) if mix_path is not None else None
    timings["load"] = time.perf_counter() - clock
    clock = time.perf_counter()

    target_device = "cuda" if device == "cuda" and torch.cuda.is_available() else "cpu"
    probabilities = _crepe_probabilities(vocal, target_device)
    length = len(probabilities)
    timings["crepe"] = time.perf_counter() - clock
    clock = time.perf_counter()

    rms_vocal = _match_length(_frame_rms(vocal), length, 0.0)
    dbfs = 20.0 * np.log10(np.maximum(rms_vocal, 1e-7))
    ratio: np.ndarray | None = None
    if mix is not None:
        rms_mix = _match_length(_frame_rms(mix), length, 0.0)
        ratio = np.clip(rms_vocal / (rms_mix + 1e-7), 0.0, 4.0)
    periodicity = probabilities.max(axis=1)
    argmax_bins = probabilities.argmax(axis=1)
    argmax_midi = _local_average_midi(probabilities, argmax_bins)

    voiced, analysis, vad_stats = _detect_voice_activity(dbfs, periodicity, ratio)
    timings["vad"] = time.perf_counter() - clock
    clock = time.perf_counter()
    if not np.any(voiced):
        raise RuntimeError("보컬 활동(유성 구간)을 감지하지 못했습니다.")

    beat_data = _estimate_beats(mix if mix is not None else vocal)
    timings["beats"] = time.perf_counter() - clock
    clock = time.perf_counter()

    midi_curve = argmax_midi.copy()
    pyin_midi_all = np.full(length, np.nan)
    pyin_voiced_all = np.zeros(length, dtype=bool)
    pyin_prob_all = np.zeros(length)
    agree_all = np.zeros(length, dtype=bool)
    octave_all = np.zeros(length, dtype=bool)
    viterbi_seconds = 0.0
    pyin_seconds = 0.0
    for start, end in _voiced_regions(analysis):
        tick = time.perf_counter()
        bins = _viterbi_bins(probabilities[start:end])
        segment_midi = _local_average_midi(probabilities[start:end], bins)
        viterbi_seconds += time.perf_counter() - tick
        tick = time.perf_counter()
        pyin_midi, pyin_voiced, pyin_prob = _pyin_segment(vocal, start, end, segment_midi)
        pyin_seconds += time.perf_counter() - tick
        agree, octave = _compare_paths(segment_midi, pyin_midi, pyin_voiced)
        segment_midi = _octave_correct(segment_midi, pyin_midi, octave)
        midi_curve[start:end] = segment_midi
        pyin_midi_all[start:end] = pyin_midi
        pyin_voiced_all[start:end] = pyin_voiced
        pyin_prob_all[start:end] = pyin_prob
        agree_all[start:end] = agree
        octave_all[start:end] = octave
    timings["viterbi"] = viterbi_seconds
    timings["pyin"] = pyin_seconds
    clock = time.perf_counter()

    compared = voiced & pyin_voiced_all
    compared_count = int(compared.sum())
    agreement_ratio = float(agree_all[compared].mean()) if compared_count else 0.0
    octave_ratio = float(octave_all[compared].mean()) if compared_count else 0.0
    confidence = np.clip(periodicity * 0.65 + pyin_prob_all * 0.35, 0.0, 1.0)

    tuning = _estimate_tuning(midi_curve, periodicity, voiced)
    midi_tuned = midi_curve - tuning
    onsets = _syllable_onsets(vocal, dbfs, voiced)
    sixteenth_seconds = 60.0 / beat_data["tempo"] / 4.0
    min_note_frames = int(max(7, min(10, round(0.8 * sixteenth_seconds / FRAME_SECONDS))))
    notes = _segment_notes(midi_tuned, confidence, voiced, onsets, min_note_frames)
    if not notes:
        raise RuntimeError("보컬에서 신뢰할 수 있는 단선율 음표를 찾지 못했습니다.")
    timings["notes"] = time.perf_counter() - clock
    clock = time.perf_counter()

    # --- register (진성/가성): harmonic features on the same 10 ms grid, using the *untuned* F0 in Hz
    register_frames: dict[str, np.ndarray] = {}
    try:
        f0_hz = np.where(voiced, 440.0 * np.power(2.0, (midi_curve - 69.0) / 12.0), np.nan)
        register_result = analyze_register(vocals_path, f0_hz, voiced, confidence, agree_all, notes, level_db=dbfs)
        register_summary = register_result["summary"]
        register_frames = register_result["frames"]
    except Exception as error:  # register is an annotation layer: never fail the transcription for it
        register_summary = {"error": f"{type(error).__name__}: {str(error)[:300]}"}
        for note in notes:
            note.setdefault("register", "unclear")
            note.setdefault("registerConfidence", 0.0)
    timings["register"] = time.perf_counter() - clock
    clock = time.perf_counter()

    notes = _align_notes_to_beats(notes, beat_data)
    key = _estimate_key(notes)
    timings["align"] = time.perf_counter() - clock

    for note in notes:
        note["sourceStart"] = round(note["sourceStart"], 4)
        note["sourceEnd"] = round(note["sourceEnd"], 4)
        note["start"] = note["sourceStart"]
        note["end"] = note["sourceEnd"]
        note["duration"] = round(note["duration"], 4)
        note["actualMidi"] = round(note["actualMidi"], 3)
        note["cents"] = round(note["cents"])
        note["confidence"] = round(note["confidence"], 4)
        note["scoreStart"] = round(note["scoreStart"], 4)
        note["scoreEnd"] = round(note["scoreEnd"], 4)
        note["scoreStartBeat"] = round(note["scoreStartBeat"], 4)
        note["scoreEndBeat"] = round(note["scoreEndBeat"], 4)

    voiced_regions = _voiced_regions(voiced)
    result: dict[str, Any] = {
        "notes": notes,
        "tempoBpm": beat_data["tempo"],
        "tempoRawBpm": beat_data["tempo_raw"],
        "tempoCandidates": beat_data["tempo_candidates"],
        "timeSignature": f"{beat_data['meter']}/4",
        "meterConfidence": beat_data["meter_confidence"],
        "timeSignatureCandidates": beat_data["meter_candidates"],
        "vocalStart": round(voiced_regions[0][0] * FRAME_SECONDS, 3),
        "vocalEnd": round(voiced_regions[-1][1] * FRAME_SECONDS, 3),
        "voicedRatio": round(float(voiced.mean()), 4),
        "voicedSeconds": round(float(voiced.sum()) * FRAME_SECONDS, 2),
        "f0AgreementRatio": round(agreement_ratio, 4),
        "octaveMismatchRatio": round(octave_ratio, 4),
        "comparedFrames": compared_count,
        "tuningCents": int(round(tuning * 100.0)),
        "key": key,
        "beatGrid": {
            "beatTimes": [round(float(value), 4) for value in beat_data["beat_times"]],
            "offsetBeats": round(beat_data.get("offset_beats", 0.0), 4),
        },
        "pitchMethod": "CREPE full (segment Viterbi) + pYIN cross-check + VAD",
        "register": register_summary,
        "timings": {name: round(value, 2) for name, value in timings.items()},
    }
    if return_frames:
        result["frames"] = {
            "midi": np.where(voiced, midi_tuned, np.nan).astype(np.float32),
            "voiced": voiced.astype(bool),
            "confidence": confidence.astype(np.float32),
            "dbfs": dbfs.astype(np.float32),
            "frameSeconds": FRAME_SECONDS,
        }
    if debug:
        result["_debug"] = {
            "dbfs": dbfs,
            "ratio": ratio,
            "periodicity": periodicity,
            "strict": vad_stats["strict"],
            "lenient": vad_stats["lenient"],
            "voiced": voiced,
            "midi": midi_tuned,
            "pyinMidi": pyin_midi_all - tuning,
            "referenceDb": vad_stats["referenceDb"],
            "register": register_frames,
        }
    return result
