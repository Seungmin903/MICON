# MICON 추천 서버 (`ai/recommend-server`)

음색(ECAPA + Singer-BYOL 두 축)과 음역(키 이동 적합도)으로 추천곡·추천 키를 내려주는 FastAPI 서버.
알고리즘 원본은 시험 브랜치 `recommend-algo` 의 `vocal-scope/model-server` 이고, 여기에는 서버에 필요한 부품만 옮겼다.
알고리즘 설명과 조정 파라미터는 [docs/TIMBRE_RECOMMENDER.md](docs/TIMBRE_RECOMMENDER.md).

- VocalScope 엔진(8787)과 **별도 프로세스**, 포트 **8788**. 의존성이 다르고 소유자가 달라 따로 배포한다.
- 호출 주체: 운영은 BE(Spring) → 이 서버. 앱은 BE 만 본다. 개발 중 앱은 `http://10.0.2.2:8788/` 로 직접 붙는다.
- 카탈로그 v1 은 파일 인덱스 (`recommender-assets/catalog-index/`, 374곡). `*.npy` 는 Git LFS.

노트북 온보딩 통합 실행·검증은 [LAPTOP_AI_ONBOARDING.md](../../docs/operations/LAPTOP_AI_ONBOARDING.md)를 따른다.

## 설치·실행 (Windows)

```powershell
cd ai\recommend-server
powershell -ExecutionPolicy Bypass -File setup.ps1   # venv + 의존성 + 모델 가중치(116 MB)
powershell -ExecutionPolicy Bypass -File start.ps1   # 0.0.0.0:8788
```

`setup.ps1` 은 `models/recommender/` 에 가중치를, `workspace/recommender/config.json` 에 이 checkout 의 절대경로 설정을 만든다. 둘 다 Git 제외.
`setup.ps1`은 CPU가 기본이며, CUDA 12.8 설치는 `setup.ps1 -TorchIndex cu128`로 명시한다. 검증한 torch/torchaudio 2.7.1, torchvision 0.22.1 조합을 사용한다. 추론은 CPU 로도 돌아간다. 곡 적재(Demucs 분리)는 GPU PC 에서 오프라인으로 한다.

## 배포 (Docker, EC2 CPU)

```bash
COMMIT_SHA="$(git rev-parse --short=12 HEAD)"
export MICON_RECOMMENDATION_IMAGE="micon-recommendation:${COMMIT_SHA}"
export MICON_BUILD_SHA="${COMMIT_SHA}"
docker compose -f infra/compose/recommendation.compose.yml build micon-recommendation
docker compose -f infra/compose/recommendation.compose.yml up -d --no-build micon-recommendation
curl http://127.0.0.1:8788/health
```

Compose는 singleton 컨테이너 이름 `micon-recommendation`을 만들고 외부 `micon_backend` 네트워크에 붙인다. BE는 같은 이름의 8788 포트로 호출하고 AI는 `backend:8080`에서 카탈로그를 읽고 콜백을 보낸다. 서비스 토큰은 `/etc/micon/backend-secrets/ai_service_token`, 비밀 아닌 설정은 `/etc/micon/runtime/recommendation.env`에서 읽는다. 전체 절차는 [RECOMMENDATION_CONTAINER_RUNTIME.md](../../docs/operations/RECOMMENDATION_CONTAINER_RUNTIME.md)를 따른다.

`workspace/analysis` 볼륨은 BE 음성 분석 작업의 원음(최대 24시간)과 `job.json`, `workspace/recommender/jobs` 볼륨은 추천 작업 상태다. 둘 다 컨테이너 재생성 뒤에도 유지된다.

- 이미지에 들어가는 것: 코드, 카탈로그 인덱스(`recommender-assets/catalog-index`, Git LFS 이므로 빌드 머신에 `git lfs pull` 필요), 모델 가중치(빌드 시 Hugging Face 에서 받아 SHA-256 검증).
- 들어가지 않는 것: 원본 음원·분리 보컬·사용자 녹음. 사용자 입력과 작업 상태는 요청 뒤 named volume에만 잠시 머물며 이미지에는 포함되지 않는다.
- DB·S3에 직접 접근하지 않고 BE 내부 API에서 숫자 song_id 짝표만 읽는다. 임베딩 인덱스를 바꾸면 파일을 커밋하고 이미지를 다시 빌드한다.
- 권장 사양: vCPU 2 · 메모리 4 GB 이상(t3.large 급), 디스크 여유 5 GB.

