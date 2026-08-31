# 캐시 스키마 확장: 임베딩 옆에 조각별 조건 메타데이터(.meta.json) 생성
# 조각별: f0 중앙값, 원시 RMS(정규화 전), 유성 비율, 중심과의 응집도
# 트랙별: 내부 응집도, 클리핑 비율, SNR 프록시, 이상 조각 수 (코러스/듀엣 의심 게이트 재료)
import json
from pathlib import Path

import numpy as np
import librosa

from sensitivity import load_chunks_v2  # 표집 위치·순서는 최종 사양과 동일
from evaluate import SEP, SR

HERE = Path(__file__).resolve().parent
CACHE = HERE / "emb_cache_v2" / "ecapa"


def raw_chunks_with_rms(vocals_path):
    """load_chunks_v2와 동일한 절단·표집이되, 정규화 전 RMS도 함께 반환."""
    y, _ = librosa.load(vocals_path, sr=SR, mono=True)
    n = int(5 * SR)
    frame = 2048
    rms_all = librosa.feature.rms(y=y, frame_length=frame, hop_length=frame)[0]
    thr = np.percentile(rms_all, 90) * 0.15
    quiet = rms_all[rms_all <= thr]
    cands, metas = [], []
    for i in range(0, len(y) - n, n):
        seg = y[i : i + n]
        seg_rms = librosa.feature.rms(y=seg, frame_length=frame, hop_length=frame)[0]
        voiced_ratio = float((seg_rms > thr).mean())
        if voiced_ratio > 0.6:
            cands.append(seg)
            metas.append({"t_start_s": i / SR, "voiced_ratio": round(voiced_ratio, 3),
                          "rms_raw": round(float(np.sqrt((seg ** 2).mean())), 5)})
    if len(cands) > 24:
        idx = np.linspace(0, len(cands) - 1, 24).astype(int)
        cands = [cands[i] for i in idx]
        metas = [metas[i] for i in idx]
    clip = float(np.mean(np.abs(y) > 0.985))
    snr_proxy = float(20 * np.log10((np.median(rms_all[rms_all > thr]) + 1e-9) /
                                    (np.median(quiet) + 1e-9))) if len(quiet) else None
    return cands, metas, clip, snr_proxy


for d in sorted(SEP.iterdir()):
    v = d / "vocals.wav"
    npy = CACHE / f"{d.name}.npy"
    out = CACHE / f"{d.name}.meta.json"
    if not v.exists() or not npy.exists() or out.exists():
        continue
    E = np.load(npy)
    chunks, metas, clip, snr = raw_chunks_with_rms(v)
    if len(chunks) != len(E):
        print(f"SKIP {d.name}: 조각 수 불일치 ({len(chunks)} vs {len(E)})")
        continue
    center = E.mean(0)
    center /= np.linalg.norm(center)
    coh_each = E @ center
    for m, c, ch in zip(metas, coh_each, chunks):
        w = librosa.resample(ch, orig_sr=SR, target_sr=16000)
        f0, voiced, _ = librosa.pyin(w, fmin=70, fmax=800, sr=16000, frame_length=1024)
        m["f0_median_hz"] = round(float(np.nanmedian(f0[voiced])), 1) if voiced.any() else None
        m["coherence_to_center"] = round(float(c), 3)
    S = E @ E.T
    iu = S[np.triu_indices(len(E), 1)]
    outliers = int((coh_each < 0.45).sum())
    meta = {
        "model": "ecapa_v2", "n_chunks": len(E),
        "track": {"internal_coherence": round(float(iu.mean()), 3),
                  "clipping_ratio": round(clip, 5), "snr_proxy_db": round(snr, 1) if snr else None,
                  "outlier_chunks": outliers,
                  "multi_voice_suspect": bool(outliers >= max(3, len(E) // 4))},
        "chunks": metas,
    }
    out.write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    print(f"meta {d.name}: 응집도 {meta['track']['internal_coherence']}, 이상조각 {outliers}"
          + (" ⚠️다성의심" if meta["track"]["multi_voice_suspect"] else ""))
