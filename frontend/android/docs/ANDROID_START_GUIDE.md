# MICON 안드로이드 앱 작업 시작 가이드 — `Desktop\claude` 폴더에서 필요한 것 (2026-09-03)

Android Studio(또는 CLI)에서 실제 기능을 만들 때 이 폴더에서 **참고해야 할 파일**, **참고하지 않아도 되는 파일**, 그리고 **엔진 API·데이터 계약**을 한 장에 정리했습니다. 앱 코드는 이 폴더가 아니라 팀 저장소(`Desktop\특화PJT_git\S15P21B110`)에 만드는 것을 전제로 합니다.

> Figma 화면은 **참고용**이며 확정 디자인이 아닙니다(`vocal-scope/docs/FIGMA_PRACTICE_SCREENS.md` 상단 안내). 데이터가 없는 요소는 구현 가능한 형태로 바꿔도 됩니다.

---

## 1. 반드시 참고할 파일 (우선순위 순)

| # | 파일 | 무엇을 얻는가 | 앱에서 쓰는 곳 |
|---|---|---|---|
| 1 | `vocal-scope/docs/FIGMA_PRACTICE_SCREENS.md` | 노래 연습·연습 분석·곡 적합도 화면 7개 요소 설명 + 요소별 데이터 대응(✅/🟡/❌) | 화면 구현 범위 결정 |
| 2 | `vocal-scope/docs/MICON_DATA_PLAN.md` | 화면 15개 ↔ 필요 데이터 ↔ 출처 대응표, 09-02 팀 결정(MR=분리 기반, 편안 음역=안정 음역, MySQL 8), 미결 목록 | 전체 화면↔데이터 지도 |
| 3 | `vocal-scope/docs/micon_schema.sql` | MySQL 8 스키마 13테이블. 곡 쪽 5개(적재기가 채움) + 사용자 쪽 8개(**앱 백엔드가 소유·제안 상태**) | Room/Retrofit 모델, 백엔드 DTO |
| 4 | `vocal-scope/docs/DATA_INVENTORY.md` | 엔진이 내보내는 모든 필드의 이름·타입·단위(§2), 채점 결과 `comparison{}`(§2.9), 버려지는 값(§4), 미구현(§5) | JSON 파싱 클래스 작성 |
| 5 | `vocal-scope/PIPELINE.md` §5 + `vocal-scope/model-server/app.py` | 엔진 REST API 원문(아래 §3에 요약) | Retrofit 인터페이스 |
| 6 | `프로젝트 핵심 용어집 — 음성·음색·음역·분석 품질.md` (이 폴더 루트) | 팀 공통 용어(안정 음역, 성구, 음색 층, 분석 품질 pass/review/fail) — 화면 문구·필드명의 기준 | UI 문구, 네이밍 |
| 7 | `outputs/micon_catalog.sqlite` | 890곡 메타 + 211곡 분석(stem-pitch 배치)이 들어간 **개발용 샘플 DB**(스키마는 위와 동일 컬럼, 생성: `vocal-scope/docs/STEM_PITCH_IMPORT.md`) | 목록·상세 화면 목데이터, 쿼리 검증 |
| 8 | `vocal-scope/model-server/workspace/tj-*/job.json` (51곡) | 실제 분석 결과 JSON 원문(`result.notes/karaokeNotes/register/lyrics/...`) + `instrumental.opus` | 파서 테스트 픽스처, 재생 테스트 |
| 9 | `vocal-scope/app/live-scoring.tsx`, `vocal-scope/public/pitch-worklet.js`, `vocal-scope/tests/pitch-worklet.test.mjs` | 라이브 채점 로직(재생 위치 기준 즉시 판정, 자동 지연 보정)과 YIN 피치 추적 순수 함수 + 테스트 | Kotlin 포팅 원본 |
| 10 | `vocal-scope/model-server/compare_engine.py` 상단 상수, `vocal-scope/app/scoring-ui.ts` | 판정 임계·점수 가중·등급 경계, 판정 색상·라벨 | 채점 표시 일관성 |
| 11 | `JIRA_GIT_RULES.md` | 커밋 `타입: 내용`(이슈 키 선택), 브랜치 `타입/camelCase`, MR 제목에 이슈 키, SP≤3 | 이슈·커밋 규칙 |

