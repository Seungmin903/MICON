# 가수-가수 전용 확장 문항(09~16) 생성 — "교차가수 거리 = 지각적 닮음" 검증 강화
# 선정 규칙(투명·자동, 체리피킹 방지 위해 규칙 고정):
#   성별 클러스터(트랙 평균벡터 2-means, 비지도 성별 대륙) 안에서
#   기준 트랙마다 A = 타가수 최근접 트랙, B = 타가수 중위권(60번째 백분위) 트랙,
#   모델 마진 s(A)-s(B) 큰 순으로 8개. 기존 문항(5~8)에 쓰인 가수쌍·기준가수 제외.
#   → 모델의 "가장 자신 있는 주장"만 추출: 여기서 사람이 뒤집으면 정렬 실패 확정적.
# 사용: python timbre_eval/export_listening2.py
import random

import numpy as np
import soundfile as sf
from scipy.cluster.vq import kmeans2

from asnorm import load_pool, cp
from export_listening import best_window, OUT
from evaluate import SR

USED_PAIRS = {frozenset(p) for p in
              [("아이유", "웬디"), ("솔라", "에일리"), ("정승환", "성시경"), ("이영현", "경서")]}
USED_REFS = {"아이유", "솔라", "정승환", "이영현", "지유림", "정다운"}
N_Q = 8
START = 9


def main():
    E, pool = load_pool()
    mean_vec = {t: (lambda m: m / np.linalg.norm(m))(E[t].mean(0)) for t in pool}
    X = np.array([mean_vec[t] for t in pool])
    _, labels = kmeans2(X, 2, seed=0, minit="++")
    cluster = dict(zip(pool, labels))

    cands = []
    for ref in pool:
        rs = ref.split("__")[0]
        if rs in USED_REFS:
            continue
        same = [t for t in pool if cluster[t] == cluster[ref] and t.split("__")[0] != rs]
        ranked = sorted(same, key=lambda t: cp(E, ref, t), reverse=True)
        if len(ranked) < 10:
            continue
        a = ranked[0]
        b = ranked[int(len(ranked) * 0.6)]
        if b.split("__")[0] == a.split("__")[0]:
            continue
        if frozenset({rs, a.split("__")[0]}) in USED_PAIRS:
            continue
        margin = cp(E, ref, a) - cp(E, ref, b)
        cands.append((margin, ref, a, b))

    # 기준 가수·A 가수 중복 없이 마진 큰 순 8개
    picked, seen = [], set()
    for margin, ref, a, b in sorted(cands, reverse=True):
        rs, as_ = ref.split("__")[0], a.split("__")[0]
        if rs in seen or as_ in seen:
            continue
        picked.append((margin, ref, a, b))
        seen |= {rs, as_}
        if len(picked) == N_Q:
            break

    random.seed(20260829)
    key_lines = ["", "## 확장 문항 09~16 — 가수-가수 전용 (export_listening2.py)", ""]
    for i, (margin, ref, near, far) in enumerate(picked):
        q = START + i
        swap = random.random() < 0.5
        a, b = (far, near) if swap else (near, far)
        for label, track in (("기준", ref), ("A", a), ("B", b)):
            seg, _ = best_window(track)
            sf.write(OUT / f"문항{q:02d}_{label}.wav", seg, SR)
        answer = "B" if swap else "A"
        key_lines.append(f"문항{q:02d}: 모델 판정 = **{answer}**  (기준 {ref} / A {a} / B {b} / 마진 {margin:+.3f})")
        print(key_lines[-1])

    with open(OUT / "진행자용_정답지.md", "a", encoding="utf-8") as f:
        f.write("\n".join(key_lines) + "\n")
    with open(OUT / "응답지.md", "a", encoding="utf-8") as f:
        f.write("\n" + "\n".join(f"문항{START+i:02d}:   A  /  B" for i in range(len(picked))) + "\n")
    print(f"\n완료: 문항 {START:02d}~{START+len(picked)-1:02d} 클립 + 정답지·응답지 추가")


if __name__ == "__main__":
    main()
