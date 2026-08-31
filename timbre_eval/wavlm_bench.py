# 검토 11번: ECAPA vs 최신 화자검증 모델(WavLM-SV) 맞대결 — 동일 하니스(커버 십자검증)
# 코어 134트랙, 동일 조각(v2 사양)으로 임베딩만 교체. 지표: gap·AUC·selfID (스케일 무관 포함)
# 사용: python timbre_eval/wavlm_bench.py
from itertools import combinations
from pathlib import Path

import numpy as np
import librosa
import torch

from evaluate import SEP, SR
from sensitivity import load_chunks_v2

HERE = Path(__file__).resolve().parent
TRACKS = [t for t in (HERE / "sweep_tracks.txt").read_text(encoding="utf-8").splitlines() if t]
OUT = HERE / "emb_cache_sweep" / "wavlm_sv"
V2 = HERE / "emb_cache_v2" / "ecapa"
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


def embed_wavlm(chunks, fe, model):
    out = []
    for c in chunks:
        w = librosa.resample(c, orig_sr=SR, target_sr=16000)
        inp = fe(w, sampling_rate=16000, return_tensors="pt", padding=True)
        with torch.no_grad():
            e = model(inp.input_values.to(DEVICE)).embeddings
        out.append(e.squeeze().cpu().numpy())
    return np.stack(out)


def stats(E):
    meta = {t: t.split("__", 1) for t in E if not t.startswith(("지유림", "정다운"))}
    def cp(a, b):
        return float((E[a] @ E[b].T).mean())
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
    return np.mean(pos), np.mean(cov), np.mean(pos) - np.mean(cov), auc, hit, len(multi)


def main():
    from transformers import Wav2Vec2FeatureExtractor, WavLMForXVector
    fe = Wav2Vec2FeatureExtractor.from_pretrained("microsoft/wavlm-base-plus-sv")
    model = WavLMForXVector.from_pretrained("microsoft/wavlm-base-plus-sv").to(DEVICE).eval()

    OUT.mkdir(parents=True, exist_ok=True)
    E = {}
    for i, t in enumerate(TRACKS, 1):
        f = OUT / f"{t}.npy"
        if f.exists():
            E[t] = np.load(f)
            continue
        ch = load_chunks_v2(SEP / t / "vocals.wav")
        e = embed_wavlm(ch, fe, model)
        e = e / np.linalg.norm(e, axis=1, keepdims=True)
        np.save(f, e)
        E[t] = e
        if i % 20 == 0:
            print(f"  embed {i}/{len(TRACKS)}", flush=True)

    E0 = {t: np.load(V2 / f"{t}.npy") for t in TRACKS if (V2 / f"{t}.npy").exists()}
    for name, e in (("ECAPA(현행)", E0), ("WavLM-SV", E)):
        p, c, g, auc, h, n = stats(e)
        print(f"{name:<12} same={p:.3f} cover={c:.3f} gap={g:+.3f} AUC={auc:.3f} selfID={h}/{n}")


if __name__ == "__main__":
    main()
