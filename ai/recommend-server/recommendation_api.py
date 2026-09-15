"""Local FastAPI routes for the ECAPA + Singer-BYOL demo UI."""

from __future__ import annotations

import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import threading
import time
import uuid
from typing import Any

from collections.abc import Mapping
from fastapi import APIRouter, BackgroundTasks, HTTPException, Request
import imageio_ffmpeg
import numpy as np
import soundfile as sf

import catalog_link
from pitch_engine import extract_vocal_melody
from pitch_profile import MIDI_MAX, MIDI_MIN, PROFILE_SCHEMA_VERSION, summarize_pitch_profile
from schemas import RecommendResult
from timbre_recommender.catalog import CatalogIndex
from timbre_recommender.composition import allowed_languages
from timbre_recommender.contracts import SCORING_VERSION
from timbre_recommender.embedding import embed_vocal_audio
from timbre_recommender.shelves import SHELVES_VERSION, build_shelves, score_all_tracks
from timbre_recommender.pitch_range import RANGE_CATALOG_FILENAME, RangeCatalog
from timbre_recommender.scoring import RankerConfig, recommend


ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT / "workspace" / "recommender"
CONFIG_PATH = WORKSPACE / "config.json"
JOBS_DIR = WORKSPACE / "jobs"
ALLOWED_EXTENSIONS = {".mp3", ".wav", ".flac", ".m4a", ".ogg", ".webm"}
MAX_BYTES = 250 * 1024 * 1024
MAX_DURATION_SECONDS = 120.0
CANDIDATE_POOL = 400  # compose 전에 후보 전체를 받는다. 최종 개수는 topK 가 정한다.
MAX_TOP_K = 50        # 섹션당 최대 곡 수. BE 가 필터(성별·언어)용으로 넉넉히 저장할 수 있게 20 → 50 (2026-09-09)
LOCK = threading.Lock()
INFERENCE_LOCK = threading.Lock()
JOBS: dict[str, dict[str, Any]] = {}
router = APIRouter(tags=["timbre-recommendations"])


def _replace_with_retry(source: Path, destination: Path) -> None:
    """Replace metadata atomically, tolerating brief Windows reader locks."""

    for attempt in range(6):
        try:
            os.replace(source, destination)
            return
        except PermissionError:
            if attempt == 5:
                raise
            time.sleep(0.02 * (attempt + 1))


def _load_config() -> dict[str, Any]:
    if not CONFIG_PATH.is_file():
        return {}
    try:
        return json.loads(CONFIG_PATH.read_text(encoding="utf-8"))
    except (OSError, json.JSONDecodeError):
        return {}


def _configuration_status() -> tuple[dict[str, Any], list[str]]:
    config = _load_config()
    checks = {
        "데모 카탈로그": Path(config.get("index_dir", "")) / "index.json",
        "곡별 음역 카탈로그": Path(config.get("index_dir", "")) / RANGE_CATALOG_FILENAME,
        "ECAPA 체크포인트": Path(config.get("ecapa_checkpoint", "")),
        "Singer-BYOL 코드": Path(config.get("singer_repo", "")) / "singer_identity" / "model.py",
        "Singer-BYOL 체크포인트": Path(config.get("singer_checkpoint_root", "")) / "byol" / "model.pt",
    }
    missing = [label for label, path in checks.items() if not str(path) or not path.is_file()]
    return config, missing


def _save_job(job_id: str) -> None:
    job_dir = JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    temporary = job_dir / ".job.json.tmp"
    temporary.write_text(
        json.dumps(JOBS[job_id], ensure_ascii=False, indent=2) + "\n", encoding="utf-8"
    )
    _replace_with_retry(temporary, job_dir / "job.json")


def _update(job_id: str, **values: Any) -> None:
    with LOCK:
        JOBS[job_id].update(values)
        _save_job(job_id)


def _convert_to_wav(source: Path, target: Path) -> None:
    command = [
        imageio_ffmpeg.get_ffmpeg_exe(), "-y", "-i", str(source), "-vn",
        "-t", str(MAX_DURATION_SECONDS + 1.0), "-ac", "1", "-ar", "44100", str(target),
    ]
    completed = subprocess.run(
        command, capture_output=True, text=True, encoding="utf-8", errors="replace"
    )
    if completed.returncode != 0:
        raise RuntimeError(f"보컬 파일을 WAV로 변환하지 못했습니다: {completed.stderr[-500:]}")
    duration = float(sf.info(target).duration)
    if duration > MAX_DURATION_SECONDS + 0.05:
        raise RuntimeError("추천용 보컬 파일은 2분 이하로 잘라서 올려주세요.")


