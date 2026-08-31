# 27개 트랙 전부를 '가짜 사용자'로: 30초 창만 잘라 임베딩 -> 전곡 갤러리에서 자기 자신을 찾는가
# + 마진 분포 (자기유사도 vs 최근접 타인) — 갤러리가 커질 때의 여유 측정
from pathlib import Path

import numpy as np

from thirty_sec_test import E, embed_window
from evaluate import SEP

track_vec = {t: e.mean(0) / np.linalg.norm(e.mean(0)) for t, e in E.items()}
ALL = list(E)

results = []
for t in ALL:
    w = embed_window(SEP / t / "vocals.wav", 90, 120)
    if w is None or len(w) < 2:
        w = embed_window(SEP / t / "vocals.wav", 60, 90)
    if w is None or len(w) < 2:
        print(f"{t}: 유효 창 없음 - 제외")
        continue
    wv = w.mean(0) / np.linalg.norm(w.mean(0))
    self_sim = float(wv @ track_vec[t])
    others = sorted(((float(wv @ track_vec[o]), o) for o in ALL if o != t), reverse=True)
    rank = 1 + sum(1 for s, _ in others if s > self_sim)
    margin = self_sim - others[0][0]
    results.append((t, rank, self_sim, margin, others[0][1]))

ok = sum(1 for _, r, _, _, _ in results if r == 1)
print(f"\n자기 식별 성공: {ok}/{len(results)} 트랙")
margins = np.array([m for _, r, _, m, _ in results])
print(f"마진 분포: 중앙값 {np.median(margins):+.3f} | 최소 {margins.min():+.3f} | 최대 {margins.max():+.3f}")
print("\n마진 얇은 순 (위험 순):")
for t, rank, ss, m, nn in sorted(results, key=lambda x: x[3])[:8]:
    flag = "" if rank == 1 else f" ← {rank}위로 밀림!"
    print(f"  {m:+.3f}  {t.replace('__','·')} (자기 {ss:.3f}, 최근접타인 {nn.replace('__','·')}){flag}")
