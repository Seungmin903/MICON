"""Register (성구) analysis: harmonic-structure features → 진성 우세 / 가성 우세 / 혼합 / 불명확.

Why harmonic features
  Falsetto (가성) is produced with a shorter closed phase of the vocal folds; acoustically the
  first harmonic dominates (large H1−H2), upper harmonics fall off quickly (steep spectral slope,
  low harmonic richness, low energy above 1 kHz) and the signal is breathier (low cepstral peak
  prominence, CPP).  Modal voice (진성) is the opposite.  These are the classic voice-quality
  measures (Hillenbrand 1994 CPP; Kreiman & Gerratt H1−H2 / spectral tilt) and they only need the
  F0 track we already have, so we compute them on the shared 10 ms frame grid.

Design rules (from the team glossary §7.1 / §11)
  * Never a binary verdict: labels are `modal` (진성 우세), `falsetto` (가성 우세), `mixed`
    (혼합) and `unclear` (불명확), each with a confidence.
  * Compare each frame with what *this singer* does at *that pitch* (per-semitone median trend →
    residual robust z-scores), then require absolute anchors too (H1−H2 ≥ 8 dB, upper harmonics no
    stronger than H1).  A relative-only z-score is dominated by soft-verse vs belted-chorus dynamics
    (IU: H1−H2 +5 dB in the whispered low verse, −5 dB in the belted C5 chorus, +10 dB only at E5+).
  * Pitch prior: falsetto is only admitted in the upper 40 % of the song's sung pitch range; the
    same acoustics lower down are labelled soft/breathy modal (`breathy` flag) — acoustically the two
    overlap, and this is the documented limitation of the rule-based stage.
  * Breathiness (CPP) is kept as its own axis: a breathy modal singer is not falsetto.
  * A 2-component GMM (Ashman's D) is still fitted, but only reported as a distribution diagnostic.
  * Quality gates: frames need a confident F0 and a usable level; per-note confidence is scaled
    by the CREPE/pYIN agreement so separation residue lowers confidence instead of flipping labels.

Everything here is a rule-based baseline ("1단계").  The per-frame features and scores are
returned for evaluate.py so a learned model (GTSinger) can be compared against the same targets.
"""

from __future__ import annotations

import math
from pathlib import Path
from typing import Any

import numpy as np
import soundfile as sf
from scipy.ndimage import median_filter

FRAME_SECONDS = 0.01
MAX_HARMONIC = 20
HARMONIC_FMAX = 5000.0      # harmonics above this are vowel/mic dominated
TILT_FMAX = 4000.0          # spectral slope fitted over harmonics up to here
ALPHA_SPLIT = 1000.0        # alpha ratio: energy above / below 1 kHz
ALPHA_LOW = 50.0
ALPHA_FMAX = 5000.0
CPP_QUEFRENCY_MIN = 0.001   # 1 ms: cepstral regression starts above the vocal-tract part
CPP_QUEFRENCY_MAX = 0.02    # 20 ms (50 Hz)
BLOCK_FRAMES = 1024
LEVEL_FLOOR_DB = -70.0      # H1 below this (dBFS-ish) → frame unusable
MIN_FRAME_CONFIDENCE = 0.5
SMOOTH_FRAMES = 15          # 150 ms median smoothing of the composite score
MIN_NOTE_SECONDS = 0.15
BIMODAL_D = 2.0             # Ashman's D above which two registers are declared
MIN_COMPONENT_WEIGHT = 0.06
ANCHOR_H1H2_DB = 8.0        # single-register song: median H1−H2 above this → cannot call it modal
FALSETTO_POSTERIOR = 0.7
MODAL_POSTERIOR = 0.3
# Physical plausibility: a human voice never has H1−H2 far above ~20 dB or almost no upper harmonics.
# Frames beyond these bounds are instrument leakage / tracking errors and are excluded instead of
# being read as "extreme falsetto" (observed: YB 나는 나비 sustained F♯4 with H1−H2 = 27–51 dB).
H1H2_MAX_PLAUSIBLE_DB = 20.0
HRF_MIN_PLAUSIBLE_DB = -15.0
SOFT_LEVEL_GAP_DB = 6.0     # falsetto frames this much quieter than modal → likely breathy/soft modal
SOFT_CONFIDENCE_SCALE = 0.7
LOW_PITCH_CONFIDENCE_SCALE = 0.6

