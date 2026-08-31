# mp3 -> 44.1kHz wav (demucs 입력용). 폴더명=가수, 파일명에서 곡명 추출.
import re
import sys
from pathlib import Path

import librosa
import soundfile as sf

DATA = Path(__file__).resolve().parent.parent / "data"
OUT = Path(__file__).resolve().parent / "wav"
OUT.mkdir(exist_ok=True)

for mp3 in sorted(DATA.glob("*/*.mp3")):
    singer = mp3.parent.name
    if "__" in mp3.stem:  # TJ 카탈로그 규칙: "가수__TJ051-97017_official__제목"
        song = [p for p in mp3.stem.split("__") if p][-1].strip()
    else:  # 기존 규칙: "웬디 - 밤편지" -> "밤편지"
        song = re.sub(r"^.*?-\s*", "", mp3.stem).strip()
    dst = OUT / f"{singer}__{song}.wav"
    if dst.exists():
        print(f"skip {dst.name}")
        continue
    y, sr = librosa.load(mp3, sr=44100, mono=False)
    sf.write(dst, y.T if y.ndim == 2 else y, 44100)
    print(f"ok   {dst.name}  ({y.shape[-1]/44100:.0f}s)")

print("done", file=sys.stderr)
