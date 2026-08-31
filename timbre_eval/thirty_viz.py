# 30초 입력 검증 시각화 2종
# 1) 자기 일관성 덤벨: cos(30초-나, 3분-나) vs cos(30초-나, 최근접 타인)
# 2) 결과 변화성 지도: 3분-나(큰 별) 주변에 30초-나 3개(작은 별)가 어디 찍히나 + 최근접 마름모
from pathlib import Path

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

from thirty_sec_test import E, USERS, WINDOWS, embed_window
from evaluate import SEP

HERE = Path(__file__).resolve().parent
track_vec = {t: e.mean(0) / np.linalg.norm(e.mean(0)) for t, e in E.items()}

# 윈도우 벡터 수집
win_vecs = {}  # (user, (t0,t1)) -> vec
for user in USERS:
    for t0, t1 in WINDOWS:
        w = embed_window(SEP / user / "vocals.wav", t0, t1)
        if w is not None:
            win_vecs[(user, (t0, t1))] = w.mean(0) / np.linalg.norm(w.mean(0))

# ---- 그림 1: 자기 일관성 덤벨 ----
fig, ax = plt.subplots(figsize=(9, 4.5))
rows = []
for user in USERS:
    for t0, t1 in WINDOWS:
        if (user, (t0, t1)) not in win_vecs:
            continue
        wv = win_vecs[(user, (t0, t1))]
        self_sim = float(wv @ track_vec[user])
        others = [(float(wv @ track_vec[o]), o) for o in E if o.split("__")[0] != user.split("__")[0]]
        best_o, best_name = max(others)
        rows.append((f"{user.split('__')[0]} {t0}-{t1}s", self_sim, best_o, best_name.split("__")[0]))
for i, (label, s, o, oname) in enumerate(rows):
    y = len(rows) - i
    ax.plot([o, s], [y, y], color="#999", lw=2, zorder=1)
    ax.scatter([s], [y], s=110, color="#0E6E8C", zorder=3, label="3분의 나" if i == 0 else None)
    ax.scatter([o], [y], s=90, color="#B23A32", zorder=3, label="최근접 타인" if i == 0 else None)
    ax.text(s + 0.012, y, f"{s:.2f}", va="center", fontsize=9, color="#0E6E8C")
    ax.text(o - 0.012, y, f"{o:.2f} ({oname})", va="center", ha="right", fontsize=9, color="#B23A32")
ax.set_yticks([len(rows) - i for i in range(len(rows))])
ax.set_yticklabels([r[0] for r in rows], fontsize=10)
ax.set_xlabel("30초-나 벡터와의 코사인 유사도")
ax.set_title("자기 일관성 — 30초의 나는 '3분의 나'와 '가장 가까운 타인' 중 누구와 가까운가")
ax.legend(loc="lower right", fontsize=9)
ax.set_xlim(0.3, 1.0)
ax.grid(axis="x", alpha=0.3)
plt.tight_layout()
plt.savefig(HERE / "results" / "viz_self_consistency.png", dpi=120)
plt.close()
print("saved viz_self_consistency")

# ---- 그림 2: 결과 변화성 지도 ----
HL = {"지유림__밤편지": ["경서__밤편지", "아이유__밤편지", "아이유__어떻게 지내", "웬디__밤편지"],
      "정다운__밤편지": ["규현__밤편지", "이영현__그래서 그대는", "이영현__My Way", "첸__밤편지"]}
fig, axes = plt.subplots(1, 2, figsize=(14, 6.5))
for ax, user in zip(axes, USERS):
    gallery = [t for t in E if not t.startswith(("지유림", "정다운"))]
    vecs = [track_vec[t] for t in gallery] + [track_vec[user]] + \
           [win_vecs[(user, w)] for w in WINDOWS if (user, w) in win_vecs]
    X = np.vstack(vecs)
    mu = X.mean(0)
    _, _, Vt = np.linalg.svd(X - mu, full_matrices=False)
    P = (X - mu) @ Vt[:2].T
    n_g = len(gallery)
    for i, t in enumerate(gallery):
        if t in HL[user]:
            ax.scatter(*P[i], s=150, marker="D", color="#0E6E8C", zorder=3)
            ax.annotate(t.replace("__", "·"), P[i], fontsize=8.5, xytext=(5, 5),
                        textcoords="offset points", color="#0E6E8C")
        else:
            ax.scatter(*P[i], s=60, marker="D", color="#bbb", zorder=2)
    full_p = P[n_g]
    ax.scatter(*full_p, s=420, marker="*", color="#B23A32", edgecolor="black", zorder=5)
    ax.annotate("3분의 나", full_p, fontsize=10, fontweight="bold", xytext=(8, -14),
                textcoords="offset points", color="#B23A32")
    wi = 0
    for w in WINDOWS:
        if (user, w) not in win_vecs:
            continue
        p = P[n_g + 1 + wi]
        wi += 1
        ax.scatter(*p, s=160, marker="*", color="#e08214", edgecolor="black", zorder=4)
        ax.annotate(f"{w[0]}s", p, fontsize=8, xytext=(4, 4), textcoords="offset points", color="#e08214")
        ax.plot([full_p[0], p[0]], [full_p[1], p[1]], ls="--", color="#e08214", lw=1, alpha=0.6, zorder=1)
    ax.set_title(f"{user.split('__')[0]} — 30초의 나(주황★)는 3분의 나(빨강★) 곁을 떠나지 않는다")
    ax.set_xlabel("PC1"); ax.set_ylabel("PC2")
plt.tight_layout()
plt.savefig(HERE / "results" / "viz_stability_map.png", dpi=120)
plt.close()
print("saved viz_stability_map")
