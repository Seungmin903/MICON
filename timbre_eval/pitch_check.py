# 트랙별 f0 중앙값 측정 -> 피치 차이가 임베딩 유사도를 설명하는지 상관 분석
import json
from pathlib import Path

import numpy as np
import librosa

HERE = Path(__file__).resolve().parent
SEP = HERE / "separated" / "htdemucs"
report = json.loads((HERE / "results" / "report.json").read_text(encoding="utf-8"))

SR = 22050

f0_med = {}
for d in sorted(SEP.iterdir()):
    v = d / "vocals.wav"
    if not v.exists():
        continue
    y, _ = librosa.load(v, sr=SR, mono=True, duration=120)
    # 무성 구간 제거 후 앞 60초만 (pyin 느려서)
    rms = librosa.feature.rms(y=y, frame_length=2048, hop_length=2048)[0]
    thr = np.percentile(rms, 90) * 0.15
    mask = np.repeat(rms > thr, 2048)[: len(y)]
    y = y[mask][: 60 * SR]
    f0, voiced, _ = librosa.pyin(y, fmin=80, fmax=800, sr=SR)
    med = float(np.nanmedian(f0[voiced]))
    f0_med[d.name] = med
    note = librosa.hz_to_note(med)
    print(f"{d.name}: median f0 = {med:.1f} Hz ({note})")

semitone = {k: 12 * np.log2(v / 440) for k, v in f0_med.items()}

print("\n[ECAPA 트랙쌍 유사도 vs 피치 차이(반음)]")
rows = []
for n1, n2, cat, sim in report["ecapa"]["track_pairs"]:
    d_st = abs(semitone[n1] - semitone[n2])
    rows.append((cat, d_st, sim, n1, n2))
    if cat != "diff_singer_diff_song":
        print(f"  {sim:.3f}  Δ{d_st:4.1f}st  [{cat}]  {n1} <-> {n2}")

from scipy.stats import spearmanr

for label, sel in [("전체 쌍", rows),
                   ("다른 가수·같은 곡(커버)만", [r for r in rows if r[0] == "diff_singer_same_song"])]:
    d = [r[1] for r in sel]
    s = [r[2] for r in sel]
    rho, p = spearmanr(d, s)
    print(f"\n{label}: n={len(sel)}, 스피어만 rho={rho:.3f} (p={p:.3f})")
    print("  (rho가 -1에 가까우면 '피치 차이가 유사도를 지배', 0 근처면 '피치로 설명 안 됨')")
