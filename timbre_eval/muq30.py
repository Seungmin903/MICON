# 반론 #15 검증: MuQ를 학습 길이에 맞는 30초 조각으로 재평가
from itertools import combinations
from pathlib import Path

import numpy as np
import librosa
import torch

from evaluate import SEP, SR, DEVICE

CHUNK_S = 30.0
MAX_CHUNKS = 8
VOICED = 0.5  # 30초 창은 간주가 끼기 쉬워 60% -> 50%로 완화

def load_chunks30(vocals_path):
    y, _ = librosa.load(vocals_path, sr=SR, mono=True)
    n = int(CHUNK_S * SR)
    frame = 2048
    rms = librosa.feature.rms(y=y, frame_length=frame, hop_length=frame)[0]
    thr = np.percentile(rms, 90) * 0.15
    out = []
    for i in range(0, len(y) - n, n):
        seg = y[i : i + n]
        seg_rms = librosa.feature.rms(y=seg, frame_length=frame, hop_length=frame)[0]
        if (seg_rms > thr).mean() > VOICED:
            r = np.sqrt((seg ** 2).mean())
            out.append(seg * (0.1 / max(r, 1e-6)))
    return out[:MAX_CHUNKS]

from muq import MuQ

model = MuQ.from_pretrained("OpenMuQ/MuQ-large-msd-iter").to(DEVICE).eval()

E = {}
for d in sorted(SEP.iterdir()):
    v = d / "vocals.wav"
    if not v.exists() or d.name.startswith("지유림"):
        continue
    chunks = load_chunks30(v)
    if not chunks:
        print(f"{d.name}: 유효 30초 조각 없음 - 제외")
        continue
    embs = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=SR, target_sr=24000)
        with torch.no_grad():
            o = model(torch.tensor(w, device=DEVICE)[None])
        embs.append(o.last_hidden_state.mean(dim=1).squeeze().cpu().numpy())
    e = np.stack(embs)
    E[d.name] = e / np.linalg.norm(e, axis=1, keepdims=True)
    print(f"{d.name}: {len(chunks)} chunks")

meta = {t: t.split("__", 1) for t in E}
pos, hard = [], []
for a, b in combinations(E, 2):
    s1, g1 = meta[a]
    s2, g2 = meta[b]
    sims = (E[a] @ E[b].T).ravel()
    if s1 == s2 and g1 != g2:
        pos.extend(sims)
    elif s1 != s2 and g1 == g2:
        hard.extend(sims)
pos, hard = np.array(pos), np.array(hard)
scores = np.concatenate([pos, hard])
labels = np.concatenate([np.ones_like(pos), np.zeros_like(hard)])
order = np.argsort(-scores)
labels = labels[order]
tpr = np.cumsum(labels == 1) / (labels == 1).sum()
fpr = np.cumsum(labels == 0) / (labels == 0).sum()
idx = np.argmin(np.abs((1 - tpr) - fpr))
print(f"\n[MuQ 30초] same_singer={pos.mean():.3f}  cover={hard.mean():.3f}"
      f"  gap={pos.mean()-hard.mean():+.3f}  EER={((1-tpr)[idx]+fpr[idx])/2:.3f}")
print("(5초 결과: same=0.810 cover=0.803 gap=+0.007 EER=0.470)")