보조: `vocal-scope/README.md`(엔진 개요), `vocal-scope/docs/MODEL_PIPELINE.md`(알고리즘 근거, 화면 문구 설명에 필요할 때만), `vocal-scope/docs/BATCH_HANDOFF.md`·`vocal-scope/REQUIREMENTS.md`(다른 PC에서 890곡 배치 돌릴 때만).

## 2. 참고하지 않아도 되는 것 (정리 대상)

이 폴더 루트에는 지난 인수 산출물이 여러 벌 쌓여 있습니다. 앱 작업에는 필요 없고, 아래는 **아직 옮기지 않았습니다**(확인 후 `_archive/` 같은 폴더로 이동 권장).

| 항목 | 크기 | 상태 |
|---|---|---|
| `vocalscope-models.zip` | 3.9 GB | 08-31 모델 가중치 번들. 온라인 PC면 불필요(자동 다운로드) |
| `vocalscope-pipeline.zip`, `vocalscope-handoff-2026-08-27.zip`, `vocal-scope-src-2026-08-26.zip` | 각 1–3 MB | 옛 소스 스냅샷. 최신은 GitLab `feat/vocalScope` |
| `vocalscope-site.tar.gz`, `-v2`, `-v3`, `package-site-lf.sh`, `social-preview-output/` | 소량 | 08-26 웹 배포 실험 산출물 |
| `codex-windows-sandbox-setup.exe` | 15 MB | 다른 도구 설치 파일 |
| `파이프라인 정리.md` | 25 KB | 08-31 초안. 같은 내용이 `vocal-scope/PIPELINE.md`로 정리됨 |
| `청춘만화 악보.png` | 98 KB | 악보 표기 검증(-38)에 쓴 참고 이미지 |
| `노래 모음/` (이 폴더 안, 44 MB) | 5곡 | 엔진 검증용 테스트 곡(이무진 3·아이유 2). **카탈로그 890곡은 `Desktop\노래 모음`(다른 폴더)** |
| `outputs/lyrics_checkpoint/` | 120 KB | 09-01 가사 수집 중간 결과(정영주 님 작업 연계), 앱과 무관 |
| `vocal-scope/docs/과거 문서/` | — | 09-03에 옮긴 구식 문서 2개 |

현재 유효한 인수물은 `vocalscope-batch-extractor-2026-09-03.zip`(234 MB) 하나입니다.

**정리 상태 평가**: `vocal-scope/docs/`는 09-03 정리로 현행 문서 5개 + 과거 문서 폴더로 깔끔합니다. 루트는 옛 zip·tar 8개가 남아 있어 어수선하지만 앱 작업을 막지는 않습니다. `vocal-scope/app/api/**`, `db/`, `lib/audio-analysis.ts`는 죽은 코드(웹이 엔진을 직접 호출)이니 참고하지 마세요.

---

## 3. 엔진 REST API 계약 (FastAPI, `127.0.0.1:8787`)

엔진은 로컬 PC의 127.0.0.1에만 바인딩됩니다. **에뮬레이터에서는 `http://10.0.2.2:8787`**, 실기기는 같은 Wi-Fi + `app.py`의 host를 `0.0.0.0`으로 바꾸거나 백엔드 프록시를 두어야 합니다. 앱은 원칙적으로 **백엔드(MySQL) → 엔진** 순으로 접근하고, 엔진 직접 호출은 녹음 업로드·채점처럼 실시간 작업에만 쓰는 구조를 권장합니다.

