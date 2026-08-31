# F실험: 폰 녹음(지유림 밤편지) vs 스튜디오/라이브 가수들 — 도메인 갭에서 비교가 성립하는가
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache"

USERS = ["지유림__밤편지", "정다운__밤편지"]
REFS = ["아이유__밤편지", "웬디__밤편지", "설윤__밤편지", "경서__밤편지",
        "첸__밤편지", "존박__밤편지", "규현__밤편지",
        "아이유__밤편지 라이브 1", "아이유__밤편지 라이브 2", "아이유__밤편지 콘서트 1"]

for model in ["ecapa", "singer_id"]:
    d = CACHE / model
    for USER in USERS:
        if not (d / f"{USER}.npy").exists():
            print(f"{model}: {USER} 임베딩 없음")
            continue
        u = np.load(d / f"{USER}.npy")
        print(f"\n=== {model} — {USER.split('__')[0]}(폰 녹음) vs 같은 곡 부른 가수들, 가까운 순 ===")
        print(f"  (사용자 청크 수: {len(u)})")
        rows = []
        for r in REFS:
            f = d / f"{r}.npy"
            if f.exists():
                rows.append((float((u @ np.load(f).T).mean()), r))
        for s, r in sorted(rows, reverse=True):
            print(f"  {s:.3f}  {r.replace('__', ' · ')}")
        self_sim = float((u @ u.T).mean())
        print(f"  (참고: 본인 청크 내부 평균 {self_sim:.3f} — 유사도 천장 감각용)")
    # 폰 녹음끼리
    a, b = (d / f"{USERS[0]}.npy"), (d / f"{USERS[1]}.npy")
    if a.exists() and b.exists():
        s = float((np.load(a) @ np.load(b).T).mean())
        print(f"  [폰끼리] 지유림 ↔ 정다운: {s:.3f}")
