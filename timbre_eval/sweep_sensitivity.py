# 파이프라인 확정 전 민감도 스윕 (검토 목록 1~5번, 코어 134트랙 동결 세트)
#   1. 창 길이 5/10/20초   3. 조각 상한 24/48   5. 전처리 상수(문턱·유성·RMS) ±2배
#      -> 변형별 재임베딩 (GPU, emb_cache_sweep/에 캐시 — 중단 후 재실행 시 이어감)
#   2. 유사도 집계 mean/절사/중앙값   4. centroid 트랙평균 vs 전조각
#      -> 기존 v2 캐시로 사후 계산 (재임베딩 없음)
# 지표: 커버 십자검증 gap(같은가수-커버쌍) + self-ID(최근접=본인 비율, 다곡가수만)
# 사용: python timbre_eval/sweep_sensitivity.py  (GPU 한가할 때)
import json
from itertools import combinations
from pathlib import Path

import numpy as np
import librosa

from evaluate import embed_ecapa, SEP, SR

HERE = Path(__file__).resolve().parent
TRACKS = [t for t in (HERE / "sweep_tracks.txt").read_text(encoding="utf-8").splitlines() if t]
SWEEP = HERE / "emb_cache_sweep"
V2 = HERE / "emb_cache_v2" / "ecapa"
EXCLUDE = ("지유림", "정다운")  # 폰 녹음은 벤치마크 제외 (기존 관례)


def load_chunks(vocals_path, chunk_s=5.0, max_chunks=24, thr_mult=0.15, voiced_min=0.6, rms_target=0.1):
    y, _ = librosa.load(vocals_path, sr=SR, mono=True)
    n = int(chunk_s * SR)
    frame = 2048
    rms = librosa.feature.rms(y=y, frame_length=frame, hop_length=frame)[0]
    thr = np.percentile(rms, 90) * thr_mult
    cands = []
    for i in range(0, len(y) - n, n):
        seg = y[i : i + n]
        seg_rms = librosa.feature.rms(y=seg, frame_length=frame, hop_length=frame)[0]
        if (seg_rms > thr).mean() > voiced_min:
            cands.append(seg)
    if len(cands) > max_chunks:
        idx = np.linspace(0, len(cands) - 1, max_chunks).astype(int)
        cands = [cands[i] for i in idx]
    return [seg * (rms_target / max(np.sqrt((seg ** 2).mean()), 1e-6)) for seg in cands]


def embed_variant(tag, **kw):
    out = SWEEP / tag
    out.mkdir(parents=True, exist_ok=True)
    E = {}
    for t in TRACKS:
        f = out / f"{t}.npy"
        if f.exists():
            E[t] = np.load(f)
            continue
        ch = load_chunks(SEP / t / "vocals.wav", **kw)
        if not ch:
            print(f"  [경고] {t}: 합격 조각 0개 — 제외")
            continue
        e = embed_ecapa(ch)
        e = e / np.linalg.norm(e, axis=1, keepdims=True)
        np.save(f, e)
        E[t] = e
    return E


def agg_sims(a, b, how):
    v = (a @ b.T).ravel()
    if how == "mean":
        return float(v.mean())
    if how == "trim20":
        k = max(1, int(len(v) * 0.1))
        return float(np.sort(v)[k:-k].mean())
    return float(np.median(v))


def bench(E, how="mean"):
    meta = {t: t.split("__", 1) for t in E if not t.startswith(EXCLUDE)}
    sim = {}
    def S(a, b):
        key = (a, b) if a < b else (b, a)
        if key not in sim:
            sim[key] = agg_sims(E[key[0]], E[key[1]], how)
        return sim[key]
    pos, cover = [], []
    for a, b in combinations(meta, 2):
        s1, g1 = meta[a]; s2, g2 = meta[b]
        if s1 == s2 and g1 != g2:
            pos.append(S(a, b))
        elif s1 != s2 and g1 == g2:
            cover.append(S(a, b))
    multi = {t for t in meta if sum(m[0] == meta[t][0] for m in meta.values()) >= 2}
    hit = 0
    for t in multi:
        best = max((S(t, o), o) for o in meta if o != t)
        hit += meta[best[1]][0] == meta[t][0]
    return (np.mean(pos), np.mean(cover), np.mean(pos) - np.mean(cover),
            hit, len(multi))


def centroid_eval(E, mode):
    meta = {t: t.split("__", 1) for t in E if not t.startswith(EXCLUDE)}
    tm = {t: (lambda m: m / np.linalg.norm(m))(E[t].mean(0)) for t in meta}
    by = {}
    for t, (s, _) in meta.items():
        by.setdefault(s, []).append(t)
    multi = [t for t in meta if len(by[meta[t][0]]) >= 2]
    hit = 0
    for t in multi:
        best_s, best_v = None, -2
        for s, ts in by.items():
            rest = [x for x in ts if x != t]
            if not rest:
                continue
            if mode == "trackmean":
                c = np.mean([tm[x] for x in rest], 0)
            else:
                c = np.concatenate([E[x] for x in rest]).mean(0)
            c = c / np.linalg.norm(c)
            v = float(tm[t] @ c)
            if v > best_v:
                best_v, best_s = v, s
        hit += best_s == meta[t][0]
    return hit, len(multi)


def main():
    results = []

    print("== 사후 계산 (v2 캐시, 재임베딩 없음) ==")
    E0 = {t: np.load(V2 / f"{t}.npy") for t in TRACKS if (V2 / f"{t}.npy").exists()}
    for how in ("mean", "trim20", "median"):
        p, c, g, h, n = bench(E0, how)
        results.append((f"집계={how}", p, c, g, h, n))
        print(f"  집계={how:<7} same={p:.3f} cover={c:.3f} gap={g:+.3f} selfID={h}/{n}")
    for mode in ("trackmean", "allchunks"):
        h, n = centroid_eval(E0, mode)
        results.append((f"centroid={mode}", None, None, None, h, n))
        print(f"  centroid={mode:<10} 가수분류 {h}/{n}")

    variants = [
        ("base(5s,24,0.15,0.6,0.1)", {}),
        ("chunk10s", {"chunk_s": 10.0}),
        ("chunk20s", {"chunk_s": 20.0}),
        ("max48", {"max_chunks": 48}),
        ("thr_x0.5", {"thr_mult": 0.075}),
        ("thr_x2", {"thr_mult": 0.30}),
        ("voiced0.4", {"voiced_min": 0.4}),
        ("voiced0.8", {"voiced_min": 0.8}),
        ("rms0.05", {"rms_target": 0.05}),
        ("rms0.2", {"rms_target": 0.2}),
    ]
    print("\n== 재임베딩 변형 (GPU) ==")
    for tag, kw in variants:
        E = embed_variant(tag, **kw)
        p, c, g, h, n = bench(E)
        results.append((tag, p, c, g, h, n))
        print(f"  {tag:<24} same={p:.3f} cover={c:.3f} gap={g:+.3f} selfID={h}/{n}", flush=True)

    md = ["# 민감도 스윕 결과", "", "| 변형 | same | cover | gap | self-ID |", "|---|---|---|---|---|"]
    for tag, p, c, g, h, n in results:
        md.append(f"| {tag} | {'' if p is None else f'{p:.3f}'} | {'' if c is None else f'{c:.3f}'} |"
                  f" {'' if g is None else f'{g:+.3f}'} | {h}/{n} |")
    (HERE / "results" / "sweep_sensitivity.md").write_text("\n".join(md) + "\n", encoding="utf-8")
    print("\n저장: results/sweep_sensitivity.md")


if __name__ == "__main__":
    main()
