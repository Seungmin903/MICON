# VocalScope 배치 추출기 인수 문서 (2026-09-03)

곡 음색 임베딩 인수는 [S3 임베딩 계약](../../docs/operations/EMBEDDING_CATALOG.md)을 사용한다.
ECAPA·Singer-BYOL NPY와 segments.json을 정확한 `song_metadata.catalog_track_id`로 묶고,
전용 prepare/upload → 인프라 register/activate → AI cache refresh 순으로 전달한다.
아래의 로컬 SQLite/분석 덤프 절차로 운영 임베딩 테이블을 생성하거나 CSV 행 순서로 곡 ID를 매칭하지 않는다.

다른 PC에서 **TJ 카탈로그 890곡의 음원 → 메타데이터(조성·BPM·박자·음역·성구·음표·가사 정렬) → MySQL** 을 돌리기 위한 문서입니다. 웹 프론트(:3000)는 필요 없습니다. 웹앱까지 포함한 전체 설치는 루트 `PIPELINE.md` §2, 알고리즘은 `docs/MODEL_PIPELINE.md`, 출력 필드 전수 목록은 `docs/DATA_INVENTORY.md`, DB 스키마는 `docs/micon_schema.sql`, 화면↔데이터 계획은 `docs/MICON_DATA_PLAN.md`를 보세요.

## 0. 한눈에

