# 연습 데이터 API 제안서 — FE·AI → BE (2026-09-10)

작성: 정다운(FE·AI). 기준 코드: develop `65e5fa6` (BE `backend/src/main/java/com/micon/backend`, Flyway V1~V10), 앱 브랜치 `feat/backendSongCatalog`.
목적: 연습 기능이 **백엔드 데이터만으로** 돌아가도록, (A) 앱이 필요로 하는 기존 API 변경과 (B) BE가 호출할 AI 서버 API 계약을 확정한다.
곡 식별자는 어디서나 `songId`(BIGINT) 하나다. 문자열 카탈로그 키(`catalog_track_id`)는 앱·AI 계약에 나오지 않는다.

이미 맞는 것(변경 없음): 응답 봉투 `ApiResponse{code,message,data}`, JWT Bearer, 인증 경로 `/auth/login|signup|logout|refresh`, `GET /songs`·`GET /songs/{songId}` 응답 형식, 오류 코드 `SONG_NOT_FOUND`(404)·`PRACTICE_ASSET_NOT_READY`(409)·`UNSUPPORTED_PRACTICE_KEY`(422).

---

## A. 앱이 필요로 하는 BE 변경 (기존 API)

### A1. `GET /songs/{songId}/practice-assets` 응답 확장 — 필수

현재 `PracticeAssetResponse.Detail`에는 반주를 **재생할 수단이 없고**(`instrumental.filePath`는 S3 object key), 연습 화면이 쓰는 조성·BPM·박자·비트 그리드가 없다. 아래 필드를 추가해 달라.

```jsonc
{
  "songId": 1, "title": "…", "selectedKey": 0,
  "instrumental": {
    "mediaId": 11, "filePath": "catalog/…/instrumental.opus", "mimeType": "audio/ogg", "fileBytes": 4200000, "sha256": "…",
    "playbackUrl": "https://….amazonaws.com/…?X-Amz-Signature=…",   // 추가. 만료 있는 GET URL (recordings/{id}/content 와 같은 방식)
    "playbackUrlExpiresAt": "2026-09-10T12:34:56Z"                    // 추가
  },
  "lyricsPlain": "…",
  "lyricsSynced": "[00:16.28] 첫 줄\n[00:23.87] 둘째 줄",              // 문자열(LRC 텍스트)로 고정해 달라. 지금은 JsonNode
  "targetNotes": [ /* B2 의 KaraokeNote 배열. song_note.karaoke_notes 그대로 */ ],
  "dataVersion": "karaoke-1.0",
  "sections": [ /* 기존 Section 그대로 */ ],
  "keyName": "G minor",            // 추가. song_analysis.key_name
  "tempoBpm": 134.0,               // 추가. song_analysis.tempo_bpm
  "timeSignature": "4/4",          // 추가. song_analysis.time_signature
  "beatGrid": { "beatTimes": [0.45, 0.89, …], "offsetBeats": 1.0 }   // 추가. song_analysis.beat_grid (JSON 그대로)
}
```

- `playbackUrl` 발급 방식은 `voice.storage.playback-url-ttl`(10분)과 같은 presigned GET. 앱은 인증 헤더 없이 이 URL 만 재생한다(ExoPlayer).
- `selectedKey ≠ 0` 이면 `song_media(kind=INSTRUMENTAL_KEY, semitones=selectedKey)` 의 URL. 없으면 기존대로 422.
- 앱 파서는 위 4개 필드가 없어도 동작하지만(기본 4/4·100 BPM), `playbackUrl` 이 없으면 연습을 시작할 수 없다.

### A2. `POST /practices` — `recordingId` 선택으로 — 필수

`PracticeRequest.Create.recordingId` 의 `@NotNull` 제거. null 이면 `PracticeService.create` 의 `isUploadedRecordingOwnedBy` 호출을 건너뛴다(현재는 `Map.of` 때문에 NPE). DDL 변경안(`recording_id BIGINT NULL`)과 일치한다.

### A3. `POST /practices/{practiceId}/complete` — `recordingId` 추가 — 필수

연습이 끝난 뒤 사용자가 "정밀 리포트"를 누를 때만 녹음을 올리므로, 녹음 연결은 생성 시점이 아니라 완료 시점에 일어난다.

