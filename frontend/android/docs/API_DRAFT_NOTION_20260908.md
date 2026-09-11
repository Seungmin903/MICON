# MICON API 계약 및 Android 연동 현황

> 최초 작성: Notion API 명세 스냅샷(2026-09-08)  
> 최신 검증: 2026-09-10, `origin/develop` `65e5fa6`  
> 검증 대상: `docs/openapi.yaml`, Spring 컨트롤러·요청/응답 DTO, Android Retrofit API·Repository·ViewModel

이 문서는 Notion 초안을 그대로 복사한 문서가 아니라, 현재 `develop` 코드에서 확인되는 실제 계약과 Android 연동 상태를 정리한 프론트 작업 기준이다.

## 1. 기준과 주의사항

- Android의 백엔드 기본 URL은 `http://10.0.2.2:8080/api/v1/`이다. 운영에서는 Nginx가 `/api/v1/` 요청을 백엔드 컨트롤러 경로로 전달한다.
- 백엔드 성공 응답은 `ApiResponse<T>{code,message,data}`이며 성공 `code`는 `SUCCESS`다.
- 일반 인증 요청은 `Authorization: Bearer <accessToken>`을 사용한다.
- 토큰 갱신은 최신 OpenAPI, 실제 백엔드, Android 모두 `Authorization: Bearer <refreshToken>`을 사용한다. 2026-09-08 초기 초안의 `X-Refresh-Token` 방식은 폐기됐다.
- `docs/openapi.yaml`에는 70개 경로, 84개 operation이 있으나 다수 항목의 작업 상태와 응답 스키마가 초기 초안 상태다. 현재 동작 판단은 Spring 컨트롤러와 DTO를 우선한다.
- 비동기 여부와 HTTP 상태는 엔드포인트마다 다르다. 모든 생성 요청이 `202 Accepted`인 것은 아니다.

## 2. 현재 백엔드 구현 현황

### 2.1 회원·인증·선호 정보

| Method | Endpoint | 상태 | 비고 |
|---|---|---|---|
| POST | `/auth/signup` | 구현 | 이메일 회원가입 후 토큰 반환, `201 Created` |
| POST | `/auth/login` | 구현 | 이메일 로그인 |
| POST | `/auth/refresh` | 구현 | Refresh Token을 Bearer 헤더로 전달 |
| POST | `/auth/logout` | 구현 | 현재 세션 로그아웃 |
| POST | `/auth/password-reset-requests` | 구현 | 인증 코드 요청 |
| POST | `/auth/password-reset-verifications` | 구현 | 인증 코드 검증 후 resetToken 반환 |
| POST | `/auth/password-resets` | 구현 | `X-Password-Reset-Token` 헤더 사용 |
| GET | `/users/me` | 구현 | 내 정보 조회 |
| DELETE | `/users/me` | 구현 | body에 `currentPassword` 필요 |
| GET | `/genres` | 구현 | Access Token 필요 |
| GET | `/artists?query&page&size` | 구현 | Access Token 필요 |
| GET / PUT | `/users/me/preferences` | 구현 | 장르·아티스트와 온보딩 완료 상태 조회/저장 |

기존 `/auth/sessions`, `/users`, `/auth/tokens/refresh`, `/auth/sessions/current` 경로는 alias 없이 제거됐다. Android도 새 경로로 함께 전환되어 있다. 로그인·회원가입·사용자 확인·로그아웃·토큰 자동 갱신과 선호 정보 API까지 연동되어 있고, 토큰은 `EncryptedSharedPreferences`에 저장한다.

### 2.2 곡·적합도·추천

| Method | Endpoint | 상태 | 핵심 응답/제약 |
|---|---|---|---|
| GET | `/songs?query&page&size` | 구현 | `songId: Long`, 제목, `artists`, 이미지, 길이 |
| GET | `/songs/{songId}` | 구현 | 앨범, 원키, BPM, 장르, 음역, 찜 여부, `practiceReady` |
| GET | `/songs/{songId}/fit?analysisId&keyOffset` | 구현 | 사용자 분석 기준 적합도·추천 키 |
| POST | `/recommendations` | 구현 | 최신 음성 분석을 사용하며 `202 Accepted` |
| GET | `/recommendations/latest?mode&sort` | 구현 | 최신 추천 shelf 조회 |
| GET | `/recommendations/{recommendationId}` | 구현 | 추천 작업 및 결과 조회 |
| GET | `/recommendations/{recommendationId}/songs/{songId}` | 구현 | 곡별 추천 상세 |
| GET | `/charts/popular` | 미구현 | OpenAPI에만 존재 |

