# exp03: f0 잔차 제거를 전체 풀에 적용했을 때 밀도 스트레스(본인 재발견) 개선 여부
# 절차: 풀 전체 조각으로 피치 방향 재추정(트랙 중심화) → 전 트랙 잔차화 →
#        1차와 동일 채점(게이트 풀, 앨리어스 병합, 최근접 top-k)
# 기준값(원본, 2026-08-29): top-1 74.6%, top-5 88.1% (풀 619 시점) — 여기선 동일 절차로 전/후 병기
# 사용: python timbre_v2/exp03_residual_density.py
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


def main():
    E, pool = load_pool()
    ST = {}
    for t in pool:
        ch = json.loads((CACHE / f"{t}.meta.json").read_text(encoding="utf-8"))["chunks"]
        if len(ch) == len(E[t]):
            ST[t] = np.array([np.log2(c["f0_median_hz"]) * 12 if c["f0_median_hz"] else np.nan
                              for c in ch])
    pool = [t for t in pool if t in ST]

    dE, dp = [], []
    for t in pool:
        st, e = ST[t], E[t]
        ok = ~np.isnan(st)
        if ok.sum() < 6:
            continue
        dE.append(e[ok] - e[ok].mean(0))
        dp.append(st[ok] - st[ok].mean())
    dp = np.concatenate(dp)
    beta = (dp @ np.vstack(dE)) / (dp @ dp)
    print(f"피치 방향 재추정: 조각 {len(dp)}개, |beta|={np.linalg.norm(beta):.4f}")

    def residual(t):
        st, e = ST[t], E[t]
        dev = np.where(np.isnan(st), 0.0, st - np.nanmean(st))
        out = e - dev[:, None] * beta[None, :]
        return out / np.linalg.norm(out, axis=1, keepdims=True)

    def selfid(emb_of):
        M = np.stack([emb_of(t).mean(0) for t in pool])
        S = M @ M.T
        np.fill_diagonal(S, -9)
        sg = [ALIAS.get(t.split("__")[0], t.split("__")[0]) for t in pool]
        cnt = defaultdict(int)
        for s in sg:
            cnt[s] += 1
        idx = [i for i, s in enumerate(sg) if cnt[s] >= 2]
        rk = np.array([next(k for k, j in enumerate(np.argsort(-S[i]), 1) if sg[j] == sg[i])
                       for i in idx])
        return rk

    r_raw = selfid(lambda t: E[t])
    r_res = selfid(residual)
    n = len(r_raw)
    print(f"풀 {len(pool)}트랙, 다곡 질의 {n}개")
    for k in (1, 3, 5, 10):
        print(f"  top-{k}: 원본 {np.mean(r_raw<=k)*100:.1f}%  →  f0잔차 {np.mean(r_res<=k)*100:.1f}%")
    win = int(np.sum(r_res < r_raw)); lose = int(np.sum(r_res > r_raw))
    from scipy.stats import binomtest
    p = binomtest(win, win + lose).pvalue if win + lose else 1.0
    print(f"  짝지은 변화: 개선 {win} / 악화 {lose} / 동일 {n-win-lose}, 부호검정 p={p:.4f}")


if __name__ == "__main__":
    main()
