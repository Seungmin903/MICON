# 실험별 시각화 4장 (v2 임베딩 = 최종 사양)
# 1) 폰 녹음 포함 지도  2) 아이유 vs 이영현  3) 아이유 환경 4버전  4) 제이플라/솔라 스타일
from itertools import combinations
from pathlib import Path

import numpy as np
import matplotlib

matplotlib.use("Agg")
import matplotlib.pyplot as plt

plt.rcParams["font.family"] = "Malgun Gothic"
plt.rcParams["axes.unicode_minus"] = False

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"
OUT = HERE / "results"

E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}


def draw(name, groups, title, star=(), figsize=(8, 6)):
    """groups: [(라벨, [트랙들], 색)] — 그룹 내 트랙 전 청크를 같은 색으로."""
    tracks = [t for _, ts, _ in groups for t in ts]
    X = np.vstack([E[t] for t in tracks])
    mu = X.mean(0)
    _, _, Vt = np.linalg.svd(X - mu, full_matrices=False)
    fig, ax = plt.subplots(figsize=figsize)
    for label, ts, color in groups:
        P = np.vstack([(E[t] - mu) @ Vt[:2].T for t in ts])
        if label in star:
            ax.scatter(P[:, 0], P[:, 1], s=90, marker="*", color=color, label=label, zorder=6, edgecolor="black", linewidth=0.5)
        else:
            ax.scatter(P[:, 0], P[:, 1], s=22, alpha=0.45, color=color, label=label)
        c = P.mean(0)
        ax.scatter(*c, s=260, marker="D", color=color, edgecolor="black", zorder=5)
    ax.set_title(title, fontsize=13)
    ax.legend(fontsize=9, loc="best")
    ax.set_xlabel("PC1 (이름 없는 합성축)"); ax.set_ylabel("PC2")
    plt.tight_layout()
    plt.savefig(OUT / f"{name}.png", dpi=120)
    plt.close()
    print("saved", name)


# 1) 폰 녹음 포함 — 밤편지 부른 전원 + 지유림(별)
draw("map_f_phone", [
    ("지유림 (폰 녹음)", ["지유림__밤편지"], "#000000"),
    ("정다운 (폰·노래방 MR)", ["정다운__밤편지"], "#7f3fbf"),
    ("경서", ["경서__밤편지"], "#bcbd22"),
    ("아이유 (4버전)", ["아이유__밤편지", "아이유__밤편지 라이브 1", "아이유__밤편지 라이브 2", "아이유__밤편지 콘서트 1"], "#d62728"),
    ("웬디", ["웬디__밤편지"], "#ff7f0e"),
    ("설윤", ["설윤__밤편지"], "#e377c2"),
    ("첸", ["첸__밤편지"], "#1f77b4"),
    ("규현", ["규현__밤편지"], "#17becf"),
    ("존박", ["존박__밤편지"], "#2ca02c"),
], "폰 녹음(★)은 어디에 떨어지나 — 밤편지 부른 전원", star=("지유림 (폰 녹음)", "정다운 (폰·노래방 MR)"))

# 2) 아이유 vs 이영현 (+ 경서: 아이유와 닮은 쪽 대조)
draw("map_lee_vs_iu", [
    ("아이유 (3곡)", ["아이유__밤편지", "아이유__금요일에 만나요", "아이유__어떻게 지내"], "#d62728"),
    ("이영현 (3곡)", ["이영현__My Way", "이영현__그래서 그대는", "이영현__이영현 – 헤어지자 말해요"], "#4B0082"),
    ("경서 (밤편지)", ["경서__밤편지"], "#bcbd22"),
], "다른 계열 여성은 갈라지는가 — 아이유 vs 이영현 (경서는 닮은 쪽 대조군)")

# 3) 아이유 환경 4버전 + 타인 대조
draw("map_env", [
    ("아이유 스튜디오", ["아이유__밤편지"], "#d62728"),
    ("아이유 라이브 1", ["아이유__밤편지 라이브 1"], "#ff6b4a"),
    ("아이유 라이브 2", ["아이유__밤편지 라이브 2"], "#ff9896"),
    ("아이유 콘서트", ["아이유__밤편지 콘서트 1"], "#8b0000"),
    ("웬디 (대조)", ["웬디__밤편지"], "#ff7f0e"),
    ("첸 (대조)", ["첸__밤편지"], "#1f77b4"),
], "환경(스튜디오/라이브/콘서트)이 지문을 얼마나 흔드나 — 같은 곡 4버전")

# 4) 제이플라 vs 솔라 — 같은 녹음 체인, 스타일만 변화
draw("map_style", [
    ("제이플라 (커버 3곡)", ["제이플라__Despacito", "제이플라__Havana", "제이플라__Shape Of You"], "#0E6E8C"),
    ("솔라 (커버 3곡)", ["솔라__Espresso", "솔라__Golden", "솔라__Take me to Church"], "#e07b39"),
    ("아이유 (스튜디오 3곡, 대조)", ["아이유__밤편지", "아이유__금요일에 만나요", "아이유__어떻게 지내"], "#d62728"),
], "같은 녹음 환경에서 곡(스타일)만 바뀔 때 — 일관형 vs 카멜레온형")