Android의 곡 목록·상세는 아직 번들 `micon_catalog.sqlite`를 사용한다. 추천은 로컬 계산 또는 설정된 경우 AI 추천 서버(`:8788`)에 직접 연결하며, Spring 추천 API에는 아직 연결되지 않았다.

### 2.3 녹음·음성 분석·리포트

| Method | Endpoint | 상태 | 비고 |
|---|---|---|---|
| POST | `/recordings` | 구현 | multipart `file`, `attemptNo`, `keepOriginal`; `201 Created` |
| GET | `/recordings` | 구현 | 페이지 목록 |
| GET | `/recordings/{recordingId}` | 구현 | 소유자 녹음 정보 |
| GET | `/recordings/{recordingId}/content` | 구현 | 재생용 응답 |
| DELETE | `/recordings/{recordingId}` | 구현 | 개별 원본 삭제 |
| POST | `/voice-analyses` | 구현 | `{recordingId, method, songId?}`; `201 Created` |
| GET | `/voice-analyses/{analysisId}` | 구현 | 분석 상태 조회 |
| POST | `/voice-analyses/{analysisId}/cancel` | 구현 | 진행 중 분석 취소 |
| POST | `/voice-analyses/{analysisId}/retry` | 구현 | 실패 분석 재시도 |
| GET | `/voice-analyses` | 구현 | 분석 이력 페이지 |
| GET | `/voice-analyses/latest` | 구현 | 최신 분석 요약, 없으면 `data=null` |
| GET | `/voice-analyses/{analysisId}/report` | 구현 | 상세 리포트 |
| DELETE | `/voice-analyses/{analysisId}` | 구현 | 분석 데이터 개별 삭제 |
| GET | `/users/me/voice-analysis-summary` | 미구현 | `/voice-analyses/latest`를 우선 사용 |
| GET | `/voice-measurement-guide` | 미구현 | OpenAPI에만 존재 |

일반 앱의 간편 분석과 리포트는 아직 `UserStore.VoiceProfile` 및 AI 엔진 직결 흐름을 사용한다. 별도의 독립 debug 음역 측정 모드는 Android에서 단계별 측정·WAV 저장을 수행한 뒤 PC의 `/preview/guided-range` 모델 서버에 직접 전송하며, Spring 녹음·분석 API나 공통 `UserStore`에는 연결되지 않는다. 위 Spring 녹음·분석 API용 Android Retrofit 인터페이스와 Repository는 없다.

### 2.4 연습 준비·세션·평가

| Method | Endpoint | 상태 | 실제 계약 |
|---|---|---|---|
| GET | `/songs/{songId}/practice-assets?selectedKey` | 구현 | 반주 메타데이터, 가사, 목표 음표, 데이터 버전, 구간 |
| GET | `/songs/{songId}/practice-guide?selectedKey` | 구현 | `difficultyType != NONE`인 구간 |
| POST | `/practices` | 구현 | 세션 생성 |
| GET | `/practices/{practiceId}` | 구현 | 본인 세션 상세 |
| POST | `/practices/{practiceId}/complete` | 구현 | 종료 시간·분석 연결 |
| POST | `/practices/{practiceId}/cancel` | 구현 | 진행 중 세션 취소 |
| PUT | `/practices/{practiceId}/performance` | 구현 | 종합/음정/유지/리듬 점수와 구간 평가 저장 |
| GET | `/practices/{practiceId}/analysis` | 구현 | `status`, `retryable` |
| POST | `/practices/{practiceId}/analysis/retry` | 구현 | 실패 결과 재시도 |
| GET | `/practices/{practiceId}/report` | 구현 | 점수·요약·구간·최약 구간 |
| GET | `/practices?date&page&size` | 구현 | 날짜 필터가 가능한 기록 페이지 |

#### 세션 생성 요청

```json
{
  "songId": 1,
  "recordingId": 10,
  "sectionId": null,
  "selectedKey": 0,
  "playbackSpeed": 1.0,
  "practiceScope": "FULL_SONG",
  "customStartMs": null,
  "customEndMs": null
}
```

