# 점수 정규화 (HANDOFF 7절 우선순위 2 — 학습 불필요)
#
# 결론 (2026-08-28 측정, 지유림 집↔노래방 쌍 1개 기준 — 강한 시사):
# - 권장 = znorm(전체코호트): z(u,t) = (s - μ_u) / σ_u, μ·σ는 u vs 추천풀 전체 점수 통계.
#   u 고정 시 단조 → 랭킹 완전 보존, 녹음 수준 인플레(집 0.176 vs 노래방 0.273)를 정의상 제거.
#   사용자에게 보여줄 "닮음 정도"는 raw 코사인이 아니라 이 z를 쓸 것.
# - top-K 변형은 과교정: znorm(top30) 격차 -0.610 sd [95% CI -0.773, -0.446] (raw +1.339 [+1.231, +1.453])
# - 표준 AS-norm(u+t 양쪽)은 격차 +0.673 sd로 절반만 교정 + t쪽 항이 랭킹을 흔듦
#   (raw↔asnorm 스피어만 0.63~0.85) — 흔든 랭킹의 좋고 나쁨은 지각 검증 전엔 판정 불가 → 채택 보류
# - 정규화가 못 고치는 것: 환경이 순위 자체를 바꾸는 상호작용.
#   z(집)↔z(노래방) 같은트랙 스피어만 0.696, 잔차 sd 0.834 — 노래방 녹음은 세부 순위가 실제로 다름.
#   해결책은 정규화가 아니라 온보딩 품질 사양("조용한 곳 무반주") 유지.
import json
import re
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"


def load_pool():
    """공용 추천 풀 게이트: 듀엣(메타)·로테이션 그룹(groups.txt)·환경변형판·사용자 녹음 제외."""
    E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}
    for f in CACHE.glob("*.meta.json"):
        name = f.stem.replace(".meta", "")
        if json.loads(f.read_text(encoding="utf-8"))["track"].get("duet_suspect"):
            E.pop(name, None)
    groups = set((HERE / "groups.txt").read_text(encoding="utf-8").split("\n")) - {""}
    duet_pat = re.compile(r"(,|&|feat\.?|듀엣|with )", re.IGNORECASE)  # 메타 미생성분 폴백
    pool = [t for t in E if "라이브" not in t and "콘서트" not in t
            and not t.startswith(("지유림", "정다운"))
            and t.split("__")[0] not in groups
            and not duet_pat.search(t.split("__")[0])]
    return E, pool


def cp(E, a, b):
    return float((E[a] @ E[b].T).mean())


def znorm_scores(E, u, pool):
    """권장 정규화: u의 풀 전체 점수를 z화. {트랙: z점수} 반환. 랭킹은 raw와 동일."""
    s = np.array([cp(E, u, t) for t in pool])
    z = (s - s.mean()) / max(s.std(), 1e-6)
    return dict(zip(pool, z.tolist()))


if __name__ == "__main__":
    E, pool = load_pool()
    print(f"추천 풀 {len(pool)}트랙")
    users = ["지유림__밤편지", "지유림__밤편지 노래방", "지유림__체념", "지유림__1,2,3,4", "정다운__밤편지"]
    print("\n[녹음별 top5 — z점수 (raw 랭킹과 동일, 절대값만 교정)]")
    for u in users:
        z = znorm_scores(E, u, pool)
        top = sorted(z, key=z.get, reverse=True)[:5]
        print(f"  {u.replace('__','·'):<14} " + ", ".join(f"{t.replace('__','·')}({z[t]:+.2f})" for t in top))
    # 자체 점검: 집/노래방 raw 평균 인플레와 z 소거 확인
    raw = {u: np.mean([cp(E, u, t) for t in pool]) for u in users[:2]}
    assert raw["지유림__밤편지 노래방"] > raw["지유림__밤편지"] + 0.05, "노래방 인플레 전제 깨짐 — 데이터 확인"
    z = {u: np.mean(list(znorm_scores(E, u, pool).values())) for u in users[:2]}
    assert all(abs(v) < 1e-9 for v in z.values())
    print("\nOK: raw 인플레 존재 + z 평균 0 확인")
