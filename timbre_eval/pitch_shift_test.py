# 피치 인과 테스트: 같은 트랙을 +3반음 이동시켜도 모델이 같은 사람으로 보는가
# 사용: python pitch_shift_test.py --model ecapa|singer_id
import argparse

import numpy as np
import librosa

from evaluate import EMBEDDERS, SEP, SR, load_chunks

N_CHUNKS = 8
SHIFT_ST = 3

ap = argparse.ArgumentParser()
ap.add_argument("--model", required=True, choices=list(EMBEDDERS))
args = ap.parse_args()
embed = EMBEDDERS[args.model]

tracks = {}
for d in sorted(SEP.iterdir()):
    if (d / "vocals.wav").exists():
        tracks[d.name] = load_chunks(d / "vocals.wav")[:N_CHUNKS]

orig, shifted = {}, {}
for name, chunks in tracks.items():
    sh = [librosa.effects.pitch_shift(c, sr=SR, n_steps=SHIFT_ST) for c in chunks]
    e_o = embed(chunks)
    e_s = embed(sh)
    orig[name] = (e_o / np.linalg.norm(e_o, axis=1, keepdims=True)).mean(0)
    shifted[name] = (e_s / np.linalg.norm(e_s, axis=1, keepdims=True)).mean(0)
    print(f"embedded {name}")

def cos(a, b):
    return float(a @ b / (np.linalg.norm(a) * np.linalg.norm(b)))

print(f"\n=== {args.model}: 자기 자신 +{SHIFT_ST}st vs 타인(원본) ===")
self_sims, other_sims = [], []
for n1 in tracks:
    s_self = cos(orig[n1], shifted[n1])
    others = [cos(orig[n1], orig[n2]) for n2 in tracks if n2.split("__")[0] != n1.split("__")[0]]
    self_sims.append(s_self)
    other_sims.append(np.mean(others))
    print(f"  {n1}: 자기+3st={s_self:.3f} | 타인 평균={np.mean(others):.3f} | 타인 최고={max(others):.3f}")
print(f"\n평균: 자기+3st={np.mean(self_sims):.3f} vs 타인={np.mean(other_sims):.3f}")
print("자기+3st가 타인 최고보다 낮아진 트랙 수:",
      sum(1 for n1, s in zip(tracks, self_sims)
          if s < max(cos(orig[n1], orig[n2]) for n2 in tracks if n2.split("__")[0] != n1.split("__")[0])),
      "/", len(tracks))
