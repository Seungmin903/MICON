# 실패한 bimodality 듀엣 탐지기 철회 -> 이름 기반 듀엣 플래그로 교체
# bimodality 수치는 탐색용 원자료로만 남김 (판정 필드 아님)
import json
import re
from pathlib import Path

CACHE = Path("timbre_eval/emb_cache_v2/ecapa")
DUET_PAT = re.compile(r"(,|&|feat\.?|듀엣|with )", re.IGNORECASE)

lc, duet = [], []
for f in sorted(CACHE.glob("*.meta.json")):
    meta = json.loads(f.read_text(encoding="utf-8"))
    name = f.stem.replace(".meta", "")
    singer = name.split("__")[0]
    t = meta["track"]
    t["duet_suspect"] = bool(DUET_PAT.search(singer))  # 이름 기반 (검증됨: 표기 관행)
    t["bimodality_validated"] = False  # 탐지기 검증 실패 기록 — 판정에 사용 금지
    f.write_text(json.dumps(meta, ensure_ascii=False, indent=1), encoding="utf-8")
    if t["low_confidence"]:
        lc.append((t["internal_coherence"], name))
    if t["duet_suspect"]:
        duet.append(name)

print("듀엣 플래그 (이름 기반):")
for n in duet:
    print("  ", n)
print("\n저신뢰 플래그 (응집도<0.40 또는 이상조각>=4):")
for c, n in sorted(lc):
    print(f"  {c:.3f}  {n}")
