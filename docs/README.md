# docs

파트에 속하지 않는 프로젝트 공통 문서를 둔다. 파트별 문서는 각 파트 폴더의 `docs/` 에 둔다.

## 레포 문서

- [Jira·Git 운영 규칙](../JIRA_GIT_RULES.md) — 이슈 작성, 브랜치·커밋·MR 규칙
- [CLAUDE.md](../CLAUDE.md) — AI 에이전트용 요약 규칙

## 파트 문서

- [팀원용 로컬 실행 안내](operations/LOCAL_DEVELOPMENT.md) — Android·BE 준비, 실기기 연결, 설정·비밀정보 구분
- [서버 초기 설정 기록](operations/SERVER_BOOTSTRAP.md) — Docker·Nginx·HTTPS 적용 및 검증 결과
- [Android Jenkins CI](operations/JENKINS_ANDROID_CI.md) — develop 대상 MR 테스트·Debug APK·소스 SHA 검증
- [GitLab 프로젝트 전용 Runner](operations/GITLAB_RUNNER.md) — 보호된 develop 경량 검증, 보안 경계와 운영·복구 절차
- [배포 준비 체크리스트](operations/DEPLOYMENT_READINESS.md) — API 계약·서버 배포·APK 배포·QA
- [SSAFY 서버 + 개인 S3 운영 계약](operations/S3_VOICE_STORAGE.md) — 계정 분리, 비공개 버킷·IAM·서버 인증, 팀원 업로드와 DB 적재 구분
- [담당 팀원용 S3 카탈로그 업로드 안내](operations/S3_CATALOG_UPLOAD_GUIDE.md) — 개인 업로더 프로필 설정, dry-run·업로드·완료 보고 절차
- [곡 임베딩 S3 적재·활성화](operations/EMBEDDING_CATALOG.md) — ECAPA·Singer-BYOL, Flyway V7, ID 매핑·조건부 업로드·롤백·AI 캐시
- [BE 운영 실행 계약](operations/BACKEND_RUNTIME_CONTRACT.md) — DB·AI·S3 환경변수와 배포 조건
- [MySQL 운영 구성](operations/MYSQL_RUNTIME.md) — SSAFY 서버의 DB 네트워크·영속성
- [서버 릴리스 Runbook](operations/SERVER_RELEASE_RUNBOOK.md) — 후보 검증·전환·복구, S3 사전 조건
- [Android Release 준비](operations/ANDROID_RELEASE.md) — APK 서명·게시·실기기 검증; AWS 키는 앱에 포함하지 않음
- [키별 반주 운영 전환](KEY_VARIANT_ROLLOUT.md) — S3·DB 등록 결과, 검증·롤백 기준
- [Android 리디자인 가이드](FRONTEND_REDESIGN_GUIDE.md) — 디자인 토큰·컴포넌트·화면 구조 기준
- [독립 분석 계획](INDEPENDENT_ANALYSIS_PLAN.md) — 음역·음색 독립 처리 계획. 구현 완료 여부 확인 전까지 보존

- Android: [frontend/android/README.md](../frontend/android/README.md), [frontend/android/docs/](../frontend/android/docs/)
- AI 엔진·데이터 계약: [VocalScope](../vocal-scope/README.md), [배치 인수·운영 적재](../vocal-scope/docs/BATCH_HANDOFF.md), [데이터 계획](../vocal-scope/docs/MICON_DATA_PLAN.md). 로컬 분석 저장과 개인 S3 운영 인수를 구분한다.

## 프로젝트 관리

- Jira: https://ssafy.atlassian.net/jira/software/c/projects/S15P21B110
- Figma (참고용 와이어프레임): https://www.figma.com/design/gD9LvKcDIDlSXf5Kds76LJ
