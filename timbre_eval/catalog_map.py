# 128트랙 가수 섬 지도: 다곡 가수 = 색 + 라벨, 단곡 = 회색 점, 폰 녹음 = 별
from collections import defaultdict
from pathlib import Path

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"

E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}
tv = {t: e.mean(0) / np.linalg.norm(e.mean(0)) for t, e in E.items()}
by_singer = defaultdict(list)
for t in tv:
    by_singer[t.split("__")[0]].append(t)

PHONE = {"지유림": "#000000", "정다운": "#7f3fbf"}
LABEL_SINGLES = {"경서", "규현", "성시경", "백예린", "볼빨간사춘기", "이소라",
                 "카더가든", "먼데이키즈", "존박", "첸", "설윤", "이승철", "이문세"}

X = np.vstack([tv[t] for t in sorted(tv)])
names = sorted(tv)
mu = X.mean(0)
_, _, Vt = np.linalg.svd(X - mu, full_matrices=False)
P = (X - mu) @ Vt[:2].T
pos = {n: P[i] for i, n in enumerate(names)}

multi = sorted([s for s, ts in by_singer.items() if len(ts) >= 2 and s not in PHONE],
               key=lambda s: -len(by_singer[s]))
colors = plt.cm.tab10(np.linspace(0, 1, 10))

fig, ax = plt.subplots(figsize=(14, 10))
for s, ts in by_singer.items():
    if s in PHONE or s in multi:
        continue
    for t in ts:
        ax.scatter(*pos[t], s=28, color="#c3c3c3", zorder=1)
    if s in LABEL_SINGLES:
        ax.annotate(s, pos[ts[0]], fontsize=8, color="#666", xytext=(4, 4), textcoords="offset points")

for i, s in enumerate(multi):
    c = colors[i % 10]
    pts = np.array([pos[t] for t in by_singer[s]])
    ax.scatter(pts[:, 0], pts[:, 1], s=55, color=c, zorder=3, edgecolor="white", linewidth=0.5)
    for t in by_singer[s]:
        song = t.split("__", 1)[1]
        song = song[:10] + "…" if len(song) > 10 else song
        ax.annotate(song, pos[t], fontsize=6.5, color=c, alpha=0.9,
                    xytext=(4, -7), textcoords="offset points", zorder=3)
    cen = pts.mean(0)
    ax.scatter(*cen, s=240, marker="D", color=c, edgecolor="black", zorder=4)
    ax.annotate(f"{s} ({len(pts)}곡)", cen, fontsize=10, fontweight="bold", color="black",
                xytext=(6, 6), textcoords="offset points",
                bbox=dict(boxstyle="round,pad=0.15", fc="white", ec=c, alpha=0.85))

for s, c in PHONE.items():
    ts = by_singer.get(s, [])
    if ts:
        ax.scatter(*pos[ts[0]], s=520, marker="*", color=c, edgecolor="black", zorder=6)
        ax.annotate(f"{s} (폰 녹음)", pos[ts[0]], fontsize=11, fontweight="bold", color=c,
                    xytext=(8, -14), textcoords="offset points")

ax.set_title(f"가수 섬 지도 — 전체 카탈로그 {len(names)}트랙 (점=트랙 대표 지문, ◆=다곡 가수 평균, ★=팀원)\n"
             "⚠️ 192차원→2D 압축: 이웃 보존율 27% — 큰 지형(남/여, 계열)만 믿고, 쌍별 가까움 판정은 neighbors.md(원공간 표)로",
             fontsize=12)
ax.set_xlabel("PC1 (이름 없는 합성축)"); ax.set_ylabel("PC2")
plt.tight_layout()
out = HERE / "results" / "catalog_map.png"
plt.savefig(out, dpi=120)
print("saved", out, "| tracks:", len(names), "| 다곡 가수:", multi)
