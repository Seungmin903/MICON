# report.json -> 트랙쌍 테이블 + 동성쌍 분석
import json
from pathlib import Path

RESULTS = Path(__file__).resolve().parent / "results"
report = json.loads((RESULTS / "report.json").read_text(encoding="utf-8"))

GENDER = {"아이유": "F", "웬디": "F", "설윤": "F", "경서": "F", "이영현": "F", "지유림": "F",
          "첸": "M", "존박": "M", "크러쉬": "M", "규현": "M"}


def singer(track):
    return track.split("__")[0]


for mname, r in report.items():
    if "error" in r:
        continue
    print(f"\n=== {mname} ===")
    pos = [t for t in r["track_pairs"] if t[2] == "same_singer_diff_song"]
    hard = [t for t in r["track_pairs"] if t[2] == "diff_singer_same_song"]
    print("  [같은 가수, 다른 곡]")
    for n1, n2, _, s in sorted(pos, key=lambda x: -x[3]):
        print(f"    {s:.3f}  {n1} <-> {n2}")
    print("  [다른 가수, 같은 곡]")
    for n1, n2, _, s in sorted(hard, key=lambda x: -x[3]):
        g = GENDER[singer(n1)] + GENDER[singer(n2)]
        tag = "동성" if g in ("FF", "MM") else "이성"
        print(f"    {s:.3f}  {n1} <-> {n2}  ({tag})")
    pos_min = min(t[3] for t in pos)
    same_g = [t[3] for t in hard if GENDER[singer(t[0])] == GENDER[singer(t[1])]]
    diff_g = [t[3] for t in hard if GENDER[singer(t[0])] != GENDER[singer(t[1])]]
    # 가수별 기준선: 커버쌍(A,B)이 A 또는 B 본인의 곡간 최솟값 이상이면 역전
    # (전역 최솟값 기준은 한 가수의 환경 오염이 전체 지표를 물들여 폐기)
    base = {}
    for n1, n2, _, s in pos:
        for n in (singer(n1), singer(n2)):
            base[n] = min(base.get(n, 1.0), s)
    viol, judged = [], 0
    for t in hard:
        bs = [base[s] for s in (singer(t[0]), singer(t[1])) if s in base]
        if bs:
            judged += 1
            if t[3] >= min(bs):
                viol.append(t)
    print(f"  pos 최솟값={pos_min:.3f} | 동성neg 평균={sum(same_g)/len(same_g):.3f}"
          f" | 이성neg 평균={sum(diff_g)/len(diff_g):.3f}"
          f" | 역전(가수별 기준선) {len(viol)}/{judged}건")