FALSETTO_SCORE = 0.6        # pitch-conditioned composite (z units) above which a note reads as falsetto
MIXED_SCORE = 0.4
MODAL_SCORE = 0.3
FALSETTO_H1H2_ANCHOR_DB = 8.0   # absolute anchor: falsetto needs a dominant first harmonic ...
LOW_PITCH_ANCHOR_PENALTY_DB = 3.0  # ... and more of it when the note is below the singer's median pitch
STRONG_ANCHOR_EXTRA_DB = 4.0    # H1-H2 this far above the anchor with weak upper harmonics -> falsetto regardless of score
FALSETTO_HRF_MAX_DB = 0.0       # ... and upper harmonics no stronger than H1 in total
MIXED_H1H2_MIN_DB = 5.0
MODAL_H1H2_MAX_DB = 5.0
BREATHY_Z = 1.0                 # CPP residual this far below the pitch trend -> breathy flag (kept separate from register)
TREND_MIN_BIN_FRAMES = 20
FALSETTO_PITCH_PERCENTILE = 60.0  # falsetto only admitted at/above this percentile of the song's sung pitch
MIN_FALSETTO_SECONDS = 1.0
MIN_FALSETTO_RATIO = 0.01

# composite "falsetto-ness": positive weights push toward falsetto
WEIGHTS = {"h1h2": 0.30, "hrf": -0.25, "slope": -0.15, "alpha": -0.10, "cpp": -0.20}
FEATURE_NAMES = ("h1h2", "hrf", "slope", "alpha", "cpp")
LABELS = ("modal", "falsetto", "mixed", "unclear")
LABELS_KO = {"modal": "진성 우세", "falsetto": "가성 우세", "mixed": "혼합", "unclear": "불명확"}


# ----------------------------------------------------------------------------- audio / spectra

def _load_mono(path: Path) -> tuple[np.ndarray, int]:
    audio, sample_rate = sf.read(path, dtype="float32", always_2d=True)
    return audio.mean(axis=1), int(sample_rate)


def _frame_spectra(audio: np.ndarray, sample_rate: int, frames: np.ndarray, n_fft: int) -> np.ndarray:
    """Magnitude spectra in dB for the given 10 ms frame indices (frame centre = index × 10 ms)."""
    hop = sample_rate * FRAME_SECONDS
    half = n_fft // 2
    window = np.hanning(n_fft).astype(np.float32)
    if isinstance(audio, Path):
        # Guided v2 reads only each requested FFT window, including zero padding at file edges.
        segments = np.zeros((len(frames), n_fft), np.float32)
        with sf.SoundFile(audio) as source:
            for i, centre in enumerate(np.rint(frames * hop).astype(np.int64)):
                left = int(centre) - half
                begin, end = max(0, left), min(len(source), left + n_fft)
                if end > begin:
                    source.seek(begin)
                    segments[i, begin - left:end - left] = source.read(end - begin, dtype="float32", always_2d=True).mean(axis=1)
        segments *= window
    else:
        padded = np.pad(audio, (half, half + n_fft))
        centres = np.rint(frames * hop).astype(np.int64) + half
        offsets = np.arange(n_fft)
        segments = padded[centres[:, None] - half + offsets[None, :]] * window
    spectrum = np.fft.rfft(segments, axis=1)
    magnitude = np.abs(spectrum) / (np.sum(window) / 2.0)  # amplitude of a sinusoid ≈ its peak value
    return 20.0 * np.log10(np.maximum(magnitude, 1e-7))


