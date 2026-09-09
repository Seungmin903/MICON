# VocalScope 배치 추출기 — 설치 요구사항 (2026-09-03)

다른 PC에서 `vocalscope-batch-extractor-2026-09-03.zip`을 풀고 890곡 배치를 돌리기 위해 **설치해야 하는 것**의 전체 목록입니다. 절차는 `docs/BATCH_HANDOFF.md`를 따르세요. 이 문서는 "무엇을 깔아야 하는가"만 답합니다.

## A. 시스템 (수동 설치)

| # | 항목 | 버전 | 설치 방법 | 확인 명령 | 필수 |
|---|---|---|---|---|---|
| 1 | Windows | 10 / 11 (64-bit) | — | `winver` | 필수 |
| 2 | NVIDIA GPU 드라이버 | CUDA 13 지원(드라이버 ≥ 580) 권장, 구버전이면 CUDA 12.8/12.6으로 자동 폴백 | GeForce Experience / nvidia.com | `nvidia-smi` | 필수(GPU 6 GB↑) |
| 3 | uv (Python 관리자) | 최신 | `winget install astral-sh.uv` | `uv --version` | 필수 |
| 4 | Python 3.11 | 3.11.x | **uv가 자동 다운로드**(`setup.ps1`) — 따로 설치 불필요 | `.venv-model\Scripts\python.exe --version` | 자동 |
| 5 | Git | 최신 | `winget install Git.Git` | `git --version` | 권장(clone/푸시용; zip만 쓰면 불필요) |
| 6 | MySQL Server | 8.4 (8.0 이상) | `winget install Oracle.MySQL` | `mysql --version` | 선택(DB 적재 단계만) |
| 7 | ffmpeg | — | **불필요** — `imageio-ffmpeg` 패키지에 실행 파일 번들 | — | 자동 |
| 8 | Node.js | — | **불필요**(웹 프론트는 이 패키지 범위 밖) | — | 불필요 |

PowerShell 실행 정책: 스크립트는 `powershell -ExecutionPolicy Bypass -File ...`로 실행하므로 정책 변경 불필요.

## B. Python 패키지 (자동 설치)

`model-server\setup.ps1` 한 번 실행 → `.venv-model` 생성 후 `model-server\requirements.txt`(아래) 설치 → GPU가 있으면 같은 torch 버전을 CUDA 인덱스에서 재설치.

```
demucs==4.1.0                 # HTDemucs 폴백 분리기
melband-roformer-infer==0.1.5 # Mel-Band RoFormer 분리기(기본)
torch==2.13.0                 # GPU: setup.ps1이 cu130 → cu128 → cu126 순으로 재설치
torchaudio==2.11.0
torchcrepe==0.0.24            # CREPE full F0 (가중치 85 MB는 패키지에 포함)
transformers==5.16.1          # wav2vec2 한국어 강제정렬
openai-whisper==20250625      # 카탈로그 가사 주입 시 사용되지 않음(설치만)
librosa==0.11.0
numpy==1.26.4
scipy==1.17.1
soundfile==0.14.0
pretty_midi==0.2.11.post0
mido==1.3.3
music21==9.7.1
pedalboard==0.9.24            # Rubber Band 키 시프트
ml_collections==1.1.0
PyYAML==6.0.3
imageio-ffmpeg==0.6.0         # ffmpeg 바이너리
fastapi==0.116.1              # 엔진 API(배치만 돌리면 미사용)
uvicorn[standard]==0.35.0
python-multipart==0.0.20
```

수동 설치가 필요하면: `uv venv --python 3.11 .venv-model` → `uv pip install --python .venv-model\Scripts\python.exe -r model-server\requirements.txt` → `uv pip install --python .venv-model\Scripts\python.exe --reinstall torch==2.13.0 --index-url https://download.pytorch.org/whl/cu130`.

## C. 모델 가중치 (첫 실행 시 자동 다운로드, 인터넷 필요)

| 모델 | 크기 | 저장 위치 | 용도 |
|---|---|---|---|
| Mel-Band RoFormer Kim vocals (MIT) | 871 MB | `%USERPROFILE%\.cache\melband-roformer-infer\melband-roformer-kim-vocals\` | 보컬/반주 분리(기본) |
| HTDemucs (demucs 4.1) | 81 MB | `%USERPROFILE%\.cache\huggingface\hub\models--adefossez--HTDemucs\` | 분리 폴백(OOM 시) |
| `kresnik/wav2vec2-large-xlsr-korean` | 1.2 GB | `%USERPROFILE%\.cache\huggingface\hub\models--kresnik--wav2vec2-large-xlsr-korean\` | 가사 음절 강제정렬 |
| CREPE full | 85 MB | torchcrepe 패키지 내부 | F0 추정 |
| Whisper large-v3-turbo | 1.6 GB | `%USERPROFILE%\.cache\whisper\` | **다운로드 안 됨**(카탈로그 가사 사용) |

합계 약 **2.2 GB**. 오프라인 PC라면 원래 PC에서 `model-server\export-models.ps1 -Out <폴더>` → 새 PC에서 `model-server\install-models.ps1 -Bundle <폴더>`.

## D. 데이터 (압축 파일에 포함 / 별도 복사)

| 항목 | 위치 | 포함 여부 |
|---|---|---|
| 카탈로그 메타데이터·가사 v6 | `data\enriched_all_final_languages_v6_lyrics_normalized.jsonl` (≈17 MB) | zip 포함 |
| 890곡 목록(경로는 원 PC 기준, `--audio-root`로 재해석) | `vocal-scope\model-server\batch\all890.json` | zip 포함 |
| 50곡 시범 + 스모크 1곡 분석 결과 | `vocal-scope\model-server\workspace\tj-*\` (≈0.3 GB) | zip 포함(재분석 자동 스킵) |
| MySQL 덤프(890곡 + 50곡 분석, 09-02) | `data\micon_catalog.mysql.sql` (31 MB) | zip 포함 |
| **음원 890곡 mp3** | `노래 모음\audio\<아티스트>\<track_id>.mp3` (5 GB) | **별도 복사 필수**(외장/네트워크) |

## E. 디스크·시간

- 디스크: venv ≈ 6 GB(torch CUDA 포함) + 모델 2.2 GB + 음원 5 GB + 결과 7 GB + 작업 중 임시 WAV 최대 320 MB/곡 → **약 20 GB 여유** 권장.
- 시간: RTX 4050(6 GB) 기준 곡당 83 s 중앙값 → 890곡 ≈ 21–24 h. 첫 실행은 모델 다운로드 수 분 추가.