## API

| 메서드·경로 | 요청 | 응답 |
|---|---|---|
| `GET /health` | — | `{ok, service, version, buildSha, timbreRuntime}`. 배포 커밋과 모델 preload 상태 확인 |
| `GET /recommendations/status` | — | 카탈로그 곡 수·모델·누락 설정 |
| `POST /recommendations` (202) | multipart `audio`(≤2분 보컬), `topK`(섹션당, ≤50), `mode=timbre\|range`, `sourceTrackId`, `genres`(반복, 온보딩 선호 장르), `preferredArtists`(반복, 온보딩 선호 가수) | job `{id, status, progress, message, genres, preferred_artists}` |
| `GET /recommendations/{jobId}` | — | job. `status=complete` 면 `result.recommendations[]` |
| `POST /voice-analysis-jobs` (202) | BE 전용. multipart `file` WAV 하나 + `meta` JSON(`requestId`, `analysisType`, `components` 필수) | `{jobId, status: QUEUED, audioExpiresAt, duplicate}`. 같은 `requestId`: 같은 음원 → 기존 jobId, 다른 음원 409, 원음 만료 410 |
| `POST /voice-analysis-jobs/{jobId}/cancel` | — | `{jobId, status}`. 원음 삭제 |
| `GET /voice-analysis-jobs/{jobId}` | — | 진단용 job.json (임베딩 값 제외) |

`meta.analysisId`와 `meta.sessionId`는 0이 아닌 정수다. BE 회원 ID는 양수, 비회원 임시 ID는 음수다. `attemptNo`는 양의 정수이며 `requestId`는 UUID다.

**BE 음성 분석 작업** (`analysis_jobs.py`): `TIMBRE`는 `ECAPA` + `SINGER_BYOL`, `RANGE`는 이미지에 포함된 `ai/vocal-scope/model-server/be_range_worker.py`를 별도 프로세스로 실행한다.
접수 후 백그라운드에서 임베딩을 만들고 컴포넌트마다 `MICON_BE_CALLBACK_URL` 로 콜백 1건을 보낸다.
ECAPA와 Singer-BYOL은 서버 시작 때 한 번 로드·워밍업하고 프로세스 수명 동안 재사용한다. `/health.timbreRuntime`과 작업의 `performance`에서 준비 상태와 단계별 시간을 확인한다.

```json
{ "requestId": "…", "jobId": "…", "component": "ECAPA", "attemptNo": 1,
  "status": "COMPLETED", "modelKey": "ECAPA", "modelVersion": "…", "dimension": 192,
  "embedding": [0.012],
  "segmentInfo": { "segmentDurationSeconds": 4.0, "segmentCount": 12, "validChunkCount": 12, "startTimes": [0.0], "embeddings": [[0.011]] },
  "qualityMetrics": { "voicedDurationSeconds": 18.4, "clippingRatio": 0.001 },
  "failureCode": null, "failureReason": null, "processedAt": "2026-09-15T10:31:00" }
```

`embedding` 은 1×D 평균, `segmentInfo.embeddings` 는 구간별 벡터다. 구간별 벡터가 없으면 추천 점수의 70%가 빠진다.
상태는 `COMPLETED` · `FAILED` · `INSUFFICIENT_DATA`(쓸 만한 4초 구간 5개 미만). 한 전송 회차에서 5xx·연결 오류를 3회 재시도한다. 미수신 콜백은 디스크에 남아 서버 재시작 후에도 최대 60초 간격으로 재전송하며 원음 강제 만료 시 함께 삭제한다.
원음은 `workspace/analysis/{jobId}/` 에 두고 모든 콜백이 2xx 된 시각 + 1시간 또는 접수 + 24시간 중 먼저 오는 때 지운다.

