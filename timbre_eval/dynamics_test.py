# 표현 민감성의 부분 검증 (팀원 녹음 불필요): 발성 강도 축
# 같은 트랙 안에서 원시 RMS 상위 1/3(세게) vs 하위 1/3(여리게) 조각의 지문 비교
# 교란 통제: 두 그룹의 f0 차이를 함께 보고 (센 구간 = 높은 구간 교란 정도 표시)
import json
from pathlib import Path

import numpy as np

CACHE = Path("timbre_eval/emb_cache_v2/ecapa")

self_cross, f0_gaps, per_track = [], [], []
for f in sorted(CACHE.glob("*.meta.json")):
    meta = json.loads(f.read_text(encoding="utf-8"))
    name = f.stem.replace(".meta", "")
    if meta["track"].get("duet_suspect") or meta["n_chunks"] < 12:
        continue
    E = np.load(CACHE / (name + ".npy"))
    rms = np.array([c["rms_raw"] for c in meta["chunks"]])
    f0 = np.array([c["f0_median_hz"] or np.nan for c in meta["chunks"]], dtype=float)
    k = len(E) // 3
    order = np.argsort(rms)
    soft_i, loud_i = order[:k], order[-k:]
    soft = E[soft_i].mean(0); soft /= np.linalg.norm(soft)
    loud = E[loud_i].mean(0); loud /= np.linalg.norm(loud)
    sim = float(soft @ loud)
    self_cross.append(sim)
    if not np.isnan(f0[soft_i]).all() and not np.isnan(f0[loud_i]).all():
        gap_st = 12 * np.log2(np.nanmedian(f0[loud_i]) / np.nanmedian(f0[soft_i]))
        f0_gaps.append(gap_st)
    per_track.append((sim, name))

# 기준선: 다른 사람끼리 (전 트랙 대표 벡터, 다른 가수 쌍 표본)
tv = {}
for f in CACHE.glob("*.npy"):
    e = np.load(f); m = e.mean(0); tv[f.stem] = m / np.linalg.norm(m)
names = sorted(tv)
rng = np.random.default_rng(0)
stranger = []
for _ in range(500):
    a, b = rng.choice(names, 2, replace=False)
    if a.split("__")[0] != b.split("__")[0]:
        stranger.append(float(tv[a] @ tv[b]))

print(f"[발성 강도 민감성 — 트랙 {len(self_cross)}개]")
print(f"같은 사람 여리게↔세게: 평균 {np.mean(self_cross):.3f} (P10 {np.percentile(self_cross,10):.3f})")
print(f"타인 기준선:            평균 {np.mean(stranger):.3f}")
print(f"강도 그룹 간 f0 차이:   중앙값 {np.median(f0_gaps):+.1f}반음 (강도-음높이 교란의 크기)")
print("\n여리게↔세게 거리가 큰(민감한) 트랙 5:")
for s, n in sorted(per_track)[:5]:
    print(f"  {s:.3f}  {n}")