| 메서드·경로 | 요청 | 응답 | 용도 |
|---|---|---|---|
| `GET /health` | — | `{ok, device, gpu, models[], separators[]}` | 연결 확인 |
| `GET /jobs?kind=reference&limit=50` | — | `{jobs:[요약]}` 완료 작업 최신순(최대 200) | 저장된 원곡 목록 |
| `POST /jobs` (202, multipart) | `audio` 파일, `sourceType=mix|vocal`, `lyrics`(≤20,000자), `separator` | job 객체(`status=queued`) | 원곡 분석 시작(배치 완료 곡은 불필요) |
| `GET /jobs/{id}` | — | job 객체 `{id, kind, status, progress 0–100, message, result, error}` | **진행률 폴링**(1–2 s), 결과 읽기 |
| `POST /jobs/{id}/compare` (202, multipart) | `audio`(webm/wav/m4a 등), `sourceType=mix|vocal`, `allowTranspose`, `octaveTolerant` | performance job(`kind=performance`, `referenceJobId`) | **부른 노래 채점**(MR 없이 부르면 `sourceType=vocal`로 분리 생략) |
| `DELETE /jobs/{id}` | — | `{id, deleted:true}`; reference 곡·분석 중이면 409 | **부른 노래 기록 삭제**(평가 탭, workspace 파일까지 제거) |
| `POST /jobs/{id}/comparison` (JSON) | `{allowTranspose?, octaveTolerant?}` | job(`result.comparison` 갱신) | 옵션만 바꿔 재채점 |
| `POST /jobs/{id}/instrumental-key` (JSON) | `{semitones}` −6…+5 | `{semitones, file, url, seconds, range}` | 키 변경 반주 미리 렌더(6분 곡 ≈9 s) |
| `GET /jobs/{id}/files/instrumental?semitones=N` | — | `audio/ogg`(Opus) 또는 `audio/wav` | **반주 스트리밍**(ExoPlayer로 재생) |
| `GET /jobs/{id}/files/{karaoke-midi\|midi\|karaoke-musicxml\|musicxml\|pitch-frames}` | — | 파일 | 악보·MIDI·10 ms F0(npz) |
| `GET /jobs/{id}/files/vocals` | — | 저장 정책 적용 곡은 **404** | 사용 안 함 |
| `POST /jobs/{id}/karaoke`, `POST /jobs/{id}/lyrics` | — | 웹 편집기용. 저장 정책 곡의 `/lyrics`는 422 | 앱 범위 밖 |

job id: 카탈로그 곡은 `tj-<TJ번호>`(= `songs.song_id`), 사용자 업로드는 UUID. 상태 값 `queued → processing → complete | failed`.

### 앱이 주로 읽을 `result` 필드 (전체는 DATA_INVENTORY §2)

| 화면 | 필드 |
|---|---|
| 곡 정보 | `key.name`, `tempoBpm`, `timeSignature`, `lowest/highest`(음이름), `lowestMidi/highestMidi`, `coreRange`, `ratios{low,middle,high}`(%), `vocalStart/vocalEnd`(s) |
| 성구 | `register.ratios{modal,falsetto,mixed,unclear}`(0–1), `register.breathyRatio`, `register.lightnessMean`(0–100), `register.modalRange/falsettoRange`, `register.caveats[]` |
| 연습 가사 싱크·멜로디 | `karaokeNotes[]`: `sourceStart/sourceEnd`(s), `midi`, `actualMidi`, `name`, `lyric`(음절), `lyricStart/lyricEnd`, `lyricStatus`(aligned\|low-confidence\|unknown-language), `register`, `lightness` |
| 채점 결과 (`result.comparison`, performance job) | `scores{pitch,timing,overall 0–100, grade S–F, stabilityCents}`, `coverage{notesSung,notesMissed,coveredRatio}`, `pitchStats{good,sharp,flat,wrong,missed,meanCents}`, `timingStats{good,early,late,off,meanDeviationMs}`, `notes[]`(음표별 판정), `measures[]`(마디 점수), `issues[]`(문제 구간), `curves{ref,user,hop,start}`, `transposition.semitones`, `thresholds` |

### 채점 상수 (엔진·웹 동일, 포팅 시 그대로)

| 항목 | 값 |
|---|---|
| 음정 판정 | good ≤ 45¢ · off ≤ 75¢(sharp/flat) · wrong > 150¢ · 유성 < 35% → missed |
| 박자 판정 | good ≤ 120 ms · off ≤ 450 ms |
| 점수 | 음정 30¢→100%, 150¢→0% · 박자 60 ms→100%, 400 ms→0% · 가중 음정 0.65 + 박자 0.35 |
| 등급 | S ≥95 · A ≥88 · B ≥78 · C ≥65 · D ≥50 · F |
| 판정 색 (`scoring-ui.ts`) | good `#3f9d5a` · sharp `#d98b2b` · flat `#3f7fd9` · wrong `#c94a3d` · missed `#9aa19c` · unsung `#d8d4c9` |
| 라이브 YIN | 16 kHz 데시메이트, 창 1024, 홉 20 ms, 70–1050 Hz, 임계 0.12, clarity ≥ 0.6, RMS ≥ 0.004, 지연 보정 ±0.4 s |

---