환경변수: `AI_SERVICE_TOKEN`(컨테이너에서는 secret 파일에서 필수 로드; 로컬에서 설정 시 `/health` 외 전 라우트 Bearer 필수) · `MICON_BE_BASE_URL`(카탈로그 조회) · `MICON_BE_CALLBACK_URL`(음성 분석 접수에 필수) ·
`RECOMMEND_CONCURRENCY=1`(음성 임베딩 추론) · `RECOMMEND_JSON_CONCURRENCY=1`(JSON 추천 계산 동시 실행, 기본 1) ·
`RECOMMEND_JSON_QUEUE_LIMIT=5`(동시 실행분 외 허용 대기 건수, 기본 5; 한도를 넘으면 POST 는 HTTP 503) ·
`RECOMMEND_EMBEDDING_OFFLOAD=false`(GPU가 작아 상주 모델을 매 작업 뒤 CPU로 내릴 때만 `true`) ·
`MICON_BUILD_SHA`(배포 커밋; 로컬 시작 스크립트는 자동 설정) · `MICON_RANGE_MODEL_PYTHON`(선택) ·
`RECOMMEND_AUDIO_TTL_SECONDS=3600` · `RECOMMEND_AUDIO_HARD_TTL_SECONDS=86400` · `RECOMMEND_CREPE_MODEL`.

JSON 추천의 동시 실행·대기 한도는 8788 Uvicorn 프로세스별로 적용한다.

8788은 Uvicorn worker 1개로 실행한다. 추천·음성 분석·가수 검색의 작업 map과 분석 요청 중복 방지 map은 프로세스 메모리에 있고, `job.json`은 재시작 복구용이지 worker 간 실시간 저장소가 아니다. worker를 늘리면 상태 조회가 다른 worker에서 404가 될 수 있고, 실행·대기 한도와 카탈로그 캐시도 worker 수만큼 따로 생긴다. 여러 worker로 확장하려면 공유 job 저장소와 원자적 요청 중복 방지·전역 대기열을 먼저 도입해야 한다.

**응답 계약**: `schemas.py` 가 원본. JSON Schema 는 [docs/response-schema.json](docs/response-schema.json), 실제 값 예시는 [docs/sample-response.json](docs/sample-response.json)
(카탈로그 곡 하나를 사용자 입력으로 삼은 합성 샘플). FE DTO 는 이 둘로 만든다. 스키마를 바꾸면 `scripts/make_sample_response.py` 를 다시 돌린다.

샘플 4종 (`docs/`). FE 는 이 넷으로 정상·null·빈 섹션·실패 화면을 고정한다.

| 파일 | 상황 | 확인할 것 |
|---|---|---|
| `sample-response.json` | 정상 | 섹션 4개(challenge 기본 꺼짐), `range_fit`·`recommended_key` 있음 |
| `sample-response-no-profile.json` | 결측 — 음역 프로파일 없음 | `query.pitchProfile=null`, `query.pitchError` 문장, 모든 item 의 `range_fit`·`recommended_key`=null, `algorithm.range_enabled=false`·`applied_mode=timbre_only`·`range_fallback_reason` 에 사유. `original_key` 섹션은 빈 배열 |
| `sample-response-boundary.json` | 경계 — topK=1, 선호 가수 없음, 장르 팝 | 섹션이 비거나 1곡, `favorite_artists` 없음, 언어 게이트 en |
| `sample-response-failed.json` | 실패 | `status=failed`, `result=null`, `error` 에 사용자 안내 문장 |

값 범위와 등급: `final_score`·`range_fit.score` 는 0~1. 표시 등급 경계(FE): ≥0.90 찰떡궁합 · ≥0.75 잘 맞아요 · 그 밖 도전해 보세요. **가창 난이도는 서버가 제공하지 않는다** — 필드 자체가 없으며 FE 가 표시하지 않거나 자체 규칙을 쓴다. 추천 사유는 `label`(음색, voice 섹션만) 하나이고 음역 사유 문장은 FE 규칙이 만든다.

**응답의 중심은 `result.shelves[]`** (`shelves-v6-stability-soft-score`, `timbre_recommender/shelves.py`). 선반마다 기준이 다르고 같은 곡이 서로 다른 선반에 나올 수 있다. 선반 안 곡 중복은 금지하며 가수당 최대 3곡이다. AI/BE 후보는 선반별 최대 50곡, Android 화면은 최대 15곡을 표시한다. `result.recommendations[]` 는 첫 선반 items 복사본(호환용).

음성 유사곡의 최종 가중치는 ECAPA 70%, Singer-BYOL 15%, 측정 발성 점수 최대 15%다. BE가 `voiceProfile.voiceQuality`의 `personal-acoustic-v1` 또는 `personal-acoustic-v2` 원본 음별 점수를 전달한다. 곡의 지속시간 가중 음정 분포에 측정 점수를 대응하고 ±3키 중 적합한 키를 고른다. 측정되지 않은 음은 0점으로 채우거나 그래프 연결선으로 보간하지 않는다. 발성 기여 비중은 `0.15 × measuredCoverage × catalogConfidence`이며, 나머지는 음색 두 모델의 70:15 비율로 재정규화한다. 사용자 발성 점수 또는 믿을 수 있는 곡 음정 분포가 없으면 음색만 사용한다. 추천 전용 점수 버전은 `voice-stability-70-15-15-v1`이며 닮은 가수 검색·모델 임베딩 계약 버전과 독립적이다.

