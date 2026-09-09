"""분리 보컬 폴더 → 프로덕션 계약 임베딩 일괄 추출 (재실행하면 이어서 한다).

    python scripts\\embed_vocals_folder.py --vocals-root <폴더> --out workspace\\embeddings [--pattern "*/vocals.wav"] [--skip-list a.txt]

- 입력: `<vocals-root>/<트랙키>/vocals.wav` (Demucs 출력 구조) 또는 `--pattern` 으로 지정.
- 출력: `<out>/<트랙키>.ecapa.npy`, `.singer_byol.npy`, `.segments.json` (곡당 약 40 KB).
- 모델은 한 번만 올린다(VocalEmbeddingRuntime). 곡당 4~10초.
- 결과 로그 `<out>/_log.csv` (track, status, segments, seconds, error). 이미 있는 곡은 건너뜀.
- 악보 추출 파이프라인에 끼울 때는 곡 하나짜리 `python -m timbre_recommender embed-vocal` 을 쓰면 되고, 이 스크립트는 폴더 단위 후처리용.
"""
from __future__ import annotations

import argparse, csv, json, sys, time
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from timbre_recommender.embedding import VocalEmbeddingRuntime, embed_vocal_audio  # noqa: E402


def main() -> None:
    ap = argparse.ArgumentParser()
    ap.add_argument("--vocals-root", type=Path, required=True)
    ap.add_argument("--pattern", default="*/vocals.wav", help="vocals-root 기준 glob. 트랙키 = 상위 폴더명(또는 파일명)")
    ap.add_argument("--out", type=Path, default=ROOT / "workspace" / "embeddings")
    ap.add_argument("--config", type=Path, default=ROOT / "workspace" / "recommender" / "config.json")
    ap.add_argument("--skip-list", type=Path, help="건너뛸 트랙키 목록(한 줄 하나)")
    ap.add_argument("--only-list", type=Path, help="이 트랙키만(한 줄 하나)")
    ap.add_argument("--limit", type=int)
    ap.add_argument("--device", default="auto")
    ap.add_argument("--max-segments", type=int, default=12, help="인덱스 빌드가 다시 ≤9 로 균등 선택하므로 여유 있게")
    args = ap.parse_args()

    cfg = json.loads(args.config.read_text(encoding="utf-8"))
    files = sorted(args.vocals_root.glob(args.pattern))
    def key_of(p: Path) -> str: return p.parent.name if p.name.lower().startswith("vocals") else p.stem
    skip = set(args.skip_list.read_text(encoding="utf-8").split("\n")) if args.skip_list else set()
    only = set(args.only_list.read_text(encoding="utf-8").split("\n")) if args.only_list else None
    todo = [p for p in files if key_of(p) not in skip and (only is None or key_of(p) in only)
            and not ((args.out / f"{key_of(p)}.ecapa.npy").exists() and (args.out / f"{key_of(p)}.singer_byol.npy").exists())]
    if args.limit: todo = todo[: args.limit]
    args.out.mkdir(parents=True, exist_ok=True)
    print(f"입력 {len(files)}곡, 처리 대상 {len(todo)}곡 → {args.out}", flush=True)
    if not todo: return

    runtime = VocalEmbeddingRuntime(
        ecapa_checkpoint=cfg["ecapa_checkpoint"], singer_repo=cfg["singer_repo"], singer_checkpoint_root=cfg["singer_checkpoint_root"],
        device=args.device, batch_size=8, offload_after_embed=False,
    )
    log_path = args.out / "_log.csv"
    new_log = not log_path.exists()
    with log_path.open("a", newline="", encoding="utf-8") as fh:
        log = csv.writer(fh)
        if new_log: log.writerow(["track", "status", "segments", "seconds", "error", "finished_at"])
        ok = fail = 0
        t_all = time.time()
        for i, p in enumerate(todo, 1):
            key = key_of(p); t0 = time.time()
            try:
                info = embed_vocal_audio(p, args.out / key, ecapa_checkpoint=cfg["ecapa_checkpoint"], singer_repo=cfg["singer_repo"],
                                         singer_checkpoint_root=cfg["singer_checkpoint_root"], device=args.device, batch_size=8,
                                         max_segments=args.max_segments, min_segments=5, runtime=runtime)
                seg = info.get("segment_count") or info.get("segments") or ""
                log.writerow([key, "ok", seg, f"{time.time()-t0:.1f}", "", time.strftime("%Y-%m-%d %H:%M:%S")]); ok += 1
                print(f"[{i}/{len(todo)}] ok  {time.time()-t0:5.1f}s  {key}", flush=True)
            except Exception as e:  # 한 곡 실패가 전체를 멈추지 않게
                log.writerow([key, "fail", "", f"{time.time()-t0:.1f}", str(e)[:300], time.strftime("%Y-%m-%d %H:%M:%S")]); fail += 1
                print(f"[{i}/{len(todo)}] FAIL {key}: {str(e)[:120]}", flush=True)
            fh.flush()
        print(f"\n완료 {ok}곡, 실패 {fail}곡, {(time.time()-t_all)/60:.1f}분", flush=True)


if __name__ == "__main__":
    main()
