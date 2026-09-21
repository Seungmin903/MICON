"""BE → 추천 AI 곡 적합도와 닮은 가수. 둘 다 저장된 숫자만 쓰므로 오디오도 모델 추론도 없다.

  POST /song-fit            동기. 곡 하나의 원키·선택 키·추천 키 적합도
  POST /artist-search-jobs  202 접수 후 콜백. 사용자 임베딩으로 닮은 가수를 고른다
"""

from __future__ import annotations

from collections.abc import Mapping
import logging
import json
from pathlib import Path
import re
import threading
import time
from typing import Any
import uuid

from fastapi import APIRouter, BackgroundTasks, HTTPException
import numpy as np

import analysis_jobs
import catalog_link
from artist_catalog import ArtistCatalog, public_response
from pitch_profile import MIDI_MAX, MIDI_MIN, PROFILE_SCHEMA_VERSION
from recommendation_api import MODEL_DIMENSIONS, WORKSPACE, _configuration_status, _replace_with_retry, user_embedding_matrices
from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.contracts import SCORING_VERSION
from timbre_recommender.pitch_range import (
    KEY_SHIFT_MAX,
    KEY_SHIFT_MIN,
    RangeCatalog,
    score_range_fit,
)
from timbre_recommender.scoring import RankerConfig, _absolute_similarity, _model_track_score


log = logging.getLogger("song_fit_api")
MAX_ARTISTS = 5          # DB 제약: rank_no BETWEEN 1 AND 5
JOBS: dict[str, dict[str, Any]] = {}
_LOCK = threading.RLock()
ARTIST_JOBS_DIR = WORKSPACE.parent / "artist-search" / "jobs"
router = APIRouter(tags=["song-fit"])


def _decimal(value: Any) -> float | None:
    try:
        return None if value is None else float(value)
    except (TypeError, ValueError):
        return None


