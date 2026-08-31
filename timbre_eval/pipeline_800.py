# 신규 곡 일괄 처리: prepare -> demucs(배치) -> v2 임베딩 -> 메타 -> 게이트
# 배치 단위 demucs 호출(윈도우 명령줄 길이 한계 회피) + 곡당 속도 로그
import subprocess
import sys
import time
from pathlib import Path

HERE = Path(__file__).resolve().parent
PY = sys.executable
WAV = HERE / "wav"
SEP = HERE / "separated" / "htdemucs"
BATCH = 40


def run(args):
    print(">>", " ".join(str(a) for a in args[:8]) + (" ..." if len(args) > 8 else ""), flush=True)
    subprocess.run([str(a) for a in args], check=True, cwd=HERE.parent)


run([PY, HERE / "prepare.py"])
todo = sorted(w for w in WAV.glob("*.wav") if not (SEP / w.stem / "vocals.wav").exists())
print(f"demucs 대상 {len(todo)}곡", flush=True)
for i in range(0, len(todo), BATCH):
    batch = todo[i : i + BATCH]
    t0 = time.time()
    run([PY, "-m", "demucs", "--two-stems=vocals", "-n", "htdemucs", "-o", HERE / "separated", *batch])
    dt = time.time() - t0
    print(f"[진행] {i+len(batch)}/{len(todo)}곡, 이번 배치 {dt/len(batch):.1f}s/곡", flush=True)
run([PY, HERE / "sensitivity.py"])
run([PY, HERE / "build_meta.py"])
run([PY, HERE / "update_flags.py"])
run([PY, HERE / "fix_duet_flag.py"])
print("파이프라인 완료", flush=True)