| 항목 | 값 |
|---|---|
| 코드 | 팀 GitLab `S15P21B110` 브랜치 `feat/vocalScope`, 폴더 `vocal-scope/` (로컬 작업 트리와 동일, 2026-09-03 확인) |
| 입력 | `노래 모음\audio\<아티스트>\<track_id>.mp3` 890곡(5 GB) + `metadata_enrichment\results\enriched_all_final_languages_v6_lyrics_normalized.jsonl`(가사 889곡) |
| 출력(곡당) | `model-server\workspace\tj-<번호>\` = `job.json`(≈300 KB) · `pitch_frames.npz`(≈170 KB) · `vocal.mid` · `karaoke.mid` · `instrumental.opus`(≈4–6 MB) → **곡당 ≈ 5–8 MB, 890곡 ≈ 6–7 GB** |
| 적재 | `build_catalog_db.py` → SQLite(개발) + MySQL INSERT 덤프 → `songs`·`song_analysis`·`song_notes`·`song_artifacts` |
| 시간 | RTX 4050(6 GB): 곡당 중앙값 83 s(44–163 s) → 890곡 ≈ **21–24 h**(50곡 시범 2026-09-02 실측) |
| 모델 다운로드 | 첫 실행 시 자동 ≈ **2.2 GB**(Mel-Band 871 MB · HTDemucs 81 MB · wav2vec2-ko 1.2 GB). Whisper(1.6 GB)는 카탈로그 가사를 넣으면 **로드되지 않음** |

## 1. 사전 조건 (Windows 10/11)

- **NVIDIA GPU 6 GB 이상** + 최신 드라이버(`nvidia-smi` 동작). CPU만으로도 돌지만 분리 단계가 곡당 수 분.
- [uv](https://docs.astral.sh/uv/) (`winget install astral-sh.uv`), Git. Python은 uv가 3.11을 받아옵니다. Node.js는 **불필요**(웹 안 씀).
- ffmpeg는 `imageio-ffmpeg` 패키지에 번들 → 별도 설치 불필요.
- MySQL 8.x(선택, 적재 단계에서만). 스키마는 `docs/micon_schema.sql`.
- 디스크: 모델 캐시 2.2 GB + 음원 5 GB + 결과 7 GB + 작업 중 임시 WAV(곡당 최대 320 MB, 완료 즉시 삭제).

## 2. 설치

```powershell
git clone <GitLab S15P21B110 URL>
cd S15P21B110
git checkout feat/vocalScope
cd vocal-scope
powershell -ExecutionPolicy Bypass -File model-server\setup.ps1          # .venv-model 생성 + requirements + GPU torch
# 드라이버가 CUDA 13을 지원하지 않으면: -CudaTag cu128  (자동으로 cu130 → cu128 → cu126 순서로 재시도함)
# GPU 없음: -CudaTag cpu
```

설치 확인:

```powershell
.venv-model\Scripts\python.exe -c "import torch, torchcrepe, mel_band_roformer, transformers; print(torch.__version__, torch.cuda.is_available())"
```

`True`가 나와야 GPU 경로입니다. 이후 **모든 파이썬 실행은 `.venv-model\Scripts\python.exe`** 로 합니다(시스템 python엔 torch 없음).

## 3. 데이터 배치

git 밖에서 복사해 오는 것(외장/네트워크):

| 파일 | 크기 | 용도 |
|---|---|---|
| `노래 모음\audio\` (890 mp3) | 5 GB | 분석 입력 |
| `노래 모음\metadata_enrichment\results\enriched_all_final_languages_v6_lyrics_normalized.jsonl` | ≈40 MB | 제목·가수·장르·**가사**(Whisper 생략 근거)·조인 키 `track_id` |
| (선택) `model-server\workspace\tj-*\` 50곡 시범 결과 | 0.3 GB | 복사해 두면 그 50곡은 재분석 생략(`status=complete` 자동 스킵) |
| (선택) `outputs\micon_catalog.mysql.sql` | 31 MB | 890곡 + 50곡 분석이 들어간 MySQL 덤프(09-02) |

경로는 아무 데나 두어도 됩니다. **JSONL 안의 `source_asset.local_path`는 원래 PC의 절대경로**라 그대로 쓰지 않고, 아래 도구들이 `--audio-root`에서 파일을 다시 찾습니다(`catalog_paths.py`: ① 목록의 `path` ② `<audio-root>/<아티스트>/<track_id>.mp3` ③ TJ 번호 색인 — 파일명에 차트 순위 접두가 붙은 100곡(`TJ001-13588`)도 ③으로 잡힘).

## 4. 실행 순서

작업 디렉터리는 `vocal-scope\model-server\`. 콘솔이 cp949면 Mel-Band 패키지가 이모지를 출력하다 죽으므로 `PYTHONIOENCODING`을 꼭 설정하고 stdout을 파일로 보냅니다. **엔진(uvicorn 8787)과 동시에 돌리지 마세요**(GPU 공유).

```powershell
cd model-server
$env:PYTHONIOENCODING = 'utf-8'; $env:PYTHONUTF8 = '1'
$V6    = 'D:\노래 모음\metadata_enrichment\results\enriched_all_final_languages_v6_lyrics_normalized.jsonl'
$AUDIO = 'D:\노래 모음\audio'
$PY    = '..\.venv-model\Scripts\python.exe'
```

### 4-1. 곡 목록 만들기

```powershell
& $PY make_song_list.py --catalog $V6 --audio-root $AUDIO --out batch\all890.json
# 기대: catalog=890 selected=890 missing_audio=0 index=890 files
```

필터 예: `--language kor --solo --with-lyrics --round-robin-genre --limit 50`(시범 50곡 방식), `--genre Ballad --genre Trot`. 음원이 없는 곡은 stderr에 `MISSING AUDIO`로 나오고 목록에서 빠집니다.

### 4-2. 1곡 스모크

```powershell
& $PY batch_analyze.py --songs batch\all890.json --catalog $V6 --audio-root $AUDIO --limit 1 --force --run-name smoke > ..\..\smoke.log 2>&1
Get-Content ..\..\smoke.log -Tail 3
```

첫 실행은 모델 다운로드(2.2 GB)가 포함되어 수 분 더 걸립니다. 확인할 것:

- 마지막 줄 `done=1 failed=0`, 곡 줄에 `key=... bpm=... range=... notes=... align=...` 값이 채워짐.
- `workspace\tj-<번호>\`에 `job.json`·`pitch_frames.npz`·`vocal.mid`·`karaoke.mid`·`instrumental.opus` **5개만** 있고 폴더가 10 MB 이하(`result.compact.policy`가 job.json에 기록됨).
- `job.json`의 `result.lyrics.source`가 `"user"`(카탈로그 가사 주입 = Whisper 생략), `result.separation.backend`가 `"melband"`, `result.device`가 `"cuda"`.
- 소요 시간이 4분 곡 기준 ≈ 60–100 s. 300 s를 넘으면 CPU로 돌고 있는지(`device`) 확인.

### 4-3. 890곡 본 배치

```powershell
& $PY batch_analyze.py --songs batch\all890.json --catalog $V6 --audio-root $AUDIO --run-name all890 > ..\..\all890.log 2>&1
```

- 진행 상황: `Get-Content ..\..\all890.log -Tail 5 -Wait`. 곡별 요약은 `workspace\_batch\all890.jsonl`(한 줄 = 한 곡: 시간·키·BPM·음역·정렬 점수·단계별 timings·오류).
- **중단·재개**: 그냥 같은 명령을 다시 실행하면 `status=complete`인 곡은 자동 스킵됩니다. 특정 곡만 다시: `--force --start <index> --limit 1`.
- 실패 곡: `job.json`의 `status=failed` + 같은 폴더의 `traceback.txt`. 배치는 한 곡이 실패해도 계속 돌고, 끝에 `failed=N`이면 종료 코드 1.
- 절전·잠금 해제: 전원 옵션에서 절전 끄기(밤새 돌릴 때). 화면 잠금은 무관.
- 처리량 참고(50곡 시범): 분리 43 s · CREPE 19 s · pYIN 11 s · Viterbi 3 s · 성구 2 s · 정렬 ≈10 s. 6분 넘는 곡은 160 s까지.

### 4-4. 품질 요약 보기

```powershell
& $PY -c "import json,statistics as st; rows=[json.loads(l) for l in open('workspace/_batch/all890.jsonl',encoding='utf-8')]; ok=[r for r in rows if r['status']=='complete']; print(len(ok),'ok',len(rows)-len(ok),'failed'); print('sec median',st.median(r['seconds'] for r in ok)); print('f0 agreement median',st.median(r['f0Agreement'] for r in ok if r['f0Agreement'] is not None)); print('align median',st.median(r['alignmentScore'] for r in ok if r['alignmentScore'] is not None))"
```

시범 50곡 기준값: f0 합의 0.86, 옥타브 불일치 < 0.05, 가사 정렬 0.21(낮음 — 청취 검증 전이라 `lyricStatus`로만 구분). `f0Agreement < 0.6`이거나 음역이 4옥타브를 넘는 곡(예: tj-38028 F2–G♯6)은 의심 목록에 적어 둡니다.

### 4-5. 개발용 DB 산출물 생성 및 로컬 적재

아래 명령은 개발용 빈 DB에서 검토할 로컬 예시이며 SSAFY 운영 DB에 직접 실행하는 절차가 아닙니다. 운영 앱 계정으로 root를 사용하지 않습니다. 기존 스키마/데이터에 재실행하기 전 변경 범위를 확인합니다.

```powershell
& $PY build_catalog_db.py --catalog $V6 --audio-root $AUDIO --out ..\..\outputs\micon_catalog.sqlite --mysql-dump ..\..\outputs\micon_catalog.mysql.sql
# 기대: songs=890 analysis_rows=890 (complete=N) ... ; 'warning: N songs have no audio file' 가 뜨면 --audio-root 확인
mysql -u root -p -e "CREATE DATABASE IF NOT EXISTS micon CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci"
mysql -u root -p micon < ..\docs\micon_schema.sql
mysql -u root -p micon < ..\..\outputs\micon_catalog.mysql.sql
```

재실행 가능(`INSERT … ON DUPLICATE KEY UPDATE`). `songs.audio_path`·`song_artifacts.path`에는 **이 PC의 절대경로**가 들어가므로, 앱 서버가 다른 곳이면 opus 파일과 함께 경로 치환이 필요합니다(오디오는 DB 밖).

### 4-6. SSAFY 서버 + 개인 S3 운영 인수 (2026-09-09)

1. [공통 S3 운영 계약](../../docs/operations/S3_VOICE_STORAGE.md)의 실제 버킷·서울 리전을 확인하고 별도 업로더 인증을 인프라에게 받습니다. 2026-09-09 버킷·BE 서버 인증과 CLI 연결은 검증했지만 팀원 catalog 업로더는 아직 발급하지 않았습니다. BE 녹음용 키나 루트 키를 공유하지 않습니다.
2. 업로드 권한·음원/가사 이용 권리·민감정보를 확인한 산출물만 전용 폴더에 모읍니다. workspace 전체·캐시·키·DB 덤프를 일괄 업로드하지 않습니다. 공통 문서의 sync dry-run으로 신규 `catalog/<batch-id>/` 대상·용량을 확인한 뒤 업로드합니다.
3. 곡 ID↔object key·크기·체크섬·데이터 버전 manifest와 다운로드 표본을 검증합니다. 실제 앱/AI가 사용할 Opus·npz·JSON 등 파일 목록은 담당자와 맞춥니다.
4. `songs.audio_path`·`song_artifacts.path`의 PC 절대경로를 운영 object key/파일 참조로 매핑합니다. 현재 적재기에 S3 경로 변환을 구현했다는 뜻이 아닙니다. `s3://...` 문자열이나 만료 URL을 기존 로컬 파일 API에 넣기만 하면 스트리밍된다고 가정하지 않습니다.
5. BE/DB 담당자가 실제 Flyway 스키마와 덤프의 호환성을 검토하고 승인된 전용 적재 절차로 SSAFY MySQL에 반영합니다. 파일 업로드와 DB import는 별도 완료 조건입니다. 운영 DB 포트를 공개하거나 위 root import를 그대로 실행하지 않습니다.
6. 음원 제공 주체·catalog 읽기 권한·인증된 다운로드/스트리밍 경로를 구현·검증한 후 곡 목록·연습 재생·AI 입력을 통합 확인합니다. 현재 BE 녹음 권한에는 catalog/ 접근이 없으며 이 문서가 자동 적재/배포 완료를 뜻하지 않습니다.