def _cleanup_query_artifacts(job_dir: Path) -> None:
    for name in (
        "original.mp3", "original.wav", "original.flac", "original.m4a", "original.ogg", "original.webm",
        "normalized.wav", "query.ecapa.npy", "query.singer_byol.npy", "query.segments.json",
    ):
        path = job_dir / name
        if path.is_file():
            path.unlink()


def build_result(
    catalog: CatalogIndex,
    ranges: RangeCatalog,
    *,
    query: dict[str, np.ndarray],
    pitch_profile: dict[str, Any] | None,
    pitch_error: str | None,
    mode: str,
    top_k: int,
    genres: tuple[str, ...] = (),
    preferred_artists: tuple[str, ...] = (),
    excluded_track_ids: tuple[str, ...] = (),
    seed: int | None = None,
) -> dict[str, Any]:
    """임베딩·프로파일 → 섹션(shelves) 응답. API 와 샘플 스크립트가 같이 쓴다. 스키마 검증까지 끝낸 dict 를 돌려준다."""
    range_enabled = bool(pitch_profile and pitch_profile.get("reliable", False))
    fallback = None
    if pitch_profile is None:
        fallback = pitch_error or "pitch_profile_unavailable"
    elif not range_enabled:
        fallback = ",".join(pitch_profile.get("qualityReasons") or ["unreliable_pitch_profile"])
    fav = [t for t in catalog.tracks if t.artist in set(preferred_artists)]
    artist_query = {k: np.vstack([catalog.embeddings(t, k) for t in fav]) for k in ("ecapa", "singer_byol")} if fav else None
    rows = score_all_tracks(
        catalog, ranges, query, query_profile=pitch_profile if range_enabled else None, mode=mode, genres=genres, artist_query=artist_query,
    )
    rows = [r for r in rows if r["track_id"] not in set(excluded_track_ids)]
    if seed is None:
        seed = int(time.strftime("%Y%m%d"))          # 새로운 발견: 하루 단위로 섞임
    shelves = build_shelves(rows, top_k=top_k, genres=genres, preferred_artists=preferred_artists, range_enabled=range_enabled, seed=seed)
    allowed = allowed_languages(genres)
    public_profile = {k: v for k, v in (pitch_profile or {}).items() if k not in {"histogram", "sourceSha256"}} or None
    result = {
        "algorithm": {
            "scoring_version": SCORING_VERSION,
            "shelves_version": SHELVES_VERSION,
            "requested_mode": mode,
            "applied_mode": mode if range_enabled else "timbre_only",
            "range_enabled": range_enabled,
            "range_fallback_reason": fallback,
            "language_gate": {"enabled": allowed is not None, "genres": list(genres), "allowed": sorted(allowed) if allowed else None},
            "preferred_artists": list(preferred_artists),
            "catalog_scored": len(rows),
        },
        "query": {"segments": {k: int(len(v)) for k, v in query.items()}, "pitchProfile": public_profile, "pitchError": pitch_error},
        "shelves": shelves,
        "recommendations": shelves[0]["items"] if shelves else [],
        "catalog_track_count": len(catalog.tracks),
        "limitations": [
            "관찰 음역은 업로드 샘플 기준이며 생리적 한계가 아니다.",
            "음색 점수는 후보군과 무관한 절대 유사도이지 성공 확률이 아니다.",
            "artist_gender 는 카탈로그에 아직 없다(null).",
        ],
    }
    return RecommendResult.model_validate(result).model_dump()   # 응답 스키마 고정(schemas.py)


# ── BE JSON 입력 ─────────────────────────────────────────────────────────────
# 모델 추론이 없으므로 INFERENCE_LOCK 을 잡지 않는다 — 추천 요청은 서로 기다리지 않는다.

MODEL_DIMENSIONS = {"ecapa": 192, "singer_byol": 1000}
_MODEL_ALIASES = {"ecapa": "ecapa", "ECAPA": "ecapa", "singer_byol": "singer_byol",
                  "singerByol": "singer_byol", "SINGER_BYOL": "singer_byol", "singerbyol": "singer_byol"}


