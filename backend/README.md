# backend

앱 백엔드(Spring / MySQL 8 예정). API, 인증·인가, 사용자·찜·연습 기록·음성 프로필 저장을 담당한다.

- Android 앱이 기대하는 응답 봉투는 `frontend/android/app/src/main/java/com/micon/app/data/backend/dto/ApiResponse.kt`
- 사용자 쪽 스키마 초안은 AI 파트 `vocal-scope/docs/micon_schema.sql` (사용자 쪽 8 테이블, 제안 상태)

Jira 파트 코드는 `BE`.

## 운영 배포 계약

[환경변수 예제](.env.example)와 [BE 운영 실행 계약 초안](../docs/operations/BACKEND_RUNTIME_CONTRACT.md)을 참고한다. 현재 코드 사실과 합의가 필요한 운영 제안을 구분했으며, 실제 서버/SQL 설정을 적용한 문서가 아니다. 예제에 실제 비밀값을 기입하지 않는다.

## 로컬 실행 환경 변수

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: MySQL 연결 정보
- `JWT_SECRET`: Access Token 서명 키. UTF-8 기준 32바이트 이상이어야 한다.
