# 재검증: 전처리 민감도 실험 — 청크 표집(앞부분 편향 -> 곡 전체 균등) + 음량 정규화 후
# ECAPA 핵심 수치가 유지되는지. 기존 결론이 전처리 선택의 산물이면 여기서 무너진다.
from itertools import combinations
from pathlib import Path

import numpy as np
import librosa

from evaluate import embed_ecapa, SEP, SR, CHUNK_S, MAX_CHUNKS

HERE = Path(__file__).resolve().parent
OUT = HERE / "emb_cache_v2" / "ecapa"
OUT.mkdir(parents=True, exist_ok=True)


def load_chunks_v2(vocals_path):
    """v2: 유효 청크를 곡 전체에서 균등 간격으로 표집 + RMS 정규화."""
    y, _ = librosa.load(vocals_path, sr=SR, mono=True)
    n = int(CHUNK_S * SR)
    frame = 2048
    rms = librosa.feature.rms(y=y, frame_length=frame, hop_length=frame)[0]
    thr = np.percentile(rms, 90) * 0.15
    cands = []
    for i in range(0, len(y) - n, n):
        seg = y[i : i + n]
        seg_rms = librosa.feature.rms(y=seg, frame_length=frame, hop_length=frame)[0]
        if (seg_rms > thr).mean() > 0.6:
            cands.append(seg)
    if len(cands) > MAX_CHUNKS:  # 앞 24개가 아니라 전체에서 균등 추출
        idx = np.linspace(0, len(cands) - 1, MAX_CHUNKS).astype(int)
        cands = [cands[i] for i in idx]
    out = []
    for seg in cands:  # 청크별 RMS 정규화 (음량 누수 차단)
        r = np.sqrt((seg ** 2).mean())
        out.append(seg * (0.1 / max(r, 1e-6)))
    return out


E = {}
for d in sorted(SEP.iterdir()):
    v = d / "vocals.wav"
    if not v.exists():
        continue
    f = OUT / f"{d.name}.npy"
    if f.exists():
        E[d.name] = np.load(f)
        continue
    ch = load_chunks_v2(v)
    e = embed_ecapa(ch)
    e = e / np.linalg.norm(e, axis=1, keepdims=True)
    np.save(f, e)
    E[d.name] = e
    print(f"v2 embedded {d.name} ({len(ch)} chunks)")


def sim(a, b):
    return float((E[a] @ E[b].T).mean())


meta = {t: t.split("__", 1) for t in E}
BENCH = {t: m for t, m in meta.items() if m[0] != "지유림"}

pos, hard = [], []
for a, b in combinations(BENCH, 2):
    s1, g1 = meta[a]
    s2, g2 = meta[b]
    if s1 == s2 and g1 != g2:
        pos.append(sim(a, b))
    elif s1 != s2 and g1 == g2:
        hard.append(sim(a, b))
print(f"\n[v2 커버 벤치마크] same_singer={np.mean(pos):.3f}  cover={np.mean(hard):.3f}"
      f"  gap={np.mean(pos)-np.mean(hard):+.3f}")

groups = {
    "환경만(아이유 밤편지 4버전)": [t for t in E if t.startswith("아이유__밤편지")],
    "제이플라 곡간": [t for t in E if t.startswith("제이플라")],
    "솔라 곡간": [t for t in E if t.startswith("솔라")],
    "아이유 스튜디오 곡간": ["아이유__밤편지", "아이유__금요일에 만나요", "아이유__어떻게 지내"],
}
for lab, ts in groups.items():
    v = [sim(a, b) for a, b in combinations(ts, 2)]
    print(f"[v2] {lab}: {np.mean(v):.3f}")

u = "지유림__밤편지"
refs = [t for t in E if meta[t][1].startswith("밤편지") and meta[t][0] != "지유림"]
rank = sorted(((sim(u, r), r) for r in refs), reverse=True)
print("\n[v2 지유림 랭킹]")
for s, r in rank:
    print(f"  {s:.3f}  {r}")
