# 모델 수준 검증: 30초 창 벡터의 최근접 트랙이 여전히 '전곡의 나'인가 (self-identification)
from pathlib import Path

import numpy as np

from thirty_sec_test import E, USERS, WINDOWS, embed_window, SEP

CACHE_KEYS = [t for t in E]  # 전 트랙 (폰 녹음 포함)

track_vec = {t: e.mean(0) / np.linalg.norm(e.mean(0)) for t, e in E.items()}

for user in USERS:
    print(f"\n=== {user.split('__')[0]} ===")
    for t0, t1 in WINDOWS:
        w = embed_window(SEP / user / "vocals.wav", t0, t1)
        if w is None:
            continue
        wv = w.mean(0) / np.linalg.norm(w.mean(0))
        sims = sorted(((float(wv @ track_vec[t]), t) for t in CACHE_KEYS), reverse=True)
        rank_self = [t for _, t in sims].index(user) + 1
        top = sims[0]
        second = sims[1]
        print(f"  [{t0}-{t1}s] 자기 자신 순위: {rank_self}위"
              f" | 1위: {top[1].replace('__','·')} ({top[0]:.3f})"
              f" | 2위: {second[1].replace('__','·')} ({second[0]:.3f})")
