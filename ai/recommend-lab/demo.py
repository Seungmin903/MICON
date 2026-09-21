"""Generate synthetic data only, outside Git. Never reads or writes production jobs."""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path

import numpy as np

from engine import MODEL_KEYS, SCORING_VERSION, DEFAULTS
from timbre_recommender.contracts import MODEL_CONTRACTS
from timbre_recommender.pitch_range import RANGE_SCORING_VERSION, PITCH_EXTRACTION_VERSION, canonical_json_sha256


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding="utf-8")


def generate(directory, count=16):
    root = Path(directory).resolve()
    if root.exists():
        raise FileExistsError("새 디렉터리를 지정해 주세요. 기존 데이터는 덮어쓰지 않습니다.")
    index = root / "catalog"
    index.mkdir(parents=True)
    rng = np.random.default_rng(42)
    arrays, file_records = {}, {}
    for key in MODEL_KEYS:
        dim = MODEL_CONTRACTS[key].dimension
        base = rng.normal(size=(count, dim))
        x = np.vstack([base[i] + rng.normal(scale=.18, size=(3, dim)) for i in range(count)])
        x = (x / np.linalg.norm(x, axis=1, keepdims=True)).astype(np.float32)
        arrays[key] = x
        path = index / f"{key}.npy"
        np.save(path, x, allow_pickle=False)
        file_records[key] = {"filename": path.name, "shape": list(x.shape), "dtype": "float32", "sha256": hashlib.sha256(path.read_bytes()).hexdigest()}
    # 장르 실험을 확인할 수 있도록 합성 카탈로그에도 장르를 섞어 둔다. 표기는 DB 라벨로 통일하고,
    # demo-04 만 예전 영어 태그로 남겨 레거시 표기 접기와 운영 RAP_TAGS 를 함께 확인한다.
    demo_genres = {4: ["Hip-Hop"], 7: ["댄스/팝"], 8: ["댄스/팝"], 9: ["댄스/팝"],
                   10: ["OST", "발라드"], 11: ["OST"]}
    tracks, profiles = [], {}
    for i in range(count):
        tid = f"demo-{i:02}"
        tracks.append({"track_id": tid, "artist": f"합성 가수 {chr(65+i//3)}", "title": f"테스트 곡 {i+1}",
                       "metadata": {"song_id": tid, "language": "eng" if i == 3 else "kor", "batch_id": "synthetic-demo",
                                    "genres": demo_genres.get(i, ["발라드"])},
                       "arrays": {k: {"offset": i*3, "count": 3} for k in MODEL_KEYS}})
        low = 51 + i
        hist = np.zeros(60); hist[low-36:low+10-36] = 1
        profiles[tid] = {"schemaVersion": 1, "histogram": hist.tolist(), "coreLowMidi": low,
                         "medianMidi": low+4, "coreHighMidi": low+9, "lowestMidi": low, "highestMidi": low+9,
                         "reliable": i != 5, "confidence": .4 if i == 6 else .9}
    metadata = {"schema_version": 1, "scoring_version": SCORING_VERSION, "created_at": "2026-09-18T00:00:00Z",
                "models": {k: {"dimension": MODEL_CONTRACTS[k].dimension} for k in MODEL_KEYS},
                "files": file_records, "tracks": tracks}
    write_json(index / "index.json", metadata)
    write_json(index / "range_profiles.json", {"schemaVersion": 1, "scoringVersion": RANGE_SCORING_VERSION,
        "pitchExtractionVersion": PITCH_EXTRACTION_VERSION, "keyShiftRange": [-6, 5], "profiles": profiles,
        "source": {"indexFile": "index.json", "indexCanonicalSha256": canonical_json_sha256(index / "index.json")}})
    query = {k: arrays[k][:3].tolist() for k in MODEL_KEYS}
    case = {"schemaVersion": 1, "consented": True, "inputKind": "synthetic-demo", "label": "합성 데모 입력 실제 목소리 아님",
        "capturedAt": "2026-09-18T15:30:00", "catalogFingerprint": hashlib.sha256((index / "index.json").read_bytes()).hexdigest(),
        "query": query, "pitchProfile": profiles["demo-00"], "genres": ["발라드"], "topK": 10}
    write_json(root / "cases/demo.json", case)
    bad_id = "job_demo_failed"
    meta = {"analysisType": "RANGE", "sessionId": 1, "protocolVersion": "guided-range-v7", "attempts": [
        {"attemptId": "demo-9", "targetMidi": 46, "referenceMidi": 47.839975, "evaluationStart": 42.512, "clientStatus": "not_passed"}]}
    job = {"jobId": bad_id, "status": "FAILED", "componentStatus": "FAILED", "failureCode": "AI_ANALYSIS_FAILED",
           "meta": meta, "createdAt": "2026-09-18T15:04:20", "components": {"RANGE": {
            "status": "FAILED", "accepted": True, "failureCode": "AI_ANALYSIS_FAILED", "callback": {"http": 200, "attempts": 1}}}}
    write_json(root / "analysis" / bad_id / "job.json", job)
    write_json(root / "analysis" / bad_id / "callback.RANGE.json", {"status": "FAILED", "failureReason": "Value error, invalid v5 reference window"})
    return root


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description="합성 카탈로그 및 입력 생성 실제 녹음 아님")
    parser.add_argument("--output", required=True)
    args = parser.parse_args()
    print(generate(args.output))
