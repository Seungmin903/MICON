"""Durable, resumable PCM upload before the ordinary voice-analysis job is committed.

The 2-second transport chunks are not model windows. Final analysis still uses the
same WAV, MR decision, 4-second/2-second-hop selection and callback contract.
"""

from __future__ import annotations

from datetime import datetime, timedelta, timezone
import hashlib
import io
import json
import os
from pathlib import Path
import shutil
import threading
import uuid
import wave

from fastapi import APIRouter, BackgroundTasks, HTTPException, Request
import numpy as np

import analysis_jobs
from timbre_recommender.embedding import load_vocal_windows


router = APIRouter(tags=["voice-analysis-streams"])
_lock = threading.RLock()
_precompute_lock = threading.Lock()
_MAX_SECONDS = 120
_MAX_CHUNK_SECONDS = 2


def _root() -> Path:
    return analysis_jobs.ANALYSIS_DIR / "_streams"


def _directory(stream_id: str) -> Path:
    try:
        if str(uuid.UUID(stream_id)) != stream_id.lower():
            raise ValueError()
    except ValueError as error:
        raise HTTPException(422, "streamId UUID가 필요합니다.") from error
    return _root() / stream_id


def _read(stream_id: str, session_id: int) -> tuple[Path, dict]:
    directory = _directory(stream_id)
    try:
        state = json.loads((directory / "state.json").read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise HTTPException(404, "업로드를 찾을 수 없습니다.") from error
    if state["sessionId"] != session_id:
        raise HTTPException(404, "업로드를 찾을 수 없습니다.")
    return directory, state


def _save(directory: Path, state: dict) -> None:
    temporary = directory / "state.tmp"
    with temporary.open("w", encoding="utf-8") as output:
        json.dump(state, output, separators=(",", ":"))
        output.flush()
        os.fsync(output.fileno())
    analysis_jobs._replace_with_retry(temporary, directory / "state.json")


def _summary(state: dict) -> dict:
    return {"streamId": state["streamId"], "sessionId": state["sessionId"],
            "sampleRate": state["sampleRate"], "nextIndex": len(state["chunks"]),
            "receivedBytes": state["receivedBytes"], "completed": state.get("completed", False)}


def _wav_bytes(pcm: bytes, sample_rate: int) -> bytes:
    output = io.BytesIO()
    with wave.open(output, "wb") as wav:
        wav.setnchannels(1)
        wav.setsampwidth(2)
        wav.setframerate(sample_rate)
        wav.writeframes(pcm)
    return output.getvalue()


def load_precomputed(stream_id: str, runtime) -> dict[str, tuple[np.ndarray, np.ndarray]]:
    """Only reuse vectors computed with the exact currently loaded checkpoints."""
    if not stream_id:
        return {}
    try:
        path = _directory(stream_id) / "precomputed.npz"
        with np.load(path, allow_pickle=False) as cached:
            if (str(cached["ecapaModel"]) != runtime.ecapa_checkpoint_sha256
                    or str(cached["singerModel"]) != runtime.singer_checkpoint_sha256
                    or str(cached["serverBuildSha"]) != analysis_jobs.build_sha()):
                return {}
            return {str(digest): (cached["ecapa"][i].copy(), cached["byol"][i].copy())
                    for i, digest in enumerate(cached["hashes"])}
    except (OSError, ValueError, KeyError):
        return {}


def _precompute(stream_id: str, session_id: int) -> None:
    """Speculatively embed clean windows while the user is still singing.

    Final full-recording MR detection is authoritative. Suspected-MR takes the
    existing separation path, so these raw vectors are never used for it.
    """
    if not _precompute_lock.acquire(blocking=False):
        return
    try:
        with _lock:
            directory, state = _read(stream_id, session_id)
            if state["completed"] or state["receivedBytes"] < state["sampleRate"] * 2 * 10:
                return
            pcm = (directory / "audio.pcm").read_bytes()[:state["receivedBytes"]]
            rate = state["sampleRate"]
        snapshot = directory / "precompute.wav"
        snapshot.write_bytes(_wav_bytes(pcm, rate))
        windows, _ = load_vocal_windows(snapshot, max_segments=64, min_segments=1)
        config, missing = analysis_jobs._configuration_status()
        missing = [name for name in missing if "카탈로그" not in name]
        if missing:
            return
        runtime = analysis_jobs.get_timbre_runtime(config)
        cached = load_precomputed(stream_id, runtime)
        hashes = [hashlib.sha256(window.tobytes()).hexdigest() for window in windows]
        fresh = [index for index, digest in enumerate(hashes) if digest not in cached]
        if not fresh:
            return
        with analysis_jobs._slot():
            ecapa, byol = runtime.embed_windows(windows[fresh])
        for row, index in enumerate(fresh):
            cached[hashes[index]] = (ecapa[row], byol[row])
        temporary = directory / "precomputed.tmp"
        with temporary.open("wb") as output:
            np.savez(output, hashes=np.array(list(cached), dtype="U64"),
                     ecapa=np.stack([item[0] for item in cached.values()]),
                     byol=np.stack([item[1] for item in cached.values()]),
                     ecapaModel=runtime.ecapa_checkpoint_sha256,
                     singerModel=runtime.singer_checkpoint_sha256,
                     serverBuildSha=analysis_jobs.build_sha())
        analysis_jobs._replace_with_retry(temporary, directory / "precomputed.npz")
        analysis_jobs.log.info("event=voice_stream_precomputed stream_id=%s new_windows=%s cached_windows=%s",
                               stream_id, len(fresh), len(cached))
    except Exception:
        analysis_jobs.log.exception("stream precompute failed for %s", stream_id)
    finally:
        _precompute_lock.release()


@router.post("/voice-analysis-streams/{stream_id}")
async def begin(stream_id: str, request: Request) -> dict:
    body = await request.json()
    session_id = body.get("sessionId")
    rate = body.get("sampleRate")
    if type(session_id) is not int or session_id == 0 or rate not in (44100, 48000):
        raise HTTPException(422, "유효한 sessionId와 44.1/48kHz sampleRate가 필요합니다.")
    directory = _directory(stream_id)
    with _lock:
        if (directory / "state.json").exists():
            _, state = _read(stream_id, session_id)
            if state["sampleRate"] != rate:
                raise HTTPException(409, "같은 streamId의 sampleRate를 변경할 수 없습니다.")
            return _summary(state)
        if directory.exists():
            shutil.rmtree(directory)  # An interrupted begin left no committed state.
        directory.mkdir(parents=True, exist_ok=False)
        state = {"streamId": stream_id, "sessionId": session_id, "sampleRate": rate,
                 "chunks": [], "receivedBytes": 0, "completed": False,
                 "createdAt": datetime.now(timezone.utc).isoformat()}
        try:
            (directory / "audio.pcm").touch()
            _save(directory, state)
        except Exception:
            shutil.rmtree(directory)
            raise
        return _summary(state)


@router.get("/voice-analysis-streams/{stream_id}")
def status(stream_id: str, sessionId: int) -> dict:
    with _lock:
        _, state = _read(stream_id, sessionId)
        return _summary(state)


@router.put("/voice-analysis-streams/{stream_id}/chunks/{index}")
async def put_chunk(stream_id: str, index: int, sessionId: int, request: Request,
                    background_tasks: BackgroundTasks) -> dict:
    if index < 0:
        raise HTTPException(422, "음수가 아닌 청크 순번이 필요합니다.")
    data = await request.body()
    with _lock:
        directory, state = _read(stream_id, sessionId)
        expected_size = state["sampleRate"] * 2 * _MAX_CHUNK_SECONDS
        if not data or len(data) % 2 or len(data) > expected_size:
            raise HTTPException(422, "2초 이하의 PCM16 모노 청크가 필요합니다.")
        digest = hashlib.sha256(data).hexdigest()
        chunks = state["chunks"]
        if index < len(chunks):
            if chunks[index] != {"bytes": len(data), "sha256": digest}:
                raise HTTPException(409, "같은 순번에 다른 청크를 보낼 수 없습니다.")
            return _summary(state)
        if state["completed"] or index != len(chunks):
            raise HTTPException(409, "누락된 이전 청크가 있거나 이미 완료된 업로드입니다.")
        if chunks and chunks[-1]["bytes"] != expected_size:
            raise HTTPException(409, "마지막 짧은 청크 뒤에는 추가할 수 없습니다.")
        if state["receivedBytes"] + len(data) > state["sampleRate"] * 2 * _MAX_SECONDS:
            raise HTTPException(413, "최대 녹음 길이를 초과했습니다.")
        with (directory / "audio.pcm").open("r+b") as output:
            output.seek(state["receivedBytes"])
            output.truncate()
            output.write(data)
            output.flush()
            os.fsync(output.fileno())
        chunks.append({"bytes": len(data), "sha256": digest})
        state["receivedBytes"] += len(data)
        _save(directory, state)
        response = _summary(state)
        if len(chunks) >= 5 and (len(chunks) - 5) % 2 == 0:
            background_tasks.add_task(_precompute, stream_id, sessionId)
        return response


@router.post("/voice-analysis-streams/{stream_id}/inspect")
async def inspect(stream_id: str, request: Request) -> dict:
    body = await request.json()
    with _lock:
        directory, state = _read(stream_id, body.get("sessionId"))
        pcm = (directory / "audio.pcm").read_bytes()[:state["receivedBytes"]]
        digest = hashlib.sha256(pcm).hexdigest()
        if len(pcm) != state["receivedBytes"] or digest != body.get("pcmSha256"):
            raise HTTPException(409, "녹음이 누락되었거나 전체 해시가 다릅니다.")
        return {**_summary(state), "pcmSha256": digest,
                "sampleCount": len(pcm) // 2, "durationMs": round(len(pcm) * 500 / state["sampleRate"])}


@router.post("/voice-analysis-streams/{stream_id}/complete", status_code=202)
async def complete(stream_id: str, request: Request, background_tasks: BackgroundTasks) -> dict:
    body = await request.json()
    meta = body.get("meta")
    if not isinstance(meta, dict) or meta.get("requestId") != stream_id:
        raise HTTPException(422, "streamId와 일치하는 작업 메타데이터가 필요합니다.")
    with _lock:
        directory, state = _read(stream_id, meta.get("sessionId"))
        pcm = (directory / "audio.pcm").read_bytes()[:state["receivedBytes"]]
        if len(pcm) != state["receivedBytes"] or hashlib.sha256(pcm).hexdigest() != body.get("pcmSha256"):
            raise HTTPException(409, "녹음이 누락되었거나 전체 해시가 다릅니다.")
        if not pcm or meta.get("recording", {}).get("sampleRate") != state["sampleRate"]:
            raise HTTPException(422, "녹음 sampleRate가 일치하지 않습니다.")
        meta = {**meta, "streamId": stream_id}
        response, should_run = analysis_jobs.ingest({"file": _wav_bytes(pcm, state["sampleRate"])}, meta)
        state["completed"] = True
        _save(directory, state)
    if should_run:
        background_tasks.add_task(analysis_jobs.process, response["jobId"])
    return response


def purge_stale(now: datetime | None = None) -> int:
    """Remove abandoned drafts; committed jobs have their own audio TTL."""
    now = now or datetime.now(timezone.utc)
    removed = 0
    with _lock:
        for directory in _root().glob("*"):
            if not directory.is_dir():
                continue
            try:
                state = json.loads((directory / "state.json").read_text(encoding="utf-8"))
                created = datetime.fromisoformat(state["createdAt"])
            except (OSError, ValueError, KeyError):
                continue
            if created + timedelta(hours=1) < now:
                shutil.rmtree(directory)
                removed += 1
    return removed