def _flat_profile(low: float, high: float, *, kind: str) -> dict[str, Any] | None:
    """최저·최고 MIDI 만 아는 상태의 프로파일. 그 구간에 질량을 고르게 깐다."""
    low_midi = int(round(max(MIDI_MIN, min(MIDI_MAX, min(low, high)))))
    high_midi = int(round(max(MIDI_MIN, min(MIDI_MAX, max(low, high)))))
    if high_midi - low_midi < 1:
        return None
    histogram = np.zeros(MIDI_MAX - MIDI_MIN + 1, dtype=np.float64)
    histogram[low_midi - MIDI_MIN : high_midi - MIDI_MIN + 1] = 1.0
    return {"schemaVersion": PROFILE_SCHEMA_VERSION, "profileKind": kind,
            "histogram": histogram.tolist(), "coreLowMidi": low_midi,
            "medianMidi": (low_midi + high_midi) // 2, "coreHighMidi": high_midi, "reliable": True}


def _track_of(song_id: Any, catalog: CatalogIndex) -> str | None:
    known = {track.track_id for track in catalog.tracks}
    for track_id, mapped in catalog_link.song_ids().items():
        if str(mapped) == str(song_id) and track_id in known:
            return track_id
    return None


def _selected_key_offset(payload: Mapping[str, Any]) -> int:
    value = payload.get("selectedKeyOffset")
    if value is None:
        return 0
    if type(value) is not int or not KEY_SHIFT_MIN <= value <= KEY_SHIFT_MAX:
        raise HTTPException(
            422,
            f"selectedKeyOffset는 {KEY_SHIFT_MIN}부터 {KEY_SHIFT_MAX} 사이의 정수여야 합니다.",
        )
    return value


def _unavailable(
    payload: Mapping[str, Any], reason: str, selected_key_offset: int
) -> dict[str, Any]:
    return public_response({"songId": payload.get("songId"), "analysisId": payload.get("analysisId"),
            "selectedKeyOffset": selected_key_offset,
            "evaluationStatus": "UNAVAILABLE", "originalKeyFit": None, "selectedKeyFit": None,
            "recommendedKeyOffset": None, "recommendationScores": payload.get("recommendationScores"),
            "reason": None, "burdenSections": list(payload.get("burdenSections") or []),
            "unavailableReason": reason})


def _fit_at(user: Mapping[str, Any], song: Mapping[str, Any], shift: int,
            user_low: float, user_high: float) -> dict[str, Any]:
    from timbre_recommender.pitch_range import _score_at_shift

    scored = _score_at_shift(user, song, shift)
    return {"userStableLowMidi": user_low, "userStableHighMidi": user_high,
            "songLowMidi": song["coreLowMidi"] + shift, "songHighMidi": song["coreHighMidi"] + shift,
            "score": round(float(scored["score"]), 4)}


def _reason(shift: int, best: Mapping[str, Any]) -> str:
    if shift == 0:
        return "원키 그대로 부를 수 있는 음역입니다."
    high_excess = float(best.get("highCoreExcessSemitones") or 0.0)
    low_excess = float(best.get("lowCoreExcessSemitones") or 0.0)
    direction = "내려" if shift < 0 else "올려"
    if high_excess > low_excess:
        return f"최고음이 안정 음역보다 높아 {abs(shift)}키 {direction} 부르는 편이 낫습니다."
    if low_excess > 0:
        return f"최저음이 안정 음역보다 낮아 {abs(shift)}키 {direction} 부르는 편이 낫습니다."
    return f"곡 중심음이 내 중심음에 맞도록 {abs(shift)}키 {direction} 부르는 편이 낫습니다."


@router.post("/song-fit")
def song_fit(payload: dict[str, Any]) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise HTTPException(422, "요청 본문은 객체여야 합니다.")
    selected = _selected_key_offset(payload)
    user_low = _decimal(payload.get("userStableLowMidi"))
    user_high = _decimal(payload.get("userStableHighMidi"))
    if user_low is None or user_high is None:
        return _unavailable(payload, "USER_STABLE_RANGE_UNAVAILABLE", selected)
    user = _flat_profile(user_low, user_high, kind="guided_range_measurement")
    if user is None:
        return _unavailable(payload, "USER_STABLE_RANGE_UNAVAILABLE", selected)

    config, missing = _configuration_status()
    song: dict[str, Any] | None = None
    if not missing:                       # 카탈로그에 이 곡의 음역 프로파일이 있으면 그것을 쓴다
        try:
            catalog = CatalogIndex.load(config["index_dir"])
            track_id = _track_of(payload.get("songId"), catalog)
            if track_id:
                song = RangeCatalog.load(config["index_dir"]).profiles.get(track_id)
        except Exception as error:
            log.warning("곡 음역 프로파일을 읽지 못했습니다: %s", error)
    if song is None:
        song_low, song_high = _decimal(payload.get("songLowMidi")), _decimal(payload.get("songHighMidi"))
        if song_low is None or song_high is None:
            return _unavailable(payload, "SONG_RANGE_UNAVAILABLE", selected)
        song = _flat_profile(song_low, song_high, kind="song_range")
        if song is None:
            return _unavailable(payload, "SONG_RANGE_UNAVAILABLE", selected)

    best = score_range_fit(user, song)
    recommended = int(best["keyShift"])
    return public_response({
        "songId": payload.get("songId"), "analysisId": payload.get("analysisId"),
        "selectedKeyOffset": selected, "evaluationStatus": "AVAILABLE",
        "originalKeyFit": _fit_at(user, song, 0, user_low, user_high),
        "selectedKeyFit": _fit_at(user, song, selected, user_low, user_high),
        "recommendedKeyOffset": recommended,
        "recommendationScores": payload.get("recommendationScores"),
        "reason": _reason(recommended, best),
        "burdenSections": list(payload.get("burdenSections") or []),
        "unavailableReason": None,
    })


# ── 닮은 가수 ─────────────────────────────────────────────────────────────────

def rank_artists(query: dict[str, np.ndarray], catalog: CatalogIndex, top_k: int,
                 artists: ArtistCatalog | None = None,
                 allowed_track_ids: set[str] | None = None) -> list[dict[str, Any]]:
    """가수별로 그 가수의 모든 곡 구간을 모아 사용자와 견준다. 두 축 점수의 평균으로 순위를 매긴다.

    추천의 음색 점수와 같은 함수를 쓴다. 곡 하나가 아니라 가수의 구간 전체가 상대라는 점만 다르다.
    """
    artists = artists or ArtistCatalog.load(catalog)
    by_artist: dict[int, list] = {}
    for track in catalog.tracks:
        if allowed_track_ids is not None and track.track_id not in allowed_track_ids:
            continue
        by_artist.setdefault(artists.by_track[track.track_id], []).append(track)
    if not by_artist:
        raise ValueError("No local catalog track is active in the current DB catalog")
    config = RankerConfig()
    scores: list[dict[str, Any]] = []
    for artist, tracks in by_artist.items():
        axis = {
            key: _absolute_similarity(_model_track_score(
                query[key], np.vstack([catalog.embeddings(track, key) for track in tracks]),
                centroid_weight=config.centroid_weight, segment_weight=config.segment_weight,
            )["raw_score"])
            for key in MODEL_DIMENSIONS
        }
        scores.append({"artistId": artist,
                       "artistName": min(tracks, key=lambda track: track.track_id).artist,
                       "ecapaScore": round(float(axis["ecapa"]), 4),
                       "singerByolScore": round(float(axis["singer_byol"]), 4),
                       "similarity": round(float(axis["ecapa"] + axis["singer_byol"]) / 2.0, 4)})
    scores.sort(key=lambda item: (-item["similarity"], item["artistId"]))
    return [{"rank": rank, **item}
            for rank, item in enumerate(scores[: max(1, min(top_k, MAX_ARTISTS))], start=1)]


def _active_track_ids(catalog: CatalogIndex, artists: ArtistCatalog, requested_version: Any) -> set[str]:
    """DB 가 지금 활성으로 보는 곡만 남긴다. 배치 이름이 바뀌어도 곡이 그대로면 계속 답한다.

    가수 ID 는 로컬 스냅샷에서만 오고(이름으로 추정하지 않는다), 어떤 곡이 후보인지는 DB 가 정한다.
    BE 짝표를 못 받는 동안에는 예전처럼 로컬 스냅샷 배치와 정확히 같을 때만 답한다.
    """
    known = {track.track_id for track in catalog.tracks}
    linked = catalog_link.song_ids()
    if not linked:
        if requested_version != artists.catalog_version:
            raise ValueError("Requested catalogVersion does not match the local artist snapshot")
        return known
    active_batch = catalog_link.status()["batchId"]
    if requested_version not in (artists.catalog_version, active_batch):
        raise ValueError("Requested catalogVersion is neither the local snapshot nor the active DB catalog")
    active = known & set(linked)
    if len(active) != len(known):
        log.warning("DB 에서 빠진 곡 %d개를 가수 후보에서 제외합니다", len(known) - len(active))
    return active


def _artist_process(job_id: str, payload: dict[str, Any]) -> None:
    job = JOBS[job_id]
    with _LOCK:
        job.update(status="RUNNING", calculationStatus="RUNNING", deliveryStatus="NOT_READY")
        _save_artist_job(job)
    try:
        config, missing = _configuration_status()
        if missing:
            raise RuntimeError(f"추천 데모 설정 누락: {', '.join(missing)}")
        query = user_embedding_matrices(payload.get("modelResults"))
        catalog = CatalogIndex.load(config["index_dir"], verify_hashes=True)
        mapping = ArtistCatalog.load(catalog)
        catalog_link.ensure()
        allowed = _active_track_ids(catalog, mapping, payload.get("catalogVersion"))
        artists = rank_artists(query, catalog, int(payload.get("topK") or MAX_ARTISTS), mapping,
                               allowed_track_ids=allowed)
        body = {"requestId": job["requestId"], "jobId": job_id, "component": "ARTIST_SEARCH",
                "status": "COMPLETED", "modelKey": "ARTIST_SEARCH", "modelVersion": SCORING_VERSION,
                "processedAt": analysis_jobs._iso(analysis_jobs._now()), "attemptNo": job["attemptNo"],
                "resultVersion": payload.get("resultVersion"), "catalogVersion": payload.get("catalogVersion"),
                "artists": artists, "failureCode": None, "failureReason": None}
    except HTTPException as error:
        body = _artist_failure(job, "INSUFFICIENT_DATA", str(error.detail))
    except Exception as error:
        log.exception("artist search %s failed", job_id)
        body = _artist_failure(job, "AI_ANALYSIS_FAILED", str(error))
    with _LOCK:
        job.update(status=body["status"], calculationStatus=body["status"],
                   deliveryStatus="PENDING", callbackBody=body, artists=body.get("artists"),
                   failureCode=body["failureCode"], failureReason=body["failureReason"])
        _save_artist_job(job)
    _deliver_artist_job(job_id)


def _artist_failure(job: dict[str, Any], code: str, reason: str) -> dict[str, Any]:
    return {"requestId": job["requestId"], "jobId": job["jobId"], "component": "ARTIST_SEARCH",
            "status": "FAILED", "modelKey": "ARTIST_SEARCH", "modelVersion": SCORING_VERSION,
            "processedAt": analysis_jobs._iso(analysis_jobs._now()), "attemptNo": job["attemptNo"],
            "resultVersion": job.get("resultVersion"), "catalogVersion": job.get("catalogVersion"),
            "artists": None, "failureCode": code, "failureReason": reason[:1000]}


@router.post("/artist-search-jobs", status_code=202)
def create_artist_search(payload: dict[str, Any], background_tasks: BackgroundTasks) -> dict[str, Any]:
    if not isinstance(payload, Mapping):
        raise HTTPException(422, "요청 본문은 객체여야 합니다.")
    request_id = str(payload.get("requestId") or "").strip()
    if not request_id:
        raise HTTPException(422, "requestId 가 필요합니다.")
    user_embedding_matrices(payload.get("modelResults"))       # 잡을 만들기 전에 입력부터 검증한다
    with _LOCK:
        for job in JOBS.values():
            if job["requestId"] == request_id:
                return {"jobId": job["jobId"], "status": job["status"], "audioExpiresAt": None, "duplicate": True}
        job_id = f"artist-search-job-{uuid.uuid4().hex}"
        JOBS[job_id] = {"jobId": job_id, "requestId": request_id, "status": "QUEUED",
                        "attemptNo": int(payload.get("attemptNo") or 1),
                        "resultVersion": payload.get("resultVersion"),
                        "catalogVersion": payload.get("catalogVersion"),
                        "createdAt": time.time(), "artists": None, "callback": None, "failureCode": None,
                        "calculationStatus": "QUEUED", "deliveryStatus": "NOT_READY",
                        "payload": dict(payload)}
        _save_artist_job(JOBS[job_id])
    background_tasks.add_task(_artist_process, job_id, dict(payload))
    return {"jobId": job_id, "status": "QUEUED", "audioExpiresAt": None, "duplicate": False}


@router.get("/artist-search-jobs/{job_id}")
def get_artist_search(job_id: str) -> dict[str, Any]:
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "검색 작업을 찾을 수 없습니다.")
    return public_response(job)


