from __future__ import annotations

import json
import os
import shutil
import threading
import time
import uuid
from pathlib import Path
from typing import Any

from fastapi import BackgroundTasks, Body, FastAPI, File, Form, HTTPException, Request, UploadFile
from fastapi.middleware.cors import CORSMiddleware
from fastapi.responses import FileResponse

from compact import key_shifted_opus
from compare_engine import compare_jobs
from engine import _create_musicxml, realign_lyrics, rebuild_karaoke, run_pipeline
from pitch_shift import MAX_SEMITONES, MIN_SEMITONES, clamp_semitones, pitch_shift_file, shifted_name
from separation import BACKENDS, normalize_backend

ROOT = Path(__file__).resolve().parent
WORKSPACE = ROOT / "workspace"
WORKSPACE.mkdir(parents=True, exist_ok=True)
COMPACT_JOBS = os.environ.get("VOCALSCOPE_COMPACT", "0") == "1"  # 저장 정책(compact.py)을 웹 업로드 작업에도 적용
ALLOWED_EXTENSIONS = {".mp3", ".wav", ".flac", ".m4a", ".ogg", ".webm"}  # .webm: 라이브 채점 브라우저 녹음(MediaRecorder)
MAX_BYTES = 250 * 1024 * 1024
LOCK = threading.Lock()
JOBS: dict[str, dict[str, Any]] = {}

app = FastAPI(title="VocalScope Model Server", version="2.0.0")
app.add_middleware(
    CORSMiddleware,
    allow_origins=[
        "http://localhost:3000",
        "http://127.0.0.1:3000",
        "https://vocal-scope.dawon171100.chatgpt.site",
    ],
    allow_credentials=False,
    allow_methods=["GET", "POST", "OPTIONS"],
    allow_headers=["*"],
)


@app.middleware("http")
async def private_network_access(request: Request, call_next):
    response = await call_next(request)
    if request.headers.get("access-control-request-private-network") == "true":
        response.headers["Access-Control-Allow-Private-Network"] = "true"
    return response


def _save_job(job_id: str) -> None:
    job_dir = WORKSPACE / job_id
    job_dir.mkdir(parents=True, exist_ok=True)
    (job_dir / "job.json").write_text(json.dumps(JOBS[job_id], ensure_ascii=False, indent=2), encoding="utf-8")


def _update(job_id: str, **values: Any) -> None:
    with LOCK:
        JOBS[job_id].update(values)
        _save_job(job_id)


def _process(
    job_id: str,
    input_path: Path,
    source_type: str,
    lyrics: str,
    separator: str,
    reference_job_id: str | None = None,
    compare_options: dict[str, bool] | None = None,
) -> None:
    """Reference jobs: the full analysis. Performance jobs (`reference_job_id` set): the *same* separation /
    VAD / F0 analysis without the lyrics stage, followed by the comparison against the reference."""
    is_performance = reference_job_id is not None
    try:
        _update(job_id, status="processing", progress=2, message="AI 모델을 준비하고 있습니다.")

        def report(progress: int, message: str) -> None:
            scaled = round(progress * 0.9) if is_performance else progress
            _update(job_id, progress=scaled, message=message)

        result = run_pipeline(
            input_path.parent, input_path, report,
            skip_separation=source_type == "vocal", lyrics_text=lyrics or None, separator=separator,
            skip_lyrics=is_performance, compact=COMPACT_JOBS,
        )
        if is_performance:
            _update(job_id, progress=92, message="원곡 멜로디와 정렬(DTW)하고 음정·박자를 채점하고 있습니다.")
            options = compare_options or {}
            result["comparison"] = compare_jobs(
                WORKSPACE / reference_job_id, input_path.parent,
                allow_transpose=options.get("allowTranspose", True), octave_tolerant=options.get("octaveTolerant", True),
                performance_job={"id": job_id, "status": "complete", "result": result},
            )
        _update(job_id, status="complete", progress=100, result=result)
    except Exception as error:
        _update(job_id, status="failed", message="비교에 실패했습니다." if is_performance else "분석에 실패했습니다.", error=str(error))