| key | 제목 | 1차 지표 | 음역 조건 | 랩 |
|---|---|---|---|---|
| `voice` | 내 목소리와 닮은 곡 | ECAPA 70% + Singer-BYOL 15% + 발성 최대 15% | 음역 탈락 필터 없음 | 제외 |
| `original_key` | 키 안 바꾸고 바로 부를 곡 | 곡 중심음 ↔ 내 중심음 근접 | 최적 키 0, 원키 커버리지 ≥ 0.95 | 제외 |
| `favorite_artists` | 선호 가수 좋아하면 | 본인 곡 → 가수 임베딩 유사도 | 부담 0 | 제외 |
| `challenge` (실험, 기본 꺼짐) | 고음 한 단계 도전 | 상한 위 시간 비율 작은 순 | 원키 상한 초과 1~3반음, 그 비율 3~20% | 제외 |
| `discovery` | 새로운 발견 | 선택 장르·장르 미상 곡 중 다른 선반에 없는 곡 우선, 부족하면 재사용. 서버는 일자 시드로 한 번, 앱은 후보 안에서 다시 섞음 | 없음 | 허용 |

곡 프로파일 음표 신뢰도 < 0.55 인 곡(랩·불안정 추출)은 음역 조건이 있는 섹션에서 제외한다. 각 item 의 `shelf` 가 소속 섹션, `metrics` 가 그 섹션의 표시 지표다.
`metadata.artist_gender` 는 성별 필터용 자리이며 아직 데이터가 없다(null).

`shelves[].items[]` 한 항목:

| 필드 | 뜻 |
|---|---|
| `position` | 목록 위치 1~. **`final_score` 내림차순**이라 앞일수록 적합. 화면의 "적합할수록 크게" 규칙과 같은 뜻. 클라이언트 재정렬 금지 |
| `song_id` | BE JSON 추천에서는 숫자 DB song_id. 활성 카탈로그 짝표로 변환하며 TJ 번호로 추측하지 않는다. 로컬 데모는 문자열 ID도 허용 |
| `shelf`, `metrics` | 소속 섹션 key 와 그 섹션의 표시 지표(`coverage0`·`center0`·`artist_similarity`·`high_ratio0`·`genre` …) |
| `axis`, `label` | `axis`는 null. 음성 선반 `label`에는 측정 발성 점수·추천 키 또는 발성 자료 부족 사유가 표시된다. |
| `axis_scores`, `axis_rank`, `confidence` | 축별 0~1 백분위·순위, 해당 축 query 구간 일치율. 로그·진단용, 표시 금지, 순서에 쓰지 않음 |
| `range_fit`, `recommended_key` | 음역/발성 적합도 상세와 추천 키(`-3..+3`반음). 둘은 함께 null이거나 같은 키여야 한다. `measured-stability-v1`의 `score`는 측정된 음의 발성 점수이며 `measuredCoverage`는 측정 데이터가 덮는 곡 시간 비율이다. 안정 음역 경계가 없으면 `coverage`, `centerScore`, `spanFit`, `softFit`은 null일 수 있다. |
| `final_score` | 최종 점수. 클라이언트 계산 금지 |
| `scoring_version`, `composition_version` | 이벤트 로그에 함께 기록 |

`result.algorithm.composition` 에 DB 장르 정책 버전, 선택·중립·제외 후보 수와 선반별 후보·반환 수가 실린다. `language_gate` 는 구버전 응답 호환을 위해 남겨 두지만 항상 `enabled=false`, `excluded_count=0` 이다.
추천 취향은 카탈로그에 저장된 DB 장르명만으로 판정한다. `영어곡`은 DB `팝`(`global-pop`), `일본곡`은 `J-POP`(`etc-jpop`)과 같다. 선택 장르가 하나라도 있으면 같은 우선순위에서 음색·음역으로 정렬한다. 알려진 비선호 장르는 모든 선반(새로운 발견 포함)에서 제외하고, 장르 미상 곡만 뒤 채움으로 쓴다. 높은 점수로 비선호 장르를 예외 허용하지 않는다. `metadata.languages` 는 표시용이며 취향 판정에 쓰지 않는다. 운영 DB에서 `global-pop` 밖의 국제 장르 곡이 생기면 장르 코드 내보내기·카탈로그 재적용이 필요하다.

