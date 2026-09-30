"""BE → 추천 AI 음성 분석 작업. 녹음 WAV 를 받아 음색 임베딩을 만들고 BE 콜백으로 돌려준다.

계약과 환경변수는 README 의 API 절에 있다.
"""

from __future__ import annotations

from datetime import datetime, timedelta
import hashlib
import json
import logging
import os
from pathlib import Path
import re
import threading
import time
import shutil
import subprocess
import sys
from typing import Any
import urllib.error
import urllib.request
import uuid

from fastapi import APIRouter, BackgroundTasks, HTTPException, Request
from artist_catalog import public_response
import librosa
import numpy as np
import soundfile as sf

from mr_detection import detect_mr_suspected
from recommendation_api import _configuration_status, _replace_with_retry
from separation import separate
from runtime_info import build_sha
from timbre_recommender.embedding import (
    ANALYSIS_SAMPLE_RATE,
    WINDOW_SAMPLES,
    VocalEmbeddingRuntime,
    embed_vocal_audio,
)


log = logging.getLogger("uvicorn.error").getChild("analysis_jobs")
ROOT = Path(__file__).resolve().parent
ANALYSIS_DIR = ROOT / "workspace" / "analysis"
MAX_BYTES = 64 * 1024 * 1024
MAX_DURATION_SECONDS = 120.0
TIMBRE_PARTS = ("timbreFile", "file")        # 음색 임베딩에 쓰는 파트. 앞의 것이 우선
COMPONENT_MODELS = {"ECAPA": ("ecapa", 192), "SINGER_BYOL": ("singer_byol", 1000)}
TERMINAL = {"COMPLETED", "FAILED", "INSUFFICIENT_DATA", "CANCELED"}

LOCK = threading.RLock()
INGEST_LOCK = threading.Lock()
DELIVERY_LOCK = threading.Lock()
JOBS: dict[str, dict[str, Any]] = {}          # jobId → job
REQUESTS: dict[str, str] = {}                 # requestId → jobId
_slots: threading.BoundedSemaphore | None = None
_runtime_lock = threading.Lock()
_timbre_runtime: VocalEmbeddingRuntime | None = None
_timbre_runtime_key: tuple[str, str, str, str, int, bool] | None = None
_timbre_runtime_warmed = False
router = APIRouter(tags=["voice-analysis-jobs"])


def _env_int(name: str, default: int) -> int:
    try:
        return max(1, int(os.environ.get(name, "") or default))
    except ValueError:
        return default


def _env_bool(name: str, default: bool = False) -> bool:
    value = os.environ.get(name)
    if value is None or not value.strip():
        return default
    return value.strip().lower() in {"1", "true", "yes", "on"}


def _now() -> datetime:
    return datetime.now().replace(microsecond=0)


def _iso(value: datetime) -> str:
    return value.isoformat(timespec="seconds")      # BE 는 LocalDateTime: 오프셋 없는 ISO


def _components(meta: dict[str, Any]) -> list[str]:
    expected = ["RANGE"] if meta.get("analysisType") == "RANGE" else list(COMPONENT_MODELS)
    if meta.get("analysisType") not in {"RANGE", "TIMBRE"} or meta.get("components") != expected:
        raise HTTPException(422, "analysisType과 components가 일치해야 합니다.")
    return expected


def _slot() -> threading.BoundedSemaphore:
    global _slots
    with LOCK:
        if _slots is None:
            _slots = threading.BoundedSemaphore(_env_int("RECOMMEND_CONCURRENCY", 1))
    return _slots


def _runtime_key(config: dict[str, Any]) -> tuple[str, str, str, str, int, bool]:
    return (
        str(Path(config["ecapa_checkpoint"]).resolve()),
        str(Path(config["singer_repo"]).resolve()),
        str(Path(config["singer_checkpoint_root"]).resolve()),
        str(config.get("device", "auto")),
        8,
        _env_bool("RECOMMEND_EMBEDDING_OFFLOAD", False),
    )


def get_timbre_runtime(config: dict[str, Any]) -> VocalEmbeddingRuntime:
    """Return the process-wide model runtime instead of loading both models per request."""
    global _timbre_runtime, _timbre_runtime_key
    key = _runtime_key(config)
    with _runtime_lock:
        if _timbre_runtime is None or _timbre_runtime_key != key:
            started = time.perf_counter()
            _timbre_runtime = VocalEmbeddingRuntime(
                ecapa_checkpoint=key[0], singer_repo=key[1], singer_checkpoint_root=key[2],
                device=key[3], batch_size=key[4], offload_after_embed=key[5],
            )
            _timbre_runtime_key = key
            log.info(
                "event=timbre_runtime_loaded build_sha=%s device=%s offload=%s duration_ms=%d",
                build_sha(), _timbre_runtime.device, key[5],
                round((time.perf_counter() - started) * 1000),
            )
        return _timbre_runtime


