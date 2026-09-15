"""BE ↔ AI 서비스 토큰. 환경변수 AI_SERVICE_TOKEN 이 비어 있으면 검사하지 않는다(개발 모드)."""

from __future__ import annotations

import os

from fastapi import Header, HTTPException


def require_service_token(authorization: str | None = Header(default=None)) -> None:
    expected = os.environ.get("AI_SERVICE_TOKEN", "").strip()
    if not expected:
        return
    if authorization != f"Bearer {expected}":
        raise HTTPException(401, "서비스 토큰이 없거나 일치하지 않습니다.")