## 테스트

### TIMBRE MR 혼입 게이트

TIMBRE 녹음은 임베딩 전에 `mr_detection.detect_mr_suspected` 로 한 번 판정한다. 분류기가 아니라 에너지 게이트이며,
스피커로 MR 을 튼 게 분명한 녹음만 느린 보컬 분리 경로로 보낸다. 0.5초 창(0.25초 hop) 의 단시간 RMS 에서

- `lowEnergyFloorRatio = p20/p80 >= 0.30` — 노래하지 않는 구간의 음량이 노래 구간만큼 크다
- `dynamicRangeP95P10 = p95/p10 <= 8.0` — 녹음 전체의 음량 변화 폭이 좁다

두 조건을 **모두** 만족할 때만 `SUSPECTED` 다. 한쪽만 걸리는 헤드폰 녹음에 분리 지연을 얹지 않기 위한 것이다.
임계값은 `MICON_MR_ENERGY_FLOOR_RATIO`, `MICON_MR_MAX_DYNAMIC_RANGE` 로 조정한다.

판정과 근거는 `job.json` 의 `mrDetection` 에 남는다(`status`, `displayLabel`, `reasonCodes`, `metrics`,
`embeddingInput`, `preprocessingVersion`, 분리한 경우 `separation`). 추천 실험실 사이드바의 `MR 의심` 배지가 이 기록을 읽는다.

- `SUSPECTED` → 44.1kHz PCM16 으로 변환 후 Mel-Band 로 분리하고 보컬만 임베딩한다. `preprocessingVersion=sep-melband-kim-v1`.
- 그 외 → 원본을 그대로 임베딩한다. `preprocessingVersion=raw-clean-v1`.
- 콜백 `modelVersion` 은 `<checkpoint_sha256>+<preprocessingVersion>` 이라 어느 입력으로 만든 임베딩인지 사후에 구분된다.
- 사용자 녹음 분리에는 `allow_fallback=False` 를 쓴다. Mel-Band 가 실패하면 HTDemucs 로 조용히 내려가지 않고,
  실패 사유를 `mrDetection.separation` 에 남긴 뒤 원본 녹음으로 임베딩을 계속한다.



### 가수 ID 로컬 스냅샷 및 표시용 가수 이름

사용자 정정에 따라 **ID만 반환하도록 전환한 작업은 철회했다.** 추천 곡의 `title`·`artist`,
상태 카탈로그의 가수 이름, 문자열 `preferred_artists`는 기존 표시 계약을 유지한다.
닮은 가수는 `artistId`(실제 `singer.singer_id`)로 집계하고 `artistName`, `rank`,
`similarity`, `ecapaScore`, `singerByolScore`를 함께 반환한다.
추천 곡 및 상태 카탈로그의 `artist_ids: [singer_id]`는 표시용 `artist`에 추가하는 식별 정보다.
선호 가수 이름 입력과 출력은 유지하며 `preferred_artist_ids`를 보조로 제공한다.
기존 저장 추천/검색 job도 GET 시 표시 이름을 유지한다. 내부 요청 payload·벡터·callbackBody 비공개는 유지한다.
ID는 정확한 곡–가수 DB 관계에서 가져오며 표시 이름으로 추측하지 않는다.

임베딩 인덱스의 표시용 이름은 추천 실험실 지도에서 계속 사용한다. 벡터를 다시 추출하지 않는다.
ID는 같은 인덱스 디렉터리의 `artist_ids.json`에서만 읽으며 검색 중 DB 조회나 이름 기반 ID 추측을 하지 않는다.
스냅샷은 `schemaVersion=1`, `catalogVersion`, `sourceRevision`, `indexSha256` 및 전체 곡 매핑을 가진다.
배치/해시/곡 집합/양수 BIGINT ID가 맞지 않거나 누락되면 검색을 실패시킨다.
현재 898곡은 곡당 MAIN 가수 한 명이므로 해당 정책만 지원하며, 복수 가수/피처링은 조용히 임의 선택하지 않는다.

