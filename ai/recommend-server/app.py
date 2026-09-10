"""MICON 추천 서버 진입점. VocalScope 엔진(8787)과 별도 프로세스, 기본 포트 8788.

호출 주체는 BE(Spring) 이고, 개발 중에는 Android 앱이 10.0.2.2:8788 로 직접 붙는다.
라우트는 recommendation_api.router 에 있다.
"""

from __future__ import annotations

from fastapi import FastAPI

from recommendation_api import router


app = FastAPI(title="MICON Recommend Server", version="0.1.0")
app.include_router(router)


@app.get("/health")
def health() -> dict[str, object]:
    return {"ok": True, "service": "micon-recommend-server", "version": app.version}
