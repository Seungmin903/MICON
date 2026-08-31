# 음역(성구 프록시) 조건부 지문 품질 검증 — 팀원 주장 2건을 한 실험으로
# 조각을 f0 중앙값 기준 저/고 밴드로 갈라:
#  (1) 같은 트랙의 저밴드↔고밴드 유사도가 타인 수준까지 떨어지는가 (성구 혼합 오염 크기)
#  (2) 고밴드끼리의 타인 식별력이 저밴드보다 나쁜가 (고음역 열화 가설)
from itertools import combinations
from pathlib import Path

import numpy as np
import librosa

from sensitivity import load_chunks_v2
from evaluate import SEP

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"

band_vec = {}  # track -> {"low": vec, "high": vec}
for d in sorted(SEP.iterdir()):
    v = d / "vocals.wav"
    f = CACHE / f"{d.name}.npy"
    if not v.exists() or not f.exists():
        continue
    E = np.load(f)
    chunks = load_chunks_v2(v)
    if len(chunks) != len(E) or len(chunks) < 6:
        continue
    f0s = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=44100, target_sr=16000)
        f0, voiced, _ = librosa.pyin(w, fmin=80, fmax=800, sr=16000, frame_length=1024)
        f0s.append(np.nanmedian(f0[voiced]) if voiced.any() else np.nan)
    f0s = np.array(f0s)
    ok = ~np.isnan(f0s)
    if ok.sum() < 6:
        continue
    med = np.median(f0s[ok])
    low_i = ok & (f0s <= med)
    high_i = ok & (f0s > med)
    if low_i.sum() < 2 or high_i.sum() < 2:
        continue
    lo = E[low_i].mean(0); hi = E[high_i].mean(0)
    band_vec[d.name] = {"low": lo / np.linalg.norm(lo), "high": hi / np.linalg.norm(hi),
                        "f0_low": float(np.median(f0s[low_i])), "f0_high": float(np.median(f0s[high_i]))}
    print(f"{d.name}: low {low_i.sum()}개({band_vec[d.name]['f0_low']:.0f}Hz) / high {high_i.sum()}개({band_vec[d.name]['f0_high']:.0f}Hz)")

def cos(a, b):
    return float(a @ b)

# (1) 같은 트랙 저↔고 (성구/음역 혼합의 자기 오염 크기)
self_cross = [cos(bv["low"], bv["high"]) for bv in band_vec.values()]
# 기준선: 타인 같은밴드 (같은 곡 커버쌍, 밤편지)
bam = [t for t in band_vec if t.split("__", 1)[1].startswith("밤편지") and not t.startswith(("지유림", "정다운"))]
strangers_same_band, strangers_low, strangers_high = [], [], []
for a, b in combinations(bam, 2):
    if a.split("__")[0] == b.split("__")[0]:
        continue
    sl = cos(band_vec[a]["low"], band_vec[b]["low"])
    sh = cos(band_vec[a]["high"], band_vec[b]["high"])
    strangers_low.append(sl); strangers_high.append(sh)
    strangers_same_band += [sl, sh]

print(f"\n(1) 같은 트랙 저밴드↔고밴드: 평균 {np.mean(self_cross):.3f} (최소 {np.min(self_cross):.3f})")
print(f"    타인(밤편지 커버쌍) 같은밴드끼리: 평균 {np.mean(strangers_same_band):.3f}")
print("    → 자기 교차밴드가 타인보다 높으면 성구 혼합은 정체성을 못 부순다")
print(f"\n(2) 타인 식별 (낮을수록 잘 가름): 저밴드끼리 {np.mean(strangers_low):.3f} vs 고밴드끼리 {np.mean(strangers_high):.3f}")
print("    → 고밴드 값이 유의하게 높으면 '고음역에서 식별력 저하' 가설 지지")
