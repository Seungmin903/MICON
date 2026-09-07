# MICON (S15P21B110)

음역·음색을 분석해 부를 수 있는 노래와 추천 키를 제공하고, 실시간 음정 피드백으로 연습을 돕는 **Android 앱**.

**운영 규칙 전문은 `JIRA_GIT_RULES_V1.md` (현재 V1.3).** 아래는 매번 지켜야 하는 것만 추린 것이다. 이슈를 만들거나 에픽을 다룰 일이 생기면 그 문서를 먼저 읽는다.

## 최초 1회 (클론 후)

```
git lfs install                      # *.sqlite (개발용 샘플 DB) 가 LFS 로 관리된다
git config core.hooksPath .githooks
```

안 하면 아래 규칙이 커밋 시점에 안 잡힌다.

## 커밋

```
[S15P21B110-12] feat: 30초 측정 카운트다운 UI
```

- 이슈 키 없는 커밋 금지. 브랜치명에 키가 없으므로 커밋과 MR 제목이 Jira 연결의 유일한 통로다.
- 타입: `feat` `fix` `refactor` `hotfix` `chore` `docs` `test`. `wip`는 쓰지 않는다.
- 기능 단위로 쪼갠다.

## 브랜치와 MR

- **`main`, `develop`에 직접 push 금지.** "develop에 올려줘", "머지해줘" 는 *작업 브랜치 push → MR* 을 의미한다. 사람이든 에이전트든 예외 없다.
- 브랜치: `타입/간단한설명` (camelCase, 이슈 키 없음) — 예 `feat/voiceRangeTest`
- 폴더: `frontend/android/`(FE) · `backend/`(BE) · `ai/`(AI) · `docs/`(공통 문서). 파트 코드와 폴더가 1:1 이다.
- GitLab 이슈의 "브랜치 생성" 버튼을 쓰지 않는다. 한글 이슈 제목이 통째로 붙은 이름이 만들어진다.
- 분기한 브랜치가 곧 MR 타겟이다. develop에서 따서 develop으로.
- MR 제목: `[S15P21B110-12] (FE) 측정 화면 추가`

## Jira

- 이슈 제목: `[파트코드] 제목` — `FE` `BE` `AI` `INFRA` `PLAN` `PM` `QA` `DESIGN`. 표에 없는 코드를 만들지 않는다.
- Story Points = 예상 소요 **시간**(정수), 최대 3.
- **이슈·에픽을 임의로 만들지 않는다.** 에픽 생성은 PM 승인 필수. 여러 건을 만들 땐 목록을 먼저 보여주고 확인받는다.
- 한글 필드 값은 유니코드 이스케이프 없이 그대로 전송한다.
