# VocalScope 인수인계 (2026-08-27)

다른 PC에서 이어서 작업하기 위한 문서입니다. 현재 상태, 새 PC 세팅 순서, 검증 방법, 남은 과제를 담았습니다. 모델·알고리즘 상세는 `docs/MODEL_PIPELINE.md`를 보세요.

2026-09-09 운영 인수 갱신: 아래는 로컬 PC 인수 기록입니다. 운영은 [SSAFY BE·MySQL + 개인 AWS S3](../docs/operations/S3_VOICE_STORAGE.md)를 사용합니다. 파일 업로드 권한과 BE 녹음 권한을 분리하고, 로컬 산출물 업로드·경로 매핑·DB 적재는 [배치 인수](docs/BATCH_HANDOFF.md)의 운영 절차를 따릅니다. 로컬 workspace 자동 이전이나 AI 서비스 배포를 완료한 것은 아닙니다. 기존 D1/R2 잔재를 운영 저장소로 채택하지 않습니다.

> **배치 추출기만 넘길 때(웹 없이 890곡 분석 → MySQL)는 `docs/BATCH_HANDOFF.md`(2026-09-03)를 보세요.** 이 문서는 웹앱 포함 전체 인수용입니다.


## 1. 지금까지 한 것 (커밋 순)

| 커밋 | 내용 |
|---|---|
| `b878ff8` | VocalScope MVP (Next.js/vinext 웹 + 로컬 FastAPI 모델 서버) |
| `03310cf` | 로컬 AI 파이프라인(HTDemucs + Basic Pitch) |
| `0e37d19` | OSMD 전체 악보 표시, 음표명 라벨 |
| `f3bf71e` | **Basic Pitch 제거 → VAD + CREPE full(세그먼트 Viterbi) + pYIN 교차검증 엔진**(`model-server/pitch_engine.py`), 평가 CLI(`evaluate.py`), 품질 지표 화면 |
| `73d772e` | **노래방 악보**: Whisper 받아쓰기(선택) + wav2vec2 한국어 CTC 음절 강제 정렬(`lyrics_engine.py`), 멜로디 단순화(`karaoke.py`), 피아노롤 검수 편집기(`app/karaoke-editor.tsx`), `POST /jobs/{id}/karaoke`, `/lyrics` |
| `6b6aa77` | 남성 음역 곡은 한 옥타브 위에 표기(8vb), 표기 옥타브 선택(auto/up/actual) |
| `438c17f` | 음표명 라벨 `3D` → `D3` 형식 |
| `83ea182` | `docs/MODEL_PIPELINE.md` 모델 파이프라인 문서 |
| (2026-08-28, 미커밋) | **보컬 분리 Mel-Band RoFormer 교체**(`model-server/separation.py`, htdemucs 폴백·업로드 화면 선택) + **성구(진성/가성) 분석 1단계**(`model-server/register_engine.py`, 결과 화면 REGISTER 패널·피아노롤 색상) + `evaluate.py --separator`/성구 지표 |

검증 결과(5곡: 이무진 신호등·뱁새·청춘만화, 아이유 LILAC·Celebrity): 보컬 시작 전 음표 0개, 두 경로 F0 합의 86–91 %, 옥타브 불일치 ≤ 1.7 %, 4분 곡 처리 90–150 s(가사 입력 시 70–100 s). 청춘만화는 공개 악보(원곡 G Key)와 첫 3마디 22음 중 21음 일치.

## 2. 새 PC 세팅 순서 (Windows 기준)

> 최신 통합 문서: **`PIPELINE.md`**(2026-08-31). 오프라인이면 모델 번들을 `model-server\install-models.ps1 -Bundle <models 폴더>`로 캐시에 설치(내보내기: `export-models.ps1`).