def _harmonic_amplitudes(mag_db: np.ndarray, f0: np.ndarray, bin_hz: float, fmax: float) -> np.ndarray:
    """Peak amplitude (dB) of harmonics 1..MAX_HARMONIC within ±12 % of k·F0; NaN when out of range."""
    count = mag_db.shape[0]
    bins = mag_db.shape[1]
    k = np.arange(1, MAX_HARMONIC + 1)
    target = f0[:, None] * k[None, :]
    valid = target < min(fmax, (bins - 2) * bin_hz)
    centre = np.rint(target / bin_hz).astype(np.int64)
    half = np.maximum(1, np.floor(0.12 * f0 / bin_hz)).astype(np.int64)
    best = np.full((count, MAX_HARMONIC), -np.inf, dtype=np.float32)
    for offset in range(-int(half.max()), int(half.max()) + 1):
        index = np.clip(centre + offset, 0, bins - 1)
        values = np.take_along_axis(mag_db, index, axis=1)
        mask = np.abs(offset) <= half[:, None]
        best = np.where(mask, np.maximum(best, values), best)
    best[~valid] = np.nan
    return best


def _band_energy_db(mag_db: np.ndarray, bin_hz: float, low: float, high: float) -> np.ndarray:
    lo = int(math.ceil(low / bin_hz))
    hi = int(math.floor(high / bin_hz))
    power = np.power(10.0, mag_db[:, lo:hi + 1] / 10.0)
    return 10.0 * np.log10(np.maximum(power.sum(axis=1), 1e-12))


def _spectral_slope(harmonics: np.ndarray, f0: np.ndarray) -> np.ndarray:
    """dB per octave of harmonic amplitude vs log2(harmonic number), harmonics up to TILT_FMAX."""
    k = np.arange(1, MAX_HARMONIC + 1)
    x = np.log2(k)[None, :].repeat(harmonics.shape[0], axis=0)
    mask = np.isfinite(harmonics) & (f0[:, None] * k[None, :] <= TILT_FMAX)
    y = np.where(mask, harmonics, 0.0)
    xm = np.where(mask, x, 0.0)
    n = mask.sum(axis=1)
    sx = xm.sum(axis=1)
    sy = y.sum(axis=1)
    sxx = (xm * xm).sum(axis=1)
    sxy = (xm * y).sum(axis=1)
    denominator = n * sxx - sx * sx
    slope = np.where((n >= 3) & (denominator > 1e-9), (n * sxy - sx * sy) / np.where(denominator > 1e-9, denominator, 1.0), np.nan)
    return slope


