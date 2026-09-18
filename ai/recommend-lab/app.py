"""Separate authenticated lab. No production imports with startup side effects."""
from __future__ import annotations

import ipaddress
import os
import secrets
import threading
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse
from fastapi.security import HTTPBasic, HTTPBasicCredentials
from pydantic import BaseModel, ConfigDict, Field

from engine import Engine
from repository import Repository


class Settings(BaseModel):
    model_config = ConfigDict(extra="forbid", allow_inf_nan=False, strict=True)
    ecapaWeight: float = Field(default=.5, ge=0, le=1)
    centroidWeight: float = Field(default=.3, ge=0, le=1)
    timbreWeight: float = Field(default=.75, ge=0, le=1)
    maxKeyShift: int = Field(default=3, ge=0, le=6)
    maxCoreExcess: float = Field(default=0, ge=0, le=12)
    minRangeScore: float = Field(default=.35, ge=0, le=1)
    minProfileConfidence: float = Field(default=.55, ge=0, le=1)
    useRangeGate: bool = True
    topK: int = Field(default=10, ge=1, le=50)


class Experiment(BaseModel):
    model_config = ConfigDict(extra="forbid")
    requestId: str = Field(min_length=1, max_length=90, pattern=r"^[A-Za-z0-9_-]+$")
    settings: Settings = Field(default_factory=Settings)


def create_app(config=None, engine=None, repository=None):
    cfg = dict(os.environ) if config is None else config
    username, password = cfg.get("LAB_USERNAME", ""), cfg.get("LAB_PASSWORD", "")
    dev = cfg.get("LAB_DEV_MODE") == "1"
    if not dev and (not username or len(password) < 12):
        raise RuntimeError("LAB_USERNAME 및 12자 이상 LAB_PASSWORD가 필요합니다. 로컬만 LAB_DEV_MODE=1 허용.")
    app = FastAPI(title="MICON Recommend Lab", docs_url=None, redoc_url=None, openapi_url=None)
    repo = repository or Repository(cfg.get("LAB_ANALYSIS_DIR"), cfg.get("LAB_RECOMMEND_JOBS_DIR"), cfg.get("LAB_CASES_DIR"))
    eng_error = None
    if engine is None and cfg.get("LAB_INDEX_DIR"):
        try:
            engine = Engine(cfg["LAB_INDEX_DIR"])
        except Exception as error:
            eng_error = type(error).__name__ + ": 카탈로그 로딩 실패. 인덱스·배열·음역 파일과 hash를 서버에서 확인해 주세요."
    app.state.engine, app.state.repository = engine, repo
    semaphore = threading.BoundedSemaphore(1)
    basic = HTTPBasic(auto_error=False)
    static = Path(__file__).parent / "static"

    def auth(request: Request, credentials: HTTPBasicCredentials | None = Depends(basic)):
        if dev:
            try:
                local = ipaddress.ip_address(request.client.host).is_loopback
            except ValueError:
                local = False
            if local and request.url.hostname in {"127.0.0.1", "localhost", "::1"} and not request.headers.get("x-forwarded-for") and not request.headers.get("forwarded"):
                return "local-development"
            raise HTTPException(403, "개발 모드는 loopback에서만 접근할 수 있습니다.")
        ok_user = secrets.compare_digest((credentials.username if credentials else "").encode(), username.encode())
        ok_pass = secrets.compare_digest((credentials.password if credentials else "").encode(), password.encode())
        if not (ok_user and ok_pass):
            raise HTTPException(401, "팀 인증이 필요합니다.", headers={"WWW-Authenticate": 'Basic realm="MICON Lab"'})
        return credentials.username

    def ready():
        if engine is None:
            raise HTTPException(503, eng_error or "LAB_INDEX_DIR가 설정되지 않았습니다.")
        return engine

    @app.middleware("http")
    async def private_response(request, call_next):
        response = await call_next(request)
        response.headers["Cache-Control"] = "no-store"
        response.headers["X-Content-Type-Options"] = "nosniff"
        response.headers["Referrer-Policy"] = "no-referrer"
        response.headers["Content-Security-Policy"] = "default-src 'self'; style-src 'self'; script-src 'self'; connect-src 'self'; img-src 'self' data:; frame-ancestors 'none'; base-uri 'self'; form-action 'self'"
        return response

    @app.get("/health")
    def health():
        return {"service": "recommend-lab", "status": "ok"}

    @app.get("/", dependencies=[Depends(auth)])
    def page():
        return FileResponse(static / "index.html")

    @app.get("/static/{filename}", dependencies=[Depends(auth)])
    def asset(filename: str):
        if filename not in {"app.js", "style.css"}:
            raise HTTPException(404)
        return FileResponse(static / filename)

    @app.get("/api/status", dependencies=[Depends(auth)])
    def status():
        return {"engineReady": engine is not None, "engineError": eng_error, "devMode": dev,
                "dataSources": {"analysis": bool(repo.analysis), "recommendations": bool(repo.recommendations), "cases": bool(repo.cases)},
                "catalog": engine.info() if engine else None}

    @app.get("/api/map", dependencies=[Depends(auth)])
    def map_data():
        return {"points": ready().points, "catalog": ready().info()}

    @app.get("/api/requests", dependencies=[Depends(auth)])
    def requests():
        rows = repo.list()
        for row in rows:
            if row.get("replayAvailable") and engine:
                try:
                    case = repo.case(row["id"])
                    if case.get("catalogFingerprint") and case["catalogFingerprint"] != engine.fingerprint:
                        raise ValueError("카탈로그 fingerprint 불일치")
                    row["points"] = engine.project(case["query"])
                except (ValueError, OSError, KeyError, TypeError):
                    row["replayAvailable"] = False
                    row["replayReason"] = "입력이 만료됐거나 분석 벡터 검증에 실패했습니다. 저장 case가 필요합니다."
        return {"requests": rows, "pollSeconds": 10, "scope": "설정된 읽기 전용 데이터 소스"}

    @app.get("/api/jobs/{job_id}", dependencies=[Depends(auth)])
    def job(job_id: str):
        try:
            return repo.job(job_id)
        except KeyError:
            raise HTTPException(404, "설정된 데이터 소스에서 찾을 수 없습니다. job-id 또는 데이터 소스를 확인해 주세요.")
        except (ValueError, OSError, TypeError):
            raise HTTPException(422, "job-id 형식 또는 저장 파일을 확인해 주세요.")

    @app.post("/api/experiments", dependencies=[Depends(auth)])
    def experiment(body: Experiment):
        eng = ready()
        if not semaphore.acquire(blocking=False):
            raise HTTPException(429, "다른 실험을 계산 중입니다. 잠시 후 다시 실행해 주세요.")
        try:
            case = repo.case(body.requestId)
            result = eng.run(case, body.settings.model_dump())
            result["inputWarning"] = case.get("warning")
            return result
        except (ValueError, OSError, KeyError, TypeError) as error:
            from repository import safe_reason
            raise HTTPException(422, safe_reason(str(error)) or "재생 입력을 확인해 주세요.")
        finally:
            semaphore.release()

    return app


if __name__ == "__main__":
    import uvicorn
    # Explicit runner prevents accidental public development binding.
    uvicorn.run(create_app(), host="127.0.0.1" if os.environ.get("LAB_DEV_MODE") == "1" else os.environ.get("LAB_HOST", "127.0.0.1"),
                port=int(os.environ.get("LAB_PORT", "8790")), proxy_headers=False)
