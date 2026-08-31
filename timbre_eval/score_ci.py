# 점수 차이가 노이즈인지 판별: 청크 부트스트랩으로 사용자-트랙 점수의 신뢰구간 계산
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"
rng = np.random.default_rng(0)

u = np.load(CACHE / "지유림__밤편지.npy")
TARGETS = ["솔라__Golden", "솔라__Espresso", "솔라__Take me to Church",
           "아이유__어떻게 지내", "아이유__금요일에 만나요", "경서__밤편지"]

print("지유림 vs 트랙 — 점수 [95% 신뢰구간] (청크 부트스트랩 2000회)")
for t in TARGETS:
    e = np.load(CACHE / f"{t}.npy")
    sims = u @ e.T  # [24, n_t]
    boots = []
    for _ in range(2000):
        ui = rng.integers(0, sims.shape[0], sims.shape[0])
        ti = rng.integers(0, sims.shape[1], sims.shape[1])
        boots.append(sims[np.ix_(ui, ti)].mean())
    lo, hi = np.percentile(boots, [2.5, 97.5])
    print(f"  {sims.mean():.3f} [{lo:.3f} ~ {hi:.3f}]  {t.replace('__',' · ')}  (조각 {sims.shape[1]}개)")
