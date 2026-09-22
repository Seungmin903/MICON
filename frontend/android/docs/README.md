# docs — Android 앱 문서

- `FIGMA_PRACTICE_SCREENS.md` — 노래 연습·연습 분석·곡 적합도 화면 요소 설명 + 요소별 데이터 대응(✅/🟡/❌).
- `PRACTICE_LYRICS_SECTIONS.md` — 연습 화면 가사 시트(LRC 원본·글자 채움), 절/간주/하이라이트 계산, 점프·간주 건너뛰기 설계 노트.
- `PIANO_SAMPLES.md` — 피아노 샘플 자산과 재생 규칙.
- `VOICE_ONBOARDING.md` — 음성 온보딩 화면과 상태 흐름.
- `용어집.md` — 음역·안정도·키 등 프로젝트 용어.
- `screenshots/` — 과거 스켈레톤 화면 캡처. 현재 UI 기준 여부를 확인하기 전까지 참고 자료로만 사용한다.

앱 실행·빌드 방법은 상위 [Android README](../README.md), 팀 공통 로컬 환경은 [LOCAL_DEVELOPMENT](../../../docs/operations/LOCAL_DEVELOPMENT.md)를 따른다. 곡·반주·연습 기록은 BE API가 기준이며 `micon_catalog.sqlite`를 앱 자산으로 사용하지 않는다.

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

테스트 픽스처는 `app/src/test/resources/fixtures/`에 둔다. 운영 데이터와 URL은 저장소에 복사하지 않고 BE API 계약으로 검증한다.
