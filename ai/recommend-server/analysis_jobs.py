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
from typing import Any
import urllib.error
import urllib.request
import uuid

from fastapi import APIRouter, BackgroundTasks, HTTPException, Request
import numpy as np
import soundfile as sf

from recommendation_api import _configuration_status, _replace_with_retry
from timbre_recommender.embedding import embed_vocal_audio


log = logging.getLogger("analysis_jobs")
ROOT = Path(__file__).resolve().parent
ANALYSIS_DIR = ROOT / "workspace" / "analysis"
MAX_BYTES = 64 * 1024 * 1024
MAX_DURATION_SECONDS = 120.0
TIMBRE_PARTS = ("timbreFile", "file")        # 음색 임베딩에 쓰는 파트. 앞의 것이 우선
COMPONENT_MODELS = {"ECAPA": ("ecapa", 192), "SINGER_BYOL": ("singer_byol", 1000)}
TERMINAL = {"COMPLETED", "FAILED", "INSUFFICIENT_DATA", "CANCELED"}

LOCK = threading.Lock()
JOBS: dict[str, dict[str, Any]] = {}          # jobId → job
REQUESTS: dict[str, str] = {}                 # requestId → jobId
_slots: threading.BoundedSemaphore | None = None
router = APIRouter(tags=["voice-analysis-jobs"])


def _env_int(name: str, default: int) -> int:
    try:
        return max(1, int(os.environ.get(name, "") or default))
    except ValueError:
        return default


def _now() -> datetime:
    return datetime.now().replace(microsecond=0)


def _iso(value: datetime) -> str:
    return value.isoformat(timespec="seconds")      # BE 는 LocalDateTime: 오프셋 없는 ISO


def _components() -> list[str]:
    raw = os.environ.get("RECOMMEND_CALLBACK_COMPONENTS", "ECAPA,SINGER_BYOL")
    return [c.strip() for c in raw.split(",") if c.strip() in COMPONENT_MODELS]


def _slot() -> threading.BoundedSemaphore:
    global _slots
    if _slots is None:
        _slots = threading.BoundedSemaphore(_env_int("RECOMMEND_CONCURRENCY", 2))
    return _slots


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
    """서버 재시작 뒤 job.json 을 다시 읽는다. 계산 중이던 작업은 실패로 남긴다."""
    if not ANALYSIS_DIR.is_dir():
        return
    for path in ANALYSIS_DIR.glob("*/job.json"):
        try:
            job = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            continue
        if job.get("status") in {"QUEUED", "RUNNING"}:
            job.update(status="FAILED", failureCode="AI_RESTARTED", failureReason="AI 서버가 재시작되어 분석이 중단되었습니다.")
        JOBS[job["jobId"]] = job
        REQUESTS[job["requestId"]] = job["jobId"]


# ── 접수 ─────────────────────────────────────────────────────────────────────

def _audio_info(path: Path) -> dict[str, Any]:
    try:
        info = sf.info(path)
    except Exception as error:
        raise HTTPException(415, f"디코딩할 수 없는 오디오입니다: {str(error)[:200]}") from error
    if info.duration > MAX_DURATION_SECONDS + 0.05:
        raise HTTPException(422, "녹음은 2분 이하여야 합니다.")
    return {"durationSeconds": round(float(info.duration), 3), "sampleRate": int(info.samplerate), "channels": int(info.channels)}


def _audio_alive(job: dict[str, Any]) -> bool:
    return not job.get("audioDeleted") and any((ANALYSIS_DIR / job["jobId"] / f["stored"]).is_file() for f in job["files"].values())


def _duplicate_response(job: dict[str, Any]) -> dict[str, Any]:
    # CALLBACK_PENDING 은 내부 상태다. BE 에는 계산 결과를 알린다.
    status = job.get("componentStatus") if job["status"] == "CALLBACK_PENDING" else job["status"]
    return {"jobId": job["jobId"], "status": status or job["status"],
            "audioExpiresAt": job["audioExpiresAt"], "duplicate": True}


