# 팀원 의문 검증: 입력이 30초뿐이어도 유의미한 비교가 되는가
# 방법: 폰 녹음에서 서로 다른 30초 창을 잘라 각각 임베딩 -> 전곡 입력과 (1)벡터 유사도 (2)추천 순위 상관 비교
from pathlib import Path

import numpy as np
import librosa
from scipy.stats import spearmanr

from evaluate import embed_ecapa, SEP, SR

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"
USERS = ["지유림__밤편지", "정다운__밤편지"]
WINDOWS = [(30, 60), (90, 120), (150, 180)]  # 초 단위: 1절 / 후렴부근 / 후반

E = {f.stem: np.load(f) for f in CACHE.glob("*.npy")}
REFS = [t for t in E if t.split("__")[0] not in ("지유림", "정다운")]


def embed_window(vocals, t0, t1):
    y, _ = librosa.load(vocals, sr=SR, mono=True, offset=t0, duration=t1 - t0)
    n = int(5 * SR)
    frame = 2048
    rms = librosa.feature.rms(y=y, frame_length=frame, hop_length=frame)[0]
    thr = np.percentile(rms, 90) * 0.15
    chunks = []
    for i in range(0, len(y) - n, n):
        seg = y[i : i + n]
        seg_rms = librosa.feature.rms(y=seg, frame_length=frame, hop_length=frame)[0]
        if (seg_rms > thr).mean() > 0.6:
            r = np.sqrt((seg ** 2).mean())
            chunks.append(seg * (0.1 / max(r, 1e-6)))
    if not chunks:
        return None
    e = embed_ecapa(chunks)
    return e / np.linalg.norm(e, axis=1, keepdims=True)


def ranking(u_emb):
    return {r: float((u_emb @ E[r].T).mean()) for r in REFS}


for user in USERS:
    full = E[user]
    full_rank = ranking(full)
    full_order = sorted(full_rank, key=full_rank.get, reverse=True)
    full_vec = full.mean(0) / np.linalg.norm(full.mean(0))
    print(f"\n=== {user.split('__')[0]} — 전곡(조각 {len(full)}개) vs 30초 창들 ===")
    print(f"  전곡 top3: {[t.replace('__','·') for t in full_order[:3]]}")
    for t0, t1 in WINDOWS:
        w = embed_window(SEP / user / "vocals.wav", t0, t1)
        if w is None:
            print(f"  [{t0}-{t1}s] 유효 조각 없음")
            continue
        w_rank = ranking(w)
        w_order = sorted(w_rank, key=w_rank.get, reverse=True)
        rho, _ = spearmanr([full_rank[r] for r in REFS], [w_rank[r] for r in REFS])
        w_vec = w.mean(0) / np.linalg.norm(w.mean(0))
        top3 = len(set(full_order[:3]) & set(w_order[:3]))
        print(f"  [{t0}-{t1}s] 조각 {len(w)}개 | 전곡벡터와 cos={float(full_vec @ w_vec):.3f}"
              f" | 순위 상관 ρ={rho:.3f} | top3 일치 {top3}/3 | 이 창의 top3: {[t.replace('__','·') for t in w_order[:3]]}")
