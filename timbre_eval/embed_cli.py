# 캐시에 없는 트랙만 임베딩해서 emb_cache/<model>/<track>.npy 로 저장
import argparse
from pathlib import Path

import numpy as np

from evaluate import EMBEDDERS, SEP, load_chunks

CACHE = Path(__file__).resolve().parent / "emb_cache"

ap = argparse.ArgumentParser()
ap.add_argument("--model", required=True, choices=list(EMBEDDERS))
args = ap.parse_args()

for d in sorted(SEP.iterdir()):
    v = d / "vocals.wav"
    out = CACHE / args.model / f"{d.name}.npy"
    if not v.exists() or out.exists():
        continue
    chunks = load_chunks(v)
    e = EMBEDDERS[args.model](chunks)
    e = e / np.linalg.norm(e, axis=1, keepdims=True)
    out.parent.mkdir(parents=True, exist_ok=True)
    np.save(out, e)
    print(f"cached {args.model}/{d.name} {e.shape}")