@router.post("/artist-search-jobs/{job_id}/retry", status_code=202)
def retry_artist_search(job_id: str, background_tasks: BackgroundTasks) -> dict[str, Any]:
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "검색 작업을 찾을 수 없습니다.")
    with _LOCK:
        delivery_failed = job["status"] == "COMPLETED" and not (job.get("callback") or {}).get("accepted")
        if job["status"] not in {"FAILED", "INSUFFICIENT_DATA"} and not delivery_failed:
            raise HTTPException(409, "실패한 계산 또는 미수락 검색 결과만 재시도할 수 있습니다.")
        payload = job.get("payload")
        if not isinstance(payload, dict):
            raise HTTPException(410, "재시도할 검색 입력이 만료됐습니다.")
        if job.get("delivering"):
            raise HTTPException(409, "콜백 전달 중에는 재계산할 수 없습니다.")
        job.update(status="QUEUED", calculationStatus="QUEUED", deliveryStatus="NOT_READY",
                   artists=None, callback=None, callbackBody=None, failureCode=None,
                   failureReason=None, deliveryFailureCode=None)
        _save_artist_job(job)
    background_tasks.add_task(_artist_process, job_id, dict(payload))
    return {"jobId": job_id, "status": "QUEUED", "audioExpiresAt": None, "duplicate": True}