## 4. DB(MySQL 8) — 앱 백엔드가 알아야 할 것

- 곡 쪽(읽기 전용, 적재기가 채움): `songs`(제목·가수·이미지·장르·언어·가사·`audio_path`), `song_analysis`(조성·BPM·음역·성구 JSON·`task_support`), `song_notes`(`karaoke_notes` JSON ≈0.3–1 MB/곡), `song_artifacts`(반주 opus 경로·키별), `song_sections`(**비어 있음**).
- 사용자 쪽(**제안 상태**, 신승민 님 -68/-69·양서희 님 -16/-66 데이터 모델 이슈와 맞춰야 함): `users`, `user_recordings`, `user_voice_profiles`(안정 음역·반음별 안정도·성구·육각 특성), `song_fit`(추천 키·적합도, 음색·리듬은 NULL=평가 불가), `practice_sessions`(연습 1회 = `comparison` 저장), `favorites`, `setlists`, `setlist_items`.
- 오디오는 DB 밖. 로컬 엔진은 `song_artifacts.path`의 Opus를 파일 서버로 스트리밍한다. 운영은 [SSAFY BE·MySQL + 개인 S3](../../../docs/operations/S3_VOICE_STORAGE.md)이며 object key 매핑·허가된 다운로드/스트리밍 연결을 별도로 검증해야 한다. PC 절대경로나 `s3://`를 Android 재생 URL로 그대로 사용하지 않는다. 녹음은 BE가 발급한 만료 URL을 사용하고 AWS 키를 앱에 포함하지 않는다. 2026-09-09 버킷·BE 전용 IAM·서버 키 저장과 CLI 연결은 검증했지만 BE 배포·앱/DB/AI 통합은 미완료다.
- 로컬 MySQL84(root 비번 미설정)에 `micon` DB가 09-02 덤프로 올라가 있고, 같은 내용의 `outputs/micon_catalog.sqlite`가 있어 백엔드 없이도 화면 개발이 가능합니다.

## 5. 지금 데이터로 되는 것 / 안 되는 것

| 바로 가능 | 로직 추가 필요 | 데이터 없음 |
|---|---|---|
| 곡 목록·검색·상세, 멜로디 곡선, 반주 재생·키 변경, 가사 싱크 글자 채움, 부른 노래 채점(점수·등급·음표별·마디·문제 구간), 성구 비율 | 안정/불안정 음역·음역별 안정성(npz 반음별 안정도), 권장 키(트레이너: 최고음보다 3키 낮게), 고음 집중 구간, 적합도 % 공식(§15 미확정) | 절/후렴 sections, 가창 난이도, 분위기, 음색·리듬 적합도, 성부/성별 |

라이브 채점은 웹에서 브라우저 AudioWorklet으로 구현되어 있고 **마이크 실사용 검증은 아직 안 됨**. 안드로이드는 `AudioRecord`(16 kHz 모노) + YIN 포팅 또는 Oboe/TarsosDSP로 대체하고, 정밀 리포트는 종료 후 녹음 파일을 `/compare`로 보내는 웹과 같은 2단 구조를 권장합니다.

## 6. 작업 시작 체크리스트

1. 팀 저장소 `Desktop\특화PJT_git\S15P21B110`에서 앱 폴더·브랜치 만들기(`JIRA_GIT_RULES.md` 규칙).
2. `outputs/micon_catalog.sqlite`를 앱 `assets` 또는 로컬 백엔드에 넣어 목록·상세 화면부터.
3. `workspace/tj-*/job.json` 몇 개를 테스트 픽스처로 복사해 `karaokeNotes`·`comparison` 파서 단위 테스트.
4. 엔진 기동(`vocal-scope\VocalScope 실행.cmd` 또는 `model-server\start.ps1`) 후 에뮬레이터에서 `GET http://10.0.2.2:8787/health` 확인.
5. 반주 재생: `GET /jobs/tj-14260/files/instrumental?semitones=0`(Opus) → ExoPlayer.
6. 채점: 녹음(m4a/wav) → `POST /jobs/tj-14260/compare` → `GET /jobs/{perfId}` 폴링 → `result.comparison`.

관련 지라: -88 업로드, -90 인벤토리, -91 대응표, -92 스키마, -93 배치, -94 저장 정책, -97 인수 패키지(모두 완료). 미결 항목은 `MICON_DATA_PLAN.md` §6.
