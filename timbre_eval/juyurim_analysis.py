# 지유림 자체 통제 세트 분석
# 축1(환경): 밤편지 집 ↔ 밤편지 노래방 (같은 곡·같은 사람, 환경만 다름)
# 축2(곡+창법): 밤편지 ↔ 체념 ↔ 1,2,3,4 (같은 사람, 곡·창법 다름)
# 축3(안정성): 녹음마다 가수 랭킹 top5가 유지되는가
from itertools import combinations
from pathlib import Path

import numpy as np

CACHE = Path("timbre_eval/emb_cache_v2/ecapa")

E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}
MINE = sorted(t for t in E if t.startswith("지유림"))
print("지유림 트랙:", MINE)

def cp(a, b):  # 청크쌍 평균 (본 벤치마크 스케일)
    return float((E[a] @ E[b].T).mean())

print("\n[지유림 녹음 상호 유사도 (청크쌍 평균)]")
for a, b in combinations(MINE, 2):
    la, lb = a.split("__")[1], b.split("__")[1]
    tag = ""
    if {la, lb} == {"밤편지", "밤편지 노래방"}:
        tag = " ← 환경만 다름 (핵심)"
    print(f"  {cp(a,b):.3f}  {la} ↔ {lb}{tag}")

# 타인 기준선: 각 녹음의 타인 최고
others = {t: e for t, e in E.items() if not t.startswith(("지유림", "정다운"))}
tv = {t: (lambda m: m/np.linalg.norm(m))(e.mean(0)) for t, e in E.items()}
print("\n[각 녹음 vs 타인 최고 (청크쌍 평균)]")
for m in MINE:
    best = max((cp(m, o), o) for o in others)
    print(f"  {m.split('__')[1]}: 타인 최고 {best[0]:.3f} ({best[1].replace('__','·')})")

print("\n[녹음별 가수 랭킹 top5 — 안정성]")
from collections import defaultdict
by_singer = defaultdict(list)
for t in others:
    by_singer[t.split("__")[0]].append(t)
cent = {s: (lambda v: v/np.linalg.norm(v))(np.mean([tv[t] for t in ts], 0)) for s, ts in by_singer.items()}
for m in MINE:
    mv = tv[m]
    top = sorted(((float(mv @ c), s) for s, c in cent.items()), reverse=True)[:5]
    print(f"  {m.split('__')[1]}: " + ", ".join(f"{s}({v:.2f})" for v, s in top))
