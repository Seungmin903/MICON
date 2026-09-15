# 통합 목소리 분석 API

Android의 목소리 분석 온보딩은 한 세션에서 기기 음역 결과와 노래 녹음의 음색 분석을 결합한다.
아래 경로는 애플리케이션의 `/api/v1/` 기준 상대 경로다.

## 호출 순서

1. `POST /voice-analysis-sessions`로 회원 분석 세션을 생성한다.
2. `PUT /voice-analysis-sessions/{sessionId}/range-result`로 `guided-range-v7` 기기 측정 결과를 저장한다.
3. `POST /voice-analysis-sessions/{sessionId}/analysis`에 노래 WAV와 메타데이터를 multipart로 제출한다.
4. `GET /voice-analysis-sessions/{sessionId}/result`를 분석이 끝날 때까지 조회한다.
5. 회원이 저장을 선택하면 `POST /voice-analysis-sessions/{sessionId}/profile`을 호출한다.

회원 요청에는 `Authorization: Bearer <accessToken>`이 필요하다. 세션 생성과 분석 제출은 게스트도
지원하지만, 통합 결과 조회와 프로필 저장은 회원 전용이다.

## 기기 음역 결과 저장

```http
PUT /voice-analysis-sessions/{sessionId}/range-result
Content-Type: application/json
```

```json
{
  "measurementId": "device-range-uuid",
  "measuredAt": "2026-09-14T12:00:00",
  "protocolVersion": "guided-range-v7",
  "lowMidi": 48,
  "highMidi": 72,
  "noteResults": [{ "midi": 48, "status": "passed" }],
  "measurementStatus": "AVAILABLE",
  "qualityStatus": "VALID"
}
```

동일 세션에 결과가 이미 있으면 기존 결과를 반환한다. 프로토콜 버전과 MIDI 범위가 올바르지 않으면
`400 INVALID_REQUEST`를 반환한다.

## 통합 결과 조회

```http
GET /voice-analysis-sessions/{sessionId}/result
```

응답은 음역, `ECAPA`·`SINGER_BYOL` 컴포넌트 상태, 닮은 가수 결과, 저장 가능 여부와
`artistSearchStatus`, `resultVersion`을 함께 반환한다. 가수 검색 결과가 0건이어도
`artistSearchStatus=NO_RESULT`로 완료 상태를 구분한다. 클라이언트는 `saveAllowed=true`이고 세션 상태가
`READY_TO_SAVE`일 때만 프로필 저장을 허용한다.

## 프로필 저장

```http
POST /voice-analysis-sessions/{sessionId}/profile
Content-Type: application/json
```

```json
{
  "resultVersion": 2
}
```

요청 버전이 현재 분석 결과와 일치해야 한다. 같은 세션을 다시 저장하면 기존 프로필 응답을 반환하며,
새 세션을 저장하면 사용자의 기존 음성 프로필을 새 세션으로 교체하고 버전을 증가시킨다.

실제 음색 완료 결과를 받으려면 백엔드의 `ai.voice-analysis-enabled=true` 설정과 AI 서버의
`ECAPA`, `SINGER_BYOL` 콜백이 필요하다.

## 명세 정리 및 구현 상태

| 방향 | Method | Endpoint | 판단 | 구현 상태 |
|---|---|---|---|---|
| 앱 → 백엔드 | POST | `/voice-analysis-sessions` | 필요 | 구현·테스트 완료 |
| 앱 → 백엔드 | GET | `/voice-analysis-sessions/active` | 필요 | 구현·테스트 완료 |
| 앱 → 백엔드 | PUT | `/voice-analysis-sessions/{sessionId}/range-result` | 필요 | 구현·테스트 완료 |
| 앱 → 백엔드 | GET | `/songs/{songId}/practice-assets` | 녹음 가이드에 필요 | 기존 구현 사용 |
| 앱 → 백엔드 | POST | `/voice-analysis-sessions/{sessionId}/analysis` | 필요 | 구현·테스트 완료 |
| 앱 → 백엔드 | GET | `/voice-analysis-sessions/{sessionId}/result` | 필요 | 구현·테스트 완료 (`artistSearchStatus` 포함) |
| 앱 → 백엔드 | POST | `/{sessionId}/retry` | 삭제 대상 | 범위가 모호해 사용하지 않음 |
| 앱 → 백엔드 | POST | `/voice-analysis-sessions/{sessionId}/analysis/{analysisId}/components/{component}/retry` | 필요 | 구현·테스트 완료 |
| 앱 → 백엔드 | POST | `/voice-analysis-sessions/{sessionId}/analysis/{analysisId}/cancel` | 필요 | 구현·테스트 완료 |
| 앱 → 백엔드 | POST | `/voice-analysis-sessions/{sessionId}/profile` | 필요 | 구현·테스트 완료 |
| 앱 → 백엔드 | GET | `/users/me/voice-profile` | 저장 결과 복구에 필요 | 구현·앱 연결·테스트 완료 |
| 백엔드 → AI | POST | `/voice-analysis-jobs` | 필요 | 구현·테스트 완료 |
| 백엔드 → AI | GET | `/voice-analysis-jobs/{jobId}` | 운영 진단용 | 구현, 앱 흐름에서는 미사용 |
| 백엔드 → AI | POST | `/voice-analysis-jobs/{jobId}/retry` | 모델 재시도에 필요 | 구현·테스트 완료 |
| 백엔드 → AI | POST | `/voice-analysis-jobs/{jobId}/cancel` | 취소 전파에 필요 | 구현·테스트 완료 |
| AI → 백엔드 | POST | `/callbacks/voice-analysis-components` | 모델별 결과 콜백에 필요 | 구현·테스트 완료 |
| 백엔드 → AI | POST | `/artist-search-jobs` | 닮은 가수 검색에 필요 | 구현·테스트 완료 |
| 백엔드 → AI | POST | `/artist-search-jobs/{jobId}/retry` | 검색 재시도에 필요 | 구현·테스트 완료 |
| AI → 백엔드 | POST | `/callbacks/voice-analysis-components` | 가수 검색도 동일 콜백 사용 | 별도 콜백 API 추가 불필요 |
| AI → 백엔드 | GET | `/internal/embedding-catalog/active` | 활성 카탈로그 조회에 필요 | 구현·테스트 완료 |

`GET /voice-analysis-jobs/{jobId}`를 백엔드의 별도 폴링 복구 작업으로 연결하지 않는다. AI가 계산 결과를
저장한 뒤 콜백 실패를 `CALLBACK_PENDING`으로 보관하고 자동 재전송하므로, 백엔드 폴링까지 추가하면
동일 결과의 복구 주체가 중복된다.
