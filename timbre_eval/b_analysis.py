# B실험: 녹음체인 통제 — 같은 채널 커버(제이플라·솔라) 곡간 유사도로 순수 스타일 몫 측정
# 비교군: 아이유 스튜디오 곡간(체인 다름), 웬디 곡간(환경 혼합), 아이유 밤편지 4버전(환경만),
#         여-여 타인 쌍(정체성 격차)
from itertools import combinations
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache"

GROUPS = {
    "제이플라 곡간 (같은 체인)": [("제이플라", None)],
    "솔라 곡간 (같은 체인)": [("솔라", None)],
    "아이유 곡간 (스튜디오, 체인 다름)": [("아이유", ["밤편지", "금요일에 만나요", "어떻게 지내"])],
    "이영현 곡간": [("이영현", None)],
    "웬디 곡간 (환경 혼합)": [("웬디", None)],
    "아이유 환경만 (밤편지 4버전)": [("아이유", ["밤편지", "밤편지 라이브 1", "밤편지 라이브 2", "밤편지 콘서트 1"])],
}
FEMALES = ["아이유", "웬디", "설윤", "경서", "이영현", "솔라", "제이플라"]

for model in ["ecapa", "singer_id"]:
    d = CACHE / model
    E, meta = {}, {}
    for f in sorted(d.glob("*.npy")):
        singer, song = f.stem.split("__", 1)
        E[f.stem] = np.load(f)
        meta[f.stem] = (singer, song)

    def sim(a, b):
        return float((E[a] @ E[b].T).mean())

    def within(singer, songs=None):
        ts = [t for t, (s, g) in meta.items() if s == singer and (songs is None or g in songs)]
        return [sim(a, b) for a, b in combinations(ts, 2)]

    print(f"\n=== {model} ===")
    for label, spec in GROUPS.items():
        singer, songs = spec[0]
        v = within(singer, songs)
        if v:
            print(f"  {label}: 평균 {np.mean(v):.3f}  (개별 {' '.join(f'{x:.3f}' for x in sorted(v))})")
    cross = []
    for s1, s2 in combinations(FEMALES, 2):
        t1 = [t for t, (s, _) in meta.items() if s == s1]
        t2 = [t for t, (s, _) in meta.items() if s == s2]
        cross += [sim(a, b) for a in t1 for b in t2]
    print(f"  여-여 타인 쌍 전체: 평균 {np.mean(cross):.3f} (n={len(cross)})")
