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
`resultVersion`을 함께 반환한다. 클라이언트는 `saveAllowed=true`이고 세션 상태가
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
