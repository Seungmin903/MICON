# MICON (S15P21B110)

음역·음색을 분석해 부를 수 있는 노래와 추천 키를 제공하고, 실시간 음정 피드백으로 연습을 돕는 **Android 앱**.
FE(Android), BE, AI, 문서를 하나의 저장소에서 관리하는 모노레포다.

## 폴더 구조

```text
.
├── frontend/
│   └── android/        # MICON Android 앱 (Kotlin · Jetpack Compose)
├── backend/            # 앱 백엔드 (Spring / MySQL 8, 예정)
├── ai/                 # 음성 분석 엔진·음원 처리·추천 (vocal-scope 등, 파트 브랜치에서 합류 예정)
├── docs/               # 프로젝트 공통 문서
├── .gitlab/            # MR 템플릿
├── .githooks/          # 커밋 메시지 규칙 훅
├── CLAUDE.md           # AI 에이전트용 요약 규칙
└── JIRA_GIT_RULES_V1.md  # Jira·Git 운영 규칙 전문
```

## 최초 1회 (클론 후)

```bash
git lfs install                          # 33 MB 개발용 샘플 DB(*.sqlite)가 LFS 로 관리된다
git clone https://lab.ssafy.com/s15-ai-speech-sub1/S15P21B110.git
cd S15P21B110
git config core.hooksPath .githooks      # 커밋 메시지 규칙 검사
```

Android 앱 빌드 절차는 [frontend/android/README.md](frontend/android/README.md).

## Git 규칙 요약

전문은 [JIRA_GIT_RULES_V1.md](JIRA_GIT_RULES_V1.md) 9번. 아래는 매번 지키는 것만 추렸다.

- **`main`, `develop` 직접 push 금지.** 작업 브랜치 → MR 로만 반영한다.
- 브랜치: `타입/간단한설명` (camelCase, 이슈 키 없음) — 예 `feat/voiceRangeTest`
- 커밋: `[이슈키] 타입: 내용` — 예 `[S15P21B110-12] feat: 30초 측정 카운트다운 UI`
- MR 제목: `[이슈키] (파트) 요약` — 예 `[S15P21B110-12] (FE) 측정 화면 추가`. 타겟은 분기한 브랜치(develop), squash merge, 셀프 머지 금지.
- 타입: `feat` `fix` `refactor` `hotfix` `chore` `docs` `test`
- 파트 코드: `FE` `BE` `AI` `INFRA` `PLAN` `PM` `QA` `DESIGN`

```text
main  ◀── MR ── develop  ◀── MR ── feat/… fix/… docs/…
```