```jsonc
// PracticeRequest.Complete
{ "durationMs": 215000, "voiceAnalysisId": null, "recordingId": 88 }   // recordingId 추가(선택). 소유권·UPLOADED 검사는 기존 규칙
```

### A4. 연습 상태 전이 API — 필수 (`녹음중` 상태 추가에 따라)

현재 상태를 바꾸는 방법이 생성(READY)·complete(COMPLETED)·cancel(ABORTED)뿐이다. 앱 단계와 대응하려면 전이 API 하나가 필요하다.

```
PATCH /practices/{practiceId}/status
{ "status": "RECORDING" | "IN_PROGRESS" | "PAUSED" }
→ 200 PracticeResponse.Detail
```

앱 단계 대응: READY(반주 로드) → `RECORDING`(마이크 켜고 카운트다운·재생) ↔ `PAUSED` → `complete`(COMPLETED) / `cancel`(ABORTED). `IN_PROGRESS` 는 "재생만, 녹음 없음" 용도로 남기거나 `RECORDING` 으로 대체 — BE 결정에 따르겠다.
허용 전이: READY→RECORDING|IN_PROGRESS, RECORDING↔PAUSED, IN_PROGRESS↔PAUSED. 그 외 409 `INVALID_PRACTICE_STATE`.

### A5. `practice_session`·`practice_result` Flyway V11 — 필수

두 테이블은 `docs/MICON_COMPLETE_DDL.sql` 에만 있고 V1~V10 에 없어 운영 DB 에서 `/practices` 가 전부 실패한다. 공유된 변경안(`recording_id NULL`, `fk_practice_session_section … ON DELETE SET NULL`, `status` ENUM 에 `RECORDING` 추가)으로 V11 을 추가해 달라. `practice_result.status` 는 그대로.

### A6. 앱 상태 확인 경로 — 결정 요청

앱 `BackendApi.health()` 가 `GET /health` 를 기대하지만 BE 에는 없다(401). 둘 중 하나로 정해 달라: (a) 공개 `GET /health` → `ApiResponse{data:{status:"UP"}}` 추가, (b) 앱이 `GET /policies`(공개) 로 대체. (b) 면 BE 변경 없음.

### A7. `GET /songs` 필터 — 선택

앱은 서버가 지원하는 `query/page/size` 만 쓰도록 목록 필터(언어·차트 분류·정렬)를 제거했다. 나중에 되살리려면 `practiceReady=true`, `sort=title|artist|newest` 정도가 필요하다. 지금은 불필요.

### A8. `PUT /practices/{practiceId}/performance` — 확인만

앱은 실시간 채점 결과를 `PracticeResultRequest.Save` 그대로 보낸다: `status=COMPLETED|INSUFFICIENT_DATA`, `overallScore/pitchScore/rhythmScore`(0~100), `sustainScore`(당장 null), `sections[]`(`sectionId` = practice-assets 의 section id, `score`, `measured`, `summary` ≤120자, 최대 20개). 녹음이 없는 연습은 이 값이 최종 결과가 된다.

---

## B. AI 서버 API (정다운 구현, BE 가 호출)

베이스 URL `AI_RECOMMENDATION_BASE_URL`, 인증 `Authorization: Bearer <AI_SERVICE_TOKEN>` (BE 와 같은 토큰), JSON, 타임아웃: 접수 3초/조회 10초. 작업은 비동기(202 → 조회 또는 콜백).
상태 값: `QUEUED | PROCESSING | COMPLETED | PARTIAL | FAILED | CANCELLED`. 오류 본문은 `{ "code": "…", "message": "…" }`.

### B1. `POST /song-processing-jobs` — 곡 하나를 연습 가능하게 만드는 작업

