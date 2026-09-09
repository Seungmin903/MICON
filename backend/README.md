# backend

앱 백엔드(Spring / MySQL 8 / S3). API, 인증·인가, 사용자·찜·연습 기록·음성 프로필 저장을 담당한다.

- Android 앱이 기대하는 응답 봉투는 `frontend/android/app/src/main/java/com/micon/app/data/backend/dto/ApiResponse.kt`
- 사용자 쪽 스키마 초안은 AI 파트 `vocal-scope/docs/micon_schema.sql` (사용자 쪽 8 테이블, 제안 상태)

Jira 파트 코드는 `BE`.

## 운영 배포 계약

[환경변수 예제](.env.example)와 [BE 운영 실행 계약](../docs/operations/BACKEND_RUNTIME_CONTRACT.md)을 참고한다. BE·MySQL은 SSAFY 서버, 비공개 S3는 개인 AWS 계정을 사용한다. [S3 운영 계약](../docs/operations/S3_VOICE_STORAGE.md)에 실제 버킷·리전·전용 IAM·서버 키 파일·배포 인계 값을 정리했다. 2026-09-09 서버 CLI 업로드/GET/삭제 검증은 완료했지만 BE 컨테이너 마운트·실제 Java 런타임과 앱/DB/AI 통합 검증은 미완료다. 예제에 실제 비밀값을 기입하지 않는다.

## 로컬 실행 환경 변수

- `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`: MySQL 연결 정보
- `JWT_SECRET`: Access Token 서명 키. UTF-8 기준 32바이트 이상이어야 한다.
- `AI_*`: 분석 서버 연결·인증·분석 URL 만료 설정. AI 서비스 배포/접근 가능 여부는 별도로 확인한다.
- `VOICE_STORAGE_*`, `VOICE_RECORDING_MAX_SIZE`: 버킷·리전·녹음 경로·재생 URL 만료·파일/요청 크기 제한.
- `AWS_SHARED_CREDENTIALS_FILE`, `AWS_PROFILE`, `AWS_EC2_METADATA_DISABLED`: AWS SDK 전용 인증 설정. 운영은 개인 계정 BE 전용 프로필을 사용하고 SSAFY 인스턴스 역할로 fallback하지 않는다.

Spring Boot는 `.env`를 자동으로 읽지 않는다. 모든 값이 빈 추적 예제는 실행 파일이 아니므로 별도 실행기에 필요한 값을 명시한다. 로컬 개발은 승인된 개발용 버킷·prefix·자격 증명을 사용하고 운영 녹음 데이터에 접근하지 않는다. Flyway가 기동 중 DB를 변경할 수 있으므로 SQL 보류/담당자 승인 조건을 먼저 확인한다.

현재 `S3VoiceStorage`에 업로드·presigned GET URL·삭제 코드가 있다. `/recordings/{id}/content` 응답은 재생 URL을 담은 JSON이다. 버킷은 공개하지 않으며 앱/AI에 AWS 키를 전달하지 않는다. 팀원의 `catalog/` 파일 업로드는 녹음 API 또는 DB 초기 데이터 import를 대신하지 않는다.
