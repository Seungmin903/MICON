# docs — Android 앱 문서

- `ANDROID_START_GUIDE.md` — 앱 작업을 시작할 때 무엇을 참고하는지 정리한 가이드(진입점, 2026-09-03 작성. 경로는 당시 로컬 폴더 기준).
- `FIGMA_PRACTICE_SCREENS.md` — 노래 연습·연습 분석·곡 적합도 화면 요소 설명 + 요소별 데이터 대응(✅/🟡/❌).
- `PRACTICE_LYRICS_SECTIONS.md` — 연습 화면 가사 시트(LRC 원본·글자 채움), 절/간주/하이라이트 계산, 점프·간주 건너뛰기 설계 노트.
- `용어집.md` — 음역·안정도·키 등 프로젝트 용어.
- `screenshots/` — 스켈레톤 화면 캡처(에뮬레이터).

## 엔진·데이터 계약 문서 (AI 파트 소유)

원본은 `feat/vocalScope` 브랜치의 `vocal-scope/` 이다. 여기에 복사본을 두지 않는다.

| 문서 | 용도 |
|---|---|
| `vocal-scope/docs/DATA_INVENTORY.md` | 엔진이 내보내는 모든 필드의 이름·타입·단위, 채점 결과 `comparison{}` |
| `vocal-scope/docs/MICON_DATA_PLAN.md` | 화면 ↔ 필요 데이터 ↔ 출처 대응표, 팀 결정, 미결 목록 |
| `vocal-scope/docs/micon_schema.sql` | MySQL 8 스키마 13테이블 (곡 5 + 사용자 8) |
| `vocal-scope/PIPELINE.md` §5, `vocal-scope/model-server/app.py` | 엔진 REST API 원문 → `data/engine/EngineApi.kt` |

## Kotlin 포팅 원본 대조표

| 웹/엔진 원본 (`vocal-scope/`) | Kotlin (`app/src/main/java/com/micon/app/`) |
|---|---|
| `public/pitch-worklet.js`, `tests/pitch-worklet.test.mjs` | `scoring/YinPitch.kt`, `test/.../scoring/YinPitchTest.kt` |
| `app/live-scoring.tsx` | `scoring/LiveScorer.kt`, `scoring/ScoringConstants.kt`, `scoring/NoteNormalizer.kt` |
| `app/scoring-ui.ts` | `scoring/ScoringUi.kt` |
| `model-server/compare_engine.py` 상단 상수 | 채점 상수 대조. 채점 계산 자체는 엔진이 한다 |
| `model-server/app.py` 라우트 | `data/engine/EngineApi.kt` |

테스트 픽스처는 `app/src/test/resources/fixtures/` (엔진 `workspace/<job>/job.json` 원문).
개발용 샘플 DB는 `app/src/main/assets/micon_catalog.sqlite` (Git LFS). 앱 백엔드 API 가 생기면 삭제하고 `SqliteSongRepository` 를 Retrofit 구현으로 교체한다.
