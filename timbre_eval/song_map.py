# 한 곡만 골라 4개 모델의 임베딩 지도를 2x2로 그림 (곡 변수 통제 -> 남는 차이는 목소리)
import sys
from pathlib import Path

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache"
MODELS = ["ecapa", "singer_id", "wavlm", "mert"]
SONG = sys.argv[1] if len(sys.argv) > 1 else "밤편지"
COLORS = {"아이유": "#d62728", "웬디": "#ff7f0e", "첸": "#1f77b4", "존박": "#2ca02c",
          "크러쉬": "#9467bd", "설윤": "#e377c2", "경서": "#bcbd22", "규현": "#17becf"}

fig, axes = plt.subplots(2, 2, figsize=(12, 10))
for ax, model in zip(axes.ravel(), MODELS):
    embs = {}
    for f in sorted((CACHE / model).glob(f"*__{SONG}.npy")):
        embs[f.stem.split("__")[0]] = np.load(f)
    X = np.vstack(list(embs.values()))
    Xc = X - X.mean(0)
    _, _, Vt = np.linalg.svd(Xc, full_matrices=False)
    i = 0
    for singer, e in embs.items():
        p = (e - X.mean(0)) @ Vt[:2].T
        c = COLORS.get(singer, "#555")
        ax.scatter(p[:, 0], p[:, 1], s=22, alpha=0.5, color=c, label=singer)
        cen = p.mean(0)
        ax.scatter(*cen, s=220, marker="D", color=c, edgecolor="black", zorder=5)
        i += len(e)
    ax.set_title(f"{model} — {SONG}만 (곡 통제, 차이=목소리)")
    ax.legend(fontsize=9)
    ax.set_xlabel("PC1"); ax.set_ylabel("PC2")
plt.tight_layout()
out = HERE / "results" / f"song_map_{SONG}.png"
plt.savefig(out, dpi=120)
print("saved", out)
