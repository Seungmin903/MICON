# 표현 민감성 검증 (문서 9절의 미검증 기준): 같은 사람이 같은 소절을 창법만 바꿔 부르면
# 임베딩이 그 변화를 감지하는가 — 그러면서도 타인보다는 자기 자신에 머무는가
# 녹음 규칙: data/창법테스트/<이름> - <스타일>.mp3 (스타일: 평소, 숨소리, 지르기 등)
# 사용: python expressiveness_test.py <이름>
import sys
from itertools import combinations
from pathlib import Path

import numpy as np

from thirty_sec_test import E  # 카탈로그 (타인 기준선용)
from sensitivity import load_chunks_v2
from evaluate import SEP, embed_ecapa

name = sys.argv[1]
tracks = sorted(d.name for d in SEP.iterdir() if d.name.startswith(name + "__"))
if len(tracks) < 2:
    print(f"{name}의 창법 녹음이 2개 미만입니다. prepare/demucs 먼저 실행하세요.")
    sys.exit(1)

V = {}
for t in tracks:
    ch = load_chunks_v2(SEP / t / "vocals.wav")
    e = embed_ecapa(ch)
    e = e / np.linalg.norm(e, axis=1, keepdims=True)
    V[t] = e
    print(f"{t}: 조각 {len(e)}개")

def tv(e):
    m = e.mean(0)
    return m / np.linalg.norm(m)

print("\n[창법 간 유사도 — 낮을수록 민감]")
styles = list(V)
for a, b in combinations(styles, 2):
    print(f"  {float(tv(V[a]) @ tv(V[b])):.3f}  {a.split('__')[1]} ↔ {b.split('__')[1]}")

# 기준선: 이 사람의 각 스타일 vs 카탈로그 타인 최고
others = {t: e for t, e in E.items() if not t.startswith(name)}
print("\n[자기 다른 창법 vs 타인 최고 — 자기 쪽이 높아야 identity 유지]")
o_vecs = {t: tv(e) for t, e in others.items()}
for s in styles:
    sv = tv(V[s])
    best = max((float(sv @ ov), t) for t, ov in o_vecs.items())
    print(f"  {s.split('__')[1]}: 타인 최고 {best[0]:.3f} ({best[1].replace('__','·')})")

print("\n판정 가이드: 창법 간 유사도가 1.0 근처에 몰리면 '둔감'(Δ 감지 불가 = v2 조건부 매칭 불가),")
print("타인 최고보다 낮게 떨어지는 창법이 있으면 '과민'(identity 붕괴). 그 사이가 합격.")