def _rows_of(value: Any, label: str) -> list[list[float]]:
    """dict(콜백 결과) · [[..]] · [..] 중 무엇이 와도 구간 행 목록으로 만든다."""
    if isinstance(value, Mapping):
        segment_info = value.get("segmentInfo") or {}
        rows = segment_info.get("embeddings") or value.get("embeddings") or value.get("segments")
        if isinstance(rows, list) and rows and isinstance(rows[0], Mapping):   # [{startSeconds, embedding}, …]
            rows = [row.get("embedding") for row in rows]
        if not rows:
            rows = value.get("embedding")               # 구간 벡터가 없으면 평균 벡터 하나로 계산한다
        value = rows
    if not isinstance(value, list) or not value:
        raise HTTPException(422, f"userEmbedding.{label}: 임베딩 값이 없습니다.")
    return value if isinstance(value[0], list) else [value]


def _matrix(value: Any, key: str) -> np.ndarray:
    dimension = MODEL_DIMENSIONS[key]
    rows = _rows_of(value, key)
    try:
        matrix = np.asarray(rows, dtype=np.float64)
    except (ValueError, TypeError) as error:
        raise HTTPException(422, f"userEmbedding.{key}: 숫자 벡터가 필요합니다.") from error
    if matrix.ndim != 2 or matrix.shape[1] != dimension:
        raise HTTPException(422, f"userEmbedding.{key}: {dimension}차원 벡터가 필요합니다(받은 모양 {matrix.shape}).")
    if not np.all(np.isfinite(matrix)):
        raise HTTPException(422, f"userEmbedding.{key}: 유한한 실수만 넣어주세요.")
    if not 1 <= matrix.shape[0] <= 12:
        raise HTTPException(422, f"userEmbedding.{key}: 구간은 1~12개여야 합니다(받은 개수 {matrix.shape[0]}).")
    with np.errstate(over="ignore"):
        norms = np.linalg.norm(matrix, axis=1)
    if not np.isfinite(norms).all() or np.any(norms < 1e-12):
        raise HTTPException(422, f"userEmbedding.{key}: 영벡터는 사용할 수 없습니다.")
    return matrix / norms[:, None]


def user_embedding_matrices(payload: Any) -> dict[str, np.ndarray]:
    """BE 가 저장해 둔 userEmbedding → {"ecapa": (N,192), "singer_byol": (M,1000)}."""
    if not isinstance(payload, Mapping):
        raise HTTPException(422, "userEmbedding 은 객체여야 합니다.")
    found: dict[str, Any] = {}
    for name, value in payload.items():
        key = _MODEL_ALIASES.get(str(name))
        if key and key not in found:
            found[key] = value
    missing = [key for key in MODEL_DIMENSIONS if key not in found]
    if missing:
        raise HTTPException(422, f"userEmbedding 에 {', '.join(missing)} 결과가 없습니다.")
    return {key: _matrix(found[key], key) for key in MODEL_DIMENSIONS}


def _decimal(value: Any) -> float | None:
    try:
        return None if value is None else float(value)
    except (TypeError, ValueError):
        return None


def _note_histogram(notes: Any) -> np.ndarray:
    """음역 측정의 음별 결과 → MIDI 히스토그램. 성공한 음에만 질량을 준다."""
    histogram = np.zeros(MIDI_MAX - MIDI_MIN + 1, dtype=np.float64)
    if not isinstance(notes, list):
        return histogram
    for note in notes:
        if not isinstance(note, Mapping):
            continue
        passed = note.get("success") if note.get("success") is not None else str(note.get("status", "")).upper() == "PASSED"
        midi = _decimal(note.get("midi"))
        if not passed or midi is None or not MIDI_MIN <= midi <= MIDI_MAX:
            continue
        histogram[int(round(midi)) - MIDI_MIN] += max(_decimal(note.get("holdMs")) or 0.0, 1.0)
    return histogram


