# 잔향 매칭 가설 확인: 노래방 녹음이 아이유의 라이브(잔향) 버전들과 유독 가까운가
from pathlib import Path

import numpy as np

C = Path("timbre_eval/emb_cache_v2/ecapa")
E = {f.stem: np.load(f) for f in C.glob("*.npy")}


def cp(a, b):
    return float((E[a] @ E[b].T).mean())


IU = ["아이유__밤편지", "아이유__밤편지 라이브 1", "아이유__밤편지 라이브 2", "아이유__밤편지 콘서트 1"]
for user in ["지유림__밤편지", "지유림__밤편지 노래방", "지유림__체념", "정다운__밤편지"]:
    vals = [(cp(user, t), t.split("__")[1]) for t in IU]
    studio = vals[0][0]
    live_mean = np.mean([v for v, n in vals[1:]])
    print(f"{user.split('__')[1]:<10} 스튜디오 {studio:.3f} | 라이브 평균 {live_mean:.3f} | 차이 {live_mean-studio:+.3f}")
    for v, n in vals:
        print(f"    {v:.3f}  {n}")
