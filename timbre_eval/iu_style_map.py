# style 영향 측정: 아이유 3곡(스타일 변동) + 밤편지 커버 3인(정체성 변동)을 한 공간에
import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt
from pathlib import Path

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache"
MODELS = ["ecapa", "singer_id", "wavlm", "mert"]

TRACKS = ["아이유__밤편지", "아이유__금요일에 만나요", "아이유__어떻게 지내",
          "웬디__밤편지", "첸__밤편지", "존박__밤편지"]
STYLE = {  # (색, 라벨)
    "아이유__밤편지": ("#d62728", "아이유·밤편지"),
    "아이유__금요일에 만나요": ("#ff9896", "아이유·금요일"),
    "아이유__어떻게 지내": ("#8b0000", "아이유·어떻게 지내"),
    "웬디__밤편지": ("#ff7f0e", "웬디·밤편지"),
    "첸__밤편지": ("#1f77b4", "첸·밤편지"),
    "존박__밤편지": ("#2ca02c", "존박·밤편지"),
}

fig, axes = plt.subplots(2, 2, figsize=(13, 11))
print("모델별 | 아이유 곡간 평균 유사도(스타일 변동) vs 아이유-타인 평균 유사도(정체성 격차)")
for ax, model in zip(axes.ravel(), MODELS):
    embs = {t: np.load(CACHE / model / f"{t}.npy") for t in TRACKS}
    X = np.vstack(list(embs.values()))
    mu = X.mean(0)
    _, _, Vt = np.linalg.svd(X - mu, full_matrices=False)
    for t in TRACKS:
        p = (embs[t] - mu) @ Vt[:2].T
        c, lab = STYLE[t]
        ax.scatter(p[:, 0], p[:, 1], s=22, alpha=0.5, color=c, label=lab)
        ax.scatter(*p.mean(0), s=230, marker="D", color=c, edgecolor="black", zorder=5)
    ax.set_title(model)
    ax.legend(fontsize=8, loc="best")

    m = {t: e.mean(0) / np.linalg.norm(e.mean(0)) for t, e in embs.items()}
    iu = [t for t in TRACKS if t.startswith("아이유")]
    others = [t for t in TRACKS if not t.startswith("아이유")]
    within = np.mean([m[a] @ m[b] for i, a in enumerate(iu) for b in iu[i + 1:]])
    between = np.mean([m[a] @ m[b] for a in iu for b in others])
    print(f"  {model:10s}: 아이유 곡간 {within:.3f} vs 아이유-타인 {between:.3f}"
          f"  (간격 {within - between:+.3f})")

plt.suptitle("아이유 3곡 + 밤편지 커버 3인 — 스타일이 흩뜨리는 폭 vs 정체성이 가르는 폭", fontsize=13)
plt.tight_layout()
out = HERE / "results" / "iu_style_map.png"
plt.savefig(out, dpi=120)
print("saved", out)
