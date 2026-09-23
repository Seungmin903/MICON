"""카탈로그 track_id ↔ 앱 song_id 짝표. BE 활성 임베딩 카탈로그에서 받아 온다.

추천 응답의 song_id 는 앱 SONG.song_id(불투명 문자열)여야 한다. 숫자로 변환하지 않는다.
MICON_BE_BASE_URL 은 BE 컨테이너 주소여야 한다. 공개 nginx 는 /internal/ 을 404 로 막는다.
비어 있으면 짝표 없이 동작한다(개발 모드).
"""

from __future__ import annotations

import json
import logging
import os
import threading
import time
import urllib.error
import urllib.request
from typing import Any


log = logging.getLogger("catalog_link")
PATH = "/internal/embedding-catalog/active"   # 스프링 컨트롤러 경로 그대로. /api/v1 은 nginx 가 붙이는 접두사다
_LOCK = threading.Lock()
_REFRESH_LOCK = threading.Lock()
_LAST_ATTEMPT_MONOTONIC: float | None = None
_CACHE: dict[str, Any] = {"revision": None, "batchId": None, "songIds": {}, "error": None}


def _song_id(value: Any) -> str:
    # Accept a legacy numeric backend during a rolling deployment, but emit strings.
    if type(value) is int and value > 0:
        return str(value)
    if (isinstance(value, str) and value.isascii() and 0 < len(value) <= 64
            and all(c.isalnum() or c in "-_" for c in value)):
        return value
    raise ValueError("Invalid catalog song ID")


def _get(url: str, token: str) -> dict[str, Any]:
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=10) as response:
        return json.loads(response.read().decode("utf-8"))


def _refresh(attempted_at: float | None = None) -> dict[str, Any]:
    global _LAST_ATTEMPT_MONOTONIC
    _LAST_ATTEMPT_MONOTONIC = time.monotonic() if attempted_at is None else attempted_at
    base = os.environ.get("MICON_BE_BASE_URL", "").strip().rstrip("/")
    if not base:
        with _LOCK:
            # Keep the last verified mapping available while the backend is absent.
            _CACHE["error"] = "MICON_BE_BASE_URL 미설정"
            return dict(_CACHE)
    try:
        body = _get(base + PATH, os.environ.get("AI_SERVICE_TOKEN", "").strip())
        data = body.get("data") or body
        mapping = {
            str(track["catalog_track_id"]): _song_id(track["song_id"])
            for track in (data.get("tracks") or [])
            if track.get("catalog_track_id") is not None and track.get("song_id") is not None
        }
        for song_id in tuple(mapping.values()):
            key = str(song_id)
            if key in mapping and mapping[key] != song_id:
                raise ValueError("Ambiguous catalog track ID and DB song ID")
            mapping[key] = song_id
        with _LOCK:
            _CACHE.update(revision=data.get("revision"), batchId=data.get("batch_id"), songIds=mapping, error=None)
    except (urllib.error.URLError, urllib.error.HTTPError, TimeoutError, OSError, ValueError, KeyError) as error:
        log.warning("활성 카탈로그를 받지 못했습니다: %s", error)
        with _LOCK:
            _CACHE["error"] = str(error)[:200]
    return status()


def refresh() -> dict[str, Any]:
    """BE 에서 활성 카탈로그를 다시 받아 짝표를 갱신한다. 실패해도 예외를 올리지 않고 error 에 남긴다."""
    with _REFRESH_LOCK:
        return _refresh()


def _refresh_in_background(attempted_at: float) -> None:
    try:
        _refresh(attempted_at)
    finally:
        _REFRESH_LOCK.release()


def ensure(min_interval_seconds: float = 30.0) -> dict[str, Any]:
    """Return the last mapping immediately and refresh it in a daemon thread if stale."""
    global _LAST_ATTEMPT_MONOTONIC
    interval = max(0.0, min_interval_seconds)
    now = time.monotonic()
    with _LOCK:
        last_attempt = _LAST_ATTEMPT_MONOTONIC
    if last_attempt is not None and now - last_attempt < interval:
        return status()
    if not _REFRESH_LOCK.acquire(blocking=False):
        return status()

    attempted_at = now
    with _LOCK:
        last_attempt = _LAST_ATTEMPT_MONOTONIC
        if last_attempt is not None and attempted_at - last_attempt < interval:
            _REFRESH_LOCK.release()
            return status()
        _LAST_ATTEMPT_MONOTONIC = attempted_at
    try:
        threading.Thread(
            target=_refresh_in_background,
            args=(attempted_at,),
            name="catalog-link-refresh",
            daemon=True,
        ).start()
    except RuntimeError as error:
        _REFRESH_LOCK.release()
        with _LOCK:
            _CACHE["error"] = str(error)[:200]
    return status()


def status() -> dict[str, Any]:
    with _LOCK:
        return {"revision": _CACHE["revision"], "batchId": _CACHE["batchId"],
                "mappedTracks": len(set(_CACHE["songIds"].values())), "error": _CACHE["error"]}


def song_ids() -> dict[str, str]:
    with _LOCK:
        return dict(_CACHE["songIds"])


def apply(result: dict[str, Any]) -> dict[str, Any]:
    """추천 결과의 song_id 를 앱 문자열 ID 로 바꾼다. 짝표에 없는 곡은 앱이 열 수 없으므로 뺀다.

    짝표가 비어 있으면(BE 미설정·조회 실패) 결과를 그대로 둔다 — 개발 중에는 문자열 song_id 로도 화면을 볼 수 있다.
    """
    mapping = song_ids()
    if not mapping:
        return result
    dropped = 0
    for shelf in result.get("shelves", []):
        kept = []
        for item in shelf.get("items", []):
            song_id = mapping.get(item.get("track_id"))
            if song_id is None:
                dropped += 1
                continue
            kept.append({**item, "song_id": song_id})
        shelf["items"] = [{**item, "position": index} for index, item in enumerate(kept, start=1)]
    first = result.get("shelves") or [{}]
    result["recommendations"] = first[0].get("items", [])
    result.setdefault("algorithm", {})["catalog_link"] = {
        "revision": status()["revision"], "mapped_tracks": len(mapping), "excluded_unregistered": dropped,
    }
    return result