for metadata_path in WORKSPACE.glob("*/job.json"):
    try:
        restored = json.loads(metadata_path.read_text(encoding="utf-8"))
        if restored.get("status") == "processing":
            restored.update(status="failed", message="AI 엔진이 중단되어 작업을 다시 시작해야 합니다.")
        JOBS[restored["id"]] = restored
    except Exception:
        continue


@app.get("/health")
def health() -> dict[str, Any]:
    import torch
    return {
        "ok": True,
        "engine": "VocalScope AI",
        "device": "cuda" if torch.cuda.is_available() else "cpu",
        "gpu": torch.cuda.get_device_name(0) if torch.cuda.is_available() else None,
        "models": ["mel-band-roformer", "vad", "crepe-full-segment-viterbi", "pyin-cross-check", "whisper-turbo", "wav2vec2-ko-ctc-align", "dtw-performance-score"],
        "separators": list(BACKENDS),
        "defaultSeparator": normalize_backend(None),
    }


async def _receive_upload(audio: UploadFile, job_dir: Path) -> Path:
    extension = Path(audio.filename or "audio").suffix.lower()
    if extension not in ALLOWED_EXTENSIONS:
        raise HTTPException(415, "MP3, WAV, FLAC, M4A, OGG 또는 WEBM 파일만 지원합니다.")
    job_dir.mkdir(parents=True, exist_ok=True)
    input_path = job_dir / f"original{extension}"
    size = 0
    with input_path.open("wb") as output:
        while chunk := await audio.read(1024 * 1024):
            size += len(chunk)
            if size > MAX_BYTES:
                output.close()
                shutil.rmtree(job_dir, ignore_errors=True)
                raise HTTPException(413, "음원 파일은 250MB 이하만 처리할 수 있습니다.")
            output.write(chunk)
    return input_path


def _job_summary(job: dict[str, Any]) -> dict[str, Any]:
    result = job.get("result") or {}
    return {
        "id": job["id"],
        "kind": job.get("kind", "reference"),
        "filename": job.get("filename"),
        "createdAt": job.get("createdAt"),
        "status": job.get("status"),
        "key": (result.get("key") or {}).get("name"),
        "tempoBpm": result.get("tempoBpm"),
        "vocalStart": result.get("vocalStart"),
        "vocalEnd": result.get("vocalEnd"),
        "noteCount": result.get("noteCount"),
        "hasKaraoke": bool(result.get("karaokeNotes")),
        "hasPitchFrames": (WORKSPACE / job["id"] / "pitch_frames.npz").exists(),
        "referenceJobId": job.get("referenceJobId"),
        "overall": ((result.get("comparison") or {}).get("scores") or {}).get("overall"),
    }


@app.get("/jobs")
def list_jobs(kind: str = "reference", limit: int = 50) -> dict[str, Any]:
    """Completed jobs, newest first — lets the web app reuse an analysed original as the comparison reference."""
    rows = [
        _job_summary(job) for job in JOBS.values()
        if job.get("status") == "complete" and job.get("result") and job.get("kind", "reference") == kind and job.get("filename")
    ]
    rows.sort(key=lambda row: row.get("createdAt") or 0, reverse=True)
    return {"jobs": rows[: max(1, min(limit, 200))]}