def ingest(files: dict[str, bytes], meta: dict[str, Any]) -> tuple[dict[str, Any], bool]:
    """파일과 meta 를 받아 작업을 만든다. (응답 dict, 새 작업이면 True). 멱등 규칙은 여기 한 곳."""
    request_id = str(meta.get("requestId") or "").strip()
    if not request_id:
        raise HTTPException(422, "meta.requestId 가 필요합니다.")
    if not any(part in files for part in TIMBRE_PARTS):
        raise HTTPException(422, f"음색 녹음 파트가 필요합니다: {' 또는 '.join(TIMBRE_PARTS)}")
    digest = hashlib.sha256()
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
        if existing["status"] == "CALLBACK_PENDING":     # 계산은 끝났고 BE 가 못 받았다. 다시 보내기만 한다
            resend_pending_callbacks()
            return _duplicate_response(JOBS[existing["jobId"]]), False
        if not _audio_alive(existing):
            raise HTTPException(410, {"code": "AUDIO_EXPIRED", "message": "원음이 만료되어 다시 녹음해야 합니다."})
        if existing["status"] in {"FAILED", "INSUFFICIENT_DATA"}:
            _update(existing["jobId"], status="QUEUED", failureCode=None, failureReason=None)   # 실패 컴포넌트 재시도
            return _duplicate_response(JOBS[existing["jobId"]]), True
        return _duplicate_response(existing), False

    job_id = f"job_{uuid.uuid4().hex}"
    job_dir = ANALYSIS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=False)
    stored: dict[str, dict[str, Any]] = {}
    for name, data in files.items():
        if len(data) > MAX_BYTES:
            raise HTTPException(413, f"{name} 파트는 64MB 이하만 받습니다.")
        path = job_dir / f"{re.sub(r'[^A-Za-z0-9_-]', '_', name)}.wav"
        path.write_bytes(data)
        stored[name] = {"stored": path.name, "bytes": len(data), **_audio_info(path)}
    created = _now()
    hard = created + timedelta(seconds=_env_int("RECOMMEND_AUDIO_HARD_TTL_SECONDS", 86400))
    job = {
        "jobId": job_id, "requestId": request_id, "analysisId": meta.get("analysisId"), "meta": meta,
        "files": stored, "audioSha256": audio_sha, "status": "QUEUED",
        "components": {c: {"status": "PENDING", "accepted": False} for c in _components()},
        "createdAt": _iso(created), "updatedAt": _iso(created),
        "audioExpiresAt": _iso(hard), "hardExpiresAt": _iso(hard), "softExpiresAt": None, "audioDeleted": False,
        "failureCode": None, "failureReason": None,
    }
    with LOCK:
        JOBS[job_id] = job
        REQUESTS[request_id] = job_id
        _save(job)
    return {"jobId": job_id, "status": "QUEUED", "audioExpiresAt": job["audioExpiresAt"], "duplicate": False}, True


@router.post("/voice-analysis-jobs", status_code=202)
async def create_job(request: Request, background_tasks: BackgroundTasks) -> dict[str, Any]:
    _, missing = _configuration_status()
    if missing:
        raise HTTPException(503, f"추천 AI 설정이 필요합니다: {', '.join(missing)}")
    form = await request.form()
    files: dict[str, bytes] = {}
    meta: dict[str, Any] | None = None
    for name, value in form.multi_items():
        if name == "meta":
            raw = value if isinstance(value, str) else (await value.read()).decode("utf-8")
            try:
                meta = json.loads(raw)
            except json.JSONDecodeError as error:
                raise HTTPException(422, "meta 는 JSON 이어야 합니다.") from error
        elif hasattr(value, "read"):
            files[name] = await value.read()
    if not isinstance(meta, dict):
        raise HTTPException(422, "meta 파트가 필요합니다.")
    response, should_run = ingest(files, meta)
    if should_run:
        background_tasks.add_task(process, response["jobId"])
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


@router.get("/voice-analysis-jobs/{job_id}")
def get_job(job_id: str) -> dict[str, Any]:
    return _get(job_id)


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