def _cpp(mag_db: np.ndarray, f0: np.ndarray, sample_rate: int, n_fft: int) -> np.ndarray:
    """Cepstral peak prominence (Hillenbrand): cepstral peak near 1/F0 minus the regression line."""
    cepstrum = np.fft.irfft(mag_db, n=n_fft, axis=1)[:, : n_fft // 2]
    q_lo = int(CPP_QUEFRENCY_MIN * sample_rate)
    q_hi = min(int(CPP_QUEFRENCY_MAX * sample_rate), cepstrum.shape[1] - 1)
    x = np.arange(q_lo, q_hi + 1, dtype=np.float64)
    y = cepstrum[:, q_lo:q_hi + 1].astype(np.float64)
    x_mean = x.mean()
    x_var = ((x - x_mean) ** 2).sum()
    slope = ((y - y.mean(axis=1, keepdims=True)) * (x - x_mean)[None, :]).sum(axis=1) / x_var
    intercept = y.mean(axis=1) - slope * x_mean
    period = sample_rate / np.maximum(f0, 1.0)
    result = np.full(mag_db.shape[0], np.nan)
    for index in range(mag_db.shape[0]):
        lo = int(max(q_lo, math.floor(period[index] * 0.85)))
        hi = int(min(q_hi, math.ceil(period[index] * 1.15)))
        if hi <= lo:
            continue
        segment = cepstrum[index, lo:hi + 1]
        peak = int(np.argmax(segment)) + lo
        result[index] = segment.max() - (slope[index] * peak + intercept[index])
    return result


# ----------------------------------------------------------------------------- statistics

def _robust_z(values: np.ndarray) -> tuple[np.ndarray, float, float]:
    finite = values[np.isfinite(values)]
    if finite.size < 10:
        return np.full_like(values, np.nan), float("nan"), float("nan")
    median = float(np.median(finite))
    mad = float(np.median(np.abs(finite - median))) * 1.4826
    scale = mad if mad > 1e-6 else float(np.std(finite)) + 1e-6
    return (values - median) / scale, median, scale


def _fit_gmm2(x: np.ndarray, iterations: int = 200) -> dict[str, Any]:
    """1-D two-component Gaussian mixture by EM; returns parameters and Ashman's D."""
    x = x[np.isfinite(x)]
    if x.size < 50:
        return {"ok": False}
    mu = np.percentile(x, [25.0, 75.0]).astype(np.float64)
    sigma = np.full(2, max(np.std(x), 1e-3))
    weight = np.array([0.5, 0.5])
    for _ in range(iterations):
        log_density = -0.5 * ((x[:, None] - mu[None, :]) / sigma[None, :]) ** 2 - np.log(sigma[None, :] * math.sqrt(2 * math.pi))
        log_joint = log_density + np.log(np.maximum(weight[None, :], 1e-9))
        log_norm = np.logaddexp(log_joint[:, 0], log_joint[:, 1])
        responsibility = np.exp(log_joint - log_norm[:, None])
        total = responsibility.sum(axis=0) + 1e-9
        new_mu = (responsibility * x[:, None]).sum(axis=0) / total
        new_sigma = np.sqrt((responsibility * (x[:, None] - new_mu[None, :]) ** 2).sum(axis=0) / total)
        new_sigma = np.maximum(new_sigma, 1e-3)
        new_weight = total / x.size
        converged = np.allclose(new_mu, mu, atol=1e-4) and np.allclose(new_sigma, sigma, atol=1e-4)
        mu, sigma, weight = new_mu, new_sigma, new_weight
        if converged:
            break
    order = np.argsort(mu)  # component 0 = lower score (modal-like), 1 = higher (falsetto-like)
    mu, sigma, weight = mu[order], sigma[order], weight[order]
    ashman_d = float(abs(mu[1] - mu[0]) / math.sqrt((sigma[0] ** 2 + sigma[1] ** 2) / 2.0))
    return {"ok": True, "mu": mu, "sigma": sigma, "weight": weight, "ashmanD": ashman_d}


def _posterior_high(x: np.ndarray, gmm: dict[str, Any]) -> np.ndarray:
    mu, sigma, weight = gmm["mu"], gmm["sigma"], gmm["weight"]
    log_density = -0.5 * ((x[:, None] - mu[None, :]) / sigma[None, :]) ** 2 - np.log(sigma[None, :] * math.sqrt(2 * math.pi))
    log_joint = log_density + np.log(np.maximum(weight[None, :], 1e-9))
    return np.exp(log_joint[:, 1] - np.logaddexp(log_joint[:, 0], log_joint[:, 1]))


def _smooth_in_regions(values: np.ndarray, voiced: np.ndarray, size: int) -> np.ndarray:
    output = values.copy()
    start = None
    for index in range(len(voiced) + 1):
        active = index < len(voiced) and voiced[index]
        if active and start is None:
            start = index
        elif not active and start is not None:
            segment = values[start:index]
            finite = np.isfinite(segment)
            if finite.sum() >= 3:
                filled = np.where(finite, segment, np.nanmedian(segment))
                smoothed = median_filter(filled, size=min(size, len(filled)), mode="nearest")
                output[start:index] = np.where(finite, smoothed, np.nan)
            start = None
    return output


# ----------------------------------------------------------------------------- public API

def analyze_register(
    vocals_path: Path,
    f0_hz: np.ndarray,
    voiced: np.ndarray,
    frame_confidence: np.ndarray,
    agreement: np.ndarray,
    notes: list[dict[str, Any]],
    level_db: np.ndarray | None = None,
    stream_audio: bool = False,
) -> dict[str, Any]:
    """Annotate `notes` in place with register labels and return song-level summary + frame arrays.

    f0_hz            per-frame F0 in Hz (NaN/0 where unknown), 10 ms grid
    voiced           per-frame VAD mask
    frame_confidence per-frame F0 confidence (0–1)
    agreement        per-frame CREPE/pYIN agreement mask
    notes            note dicts with sourceStart/sourceEnd in seconds (annotated in place)
    level_db         optional per-frame stem level (dBFS) used for the soft-singing caveat
    """
    audio, sample_rate = (vocals_path, sf.info(vocals_path).samplerate) if stream_audio else _load_mono(vocals_path)
    n_fft = 4096 if sample_rate >= 32000 else 2048
    bin_hz = sample_rate / n_fft
    length = len(voiced)
    f0 = np.asarray(f0_hz, dtype=np.float64)
    usable = voiced & np.isfinite(f0) & (f0 > 40.0) & (frame_confidence >= MIN_FRAME_CONFIDENCE)
    frames = np.flatnonzero(usable)

    features = {name: np.full(length, np.nan) for name in FEATURE_NAMES}
    fmax = min(HARMONIC_FMAX, sample_rate / 2.0 - 2 * bin_hz)
    for start in range(0, len(frames), BLOCK_FRAMES):
        block = frames[start:start + BLOCK_FRAMES]
        mag_db = _frame_spectra(audio, sample_rate, block, n_fft)
        block_f0 = f0[block]
        harmonics = _harmonic_amplitudes(mag_db, block_f0, bin_hz, fmax)
        h1 = harmonics[:, 0]
        level_ok = np.isfinite(h1) & (h1 > LEVEL_FLOOR_DB)
        power = np.power(10.0, np.nan_to_num(harmonics, nan=-np.inf) / 10.0)
        upper = power[:, 1:].sum(axis=1)
        hrf = 10.0 * np.log10(np.maximum(upper, 1e-12)) - h1
        h1h2 = h1 - harmonics[:, 1]
        slope = _spectral_slope(harmonics, block_f0)
        alpha = _band_energy_db(mag_db, bin_hz, ALPHA_SPLIT, min(ALPHA_FMAX, fmax)) - _band_energy_db(mag_db, bin_hz, ALPHA_LOW, ALPHA_SPLIT)
        cpp = _cpp(mag_db, block_f0, sample_rate, n_fft)
        for name, values in (("h1h2", h1h2), ("hrf", hrf), ("slope", slope), ("alpha", alpha), ("cpp", cpp)):
            values = np.where(level_ok, values, np.nan)
            features[name][block] = values

    # --- 1) plausibility gate: implausible harmonic structure = not a (single) human voice → excluded
    with np.errstate(invalid="ignore"):
        atypical = (features["h1h2"] > H1H2_MAX_PLAUSIBLE_DB) | (features["hrf"] < HRF_MIN_PLAUSIBLE_DB)
    atypical_frames = int(np.count_nonzero(atypical))
    for name in FEATURE_NAMES:
        features[name][atypical] = np.nan

    valid = np.isfinite(features["h1h2"]) & np.isfinite(features["cpp"]) & np.isfinite(f0) & (f0 > 40.0)
    midi = np.full(length, np.nan)
    midi[valid] = 69.0 + 12.0 * np.log2(f0[valid] / 440.0)

    # --- 2) pitch-conditioned residuals: compare each frame with what this singer does at that pitch.
    # Removes the soft-verse / belted-chorus dynamics trend that otherwise dominates a per-song z-score
    # (IU: H1−H2 ≈ +5 dB in the soft low verse, −5 dB in the belted C5 chorus, +10 dB only at E5+).
    residual_z: dict[str, np.ndarray] = {}
    medians: dict[str, float] = {}
    trend_slopes: dict[str, float | None] = {}
    for name in FEATURE_NAMES:
        trend, slope = _pitch_trend(midi, features[name], valid)
        trend_slopes[name] = slope
        z, _, _ = _robust_z(np.where(valid, features[name] - trend, np.nan))
        residual_z[name] = np.clip(np.nan_to_num(z, nan=0.0), -4.0, 4.0)
        finite = features[name][valid]
        medians[name] = float(np.median(finite)) if finite.size else float("nan")
    composite = np.full(length, np.nan)
    if valid.any():
        weighted = sum(WEIGHTS[name] * residual_z[name] for name in FEATURE_NAMES)
        composite[valid] = weighted[valid]
    composite = _smooth_in_regions(composite, voiced, SMOOTH_FRAMES)
    breathiness = np.where(valid, -residual_z["cpp"], np.nan)  # positive = breathier than usual at that pitch

    gmm = _fit_gmm2(composite)  # distribution shape, reported for diagnostics only
    pitch_median = float(np.nanmedian(midi[valid])) if valid.any() else float("nan")
    # Pitch prior: falsetto is only admitted in the upper part of *this singer's* range in this song.
    # Below it, the same acoustics (dominant H1, weak upper harmonics, low CPP) are read as soft/breathy
    # modal phonation — the classic confound (IU's whispered verses at G♯3–B3 have H1−H2 = 12–16 dB).
    pitch_floor = float(np.nanpercentile(midi[valid], FALSETTO_PITCH_PERCENTILE)) if valid.any() else float("nan")

    # --- 3) per-note decision: pitch-conditioned score AND absolute anchors must agree ---------------
    for note in notes:
        left = int(round(note["sourceStart"] / FRAME_SECONDS))
        right = max(left + 1, int(round(note["sourceEnd"] / FRAME_SECONDS)))
        body = composite[left + 3:right] if right - left >= 12 else composite[left:right]
        finite = body[np.isfinite(body)]
        duration = note["sourceEnd"] - note["sourceStart"]
        agree = float(np.mean(agreement[left:right])) if right > left else 0.0
        quality = float(np.mean(frame_confidence[left:right])) * (0.5 + 0.5 * agree)
        score = float(np.median(finite)) if finite.size else float("nan")
        raw_h1h2 = _nanmedian_or_nan(features["h1h2"][left:right])
        raw_hrf = _nanmedian_or_nan(features["hrf"][left:right])
        breathy = _nanmedian_or_nan(breathiness[left:right])
        low_pitch = math.isfinite(pitch_median) and float(note["midi"]) < pitch_median
        in_falsetto_range = math.isfinite(pitch_floor) and float(note["midi"]) >= pitch_floor
        anchor = FALSETTO_H1H2_ANCHOR_DB + (LOW_PITCH_ANCHOR_PENALTY_DB if low_pitch else 0.0)

        label = "unclear"
        confidence = 0.0
        light_phonation = False
        if finite.size >= 3 and duration >= MIN_NOTE_SECONDS and math.isfinite(raw_h1h2) and math.isfinite(raw_hrf):
            strong_absolute = raw_h1h2 >= anchor + STRONG_ANCHOR_EXTRA_DB and raw_hrf <= FALSETTO_HRF_MAX_DB - 3.0
            relative_and_anchor = score >= FALSETTO_SCORE and raw_h1h2 >= anchor and raw_hrf <= FALSETTO_HRF_MAX_DB
            light_phonation = strong_absolute or relative_and_anchor
            if light_phonation and in_falsetto_range:
                label = "falsetto"
                evidence = 0.4 + 0.3 * min(max((score - FALSETTO_SCORE) / 0.8, 0.0), 1.0) + 0.3 * min(max((raw_h1h2 - anchor) / 6.0, 0.0), 1.0)
                confidence = min(1.0, evidence) * quality
            elif light_phonation:  # falsetto-like acoustics below the singer's upper range → soft/breathy modal
                label = "modal"
                confidence = 0.4 * quality
            elif score >= MIXED_SCORE and raw_h1h2 >= MIXED_H1H2_MIN_DB and in_falsetto_range:
                label = "mixed"
                confidence = 0.5 * quality
            elif score <= MODAL_SCORE or raw_h1h2 <= MODAL_H1H2_MAX_DB:
                label = "modal"
                confidence = min(1.0, 0.3 + max(MODAL_SCORE - score, 0.0) / 1.5 + max(MODAL_H1H2_MAX_DB - raw_h1h2, 0.0) / 10.0) * quality
            else:
                label = "modal"
                confidence = 0.2 * quality
        note["register"] = label
        note["registerScore"] = None if not math.isfinite(score) else round(score, 3)
        note["registerConfidence"] = round(float(confidence), 3)
        note["h1h2Db"] = _round_or_none(raw_h1h2)
        note["hrfDb"] = _round_or_none(raw_hrf)
        note["cppDb"] = _round_or_none(_nanmedian_or_nan(features["cpp"][left:right]))
        note["breathy"] = bool(label != "falsetto" and ((math.isfinite(breathy) and breathy >= BREATHY_Z) or light_phonation))
        note["lightness"] = _lightness(score, raw_h1h2, raw_hrf)
        note["_frames"] = (left, right)

    # --- 4) caveats: the two classic confounds of acoustic register detection ---------------------
    caveats: list[str] = []
    confidence_scale = 1.0
    median_midi: dict[str, float | None] = {"modal": None, "falsetto": None}
    modal_midi = [int(n["midi"]) for n in notes if n["register"] == "modal"]
    falsetto_midi = [int(n["midi"]) for n in notes if n["register"] == "falsetto"]
    if modal_midi and falsetto_midi:
        median_midi = {"modal": float(np.median(modal_midi)), "falsetto": float(np.median(falsetto_midi))}
        if median_midi["falsetto"] <= median_midi["modal"]:
            caveats.append("가성 우세로 판정된 구간의 음높이가 진성 구간보다 낮습니다 — 숨소리 섞인 약한 진성일 가능성이 있어 신뢰도를 낮췄습니다.")
            confidence_scale *= LOW_PITCH_CONFIDENCE_SCALE
        if level_db is not None:
            def _mean_level(label: str) -> float | None:
                values = [level_db[n["_frames"][0]:n["_frames"][1]] for n in notes if n["register"] == label]
                flat = np.concatenate(values) if values else np.empty(0)
                return float(np.mean(flat)) if flat.size else None
            modal_level, falsetto_level = _mean_level("modal"), _mean_level("falsetto")
            if modal_level is not None and falsetto_level is not None and falsetto_level < modal_level - SOFT_LEVEL_GAP_DB:
                caveats.append(f"가성 우세 구간이 진성 구간보다 {modal_level - falsetto_level:.0f} dB 작게 녹음되어 있습니다 — 약한 발성(숨소리 진성)과 구분이 어려울 수 있습니다.")
                confidence_scale *= SOFT_CONFIDENCE_SCALE
    if atypical_frames > 0.02 * max(1, int(valid.sum()) + atypical_frames):
        caveats.append("배음 구조가 사람 목소리 범위를 벗어난 프레임이 있어 제외했습니다 — 분리 stem에 악기 잔여음이 있거나 F0 추적 오류일 수 있습니다.")

    seconds = {label: 0.0 for label in LABELS}
    ranges: dict[str, list[int]] = {"modal": [], "falsetto": []}
    breathy_seconds = 0.0
    lightness_histogram = [0.0] * 10  # seconds per 10-point bin of the continuous lightness score
    lightness_weighted = 0.0
    lightness_seconds = 0.0
    for note in notes:
        note.pop("_frames")
        if confidence_scale < 1.0 and note["register"] in ("falsetto", "mixed"):
            note["registerConfidence"] = round(note["registerConfidence"] * confidence_scale, 3)
        duration = note["sourceEnd"] - note["sourceStart"]
        seconds[note["register"]] += duration
        if note.get("breathy"):
            breathy_seconds += duration
        if note["register"] in ranges:
            ranges[note["register"]].append(int(note["midi"]))
        if note.get("lightness") is not None:
            lightness_histogram[min(9, int(note["lightness"]) // 10)] += duration
            lightness_weighted += note["lightness"] * duration
            lightness_seconds += duration

    total = sum(seconds.values()) or 1.0
    falsetto_floor = max(MIN_FALSETTO_SECONDS, MIN_FALSETTO_RATIO * total)
    two_registers = seconds["falsetto"] >= falsetto_floor
    if 0.0 < seconds["falsetto"] < falsetto_floor:
        caveats.append(f"가성 우세 구간이 {seconds['falsetto']:.1f}초로 짧아 곡 전체로는 진성 위주로 보되, 해당 음표는 개별적으로 검수하세요.")
    summary: dict[str, Any] = {
        "method": "harmonic features (H1−H2, HRF, spectral slope, alpha ratio, CPP) → pitch-conditioned residual z → composite score + absolute anchors",
        "bimodal": two_registers,
        "twoRegisters": two_registers,
        "ashmanD": None if not gmm.get("ok") else round(gmm["ashmanD"], 3),
        "componentWeights": None if not gmm.get("ok") else [round(float(w), 3) for w in gmm["weight"]],
        "singleRegisterLabel": None if two_registers else ("modal" if seconds["modal"] >= seconds["unclear"] else "unclear"),
        "seconds": {label: round(value, 2) for label, value in seconds.items()},
        "ratios": {label: round(value / total, 4) for label, value in seconds.items()},
        "breathyRatio": round(breathy_seconds / total, 4),
        "lightnessMean": None if not lightness_seconds else round(lightness_weighted / lightness_seconds, 1),
        "lightnessHistogram": [round(value, 2) for value in lightness_histogram],
        "medianFeatures": {name: _round_or_none(value) for name, value in medians.items()},
        "pitchTrendSlopes": {name: _round_or_none(value, 3) for name, value in trend_slopes.items()},
        "pitchMedianMidi": _round_or_none(pitch_median, 1),
        "falsettoPitchFloorMidi": _round_or_none(pitch_floor, 1),
        "modalRangeMidi": [min(ranges["modal"]), max(ranges["modal"])] if len(ranges["modal"]) >= 2 else None,
        "falsettoRangeMidi": [min(ranges["falsetto"]), max(ranges["falsetto"])] if len(ranges["falsetto"]) >= 2 else None,
        "usableFrames": int(valid.sum()),
        "atypicalFrames": atypical_frames,
        "medianMidi": {key: _round_or_none(value, 1) for key, value in median_midi.items()},
        "caveats": caveats,
        "confidenceScale": round(confidence_scale, 2),
        "labels": LABELS_KO,
    }
    return {"summary": summary, "frames": {"composite": composite, "breathiness": breathiness, **features}}


def _lightness(score: float, h1h2_db: float, hrf_db: float) -> int | None:
    """Continuous 0–100 "발성 가벼움": 0 = dense modal/belt, 100 = open falsetto.

    Half relative (pitch-conditioned composite score, this singer vs. themself) and half absolute
    (H1−H2 and harmonic richness on fixed dB scales), so the value is comparable across songs while
    still reflecting where the note sits within the song.  Head voice / mix land in the middle.
    Reference points: belted C5 with H1−H2 −5 dB, HRF +9 dB → ≈ 10; neutral 4 dB / 0 dB → ≈ 50;
    light falsetto 12 dB / −5 dB → ≈ 85."""
    if not (math.isfinite(h1h2_db) and math.isfinite(hrf_db)):
        return None
    relative = 0.0 if not math.isfinite(score) else max(-2.5, min(2.5, score))
    absolute = (h1h2_db - 4.0) / 8.0 - hrf_db / 10.0
    light = 0.5 * relative + 0.5 * absolute
    return int(round(100.0 / (1.0 + math.exp(-1.6 * light))))


def _nanmedian_or_nan(values: np.ndarray) -> float:
    finite = values[np.isfinite(values)]
    return float(np.median(finite)) if finite.size else float("nan")


def _pitch_trend(midi: np.ndarray, values: np.ndarray, valid: np.ndarray) -> tuple[np.ndarray, float | None]:
    """Expected feature value at each frame's pitch: count-weighted line through per-semitone medians.

    Bins with fewer than TREND_MIN_BIN_FRAMES frames are ignored; with < 3 usable bins the trend is the
    global median (slope None).  Returns (trend per frame, slope per semitone)."""
    trend = np.full(len(values), np.nan)
    if not valid.any():
        return trend, None
    bins = np.rint(midi[valid]).astype(int)
    feature = values[valid]
    centres, medians_, counts = [], [], []
    for bin_value in np.unique(bins):
        mask = bins == bin_value
        if mask.sum() >= TREND_MIN_BIN_FRAMES:
            centres.append(float(bin_value))
            medians_.append(float(np.median(feature[mask])))
            counts.append(float(mask.sum()))
    if len(centres) < 3:
        trend[valid] = float(np.median(feature))
        return trend, None
    x, y, w = np.asarray(centres), np.asarray(medians_), np.sqrt(np.asarray(counts))
    slope, intercept = np.polyfit(x, y, 1, w=w)
    trend[valid] = intercept + slope * midi[valid]
    return trend, float(slope)


def _round_or_none(value: Any, digits: int = 2) -> float | None:
    try:
        number = float(value)
    except (TypeError, ValueError):
        return None
    return None if not math.isfinite(number) else round(number, digits)
