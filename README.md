# MICON (S15P21B110)

음역·음색을 분석해 부를 수 있는 노래와 추천 키를 제공하고, 실시간 음정 피드백으로 연습을 돕는 **Android 앱**.
FE(Android), BE, AI, 문서를 하나의 저장소에서 관리하는 모노레포다.

## 폴더 구조

```text
.
├── frontend/
│   └── android/        # MICON Android 앱 (Kotlin · Jetpack Compose)
├── backend/            # 앱 백엔드 (Spring / MySQL 8 / S3 연동)
├── ai/                 # AI 파트 안내·운영 인수 계약
├── vocal-scope/        # 로컬 음성 분석 엔진·음원 처리·배치 도구
├── docs/               # 프로젝트 공통 문서
├── .gitlab/            # MR 템플릿
├── CLAUDE.md           # AI 에이전트용 요약 규칙
└── JIRA_GIT_RULES.md   # Jira·Git 운영 규칙 전문 (버전은 문서 안에서 관리)
```

## 최초 1회 (클론 후)

```bash
git lfs install                          # 33 MB 개발용 샘플 DB(*.sqlite)가 LFS 로 관리된다
git clone https://lab.ssafy.com/s15-ai-speech-sub1/S15P21B110.git
cd S15P21B110
git config --unset core.hooksPath        # 예전에 .githooks 로 바꿔둔 사람만. 저장소에 훅은 없다
```

Android 앱 빌드 절차는 [frontend/android/README.md](frontend/android/README.md).
팀원별 준비물·로컬 실행·실기기 연결은 [로컬 실행 안내](docs/operations/LOCAL_DEVELOPMENT.md)를 먼저 확인한다.
현재 운영 환경의 적용 결과와 미완료 범위는 [서버 설정 기록](docs/operations/SERVER_BOOTSTRAP.md), [배포 준비 체크리스트](docs/operations/DEPLOYMENT_READINESS.md)에 구분해 기록한다.

운영 구조는 **SSAFY 서버의 BE·MySQL + 개인 AWS 계정의 비공개 S3**다. 버킷·인증·권한·팀원 로컬 데이터 업로드 안내는 [S3 운영 계약](docs/operations/S3_VOICE_STORAGE.md), 전체 환경변수는 [BE 실행 계약](docs/operations/BACKEND_RUNTIME_CONTRACT.md)을 따른다. 개인 S3의 실제 생성·연결은 아직 미완료이며, 파일 업로드와 DB 적재는 별도 절차다.

## Git 규칙 요약

전문은 [JIRA_GIT_RULES.md](JIRA_GIT_RULES.md) 8번. 아래는 매번 지키는 것만 추렸다.

- **`main`, `develop` 직접 push 금지.** 작업 브랜치 → MR 로만 반영한다.
- 브랜치: `타입/간단한설명` (camelCase, 이슈 키 없음, 파트 코드 없음) — 예 `feat/voiceRangeTest`. 항상 최신 develop에서 딴다.
- 커밋: `타입: 내용`, 이슈 키는 선택 — 예 `feat: 30초 측정 카운트다운 UI`, `[S15P21B110-12] feat: …`
- MR 제목: `[이슈키] (파트) 요약` — 예 `[S15P21B110-12] (FE) 측정 화면 추가`. 이슈가 없으면 키 없이 올리고 MR 템플릿의 파트·에픽·SP·완료 기준을 채운다. 타겟은 분기한 브랜치(develop), squash merge, 셀프 머지 금지.
- 타입: `feat` `fix` `refactor` `hotfix` `chore` `docs` `test`
- 파트 코드: `FE` `BE` `AI` `INFRA` `PLAN` `PM` `QA` `DESIGN`

```text
main  ◀── MR ── develop  ◀── MR ── feat/… fix/… docs/…
```
