# 한 트랙의 최종사양(v2) 조각들을 청취용 wav로 내보내기
# 사용: python export_chunks.py "지유림__밤편지"
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

from sensitivity import load_chunks_v2
from evaluate import SEP, SR

track = sys.argv[1]
chunks = load_chunks_v2(SEP / track / "vocals.wav")
out = Path(__file__).resolve().parent / "chunks_preview" / track
out.mkdir(parents=True, exist_ok=True)

gap = np.zeros(int(0.5 * SR), dtype=np.float32)
parts = []
for i, c in enumerate(chunks, 1):
    sf.write(out / f"chunk_{i:02d}.wav", c, SR)
    parts += [c.astype(np.float32), gap]
sf.write(out / "_전체듣기.wav", np.concatenate(parts), SR)
print(f"{track}: {len(chunks)}개 조각 -> {out}")