필요한 것: **NVIDIA GPU(6 GB 이상 권장)**, Node.js ≥ 22.13, Python 3.11, [uv](https://docs.astral.sh/uv/), Git. CPU만 있어도 동작하지만 곡당 수십 분 걸립니다.

```powershell
# 0) 압축 해제 후 프로젝트 폴더에서
git log --oneline -3            # 이력이 그대로 들어 있는지 확인

# 1) 웹 의존성
npm install

# 2) 파이썬 모델 환경 (.venv-model 생성 + requirements 설치 + GPU면 torch cu130 재설치)
powershell -ExecutionPolicy Bypass -File model-server\setup.ps1

# 3) 실행 (엔진 8787 + 웹 3000 기동 후 브라우저 오픈)
.\VocalScope` 실행.cmd
```

- 첫 분석 때 모델을 자동 다운로드합니다: **Mel-Band RoFormer 보컬(913 MB, `%USERPROFILE%\.cache\melband-roformer-infer`)**, HTDemucs(~80 MB, 폴백), CREPE full(~90 MB), **Whisper turbo(1.5 GB)**, **wav2vec2 한국어(1.3 GB)**. 인터넷이 필요하고 캐시는 `%USERPROFILE%\.cache\huggingface`, `%USERPROFILE%\.cache\whisper`, `%USERPROFILE%\.cache\torch`에 저장됩니다. Mel-Band 첫 실행은 디스크 캐시가 없어 분리가 200 s까지 걸릴 수 있고 이후 ≈ 42 s입니다.
- `setup.ps1`은 `uv`를 사용합니다. 없으면 `winget install astral-sh.uv` 또는 `pip install uv`.
- 시스템 Python으로 실행하면 torch가 없어 실패합니다. **항상 `.venv-model\Scripts\python.exe`** 를 쓰세요(`run-local.ps1`이 그렇게 되어 있음).

## 3. 개발 시 꼭 알아야 할 것

- **엔진은 `--reload`가 없습니다.** `model-server/*.py`를 고치면 8787 프로세스를 끝내고 다시 띄워야 반영됩니다. `stop-local.ps1` → `run-local.ps1` (또는 `VocalScope 실행.cmd`). 수동으로 띄운 경우 `.vocalscope-pids.json`이 없어 `stop-local.ps1`이 못 죽이니 `Get-NetTCPConnection -LocalPort 8787`로 PID를 찾아 부모 python.exe까지 종료하세요(uv venv는 트램펄린 + 인터프리터 두 프로세스).
- 웹(`npm run dev`, vinext)은 HMR이라 저장 즉시 반영됩니다. `npm run lint`, `npx tsc --noEmit -p tsconfig.json`이 0 error인 상태입니다.
- GPU 6 GB를 엔진과 `evaluate.py`가 공유하므로 **동시에 돌리지 마세요**.
- mido는 MIDI 텍스트를 latin-1로 저장해 한글 가사가 깨집니다 → `engine._insert_midi_lyrics`가 UTF-8로 재삽입합니다(건드릴 때 주의).
- `app/api/*`(Cloudflare D1/R2용)는 배포 잔재이며 현재 화면은 8787 엔진과 직접 통신합니다.
- **MR 키 조절(2026-08-28)**: `model-server/pitch_shift.py`(pedalboard `PitchShift`, 템포 유지) → `POST /jobs/{id}/instrumental-key {"semitones": ±1~6}` 후 `GET /jobs/{id}/files/instrumental?semitones=N`. 결과는 `instrumental_key±N.wav`로 캐시. UI는 `app/key-shift-player.tsx`. 렌더는 **torch가 없는 서브프로세스**에서 돌립니다 — torch가 import된 프로세스에서는 pedalboard가 5~15배 느려지고, 한 인스턴스에 긴 입력을 넣으면 초선형으로 느려져서 20 s 조각 + 크로스페이드로 처리합니다(4분 곡 ≈ 3 s). 자세한 이유는 파일 상단 docstring.

## 4. 검증 방법

```powershell
# 기존 작업 폴더의 stem 재사용(Demucs 생략, ~40 s) — 신호등 기준 첫 보컬 ≥ 21 s 단언
.venv-model\Scripts\python.exe model-server\evaluate.py --job model-server\workspace\<job-id> --expect-vocal-start-min 21

# 음원부터 전체(분리 포함) + 노래방 단계까지, 가사 파일 지정
.venv-model\Scripts\python.exe model-server\evaluate.py --audio "곡.mp3" --lyrics-file 가사.txt --out <출력 폴더>

# 폴더 일괄
.venv-model\Scripts\python.exe model-server\evaluate.py --folder "노래 폴더" --karaoke --out <출력 폴더>
```

합격 기준(신호등): `vocalStart` 21.5–22.3 s, 조기 음표 0, F0 합의 > 0.8, 옥타브 불일치 < 0.05, 엔진 < 90 s, MusicXML 1번 마디에 음표 존재, B major.

압축에는 저작권 문제로 테스트 음원(`노래 모음/`)과 `model-server/workspace/`(분석 결과물)를 넣지 않았습니다. 같은 곡으로 검증하려면 mp3를 따로 옮기세요.

## 4-b. 부른 노래 채점 (2026-08-31 추가)

- `model-server/compare_engine.py` — 원곡 job vs 사용자 녹음 job 비교(이조 탐색 → 서브시퀀스 DTW → 음표별 음정·박자 판정 → 점수/마디/문제 구간/차트 곡선). `pitch_engine.extract_vocal_melody(return_frames=True)`가 10 ms F0 곡선을 돌려주고 `engine.run_pipeline`이 `pitch_frames.npz`로 저장(`skip_lyrics=True`면 Whisper/정렬 생략).
- `app.py`: `GET /jobs`(완료된 원곡 목록), `POST /jobs/{id}/compare`(녹음 업로드 → 같은 파이프라인 → 비교, job `kind="performance"`), `POST /jobs/{id}/comparison`(옵션만 바꿔 재채점), 파일 `pitch-frames`.
- 웹: `app/performance-compare.tsx`(업로드·옵션·점수·피치 오버레이 캔버스·마디 히트맵·문제 구간·재생 동기화), `page.tsx`에 저장된 원곡 불러오기 select + 결과 패널 하단 카드.
- 검증: 합성 가창(이조·지연·디튠·무음 삽입)으로 정답 재현 확인 — 상세 `docs/MODEL_PIPELINE.md` [13]. **실제 사람 녹음으로는 아직 미검증**: 실사용 시 임계값(45¢/120 ms) 및 가중(0.65/0.35)은 `compare_engine.py` 상단 상수로 조정.
- 하드-won: librosa `dtw(subseq=True)`는 쿼리(부른 노래)가 원곡보다 길면 내부 전치 후 경로 (열,행)이 바뀐 채 반환됨 → 자체 `subsequence_dtw`로 교체. 실제 녹음(엔플라잉 Flashback 234 s, 원키) 1건 채점 성공: 80.7 B, 정렬 일치 78 %.
- 알려진 한계: 후렴 반복 곡에서 일부 구간만 부르면 다른 절에 정렬될 수 있음(가사 미사용). 랩·애드리브·화음은 원곡과 달라 감점. 옛 job(`pitch_frames.npz` 없음)은 음표 계단 곡선으로 대체 정렬.

## 4-c. 라이브 채점 (2026-08-31 추가)

- `public/pitch-worklet.js`(자체 YIN, Node 테스트 포함) + `app/live-scoring.tsx`(러너웨이 캔버스·실시간 점수·마디 히트맵·지연 자동 보정·정밀 리포트 핸드오프), `page.tsx`에 카드 연결, `performance-compare.tsx`에 `initialJob` prop. 서버는 `.webm` 허용만 변경.
- 판정 상수는 compare_engine과 동일하게 유지할 것(두 곳: `compare_engine.py` 상단, `live-scoring.tsx` 상단).
- 하드-won(08-31 수정): YIN 첫-딥 선택은 H2 우세 저음에서 정확히 옥타브 위(+1200¢)를 골랐음 → k·τ 더-깊은-딥 채택으로 해결. 64 ms 창의 소리 꺼짐 경계 프레임은 clarity 0.9로 유성 통과 → rmsLow(반창 RMS 최소) 게이트로 차단. 회귀 테스트는 pitch-worklet를 `new Function(src)()`로 로드해 Node에서 실행.
- **마이크·재생이 필요한 기능이라 브라우저 실사용 검증은 아직 안 됨** — 확인 순서: 마이크 켜기(레벨·음이름 미리보기) → 시작(3초 카운트다운, 전주 건너뛰기) → 러너웨이에 음표 색 판정·궤적 → 종료 → 정밀 리포트.

## 5. 남은 과제 / 아이디어

-1. **메타데이터 보관 + 원곡 자동 매칭(다음 단계)**: 분석 결과(`job.json` + `pitch_frames.npz`)를 곡 메타데이터 파일로 내보내 라이브러리에 쌓고, 사용자가 녹음만 올리면 `compare_engine.search_transposition`의 저해상도 DTW 비용을 모든 후보 곡에 대해 계산(또는 크로마 지문 사전 필터)해 원곡을 찾은 뒤 채점. `GET /jobs`가 그 라이브러리의 첫 형태.

0. **성구 판정 2단계(학습 모델)**: 현재 `register_engine.py`는 규칙 기반이며 사람 청취 라벨로 검증되지 않았습니다. 7곡 결과: 5곡 단일 성구(진성), 한로로는 두 성구 검출(단, 가성 구간 음높이가 진성보다 낮아 "숨소리 진성" 가능성 경고), YB 나는 나비는 악기 잔여음 프레임(H1−H2 > 20 dB) 1,392개가 제외됨. 다음 단계: (a) 테스트 곡에 가성 구간 손 라벨(초 단위) 작성 → `evaluate.py`에 프레임 P/R 추가, (b) GTSinger(falsetto·mixed·breathy 음소 라벨, 한국어 포함, CC BY-NC-SA) 기반 분류기를 Demucs/Mel-Band 분리 stem으로 도메인 증강해 학습, 같은 `notes[].register` 스키마로 출력. 참고 모델: STARS(arXiv 2507.06670).
0-b. **RMVPE 3번째 F0 경로**: 논문(arXiv 2306.15412) 기준 깨끗한 보컬에서는 CREPE와 동등(MDB-stem-synth RPA 97.1 vs 97.5)이라 보류. 분리 잔여음이 많은 곡에서 F0 합의율이 낮게 나오면 그때 `evaluate.py`에 비교 경로로 추가.
1. **업로드 시 가수 성부 선택**(남성곡 8vb / 여성곡 실음 / 자동): 음역만으로는 남녀 구분이 불가능(아이유 Celebrity가 남성곡으로 오판됨). 결과 화면의 "표기 옥타브" 선택기로는 이미 수동 교정 가능.
2. **조옮김 악보 출력**(원키 외 C키·남/여 키 ±n반음): 검출은 그대로 두고 표기·MIDI만 이동.
3. 가사 정확도는 사용자 입력이 전제. Whisper 받아쓰기는 오탈자 있음(정렬 신뢰도 0.2 전후).
4. 코드(화음) 인식, 곡 구조(전주/후렴) 표시, 혼성 듀엣 파트 분리는 미구현.
5. 매우 높은 이상치(예: 청춘만화 A5)는 코러스/분리 잔여음 가능 → 편집기에서 검수.

## 6. 폴더 구조 요약

```
vocal-scope/
├─ app/                    Next.js(vinext) 화면: page.tsx, karaoke-editor.tsx, globals.css
├─ model-server/
│   ├─ app.py              FastAPI: /health, /jobs, /jobs/{id}, /jobs/{id}/files/{kind}, /karaoke, /lyrics
│   ├─ engine.py           파이프라인 오케스트레이션, MIDI/MusicXML 생성, 옥타브 표기
│   ├─ pitch_engine.py     VAD + CREPE + Viterbi + pYIN + 분절 + 격자 + 조성
│   ├─ lyrics_engine.py    Whisper 받아쓰기, wav2vec2 CTC 강제 정렬
│   ├─ karaoke.py          노래방 기준 멜로디 단순화
│   ├─ evaluate.py         오프라인 평가 CLI
│   ├─ requirements.txt / setup.ps1 / start.ps1
│   └─ workspace/          (제외) 작업별 입력·stem·MIDI·MusicXML
├─ docs/MODEL_PIPELINE.md  모델 순서·설정·근거 문서
├─ run-local.ps1 / stop-local.ps1 / VocalScope 실행.cmd
└─ .venv-model/ node_modules/ (제외 — 새 PC에서 setup.ps1, npm install로 생성)
```