```jsonc
// 요청 (BE → AI)
{
  "songId": 1,
  "dataVersion": "karaoke-1.0",                    // BE 가 정하는 결과 버전 태그. 재처리 시 증가
  "vocalStem": {                                   // GET /internal/catalog-media/vocal-stems/{songId} 가 발급하는 값 그대로
    "url": "https://….amazonaws.com/…/vocals.wav?X-Amz-Signature=…",
    "expiresAt": "2026-09-10T12:40:00Z",           // 최소 30분 (다운로드 16~45 MB)
    "sha256": "99ce283b…", "sizeBytes": 37875760, "mimeType": "audio/wav"
  },
  "lyricsPlain": "…" ,                             // song_metadata.lyrics_plain. 없으면 null → 가사 정렬 생략
  "lyricsSynced": "[00:16.28] …",                  // 이미 있는 LRC (있으면 정렬 기준·검증에 사용). 없으면 null
  "language": "kor",                               // song_metadata.primary_language (kor|eng|jpn|…). 정렬 모델 선택에 사용
  "callbackUrl": "https://<BE>/callbacks/song-processing"   // null 이면 콜백 없이 조회만
}
// 응답 202
{ "jobId": "5f3c…", "songId": 1, "status": "QUEUED" }
// 오류: 400 INVALID_REQUEST, 401 UNAUTHORIZED, 409 JOB_ALREADY_RUNNING(같은 songId 진행 중), 503 BUSY(큐 초과)
```

### B2. `GET /song-processing-jobs/{jobId}` — 상태·결과 조회 (콜백 유실 복구)

```jsonc
{
  "jobId": "5f3c…", "songId": 1, "status": "COMPLETED", "progress": 100,
  "stage": "summary",             // download | pitch | notes | lyrics | sections | summary
  "startedAt": "…", "finishedAt": "…",
  "error": null,                  // FAILED 이면 { "code": "STEM_DOWNLOAD_FAILED" | "PITCH_FAILED" | "LYRICS_FAILED" | "INTERNAL", "message": "…" }
  "result": {
    "analysisVersion": "vocalscope-1.3",           // → song_analysis.analysis_version
    "model": "pre-separated stem + crepe-full + pyin", "device": "cpu",
    "keyName": "G minor", "keyTonic": 7, "keyMode": "MINOR", "keyConfidence": 0.42,
    "tempoBpm": 134.0, "tempoRawBpm": 133.6, "timeSignature": "4/4", "meterConfidence": 0.8, "tuningCents": -3,
    "lowestMidi": 50, "highestMidi": 69, "coreLowMidi": 55, "coreHighMidi": 65,
    "rangeRatios": { "low": 0.2, "middle": 0.6, "high": 0.2 },
    "vocalStartSec": 16.28, "vocalEndSec": 232.36, "voicedSeconds": 150.2, "voicedRatio": 0.62,
    "noteCount": 358, "rangeConfidence": 0.86, "f0Agreement": 0.86, "octaveMismatch": 0.02,
    "taskSupport": { "pitch_range": "pass", "rhythm": "pass", "timbre": "not_analyzed", "vocal_identity": "not_analyzed" },
    "registerSummary": { "twoRegisters": true, "ratios": { "modal": 0.7, "falsetto": 0.1, "mixed": 0.1, "unclear": 0.1 }, "modalRangeMidi": [50, 66], "falsettoRangeMidi": [64, 69] },
    "beatGrid": { "beatTimes": [0.45, 0.89, …], "offsetBeats": 1.0 },
    "karaoke": {
      "version": "karaoke-1.0", "noteCount": 358,
      "notes": [ { "sourceStart": 16.28, "sourceEnd": 16.90, "midi": 62, "actualMidi": 61.8, "name": "D4", "cents": -12, "confidence": 0.71,
                   "duration": 0.62, "register": "modal", "registerConfidence": 0.8, "lightness": 40.1, "breathy": false,
                   "lyric": "그", "lyricExtend": false, "lyricStart": 16.28, "lyricEnd": 16.90, "lyricStatus": "aligned",
                   "scoreStartBeat": 36.0, "scoreEndBeat": 37.0, "scoreStart": 16.28, "scoreEnd": 16.90 }, … ],
      "stats": { "alignedRatio": 0.93, "melisma": 41 }
    },
    "lyrics": { "synced": "[00:14.38] …", "source": "catalog+aligned", "aligner": "wav2vec2-ko", "alignmentScore": 0.18, "syllables": 192 },
    "sections": [ { "sectionType": "VERSE", "sectionName": "1절", "startMs": 16280, "endMs": 61200, "sequenceNo": 1,
                    "difficultyType": "HIGH_NOTE", "difficultyScore": 72.5, "confidence": 0.7, "description": "후반 G4 지속" }, … ],
    "items": { "pitch": "ok", "karaoke": "ok", "lyrics": "ok", "sections": "ok" }   // 항목별 성공 여부: ok | skipped | failed. PARTIAL 판정 근거
  }
}
```