def preload_timbre_runtime() -> dict[str, Any]:
    """Load and warm models before the first request when the model config is available."""
    global _timbre_runtime_warmed
    config, missing = _configuration_status()
    model_missing = [name for name in missing if "카탈로그" not in name]
    if model_missing:
        log.warning("timbre runtime preload skipped: %s", ", ".join(model_missing))
        return {"loaded": False, "warmed": False, "missing": model_missing}
    runtime = get_timbre_runtime(config)
    if not _timbre_runtime_warmed:
        started = time.perf_counter()
        seconds = np.arange(WINDOW_SAMPLES, dtype=np.float32) / ANALYSIS_SAMPLE_RATE
        warmup = (0.05 * np.sin(2 * np.pi * 220 * seconds)).reshape(1, -1)
        runtime.embed_windows(warmup)
        _timbre_runtime_warmed = True
        log.info(
            "event=timbre_runtime_warmed build_sha=%s device=%s duration_ms=%d",
            build_sha(), runtime.device, round((time.perf_counter() - started) * 1000),
        )
    return {"loaded": True, "warmed": _timbre_runtime_warmed, "device": runtime.device}


def timbre_runtime_status() -> dict[str, Any]:
    runtime = _timbre_runtime
    return {
        "loaded": runtime is not None,
        "warmed": _timbre_runtime_warmed,
        "device": runtime.device if runtime is not None else None,
    }