def profile_from_voice_profile(voice_profile: Any) -> tuple[dict[str, Any] | None, str | None]:
    """BE voiceProfile → 음역 점수 계산에 쓰는 프로파일. (프로파일, 실패 사유) 중 하나만 채워진다.

    노래에서 관찰한 프로파일(observed*·pitchHistogram)이 있으면 그대로 쓰고,
    없으면 앱 음역 측정 결과(stableLow/HighMidi + 음별 결과)로 만든다. 지금 흐름은 후자다.
    """
    if not isinstance(voice_profile, Mapping):
        return None, "voice_profile_missing"
    histogram = voice_profile.get("pitchHistogram")
    core_low = _decimal(voice_profile.get("observedCoreLowMidi"))
    core_high = _decimal(voice_profile.get("observedCoreHighMidi"))
    median = _decimal(voice_profile.get("observedMedianMidi"))
    if isinstance(histogram, list) and len(histogram) == MIDI_MAX - MIDI_MIN + 1 and None not in (core_low, core_high, median):
        observed = {"schemaVersion": PROFILE_SCHEMA_VERSION, "profileKind": "observed_user_sample",
                    "histogram": histogram, "coreLowMidi": int(round(core_low)),
                    "medianMidi": int(round(median)), "coreHighMidi": int(round(core_high)),
                    "lowestMidi": int(round(core_low)), "highestMidi": int(round(core_high)),
                    "reliable": True, "qualityReasons": [],
                    "confidence": _decimal(voice_profile.get("rangeConfidence")) or 1.0}
        return observed, None

    low = _decimal(voice_profile.get("stableLowMidi"))
    high = _decimal(voice_profile.get("stableHighMidi"))
    if low is None or high is None:
        return None, "user_range_unavailable"
    low, high = sorted((low, high))
    low = int(round(max(MIDI_MIN, min(MIDI_MAX, low))))
    high = int(round(max(MIDI_MIN, min(MIDI_MAX, high))))
    if high - low < 1:
        return None, "user_range_too_narrow"
    mass = _note_histogram(voice_profile.get("pitchDistribution"))
    if mass.sum() <= 0:                      # 음별 결과가 없으면 안정 음역에 고르게 깔아 쓴다
        mass[low - MIDI_MIN : high - MIDI_MIN + 1] = 1.0
    median_value = _decimal(voice_profile.get("medianMidi"))
    median_midi = int(round(median_value)) if median_value is not None else (low + high) // 2
    median_midi = max(low, min(high, median_midi))
    # rangeConfidence 는 지금 흐름에서 비어 온다. 앱 측정이 VALID 로 접수된 값이므로 신뢰한다.
    confidence = _decimal(voice_profile.get("rangeConfidence"))
    profile = {
        "schemaVersion": PROFILE_SCHEMA_VERSION, "profileKind": "guided_range_measurement",
        "method": "on-device guided-range-v7", "histogram": mass.tolist(),
        "lowestMidi": low, "coreLowMidi": low, "medianMidi": median_midi, "coreHighMidi": high, "highestMidi": high,
        "confidence": 1.0 if confidence is None else confidence,
        "reliable": confidence is None or confidence >= 0.35,
        "qualityReasons": [] if confidence is None or confidence >= 0.35 else ["low_range_confidence"],
        "noteCount": int((mass > 0).sum()), "voicedSeconds": None,
    }
    return profile, None


def _json_process(job_id: str, payload: dict[str, Any]) -> None:
    try:
        config, missing = _configuration_status()
        if missing:
            raise RuntimeError(f"추천 데모 설정 누락: {', '.join(missing)}")
        _update(job_id, status="processing", progress=40, message="저장된 음색·음역으로 곡을 고르고 있습니다.")
        query = user_embedding_matrices(payload.get("userEmbedding"))
        profile, profile_error = profile_from_voice_profile(payload.get("voiceProfile"))
        catalog = CatalogIndex.load(config["index_dir"], verify_hashes=True)
        ranges = RangeCatalog.load(
            config["index_dir"], expected_track_ids={track.track_id for track in catalog.tracks}, verify_hashes=True,
        )
        result = build_result(
            catalog, ranges, query=query, pitch_profile=profile, pitch_error=profile_error,
            mode=_ai_mode(payload.get("mode")), top_k=max(1, min(int(payload.get("topK") or 10), MAX_TOP_K)),
            genres=_strings(payload.get("genres")), preferred_artists=_strings(payload.get("preferredArtists")),
            excluded_track_ids=_excluded_tracks(payload.get("sourceSongId"), catalog),
            seed=payload.get("seed"),
        )
        result = catalog_link.apply(result)
        _update(job_id, status="complete", progress=100, result=result,
                message="음색과 음역 추천이 완료되었습니다." if result["algorithm"]["range_enabled"]
                        else "음역 신뢰도가 낮아 음색 추천으로 완료했습니다.")
    except HTTPException as error:
        _update(job_id, status="failed", progress=100, message="추천 입력이 올바르지 않습니다.", error=str(error.detail))
    except Exception as error:
        _update(job_id, status="failed", progress=100, message="맞춤 추천에 실패했습니다.", error=str(error))


