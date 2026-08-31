# 최종 청취 세션 문항 (845트랙 풀 기반, listening_final/)
# 구성: 신규 최고마진 4 + 파일럿 불일치 재검 3 + 컨트롤 1 = 8문항
# 게이트: load_pool(그룹·듀엣·라이브) + 응집도>=0.55 (임베딩에서 직접 계산, 메타 불요)
# 사용: python timbre_eval/export_listening_final.py
import random

import numpy as np
import soundfile as sf
from scipy.cluster.vq import kmeans2

from asnorm import load_pool, cp
from export_listening import best_window
from evaluate import SR, SEP

from pathlib import Path
OUT = Path(__file__).resolve().parent / "listening_final"

# 파일럿(문항11·13·15)에서 사람이 모델을 뒤집은 쌍 — 블라인드 재검 (ref, 모델가깝다, 모델멀다)
MISMATCH = [
    ("김나영__봄 내음보다 너를", "윤하__기다리다", "이하이__Rose"),
    ("황가람__나는 반딧불", "이클립스__소나기(선재업고튀어OST)", "Tim__사랑합니다"),
    ("이하이__Rose", "볼빨간사춘기__여름아 부탁해", "WAX__화장을 고치고"),
]
RAP = set("""San E 아웃사이더 MC Sniper 기리보이 창모 비와이 빈지노 빈첸(이병재) 조광일 나플라
릴러말즈 저스디스 염따 페노메코 지코 MC몽 김진표 수퍼비 씨잼 호미들 재지팩트 키네틱플로우
프라이머리 다이나믹듀오 에픽하이 리쌍""".split())  # 랩↔랩 문항은 서비스 도메인 밖 — 기준/A 후보에서 제외
USED_SINGERS = {t.split("__")[0] for q in MISMATCH for t in q} | RAP | \
    {"아이유", "솔라", "정승환", "이영현", "웬디", "에일리", "성시경", "경서", "지유림", "정다운"}


def coh(e):
    c = e.mean(0)
    c /= np.linalg.norm(c)
    return float((e @ c).mean())


def main():
    E, pool = load_pool()
    CO = {t: coh(E[t]) for t in pool}
    clean = [t for t in pool if CO[t] >= 0.55]
    print(f"풀 {len(pool)} -> 응집도>=0.55 통과 {len(clean)}트랙")
    X = np.stack([(lambda m: m / np.linalg.norm(m))(E[t].mean(0)) for t in clean])
    _, cl = kmeans2(X, 2, seed=0, minit="++")
    cluster = dict(zip(clean, cl))

    cands = []
    for ref in clean:
        rs = ref.split("__")[0]
        if rs in USED_SINGERS:
            continue
        same = [t for t in clean if cluster[t] == cluster[ref] and t.split("__")[0] != rs]
        ranked = sorted(same, key=lambda t: cp(E, ref, t), reverse=True)
        if len(ranked) < 20:
            continue
        a, b = ranked[0], ranked[int(len(ranked) * 0.6)]
        if a.split("__")[0] == b.split("__")[0] or a.split("__")[0] in USED_SINGERS:
            continue
        cands.append((cp(E, ref, a) - cp(E, ref, b), ref, a, b))

    picked, seen = [], set()
    for margin, ref, a, b in sorted(cands, reverse=True):
        rs, as_ = ref.split("__")[0], a.split("__")[0]
        if rs in seen or as_ in seen:
            continue
        picked.append((margin, ref, a, b))
        seen |= {rs, as_}
        if len(picked) == 4:
            break

    # 컨트롤: 미사용 다곡가수 중 응집도 최고 — 본인 타곡(near) vs 같은군집 중위권 타인(far)
    multi = {}
    for t in clean:
        multi.setdefault(t.split("__")[0], []).append(t)
    ctrl_singer = max((s for s, ts in multi.items() if len(ts) >= 2 and s not in USED_SINGERS | seen),
                      key=lambda s: np.mean([CO[t] for t in multi[s]]))
    ref = max(multi[ctrl_singer], key=CO.get)
    near = max((t for t in multi[ctrl_singer] if t != ref), key=lambda t: cp(E, ref, t))
    others = sorted((t for t in clean if cluster.get(t) == cluster.get(ref)
                     and t.split("__")[0] != ctrl_singer), key=lambda t: cp(E, ref, t), reverse=True)
    far = others[int(len(others) * 0.6)]
    control = (cp(E, ref, near) - cp(E, ref, far), ref, near, far)

    questions = [(f"신규{i+1}", q) for i, q in enumerate(picked)]
    questions += [("불일치재검", (cp(E, r, a) - cp(E, r, b), r, a, b)) for r, a, b in MISMATCH]
    questions.append(("컨트롤", control))

    OUT.mkdir(exist_ok=True)
    random.seed(20260830)
    key = ["# 최종 세션 정답지 — 참가자 노출 금지", ""]
    for q, (kind, (margin, ref, a_near, b_far)) in enumerate(questions, 1):
        swap = random.random() < 0.5
        a, b = (b_far, a_near) if swap else (a_near, b_far)
        for label, track in (("기준", ref), ("A", a), ("B", b)):
            seg, _ = best_window(track)
            sf.write(OUT / f"문항{q:02d}_{label}.wav", seg, SR)
        ans = "B" if swap else "A"
        key.append(f"문항{q:02d} [{kind}]: 모델 판정 = **{ans}**  (기준 {ref} / A {a} / B {b} / 마진 {margin:+.3f})")
        print(key[-1])
    (OUT / "진행자용_정답지.md").write_text("\n".join(key) + "\n", encoding="utf-8")
    resp = ["# 최종 청취 세션 응답지", "", "이름: ______  날짜: ______", "",
            "각 문항: 기준 → A → B 듣고 더 닮은 쪽에 O.", ""]
    resp += [f"문항{q:02d}:   A  /  B" for q in range(1, len(questions) + 1)]
    (OUT / "응답지.md").write_text("\n".join(resp) + "\n", encoding="utf-8")
    print(f"\n완료 -> {OUT}")


if __name__ == "__main__":
    main()
