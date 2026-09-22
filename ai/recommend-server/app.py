"""MICON 추천 서버 진입점. VocalScope 엔진(8787)과 별도 프로세스, 기본 포트 8788.

호출 주체는 BE(Spring) 이고, 개발 중에는 Android 앱이 10.0.2.2:8788 로 직접 붙는다.
라우트: recommendation_api.router(추천), analysis_jobs.router(BE 음성 분석 작업 접수·콜백).
AI_SERVICE_TOKEN 이 설정되어 있으면 /health 를 뺀 모든 라우트가 Bearer 토큰을 요구한다.
"""

from __future__ import annotations

from contextlib import asynccontextmanager

from fastapi import Depends, FastAPI

import analysis_jobs
import catalog_link
from recommendation_api import router as recommendation_router
from runtime_info import build_sha
from service_auth import require_service_token
from song_fit_api import router as song_fit_router, restore_artist_jobs


@asynccontextmanager
async def lifespan(_: FastAPI):
    analysis_jobs.restore_jobs()
    restore_artist_jobs()
    catalog_link.refresh()          # 앱 song_id 짝표. 실패해도 기동은 계속된다(상태는 /recommendations/status)
    analysis_jobs.preload_timbre_runtime()
    stop = analysis_jobs.start_sweeper()
    try:
        yield
    finally:
        stop.set()


app = FastAPI(title="MICON Recommend Server", version="0.2.0", lifespan=lifespan)
app.include_router(recommendation_router, dependencies=[Depends(require_service_token)])
app.include_router(analysis_jobs.router, dependencies=[Depends(require_service_token)])
app.include_router(song_fit_router, dependencies=[Depends(require_service_token)])


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "ok": True,
        "service": "micon-recommend-server",
        "version": app.version,
        "buildSha": build_sha(),
        "timbreRuntime": analysis_jobs.timbre_runtime_status(),
    }
