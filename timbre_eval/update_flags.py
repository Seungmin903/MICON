# 메타 플래그 재설계 (오디오 재처리 없이 임베딩만으로 갱신)
# low_confidence: 응집도<0.40 또는 이상조각>=4 — "단일 안정 음색 아님" (사유 불문 저신뢰)
# bimodality: 조각이 두 덩어리로 갈라지는 정도 (듀엣/교창 전용 신호) — 2-군집 분리도
import json
from pathlib import Path

import numpy as np

CACHE = Path("timbre_eval/emb_cache_v2/ecapa")


def bimodality(E, iters=10):
    """간단 2-means: (군집 간 중심 거리) / (군집 내 평균 퍼짐). 클수록 두 목소리 의심."""
    n = len(E)
    sims_to_first = E @ E[0]
    a = E[np.argmax(sims_to_first)]
    b = E[np.argmin(sims_to_first)]
    for _ in range(iters):
        da, db = E @ a, E @ b
        ga, gb = da >= db, da < db
        if ga.sum() == 0 or gb.sum() == 0:
            return 0.0, 0.5
        a = E[ga].mean(0); a /= np.linalg.norm(a)
        b = E[gb].mean(0); b /= np.linalg.norm(b)
    between = 1 - float(a @ b)
    within = 1 - float(np.mean([E[ga] @ a, ]) if False else (np.concatenate([E[ga] @ a, E[gb] @ b])).mean())
    ratio = between / max(within, 1e-6)
    balance = min(ga.sum(), gb.sum()) / n  # 0.5면 반반 (듀엣스러움)
    return ratio, float(balance)


rows = []
for f in sorted(CACHE.glob("*.meta.json")):
    meta = json.loads(f.read_text(encoding="utf-8"))
    E = np.load(CACHE / (f.stem.replace(".meta", "") + ".npy"))
    t = meta["track"]
    ratio, balance = bimodality(E)
    t["bimodality_ratio"] = round(ratio, 2)
    t["bimodality_balance"] = round(balance, 2)
    t["low_confidence"] = bool(t["internal_coherence"] < 0.40 or t["outlier_chunks"] >= 4)
    t["duet_suspect"] = bool(ratio > 2.0 and balance >= 0.25)
    t.pop("multi_voice_suspect", None)
    f.write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    rows.append((ratio, balance, t["internal_coherence"], t["low_confidence"], t["duet_suspect"], f.stem.replace(".meta", "")))

rows.sort(reverse=True)
print("bimodality 상위 10 (듀엣 탐지 검증 — 알려진 듀엣: 조유진,박기영 / 요네즈&우타다 / 허각Feat.존박):")
for r, b, c, lc, ds, name in rows[:10]:
    print(f"  분리도 {r:.2f} 균형 {b:.2f} 응집 {c:.3f} 저신뢰 {int(lc)} 듀엣의심 {int(ds)}  {name}")
n_lc = sum(1 for x in rows if x[3]); n_ds = sum(1 for x in rows if x[4])
print(f"\n플래그 합계: 저신뢰 {n_lc}건, 듀엣의심 {n_ds}건 / {len(rows)}트랙")
