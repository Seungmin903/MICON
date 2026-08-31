# 곡-조건부 음색 추천 데모: 사용자 지문 vs 각 트랙 보컬 지문(신호3) + 가수 centroid(신호4)
# 혼합점수 = 0.6*z(트랙) + 0.4*z(가수) — 신호별 z정규화 후 혼합 (초기 휴리스틱 가중치)
# raw 혼합은 스케일 혼용: sd(가수)≈2*sd(트랙)이라 명목 60:40이 실효 43:57로 뒤집혔었음 (2026-08-28 측정)
# 품질 게이트: duet_suspect 트랙은 추천 풀에서 제외 (여러 목소리의 평균 지문이라 매칭 무효)
import json
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"
USERS = ["지유림__밤편지", "지유림__밤편지 노래방", "지유림__체념", "지유림__1,2,3,4", "정다운__밤편지"]

E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}
excluded = []
for f in CACHE.glob("*.meta.json"):
    name = f.stem.replace(".meta", "")
    if json.loads(f.read_text(encoding="utf-8"))["track"].get("duet_suspect"):
        E.pop(name, None)
        excluded.append(name)
print(f"[게이트] 듀엣 플래그로 제외된 트랙 {len(excluded)}개: {', '.join(t.split('__')[0] for t in excluded)}")
# 환경 변형판(라이브·콘서트)은 실험 재료지 카탈로그 곡이 아님 — 곡당 스튜디오 정본만 사용
env_variants = [t for t in E if ("라이브" in t or "콘서트" in t)]
for t in env_variants:
    E.pop(t)
print(f"[게이트] 환경 변형판 제외 {len(env_variants)}개: {', '.join(env_variants)}")
singer_tracks = defaultdict(list)
for t in E:
    singer_tracks[t.split("__")[0]].append(t)

track_mean = {t: e.mean(0) / np.linalg.norm(e.mean(0)) for t, e in E.items()}
centroid = {s: (lambda m: m / np.linalg.norm(m))(np.mean([track_mean[t] for t in ts], 0))
            for s, ts in singer_tracks.items()}

for user in USERS:
    u_chunks = E[user]
    u_mean = track_mean[user]
    uname = user.split("__")[0]
    print(f"\n=== {uname} 님을 위한 곡 단위 추천 (음색 축만, 음역 필터 미적용) ===")
    rows = []
    for t in E:
        singer = t.split("__")[0]
        if singer == uname or singer in ("지유림", "정다운"):
            continue
        s_track = float((u_chunks @ E[t].T).mean())          # 신호3: 그 곡의 보컬과
        s_singer = float(u_mean @ centroid[singer])          # 신호4: 가수 전체와
        rows.append((s_track, s_singer, t))
    st = np.array([r[0] for r in rows])
    ss = np.array([r[1] for r in rows])
    zt = (st - st.mean()) / max(st.std(), 1e-6)
    zs = (ss - ss.mean()) / max(ss.std(), 1e-6)
    mixed = sorted(zip(0.6 * zt + 0.4 * zs, st, ss, (r[2] for r in rows)), reverse=True)
    print(f"{'혼합z':>6} {'트랙':>6} {'가수':>6}  곡")
    for mix, s3, s4, t in mixed:
        print(f"{mix:+6.2f} {s3:6.3f} {s4:6.3f}  {t.replace('__', ' · ')}")