def _ai_mode(value: Any) -> str:
    mode = str(value or "timbre").lower()
    if mode not in {"timbre", "range"}:
        raise HTTPException(422, "mode는 timbre 또는 range여야 합니다.")
    return mode


def _strings(value: Any) -> tuple[str, ...]:
    if not isinstance(value, list):
        return ()
    return tuple(item.strip() for item in value if isinstance(item, str) and item.strip())


def _excluded_tracks(source_song_id: Any, catalog: CatalogIndex, extra: tuple[str, ...] = ()) -> tuple[str, ...]:
    """추천 후보에서 뺄 곡. 앱에 등록되지 않은 곡과 방금 부른 원곡이다.

    뽑고 나서 버리면 섹션이 짧아지므로 채점 전에 뺀다. 짝표가 없으면(개발 모드) 등록 여부로 거르지 않는다.
    """
    known = {track.track_id for track in catalog.tracks}
    mapping = catalog_link.song_ids()
    excluded = set(extra)
    if mapping:
        excluded |= known - set(mapping)
        if source_song_id is not None:
            excluded |= {track_id for track_id, song_id in mapping.items()
                         if str(song_id) == str(source_song_id) and track_id in known}
    return tuple(sorted(excluded))


def _resolved_device(requested: str) -> str:
    import torch

    if requested == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    if requested == "cuda" and not torch.cuda.is_available():
        raise RuntimeError("CUDA가 요청되었지만 현재 사용할 수 없습니다.")
    return requested


def _process(
    job_id: str,
    input_path: Path,
    top_k: int,
    mode: str,
    excluded_track_ids: tuple[str, ...],
    genres: tuple[str, ...] = (),
    preferred_artists: tuple[str, ...] = (),
) -> None:
    job_dir = input_path.parent
    try:
        config, missing = _configuration_status()
        if missing:
            raise RuntimeError(f"추천 데모 설정 누락: {', '.join(missing)}")
        with INFERENCE_LOCK:
            normalized = job_dir / "normalized.wav"
            _update(job_id, status="processing", progress=18, message="보컬 파일을 44.1 kHz로 정규화하고 있습니다.")
            _convert_to_wav(input_path, normalized)
            pitch_profile: dict[str, Any] | None = None
            pitch_error: str | None = None
            _update(job_id, progress=28, message="VocalScope가 현재 부른 음역 분포를 분석하고 있습니다.")
            try:
                melody = extract_vocal_melody(
                    normalized,
                    None,
                    _resolved_device(config.get("device", "auto")),
                    return_frames=False,
                )
                pitch_profile = summarize_pitch_profile(
                    melody["notes"],
                    profile_kind="observed_user_sample",
                    f0_agreement_ratio=melody.get("f0AgreementRatio"),
                    octave_mismatch_ratio=melody.get("octaveMismatchRatio"),
                    compared_frames=melody.get("comparedFrames"),
                )
            except Exception as error:
                pitch_error = str(error)[:400]
            _update(job_id, progress=45, message="좋은 4초 보컬 구간을 고르고 있습니다.")
            prefix = job_dir / "query"
            _update(job_id, progress=52, message="ECAPA와 Singer-BYOL 임베딩을 생성하고 있습니다.")
            embed_vocal_audio(
                normalized,
                prefix,
                ecapa_checkpoint=config["ecapa_checkpoint"],
                singer_repo=config["singer_repo"],
                singer_checkpoint_root=config["singer_checkpoint_root"],
                device=config.get("device", "auto"),
                batch_size=8,
                max_segments=12,
                min_segments=5,
            )
            _update(job_id, progress=88, message="음색과 음역 적합도, 추천 키를 결합하고 있습니다.")
            catalog = CatalogIndex.load(config["index_dir"], verify_hashes=True)
            ranges = RangeCatalog.load(
                config["index_dir"],
                expected_track_ids={track.track_id for track in catalog.tracks},
                verify_hashes=True,
            )
            result = build_result(
                catalog, ranges,
                query={
                    "ecapa": np.load(f"{prefix}.ecapa.npy", allow_pickle=False),
                    "singer_byol": np.load(f"{prefix}.singer_byol.npy", allow_pickle=False),
                },
                pitch_profile=pitch_profile, pitch_error=pitch_error, mode=mode, top_k=top_k,
                genres=genres, preferred_artists=preferred_artists,
                excluded_track_ids=_excluded_tracks(None, catalog, extra=excluded_track_ids),
            )
            result = catalog_link.apply(result)
        _update(
            job_id,
            status="complete",
            progress=100,
            message=(
                "음색과 음역 추천이 완료되었습니다."
                if result["algorithm"]["range_enabled"]
                else "음역 신뢰도가 낮아 음색 추천으로 완료했습니다."
            ),
            result=result,
        )
    except Exception as error:
        _update(
            job_id,
            status="failed",
            progress=100,
            message="맞춤 추천에 실패했습니다.",
            error=str(error),
        )
    finally:
        _cleanup_query_artifacts(job_dir)