def _save(job: dict[str, Any]) -> None:
    job_dir = ANALYSIS_DIR / job["jobId"]
    job_dir.mkdir(parents=True, exist_ok=True)
    temporary = job_dir / ".job.json.tmp"
    temporary.write_text(json.dumps(job, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    _replace_with_retry(temporary, job_dir / "job.json")


def _update(job_id: str, **values: Any) -> dict[str, Any]:
    with LOCK:
        job = JOBS[job_id]
        job.update(values)
        job["updatedAt"] = _iso(_now())
        _save(job)
        return job


def restore_jobs() -> None:
    """서버 재시작 뒤 작업과 미전송 콜백을 복원한다. 미완료 계산은 다시 시작한다."""
    if not ANALYSIS_DIR.is_dir():
        return
    for path in ANALYSIS_DIR.glob("*/job.json"):
        try:
            job = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if job.get("status") in {"QUEUED", "RUNNING"}:
            job.update(status="QUEUED")
        JOBS[job["jobId"]] = job
        REQUESTS[job["requestId"]] = job["jobId"]
        _save(job)


# ── 접수 ─────────────────────────────────────────────────────────────────────

def _audio_info(path: Path, maximum_seconds: float = MAX_DURATION_SECONDS) -> dict[str, Any]:
    try:
        info = sf.info(path)
    except Exception as error:
        raise HTTPException(415, f"디코딩할 수 없는 오디오입니다: {str(error)[:200]}") from error
    if info.duration <= 0 or info.duration > maximum_seconds + 0.05:
        raise HTTPException(422, f"녹음은 0초 초과 {maximum_seconds:g}초 이하여야 합니다.")
    if info.channels != 1 or info.samplerate not in (16000, 44100, 48000) or info.subtype != "PCM_16":
        raise HTTPException(422, "16/44.1/48kHz mono PCM16 WAV가 필요합니다.")
    return {"durationSeconds": round(float(info.duration), 3), "sampleRate": int(info.samplerate), "channels": int(info.channels)}


def _audio_alive(job: dict[str, Any]) -> bool:
    return not job.get("audioDeleted") and any((ANALYSIS_DIR / job["jobId"] / f["stored"]).is_file() for f in job["files"].values())


def _duplicate_response(job: dict[str, Any]) -> dict[str, Any]:
    # CALLBACK_PENDING is internal; the receipt reports the computed result.
    status = job.get("componentStatus") if job["status"] == "CALLBACK_PENDING" else job["status"]
    return {"jobId": job["jobId"], "status": status or job["status"],
            "audioExpiresAt": job["audioExpiresAt"], "duplicate": True}


def ingest(files: dict[str, bytes], meta: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    with INGEST_LOCK:
        return _ingest(files, meta)


def _ingest(files: dict[str, bytes], meta: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    """파일과 meta 를 받아 작업을 만든다. (응답 dict, 새 작업이면 True). 멱등 규칙은 여기 한 곳."""
    request_id = str(meta.get("requestId") or "").strip()
    try:
        uuid.UUID(request_id)
        # BE uses negative IDs for guest sessions and jobs, positive IDs for members.
        if any(type(meta.get(k)) is not int or meta[k] == 0 for k in ("analysisId", "sessionId")):
            raise ValueError()
        if type(meta.get("attemptNo")) is not int or meta["attemptNo"] < 1:
            raise ValueError()
        expected_contract = "v2" if meta.get("analysisType") == "RANGE" and meta.get("protocolVersion") == "guided-range-v8" else "v1"
        if meta.get("contractVersion") != expected_contract:
            raise ValueError()
    except (ValueError, TypeError):
        raise HTTPException(422, "requestId UUID, 0이 아닌 정수 analysisId/sessionId, 양수 attemptNo, 올바른 contractVersion이 필요합니다.")
    components = _components(meta)
    if set(files) != {"file"} or not files["file"] or len(files["file"]) > MAX_BYTES:
        raise HTTPException(422, "64MB 이하 file WAV 파트 하나가 필요합니다.")
    if not any(part in files for part in TIMBRE_PARTS):
        raise HTTPException(422, f"음색 녹음 파트가 필요합니다: {' 또는 '.join(TIMBRE_PARTS)}")
    digest = hashlib.sha256()
    digest.update(json.dumps(meta, sort_keys=True, separators=(",", ":"), allow_nan=False).encode("utf-8"))
    for name in sorted(files):
        digest.update(name.encode("utf-8")); digest.update(files[name])
    audio_sha = digest.hexdigest()

    with LOCK:
        existing = JOBS.get(REQUESTS.get(request_id, ""))
    if existing:
        if existing["audioSha256"] != audio_sha:
            raise HTTPException(409, {"code": "IDEMPOTENCY_KEY_REUSED", "message": "같은 requestId 로 다른 음원이 전송되었습니다."})
        if existing["status"] == "COMPLETED":
            return _duplicate_response(existing), False
        if existing["status"] == "CALLBACK_PENDING":
            # The route schedules delivery after returning 202; sweep also retries it.
            _update(existing["jobId"], nextCallbackAt=None)
            return _duplicate_response(existing), False
        if not _audio_alive(existing):
            raise HTTPException(410, {"code": "AUDIO_EXPIRED", "message": "원음이 만료되어 다시 녹음해야 합니다."})
        # A terminal BE component is immutable. A new attempt uses a new requestId.
        return _duplicate_response(existing), False

    job_id = f"job_{uuid.uuid4().hex}"
    job_dir = ANALYSIS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=False)
    stored: dict[str, dict[str, Any]] = {}
    try:
        for name, data in files.items():
            path = job_dir / f"{name}.wav"
            path.write_bytes(data)
            stored[name] = {"stored": path.name, "bytes": len(data),
                            **_audio_info(path, 180.0 if components == ["RANGE"] else 120.0)}
        if components != ["RANGE"] and stored["file"]["sampleRate"] < 44100:
            raise HTTPException(422, "음색 녹음은 44.1kHz 또는 48kHz 원음이 필요합니다.")
    except Exception:
        shutil.rmtree(job_dir)
        raise
    created = _now()
    hard = created + timedelta(seconds=_env_int("RECOMMEND_AUDIO_HARD_TTL_SECONDS", 86400))
    job = {
        "jobId": job_id, "requestId": request_id, "analysisId": meta.get("analysisId"), "meta": meta,
        "files": stored, "audioSha256": audio_sha, "status": "QUEUED",
        "serverBuildSha": build_sha(),
        "components": {c: {"status": "PENDING", "accepted": False} for c in components},
        "createdAt": _iso(created), "updatedAt": _iso(created),
        "audioExpiresAt": _iso(hard), "hardExpiresAt": _iso(hard), "softExpiresAt": None, "audioDeleted": False,
        "failureCode": None, "failureReason": None,
    }
    with LOCK:
        try:
            _save(job)
        except Exception:
            shutil.rmtree(job_dir)
            raise
        JOBS[job_id] = job
        REQUESTS[request_id] = job_id
    return {"jobId": job_id, "status": "QUEUED", "audioExpiresAt": job["audioExpiresAt"], "duplicate": False}, True


@router.post("/voice-analysis-jobs", status_code=202)
async def create_job(request: Request, background_tasks: BackgroundTasks) -> dict[str, Any]:
    form = await request.form(max_part_size=8_000_000)
    files: dict[str, bytes] = {}
    meta: dict[str, Any] | None = None
    try:
        for name, value in form.multi_items():
            if name == "meta":
                raw = value if isinstance(value, str) else (await value.read(8_000_001)).decode("utf-8")
                if len(raw.encode("utf-8")) > 8_000_000:
                    raise HTTPException(413, "meta 크기 초과")
                try:
                    meta = json.loads(raw)
                except json.JSONDecodeError as error:
                    raise HTTPException(422, "meta 는 JSON 이어야 합니다.") from error
            elif hasattr(value, "read"):
                if name != "file" or name in files:
                    raise HTTPException(422, "file 파트는 하나만 받습니다.")
                chunks = bytearray()
                while chunk := await value.read(65536):
                    chunks.extend(chunk)
                    if len(chunks) > MAX_BYTES:
                        raise HTTPException(413, "녹음 크기 초과")
                files[name] = bytes(chunks)
    finally:
        await form.close()
    if not isinstance(meta, dict):
        raise HTTPException(422, "meta 파트가 필요합니다.")
    components = _components(meta)
    if components != ["RANGE"]:
        _, missing = _configuration_status()
        missing = [name for name in missing if "카탈로그" not in name]
        if missing:
            raise HTTPException(503, f"음색 모델 설정이 필요합니다: {', '.join(missing)}")
    if not os.environ.get("MICON_BE_CALLBACK_URL", "").strip():
        raise HTTPException(503, "BE callback URL이 설정되지 않았습니다.")
    response, should_run = ingest(files, meta)
    log.info(
        "event=voice_analysis_ai_accepted build_sha=%s request_id=%s session_id=%s analysis_id=%s "
        "ai_job_id=%s duplicate=%s",
        build_sha(), meta.get("requestId"), meta.get("sessionId"), meta.get("analysisId"),
        response["jobId"], not should_run,
    )
    if should_run:
        background_tasks.add_task(process, response["jobId"])
    elif JOBS[response["jobId"]]["status"] == "CALLBACK_PENDING":
        background_tasks.add_task(deliver_callbacks, response["jobId"])
    return response


@router.post("/voice-analysis-jobs/{job_id}/cancel")
def cancel_job(job_id: str) -> dict[str, Any]:
    job = _get(job_id)
    running = job["status"] == "RUNNING"
    if job["status"] not in TERMINAL:
        _update(job_id, status="CANCELED")
    if not running:                              # 계산 중이면 worker 가 끝난 뒤 지운다
        _delete_audio(job_id)
    return {"jobId": job_id, "status": JOBS[job_id]["status"]}


@router.post("/voice-analysis-jobs/{job_id}/retry", status_code=202)
def retry_job(job_id: str, background_tasks: BackgroundTasks, component: str) -> dict[str, Any]:
    job = _get(job_id)
    if component not in job["components"]:
        raise HTTPException(422, "재시도할 수 없는 컴포넌트입니다.")
    state = job["components"][component]
    if state.get("status") not in {"FAILED", "INSUFFICIENT_DATA"}:
        raise HTTPException(409, "실패한 컴포넌트만 재시도할 수 있습니다.")
    if job.get("audioDeleted") or _now() >= datetime.fromisoformat(job["hardExpiresAt"]):
        raise HTTPException(410, "재시도할 원본 음원이 만료됐습니다.")
    components = dict(job["components"])
    components[component] = {"status": "PENDING", "accepted": False}
    _update(job_id, status="QUEUED", componentStatus="RUNNING", components=components,
            failureCode=None, failureReason=None)
    background_tasks.add_task(process, job_id)
    current = JOBS[job_id]
    return {"jobId": job_id, "status": "QUEUED",
            "audioExpiresAt": current["audioExpiresAt"], "duplicate": True}


@router.get("/voice-analysis-jobs/{job_id}")
def get_job(job_id: str) -> dict[str, Any]:
    return public_response(_get(job_id))


def _get(job_id: str) -> dict[str, Any]:
    if not re.fullmatch(r"job_[0-9a-f]{32}", job_id):
        raise HTTPException(400, "잘못된 작업 번호입니다.")
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "작업을 찾을 수 없습니다.")
    return job


# ── 계산과 콜백 ───────────────────────────────────────────────────────────────

def _attempt_no(job: dict[str, Any]) -> int:
    try:
        return max(1, int(job.get("meta", {}).get("attemptNo", 1)))
    except (TypeError, ValueError):
        return 1


def build_callbacks(job: dict[str, Any], ecapa: np.ndarray, byol: np.ndarray, metadata: dict[str, Any],
                    preprocessing_version: str = "raw-legacy-v0") -> dict[str, dict[str, Any]]:
    """컴포넌트별 COMPLETED 콜백 본문.

    segmentInfo 는 명세의 세 필드에 embeddings 를 더한다. 구간별 벡터가 없으면 추천 점수의 70%가 사라진다.
    """
    matrices = {"ecapa": ecapa, "singer_byol": byol}
    segments = metadata["segments"]
    duration = job["files"][_timbre_part(job)]["durationSeconds"]
    quality = {
        "voicedDurationSeconds": round(duration * float(np.mean([s["active_frame_fraction"] for s in segments])), 2),
        "clippingRatio": round(float(np.max([s["clipped_fraction"] for s in segments])), 5),
    }
    attempt = _attempt_no(job)
    out: dict[str, dict[str, Any]] = {}
    for component in job["components"]:
        key, dimension = COMPONENT_MODELS[component]
        rows = matrices[key].astype(np.float64)
        if rows.ndim != 2 or rows.shape != (len(segments), dimension) or not 5 <= len(rows) <= 12:
            raise ValueError("Invalid embedding dimensions or segment count")
        if not np.isfinite(rows).all() or np.any(np.linalg.norm(rows, axis=1) < 1e-12):
            raise ValueError("Embeddings must be finite and nonzero")
        rows /= np.maximum(np.linalg.norm(rows, axis=1, keepdims=True), 1e-12)
        mean = rows.mean(axis=0)
        mean_norm = float(np.linalg.norm(mean))
        if not np.isfinite(mean_norm) or mean_norm < 1e-12:
            raise ValueError("Segment embeddings do not have a usable mean")
        mean /= mean_norm
        out[component] = {
            "requestId": job["requestId"], "jobId": job["jobId"], "component": component, "attemptNo": attempt,
            "status": "COMPLETED", "modelKey": component,
            "modelVersion": f"{metadata['models'][key]['checkpoint_sha256']}+{preprocessing_version}", "dimension": dimension,
            "embedding": [round(float(v), 7) for v in mean],
            "segmentInfo": {
                "segmentDurationSeconds": metadata["window_seconds"],
                "segmentCount": len(segments),
                "validChunkCount": len(segments),
                "startTimes": [round(s["start_seconds"], 3) for s in segments],
                "embeddings": [[round(float(v), 7) for v in row] for row in rows],
            },
            "qualityMetrics": quality, "failureCode": None, "failureReason": None, "processedAt": _iso(_now()),
        }
    return out


def _failure_callback(job: dict[str, Any], component: str, status: str, code: str, reason: str) -> dict[str, Any]:
    return {"requestId": job["requestId"], "jobId": job["jobId"], "component": component,
            "attemptNo": _attempt_no(job), "status": status, "modelKey": component,
            "modelVersion": None, "dimension": None, "embedding": None, "segmentInfo": None, "qualityMetrics": None,
            "failureCode": code, "failureReason": reason[:1000], "processedAt": _iso(_now())}


def _log_callback_failure(payload: dict[str, Any], result: dict[str, Any], *, retrying: bool) -> None:
    """Persisted callback state is overwritten by later rounds, so log every failed delivery."""
    log.warning(
        "event=voice_callback_delivery_%s build_sha=%s ai_job_id=%s component=%s attempt=%s "
        "http=%s error=%s",
        "retrying" if retrying else "failed", build_sha(), payload.get("jobId"),
        payload.get("component"), result["attempts"], result["http"],
        json.dumps((result.get("error") or "")[:500], ensure_ascii=False),
    )


def post_callback(payload: dict[str, Any]) -> dict[str, Any]:
    """콜백 1건. 5xx·연결 오류·타임아웃은 3회 재시도, 4xx 는 즉시 중단. 결과 {http, attempts, accepted}."""
    url = os.environ.get("MICON_BE_CALLBACK_URL", "").strip()
    if not url:
        log.warning("callback skipped job=%s component=%s: MICON_BE_CALLBACK_URL 미설정",
                    payload.get("jobId"), payload.get("component"))
        return {"http": None, "attempts": 0, "accepted": False, "skipped": "MICON_BE_CALLBACK_URL 미설정"}
    headers = {"Content-Type": "application/json"}
    token = os.environ.get("AI_SERVICE_TOKEN", "").strip()
    if token:
        headers["Authorization"] = f"Bearer {token}"
    body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
    last: dict[str, Any] = {"http": None, "attempts": 0, "accepted": False}
    for attempt in range(1, 4):
        last["attempts"] = attempt
        try:
            with urllib.request.urlopen(urllib.request.Request(url, data=body, headers=headers, method="POST"), timeout=10) as response:
                last.update(http=response.status, accepted=True)
                return last
        except urllib.error.HTTPError as error:
            last["http"] = error.code
            last["error"] = error.read(500).decode("utf-8", "replace")
            if error.code < 500:
                _log_callback_failure(payload, last, retrying=False)
                return last
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last["error"] = str(error)[:200]
        _log_callback_failure(payload, last, retrying=attempt < 3)
        if attempt < 3:
            time.sleep(2 ** (attempt - 1))
    return last


def _upgrade_callback_payload(payload: dict[str, Any]) -> bool:
    """Upgrade persisted pre-contract TIMBRE callbacks without recomputing embeddings."""
    if payload.get("status") != "COMPLETED" or payload.get("component") not in COMPONENT_MODELS:
        return False
    segment_info = payload.get("segmentInfo")
    if not isinstance(segment_info, dict) or "validChunkCount" in segment_info:
        return False
    embeddings = segment_info.get("embeddings")
    segment_count = segment_info.get("segmentCount")
    if (not isinstance(embeddings, list) or not isinstance(segment_count, int)
            or segment_count != len(embeddings) or not 5 <= segment_count <= 12):
        return False
    segment_info["validChunkCount"] = segment_count
    return True


def _timbre_part(job: dict[str, Any]) -> str:
    return next(part for part in TIMBRE_PARTS if part in job["files"])


def _separator_device(requested: str) -> str:
    if requested != "auto":
        return requested
    import torch
    return "cuda" if torch.cuda.is_available() else "cpu"


def _prepare_separation_input(source: Path, destination: Path) -> None:
    """Mel-Band requires 44.1 kHz while app recordings can be 48 kHz."""
    signal, sample_rate = sf.read(source, dtype="float32", always_2d=True)
    if sample_rate != 44_100:
        signal = librosa.resample(signal.T, orig_sr=sample_rate, target_sr=44_100,
                                  res_type="soxr_hq", axis=-1).T
    sf.write(destination, signal, 44_100, subtype="PCM_16")


def process(job_id: str) -> None:
    job = JOBS[job_id]
    with LOCK:
        if job["status"] != "QUEUED":
            return
        _update(job_id, status="RUNNING")
    config, _ = _configuration_status()
    job_dir = ANALYSIS_DIR / job_id
    audio = job_dir / job["files"][_timbre_part(job)]["stored"]
    prefix = job_dir / "query"
    pending = [c for c, state in job["components"].items() if not state["accepted"]]
    callbacks: dict[str, dict[str, Any]] = {}
    slot_started = time.perf_counter()
    slot_wait_ms = 0.0
    inference_started: float | None = None
    try:
        with _slot():
            slot_wait_ms = round((time.perf_counter() - slot_started) * 1000, 1)
            inference_started = time.perf_counter()
            log.info(
                "event=voice_analysis_ai_started build_sha=%s request_id=%s session_id=%s "
                "analysis_id=%s ai_job_id=%s queue_wait_ms=%d slot_wait_ms=%.1f",
                build_sha(), job["requestId"], job["meta"].get("sessionId"), job.get("analysisId"),
                job_id,
                max(0, round((datetime.now() - datetime.fromisoformat(job["createdAt"])).total_seconds() * 1000)),
                slot_wait_ms,
            )
            if JOBS[job_id]["status"] == "CANCELED":
                _delete_audio(job_id)
                return
            if job["meta"]["analysisType"] == "RANGE":
                callbacks = {"RANGE": _range_callback(job, audio)}
            else:
                callbacks = _timbre_callbacks(job, audio, prefix, config)
        callbacks = {c: callbacks[c] for c in pending}
    except ValueError as error:                     # 쓸 만한 4초 구간 부족 등 입력 문제
        callbacks = {c: _failure_callback(job, c, "INSUFFICIENT_DATA", "INSUFFICIENT_DATA", str(error)) for c in pending}
    except Exception as error:
        log.exception("analysis job %s failed", job_id)
        callbacks = {c: _failure_callback(job, c, "FAILED", "AI_ANALYSIS_FAILED", str(error)) for c in pending}
    finally:
        for name in (f"{prefix}.ecapa.npy", f"{prefix}.singer_byol.npy", f"{prefix}.segments.json"):
            Path(name).unlink(missing_ok=True)

    if JOBS[job_id]["status"] == "CANCELED":
        _delete_audio(job_id)
        return
    components = dict(job["components"])
    for component, payload in callbacks.items():
        payload_file = job_dir / f"callback.{component}.json"
        temporary = payload_file.with_suffix(".tmp")
        temporary.write_text(json.dumps(payload, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        _replace_with_retry(temporary, payload_file)
        components[component] = {"status": payload["status"], "accepted": False,
                                 "failureCode": payload["failureCode"], "processedAt": payload["processedAt"]}
    performance = dict(JOBS[job_id].get("performance") or {})
    performance.update(
        slotWaitMs=slot_wait_ms,
        inferenceMs=round((time.perf_counter() - (inference_started or slot_started)) * 1000, 1),
    )
    _update(job_id, performance=performance)
    _finish(job_id, components)
    log.info(
        "event=voice_analysis_ai_computed build_sha=%s request_id=%s session_id=%s analysis_id=%s "
        "ai_job_id=%s slot_wait_ms=%.1f inference_ms=%.1f component_status=%s",
        build_sha(), job["requestId"], job["meta"].get("sessionId"), job.get("analysisId"), job_id,
        slot_wait_ms, performance["inferenceMs"], JOBS[job_id].get("componentStatus"),
    )
    deliver_callbacks(job_id)


def _finish(job_id: str, components: dict[str, Any]) -> None:
    """Keep the computed result separate from delivery until BE acknowledges it."""
    with LOCK:
        job = JOBS[job_id]
        if job["status"] == "CANCELED" or job.get("audioDeleted"):
            return
        statuses = {c["status"] for c in components.values()}
        status = "COMPLETED" if statuses == {"COMPLETED"} else ("INSUFFICIENT_DATA" if "INSUFFICIENT_DATA" in statuses else "FAILED")
        accepted = all(c["accepted"] for c in components.values())
        failed = next((c for c in components.values() if c["status"] != "COMPLETED"), None)
        values: dict[str, Any] = {
            "status": status if accepted else "CALLBACK_PENDING", "componentStatus": status,
            "components": components, "failureCode": failed and failed.get("failureCode"),
            "failureReason": None,
        }
        if accepted:
            values["nextCallbackAt"] = None
            if job.get("softExpiresAt") is None:
                soft = min(_now() + timedelta(seconds=_env_int("RECOMMEND_AUDIO_TTL_SECONDS", 3600)),
                           datetime.fromisoformat(job["hardExpiresAt"]))
                values.update(softExpiresAt=_iso(soft), audioExpiresAt=_iso(soft))
        _update(job_id, **values)


def _timbre_callbacks(job, audio, prefix, config):
    started = time.perf_counter()
    timings: dict[str, Any] = {}
    _limit_torch_threads()
    stage = time.perf_counter()
    detection = detect_mr_suspected(audio)
    timings["mrDetectionMs"] = round((time.perf_counter() - stage) * 1000, 1)
    embedding_audio = audio
    preprocessing_version = "raw-clean-v1"
    timings["separationMs"] = 0.0
    timings["separationPrepareMs"] = 0.0
    separation_info: dict[str, Any] = {}
    if detection["requiresSeparation"]:
        root = ANALYSIS_DIR / job["jobId"]
        separation_input = root / "separation-input.wav"
        stage = time.perf_counter()
        try:
            _prepare_separation_input(audio, separation_input)
            timings["separationPrepareMs"] = round((time.perf_counter() - stage) * 1000, 1)
            vocals, _, separation_info = separate(
                separation_input, root / "vocal-separation", _separator_device(config.get("device", "auto")),
                backend="melband", allow_fallback=False,
            )
        except Exception as error:
            detection["separation"] = {"status": "FAILED", "failureReason": str(error)[:500]}
            detection["embeddingInput"] = "raw-recording"
            detection["preprocessingVersion"] = preprocessing_version
        else:
            detection["separation"] = {"status": "COMPLETED", **separation_info}
            detection["embeddingInput"] = "separated-vocals"
            embedding_audio = vocals
            preprocessing_version = "sep-melband-kim-v1"
        timings["separationMs"] = round((time.perf_counter() - stage) * 1000, 1)
    else:
        detection["embeddingInput"] = "raw-recording"
    detection["preprocessingVersion"] = preprocessing_version
    _update(job["jobId"], mrDetection=detection)
    stage = time.perf_counter()
    runtime = get_timbre_runtime(config)
    timings["runtimeAcquireMs"] = round((time.perf_counter() - stage) * 1000, 1)
    metadata = embed_vocal_audio(
        embedding_audio, prefix, ecapa_checkpoint=config["ecapa_checkpoint"], singer_repo=config["singer_repo"],
        singer_checkpoint_root=config["singer_checkpoint_root"], device=config.get("device", "auto"),
        batch_size=8, max_segments=12, min_segments=5, force=True, runtime=runtime, timings=timings,
    )
    callbacks = build_callbacks(job, np.load(f"{prefix}.ecapa.npy", allow_pickle=False),
                                np.load(f"{prefix}.singer_byol.npy", allow_pickle=False), metadata,
                                preprocessing_version)
    timings["totalMs"] = round((time.perf_counter() - started) * 1000, 1)
    timings["device"] = runtime.device
    timings["usedSeparation"] = detection["requiresSeparation"]
    _update(job["jobId"], performance=timings)
    log.info(
        "event=voice_analysis_ai_profile build_sha=%s ai_job_id=%s device=%s segments=%s "
        "mr_detection_ms=%s separation_ms=%s runtime_acquire_ms=%s segmentation_ms=%s "
        "ecapa_ms=%s singer_byol_ms=%s artifact_write_ms=%s total_ms=%s "
        "separation_prepare_ms=%s separation_input_seconds=%s model_load_ms=%s "
        "model_to_device_ms=%s demix_ms=%s model_to_cpu_ms=%s output_write_ms=%s",
        build_sha(), job["jobId"], runtime.device, timings.get("segmentCount"),
        timings.get("mrDetectionMs"), timings.get("separationMs"), timings.get("runtimeAcquireMs"),
        timings.get("segmentationMs"), timings.get("ecapaMs"), timings.get("singerByolMs"),
        timings.get("artifactWriteMs"), timings.get("totalMs"),
        timings.get("separationPrepareMs"), separation_info.get("inputSeconds"),
        separation_info.get("modelLoadMs"), separation_info.get("modelToDeviceMs"),
        separation_info.get("demixMs"), separation_info.get("modelToCpuMs"),
        separation_info.get("outputWriteMs"),
    )
    return callbacks


def _range_callback(job, audio):
    root = ANALYSIS_DIR / job["jobId"]
    meta_path = root / "range-meta.json"
    output = root / "range-result.json"
    meta_path.write_text(json.dumps(job["meta"], ensure_ascii=False), encoding="utf-8")
    command = [os.environ.get("MICON_RANGE_MODEL_PYTHON", sys.executable),
               str(ROOT.parent / "vocal-scope" / "model-server" / "be_range_worker.py"),
               str(audio.resolve()), str(meta_path.resolve()), str(output.resolve())]
    parent = job["meta"].get("parentAnalysisId")
    if parent is not None:
        previous = next((j for j in JOBS.values() if j["analysisId"] == parent and
                         j["meta"].get("analysisType") == "RANGE"), None)
        if previous is None:
            raise ValueError("이전 음역 결과가 만료되었습니다. 기본 측정을 다시 시작해 주세요.")
        command.append(str((ANALYSIS_DIR / previous["jobId"] / "range-result.json").resolve()))
    run = subprocess.run(command, capture_output=True, text=True, encoding="utf-8", errors="replace", timeout=600)
    if run.returncode != 0:
        raise RuntimeError("음역 모델 검증 실패: " + run.stderr[-500:])
    report = json.loads(output.read_text(encoding="utf-8"))
    payload = _failure_callback(job, "RANGE", "INSUFFICIENT_DATA", "INSUFFICIENT_DATA", "확인된 음역이 없습니다.")
    if report.get("availability") != "available":
        return payload
    protocol = report.get("protocolVersion", "guided-range-v7")
    payload.update(status="COMPLETED", modelKey="RANGE", modelVersion=protocol,
                   failureCode=None, failureReason=None, measurementId=job["meta"]["measurementId"],
                   protocolVersion=protocol, lowMidi=report["lowMidi"], highMidi=report["highMidi"],
                   stableLowMidi=report.get("stableLowMidi"), stableHighMidi=report.get("stableHighMidi"),
                   noteResults=report["notes"], measurementStatus="AVAILABLE", qualityStatus="VALID")
    return payload


def deliver_callbacks(job_id: str) -> None:
    with DELIVERY_LOCK:
        job = JOBS[job_id]
        if job["status"] in {"CANCELED", "RUNNING", "QUEUED"} or job.get("audioDeleted"):
            return
        if job.get("softExpiresAt") and all(c["accepted"] for c in job["components"].values()):
            return
        if job.get("nextCallbackAt") and _now() < datetime.fromisoformat(job["nextCallbackAt"]):
            return
        for component, state in list(job["components"].items()):
            payload = ANALYSIS_DIR / job_id / f"callback.{component}.json"
            if state["accepted"] or not payload.is_file():
                continue
            body = json.loads(payload.read_text(encoding="utf-8"))
            if _upgrade_callback_payload(body):
                temporary = payload.with_suffix(".tmp")
                temporary.write_text(json.dumps(body, ensure_ascii=False, allow_nan=False), encoding="utf-8")
                _replace_with_retry(temporary, payload)
                log.info("event=voice_callback_payload_upgraded build_sha=%s ai_job_id=%s component=%s",
                         build_sha(), job_id, component)
            delivery_started = time.perf_counter()
            result = post_callback(body)
            duration_ms = round((time.perf_counter() - delivery_started) * 1000)
            state.update(accepted=result["accepted"], callback=result)
            if not result["accepted"]:
                state.update(
                    callbackFailures=int(state.get("callbackFailures") or 0) + 1,
                    lastCallbackFailure={
                        "at": _iso(_now()), "http": result.get("http"), "attempts": result.get("attempts"),
                        "error": result.get("error") or result.get("skipped"),
                    },
                )
            elif state.get("callbackFailures"):
                log.warning(
                    "callback accepted after %s failed deliveries job=%s component=%s http=%s",
                    state["callbackFailures"], job_id, component, result.get("http"),
                )
            log.info(
                "event=voice_callback_delivery_finished build_sha=%s request_id=%s session_id=%s "
                "analysis_id=%s ai_job_id=%s component=%s accepted=%s http=%s attempts=%s duration_ms=%s",
                build_sha(), job["requestId"], job["meta"].get("sessionId"), job.get("analysisId"),
                job_id, component, result["accepted"], result.get("http"), result.get("attempts"), duration_ms,
            )
            _update(job_id, components=job["components"])
        _finish(job_id, job["components"])
        if job["status"] == "CALLBACK_PENDING" and not job.get("audioDeleted"):
            attempts = job.get("callbackRounds", 0) + 1
            _update(job_id, callbackRounds=attempts,
                    nextCallbackAt=_iso(_now() + timedelta(seconds=min(60, 2 ** min(attempts, 6)))))


def _limit_torch_threads() -> None:
    import torch

    if not torch.cuda.is_available():
        torch.set_num_threads(max(1, (os.cpu_count() or 2) // _env_int("RECOMMEND_CONCURRENCY", 2)))


# ── 원음 삭제 ─────────────────────────────────────────────────────────────────

def _delete_audio(job_id: str) -> None:
    job = JOBS[job_id]
    for entry in job["files"].values():
        (ANALYSIS_DIR / job_id / entry["stored"]).unlink(missing_ok=True)
    for copy in (ANALYSIS_DIR / job_id).glob("callback.*.json"):     # 임베딩 사본도 원음과 함께 지운다. 정본은 BE
        copy.unlink(missing_ok=True)
    for name in ("range-meta.json", "range-result.json"):
        (ANALYSIS_DIR / job_id / name).unlink(missing_ok=True)
    if not job.get("audioDeleted"):
        _update(job_id, audioDeleted=True)


def sweep(now: datetime | None = None) -> list[str]:
    """만료된 원음을 지운다. 삭제 시각 = min(soft, hard). 지운 jobId 목록을 돌려준다."""
    now = now or _now()
    deleted = []
    for job in list(JOBS.values()):
        if job.get("audioDeleted") or job["status"] == "RUNNING":
            continue
        due = datetime.fromisoformat(job["hardExpiresAt"])
        if job.get("softExpiresAt"):
            due = min(due, datetime.fromisoformat(job["softExpiresAt"]))
        if now >= due:
            _delete_audio(job["jobId"])
            deleted.append(job["jobId"])
        elif job["status"] == "QUEUED":
            threading.Thread(target=process, args=(job["jobId"],), daemon=True).start()
        else:
            deliver_callbacks(job["jobId"])
    return deleted


def start_sweeper(interval_seconds: int = 5):
    stop = threading.Event()
    def run() -> None:
        while not stop.wait(interval_seconds):
            try:
                sweep()
            except Exception:
                log.exception("audio sweep failed")

    thread = threading.Thread(target=run, name="analysis-audio-sweeper", daemon=True)
    thread.start()
    return stop
