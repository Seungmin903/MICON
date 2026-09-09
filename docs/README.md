# docs

파트에 속하지 않는 프로젝트 공통 문서를 둔다. 파트별 문서는 각 파트 폴더의 `docs/` 에 둔다.

## 레포 문서

- [Jira·Git 운영 규칙](../JIRA_GIT_RULES.md) — 이슈 작성, 브랜치·커밋·MR 규칙
- [CLAUDE.md](../CLAUDE.md) — AI 에이전트용 요약 규칙

## 파트 문서

- [팀원용 로컬 실행 안내](operations/LOCAL_DEVELOPMENT.md) — Android·BE 준비, 실기기 연결, 설정·비밀정보 구분
- [서버 초기 설정 기록](operations/SERVER_BOOTSTRAP.md) — Docker·Nginx·HTTPS 적용 및 검증 결과
- [Android Jenkins CI](operations/JENKINS_ANDROID_CI.md) — 수동 빌드 성공 근거와 자동화 잔여 작업
- [배포 준비 체크리스트](operations/DEPLOYMENT_READINESS.md) — API 계약·서버 배포·APK 배포·QA
- [SSAFY 서버 + 개인 S3 운영 계약](operations/S3_VOICE_STORAGE.md) — 계정 분리, 비공개 버킷·IAM·서버 인증, 팀원 업로드와 DB 적재 구분
- [담당 팀원용 S3 카탈로그 업로드 안내](operations/S3_CATALOG_UPLOAD_GUIDE.md) — 개인 업로더 프로필 설정, dry-run·업로드·완료 보고 절차
- [BE 운영 실행 계약](operations/BACKEND_RUNTIME_CONTRACT.md) — DB·AI·S3 환경변수와 배포 조건
- [MySQL 운영 구성](operations/MYSQL_RUNTIME.md) — SSAFY 서버의 DB 네트워크·영속성
- [서버 릴리스 Runbook](operations/SERVER_RELEASE_RUNBOOK.md) — 후보 검증·전환·복구, S3 사전 조건
- [Android Release 준비](operations/ANDROID_RELEASE.md) — APK 서명·게시·실기기 검증; AWS 키는 앱에 포함하지 않음

- Android: [frontend/android/README.md](../frontend/android/README.md), [frontend/android/docs/](../frontend/android/docs/)
- AI 엔진·데이터 계약: [VocalScope](../vocal-scope/README.md), [배치 인수·운영 적재](../vocal-scope/docs/BATCH_HANDOFF.md), [데이터 계획](../vocal-scope/docs/MICON_DATA_PLAN.md). 로컬 분석 저장과 개인 S3 운영 인수를 구분한다.

## 프로젝트 관리

- Jira: https://ssafy.atlassian.net/jira/software/c/projects/S15P21B110
- Figma (참고용 와이어프레임): https://www.figma.com/design/gD9LvKcDIDlSXf5Kds76LJ