for metadata_path in JOBS_DIR.glob("*/job.json") if JOBS_DIR.exists() else ():
    try:
        restored = json.loads(metadata_path.read_text(encoding="utf-8"))
        if restored.get("status") in {"queued", "processing"}:
            restored.update(status="failed", progress=100, message="AI 엔진이 중단되어 다시 실행해야 합니다.")
        JOBS[restored["id"]] = restored
    except Exception:
        continue


@router.get("/recommendations/status")
def recommendation_status() -> dict[str, Any]:
    import torch

    config, missing = _configuration_status()
    catalog_link_state = catalog_link.ensure()
    tracks = 0
    catalog_tracks: list[dict[str, str]] = []
    if not missing:
        try:
            catalog = CatalogIndex.load(config["index_dir"])
            RangeCatalog.load(
                config["index_dir"],
                expected_track_ids={track.track_id for track in catalog.tracks},
                verify_hashes=True,
            )
            tracks = len(catalog.tracks)
            catalog_tracks = [
                {
                    "track_id": track.track_id,
                    "title": track.title,
                    "artist": track.artist,
                }
                for track in catalog.tracks
            ]
        except Exception:
            missing.append("데모 카탈로그 검증")
    return {
        "ready": not missing and catalog_link_state["mappedTracks"] > 0,
        "engineReady": not missing,
        "device": "cuda" if torch.cuda.is_available() else "cpu",
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "catalogTracks": tracks,
        "catalog": catalog_tracks,
        "models": ["ECAPA-TDNN VoxCeleb", "Singer-BYOL", "VocalScope CREPE + pYIN"],
        "rangeModes": ["timbre", "range"],
        "catalogLink": catalog_link_state,   # 앱 song_id 짝표. mappedTracks 가 0 이면 BE 에 저장할 수 없다
        "missing": missing,
    }


@router.post("/recommendations", status_code=202)
async def create_recommendation(request: Request, background_tasks: BackgroundTasks) -> dict[str, Any]:
    """BE 는 JSON(저장된 임베딩), 개발용 데모는 multipart(오디오)로 부른다. Content-Type 으로 가른다."""
    if request.headers.get("content-type", "").startswith("application/json"):
        return _create_from_json(await request.json(), background_tasks)
    return await _create_from_multipart(await request.form(), background_tasks)


