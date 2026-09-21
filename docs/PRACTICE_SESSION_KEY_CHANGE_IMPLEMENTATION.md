# 연습 세션 키 선택·변경 기록

## 동작

- Android는 실제 연습을 시작할 때 곡 상세에서 선택한 키로 `POST /practices/session`을 호출한다.
- 연습 중 키를 바꾸면 반주와 채점 기준을 즉시 바꾸고 `PATCH /practices/{practiceId}/key`로 현재 키를 저장한다.
- 서버는 키 값이 실제로 달라질 때만 `key_change_count`를 하나 늘린다.
- 연습 완료 전 마지막 키를 다시 동기화한 뒤 `POST /practices/{practiceId}/complete`를 호출한다.

## API

```http
PATCH /api/v1/practices/{practiceId}/key
Content-Type: application/json

{ "selectedKey": -2 }
```

- 허용 키: `-3..3` (Android 실시간 피치 변환 범위와 동일)
- 대상: 본인의 `READY`, `RECORDING`, `IN_PROGRESS`, `PAUSED` 세션
- 완료·취소 세션은 `409 INVALID_PRACTICE_STATE`

## DB 전제 조건

운영 DB에는 `V30__add_practice_session_key_change_info.sql`이 적용되어 있어야 한다.

- `initial_key`: 실제 연습 시작 키
- `selected_key`: 현재 키, 완료 뒤 최종 키
- `key_change_count`: 실제 키가 바뀐 횟수

V29 DB에는 위 컬럼이 없으므로 이 기능을 배포하기 전에 V30 적용 여부를 확인한다. Flyway `repair`로 체크섬을 바꾸지 않는다.

## 반주 정책

반주는 원키 파일 하나를 Android 재생기에서 실시간으로 피치 변환한다. 따라서 세션 생성·키 변경·완료 API는 비원키 `INSTRUMENTAL_KEY` 파일의 존재를 요구하지 않는다. 단, 원키 반주·가사·목표 음정이 준비되어야 연습 세션을 시작할 수 있다.

## 검증

- 시작 키 `-2`로 생성하면 `initial_key=-2`, `selected_key=-2`, `key_change_count=0`
- `-2 → 0 → 2` 변경 뒤 최종 키 `2`, 변경 횟수 `2`
- 같은 키 재전송은 변경 횟수를 늘리지 않음
- 완료/취소 세션 및 다른 사용자의 세션은 키 변경 거절
- 원키 반주만 있는 곡에서도 비원키 연습 기록 저장