- `songId`와 `recordingId`는 필수 `Long`이다.
- `selectedKey` 범위는 -12~12, `playbackSpeed`는 0.50~2.00이다.
- `practiceScope`는 `FULL_SONG`, `SECTION`, `CUSTOM`이다.
- `SECTION`은 해당 곡의 `sectionId`가 필요하다.
- `CUSTOM`은 유효한 `customStartMs < customEndMs`가 필요하다.
- 선택 키에 맞는 `READY` 반주가 DB에 없으면 `UNSUPPORTED_PRACTICE_KEY`가 발생한다.

#### 평가 결과 저장 요청

```json
{
  "status": "COMPLETED",
  "overallScore": 82.5,
  "pitchScore": 85.0,
  "sustainScore": 79.0,
  "rhythmScore": 81.0,
  "summaryText": "음정은 안정적이며 긴 음의 유지가 필요합니다.",
  "sections": [
    {"sectionId": 100, "score": 74.0, "measured": true, "summary": "후렴 고음 보완"}
  ]
}
```

- 현재 `performance` API는 프레임별 타임라인이나 반복 회차를 받지 않는다. 계산을 끝낸 점수와 최대 20개 구간 평가를 저장한다.
- `status=COMPLETED`이면 네 가지 점수가 모두 필요하다.
- 현재 서비스 구현상 세션을 먼저 `complete`한 뒤 `performance`를 저장해야 한다.
- `analysis`는 저장된 결과가 없으면 `ANALYZING`, 실패 결과면 `retryable=true`를 반환한다.

#### Android 현재 상태

- `PracticeViewModel`은 번들 SQLite에서 곡·음표·가사를 읽고 AI 엔진에서 반주를 재생한다.
- 실시간 채점은 Android 내부 `LiveScorer`가 수행한다.
- 종료 후 WAV를 AI 엔진 `/jobs/{id}/compare`로 직접 전송한다.
- 평가 탭은 AI 엔진 `GET /jobs?kind=performance`를 사용한다.
- 기록 삭제도 Spring API가 아니라 AI 엔진 `DELETE /jobs/{id}`를 사용한다.
- Spring의 `practice-assets`, `/practices`, 기록·리포트 API를 호출하는 Android Retrofit/Repository 구현은 아직 없다.

따라서 백엔드 기능은 구현됐지만, 연습·평가 화면의 Spring 연동은 미완료다.

### 2.5 찜·세트리스트·설정·데이터 관리

| 영역 | 구현된 Endpoint | Android 상태 |
|---|---|---|
| 찜 | `GET /users/me/favorites`, `PUT/DELETE /users/me/favorites/{songId}` | `UserStore` 임시 상태 사용 |
| 세트리스트 | `POST/GET /setlists`, `GET/PATCH/DELETE /setlists/{id}`, 곡 추가·삭제·순서 변경 | `UserStore` 임시 상태 사용 |
| 설정 | `GET/PATCH /users/me/settings` | 화면 로컬 상태; 백엔드는 `saveOriginalRecording`만 저장 |
| 정책 | `GET /policies` | 백엔드 구현, Android 미연동 |
| 전체 음성 삭제 | `POST /users/me/voice-data-deletions`, `GET /users/me/voice-data-deletions/{id}` | Android 미연동 |
| 동의 | `GET/PATCH /users/me/consents` | 백엔드 미구현 |

카운트인과 진동은 Android 연습 화면에서만 관리된다. 현재 백엔드 설정 DTO에는 `saveOriginalRecording`만 존재한다.

## 3. OpenAPI와 실제 구현의 주요 차이

| 항목 | OpenAPI/초안 | 현재 구현 |
|---|---|---|
| 인증 경로 | 기존 초안의 `/auth/sessions`, `/users`, `/auth/tokens/refresh`, `/auth/sessions/current` | `/auth/login`, `/auth/signup`, `/auth/refresh`, `/auth/logout` |
| Refresh Token 헤더 | 초기 초안은 `X-Refresh-Token` | 최신 OpenAPI·백엔드·Android 모두 `Authorization: Bearer <refreshToken>` |
| 음성 분석 생성 | `202 Accepted` 제안 | `201 Created` |
| 최신 음성 분석 | `/users/me/voice-analysis-summary` | `/voice-analyses/latest` 구현 |
| 연습 수행 기록 | 타임라인·반복 회차 설명 | 최종 점수와 구간 평가 저장 |
| 설정 | 원본 저장·카운트인·진동 | 원본 녹음 저장 여부만 서버 저장 |
| 연습 반주 응답 | 접근 URL을 기대할 수 있는 설명 | 현재 DTO 필드는 `filePath`; URL 발급 계약 확인 필요 |
| 상태 메타데이터 | 다수 `시작 전`/`미확인` | 실제 컨트롤러는 다수 구현 완료 |

