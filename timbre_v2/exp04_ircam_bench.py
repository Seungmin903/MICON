# exp04: IRCAM/SonyCSL 가수 정체성 인코더 vs ECAPA — 동일 하니스(커버 십자검증, 코어 134)
# 후보: byol(논문상 OOD 일반화 최강), contrastive(도메인 내 최강). 44.1kHz 조각 그대로 입력.
# 사용: python timbre_v2/exp04_ircam_bench.py
import sys
from itertools import combinations
from pathlib import Path

import numpy as np
import torch

HERE = Path(__file__).resolve().parent
EVAL1 = HERE.parent / "timbre_eval"
sys.path.insert(0, str(EVAL1))
sys.path.insert(0, str(HERE / "vendor" / "ssl-singer-identity"))
from evaluate import SEP  # noqa: E402
from sensitivity import load_chunks_v2  # noqa: E402
from singer_identity import load_model  # noqa: E402

CACHE_ECAPA = EVAL1 / "emb_cache_v2" / "ecapa"
TRACKS = [t for t in (EVAL1 / "sweep_tracks.txt").read_text(encoding="utf-8").splitlines() if t]
DEVICE = "cuda" if torch.cuda.is_available() else "cpu"


def embed_all(model_name):
    out_dir = HERE / "emb_cache" / model_name
    out_dir.mkdir(parents=True, exist_ok=True)
    model = load_model(model_name).to(DEVICE).eval()
    E = {}
    for i, t in enumerate(TRACKS, 1):
        f = out_dir / f"{t}.npy"
        if f.exists():
            E[t] = np.load(f)
            continue
        ch = load_chunks_v2(SEP / t / "vocals.wav")  # 44.1kHz 그대로
        with torch.no_grad():
            x = torch.tensor(np.stack(ch), dtype=torch.float32, device=DEVICE)
            e = model(x).cpu().numpy()
        e = e / np.linalg.norm(e, axis=1, keepdims=True)
        np.save(f, e)
        E[t] = e
        if i % 20 == 0:
            print(f"  [{model_name}] {i}/{len(TRACKS)}", flush=True)
    return E


def bench(E):
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
    results = []
    E0 = {t: np.load(CACHE_ECAPA / f"{t}.npy") for t in TRACKS if (CACHE_ECAPA / f"{t}.npy").exists()}
    results.append(("ECAPA(현행)",) + bench(E0))
    for name in ("byol", "contrastive"):
        E = embed_all(name)
        results.append((f"IRCAM-{name}",) + bench(E))
    print()
    for name, p, c, g, auc, h, n in results:
        print(f"{name:<18} same={p:.3f} cover={c:.3f} gap={g:+.3f} AUC={auc:.3f} selfID={h}/{n}")


if __name__ == "__main__":
    main()