def _save_artist_job(job: dict[str, Any]) -> None:
    directory = ARTIST_JOBS_DIR / job["jobId"]
    directory.mkdir(parents=True, exist_ok=True)
    temporary = directory / ".job.json.tmp"
    temporary.write_text(json.dumps(job, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    _replace_with_retry(temporary, directory / "job.json")


def restore_artist_jobs() -> None:
    """Restore computation/delivery state without replaying historical callbacks.

    Recovery is explicit: stale attempts must not silently overwrite newer BE
    results, and a startup must never initiate database writes on its own.
    """
    if not ARTIST_JOBS_DIR.is_dir():
        return
    with _LOCK:
        for path in ARTIST_JOBS_DIR.glob("*/job.json"):
            try:
                job = json.loads(path.read_text(encoding="utf-8"))
                jid = job["jobId"]
                if not re.fullmatch(r"artist-search-job-[0-9a-f]{32}", jid) or path.parent.name != jid:
                    continue
                if job["status"] in {"QUEUED", "RUNNING"}:
                    job.update(status="FAILED", failureCode="AI_ENGINE_RESTARTED",
                               failureReason="검색 계산 중 AI 엔진이 재시작됐습니다.")
                job["calculationStatus"] = job["status"]
                job["delivering"] = False
                callback = job.get("callback") or {}
                rejected = isinstance(callback.get("http"), int) and 400 <= callback["http"] < 500
                job["deliveryStatus"] = ("ACCEPTED" if callback.get("accepted")
                    else "REJECTED" if rejected else "PENDING_RETRY" if callback
                    else "PENDING" if job.get("callbackBody") else "NOT_READY")
                job["deliveryFailureCode"] = (None if callback.get("accepted")
                    else "CALLBACK_REJECTED" if rejected else "CALLBACK_UNAVAILABLE" if callback else None)
                JOBS.setdefault(jid, job)
            except (OSError, ValueError, KeyError, TypeError):
                continue


def _deliver_artist_job(job_id: str) -> None:
    with _LOCK:
        job = JOBS[job_id]
        if job.get("delivering") or (job.get("callback") or {}).get("accepted"):
            return
        body = job.get("callbackBody")
        if not isinstance(body, dict):
            return
        job.update(delivering=True, deliveryStatus="DELIVERING")
        _save_artist_job(job)
    try:
        result = analysis_jobs.post_callback(public_response(body))
    except Exception:
        log.exception("artist callback transport failed")
        result = {"http": None, "attempts": 0, "accepted": False, "error": "Callback transport failed"}
    with _LOCK:
        accepted = result.get("accepted") is True
        rejected = not accepted and isinstance(result.get("http"), int) and 400 <= result["http"] < 500
        job.update(delivering=False, callback=result,
                   deliveryStatus="ACCEPTED" if accepted else "REJECTED" if rejected else "PENDING_RETRY",
                   deliveryFailureCode=None if accepted else "CALLBACK_REJECTED" if rejected else "CALLBACK_UNAVAILABLE")
        _save_artist_job(job)


@router.post("/artist-search-jobs/{job_id}/redeliver", status_code=202)
def redeliver_artist_search(job_id: str, background_tasks: BackgroundTasks) -> dict[str, Any]:
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "검색 작업을 찾을 수 없습니다.")
    with _LOCK:
        body = job.get("callbackBody")
        if not isinstance(body, dict):
            raise HTTPException(410, "보존된 콜백이 없어 검색 재계산이 필요합니다.")
        if body.get("status") == "COMPLETED" and any(
            type(a.get("artistId")) is not int or a["artistId"] < 1 for a in body.get("artists") or []
        ):
            raise HTTPException(409, "유효 가수 ID가 없는 이전 결과는 재계산해야 합니다.")
        if job.get("delivering"):
            raise HTTPException(409, "콜백 전달이 이미 진행 중입니다.")
        if not (job.get("callback") or {}).get("accepted"):
            background_tasks.add_task(_deliver_artist_job, job_id)
    return {"jobId": job_id, "status": job["status"], "deliveryStatus": job.get("deliveryStatus")}