`sections` 는 이미 적재된 인트로/보컬/간주 구간을 대체하지 않고 **절·후렴·난관 구간을 추가**하는 용도다(`source_type='AI'`, `sequence_no` 충돌은 BE 가 결정 — C 참고). 반주는 이 작업의 산출물이 아니다.

### B3. `POST {BE}/callbacks/song-processing` — AI → BE (기존 openapi micon011)

```jsonc
{ "eventId": "b7e2…", "jobId": "5f3c…", "songId": 1, "sequence": 3,
  "status": "COMPLETED", "progress": 100, "stage": "summary",
  "result": { /* B2 result 와 동일 */ }, "error": null, "occurredAt": "…" }
// BE 응답 200 { "jobId": "5f3c…", "duplicate": false }
```
`eventId` 로 중복 제거, `sequence` 로 역순 무시. AI 는 3회 재시도 후 포기하며, BE 는 어차피 B2 로 복구할 수 있다. 인증은 `AI_SERVICE_TOKEN` Bearer (`VoiceAnalysisCallbackController` 와 같은 `AiServiceTokenValidator`).

### B4. `POST /song-processing-jobs/{jobId}/cancel` → 202 `{ "jobId", "status": "CANCELLED" }` (이미 끝났으면 409).

### B5. 이미 BE 가 정의한 계약 — 그대로 구현한다

| 엔드포인트 | BE 쪽 정의 | 비고 |
|---|---|---|
| `POST /voice-analysis-jobs`, `POST /voice-analysis-jobs/{jobId}/cancel` | `VoiceAnalysisJobGateway.Command{voiceAnalysisId, recordingUrl, method, songId, guideVersion}` → `Result{jobId, status}` | 요청에 `recordingUrl` 만료 시각과 `callbackUrl` 도 넣어 주면 좋다 |
| AI → `POST /callbacks/voice-analyses` | `VoiceAnalysisCallbackRequest{voiceAnalysisId, jobId, status, analysisVersion, qualityStatus, failureCode, failureReason, range{…}, timbre{…}, measuredAt}` | `range` 는 채운다. `timbre` 는 추천 서버 소관이라 당장 null |
| `POST /song-fit` | `SongFitGateway.Command{userId, songId, analysisId, songAnalysisId, selectedKeyOffset, userStableLow/HighMidi, songLow/HighMidi, …}` → `SongFitResponse{originalKeyFit, selectedKeyFit, recommendedKeyOffset, recommendationScores, reason, burdenSections}` | 동기. 앱 `RangeFit.kt` 와 같은 공식을 서버로 옮김 |

### B6. `POST /practice-analysis-jobs` — 정밀 채점 (openapi micon018, 스키마 제안)

```jsonc
// 요청
{ "practiceId": 501, "songId": 1, "selectedKey": -2, "dataVersion": "karaoke-1.0",
  "recording": { "url": "https://…presigned", "expiresAt": "…", "mimeType": "audio/wav", "sourceType": "vocal" },  // sourceType: vocal(이어폰) | mix(스피커, 분리 필요)
  "scope": { "practiceScope": "FULL_SONG" | "SECTION" | "CUSTOM", "sectionId": null, "customStartMs": null, "customEndMs": null },
  "callbackUrl": "https://<BE>/callbacks/practice-analyses" }
// 응답 202 { "jobId": "…", "practiceId": 501, "status": "QUEUED" }
// GET /practice-analysis-jobs/{jobId} → result:
{ "status": "COMPLETED" | "INSUFFICIENT_DATA" | "FAILED",                 // PracticeResultRequest.ResultStatus 와 동일
  "overallScore": 82.4, "pitchScore": 85.0, "sustainScore": 70.2, "rhythmScore": 78.9, "summaryText": "…(≤500자)",
  "sections": [ { "sectionId": 12, "score": 88.0, "measured": true, "summary": "…(≤120자)" } ],   // 최대 20개
  "detail": { "transposeSemitones": -2, "judgedNotes": 300, "notes": [ { "index": 0, "status": "good|sharp|flat|wrong|missed", "cents": -12, "timingSec": 0.05 } ],
              "measures": [ { "index": 1, "score": 90 } ], "issues": [ { "startSec": 61.2, "endSec": 64.0, "reason": "고음 낮게" } ] } }
```
`sections`·점수 필드는 `PUT /practices/{id}/performance` 본문과 같은 이름으로 맞춰, BE 가 `practice_result`·`practice_section_result` 에 그대로 쓸 수 있게 한다. 콜백 `POST /callbacks/practice-analyses` 는 B3 와 같은 봉투.