def _create_from_json(payload: Any, background_tasks: BackgroundTasks) -> dict[str, Any]:
    _, missing = _configuration_status()
    if missing:
        raise HTTPException(503, f"추천 데모 설정이 필요합니다: {', '.join(missing)}")
    if not isinstance(payload, Mapping):
        raise HTTPException(422, "요청 본문은 객체여야 합니다.")
    user_embedding_matrices(payload.get("userEmbedding"))      # 잡을 만들기 전에 입력부터 검증한다
    _ai_mode(payload.get("mode"))
    if catalog_link.ensure()["mappedTracks"] == 0:
        raise HTTPException(503, "DB song_id 카탈로그 연결이 준비되지 않았습니다.")
    if type(payload.get("topK")) is not int or not 1 <= payload["topK"] <= MAX_TOP_K:
        raise HTTPException(422, "topK는 1~50 정수여야 합니다.")
    if type(payload.get("profileId")) is not int or payload["profileId"] < 1:
        raise HTTPException(422, "profileId는 양수 DB ID여야 합니다.")
    job_id = str(uuid.uuid4())
    JOBS[job_id] = {
        "id": job_id, "filename": None, "mode": _ai_mode(payload.get("mode")),
        "sourceTrackId": None, "profileId": payload.get("profileId"), "sourceSongId": payload.get("sourceSongId"),
        "genres": list(_strings(payload.get("genres"))), "preferred_artists": list(_strings(payload.get("preferredArtists"))),
        "createdAt": time.time(), "status": "queued", "progress": 8,
        "message": "추천 대기열에 추가되었습니다.", "result": None, "error": None,
    }
    (JOBS_DIR / job_id).mkdir(parents=True, exist_ok=True)
    _save_job(job_id)
    background_tasks.add_task(_json_process, job_id, dict(payload))
    return JOBS[job_id]


async def _create_from_multipart(form: Any, background_tasks: BackgroundTasks) -> dict[str, Any]:
    config, missing = _configuration_status()
    if missing:
        raise HTTPException(503, f"추천 데모 설정이 필요합니다: {', '.join(missing)}")
    audio = form.get("audio")
    if audio is None or not hasattr(audio, "read"):
        raise HTTPException(422, "audio 파일 파트가 필요합니다.")
    topK = int(form.get("topK") or 10)
    mode = str(form.get("mode") or "timbre")
    sourceTrackId = str(form.get("sourceTrackId") or "")
    genres = [value for value in form.getlist("genres") if isinstance(value, str)]
    preferredArtists = [value for value in form.getlist("preferredArtists") if isinstance(value, str)]
    extension = Path(audio.filename or "audio").suffix.lower()
    if extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(415, "WAV, FLAC, MP3, M4A, OGG 또는 WEBM 파일만 지원합니다.")
    if mode not in {"timbre", "range"}:
        raise HTTPException(422, "mode는 timbre 또는 range여야 합니다.")
    source_track_id = sourceTrackId.strip()
    excluded_track_ids: tuple[str, ...] = ()
    if source_track_id:
        try:
            catalog = CatalogIndex.load(config["index_dir"])
        except Exception as error:
            raise HTTPException(503, f"추천 카탈로그를 읽지 못했습니다: {error}") from error
        if source_track_id not in {track.track_id for track in catalog.tracks}:
            raise HTTPException(422, "선택한 원곡이 추천 카탈로그에 없습니다.")
        excluded_track_ids = (source_track_id,)
    job_id = str(uuid.uuid4())
    job_dir = JOBS_DIR / job_id
    job_dir.mkdir(parents=True, exist_ok=False)
    input_path = job_dir / f"original{extension}"
    size = 0
    with input_path.open("wb") as output:
        while chunk := await audio.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_BYTES:
                output.close()
                shutil.rmtree(job_dir, ignore_errors=True)
                raise HTTPException(413, "보컬 파일은 250MB 이하만 사용할 수 있습니다.")
            output.write(chunk)
    JOBS[job_id] = {
        "id": job_id,
        "filename": audio.filename,
        "mode": mode,
        "sourceTrackId": source_track_id or None,
        "genres": [g.strip() for g in genres if g and g.strip()],
        "preferred_artists": [a.strip() for a in preferredArtists if a and a.strip()],
        "createdAt": time.time(),
        "status": "queued",
        "progress": 8,
        "message": "추천 대기열에 추가되었습니다.",
        "result": None,
        "error": None,
    }
    _save_job(job_id)
    background_tasks.add_task(
        _process,
        job_id,
        input_path,
        max(1, min(int(topK), MAX_TOP_K)),
        mode,
        excluded_track_ids,
        tuple(JOBS[job_id]["genres"]),
        tuple(JOBS[job_id]["preferred_artists"]),
    )
    return JOBS[job_id]


@router.get("/recommendations/{job_id}")
def get_recommendation(job_id: str) -> dict[str, Any]:
    if not re.fullmatch(r"[0-9a-f-]{36}", job_id, flags=re.IGNORECASE):
        raise HTTPException(400, "잘못된 추천 작업 번호입니다.")
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "추천 작업을 찾을 수 없습니다.")
    return job