카탈로그 배포 시 DB의 `song_singer JOIN singer`를 읽기 전용으로 export하고,
이름 없는 JSON 배열 `[{"songId":"<DB 문자열 ID>","artistId":396,"role":"MAIN"}, ...]`을
아래 도구의 stdin으로 전달한다. DB 연결/인증 정보는 실행 중인 AI에 추가하지 않는다.

```powershell
python scripts/export_artist_catalog.py --catalog <index-directory> --revision <active-revision>
```

기존 스냅샷은 도구가 덮어쓰지 않는다. 다음 카탈로그는 새 디렉터리에서 완전성 검사 후 전환한다.
같은 catalogVersion의 DB 관계가 변경됐다면 별도 메타데이터 revision/재배포 정책을 협의해야 한다.
`GET /recommendations/status`의 `artistCatalog`로 로컬 매핑 준비 상태·개수·배치를 확인한다.

닮은 가수 job/입력/콜백은 `workspace/artist-search/jobs/<jobId>/job.json`에 보존한다.
계산 `status`와 전달 `deliveryStatus`(`NOT_READY`, `PENDING`, `DELIVERING`, `ACCEPTED`,
`REJECTED`, `PENDING_RETRY`)를 구분한다. `deliveryFailureCode`는 전달 장애이며 계산 실패 코드와 별개다.
`COMPLETED`만 보고 BE 저장 성공으로 판단하면 안 된다.

- `POST /artist-search-jobs/{jobId}/retry`: 계산 실패 또는 계산 완료·콜백 미수락 작업을 재계산한다.
- `POST /artist-search-jobs/{jobId}/redeliver`: 보존된 유효 콜백을 재추론 없이 재전송한다. 이미 수락된 결과는 다시 보내지 않는다.
- null ID인 이전 결과는 재전송하지 않고 ID 매핑 수정 후 재계산해야 한다.
- 재시작 때 job은 복원하지만 이전 콜백을 자동 전송하지 않는다. BE의 현재 attempt/resultVersion 확인 후 명시적으로 복구한다.
- 입력 부족은 BE가 지원하는 `FAILED` 상태와 `failureCode=INSUFFICIENT_DATA`로 전달한다.

AI 수정과 별도로 BE 검색 활성화, 정체 작업 복구·시간 제한, 최신 attempt/버전 검증이 필요하다.
보존 job에는 사용자 임베딩이 있으므로 운영 내부 접근 제어·보존 기간 정책을 적용하고 외부로 공개하지 않는다.

### 통합 곡 상세의 `/song-fit` 계약

`song_fit_api.py`의 동기 `POST /song-fit`은 BE가 전달한 `recommendationScores`를 반환한다(표시용 가수 이름도 유지).
기존 `overall`, `range`, `timbre`, `rhythm`, `algorithmVersion` 외에 선택 필드
`timbreAxis`(`ECAPA` / `SINGER_BYOL` / `CONSENSUS`)와 `timbreLabel`이 포함될 수 있다.
두 필드가 없는 구버전 요청도 지원하며, `AVAILABLE` / `UNAVAILABLE` 모두 설명과 점수를 변형하지 않는다.
새 임베딩 추론이나 추천 생성은 실행하지 않는다. `songId`, `analysisId`, `selectedKeyOffset`은 요청과 일치해야 한다.

`burdenSections`는 곡의 주의 구간이다. 앱은 원키 목표 음표와 측정 음역을 비교해
선택 키의 개인 음역 밖 구간을 별도로 표시한다. 상세는 [통합 계약](../../docs/song-detail-integration.md)을 참고한다.

### 실행

```powershell
.venv\Scripts\python.exe -m pytest tests
```

## 폴더

| 경로 | 내용 |
|---|---|
| `app.py` | FastAPI 진입점 |
| `recommendation_api.py` | 라우트·잡 처리 |
| `timbre_recommender/` | 임베딩·카탈로그·음역 점수·순위 |
| `pitch_engine.py`, `register_engine.py`, `pitch_profile.py` | 사용자 녹음 → 음역 프로파일 (CREPE + pYIN) |
| `separation.py`, `build_audio_catalog.py`, `build_range_catalog.py` | 곡 적재 (오프라인) |
| `third_party/ssl-singer-identity/` | Singer-BYOL 공식 코드 사본 (MIT, revision 고정) |
| `recommender-assets/` | 카탈로그 인덱스, 모델 출처, 선곡 정책 |
