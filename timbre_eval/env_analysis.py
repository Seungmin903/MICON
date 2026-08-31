# A실험: 환경 몫 분해 — 같은 사람·같은 곡·다른 환경 vs 스타일(다른 곡) vs 정체성(다른 사람)
# 모든 값은 청크쌍 평균 코사인 (기존 커버 벤치마크와 같은 스케일)
from itertools import combinations
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache"

IU_ENV = ["아이유__밤편지", "아이유__밤편지 라이브 1", "아이유__밤편지 라이브 2", "아이유__밤편지 콘서트 1"]
IU_SONGS = ["아이유__밤편지", "아이유__금요일에 만나요", "아이유__어떻게 지내"]
OTHERS = ["웬디__밤편지", "설윤__밤편지", "경서__밤편지", "첸__밤편지", "존박__밤편지", "규현__밤편지"]

for model in ["ecapa", "singer_id"]:
    E = {}
    for t in set(IU_ENV + IU_SONGS + OTHERS):
        f = CACHE / model / f"{t}.npy"
        if f.exists():
            E[t] = np.load(f)

    def sim(a, b):
        return float((E[a] @ E[b].T).mean())

    print(f"\n=== {model} ===")
    print("[환경만 다름 — 같은 사람·같은 곡]")
    env = []
    for a, b in combinations([t for t in IU_ENV if t in E], 2):
        s = sim(a, b)
        env.append(s)
        print(f"  {s:.3f}  {a.split('__')[1]} ↔ {b.split('__')[1]}")
    print("[스타일(+스튜디오 내 환경) — 같은 사람·다른 곡]")
    sty = []
    for a, b in combinations([t for t in IU_SONGS if t in E], 2):
        s = sim(a, b)
        sty.append(s)
        print(f"  {s:.3f}  {a.split('__')[1]} ↔ {b.split('__')[1]}")
    print("[정체성 — 다른 사람·같은 곡(밤편지)]")
    idn = []
    for o in OTHERS:
        if o in E:
            s = sim("아이유__밤편지", o)
            idn.append(s)
            print(f"  {s:.3f}  아이유 ↔ {o.split('__')[0]}")
    print(f"요약: 환경만={np.mean(env):.3f} | 스타일={np.mean(sty):.3f} | 타인={np.mean(idn):.3f}")
    print(f"몫 분해(1-유사도): 환경 {1-np.mean(env):.3f} | 스타일+환경 {1-np.mean(sty):.3f}"
          f" | 정체성까지 {1-np.mean(idn):.3f}")