def build_callbacks(job: dict[str, Any], ecapa: np.ndarray, byol: np.ndarray, metadata: dict[str, Any]) -> dict[str, dict[str, Any]]:
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
        rows /= np.maximum(np.linalg.norm(rows, axis=1, keepdims=True), 1e-12)
        mean = rows.mean(axis=0)
        mean /= max(float(np.linalg.norm(mean)), 1e-12)
        out[component] = {
            "requestId": job["requestId"], "jobId": job["jobId"], "component": component, "attemptNo": attempt,
            "status": "COMPLETED", "modelKey": component,
            "modelVersion": metadata["models"][key]["checkpoint_sha256"], "dimension": dimension,
            "embedding": [round(float(v), 7) for v in mean],
            "segmentInfo": {
                "segmentDurationSeconds": metadata["window_seconds"],
                "segmentCount": len(segments),
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


def post_callback(payload: dict[str, Any]) -> dict[str, Any]:
    """콜백 1건. 5xx·연결 오류·타임아웃은 3회 재시도, 4xx 는 즉시 중단. 결과 {http, attempts, accepted}."""
    url = os.environ.get("MICON_BE_CALLBACK_URL", "").strip()
    if not url:
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
            if error.code < 500:
                last["error"] = error.read(500).decode("utf-8", "replace")
                return last
        except (urllib.error.URLError, TimeoutError, OSError) as error:
            last["error"] = str(error)[:200]
        time.sleep(2 ** (attempt - 1))
    return last


def _timbre_part(job: dict[str, Any]) -> str:
    return next(part for part in TIMBRE_PARTS if part in job["files"])


def process(job_id: str) -> None:
    job = JOBS[job_id]
    if job["status"] == "CANCELED":
        return
    config, _ = _configuration_status()
    job_dir = ANALYSIS_DIR / job_id
    audio = job_dir / job["files"][_timbre_part(job)]["stored"]
    prefix = job_dir / "query"
    pending = [c for c, state in job["components"].items() if not state["accepted"]]
    callbacks: dict[str, dict[str, Any]] = {}
    try:
        with _slot():
            _update(job_id, status="RUNNING")
            _limit_torch_threads()
            metadata = embed_vocal_audio(
                audio, prefix, ecapa_checkpoint=config["ecapa_checkpoint"], singer_repo=config["singer_repo"],
                singer_checkpoint_root=config["singer_checkpoint_root"], device=config.get("device", "auto"),
                batch_size=8, max_segments=12, min_segments=5, force=True,
            )
        built = build_callbacks(job, np.load(f"{prefix}.ecapa.npy", allow_pickle=False), np.load(f"{prefix}.singer_byol.npy", allow_pickle=False), metadata)
        callbacks = {c: built[c] for c in pending}
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
        result = post_callback(payload)
        components[component] = {"status": payload["status"], "accepted": result["accepted"], "callback": result,
                                 "failureCode": payload["failureCode"], "processedAt": payload["processedAt"]}
        (job_dir / f"callback.{component}.json").write_text(json.dumps(payload, ensure_ascii=False), encoding="utf-8")
    _finish(job_id, components)


def _finish(job_id: str, components: dict[str, Any]) -> None:
    """작업 상태를 정한다. BE 가 받아 주지 않은 컴포넌트가 있으면 완료로 두지 않는다 — 그래야 다시 보낼 수 있다."""
    job = JOBS[job_id]
    statuses = {c["status"] for c in components.values()}
    status = "COMPLETED" if statuses == {"COMPLETED"} else ("INSUFFICIENT_DATA" if "INSUFFICIENT_DATA" in statuses else "FAILED")
    accepted = all(c["accepted"] for c in components.values())
    failed = next((c for c in components.values() if c["status"] != "COMPLETED"), None)
    values: dict[str, Any] = {"status": status if accepted else "CALLBACK_PENDING", "components": components,
                              "componentStatus": status,
                              "failureCode": failed and failed.get("failureCode"), "failureReason": None}
    if accepted:
        soft = _now() + timedelta(seconds=_env_int("RECOMMEND_AUDIO_TTL_SECONDS", 3600))
        values["softExpiresAt"] = _iso(soft)
        values["audioExpiresAt"] = _iso(min(soft, datetime.fromisoformat(job["hardExpiresAt"])))
    _update(job_id, **values)


def resend_pending_callbacks() -> list[str]:
    """BE 가 살아난 뒤 밀린 콜백을 보낸다. 계산은 끝났고 본문도 남아 있으므로 다시 계산하지 않는다."""
    resent = []
    for job in [j for j in list(JOBS.values()) if j["status"] == "CALLBACK_PENDING"]:
        components = dict(job["components"])
        changed = False
        for component, state in components.items():
            if state.get("accepted"):
                continue
            path = ANALYSIS_DIR / job["jobId"] / f"callback.{component}.json"
            if not path.is_file():
                continue
            result = post_callback(json.loads(path.read_text(encoding="utf-8")))
            components[component] = {**state, "accepted": result["accepted"], "callback": result}
            changed = True
        if changed:
            _finish(job["jobId"], components)
            resent.append(job["jobId"])
    return resent


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
    return deleted


def start_sweeper(interval_seconds: int = 60) -> threading.Thread:
    def run() -> None:
        while True:
            time.sleep(interval_seconds)
            for step in (resend_pending_callbacks, sweep):
                try:
                    step()
                except Exception:
                    log.exception("%s failed", step.__name__)

    thread = threading.Thread(target=run, name="analysis-audio-sweeper", daemon=True)
    thread.start()
    return thread