OpenAPI에는 백엔드 컨트롤러가 없는 AI 내부 작업·콜백 경로도 포함되어 있다. 반대로 백엔드에는 OpenAPI에 없는 `/internal/embedding-catalog/active`가 존재한다.

## 4. Android 연동 완료/미완료 요약

| 기능 | 상태 | 현재 데이터 소스 |
|---|---|---|
| 로그인·회원가입·세션 복원·로그아웃·토큰 갱신 | 완료 | Spring 백엔드 |
| 선호 장르·아티스트 온보딩 | 완료 | Spring 백엔드 |
| 곡 목록·상세·연습 데이터 | 미완료 | 번들 SQLite + AI 엔진 |
| 추천 | 미완료 | 로컬 계산 또는 AI 추천 서버 직결 |
| 일반 앱 음성 측정·분석·리포트의 Spring 연동 | 미완료 | AI 엔진 + `UserStore` |
| 독립 debug 음역 측정 | 구현 | Android 로컬 측정 + PC `/preview/guided-range`; 운영·계정 저장 미연동 |
| 연습 세션·평가 결과·연습 기록 | 미완료 | Android 로컬 채점 + AI 엔진 직결 |
| 찜·세트리스트 | 미완료 | `UserStore` |
| 설정·정책·음성 데이터 삭제 | 미완료 | 화면 로컬 상태 또는 미연동 |

## 5. 연습·평가 프론트 작업 우선순위

1. 백엔드 `Long songId`와 앱/엔진의 `tj-<번호>` 문자열 ID 연결 규칙을 확정한다.
2. `PracticeApi`와 DTO/Repository를 추가해 `practice-assets`, 세션, 평가, 이력, 리포트를 호출한다.
3. 세션 생성에 연습 전 `recordingId`가 필요한 현재 계약을 백엔드와 재검토한다. 연습 중 생성되는 녹음을 어떻게 선행 등록할지 흐름이 불명확하다.
4. `practice-assets.instrumental.filePath`가 앱에서 직접 재생 가능한 URL인지, 별도 서명 URL API가 필요한지 확정한다.
5. Android 내부 `LiveScorer` 결과를 `performance` 요청의 네 가지 점수와 구간 평가로 변환한다.
6. `complete → performance 저장 → analysis 폴링 → report 조회` 순서를 실제 백엔드 상태 제약과 함께 통합 테스트한다.
7. 평가 탭의 엔진 `/jobs` 의존성을 `/practices` 이력·리포트로 교체한다.
8. 삭제 UX는 대상 계약을 확정한다. 현재 백엔드에는 연습 기록 삭제 API가 없고, Android는 엔진 작업 삭제를 사용한다.

## 6. 현재 미확정 또는 확인이 필요한 항목

- 연습 세션과 녹음 생성의 순서
- 연습 기록 삭제 API 필요 여부 및 녹음·분석 연쇄 삭제 범위
- `filePath`의 앱 접근 방식과 S3 서명 URL 발급 주체
- 곡 ID와 엔진 job ID 매핑
- 프레임별 수행 기록을 서버에 저장할지, 최종 점수만 저장할지
- `complete`와 `performance` 호출 순서 및 비동기 분석의 실제 책임 주체
- OpenAPI의 다수 `x-frontend-status`와 placeholder 응답을 실제 구현 상태로 갱신할지 여부
- 카운트인·진동 설정을 서버 동기화할지 Android 로컬 설정으로 유지할지 여부

## 7. 관련 코드

- 계약 초안: `docs/openapi.yaml`
- 인증 확정 문서: `docs/AUTH_API.md`
- 백엔드 연습 API: `backend/src/main/java/com/micon/backend/practice/api/`
- 백엔드 녹음·음성 분석 API: `backend/src/main/java/com/micon/backend/voice/api/`
- Android 인증 API: `frontend/android/app/src/main/java/com/micon/app/data/backend/`
- Android 연습: `frontend/android/app/src/main/java/com/micon/app/ui/practice/`
- Android 평가: `frontend/android/app/src/main/java/com/micon/app/ui/evaluation/`
- Android 임시 사용자 상태: `frontend/android/app/src/main/java/com/micon/app/data/local/UserStore.kt`
