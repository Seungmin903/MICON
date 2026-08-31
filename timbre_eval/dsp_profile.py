# DSP 프록시 음색 프로필: 아이유 밤편지 vs 같은 곡 부른 6인 (+지유림)
# 주의: 임상 지표를 노래에 적용 - 절대값 아닌 트랙 간 상대 비교로만 해석
from pathlib import Path

import numpy as np
import librosa
import parselmouth
from parselmouth.praat import call

from sensitivity import load_chunks_v2
from evaluate import SEP, SR

TRACKS = ["아이유__밤편지", "웬디__밤편지", "경서__밤편지", "설윤__밤편지",
          "첸__밤편지", "존박__밤편지", "규현__밤편지", "지유림__밤편지", "이영현__그래서 그대는"]


def h1_h2(seg, f0):
    """평균 스펙트럼에서 f0와 2f0 피크의 dB 차 (기식성 프록시)."""
    spec = np.abs(np.fft.rfft(seg * np.hanning(len(seg))))
    freqs = np.fft.rfftfreq(len(seg), 1 / SR)
    def peak_db(f):
        band = (freqs > f * 0.9) & (freqs < f * 1.1)
        return 20 * np.log10(spec[band].max() + 1e-9)
    return peak_db(f0) - peak_db(2 * f0)


print(f"{'트랙':<18}{'센트로이드(Hz)':>14}{'HNR(dB)':>10}{'jitter(%)':>11}{'shimmer(%)':>12}{'H1-H2(dB)':>11}")
for t in TRACKS:
    chunks = load_chunks_v2(SEP / t / "vocals.wav")
    cents, hnrs, jits, shims, h12s = [], [], [], [], []
    for c in chunks:
        cents.append(float(np.median(librosa.feature.spectral_centroid(y=c, sr=SR))))
        snd = parselmouth.Sound(c.astype(np.float64), sampling_frequency=SR)
        harm = snd.to_harmonicity_cc()
        h = call(harm, "Get mean", 0, 0)
        if not np.isnan(h):
            hnrs.append(h)
        try:
            pp = call(snd, "To PointProcess (periodic, cc)", 75, 600)
            j = call(pp, "Get jitter (local)", 0, 0, 0.0001, 0.02, 1.3)
            s = call([snd, pp], "Get shimmer (local)", 0, 0, 0.0001, 0.02, 1.3, 1.6)
            if not np.isnan(j):
                jits.append(j * 100)
            if not np.isnan(s):
                shims.append(s * 100)
        except Exception:
            pass
        f0, voiced, _ = librosa.pyin(librosa.resample(c, orig_sr=SR, target_sr=16000),
                                     fmin=80, fmax=800, sr=16000)
        if voiced.any():
            h12s.append(h1_h2(c, float(np.nanmedian(f0[voiced]))))

    def m(x):
        return f"{np.median(x):.0f}" if x else "-"
    def m1(x):
        return f"{np.median(x):.1f}" if x else "-"
    print(f"{t.replace('__','·'):<18}{m(cents):>14}{m1(hnrs):>10}{m1(jits):>11}{m1(shims):>12}{m1(h12s):>11}")