@app.post("/jobs", status_code=202)
async def create_job(
    background_tasks: BackgroundTasks,
    audio: UploadFile = File(...),
    sourceType: str = Form("mix"),
    lyrics: str = Form(""),
    separator: str = Form(""),
) -> dict[str, Any]:
    if len(lyrics) > 20000:
        raise HTTPException(413, "가사는 20,000자 이하로 입력해주세요.")
    job_id = str(uuid.uuid4())
    job_dir = WORKSPACE / job_id
    input_path = await _receive_upload(audio, job_dir)
    JOBS[job_id] = {
        "id": job_id,
        "kind": "reference",
        "filename": audio.filename,
        "createdAt": time.time(),
        "sourceType": "vocal" if sourceType == "vocal" else "mix",
        "separator": normalize_backend(separator),
        "hasLyrics": bool(lyrics.strip()),
        "status": "queued",
        "progress": 0,
        "message": "분석 대기열에 추가되었습니다.",
        "result": None,
        "error": None,
    }
    _save_job(job_id)
    background_tasks.add_task(_process, job_id, input_path, JOBS[job_id]["sourceType"], lyrics.strip(), JOBS[job_id]["separator"])
    return JOBS[job_id]


def _completed_job(job_id: str) -> dict[str, Any]:
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "작업을 찾을 수 없습니다.")
    if job.get("status") != "complete" or not job.get("result"):
        raise HTTPException(409, "분석이 완료된 작업에서만 사용할 수 있습니다.")
    return job


@app.post("/jobs/{job_id}/karaoke")
def save_karaoke(
    job_id: str,
    notes: list[dict[str, Any]] = Body(..., embed=True),
    octave: str = Body("auto", embed=True),
) -> dict[str, Any]:
    """Editor → regenerate karaoke.mid / karaoke.musicxml from the edited notes.
    `octave`: auto | up (written an octave above the sounding pitch, 8vb) | actual."""
    job = _completed_job(job_id)
    if len(notes) > 3000:
        raise HTTPException(413, "음표가 너무 많습니다.")
    if octave not in {"auto", "up", "actual"}:
        raise HTTPException(422, "octave는 auto, up, actual 중 하나여야 합니다.")
    try:
        cleaned = rebuild_karaoke(WORKSPACE / job_id, notes, job["result"], octave)
    except (KeyError, TypeError, ValueError) as error:
        raise HTTPException(422, f"음표 형식이 잘못되었습니다: {error}") from error
    except RuntimeError as error:
        raise HTTPException(422, str(error)) from error
    with LOCK:
        result = JOBS[job_id]["result"]
        result["karaokeNotes"] = cleaned
        result["karaokeVersion"] = int(result.get("karaokeVersion", 1)) + 1
        result.setdefault("karaoke", {})["karaokeNotes"] = len(cleaned)
        result["karaoke"]["notesWithLyric"] = sum(1 for item in cleaned if item.get("lyric"))
        result["karaoke"]["edited"] = True
        _save_job(job_id)
    return JOBS[job_id]


@app.post("/jobs/{job_id}/lyrics")
def update_lyrics(job_id: str, text: str = Body(..., embed=True)) -> dict[str, Any]:
    """Corrected lyrics → forced alignment again → karaoke melody rebuilt from the source notes."""
    import torch

    job = _completed_job(job_id)
    if len(text) > 20000:
        raise HTTPException(413, "가사는 20,000자 이하로 입력해주세요.")
    device = "cuda" if torch.cuda.is_available() else "cpu"
    try:
        karaoke_notes, stats, lyrics_info = realign_lyrics(WORKSPACE / job_id, text, job["result"], device)
    except RuntimeError as error:
        raise HTTPException(422, str(error)) from error
    with LOCK:
        result = JOBS[job_id]["result"]
        result["karaokeNotes"] = karaoke_notes
        result["karaoke"] = stats
        result["lyrics"] = lyrics_info
        result["karaokeVersion"] = int(result.get("karaokeVersion", 1)) + 1
        _save_job(job_id)
    return JOBS[job_id]


@app.get("/jobs/{job_id}")
def get_job(job_id: str) -> dict[str, Any]:
    job = JOBS.get(job_id)
    if not job:
        raise HTTPException(404, "작업을 찾을 수 없습니다.")
    return job


def _flag(value: str, default: bool = True) -> bool:
    text = (value or "").strip().lower()
    if not text:
        return default
    return text in {"1", "true", "yes", "on"}


