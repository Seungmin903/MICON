"""Loopback-only test server: real range model inference without the account database.

Run on 127.0.0.1:8789 and connect the test phone through adb reverse.
No Spring backend, account database, or public deployment is used by this entry point.
"""
import json
import os
import tempfile
from pathlib import Path
from threading import BoundedSemaphore
from uuid import UUID

from fastapi import FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.responses import JSONResponse
from pydantic import ValidationError

from guided_range import analyze
from range_models import Submission
from range_upload import RangeUploadRoute

app = FastAPI(title="MICON local range model preview", version="1")
app.router.route_class = RangeUploadRoute
STORAGE = Path(os.environ.get("RANGE_PREVIEW_STORAGE", str(Path(__file__).resolve().parent / ".range-preview"))).resolve()
worker = BoundedSemaphore(1)


@app.middleware("http")
async def loopback_only(request: Request, call_next):
    if request.client is None or request.client.host not in ("127.0.0.1", "::1"):
        return JSONResponse({"detail": "Local test connection required"}, status_code=403)
    return await call_next(request)


@app.get("/health")
def health():
    return {"status": "ok", "mode": "model_preview", "models": ["CREPE full", "pYIN"], "protocolVersion": "guided-range-v7", "supportedProtocolVersions": ["guided-range-v1", "guided-range-v2", "guided-range-v3", "guided-range-v4", "guided-range-v5", "guided-range-v6", "guided-range-v7"]}


def report_path(identifier: str) -> Path:
    return STORAGE / f"{UUID(identifier)}.json"


@app.post("/preview/guided-range")
def guided_range(audio: UploadFile = File(...), metadata: str = Form(...)):
    if len(metadata) > 8_000_000:
        raise HTTPException(413, "Measurement metadata too large")
    try:
        submission = Submission.model_validate_json(metadata)
        if submission.protocolVersion not in ('guided-range-v5', 'guided-range-v6', 'guided-range-v7') and len(metadata) > 200_000:
            raise HTTPException(413, 'Measurement metadata too large')
        destination = report_path(submission.sessionId)
        parent_path = report_path(submission.parentAnalysisId) if submission.mode == "extension" else None
    except (ValidationError, ValueError, TypeError) as e:
        raise HTTPException(422, "Invalid measurement metadata") from e
    if not worker.acquire(blocking=False):
        raise HTTPException(503, "Measurement model is busy; retry shortly")
    try:
        if destination.is_file():
            return json.loads(destination.read_text(encoding="utf-8"))
        previous = None
        if parent_path is not None:
            if not parent_path.is_file():
                raise HTTPException(404, "Previous model analysis is unavailable; start a new measurement")
            previous = json.loads(parent_path.read_text(encoding="utf-8"))
            if previous.get("verification") != "model_verified" or previous.get("availability") != "available":
                raise HTTPException(422, "Extension requires a completed model analysis")
        STORAGE.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="recording-", dir=STORAGE) as directory:
            path = Path(directory) / "recording.wav"
            count = 0
            with path.open("wb") as output:
                while chunk := audio.file.read(65536):
                    count += len(chunk)
                    if submission.protocolVersion == "guided-range-v1" and count > 10_000_000:
                        raise HTTPException(413, "Recording too large")
                    output.write(chunk)
            try:
                report = analyze(path, submission, previous)
            except ValueError as e:
                raise HTTPException(422, str(e)) from e
        report.update(verification="model_verified", analysisId=submission.sessionId)
        temporary = destination.with_suffix(".json.new")
        temporary.write_text(json.dumps(report, ensure_ascii=False, allow_nan=False), encoding="utf-8")
        os.replace(temporary, destination)
        return report
    finally:
        worker.release()
