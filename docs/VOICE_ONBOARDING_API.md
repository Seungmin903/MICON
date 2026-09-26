# 통합 목소리 분석 API

Android의 현재 첫 온보딩은 `guided-range-v8` 자유 음역 측정(기기 미리보기) 후
`holo-v1` 노래 녹음으로 이어진다. 최종 세션에서는 기기 음역 결과와 노래 녹음의 음색 분석을 결합한다.
아래 경로는 애플리케이션의 `/api/v1/` 기준 상대 경로다.

## 호출 순서

1. `POST /voice-analysis-sessions`로 회원 분석 세션을 생성한다.
2. `PUT /voice-analysis-sessions/{sessionId}/range-result`로 `holo-v1` 기기 측정 결과와 선택적 `voiceQuality`를 저장한다.
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
  "protocolVersion": "holo-v1",
  "lowMidi": 48,
  "highMidi": 72,
  "noteResults": [{ "midi": 48, "status": "passed" }],
  "measurementStatus": "AVAILABLE",
  "qualityStatus": "VALID",
  "voiceQuality": {
    "version": "personal-acoustic-v1",
    "referenceMidi": 57,
    "points": [
      { "midi": 55, "score": 62.5, "highChange": 0, "lowIrregularity": 0.5, "holdScore": 100 },
      { "midi": 57, "score": 100, "highChange": 0, "lowIrregularity": 0, "holdScore": 100 }
    ]
  }
}
```

`voiceQuality`는 생략할 수 있다. 이전 앱의 결과도 계속 받는다. 값이 있으면 버전, 기준음,
음별 오름차순, 0~100 점수와 구성 요소의 일관성을 검증한다. 동일 `measurementId`에
다른 점수를 재전송하면 `409 IDEMPOTENCY_KEY_REUSED`를 반환한다. 점수는
`voice_range_measurement.voice_quality_json`에 저장되고 세션·결과 조회 시 그대로 반환된다.
별도 `POST /range-analysis` 경로의 `guided-range-v8` 업로드에도 같은 선택적 점수를
첨부할 수 있으며, 백엔드는 AI 분석 입력과 분리해 보관한 뒤 콜백 결과에 결합한다.

이 점수는 첫 유지음에 대한 개인별 음향 비교다. 높은 음의 배음/주기성 변화와 낮은 음의
끊김/불규칙성을 반영하지만 가성 확률, 의학적 음성 평가, 확정된 테시투라가 아니다.
기기 계산식은 `clamp(100 - 75×highChange - 75×lowIrregularity, 0, 100)
× (0.65 + 0.35×holdScore/100)`이다. `highChange`는 기준음 대비 고차 배음 비율,
H1/H2 상승 및 주기성의 감소를, `lowIrregularity`는 주기성 저하, 두 배 주기 우세,
음정 흔들림과 발성 중 추적 끊김을 합친 0~1 지표다. 성대 폐쇄율은 마이크 파형만으로
직접 측정하지 않는다. 0.3초 이상 유지한 음만 점을 찍고 미측정 반음은 잇지 않는다.
기존 AI 음역 판정·추천 입력과 임계값은 이 변경으로 바뀌지 않는다. Android는 점수가
없으면 이전 점수 그래프를 대신 표시하지 않고 미측정 안내를 보여준다.

개선 방향은 서로 다른 휴대폰과 화자에서 같은 음을 진성·가성·불안정 저음으로 반복 녹음해
점수 분포와 오탐을 검증하고 가중치·구간을 조정하는 것이다. 이 검증 전에는 임의의
점수 임계값으로 테시투라를 확정하거나 추천 키를 바꾸지 않는다.

백엔드의 `V39__store_on_device_voice_quality.sql`을 먼저 배포한 뒤 새 앱을 배포한다.
AI 서버는 이 부가 필드를 받지 않으므로 변경·재배포가 필요하지 않다.

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