@app.post("/jobs/{job_id}/compare", status_code=202)
async def compare_performance(
    job_id: str,
    background_tasks: BackgroundTasks,
    audio: UploadFile = File(...),
    sourceType: str = Form("mix"),
    separator: str = Form(""),
    allowTranspose: str = Form("true"),
    octaveTolerant: str = Form("true"),
) -> dict[str, Any]:
    """Upload a sung performance of the analysed original (`job_id`). The recording is analysed with the same
    pipeline (vocal separation unless sourceType=vocal, VAD, CREPE/pYIN) and then scored against the original."""
    reference = JOBS.get(job_id)
    if not reference:
        raise HTTPException(404, "원곡 분석 작업을 찾을 수 없습니다.")
    if reference.get("status") != "complete" or not reference.get("result"):
        raise HTTPException(409, "원곡 분석이 완료된 뒤에 비교할 수 있습니다.")
    performance_id = str(uuid.uuid4())
    job_dir = WORKSPACE / performance_id
    input_path = await _receive_upload(audio, job_dir)
    options = {"allowTranspose": _flag(allowTranspose), "octaveTolerant": _flag(octaveTolerant)}
    JOBS[performance_id] = {
        "id": performance_id,
        "kind": "performance",
        "referenceJobId": job_id,
        "referenceFilename": reference.get("filename"),
        "filename": audio.filename,
        "createdAt": time.time(),
        "sourceType": "vocal" if sourceType == "vocal" else "mix",
        "separator": normalize_backend(separator),
        "compareOptions": options,
        "hasLyrics": False,
        "status": "queued",
        "progress": 0,
        "message": "부른 노래 분석 대기열에 추가되었습니다.",
        "result": None,
        "error": None,
    }
    _save_job(performance_id)
    background_tasks.add_task(
        _process, performance_id, input_path, JOBS[performance_id]["sourceType"], "", JOBS[performance_id]["separator"],
        job_id, options,
    )
    return JOBS[performance_id]


@app.post("/jobs/{job_id}/comparison")
def recompute_comparison(job_id: str, payload: dict[str, Any] = Body(default={})) -> dict[str, Any]:
    """Re-score an already analysed performance with different options (no audio processing)."""
    job = _completed_job(job_id)
    reference_id = job.get("referenceJobId")
    if not reference_id or reference_id not in JOBS:
        raise HTTPException(409, "이 작업은 부른 노래 비교 작업이 아닙니다.")
    previous = job.get("compareOptions") or {}
    options = {
        "allowTranspose": bool(payload.get("allowTranspose", previous.get("allowTranspose", True))),
        "octaveTolerant": bool(payload.get("octaveTolerant", previous.get("octaveTolerant", True))),
    }
    try:
        comparison = compare_jobs(WORKSPACE / reference_id, WORKSPACE / job_id, options["allowTranspose"], options["octaveTolerant"])
    except RuntimeError as error:
        raise HTTPException(422, str(error)) from error
    with LOCK:
        JOBS[job_id]["result"]["comparison"] = comparison
        JOBS[job_id]["compareOptions"] = options
        _save_job(job_id)
    return JOBS[job_id]


@app.post("/jobs/{job_id}/instrumental-key")
def prepare_instrumental_key(job_id: str, payload: dict[str, Any] = Body(default={})) -> dict[str, Any]:
    """Render (and cache) the accompaniment shifted by N semitones — karaoke-style key change."""
    if job_id not in JOBS:
        raise HTTPException(404, "작업을 찾을 수 없습니다.")
    job_dir = WORKSPACE / job_id
    source = job_dir / "instrumental.wav"
    compacted = not source.exists() and (job_dir / "instrumental.opus").exists()
    if not source.exists() and not compacted:
        raise HTTPException(404, "반주 파일이 아직 준비되지 않았습니다.")
    requested = int(payload.get("semitones", 0) or 0)
    semitones = clamp_semitones(requested)
    if semitones != requested:
        raise HTTPException(422, f"키 조절 범위는 {MIN_SEMITONES:+d} ~ {MAX_SEMITONES:+d} 반음입니다.")
    started = time.perf_counter()
    try:
        rendered = key_shifted_opus(job_dir, semitones) if compacted else pitch_shift_file(source, semitones)
    except Exception as error:
        raise HTTPException(500, f"키 변경 렌더링에 실패했습니다: {error}") from error
    return {
        "semitones": semitones,
        "file": rendered.name,
        "url": f"/jobs/{job_id}/files/instrumental?semitones={semitones}",
        "seconds": round(time.perf_counter() - started, 1),
        "range": [MIN_SEMITONES, MAX_SEMITONES],
    }


