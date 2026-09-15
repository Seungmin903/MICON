# 노트북 FastAPI 온보딩 통합

## 기준과 적용 범위

- 2026-09-15 기준: MR !110이 병합된 develop `cfcff0a`를 반영했다. 카탈로그 내부 경로와 콜백 미수신 상태, 추천 입력의 두 모델 전달을 유지하면서 아래 보완을 적용한다.
- 작업 브랜치: `codex/audio-ai-integration-20260915` (MR !112). 이 문서의 온보딩 보완은 MR !112가 병합되어야 develop에 반영된다.
- 사용자 확인: 두 온보딩 경로는 **음역 가이드 측정 + 곡 녹음 음색 분석**. 회원 온보딩은 Android → BE → 노트북 FastAPI로 연결한다.
- 요구사항 출처: [Notion API v2](https://app.notion.com/p/API-v2-3d801cbc36308058aed0eaa5d77dee72)의 API 데이터베이스. 문서 하단의 과거 설명보다 DB 행을 우선했다.
- Notion/Jira는 수정하지 않았다. API DB에 반영할 항목은 아래에 명시했다.

## 최종 데이터 흐름

```text
Android 음역 가이드: 기존 guided-range-v7 WAV + attempts/sampling/registerEvents
  → POST /api/v1/voice-analysis-sessions/{sessionId}/range-analysis
  → BE multipart 중계 → POST /voice-analysis-jobs (RANGE)
  → 별도 Python 프로세스의 guided-range 모델 검증
  → BE 컴포넌트 콜백 → GET 세션 /result → 기기 결과 저장 → 로컬 WAV 삭제

Android 곡 녹음: 48kHz 우선, 44.1kHz 대체 / mono PCM16 WAV
  → 같은 sessionId의 POST /api/v1/voice-analysis-sessions/{sessionId}/analysis
  → BE multipart 중계 → POST /voice-analysis-jobs (TIMBRE)
  → ECAPA 192차원 + Singer-BYOL 1000차원
  → BE 컴포넌트 콜백 → GET 세션 /result → 명시적 프로필 저장 → 로컬 녹음 삭제

저장된 프로필 → BE가 두 모델 임베딩·음역을 JSON으로 POST /recommendations
  → AI가 숫자 DB song_id로 응답 → BE 결과 저장 → Android 조회
```

**온보딩 사용자 녹음은 S3에 업로드하지 않는다.** BE의 multipart 임시 파일과 AI 작업 디렉터리를 사용한다. 곡 카탈로그의 S3 저장은 별개의 기존 기능이다. 비회원 음역은 기존 기기 내 측정 경로를 유지한다.

AI의 202는 파일·작업 ID를 로컬 디스크에 기록한 뒤 반환한다. 임베딩 계산 완료를 뜻하지 않는다. BE의 RANGE 요청 메타에는 `sampling`, `registerEvents`를 추가했고, TIMBRE는 `meta.recording`에 곡·녹음 구간 메타를 전달한다.

## 실행 준비

저장소 전체를 노트북에 둔다. RANGE는 `ai/vocal-scope/model-server`의 실제 코드를 별도 프로세스로 실행한다. 이로써 두 서비스에 각각 있는 `pitch_engine.py` 이름 충돌을 피한다.

```powershell
cd ai/recommend-server
git lfs pull --include="ai/recommend-server/recommender-assets/catalog-index/*"
./setup.ps1
# CUDA가 필요한 노트북은 호환 드라이버 확인 후 ./setup.ps1 -TorchIndex cu128
```

설치에는 Python 3.11과 uv가 필요하다. setup은 자체 .venv, 모델 체크포인트, 실행 설정을 만든다. 이 작업에서 사용한 모델 라이브러리 조합은 torch/torchaudio 2.7.1 + torchvision 0.22.1이다. [PyTorch 공식 조합](https://pytorch.org/get-started/previous-versions/#v271)을 따르며 기존 2.13.0/2.11.0 혼용을 제거했다. 새 노트북에 모델 설치·다운로드까지 실행한 것은 아니다.

아래 주소·토큰은 운영자가 실제 값으로 설정한다. 토큰은 BE와 동일한 값을 사용한다.

```powershell
$env:AI_SERVICE_TOKEN = '<BE와 공유하는 서비스 토큰>'
$env:MICON_BE_BASE_URL = 'http://<AI에서 접근 가능한 BE 내부 호스트>:8080'
$env:MICON_BE_CALLBACK_URL = 'https://<BE 호스트>/api/v1/callbacks/voice-analysis-components'
$env:RECOMMEND_CONCURRENCY = '1'
# RANGE를 다른 가상환경에서 실행할 때만 지정
# $env:MICON_RANGE_MODEL_PYTHON = 'C:/.../range/.venv/Scripts/python.exe'
./start-laptop.ps1 -Port 8788
```

BE 설정:

```text
AI_RECOMMENDATION_BASE_URL=http://<BE에서 접근 가능한 노트북 주소>:8788
AI_SERVICE_TOKEN=<동일한 토큰>
AI_VOICE_ANALYSIS_ENABLED=true
AI_VOICE_ANALYSIS_MAX_WAIT=10m
AI_RECOMMENDATION_POLLING_MAX_WAIT=10m
```

앱의 API_BASE_URL은 BE 주소다. AI 토큰·노트북 주소를 앱에 넣지 않는다. 기존 외부 BE에서 노트북까지 라우팅할 수 있어야 하며, localhost는 해당 프로세스가 실행되는 컴퓨터 자신이다. 저장소 .gitignore가 .env/.env.*를 제외한다. start-laptop.ps1은 현재 프로세스의 환경변수를 읽으며 .env를 자동 로드하지 않는다.

`MICON_BE_BASE_URL`은 `/api/v1` 없이 BE 내부 주소를 지정한다. AI는 여기에 `/internal/embedding-catalog/active`를 붙인다. 공개 Nginx는 `/internal/`과 `/api/v1/internal/`을 모두 404로 차단하므로 노트북에서 BE 내부망 또는 터널로 접근할 수 있어야 한다. 위 콜백 예시는 공개 API 주소이며, 콜백도 내부 BE로 보낼 때는 `/callbacks/voice-analysis-components`를 사용한다.

동시에 여러 uvicorn worker를 실행하지 않는다. 현재 멱등 요청 조회·동시 실행 제한은 단일 프로세스를 전제로 한다.

## 준비 상태 확인

1. `GET /health` 200으로 프로세스 생존을 확인한다. 모델 준비 완료 판정은 아니다.
2. 서비스 토큰을 넣은 `GET /recommendations/status`로 모델·인덱스 누락과 카탈로그 연결 상태를 확인한다.
3. BE 내부 주소의 `GET /internal/embedding-catalog/active`에서 `catalog_track_id ↔ song_id` 짝표를 받는다. 짝표가 없으면 운영 JSON 추천은 503으로 거절한다. 조회 실패 뒤에는 추천 접수 또는 상태 확인 시 최대 30초에 한 번 재조회한다.
4. 추천의 파일 인덱스에는 음색 임베딩뿐 아니라 RangeCatalog가 읽는 곡 음역 프로파일도 있어야 한다. embedding_catalog 어댑터만 실행하면 곡 음역 프로파일이 새로 생성되는 것은 아니다.
5. BE → 노트북 접수와 노트북 → BE 콜백을 모두 실제 주소에서 확인한다.

서버 배포용 recommend-server 이미지는 RANGE sibling worker도 포함한다. 노트북 경로는 GPU 또는 현장 마이크 검증이 필요한 경우에 사용하고, 서버 상시 운영 절차는 [추천 AI 컨테이너 실행](RECOMMENDATION_CONTAINER_RUNTIME.md)을 따른다.

## 재시도와 삭제

| 상황 | 처리 |
|---|---|
| 앱이 접수 응답을 놓침 | 로컬 저장한 같은 requestId·WAV로 재전송. AI는 같은 jobId 반환 |
| 같은 requestId로 다른 오디오·메타 전송 | AI 409 |
| RANGE의 DISPATCH_UNKNOWN | BE가 같은 요청의 재전송 허용 |
| 앱이 확정 실패 결과를 받음 | 후속 시도에 새 요청 ID. 전송 불명 상태와 구분 |
| AI 프로세스 재시작 | 디스크 작업 복원, 미완료 계산 재시작, 미수신 콜백 재전송 |
| BE 콜백 일시 실패 | `CALLBACK_PENDING`으로 보관하고 디스크 outbox에서 최대 60초 간격 재시도. 동일 요청 재접수 시 미수신 콜백만 백그라운드 전송 |
| AI 원본 정리 | 모든 콜백 수신 후 기본 1시간 또는 접수 후 기본 24시간 중 빠른 시점 |
| 사용자 취소 | queued 작업 실행 방지, 실행 중 작업은 종료 후 오디오 삭제 |
| 추천 조회 404 등 영구 실패 | BE FAILED 전환. 408/429·네트워크 오류는 지연 재시도 |
| 추천이 계속 미완료 | DB에 저장된 생성 시각부터 기본 10분 후 FAILED |

TIMBRE 콜백은 두 모델의 차원·버전·유한한 비영벡터를 검증한다. 한 컴포넌트만 완료돼서는 세션이 저장 가능해지지 않는다. 추천 요청은 저장된 두 모델 결과를 모두 읽는다.

## 기존 보고서 12항목의 현재 상태

| 기존 항목 | 이번 기준의 처리 |
|---|---|
| BE JSON ↔ AI multipart 추천 불일치 | 팀원 JSON 어댑터 재사용. BE가 실제 저장된 두 모델 벡터를 JSON으로 전달하도록 보완 |
| voice-analysis-jobs·취소 미구현 | 팀원 TIMBRE 구현 재사용 + RANGE worker·재시작·콜백 재전송 추가 |
| 앱 분석 제출 로컬 전용 | 회원 온보딩은 실제 BE API 연결. 일반 녹음 리포트 화면의 별도 legacy 경로는 아래 제한 참고 |
| 문자열 곡 ID ↔ Long 불일치 | 활성 카탈로그 짝표로 숫자 DB ID 변환. TJ 번호에서 DB ID 추측하지 않음 |
| 임베딩 없는 완료 | 최신 BE 컴포넌트 검증에 비영벡터·overflow 검증, 추천 입력 양쪽 모델 필수화 추가 |
| DB 트랜잭션 내 원본 삭제 | 최신 develop의 직접 업로드·삭제 작업 구조 재사용. 온보딩 S3 원본 자체를 만들지 않음 |
| 추천 무한 대기 | 4xx 종료, 일시 실패 지연, 영속 생성 시각 기준 제한 시간 |
| 음색용 16kHz 우선 | 음색 녹음 48/44.1kHz만 사용. YIN은 별도 감소 신호로 분석 |
| 구간 메타 전달 누락 | TIMBRE recording 전체 메타, RANGE sampling/registerEvents 전달 |
| 빠른 콜백 경합 | 요청 ID 선저장 재사용 + RANGE/TIMBRE의 완료·진행 상태가 늦은 접수 응답으로 퇴행하는 경로 차단. RUNNING 등 기존 AI 작업의 접수 응답도 허용 |
| 카탈로그 adapter ID 누락 | 명시적 song_id·최대 12구간 반영, 실제 추천기 연결 시험 |
| CREPE 표기 full 고정 | 실행한 모델 크기·device·sample rate를 결과에 기록 |

### 아직 검증하지 않은 범위

- 실제 Android 마이크의 샘플레이트 협상, BLE/이어폰 입력, 노트북 네트워크, 실제 ECAPA/Singer-BYOL/CREPE 추론 결과·소요 시간. 합성 계약 테스트와 실사용 음질 검증은 별개다.
- AAC/M4A 압축 품질 비교. 현재 온보딩 접수는 WAV만 지원한다. 압축 형식 전환을 완료했다고 볼 수 없다.
- 온보딩 밖의 일반 `BackendSongAnalysisSubmitter`는 여전히 `/recordings → /voice-analyses` 리포트 경로를 사용한다. 최신 BE에서 해당 분석 API와 맞지 않으며, 일반 노래 평가 리포트까지 이 변경으로 완료됐다고 볼 수 없다. 본 변경은 사용자가 확인한 두 온보딩 경로에 적용된다.
- 노트북 상시 가동·배포·외부 접속 설정은 실행하지 않았다.

## 검증 기록 (2026-09-15)

| 검증 | 결과 |
|---|---|
| Backend Gradle 전체 | 기존 151 통과. !110 충돌 해결 후 BE 소스·테스트는 검증 당시와 동일하며 Gradle test도 UP-TO-DATE로 성공 |
| Android debug JVM 전체 | 291개 중 288 통과, 기존 3개 skip, 실패 0 |
| AI recommend-server unittest 전체 | !110 통합 후 78 통과 |
| embedding_catalog loader + 실제 추천기 adapter | 13 통과, S3는 로컬 mock 사용 |
| Windows / Python 3.11 requirements 해석 | uv pip compile 성공 |
| 실제 uvicorn 프로세스 로컬 기동 | health 200, 토큰 없는 보호 경로 401, 확인 후 종료 |

추가 검증은 BE 실제 HTTP multipart, FastAPI multipart 수신, Android 접수 실패 후 새 gateway 인스턴스 재시도, 공통 RANGE fixture의 실제 Python 스키마 검증, 콜백 디스크 복원·삭제, 추천 실패 종료를 포함한다. 모델 추론을 대신하는 mock을 사용하는 계약 테스트가 포함되어 있다.

!110 통합 검증에는 `CALLBACK_PENDING`의 재시작 복원, 동일 요청의 미수신 콜백만 재전송, 콜백 전송 중 취소 상태 유지, 내부 카탈로그 경로와 서비스 토큰 전달을 포함한다. Android·카탈로그 어댑터는 이번 충돌 해결에서 변경하지 않아 기존 검증 결과를 유지한다. BYOL 누락 소스 복구는 별도 MR !114에서 관리한다.

Android 검증 환경의 Gradle 9.5/Windows 한글 경로 문제는 `--no-configuration-cache "-Dorg.gradle.jvmargs=-Xmx4096m -Dfile.encoding=MS949"` 실행 옵션으로 해결했다. 제품 빌드 설정에는 이 PC 전용 옵션을 넣지 않았다. [Gradle 관련 이슈](https://github.com/gradle/gradle/issues/30391).

## API DB 반영 항목

1. FE → BE range-analysis: guided-range-v7의 `sampling` 필수, `registerEvents` 전달. [공통 예시](../contracts/onboarding-range-v7.json).
2. BE → AI voice-analysis-jobs: `analysisType=RANGE|TIMBRE`, 명시적 components, WAV 하나. TIMBRE의 전체 녹음 메타는 `recording` 안에 전달.
3. 통합 세션 result의 range: `noteResults` 추가. FE가 모델 검증 결과를 복원하고 같은 세션에서 곡 녹음을 이어 간다.
4. BE → AI recommendations: 양쪽 모델 userEmbedding JSON과 숫자 DB song_id 짝표 필수.

이 항목들은 로컬 구현·공통 fixture에 반영했고 Notion 데이터베이스 자체를 편집하지 않았다.
