"""BE → 추천 AI 곡 적합도와 닮은 가수. 둘 다 저장된 숫자만 쓰므로 오디오도 모델 추론도 없다.

  POST /song-fit            동기. 곡 하나의 원키·선택 키·추천 키 적합도
  POST /artist-search-jobs  202 접수 후 콜백. 사용자 임베딩으로 닮은 가수를 고른다
"""

from __future__ import annotations

from collections.abc import Mapping
import logging
import threading
import time
from typing import Any
import uuid

from fastapi import APIRouter, BackgroundTasks, HTTPException
import numpy as np

import analysis_jobs
import catalog_link
from pitch_profile import MIDI_MAX, MIDI_MIN, PROFILE_SCHEMA_VERSION
from recommendation_api import MODEL_DIMENSIONS, _configuration_status, user_embedding_matrices
from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.contracts import SCORING_VERSION
from timbre_recommender.pitch_range import RangeCatalog, score_range_fit
from timbre_recommender.scoring import RankerConfig, _absolute_similarity, _model_track_score


log = logging.getLogger("song_fit_api")
MAX_ARTISTS = 5          # DB 제약: rank_no BETWEEN 1 AND 5
JOBS: dict[str, dict[str, Any]] = {}
_LOCK = threading.Lock()
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


def _unavailable(payload: Mapping[str, Any], reason: str) -> dict[str, Any]:
    return {"songId": payload.get("songId"), "analysisId": payload.get("analysisId"),
            "selectedKeyOffset": int(payload.get("selectedKeyOffset") or 0),
            "evaluationStatus": "UNAVAILABLE", "originalKeyFit": None, "selectedKeyFit": None,
            "recommendedKeyOffset": None, "recommendationScores": payload.get("recommendationScores"),
            "reason": None, "burdenSections": list(payload.get("burdenSections") or []),
            "unavailableReason": reason}


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
    user_low = _decimal(payload.get("userStableLowMidi"))
    user_high = _decimal(payload.get("userStableHighMidi"))
    if user_low is None or user_high is None:
        return _unavailable(payload, "USER_STABLE_RANGE_UNAVAILABLE")
    user = _flat_profile(user_low, user_high, kind="guided_range_measurement")
    if user is None:
        return _unavailable(payload, "USER_STABLE_RANGE_UNAVAILABLE")

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
            return _unavailable(payload, "SONG_RANGE_UNAVAILABLE")
        song = _flat_profile(song_low, song_high, kind="song_range")
        if song is None:
            return _unavailable(payload, "SONG_RANGE_UNAVAILABLE")

    best = score_range_fit(user, song)
    selected = int(payload.get("selectedKeyOffset") or 0)
    recommended = int(best["keyShift"])
    return {
        "songId": payload.get("songId"), "analysisId": payload.get("analysisId"),
        "selectedKeyOffset": selected, "evaluationStatus": "AVAILABLE",
        "originalKeyFit": _fit_at(user, song, 0, user_low, user_high),
        "selectedKeyFit": _fit_at(user, song, selected, user_low, user_high),
        "recommendedKeyOffset": recommended,
        "recommendationScores": payload.get("recommendationScores"),
        "reason": _reason(recommended, best),
        "burdenSections": list(payload.get("burdenSections") or []),
        "unavailableReason": None,
    }


# ── 닮은 가수 ─────────────────────────────────────────────────────────────────

def rank_artists(query: dict[str, np.ndarray], catalog: CatalogIndex, top_k: int) -> list[dict[str, Any]]:
    """가수별로 그 가수의 모든 곡 구간을 모아 사용자와 견준다. 두 축 점수의 평균으로 순위를 매긴다.

    추천의 음색 점수와 같은 함수를 쓴다. 곡 하나가 아니라 가수의 구간 전체가 상대라는 점만 다르다.
    """
    by_artist: dict[str, list] = {}
    for track in catalog.tracks:
        by_artist.setdefault(track.artist, []).append(track)
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
        scores.append({"artistName": artist,
                       "ecapaScore": round(float(axis["ecapa"]), 4),
                       "singerByolScore": round(float(axis["singer_byol"]), 4),
                       "similarity": round(float(axis["ecapa"] + axis["singer_byol"]) / 2.0, 4)})
    scores.sort(key=lambda item: (-item["similarity"], item["artistName"]))
    return [{"rank": rank, "artistId": None, **item}
            for rank, item in enumerate(scores[: max(1, min(top_k, MAX_ARTISTS))], start=1)]


def _artist_process(job_id: str, payload: dict[str, Any]) -> None:
    job = JOBS[job_id]
    try:
        config, missing = _configuration_status()
        if missing:
            raise RuntimeError(f"추천 데모 설정 누락: {', '.join(missing)}")
        query = user_embedding_matrices(payload.get("modelResults"))
        catalog = CatalogIndex.load(config["index_dir"], verify_hashes=True)
        artists = rank_artists(query, catalog, int(payload.get("topK") or MAX_ARTISTS))
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
    result = analysis_jobs.post_callback(body)
    with _LOCK:
        job.update(status=body["status"], callback=result, artists=body.get("artists"),
                   failureCode=body["failureCode"])


def _artist_failure(job: dict[str, Any], code: str, reason: str) -> dict[str, Any]:
    status = "INSUFFICIENT_DATA" if code == "INSUFFICIENT_DATA" else "FAILED"
    return {"requestId": job["requestId"], "jobId": job["jobId"], "component": "ARTIST_SEARCH",
            "status": status, "modelKey": "ARTIST_SEARCH", "modelVersion": SCORING_VERSION,
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
                        "createdAt": time.time(), "artists": None, "callback": None, "failureCode": None}
    background_tasks.add_task(_artist_process, job_id, dict(payload))
    return {"jobId": job_id, "status": "QUEUED", "audioExpiresAt": None, "duplicate": False}


@router.get("/artist-search-jobs/{job_id}")
def get_artist_search(job_id: str) -> dict[str, Any]:
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "검색 작업을 찾을 수 없습니다.")
    return job
