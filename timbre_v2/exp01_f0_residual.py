# exp01: f0 잔차 제거 — 피치 얽힘을 선형 성분만큼 벗겨내고 정체성 보존 여부 측정
# 설계: 조각 임베딩을 트랙별 중심화 → (조각편차, 조각 log2 f0 편차)로 피치 방향 회귀 추정
#       → 전 조각에서 그 성분 제거 → L2 재정규화. 전역 회귀 금지(성별=정체성까지 지움).
# 판정 지표 (전/후):
#   ① 피치 얽힘: 같은 트랙 내 조각쌍의 cos ↔ |Δ세미톤| 스피어만 (음수 클수록 얽힘)
#   ② 정체성: 커버 십자검증 gap·AUC + selfID (코어 134 동결세트)
#   ③ 음역 밴드: 같은 사람 저↔고 f0 밴드 유사도 (1차 0.816 — 오르면 성공 방향)
# 사용: python timbre_v2/exp01_f0_residual.py
import json
import sys
from itertools import combinations
from pathlib import Path

import numpy as np
from scipy.stats import spearmanr

HERE = Path(__file__).resolve().parent
EVAL1 = HERE.parent / "timbre_eval"
sys.path.insert(0, str(EVAL1))
CACHE = EVAL1 / "emb_cache_v2" / "ecapa"
CORE = [t for t in (EVAL1 / "sweep_tracks.txt").read_text(encoding="utf-8").splitlines() if t]


def load_with_f0(track):
    E = np.load(CACHE / f"{track}.npy")
    meta = json.loads((CACHE / f"{track}.meta.json").read_text(encoding="utf-8"))["chunks"]
    if len(meta) != len(E):
        return None
    f0 = np.array([c["f0_median_hz"] if c["f0_median_hz"] else np.nan for c in meta])
    return E, np.log2(f0) * 12  # 세미톤 척도


def fit_pitch_direction(tracks):
    """트랙 중심화된 (임베딩 편차, 세미톤 편차)로 피치 방향(192,) 회귀."""
    dE, dp = [], []
    for t in tracks:
        r = load_with_f0(t)
        if r is None:
            continue
        E, st = r
        ok = ~np.isnan(st)
        if ok.sum() < 6:
            continue
        E, st = E[ok], st[ok]
        dE.append(E - E.mean(0))
        dp.append(st - st.mean())
    dE, dp = np.vstack(dE), np.concatenate(dp)
    beta = (dp @ dE) / (dp @ dp)  # 각 차원 기울기 (임베딩단위/세미톤)
    return beta, len(dp)


def residualize(E, st, beta):
    ok = ~np.isnan(st)
    out = E.copy()
    dev = np.where(ok, st - np.nanmean(st), 0.0)
    out = out - dev[:, None] * beta[None, :]
    return out / np.linalg.norm(out, axis=1, keepdims=True)


def entanglement(tracks, emb):
    """같은 트랙 내 조각쌍 cos ↔ |Δ세미톤| 스피어만 (트랙별 계산 후 평균)."""
    rhos = []
    for t in tracks:
        r = load_with_f0(t)
        if r is None:
            continue
        _, st = r
        E = emb[t]
        ok = ~np.isnan(st)
        if ok.sum() < 8:
            continue
        E, st = E[ok], st[ok]
        cs, ds = [], []
        for i, j in combinations(range(len(E)), 2):
            cs.append(float(E[i] @ E[j]))
            ds.append(abs(st[i] - st[j]))
        rho = spearmanr(cs, ds).statistic
        if not np.isnan(rho):
            rhos.append(rho)
    return float(np.mean(rhos))


def bench(emb):
    meta = {t: t.split("__", 1) for t in emb if not t.startswith(("지유림", "정다운"))}
    def cp(a, b):
        return float((emb[a] @ emb[b].T).mean())
    pos, cov = [], []
    for a, b in combinations(meta, 2):
        s1, g1 = meta[a]; s2, g2 = meta[b]
        if s1 == s2 and g1 != g2:
            pos.append(cp(a, b))
        elif s1 != s2 and g1 == g2:
            cov.append(cp(a, b))
    auc = float(np.mean([[p > c for c in cov] for p in pos]))
    multi = [t for t in meta if sum(m[0] == meta[t][0] for m in meta.values()) >= 2]
    hit = sum(meta[max((cp(t, o), o) for o in meta if o != t)[1]][0] == meta[t][0] for t in multi)
    return np.mean(pos) - np.mean(cov), auc, hit, len(multi)


def band_sim(tracks, emb):
    """사람별 저/고 f0 밴드(개인 중앙값 기준) 조각 평균벡터 간 코사인 — 같은사람 vs 타인."""
    lo_hi = {}
    for t in tracks:
        r = load_with_f0(t)
        if r is None:
            continue
        _, st = r
        E = emb[t]
        ok = ~np.isnan(st)
        s = t.split("__")[0]
        lo_hi.setdefault(s, [[], []])
        med_all = np.nanmedian(st)
        lo_hi[s][0] += [E[i] for i in np.where(ok)[0] if st[i] <= med_all]
        lo_hi[s][1] += [E[i] for i in np.where(ok)[0] if st[i] > med_all]
    vecs = {s: [(lambda m: m / np.linalg.norm(m))(np.mean(v, 0)) for v in lh]
            for s, lh in lo_hi.items() if len(lh[0]) >= 5 and len(lh[1]) >= 5}
    same = [float(v[0] @ v[1]) for v in vecs.values()]
    other = [float(vecs[a][0] @ vecs[b][1]) for a, b in combinations(vecs, 2)]
    return np.mean(same), np.mean(other)


def main():
    core = [t for t in CORE if (CACHE / f"{t}.meta.json").exists()]
    beta, n = fit_pitch_direction(core)
    print(f"피치 방향 추정: 조각 {n}개, |beta|={np.linalg.norm(beta):.4f} (임베딩단위/세미톤)")

    raw, res = {}, {}
    for t in core:
        r = load_with_f0(t)
        if r is None:
            continue
        E, st = r
        raw[t] = E
        res[t] = residualize(E, st, beta)

    for name, emb in (("원본", raw), ("f0잔차 제거", res)):
        rho = entanglement(list(emb), emb)
        gap, auc, h, m = bench(emb)
        bs, bo = band_sim(list(emb), emb)
        print(f"[{name}] 트랙내 cos↔|Δst| ρ={rho:+.3f} | gap={gap:+.3f} AUC={auc:.3f} "
              f"selfID={h}/{m} | 저↔고밴드 같은사람 {bs:.3f} / 타인 {bo:.3f}")


if __name__ == "__main__":
    main()
