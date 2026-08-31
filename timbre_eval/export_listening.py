# 청취 세션(2AFC 8문항) 클립 일괄 추출 + A/B 무작위 배치 + 정답지/응답지 생성
# 사용: python timbre_eval/export_listening.py
# 창 선택: 유성비율>0.6 통과 20초 창 중 RMS가 트랙 중앙값에 가장 가까운 창
#          (안재욱 '친구' 코러스 사고 재발 방지 — 극단 구간 회피)
import random
from pathlib import Path

import numpy as np
import librosa
import soundfile as sf

from evaluate import SEP, SR

HERE = Path(__file__).resolve().parent
OUT = HERE / "listening_session"
CLIP_S = 20.0

# 청취세션_프로토콜.md 파트1 표와 1:1. (기준, 모델가깝다판정, 모델멀다판정)
QUESTIONS = [
    ("지유림__밤편지", "경서__밤편지", "설윤__밤편지"),
    ("지유림__밤편지", "아이유__밤편지", "웬디__밤편지"),
    ("정다운__밤편지", "정승환__눈사람", "존박__밤편지"),
    ("정다운__밤편지", "규현__밤편지", "첸__밤편지"),
    ("아이유__밤편지", "웬디__밤편지", "이영현__그래서 그대는"),
    ("솔라__Golden", "에일리__첫눈처럼 너에게 가겠다(도깨비OST)", "솔라__Take me to Church"),
    ("정승환__눈사람", "성시경__거리에서", "존박__밤편지"),
    ("이영현__My Way", "이영현__이영현 – 헤어지자 말해요", "경서__밤편지"),
]


def best_window(track):
    y, _ = librosa.load(SEP / track / "vocals.wav", sr=SR, mono=True)
    n = int(CLIP_S * SR)
    frame = 2048
    rms_all = librosa.feature.rms(y=y, frame_length=frame, hop_length=frame)[0]
    thr = np.percentile(rms_all, 90) * 0.15
    hop = int(2.5 * SR)  # 2.5초 간격 슬라이딩
    cands = []
    for i in range(0, len(y) - n, hop):
        seg = y[i : i + n]
        r = librosa.feature.rms(y=seg, frame_length=frame, hop_length=frame)[0]
        voiced = (r > thr).mean()
        if voiced > 0.6:
            cands.append((i, float(np.mean(r))))
    if not cands:  # 문턱 미달 트랙 폴백: 가장 유성 많은 창
        i = int(np.argmax([
            (librosa.feature.rms(y=y[j : j + n], frame_length=frame, hop_length=frame)[0] > thr).mean()
            for j in range(0, len(y) - n, hop)
        ])) * hop
        cands = [(i, 0.0)]
    med = np.median([m for _, m in cands])
    start = min(cands, key=lambda c: abs(c[1] - med))[0]
    seg = y[start : start + n]
    seg = seg * (0.1 / max(np.sqrt((seg ** 2).mean()), 1e-6))  # 음량 통일
    peak = np.abs(seg).max()
    if peak > 0.95:
        seg = seg * (0.95 / peak)
    return seg, start / SR


def main():
    OUT.mkdir(exist_ok=True)
    random.seed(20260828)  # 재현 가능 무작위
    key_lines = ["# 진행자용 정답지 — 참가자에게 절대 노출 금지", ""]
    cache = {}

    def clip(track):
        if track not in cache:
            cache[track] = best_window(track)
        return cache[track]

    for q, (ref, near, far) in enumerate(QUESTIONS, 1):
        swap = random.random() < 0.5  # True면 A=멀다판정
        a, b = (far, near) if swap else (near, far)
        for label, track in (("기준", ref), ("A", a), ("B", b)):
            seg, t0 = clip(track)
            sf.write(OUT / f"문항{q:02d}_{label}.wav", seg, SR)
        answer = "B" if swap else "A"
        key_lines.append(f"문항{q:02d}: 모델 판정 = **{answer}**  (기준 {ref} / A {a} / B {b})")

    (OUT / "진행자용_정답지.md").write_text("\n".join(key_lines) + "\n", encoding="utf-8")

    resp = ["# 청취 세션 응답지", "", "이름: ______   날짜: ______", "",
            "각 문항: 기준 → A → B 순서로 듣고, 기준과 더 닮은 쪽에 O.", ""]
    resp += [f"문항{q:02d}:   A  /  B" for q in range(1, len(QUESTIONS) + 1)]
    (OUT / "응답지.md").write_text("\n".join(resp) + "\n", encoding="utf-8")

    # 파트3 자유 청취: 안재욱 친구 90-120s (코러스 재확인용, 원본 그대로)
    y, _ = librosa.load(SEP / "안재욱__친구" / "vocals.wav", sr=SR, mono=True)
    sf.write(OUT / "자유청취_안재욱_친구_90-120s.wav", y[90 * SR : 120 * SR], SR)

    print(f"완료: {OUT}")
    for f in sorted(OUT.iterdir()):
        print(" ", f.name)


if __name__ == "__main__":
    main()