def _regenerate_musicxml(job_id: str, kind: str, target: Path) -> None:
    """Compacted jobs keep only the MIDI files; rebuild the MusicXML from MIDI + notes when it is requested."""
    result = (JOBS.get(job_id) or {}).get("result") or {}
    shift = int((result.get("scoreOctave") or {}).get("shift") or 0)
    job_dir = WORKSPACE / job_id
    if kind == "musicxml":
        midi = job_dir / "vocal.mid"
        if midi.exists():
            _create_musicxml(midi, target, octave_shift=shift)
    else:
        midi = job_dir / "karaoke.mid"
        notes = sorted(result.get("karaokeNotes") or [], key=lambda item: item.get("scoreStartBeat", 0))
        if midi.exists():
            _create_musicxml(midi, target, [item.get("lyric") for item in notes] or None, title="Karaoke Melody", octave_shift=shift)


@app.get("/jobs/{job_id}/files/{kind}")
def get_file(job_id: str, kind: str, semitones: int = 0):
    filenames = {
        "vocals": ("vocals.wav", "audio/wav"),
        "instrumental": ("instrumental.wav", "audio/wav"),
        "midi": ("vocal.mid", "audio/midi"),
        "musicxml": ("vocal.musicxml", "application/vnd.recordare.musicxml+xml"),
        "karaoke-midi": ("karaoke.mid", "audio/midi"),
        "karaoke-musicxml": ("karaoke.musicxml", "application/vnd.recordare.musicxml+xml"),
        "pitch-frames": ("pitch_frames.npz", "application/octet-stream"),
    }
    if kind not in filenames:
        raise HTTPException(404, "지원하지 않는 결과 파일입니다.")
    filename, media_type = filenames[kind]
    job_dir = WORKSPACE / job_id
    path = job_dir / filename
    # 저장 정책이 적용된 작업: 반주는 Opus, MusicXML은 MIDI + 음표에서 즉석 생성, 보컬 스템은 없음
    if kind == "instrumental" and not path.exists() and (job_dir / "instrumental.opus").exists():
        if semitones != 0 and clamp_semitones(semitones) != semitones:
            raise HTTPException(422, f"키 조절 범위는 {MIN_SEMITONES:+d} ~ {MAX_SEMITONES:+d} 반음입니다.")
        opus = key_shifted_opus(job_dir, semitones)
        return FileResponse(opus, media_type="audio/ogg", filename=opus.name)
    if kind in ("musicxml", "karaoke-musicxml") and not path.exists():
        _regenerate_musicxml(job_id, kind, path)
    if kind == "vocals" and not path.exists() and (job_dir / "instrumental.opus").exists():
        raise HTTPException(404, "보컬 스템은 저장 정책으로 보관하지 않습니다. 필요하면 원곡을 다시 분석하세요.")
    if not path.exists():
        raise HTTPException(404, "결과 파일이 아직 준비되지 않았습니다.")
    if kind == "instrumental" and semitones != 0:
        if clamp_semitones(semitones) != semitones:
            raise HTTPException(422, f"키 조절 범위는 {MIN_SEMITONES:+d} ~ {MAX_SEMITONES:+d} 반음입니다.")
        path = pitch_shift_file(path, semitones)  # cached after the first render
        filename = shifted_name("instrumental", semitones)
    return FileResponse(path, media_type=media_type, filename=filename)