---

## C. BE 저장 매핑 (B2 result → DB)

| result 필드 | 테이블.컬럼 |
|---|---|
| `analysisVersion, model, device` | `song_analysis.analysis_version, model, device` |
| `keyName, keyTonic, keyMode, keyConfidence, tuningCents` | `song_analysis.key_name, key_tonic, key_mode, key_confidence, tuning_cents` |
| `tempoBpm, tempoRawBpm, timeSignature, meterConfidence` | `song_analysis.tempo_bpm, tempo_raw_bpm, time_signature, meter_confidence` |
| `lowestMidi, highestMidi, coreLowMidi, coreHighMidi, rangeRatios` | `song_analysis.lowest_midi, highest_midi, core_low_midi, core_high_midi, range_ratios` |
| `vocalStartSec, vocalEndSec, voicedSeconds, voicedRatio, noteCount, rangeConfidence, f0Agreement, octaveMismatch` | 동명 컬럼 |
| `taskSupport, registerSummary, beatGrid` | `song_analysis.task_support, register_summary, beat_grid` (JSON) |
| `karaoke.notes, karaoke.version, karaoke.noteCount` | `song_note.karaoke_notes, karaoke_version, karaoke_note_count` (analysis_id = 위 행) |
| `lyrics.synced` | `song_metadata.lyrics_synced` (기존 값이 NULL 일 때만, 또는 BE 정책) |
| `sections[]` | `song_section` (`source_type='AI'`, `analysis_id`). 기존 정영주 구간(INTRO/VOCAL/…)과 `sequence_no` 가 겹치므로 **BE 가 규칙 결정**: 별도 번호대(예 100+) 또는 기존 행 교체 |
| `status` | `song_analysis.status`: COMPLETED / PARTIAL(items 에 failed) / FAILED(+`error_code`, `error_message`) |
| — | `song.practice_ready = TRUE` 는 **`song_note` 가 있고 `song_media(INSTRUMENTAL, READY)` 가 있을 때만**. 지금은 384곡 전부 TRUE 라 practice-assets 가 409 를 낸다 |

---

## D. 초기 384곡 처리 흐름 (BE 트리거)

1. `song_media(kind='VOCAL_STEM', generation_status='READY')` 가 있는 곡을 순회.
2. 곡마다 `/internal/catalog-media/vocal-stems/{songId}` 로 URL 발급 → B1 요청. AI 동시 처리 1~2건(CPU). 곡당 CPU 1~2분 → 384곡 약 6~10시간(백그라운드).
3. 콜백 또는 B2 폴링으로 결과 저장(C). 실패 곡은 `song_analysis.status=FAILED` 로 남기고 목록 보고.
4. 반주는 별도 경로: 데이터 PC `no_vocals.wav` → S3 업로드 → INFRA importer `song_media(INSTRUMENTAL)`. 둘이 모두 끝난 곡만 `practice_ready=TRUE`.

---

## E. 결정이 필요한 항목 (BE 회신 요청)

1. A4 상태 전이: `PATCH /practices/{id}/status` 형태로 갈지, `RECORDING` 이 `IN_PROGRESS` 를 대체할지.
2. A6 앱 상태 확인: `GET /health` 추가 vs `GET /policies` 사용.
3. A1 `lyricsSynced` 타입: LRC 문자열 고정 확인.
4. C `sections` 병합 규칙과 `practice_ready` 갱신 조건.
5. B1 `vocalStem.expiresAt` 최소 30분 가능 여부 (`CATALOG_MEDIA_DOWNLOAD_URL_TTL` 현재 10분, 1시간 초과 불가).
6. B 전체: 콜백 사용 여부(콜백 + 폴링 병행 제안), AI 서버의 운영 주소·포트(`AI_RECOMMENDATION_BASE_URL`), 서비스 토큰 전달 방식.

회신이 오는 대로 AI 서버 라우터(B1~B4, B5·B6)를 구현하고, 앱은 A1·A3·A4 계약에 맞춰 `PracticeRepository` 를 붙인다.
