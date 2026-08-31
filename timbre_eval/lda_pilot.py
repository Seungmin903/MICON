# 백엔드 투영(LDA) 파일럿: 카탈로그 가수 라벨로 얇은 변환층 학습 -> 커버 벤치마크로 채점
# 순환 방지: 학습 가수와 평가 가수(밤편지 커버 세트 + 웬디/아이유)를 분리
from collections import defaultdict
from itertools import combinations
from pathlib import Path

import numpy as np
from scipy.linalg import eigh

CACHE = Path("timbre_eval/emb_cache_v2/ecapa")
import json

E, singer_of = {}, {}
for f in CACHE.glob("*.npy"):
    name = f.stem
    mf = CACHE / f"{name}.meta.json"
    if mf.exists() and json.loads(mf.read_text(encoding="utf-8"))["track"].get("duet_suspect"):
        continue
    if name.startswith(("지유림", "정다운")):
        continue
    E[name] = np.load(f)
    singer_of[name] = name.split("__")[0]

by_singer = defaultdict(list)
for t, s in singer_of.items():
    by_singer[s].append(t)

# 평가 세트: 밤편지 커버 관련 가수 (여기 가수들은 학습에서 제외)
EVAL_SINGERS = {"아이유", "웬디", "경서", "설윤", "첸", "존박", "규현", "크러쉬"}
train_singers = [s for s, ts in by_singer.items() if len(ts) >= 2 and s not in EVAL_SINGERS]
print(f"학습 가수 {len(train_singers)}명: {train_singers}")

# LDA: 트랙 평균 벡터를 표본으로 (조각 잡음이 아니라 트랙 간 변동을 클래스 내 산포로 학습)
Xw, yw = [], []
for i, s in enumerate(train_singers):
    for t in by_singer[s]:
        v = E[t].mean(0)
        Xw.append(v / np.linalg.norm(v))
        yw.append(i)
Xw = np.vstack(Xw)
yw = np.array(yw)
mu = Xw.mean(0)
Sw = np.zeros((192, 192))
Sb = np.zeros((192, 192))
for i in range(len(train_singers)):
    Xi = Xw[yw == i]
    mi = Xi.mean(0)
    Sw += (Xi - mi).T @ (Xi - mi)
    Sb += len(Xi) * np.outer(mi - mu, mi - mu)
Sw += np.eye(192) * (np.trace(Sw) / 192 * 0.1)  # 축소 정규화 (표본 부족 보정)
vals, vecs = eigh(Sb, Sw)
dim = min(len(train_singers) - 1, 12)
Wp = vecs[:, -dim:]  # 상위 판별 방향
print(f"LDA 차원: {dim}")


def project(e):
    p = e @ Wp
    return p / np.linalg.norm(p, axis=-1, keepdims=True)


def benchmark(transform):
    pos, hard = [], []
    tracks = {t: (singer_of[t], t.split("__", 1)[1]) for t in E
              if singer_of[t] in EVAL_SINGERS and "라이브" not in t and "콘서트" not in t}
    for a, b in combinations(tracks, 2):
        s1, g1 = tracks[a]
        s2, g2 = tracks[b]
        sims = (transform(E[a]) @ transform(E[b]).T).ravel()
        if s1 == s2 and g1 != g2:
            pos.extend(sims)
        elif s1 != s2 and g1 == g2:
            hard.extend(sims)
    pos, hard = np.array(pos), np.array(hard)
    scores = np.concatenate([pos, hard])
    labels = np.concatenate([np.ones_like(pos), np.zeros_like(hard)])
    o = np.argsort(-scores)
    labels = labels[o]
    tpr = np.cumsum(labels == 1) / (labels == 1).sum()
    fpr = np.cumsum(labels == 0) / (labels == 0).sum()
    i = np.argmin(np.abs((1 - tpr) - fpr))
    return pos.mean(), hard.mean(), ((1 - tpr)[i] + fpr[i]) / 2


for label, tf in [("원본 192차원", lambda e: e / np.linalg.norm(e, axis=-1, keepdims=True)),
                  (f"LDA {dim}차원", project)]:
    p, h, eer = benchmark(tf)
    print(f"{label}: 같은가수 {p:.3f} | 커버쌍 {h:.3f} | 간격 {p-h:+.3f} | EER {eer:.3f}")