## 5. 산출물 구조

```
workspace/
  tj-14260/                 ← job id = tj-<TJ 번호> (= songs.song_id)
    job.json                ← result{} 전체: key, tempoBpm, timeSignature, lowest/highest, coreRange, register{}, notes[], karaokeNotes[], lyrics{}, separation{}, timings{}, compact{}
    pitch_frames.npz        ← 10 ms F0(midi, voiced, confidence, dbfs) — 채점·안정 음역 계산용
    vocal.mid  karaoke.mid  ← 채보 MIDI(원본/노래방 단순화), MusicXML은 엔진이 요청 시 재생성
    instrumental.opus       ← 반주 128 kbps VBR (키 변경 반주는 엔진이 instrumental_key±N.opus로 캐시)
  _batch/all890.jsonl       ← 곡별 요약 로그
```

필드 정의·단위는 `docs/DATA_INVENTORY.md` §2(🟢 출력), §4(🟡 계산되나 버려지는 값 16종), §5(⚪ 미구현). 같은 문서 §6에 문서↔코드 불일치 목록이 있습니다.

## 6. 필드를 추가하려면 (옮긴 PC에서 필드 확정 시)

890곡 재실행이 24 h이므로 **본 배치 전에** 추가 필드를 정해 넣는 것이 좋습니다. 한 필드를 추가할 때 손대는 곳:

1. **계산·출력** — 값이 이미 계산되는 🟡 항목이면 해당 엔진(`pitch_engine.py`/`register_engine.py`/`engine.py`)에서 `result`에 키만 추가. 단위·타입을 `DATA_INVENTORY.md` §2 표에 같은 이름으로 기록.
2. **배치 요약** — `batch_analyze.summarize()`에 한 줄(jsonl 로그로 분포를 바로 볼 수 있게).
3. **DB** — `docs/micon_schema.sql`의 `song_analysis`에 컬럼(스칼라) 또는 기존 JSON 컬럼에 키, `build_catalog_db.py`의 INSERT 두 곳(SQLite DDL + MySQL 덤프)에 매핑.
4. 1곡 `--force`로 확인 후 배치.

npz에만 있는 값(`dbfs`, 반음별 안정도, 유성 구간 목록)은 배치를 다시 돌리지 않고 npz 후처리 스크립트로도 뽑을 수 있습니다.

## 7. 자주 걸리는 문제

| 증상 | 원인·조치 |
|---|---|
| `UnicodeEncodeError: 'cp949' codec` | `$env:PYTHONIOENCODING='utf-8'; $env:PYTHONUTF8='1'` 후 stdout을 파일로 리다이렉트 |
| `MISSING FILE ...` | `--audio-root`가 `audio` 폴더(아티스트 폴더들의 부모)를 가리키는지 확인 |
| `torch.cuda.is_available()` False | 드라이버 구버전 → `setup.ps1 -CudaTag cu128`(또는 cu126) 재실행 |
| CUDA OOM | 분리기가 청크를 절반으로 줄여 재시도 → 그래도 실패 시 HTDemucs로 자동 폴백(`separation.fallbackReason` 기록). 다른 GPU 프로세스(엔진 8787, 브라우저 HW 가속) 종료 |
| 곡당 300 s 이상 | CPU로 돌고 있음(`result.device`) 또는 GPU 공유 중 |
| Mel-Band 모델 다운로드 실패 | 재실행하면 이어받음. 오프라인 PC는 원래 PC에서 `export-models.ps1 -Out <폴더>` → 새 PC `install-models.ps1 -Bundle <폴더>` (Whisper 파일은 복사 안 해도 됨) |
| 가사 정렬 `alignmentScore` 매우 낮음(< 0.1) | 카탈로그 가사가 다른 버전(라이브·리믹스)일 가능성 → `karaokeNotes[].lyricStatus`로 걸러 쓰고 의심 목록에 기록 |

## 8. 이 PC에서 확인한 것 (2026-09-03)

- `make_song_list.py` → 890/890 경로 해석(순위 접두 100곡 포함), `missing_audio=0`.
- `build_catalog_db.py --audio-root` → songs 890 · song_analysis 50 · `audio_path` 존재 890/890, 옛 경로 접두 0.
- 새 venv에서 `requirements.txt` 해석(dry-run) 103 패키지 충돌 없음.
- 1곡 스모크(`path`를 일부러 틀린 목록 + `--audio-root`): 비와이 〈가라사대〉(tj-91802, 2분 13초) → `--audio-root`로 파일 해석 성공, **82 s**, `done=1 failed=0`. 폴더 2.9 MB(파일 5개), `device=cuda`, `separation.backend=melband`, `lyrics.source=user`(Whisper 미로드), `compact` 적용. 결과: G major · 134 BPM · 4/4 · G2–F♯4 · 음표 284 · f0 합의 0.81 · 정렬 0.18.
