# 왜곡 없는 공유물: (1) 원공간 최근접 이웃 표 (2) 2D 지도의 이웃 보존율 계량
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"

E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}
tv = {t: (lambda m: m / np.linalg.norm(m))(e.mean(0)) for t, e in E.items()}
names = sorted(tv)
X = np.vstack([tv[t] for t in names])
S = X @ X.T
np.fill_diagonal(S, -1)

# --- (2) 2D 지도의 이웃 보존율 ---
mu = X.mean(0)
_, _, Vt = np.linalg.svd(X - mu, full_matrices=False)
P = (X - mu) @ Vt[:2].T
D2 = ((P[:, None, :] - P[None, :, :]) ** 2).sum(-1)
np.fill_diagonal(D2, np.inf)
K = 5
overlaps = []
for i in range(len(names)):
    true_nn = set(np.argsort(-S[i])[:K])
    map_nn = set(np.argsort(D2[i])[:K])
    overlaps.append(len(true_nn & map_nn) / K)
print(f"2D 지도의 이웃 보존율(top-{K} 일치): 평균 {np.mean(overlaps):.0%}"
      f" — 지도에서 이웃처럼 보이는 것 중 약 {1-np.mean(overlaps):.0%}는 착시")

# --- (1) 최근접 이웃 표 ---
lines = ["# 원공간 최근접 이웃 표 (ECAPA 192차원 코사인 — 왜곡 없음)",
         "", f"트랙 {len(names)}개 · 2D 지도의 top-{K} 이웃 보존율 {np.mean(overlaps):.0%}",
         "", "| 트랙 | 1위 이웃 | 2위 | 3위 |", "|---|---|---|---|"]
for i, t in enumerate(names):
    nn = np.argsort(-S[i])[:3]
    cells = [f"{names[j].replace('__',' · ')} ({S[i][j]:.2f})" for j in nn]
    lines.append(f"| {t.replace('__',' · ')} | " + " | ".join(cells) + " |")
out = HERE / "results" / "neighbors.md"
out.write_text("\n".join(lines), encoding="utf-8")
print("saved", out)
