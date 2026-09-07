# MICON (S15P21B110)

음역·음색을 분석해 부를 수 있는 노래와 추천 키를 제공하고, 실시간 음정 피드백으로 연습을 돕는 **Android 앱**.

**운영 규칙 전문은 `JIRA_GIT_RULES.md`** (파일명에 버전 없음 — 버전은 문서 안에서만 관리한다). 아래는 매번 지켜야 하는 것만 추린 요약이며, 어긋나면 전문이 우선한다. 이슈를 만들거나 에픽을 다룰 일이 생기면 그 문서를 먼저 읽는다.

## 최초 1회 (클론 후)

```
git lfs install                      # *.sqlite (개발용 샘플 DB) 가 LFS 로 관리된다
git config --unset core.hooksPath    # 예전에 .githooks 로 바꿔둔 사람만. 저장소에 훅은 없다 — 규칙은 MR 리뷰로 지킨다
```

## 커밋

```
[S15P21B110-12] feat: 30초 측정 카운트다운 UI
```

- 커밋의 이슈 키는 **선택** (squash 후 develop엔 MR 단위만 남아 커밋 키는 Jira 연결에 기여하지 않음). 쓸 때는 전체 키 `[S15P21B110-12] feat: …`.
- **MR 제목에는 전체 키 필수** — 이슈가 없으면 MR 설명(파트·에픽·SP·완료 기준)으로 자동 생성되어 키가 붙는다(규칙 7번 A0, 관리자 설정 전까지는 수동).
- 타입: `feat` `fix` `refactor` `hotfix` `chore` `docs` `test`. `wip`는 쓰지 않는다.
- 기능 단위로 쪼갠다.

## 브랜치와 MR

- **`main`, `develop`에 직접 push 금지.** "develop에 올려줘", "머지해줘" 는 *작업 브랜치 push → MR* 을 의미한다. 사람이든 에이전트든 예외 없다.
- 브랜치: `타입/간단한설명` (camelCase, 이슈 키 없음, **파트 코드 없음**) — 예 `feat/micPermission`, `fix/tokenRefresh`, `docs/timbreExperiment`. 파트는 이슈 제목·MR 제목이 담당한다.
- 폴더: `frontend/android/`(FE) · `backend/`(BE) · `ai/`(AI) · `docs/`(공통 문서). 파트 코드와 폴더가 1:1이다.
- **항상 최신 develop에서 딴다**: `git fetch origin && git switch -c feat/짧은이름 origin/develop`. 다른 브랜치나 옛 커밋에서 따면 develop과 히스토리가 끊겨 MR이 불가능하다.
- GitLab 이슈의 "브랜치 생성" 버튼을 쓰지 않는다. 한글 이슈 제목이 통째로 붙은 이름이 만들어진다.
- 분기한 브랜치가 곧 MR 타겟이다. develop에서 따서 develop으로. 머지는 squash.
- MR 제목: `[S15P21B110-12] (FE) 측정 화면 추가`. 이슈가 없으면 키 없이 올린다 — 자동화(A0)가 이슈를 만들어 키를 붙인다.
- MR 설명은 `.gitlab/merge_request_templates/Default.md` 형식으로 채운다. **파트·에픽·SP·완료 기준(체크된 항목)은 필수** — 비어 있으면 이슈가 자동 생성되지 않는다.

## Jira

- 이슈 제목: `[파트코드] 제목` — `FE` `BE` `AI` `INFRA` `PLAN` `PM` `QA` `DESIGN`. 표에 없는 코드를 만들지 않는다. (`AOS`는 V3에서 `FE`로 되돌림 — 기존 `[AOS]` 이슈는 소급 수정하지 않는다.)
- 에픽/parent 없는 이슈는 만들지 않는다. 개발 단계 에픽 키가 전문의 표에 기입되기 전까지 그 소관 이슈는 생성하지 않는다.
- Story Points = 예상 소요 **시간**(정수), 최대 3.
- **이슈·에픽을 임의로 만들지 않는다.** 에픽 생성은 PM 승인 필수. 여러 건을 만들 땐 목록을 먼저 보여주고 확인받는다.
- 한글 필드 값은 유니코드 이스케이프 없이 그대로 전송한다.
