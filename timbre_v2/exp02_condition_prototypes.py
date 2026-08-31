# exp02: 조건별 프로토타입 — 가수를 f0 밴드별 대표 지문(저/고)으로 층화하면
#        통짜 centroid보다 본인 재발견(leave-one-track-out)이 좋아지는가
# 방식 A(기존): 질의 트랙 평균벡터 vs 가수 통짜 centroid(질의 제외)
# 방식 B(층화): 질의 트랙의 저/고밴드 벡터 vs 가수의 저/고밴드 프로토타입(질의 제외), 밴드 일치 코사인 평균
#   밴드 기준 = 그 가수의 (질의 제외) 전 조각 f0 중앙값. RMS 축은 다음 단계(f0 먼저).
# 대상: 게이트 후 풀(그룹·듀엣·라이브 제외)의 다곡가수. 앨리어스 병합은 밀도실험과 동일.
# 사용: python timbre_v2/exp02_condition_prototypes.py
import json
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np

HERE = Path(__file__).resolve().parent
EVAL1 = HERE.parent / "timbre_eval"
sys.path.insert(0, str(EVAL1))
from asnorm import load_pool  # noqa: E402

CACHE = EVAL1 / "emb_cache_v2" / "ecapa"
ALIAS = {"브라운아이즈": "나얼", "MC THE MAX": "이수", "윤도현밴드": "윤도현", "부활": "정동하"}


def norm(v):
    return v / max(np.linalg.norm(v), 1e-9)


def main():
    E, pool = load_pool()
    ST = {}  # 트랙별 조각 f0(세미톤)
    for t in pool:
        f = CACHE / f"{t}.meta.json"
        ch = json.loads(f.read_text(encoding="utf-8"))["chunks"]
        if len(ch) != len(E[t]):
            continue
        ST[t] = np.array([np.log2(c["f0_median_hz"]) * 12 if c["f0_median_hz"] else np.nan for c in ch])
    tracks = [t for t in pool if t in ST]
    by = defaultdict(list)
    for t in tracks:
        by[ALIAS.get(t.split("__")[0], t.split("__")[0])].append(t)
    multi = {s: ts for s, ts in by.items() if len(ts) >= 2}
    singers = sorted(by)

    def band_vecs(chunk_E, chunk_st, thr):
        lo = chunk_E[~np.isnan(chunk_st) & (chunk_st <= thr)]
        hi = chunk_E[~np.isnan(chunk_st) & (chunk_st > thr)]
        return (norm(lo.mean(0)) if len(lo) >= 3 else None,
                norm(hi.mean(0)) if len(hi) >= 3 else None)

    ranksA, ranksB = [], []
    skipped = 0
    for s, ts in multi.items():
        for q in ts:
            rest = {x: [t for t in by[x] if t != q] for x in singers}
            rest = {x: v for x, v in rest.items() if v}
            # 방식 A: 통짜 centroid
            qv = norm(E[q].mean(0))
            scA = {x: float(qv @ norm(np.mean([norm(E[t].mean(0)) for t in v], 0)))
                   for x, v in rest.items()}
            # 방식 B: f0 밴드 프로토타입 (기준 = 그 가수의 질의 제외 조각 중앙값)
            scB = {}
            q_st = ST[q]
            for x, v in rest.items():
                all_st = np.concatenate([ST[t] for t in v])
                thr = np.nanmedian(all_st)
                pE = np.vstack([E[t] for t in v])
                p_st = np.concatenate([ST[t] for t in v])
                p_lo, p_hi = band_vecs(pE, p_st, thr)
                q_lo, q_hi = band_vecs(E[q], q_st, thr)
                sims = [float(a @ b) for a, b in ((q_lo, p_lo), (q_hi, p_hi))
                        if a is not None and b is not None]
                if sims:
                    scB[x] = float(np.mean(sims))
            if s not in scB:
                skipped += 1
                continue
            ranksA.append(1 + sum(v > scA[s] for x, v in scA.items() if x != s))
            ranksB.append(1 + sum(v > scB[s] for x, v in scB.items() if x != s))

    ranksA, ranksB = np.array(ranksA), np.array(ranksB)
    n = len(ranksA)
    print(f"질의 {n}트랙 (밴드 부족 스킵 {skipped}), 가수 후보 {len(singers)}명")
    for k in (1, 3, 5):
        print(f"  top-{k}: 통짜 {np.mean(ranksA<=k)*100:.1f}%  vs  층화 {np.mean(ranksB<=k)*100:.1f}%")
    win = int(np.sum(ranksB < ranksA)); lose = int(np.sum(ranksB > ranksA))
    print(f"  짝지은 순위 변화: 층화 승 {win} / 패 {lose} / 무 {n-win-lose}")
    if win + lose:
        from scipy.stats import binomtest
        print(f"  부호검정 p = {binomtest(win, win+lose).pvalue:.4f}")


if __name__ == "__main__":
    main()
