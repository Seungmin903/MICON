"""Local FastAPI routes for the ECAPA + Singer-BYOL demo UI."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import uuid
from typing import Any

from fastapi import APIRouter, BackgroundTasks, File, Form, HTTPException, UploadFile
import imageio_ffmpeg
import numpy as np
import soundfile as sf

from pitch_engine import extract_vocal_melody
from pitch_profile import summarize_pitch_profile
from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.embedding import embed_vocal_audio
from timbre_recommender.pitch_range import RANGE_CATALOG_FILENAME, RangeCatalog
from timbre_recommender.scoring import RankerConfig, recommend


ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT / "workspace" / "recommender"
CONFIG_PATH = WORKSPACE / "config.json"
JOBS_DIR = WORKSPACE / "jobs"
ALLOWED_EXTENSIONS = {".mp3", ".wav", ".flac", ".m4a", ".ogg", ".webm"}
MAX_BYTES = 250 * 1024 * 1024
MAX_DURATION_SECONDS = 120.0
LOCK = threading.Lock()
INFERENCE_LOCK = threading.Lock()
JOBS: dict[str, dict[str, Any]] = {}
router = APIRouter(tags=["timbre-recommendations"])


def _replace_with_retry(source: Path, destination: Path) -> None:
    """Replace metadata atomically, tolerating brief Windows reader locks."""

    for attempt in range(6):
        try:
            os.replace(source, destination)
            return
        except PermissionError:
            if attempt == 5:
                raise
            time.sleep(0.02 * (attempt + 1))


def _load_config() -> dict[str, Any]:
    if not CONFIG_PATH.is_file():
        return {}
    try:
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def _configuration_status() -> tuple[dict[str, Any], list[str]]:
    config = _load_config()
    checks = {
        "데모 카탈로그": Path(config.get("index_dir", "")) / "index.json",
        "곡별 음역 카탈로그": Path(config.get("index_dir", "")) / RANGE_CATALOG_FILENAME,
        "ECAPA 체크포인트": Path(config.get("ecapa_checkpoint", "")),
        "Singer-BYOL 코드": Path(config.get("singer_repo", "")) / "singer_identity" / "model.py",
        "Singer-BYOL 체크포인트": Path(config.get("singer_checkpoint_root", "")) / "byol" / "model.pt",
    }
    missing = [label for label, path in checks.items() if not str(path) or not path.is_file()]
    return config, missing


def _save_job(job_id: str) -> None:
    job_dir = JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    temporary = job_dir / ".job.json.tmp"
    temporary.write_text(
        json.dumps(JOBS[job_id], ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    _replace_with_retry(temporary, job_dir / "job.json")


def _update(job_id: str, **values: Any) -> None:
    with LOCK:
        JOBS[job_id].update(values)
        _save_job(job_id)


def _convert_to_wav(source: Path, target: Path) -> None:
    command = [
        imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-i", str(source), "-vn",
        "-t", str(MAX_DURATION_SECONDS + 1.0), "-ac", "1", "-ar", "44100", str(target),
    ]
    completed = subprocess.run(
        command, capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if completed.returncode != 0:
        raise RuntimeError(f"보컬 파일을 WAV로 변환하지 못했습니다: {completed.stderr[-500:]}")
    duration = float(sf.info(target).duration)
    if duration > MAX_DURATION_SECONDS + 0.05:
        raise RuntimeError("추천용 보컬 파일은 2분 이하로 잘라서 올려주세요.")


def _cleanup_query_artifacts(job_dir: Path) -> None:
    for name in (
        "original.mp3", "original.wav", "original.flac", "original.m4a", "original.ogg", "original.webm",
        "normalized.wav", "query.ecapa.npy", "query.singer_byol.npy", "query.segments.json",
    ):
        path = job_dir / name
        if path.is_file():
            path.unlink()


def _resolved_device(requested: str) -> str:
    import torch

    if requested == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    if requested == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA가 요청되었지만 현재 사용할 수 없습니다.")
    return requested


def _process(
    job_id: str,
    input_path: Path,
    top_k: int,
    mode: str,
    excluded_track_ids: tuple[str, ...],
) -> None:
    job_dir = input_path.parent
    try:
        config, missing = _configuration_status()
        if missing:
            raise RuntimeError(f"추천 데모 설정 누락: {', '.join(missing)}")
        with INFERENCE_LOCK:
            normalized = job_dir / "normalized.wav"
            _update(job_id, status="processing", progress=18, message="보컬 파일을 44.1 kHz로 정규화하고 있습니다.")
            _convert_to_wav(input_path, normalized)
            pitch_profile: dict[str, Any] | None = None
            pitch_error: str | None = None
            _update(job_id, progress=28, message="VocalScope가 현재 부른 음역 분포를 분석하고 있습니다.")
            try:
                melody = extract_vocal_melody(
                    normalized,
                    None,
                    _resolved_device(config.get("device", "auto")),
                    return_frames=False,
                )
                pitch_profile = summarize_pitch_profile(
                    melody["notes"],
                    profile_kind="observed_user_sample",
                    f0_agreement_ratio=melody.get("f0AgreementRatio"),
                    octave_mismatch_ratio=melody.get("octaveMismatchRatio"),
                    compared_frames=melody.get("comparedFrames"),
                )
            except Exception as error:
                pitch_error = str(error)[:400]
            _update(job_id, progress=45, message="좋은 4초 보컬 구간을 고르고 있습니다.")
            prefix = job_dir / "query"
            _update(job_id, progress=52, message="ECAPA와 Singer-BYOL 임베딩을 생성하고 있습니다.")
            embed_vocal_audio(
                normalized,
                prefix,
                ecapa_checkpoint=config["ecapa_checkpoint"],
                singer_repo=config["singer_repo"],
                singer_checkpoint_root=config["singer_checkpoint_root"],
                device=config.get("device", "auto"),
                batch_size=8,
                max_segments=12,
                min_segments=5,
            )
            _update(job_id, progress=88, message="음색과 음역 적합도, 추천 키를 결합하고 있습니다.")
            catalog = CatalogIndex.load(config["index_dir"], verify_hashes=True)
            ranges = RangeCatalog.load(
                config["index_dir"],
                expected_track_ids={track.track_id for track in catalog.tracks},
                verify_hashes=True,
            )
            result = recommend(
                catalog,
                {
                    "ecapa": np.load(f"{prefix}.ecapa.npy", allow_pickle=False),
                    "singer_byol": np.load(f"{prefix}.singer_byol.npy", allow_pickle=False),
                },
                config=RankerConfig(top_k=top_k),
                excluded_track_ids=excluded_track_ids,
                query_pitch_profile=pitch_profile,
                catalog_pitch_profiles=ranges.profiles if pitch_profile is not None else None,
                mode=mode,
            )
            result["query"]["pitchError"] = pitch_error
        _update(
            job_id,
            status="complete",
            progress=100,
            message=(
                "음색과 음역 추천이 완료되었습니다."
                if result["algorithm"]["range_scoring"]["enabled"]
                else "음역 신뢰도가 낮아 음색 추천으로 완료했습니다."
            ),
            result=result,
        )
    except Exception as error:
        _update(
            job_id,
            status="failed",
            progress=100,
            message="맞춤 추천에 실패했습니다.",
            error=str(error),
        )
    finally:
        _cleanup_query_artifacts(job_dir)


for metadata_path in JOBS_DIR.glob("*/job.json") if JOBS_DIR.exists() else ():
    try:
        restored = json.loads(metadata_path.read_text(encoding="utf-8"))
        if restored.get("status") in {"queued", "processing"}:
            restored.update(status="failed", progress=100, message="AI 엔진이 중단되어 다시 실행해야 합니다.")
        JOBS[restored["id"]] = restored
    except Exception:
        continue


@router.get("/recommendations/status")
def recommendation_status() -> dict[str, Any]:
    import torch

    config, missing = _configuration_status()
    tracks = 0
    catalog_tracks: list[dict[str, str]] = []
    if not missing:
        try:
            catalog = CatalogIndex.load(config["index_dir"])
            RangeCatalog.load(
                config["index_dir"],
                expected_track_ids={track.track_id for track in catalog.tracks},
                verify_hashes=True,
            )
            tracks = len(catalog.tracks)
            catalog_tracks = [
                {
                    "track_id": track.track_id,
                    "title": track.title,
                    "artist": track.artist,
                }
                for track in catalog.tracks
            ]
        except Exception:
            missing.append("데모 카탈로그 검증")
    return {
        "ready": not missing,
        "device": "cuda" if torch.cuda.is_available() else "cpu",
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "catalogTracks": tracks,
        "catalog": catalog_tracks,
        "models": ["ECAPA-TDNN VoxCeleb", "Singer-BYOL", "VocalScope CREPE + pYIN"],
        "rangeModes": ["timbre", "range"],
        "missing": missing,
    }


@router.post("/recommendations", status_code=202)
async def create_recommendation(
    background_tasks: BackgroundTasks,
    audio: UploadFile = File(...),
    topK: int = Form(10),
    mode: str = Form("timbre"),
    sourceTrackId: str = Form(""),
) -> dict[str, Any]:
    config, missing = _configuration_status()
    if missing:
        raise HTTPException(503, f"추천 데모 설정이 필요합니다: {', '.join(missing)}")
    extension = Path(audio.filename or "audio").suffix.lower()
    if extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(415, "WAV, FLAC, MP3, M4A, OGG 또는 WEBM 파일만 지원합니다.")
    if mode not in {"timbre", "range"}:
        raise HTTPException(422, "mode는 timbre 또는 range여야 합니다.")
    source_track_id = sourceTrackId.strip()
    excluded_track_ids: tuple[str, ...] = ()
    if source_track_id:
        try:
            catalog = CatalogIndex.load(config["index_dir"])
        except Exception as error:
            raise HTTPException(503, f"추천 카탈로그를 읽지 못했습니다: {error}") from error
        if source_track_id not in {track.track_id for track in catalog.tracks}:
            raise HTTPException(422, "선택한 원곡이 추천 카탈로그에 없습니다.")
        excluded_track_ids = (source_track_id,)
    job_id = str(uuid.uuid4())
    job_dir = JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=False)
    input_path = job_dir / f"original{extension}"
    size = 0
    with input_path.open("wb") as output:
        while chunk := await audio.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_BYTES:
                output.close()
                shutil.rmtree(job_dir, ignore_errors=True)
                raise HTTPException(413, "보컬 파일은 250MB 이하만 사용할 수 있습니다.")
            output.write(chunk)
    JOBS[job_id] = {
        "id": job_id,
        "filename": audio.filename,
        "mode": mode,
        "sourceTrackId": source_track_id or None,
        "createdAt": time.time(),
        "status": "queued",
        "progress": 8,
        "message": "추천 대기열에 추가되었습니다.",
        "result": None,
        "error": None,
    }
    _save_job(job_id)
    background_tasks.add_task(
        _process,
        job_id,
        input_path,
        max(1, min(int(topK), 20)),
        mode,
        excluded_track_ids,
    )
    return JOBS[job_id]


@router.get("/recommendations/{job_id}")
def get_recommendation(job_id: str) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9a-f-]{36}", job_id, flags=re.IGNORECASE):
        raise HTTPException(400, "잘못된 추천 작업 번호입니다.")
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "추천 작업을 찾을 수 없습니다.")
    return job
