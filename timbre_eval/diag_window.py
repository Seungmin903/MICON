# 자기식별 실패 트랙의 30초 창 정밀 진단 + 청취용 추출
import sys

import numpy as np
import librosa
import soundfile as sf

from thirty_sec_test import embed_window
from evaluate import SEP, SR

t = sys.argv[1] if len(sys.argv) > 1 else "안재욱__친구"
t0, t1 = (int(sys.argv[2]), int(sys.argv[3])) if len(sys.argv) > 3 else (90, 120)

y, _ = librosa.load(SEP / t / "vocals.wav", sr=SR, mono=True, offset=t0, duration=t1 - t0)
out = f"timbre_eval/results/{t}_{t0}-{t1}s.wav"
sf.write(out, y, SR)
print("청취 파일:", out)

w = embed_window(SEP / t / "vocals.wav", t0, t1)
print("창 유효 조각 수:", 0 if w is None else len(w))
if w is not None:
    wv = w.mean(0)
    wv /= np.linalg.norm(wv)
    E = np.load(f"timbre_eval/emb_cache_v2/ecapa/{t}.npy")
    tm = E.mean(0)
    tm /= np.linalg.norm(tm)
    print(f"창 vs 전곡 대표: {float(wv @ tm):.3f}")
    per = np.sort(E @ wv)
    print("창 vs 전곡 조각별: 상위3", np.round(per[-3:], 3), "| 하위3", np.round(per[:3], 3))
f0, v, _ = librosa.pyin(librosa.resample(y, orig_sr=SR, target_sr=16000), fmin=60, fmax=800, sr=16000)
print("창 f0 중앙값:", f"{float(np.nanmedian(f0[v])):.0f}Hz" if v.any() else "무성")
rms = librosa.feature.rms(y=y)[0]
print(f"창 RMS: 중앙값 {np.median(rms):.4f}, 최대 {rms.max():.4f}")
